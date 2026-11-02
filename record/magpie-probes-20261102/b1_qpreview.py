# -*- coding: utf-8 -*-
"""Elevated scene: toggle quick-preview toolbar icon, single-click entry, shot bottom preview."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(1344, 128)   # quick preview toggle in log toolbar
time.sleep(0.6)
shot("qpreview_toggled")
pyautogui.click(1500, 196)   # click entry
time.sleep(1.0)
shot("qpreview_bottom")
print("DONE")
