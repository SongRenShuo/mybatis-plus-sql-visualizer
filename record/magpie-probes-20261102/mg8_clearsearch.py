# -*- coding: utf-8 -*-
"""Elevated scene: clear settings search, shot full tree."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(594, 209)
pyautogui.hotkey("ctrl", "a")
pyautogui.press("backspace")
time.sleep(1.0)
shot("mg8_tree_full")
print("DONE")
