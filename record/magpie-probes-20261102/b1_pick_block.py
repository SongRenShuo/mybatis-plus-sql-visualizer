# -*- coding: utf-8 -*-
"""Elevated scene: pick BLOCK, apply, OK, then screenshot the log list."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(1090, 629)   # 阻断（隐藏 SQL）
time.sleep(0.5)
pyautogui.click(1399, 854)   # Apply
time.sleep(0.8)
pyautogui.click(1231, 854)   # OK
time.sleep(1.2)
shot("block_list")
print("DONE")
