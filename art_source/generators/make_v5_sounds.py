"""v5 효과음 (소리 다듬기 패스): 비어 있던 단계(발사·적중·꼬리·궁극기 전용 모으기)와 자주 반복되는 소리의 변형본.
사용법: python3 make_v5_sounds.py [프로젝트 루트]
→ art_source/sound_raw/ 에 원본(마스터 전)을 씀. 그 다음 master_sounds.py 로 assets 에 마스터본을 만든다.

설계 원칙
- 모든 소리 = 짧은 앞 충격(트랜지언트) + 몸통(중저역) + 꼬리. 고역은 6kHz 위를 크게 쓰지 않는다 (방송에서 귀가 아픔).
- 반복되는 소리(_1/_2/_3)는 같은 레시피에 다른 시드·약간 다른 음정으로 만든 진짜 변형본.
"""
import os
import sys

import numpy as np
from scipy import signal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from audiolib import SR, save_ogg  # noqa: E402

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
RAW = ROOT + '/art_source/sound_raw/'
rng = np.random.default_rng(5050)


# ---------------------------------------------------------------- 도구
def t_(sec): return np.arange(int(sec * SR)) / SR
def noise(sec, r=None): return (r or rng).standard_normal(int(sec * SR))
def bp(x, lo, hi, o=2): return signal.sosfilt(signal.butter(o, [lo, hi], 'bandpass', fs=SR, output='sos'), x)
def lp(x, f, o=2): return signal.sosfilt(signal.butter(o, f, 'lowpass', fs=SR, output='sos'), x)
def hp(x, f, o=2): return signal.sosfilt(signal.butter(o, f, 'highpass', fs=SR, output='sos'), x)
def osc(freq): return 2 * np.pi * np.cumsum(freq) / SR
def saw(ph): return 2 * ((ph / (2 * np.pi)) % 1) - 1
def env_ad(t, a, d, p=1.0): return np.where(t < a, (t / max(a, 1e-6)) ** p, np.exp(-(t - a) / d))
def gauss(t, c, w): return np.exp(-((t - c) / w) ** 2)


def sat(x, k=1.5):
    x = x / (np.abs(x).max() + 1e-9)
    return np.tanh(x * k) / np.tanh(k)


def sweep(x, f0, f1, q=1.5, blocks=160, curve=None):
    """대역 필터 중심을 f0 → f1 로 옮김 (curve: 0..1 진행, 길이 = x)."""
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


def crackles(sec, rate, lo=1500, hi=5000, size=(60, 400), amp_fn=None, r=None):
    r = r or rng
    n = int(sec * SR); x = np.zeros(n); pos = 0.0
    while True:
        rt = rate(pos) if callable(rate) else rate
        pos += r.exponential(1 / max(rt, 1e-3)); i = int(pos * SR)
        if i >= n: break
        L = min(int(r.uniform(*size)), n - i)
        if L < 8: continue
        c = bp(r.standard_normal(L), r.uniform(lo, hi * 0.7), hi) * np.exp(-np.arange(L) / (L / 4))
        x[i:i + L] += c * r.uniform(0.3, 1.0) * (amp_fn(pos) if amp_fn else 1)
    return x


def reverb(x, sec=1.2, mix=0.3, tone=3500, pre=0.012, r=None):
    r = r or rng
    n = int(sec * SR)
    ir = lp(r.standard_normal(n) * np.exp(-np.arange(n) / SR * 6.9 / sec), tone)
    ir = np.concatenate([np.zeros(int(pre * SR)), ir])
    y = signal.fftconvolve(x, ir)[:len(x)]
    return x + y / (np.abs(y).max() + 1e-9) * np.abs(x).max() * mix


def pad(x, sec):
    n = int(sec * SR)
    return np.concatenate([x, np.zeros(max(0, n - len(x)))])


def rumble(sec, decay, cut=150, r=None, speed=5, depth=0.6):
    """불규칙하게 구르는 낮은 우르릉."""
    r = r or rng; t = t_(sec)
    m = lp(r.standard_normal(len(t)), speed, 2); m /= np.abs(m).max() + 1e-9
    env = np.clip(0.55 + depth * m, 0.05, 1.4) * np.exp(-t * decay) * np.clip(t / 0.03, 0, 1)
    return lp(r.standard_normal(len(t)), cut, 4) * env * 6 + bp(r.standard_normal(len(t)), cut, cut * 3.5) * env * 1.6


