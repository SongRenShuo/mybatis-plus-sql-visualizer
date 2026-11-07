import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
pyautogui.press("esc")
time.sleep(1.0)
W.shot("w62-after-esc")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
