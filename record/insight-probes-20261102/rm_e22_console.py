# -*- coding: utf-8 -*-
"""E22: 选中 mp_demo 后打开查询控制台（工具栏 play 图标 1671,98）→ 建立真实连接。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
pyautogui.click(1595, 205)
time.sleep(0.6)
pyautogui.click(1671, 98)
time.sleep(9.0)
print(rm_lib.shot("rm-32-console"))
