"""2.3 타격감 소리: 지속 피해 틱 · 퍼지는 적중 · 무거운 공격 직전의 바람 · 궁극기 직전 차오름 · 파편 적중.

사용법: python3 make_v6_sounds.py [프로젝트 루트]   → art_source/sound_raw/ 에 원본을 씀
그 다음 python3 master_sounds.py 로 assets 에 마스터본 + sounds.json 을 만든다.

설계
- 틱 소리(fire/wind/water/ice tick): 0.2~0.3초, 부드러운 앞(3~5ms), 중역 위주. 0.5초마다 반복되므로 작고 짧게.
- 무거운 바람(common/heavy_whoosh): 약 0.3초 동안 점점 커지며 음정이 올라감(다가오는 느낌) → 끝 = 타격 순간.
  서버가 타격 6틱(0.3초) 전에 튼다 (SkillSounds.WHOOSH_TICKS).
- 궁극기 차오름(common/ult_swell): 0.8초 동안 차오르다 끝에서 뚝 → 바로 이어지는 궁극기 타격을 키움.
  서버가 마지막 타격 16틱 전에 튼다 (SkillSounds.SWELL_TICKS).
- 파편 적중(ice/shard_hit): 얼음 파편(가벼운 공격) 전용 짧고 밝은 '틱' — 얼음 창의 무거운 깨짐(ice/shatter)과 구분.
"""
import os
import sys

import numpy as np
from scipy import signal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from audiolib import SR, save_ogg  # noqa: E402

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
RAW = ROOT + '/art_source/sound_raw/'


def t_(sec): return np.arange(int(sec * SR)) / SR
def bp(x, lo, hi, o=2): return signal.sosfilt(signal.butter(o, [lo, hi], 'bandpass', fs=SR, output='sos'), x)
def lp(x, f, o=2): return signal.sosfilt(signal.butter(o, f, 'lowpass', fs=SR, output='sos'), x)
def hp(x, f, o=2): return signal.sosfilt(signal.butter(o, f, 'highpass', fs=SR, output='sos'), x)
def osc(freq): return 2 * np.pi * np.cumsum(freq) / SR
def env_ad(t, a, d): return np.where(t < a, t / max(a, 1e-6), np.exp(-(t - a) / d))


def sat(x, k=1.5):
    x = x / (np.abs(x).max() + 1e-9)
    return np.tanh(x * k) / np.tanh(k)


