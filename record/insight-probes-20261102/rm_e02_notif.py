# -*- coding: utf-8 -*-
"""E02: 关掉三条启动通知（数据库提示/中文提示/脚本启动器提示）。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.4)
for name, pt in [
    ("db", (1793, 686)),
    ("zh", (1796, 813)),
    ("launcher", (1723, 969)),
]:
    pyautogui.click(*pt)
    time.sleep(0.6)
    print("clicked", name, pt)
print(rm_lib.shot("rm-04-notif-cleared"))
