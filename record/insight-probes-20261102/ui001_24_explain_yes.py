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
pt = center_of("Yes")
W.log("yes button", pt)
if pt:
    pyautogui.click(pt[0], pt[1])
    time.sleep(2.5)
W.shot("ui35-after-yes")
for label in ("选择数据源", "数据源", "MySQL", "未绑定数据源", "执行计划", "EXPLAIN", "全表扫描", "建议"):
    W.log("TXT", label, W.texts(label, 5))
items = robot.menu_items()
W.log("menu items", len(items), items[:8])
W.log("DONE")
