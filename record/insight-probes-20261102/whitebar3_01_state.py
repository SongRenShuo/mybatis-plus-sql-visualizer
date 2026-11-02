import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W

W.log("== state at", time.strftime("%H:%M:%S"))
W.log("sandbox pid", W.sandbox_pid())
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
for label in ("Trust", "信任", "Trust Project", "新版本亮点", "检测到 MyBatis", "SQL 预览", "打开 SQL 日志"):
    W.log("TXT", label, W.texts(label, 6))
W.shot("w3-00-state")
W.log("DONE")
