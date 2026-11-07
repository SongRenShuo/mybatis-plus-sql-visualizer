# -*- coding: utf-8 -*-
"""E14: 光标落在 selectOrderWithUserByEw（第 13 行）→ Alt+Shift+Q 触发预览。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
pyautogui.click(700, 356)      # 第 13 行 SQL 文本
time.sleep(0.8)
print(rm_lib.shot("rm-19-cursor"))
pyautogui.hotkey("alt", "shift", "q")
time.sleep(4.0)
print(rm_lib.shot("rm-20-preview"))
