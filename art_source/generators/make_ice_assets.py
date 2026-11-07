"""얼음 능력 에셋: 색 단계표, 서리 플립북, 얼음 조각·반짝임·서리 자국·마법진 텍스처, 스킬 아이콘 5개, 열매, 효과음 6개.
사용법: python3 make_ice_assets.py <프로젝트 루트>      (텍스처만: --tex, 소리만: --snd)
- 더하기 섞기(additive) 텍스처: 검은 배경 RGB / 알파 텍스처(서리 자국): 투명 배경 RGBA
- frost_sheet 는 plasma_sheet 와 같은 8x8 x 128px, 6겹 대칭 + 1/6 회전 → 63 → 0 이 이음새 없이 반복
- 효과음: 모노 48kHz, ogg vorbis q5, 최고 -1dBFS (make_v4_sounds.py 와 같은 방식)
"""
import sys, os, json, subprocess
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

args = [a for a in sys.argv[1:] if not a.startswith('--')]
ROOT = args[0] if args else os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
DO_TEX = '--snd' not in sys.argv
DO_SND = '--tex' not in sys.argv
A = ROOT + '/src/main/resources/assets/blackocean_skills/'
FX = A + 'textures/fx/'
GUI = A + 'textures/gui/skills/'
ITEM = A + 'textures/item/'
SD = A + 'sounds/skill/'
for d in (FX, GUI, ITEM):
    os.makedirs(d, exist_ok=True)


# ================================================================ 공용 도구 (make_v4_textures.py 와 같음)
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


# 흰빛 → 옅은 청록 → 청록 → 하늘 → 짙은 파랑 → 남색
ice_ramp = make_ramp([(1, 1, 1), (0.84, 0.98, 1), (0.52, 0.9, 1), (0.24, 0.62, 1), (0.08, 0.27, 0.8), (0.015, 0.04, 0.2)])


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


def L_(img):
    return np.asarray(img, float) / 255


def dendrite(dr, cx, cy, a, length, width, depth, scale, f=255, rng=None):
    """눈송이 가지: 곧은 줄기 + 60도로 갈라지는 잔가지 (재귀)."""
    x1, y1 = cx + np.cos(a) * length, cy + np.sin(a) * length
    dr.line([(cx * scale, cy * scale), (x1 * scale, y1 * scale)], fill=int(f), width=max(1, int(width * scale)))
    if depth <= 0:
        return
    nb = 3 if depth > 1 else 2
    for i in range(nb):
        q = 0.3 + 0.55 * i / max(1, nb - 1) * (1 if rng is None else rng.uniform(0.85, 1.1))
        bx, by = cx + np.cos(a) * length * q, cy + np.sin(a) * length * q
        bl = length * (1 - q) * 0.62
        for s in (-1, 1):
            dendrite(dr, bx, by, a + s * np.pi / 3, bl, width * 0.7, depth - 1, scale, f * 0.92, rng)


def snowflake_mask(R, arm, width, depth, rot=0.0, SS=4, seed=0):
    rng = np.random.default_rng(seed)
    im = Image.new('L', (R * SS, R * SS), 0)
    dr = ImageDraw.Draw(im)
    c = R / 2
    for k in range(6):
        dendrite(dr, c, c, rot + k * np.pi / 3, arm, width, depth, SS, 255, rng)
    return im.resize((R, R), Image.BOX)


