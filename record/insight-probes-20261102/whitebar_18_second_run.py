import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui

time.sleep(5)
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
W.shot("wb27-second-run-startup")
for label in ("检测到 MyBatis mapper 工程", "SQL Visualizer 新版本亮点", "SQL 预览", "打开 SQL 日志"):
    W.log("TXT", label, W.texts(label, 6))
W.log("DONE")
