# -*- coding: utf-8 -*-
"""E10: 用 Shift+Enter（菜单标注的 Properties 快捷键）打开数据源属性。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
pyautogui.press("escape")
time.sleep(0.6)
pyautogui.click(1595, 205)
time.sleep(0.6)
pyautogui.hotkey("shift", "enter")
time.sleep(3.5)
for wt, wp, wh, wr in rm_lib.windows():
    if wr[2] - wr[0] > 300:
        print("win:", repr(wt)[:80], wr)
print(rm_lib.shot("rm-12-props2"))
