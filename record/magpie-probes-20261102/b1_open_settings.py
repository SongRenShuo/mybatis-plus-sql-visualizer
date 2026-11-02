# -*- coding: utf-8 -*-
"""Elevated scene: focus IDE, open Settings, screenshot."""
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


def ide_windows():
    return [w for w in pg.getAllWindows()
            if w.title and "mybatis-plus-sql-visualizer-demo" in w.title and w.visible]


def main():
    wins = ide_windows()
    print("IDE_WINDOWS=%d" % len(wins))
    if not wins:
        return 1
    w = wins[0]
    try:
        w.activate()
    except Exception:
        pass
    u32.SetWindowPos(w._hWnd, -1, 0, 0, 0, 0, 0x2 | 0x1)
    time.sleep(0.5)
    pyautogui.hotkey("ctrl", "alt", "s")
    time.sleep(2.0)
    shot("settings_open")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
