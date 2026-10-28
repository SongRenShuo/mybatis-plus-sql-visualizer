# -*- coding: utf-8 -*-
"""预览面板控件坐标探针：一次量准复选框列与输入框列。

动机（20261027）：a14 连废 3 个 take 全因坐标目测偏（输入框点到了上一个框）。
本探针用像素分析自动定位：
  · 复选框：蓝色实心方块（勾选态）→ 按行聚簇给出中心坐标
  · 输入框：暗底 + 圆角亮边框矩形 → 按行给出中心坐标
输出可直接粘进 scene 脚本的常量。

用法：python probe_panel_coords.py <输出前缀>
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import numpy as np
from PIL import Image

REGION = (285, 26, 1354, 974)


def grab(name):
    from gifkit import shot
    p = os.path.join(HERE, "perf", f"_probe_{name}.png")
    shot(REGION, p)
    return np.asarray(Image.open(p).convert("RGB"), dtype=np.int16), p


def find_checkboxes(img):
    """蓝色勾选方块：b-r>60 & b>120，按行聚簇（每个方块 ~14px 高）。"""
    r, g, b = img[..., 0], img[..., 1], img[..., 2]
    mask = (b - r > 60) & (b > 120)
    # 只看左侧参数区（x < 200 相对 region）
    mask[:, 200:] = False
    rows = mask.sum(axis=1)
    hits, run = [], []
    for y, c in enumerate(rows):
        if c > 4:
            run.append(y)
        elif run:
            if len(run) >= 6:
                ys = run
                band = mask[ys[0]:ys[-1] + 1]
                xs = np.where(band.any(axis=0))[0]
                hits.append({
                    "y": int((ys[0] + ys[-1]) // 2) + REGION[1],
                    "x": int((xs[0] + xs[-1]) // 2) + REGION[0],
                    "h": len(run),
                })
            run = []
    return hits


def find_inputs(img):
    """输入框：在参数区找「连续水平亮线」（边框上沿），给出框中心。"""
    gray = img.mean(axis=2)
    # 参数区大致 x 200~420, y 400~700（region 相对）
    sub = gray[380:720, 200:420]
    # 行均值高于本底（边框比底色亮）且是细线
    rows = sub.mean(axis=1)
    base = np.median(rows)
    cand = np.where(rows > base + 6)[0]
    groups, cur = [], []
    for y in cand:
        if cur and y - cur[-1] > 3:
            groups.append(cur); cur = []
        cur.append(y)
    if cur:
        groups.append(cur)
    boxes = []
    for i in range(0, len(groups) - 1, 2):     # 上下边框成对
        top, bot = groups[i][-1], groups[i + 1][0]
        if bot - top < 18 or bot - top > 40:
            continue
        boxes.append({
            "y": int(380 + (top + bot) // 2) + REGION[1],
            "x": int(200 + 200) + REGION[0],     # 框横中点（参数框宽约 160~200）
            "h": bot - top,
        })
    return boxes


def main():
    prefix = sys.argv[1] if len(sys.argv) > 1 else "a14"
    img, path = grab(prefix)
    print("screenshot:", path)
    print("\n=== 复选框（屏幕坐标，蓝块中心）===")
    for c in find_checkboxes(img):
        print(f"  (x={c['x']}, y={c['y']})   高 {c['h']}px")
    print("\n=== 输入框（屏幕坐标，框内中点）===")
    for c in find_inputs(img):
        print(f"  (x={c['x']}, y={c['y']})   高 {c['h']}px")
    print("\n提示：把上面的坐标粘进 scene 脚本常量（已含 REGION 偏移，可直接 click）")


if __name__ == "__main__":
    main()
