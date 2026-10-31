# -*- coding: utf-8 -*-
"""N03 SQL 日志 DDL 类型颜色标记（R15 补录计划 N3，P2 可裁项）。

分镜（约 10s）：
  1. SQL 日志工具窗列表里出现 CREATE / DROP 类 DDL 条目（虚线色条）与 DML 条目（实色条）同屏
  2. 点「全部 ▾」类型过滤 → 弹窗里两档 DDL（CREATE·ALTER·RENAME / DROP·TRUNCATE）勾选形态
  3. 只勾 DDL → 列表只剩 DDL 条目；勾回 → 还原

前置（批 0 踩点）：
  · demo 控制台先跑出 DDL 日志：原生控制台执行 CREATE TABLE / DROP TABLE 临时表
    （如 `CREATE TABLE mpsv_tmp_ddl_demo (id INT)` → `DROP TABLE mpsv_tmp_ddl_demo`），
    让增强捕获收进 DDL 条目；录完无需还原（表已删）
  · 「捕捉」开关已开（悬停工具栏绿色三角，坑 36），诊断行确认在收条目
  · 类型过滤弹窗按钮坐标、色条判定带现标

风险：DDL 条目被旧日志淹没 → 先「清空」再触发 DDL；类型过滤弹窗是 JBPopup（robot 不可达），
坐标按像素带现量。
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import pyautogui  # noqa: E402
from gifkit import Recorder, shot  # noqa: E402
from nav import force_en_us  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "n03_ddl_type_color.mp4")

# TODO(批0 踩点后回填)：
FILTER_BTN = None        # 「全部 ▾」类型过滤按钮（region 相对）
DDL_CHECK_A = None       # 过滤弹窗第一档 DDL 复选框
DDL_CHECK_B = None       # 过滤弹窗第二档 DDL 复选框


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()
    time.sleep(0.5)

    # ---- prep（不入镜）：SQL 日志工具窗就位 + 捕捉开 + DDL 条目在列表顶部 ----
    # （DDL 由批 0 在原生控制台跑临时表产生；此处假设列表已含 CREATE/DROP 条目）
    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 1. 打开类型过滤弹窗
        pyautogui.click(*FILTER_BTN)
        time.sleep(1.2)
        # 2. 展示两档 DDL 勾选形态（停帧）
        time.sleep(1.6)
        # 3. 只勾 DDL 两档 → 应用 → 列表只剩 DDL
        pyautogui.click(*DDL_CHECK_A)
        time.sleep(0.5)
        pyautogui.click(*DDL_CHECK_B)
        time.sleep(1.4)
        # （确认/关闭弹窗的动作按批 0 实际弹窗形态补）
        time.sleep(1.6)
        shot(REGION, os.path.join(HERE, "perf", "n03_final.png"))
        # 4. 勾回还原（不入镜的 prep 段做）
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    main()