def crack_cluster(sec, r, n=6, lo=500, hi=2600, spread=0.05):
    """번개가 여러 갈래로 갈라지는 '콰직' (중역 위주라 귀가 덜 아픔)."""
    x = np.zeros(int(sec * SR))
    for k in range(n):
        at = r.uniform(0, spread) + (k > n - 3) * r.uniform(0.03, 0.12)
        L = int(r.uniform(0.06, 0.2) * SR); tt = np.arange(L) / SR
        c = bp(r.standard_normal(L), r.uniform(lo, lo * 2), hi) * np.exp(-tt * r.uniform(20, 45))
        place(x, c * r.uniform(0.5, 1.0), at)
    return x


def splash(sec, r, size=1.0, bright=3500):
    """물이 터지는 '촤악' — 넓은 대역 노이즈 버스트 + 물방울 고리."""
    t = t_(sec)
    burst = bp(r.standard_normal(len(t)), 300, bright) * env_ad(t, 0.004, 0.09 * size) * 2.0
    body = lp(r.standard_normal(len(t)), 900) * env_ad(t, 0.006, 0.18 * size) * 1.6
    drops = np.zeros(len(t))
    for _ in range(int(25 * size)):
        f0 = r.uniform(500, 1800); L = int(r.uniform(0.02, 0.06) * SR); tt = np.arange(L) / SR
        d = np.sin(osc(f0 * (1 + 2.2 * tt / tt[-1]))) * np.exp(-tt / (tt[-1] / 4))
        place(drops, d * r.uniform(0.15, 0.5), r.exponential(0.12 * size))
    return burst + body + drops * 0.8


def whoosh(sec, f0, f1, center, width, r, q=1.6, amp=1.0):
    t = t_(sec)
    return sweep(r.standard_normal(len(t)), f0, f1, q=q) * gauss(t, center, width) * amp


def flame(sec, r, cut=1200, flutter=11):
    """화르르 타는 불 — 저역 노이즈에 불규칙한 떨림."""
    t = t_(sec)
    m = lp(r.standard_normal(len(t)), flutter, 2); m /= np.abs(m).max() + 1e-9
    return lp(r.standard_normal(len(t)), cut, 3) * (0.6 + 0.4 * m)


def ice_tinks(sec, r, n=10, lo=1800, hi=4200, at_fn=None):
    """얼음 조각이 부딪히는 '찰캉' — 비조화 배음을 가진 짧은 울림 (고역은 4.5kHz 아래로)."""
    x = np.zeros(int(sec * SR))
    for k in range(n):
        f = r.uniform(lo, hi); L = int(r.uniform(0.05, 0.16) * SR); tt = np.arange(L) / SR
        y = sum(g * np.sin(2 * np.pi * f * m * tt + r.uniform(0, 6)) for m, g in [(1, 1), (1.51, 0.5), (2.13, 0.25)])
        y *= np.exp(-tt * r.uniform(25, 60)) * np.clip(tt / 0.0008, 0, 1)
        place(x, y * r.uniform(0.3, 1.0), at_fn(k, r) if at_fn else r.exponential(0.08))
    return x


def save(x, rel):
    x = np.asarray(x, dtype=np.float64)
    x = x / (np.abs(x).max() + 1e-9) * 0.89
    save_ogg(RAW + rel + '.ogg', x, q=6)
    print(f'{rel}.ogg  {len(x) / SR:.2f}s')


R = lambda s: np.random.default_rng(s)  # noqa: E731

# ================================================================ 화염
# fire_bullets.charge: 손끝에 불이 붙는 짧은 '퓻' (라이터를 켜는 느낌)
r = R(11); d = 0.32; t = t_(d)
x = boom(d, 260, 90, 30, 18) * 0.8 + bp(r.standard_normal(len(t)), 500, 3000) * env_ad(t, 0.01, 0.06) * 1.2
x += flame(d, r, 1500, 20) * env_ad(t, 0.03, 0.12) * 1.4 + crackles(d, 60, 1500, 4500, (40, 160), r=r) * 0.5
save(sat(x, 2.2), 'fire_bullets/charge')

