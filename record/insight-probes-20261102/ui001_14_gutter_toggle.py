import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui


def center_of(label):
    xp = "//div[contains(@accessiblename,'%s')]" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    if not hits:
        return None, None
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    return robot.component_screen_center(xp, comp=hits[0]), hits[0]


W.front()
time.sleep(0.3)
W.assert_front()

pt, c = center_of("全不选")
W.log("toggle (select-none) at", pt)
if pt:
    pyautogui.click(pt[0], pt[1])
    time.sleep(1.5)
    W.shot("ui14-gutter-none")
    W.log("after none: 无 present?", W.texts("无", 4))
    W.log("after none: 全部 present?", W.texts("全部", 4))
    pt2, c2 = center_of("全选")
    W.log("toggle flipped to 全选 at", pt2)
    if pt2:
        pyautogui.click(pt2[0], pt2[1])
        time.sleep(1.5)
        W.shot("ui15-gutter-all")
        W.log("after all: 全部 present?", W.texts("全部", 4))

# 关浮层 + 取消设置（不落盘，避免改用户配置）
pyautogui.press("esc")
time.sleep(0.8)
ptc, cc = center_of("Cancel")
W.log("cancel at", ptc)
if ptc:
    pyautogui.click(ptc[0], ptc[1])
    time.sleep(1.5)
W.shot("ui15-settings-closed")
W.log("DONE")
