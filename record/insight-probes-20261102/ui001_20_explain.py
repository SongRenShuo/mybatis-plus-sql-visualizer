import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui
import nav


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
nav.goto_text("selectByCustomSqlSegment")
time.sleep(0.6)
W.assert_front()
pyautogui.hotkey("alt", "shift", "q")
time.sleep(4.0)
W.shot("ui27-preview-ewnull")
pt = center_of("执行计划")
W.log("explain button", pt)
if pt:
    pyautogui.click(pt[0], pt[1])
    time.sleep(3.0)
    W.shot("ui28-explain-clicked")
    items = robot.menu_items()
    W.log("menu items after explain", len(items), items[:6])
    for label in ("选择数据源", "数据源", "MySQL", "jpa_demo", "mpj_demo", "未绑定"):
        W.log("TXT", label, W.texts(label, 4))
W.log("DONE")
