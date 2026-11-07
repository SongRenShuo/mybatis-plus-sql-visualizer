# -*- coding: utf-8 -*-
"""Elevated scene: MASK -> OFF -> MASK (force cache key change), then observe list."""
import os
import time

import pyautogui
import pygetwindow as pg
import pyperclip

pyautogui.FAILSAFE = False
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")


def shot(name):
    p = os.path.join(TMP, "b1_%s.png" % name)
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
pyperclip.copy("展示与导出脱敏")
pyautogui.hotkey("ctrl", "v")
time.sleep(1.6)
pyautogui.click(1116, 528)
time.sleep(0.8)
pyautogui.click(1090, 558)   # 关闭
time.sleep(0.4)
pyautogui.click(1399, 854)   # Apply (OFF)
time.sleep(0.6)
pyautogui.click(1116, 528)
time.sleep(0.8)
pyautogui.click(1055, 580)   # 打码
time.sleep(0.4)
pyautogui.click(1399, 854)   # Apply (MASK)
time.sleep(0.6)
pyautogui.click(1231, 854)   # OK
time.sleep(1.2)
shot("cycle_mask_list")
print("DONE")
