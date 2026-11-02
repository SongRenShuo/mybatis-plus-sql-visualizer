import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import robot
import pyautogui


def click_text(label, wait=1.5):
    xp = "//div[@accessiblename='%s']" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    W.log("cands", label, [(c.get("className", "").split(".")[-1], c.get("width"), c.get("height")) for c in hits[:6]])
    if not hits:
        return False
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    x, y = robot.component_screen_center(xp, comp=hits[0])
    W.log("click", label, x, y)
    pyautogui.click(x, y)
    time.sleep(wait)
    return True


click_text("SQL → Java")
W.shot("w3-19-sqltojava-tab")
for lbl in ("转换", "格式化", "清空"):
    W.log("TXT", lbl, W.texts(lbl, 6))
W.log("DONE")
