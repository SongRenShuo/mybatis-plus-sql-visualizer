# -*- coding: utf-8 -*-
"""E23: 右键 mp_demo → SQL Scripts 子菜单（看能否开 Query Console）。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.4)
pyautogui.click(1595, 205)
time.sleep(0.5)
pyautogui.rightClick(1595, 205)
time.sleep(1.2)
pyautogui.moveTo(1690, 445)   # SQL Scripts
time.sleep(1.8)
print(rm_lib.shot("rm-33-sqlscripts-submenu"))
