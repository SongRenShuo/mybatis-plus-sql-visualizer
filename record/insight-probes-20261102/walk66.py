import time
import walk_util as W
import robot
import pyautogui

W.front()
time.sleep(0.3)
for key in ("切换数据源", "数据源", "已绑定", "上次使用"):
    xp = "//div[contains(@accessiblename,'%s')]" % key
    comps = [c for c in robot.find_components(xp) if c.get("width", 0) > 0]
    W.log("== %s : %d" % (key, len(comps)))
    for c in comps[:6]:
        W.log("   ", c.get("className", "").split(".")[-1], c.get("width"), c.get("height"))
W.assert_front()
pyautogui.click(1311, 234)     # 执行计划
time.sleep(4.0)
W.shot("w66-explain")