# fire_pillar.charge: 땅이 달아오르는 낮은 웅웅 + 쉬익 (위로 차오름)
r = R(12); d = 0.75; t = t_(d); p = t / d
x = lp(r.standard_normal(len(t)), 140, 4) * 5 * (0.2 + 0.8 * p ** 1.5)
x += sweep(r.standard_normal(len(t)), 200, 1800, q=1.3) * (0.1 + 0.9 * p ** 2) * 1.6
x += np.sin(osc(45 + 60 * p ** 2)) * 0.7 * p
x += crackles(d, lambda s: 10 + 90 * (s / d) ** 2, 1200, 4000, (60, 250), lambda s: s / d, r=r) * 0.6
save(sat(x, 2.0), 'fire_pillar/charge')

# fire.sun_launch: 태양을 내던지는 '후우웅' — 묵직한 저역 쿵 + 길게 끄는 불의 포효
r = R(13); d = 2.0; t = t_(d)
x = boom(d, 120, 38, 8, 3.5) * 2.0
x += whoosh(d, 150, 1400, 0.25, 0.22, r, amp=3.0) + whoosh(d, 1100, 200, 0.75, 0.45, r, amp=1.8)
x += flame(d, r, 900, 9) * env_ad(t, 0.08, 0.7) * 2.2
x += crackles(d, lambda s: 120 * np.exp(-s * 1.5), 1200, 4000, (60, 300), r=r) * 1.4
x += flame(d, r, 2600, 16) * env_ad(t, 0.05, 0.5) * 0.9 + whoosh(d, 500, 2600, 0.22, 0.15, r, amp=1.2)
save(sat(reverb(x, 1.4, 0.3, 2500, r=r), 2.4), 'fire/sun_launch')

# fire.sun_burn: 불바다 — 크게 일렁이다 잦아드는 화르르 + 타닥 (꼬리)
r = R(14); d = 3.6; t = t_(d)
env = env_ad(t, 0.25, 1.4) * np.clip((d - t) / 0.8, 0, 1)
x = flame(d, r, 700, 6) * env * 2.4 + flame(d, r, 2200, 14) * env * 0.6
x += crackles(d, lambda s: 70 * np.exp(-s * 0.6), 1200, 4200, (60, 400), lambda s: np.exp(-s * 0.5), r=r) * 0.7
save(sat(x, 1.8), 'fire/sun_burn')

# ================================================================ 번개
# lightning.zap ×3: '파직' — 중역 균열 + 짧은 지글 + 작은 쿵 (예전보다 10kHz 이상 크게 줄임)
for i in range(3):
    r = R(20 + i); d = 0.42; t = t_(d)
    x = crack_cluster(d, r, n=5, lo=900, hi=4500, spread=0.02) * 1.6
    zf = r.uniform(1300, 1900) * np.exp(-t * 14) + 140
    x += bp(np.sign(np.sin(osc(zf))), 250, 4500) * np.exp(-t * 18) * 0.45
    x += bp(r.standard_normal(len(t)), 2200, 5500) * np.exp(-t * 9) * 0.45 * (0.6 + 0.4 * np.sign(np.sin(2 * np.pi * r.uniform(40, 60) * t)))
    x += boom(d, 190, 60, 30, 16) * 0.9
    save(sat(x, 3.2), f'lightning/zap_{i + 1}')

# lightning.strike ×3: 낙뢰 한 번 — '콰직' + 쿵 + 짧게 구르는 천둥 (1초)
for i in range(3):
    r = R(30 + i); d = 1.2; t = t_(d)
    x = crack_cluster(d, r, n=7, lo=600, hi=3800) * 2.8
    x += hp(r.standard_normal(len(t)), 1200) * env_ad(t, 0.0008, 0.012) * 1.5
    x += boom(d, r.uniform(140, 170), 40, 14, 4.0) * 2.2
    x += rumble(d, 2.6, 160, r, speed=7) * 0.9
    save(sat(reverb(x, 1.0, 0.28, 2600, r=r), 3.4), f'lightning/strike_{i + 1}')

