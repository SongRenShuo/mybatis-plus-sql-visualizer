# -*- coding: utf-8 -*-
"""E32: LogListAnchor——手动上滚后触发新 SQL，看视口顶部条目是否被顶走。"""
import time
import pyautogui
import rm_lib
import urllib.request

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.6)
pyautogui.moveTo(1700, 400)
time.sleep(0.4)
for _ in range(5):
    pyautogui.scroll(3)
    time.sleep(0.25)
time.sleep(1.2)
print(rm_lib.shot("rm-51-scrolled"))
for _ in range(3):
    try:
        urllib.request.urlopen("http://127.0.0.1:8081/logFeed/n1", timeout=10).read()
    except Exception as e:
        print("curl err", e)
    time.sleep(1.0)
time.sleep(4.0)
print(rm_lib.shot("rm-52-after-new"))
