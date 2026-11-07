"""천둥 능력 에셋: 색 단계표, 룬 원·금 간 땅 텍스처, 스킬 아이콘 5개, 열매, 효과음 6개.
사용법: python3 make_thunder_assets.py <프로젝트 루트>
(make_v4_textures.py / make_v4_sounds.py 와 같은 방식. 다른 능력 파일은 건드리지 않는다.)
- 더하기 섞기 텍스처: 검은 배경 RGB
- 소리: 모노 48kHz ogg vorbis q5, 최고 -1dBFS
"""
import sys, os, json, subprocess
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy import signal
from scipy.io import wavfile

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
A = ROOT + '/src/main/resources/assets/blackocean_skills/'
FX = A + 'textures/fx/'
GUI = A + 'textures/gui/skills/'
ITEM = A + 'textures/item/'
SD = A + 'sounds/skill/'
for d in (FX, GUI, ITEM, SD + 'thunder'):
    os.makedirs(d, exist_ok=True)


# ================================================================ 공용 (텍스처)
def periodic_noise(n, beta=2.0, lo=2, hi=None, seed=0, shape=None):
    h, w = shape or (n, n)
    r = np.random.default_rng(seed)
    F = np.fft.fft2(r.standard_normal((h, w)))
    fy = np.fft.fftfreq(h)[:, None] * h
    fx_ = np.fft.fftfreq(w)[None, :] * w
    f = np.sqrt(fx_ ** 2 + fy ** 2); f[0, 0] = 1
    amp = 1 / f ** (beta / 2)
    amp[f < lo] = 0
    if hi: amp[f > hi] = 0
    x = np.real(np.fft.ifft2(F * amp))
    return (x - x.min()) / (x.max() - x.min())


def make_ramp(cols):
    cols = np.array(cols[::-1], float)
    stops = np.linspace(0, 1, len(cols))
    def f(t):
        t = np.clip(t, 0, 1)
        return np.stack([np.interp(t, stops, cols[:, i]) for i in range(3)], -1)
    return f


# 흰빛 → 옅은 금 → 금 → 호박색 → 보랏빛 가장자리 → 짙은 보라
thunder_ramp = make_ramp([(1, 1, 1), (1, 0.96, 0.72), (1, 0.8, 0.28), (0.96, 0.5, 0.1), (0.55, 0.22, 0.85), (0.07, 0.02, 0.16)])


def save(arr, path, mode='RGB', clamp=None):
    a = np.clip(arr * 255 + 0.5, 0, 255).astype(np.uint8)
    Image.fromarray(a, mode).save(path)
    if clamp is not None:
        with open(path + '.mcmeta', 'w') as f:
            json.dump({"texture": {"blur": True, "clamp": clamp}}, f)


def gray3(v):
    v = np.clip(v, 0, 1)
    return np.stack([v, v, v], -1)


def grid(R):
    yy, xx = (np.mgrid[0:R, 0:R] + 0.5) / R * 2 - 1
    return xx, yy, np.sqrt(xx ** 2 + yy ** 2), np.arctan2(yy, xx)


# ---------------------------------------------------------------- 1) 색 단계표
t = np.linspace(1, 0, 256)[None, :].repeat(8, 0)
save(thunder_ramp(t), FX + 'thunder_ramp.png', clamp=True)

# ---------------------------------------------------------------- 2) 룬 원 (512, 흰색 더하기)
BIG = 2048
im = Image.new('L', (BIG, BIG), 0)
d = ImageDraw.Draw(im)
C = BIG / 2


def circ(r, w, f=255):
    d.ellipse([C - r * C, C - r * C, C + r * C, C + r * C], outline=f, width=int(w))


def pol(r, a):
    return (C + np.cos(a) * r * C, C + np.sin(a) * r * C)


