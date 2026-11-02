import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_win as WIN
import pyautogui

pid = W.sandbox_pid()
prev = None
for hwnd, wpid, title, rect in WIN.by_pid(pid):
    if "SQL 预览" in title:
        prev = hwnd
W.log("prev", prev)
if prev:
    WIN.foreground(prev)
    time.sleep(1.0)
    pyautogui.hotkey("alt", "f4")
    time.sleep(2.0)
W.log("windows", [(t, r) for _, _, t, r in WIN.by_pid(pid)])
W.shot("w3-20-after-close")
for lbl in ("转换", "SQL → Java"):
    W.log("TXT", lbl, W.texts(lbl, 6))
W.log("DONE")
