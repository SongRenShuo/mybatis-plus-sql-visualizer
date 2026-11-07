import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_win as WIN
import whitebar3_scan as S
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
    W.log("fg", WIN.foreground(prev))
    time.sleep(1.0)


def click_text(label, exact=True):
    xp = ("//div[@accessiblename='%s']" % label) if exact else ("//div[contains(@accessiblename,'%s')]" % label)
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    W.log("cands", label, [(c.get("className", "").split(".")[-1], c.get("width"), c.get("height")) for c in hits[:5]])
    if not hits:
        return False
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    comp = hits[0]
    chain = robot.find_components("(%s)/ancestor::div" % xp)
    sx = sum(int(p["x"]) for p in chain)
    sy = sum(int(p["y"]) for p in chain)
    x = sx + int(comp["x"]) + int(comp["width"]) // 2
    y = sy + int(comp["y"]) + int(comp["height"]) // 2
    W.log("click", label, x, y)
    pyautogui.click(x, y)
    time.sleep(1.2)
    return True


# 更多 → 第 5 项「运行SQL分析」
XP = "//div[contains(@accessiblename,'更多')]"
hits = [c for c in robot.find_components(XP) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
if hits:
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    x, y = robot.component_screen_center(XP, comp=hits[0])
    pyautogui.click(x, y)
    time.sleep(1.2)
    for _ in range(4):
        pyautogui.press("down")
        time.sleep(0.15)
    pyautogui.press("enter")
    time.sleep(3.0)
W.shot("w3-07-runtime")

click_text("手动对照实际SQL")
time.sleep(3.0)
path = W.shot("w3-08-observed-diff")
rect = W.window_rect(title="SQL Visualizer", pid=pid, min_size=(600, 400))
W.log("rect", rect)
if rect:
    res = S.scan(path, rect, tag="w3-08-observed-diff")
    W.log(S.fmt(res))
W.log("DONE")
