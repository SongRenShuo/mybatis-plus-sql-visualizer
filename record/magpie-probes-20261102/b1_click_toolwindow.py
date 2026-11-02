# -*- coding: utf-8 -*-
"""Elevated scene: click first Find Action row to activate toolwindow, shot."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(741, 311)
time.sleep(1.5)
shot("toolwindow")
print("DONE")
