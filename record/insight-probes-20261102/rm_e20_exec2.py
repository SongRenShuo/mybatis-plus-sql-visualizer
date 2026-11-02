# -*- coding: utf-8 -*-
"""E20: 执行 SQL（无 ${} 语句，真实 MySQL 目标）。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.5)
pyautogui.click(1178, 830)     # 执行 SQL
time.sleep(6.0)
print(rm_lib.shot("rm-28-exec-result"))
time.sleep(6.0)
print(rm_lib.shot("rm-29-exec-result2"))
