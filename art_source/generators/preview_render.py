"""모드의 화염 셰이더(fire_flow.fsh)와 정점 열기 계산을 그대로 옮긴 미리보기 렌더러.
실제 게임 화면이 아니라 '같은 수식'으로 그린 근사 미리보기다.
"""
import sys, numpy as np
from PIL import Image

ROOT = sys.argv[1]
OUT = sys.argv[2]
TEX = ROOT + '/src/main/resources/assets/blackocean_skills/textures/fx/'
MESH = ROOT + '/src/main/resources/assets/blackocean_skills/models/fx/'
W, H = 960, 540
noise = np.asarray(Image.open(TEX + 'fire_noise_a.png').convert('L'), np.float32) / 255.0


def load_tex(n, mode='RGB'):
    return np.asarray(Image.open(TEX + n + '.png').convert(mode), np.float32) / 255.0


def sample(tex, u, v):
    h, w = tex.shape[:2]
    x = (u % 1.0) * w - 0.5; y = (v % 1.0) * h - 0.5
    x0 = np.floor(x).astype(int); y0 = np.floor(y).astype(int); fx = x - x0; fy = y - y0
    x0 %= w; y0 %= h; x1 = (x0 + 1) % w; y1 = (y0 + 1) % h
    if tex.ndim == 3: fx = fx[..., None]; fy = fy[..., None]
    a = tex[y0, x0] * (1 - fx) + tex[y0, x1] * fx
    b = tex[y1, x0] * (1 - fx) + tex[y1, x1] * fx
    return a * (1 - fy) + b * fy


def ramp(t):
    stops = np.array([0, .2, .45, .7, .88, 1.0])
    cols = np.array([[.2, .01, 0], [.75, .08, .02], [1, .36, .04], [1, .68, .18], [1, .92, .6], [1, 1, .95]])
    t = np.clip(t, 0, 1)
    return np.stack([np.interp(t, stops, cols[:, i]) for i in range(3)], -1)


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t)


def cel(v):
    s = smoothstep(.16, .2, v) + smoothstep(.4, .44, v) + smoothstep(.64, .68, v) + smoothstep(.84, .88, v)
    return v * 0.45 + s * 0.25 * 0.55


def fire_frag(u, v, heat, speed, erosion, intensity, time):
    n1 = sample(noise, u, v + time * speed * 0.55)
    n2 = sample(noise, u * 2.1 + 0.37, v * 1.7 + time * speed * 0.95)
    n = n1 * .62 + n2 * .38
    val = heat * (0.45 + 1.05 * n) * intensity
    mask = smoothstep(erosion, erosion + 0.18, n * 0.75 + heat * 0.45)
    c = cel(np.clip(val, 0, 1))
    a = mask * np.clip(val * 1.35, 0, 1)
    return ramp(c) * a[..., None]


class Cam:
    def __init__(s, pos, target, fov=60):
        s.p = np.array(pos, float)
        f = np.array(target, float) - s.p; f /= np.linalg.norm(f)
        r = np.cross(f, [0, 1, 0]); r /= np.linalg.norm(r)
        u = np.cross(r, f)
        s.R = np.stack([r, u, f]); s.k = 1 / np.tan(np.radians(fov) / 2)

    def proj(s, P):
        c = (P - s.p) @ s.R.T
        z = c[:, 2]
        x = W / 2 + c[:, 0] / z * s.k * H / 2
        y = H / 2 - c[:, 1] / z * s.k * H / 2
        return np.stack([x, y], -1), z


img = np.zeros((H, W, 3), np.float32)