if DO_TEX:
    # ------------------------------------------------------------ 1) 색 단계표
    t = np.linspace(1, 0, 256)[None, :].repeat(8, 0)
    fx(ice_ramp(t), 'ice_ramp', True)

    # ------------------------------------------------------------ 2) 서리 플립북 (8x8, 128px) — 6겹 서리 꽃 + 차가운 안개
    S, G = 128, 8
    NF = G * G
    sheet = np.zeros((S * G, S * G, 3))
    xx, yy, rr, ang = grid(S)
    big = snowflake_mask(S * 2, 100, 3.0, 2, seed=7)   # 2배로 만들어 돌린 뒤 줄임
    small = snowflake_mask(S * 2, 58, 2.4, 1, rot=np.pi / 6, seed=8)
    pn = periodic_noise(256, beta=2.3, lo=2, hi=20, seed=61)
    for k in range(NF):
        ph = k / NF
        a1 = ph * 60.0                 # 1/6 바퀴 → 처음과 같은 모양
        a2 = -ph * 60.0
        b = L_(big.rotate(a1, Image.BICUBIC).resize((S, S), Image.BOX))
        s2 = L_(small.rotate(a2, Image.BICUBIC).resize((S, S), Image.BOX))
        bg = L_(Image.fromarray((b * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2.5)))
        n = np.roll(np.roll(pn, int(ph * 256), 0), int(ph * 256), 1)[::2, ::2]
        pulse = 1 + 0.1 * np.sin(2 * np.pi * ph * 3)
        core = np.exp(-(rr / (0.16 * pulse)) ** 2) * 0.8
        haze = np.clip(1 - rr / 0.9, 0, 1) ** 1.6 * (0.25 + 0.5 * n)
        v = np.clip(core + b * 0.85 + s2 * 0.7 + bg * 0.9 + haze, 0, 1)
        vc = cel(v, levels=(0.1, 0.3, 0.55, 0.8), soft=0.05) * 0.75 + v * 0.25
        col = ice_ramp(0.25 + vc * 0.75) * np.clip(vc * 1.25, 0, 1)[..., None]
        col = col * np.clip((1 - rr) / 0.12, 0, 1)[..., None]
        gy, gx = divmod(k, G)
        sheet[gy * S:(gy + 1) * S, gx * S:(gx + 1) * S] = np.clip(col, 0, 1)
    fx(sheet, 'frost_sheet', True)

    # ------------------------------------------------------------ 3) 얼음 조각 32x32 (흰빛, 색은 실행 중 입힘)
    SS = 8; R = 32 * SS
    im = Image.new('L', (R, R), 0); d = ImageDraw.Draw(im)
    top, bot, mid, half = (16, 1.5), (16, 30.5), 11.5, 6.2
    P = lambda pts: [(x * SS, y * SS) for x, y in pts]
    d.polygon(P([top, (16 - half, mid), (16 - half * 0.55, 24), bot, (16, mid + 2)]), fill=255)   # 밝은 왼쪽 면
    d.polygon(P([top, (16 + half, mid), (16 + half * 0.6, 23), bot, (16, mid + 2)]), fill=150)    # 어두운 오른쪽 면
    d.line(P([top, (16, mid + 2), bot]), fill=255, width=int(1.0 * SS))
    d.line(P([(16 - half, mid), (16, mid + 2), (16 + half, mid)]), fill=230, width=int(0.8 * SS))
    m = im.resize((32, 32), Image.BOX)
    v = L_(m)
    halo = L_(m.filter(ImageFilter.GaussianBlur(1.6)))
    fx(gray3(np.maximum(v, halo * 0.45)), 'ice_shard', True)

    # ------------------------------------------------------------ 4) 반짝임 별 64x64 (4갈래 + 옅은 대각 + 심)
    xx, yy, rr, ang = grid(64)
    cross = np.exp(-np.abs(xx) * 30 - np.abs(yy) * 3.2) + np.exp(-np.abs(yy) * 30 - np.abs(xx) * 3.2)
    u, w = (xx + yy) / np.sqrt(2), (xx - yy) / np.sqrt(2)
    diag = (np.exp(-np.abs(u) * 34 - np.abs(w) * 7) + np.exp(-np.abs(w) * 34 - np.abs(u) * 7)) * 0.45
    v = np.clip(cross + diag + np.exp(-rr * 9) * 1.2 + np.exp(-rr * 3.5) * 0.15, 0, 1) * np.clip((1 - rr) / 0.15, 0, 1)
    fx(gray3(v), 'ice_glint', True)

    # ------------------------------------------------------------ 5) 서리 자국 256 RGBA (알파 섞기, 흰 서리 + 갈라진 결)
    R = 256
    xx, yy, rr, ang = grid(R)
    n1 = periodic_noise(R, beta=2.4, lo=2, hi=24, seed=71)
    n2 = periodic_noise(R, beta=1.8, lo=8, hi=80, seed=72)
    ferns = Image.new('L', (R * 2, R * 2), 0)
    fd = ImageDraw.Draw(ferns)
    rng = np.random.default_rng(73)
    for k in range(14):
        a = k / 14 * 2 * np.pi + rng.normal(0, 0.12)
        dendrite(fd, R / 2, R / 2, a, rng.uniform(70, 118), 1.6, 2, 2, rng.uniform(150, 255), rng)
    for k in range(40):  # 바깥쪽 작은 서리 꽃
        a = rng.uniform(0, 2 * np.pi); r0 = rng.uniform(30, 105)
        cx, cy = R / 2 + np.cos(a) * r0, R / 2 + np.sin(a) * r0
        for j in range(6):
            dendrite(fd, cx, cy, j * np.pi / 3 + a, rng.uniform(5, 11), 1.0, 1, 2, 200, rng)
    fern = L_(ferns.resize((R, R), Image.BOX))
    fern_b = L_(Image.fromarray((fern * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2)))
    edge = np.clip((0.97 - rr) / 0.35, 0, 1) ** 1.2
    patch = np.clip((n1 - 0.32) * 2.2, 0, 1)
    a = np.clip(patch * 0.42 + fern * 0.85 + fern_b * 0.35 + (n2 - 0.5) * 0.18, 0, 1) * edge
    a = np.clip(a + np.exp(-(rr / 0.35) ** 2) * 0.35 * edge, 0, 1) * 0.85
    shade = np.clip(0.78 + 0.22 * fern + 0.12 * (n2 - 0.5), 0, 1)
    rgba = np.dstack([shade * 0.9, shade * 0.97, shade, a])
    fx(rgba, 'ice_frost', True, 'RGBA')

    # ------------------------------------------------------------ 6) 얼음 마법진 512 (더하기, 흰빛)
    R = 512; SS = 2
    im = Image.new('L', (R * SS, R * SS), 0); d = ImageDraw.Draw(im)
    C = R * SS / 2
    def circ(r, w, f=255):
        d.ellipse([C - r * SS, C - r * SS, C + r * SS, C + r * SS], outline=int(f), width=int(w * SS))
    def seg(p0, p1, w, f=255):
        d.line([(C + p0[0] * SS, C + p0[1] * SS), (C + p1[0] * SS, C + p1[1] * SS)], fill=int(f), width=max(1, int(w * SS)))
    circ(250, 5); circ(238, 2, 200); circ(196, 3); circ(184, 1.5, 180); circ(120, 2.5, 230); circ(60, 2, 200)
    # 12 눈금 + 24 작은 눈금
    for k in range(48):
        a = k / 48 * 2 * np.pi
        r0, r1 = (238, 250) if k % 4 == 0 else (240, 246)
        seg((np.cos(a) * r0, np.sin(a) * r0), (np.cos(a) * r1, np.sin(a) * r1), 2.5 if k % 4 == 0 else 1.5)
    # 고리 사이 룬 글자 (짧은 획 조합, 결정 모양)
    rng = np.random.default_rng(91)
    NG = 18
    for k in range(NG):
        a = k / NG * 2 * np.pi
        ca, sa = np.cos(a), np.sin(a)
        tx, ty = -sa, ca
        rc = 217
        def gp(u, v):  # 글자 좌표(u 접선, v 반지름) → 평면
            return (ca * (rc + v) + tx * u, sa * (rc + v) + ty * u)
        strokes = rng.integers(3, 6)
        pts = [(-7, -9), (7, -9), (-7, 9), (7, 9), (0, -11), (0, 11), (-8, 0), (8, 0), (0, 0)]
        for _ in range(strokes):
            i, j = rng.choice(len(pts), 2, replace=False)
            seg(gp(*pts[i]), gp(*pts[j]), 2.2)
        d.ellipse([C + (ca * 196) * SS - 4 * SS, C + (sa * 196) * SS - 4 * SS, C + (ca * 196) * SS + 4 * SS, C + (sa * 196) * SS + 4 * SS], fill=220) if k % 3 == 0 else None
    # 육각성 (두 삼각형) + 안쪽 육각형
    for off in (0, np.pi / 3):
        P3 = [(np.cos(off + i * 2 * np.pi / 3 - np.pi / 2) * 184, np.sin(off + i * 2 * np.pi / 3 - np.pi / 2) * 184) for i in range(3)]
        for i in range(3):
            seg(P3[i], P3[(i + 1) % 3], 3)
    P6 = [(np.cos(i * np.pi / 3) * 106, np.sin(i * np.pi / 3) * 106) for i in range(6)]
    for i in range(6):
        seg(P6[i], P6[(i + 1) % 6], 2.2, 220)
    # 가운데 눈송이
    rng2 = np.random.default_rng(92)
    for k in range(6):
        a = k * np.pi / 3 - np.pi / 2
        x0, y0 = 0, 0
        L = 96
        seg((0, 0), (np.cos(a) * L, np.sin(a) * L), 3.5)
        for q in (0.35, 0.6, 0.82):
            bx, by = np.cos(a) * L * q, np.sin(a) * L * q
            bl = L * (1 - q) * 0.6 + 6
            for s in (-1, 1):
                seg((bx, by), (bx + np.cos(a + s * np.pi / 3) * bl, by + np.sin(a + s * np.pi / 3) * bl), 2.4)
    m = im.resize((R, R), Image.BOX)
    v = L_(m)
    glow = L_(m.filter(ImageFilter.GaussianBlur(5)))
    xx, yy, rr, ang = grid(R)
    v = np.clip(v * 0.95 + glow * 0.9 + np.exp(-(rr / 0.25) ** 2) * 0.1, 0, 1) * np.clip((1 - rr) / 0.02, 0, 1)
    fx(gray3(v), 'ice_rune', True)

    # ------------------------------------------------------------ 7) 스킬 아이콘 64x64 (make_v4_textures.make_icon 과 같은 방식)
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
        def mask(self):
            return self.im.resize((IS, IS), Image.BOX)

    E = dict(ramp=ice_ramp, bg=(0.02, 0.05, 0.11), glow=(0.35, 0.8, 1.0))

    def blur(img, r):
        return np.asarray(img.filter(ImageFilter.GaussianBlur(r)), float) / 255

    def make_icon(name, fn, ult=False):
        d = D(); fn(d)
        m_img = d.mask()
        M = np.asarray(m_img, float) / 255
        cover = np.clip(M * 4, 0, 1)
        b3 = blur(m_img, 2.5)
        g6 = blur(m_img, 5)
        yy, xx = np.mgrid[0:IS, 0:IS]
        ny = 1 - (yy + 0.5) / IS
        heat = np.clip(0.1 + M * 0.56 + b3 * 0.3 + ny * 0.06, 0, 1)
        col = E['ramp'](heat)
        r = np.sqrt(((xx + 0.5 - IS / 2) / (IS / 2)) ** 2 + ((yy + 0.5 - IS / 2) / (IS / 2)) ** 2)
        bg = np.zeros((IS, IS, 3)) + np.array(E['bg'])
        bg += np.clip(0.42 - r * 0.38, 0, 1)[..., None] * np.array(E['glow']) * 0.55
        bg *= np.clip(1.15 - r * 0.35, 0, 1)[..., None]
        outline = np.clip(blur(Image.fromarray((np.clip(cover, 0, 1) * 255).astype(np.uint8)), 1.1) * 2.2, 0, 1)
        rgb = bg + g6[..., None] * np.array(E['glow']) * 0.75
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

    def crystal(d, x0, y0, x1, y1, w, f_l=255, f_r=185, tipq=0.25):
        """x0,y0(뿌리) → x1,y1(끝) 방향의 결정. 밝은 면/어두운 면 두 쪽."""
        dx, dy = x1 - x0, y1 - y0
        L = np.hypot(dx, dy); ux, uy = dx / L, dy / L; px, py = -uy, ux
        sx, sy = x0 + dx * (1 - tipq), y0 + dy * (1 - tipq)
        a = (x0 + px * w * 0.7, y0 + py * w * 0.7); b = (x0 - px * w * 0.7, y0 - py * w * 0.7)
        c = (sx + px * w, sy + py * w); e = (sx - px * w, sy - py * w)
        m0, m1 = (x0, y0), (sx, sy)
        d.poly([a, c, (x1, y1), m1, m0], f_l)
        d.poly([b, e, (x1, y1), m1, m0], f_r)
        d.line([m0, m1, (x1, y1)], 0.9, 255)

    def i_ice_spear(d):
        crystal(d, 9, 55, 57, 7, 6.5, 255, 190, 0.3)
        # 꼬리의 깃 결정
        crystal(d, 18, 46, 6, 40, 3, 220, 160, 0.4)
        crystal(d, 18, 46, 24, 58, 3, 220, 160, 0.4)
        # 서리 고리
        d.ell(28, 36, 9, 4.5, outline=200, w=1.6)
        for x, y, r in [(50, 26, 1.8), (40, 12, 1.4), (14, 30, 1.5), (34, 54, 1.3)]:
            d.ell(x, y, r, f=230)

    def i_ice_shards(d):
        for i, a in enumerate(np.radians([-62, -38, -14, 10, 34])):
            r0, r1 = 12, 30 + (6 if i == 2 else 0)
            ox, oy = 12, 52
            aa = a - np.pi / 4
            crystal(d, ox + np.cos(aa) * r0 * 1.2, oy + np.sin(aa) * r0 * 1.2, ox + np.cos(aa) * (r0 + r1) * 1.2, oy + np.sin(aa) * (r0 + r1) * 1.2,
                    3.6, 255, 180, 0.35)
        d.ell(12, 52, 6, f=220)

    def i_ice_spikes(d):
        # 앞으로 갈수록 커지는 가시 줄 (바닥선 위)
        for x, h, w in [(8, 12, 3.5), (18, 18, 4.5), (30, 27, 5.8), (45, 38, 7.2)]:
            crystal(d, x + 2, 56, x + 4, 56 - h, w, 255, 180, 0.35)
            crystal(d, x - 2, 56, x - 6, 56 - h * 0.55, w * 0.55, 220, 160, 0.4)
        d.poly([(2, 55), (62, 55), (62, 59), (2, 59)], 170)

    def i_frost_nova(d):
        cx, cy = 32, 32
        d.ell(cx, cy, 27, outline=210, w=2.2)
        for k in range(6):
            a = k * np.pi / 3 - np.pi / 2
            ex, ey = cx + np.cos(a) * 24, cy + np.sin(a) * 24
            d.line([(cx, cy), (ex, ey)], 3.2, 255)
            for q in (0.45, 0.72):
                bx, by = cx + np.cos(a) * 24 * q, cy + np.sin(a) * 24 * q
                for s in (-1, 1):
                    d.line([(bx, by), (bx + np.cos(a + s * np.pi / 3) * 7 * (1.1 - q), by + np.sin(a + s * np.pi / 3) * 7 * (1.1 - q))], 2.2, 235)
        for k in range(12):
            a = k * np.pi / 6
            crystal(d, cx + np.cos(a) * 27, cy + np.sin(a) * 27, cx + np.cos(a) * 32, cy + np.sin(a) * 32, 2.2, 240, 180, 0.6)
        d.ell(cx, cy, 5, f=255)

    def i_ice_age(d):
        # 하늘에서 떨어지는 거대한 빙산 + 바닥 충격
        crystal(d, 32, 6, 32, 46, 11, 255, 185, 0.32)
        crystal(d, 22, 14, 18, 36, 5.5, 230, 165, 0.4)
        crystal(d, 42, 12, 47, 34, 5.5, 230, 165, 0.4)
        for x in (12, 20, 44, 52):
            d.line([(x, 4), (x + (32 - x) * 0.08, 14)], 1.4, 150)
        d.poly([(8, 52), (56, 52), (60, 56), (4, 56)], 190)
        for a in (-160, -130, -50, -20):
            ar = np.radians(a)
            crystal(d, 32 + np.cos(ar) * 10, 52, 32 + np.cos(ar) * 22, 52 + np.sin(ar) * 9, 2.6, 240, 170, 0.5)

    for n, f, u in [('ice_spear', i_ice_spear, False), ('ice_shards', i_ice_shards, False), ('ice_spikes', i_ice_spikes, False),
                    ('frost_nova', i_frost_nova, False), ('ice_age', i_ice_age, True)]:
        make_icon(n, f, u)

    # ------------------------------------------------------------ 8) 열매 16x16 (make_v4_textures 의 열매와 같은 모양·꼭지·잎·소용돌이)
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
    ol, dk, md, lt, hl, sw, swl = (16, 42, 86), (62, 128, 196), (118, 186, 236), (186, 232, 255), (250, 255, 255), (232, 248, 255), (255, 255, 255)
    STEM = [(60, 40, 24), (110, 75, 40)]
    LEAF = [(40, 110, 40), (90, 175, 70)]
    cx, cy, R0 = 7.5, 9.5, 6.3
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
                Lv = np.array([-0.55, -0.6, 0.58]); Lv /= np.linalg.norm(Lv)
                s = n @ Lv
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
    Image.fromarray(img, 'RGBA').save(ITEM + 'ice_fruit.png')
    print('textures ok')


