import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui
import pyperclip


def center_of(label):
    xp = "//div[contains(@accessiblename,'%s')]" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    if not hits:
        return None
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    return robot.component_screen_center(xp, comp=hits[0])


W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.hotkey("ctrl", "alt", "s")
time.sleep(3.0)
pyautogui.click(583, 205)
time.sleep(0.4)
pyautogui.hotkey("ctrl", "a")
time.sleep(0.2)
pyperclip.copy("Gutter 图标来源分组")
pyautogui.hotkey("ctrl", "v")
time.sleep(2.5)
W.shot("wb22-gutter-row")
for label in ("Gutter 图标来源分组", "全部", "无"):
    W.log("TXT", label, W.texts(label, 5))
pt = center_of("全部")
W.log("summary", pt)
if pt:
    pyautogui.click(pt[0], pt[1])
    time.sleep(1.6)
    W.shot("wb23-gutter-popup")
    groups = ("mapper XML 语句", "注解 SQL（@Select 族）", "JPA / HQL 族",
              "JdbcTemplate 字符串 SQL", "通用 XML 内嵌兜底", "Wrapper 链 / 方法名字符串 SQL")
    for g in groups:
        W.log("GROUP", g, bool(center_of(g)))
    W.log("toggle 全选", bool(center_of("全选")), "全不选", bool(center_of("全不选")))
pyautogui.press("esc")
time.sleep(0.8)
pyautogui.press("esc")
time.sleep(1.2)
W.log("windows", [t for _, pid, t, _ in W.windows() if pid == W.sandbox_pid()])
W.log("DONE")
