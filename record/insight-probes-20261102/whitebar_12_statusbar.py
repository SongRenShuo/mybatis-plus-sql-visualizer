import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui


def center_of(label, cls=None):
    if cls:
        xp = "//div[contains(@class,'%s') and contains(@accessiblename,'%s')]" % (cls, label)
    else:
        xp = "//div[contains(@accessiblename,'%s')]" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    if not hits:
        return None, None
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    return robot.component_screen_center(xp, comp=hits[0]), hits[0]


W.front()
time.sleep(0.3)
W.assert_front()
# 关设置
pyautogui.press("esc")
time.sleep(1.2)
pyautogui.press("esc")
time.sleep(1.5)
W.log("windows", [t for _, pid, t, _ in W.windows() if pid == W.sandbox_pid()])
pt, c = center_of("SQL 预览", cls="TextPresentationComponent")
W.log("status widget", pt, c.get("width") if c else None, c.get("height") if c else None)
if pt:
    pyautogui.click(pt[0], pt[1])
    time.sleep(1.5)
    pyautogui.click(pt[0], pt[1])   # 首击可能只聚焦
    time.sleep(3.0)
W.shot("wb19-widget-click")
for label in ("SQL 日志", "捕捉", "开始捕捉"):
    W.log("TXT", label, W.texts(label, 6))
W.log("DONE")
