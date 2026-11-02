import time
import walk_util as W
import robot

W.front()
time.sleep(0.3)
for key in ("21:30:41", "21:30:40", "WHERE 1 = 1", "SQL 日志"):
    comps = robot.find_components("//div[contains(@accessiblename,'%s')]" % key)
    vis = [c for c in comps if c.get("width", 0) > 0]
    W.log("== %s matched %d" % (key, len(vis)))
    for c in vis[:8]:
        W.log("   ", c.get("className", "").split(".")[-1], "|", c.get("accessiblename"), "|",
              c.get("x"), c.get("y"), c.get("width"), c.get("height"))
