import time
import walk_util as W
import robot

W.front()
time.sleep(0.3)
for key in ("更多", "执行计划", "刷新", "格式"):
    comps = robot.find_components("//div[contains(@accessiblename,'%s')]" % key)
    vis = [c for c in comps if c.get("width", 0) > 0]
    W.log("== %s matched %d" % (key, len(vis)))
    for c in vis[:6]:
        try:
            sx, sy = robot.component_screen_center("//div[contains(@accessiblename,'%s')]" % key, comp=c)
        except Exception:
            sx, sy = -1, -1
        W.log("   ", c.get("className", "").split(".")[-1], "|", c.get("accessiblename"), "|",
              c.get("name"), "|", sx, sy, c.get("width"), c.get("height"))
