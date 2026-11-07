import time
import walk_util as W
import robot

W.front()
time.sleep(0.3)
for key in ("反查", "打开选中引用", "关闭分析", "取消"):
    xp = "//div[contains(@class,'JButton') and (contains(@accessiblename,'%s') or contains(@name,'%s'))]" % (key, key)
    comps = robot.find_components(xp)
    vis = [c for c in comps if c.get("width", 0) > 0]
    W.log("== %s total=%d visible=%d" % (key, len(comps), len(vis)))
    for c in vis[:5]:
        try:
            x, y = robot.component_screen_center(xp, comp=c)
        except Exception:
            x, y = -1, -1
        W.log("   ", x, y, c.get("width"), c.get("height"))