def sweep(x, f0, f1, q=1.5, blocks=120, curve=None):
    out = np.zeros_like(x); n = len(x); step = max(n // blocks, 1); zi = None
    for i in range(blocks):
        a, b = i * step, (n if i == blocks - 1 else (i + 1) * step)
        if a >= n: break
        q_ = i / (blocks - 1) if curve is None else curve[min(a, n - 1)]
        f = np.clip(f0 * (f1 / f0) ** q_, 30, SR / 2.3)
        sos = signal.butter(2, [f / (1 + 1 / q), min(f * (1 + 1 / q), SR / 2.1)], 'bandpass', fs=SR, output='sos')
        if zi is None: zi = np.zeros((sos.shape[0], 2))
        out[a:b], zi = signal.sosfilt(sos, x[a:b], zi=zi)
    return out


def boom(sec, f_hi, f_lo, k_pitch, k_amp, a=0.002):
    t = t_(sec)
    return np.sin(osc(f_lo + (f_hi - f_lo) * np.exp(-t * k_pitch))) * np.exp(-t * k_amp) * np.clip(t / a, 0, 1)


def place(dst, src, at):
    i = int(at * SR); L = min(len(src), len(dst) - i)
    if L > 0: dst[i:i + L] += src[:L]


def tinks(sec, r, n, lo, hi, at_fn):
    x = np.zeros(int(sec * SR))
    for k in range(n):
        f = r.uniform(lo, hi); L = int(r.uniform(0.04, 0.11) * SR); tt = np.arange(L) / SR
        y = sum(g * np.sin(2 * np.pi * f * m * tt + r.uniform(0, 6)) for m, g in [(1, 1), (1.51, 0.45), (2.13, 0.2)])
        y *= np.exp(-tt * r.uniform(35, 70)) * np.clip(tt / 0.0008, 0, 1)
        place(x, y * r.uniform(0.35, 1.0), at_fn(k, r))
    return x


def fade_out(x, sec):
    n = min(len(x), int(sec * SR))
    x[-n:] *= np.cos(np.linspace(0, np.pi / 2, n)) ** 2
    return x


def save(x, rel):
    x = np.asarray(x, dtype=np.float64)
    x = x / (np.abs(x).max() + 1e-9) * 0.89
    save_ogg(RAW + rel + '.ogg', x, q=6)
    print(f'{rel}.ogg  {len(x) / SR:.2f}s')


R = lambda s: np.random.default_rng(s)  # noqa: E731

# ---------------------------------------------------------------- 지속 피해 틱
# fire.tick: 불길이 핥고 지나가는 짧은 '화륵' + 타닥 두어 번
r = R(601); d = 0.28; t = t_(d)
x = lp(r.standard_normal(len(t)), 1600, 3) * env_ad(t, 0.006, 0.06) * 2.2
x += bp(r.standard_normal(len(t)), 600, 2600) * env_ad(t, 0.004, 0.03) * 1.0
x += boom(d, 190, 95, 35, 22) * 0.6
for k in range(3):
    L = int(0.006 * SR); c = bp(r.standard_normal(L), 1500, 4200) * np.exp(-np.arange(L) / (L / 4))
    place(x, c * 0.8, 0.02 + k * 0.035 + r.uniform(0, 0.02))
save(fade_out(sat(x, 2.0), 0.05), 'fire/tick')

# wind.tick: 칼바람이 스치는 짧은 '휙'
r = R(602); d = 0.26; t = t_(d)
x = sweep(r.standard_normal(len(t)), 1900, 650, q=2.2) * np.exp(-((t - 0.05) / 0.035) ** 2) * 2.2
x += bp(r.standard_normal(len(t)), 180, 700) * env_ad(t, 0.004, 0.03) * 0.9
save(fade_out(sat(x, 1.8), 0.05), 'wind/tick')

# water.tick: 물이 찰싹 치는 '촵' + 작은 물방울
r = R(603); d = 0.3; t = t_(d)
x = bp(r.standard_normal(len(t)), 350, 2600) * env_ad(t, 0.003, 0.035) * 2.0
x += lp(r.standard_normal(len(t)), 700) * env_ad(t, 0.004, 0.06) * 1.2
for k in range(4):
    f0 = r.uniform(600, 1500); L = int(r.uniform(0.02, 0.04) * SR); tt = np.arange(L) / SR
    place(x, np.sin(osc(f0 * (1 + 2.0 * tt / tt[-1]))) * np.exp(-tt / (tt[-1] / 4)) * 0.35, 0.015 + r.exponential(0.04))
save(fade_out(sat(x, 1.8), 0.05), 'water/tick')

# ice.tick: 서리가 엉기는 작은 '사각' + 찰캉 두어 번
r = R(604); d = 0.26; t = t_(d)
x = bp(r.standard_normal(len(t)), 700, 3000) * env_ad(t, 0.002, 0.02) * 1.6
x += tinks(d, r, 3, 2000, 3600, lambda k, r: 0.004 + k * 0.022 + r.uniform(0, 0.01)) * 0.9
x += boom(d, 240, 120, 40, 30) * 0.4
save(fade_out(sat(x, 1.8), 0.05), 'ice/tick')

# ---------------------------------------------------------------- 가벼운 적중
# ice.shard_hit: 파편 적중 — 짧고 밝은 '틱-착' (0.3초, 앞 1ms)
r = R(605); d = 0.3; t = t_(d)
x = hp(r.standard_normal(len(t)), 1500) * env_ad(t, 0.0006, 0.008) * 1.6
x += tinks(d, r, 5, 2200, 4200, lambda k, r: k * 0.004 + r.uniform(0, 0.004)) * 1.3
x += bp(r.standard_normal(len(t)), 400, 1400) * env_ad(t, 0.001, 0.018) * 1.2
x += boom(d, 330, 160, 60, 45) * 0.35
save(fade_out(sat(x, 2.4), 0.06), 'ice/shard_hit')

# ---------------------------------------------------------------- 무거운 공격 직전 바람 (2개)
for i in range(2):
    # 꼭대기 0.285초: 변형 2는 sounds.json 에서 음정 0.92 → 0.31초. 두 변형 모두 6틱(0.3초) ±0.3틱 안에 끝남
    r = R(610 + i); d = 0.32; t = t_(d); p = np.clip(t / 0.285, 0, 1)
    cur = p ** 1.5
    x = sweep(r.standard_normal(len(t)), r.uniform(260, 320), r.uniform(1300, 1600), q=1.5, curve=cur) * p ** 2.2 * 2.4
    x += sweep(r.standard_normal(len(t)), 90, 320, q=1.1, curve=cur) * p ** 2.6 * 2.0
    x += lp(r.standard_normal(len(t)), 120, 4) * p ** 3 * 4
    x *= np.where(t < 0.285, 1.0, np.cos(np.clip((t - 0.285) / 0.03, 0, 1) * np.pi / 2) ** 2)
    save(sat(x, 1.6), f'common/heavy_whoosh_{i + 1}')

# ---------------------------------------------------------------- 궁극기 직전 차오름
r = R(620); d = 0.84; t = t_(d); p = np.clip(t / 0.8, 0, 1)
cur = p ** 1.6
x = sweep(r.standard_normal(len(t)), 180, 2400, q=1.8, curve=cur) * p ** 2.4 * 2.0
x += sweep(r.standard_normal(len(t)), 70, 400, q=1.2, curve=cur) * p ** 2.0 * 2.2
x += np.sin(osc(38 + 40 * p ** 2)) * p ** 2 * 0.9
trem = 0.65 + 0.35 * np.sin(osc(4 + 26 * p ** 2))
x *= trem
x *= np.where(t < 0.8, 1.0, np.cos(np.clip((t - 0.8) / 0.04, 0, 1) * np.pi / 2) ** 2)
save(sat(x, 1.8), 'common/ult_swell')