# lightning.storm_end: 마지막 거대 번개 — 두 겹 균열, 가슴 울리는 쿵, 3.5초 동안 멀리 굴러감
r = R(35); d = 3.8; t = t_(d)
x = crack_cluster(d, r, n=10, lo=500, hi=3500, spread=0.08) * 3.6
x += bp(r.standard_normal(len(t)), 1500, 4000) * env_ad(t, 0.01, 0.35) * 0.6 * (0.6 + 0.4 * np.sign(np.sin(2 * np.pi * 43 * t)))
x += hp(r.standard_normal(len(t)), 900) * env_ad(t, 0.001, 0.02) * 1.6
x += boom(d, 110, 28, 7, 1.6) * 3.2 + boom(d, 70, 30, 5, 1.2, a=0.02) * 1.4
x += rumble(d, 0.8, 140, r, speed=4, depth=0.8) * 1.8
save(sat(reverb(x, 2.2, 0.38, 2200, r=r), 3.6), 'lightning/storm_end')

# lightning.blink_arrive: 도착 '팟!' — 공기가 갈라졌다 닫히는 소리
r = R(36); d = 0.55; t = t_(d)
x = boom(d, 300, 70, 40, 14) * 1.4 + crack_cluster(d, r, n=4, lo=1000, hi=4200, spread=0.015) * 1.4
x += sweep(r.standard_normal(len(t)), 3000, 400, q=1.6) * env_ad(t, 0.004, 0.08) * 1.2
x += crackles(d, lambda s: 120 * np.exp(-s * 9), 1800, 4800, (30, 140), r=r) * 0.6
save(sat(x, 3.0), 'lightning/blink_arrive')

# ================================================================ 바람
# wind.blade_hit ×2: 칼날이 베고 지나가는 '샥' (짧고 단단)
for i in range(2):
    r = R(40 + i); d = 0.4; t = t_(d)
    x = sweep(r.standard_normal(len(t)), r.uniform(2600, 3200), 700, q=2.2) * env_ad(t, 0.003, 0.06) * 2.0
    x += bp(r.standard_normal(len(t)), 150, 800) * env_ad(t, 0.002, 0.03) * 1.6
    x += boom(d, 220, 90, 40, 25) * 0.7
    save(sat(x, 2.4), f'wind/blade_hit_{i + 1}')

# wind.tornado_end: 회오리가 풀리며 터지는 '후왁!' — 공기 폭발 + 사방으로 흩어지는 바람
r = R(42); d = 1.5; t = t_(d)
x = boom(d, 140, 45, 10, 6) * 1.4 + bp(r.standard_normal(len(t)), 100, 900) * env_ad(t, 0.005, 0.14) * 2.4
x += whoosh(d, 1600, 250, 0.35, 0.3, r, amp=2.0) + whoosh(d, 600, 150, 0.6, 0.45, r, q=1.2, amp=1.2)
save(sat(reverb(x, 1.2, 0.25, 2500, r=r), 2.2), 'wind/tornado_end')

# wind.tempest_charge: 하늘이 빨려 들어가는 울부짖음 — 점점 높아지고 빨라지는 바람 (1.4초)
r = R(43); d = 1.4; t = t_(d); p = t / d
cur = np.clip(p ** 1.3 + 0.08 * np.sin(2 * np.pi * (2 + 6 * p) * t), 0, 1)
x = sweep(r.standard_normal(len(t)), 180, 1600, q=2.4, curve=cur) * (0.15 + 0.85 * p ** 1.2) * 2.2
x += sweep(r.standard_normal(len(t)), 90, 500, q=1.4, curve=cur) * (0.3 + 0.7 * p) * 1.4
x += lp(r.standard_normal(len(t)), 90, 4) * 3 * p ** 2
save(sat(x, 2.0), 'wind/tempest_charge')

# ================================================================ 물
# water.prison: 물이 감싸며 갇히는 '꾸르륵' — 소용돌이 + 거품 (2.6초, 꼬리 페이드)
r = R(50); d = 2.6; t = t_(d)
env = env_ad(t, 0.12, 1.4) * np.clip((d - t) / 0.6, 0, 1)
x = splash(d, r, 0.7, 2500) * 0.8
x += sweep(r.standard_normal(len(t)), 250, 700, q=2.0, curve=0.5 + 0.5 * np.sin(2 * np.pi * 1.6 * t)) * env * 1.6
bub = np.zeros(len(t))
for _ in range(70):
    f0 = r.uniform(250, 900); L = int(r.uniform(0.03, 0.09) * SR); tt = np.arange(L) / SR
    place(bub, np.sin(osc(f0 * (1 + 1.6 * tt / tt[-1]))) * np.exp(-tt / (tt[-1] / 3)) * r.uniform(0.2, 0.6), r.uniform(0.05, d - 0.3))
