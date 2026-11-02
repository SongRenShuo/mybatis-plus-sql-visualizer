# -*- coding: utf-8 -*-
"""Elevated scene: select first log entry, Ctrl+C copy, screenshot.

Clipboard content is read afterwards by the caller (non-elevated) via clipread.
"""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(1500, 196)   # select first entry (its first SQL line)
time.sleep(0.5)
pyautogui.hotkey("ctrl", "c")
time.sleep(0.8)
shot("copied")
print("DONE")
