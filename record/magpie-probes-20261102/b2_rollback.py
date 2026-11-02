# -*- coding: utf-8 -*-
"""Elevated scene: reopen About page, click rollback, handle confirm dialog."""
import os
import time

import pyautogui
import pygetwindow as pg
import pyperclip

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b2_%s.png" % name)
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
    time.sleep(0.4)


focus_ide()
pyautogui.hotkey("ctrl", "alt", "s")
time.sleep(2.0)
pyautogui.click(594, 209)
pyautogui.hotkey("ctrl", "a")
pyautogui.press("backspace")
pyperclip.copy("SQL Visualizer")
pyautogui.hotkey("ctrl", "v")
time.sleep(1.5)
pyautogui.press("enter")
time.sleep(1.5)
pyautogui.click(552, 544)   # About node
time.sleep(1.2)
shot("about_with_backup")
pyautogui.click(958, 405)   # rollback button
time.sleep(1.0)
shot("confirm_dialog")
print("DONE")
