import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui

W.front()
time.sleep(0.4)
W.assert_front()
pyautogui.hotkey("ctrl", "alt", "s")
time.sleep(3.5)
W.shot("ui08-settings")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
for label in ("界面与操作", "更新与诊断", "SQL Visualizer", "Gutter 图标来源分组", "全部", "无"):
    xp = "//div[contains(@accessiblename,'%s')]" % label
    try:
        hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    except Exception as e:
        W.log("ERR", label, e)
        hits = []
    W.log("FIND", label, "n=%d" % len(hits),
          [(c.get("className", "").split(".")[-1], c.get("width"), c.get("height")) for c in hits[:5]])
W.log("DONE")
