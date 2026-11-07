# -*- coding: utf-8 -*-
"""E16: 关掉 ${} 确认框 → 换一条无 ${} 的语句（selectOrdersByCondition）重新预览并执行。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
pyautogui.click(1717, 809)     # No（${} 确认）
time.sleep(1.5)
pyautogui.click(700, 513)      # 第 20 行 select id="selectOrdersByCondition"
time.sleep(0.8)
pyautogui.hotkey("alt", "shift", "q")
time.sleep(4.0)
print(rm_lib.shot("rm-22-preview2"))
