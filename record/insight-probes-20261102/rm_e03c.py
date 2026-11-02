# -*- coding: utf-8 -*-
"""E03c: 先点编辑器取焦点，再 Ctrl+Shift+A；同时验证键盘是否到达（Esc 后输入被丢弃）。"""
import time
import pyautogui
import pyperclip
import rm_lib

t, pid, h, r = rm_lib.ide_window()
print("front:", rm_lib.front(h))
time.sleep(0.8)
pyautogui.click(600, 500)   # 编辑器正文
time.sleep(0.6)
pyautogui.press("escape")
time.sleep(0.4)
pyautogui.hotkey("ctrl", "shift", "a")
time.sleep(1.0)
print(rm_lib.shot("rm-05d-after-click-ctrlf"))
pyperclip.copy("Database")
pyautogui.hotkey("ctrl", "v")
time.sleep(1.3)
print(rm_lib.shot("rm-05e-typed"))
