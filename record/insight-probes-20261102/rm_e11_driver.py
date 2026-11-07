# -*- coding: utf-8 -*-
"""E11: 数据源对话框 —— 下载缺失驱动 → Test Connection。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.4)
pyautogui.click(837, 755)      # Download missing driver files
print("clicked download")
time.sleep(15.0)
print(rm_lib.shot("rm-13-driver-download"))
pyautogui.click(855, 789)      # Test Connection
print("clicked test")
time.sleep(10.0)
print(rm_lib.shot("rm-14-test-conn"))
