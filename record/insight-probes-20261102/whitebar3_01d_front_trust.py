import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import robot
import pyautogui
from locate import ensure_foreground, foreground_hwnd, pid_of_hwnd

pid = W.sandbox_pid()
W.log("pid", pid)
for hwnd, wpid, title, rect in W.windows():
    if wpid == pid:
        W.log("WIN", title, rect)

# 1) 先把 IDE 主窗置前
main = None
best = 0
for hwnd, wpid, title, rect in W.windows():
    if wpid == pid:
        area = (rect[2] - rect[0]) * (rect[3] - rect[1])
        if area > best:
            best, main = area, hwnd
if main:
    ok = ensure_foreground(main, tries=6)
    W.log("foreground main", ok, pid_of_hwnd(foreground_hwnd()) == pid)
    time.sleep(1.0)

# 2) 再把 Trust 对话框置前（800x400 上下的子窗）
dlg = None
for hwnd, wpid, title, rect in W.windows():
    if wpid == pid:
        w, h = rect[2] - rect[0], rect[3] - rect[1]
        if 450 < w < 1000 and 150 < h < 600:
            dlg = hwnd
            W.log("cand dlg", title, rect)
if dlg:
    ok = ensure_foreground(dlg, tries=6)
    W.log("foreground dlg", ok, pid_of_hwnd(foreground_hwnd()) == pid)
    time.sleep(0.8)
W.shot("w3-01d-front")

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
    time.sleep(1.2)
    pyautogui.click(x, y)
time.sleep(20)
W.shot("w3-01d-after")
for label in ("Trust", "新版本亮点", "检测到 MyBatis", "SQL 预览"):
    W.log("TXT", label, W.texts(label, 6))
for hwnd, wpid, title, rect in W.windows():
    if wpid == pid:
        W.log("POST WIN", title, rect)
W.log("DONE")
