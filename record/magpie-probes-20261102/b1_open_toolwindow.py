# -*- coding: utf-8 -*-
"""Elevated scene: open SQL Visualizer toolwindow via Find Action."""
import ctypes
import os
import time

import pyautogui
import pygetwindow as pg
import pyperclip

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")
u32 = ctypes.windll.user32


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


wins = [w for w in pg.getAllWindows()
        if w.title and "mybatis-plus-sql-visualizer-demo" in w.title and w.visible]
if not wins:
    raise SystemExit(1)
w = wins[0]
try:
    w.activate()
except Exception:
    pass
u32.SetWindowPos(w._hWnd, -1, 0, 0, 0, 0, 0x2 | 0x1)
time.sleep(0.4)
pyautogui.hotkey("ctrl", "shift", "a")
time.sleep(1.0)
pyperclip.copy("sql visualizer")
pyautogui.hotkey("ctrl", "v")
time.sleep(1.2)
shot("findaction")
print("DONE")
