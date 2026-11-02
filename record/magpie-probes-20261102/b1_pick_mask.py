# -*- coding: utf-8 -*-
"""Elevated scene: pick MASK item, click Apply, screenshot."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(1055, 580)   # 'mask (***)'
time.sleep(0.6)
shot("mask_picked")
pyautogui.click(1399, 854)   # Apply
time.sleep(1.0)
shot("applied")
print("DONE")
