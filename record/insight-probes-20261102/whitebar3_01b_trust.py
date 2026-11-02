import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import robot
import pyautogui

XP = "//div[@accessiblename='Trust Project']"
hits = [c for c in robot.find_components(XP) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
W.log("trust cands", [(c.get("className", "").split(".")[-1], c.get("width"), c.get("height")) for c in hits])
if hits:
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    comp = hits[0]
    chain = robot.find_components("(%s)/ancestor::div" % XP)
    sx = sum(int(p["x"]) for p in chain)
    sy = sum(int(p["y"]) for p in chain)
    x = sx + int(comp["x"]) + int(comp["width"]) // 2
    y = sy + int(comp["y"]) + int(comp["height"]) // 2
    W.log("click trust", x, y)
    pyautogui.click(x, y)
    time.sleep(1.0)
    pyautogui.click(x, y)
else:
    W.log("no trust button")
time.sleep(22)
W.shot("w3-01-after-trust")
for label in ("Trust", "新版本亮点", "检测到 MyBatis", "SQL 预览", "打开 SQL 日志", "捕获"):
    W.log("TXT", label, W.texts(label, 6))
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
W.log("DONE")
