import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui

W.front()
time.sleep(1.0)
W.shot("wb30-second-run-front")
for label in ("检测到 MyBatis mapper 工程", "SQL Visualizer 新版本亮点", "SQL 预览", "捕获"):
    W.log("TXT", label, W.texts(label, 6))
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
W.log("DONE")
