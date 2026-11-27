# -*- coding: utf-8 -*-
"""逐帧判定 gutter 图标顺序：左=预览(紫)/右=跳转(红) 为 OK；红在左即复现。

自动定位方法行：在文件编辑区的图标带（X0..X1）里找有彩色像素的行带，
再用色相聚类求每行图标从左到右的颜色序列。

用法：python record/prevorder_judge.py ["<glob>" ...]
     默认扫 record/tmp/prevorder-multi/*.png；可用 MPSV_PREVORDER_DIR 覆盖目录。
退出码：0=全部预览在左；1=存在「跳转在左」的帧（复现）。

**X0/X1 是坐标锚点，换 IDE 字号/主题/缩放需重新标定**：先把任意一个含方法图标的
截图第 y 行像素打出来，找到图标带的左右边界（本机最大化 1920×1080、默认字号实测
74..132）。判错时先怀疑这两个值，别怀疑色相阈值。
"""
import glob
import os
import sys

from PIL import Image

OUT = os.environ.get("MPSV_PREVORDER_DIR") or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "tmp", "prevorder-multi")

# 编辑区 gutter 的 x 范围（IDE 最大化、默认字号时实测；换环境须重新标定）
X0, X1 = 74, 132


def colored(r, g, b):
    mx, mn = max(r, g, b), min(r, g, b)
    return mx > 70 and (mx - mn) > 35


def classify(r, g, b):
    mx, mn = max(r, g, b), min(r, g, b)
    if mx <= 70 or (mx - mn) <= 35:
        return None
    if r > 140 and g < 110 and b < 110:
        return "RED"
    if b > 130 and r > 85 and g < 160:
        return "PURPLE"
    if g > 120 and r < 140:
        return "GREEN"
    return "OTHER"


def row_signature(px, y0, y1):
    """该行带内从左到右的图标颜色序列（连续同色列合并，宽度 >=3 才算）。"""
    seq = []
    cur = None
    for x in range(X0, X1):
        cols = [(px[x, y][0], px[x, y][1], px[x, y][2]) for y in range(y0, y1)]
        cols = [c for c in cols if colored(*c)]
        cls = None
        if cols:
            n = len(cols)
            cls = classify(sum(c[0] for c in cols) // n,
                           sum(c[1] for c in cols) // n,
                           sum(c[2] for c in cols) // n)
        if cls:
            if cur is None or cur[0] != cls:
                cur = [cls, x, x]
                seq.append(cur)
            else:
                cur[2] = x
        else:
            cur = None
    return [s[0] for s in seq if s[2] - s[1] >= 3]


def find_icon_rows(px, h):
    rows = []
    run = None
    for y in range(0, h):
        has = any(colored(*px[x, y][:3]) for x in range(X0, X1))
        if has:
            if run is None:
                run = [y, y]
            else:
                run[1] = y
        else:
            if run and run[1] - run[0] >= 8:
                rows.append(tuple(run))
            run = None
    if run and run[1] - run[0] >= 8:
        rows.append(tuple(run))
    return rows


def judge(path):
    im = Image.open(path).convert("RGB")
    px = im.load()
    w, h = im.size
    out = []
    for y0, y1 in find_icon_rows(px, h):
        sig = row_signature(px, y0, y1)
        if not sig:
            continue
        out.append((y0, sig))
    return out


def main():
    # 默认扫全部帧（不多前缀都不加），显式传则按前缀过滤
    pats = sys.argv[1:] or ["*"]
    files = []
    for pat in pats:
        files += sorted(glob.glob(os.path.join(OUT, pat + ".png")))
    if not files:
        print("no frames under %s (先跑 prevorder_probe.py)" % OUT)
        sys.exit(2)
    bad = []
    for p in files:
        name = os.path.basename(p)
        rows = judge(p)
        marks = []
        for y0, sig in rows:
            core = [c for c in sig if c in ("RED", "PURPLE")]
            verdict = "?"
            if core == ["PURPLE", "RED"]:
                verdict = "OK(预览左)"
            elif core == ["RED", "PURPLE"]:
                verdict = "REVERSED(跳转左)"
                bad.append((name, y0))
            elif core == ["PURPLE"]:
                verdict = "PREVIEW-ONLY"
            elif core == ["RED"]:
                verdict = "NAV-ONLY"
            marks.append("y%d:%s %s" % (y0, "-".join(sig), verdict))
        print("%-32s %s" % (name, " | ".join(marks) if marks else "(no icons)"))
    print()
    ordered = sum(1 for p in files for y0, sig in judge(p)
                  if [c for c in sig if c in ("RED", "PURPLE")] == ["PURPLE", "RED"])
    print("frames=%d  rows_preview-left=%d" % (len(files), ordered))
    if bad:
        print("REPRODUCED %d frames with nav-left:" % len(bad))
        for n, y in bad:
            print("  ", n, "y=", y)
        sys.exit(1)
    print("NO REPRO: all frames preview-left")


if __name__ == "__main__":
    main()