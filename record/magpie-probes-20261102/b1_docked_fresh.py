# -*- coding: utf-8 -*-
"""Elevated scene: close docked preview, re-toggle, re-click entry (fresh render)."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(1828, 542)   # close docked preview
time.sleep(0.5)
pyautogui.click(1394, 128)   # quick preview toggle
time.sleep(0.6)
pyautogui.click(1500, 196)   # click entry (fresh render)
time.sleep(1.0)
shot("docked_fresh")
print("DONE")
