import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_scan as S
import robot
import pyautogui


def click_text(label):
    xp = "//div[@accessiblename='%s']" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    W.log("cands", label, [(c.get("className", "").split(".")[-1], c.get("width"), c.get("height")) for c in hits[:5]])
    if not hits:
        return False
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    try:
        x, y = robot.component_screen_center(xp, comp=hits[0])
    except Exception as e:
        W.log("center failed", e)
        return False
    W.log("click", label, x, y)
    pyautogui.click(x, y)
    time.sleep(1.6)
    return True


click_text("预览执行候选")
W.shot("w3-11-candidates2")
click_text("手动对照实际SQL")
time.sleep(2.5)
path = W.shot("w3-12-observed-diff2")
rect = W.window_rect(title="SQL Visualizer", pid=W.sandbox_pid(), min_size=(600, 400))
W.log("rect", rect)
if rect:
    W.log(S.fmt(S.scan(path, rect, tag="w3-12-observed-diff2")))
W.log("DONE")
