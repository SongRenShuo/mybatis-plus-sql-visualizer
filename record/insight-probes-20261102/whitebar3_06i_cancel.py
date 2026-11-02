import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_win as WIN
import robot
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
    WIN.foreground(prev)
    time.sleep(1.0)

XP = "//div[@accessiblename='取消']"
hits = [c for c in robot.find_components(XP) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
W.log("cancel cands", [(c.get("className", "").split(".")[-1], c.get("width"), c.get("height")) for c in hits])
if hits:
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    comp = hits[0]
    chain = robot.find_components("(%s)/ancestor::div" % XP)
    sx = sum(int(p["x"]) for p in chain)
    sy = sum(int(p["y"]) for p in chain)
    x = sx + int(comp["x"]) + int(comp["width"]) // 2
    y = sy + int(comp["y"]) + int(comp["height"]) // 2
    W.log("click cancel", x, y)
    pyautogui.click(x, y)
    time.sleep(2.5)
W.shot("w3-06i-after-cancel")
W.log("windows", [(t, r) for _, _, t, r in WIN.by_pid(pid)])
# 若差异窗还在，置前截图
for hwnd, wpid, title, rect in WIN.by_pid(pid):
    if "差异" in title:
        W.log("fg diff", hwnd, WIN.foreground(hwnd))
        time.sleep(1.5)
        W.shot("w3-06j-diff-alone")
        break
W.log("DONE")
