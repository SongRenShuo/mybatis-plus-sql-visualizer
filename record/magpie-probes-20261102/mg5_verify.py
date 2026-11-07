# -*- coding: utf-8 -*-
"""Elevated scene: focus IDE, open settings, search mask option, shot recovered state."""
import ctypes
import os
import time

import pyautogui
import pygetwindow as pg
import pyperclip

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


def focus_ide():
    wins = [w for w in pg.getAllWindows()
            if w.title and "mybatis-plus-sql-visualizer-demo" in w.title and w.visible]
    if not wins:
        raise SystemExit(1)
    try:
        wins[0].activate()
    except Exception:
        pass
    ctypes.windll.user32.SetWindowPos(wins[0]._hWnd, -1, 0, 0, 0, 0, 0x2 | 0x1)
    time.sleep(0.5)


focus_ide()
pyautogui.hotkey("ctrl", "alt", "s")
time.sleep(2.5)
pyautogui.click(594, 209)
pyautogui.hotkey("ctrl", "a")
pyautogui.press("backspace")
pyperclip.copy("展示与导出脱敏")
pyautogui.hotkey("ctrl", "v")
time.sleep(1.8)
shot("mg5_recovered")
print("DONE")
