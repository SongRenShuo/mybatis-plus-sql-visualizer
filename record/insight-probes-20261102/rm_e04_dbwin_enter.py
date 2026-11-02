# -*- coding: utf-8 -*-
"""E04: 确认 Find Action 的 Database 项 → 打开 Database 工具窗。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
pyautogui.press("enter")
time.sleep(2.5)
print(rm_lib.shot("rm-06-dbwindow"))
