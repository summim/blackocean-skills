"""v4 에셋: 번개/바람/물 + 화염 궁극기용 텍스처, 스킬 아이콘, 열매 아이템.
사용법: python3 make_v4_textures.py <프로젝트 루트>
- 더하기 섞기(additive) 텍스처: 검은 배경 RGB, 알파 255
- 알파 텍스처: 투명 배경 RGBA
- 반복 노이즈는 FFT 주기 노이즈 → 이음새 없음, 시트는 위상 이동으로 63→0 루프
"""
import sys, os, json
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
A = ROOT + '/src/main/resources/assets/blackocean_skills/'
FX = A + 'textures/fx/'
GUI = A + 'textures/gui/skills/'
ITEM = A + 'textures/item/'
for d in (FX, GUI, ITEM):
    os.makedirs(d, exist_ok=True)


# ---------------------------------------------------------------- 공용 도구
def periodic_noise(n, beta=2.0, lo=2, hi=None, seed=0, shape=None):
    h, w = shape or (n, n)
    r = np.random.default_rng(seed)
    F = np.fft.fft2(r.standard_normal((h, w)))
    fy = np.fft.fftfreq(h)[:, None] * h
    fx = np.fft.fftfreq(w)[None, :] * w
    f = np.sqrt(fx ** 2 + fy ** 2); f[0, 0] = 1
    amp = 1 / f ** (beta / 2)
    amp[f < lo] = 0
    if hi: amp[f > hi] = 0
    x = np.real(np.fft.ifft2(F * amp))
    return (x - x.min()) / (x.max() - x.min())


def make_ramp(cols):
    """cols: 밝음(1.0) → 어두움(0.0) 순서의 색 목록. t=1 이 가장 밝은 중심."""
    cols = np.array(cols[::-1], float)
    stops = np.linspace(0, 1, len(cols))
    def f(t):
        t = np.clip(t, 0, 1)
        return np.stack([np.interp(t, stops, cols[:, i]) for i in range(3)], -1)
    return f


fire_ramp = make_ramp([(1, 1, 0.95), (1, 0.92, 0.6), (1, 0.68, 0.18), (1, 0.36, 0.04), (0.75, 0.08, 0.02), (0.20, 0.01, 0.0)])
lightning_ramp = make_ramp([(1, 1, 1), (0.75, 0.95, 1), (0.3, 0.6, 1), (0.45, 0.2, 0.95), (0.05, 0.02, 0.2)])
wind_ramp = make_ramp([(1, 1, 1), (0.85, 1, 0.92), (0.45, 0.95, 0.75), (0.15, 0.6, 0.55), (0.02, 0.12, 0.12)])
water_ramp = make_ramp([(1, 1, 1), (0.8, 0.97, 1), (0.3, 0.7, 1), (0.06, 0.3, 0.75), (0.01, 0.04, 0.18)])


def save(arr, path, mode='RGB', clamp=None):
    a = np.clip(arr * 255 + 0.5, 0, 255).astype(np.uint8)
    Image.fromarray(a, mode).save(path)
    if clamp is not None:
        with open(path + '.mcmeta', 'w') as f:
            json.dump({"texture": {"blur": True, "clamp": clamp}}, f)


def fx(arr, name, clamp, mode='RGB'):
    save(arr, FX + name + '.png', mode, clamp)


def cel(v, levels=(0.18, 0.42, 0.68, 0.88), soft=0.025):
    out = np.zeros_like(v)
    for l in levels:
        out += np.clip((v - l) / soft + 0.5, 0, 1) / len(levels)
    return out


def grid(R):
    yy, xx = (np.mgrid[0:R, 0:R] + 0.5) / R * 2 - 1
    return xx, yy, np.sqrt(xx ** 2 + yy ** 2), np.arctan2(yy, xx)


def gray3(v):
    v = np.clip(v, 0, 1)
    return np.stack([v, v, v], -1)


def splat(canvas, x, y, sigma, amp):
    """canvas(픽셀 좌표)에 가우시안 점 하나를 더한다."""
    H, W = canvas.shape
    r = int(sigma * 3.5) + 1
    x0, x1 = max(int(x) - r, 0), min(int(x) + r + 1, W)
    y0, y1 = max(int(y) - r, 0), min(int(y) + r + 1, H)
    if x0 >= x1 or y0 >= y1: return
    gy, gx = np.mgrid[y0:y1, x0:x1]
    canvas[y0:y1, x0:x1] += amp * np.exp(-((gx + 0.5 - x) ** 2 + (gy + 0.5 - y) ** 2) / (2 * sigma ** 2))


# ---------------------------------------------------------------- 1) 색 단계표
t = np.linspace(1, 0, 256)[None, :].repeat(8, 0)
fx(lightning_ramp(t), 'lightning_ramp', True)
fx(wind_ramp(t), 'wind_ramp', True)
fx(water_ramp(t), 'water_ramp', True)

# ---------------------------------------------------------------- 2) 흰색 스프라이트
xx, yy, rr, ang = grid(256)
glow = np.exp(-rr * 4.0) + 0.5 * np.exp(-rr * 9)
rays = np.cos(ang * 3) ** 2
rays = rays ** 12 * np.exp(-rr * 5.0) * 0.28
v = np.clip(glow + rays, 0, 1) * np.clip((1 - rr) / 0.25, 0, 1) ** 1.5
fx(gray3(v), 'glow_white', True)

xx, yy, rr, ang = grid(64)
v = np.clip(np.exp(-rr * 5.0) * 1.4 - 0.05, 0, 1) + 0.25 * np.exp(-rr * 2.2)
v = np.clip(v, 0, 1) * np.clip((1 - rr) / 0.2, 0, 1)
fx(gray3(v), 'spark_white', True)