rng = np.random.default_rng(1207)
circ(0.965, 22)
circ(0.925, 7)
circ(0.70, 12)
circ(0.665, 5)
circ(0.36, 10)
circ(0.30, 5)
# 바깥 띠의 룬 글자 24개 (각 칸마다 다른 획)
N = 24
for i in range(N):
    a0 = 2 * np.pi * i / N
    span = 2 * np.pi / N * 0.62
    rin, rout = 0.735, 0.885
    strokes = rng.integers(2, 4)
    # 세로 줄기
    am = a0 + span * rng.uniform(0.35, 0.65)
    d.line([pol(rin, am), pol(rout, am)], fill=235, width=11)
    for _ in range(strokes):
        kind = rng.integers(0, 3)
        rr0 = rng.uniform(rin, rout)
        if kind == 0:  # 비스듬한 가지
            rr1 = np.clip(rr0 + rng.uniform(-0.07, 0.07), rin, rout)
            d.line([pol(rr0, am), pol(rr1, am + span * rng.choice([-0.5, 0.5]))], fill=235, width=9)
        elif kind == 1:  # 가로 획
            d.line([pol(rr0, am - span * 0.45), pol(rr0, am + span * 0.45)], fill=235, width=9)
        else:  # 작은 꺾쇠
            p0 = pol(rr0, am)
            p1 = pol(rr0 + 0.04, am + span * 0.4)
            p2 = pol(rr0 + 0.08, am)
            d.line([p0, p1, p2], fill=235, width=9, joint='curve')
    # 칸 사이 점
    d.ellipse([*(np.array(pol(0.81, a0 - np.pi / N * 0.05)) - 9), *(np.array(pol(0.81, a0 - np.pi / N * 0.05)) + 9)], fill=255)
# 육각별 (두 삼각형)
for k in range(2):
    pts = [pol(0.655, -np.pi / 2 + k * np.pi / 3 + j * 2 * np.pi / 3) for j in range(3)]
    d.line(pts + [pts[0]], fill=225, width=12, joint='curve')
# 꼭짓점의 작은 원
for j in range(6):
    p = pol(0.655, -np.pi / 2 + j * np.pi / 3)
    d.ellipse([p[0] - 30, p[1] - 30, p[0] + 30, p[1] + 30], outline=255, width=8)
# 바깥 눈금
for j in range(72):
    a = 2 * np.pi * j / 72
    L = 0.035 if j % 3 == 0 else 0.018
    d.line([pol(0.925, a), pol(0.925 - L, a)], fill=200, width=6)
# 가운데 번개 문양 (망치 머리 + 번개)
bolt = [(-0.06, -0.26), (0.10, -0.26), (0.02, -0.04), (0.12, -0.04), (-0.08, 0.27), (-0.01, 0.02), (-0.11, 0.02)]
d.polygon([(C + x * C, C + y * C) for x, y in bolt], fill=255)
for j in range(8):
    a = 2 * np.pi * j / 8 + np.pi / 8
    d.line([pol(0.38, a), pol(0.62, a)], fill=150, width=6)
lines = np.asarray(im.resize((512, 512), Image.BOX), float) / 255
glow = np.asarray(im.resize((512, 512), Image.BOX).filter(ImageFilter.GaussianBlur(6)), float) / 255
glow2 = np.asarray(im.resize((512, 512), Image.BOX).filter(ImageFilter.GaussianBlur(18)), float) / 255
xx, yy, rr, ang = grid(512)
v = lines + glow * 0.9 + glow2 * 0.5 + 0.05 * np.clip(1 - rr, 0, 1)
v = np.clip(v, 0, 1) * np.clip((0.995 - rr) / 0.02, 0, 1)
save(gray3(v), FX + 'thunder_rune.png', clamp=True)

# ---------------------------------------------------------------- 3) 금 간 땅 (512, 흰색 더하기)
BIG = 2048
im = Image.new('L', (BIG, BIG), 0)
d = ImageDraw.Draw(im)
C = BIG / 2
rng = np.random.default_rng(77)


def crack(x, y, a, length, w, depth):
    pts = [(x, y)]
    seg = 16 + rng.integers(0, 3)
    for i in range(seg):
        a += rng.normal(0, 0.28)
        step = length / seg
        x += np.cos(a) * step; y += np.sin(a) * step
        pts.append((x, y))
        r = np.hypot(x - C, y - C) / C
        if r > 0.97: break
    for i in range(len(pts) - 1):
        f = 1 - i / len(pts)
        d.line([pts[i], pts[i + 1]], fill=int(255 * (0.55 + 0.45 * f)), width=max(2, int(w * (0.35 + 0.65 * f))))
    if depth > 0:
        for _ in range(rng.integers(1, 3)):
            k = rng.integers(2, max(3, len(pts) - 2))
            bx, by = pts[k]
            crack(bx, by, a + rng.choice([-1, 1]) * rng.uniform(0.4, 0.9), length * rng.uniform(0.3, 0.55), w * 0.55, depth - 1)


M = 15
for i in range(M):
    a = 2 * np.pi * i / M + rng.uniform(-0.15, 0.15)
    r0 = 0.12
    crack(C + np.cos(a) * r0 * C, C + np.sin(a) * r0 * C, a, C * rng.uniform(0.62, 0.86), 30, 2)
