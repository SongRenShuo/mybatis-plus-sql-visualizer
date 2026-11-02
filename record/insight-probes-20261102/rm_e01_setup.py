# -*- coding: utf-8 -*-
"""E01: 沙盒窗口归一（最大化 + 置前），截一帧。"""
import time
import rm_lib

t, pid, h, r = rm_lib.ide_window()
print("before rect", r)
print("front", rm_lib.front(h))
rm_lib.maximize(h)
time.sleep(1.5)
print("after rect", rm_lib.rect_of(h))
print(rm_lib.shot("rm-03-maxed"))
