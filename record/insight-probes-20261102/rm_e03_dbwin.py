# -*- coding: utf-8 -*-
"""E03: 打开 Database 工具窗（Find Action 搜 Database）。"""
import time
import pyautogui
import pyperclip
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.5)
pyautogui.hotkey("ctrl", "shift", "a")
time.sleep(1.2)
pyperclip.copy("Database")
time.sleep(0.3)
pyautogui.hotkey("ctrl", "v")
time.sleep(1.5)
print(rm_lib.shot("rm-05-findaction"))
