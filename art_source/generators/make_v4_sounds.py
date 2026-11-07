"""v4 효과음: 번개/바람/물 + 화염 궁극기 + 공용(궁극기 발동, 열매 변신).
사용법: python3 make_v4_sounds.py <프로젝트 루트>
모두 모노 48kHz, ogg vorbis q5, 최고 -1dBFS. 반복용(tornado, jet)은 FFT 주기 노이즈와
주기에 맞춘 변조만 써서 처음과 끝이 이어진다 (페이드 없음).
"""
import sys, os, subprocess
import numpy as np
from scipy import signal
from scipy.io import wavfile

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
SD = ROOT + '/src/main/resources/assets/blackocean_skills/sounds/skill/'
SR = 48000
rng = np.random.default_rng(4040)


# ---------------------------------------------------------------- 도구
def t_(sec): return np.arange(int(sec * SR)) / SR
def noise(sec): return rng.standard_normal(int(sec * SR))
def bp(x, lo, hi, o=2): return signal.sosfilt(signal.butter(o, [lo, hi], 'bandpass', fs=SR, output='sos'), x)
def lp(x, f, o=2): return signal.sosfilt(signal.butter(o, f, 'lowpass', fs=SR, output='sos'), x)
def hp(x, f, o=2): return signal.sosfilt(signal.butter(o, f, 'highpass', fs=SR, output='sos'), x)
def sat(x, k=1.5):
    """먼저 최고값을 1로 맞춘 뒤 tanh 로 눌러 준다 (k = 드라이브 양)."""
    x = x / (np.abs(x).max() + 1e-9)
    return np.tanh(x * k) / np.tanh(k)
def osc(freq):  # 순간 주파수 배열 → 위상
    return 2 * np.pi * np.cumsum(freq) / SR
def saw(ph): return 2 * ((ph / (2 * np.pi)) % 1) - 1
def env_ad(t, a, d, p=1.0):
    return np.where(t < a, (t / max(a, 1e-6)) ** p, np.exp(-(t - a) / d))


