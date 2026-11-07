# -*- coding: utf-8 -*-
"""Elevated scene: hover candidate toolbar icons to reveal tooltips."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


for i, x in enumerate([1394, 1419, 1444, 1470]):
    pyautogui.moveTo(x, 128)
    time.sleep(1.4)
    shot("hover_%d" % x)
print("DONE")
