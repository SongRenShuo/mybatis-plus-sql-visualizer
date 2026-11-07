import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_win as WIN

pid = W.sandbox_pid()
target = None
for hwnd, wpid, title, rect in WIN.by_pid(pid):
    if "SQL 差异对比" in title:
        target = (hwnd, title, rect)
W.log("target", target)
if target:
    W.log("fg", WIN.foreground(target[0]))
    time.sleep(1.5)
    W.shot("w3-06b-diff")
    W.log("rect", target[2])
else:
    W.log("diff window not found")
W.log("DONE")
