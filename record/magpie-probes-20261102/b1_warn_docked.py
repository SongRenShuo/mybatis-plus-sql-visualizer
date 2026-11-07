# -*- coding: utf-8 -*-
"""Elevated scene: click quick-preview toggle then entry, shot docked preview."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(1394, 128)   # quick preview toggle (tooltip verified)
time.sleep(0.6)
pyautogui.click(1500, 196)   # single click entry
time.sleep(1.0)
shot("warn_docked")
print("DONE")
