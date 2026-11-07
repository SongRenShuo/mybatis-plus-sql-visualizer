import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui


def center_of(label):
    xp = "//div[contains(@accessiblename,'%s')]" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    if not hits:
        return None
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    return robot.component_screen_center(xp, comp=hits[0])


W.front()
time.sleep(0.3)
W.assert_front()
pt = center_of("生成体检报告")
W.log("env check button", pt)
if pt:
    pyautogui.click(pt[0], pt[1])
    time.sleep(4.0)
    W.shot("ui25-envcheck-clicked")
    for label in ("已打开", "体检报告", "报告已", "编辑器页签"):
        W.log("STATUS", label, W.texts(label, 4))
# 关设置 → 看体检单编辑器页签
pyautogui.press("esc")
time.sleep(1.0)
pyautogui.press("esc")
time.sleep(1.5)
W.shot("ui26-envcheck-tab")
for label in ("SQL Visualizer environment check", "环境体检", "sql-visualizer-environment-check"):
    W.log("TAB", label, W.texts(label, 4))
W.log("DONE")