x += bub * env * 1.2 + lp(r.standard_normal(len(t)), 200, 3) * env * 2
save(sat(x, 1.8), 'water/prison')

# water.burst: 감옥이 터지는 '퍼엉 촤아악'
r = R(51); d = 1.4; t = t_(d)
x = boom(d, 150, 45, 14, 5) * 1.8 + splash(d, r, 1.4, 3500) * 1.5
save(sat(reverb(x, 1.0, 0.22, 2500, r=r), 2.4), 'water/burst')

# water.dragon_charge: 바닷물이 솟구쳐 모이는 '쏴아아' — 점점 커지는 물결 + 낮은 웅웅 (1.4초)
r = R(52); d = 1.4; t = t_(d); p = t / d
x = sweep(r.standard_normal(len(t)), 200, 1400, q=1.2, curve=p ** 1.2) * (0.15 + 0.85 * p ** 1.4) * 2.0
x += lp(r.standard_normal(len(t)), 120, 4) * 4 * p ** 1.5 + np.sin(osc(40 + 30 * p)) * 0.6 * p
x += splash(d, r, 0.4, 2200) * 0.3 * np.clip(p * 3, 0, 1)
save(sat(x, 1.9), 'water/dragon_charge')

# water.dragon_impact: 용이 내리꽂히는 '콰아앙 — 촤아아아' + 소용돌이로 이어지는 긴 물살 (3.2초)
r = R(53); d = 3.2; t = t_(d)
x = boom(d, 110, 30, 7, 2.4) * 3.0 + bp(r.standard_normal(len(t)), 80, 600) * env_ad(t, 0.004, 0.2) * 2.4
x += splash(d, r, 2.5, 3200) * 2.0
wash = sweep(r.standard_normal(len(t)), 300, 900, q=1.3, curve=0.5 + 0.5 * np.sin(2 * np.pi * 0.9 * t))
x += wash * env_ad(t, 0.3, 1.2) * np.clip((d - t) / 0.7, 0, 1) * 1.4
save(sat(reverb(x, 1.8, 0.3, 2400, r=r), 2.8), 'water/dragon_impact')

# ================================================================ 얼음
# ice.shards: 파편 7발이 한꺼번에 날아가는 '촤라락' (얼음 창 발사와 구분)
r = R(60); d = 0.6; t = t_(d)
x = ice_tinks(d, r, 9, 1500, 3800, lambda k, r: k * 0.012 + r.uniform(0, 0.01)) * 1.2
x += whoosh(d, 2400, 500, 0.12, 0.08, r, q=1.8, amp=1.6) + bp(r.standard_normal(len(t)), 150, 700) * env_ad(t, 0.003, 0.04) * 1.2
save(sat(x, 2.2), 'ice/shards')

# ice.age_charge: 공기가 얼어붙는 '쩌저적' — 점점 촘촘해지는 균열 + 낮게 차오르는 냉기 (1.6초)
r = R(61); d = 1.6; t = t_(d); p = t / d
x = sweep(r.standard_normal(len(t)), 300, 2200, q=2.0, curve=p) * (0.1 + 0.9 * p ** 1.5) * 1.4
x += lp(r.standard_normal(len(t)), 110, 4) * 3.5 * p ** 1.4
x += crackles(d, lambda s: 8 + 160 * (s / d) ** 2, 900, 3200, (80, 500), lambda s: 0.3 + 0.7 * s / d, r=r) * 1.2
x += ice_tinks(d, r, 14, 1600, 3600, lambda k, r: d * (0.3 + 0.7 * np.sqrt(r.uniform()))) * 0.5
save(sat(x, 2.2), 'ice/age_charge')

# ice.glacier_shatter: 빙하가 산산조각 — 거대한 '콰지직' + 쏟아지는 얼음 조각 (2.8초)
r = R(62); d = 2.8; t = t_(d)
x = boom(d, 120, 32, 8, 2.8) * 2.4 + bp(r.standard_normal(len(t)), 300, 2400) * env_ad(t, 0.002, 0.08) * 2.4
x += crackles(d, lambda s: 400 * np.exp(-s * 3), 700, 3000, (100, 900), lambda s: np.exp(-s * 1.2), r=r) * 1.6
x += ice_tinks(d, r, 60, 1400, 3800, lambda k, r: r.exponential(0.45)) * 0.9
x += rumble(d, 1.4, 130, r, speed=5) * 0.8
save(sat(reverb(x, 1.6, 0.3, 2600, r=r), 2.8), 'ice/glacier_shatter')

