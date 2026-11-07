# -*- coding: utf-8 -*-
"""E26: 启动 demo 应用（Run 绿色箭头），并重开 SQL Visualizer 工具窗。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.5)
pyautogui.click(1341, 18)      # Run
print("clicked run")
time.sleep(8.0)
print(rm_lib.shot("rm-37-run-started"))
pyautogui.click(15, 330)       # 左侧 SQL Visualizer 工具窗图标
time.sleep(3.0)
print(rm_lib.shot("rm-38-toolwindow"))
