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
W.log("yes", pt)
if pt:
    pyautogui.click(pt[0], pt[1])
    time.sleep(0.6)
    pyautogui.click(pt[0], pt[1])
    time.sleep(2.5)
W.shot("ui36-after-yes")
for label in ("选择数据源", "数据源", "MySQL", "jpa_demo", "未绑定数据源"):
    W.log("TXT", label, W.texts(label, 5))
# 若出现 IDE 原生数据源选择器：键盘选中第一项执行
if W.texts("选择数据源", 3) or W.texts("数据源", 3):
    pyautogui.press("down")
    time.sleep(0.4)
    pyautogui.press("enter")
    W.log("picked first data source via keyboard")
time.sleep(10)
W.shot("ui37-explain-result")
for label in ("全表扫描", "建议", "EXPLAIN", "EXPLAIN 建议", "未绑定数据源", "该表不在"):
    W.log("RESULT", label, W.texts(label, 5))
W.log("DONE")
