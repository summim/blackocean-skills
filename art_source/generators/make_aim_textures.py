"""조준 표시용 텍스처 (흰색, 알파로 모양. 색은 게임에서 입힘)"""
import sys, json, numpy as np
from PIL import Image
A = sys.argv[1] + '/src/main/resources/assets/blackocean_skills/textures/fx/'
def save(a, name, clamp):
    rgb = np.ones(a.shape + (3,))
    Image.fromarray((np.dstack([rgb, np.clip(a, 0, 1)]) * 255).astype(np.uint8), 'RGBA').save(A + name + '.png')
    json.dump({"texture": {"blur": True, "clamp": clamp}}, open(A + name + '.png.mcmeta', 'w'))
# 범위 원: 또렷한 테두리 + 안쪽 옅은 채움 + 테두리 안쪽 은은한 번짐
R = 256
yy, xx = (np.mgrid[0:R, 0:R] + 0.5) / R * 2 - 1
r = np.sqrt(xx ** 2 + yy ** 2)
edge = np.exp(-((r - 0.955) / 0.022) ** 2)
inner = np.clip((r - 0.55) / 0.4, 0, 1) ** 2 * 0.35 + 0.12
a = np.where(r < 0.985, np.maximum(edge, inner), edge)
# 바깥 테두리에 끊긴 눈금 (돌아가는 느낌)
ang = np.arctan2(yy, xx)
ticks = (np.cos(ang * 24) > 0.6) * np.exp(-((r - 0.9) / 0.012) ** 2) * 0.8
save(np.clip(a + ticks, 0, 1) * (r < 1), 'aim_ring', True)
# 진행 방향 화살표 띠 (세로로 반복, 위쪽이 앞)
H, W = 128, 64
y = (np.arange(H) + 0.5) / H; x = (np.arange(W) + 0.5) / W * 2 - 1
Y, X = np.meshgrid(y, x, indexing='ij')
chev = np.exp(-((Y - (0.55 - np.abs(X) * 0.35)) / 0.05) ** 2) * (np.abs(X) < 0.8)
side = np.exp(-((np.abs(X) - 0.92) / 0.05) ** 2) * 0.9
body = 0.16 * (np.abs(X) < 0.95)
save(np.clip(chev * 0.95 + side + body, 0, 1), 'aim_arrow', False)
print('ok')
