# -*- coding: utf-8 -*-
"""N02「DML 影响面预演」新增录制（T-DocsRefresh1023 / R42 + T-DmlImpactProminence）。

前置（硬）：
- 预览必须已绑到 **mpj_demo@localhost**（该库 URL 带 sql_mode=ANSI_QUOTES，MPJ 的双引号表名才合法）；
- mpj_demo 有活控制台（打开过 Query Console）——绑定链优先取活控制台。

分镜（约 9 秒）：
1. UPDATE 预览打开（面板顶部 Meta 行可见目标表），停 1.2s
2. 「更多」→「预估影响行数」→ 顶部蓝色横幅给出行数，停 2.2s
3. 点蓝色主行动「复制 SQL 并关闭」，停 1.2s

坑（本轮实测）：
- 「更多」菜单项必须先 **悬停再点**（JBPopup 靠 hover 激活，直接 click 常只关掉菜单）；
- Escape 会连预览一起关掉，清残留菜单要 **点标题栏空白处**。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui
from gifkit import Recorder, click
from robot import find_components

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n02_dml_impact.mp4")

OX, OY = REGION[0], REGION[1]
MORE = (1223, 208)
ITEM_DML = (1233, 434)       # 「预估影响行数」菜单项（九项里最后一项）
TITLE_BLANK = (600, 161)     # 目标栏空白（关菜单不进预览）
COPY_AND_CLOSE = (1115, 806)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def hover_click(pt, hover_wait=0.55, after=1.2):
    sx, sy = abs_pt(pt)
    pyautogui.moveTo(sx, sy, duration=0.35)
    time.sleep(hover_wait)
    pyautogui.click(sx, sy)
    time.sleep(after)


def main():
    if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
        raise RuntimeError("前置未满足：预览未打开")

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(1.2)
        click(*abs_pt(TITLE_BLANK), move_dur=0.2, pauses=(0.1, 0.6))

        hover_click(MORE, after=1.3)
        hover_click(ITEM_DML, after=2.4)      # 结果横幅出现
        hover_click(COPY_AND_CLOSE, after=1.3)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
