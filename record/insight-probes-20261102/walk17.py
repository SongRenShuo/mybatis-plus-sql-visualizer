import time
import walk_util as W
import robot
import pyautogui

W.front()
time.sleep(0.3)
for key in ("完整", "预览", "关闭", "打开"):
    comps = robot.find_components("//div[contains(@accessiblename,'%s')]" % key)
    vis = [c for c in comps if c.get("width", 0) > 0]
    W.log("== %s matched %d" % (key, len(vis)))
    for c in vis[:10]:
        W.log("   ", c.get("className", "").split(".")[-1], "|", c.get("accessiblename"), "|",
              c.get("x"), c.get("y"), c.get("width"), c.get("height"))
