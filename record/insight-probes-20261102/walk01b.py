import time
import walk_util as W
import robot

W.front()
time.sleep(0.3)


def dump(xp, label, maxn=60):
    try:
        comps = robot.find_components(xp)
    except Exception as e:
        W.log(label, "ERR", e)
        return
    vis = [c for c in comps if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    W.log("== %s : total=%d visible=%d" % (label, len(comps), len(vis)))
    for c in vis[:maxn]:
        W.log("   ", c.get("className", "").split(".")[-1], "|",
              c.get("accessiblename") or "", "|", c.get("name") or "", "|",
              c.get("x"), c.get("y"), c.get("width"), c.get("height"))


dump("//div[contains(@accessiblename,'SQL')]", "accessible contains SQL")
dump("//div[contains(@class,'SquareStripeButton')]", "SquareStripeButton", 40)
dump("//div[contains(@accessiblename,'更多')]", "more")
dump("//div[contains(@accessiblename,'工具')]", "toolwindow-ish")
W.shot("w01b-dump")
