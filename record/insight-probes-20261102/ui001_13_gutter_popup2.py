import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()

xp = "//div[contains(@accessiblename,'全部')]"
hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
x, y = robot.component_screen_center(xp, comp=hits[0])
W.log("summary at", x, y)
# 首击可能被设置窗的聚焦吞掉：连点两次
pyautogui.click(x, y)
time.sleep(0.7)
pyautogui.click(x, y)
time.sleep(2.0)
W.shot("ui13-gutter-popup")
GROUP_LABELS = ("mapper XML 语句", "注解 SQL（@Select 族）", "JPA / HQL 族",
                "JdbcTemplate 字符串 SQL", "通用 XML 内嵌兜底", "Wrapper 链 / 方法名字符串 SQL",
                "全选", "全不选")
for label in GROUP_LABELS:
    xp2 = "//div[contains(@accessiblename,'%s')]" % label
    try:
        hs = [c for c in robot.find_components(xp2) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    except Exception as e:
        W.log("ERR", label, e)
        hs = []
    W.log("POPUP", label, "n=%d" % len(hs), [(c.get("className", "").split(".")[-1], c.get("width"), c.get("height")) for c in hs[:3]])
    for c in hs[:1]:
        try:
            sx, sy = robot.component_screen_center(xp2, comp=c)
            W.log("   screen", sx, sy)
        except Exception as e:
            W.log("   coord err", e)
W.log("DONE")
