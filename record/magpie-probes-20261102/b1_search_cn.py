# -*- coding: utf-8 -*-
"""Elevated scene: search settings for the Chinese display name of mask option."""
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


pyautogui.click(594, 209)
pyautogui.hotkey("ctrl", "a")
pyautogui.press("backspace")
time.sleep(0.3)
pyperclip.copy("展示与导出脱敏")
pyautogui.hotkey("ctrl", "v")
time.sleep(1.5)
shot("search_cn")
print("DONE")
