import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui

xp = "(//div[contains(@accessiblename,'Trust')])[2]"
comp = robot.find_one(xp)
if comp is None:
    W.log("no trust dialog")
else:
    chain = robot.find_components("(%s)/ancestor::div" % xp)
    sx = sum(int(p["x"]) for p in chain)
    sy = sum(int(p["y"]) for p in chain)
    x = sx + int(comp["x"]) + int(comp["width"]) // 2
    y = sy + int(comp["y"]) + int(comp["height"]) // 2
    W.log("click trust", x, y)
    pyautogui.click(x, y)
time.sleep(25)
W.shot("wb32-second-open-after-trust")
for label in ("检测到 MyBatis mapper 工程", "SQL Visualizer 新版本亮点", "SQL 预览", "捕获", "打开 SQL 日志"):
    W.log("TXT", label, W.texts(label, 6))
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
W.log("DONE")
