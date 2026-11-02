# -*- coding: utf-8 -*-
"""Elevated scene: open the mask mode combo popup, screenshot for item pick."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


shot("combo_before")
pyautogui.click(1116, 528)   # mask mode combo
time.sleep(0.8)
shot("combo_popup")
print("DONE")
