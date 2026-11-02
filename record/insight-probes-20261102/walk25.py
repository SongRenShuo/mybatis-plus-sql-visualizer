import time
import walk_util as W
import robot

W.front()
time.sleep(0.3)
for cls in ("EditorComponentImpl", "EditorTextField", "JBTextField", "TextField"):
    xp = "//div[contains(@class,'%s')]" % cls
    comps = [c for c in robot.find_components(xp) if c.get("width", 0) > 0]
    W.log("== %s : %d" % (cls, len(comps)))
    for c in comps[:8]:
        try:
            x, y = robot.component_screen_center(xp, comp=c)
        except Exception:
            x, y = -1, -1
        W.log("   ", c.get("className", "").split(".")[-1], x, y, c.get("width"), c.get("height"))
