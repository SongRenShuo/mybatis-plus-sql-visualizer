import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import robot
import pyautogui

pid = W.sandbox_pid()
W.log("pid", pid)
for hwnd, wpid, title, rect in W.windows():
    if wpid == pid:
        W.log("PRE WIN", title, rect)

XP = "//div[@accessiblename='Quit IntelliJ IDEA']"
hits = [c for c in robot.find_components(XP) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
W.log("quit cands", [(c.get("className", "").split(".")[-1], c.get("width"), c.get("height")) for c in hits])
if hits:
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    comp = hits[0]
    chain = robot.find_components("(%s)/ancestor::div" % XP)
    sx = sum(int(p["x"]) for p in chain)
    sy = sum(int(p["y"]) for p in chain)
    x = sx + int(comp["x"]) + int(comp["width"]) // 2
    y = sy + int(comp["y"]) + int(comp["height"]) // 2
    W.log("click quit", x, y)
    pyautogui.click(x, y)
else:
    W.log("no quit button, fallback ctrl+q")
    pyautogui.hotkey("ctrl", "q")

time.sleep(12)
# 退出确认弹窗（若有）用 Enter 兜底
try:
    for lbl in ("Exit", "Quit", "OK"):
        t = W.texts(lbl, 3)
        if t:
            W.log("confirm dialog", lbl, t)
            pyautogui.press("enter")
            break
except Exception as e:
    W.log("confirm probe err", e)
time.sleep(8)
W.shot("w3-01-quit")
for hwnd, wpid, title, rect in W.windows():
    if wpid == pid:
        W.log("POST WIN", title, rect)
W.log("DONE")