def sweep(x, f0, f1, q=1.5, blocks=200, curve=None):
    """대역 필터 중심을 f0 → f1 로 옮긴다 (curve: 0..1 진행 배열을 직접 줄 수도 있음)."""
    out = np.zeros_like(x); n = len(x); step = max(n // blocks, 1)
    zi = None
    for i in range(blocks):
        a, b = i * step, (n if i == blocks - 1 else (i + 1) * step)
        if a >= n: break
        q_ = i / (blocks - 1) if curve is None else curve[min(a, n - 1)]
        f = f0 * (f1 / f0) ** q_ if curve is None else f0 * (f1 / f0) ** q_
        f = np.clip(f, 30, SR / 2.3)
        sos = signal.butter(2, [f / (1 + 1 / q), min(f * (1 + 1 / q), SR / 2.1)], 'bandpass', fs=SR, output='sos')
        if zi is None: zi = np.zeros((sos.shape[0], 2))
        out[a:b], zi = signal.sosfilt(sos, x[a:b], zi=zi)
    return out


def boom(sec, f_hi, f_lo, k_pitch, k_amp):
    t = t_(sec)
    return np.sin(osc(f_lo + (f_hi - f_lo) * np.exp(-t * k_pitch))) * np.exp(-t * k_amp)


def crackles(sec, rate, lo=1500, hi=6000, size=(60, 400), amp_fn=None, seed=None):
    r = np.random.default_rng(seed) if seed is not None else rng
    n = int(sec * SR); x = np.zeros(n); pos = 0.0
    while True:
        rt = rate(pos) if callable(rate) else rate
        pos += r.exponential(1 / max(rt, 1e-3))
        i = int(pos * SR)
        if i >= n: break
        L = int(r.uniform(*size)); L = min(L, n - i)
        if L < 8: continue
        c = hp(r.standard_normal(L), r.uniform(lo, hi)) * np.exp(-np.arange(L) / (L / 4))
        g = r.uniform(0.3, 1.0) * (amp_fn(pos) if amp_fn else 1)
        x[i:i + L] += c * g
    return x


def bubble(f0, dur, rise=2.5, decay=None):
    t = t_(dur)
    decay = decay or dur / 4
    f = f0 * (1 + rise * t / dur)
    return np.sin(osc(f)) * np.exp(-t / decay) * np.clip(t / 0.002, 0, 1)


def place(dst, src, at, circular=False):
    i = int(at * SR)
    if circular:
        idx = (np.arange(len(src)) + i) % len(dst)
        np.add.at(dst, idx, src)
    else:
        L = min(len(src), len(dst) - i)
        if L > 0: dst[i:i + L] += src[:L]


def reverb(x, sec=1.2, mix=0.3, tone=4000, pre=0.01):
    n = int(sec * SR)
    ir = rng.standard_normal(n) * np.exp(-np.arange(n) / SR * 6.9 / sec)
    ir = lp(ir, tone)
    ir = np.concatenate([np.zeros(int(pre * SR)), ir])
    y = signal.fftconvolve(x, ir)[:len(x)]
    y = y / (np.abs(y).max() + 1e-9) * np.abs(x).max()
    return x + y * mix


def fit(x, sec):
    n = int(sec * SR)
    return x[:n] if len(x) >= n else np.concatenate([x, np.zeros(n - len(x))])


def fade(x, fin=0.004, fout=0.04):
    a = int(fin * SR); b = int(fout * SR)
    x = x.copy(); x[:a] *= np.linspace(0, 1, a); x[-b:] *= np.linspace(1, 0, b) ** 1.5
    return x


def save(x, folder, name, loop=False, fout=0.04):
    x = x - np.mean(x) if loop else x
    if not loop:
        x = fade(x, fout=fout)
    x = x / (np.abs(x).max() + 1e-9) * 0.891  # -1 dBFS
    os.makedirs(SD + folder, exist_ok=True)
    w = f'{SD}{folder}/{name}.wav'; o = w[:-4] + '.ogg'
    for _ in range(4):  # vorbis 는 압축 후 최고값이 튀므로, 풀어서 재고 다시 맞춘다
        wavfile.write(w, SR, (x * 32767).astype(np.int16))
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', w, '-ac', '1', '-ar', str(SR), '-c:a', 'libvorbis', '-q:a', '5', o], check=True)
        dec = np.frombuffer(subprocess.run(['ffmpeg', '-v', 'error', '-i', o, '-f', 'f32le', '-'], capture_output=True, check=True).stdout, np.float32)
        pk = np.abs(dec).max()
        if pk <= 0.905: break
        x = x * (0.88 / pk)
    os.remove(w)
    print(f'{folder}/{name}.ogg  {len(x) / SR:.2f}s  peak {20 * np.log10(pk):.1f} dB  rms {20 * np.log10(np.sqrt((dec ** 2).mean())):.1f} dB')


def fft_noise(N, center, width_oct, seed=None):
    """주기적 대역 노이즈 (길이 N 이 그대로 한 주기)."""
    r = np.random.default_rng(seed)
    spec = np.fft.rfft(r.standard_normal(N))
    fr = np.fft.rfftfreq(N, 1 / SR)
    shape = np.exp(-((np.log2(fr + 1) - np.log2(center)) / width_oct) ** 2)
    x = np.fft.irfft(spec * shape, N)
    return x / np.abs(x).max()


def thunder_crack(sec, seed=0):
    """여러 갈래 번개가 거의 동시에 갈라지는 '콰직'."""
    r = np.random.default_rng(seed)
    x = np.zeros(int(sec * SR))
    for k in range(7):
        at = r.uniform(0, 0.06) + (k > 4) * r.uniform(0.05, 0.15)
        L = int(r.uniform(0.08, 0.25) * SR)
        tt = np.arange(L) / SR
        c = hp(r.standard_normal(L), r.uniform(600, 2500)) * np.exp(-tt * r.uniform(18, 40))
        place(x, c * r.uniform(0.5, 1.0), at)
    return x


# ================================================================ 번개
# charge: 높아지는 전기 웅웅 + 점점 잦아지는 타닥
d = 0.6; t = t_(d); p = t / d
f = 70 * (3.2 ** p) * (1 + 0.02 * np.sin(2 * np.pi * 31 * t))
buzz = saw(osc(f)) * 0.6 + saw(osc(f * 1.005)) * 0.4
buzz = sweep(buzz, 400, 2400, q=1.2) * 1.4 + lp(buzz, 900) * 0.4
buzz *= (0.25 + 0.75 * p ** 1.3) * (0.75 + 0.25 * np.sign(np.sin(2 * np.pi * (18 + 30 * p) * t)))
hum = np.sin(osc(55 * (2 ** p))) * 0.5 * p
cr = crackles(d, lambda s: 15 + 140 * (s / d) ** 2, 2000, 8000, (40, 250), lambda s: 0.3 + 0.7 * s / d)
save(sat(buzz + hum + cr * 0.9, 2.6), 'lightning', 'charge')

# zap: 날카로운 '찌직' + 짧은 지글
d = 0.45; t = t_(d)
crack = hp(noise(d), 1800) * np.exp(-t * 35) * 2.0
zf = 1800 * np.exp(-t * 14) + 140
zt = np.sign(np.sin(osc(zf))) * np.exp(-t * 16) * 0.5
zt = bp(zt, 300, 6000)
sizzle = hp(noise(d), 3000) * np.exp(-t * 5) * 1.0 * (0.6 + 0.4 * np.sign(np.sin(2 * np.pi * 47 * t)))
cr = crackles(d, lambda s: 120 * np.exp(-s * 6), 2500, 9000, (30, 200))
thump = boom(d, 180, 60, 30, 18) * 0.7
save(sat(crack + zt + sizzle + cr * 0.8 + thump, 4.0), 'lightning', 'zap')

# thunder: 아주 큰 '콰광' 뒤에 길게 구르는 우르릉
d = 2.5; t = t_(d)
cr = thunder_crack(d, seed=3) * 1.6
bang = boom(d, 120, 32, 9, 2.2) * 1.4
body = lp(noise(d), 1400) * np.exp(-t * 4.5) * 1.6
# 구르는 우르릉: 낮은 노이즈에 느리고 불규칙한 진폭
rum = lp(noise(d), 160, 4) * 7.0
modn = lp(rng.standard_normal(len(t)), 6, 2); modn = modn / np.abs(modn).max()
rum_env = np.clip(0.55 + 0.6 * modn, 0.05, 1.3) * np.exp(-t * 0.75) * np.clip(t / 0.05, 0, 1)
mid = bp(noise(d), 120, 500) * rum_env * 2.2
x = cr + bang + body + rum * rum_env + mid
x = reverb(x, 1.6, 0.35, tone=2500)
save(sat(x, 4.0), 'lightning', 'thunder', fout=0.3)

# blink: 찌직 + 빠른 휙
d = 0.5; t = t_(d)
z = hp(noise(d), 2000) * np.exp(-t * 60) * 1.5 + np.sign(np.sin(osc(2400 * np.exp(-t * 25) + 200))) * np.exp(-t * 30) * 0.35
wh = sweep(noise(d), 600, 5000, q=1.8) * np.exp(-((t - 0.16) / 0.07) ** 2) * 2.2
wh2 = sweep(noise(d), 4000, 900, q=1.5) * np.exp(-((t - 0.26) / 0.09) ** 2) * 1.0
cr = crackles(d, lambda s: 150 * np.exp(-s * 8), 3000, 9000, (30, 150))
save(sat(z + wh + wh2 + cr * 0.6, 3.5), 'lightning', 'blink')

# discharge: 사방으로 터지는 수많은 타닥 + 낮은 쿵
d = 1.0; t = t_(d)
cr = crackles(d, lambda s: 900 * np.exp(-s * 4.5) + 30, 1500, 9000, (40, 500), lambda s: np.exp(-s * 2.5))
thump = boom(d, 140, 38, 14, 4.5) * 1.6
burst = hp(noise(d), 1200) * np.exp(-t * 20) * 1.4
f = 110 * np.exp(-t * 2) + 50
buzz = bp(saw(osc(f)) + saw(osc(f * 1.5)) * 0.5, 200, 3000) * np.exp(-t * 5) * 0.6
x = reverb(cr * 1.2 + thump + burst + buzz, 0.9, 0.25)
save(sat(x, 3.5), 'lightning', 'discharge')

# storm_charge: 불길하게 차오르는 우르릉 + 멀리서 치는 천둥
d = 1.5; t = t_(d); p = t / d
rum = lp(noise(d), 120, 4) * 3.5 * (0.15 + 0.85 * p ** 1.5)
drone_f = 42 * (1.5 ** p)
drone = (np.sin(osc(drone_f)) + 0.5 * np.sin(osc(drone_f * 1.498)) + 0.3 * saw(osc(drone_f * 2.003))) * (0.1 + 0.6 * p ** 2)
drone = lp(drone, 600)
dist = np.zeros(len(t))
for at, g in [(0.35, 0.6), (0.95, 1.0)]:
    L = 0.6
    c = lp(thunder_crack(L, seed=int(at * 10)), 900) * 1.2 + lp(noise(L), 200) * np.exp(-t_(L) * 4) * 2
    place(dist, c * g, at)
rise = sweep(noise(d), 200, 2500, q=1.0) * p ** 3 * 1.2
x = reverb(rum + drone + dist + rise, 1.4, 0.4, tone=1800)
save(sat(x, 2.6), 'lightning', 'storm_charge', fout=0.08)

# ================================================================ 바람
# charge: 빙글 도는 휘이잉 (점점 높아짐)
d = 0.6; t = t_(d); p = t / d
x = sweep(noise(d), 300, 2600, q=2.0)
swirl = 0.55 + 0.45 * np.sin(2 * np.pi * np.cumsum(6 + 22 * p) / SR)
whistle = np.sin(osc(700 * (2 ** (p * 1.2)) * (1 + 0.03 * np.sin(2 * np.pi * 9 * t)))) * 0.12 * p ** 2
low = lp(noise(d), 200) * 1.5 * p
save(sat(x * swirl * (0.2 + 0.8 * p ** 1.4) * 1.8 + whistle + low, 2.4), 'wind', 'charge')

# blade: 날카롭게 베는 '슈왁'
d = 0.6; t = t_(d)
cur = np.clip(t / 0.18, 0, 1) ** 0.7
x = sweep(noise(d), 1200, 7000, q=3.0, curve=cur) * env_ad(t, 0.07, 0.09, 2) * 2.4
body = sweep(noise(d), 500, 2500, q=1.5, curve=cur) * env_ad(t, 0.05, 0.12, 2) * 1.0
ring = (np.sin(osc(np.full(len(t), 3900))) + 0.6 * np.sin(osc(np.full(len(t), 5730)))) * env_ad(t - 0.06, 0.003, 0.08) * (t > 0.06) * 0.18
air = bp(noise(d), 300, 1200) * np.exp(-((t - 0.12) / 0.12) ** 2) * 0.6
save(sat(x + body + ring + air, 2.8), 'wind', 'blade')

# dash: 세게 지나가는 휘익 (도플러: 올라갔다 내려감)
d = 0.7; t = t_(d)
c = 0.5 + 0.5 * np.tanh((t - 0.3) / 0.09)
cur = np.where(t < 0.3, (t / 0.3) ** 1.2, 1 - (t - 0.3) / 0.4 * 0.8)
x = sweep(noise(d), 350, 3200, q=1.6, curve=np.clip(cur, 0, 1)) * np.exp(-((t - 0.3) / 0.13) ** 2) * 2.6
rum = lp(noise(d), 160) * np.exp(-((t - 0.3) / 0.15) ** 2) * 2.5
hiss = hp(noise(d), 3500) * np.exp(-((t - 0.28) / 0.08) ** 2) * 0.6
flap = 0.8 + 0.2 * np.sin(2 * np.pi * 34 * t)
save(sat((x + hiss) * flap + rum, 2.8), 'wind', 'dash')

# tornado: 울부짖는 바람 3초 반복 (주기 3초 → 모든 변조는 k/3 Hz)
d = 3.0; N = int(d * SR); t = np.arange(N) / SR
T = d
howl = np.zeros(N)
for k, (fc, w, rate, phs) in enumerate([(320, 0.12, 2, 0.0), (470, 0.10, 3, 1.3), (690, 0.09, 4, 2.1), (980, 0.08, 5, 0.7), (1450, 0.07, 7, 2.9)]):
    band = fft_noise(N, fc, w, seed=100 + k)
    am = 0.5 + 0.5 * np.sin(2 * np.pi * rate / T * t + phs)
    howl += band * am ** 2 * (1.0 / (1 + k * 0.25))
rush = fft_noise(N, 600, 1.4, seed=120) * (0.75 + 0.25 * np.sin(2 * np.pi * 6 / T * t))
rum = fft_noise(N, 70, 0.8, seed=121) * 1.2 * (0.8 + 0.2 * np.sin(2 * np.pi * 2 / T * t + 1))
hiss = fft_noise(N, 4500, 0.8, seed=122) * 0.25 * (0.7 + 0.3 * np.sin(2 * np.pi * 9 / T * t))
gust = 0.75 + 0.25 * np.sin(2 * np.pi * 1 / T * t) + 0.1 * np.sin(2 * np.pi * 5 / T * t + 2)
x = (howl * 0.9 + rush * 0.8 + rum + hiss) * gust
save(sat(x * 1.3, 1.5), 'wind', 'tornado', loop=True)

# burst: 크게 터지는 돌풍 '쿠왕'
d = 1.0; t = t_(d)
th = boom(d, 110, 35, 12, 4) * 1.6
blast = lp(noise(d), 2500) * env_ad(t, 0.01, 0.18) * 2.0
wh = sweep(noise(d), 2800, 250, q=1.4) * env_ad(t, 0.02, 0.3) * 2.0
hiss = hp(noise(d), 3000) * env_ad(t, 0.005, 0.12) * 0.6
x = reverb(th + blast + wh + hiss, 0.9, 0.3, tone=3000)
save(sat(x, 3.2), 'wind', 'burst')

# ================================================================ 물
# charge: 보글보글 + 소용돌이쳐 올라오는 물
d = 0.6; t = t_(d); p = t / d
x = np.zeros(len(t))
pos = 0.0
while pos < d - 0.05:
    pos += rng.exponential(1 / (25 + 160 * (pos / d) ** 1.5))
    f0 = rng.uniform(250, 600) * (1 + 1.5 * pos / d)
    place(x, bubble(f0, rng.uniform(0.02, 0.06), rise=rng.uniform(1.5, 3)) * rng.uniform(0.3, 1) * (0.4 + 0.6 * pos / d), pos)
swirl = sweep(noise(d), 250, 1800, q=1.5) * (0.2 + 0.8 * p) * (0.6 + 0.4 * np.sin(2 * np.pi * np.cumsum(5 + 15 * p) / SR)) * 0.9
low = lp(noise(d), 250) * p * 1.2
save(sat(x * 1.2 + swirl + low, 2.2), 'water', 'charge')

# shot: 물줄기 휙 + '뽁'
d = 0.5; t = t_(d)
bloop = np.sin(osc(220 * (1 + 3 * np.clip(t / 0.06, 0, 1)))) * np.exp(-t * 28) * 1.0
wh = sweep(noise(d), 500, 2500, q=1.4) * env_ad(t, 0.02, 0.12) * 1.8
wet = bp(noise(d), 800, 3000) * env_ad(t, 0.01, 0.08) * (0.6 + 0.4 * np.sin(2 * np.pi * 60 * t)) * 0.8
low = lp(noise(d), 180) * env_ad(t, 0.005, 0.06) * 2
x = bloop + wh + wet + low
for k in range(5):
    place(x, bubble(rng.uniform(500, 1200), 0.04) * 0.4, 0.04 + k * 0.03)
save(sat(x, 2.4), 'water', 'shot')

# splash: 크게 '촤악'
d = 0.9; t = t_(d)
thump = boom(d, 120, 45, 20, 9) * 1.4
slap = bp(noise(d), 200, 3500) * env_ad(t, 0.004, 0.09) * 2.4
spray = hp(noise(d), 2500) * env_ad(t, 0.01, 0.25) * 0.9 * (0.7 + 0.3 * np.abs(lp(rng.standard_normal(len(t)), 40) * 8))
x = thump + slap + spray
pos = 0.03
while pos < 0.7:
    pos += rng.exponential(0.012 + pos * 0.04)
    place(x, bubble(rng.uniform(400, 1600), rng.uniform(0.02, 0.05)) * rng.uniform(0.2, 0.7) * np.exp(-pos * 3), pos)
fall = bp(noise(d), 1000, 6000) * np.exp(-((t - 0.3) / 0.22) ** 2) * 0.6
x = reverb(x + fall, 0.7, 0.2)
save(sat(x, 3.4), 'water', 'splash')

# jet: 세찬 물줄기 2초 반복
d = 2.0; N = int(d * SR); t = np.arange(N) / SR; T = d
rush = fft_noise(N, 900, 1.2, seed=200) * (0.85 + 0.15 * np.sin(2 * np.pi * 14 / T * t))
hiss = fft_noise(N, 4200, 0.9, seed=201) * 0.55 * (0.8 + 0.2 * np.sin(2 * np.pi * 26 / T * t + 1))
low = fft_noise(N, 90, 0.7, seed=202) * 0.9 * (0.85 + 0.15 * np.sin(2 * np.pi * 3 / T * t))
gurg = np.zeros(N)
r2 = np.random.default_rng(203)
for _ in range(90):  # 원형으로 감싸 넣어 이음새 없음
    b = bubble(r2.uniform(300, 1400), r2.uniform(0.015, 0.05), rise=r2.uniform(1, 3)) * r2.uniform(0.1, 0.35)
    place(gurg, b, r2.uniform(0, d), circular=True)
flutter = 0.88 + 0.12 * np.sin(2 * np.pi * 40 / T * t)
x = (rush + hiss + low) * flutter + gurg
save(sat(x * 1.2, 1.5), 'water', 'jet', loop=True)

# wave: 크게 덮치는 파도 → 쏴아 빠지는 물
d = 2.0; t = t_(d)
build = sweep(noise(d), 150, 1200, q=0.9, curve=np.clip(t / 0.55, 0, 1)) * np.clip(t / 0.55, 0, 1) ** 2 * 1.4
crash_env = np.where(t < 0.55, 0, np.exp(-(t - 0.55) / 0.5))
crash = lp(noise(d), 3500) * crash_env * 2.4 + boom(d, 90, 35, 8, 3) * 0
bm = np.zeros(len(t)); place(bm, boom(1.4, 100, 32, 10, 3.5) * 1.6, 0.55)
wash = bp(noise(d), 600, 7000) * np.where(t < 0.6, 0, np.exp(-(t - 0.6) / 0.9)) * 1.0 * (0.75 + 0.25 * np.abs(lp(rng.standard_normal(len(t)), 30) * 6))
fizz = np.zeros(len(t)); pos = 0.7
while pos < 1.9:
    pos += rng.exponential(0.008)
    place(fizz, bubble(rng.uniform(1500, 4000), 0.012) * rng.uniform(0.05, 0.25) * np.exp(-(pos - 0.7) * 1.6), pos)
x = reverb(build + crash + bm + wash + fizz, 1.0, 0.3)
save(sat(x, 2.6), 'water', 'wave', fout=0.25)

# dragon: 낮고 젖은 포효 + 밀려드는 물
d = 2.5; t = t_(d)
pc = np.interp(t, [0, 0.25, 0.9, 1.8, 2.5], [55, 85, 95, 70, 50])
pc = pc * (1 + 0.04 * np.sin(2 * np.pi * 5.5 * t)) * (1 + 0.02 * lp(rng.standard_normal(len(t)), 20) * 10)
src = saw(osc(pc)) + 0.6 * saw(osc(pc * 1.01)) + 0.4 * saw(osc(pc * 0.5))
rough = 0.65 + 0.35 * np.sin(2 * np.pi * 31 * t + np.cumsum(rng.standard_normal(len(t))) * 0.002)
src = src * rough + lp(noise(d), 1500) * 0.4
vow = bp(src, 500, 900) * 1.4 + bp(src, 1000, 1500) * 0.8 + bp(src, 2300, 3000) * 0.3 + lp(src, 250) * 0.9
roar_env = np.interp(t, [0, 0.12, 0.4, 1.5, 2.3, 2.5], [0, 0.8, 1.0, 0.85, 0.15, 0])
roar = vow * roar_env
surge = sweep(noise(d), 200, 1600, q=0.9, curve=np.interp(t, [0, 1.0, 2.5], [0, 1, 0.3])) * np.interp(t, [0, 0.3, 1.2, 2.5], [0.2, 0.8, 1.0, 0.1]) * 1.6
bub = np.zeros(len(t)); pos = 0.1
while pos < 2.3:
    pos += rng.exponential(0.02)
    place(bub, bubble(rng.uniform(150, 600), rng.uniform(0.03, 0.08)) * rng.uniform(0.1, 0.4), pos)
sub = np.sin(osc(pc * 0.5)) * roar_env * 0.8
x = reverb(roar * 1.2 + surge + bub * 0.8 + sub, 1.3, 0.35, tone=3000)
save(sat(x, 2.0), 'water', 'dragon', fout=0.2)

# ================================================================ 불 (궁극기)
# sun_charge: 낮게 깔렸다가 치솟는 불의 포효
d = 1.4; t = t_(d); p = t / d
roar = sweep(noise(d), 120, 1600, q=0.8) * (0.15 + 0.85 * p ** 1.6) * 2.2
rum = lp(noise(d), 140, 4) * 3.0 * (0.3 + 0.7 * p)
drone_f = 38 * (2 ** (p * 1.3))
drone = lp(saw(osc(drone_f)) + 0.6 * saw(osc(drone_f * 1.5)), 500) * p ** 2 * 0.9
cr = crackles(d, lambda s: 20 + 200 * (s / d) ** 2, 800, 4000, (80, 400), lambda s: 0.3 + 0.7 * s / d)
suck = hp(noise(d), 3000) * p ** 4 * 0.6
x = reverb(roar + rum + drone + cr * 0.7 + suck, 1.0, 0.25, tone=2500)
save(sat(x, 2.6), 'fire', 'sun_charge', fout=0.05)

# sun_impact: 거대한 폭발 + 길게 타오르는 꼬리
d = 2.5; t = t_(d)
b1 = boom(d, 140, 28, 7, 1.6) * 1.8
b2 = np.zeros(len(t)); place(b2, boom(1.5, 90, 30, 9, 2.5) * 1.0, 0.07)
crack = thunder_crack(d, seed=77) * 1.0 + hp(noise(d), 2000) * np.exp(-t * 30) * 1.5
blast = lp(noise(d), 3000) * env_ad(t, 0.005, 0.25) * 2.6
roar = bp(noise(d), 120, 1800) * np.interp(t, [0, 0.1, 0.6, 2.5], [0.6, 1.0, 0.7, 0]) * 1.8
rum = lp(noise(d), 120, 4) * np.exp(-t * 1.2) * 4
cr = crackles(d, lambda s: 60 * np.exp(-s * 0.8) + 10, 700, 4000, (80, 500), lambda s: np.exp(-s * 0.9))
x = b1 + b2 + crack + blast + roar + rum + cr * 0.8
x = reverb(x, 1.8, 0.35, tone=2200)
save(sat(x, 3.4), 'fire', 'sun_impact', fout=0.4)

# ================================================================ 공용
# ultimate: 낮은 쿵 → 반짝이며 치솟는 상승음 → 꽝
d = 2.0; t = t_(d)
x = np.zeros(len(t))
place(x, boom(1.2, 90, 30, 10, 3) * 1.6 + lp(noise(1.2), 300) * np.exp(-t_(1.2) * 5) * 1.5, 0.0)
rise_len = 1.05; tr = t_(rise_len); pr = tr / rise_len
chord = np.zeros(len(tr))
for ratio, g in [(1, 1), (1.26, 0.7), (1.5, 0.8), (2, 0.6), (3, 0.3)]:
    f = 110 * ratio * (2 ** (pr ** 1.5 * 2.0))
    chord += g * (saw(osc(f)) * 0.5 + saw(osc(f * 1.007)) * 0.5)
chord = sweep(chord, 400, 6000, q=1.0) * pr ** 1.3 * 1.4
shimmer = np.zeros(len(tr))
for k in range(6):
    f = rng.uniform(2500, 6000) * (2 ** (pr * 0.8))
    shimmer += np.sin(osc(f)) * (0.5 + 0.5 * np.sin(2 * np.pi * rng.uniform(9, 17) * tr + k))
shimmer *= pr ** 3 * 0.18
wsh = sweep(noise(rise_len), 300, 7000, q=1.3) * pr ** 1.8 * 1.5
place(x, chord + shimmer + wsh, 0.12)
hit = boom(0.85, 160, 32, 14, 3.5) * 2.0 + lp(noise(0.85), 4000) * env_ad(t_(0.85), 0.003, 0.15) * 2.4 + hp(noise(0.85), 3000) * np.exp(-t_(0.85) * 6) * 0.8
place(x, hit, 1.15)
bell = np.zeros(int(0.85 * SR)); tb = t_(0.85)
for f, g in [(880, 0.4), (1318, 0.3), (1760, 0.25), (2637, 0.15)]:
    bell += np.sin(osc(np.full(len(tb), f))) * g * np.exp(-tb * 3.5)
place(x, bell * 0.6, 1.15)
x = reverb(x, 1.4, 0.35, tone=5000)
save(sat(x, 3.2), 'common', 'ultimate', fout=0.3)

# transform: 열매를 먹고 힘이 차오르는 마법 소리
d = 2.0; t = t_(d); p = t / d
swell = np.zeros(len(t))
notes = [261.6, 329.6, 392.0, 493.9, 587.3, 784.0]  # C 장7 + 9 계열 상승
for i, f in enumerate(notes):
    at = 0.12 + i * 0.13
    L = d - at; tl = t_(L)
    tone = (np.sin(osc(np.full(len(tl), f))) + 0.35 * np.sin(osc(np.full(len(tl), f * 2.001))) + 0.15 * np.sin(osc(np.full(len(tl), f * 3.003))))
    tone *= np.clip(tl / 0.05, 0, 1) * np.exp(-tl * 1.1) * (1 + 0.2 * np.sin(2 * np.pi * 5 * tl))
    place(swell, tone * 0.35, at)
pad_f = 130.8
pad = lp(saw(osc(np.full(len(t), pad_f))) + saw(osc(np.full(len(t), pad_f * 1.006))) + saw(osc(np.full(len(t), pad_f * 1.5))), 400 + 0 * t[0])
pad = sweep(pad, 300, 3000, q=0.9) * np.interp(t, [0, 1.2, 1.5, 2.0], [0.05, 1, 0.8, 0]) * 0.8
rev = sweep(noise(d), 400, 8000, q=1.2) * np.interp(t, [0, 1.25, 1.35, 2.0], [0, 1, 0.2, 0]) ** 2 * 1.0
sp = np.zeros(len(t))
for _ in range(40):
    at = rng.uniform(0.3, 1.8)
    sp_len = 0.12; ts = t_(sp_len)
    place(sp, np.sin(osc(np.full(len(ts), rng.uniform(2500, 7000)))) * np.exp(-ts * 30) * rng.uniform(0.05, 0.2), at)
pop = np.zeros(len(t)); place(pop, boom(0.6, 200, 60, 20, 6) * 0.8, 1.3)
x = reverb(swell + pad + rev + sp + pop, 1.5, 0.45, tone=6000)
save(sat(x, 1.4), 'common', 'transform', fout=0.3)
