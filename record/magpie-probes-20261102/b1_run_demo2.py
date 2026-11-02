# -*- coding: utf-8 -*-
"""Elevated scene: focus IDE and press Shift+F10 to run current configuration."""
import ctypes
import os
import time

import pyautogui
import pygetwindow as pg

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")
u32 = ctypes.windll.user32


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


wins = [w for w in pg.getAllWindows()
        if w.title and "mybatis-plus-sql-visualizer-demo" in w.title and w.visible]
print("IDE_WINDOWS=%d" % len(wins))
if not wins:
    raise SystemExit(1)
w = wins[0]
try:
    w.activate()
except Exception:
    pass
u32.SetWindowPos(w._hWnd, -1, 0, 0, 0, 0, 0x2 | 0x1)
time.sleep(0.5)
pyautogui.click(900, 500)   # focus editor area
time.sleep(0.3)
pyautogui.hotkey("shift", "f10")
time.sleep(3.0)
shot("shiftf10_run")
print("DONE")
