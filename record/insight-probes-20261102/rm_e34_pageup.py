# -*- coding: utf-8 -*-
"""E34: 点条目取焦 → PageUp 上翻 → 触发新 SQL → 看顶部条目是否被顶走。"""
import time
import pyautogui
import rm_lib
import urllib.request

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.6)
pyautogui.click(1700, 450)      # 选中一条
time.sleep(0.8)
for _ in range(3):
    pyautogui.press("pageup")
    time.sleep(0.5)
time.sleep(1.0)
print(rm_lib.shot("rm-56-pageup"))
for u in ("/logFeed/dateParam", "/logFeed/heartbeat"):
    try:
        urllib.request.urlopen("http://127.0.0.1:8081" + u, timeout=10).read()
    except Exception as e:
        print("err", e)
    time.sleep(1.2)
time.sleep(4.0)
print(rm_lib.shot("rm-57-after-pageup"))
