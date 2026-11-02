import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_win as WIN
import robot
import pyautogui

u32 = __import__("ctypes").windll.user32
pid = W.sandbox_pid()

# 1) 恢复预览对话框
prev = None
for hwnd, wpid, title, rect in WIN.by_pid(pid):
    if "SQL 预览" in title:
        prev = hwnd
W.log("prev", prev)
if prev:
    u32.ShowWindow(prev, 9)   # SW_RESTORE
    time.sleep(0.6)
    W.log("fg prev", WIN.foreground(prev))
    time.sleep(1.2)

before = {h for h, _, _, _ in WIN.by_pid(pid)}
W.log("before", [(t, r) for _, _, t, r in WIN.by_pid(pid)])

# 2) 再走一次「更多 → 第 11 项」
XP = "//div[contains(@accessiblename,'更多')]"
hits = [c for c in robot.find_components(XP) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
W.log("more cands", len(hits))
if hits:
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    x, y = robot.component_screen_center(XP, comp=hits[0])
    pyautogui.click(x, y)
    time.sleep(1.2)
    for _ in range(11):
        pyautogui.press("down")
        time.sleep(0.15)
    pyautogui.press("enter")
    time.sleep(4.0)

W.shot("w3-06g-after-more")
new = [(h, t, r) for h, _, t, r in WIN.by_pid(pid) if h not in before]
W.log("new windows", new)
for h, t, r in new:
    if "SQL 差异" in t or "差异" in t:
        W.log("fg new", WIN.foreground(h))
        time.sleep(1.5)
        W.shot("w3-06h-diff")
        W.log("new rect", r)
W.log("all", [(t, r) for _, _, t, r in WIN.by_pid(pid)])
W.log("DONE")