# ================================================================ 효과음
if DO_SND:
    from scipy import signal
    from scipy.io import wavfile
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

    def place(dst, src, at):
        i = int(at * SR)
        L = min(len(src), len(dst) - i)
        if L > 0 and i >= 0: dst[i:i + L] += src[:L]

    def reverb(x, sec=1.2, mix=0.3, tone=4000, pre=0.01):
        n = int(sec * SR)
        ir = rng.standard_normal(n) * np.exp(-np.arange(n) / SR * 6.9 / sec)
        ir = lp(ir, tone)
        ir = np.concatenate([np.zeros(int(pre * SR)), ir])
        y = signal.fftconvolve(x, ir)[:len(x)]
        y = y / (np.abs(y).max() + 1e-9) * np.abs(x).max()
        return x + y * mix

    def fade(x, fin=0.003, fout=0.04):
        a = int(fin * SR); b = int(fout * SR)
        x = x.copy(); x[:a] *= np.linspace(0, 1, a); x[-b:] *= np.linspace(1, 0, b) ** 1.5
        return x

    def save_snd(x, name, fout=0.05):
        x = fade(x, fout=fout)
        x = x / (np.abs(x).max() + 1e-9) * 0.891
        os.makedirs(SD + 'ice', exist_ok=True)
        w = f'{SD}ice/{name}.wav'; o = w[:-4] + '.ogg'
        for _ in range(4):
            wavfile.write(w, SR, (x * 32767).astype(np.int16))
            subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', w, '-ac', '1', '-ar', str(SR), '-c:a', 'libvorbis', '-q:a', '5', o], check=True)
            dec = np.frombuffer(subprocess.run(['ffmpeg', '-v', 'error', '-i', o, '-f', 'f32le', '-'], capture_output=True, check=True).stdout, np.float32)
            pk = np.abs(dec).max()
            if pk <= 0.9: break
            x = x * (0.88 / pk)
        os.remove(w)
        print(name, f'{len(x) / SR:.2f}s')

    def tink(f, dur, decay, inharm=(1.0, 2.76, 5.4, 8.93), gains=(1, 0.5, 0.3, 0.15)):
        """유리·얼음 조각이 부딪히는 맑은 '팅' (비조화 배음)."""
        t = t_(dur); y = np.zeros(len(t))
        for m, g in zip(inharm, gains):
            y += np.sin(osc(np.full(len(t), f * m)) + rng.uniform(0, 6.28)) * g * np.exp(-t / (decay / (1 + 0.6 * m)))
        return y * np.clip(t / 0.0008, 0, 1)

    def glass_rain(sec, n, t0, t1, flo, fhi, amp_fn=lambda q: 1.0):
        """잘게 부서져 떨어지는 얼음 조각들 (팅 소리 여러 개)."""
        y = np.zeros(int(sec * SR))
        for _ in range(n):
            at = t0 + (t1 - t0) * rng.random() ** 1.6
            q = (at - t0) / max(1e-6, t1 - t0)
            place(y, tink(rng.uniform(flo, fhi), 0.25, rng.uniform(0.03, 0.12)) * rng.uniform(0.2, 1.0) * amp_fn(q), at)
        return y

    def crack(sec, sharp=1.0):
        """얼음이 쩍 갈라지는 소리: 아주 짧은 넓은 대역 탁 + 떨리며 내려가는 공명."""
        t = t_(sec)
        click = hp(noise(sec), 1800) * np.exp(-t * 120 * sharp)
        body = bp(noise(sec), 300, 2600) * np.exp(-t * 28)
        ring = np.zeros(len(t))
        for f in rng.uniform(900, 3400, 5):
            ring += np.sin(osc(f * (1 - 0.18 * (1 - np.exp(-t * 18))))) * np.exp(-t * rng.uniform(14, 30)) * 0.25
        return click * 1.0 + body * 0.6 + ring

    def creak(sec, f0, f1):
        """얼어붙는 끼익 (거친 톱니파 마찰)."""
        t = t_(sec)
        f = f0 * (f1 / f0) ** (t / sec)
        jitter = 1 + 0.08 * lp(noise(sec), 30)
        ph = osc(f * jitter)
        pulses = (np.sin(ph) > 0.92).astype(float)
        return bp(pulses + 0.02 * noise(sec), 400, 5000)

    # ---- charge: 반짝이며 차오르는 서리 + 얼어붙는 끼익 (약 1.0초, 궁극기는 0.6배 음높이로 늘려서 씀)
    d = 1.05; t = t_(d); p = t / d
    shimmer = np.zeros(len(t))
    for _ in range(70):
        at = d * rng.random() ** 0.7
        place(shimmer, tink(rng.uniform(2600, 7500), 0.2, rng.uniform(0.02, 0.06)) * (0.15 + 0.85 * (at / d)) * rng.uniform(0.2, 0.7), at)
    air = sweep(noise(d), 900, 7000, q=1.1) * (p ** 1.5) * 0.9
    pad = np.zeros(len(t))
    for f in (660, 990, 1320, 1980):
        pad += np.sin(osc(f * (1 + 0.004 * np.sin(2 * np.pi * 5.5 * t)))) * 0.12
    pad *= np.clip(p / 0.6, 0, 1) ** 2
    frz = creak(d, 18, 60) * np.interp(t, [0, 0.3, 0.9, d], [0, 0.4, 0.8, 0.3]) * 0.6
    sub = np.sin(osc(70 + 50 * p)) * p ** 2 * 0.35
    x = reverb(shimmer + air + pad + frz + sub, 0.9, 0.35, tone=8000)
    save_snd(sat(x, 1.6), 'charge', fout=0.06)

    # ---- shot: 날카로운 휙 + 유리 같은 '칭'
    d = 0.75; t = t_(d)
    whoosh = sweep(noise(d), 4000, 900, q=1.3) * np.interp(t, [0, 0.04, 0.35, d], [0, 1, 0.25, 0])
    ting = tink(1760, d, 0.35) * 0.55 + tink(2637, d, 0.25) * 0.35
    snap = crack(0.2, 1.4) * 0.6
    x = np.zeros(len(t)); place(x, snap, 0.0)
    x = reverb(whoosh + ting + x, 0.8, 0.3, tone=9000)
    save_snd(sat(x, 1.8), 'shot')

    # ---- shatter: 유리처럼 깨지는 '쨍그랑' + 흩어지는 조각
    d = 1.15; t = t_(d)
    x = np.zeros(len(t))
    place(x, crack(0.4, 1.0) * 1.2, 0.0)
    burst = hp(noise(d), 2500) * np.exp(-t * 14) * 0.8
    x += burst
    x += glass_rain(d, 90, 0.01, 0.9, 1800, 8000, lambda q: (1 - q) ** 1.3) * 0.9
    x += boom(d, 180, 60, 30, 14) * 0.35
    x = reverb(x, 0.9, 0.3, tone=9000)
    save_snd(sat(x, 2.0), 'shatter')

    # ---- spikes: 땅에서 솟구치는 깊은 얼음 균열 '쩌적-콰직'
    d = 0.85; t = t_(d)
    x = np.zeros(len(t))
    thud = boom(d, 140, 45, 18, 9) * 1.0
    grind = lp(bp(noise(d), 150, 1400) * np.exp(-t * 7), 1600) * 0.8
    place(x, crack(0.5, 0.8) * 1.0, 0.0)
    place(x, crack(0.4, 1.2) * 0.6, 0.06)
    place(x, crack(0.3, 1.5) * 0.35, 0.13)
    x += thud + grind
    x += glass_rain(d, 22, 0.05, 0.7, 2000, 6500, lambda q: 1 - q) * 0.35
    x = reverb(x, 0.8, 0.25, tone=6000)
    save_snd(sat(x, 2.2), 'spikes')

    # ---- nova: 낮은 '쿵' + 퍼져 나가며 얼어붙는 쩌저적 + 반짝이는 꼬리
    d = 1.7; t = t_(d)
    x = np.zeros(len(t))
    x += boom(d, 160, 38, 14, 4.5) * 1.1
    blast = sweep(noise(d), 6000, 500, q=0.9) * np.exp(-t * 5) * 0.9
    x += blast
    for i in range(9):
        place(x, crack(0.35, 1.0) * (0.8 - 0.07 * i), 0.02 + i * 0.06 + rng.uniform(0, 0.02))
    x += creak(d, 40, 14) * np.interp(t, [0, 0.1, 0.8, d], [0, 0.7, 0.3, 0]) * 0.4
    x += glass_rain(d, 80, 0.1, 1.5, 2500, 9000, lambda q: (1 - q) ** 0.8) * 0.5
    x = reverb(x, 1.6, 0.4, tone=7000)
    save_snd(sat(x, 2.0), 'nova', fout=0.2)

    # ---- glacier: 거대한 빙산 충돌 '콰과광' → 지진 같은 울림 → 금 가며 쏟아지는 얼음 (약 2.6초)
    d = 2.7; t = t_(d)
    x = np.zeros(len(t))
    sub = boom(d, 95, 26, 6, 1.4) * 1.3
    impact = lp(noise(d), 900) * np.exp(-t * 6) * 1.2
    crash = bp(noise(d), 400, 6000) * np.exp(-t * 4.5) * 0.9
    rumble = lp(noise(d), 160, 3) * np.interp(t, [0, 0.05, 1.2, d], [0, 1, 0.6, 0]) * 1.6
    x += sub + impact + crash + rumble
    for i in range(14):
        place(x, crack(0.5, 0.7 + rng.random()) * rng.uniform(0.4, 1.0), 0.0 + i * 0.07 + rng.uniform(0, 0.05))
    for i in range(6):  # 뒤늦게 쩍쩍 갈라지는 큰 균열
        place(x, crack(0.6, 0.6) * 0.7, 0.9 + i * 0.22 + rng.uniform(0, 0.08))
    x += glass_rain(d, 260, 0.05, 2.4, 1500, 9000, lambda q: (1 - q) ** 0.7) * 0.8
    x += creak(d, 25, 9) * np.interp(t, [0, 0.4, 1.6, d], [0, 0.6, 0.4, 0]) * 0.35
    x = reverb(x, 2.4, 0.45, tone=5000, pre=0.02)
    save_snd(sat(x, 2.6), 'glacier', fout=0.4)
    print('sounds ok')
