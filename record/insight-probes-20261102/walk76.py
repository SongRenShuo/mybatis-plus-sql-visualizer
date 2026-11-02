import time
import walk_util as W
import robot

W.front()
time.sleep(0.3)
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
for key in ("Ui and Actions", "Banned APIs", "External SQL Template", "SQL 日志"):
    xp = "//div[contains(@accessiblename,'%s')]" % key
    comps = [c for c in robot.find_components(xp) if c.get("width", 0) > 0]
    W.log("== %s : %d" % (key, len(comps)))
    for c in comps[:3]:
        try:
            x, y = robot.component_screen_center(xp, comp=c)
        except Exception:
            x, y = -1, -1
        W.log("   ", c.get("className", "").split(".")[-1], x, y, c.get("width"), c.get("height"))
