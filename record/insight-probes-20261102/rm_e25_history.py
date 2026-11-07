# -*- coding: utf-8 -*-
"""E25: 关闭预览弹窗 → 打开 SQL 历史页签 → 截帧（V179 关键字换行三行）。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.4)
pyautogui.click(1497, 830)     # 取消
time.sleep(1.5)
print(rm_lib.shot("rm-35-preview-closed"))
pyautogui.click(1568, 55)      # SQL 历史 页签
time.sleep(3.0)
print(rm_lib.shot("rm-36-history-tab"))
