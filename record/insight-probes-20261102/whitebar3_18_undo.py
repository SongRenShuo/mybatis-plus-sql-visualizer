import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_win as WIN
import pyautogui

pid = W.sandbox_pid()
main = None
best = 0
for hwnd, wpid, title, rect in WIN.by_pid(pid):
    area = (rect[2] - rect[0]) * (rect[3] - rect[1])
    if area > best:
        best, main = area, hwnd
WIN.foreground(main)
time.sleep(0.8)
pyautogui.click(700, 500)
time.sleep(0.4)
for _ in range(4):
    pyautogui.hotkey("ctrl", "z")
    time.sleep(0.5)
W.shot("w3-28-undone")
W.log("DONE")
