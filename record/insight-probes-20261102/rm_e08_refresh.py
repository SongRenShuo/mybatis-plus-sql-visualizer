# -*- coding: utf-8 -*-
"""E08: 选中 mp_demo@localhost 后 Ctrl+F5 刷新（对未连接源=建立连接）。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
pyautogui.press("escape")
time.sleep(0.5)
pyautogui.click(1595, 205)
time.sleep(0.5)
pyautogui.hotkey("ctrl", "f5")
time.sleep(8.0)
print(rm_lib.shot("rm-10-after-refresh"))
