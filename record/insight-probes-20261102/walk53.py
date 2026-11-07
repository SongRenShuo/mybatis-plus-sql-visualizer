import time
import walk_util as W
import robot
import pyautogui

W.front()
time.sleep(0.3)
comps = robot.find_components("//div[contains(@class,'TabLabel')]")
W.log("tablabels:", len(comps))
for c in comps:
    if c.get("width", 0) <= 0:
        continue
    W.log("  ", c.get("className", "").split(".")[-1], "|", c.get("accessiblename"), "|", c.get("name"),
          "|", c.get("x"), c.get("y"), c.get("width"), c.get("height"))
