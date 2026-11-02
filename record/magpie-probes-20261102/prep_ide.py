# -*- coding: utf-8 -*-
"""Elevated scene: dismiss AI Assistant popup, maximize and topmost the IDE."""
import ctypes
import os
import time

import pyautogui
import pygetwindow as pg

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")
u32 = ctypes.windll.user32
SWP_NOMOVE = 0x2
SWP_NOSIZE = 0x1
HWND_TOPMOST = -1


def shot(name):
    p = os.path.join(TMP, "prep_%s.png" % name)
    pyautogui.screenshot().save(p)
    print("shot %s" % p)


def ide_windows():
    return [w for w in pg.getAllWindows()
            if w.title and "mybatis-plus-sql-visualizer-demo" in w.title and w.visible]


def main():
    wins = ide_windows()
    print("IDE_WINDOWS=%d" % len(wins))
    if not wins:
        return 1
    w = wins[0]
    # dismiss AI assistant popup: click its collapse '—' button region
    pyautogui.click(1590, 80)
    time.sleep(0.6)
    shot("after_dismiss_ai")
    try:
        w.maximize()
    except Exception as e:
        print("MAXIMIZE_ERR=%r" % e)
    time.sleep(0.8)
    hwnd = w._hWnd
    u32.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE)
    time.sleep(0.4)
    ex = u32.GetWindowLongW(hwnd, -20)  # GWL_EXSTYLE
    print("TOPMOST_SET=0x%X" % (ex & 0x8))
    shot("after_maximize")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
