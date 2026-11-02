import time
import walk_util as W
import robot

W.front()
time.sleep(0.3)
comps = robot.find_components("//div")
out = []
for i, c in enumerate(comps, 1):
    if c.get("width", 0) <= 0 or c.get("height", 0) <= 0:
        continue
    try:
        x, y = robot.component_screen_center("(//div)[%d]" % i, comp=c)
    except Exception:
        continue
    if 380 <= x <= 1550 and 450 <= y <= 500:
        out.append((c.get("className", "").split(".")[-1], x, y, c.get("width"), c.get("height")))
for s in sorted(set(out), key=lambda t: t[1]):
    W.log("F", s)
