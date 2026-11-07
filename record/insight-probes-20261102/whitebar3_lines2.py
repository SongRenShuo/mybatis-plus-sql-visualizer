# -*- coding: utf-8 -*-
"""分隔条取证：在对话框矩形内找「厚度<=4 且整行/整列同色」的细线，报位置+色值。"""
import sys, os
from collections import Counter
from PIL import Image

SHOTS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "build", "MPSV-WhitebarVerify3-shots")
BG = (43, 45, 48)          # Darcula 面板底 #2B2D30
TARGET_BAD = {(238, 238, 238), (255, 255, 255), (122, 138, 153)}


def lines(name, rect, axis, min_frac=0.9):
    im = Image.open(os.path.join(SHOTS, name + ".png")).convert("RGB")
    px = im.load()
    l, t, r, b = rect
    seq = []
    rng = range(t, b) if axis == "row" else range(l, r)
    for k in rng:
        if axis == "row":
            c = Counter(px[x, k] for x in range(l, r))
            span = r - l
        else:
            c = Counter(px[k, y] for y in range(t, b))
            span = b - t
        col, n = c.most_common(1)[0]
        if n >= span * min_frac:
            seq.append((k, col))
    # 合并连续
    groups = []
    for k, col in seq:
        if groups and groups[-1]["col"] == col and k == groups[-1]["end"] + 1:
            groups[-1]["end"] = k
            groups[-1]["n"] += 1
        else:
            groups.append({"start": k, "end": k, "col": col, "n": 1})
    return [g for g in groups if g["n"] <= 6]


def report(name, rect):
    print("== %s rect=%s" % (name, rect))
    for axis in ("row", "col"):
        thin = lines(name, rect, axis)
        print("  thin %ss: %s" % (axis, [(g["start"], g["end"], g["col"]) for g in thin]))
    # 目标坏色计数
    im = Image.open(os.path.join(SHOTS, name + ".png")).convert("RGB")
    px = im.load()
    l, t, r, b = rect
    hits = Counter()
    for y in range(t, b):
        for x in range(l, r):
            c = px[x, y]
            if c in TARGET_BAD:
                hits[c] += 1
    print("  BAD color pixel count:", dict(hits) or "0")


if __name__ == "__main__":
    R = (365, 165, 1555, 867)
    for nm in sys.argv[1:]:
        rect = R
        if nm.startswith("w3-06b"):
            rect = (360, 120, 1560, 912)
        report(nm, rect)
