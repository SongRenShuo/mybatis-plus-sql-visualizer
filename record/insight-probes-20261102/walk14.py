import time
import walk_util as W
import robot

W.front()
time.sleep(0.3)
pid, hwnd, title, rect = W.ide_window()
comps = robot.find_components("//div")
out = []
for i, c in enumerate(comps, 1):
    if c.get("width", 0) <= 0 or c.get("height", 0) <= 0:
        continue
    try:
        sx, sy = robot.component_screen_center("(//div)[%d]" % i, comp=c)
    except Exception:
        continue
    if 1370 <= sx <= 1625 and 150 <= sy <= 640:
        out.append((c.get("className", "").split(".")[-1], sx, sy, c.get("width"), c.get("height"),
                    c.get("accessiblename") or ""))
for s in sorted(set(out), key=lambda t: (t[2], t[1]))[:40]:
    W.log("L", s)
