# -*- coding: utf-8 -*-
"""E37: SQL 日志工具栏溢出「>」菜单，看 UI 增量入口（快速预览/交流群等）。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.5)
pyautogui.click(1692, 118)
time.sleep(1.5)
print(rm_lib.shot("rm-62-more-menu"))
