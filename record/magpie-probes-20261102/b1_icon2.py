# -*- coding: utf-8 -*-
"""Elevated scene: close mask-rule dialog, try quick-preview icon at 1370."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(1280, 722)   # 关闭
time.sleep(0.6)
pyautogui.click(1370, 128)   # next candidate icon
time.sleep(0.8)
shot("icon2")
pyautogui.click(1500, 196)
time.sleep(1.0)
shot("after_entry_click")
print("DONE")
