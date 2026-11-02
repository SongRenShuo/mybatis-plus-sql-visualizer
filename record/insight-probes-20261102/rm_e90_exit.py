# -*- coding: utf-8 -*-
"""E90: 收尾——先停 demo 应用，再优雅退出 IDE。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.6)
pyautogui.click(1582, 18)     # Stop 应用
print("clicked stop")
time.sleep(4.0)
print(rm_lib.shot("rm-90-stopped"))
pyautogui.hotkey("alt", "f4")  # 关 IDE
print("sent alt+f4")
time.sleep(6.0)
print(rm_lib.shot("rm-91-exit"))
