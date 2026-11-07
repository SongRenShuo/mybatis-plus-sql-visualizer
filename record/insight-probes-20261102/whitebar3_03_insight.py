import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_scan as S
import robot
import pyautogui

SHOTS = W.SHOTS


def more():
    xp = "//div[contains(@accessiblename,'更多')]"
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    W.log("more cands", [(c.get("className", "").split(".")[-1], c.get("width"), c.get("height")) for c in hits[:5]])
    if not hits:
        return False
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    x, y = robot.component_screen_center(xp, comp=hits[0])
    pyautogui.click(x, y)
    time.sleep(1.2)
    return True


def open_item(downs, name):
    if not more():
        W.log("FAIL", name, "no 更多 button")
        return None
    for _ in range(downs):
        pyautogui.press("down")
        time.sleep(0.15)
    pyautogui.press("enter")
    time.sleep(3.5)
    path = W.shot(name)
    rect = W.window_rect(title="SQL Visualizer", pid=W.sandbox_pid(), min_size=(600, 400))
    W.log("RECT", name, rect)
    if not rect:
        for hwnd, pid, title, r in W.windows():
            if pid == W.sandbox_pid():
                W.log("WIN", name, title, r)
        return None
    res = S.scan(path, rect, tag=name)
    W.log(S.fmt(res))
    return res


W.front()
time.sleep(0.4)
W.assert_front()
for downs, name in ((2, "w3-03-scenarios"), (7, "w3-04-schema"), (9, "w3-05-comparison"), (11, "w3-06-diff")):
    open_item(downs, name)
W.log("DONE")
