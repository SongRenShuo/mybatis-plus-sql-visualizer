# -*- coding: utf-8 -*-
"""r04 归一 probe：开面板 → 点「全选」→ 截图供主控目视 → 取消收起。
复选框像素检测在本面板判不准（20261026 实测），改用面板自带全选按钮（确定性大目标）。
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import pyautogui
from gifkit import Recorder, click, shot

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

REGION = (285, 26, 1354, 974)
OX, OY = REGION[0], REGION[1]
POPUP_UPDATE_ROW = (623, 452)
POPUP_OK = (839, 644)
SELECT_ALL = (319, 292)     # 动态条件「全选」按钮（region 相对）
CANCEL = (1212, 798)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def main():
    pyautogui.hotkey("alt", "shift", "q")
    time.sleep(1.4)
    click(*abs_pt(POPUP_UPDATE_ROW), move_dur=0.3, pauses=(0.2, 0.7))
    click(*abs_pt(POPUP_OK), move_dur=0.25, pauses=(0.15, 1.8))
    time.sleep(0.5)
    click(*abs_pt(SELECT_ALL), move_dur=0.25)
    time.sleep(0.8)
    shot(REGION, os.path.join(HERE, "perf", "_norm_r04_after.png"))
    click(*abs_pt(CANCEL), move_dur=0.3)
    time.sleep(1.0)
    print("normalize probe done")


if __name__ == "__main__":
    main()
