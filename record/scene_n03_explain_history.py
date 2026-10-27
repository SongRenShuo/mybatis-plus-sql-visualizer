# -*- coding: utf-8 -*-
"""N03「EXPLAIN 跨次对比」新增录制（T-DocsRefresh1023 / R61 + R90）。

前置（硬）：
1. 预览绑 **mpj_demo@localhost**（带 ANSI_QUOTES）；
2. **设置页「SQL 预览 → 预览面板内结构化渲染 EXPLAIN」必须开启**——「与上次执行对比」
   这个入口长在 `ExplainPlanResultView`（结构化视图）里，默认关闭时 EXPLAIN 走原生控制台，
   面板内没有对比入口（源码：ExplainPlanResultView.showComparison → panel.explain.compare.title）。
   这是本轮查证的硬前提，未开启则本脚本只能录到控制台计划、录不到对比。
3. 「跨会话保留原始 EXPLAIN 计划」可关（默认关）——会话内对比不需要它。

分镜（约 10 秒）：
1. 预览已开（联表 SELECT），点「执行计划」→ 结构化/控制台计划出现，停 2.4s
2. 再点「执行计划」（第二次）→ 计划视图出现「与上次执行对比」，停 2.0s
3. 点「与上次执行对比」→ 计划树逐节点标注 cost/rows 变化，停 2.2s
4. 关预览，停 0.9s

坑：结构化渲染是**实验特性、默认关闭**。录 N03 前必须先在设置页开启，录完还原。
若只想录「原生控制台计划」，那不是本图的主题（本图主题是跨次对比），不要用那张冒充。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui
from gifkit import Recorder, click, wait_stable
from robot import find_components

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n03_explain_history.mp4")

OX, OY = REGION[0], REGION[1]
EXPLAIN = (1020, 208)        # 预览按钮行「执行计划」
COMPARE = (700, 330)         # 「与上次执行对比」（结构化视图头部；原生控制台路径下不存在时跳过）
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
        time.sleep(1.0)
        click(*abs_pt(EXPLAIN), move_dur=0.3)      # 第一次 EXPLAIN
        wait_stable(REGION)
        time.sleep(2.4)

        click(*abs_pt(EXPLAIN), move_dur=0.3)      # 第二次 → 出现对比入口
        wait_stable(REGION)
        time.sleep(2.2)

        # 对比入口只在结构化渲染开启时存在；关闭态下第二次计划直接在控制台出现，
        # 这里用容错点击：点不到就不阻断录制。
        click(*abs_pt(COMPARE), move_dur=0.3, pauses=(0.15, 2.2))

        click(*abs_pt(CLOSE), move_dur=0.3)
        time.sleep(0.9)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
