# -*- coding: utf-8 -*-
"""E05: 关掉误开的系统菜单 → 编辑器取焦 → Ctrl+Shift+A → Database → Enter → 截帧。"""
import time
import pyautogui
import pyperclip
import rm_lib

t, pid, h, r = rm_lib.ide_window()
print("front:", rm_lib.front(h))
time.sleep(0.5)
pyautogui.press("escape")
time.sleep(0.5)
pyautogui.click(600, 500)
time.sleep(0.6)
pyautogui.hotkey("ctrl", "shift", "a")
time.sleep(1.0)
pyperclip.copy("Database")
pyautogui.hotkey("ctrl", "v")
time.sleep(1.2)
print(rm_lib.shot("rm-07a-fa"))
pyautogui.press("enter")
time.sleep(3.0)
print(rm_lib.shot("rm-07b-dbwin"))
