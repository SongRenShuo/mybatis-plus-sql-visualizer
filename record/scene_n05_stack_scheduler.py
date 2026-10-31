# -*- coding: utf-8 -*-
"""N05 调用栈跳转·定时任务线程帧（R15 补录计划 N5，P3 可裁项）。

分镜（约 12s）：
  1. demo 应用跑起来（IDE 内启动，坑 74），@Scheduled 任务触发一条 SQL 进增强捕获
  2. SQL 日志列表点该条目的调用栈入口 → 弹层标题线程名 `scheduling-1`
  3. 点栈帧跳转到代码行

前置（demo 工程改动，走插件仓正常流程单独提交）：
  · demo 加一个 @Scheduled 定时任务（fixedRate=2s），体内调一次 mapper 查询
    （落在 mybatis-plus-demo 模块，如 UserMapper.selectById(1)）+ 主类 @EnableScheduling
  · 改动单独提交、录制完成后保留（这是演示能力的一部分，不是录制态污染）
风险：调度任务 2s 一条会持续刷列表——录「类型过滤/其他条目」类镜头时先停应用；
  本张最后录，录完即停应用（坑 74：IDE 内起的应用非提权杀不掉，先想好停法）。
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
OUT = os.path.join(HERE, "perf", "n05_stack_scheduler.mp4")

# TODO(批0 踩点后回填)：
STACK_ENTRY = None       # 列表里调度条目的调用栈入口（region 相对）
STACK_FRAME = None       # 弹层里可跳转的栈帧项


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()
    time.sleep(0.5)

    # ---- prep（不入镜）：IDE 内启动 demo 应用（坑 74 链路：配置下拉→Recent→绿三角）----
    #      等调度任务产出 ≥2 条 SQL 日志；SQL 日志工具窗就位、捕捉开
    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 1. 鼠标滑到调度条目（线程名 scheduling-1 在列表元信息行）
        pyautogui.moveTo(abs_x(STACK_ENTRY), abs_y(STACK_ENTRY), duration=0.6)
        time.sleep(1.4)
        # 2. 点调用栈入口 → 弹层（标题线程名 scheduling-1）
        pyautogui.click(abs_x(STACK_ENTRY), abs_y(STACK_ENTRY))
        time.sleep(1.6)
        shot(REGION, os.path.join(HERE, "perf", "n05_stack_popup.png"))
        # 3. 点栈帧 → 跳转到代码行
        pyautogui.click(abs_x(STACK_FRAME), abs_y(STACK_FRAME))
        time.sleep(2.0)
        shot(REGION, os.path.join(HERE, "perf", "n05_final.png"))
    finally:
        print("frames:", r.stop())


def abs_x(p):
    return p[0] + REGION[0]


def abs_y(p):
    return p[1] + REGION[1]


if __name__ == "__main__":
    main()
