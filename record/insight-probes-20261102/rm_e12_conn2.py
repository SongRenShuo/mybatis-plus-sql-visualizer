# -*- coding: utf-8 -*-
"""E12: 关闭对话框 → 连接 mp_demo 与 mpj_demo_url 两个真实 MySQL 源。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
pyautogui.click(1150, 789)          # OK
time.sleep(2.5)
pyautogui.doubleClick(1595, 205)    # mp_demo@localhost
time.sleep(7.0)
print(rm_lib.shot("rm-15-mpdemo-connected"))
pyautogui.doubleClick(1595, 251)    # mpj_demo_url@localhost
time.sleep(7.0)
print(rm_lib.shot("rm-16-mpjdemo-connected"))
