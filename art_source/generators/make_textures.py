"""화염 텍스처 생성기. 모든 반복 텍스처는 FFT 노이즈라 상하좌우가 이음새 없이 이어진다.
더하기 섞기(additive)용 텍스처는 검은 배경, 연기만 투명 배경.
"""
import sys, os, json
import numpy as np
from PIL import Image

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(7)


def periodic_noise(n, beta=2.0, lo=2, hi=None, seed=0, shape=None):
    h, w = shape or (n, n)
    r = np.random.default_rng(seed)
    white = r.standard_normal((h, w))
    F = np.fft.fft2(white)
    fy = np.fft.fftfreq(h)[:, None] * h
    fx = np.fft.fftfreq(w)[None, :] * w
    f = np.sqrt(fx ** 2 + fy ** 2)
    f[0, 0] = 1
    amp = 1 / f ** (beta / 2)
    amp[f < lo] = 0
    if hi: amp[f > hi] = 0
    x = np.real(np.fft.ifft2(F * amp))
    x = (x - x.min()) / (x.max() - x.min())
    return x


def ramp(t):
    """중심(1.0)은 흰빛, 가장자리(0.0)로 갈수록 노랑→주황→빨강→검붉음."""
    stops = np.array([0.0, 0.2, 0.45, 0.7, 0.88, 1.0])
    cols = np.array([[0.20, 0.01, 0.0], [0.75, 0.08, 0.02], [1.0, 0.36, 0.04],
                     [1.0, 0.68, 0.18], [1.0, 0.92, 0.6], [1.0, 1.0, 0.95]])
    t = np.clip(t, 0, 1)
    return np.stack([np.interp(t, stops, cols[:, i]) for i in range(3)], -1)


def save(arr, name, mode='RGB', blur=True):
    a = np.clip(arr * 255, 0, 255).astype(np.uint8)
    Image.fromarray(a, mode).save(f'{OUT}/{name}.png')
    if blur:  # 부드럽게 보간 (마크 기본값은 도트처럼 각지게 보임)
        with open(f'{OUT}/{name}.png.mcmeta', 'w') as f:
            json.dump({"texture": {"blur": True, "clamp": False}}, f)


def cel(v, levels=(0.18, 0.42, 0.68, 0.88), soft=0.025):
    """애니풍 단계 음영: 연속값을 몇 개의 띠로 나눠 외곽선이 읽히게 한다."""
    out = np.zeros_like(v)
    for i, l in enumerate(levels):
        out += np.clip((v - l) / soft + 0.5, 0, 1) / len(levels)
    return out


# 1) 흐르는 노이즈 2장
na = periodic_noise(256, beta=2.2, lo=2, seed=1)
nb = periodic_noise(256, beta=1.6, lo=4, seed=2)
save(np.stack([na] * 3, -1), 'fire_noise_a')
save(np.stack([nb] * 3, -1), 'fire_noise_b')

# 2) 색 단계표 (왼쪽 = 가장 뜨거움)
t = np.linspace(1, 0, 256)[None, :].repeat(8, 0)
save(ramp(t), 'fire_ramp')

# 3) 손그림풍 불꽃 스프라이트 시트 8x8 (64프레임 루프)
S, G = 128, 8
yy, xx = (np.mgrid[0:S, 0:S] + 0.5) / S
yb = 1 - yy  # 0 = 아래, 1 = 위
big = periodic_noise(256, beta=2.6, lo=2, hi=14, seed=3)
det = periodic_noise(256, beta=2.0, lo=6, hi=28, seed=13)
sheet = np.zeros((S * G, S * G, 3))
for k in range(G * G):
    ph = k / (G * G)
    oy = int(ph * 256)  # 노이즈를 위로 흘림 → 마지막 프레임이 첫 프레임으로 이어짐
    n = np.roll(big, oy, axis=0)[::2, ::2]
    d = np.roll(det, oy * 2, axis=0)[::2, ::2]
    sway = 0.05 * np.sin(2 * np.pi * (ph * 2 + yb * 1.2)) * yb
    cx = 0.5 + sway
    # 아래는 둥글고 위로 갈수록 뾰족한 물방울 모양
    width = 0.34 * np.sqrt(np.clip(yb / 0.2, 0, 1)) * np.clip(1 - yb, 0, 1) ** 0.85 + 0.02
    body = 1 - np.abs(xx - cx) / width
    # 뜨거운 중심은 아래쪽 1/3 지점
    core = np.exp(-(((xx - cx) / 0.16) ** 2 + ((yb - 0.3) / 0.25) ** 2))
    v = body * 0.55 + core * 0.45 + (n - 0.5) * 0.75 * yb + (d - 0.5) * 0.25 - yb * 0.15
    v = np.clip(v, 0, 1) * (yb > 0.04)
    c = cel(v, levels=(0.12, 0.35, 0.6, 0.82), soft=0.03)
    col = ramp(c) * (c > 0.01)[..., None]
    gy, gx = divmod(k, G)
    sheet[gy * S:(gy + 1) * S, gx * S:(gx + 1) * S] = col
