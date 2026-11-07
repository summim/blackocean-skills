"""효과음 점검표: 채널·샘플레이트·길이·피크·최대 순간 음량·고역 비율.
사용법: python3 analyze_sounds.py <sounds 폴더>
"""
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from audiolib import SR, load, probe, momentary_max, band_ratio, centroid, true_peak_db  # noqa: E402

root = sys.argv[1]
print(f"{'file':42s} {'ch,sr':9s} {'dur':>5s} {'tpk':>5s} {'Mmax':>6s} {'2.5-8k':>6s} {'10k+':>5s} {'cent':>5s} {'KB':>4s}")
tot = 0
for p in sorted(glob.glob(root + '/**/*.ogg', recursive=True)):
    x = load(p)
    kb = os.path.getsize(p) / 1024
    tot += kb
    print(f"{os.path.relpath(p, root):42s} {probe(p):9s} {len(x) / SR:5.2f} {true_peak_db(x):5.1f} {momentary_max(x):6.1f} "
          f"{band_ratio(x, 2500, 8000):6.3f} {band_ratio(x, 10000, 24000):5.3f} {centroid(x):5.0f} {kb:4.0f}")
print(f"total {tot / 1024:.2f} MB")
