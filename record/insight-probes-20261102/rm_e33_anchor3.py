# -*- coding: utf-8 -*-
"""E33: 列表已有 13 条（可滚动）后重做锚定测试。"""
import time
import pyautogui
import rm_lib
import urllib.request

t, pid, h, r = rm_lib.ide_window()
rm_lib.front(h)
time.sleep(0.6)
pyautogui.moveTo(1700, 400)
time.sleep(0.4)
for _ in range(6):
    pyautogui.scroll(3)
    time.sleep(0.3)
time.sleep(1.5)
print(rm_lib.shot("rm-54-scroll2"))
for u in ("/logFeed/dateParam", "/logFeed/heartbeat", "/logFeed/dateParam"):
    try:
        urllib.request.urlopen("http://127.0.0.1:8081" + u, timeout=10).read()
    except Exception as e:
        print("err", e)
    time.sleep(1.2)
time.sleep(4.0)
print(rm_lib.shot("rm-55-after2"))
