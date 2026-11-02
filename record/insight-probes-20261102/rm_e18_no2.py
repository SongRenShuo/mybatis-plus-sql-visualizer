# -*- coding: utf-8 -*-
"""E18: 点 No（实际坐标 1088,567）→ 换第 20 行预览。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
pyautogui.click(1088, 567)     # No
time.sleep(1.8)
print(rm_lib.shot("rm-25-after-no"))
pyautogui.click(700, 513)
time.sleep(0.8)
pyautogui.hotkey("alt", "shift", "q")
time.sleep(4.0)
print(rm_lib.shot("rm-26-preview2"))