xx, yy, rr, ang = grid(32)
d = np.abs(xx) + np.abs(yy) * 0.8
v = np.clip(np.exp(-d * 3.0) * 1.4 - 0.05, 0, 1) * np.clip((1 - d) / 0.15, 0, 1)
v = np.clip(v + 0.2 * np.exp(-rr * 3) * (rr < 1), 0, 1)
fx(gray3(v), 'ember_white', True)

# 충격파 링 (흰색)
xx, yy, rr, ang = grid(512)
a01 = (ang / (2 * np.pi)) % 1
an = periodic_noise(0, beta=2.0, lo=3, seed=46, shape=(8, 512))[4]
off = (an[(a01 * 511).astype(int)] - 0.5) * 0.05
band = np.exp(-((rr - 0.86 - off) / 0.055) ** 2)
sharp = np.exp(-((rr - 0.875 - off) / 0.018) ** 2) * 0.5
inner = np.exp(-((rr - 0.78) / 0.14) ** 2) * 0.3 + 0.06 * np.clip(1 - rr / 0.86, 0, 1)
v = np.clip(band + sharp + inner, 0, 1) * np.clip((0.995 - rr) / 0.04, 0, 1)
fx(gray3(v), 'shock_ring_white', True)

# 광선 단면 32x128 (세로 반복)
W, H = 32, 128
x = (np.arange(W) + 0.5) / W * 2 - 1
y = (np.arange(H) + 0.5) / H
core = np.exp(-(x / 0.12) ** 2)
soft = np.exp(-(x / 0.42) ** 2) * 0.55
edge = np.clip((1 - np.abs(x)) / 0.12, 0, 1)
prof = np.clip(core + soft, 0, 1) * edge
vn = periodic_noise(0, beta=2.0, lo=1, hi=12, seed=31, shape=(H, 4))[:, 0]
var = 0.82 + 0.1 * np.sin(2 * np.pi * 2 * y) + 0.16 * (vn - 0.5)
img = prof[None, :] * var[:, None]
img = np.maximum(img, core[None, :] * 0.95)  # 가운데 심은 항상 흰빛
fx(gray3(img), 'beam', False)

# 나뭇잎 32x32
SS = 8; R = 32 * SS
yy, xx = (np.mgrid[0:R, 0:R] + 0.5) / R * 2 - 1
c, s = np.cos(np.pi / 4), np.sin(np.pi / 4)
u = (xx * c - yy * s)  # 잎 축 (왼아래→오른위)
w = (xx * s + yy * c)
L = 0.82
tt = np.clip((u + L) / (2 * L), 0, 1)
halfw = 0.36 * np.sin(np.pi * tt) ** 0.8 * (1 - 0.25 * tt) + 0.0
wcurve = w - 0.08 * np.sin(np.pi * tt)  # 살짝 휜 잎
inside = (np.abs(wcurve) < halfw) & (np.abs(u) < L)
vein = (np.abs(wcurve) < 0.035) & inside
sidevein = inside & (np.abs(np.sin((u - np.abs(wcurve) * 0.9) * 14)) < 0.18) & (np.abs(wcurve) > 0.05) & (np.abs(wcurve) < halfw * 0.8)
stem = (np.abs(w + 0.0) < 0.04) & (u < -L + 0.02) & (u > -L - 0.15)
shade = 0.95 - 0.25 * (wcurve > 0)
leaf = inside * shade
leaf = np.where(vein | sidevein, 0.55, leaf)
leaf = np.maximum(leaf, stem * 0.8)
m = Image.fromarray((np.clip(leaf, 0, 1) * 255).astype(np.uint8)).resize((32, 32), Image.BOX)
v = np.asarray(m, float) / 255
halo = np.asarray(m.filter(ImageFilter.GaussianBlur(1.5)), float) / 255
v = np.clip(np.maximum(v, halo * 0.35), 0, 1)
fx(gray3(v), 'leaf', True)

# 물방울/거품 32x32
SS = 8; R = 32 * SS
xx, yy, rr, ang = grid(R)
rad = 0.82
rim = np.exp(-((rr - rad + 0.06) / 0.07) ** 2)
fill = 0.22 + 0.25 * (rr / rad) ** 3
hl = np.exp(-(((xx + 0.32) / 0.16) ** 2 + ((yy + 0.34) / 0.13) ** 2)) * 1.2
hl2 = np.exp(-(((xx - 0.3) / 0.1) ** 2 + ((yy - 0.36) / 0.1) ** 2)) * 0.45
v = np.where(rr < rad, np.maximum(fill, rim) + hl + hl2, 0)
v = np.clip(v, 0, 1)
m = Image.fromarray((v * 255).astype(np.uint8)).resize((32, 32), Image.BOX)
v = np.asarray(m, float) / 255
halo = np.asarray(m.filter(ImageFilter.GaussianBlur(1.2)), float) / 255
fx(gray3(np.maximum(v, halo * 0.3)), 'droplet', True)

# ---------------------------------------------------------------- 3) 플립북 시트 (8x8, 128px)
S, G = 128, 8
NF = G * G


def put(sheet, k, frame):
    gy, gx = divmod(k, G)
    sheet[gy * S:(gy + 1) * S, gx * S:(gx + 1) * S] = frame


