# -*- coding: utf-8 -*-
"""E07: 右键数据源节点，取上下文菜单。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.4)
pyautogui.click(1595, 205)          # 先左键选中
time.sleep(0.5)
pyautogui.rightClick(1595, 205)
time.sleep(1.2)
print(rm_lib.shot("rm-09-ctxmenu"))
