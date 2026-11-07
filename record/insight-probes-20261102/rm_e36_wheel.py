# -*- coding: utf-8 -*-
"""E36: 用鼠标滚轮（先点条目取焦）上滚，确认视口真的离开底部后再触发新 SQL。"""
import time
import pyautogui
import rm_lib
import urllib.request

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.5)
pyautogui.click(1700, 450)
time.sleep(0.8)
pyautogui.moveTo(1700, 400)
time.sleep(0.3)
for _ in range(5):
    pyautogui.scroll(10)
    time.sleep(0.35)
time.sleep(1.2)
print(rm_lib.shot("rm-60-wheel"))
for u in ("/logFeed/dateParam", "/logFeed/heartbeat"):
    try:
        urllib.request.urlopen("http://127.0.0.1:8081" + u, timeout=10).read()
    except Exception as e:
        print("err", e)
    time.sleep(1.5)
time.sleep(4.0)
print(rm_lib.shot("rm-61-wheel-after"))
