# -*- coding: utf-8 -*-
"""E24: 关菜单 → 预览面板「执行计划」按钮，看是否走同一 Runner 选择链。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
pyautogui.press("escape")
time.sleep(0.8)
pyautogui.click(1349, 246)     # 执行计划
time.sleep(6.0)
print(rm_lib.shot("rm-34-explain"))