# 가운데 패인 자리: 들쭉날쭉한 고리 + 짧은 금
pts = []
for j in range(60):
    a = 2 * np.pi * j / 60
    r = 0.14 + rng.uniform(-0.02, 0.02)
    pts.append((C + np.cos(a) * r * C, C + np.sin(a) * r * C))
d.line(pts + [pts[0]], fill=255, width=22, joint='curve')
for j in range(26):
    a = rng.uniform(0, 2 * np.pi)
    r0 = rng.uniform(0.02, 0.12)
    crack(C + np.cos(a) * r0 * C, C + np.sin(a) * r0 * C, a + rng.normal(0, 0.5), C * rng.uniform(0.05, 0.12), 12, 0)
small = im.resize((512, 512), Image.BOX)
lines = np.asarray(small, float) / 255
glow = np.asarray(small.filter(ImageFilter.GaussianBlur(4)), float) / 255
glow2 = np.asarray(small.filter(ImageFilter.GaussianBlur(14)), float) / 255
xx, yy, rr, ang = grid(512)
v = lines * 1.1 + glow * 1.0 + glow2 * 0.6 + np.exp(-rr * 7) * 0.5
v = np.clip(v, 0, 1) * np.clip((0.99 - rr) / 0.25, 0, 1)
save(gray3(v), FX + 'thunder_cracks.png', clamp=True)

# ---------------------------------------------------------------- 4) 스킬 아이콘 64x64 (make_v4_textures 와 같은 방식)
IS = 64; SC = 4


class D:
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


E = dict(ramp=thunder_ramp, bg=(0.075, 0.04, 0.11), glow=(1.0, 0.68, 0.16))


def blur(img, r):
    return np.asarray(img.filter(ImageFilter.GaussianBlur(r)), float) / 255


def make_icon(name, fn, ult=False):
    e = E
    d = D(); fn(d)
    m_img = d.mask()
    M = np.asarray(m_img, float) / 255
    cover = np.clip(M * 4, 0, 1)
    b3 = blur(m_img, 2.5)
    g6 = blur(m_img, 5)
    yy, xx = np.mgrid[0:IS, 0:IS]
    ny = 1 - (yy + 0.5) / IS
    heat = np.clip(0.1 + M * 0.56 + b3 * 0.3 + ny * 0.06, 0, 1)
    col = e['ramp'](heat)
    r = np.sqrt(((xx + 0.5 - IS / 2) / (IS / 2)) ** 2 + ((yy + 0.5 - IS / 2) / (IS / 2)) ** 2)
    bg = np.zeros((IS, IS, 3)) + np.array(e['bg'])
    bg += np.clip(0.42 - r * 0.38, 0, 1)[..., None] * np.array(e['glow']) * 0.55
    # 보랏빛 가장자리 (천둥의 보조색)
    bg += np.clip(r - 0.7, 0, 1)[..., None] * np.array([0.35, 0.12, 0.55]) * 0.5
    bg *= np.clip(1.15 - r * 0.35, 0, 1)[..., None]
    outline = np.clip(blur(Image.fromarray((np.clip(cover, 0, 1) * 255).astype(np.uint8)), 1.1) * 2.2, 0, 1)
    rgb = bg + g6[..., None] * np.array(e['glow']) * 0.75
    rgb = rgb * (1 - outline[..., None] * 0.8)
    rgb = rgb * (1 - cover[..., None]) + col * cover[..., None]
    rgb = np.clip(rgb, 0, 1)
    if ult:
        fr = Image.new('L', (IS * SC, IS * SC), 0); fd = ImageDraw.Draw(fr)
        fd.rounded_rectangle([1 * SC, 1 * SC, (IS - 1) * SC - 1, (IS - 1) * SC - 1], radius=9 * SC, outline=255, width=int(3.2 * SC))
        hl = Image.new('L', (IS * SC, IS * SC), 0); hd = ImageDraw.Draw(hl)
        hd.rounded_rectangle([2.2 * SC, 2.2 * SC, (IS - 2.2) * SC, (IS - 2.2) * SC], radius=8 * SC, outline=255, width=int(0.9 * SC))
        F = np.asarray(fr.resize((IS, IS), Image.BOX), float)[..., None] / 255
        Hh = np.asarray(hl.resize((IS, IS), Image.BOX), float)[..., None] / 255
        outside = Image.new('L', (IS * SC, IS * SC), 0); od = ImageDraw.Draw(outside)
        od.rounded_rectangle([1 * SC, 1 * SC, (IS - 1) * SC - 1, (IS - 1) * SC - 1], radius=9 * SC, fill=255)
        alpha = np.asarray(outside.resize((IS, IS), Image.BOX), float) / 255
        gt = (ny[..., None] * 0.7 + 0.3)
        diag = 0.5 + 0.5 * np.sin((xx + yy)[..., None] / IS * np.pi * 2.0)
        gold = np.array([0.55, 0.32, 0.06]) * (1 - gt) + np.array([1.0, 0.85, 0.38]) * gt
        gold = gold * (0.85 + 0.25 * diag)
        rgb = rgb * (1 - F) + gold * F
        rgb = rgb * (1 - Hh * 0.8) + np.array([1, 0.97, 0.8]) * Hh * 0.8
        inner = blur(fr.resize((IS, IS), Image.BOX), 2.5)[..., None] * (1 - F)
        rgb = rgb + inner * np.array([0.9, 0.6, 0.15]) * 0.35
        for cx, cy in [(6, 6), (57, 6), (6, 57), (57, 57)]:
            sp = np.exp(-(((xx - cx) / 1.0) ** 2 + ((yy - cy) / 1.0) ** 2)) + 0.5 * np.exp(-(np.abs(xx - cx) * 1.5 + np.abs(yy - cy) * 6)) + 0.5 * np.exp(-(np.abs(yy - cy) * 1.5 + np.abs(xx - cx) * 6))
            rgb = rgb + sp[..., None] * np.array([1, 0.95, 0.7]) * 0.8 * (1 - cover[..., None])
        rgb = np.clip(rgb, 0, 1)
    else:
        alpha = np.ones((IS, IS))
    save(np.dstack([rgb, alpha]), GUI + name + '.png', 'RGBA')


