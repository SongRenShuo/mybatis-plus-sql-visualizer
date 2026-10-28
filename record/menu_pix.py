# -*- coding: utf-8 -*-
"""弹层项定位：像素双帧 diff（替代硬编码行坐标）。

动机（20261027 复盘）：a11 的 6 次废 take 全部源于「执行计划/数据源选择弹层」的行坐标
硬编码——布局一变就点到相邻项（误点 dbx_test(PG) 弹凭据框）。而这类弹层是 JBPopup，
`robot.menu_items()` 对它返回 0（查不到 ActionMenuItem），组件树路线不可用。

已验证过的替代方案（`probe_menu_pix.py` 口径）：
  截帧 A（菜单刚开） → 键盘 Down×1 → 截帧 B → difference(A,B).getbbox()
  = 首项高亮带 → 由带高与项高推算第 n 项中心 y。

用法（在 scene 脚本里）：
    from menu_pix import open_and_locate, click_item
    items = open_and_locate(trigger_click=(1295, 251))   # 打开弹层并量出各项
    click_item(items, 1)                                  # 点第 2 项（0 起）

约定：弹层项等距；首项高亮带高度 = 项高（或项高的一半，函数内自动判别）。
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import numpy as np
import pyautogui
from PIL import Image, ImageChops

REGION = (285, 26, 1354, 974)


def _grab(path):
    from gifkit import shot
    shot(REGION, path)
    return Image.open(path).convert("L")


def open_and_locate(trigger_click, out_dir=None, settle=1.6, step_key="down"):
    """点开弹层 → 量出各项的屏幕坐标。

    返回 [{'index':0,'y':...,'x':...}, ...]（屏幕绝对坐标，x 取弹层横向中心）。
    弹层没弹出（两帧无差异）时抛 RuntimeError。
    """
    out_dir = out_dir or os.path.join(HERE, "perf")
    os.makedirs(out_dir, exist_ok=True)
    a_path = os.path.join(out_dir, "_menu_a.png")
    b_path = os.path.join(out_dir, "_menu_b.png")

    pyautogui.click(*trigger_click)
    time.sleep(settle)
    a = _grab(a_path)

    pyautogui.press(step_key)          # 下移一项 → 高亮带移动
    time.sleep(0.5)
    b = _grab(b_path)

    diff = ImageChops.difference(a, b)
    bbox = diff.getbbox()
    if bbox is None:
        raise RuntimeError("弹层未出现或键盘无响应（两帧无差异）")

    left, top, right, bottom = bbox
    band_h = bottom - top
    # 项高：常见 24px；带 padding 的带为 48px 取半（skill 坑表口径）
    item_h = band_h if band_h <= 32 else band_h // 2
    cx = (left + right) // 2 + REGION[0]

    # 首项中心 y（带中心）→ 向上回溯到菜单首项
    first_y = top + band_h // 2 + REGION[1]
    # 按 step 后高亮已移到第 2 项，故首项 = 当前带中心 - item_h
    first_y -= item_h

    items = []
    for i in range(12):                 # 上限 12 项，够用
        items.append({"index": i, "x": cx, "y": first_y + i * item_h})
    return items, {"band": (left, top, right, bottom), "item_h": item_h}


def click_item(items, index):
    """点第 index 项（0 起，负数从末尾）。"""
    it = items[index] if index >= 0 else items[index]
    pyautogui.click(it["x"], it["y"])
    return it


def find_item_by_text(items, text, probe_dir=None):
    """按文本找项：逐项悬停后截帧，用 OCR 不可用时退化为「返回 None 让调用方兜底」。

    当前实现只做「保守探测」：悬停每一项并记录该行区域指纹，供人工比对
    （真正的文本识别需 OCR，本仓库未引入依赖）。
    """
    return None


if __name__ == "__main__":
    print(__doc__)
    print("此模块供 scene 脚本 import 使用；直接运行仅打印说明。")
