# -*- coding: utf-8 -*-
"""E30: 点 SQL 日志工具栏最左绿色按钮（捕捉开关 1559,118），等真实 SQL。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.5)
pyautogui.click(1559, 118)
time.sleep(2.0)
print(rm_lib.shot("rm-45-capture-btn"))
time.sleep(12.0)
print(rm_lib.shot("rm-46-logs2"))
