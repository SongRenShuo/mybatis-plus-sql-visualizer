# -*- coding: utf-8 -*-
"""Elevated scene (3rd pass): the first click only focused the dialog. Click the
Trust button center again and verify by screenshot."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "trust_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


shot("pass3_before")
pyautogui.click(898, 583)
time.sleep(1.0)
pyautogui.click(898, 583)
time.sleep(1.5)
shot("pass3_after")
print("DONE")
