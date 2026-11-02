# -*- coding: utf-8 -*-
"""在指定帧的对话框矩形内找「整行/整列同色」的细线，报出色值与厚度（分隔条取证）。"""
import sys, os
from collections import Counter
from PIL import Image

SHOTS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "build", "MPSV-WhitebarVerify3-shots")
RECT = (365, 165, 1555, 867)


def analyze(name, rect=RECT, min_frac=0.85, ylimit=None):
    im = Image.open(os.path.join(SHOTS, name + ".png")).convert("RGB")
    px = im.load()
    l, t, r, b = rect
    if ylimit:
        t, b = ylimit
    w, h = r - l, b - t
    print("== %s rect=%s" % (name, (l, t, r, b)))
    # 行
    rows = []
    for y in range(t, b):
        c = Counter(px[x, y] for x in range(l, r))
        col, n = c.most_common(1)[0]
        if n >= w * min_frac:
            rows.append((y, col, round(n / w, 3)))
    # 列
    cols = []
    for x in range(l, r):
        c = Counter(px[x, y] for y in range(t, b))
        col, n = c.most_common(1)[0]
        if n >= h * min_frac:
            cols.append((x, col, round(n / h, 3)))
    # 合并连续行
    def merge(items):
        out = []
        for k, col, frac in items:
            if out and out[-1][1] == col and k == out[-1][2] + 1:
                out[-1] = (out[-1][0], col, k, out[-1][3] + 1)
            else:
                out.append((k, col, k, 1))
        return out
    print("  uniform rows:", merge(rows))
    print("  uniform cols:", merge(cols))


if __name__ == "__main__":
    for nm in sys.argv[1:]:
        analyze(nm)
