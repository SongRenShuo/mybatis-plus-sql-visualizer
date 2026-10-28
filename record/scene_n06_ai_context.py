# -*- coding: utf-8 -*-
"""N06「复制为 AI 上下文」新增录制（T-DocsRefresh1023 / R37 + T-AiContextLegibility B）。

分镜（约 7 秒）：
1. 预览面板已开（MPJ 三表 JOIN），停 1.0s
2. 「更多」→「复制为 AI 上下文」→ 通知「已脱敏敏感字面量 N 处」，停 1.4s
3. 「更多」→「复制为 AI 上下文（含表列名）」→ 通知，停 1.4s
4. 关预览回编辑器，停 0.9s

前置（prep）：预览已打开（由调用方保证）。
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
OUT = os.path.join(BASE, "perf", "n06_ai_context.mp4")

OX, OY = REGION[0], REGION[1]
MORE = (1223, 208)
ITEM_AI = (1233, 388)          # 复制为 AI 上下文
ITEM_AI_IDS = (1233, 411)      # 复制为 AI 上下文（含表列名）
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
        time.sleep(0.8)
        # 清掉残留菜单：点标题栏空白
        click(*abs_pt((600, 161)), move_dur=0.2, pauses=(0.1, 0.6))

        click(*abs_pt(MORE), move_dur=0.3, pauses=(0.2, 1.2))
        click(*abs_pt(ITEM_AI), move_dur=0.25, pauses=(0.15, 1.6))

        click(*abs_pt((600, 161)), move_dur=0.2, pauses=(0.1, 0.5))
        click(*abs_pt(MORE), move_dur=0.3, pauses=(0.2, 1.2))
        click(*abs_pt(ITEM_AI_IDS), move_dur=0.25, pauses=(0.15, 1.6))

        click(*abs_pt(CLOSE), move_dur=0.3)
        time.sleep(0.9)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
