import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_win as WIN
import pyautogui

u32 = __import__("ctypes").windll.user32
pid = W.sandbox_pid()
prev = None
for hwnd, wpid, title, rect in WIN.by_pid(pid):
    if "SQL 预览" in title:
        prev = hwnd
W.log("prev", prev)
if prev:
    u32.ShowWindow(prev, 9)
    time.sleep(0.6)
    W.log("fg", WIN.foreground(prev))
    time.sleep(1.0)
    pyautogui.press("escape")
    time.sleep(2.0)
W.shot("w3-06k-after-esc")
W.log("windows", [(t, r) for _, _, t, r in WIN.by_pid(pid)])
for hwnd, wpid, title, rect in WIN.by_pid(pid):
    if "差异" in title:
        W.log("fg diff", hwnd, WIN.foreground(hwnd))
        time.sleep(1.5)
        W.shot("w3-06l-diff-alone")
        break
W.log("DONE")
