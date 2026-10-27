# -*- coding: utf-8 -*-
"""R12 执行 SQL 重录（T-DocsRefresh2023 / T-PreviewSideQuery P3a）。

前置（硬）：预览绑 mpj_demo（ANSI_QUOTES）；该库有 user / user_address / area 三表。

分镜（约 8 秒）：
1. 联表预览（LEFT JOIN + RIGHT JOIN），停 1.2s
2. 点「执行 SQL」→ 执行视图（真实结果网格），停 3.0s
3. 点执行视图头部「返回预览」→ 回到可编辑 SQL，停 1.4s
4. 关预览回编辑器，停 0.9s

坑：执行视图头部「返回预览」是蓝色主按钮，位于结果区顶部右侧（region 相对约 1215, 316）。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import Recorder, click, wait_stable
from robot import find_components

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "r12_execute.mp4")

OX, OY = REGION[0], REGION[1]
EXECUTE = (924, 807)
BACK_TO_PREVIEW = (1215, 316)
CLOSE = (1185, 806)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def main():
    if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
        raise RuntimeError("前置未满足：预览未打开")

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(1.2)
        click(*abs_pt(EXECUTE), move_dur=0.3)
        wait_stable(REGION)
        time.sleep(3.0)

        click(*abs_pt(BACK_TO_PREVIEW), move_dur=0.3, pauses=(0.15, 1.5))
        click(*abs_pt(CLOSE), move_dur=0.3)
        time.sleep(1.0)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
