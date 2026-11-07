# -*- coding: utf-8 -*-
"""E35: 关掉引导气泡后重做锚定测试（PageUp 上翻 → 新 SQL → 比对顶部条目）。"""
import time
import pyautogui
import rm_lib
import urllib.request

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.5)
pyautogui.click(1825, 631)     # 知道了
time.sleep(1.2)
pyautogui.click(1700, 450)     # 选中条目取焦
time.sleep(0.8)
for _ in range(3):
    pyautogui.press("pageup")
    time.sleep(0.6)
time.sleep(1.2)
print(rm_lib.shot("rm-58-pause"))
for u in ("/logFeed/dateParam", "/logFeed/heartbeat"):
    try:
        urllib.request.urlopen("http://127.0.0.1:8081" + u, timeout=10).read()
    except Exception as e:
        print("err", e)
    time.sleep(1.5)
time.sleep(4.0)
print(rm_lib.shot("rm-59-after"))
