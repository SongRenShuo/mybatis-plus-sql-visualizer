import time
import walk_util as W
import robot
import pyautogui

W.front()
time.sleep(0.3)
comps = robot.find_components("//div[contains(@class,'TabLabel')]")
idx = None
for i, c in enumerate(comps, 1):
    if c.get("width") == 77 and c.get("height") == 29:
        idx = i
        break
W.log("sql tab index:", idx)
if idx:
    xp = "(//div[contains(@class,'TabLabel')])[%d]" % idx
    x, y = robot.component_screen_center(xp)
    W.log("tab center", x, y)
    W.assert_front()
    pyautogui.click(x, y)
    time.sleep(1.2)
    W.shot("w54-sql-tab")
