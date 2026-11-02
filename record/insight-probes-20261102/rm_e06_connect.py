# -*- coding: utf-8 -*-
"""E06: 连接 mp_demo@localhost（双击数据源节点）。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.5)
pyautogui.doubleClick(1595, 205)
time.sleep(6.0)
print(rm_lib.shot("rm-08-mpdemo-connect"))
