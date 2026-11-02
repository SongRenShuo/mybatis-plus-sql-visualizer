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
W.log("main", main)
WIN.foreground(main)
time.sleep(0.8)
pyautogui.hotkey("alt", "f4")
time.sleep(4.0)
W.shot("w3-22-quit1")
ws = WIN.by_pid(pid)
W.log("after alt-f4", [(t, r) for _, _, t, r in ws])
if ws:
    # 可能有退出确认对话框：按 Enter 确认
    pyautogui.press("return")
    time.sleep(3.0)
    W.log("after enter", [(t, r) for _, _, t, r in WIN.by_pid(pid)])
W.log("DONE")
