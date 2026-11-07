import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui
import pyperclip


def comp_screen(label):
    xp = "//div[contains(@accessiblename,'%s')]" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    if not hits:
        return None, None
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    return robot.component_screen_center(xp, comp=hits[0]), hits[0]


def popup_open():
    return bool(comp_screen("全不选")[0] or comp_screen("全选")[0])


W.front()
time.sleep(0.3)
W.assert_front()
# 确保设置已打开
if not W.window_rect(title="Settings", pid=W.sandbox_pid(), min_size=(800, 500)):
    pyautogui.hotkey("ctrl", "alt", "s")
    time.sleep(3.0)
pyautogui.click(583, 205)
time.sleep(0.4)
pyautogui.hotkey("ctrl", "a")
time.sleep(0.2)
pyperclip.copy("Gutter 图标来源分组")
pyautogui.hotkey("ctrl", "v")
time.sleep(2.5)
pt, c = comp_screen("Gutter 图标来源分组")
W.log("row label", pt, c.get("width") if c else None)
W.shot("wb26-gutter-page")
if pt and c:
    bx = pt[0] + int(c["width"]) // 2 + 6 + 78 // 2
    by = pt[1]
    for attempt in range(3):
        pyautogui.click(bx, by)
        time.sleep(1.4)
        if popup_open():
            W.log("popup opened attempt", attempt + 1, "at", bx, by)
            break
    W.shot("wb26-gutter-popup")
    for g in ("mapper XML 语句", "注解 SQL（@Select 族）", "JPA / HQL 族",
              "JdbcTemplate 字符串 SQL", "通用 XML 内嵌兜底", "Wrapper 链 / 方法名字符串 SQL"):
        W.log("GROUP", g, comp_screen(g)[0])
    W.log("toggle 全不选", comp_screen("全不选")[0], "全选", comp_screen("全选")[0])
# 关浮层与设置（Esc 不落盘）
pyautogui.press("esc")
time.sleep(0.8)
pyautogui.press("esc")
time.sleep(1.2)
W.log("windows", [t for _, pid, t, _ in W.windows() if pid == W.sandbox_pid()])
W.log("DONE")
