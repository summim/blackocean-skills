"""화염 주먹 효과음 합성기. 모두 모노 48kHz → ogg(vorbis) 저장.
마크는 스테레오 소리를 거리에 따라 줄이지 않으므로 반드시 모노.
"""
import sys, os, subprocess
import numpy as np
from scipy import signal
from scipy.io import wavfile

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
SR = 48000
rng = np.random.default_rng(11)


def t_(sec):
    return np.arange(int(sec * SR)) / SR


def bp(x, lo, hi, order=2):
    sos = signal.butter(order, [lo, hi], 'bandpass', fs=SR, output='sos')
    return signal.sosfilt(sos, x)


def lp(x, f, order=2):
    return signal.sosfilt(signal.butter(order, f, 'lowpass', fs=SR, output='sos'), x)


def hp(x, f, order=2):
    return signal.sosfilt(signal.butter(order, f, 'highpass', fs=SR, output='sos'), x)


def sweep_filter(x, f0, f1, q=1.2, blocks=64):
    """블록 단위로 대역 필터 주파수를 옮겨 '빨려 드는' 느낌을 낸다."""
    out = np.zeros_like(x); n = len(x); step = n // blocks
    zi = None
    for i in range(blocks):
        a, b = i * step, (n if i == blocks - 1 else (i + 1) * step)
        f = f0 * (f1 / f0) ** (i / (blocks - 1))
        sos = signal.butter(2, [f / (1 + 1 / q), f * (1 + 1 / q)], 'bandpass', fs=SR, output='sos')
        if zi is None: zi = signal.sosfilt_zi(sos) * 0
        out[a:b], zi = signal.sosfilt(sos, x[a:b], zi=zi)
    return out


def sat(x, k=1.5):
    return np.tanh(x * k) / np.tanh(k)


def fade(x, fin=0.005, fout=0.02):
    a = int(fin * SR); b = int(fout * SR)
    x = x.copy(); x[:a] *= np.linspace(0, 1, a); x[-b:] *= np.linspace(1, 0, b)
    return x


def save(x, name, do_fade=True):
    if do_fade: x = fade(x)
    x = x / (np.max(np.abs(x)) + 1e-9) * 0.89  # 최고 -1dB
    wav = f'{OUT}/{name}.wav'
    wavfile.write(wav, SR, (x * 32767).astype(np.int16))
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', wav, '-ac', '1', '-c:a', 'libvorbis', '-q:a', '5', f'{OUT}/{name}.ogg'], check=True)
    os.remove(wav)
    print(name, f'{len(x) / SR:.2f}s')


# 1) charge: 불이 손으로 빨려 드는 소리 (0.45초)
t = t_(0.45)
noise = rng.standard_normal(len(t))
whoosh = sweep_filter(noise, 250, 3200, q=1.5) * (t / t[-1]) ** 1.6
tone_f = 110 * (4.0 ** (t / t[-1]))
tone = np.sin(2 * np.pi * np.cumsum(tone_f) / SR) * 0.35 * (t / t[-1]) ** 2
rumble = lp(rng.standard_normal(len(t)), 180) * 2.0 * (0.3 + 0.7 * t / t[-1])
save(sat(whoosh + tone + rumble, 2.0), 'charge')

# 2) launch: 묵직한 쾅 + 불꽃이 터져 나가는 소리 (0.6초)
t = t_(0.6)
f = 40 + 70 * np.exp(-t * 18)
thump = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 7)
burst = hp(rng.standard_normal(len(t)), 900) * np.exp(-t * 9) * 0.5
roar = bp(rng.standard_normal(len(t)), 200, 1800) * np.exp(-t * 5) * 1.6
save(sat(thump * 1.4 + burst + roar, 2.5), 'launch')

# 3) travel: 바람을 가르는 화르르르 (1초, 끊김 없는 반복)
N = SR
spec = np.fft.rfft(rng.standard_normal(N))
fr = np.fft.rfftfreq(N, 1 / SR)
shape = np.exp(-((np.log(fr + 1) - np.log(600)) / 0.9) ** 2)  # 600Hz 중심 둥근 대역
loop = np.fft.irfft(spec * shape, N)  # FFT 노이즈는 처음과 끝이 이어짐
t = np.arange(N) / SR
flutter = 0.7 + 0.3 * np.sin(2 * np.pi * 7 * t) * np.sin(2 * np.pi * 2 * t)  # 정수 Hz → 루프 이음새 없음
spec2 = np.fft.rfft(rng.standard_normal(N))
hiss = np.fft.irfft(spec2 * np.exp(-((np.log(fr + 1) - np.log(4000)) / 0.6) ** 2), N) * 0.25
save(sat(loop * flutter + hiss, 1.4), 'travel', do_fade=False)

# 4) impact: 크고 낮은 폭발 + 짧은 고음 '팍' (1초)
t = t_(1.0)
f = 30 + 60 * np.exp(-t * 10)
boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 3.5)
body = lp(rng.standard_normal(len(t)), 900) * np.exp(-t * 4) * 2.2
crack = hp(rng.standard_normal(len(t)), 2500) * np.exp(-t * 60) * 1.2
tail = bp(rng.standard_normal(len(t)), 150, 600) * np.exp(-t * 2.2) * 0.8
save(sat(boom * 1.6 + body + crack + tail, 3.0), 'impact')

# 5) crackle: 장작 타닥타닥이 줄어들며 끝남 (1.4초)
t = t_(1.4)
x = bp(rng.standard_normal(len(t)), 300, 2500) * 0.12 * np.exp(-t * 1.8)
pos = 0.0
while pos < 1.35:
    pos += rng.exponential(0.018 + pos * 0.06)  # 갈수록 드물게
    i = int(pos * SR)
    if i >= len(t) - 400: break
    L = int(rng.uniform(80, 300))
    click = hp(rng.standard_normal(L), rng.uniform(1500, 4000)) * np.exp(-np.arange(L) / (L / 4))
    x[i:i + L] += click * rng.uniform(0.3, 1.0) * np.exp(-pos * 1.5)
save(x, 'crackle')
