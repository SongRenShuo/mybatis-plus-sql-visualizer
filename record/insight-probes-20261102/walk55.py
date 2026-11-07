import time
import walk_util as W
import robot
import pyautogui

W.front()
time.sleep(0.3)
comps = robot.find_components("//div[contains(@class,'TabLabel')]")
target = None
for i, c in enumerate(comps, 1):
    if c.get("width", 0) <= 0:
        continue
    try:
        x, y = robot.component_screen_center("(//div[contains(@class,'TabLabel')])[%d]" % i, comp=c)
    except Exception:
        continue
    W.log("tab", i, c.get("width"), c.get("height"), x, y)
    if 250 <= y <= 290 and c.get("width") == 77 and target is None:
        target = (i, x, y)
W.log("target:", target)
if target:
    W.assert_front()
    pyautogui.click(target[1], target[2])
    time.sleep(1.3)
    W.shot("w55-sql-tab")
