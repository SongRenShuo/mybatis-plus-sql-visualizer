# -*- coding: utf-8 -*-
"""E29: 打开 SQL 日志捕捉开关（工具栏圆形按钮），等真实 SQL 落列表。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.5)
pyautogui.click(1590, 118)     # 捕捉开关
time.sleep(2.0)
print(rm_lib.shot("rm-42-capture-on"))
time.sleep(10.0)
print(rm_lib.shot("rm-43-logs"))
