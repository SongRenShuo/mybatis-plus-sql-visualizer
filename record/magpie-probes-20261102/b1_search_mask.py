# -*- coding: utf-8 -*-
"""Elevated scene: search settings for 'mask' via clipboard paste."""
import os
import time

import pyautogui
import pyperclip

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(594, 209)    # settings search field
time.sleep(0.4)
pyperclip.copy("mask")
pyautogui.hotkey("ctrl", "v")
time.sleep(1.2)
shot("search_mask")
print("DONE")
