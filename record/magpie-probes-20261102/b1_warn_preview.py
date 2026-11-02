# -*- coding: utf-8 -*-
"""Elevated scene: double-click entry to open full preview (WARN first line)."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(1500, 196)
time.sleep(0.3)
pyautogui.click(1500, 196)
time.sleep(1.2)
shot("warn_preview")
pyautogui.press("escape")
time.sleep(0.6)
print("DONE")
