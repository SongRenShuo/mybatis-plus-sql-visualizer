# -*- coding: utf-8 -*-
"""Elevated scene: click export menu item, screenshot the followup dialog."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(1622, 467)   # 导出可见 SQL
time.sleep(1.0)
shot("export_dialog")
print("DONE")
