import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_win as WIN

u32 = __import__("ctypes").windll.user32
pid = W.sandbox_pid()
prev = None
diff = None
for hwnd, wpid, title, rect in WIN.by_pid(pid):
    if "SQL 预览" in title:
        prev = hwnd
    if "SQL 差异对比" in title:
        diff = (hwnd, title, rect)
W.log("prev", prev, "diff", diff)
if prev:
    u32.ShowWindow(prev, 6)   # SW_MINIMIZE
    time.sleep(1.5)
if diff:
    W.log("fg diff", WIN.foreground(diff[0]))
    time.sleep(1.5)
W.shot("w3-06d-diff-front")
W.log("foreground hwnd", u32.GetForegroundWindow())
for hwnd, wpid, title, rect in WIN.by_pid(pid):
    W.log("WIN", title, rect)
W.log("DONE")