def rot(pts, a, cx, cy, ox=0, oy=0):
    c, s = np.cos(a), np.sin(a)
    return [(cx + (x - ox) * c - (y - oy) * s, cy + (x - ox) * s + (y - oy) * c) for x, y in pts]


def rect(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def hammer(d, cx, cy, a, s, head=255, handle=200, band=150):
    """(cx,cy) = 망치 머리 가운데, a = 기울기(라디안, 0 = 손잡이가 아래), s = 크기 배율."""
    def P(pts):
        return rot([(x * s, y * s) for x, y in pts], a, cx, cy)
    d.poly(P(rect(-3.2, 4, 3.2, 30)), handle)            # 손잡이
    d.poly(P(rect(-4.6, 29, 4.6, 34)), head)              # 끝 마개
    d.poly(P(rect(-4.2, 12, 4.2, 14.5)), band)            # 손잡이 감은 띠
    d.poly(P(rect(-4.2, 19, 4.2, 21.5)), band)
    d.poly(P(rect(-15, -8, 15, 6)), head)                 # 머리
    d.poly(P(rect(-18, -10, -12.5, 8)), head)             # 양 끝 마개
    d.poly(P(rect(12.5, -10, 18, 8)), head)
    d.poly(P(rect(-2.5, -9, 2.5, 7)), band)               # 가운데 띠
    d.poly(P([(-1.2, -6.5), (2.0, -6.5), (0.2, -1.5), (2.2, -1.5), (-1.6, 5), (-0.4, 0.2), (-2.4, 0.2)]), 255)


def zig(pts_in, rng, amp):
    out = [pts_in[0]]
    for (x0, y0), (x1, y1) in zip(pts_in[:-1], pts_in[1:]):
        out.append(((x0 + x1) / 2 + rng.uniform(-amp, amp), (y0 + y1) / 2 + rng.uniform(-amp, amp)))
        out.append((x1, y1))
    return out


def i_thunder_hammer(d):
    rng = np.random.default_rng(5)
    # 회전하며 날아가는 느낌의 호
    d.arc(30, 32, 27, 200, 330, 2.2, 120)
    d.arc(30, 32, 22, 210, 310, 1.6, 90)
    hammer(d, 34, 26, -0.6, 1.0)
    for p in ([(15, 14), (8, 9), (3, 12)], [(52, 16), (58, 9), (62, 11)], [(48, 40), (56, 46), (60, 44)]):
        d.taper(zig(p, rng, 2.0), 2.6, 0.8, 230)


def i_thunder_leap(d):
    rng = np.random.default_rng(8)
    # 포물선 궤적 (점점 굵어짐)
    pts = [(6 + 44 * q, 48 - 40 * 4 * q * (1 - q) * 0.95) for q in np.linspace(0, 0.82, 12)]
    d.taper(pts, 1.2, 4.0, 150)
    # 내려찍는 번개 화살
    d.poly([(44, 18), (54, 18), (50, 30), (56, 30), (44, 50), (46, 35), (40, 35)], 255)
    # 바닥 충격
    d.ell(46, 56, 16, 3.6, f=200)
    for a in (-160, -130, -50, -20):
        ar = np.radians(a)
        d.taper([(46 + np.cos(ar) * 6, 55 + np.sin(ar) * 3), (46 + np.cos(ar) * 15, 55 + np.sin(ar) * 9)], 2.4, 0.8, 230)
    d.taper(zig([(30, 58), (20, 61), (10, 59)], rng, 1.2), 1.8, 0.6, 170)


def i_thunderclap(d):
    rng = np.random.default_rng(12)
    # 부채꼴 충격파 (오른쪽 위로 퍼짐)
    for r, w, f in [(20, 3.0, 230), (30, 2.6, 190), (40, 2.2, 150)]:
        d.arc(14, 50, r, -70, -5, w, f)
    for a in (-60, -38, -16):
        ar = np.radians(a)
        d.taper(zig([(14 + np.cos(ar) * 9, 50 + np.sin(ar) * 9), (14 + np.cos(ar) * 46, 50 + np.sin(ar) * 46)], rng, 2.4), 2.6, 0.8, 215)
    # 바닥을 내려친 망치
    hammer(d, 16, 46, np.pi + 0.35, 0.72)
    d.ell(14, 56, 13, 3, f=210)


def i_thunder_roar(d):
    rng = np.random.default_rng(21)
    for r, w, f in [(13, 3.0, 235), (20, 2.6, 200), (27, 2.2, 160)]:
        d.arc(32, 32, r, -50, 50, w, f)
        d.arc(32, 32, r, 130, 230, w, f)
    for a in (-90, 90):
        ar = np.radians(a)
        d.taper(zig([(32 + np.cos(ar) * 10, 32 + np.sin(ar) * 10), (32 + np.cos(ar) * 29, 32 + np.sin(ar) * 29)], rng, 3.0), 3.0, 0.9, 225)
    d.ell(32, 32, 9.5, f=210)
    d.ell(32, 32, 7, f=255)
    d.poly([(31, 26), (35, 26), (33, 31), (36, 31), (30, 39), (31.5, 33), (29, 33)], 120)


def cloud(d, cx, cy, s, f):
    for ox, oy, r in [(-10, 2, 8), (-2, -4, 10), (9, -1, 9), (15, 4, 6.5), (-16, 5, 5.5)]:
        d.ell(cx + ox * s, cy + oy * s, r * s, f=f)
    d.poly([(cx - 18 * s, cy + 5 * s), (cx + 19 * s, cy + 5 * s), (cx + 19 * s, cy + 10 * s), (cx - 18 * s, cy + 10 * s)], f)


def i_thunder_judgment(d):
    rng = np.random.default_rng(31)
    cloud(d, 32, 11, 1.15, 120)
    # 빛기둥
    d.poly([(26, 18), (38, 18), (41, 56), (23, 56)], 95)
    # 머리를 아래로 내리꽂는 거대한 망치
    hammer(d, 32, 40, np.pi, 0.95)
    d.ell(32, 56, 22, 4, f=210)
    for a in (-170, -145, -35, -10):
        ar = np.radians(a)
        d.taper([(32 + np.cos(ar) * 9, 55 + np.sin(ar) * 3), (32 + np.cos(ar) * 26, 55 + np.sin(ar) * 10)], 2.4, 0.8, 230)
    for p in ([(14, 20), (9, 30), (12, 36)], [(50, 20), (55, 30), (52, 36)]):
        d.taper(zig(p, rng, 2.0), 2.4, 0.8, 220)


for n, f, u in [('thunder_hammer', i_thunder_hammer, False), ('thunder_leap', i_thunder_leap, False),
                ('thunderclap', i_thunderclap, False), ('thunder_roar', i_thunder_roar, False),
                ('thunder_judgment', i_thunder_judgment, True)]:
    make_icon(n, f, u)

# ---------------------------------------------------------------- 5) 열매 16x16 (make_v4_textures 와 같은 도트 방식)
TOP = [
    "................",
    "..........gg....",
    "......k..gGgg...",
    ".......kkgg.....",
    ".........k......",
]
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
#          외곽선        어두움          중간            밝음            하이라이트       소용돌이(어두움)  소용돌이(밝음)
PAL = [(48, 22, 64), (168, 104, 14), (222, 160, 26), (255, 212, 70), (255, 250, 222), (110, 52, 190), (178, 120, 255)]
STEM = [(60, 40, 24), (110, 75, 40)]
LEAF = [(40, 110, 40), (90, 175, 70)]
cx, cy, R0 = 7.5, 9.5, 6.3
ol, dk, md, lt, hl, sw, swl = PAL
img = np.zeros((16, 16, 4), np.uint8)
inside = np.zeros((16, 16), bool)
for y in range(16):
    for x in range(16):
        dx, dy = x + 0.5 - cx, y + 0.5 - cy
        dyy = dy * (1.0 if dy > 0 else 1.06)
        inside[y, x] = np.hypot(dx, dyy) < R0
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
img[3, 7] = (*STEM[1], 255) if img[3, 7, 3] == 0 else img[3, 7]
Image.fromarray(img, 'RGBA').save(ITEM + 'thunder_fruit.png')

# ================================================================ 6) 효과음
SR = 48000
rng = np.random.default_rng(5150)


def t_(sec): return np.arange(int(sec * SR)) / SR
def noise(sec): return rng.standard_normal(int(sec * SR))
def bp(x, lo, hi, o=2): return signal.sosfilt(signal.butter(o, [lo, hi], 'bandpass', fs=SR, output='sos'), x)
def lp(x, f, o=2): return signal.sosfilt(signal.butter(o, f, 'lowpass', fs=SR, output='sos'), x)
def hp(x, f, o=2): return signal.sosfilt(signal.butter(o, f, 'highpass', fs=SR, output='sos'), x)
def sat(x, k=1.5):
    x = x / (np.abs(x).max() + 1e-9)
    return np.tanh(x * k) / np.tanh(k)
def osc(freq): return 2 * np.pi * np.cumsum(freq) / SR
def saw(ph): return 2 * ((ph / (2 * np.pi)) % 1) - 1
def env_ad(t, a, d, p=1.0): return np.where(t < a, (t / max(a, 1e-6)) ** p, np.exp(-(t - a) / d))


def sweep(x, f0, f1, q=1.5, blocks=200):
    out = np.zeros_like(x); n = len(x); step = max(n // blocks, 1); zi = None
    for i in range(blocks):
        a, b = i * step, (n if i == blocks - 1 else (i + 1) * step)
        if a >= n: break
        f = np.clip(f0 * (f1 / f0) ** (i / (blocks - 1)), 30, SR / 2.3)
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
        x[i:i + L] += c * r.uniform(0.3, 1.0) * (amp_fn(pos) if amp_fn else 1)
    return x


def place(dst, src, at):
    i = int(at * SR)
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


def fade(x, fin=0.004, fout=0.04):
    a = int(fin * SR); b = int(fout * SR)
    x = x.copy(); x[:a] *= np.linspace(0, 1, a); x[-b:] *= np.linspace(1, 0, b) ** 1.5
    return x


def save_snd(x, name, fout=0.04):
    x = fade(x, fout=fout)
    x = x / (np.abs(x).max() + 1e-9) * 0.891
    w = f'{SD}thunder/{name}.wav'; o = w[:-4] + '.ogg'
    for _ in range(4):
        wavfile.write(w, SR, (x * 32767).astype(np.int16))
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', w, '-ac', '1', '-ar', str(SR), '-c:a', 'libvorbis', '-q:a', '5', o], check=True)
        dec = np.frombuffer(subprocess.run(['ffmpeg', '-v', 'error', '-i', o, '-f', 'f32le', '-'], capture_output=True, check=True).stdout, np.float32)
        pk = np.abs(dec).max()
        if pk <= 0.905: break
        x = x * (0.88 / pk)
    os.remove(w)
    print(f'thunder/{name}.ogg  {len(x) / SR:.2f}s  peak {20 * np.log10(pk):.1f} dB  rms {20 * np.log10(np.sqrt((dec ** 2).mean())):.1f} dB')


def crack_cluster(sec, seed=0, n=7, lo=500, hi=2400):
    """여러 갈래가 거의 동시에 갈라지는 '콰직'."""
    r = np.random.default_rng(seed)
    x = np.zeros(int(sec * SR))
    for k in range(n):
        at = r.uniform(0, 0.05) + (k > n - 3) * r.uniform(0.04, 0.12)
        L = int(r.uniform(0.08, 0.3) * SR)
        tt = np.arange(L) / SR
        c = hp(r.standard_normal(L), r.uniform(lo, hi)) * np.exp(-tt * r.uniform(14, 34))
        place(x, c * r.uniform(0.5, 1.0), at)
    return x


def rolling(sec, decay, cut=150, seed=1, speed=5):
    """멀리까지 구르는 우르릉 (불규칙한 진폭의 낮은 노이즈)."""
    r = np.random.default_rng(seed)
    t = t_(sec)
    base = lp(r.standard_normal(len(t)), cut, 4)
    base /= np.abs(base).max() + 1e-9
    modn = lp(r.standard_normal(len(t)), speed, 2); modn /= np.abs(modn).max() + 1e-9
    env = np.clip(0.55 + 0.65 * modn, 0.05, 1.4) * np.exp(-t * decay) * np.clip(t / 0.06, 0, 1)
    mid = bp(r.standard_normal(len(t)), 90, 420); mid /= np.abs(mid).max() + 1e-9
    return (base * 1.0 + mid * 0.45) * env


def sonic_boom(sec, width=0.006, gap=0.11):
    """N 자 압력파 두 번 ('쾅-쾅'): 날카로운 앞·뒤 충격."""
    t = t_(sec)
    x = np.zeros(len(t))
    for at, g in [(0.0, 1.0), (gap, 0.8)]:
        L = int(0.05 * SR); tt = np.arange(L) / SR
        nw = np.where(tt < width * 2, 1 - tt / width, 0)  # +1 → -1 직선
        nw = np.where(tt < width * 2, nw, 0)
        place(x, nw * g, at)
    return x


# charge: 낮게 차오르는 우르릉 + 금빛 웅웅(화음) + 점점 잦아지는 타닥, 끝에서 '파직'
d = 0.9; t = t_(d); p = t / d
rum = lp(noise(d), 110, 4) * 4.0 * (0.25 + 0.75 * p ** 1.4)
hum = np.zeros(len(t))
for ratio, g in [(1, 1.0), (1.5, 0.6), (2.0, 0.45), (3.0, 0.2)]:
    f = 55 * ratio * (2 ** (p * 1.4))
    hum += g * (saw(osc(f)) * 0.5 + saw(osc(f * 1.006)) * 0.5)
hum = sweep(hum, 200, 2200, q=1.1) * (0.15 + 0.85 * p ** 1.6) * 1.3
cr = crackles(d, lambda s: 12 + 170 * (s / d) ** 2, 1200, 7000, (40, 300), lambda s: 0.3 + 0.7 * s / d)
snap = np.zeros(len(t)); place(snap, crack_cluster(0.2, seed=2, n=4) * 0.9, d - 0.16)
save_snd(sat(rum + hum + cr * 0.8 + snap, 2.6), 'charge', fout=0.06)

# hammer: 무겁게 휘두르는 '후웅' + 번개 '콰직' + 지글거리는 꼬리
d = 1.3; t = t_(d)
wh = sweep(noise(d), 120, 900, q=1.6) * np.exp(-((t - 0.18) / 0.16) ** 2) * 3.0
wh2 = sweep(noise(d), 900, 200, q=1.4) * np.exp(-((t - 0.42) / 0.2) ** 2) * 1.6
crk = crack_cluster(d, seed=9, n=6, lo=700, hi=3000) * 1.4
thump = boom(d, 150, 40, 12, 4.0) * 1.6
sizzle = hp(noise(d), 2500) * np.exp(-t * 3.5) * 0.5 * (0.6 + 0.4 * np.sign(np.sin(2 * np.pi * 37 * t)))
roll = rolling(d, 2.2, 140, seed=4) * 2.0
x = reverb(wh + wh2 + crk + thump + sizzle + roll, 1.0, 0.3, tone=2800)
save_snd(sat(x, 3.2), 'hammer', fout=0.2)

# clap: 날카로운 음속 폭음 '콰앙!' (N 파 두 번) + 밝은 균열음 + 낮은 쿵 + 메아리
d = 1.7; t = t_(d)
sb = sonic_boom(d) * 3.0
sb = lp(sb, 9000)
crk = crack_cluster(d, seed=13, n=8, lo=900, hi=4000) * 1.5
snap = hp(noise(d), 1500) * env_ad(t, 0.001, 0.025) * 2.6
b1 = boom(d, 170, 36, 16, 3.2) * 2.2
body = lp(noise(d), 1800) * env_ad(t, 0.002, 0.12) * 1.8
roll = rolling(d, 1.6, 170, seed=7, speed=7) * 2.4
x = sb + crk + snap + b1 + body + roll
x = reverb(x, 1.6, 0.4, tone=3200, pre=0.02)
save_snd(sat(x, 4.2), 'clap', fout=0.3)

# slam: 땅이 흔들리는 '쿠웅' — 아주 낮은 쿵 + 부서지는 돌 + 긴 진동
d = 2.0; t = t_(d)
sub = boom(d, 95, 26, 6, 1.8) * 2.6
sub2 = np.zeros(len(t)); place(sub2, boom(1.4, 70, 24, 8, 2.4) * 1.4, 0.06)
thud = lp(noise(d), 600) * env_ad(t, 0.003, 0.09) * 3.0
rocks = crackles(d, lambda s: 260 * np.exp(-s * 3.0) + 8, 150, 1600, (200, 1600), lambda s: np.exp(-s * 1.6), seed=21)
rocks = lp(rocks, 2500) * 1.6
shake = lp(noise(d), 70, 4) * np.exp(-t * 1.4) * 5.0 * (0.7 + 0.3 * np.sin(2 * np.pi * 9 * t))
crk = crack_cluster(d, seed=17, n=5, lo=400, hi=1500) * 0.8
x = sub + sub2 + thud + rocks + shake + crk
x = reverb(x, 1.4, 0.3, tone=1800)
save_snd(sat(x, 3.6), 'slam', fout=0.35)

# roar: 굵게 차오르는 천둥의 포효 — 낮은 으르렁(톱니+모음 필터) + 굴러가는 천둥, 0.6초쯤 정점
d = 2.0; t = t_(d); p = t / d
envr = np.interp(t, [0, 0.15, 0.6, 0.9, 2.0], [0.1, 0.55, 1.0, 0.75, 0])
f0 = 48 + 22 * np.interp(t, [0, 0.6, 2.0], [0, 1, 0.3]) + 3 * np.sin(2 * np.pi * 6.5 * t)
growl = saw(osc(f0)) + 0.7 * saw(osc(f0 * 1.009)) + 0.5 * saw(osc(f0 * 0.5))
growl = growl * (0.75 + 0.25 * np.sign(np.sin(2 * np.pi * 23 * t)))  # 거친 떨림
form = bp(growl, 250, 750) * 1.4 + bp(growl, 900, 1500) * 0.6 + lp(growl, 200) * 1.2
form = form * envr
roll = rolling(d, 0.8, 160, seed=31, speed=6) * 3.0 * np.clip(t / 0.3, 0, 1)
crk = np.zeros(len(t))
for at, g in [(0.05, 0.6), (0.45, 1.0), (0.62, 0.8)]:
    place(crk, crack_cluster(0.4, seed=int(at * 100), n=5) * g, at)
air = sweep(noise(d), 300, 2400, q=1.2) * envr * 0.7
x = reverb(form + roll + crk * 1.2 + air, 1.5, 0.35, tone=2400)
save_snd(sat(x, 3.0), 'roar', fout=0.4)

# judgment: 3초짜리 신의 일격 — 거대한 균열 + 초저음 낙하 + 금속성 울림 + 성가대 같은 화음 + 길게 구르는 천둥
d = 3.0; t = t_(d)
crk = crack_cluster(d, seed=99, n=12, lo=500, hi=3500) * 2.0
snap = hp(noise(d), 1200) * env_ad(t, 0.001, 0.04) * 3.0
sb = lp(sonic_boom(d, width=0.009, gap=0.07), 7000) * 2.6
sub = boom(d, 110, 22, 4.5, 1.1) * 3.0
sub2 = np.zeros(len(t)); place(sub2, boom(2.2, 80, 20, 5, 1.4) * 1.8, 0.09)
blast = lp(noise(d), 3000) * env_ad(t, 0.003, 0.3) * 3.0
bell = np.zeros(len(t))
for f, g in [(196, 0.5), (293.7, 0.35), (392, 0.3), (587.3, 0.2), (784, 0.14), (1046.5, 0.08)]:
    bell += np.sin(osc(np.full(len(t), f * (1 + 0.0015 * np.sin(2 * np.pi * 4 * t))))) * g * np.exp(-t * 1.1) * np.clip(t / 0.01, 0, 1)
choir = np.zeros(len(t))
for f in [98, 146.8, 196, 246.9, 293.7]:
    choir += saw(osc(np.full(len(t), f))) + saw(osc(np.full(len(t), f * 1.004)))
choir = (bp(choir, 400, 900) + bp(choir, 1000, 1400) * 0.5) * np.interp(t, [0, 0.1, 1.2, 3.0], [0, 0.8, 0.5, 0]) * 0.5
roll = rolling(d, 0.65, 150, seed=55, speed=4) * 4.5
rocks = lp(crackles(d, lambda s: 200 * np.exp(-s * 1.8) + 10, 150, 1500, (200, 1600), lambda s: np.exp(-s * 1.0), seed=56), 2200) * 1.4
x = crk + snap + sb + sub + sub2 + blast + bell * 0.9 + choir + roll + rocks
x = reverb(x, 2.4, 0.42, tone=2600, pre=0.03)
save_snd(sat(x, 3.6), 'judgment', fout=0.6)

print('ok')
