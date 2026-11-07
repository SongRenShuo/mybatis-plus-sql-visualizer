import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_scan as S
import robot
import pyautogui


def click_text(label, wait=1.2):
    xp = "//div[@accessiblename='%s']" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    W.log("cands", label, [(c.get("className", "").split(".")[-1], c.get("width"), c.get("height")) for c in hits[:5]])
    if not hits:
        return False
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    x, y = robot.component_screen_center(xp, comp=hits[0])
    W.log("click", label, x, y)
    pyautogui.click(x, y)
    time.sleep(wait)
    return True


click_text("SQL")
click_text("全不选", wait=1.5)
W.shot("w3-13-sql-unchecked")
click_text("执行 SQL", wait=4.0)
W.shot("w3-14-exec-clicked")
for lbl in ("选择数据源", "执行失败", "结果", "数据源"):
    W.log("TXT", lbl, W.texts(lbl, 5))
time.sleep(8)
path = W.shot("w3-15-exec-result")
rect = W.window_rect(title="SQL Visualizer", pid=W.sandbox_pid(), min_size=(600, 400))
W.log("rect", rect)
for lbl in ("执行失败", "结果", "选择数据源"):
    W.log("AFTER", lbl, W.texts(lbl, 5))
if rect:
    W.log(S.fmt(S.scan(path, rect, tag="w3-15-exec-result")))
W.log("DONE")
