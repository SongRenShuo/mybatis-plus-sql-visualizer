# -*- coding: utf-8 -*-
"""E27: 用 robot 组件身份点 Run 按钮（不用像素）。"""
import time
import rm_lib
import robot

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.5)
c = robot.find_one("//div[@myicon='run.svg']")
print("run button comp:", c)
print("screen center:", robot.component_screen_center("//div[@myicon='run.svg']", c))
robot.click_component("//div[@myicon='run.svg']")
print("clicked run")
time.sleep(10.0)
print(rm_lib.shot("rm-39-after-run"))
