# -*- coding: utf-8 -*-
"""N03 SQL 日志 DDL 类型颜色标记（R15 补录计划 N3）。

前置（已完成）：demo 应用经 IDE 运行配置启动跑过一轮调度
（tick1 CREATE / tick6 DROP / 其余 SELECT），捕捉开启已收条目；应用已停、列表冻结。
列表当前停在 DDL 段：`CREATE TABLE IF NOT EXISTS mpsv_tmp_ddl_demo`（虚线色条）
与 `DROP TABLE IF EXISTS mpsv_tmp_ddl_demo`（虚线色条）夹着 SELECT 条目（实色条）。

分镜（约 11s）：
  1. 列表定格：DDL 虚线色条 vs SELECT 实色条同屏
  2. 点「全部 ▾」→ 类型过滤弹层（查询/插入/更新/删除/建表/删表 六档 + 只看慢查询 + 全不选）
  3. 全不选 → 只勾「建表」「删表」→ 关弹层 → 列表只剩 DDL
收尾：定格在过滤后的 DDL 列表，并把六档勾回。

坐标（批0 按当前布局活体实测，region 相对）：
  过滤按钮 robot 定位（`//div[contains(@text,'全部')]`）；弹层勾选 x=1147，
  六档行 y：查询 213 / 插入 241 / 更新 269 / 删除 297 / 建表 325 / 删表 353；
  「全不选」按钮 y≈464；关弹层点弹层外空白 (900, 560)。
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import pyautogui  # noqa: E402
from gifkit import Recorder, shot  # noqa: E402
from robot import click_component  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "n03_ddl_type_color.mp4")

CHK_X = 1147
ROWS = {"query": 213, "insert": 241, "update": 269, "delete": 297, "create": 325, "drop": 353}
BTN_NONE = (1170, 464)
BLANK = (900, 560)


def ap(x, y):
    return (x + REGION[0], y + REGION[1])


def open_filter():
    click_component("//div[contains(@text,'全部')]")
    time.sleep(1.4)


def toggle(row_key):
    pyautogui.click(*ap(CHK_X, ROWS[row_key]))
    time.sleep(0.45)


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    time.sleep(0.5)

    pyautogui.moveTo(*ap(1300, 300), duration=0.4)
    time.sleep(0.5)

    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 1. DDL 虚线色条 + SELECT 实色条同屏定格
        pyautogui.moveTo(*ap(700, 200), duration=0.6)
        time.sleep(2.2)
        # 2. 类型过滤弹层
        open_filter()
        time.sleep(1.4)
        # 3. 全不选 → 只勾建表/删表
        pyautogui.click(*ap(*BTN_NONE))
        time.sleep(0.8)
        toggle("create")
        toggle("drop")
        time.sleep(0.8)
        # 4. 关弹层 → 列表只剩 DDL
        pyautogui.click(*ap(*BLANK))
        time.sleep(2.6)
        shot(REGION, os.path.join(HERE, "perf", "n03_final.png"))
    finally:
        print("frames:", r.stop())

    # ---- 收尾（不入镜）：六档勾回 ----
    open_filter()
    pyautogui.click(*ap(*BTN_NONE))
    time.sleep(0.6)
    for key in ROWS:
        toggle(key)
    pyautogui.click(*ap(*BLANK))
    time.sleep(0.6)


if __name__ == "__main__":
    main()
