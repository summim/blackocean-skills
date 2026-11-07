"""효과음 공용 도구: ogg 읽기/쓰기, 음량 측정(BS.1770 K-가중), 마스터링 체인.

측정 기준: "최대 순간 음량" (K-가중 400ms 창의 최댓값, LUFS 단위).
짧은 효과음은 통합 음량(integrated)이 게이트 때문에 의미가 없어서, 게임 효과음 업계에서 쓰는
momentary-max 를 기준으로 맞춘다.
"""
import os
import subprocess
import tempfile

import numpy as np
from scipy import signal
from scipy.io import wavfile

SR = 48000


# ---------------------------------------------------------------- 입출력
def load(path, sr=SR):
    """아무 형식 → 모노 float64 (ffmpeg 로 다운믹스·리샘플)."""
    out = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 'f32le', '-ac', '1', '-ar', str(sr), '-'],
                         capture_output=True, check=True).stdout
    return np.frombuffer(out, dtype=np.float32).astype(np.float64)


def probe(path):
    r = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'stream=channels,sample_rate', '-of', 'csv=p=0', path],
                       capture_output=True, text=True).stdout.strip()
    return r


def save_ogg(path, x, sr=SR, q=4):
    """모노 ogg vorbis. q4 ≈ 112~128kbps (모노 효과음엔 충분)."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    x = np.clip(x, -1, 1)
    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f:
        tmp = f.name
    try:
        wavfile.write(tmp, sr, (x * 32767).astype(np.int16))
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', tmp, '-ac', '1', '-c:a', 'libvorbis', '-q:a', str(q),
                        '-map_metadata', '-1', path], check=True)
    finally:
        os.unlink(tmp)


# ---------------------------------------------------------------- 측정
def kweight(x, sr=SR):
    """BS.1770 K-가중 (48kHz 계수)."""
    b1 = [1.53512485958697, -2.69169618940638, 1.19839281085285]
    a1 = [1, -1.69065929318241, 0.73248077421585]
    b2 = [1.0, -2.0, 1.0]
    a2 = [1, -1.99004745483398, 0.99007225036621]
    return signal.lfilter(b2, a2, signal.lfilter(b1, a1, x))


def momentary_max(x, sr=SR, win=0.4):
    k = kweight(x, sr)
    n = int(win * sr)
    if len(k) < n:
        k = np.pad(k, (0, n - len(k)))
    c = np.cumsum(np.concatenate([[0.0], k * k]))
    p = (c[n:] - c[:-n]) / n
    return -0.691 + 10 * np.log10(p.max() + 1e-12)


def band_ratio(x, lo, hi, sr=SR):
    f, P = signal.welch(x, sr, nperseg=2048)
    tot = P[f > 60].sum() + 1e-15
    return P[(f >= lo) & (f < hi)].sum() / tot


def centroid(x, sr=SR):
    f, P = signal.welch(x, sr, nperseg=2048)
    return (f * P).sum() / (P.sum() + 1e-15)


def true_peak_db(x):
    up = signal.resample_poly(x, 4, 1)
    return 20 * np.log10(np.abs(up).max() + 1e-12)


# ---------------------------------------------------------------- 처리
def _sos(kind, f, sr=SR, o=2):
    return signal.butter(o, f, kind, fs=sr, output='sos')


def highpass(x, f, o=2):
    return signal.sosfilt(_sos('highpass', f, o=o), x)


def lowpass(x, f, o=2):
    return signal.sosfilt(_sos('lowpass', f, o=o), x)


def shelf(x, f0, gain_db, high=True, sr=SR):
    """RBJ 셸빙 필터 (S=1)."""
    A = 10 ** (gain_db / 40)
    w = 2 * np.pi * f0 / sr
    cw, sw = np.cos(w), np.sin(w)
    al = sw / 2 * np.sqrt(2)
    s = 1 if high else -1
    b0 = A * ((A + 1) + s * (A - 1) * cw + 2 * np.sqrt(A) * al)
    b1 = -s * 2 * A * ((A - 1) + s * (A + 1) * cw)
    b2 = A * ((A + 1) + s * (A - 1) * cw - 2 * np.sqrt(A) * al)
    a0 = (A + 1) - s * (A - 1) * cw + 2 * np.sqrt(A) * al
    a1 = s * 2 * ((A - 1) - s * (A + 1) * cw)
    a2 = (A + 1) - s * (A - 1) * cw - 2 * np.sqrt(A) * al
    return signal.lfilter([b0 / a0, b1 / a0, b2 / a0], [1, a1 / a0, a2 / a0], x)


def peaking(x, f0, gain_db, q=1.0, sr=SR):
    A = 10 ** (gain_db / 40)
    w = 2 * np.pi * f0 / sr
    al = np.sin(w) / (2 * q)
    b = [1 + al * A, -2 * np.cos(w), 1 - al * A]
    a = [1 + al / A, -2 * np.cos(w), 1 - al / A]
    return signal.lfilter(np.array(b) / a[0], np.array(a) / a[0], x)


def octave_db(x, center, sr=SR):
    """옥타브 대역 에너지 (전체 대비 dB)."""
    f, P = signal.welch(x, sr, nperseg=4096)
    m = (f >= center / np.sqrt(2)) & (f < center * np.sqrt(2))
    return 10 * np.log10(P[m].sum() / (P.sum() + 1e-15) + 1e-12)


# 옥타브 대역 상한 (전체 대비 dB). 넘으면 그만큼 깎는다. 얼음·바람처럼 원래 밝은 소리도 이 선까지만.
HF_LIMITS = [(4000, -6.0, 3600, 0.8, 6.0), (8000, -12.0, 7000, 0.9, 9.0)]
AIR_LIMIT, AIR_SHELF, AIR_MAX = -22.0, 11000, 12.0


def tame_highs(x):
    """방송에서 귀를 찌르는 3~8kHz 와 10kHz 이상의 지글거림을 대역 상한까지 눌러 준다."""
    x = shelf(x, 12000, -1.5)  # 기본: 아주 살짝
    for _ in range(2):
        for center, limit, f0, q, cap in HF_LIMITS:
            ex = octave_db(x, center) - limit
            if ex > 0.5:
                x = peaking(x, f0, -min(cap, ex), q=q)
        ex = octave_db(x, 16000) - AIR_LIMIT
        if ex > 0.5:
            x = shelf(x, AIR_SHELF, -min(AIR_MAX, ex))
    return x


def trim(x, thr_db=-60, pre=0.003, tail_fade=0.04, loop=False):
    """앞 무음 자르기(아주 짧은 여유 남김) + 뒤 무음 자르기 + 꼬리 페이드. 반복용은 건드리지 않음."""
    if loop:
        return x
    a = np.abs(x)
    m = a.max() + 1e-12
    idx = np.where(a > m * 10 ** (thr_db / 20))[0]
    if len(idx) == 0:
        return x
    s = max(0, idx[0] - int(pre * SR))
    e = min(len(x), idx[-1] + int(0.01 * SR))
    x = x[s:e].copy()
    # 시작 클릭 방지 1ms
    n0 = min(len(x), int(0.001 * SR))
    x[:n0] *= np.linspace(0, 1, n0)
    nf = min(len(x), int(tail_fade * SR))
    x[-nf:] *= np.cos(np.linspace(0, np.pi / 2, nf)) ** 2
    return x


def limiter(x, ceiling_db=-1.0, attack=0.0015, release=0.08):
    """미리보기(lookahead) 피크 리미터 + 마지막 tanh 안전장치. 4배 오버샘플 피크 기준."""
    c = 10 ** (ceiling_db / 20)
    up = np.abs(signal.resample_poly(x, 4, 1))
    pk = up.reshape(-1, 4).max(axis=1)[:len(x)] if len(up) >= 4 * len(x) else np.abs(x)
    if len(pk) < len(x):
        pk = np.pad(pk, (0, len(x) - len(pk)))
    need = np.minimum(1.0, c / (pk + 1e-12))
    la = max(1, int(attack * SR))
    # lookahead: 앞으로 la 샘플 안의 최소 게인
    from scipy.ndimage import minimum_filter1d
    g = minimum_filter1d(need, size=2 * la + 1, origin=0)
    # release 평활 (위로 올라갈 때만 느리게)
    rc = np.exp(-1.0 / (release * SR))
    out = np.empty_like(g)
    cur = 1.0
    for i in range(len(g)):
        t = g[i]
        cur = t if t < cur else t + (cur - t) * rc
        out[i] = cur
    # 공격 평활
    k = np.hanning(2 * la + 1)
    k /= k.sum()
    out = np.minimum(out, np.convolve(out, k, mode='same'))
    y = x * out
    return np.tanh(y / c * 0.98) * c / np.tanh(0.98) if np.abs(y).max() > c else y


def master(x, target, loop=False, hp=35, tame=True, ceiling=-1.0):
    """모노 신호 → 표준 체인: 저역 정리 → 고역 다듬기 → 무음 정리 → 목표 음량 → 리미터."""
    x = np.asarray(x, dtype=np.float64)
    x = x - x.mean()
    x = highpass(x, hp, o=2)
    if tame:
        x = tame_highs(x)
    x = trim(x, loop=loop)
    for _ in range(3):  # 리미터가 줄인 만큼 다시 맞춤
        m = momentary_max(x)
        x = x * 10 ** ((target - m) / 20)
        x = limiter(x, ceiling)
        if abs(momentary_max(x) - target) < 0.3:
            break
    return x


def pitch_shift(x, ratio):
    """재생 속도 변환 방식(길이도 같이 바뀜) — 변형본 만들 때."""
    from fractions import Fraction
    fr = Fraction(ratio).limit_denominator(200)
    return signal.resample_poly(x, fr.denominator, fr.numerator)
