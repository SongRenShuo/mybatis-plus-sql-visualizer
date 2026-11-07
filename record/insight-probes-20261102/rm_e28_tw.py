# -*- coding: utf-8 -*-
"""E28: robot 点 SQL Visualizer 工具窗按钮 → 截帧。"""
import time
import rm_lib
import robot

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.4)
xp = "//div[@accessiblename='SQL Visualizer']"
print("center:", robot.component_screen_center(xp))
robot.click_component(xp)
time.sleep(3.0)
print(rm_lib.shot("rm-41-toolwindow-open"))
