# -*- coding: utf-8 -*-
"""N05 调用栈·定时任务线程帧（R15 补录计划 N5）。

前置（已完成）：demo 应用经 IDE 运行配置启动，`@Scheduled` 每 3s 查一次 user；
捕捉开启，列表里有大量 `[SchedulerDemoTask.java:40]` 条目（线程 scheduling-1）。
栈弹层是**预览面板 meta 行的悬停弹层**（`StackFrameHover`，400ms 防抖），
不是列表 chip 的点击——这是本张的第一条实测结论。

分镜（约 11s）：
  1. 列表定格：调度条目（SchedulerDemoTask.java:40）
  2. 双击条目 → 完整预览面板打开（meta 行出现「调用栈: org.mybatis.spring.Sq…」）
  3. 悬停「调用栈」→ 弹层标题 `调用栈 · scheduling-1`（线程名帧）
  4. 点栈帧 `pollUser cn.song.mybatisplusdemo.task.SchedulerDemoTask:40` → 跳源码
收尾：定格在 SchedulerDemoTask.java 第 40 行。

坐标（批0 按当前布局实测，region 相对）：
  列表条目体 (500, 275)；预览 meta 行「调用栈」chip (420, 220)；
  弹层内 pollUser 帧行 (500, 429)。
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import pyautogui  # noqa: E402
from gifkit import Recorder, shot  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "n05_stack_scheduler.mp4")

LIST_ROW = (500, 275)
STACK_CHIP = (420, 220)
FRAME_POLLUSER = (500, 429)


def ap(x, y):
    return (x + REGION[0], y + REGION[1])


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    time.sleep(0.5)

    # ---- prep（不入镜）：关掉可能开着的预览，鼠标停安全区 ----
    pyautogui.press("esc")
    time.sleep(1.0)
    pyautogui.moveTo(*ap(1300, 600), duration=0.4)
    time.sleep(0.5)

    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 1. 列表定格：调度条目
        pyautogui.moveTo(*ap(*LIST_ROW), duration=0.6)
        time.sleep(2.0)
        # 2. 双击开预览（3.4~3.6s 画完）
        pyautogui.doubleClick(*ap(*LIST_ROW))
        time.sleep(3.8)
        # 3. 悬停 meta 行的「调用栈」→ 弹层（标题带线程名）
        pyautogui.moveTo(*ap(*STACK_CHIP), duration=0.6)
        time.sleep(2.2)
        # 4. 点 pollUser 栈帧 → 跳源码
        pyautogui.click(*ap(*FRAME_POLLUSER))
        time.sleep(2.4)
        # 关预览露出源码（跳转落点：SchedulerDemoTask.java 第 40 行）
        pyautogui.click(*ap(1201, 798))
        time.sleep(1.8)
        pyautogui.moveTo(*ap(700, 500), duration=0.6)
        time.sleep(1.6)
        shot(REGION, os.path.join(HERE, "perf", "n05_final.png"))
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    main()
