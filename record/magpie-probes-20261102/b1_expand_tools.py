# -*- coding: utf-8 -*-
"""Elevated scene: expand Tools node in Settings tree, screenshot."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


shot("tools_before")
pyautogui.click(519, 420)   # Tools root
time.sleep(0.3)
pyautogui.click(490, 420)   # expand arrow
time.sleep(1.0)
shot("tools_after")
print("DONE")