# 3a) 플라즈마: 흰-청록 심 + 갈라지는 전기 가지
rng = np.random.default_rng(404)
sheet = np.zeros((S * G, S * G, 3))
xx, yy, rr, ang = grid(S)
pn = periodic_noise(256, beta=2.2, lo=2, hi=18, seed=41)
tint = lightning_ramp
for k in range(NF):
    ph = k / NF
    SSc = 4
    hi = Image.new('L', (S * SSc, S * SSc), 0)
    dr = ImageDraw.Draw(hi)

    def bolt(x0, y0, a0, length, width, depth):
        x, y, a = x0, y0, a0
        pts = [(x, y)]
        nseg = int(length / 8) + 2
        for i in range(nseg):
            a = a0 + rng.normal(0, 0.55)
            step = length / nseg
            x += np.cos(a) * step; y += np.sin(a) * step
            pts.append((x, y))
            if depth > 0 and rng.random() < 0.13:
                bolt(x, y, a + rng.choice([-1, 1]) * rng.uniform(0.4, 1.0), length * rng.uniform(0.3, 0.5), max(1, width * 0.55), depth - 1)
        dr.line([(px * SSc, py * SSc) for px, py in pts], fill=255, width=int(width * SSc))

    narc = rng.integers(5, 8)
    base = rng.uniform(0, 2 * np.pi)
    for i in range(narc):
        a0 = base + i * 2 * np.pi / narc + rng.normal(0, 0.3)
        L0 = rng.uniform(30, 52)
        bolt(64 + np.cos(a0) * 10, 64 + np.sin(a0) * 10, a0, L0, rng.uniform(1.4, 2.4), 2)
    arcs = np.asarray(hi.resize((S, S), Image.BOX), float) / 255
    arcglow = np.asarray(hi.resize((S, S), Image.BOX).filter(ImageFilter.GaussianBlur(3)), float) / 255
    pulse = 1 + 0.08 * np.sin(2 * np.pi * ph * 4)
    n = np.roll(np.roll(pn, int(ph * 256), 0), int(ph * 256 * 2), 1)[::2, ::2]
    core = np.exp(-(rr / (0.22 * pulse)) ** 2) + (n - 0.5) * 0.35 * np.exp(-(rr / 0.35) ** 2)
    halo = np.exp(-rr * 4.5) * 0.45
    v = np.clip(core + arcs * 0.95 + arcglow * 1.3 + halo, 0, 1)
    vc = cel(v, levels=(0.1, 0.3, 0.55, 0.8), soft=0.04) * 0.85 + v * 0.15
    col = tint(0.3 + vc * 0.7) * np.clip(vc * 1.3, 0, 1)[..., None]
    col = np.clip(col * 1.1, 0, 1) * np.clip((1 - rr) / 0.1, 0, 1)[..., None]
    put(sheet, k, col)
fx(sheet, 'plasma_sheet', True)

