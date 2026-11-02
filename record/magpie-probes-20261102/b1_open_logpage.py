# -*- coding: utf-8 -*-
"""Elevated scene: open SQL Log settings page (SQL Visualizer -> SQL 日志)."""
import os
import time

import pyautogui

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


pyautogui.click(794, 480)   # 'SQL 日志' section link
time.sleep(1.2)
shot("log_page")
print("DONE")
