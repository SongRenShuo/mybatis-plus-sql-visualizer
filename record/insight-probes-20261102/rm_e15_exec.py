# -*- coding: utf-8 -*-
"""E15: 预览面板「执行 SQL」→ 观察是否要求选择数据源。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.4)
pyautogui.click(1178, 830)   # 执行 SQL
time.sleep(5.0)
print(rm_lib.shot("rm-21-exec"))
