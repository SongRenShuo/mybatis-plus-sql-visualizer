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


W.front()
time.sleep(0.3)
W.assert_front()
pt = center_of("更多")
W.log("more", pt)
if not pt:
    raise SystemExit(1)
pyautogui.click(pt[0], pt[1])
time.sleep(1.2)
for _ in range(6):
    pyautogui.press("down")
    time.sleep(0.15)
pyautogui.press("enter")
time.sleep(4.0)
W.shot("wb20-usage-panel")
for label in ("反查对象引用", "打开选中引用", "取消", "关闭分析"):
    p = center_of(label)
    W.log("BTN", label, p)
# 点取消证明可点（面板内取消无 accessibleName，用相邻按钮边界推导）
p_open = center_of("打开选中引用")
p_close = center_of("关闭分析")
if p_open and p_close:
    cx = (p_open[0] + 53 + p_close[0] - 41) // 2
    W.log("derived cancel", cx, p_open[1])
    pyautogui.click(cx, p_open[1])
    time.sleep(1.5)
W.shot("wb21-usage-cancel")
W.log("DONE")
