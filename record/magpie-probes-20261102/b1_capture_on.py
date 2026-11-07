# -*- coding: utf-8 -*-
"""Elevated scene: enable capture toggle in log panel toolbar."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(1266, 128)   # capture toggle (green triangle)
time.sleep(1.0)
shot("capture_on")
print("DONE")
