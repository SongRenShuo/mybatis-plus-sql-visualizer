# -*- coding: utf-8 -*-
"""Elevated scene: re-click entry to re-render docked preview (positive control)."""
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
time.sleep(0.4)
pyautogui.click(1500, 196)
time.sleep(1.0)
shot("docked_positive")
print("DONE")
