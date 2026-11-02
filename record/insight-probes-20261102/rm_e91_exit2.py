# -*- coding: utf-8 -*-
"""E91: 确认退出对话框 → Exit。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.4)
pyautogui.click(990, 563)
print("clicked exit")
time.sleep(8.0)
