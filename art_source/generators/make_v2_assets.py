"""v1.2 추가 에셋: 스킬 아이콘 4개, 그을음 자국, 효과음 5개"""
import sys, os, subprocess, json
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy import signal
from scipy.io import wavfile

ROOT = sys.argv[1]
A = ROOT + '/src/main/resources/assets/blackocean_skills/'
os.makedirs(A + 'textures/gui/skills', exist_ok=True)
os.makedirs(A + 'textures/fx', exist_ok=True)

def ramp(t):
    stops = np.array([0.0, 0.2, 0.45, 0.7, 0.88, 1.0])
    cols = np.array([[0.20, 0.01, 0.0], [0.75, 0.08, 0.02], [1.0, 0.36, 0.04], [1.0, 0.68, 0.18], [1.0, 0.92, 0.6], [1.0, 1.0, 0.95]])
    t = np.clip(t, 0, 1)
    return np.stack([np.interp(t, stops, cols[:, i]) for i in range(3)], -1)

# ---------- 아이콘: 64x64 마스크를 그려 화염 색으로 칠하고, 테두리 + 어두운 배경 ----------
S = 64
def icon(name, draw_fn):
    m = Image.new('L', (S, S), 0); d = ImageDraw.Draw(m); draw_fn(d)
    mask = np.asarray(m.filter(ImageFilter.GaussianBlur(0.8)), np.float32) / 255
    heat = np.asarray(m.filter(ImageFilter.GaussianBlur(5)), np.float32) / 255
    yy = np.linspace(1, 0, S)[:, None]
    col = ramp(np.clip(heat * 0.8 + 0.08 + yy * 0.12, 0, 1))
    glow = np.asarray(m.filter(ImageFilter.GaussianBlur(4)), np.float32) / 255
    bg = np.zeros((S, S, 3)); bg[:] = (0.09, 0.05, 0.05)
    r = np.sqrt(((np.mgrid[0:S, 0:S][1] - S/2) / (S/2)) ** 2 + ((np.mgrid[0:S, 0:S][0] - S/2) / (S/2)) ** 2)
    bg += np.clip(0.35 - r * 0.3, 0, 1)[..., None] * np.array([0.6, 0.15, 0.02])
    rgb = bg * (1 - mask[..., None]) + col * mask[..., None] + glow[..., None] * np.array([0.9, 0.35, 0.05]) * 0.5 * (1 - mask[..., None])
    img = np.dstack([np.clip(rgb, 0, 1), np.ones((S, S))])
    Image.fromarray((img * 255).astype(np.uint8), 'RGBA').save(A + f'textures/gui/skills/{name}.png')

def fist(d):  # 옆에서 본 주먹 + 뒤로 뻗는 불꽃
    d.polygon([(4, 30), (18, 18), (16, 26), (26, 20), (24, 30), (30, 34), (20, 40), (22, 46), (6, 38)], fill=200)
    d.rounded_rectangle([22, 16, 52, 46], radius=9, fill=255)
    for y in (18, 25, 32, 39):
        d.ellipse([46, y - 1, 58, y + 8], fill=255)
    d.ellipse([26, 34, 44, 50], fill=255)
def bullets(d):
    for i, (x, y) in enumerate([(14, 14), (30, 26), (12, 40), (40, 46), (46, 12)]):
        r = 7 - (i % 2)
        d.ellipse([x + 8 - r, y - r, x + 8 + r, y + r], fill=255)
        d.polygon([(x + 8, y - r + 1), (x - 8, y), (x + 8, y + r - 1)], fill=170)
def pillar(d):
    d.polygon([(20, 60), (16, 34), (22, 22), (20, 10), (28, 18), (32, 2), (36, 16), (44, 8), (42, 24), (48, 36), (44, 60)], fill=255)
    d.ellipse([4, 52, 60, 63], fill=150)
def ring(d):
    d.ellipse([4, 14, 60, 50], outline=255, width=7)
    d.ellipse([16, 4, 48, 60], outline=200, width=5)
    d.ellipse([26, 26, 38, 38], fill=255)

for n, f in [('fire_fist', fist), ('fire_bullets', bullets), ('fire_pillar', pillar), ('flame_ring', ring)]:
    icon(n, f)

# ---------- 그을음 자국 (알파 섞기, 검붉은 갈라짐) ----------
R = 256
yy, xx = (np.mgrid[0:R, 0:R] + 0.5) / R * 2 - 1
rr = np.sqrt(xx ** 2 + yy ** 2); ang = np.arctan2(yy, xx)
rng = np.random.default_rng(5)
F = np.fft.fft2(rng.standard_normal((R, R))); f = np.sqrt(np.fft.fftfreq(R)[:, None] ** 2 + np.fft.fftfreq(R)[None, :] ** 2) * R; f[0, 0] = 1
n = np.real(np.fft.ifft2(F / f ** 1.2)); n = (n - n.min()) / (n.max() - n.min())
edge = 0.75 + 0.18 * np.sin(ang * 7 + n * 6) + (n - 0.5) * 0.3
a = np.clip((edge - rr) / 0.25, 0, 1) ** 1.3 * (0.55 + 0.45 * n)
cracks = np.clip(1 - np.abs(np.sin(ang * 9 + n * 4)) * 8, 0, 1) * np.clip(1 - rr, 0, 1) * (rr > 0.15)
rgb = np.stack([0.06 + cracks * 0.7, 0.04 + cracks * 0.18, 0.03 + cracks * 0.02], -1)
Image.fromarray((np.dstack([rgb, np.clip(a * 0.9, 0, 1)]) * 255).astype(np.uint8), 'RGBA').save(A + 'textures/fx/scorch.png')
json.dump({"texture": {"blur": True, "clamp": True}}, open(A + 'textures/fx/scorch.png.mcmeta', 'w'))

