# -*- coding: utf-8 -*-
"""E13: 关闭数据源对话框，展开 mp_demo 节点（触发真实连接）。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
pyautogui.press("escape")
time.sleep(1.5)
print(rm_lib.shot("rm-17-after-esc"))
pyautogui.click(1553, 205)   # mp_demo 节点 chevron
time.sleep(8.0)
print(rm_lib.shot("rm-18-expanded"))
