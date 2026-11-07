# -*- coding: utf-8 -*-
"""E19: 点编辑器（预览面板左侧）第 20 行 → Alt+Shift+Q 重新预览。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
pyautogui.click(250, 513)      # 编辑器内第 20 行（预览面板左侧）
time.sleep(0.9)
pyautogui.hotkey("alt", "shift", "q")
time.sleep(4.5)
print(rm_lib.shot("rm-27-preview-orders"))
