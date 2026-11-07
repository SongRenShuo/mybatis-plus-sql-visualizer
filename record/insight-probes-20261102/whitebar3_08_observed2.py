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
    comp = hits[0]
    chain = robot.find_components("(%s)/ancestor::div" % xp)
    sx = sum(int(p["x"]) for p in chain)
    sy = sum(int(p["y"]) for p in chain)
    x = sx + int(comp["x"]) + int(comp["width"]) // 2
    y = sy + int(comp["y"]) + int(comp["height"]) // 2
    W.log("click", label, x, y)
    pyautogui.click(x, y)
    time.sleep(1.5)
    return True


click_text("预览执行候选")
W.shot("w3-09-candidates")
click_text("手动对照实际SQL")
time.sleep(2.5)
path = W.shot("w3-10-observed-diff")
rect = W.window_rect(title="SQL Visualizer", pid=W.sandbox_pid(), min_size=(600, 400))
W.log("rect", rect)
if rect:
    W.log(S.fmt(S.scan(path, rect, tag="w3-10-observed-diff")))
W.log("DONE")
