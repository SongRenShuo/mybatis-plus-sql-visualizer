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
pyautogui.hotkey("ctrl", "alt", "s")
time.sleep(3.0)
# 左树 speed search：点树内任一行后输入节点名回车
pyautogui.click(574, 571)
time.sleep(0.6)
pyautogui.typewrite("About", interval=0.12)
time.sleep(1.2)
pyautogui.press("enter")
time.sleep(2.5)
W.shot("ui24-about-page")
for label in ("Environment check", "Generate report", "环境体检", "生成体检单", "About"):
    W.log("TXT", label, W.texts(label, 5))
W.log("DONE")
