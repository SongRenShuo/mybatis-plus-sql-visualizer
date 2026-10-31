# -*- coding: utf-8 -*-
"""N04 参数预设永久绑定方法（R15 补录计划 N4）。

前置（已完成）：预设 `tom-query` 已在 `WrapperExtServiceImpl.listByNotAndNested`
（username 参数化链）上建好；预览开着，combo=tom-query、参数输入框为空、
SQL 呈 `username LIKE ?` 占位形态。

分镜（约 10s）：
  1. 预设下拉选 tom-query → username 填入 tom、SQL 实时变 `'%tom%'`
  2. 手工改写 SQL 文本（deleted = 0 → 1）→ 预设仍命中
  3. 预设下拉再选 tom-query → 参数照常回填（绑定在方法不在文本）
收尾：鼠标停右侧 SQL Visualizer 面板空白区。
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import numpy as np  # noqa: E402
import pyautogui  # noqa: E402
from PIL import Image  # noqa: E402
from gifkit import Recorder, shot  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "n04_preset_binding.mp4")
TMP = os.path.join(HERE, "perf", "_n04_probe.png")

COMBO = (226, 332)          # 预设下拉（region 相对）
COMBO_POP = (150, 340, 300, 430)   # 下拉弹层扫描带
ROW_DEFAULT = 368           # 弹层第一行 默认预设（批0 实测）
ROW_TOM = 400               # 弹层第二行 tom-query（批0 实测）
SQL_DEL_ZERO = (700, 718)   # 末行 `AND deleted = 0` 的 0 右侧


def abs_pt(rx, ry):
    return (rx + REGION[0], ry + REGION[1])


def grab():
    shot(REGION, TMP)
    return np.asarray(Image.open(TMP).convert("L"), dtype=int)


def popup_rows(max_wait=8.0):
    """下拉弹层的条目行（亮带中心），region 相对 y 列表。"""
    x0, y0, x1, y1 = COMBO_POP
    deadline = time.time() + max_wait
    while time.time() < deadline:
        img = grab()
        sub = img[y0:y1, x0:x1]
        rows = sub.mean(axis=1)
        bands, inb, st = [], False, 0
        for i, v in enumerate(rows):
            if v > 30 and not inb:
                st, inb = i, True
            elif v <= 30 and inb:
                if 3 <= i - st <= 40:
                    bands.append(y0 + (st + i) // 2)
                inb = False
        if inb and 3 <= (y1 - y0) - st <= 40:
            bands.append(y0 + (st + (y1 - y0)) // 2)
        if len(bands) >= 2:
            return bands
        time.sleep(0.3)
    raise RuntimeError("预设下拉弹层未出现（ bands=%r）" % bands[:4])


def pick_preset(direction):
    """键盘选预设：点开下拉（首击可能只给焦点，重试），Up=默认预设 / Down=tom-query + Enter。

    方向键路径对 JBPopup/JComboBox 稳定；鼠标点项在触控板精度的边缘反复落空（实测 3 连败）。
    回填是否生效由调用方的像素断言兜底重试。
    """
    key = 'up' if direction == 'default' else 'down'
    pyautogui.click(*abs_pt(*COMBO))
    time.sleep(1.0)
    pyautogui.press(key)
    time.sleep(0.4)
    pyautogui.press('enter')
    time.sleep(1.5)


def field_filled():
    """username 输入框里有字（框内亮度带 >阈值）。"""
    img = grab()
    box = img[425:450, 275:370]
    return (box > 90).sum() > 30


def apply_tom_query():
    """切到 tom-query 并确认回填（最多三轮）。"""
    for _ in range(3):
        pick_preset('default')
        pick_preset('tom')
        if field_filled():
            return
    raise RuntimeError("预设回填未生效")


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    time.sleep(0.5)

    # 开录前鼠标先停安全区（停在 SQL 文本上会带悬停高亮入镜）
    pyautogui.moveTo(REGION[0] + 950, REGION[1] + 700, duration=0.4)
    time.sleep(0.6)
    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 1. 切到默认预设（清参数）再切回 tom-query → 参数回填、SQL 实时变
        apply_tom_query()
        # 2. 改写 SQL 文本：deleted = 0 → 1（可见、可逆）
        pyautogui.click(*abs_pt(*SQL_DEL_ZERO))
        time.sleep(0.5)
        pyautogui.press("backspace")
        time.sleep(0.3)
        pyautogui.typewrite("1", interval=0.15)
        time.sleep(1.6)
        # 3. 预设仍命中：先收掉改写触发的补全弹窗，再切换→回填
        pyautogui.press('esc')
        time.sleep(0.6)
        apply_tom_query()
        time.sleep(1.2)
        # 收尾
        pyautogui.moveTo(REGION[0] + 950, REGION[1] + 700, duration=0.5)
        time.sleep(1.0)
        shot(REGION, os.path.join(HERE, "perf", "n04_final.png"))
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    main()