save(sheet, 'flame_sheet')

# 4) 궤적 띠 (가로 반복, 왼쪽이 주먹 쪽)
H, W = 128, 512
tn = periodic_noise(0, beta=2.6, lo=2, hi=16, seed=4, shape=(H, W))
tn2 = periodic_noise(0, beta=2.0, lo=6, hi=40, seed=5, shape=(H, W))
yv = np.linspace(-1, 1, H)[:, None]
streak = 1 - np.abs(yv) ** 1.4
v = streak * 0.9 + (tn - 0.5) * 0.8 + (tn2 - 0.5) * 0.3
c = cel(np.clip(v, 0, 1))
save(ramp(c) * (c > 0.01)[..., None], 'trail_flame')

# 5) 충격파 링
R = 512
yy, xx = (np.mgrid[0:R, 0:R] + 0.5) / R * 2 - 1
rr = np.sqrt(xx ** 2 + yy ** 2)
ang = (np.arctan2(yy, xx) / (2 * np.pi)) % 1
an = periodic_noise(0, beta=2.0, lo=3, seed=6, shape=(8, 512))[4]
band = np.exp(-((rr - 0.86 - (an[(ang * 511).astype(int)] - 0.5) * 0.05) / 0.06) ** 2)
inner = np.exp(-((rr - 0.8) / 0.12) ** 2) * 0.35
v = np.clip(band + inner, 0, 1) * (rr < 1)
save(ramp(v * 0.95) * v[..., None], 'shock_ring')

# 6) 섬광 (가운데 흰빛 + 짧은 빛살)
R = 256
yy, xx = (np.mgrid[0:R, 0:R] + 0.5) / R * 2 - 1
rr = np.sqrt(xx ** 2 + yy ** 2); ang = np.arctan2(yy, xx)
glow = np.exp(-rr * 4.0) + 0.5 * np.exp(-rr * 9)
rays = np.clip(np.cos(ang * 6) ** 16, 0, 1) * np.exp(-rr * 3) * 0.6
v = np.clip(glow + rays, 0, 1) * np.clip(1 - rr, 0, 1)
save(ramp(0.55 + v * 0.45) * v[..., None], 'flash_glow')

# 7) 불꽃 점, 불씨
for name, R, sharp in [('spark', 64, 5.0), ('ember', 32, 3.0)]:
    yy, xx = (np.mgrid[0:R, 0:R] + 0.5) / R * 2 - 1
    if name == 'ember':
        d = np.abs(xx) + np.abs(yy) * 0.8  # 작은 마름모
    else:
        d = np.sqrt(xx ** 2 + yy ** 2)
    v = np.clip(np.exp(-d * sharp) * 1.4 - 0.05, 0, 1) * (d < 1)
    save(ramp(0.4 + v * 0.6) * v[..., None], name)

# 8) 연기 시트 8x8 (투명 배경, 회색)
S, G = 128, 8
yy, xx = (np.mgrid[0:S, 0:S] + 0.5) / S * 2 - 1
rr = np.sqrt(xx ** 2 + yy ** 2)
smoke = np.zeros((S * G, S * G, 4))
base = periodic_noise(256, beta=2.6, lo=2, hi=20, seed=8)
for k in range(G * G):
    ph = k / (G * G)
    n = np.roll(np.roll(base, int(ph * 256), 0), int(ph * 128), 1)[:S * 2:2, :S * 2:2]
    puff = np.clip(1 - rr / (0.8 + 0.18 * ph), 0, 1) ** 0.9
    a = np.clip(puff * (0.8 + n * 0.9) - 0.1, 0, 1) * (1 - ph) ** 0.6
    shade = 0.18 + n * 0.25
    gy, gx = divmod(k, G)
    smoke[gy * S:(gy + 1) * S, gx * S:(gx + 1) * S] = np.dstack([shade, shade, shade * 0.95, a * 0.85])
save(smoke, 'smoke_sheet', 'RGBA')

# 9) 아지랑이 왜곡 (노멀맵식: R=X방향, G=Y방향, 0.5=왜곡 없음)
h = periodic_noise(256, beta=2.0, lo=3, seed=9)
gy_, gx_ = np.gradient(h)
gx_ = np.roll(gx_, 0); s = 6.0
dist = np.dstack([0.5 + np.clip(gx_ * s * 256 / 40, -0.5, 0.5), 0.5 + np.clip(gy_ * s * 256 / 40, -0.5, 0.5), np.ones_like(h)])
save(dist, 'heat_distort')

print('textures:', sorted(f for f in os.listdir(OUT) if f.endswith('.png')))
