import time
import socket
import walk_util as W
import pyautogui

W.front()
W.assert_front()
pyautogui.hotkey("shift", "f10")
W.log("run triggered")
ok = False
for i in range(60):
    time.sleep(3)
    s = socket.socket()
    s.settimeout(1.0)
    try:
        s.connect(("127.0.0.1", 8081))
        ok = True
        s.close()
        break
    except Exception:
        s.close()
    if i % 5 == 0:
        W.log("waiting app...", (i + 1) * 3, "s")
W.log("port 8081 up:", ok)
time.sleep(6)
W.shot("w07-app-run")
