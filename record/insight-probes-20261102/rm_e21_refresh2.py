# -*- coding: utf-8 -*-
"""E21: 选中 mp_demo 后点工具栏 Refresh（1611,98）建立真实连接。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
pyautogui.click(1595, 205)     # 选中 mp_demo@localhost
time.sleep(0.6)
pyautogui.click(1611, 98)      # Refresh
time.sleep(10.0)
print(rm_lib.shot("rm-31-refresh-connect"))
