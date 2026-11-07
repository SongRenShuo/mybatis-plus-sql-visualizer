import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui


def center_of(label):
    xp = "//div[contains(@accessiblename,'%s')]" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    if not hits:
        return None
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    return robot.component_screen_center(xp, comp=hits[0])


def popup_open():
    return bool(center_of("全不选") or center_of("全选"))


W.front()
time.sleep(0.3)
W.assert_front()
pt = center_of("全部")
W.log("summary", pt)
for attempt in range(3):
    if pt:
        pyautogui.click(pt[0], pt[1])
        time.sleep(1.4)
    if popup_open():
        W.log("popup opened at attempt", attempt + 1)
        break
    W.log("attempt", attempt + 1, "no popup")
W.shot("wb24-gutter-popup")
for g in ("mapper XML 语句", "注解 SQL（@Select 族）", "JPA / HQL 族",
          "JdbcTemplate 字符串 SQL", "通用 XML 内嵌兜底", "Wrapper 链 / 方法名字符串 SQL"):
    W.log("GROUP", g, center_of(g))
W.log("toggle 全不选", center_of("全不选"), "全选", center_of("全选"))
W.log("DONE")
