# -*- coding: utf-8 -*-
"""E09: 数据源 Properties 对话框（用于 Test Connection 取证）。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.4)
pyautogui.click(1595, 205)
time.sleep(0.4)
pyautogui.rightClick(1595, 205)
time.sleep(1.0)
pyautogui.click(1680, 235)   # Properties
time.sleep(3.0)
for wt, wp, wh, wr in rm_lib.windows():
    if "data source" in wt.lower() or "数据源" in wt:
        print("dialog:", repr(wt), wr)
print(rm_lib.shot("rm-11-props"))