# ================================================================ 천둥
# thunder.hammer_impact: 망치가 꽂히는 '쾅!' — 금속성 울림 + 번개 '콰직' + 쿵
r = R(70); d = 1.4; t = t_(d)
ring = sum(g * np.sin(2 * np.pi * f * t) for f, g in [(310, 1.0), (523, 0.6), (871, 0.35), (1290, 0.2)]) * np.exp(-t * 7)
x = boom(d, 170, 42, 15, 4.5) * 2.4 + ring * 0.7 + crack_cluster(d, r, n=6, lo=700, hi=3500) * 1.4
x += bp(r.standard_normal(len(t)), 200, 2000) * env_ad(t, 0.001, 0.05) * 1.8 + rumble(d, 2.4, 150, r) * 0.7
save(sat(reverb(x, 1.1, 0.25, 2800, r=r), 3.2), 'thunder/hammer_impact')

# thunder.leap: 뛰어오르는 '후웅 — 지직' (도약 시작)
r = R(71); d = 0.7; t = t_(d)
x = boom(d, 130, 50, 18, 9) * 1.4 + whoosh(d, 150, 1200, 0.18, 0.13, r, amp=2.4)
x += crackles(d, lambda s: 220 * np.exp(-s * 4), 1500, 4500, (30, 150), r=r) * 1.6 + whoosh(d, 500, 2500, 0.16, 0.1, r, amp=1.2)
save(sat(x, 2.4), 'thunder/leap')

# thunder.roar_burst: 끌어당긴 뒤 터지는 '콰콰쾅' — 둥근 폭발 + 사방 번개 (천둥 일격과 구분: 더 둥글고 낮음)
r = R(72); d = 1.6; t = t_(d)
x = boom(d, 120, 36, 10, 3.2) * 2.6 + bp(r.standard_normal(len(t)), 120, 1200) * env_ad(t, 0.003, 0.12) * 2.2
x += crackles(d, lambda s: 600 * np.exp(-s * 5) + 20, 900, 4000, (40, 300), lambda s: np.exp(-s * 2), r=r) * 2.6
x += crack_cluster(d, r, n=5, lo=700, hi=3500) * 1.6
x += rumble(d, 1.8, 150, r, speed=6) * 1.0
save(sat(reverb(x, 1.3, 0.3, 2600, r=r), 3.0), 'thunder/roar_burst')

# thunder.rise: 하늘로 솟구치는 동안 — 차오르는 전기 웅웅 + 우르릉 (1.6초)
r = R(73); d = 1.6; t = t_(d); p = t / d
hum = sum(g * saw(osc(55 * m * 2 ** (p * 1.2))) for m, g in [(1, 1), (1.5, 0.5), (2, 0.35)])
x = sweep(hum, 200, 1600, q=1.2, curve=p) * (0.2 + 0.8 * p ** 1.4) * 1.3
x += whoosh(d, 120, 900, 0.5, 0.5, r, q=1.3, amp=1.6) + rumble(d, 0.2, 120, r, speed=4) * 0.8 * p
x += crackles(d, lambda s: 10 + 150 * (s / d) ** 2, 1200, 4000, (30, 200), lambda s: 0.3 + 0.7 * s / d, r=r) * 0.6
save(sat(x, 2.4), 'thunder/rise')

# thunder.aftershock ×2: 여진 — 땅이 다시 들썩이는 '쿠웅' (낮고 짧게, 1초)
for i in range(2):
    r = R(74 + i); d = 1.2; t = t_(d)
    x = boom(d, r.uniform(85, 105), 32, 9, 4) * 2.4 + bp(r.standard_normal(len(t)), 80, 500) * env_ad(t, 0.004, 0.1) * 1.8
    x += crackles(d, lambda s: 300 * np.exp(-s * 6), 600, 2800, (100, 700), r=r) * 2.4 + rumble(d, 2.8, 120, r) * 1.0
    x += bp(r.standard_normal(len(t)), 350, 1800) * env_ad(t, 0.003, 0.07) * 1.6
    save(sat(x, 2.6), f'thunder/aftershock_{i + 1}')
