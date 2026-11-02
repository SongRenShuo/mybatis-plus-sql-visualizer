import time
import walk_util as W
import pyautogui
import robot

W.front()
time.sleep(0.3)
comps = robot.find_components("//div[contains(@class,'ActionButton')]")
rows = []
for i, c in enumerate(comps, 1):
    if c.get("width", 0) <= 0:
        continue
    xp = "(//div[contains(@class,'ActionButton')])[%d]" % i
    try:
        sx, sy = robot.component_screen_center(xp, comp=c)
    except Exception as e:
        continue
    rows.append((sx, sy, c.get("width"), c.get("height"), c.get("className").split(".")[-1],
                 c.get("accessiblename") or "", c.get("name") or ""))
for r in sorted(rows):
    W.log("BTN", r)
