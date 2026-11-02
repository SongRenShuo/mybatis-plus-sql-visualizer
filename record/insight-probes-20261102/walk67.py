import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
pyautogui.press("esc")
time.sleep(0.8)
W.assert_front()
pyautogui.click(1488, 234)
time.sleep(1.0)
for _ in range(18):
    pyautogui.press("down")
    time.sleep(0.15)
pyautogui.press("enter")
time.sleep(3.0)
W.shot("w67-export-trace")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
