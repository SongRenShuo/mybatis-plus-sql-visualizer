import time
import walk_util as W
import robot

W.front()
time.sleep(0.3)
comps = robot.find_components("//div")
seen = []
for i, c in enumerate(comps, 1):
    if c.get("width", 0) <= 0 or c.get("height", 0) <= 30:
        continue
    xp = "(//div)[%d]" % i
    try:
        sx, sy = robot.component_screen_center(xp, comp=c)
    except Exception:
        continue
    if sx > 1360 and sy > 100:
        seen.append((c.get("className", "").split(".")[-1], sx, sy, c.get("width"), c.get("height")))
for s in sorted(set(seen), key=lambda t: (t[2], t[1]))[:40]:
    W.log("COMP", s)
