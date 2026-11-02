# -*- coding: utf-8 -*-
"""白条第三轮复验：像素判据扫描库。

判据与第二轮同口径（同一帧、同一对话框矩形，逐像素）：
  BAND  = (238,238,238) / (255,255,255) 的 8px 水平全宽带 —— 金属 SplitPane.shadow 真身
  VLINE = (122,138,153) 竖直线 —— 金属 SplitPane.darkShadow 真身
修复后期望：两者在对话框内部一律 0 命中；分隔条应是 OnePixelSplitter 的 1px 主题色。
"""
import os
from PIL import Image

BAND_COLORS = [(238, 238, 238), (255, 255, 255)]
VLINE_COLORS = [(122, 138, 153)]


def _load(path):
    return Image.open(path).convert("RGB")


def scan(path, rect, tag="", min_frac=0.25):
    """逐像素扫 rect 内的目标色，返回可打印的读数。"""
    im = _load(path)
    l, t, r, b = rect
    # 裁剪到屏幕范围
    W, H = im.size
    l, t, r, b = max(0, l), max(0, t), min(W, r), min(H, b)
    px = im.load()
    w, h = r - l, b - t
    out = {"tag": tag, "rect": (l, t, r, b), "size": (w, h)}

    # 每行/每列的目标色计数
    rows = {}
    cols = {}
    for y in range(t, b):
        rc = {}
        for x in range(l, r):
            c = px[x, y]
            if c in BAND_COLORS or c in VLINE_COLORS:
                rc[c] = rc.get(c, 0) + 1
        if rc:
            rows[y] = rc
    for x in range(l, r):
        cc = {}
        for y in range(t, b):
            c = px[x, y]
            if c in BAND_COLORS or c in VLINE_COLORS:
                cc[c] = cc.get(c, 0) + 1
        if cc:
            cols[x] = cc

    def strong(d, span, colors):
        hits = []
        for k, cnt in sorted(d.items()):
            for c in colors:
                n = cnt.get(c, 0)
                if n >= span * min_frac:
                    hits.append((k, c, n, "%.2f" % (n / span)))
        return hits

    out["band_rows"] = strong(rows, w, BAND_COLORS)
    out["vline_cols"] = strong(cols, h, VLINE_COLORS)
    # 反向：白色列 / 灰色列的弱命中（用于发现 1px 竖直分隔条）
    out["white_cols"] = strong(cols, h, BAND_COLORS)
    out["vline_rows"] = strong(rows, w, VLINE_COLORS)

    # 分隔条带：找「连续 1~6 行，颜色一致，横跨 >=80% 宽度，且与上下行不同」的水平线
    seps = []
    prev = None
    for y in range(t + 1, b - 1):
        line = [px[x, y] for x in range(l + 2, r - 2, max(1, w // 200))]
        key = None
        if line and all(c == line[0] for c in line):
            key = line[0]
        if key is not None and key != prev:
            seps.append((y, key))
        prev = key
    out["uniform_rows"] = seps[:40]
    return out


def fmt(res):
    lines = []
    lines.append("== %s rect=%s size=%s" % (res["tag"], res["rect"], res["size"]))
    lines.append("  BAND rows (238/255 spanning >=25%%): %s" % (res["band_rows"][:12] or "none"))
    lines.append("  VLINE cols (122,138,153 spanning >=25%%): %s" % (res["vline_cols"][:12] or "none"))
    lines.append("  white cols (weak, >=25%%): %s" % (res["white_cols"][:8] or "none"))
    lines.append("  vline rows (weak, >=25%%): %s" % (res["vline_rows"][:8] or "none"))
    lines.append("  uniform full-width rows: %s" % (res["uniform_rows"][:16] or "none"))
    return "\n".join(lines)


def probe_row(path, rect, ys):
    """给定若干绝对 y，打印该行在若干 x 处的色值（用于人工核对分隔条颜色）。"""
    im = _load(path)
    px = im.load()
    l, t, r, b = rect
    out = []
    for y in ys:
        samples = [(x, px[x, y]) for x in (l + 10, l + (r - l) // 3, l + (r - l) // 2, l + 2 * (r - l) // 3, r - 10)]
        out.append((y, samples))
    return out