# ---------- 효과음 ----------
SR = 48000; SD = A + 'sounds/skill/'
r2 = np.random.default_rng(21)
def t_(s): return np.arange(int(s * SR)) / SR
def bp(x, lo, hi): return signal.sosfilt(signal.butter(2, [lo, hi], 'bandpass', fs=SR, output='sos'), x)
def lp(x, f): return signal.sosfilt(signal.butter(2, f, 'lowpass', fs=SR, output='sos'), x)
def hp(x, f): return signal.sosfilt(signal.butter(2, f, 'highpass', fs=SR, output='sos'), x)
def sat(x, k): return np.tanh(x * k) / np.tanh(k)
def save(x, folder, name):
    os.makedirs(SD + folder, exist_ok=True)
    a, b = int(0.004 * SR), int(0.03 * SR); x = x.copy(); x[:a] *= np.linspace(0, 1, a); x[-b:] *= np.linspace(1, 0, b)
    x = x / (np.abs(x).max() + 1e-9) * 0.89
    w = f'{SD}{folder}/{name}.wav'; wavfile.write(w, SR, (x * 32767).astype(np.int16))
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', w, '-ac', '1', '-c:a', 'libvorbis', '-q:a', '5', w[:-4] + '.ogg'], check=True); os.remove(w)
noise = lambda s: r2.standard_normal(len(t_(s)))

t = t_(0.32)  # 탄환 발사: 짧은 퓽 + 펑
f = 900 * np.exp(-t * 9) + 180
save(sat(np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 12) * 0.5 + bp(noise(0.32), 400, 3000) * np.exp(-t * 14) * 1.4 + lp(noise(0.32), 200) * np.exp(-t * 20) * 2, 2), 'fire_bullets', 'shot')
t = t_(0.45)  # 탄환 적중: 작은 폭발
save(sat(np.sin(2 * np.pi * np.cumsum(55 + 90 * np.exp(-t * 14)) / SR) * np.exp(-t * 8) + lp(noise(0.45), 1200) * np.exp(-t * 9) * 1.5 + hp(noise(0.45), 3000) * np.exp(-t * 50), 2.5), 'fire_bullets', 'impact')
t = t_(0.75)  # 불기둥 경고: 땅이 우르릉 + 높아지는 쉿
save(sat(lp(noise(0.75), 120) * 3 * (0.4 + t / 0.75) + bp(noise(0.75), 800, 2500) * (t / 0.75) ** 3 * 0.7, 1.8), 'fire_pillar', 'warn')
t = t_(1.6)  # 불기둥 분출: 쾅 + 길게 솟는 화르르
boom = np.sin(2 * np.pi * np.cumsum(35 + 60 * np.exp(-t * 9)) / SR) * np.exp(-t * 3)
roar = bp(noise(1.6), 150, 2200) * np.exp(-((t - 0.35) / 0.6) ** 2) * 2.2
hiss = hp(noise(1.6), 2500) * np.exp(-t * 2.5) * 0.5
save(sat(boom * 1.6 + roar + hiss + lp(noise(1.6), 300) * np.exp(-t * 2) * 1.5, 2.6), 'fire_pillar', 'erupt')
t = t_(0.9)  # 화염 고리 모으기: 빙빙 도는 쉭쉭
x = noise(0.9); out = np.zeros_like(x)
for k in range(18):
    a, b = k * len(x) // 18, (k + 1) * len(x) // 18
    fc = 500 + 1800 * (0.5 + 0.5 * np.sin(k * 1.9)) * (0.4 + k / 18)
    out[a:b] = bp(x[a:b], fc * 0.7, fc * 1.4)
whirl = out * (0.5 + 0.5 * np.sin(2 * np.pi * (6 + 10 * t) * t)) * (0.3 + t)
save(sat(whirl * 1.6 + lp(noise(0.9), 160) * (t / 0.9) * 2, 2), 'flame_ring', 'charge')
t = t_(1.4)  # 화염 고리 폭발: 넓게 퍼지는 쾅 + 휘이익
boom = np.sin(2 * np.pi * np.cumsum(28 + 70 * np.exp(-t * 7)) / SR) * np.exp(-t * 2.6)
sweep = bp(noise(1.4), 300, 4000) * np.exp(-t * 3.2) * 1.8
save(sat(boom * 1.8 + sweep + lp(noise(1.4), 700) * np.exp(-t * 2.4) * 2 + hp(noise(1.4), 3000) * np.exp(-t * 40), 3), 'flame_ring', 'burst')
print('ok')