# 3b) 바람 소용돌이: 혜성 모양 초승달 줄기 3개가 돈다 (머리는 굵고 꼬리는 가늘게)
sheet = np.zeros((S * G, S * G, 3))
xx, yy, rr, ang = grid(S)
yq = yy / 0.9  # 살짝 납작한 타원 궤도
rq = np.sqrt(xx ** 2 + yq ** 2); aq = np.arctan2(yq, xx)
arms = [(0.0, 0.66, 2.7, 1.0), (2 * np.pi / 3, 0.5, 2.3, 0.85), (4 * np.pi / 3, 0.78, 2.0, 0.7)]
wn = periodic_noise(256, beta=2.0, lo=2, hi=20, seed=52)
for k in range(NF):
    ph = k / NF
    rot = 2 * np.pi * ph * 2  # 64프레임에 두 바퀴 → 루프
    v = np.zeros_like(rr)
    for a0, rad, span, br in arms:
        head = a0 + rot
        phi = np.mod(head - aq + 0.25, 2 * np.pi) - 0.25  # 머리 앞쪽 약간 음수 허용
        q = phi / span
        ok = (q > -0.1) & (q < 1)
        qc = np.clip(q, 0, 1)
        rc = rad * (1 - 0.2 * qc) + 0.03 * np.sin(2 * np.pi * (ph * 4 + qc))
        w = 0.012 + 0.085 * (1 - qc) ** 1.6
        # 머리 둥글게: q<0 쪽은 각도 거리로 빠르게 사라짐
        headcap = np.where(q < 0, np.exp(-((q * span * rad) / 0.06) ** 2), 1.0)
        bright = br * (1 - qc) ** 1.1 * headcap
        main = np.exp(-((rq - rc) / w) ** 2) * bright
        inner = np.exp(-((rq - rc * 0.82) / (w * 0.35 + 0.006)) ** 2) * bright * 0.45 * (qc > 0.08)
        v = np.maximum(v, (main + inner) * ok)
    n = np.roll(wn, int(ph * 256), 1)[::2, ::2]
    blurv = np.asarray(Image.fromarray(np.clip(v * 255, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(4)), float) / 255
    v = np.clip(v * (0.85 + 0.3 * n) + blurv * 0.7, 0, 1)
    vc = cel(v, levels=(0.08, 0.3, 0.6, 0.85), soft=0.05) * 0.7 + v * 0.3
    col = wind_ramp(0.5 + vc * 0.5) * vc[..., None]
    put(sheet, k, col * np.clip((1 - rr) / 0.08, 0, 1)[..., None])
fx(sheet, 'wisp_sheet', True)

# 3c) 물 튀김 왕관: 프레임 0 작게 → 63 크고 희미하게
sheet = np.zeros((S * G, S * G, 3))
xx, yy, rr, ang = grid(S)
rng = np.random.default_rng(77)
nsp = 14
sp_ang = np.sort(rng.uniform(0, 2 * np.pi, nsp))
sp_len = rng.uniform(0.6, 1.0, nsp)
drops = [(rng.uniform(0, 2 * np.pi), rng.uniform(0.9, 1.45), rng.uniform(1.2, 2.6)) for _ in range(22)]
an = periodic_noise(0, beta=2.0, lo=2, hi=24, seed=78, shape=(8, 512))[3]
for k in range(NF):
    ph = (k + 0.5) / NF
    e = 1 - (1 - ph) ** 2.2  # 처음엔 빠르게 퍼지고 나중엔 느리게
    R0 = 0.1 + 0.52 * e
    fade = (1 - ph) ** 0.9
    a01 = (ang / (2 * np.pi)) % 1
    wob = (an[(a01 * 511).astype(int)] - 0.5) * 0.08
    ring = np.exp(-((rr - R0 - wob * e) / (0.025 + 0.03 * e)) ** 2)
    # 왕관 뾰족이: 각도 방향으로 좁은 가시
    spikes = np.zeros_like(rr)
    for a_, l_ in zip(sp_ang, sp_len):
        da = np.angle(np.exp(1j * (ang - a_)))
        tip = R0 + (0.06 + 0.22 * l_) * (0.3 + 0.7 * np.sin(np.pi * min(1, ph * 1.6)))
        along = np.clip((rr - R0 + 0.03) / (tip - R0 + 0.03), 0, 1)
        width = (0.11 * (1 - along) ** 1.2 + 0.012) / np.maximum(rr, 0.05)
        sp = np.exp(-(da / width) ** 2) * (rr > R0 - 0.03) * (rr < tip)
        spikes = np.maximum(spikes, sp * (0.7 + 0.3 * (1 - along)))
        # 가시 끝 물방울
        bx, by = np.cos(a_) * (tip + 0.04), np.sin(a_) * (tip + 0.04)
        spikes = np.maximum(spikes, np.exp(-(((xx - bx) ** 2 + (yy - by) ** 2) / (0.03 + 0.012 * l_) ** 2)) * 0.95)
    dropv = np.zeros_like(rr)
    for a_, sp_, sz in drops:
        dr_ = R0 * sp_ + 0.1 * e
        if dr_ > 0.95: continue
        bx, by = np.cos(a_) * dr_, np.sin(a_) * dr_
        d2 = np.sqrt((xx - bx) ** 2 + (yy - by) ** 2) / (sz * 0.012 * (0.8 + 0.6 * e))
        dropv = np.maximum(dropv, np.clip(1.2 - d2, 0, 1) * 0.6 + np.exp(-((d2 - 0.9) / 0.25) ** 2) * 0.6)
    inner = np.clip(1 - rr / max(R0, 0.01), 0, 1) ** 0.6 * 0.18 * (1 - e) + np.exp(-(rr / 0.15) ** 2) * 0.5 * (1 - ph) ** 3
    v = np.clip(ring * 0.95 + spikes + dropv + inner, 0, 1) * fade
    vc = cel(v, levels=(0.06, 0.22, 0.45, 0.7), soft=0.03) * 0.75 + v * 0.25
    col = water_ramp(0.45 + vc * 0.55) * vc[..., None]
    col *= np.clip((1 - rr) / 0.06, 0, 1)[..., None]
    put(sheet, k, np.clip(col * 1.15, 0, 1))
fx(sheet, 'splash_sheet', True)

# 3d) 안개 (투명 배경, 흰색)
sheet = np.zeros((S * G, S * G, 4))
xx, yy, rr, ang = grid(S)
base = periodic_noise(256, beta=2.6, lo=2, hi=20, seed=88)
det = periodic_noise(256, beta=2.0, lo=6, hi=40, seed=89)
for k in range(NF):
    ph = k / NF
    n = np.roll(np.roll(base, int(ph * 256), 0), int(ph * 128), 1)[::2, ::2]
    dn = np.roll(det, int(ph * 256), 1)[::2, ::2]
    rad = 0.55 + 0.38 * ph
    puff = np.clip(1 - rr / rad, 0, 1) ** 0.8
    a = np.clip(puff * (0.75 + n * 1.0 + (dn - 0.5) * 0.3) - 0.12, 0, 1)
    a *= np.clip(ph / 0.08, 0, 1) ** 0.5 * (1 - ph) ** 0.7
    a = np.clip(a, 0, 1) * np.clip((1 - rr) / 0.08, 0, 1)
    shade = 0.84 + 0.14 * n
    gy, gx = divmod(k, G)
    sheet[gy * S:(gy + 1) * S, gx * S:(gx + 1) * S] = np.dstack([shade, shade, shade, a * 0.85])
fx(sheet, 'mist_sheet', True, 'RGBA')

# ---------------------------------------------------------------- 4) 스킬 아이콘 64x64
IS = 64; SC = 4  # 4배로 그린 뒤 줄여서 계단 현상 제거


class D:
    """64 좌표로 받아서 256 캔버스에 그린다."""
    def __init__(self):
        self.im = Image.new('L', (IS * SC, IS * SC), 0)
        self.d = ImageDraw.Draw(self.im)

    def P(self, pts):
        return [(x * SC, y * SC) for x, y in pts]

    def poly(self, pts, f=255):
        self.d.polygon(self.P(pts), fill=int(f))

    def ell(self, cx, cy, rx, ry=None, f=255, outline=None, w=0):
        ry = rx if ry is None else ry
        box = [(cx - rx) * SC, (cy - ry) * SC, (cx + rx) * SC, (cy + ry) * SC]
        if outline is not None:
            self.d.ellipse(box, outline=int(outline), width=int(w * SC))
        else:
            self.d.ellipse(box, fill=int(f))

    def line(self, pts, w, f=255):
        self.d.line(self.P(pts), fill=int(f), width=int(w * SC), joint='curve')
        r = w * SC / 2
        for x, y in self.P([pts[0], pts[-1]]):
            self.d.ellipse([x - r, y - r, x + r, y + r], fill=int(f))

    def arc(self, cx, cy, r, a0, a1, w, f=255, ry=None):
        ry = r if ry is None else ry
        self.d.arc([(cx - r) * SC, (cy - ry) * SC, (cx + r) * SC, (cy + ry) * SC], a0, a1, fill=int(f), width=int(w * SC))

    def taper(self, pts, w0, w1, f=255):
        """굵기가 w0 → w1 로 변하는 곡선 (원을 촘촘히 찍음)."""
        pts = np.array(pts, float)
        seg = np.sqrt((np.diff(pts, axis=0) ** 2).sum(1))
        tot = seg.sum(); cum = np.concatenate([[0], np.cumsum(seg)])
        n = int(tot * 3) + 2
        for s_ in np.linspace(0, tot, n):
            i = min(np.searchsorted(cum, s_, side='right') - 1, len(seg) - 1)
            q = (s_ - cum[i]) / max(seg[i], 1e-6)
            x, y = pts[i] + (pts[i + 1] - pts[i]) * q
            w = w0 + (w1 - w0) * s_ / tot
            self.ell(x, y, w / 2, f=f)

    def mask(self):
        return self.im.resize((IS, IS), Image.BOX)


ELEM = {
    'fire': dict(ramp=fire_ramp, bg=(0.10, 0.045, 0.035), glow=(1.0, 0.38, 0.06)),
    'lightning': dict(ramp=lightning_ramp, bg=(0.045, 0.04, 0.11), glow=(0.35, 0.55, 1.0)),
    'wind': dict(ramp=wind_ramp, bg=(0.03, 0.08, 0.075), glow=(0.3, 0.95, 0.7)),
    'water': dict(ramp=water_ramp, bg=(0.025, 0.055, 0.12), glow=(0.2, 0.55, 1.0)),
}


def blur(img, r):
    return np.asarray(img.filter(ImageFilter.GaussianBlur(r)), float) / 255


def make_icon(name, elem, fn, ult=False):
    e = ELEM[elem]
    d = D(); fn(d)
    m_img = d.mask()
    M = np.asarray(m_img, float) / 255
    cover = np.asarray(m_img.point(lambda p: 255 if p > 0 else 0).resize((IS, IS)), float) / 255
    cover = np.clip(M * 4, 0, 1)  # 덮임 정도 (회색 칠도 완전 불투명)
    b3 = blur(m_img, 2.5)
    g6 = blur(m_img, 5)
    yy, xx = np.mgrid[0:IS, 0:IS]
    ny = 1 - (yy + 0.5) / IS
    heat = np.clip(0.1 + M * 0.56 + b3 * 0.3 + ny * 0.06, 0, 1)
    col = e['ramp'](heat)
    # 배경: 원소색이 비치는 어두운 원형 비네트
    r = np.sqrt(((xx + 0.5 - IS / 2) / (IS / 2)) ** 2 + ((yy + 0.5 - IS / 2) / (IS / 2)) ** 2)
    bg = np.zeros((IS, IS, 3)) + np.array(e['bg'])
    bg += np.clip(0.42 - r * 0.38, 0, 1)[..., None] * np.array(e['glow']) * 0.55
    bg *= np.clip(1.15 - r * 0.35, 0, 1)[..., None]
    # 어두운 외곽선 (작게 줄여도 모양이 읽히게)
    outline = np.clip(blur(Image.fromarray((np.clip(cover, 0, 1) * 255).astype(np.uint8)), 1.1) * 2.2, 0, 1)
    rgb = bg + g6[..., None] * np.array(e['glow']) * 0.75
    rgb = rgb * (1 - outline[..., None] * 0.8)
    rgb = rgb * (1 - cover[..., None]) + col * cover[..., None]
    rgb = np.clip(rgb, 0, 1)
    if ult:
        # 금테 (둥근 사각형), 위는 밝고 아래는 짙은 금색
        fr = Image.new('L', (IS * SC, IS * SC), 0); fd = ImageDraw.Draw(fr)
        fd.rounded_rectangle([1 * SC, 1 * SC, (IS - 1) * SC - 1, (IS - 1) * SC - 1], radius=9 * SC, outline=255, width=int(3.2 * SC))
        hl = Image.new('L', (IS * SC, IS * SC), 0); hd = ImageDraw.Draw(hl)
        hd.rounded_rectangle([2.2 * SC, 2.2 * SC, (IS - 2.2) * SC, (IS - 2.2) * SC], radius=8 * SC, outline=255, width=int(0.9 * SC))
        F = np.asarray(fr.resize((IS, IS), Image.BOX), float)[..., None] / 255
        Hh = np.asarray(hl.resize((IS, IS), Image.BOX), float)[..., None] / 255
        # 금테 바깥 모서리는 투명하게 → 둥근 아이콘
        outside = Image.new('L', (IS * SC, IS * SC), 0); od = ImageDraw.Draw(outside)
        od.rounded_rectangle([1 * SC, 1 * SC, (IS - 1) * SC - 1, (IS - 1) * SC - 1], radius=9 * SC, fill=255)
        alpha = np.asarray(outside.resize((IS, IS), Image.BOX), float) / 255
        gt = (ny[..., None] * 0.7 + 0.3)
        diag = 0.5 + 0.5 * np.sin((xx + yy)[..., None] / IS * np.pi * 2.0)
        gold = np.array([0.55, 0.32, 0.06]) * (1 - gt) + np.array([1.0, 0.85, 0.38]) * gt
        gold = gold * (0.85 + 0.25 * diag)
        rgb = rgb * (1 - F) + gold * F
        rgb = rgb * (1 - Hh * 0.8) + np.array([1, 0.97, 0.8]) * Hh * 0.8
        # 금테 안쪽에 은은한 금빛 번짐
        inner = blur(fr.resize((IS, IS), Image.BOX), 2.5)[..., None] * (1 - F)
        rgb = rgb + inner * np.array([0.9, 0.6, 0.15]) * 0.35
        # 네 모서리 반짝임
        for cx, cy in [(6, 6), (57, 6), (6, 57), (57, 57)]:
            sp = np.exp(-(((xx - cx) / 1.0) ** 2 + ((yy - cy) / 1.0) ** 2)) + 0.5 * np.exp(-(np.abs(xx - cx) * 1.5 + np.abs(yy - cy) * 6)) + 0.5 * np.exp(-(np.abs(yy - cy) * 1.5 + np.abs(xx - cx) * 6))
            rgb = rgb + sp[..., None] * np.array([1, 0.95, 0.7]) * 0.8 * (1 - cover[..., None])
        rgb = np.clip(rgb, 0, 1)
    else:
        alpha = np.ones((IS, IS))
    save(np.dstack([rgb, alpha]), GUI + name + '.png', 'RGBA')


def zig(pts_in, rng, amp):
    out = [pts_in[0]]
    for (x0, y0), (x1, y1) in zip(pts_in[:-1], pts_in[1:]):
        out.append(((x0 + x1) / 2 + rng.uniform(-amp, amp), (y0 + y1) / 2 + rng.uniform(-amp, amp)))
        out.append((x1, y1))
    return out


# ---- 불
def i_fire_sun(d):
    cx, cy = 32, 32
    for i in range(16):
        a = i / 16 * 2 * np.pi + 0.1
        L = 27 if i % 2 == 0 else 21
        w = 0.2 if i % 2 == 0 else 0.17
        # 휘어진 불꽃 혀
        pts = [(cx + np.cos(a - w) * 12, cy + np.sin(a - w) * 12),
               (cx + np.cos(a + 0.12) * (L * 0.75), cy + np.sin(a + 0.12) * (L * 0.75)),
               (cx + np.cos(a + 0.22) * L, cy + np.sin(a + 0.22) * L),
               (cx + np.cos(a + w) * 12, cy + np.sin(a + w) * 12)]
        d.poly(pts, 175 if i % 2 else 215)
    d.ell(cx, cy, 15, f=200)
    d.ell(cx, cy, 12.5, f=255)


# ---- 번개
def bolt_poly(d, pts, w0, w1, f=255):
    d.taper(pts, w0, w1, f)


def i_lightning_bolt(d):
    d.taper([(27, 24), (16, 27), (11, 22), (4, 27)], 3.4, 1.2, 210)
    d.taper([(37, 30), (48, 35), (52, 44), (60, 46)], 3.4, 1.2, 210)
    d.taper([(24, 47), (14, 50), (8, 58)], 2.8, 1.0, 190)
    d.poly([(40, 2), (19, 31), (31, 31), (17, 62), (47, 25), (35, 25), (52, 2)], 255)


def i_lightning_blink(d):
    # 잔상 셋 (왼쪽일수록 흐림) + 화살촉
    for i, (x, f) in enumerate([(6, 90), (17, 150)]):
        d.poly([(x, 16), (x + 9, 16), (x + 21, 32), (x + 9, 48), (x, 48), (x + 12, 32)], f)
    d.poly([(28, 12), (40, 12), (58, 32), (40, 52), (28, 52), (44, 32)], 255)
    # 화살 속 작은 번개
    d.poly([(38, 18), (30, 34), (36, 34), (31, 46), (44, 28), (38, 28), (43, 18)], 0)
    d.poly([(39, 20), (32, 33), (37.5, 33), (33, 43), (42, 29), (36.5, 29), (41, 20)], 255)
    d.line([(2, 24), (10, 24)], 1.6, 120)
    d.line([(0, 40), (9, 40)], 1.6, 120)


def cloud(d, cx, cy, s, f):
    for ox, oy, r in [(-10, 2, 8), (-2, -4, 10), (9, -1, 9), (15, 4, 6.5), (-16, 5, 5.5)]:
        d.ell(cx + ox * s, cy + oy * s, r * s, f=f)
    d.poly([(cx - 18 * s, cy + 5 * s), (cx + 19 * s, cy + 5 * s), (cx + 19 * s, cy + 10 * s), (cx - 18 * s, cy + 10 * s)], f)
    d.ell(cx - 16 * s, cy + 6 * s, 4.2 * s, f=f)
    d.ell(cx + 17 * s, cy + 6 * s, 4.2 * s, f=f)


def i_lightning_strike(d):
    cloud(d, 32, 13, 1.05, 130)
    d.poly([(34, 20), (24, 37), (31, 38), (23, 56), (41, 33), (34, 32), (41, 20)], 255)
    d.ell(28, 58, 18, 3.5, f=170)
    for a in (-150, -120, -60, -30):
        ar = np.radians(a)
        d.taper([(23 + np.cos(ar) * 4, 56 + np.sin(ar) * 4), (23 + np.cos(ar) * 11, 56 + np.sin(ar) * 9)], 2.6, 0.8, 230)


def i_lightning_discharge(d):
    rng = np.random.default_rng(9)
    for i in range(8):
        a = i / 8 * 2 * np.pi + 0.3
        pts = [(32 + np.cos(a) * 11, 32 + np.sin(a) * 11)]
        for k in range(3):
            rr_ = 11 + (k + 1) * 6.5
            aa = a + (0.28 if k % 2 == 0 else -0.28)
            pts.append((32 + np.cos(aa) * rr_, 32 + np.sin(aa) * rr_))
        d.taper(pts, 3.6, 1.2, 225)
    d.ell(32, 32, 12.5, f=200)
    d.ell(32, 32, 10, f=255)


def i_lightning_storm(d):
    cloud(d, 32, 16, 1.2, 140)
    d.poly([(30, 25), (22, 38), (28, 39), (21, 54), (36, 35), (30, 34), (36, 25)], 255)
    d.poly([(16, 26), (11, 36), (15, 37), (10, 47), (20, 34), (16, 33), (20, 26)], 225)
    d.poly([(44, 26), (39, 37), (44, 38), (40, 50), (50, 35), (45, 34), (49, 26)], 225)
    for x, y in [(52, 52), (14, 54), (32, 58)]:
        d.ell(x, y, 1.6, f=180)


# ---- 바람
def i_wind_blade(d):
    tmp = D()
    tmp.ell(30, 32, 26, f=255)
    tmp.ell(20, 32, 23, 24, f=0)
    tm = np.asarray(tmp.im, np.uint8)
    d.im.paste(255, mask=Image.fromarray(tm))
    d.line([(4, 22), (20, 22)], 2.0, 140)
    d.line([(2, 32), (18, 32)], 2.0, 170)
    d.line([(4, 42), (20, 42)], 2.0, 140)


def i_wind_dash(d):
    for i, (x, f) in enumerate([(8, 110), (22, 175), (36, 255)]):
        d.line([(x, 14), (x + 16, 32), (x, 50)], 6.5, f)
    d.line([(4, 32), (12, 32)], 2.2, 120)


def i_wind_whirlwind(d):
    rows = [(10, 22, 34), (18, 18, 32), (26, 14, 31), (34, 10, 33), (41, 7, 35), (48, 5, 34), (54, 3, 32)]
    for i, (y, rx, cx) in enumerate(rows):
        d.arc(cx, y, rx, 0, 360, 3.2 - i * 0.1, 255 if i < 4 else 220, ry=max(rx * 0.32, 1.6))
    d.ell(31, 58, 2.3, f=220)
    d.line([(50, 24), (58, 22)], 1.6, 140)
    d.line([(6, 34), (14, 36)], 1.6, 140)


def i_wind_burst(d):
    d.ell(32, 32, 6.5, f=255)
    for k, (r, w, f) in enumerate([(13, 3.6, 240), (21, 3.2, 200), (29, 2.8, 150)]):
        for a0 in (0, 120, 240):
            a0 += k * 25 - 30
            d.arc(32, 32, r, a0, a0 + 80, w, f)


def i_wind_tempest(d):
    def body(q):
        y = 7 + q * 49
        cx = 32 + 6 * np.sin(q * 3.6 + 0.3) * q
        hw = 25 * (1 - q) ** 1.5 + 2.5
        return y, cx, hw
    pts_l, pts_r = [], []
    for i in range(40):
        y, cx, hw = body(i / 39)
        pts_l.append((cx - hw, y)); pts_r.append((cx + hw, y))
    d.poly(pts_l + pts_r[::-1], 105)
    for i in range(9):
        q = (i + 0.4) / 9
        y, cx, hw = body(q)
        d.arc(cx, y, hw, 10, 180, 3.0 - q * 1.2, 255, ry=max(hw * 0.3, 1.2))
        d.arc(cx, y, hw * 0.92, 190, 300, 1.6, 200, ry=max(hw * 0.3, 1.2))
    d.ell(32, 7, 25, 5, f=235)
    d.ell(32, 7, 20, 2.8, f=110)
    y, cx, hw = body(1.0)
    d.arc(cx, y + 2, 14, 200, 520, 2.0, 200, ry=3)
    for x, y, r in [(7, 38, 2.0), (56, 28, 2.2), (10, 52, 1.6), (54, 46, 1.8)]:
        d.ell(x, y, r, f=210)


# ---- 물
def i_water_bullet(d):
    for y, x0, f in [(22, 6, 150), (32, 2, 190), (42, 8, 150)]:
        d.taper([(x0, y), (24, y)], 1.4, 3.6, f)
    d.ell(39, 32, 18, f=200)
    d.ell(39, 32, 15.5, f=255)
    d.ell(33, 26, 4.5, 3.5, f=150)
    d.ell(46, 40, 2.2, f=170)
    # 위로 튀는 작은 물방울
    d.ell(18, 12, 4, f=235)
    d.poly([(14.6, 11), (19, 2), (21.4, 11)], 235)


def i_water_jet(d):
    d.poly([(3, 28), (48, 18), (54, 32), (48, 46), (3, 36)], 150)
    d.taper([(3, 32), (52, 32)], 4.5, 13, 255)
    d.taper([(10, 27), (46, 22)], 1.2, 2.2, 230)
    d.taper([(10, 37), (46, 42)], 1.2, 2.2, 230)
    for a in (-70, -40, -10, 20, 50, 75):
        ar = np.radians(a)
        d.taper([(54 + np.cos(ar) * 3, 32 + np.sin(ar) * 9), (56 + np.cos(ar) * 8, 32 + np.sin(ar) * 22)], 3.2, 1.2, 225)
    for x, y, r in [(60, 14, 1.8), (61, 50, 1.8), (62, 32, 1.6)]:
        d.ell(x, y, r, f=230)


def i_water_prison(d):
    d.ell(32, 32, 26, f=95)
    d.ell(32, 32, 26, outline=255, w=3.6)
    d.arc(32, 32, 15, 200, 470, 3.2, 230)
    d.arc(32, 32, 8, 40, 300, 2.8, 200)
    d.arc(32, 32, 21, 205, 250, 3.0, 255)
    d.ell(19, 18, 3, f=255)
    d.ell(47, 50, 2.2, f=200)


def i_water_wave(d):
    # 바닥 물결
    base = [(0, 50)] + [(x, 50 + 2.5 * np.sin(x * 0.25)) for x in range(0, 65, 2)] + [(64, 64), (0, 64)]
    d.poly(base, 150)
    # 말려 드는 물마루: 안쪽 끝에서 바깥으로 굵어지는 나선 획
    pts = []
    for i in range(60):
        q = i / 59
        th = np.radians(225 - q * 270)  # 왼쪽 안쪽 → 위 → 오른쪽 아래
        r_ = 5 + q * 17
        pts.append((30 + np.cos(th) * r_, 30 - np.sin(th) * r_))
    pts += [(52, 50), (61, 55)]
    d.taper(pts, 3, 16, 235)
    d.taper(pts[20:52], 1.6, 3.2, 255)
    d.ell(pts[0][0], pts[0][1], 2.6, f=255)
    for x, y, r in [(13, 24, 2.0), (10, 32, 1.6), (15, 15, 1.5), (8, 42, 1.4)]:
        d.ell(x, y, r, f=230)


def i_water_dragon(d):
    # S자 몸통 (꼬리 → 머리)
    pts = []
    for i in range(40):
        q = i / 39
        y = 58 - q * 40
        x = 32 + 15 * np.sin(q * np.pi * 2.0 + 0.4) * (0.6 + 0.4 * q)
        pts.append((x, y))
    d.taper(pts, 3, 14, 220)
    hx, hy = pts[-1]
    # 머리: 둥근 머리 + 앞으로 뻗은 주둥이 + 뿔
    d.ell(hx, hy - 3, 9, 8, f=255)
    d.poly([(hx + 2, hy - 8), (hx + 18, hy - 5), (hx + 17, hy - 1), (hx + 4, hy + 2)], 255)
    d.poly([(hx + 4, hy + 1), (hx + 16, hy + 2), (hx + 13, hy + 5), (hx + 3, hy + 4)], 200)
    d.poly([(hx - 3, hy - 8), (hx - 10, hy - 17), (hx - 1, hy - 10)], 230)
    d.poly([(hx + 1, hy - 9), (hx - 2, hy - 19), (hx + 4, hy - 10)], 230)
    d.ell(hx + 4, hy - 5, 1.5, f=0)
    # 등 지느러미
    for q in (0.3, 0.5, 0.7):
        i = int(q * 39); x, y = pts[i]
        d.poly([(x - 3, y), (x + 1, y - 6), (x + 3, y + 1)], 200)
    for x, y, r in [(52, 54, 2.2), (10, 50, 1.8), (56, 44, 1.5)]:
        d.ell(x, y, r, f=220)


ICONS = [
    ('fire_sun', 'fire', i_fire_sun, True),
    ('lightning_bolt', 'lightning', i_lightning_bolt, False),
    ('lightning_blink', 'lightning', i_lightning_blink, False),
    ('lightning_strike', 'lightning', i_lightning_strike, False),
    ('lightning_discharge', 'lightning', i_lightning_discharge, False),
    ('lightning_storm', 'lightning', i_lightning_storm, True),
    ('wind_blade', 'wind', i_wind_blade, False),
    ('wind_dash', 'wind', i_wind_dash, False),
    ('wind_whirlwind', 'wind', i_wind_whirlwind, False),
    ('wind_burst', 'wind', i_wind_burst, False),
    ('wind_tempest', 'wind', i_wind_tempest, True),
    ('water_bullet', 'water', i_water_bullet, False),
    ('water_jet', 'water', i_water_jet, False),
    ('water_prison', 'water', i_water_prison, False),
    ('water_wave', 'water', i_water_wave, False),
    ('water_dragon', 'water', i_water_dragon, True),
]
for n, e, f, u in ICONS:
    make_icon(n, e, f, u)

# ---------------------------------------------------------------- 5) 열매 아이템 16x16 (도트, 흐림 없음)
# 몸통은 수식으로, 꼭지/잎은 손으로 찍는다.
TOP = [
    "................",
    "..........gg....",
    "......k..gGgg...",
    ".......kkgg.....",
    ".........k......",
]
# 소용돌이 무늬 (손으로 찍은 말린 곡선 두 개 + 작은 하나)
SWIRL = [
    "................",
    "................",
    "................",
    "................",
    "................",
    "...sss..........",
    "..s...s...sss...",
    "..s.s.s..s...s..",
    "..s..s...s.s.s..",
    "...s.....s..s...",
    "....sss...s.....",
    "...s...s...sss..",
    "...s.s.s........",
    "...s..s.........",
    "................",
    "................",
]
FRUIT = {
    #          외곽선          어두움            중간             밝음             하이라이트        소용돌이(어두움)   소용돌이(밝음)
    'fire': [(70, 12, 10), (150, 28, 20), (205, 48, 28), (238, 92, 45), (255, 220, 170), (220, 120, 20), (255, 196, 50)],
    'lightning': [(26, 22, 70), (55, 62, 160), (75, 110, 225), (120, 165, 255), (230, 245, 255), (215, 165, 20), (255, 232, 70)],
    'wind': [(14, 52, 40), (55, 150, 110), (100, 205, 155), (155, 238, 195), (235, 255, 245), (18, 80, 58), (30, 115, 80)],
    'water': [(10, 26, 70), (25, 70, 170), (40, 120, 225), (90, 180, 255), (225, 245, 255), (125, 215, 250), (205, 250, 255)],
}
STEM = [(60, 40, 24), (110, 75, 40)]
LEAF = [(40, 110, 40), (90, 175, 70)]
cx, cy, R0 = 7.5, 9.5, 6.3
for name, (ol, dk, md, lt, hl, sw, swl) in FRUIT.items():
    img = np.zeros((16, 16, 4), np.uint8)
    inside = np.zeros((16, 16), bool)
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            # 위가 살짝 꺼진 사과 같은 모양
            dyy = dy * (1.0 if dy > 0 else 1.06)
            r = np.hypot(dx, dyy)
            inside[y, x] = r < R0
    for y in range(16):
        for x in range(16):
            if not inside[y, x]: continue
            edge = not (inside[y - 1, x] and inside[(y + 1) % 16, x] and inside[y, x - 1] and inside[y, (x + 1) % 16]) or y == 15
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            if edge:
                c = ol
            else:
                nz = np.sqrt(max(R0 ** 2 - dx * dx - dy * dy, 0)) / R0
                n = np.array([dx / R0, dy / R0, nz])
                L = np.array([-0.55, -0.6, 0.58]); L /= np.linalg.norm(L)
                s = n @ L
                c = dk if s < 0.15 else (md if s < 0.55 else lt)
                # 소용돌이 무늬 (소용돌이 두 개)
                if SWIRL[y][x] == 's':
                    c = sw if s < 0.3 else swl
                if (x, y) in [(4, 6), (5, 6), (4, 7)]:
                    c = hl
            img[y, x] = (*c, 255)
    for y, row in enumerate(TOP):
        for x, ch in enumerate(row):
            if ch == 'k': img[y, x] = (*STEM[0], 255)
            if ch == 'g': img[y, x] = (*LEAF[0], 255)
            if ch == 'G': img[y, x] = (*LEAF[1], 255)
    # 꼭지가 몸통에 닿는 곳
    img[3, 7] = (*STEM[1], 255) if img[3, 7, 3] == 0 else img[3, 7]
    Image.fromarray(img, 'RGBA').save(ITEM + f'{name}_fruit.png')

print('ok')
