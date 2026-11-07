"""옥타브 대역별 에너지(dB, 전체 대비) — 소리가 너무 둔하거나 날카로운지 빠르게 확인.
사용법: python3 octave_profile.py <ogg 들...>
"""
import os
import sys

import numpy as np
from scipy import signal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from audiolib import SR, load  # noqa: E402

BANDS = [63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
print(f"{'file':40s} " + ' '.join(f'{b:>6d}' for b in BANDS))
for p in sys.argv[1:]:
    x = load(p)
    f, P = signal.welch(x, SR, nperseg=4096)
    tot = P.sum() + 1e-15
    row = []
    for b in BANDS:
        m = (f >= b / np.sqrt(2)) & (f < b * np.sqrt(2))
        row.append(10 * np.log10(P[m].sum() / tot + 1e-12))
    print(f"{os.path.basename(os.path.dirname(p)) + '/' + os.path.basename(p):40s} " + ' '.join(f'{v:6.1f}' for v in row))
