# -*- coding: utf-8 -*-
"""E03b: 逐步取证 —— 先确认键盘事件到达（Ctrl+Shift+A 后立即截图）。"""
import time
import pyautogui
import pyperclip
import rm_lib

t, pid, h, r = rm_lib.ide_window()
print("front:", rm_lib.front(h))
time.sleep(1.0)
pyautogui.hotkey("ctrl", "shift", "a")
time.sleep(0.9)
print(rm_lib.shot("rm-05b-after-ctrlf"))
time.sleep(0.5)
pyperclip.copy("Database")
pyautogui.hotkey("ctrl", "v")
time.sleep(1.4)
print(rm_lib.shot("rm-05c-typed"))
