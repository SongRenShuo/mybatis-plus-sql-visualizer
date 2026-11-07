# -*- coding: utf-8 -*-
"""E31: LogListAnchor 实测——攒条目 → 手动上滚 → 新 SQL 到达后看视口顶部是否被顶走。"""
import time
import pyautogui
import rm_lib

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.5)
print("waiting for entries...")
time.sleep(55.0)
print(rm_lib.shot("rm-47-list-full"))
# 把鼠标移到列表中部并上滚
pyautogui.moveTo(1700, 400)
time.sleep(0.5)
for _ in range(6):
    pyautogui.scroll(3)
    time.sleep(0.25)
time.sleep(1.5)
print(rm_lib.shot("rm-48-scrolled-up"))
# 等新 SQL 到达（调度每 5s 一条）
time.sleep(20.0)
print(rm_lib.shot("rm-49-after-new-sql"))