def raster(cam, P, attrs, shade, cull=False):
    """P: (N,3,3) 삼각형, attrs: (N,3,k). shade(attr_px) -> 색 (더하기 섞기)"""
    global img
    for tri, at in zip(P, attrs):
        s, z = cam.proj(tri)
        if np.any(z < 0.1): continue
        if cull:
            area = (s[1, 0] - s[0, 0]) * (s[2, 1] - s[0, 1]) - (s[2, 0] - s[0, 0]) * (s[1, 1] - s[0, 1])
            if area >= 0: continue  # 화면 y 가 아래로 커지므로 반시계 = 음수
        x0, x1 = int(max(0, np.floor(s[:, 0].min()))), int(min(W - 1, np.ceil(s[:, 0].max())))
        y0, y1 = int(max(0, np.floor(s[:, 1].min()))), int(min(H - 1, np.ceil(s[:, 1].max())))
        if x1 < x0 or y1 < y0: continue
        xs, ys = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
        d = (s[1, 1] - s[2, 1]) * (s[0, 0] - s[2, 0]) + (s[2, 0] - s[1, 0]) * (s[0, 1] - s[2, 1])
        if abs(d) < 1e-9: continue
        w0 = ((s[1, 1] - s[2, 1]) * (xs - s[2, 0]) + (s[2, 0] - s[1, 0]) * (ys - s[2, 1])) / d
        w1 = ((s[2, 1] - s[0, 1]) * (xs - s[2, 0]) + (s[0, 0] - s[2, 0]) * (ys - s[2, 1])) / d
        w2 = 1 - w0 - w1
        m = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
        if not m.any(): continue
        # 원근 보정 보간
        iz = np.array([1 / z[0], 1 / z[1], 1 / z[2]])
        ww = np.stack([w0[m] * iz[0], w1[m] * iz[1], w2[m] * iz[2]], -1)
        ww /= ww.sum(-1, keepdims=True)
        a = ww @ at
        img[ys[m].astype(int), xs[m].astype(int)] += shade(a)


def load_obj(n):
    v, vt, vn, f = [], [], [], []
    for line in open(MESH + n + '.obj'):
        t = line.split()
        if not t: continue
        if t[0] == 'v': v.append([float(x) for x in t[1:4]])
        elif t[0] == 'vt': vt.append([float(x) for x in t[1:3]])
        elif t[0] == 'vn': vn.append([float(x) for x in t[1:4]])
        elif t[0] == 'f': f.append([int(x.split('/')[0]) - 1 for x in t[1:4]])
    return np.array(v), np.array(vt), np.array(vn), np.array(f)


def rot_to(d):
    z = np.array([0, 0, 1.0]); d = d / np.linalg.norm(d)
    c = z @ d; ax = np.cross(z, d); s = np.linalg.norm(ax)
    if s < 1e-8: return np.eye(3)
    ax /= s; K = np.array([[0, -ax[2], ax[1]], [ax[2], 0, -ax[0]], [-ax[1], ax[0], 0]])
    return np.eye(3) + K * s + K @ K * (1 - c)


def draw_mesh(cam, name, center, d, scale, heatfn, speed, erosion, inten, time, uvshift=0, cull=False):
    v, vt, vn, f = load_obj(name)
    R = rot_to(np.array(d, float))
    P = (v * scale) @ R.T + center
    N = vn @ R.T
    view = cam.p - P; view /= np.linalg.norm(view, axis=1, keepdims=True)
    facing = np.abs((N * view).sum(1))
    heat = np.clip(heatfn(facing, v[:, 2]), 0, 1)
    at = np.concatenate([vt + [uvshift, 0], heat[:, None]], 1)
    raster(cam, P[f], at[f], lambda a: fire_frag(a[:, 0], a[:, 1], a[:, 2], speed, erosion, inten, time), cull)


def billboard(cam, pos, size, tex, color, alpha=1.0):
    t = load_tex(tex)
    s, z = cam.proj(np.array([pos], float))
    if z[0] < 0.1: return
    px = size / z[0] * cam.k * H / 2
    x0, y0 = int(s[0, 0] - px / 2), int(s[0, 1] - px / 2); n = max(2, int(px))
    tt = np.asarray(Image.fromarray((t * 255).astype(np.uint8)).resize((n, n), Image.BILINEAR), np.float32) / 255
    xa, ya = max(0, x0), max(0, y0); xb, yb = min(W, x0 + n), min(H, y0 + n)
    if xb <= xa or yb <= ya: return
    img[ya:yb, xa:xb] += tt[ya - y0:yb - y0, xa - x0:xb - x0] * np.array(color) * alpha


