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
        return None
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    return robot.component_screen_center(xp, comp=hits[0])


W.front()
time.sleep(0.3)
W.assert_front()
pt = center_of("全部")
W.log("summary", pt)
if pt:
    pyautogui.click(pt[0], pt[1])
    time.sleep(1.8)
W.shot("ui19-popup-open")
pt_none = center_of("全不选")
pt_all = center_of("全选")
W.log("toggle none/all", pt_none, pt_all)
target = pt_none or pt_all
if target:
    pyautogui.click(target[0], target[1])
    time.sleep(1.8)
    W.shot("ui19-after-toggle")
    W.log("summary state: 无?", W.texts("无", 4), "全部?", W.texts("全部", 4))
    # 再点一次恢复
    again = center_of("全选") or center_of("全不选")
    W.log("toggle again", again)
    if again:
        pyautogui.click(again[0], again[1])
        time.sleep(1.5)
        W.shot("ui20-restored")
        W.log("summary state: 全部?", W.texts("全部", 4))
W.log("DONE")
