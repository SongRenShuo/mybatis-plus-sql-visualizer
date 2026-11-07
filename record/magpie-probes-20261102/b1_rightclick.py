# -*- coding: utf-8 -*-
"""Elevated scene: right-click the selected log entry to open context menu."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(1500, 196)   # ensure selection
time.sleep(0.4)
pyautogui.rightClick(1500, 196)
time.sleep(0.8)
shot("context_menu")
print("DONE")