def flame_sprite(cam, pos, size, frame, alpha=1.0):
    t = load_tex('flame_sheet'); g = frame % 64
    cell = t[(g // 8) * 128:(g // 8 + 1) * 128, (g % 8) * 128:(g % 8 + 1) * 128]
    s, z = cam.proj(np.array([pos], float))
    px = size / z[0] * cam.k * H / 2; n = max(2, int(px))
    tt = np.asarray(Image.fromarray((cell * 255).astype(np.uint8)).resize((n, n), Image.BILINEAR), np.float32) / 255
    x0, y0 = int(s[0, 0] - n / 2), int(s[0, 1] - n / 2)
    xa, ya = max(0, x0), max(0, y0); xb, yb = min(W, x0 + n), min(H, y0 + n)
    if xb > xa and yb > ya: img[ya:yb, xa:xb] += tt[ya - y0:yb - y0, xa - x0:xb - x0] * alpha


def trail(cam, pts, time):
    tris, ats = [], []
    n = len(pts); along = 0
    for k in range(n - 1):
        a, b = pts[k], pts[k + 1]
        t0, t1 = k / (n - 1), (k + 1) / (n - 1)
        def side(w):
            dd = b - a; tc = cam.p - a; s = np.cross(dd, tc); return s / np.linalg.norm(s) * w * .5
        wd = lambda t: 1.8 * (1 - t) ** .7 + .12
        sa, sb = side(wd(t0)), side(wd(t1))
        ss=lambda t: min(1,t/0.12)**2*(3-2*min(1,t/0.12)); h0, h1 = (1 - t0) ** 1.2 * ss(t0), (1 - t1) ** 1.2 * ss(t1)
        L = np.linalg.norm(b - a); v0, v1 = along * .22, (along + L) * .22; along += L
        quads = [([a - sa, a, b, b - sb], [(0, v0, 0), (.5, v0, h0), (.5, v1, h1), (0, v1, 0)]),
                 ([a, a + sa, b + sb, b], [(.5, v0, h0), (1, v0, 0), (1, v1, 0), (.5, v1, h1)])]
        for q, at in quads:
            for idx in ([0, 1, 2], [0, 2, 3]):
                tris.append([q[i] for i in idx]); ats.append([at[i] for i in idx])
    raster(cam, np.array(tris), np.array(ats), lambda a: fire_frag(a[:, 0], a[:, 1], a[:, 2], 2.2, .22, 1.2, time))


def background():
    y = np.linspace(0, 1, H)[:, None, None]
    return np.concatenate([0.05 + 0.10 * y, 0.08 + 0.12 * y, 0.14 + 0.16 * y], -1) * np.ones((1, W, 1))


rng = np.random.default_rng(3)

# --- 장면 1: 날아가는 화염 주먹 ---
img[:] = background()
cam = Cam((6.5, 1.6, -1.0), (0.0, 0.2, -1.5), fov=55)
d = np.array([0.05, 0.02, 1.0]); time = 37.3
head = np.array([0.0, 0.2, 1.5]); scale = 1.3
pts = [head - d * 1.6 * k for k in range(1, 11)]
pts = [head] + [p + np.array([0, np.sin(i * .7) * .08, 0]) for i, p in enumerate(pts)]
trail(cam, pts, time)
billboard(cam, head, 5.5, 'flash_glow', (1, .55, .2), .28)
for k in (3, 2, 1):
    draw_mesh(cam, 'fist_core', head - d * 1.6 * k, d, scale * (1 + .06 * k), lambda f, z: .2 + .4 * f,
              .9, .35 + .14 * k, .42 - .12 * k, time, k * .13, cull=True)
draw_mesh(cam, 'fist_core', head, d, scale, lambda f, z: (.22 + .5 * f) * np.clip((z + 1.35) / .95, 0, 1), .9, .05, 1.0, time, cull=True)
draw_mesh(cam, 'fist_shell', head, d, scale, lambda f, z: (.1 + .75 * (1 - f) ** 1.5) * np.clip((z + 2.3) / 1.6, 0, 1), 1.7, .38, .95, time, .5)
for i in range(10):
    p = head - d * rng.uniform(1.2, 4.5) + rng.normal(0, .35, 3)
    flame_sprite(cam, p, rng.uniform(.6, 1.2), rng.integers(64), .9)
for i in range(25):
    p = head - d * rng.uniform(.5, 6) + rng.normal(0, .5, 3)
    billboard(cam, p, rng.uniform(.08, .16), 'ember', (1, .6, .25), 1)
shot1 = np.clip(img.copy(), 0, 1)

# --- 장면 2: 적중 3틱 후 ---
img[:] = background()
cam = Cam((0, 3.0, 9.0), (0, 0.6, 0), fov=55)
# 바닥 표시선
for gx in np.linspace(-8, 8, 17):
    for zz in np.linspace(-6, 6, 300):
        s, z = cam.proj(np.array([[gx, 0, zz]]))
        x, y = int(s[0, 0]), int(s[0, 1])
        if 0 <= x < W and 0 <= y < H: img[y, x] += .06
c = np.array([0, 0.8, 0]); t = 3.0
billboard(cam, c, 9.0 * (1 + t / 20), 'flash_glow', (1, .5, .15), .35)
# 충격파 링 (바닥 평면)
R = 0.5 + 7.0 * (1 - (1 - t / 9) ** 3)
ring = load_tex('shock_ring')
for yy in range(H):
    pass
quad = np.array([[-R, .08, -R], [R, .08, -R], [R, .08, R], [-R, .08, R]])
uvq = np.array([[0, 0], [1, 0], [1, 1], [0, 1]])
fade = (1 - t / 9) ** 2
def ring_shade(a):
    return sample(ring, a[:, 0], a[:, 1]) * fade * np.array([1, .5, .18])
raster(cam, quad[[[0, 1, 2], [0, 2, 3]]], np.concatenate([uvq, np.zeros((4, 1))], 1)[[[0, 1, 2], [0, 2, 3]]], ring_shade)
# 폭발 구
LAT, LON = 14, 24
def sp(i, j):
    th, ph = np.pi * i / LAT, 2 * np.pi * j / LON
    n = np.array([np.sin(th) * np.cos(ph), np.cos(th), np.sin(th) * np.sin(ph)])
    lump = 1 + 0.13 * np.sin(3 * ph + 1.3) * np.sin(4 * th) + 0.09 * np.sin(5 * ph + 2 * th + 0.7)
    return n * lump, np.array([j / LON * 2, i / LAT])
radius = .6 + 2.6 * (1 - (1 - t / 7) ** 3)
tris, ats = [], []
for i in range(LAT):
    for j in range(LON):
        q = [sp(i, j), sp(i + 1, j), sp(i + 1, j + 1), sp(i, j + 1)]
        for idx in ([0, 1, 2], [0, 2, 3]):
            P = [c + q[k][0] * radius for k in idx]
            at = []
            for k in idx:
                nrm = q[k][0]; pp = c + nrm * radius; vv = cam.p - pp; vv /= np.linalg.norm(vv)
                nn = nrm / np.linalg.norm(nrm); fac = abs(nn @ vv); h = (.12 + .6 * fac) * (.75 + .25 * (nn[1] * .5 + .5))
                at.append([q[k][1][0], q[k][1][1], h])
            tris.append(P); ats.append(at)
raster(cam, np.array(tris), np.array(ats), lambda a: fire_frag(a[:, 0], a[:, 1], a[:, 2], 1.4, 0.0, 1.05 - .4 * t / 12, 41.0))
for i in range(16):
    dd = rng.normal(0, 1, 3); dd[1] = abs(dd[1]) + .35; dd /= np.linalg.norm(dd)
    flame_sprite(cam, c + dd * (0.4 + .3 * t * .9), rng.uniform(1.8, 2.4), rng.integers(64), .85)
for i in range(36):
    dd = rng.normal(0, 1, 3); dd[1] = abs(dd[1]); dd /= np.linalg.norm(dd)
    p1 = c + dd * rng.uniform(1.5, 3.8); p0 = p1 - dd * .9
    for s_ in np.linspace(0, 1, 12):
        billboard(cam, p0 + (p1 - p0) * s_, .14, 'spark', (1, .82, .45), .5 + .5 * s_)
shot2 = np.clip(img.copy(), 0, 1)

out = np.concatenate([shot1, shot2], 0)
Image.fromarray((out * 255).astype(np.uint8)).save(OUT)
print('saved', OUT)
