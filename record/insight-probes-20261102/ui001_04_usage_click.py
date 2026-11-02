import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui

W.front()
time.sleep(0.4)
W.assert_front()


def btn_center(label):
    xp = "//div[contains(@accessiblename,'%s')]" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    if not hits:
        W.log("MISS", label)
        return None
    return robot.component_screen_center(xp, comp=hits[0])


def click_label(label):
    pt = btn_center(label)
    if pt is None:
        return False
    W.log("click", label, pt)
    pyautogui.click(pt[0], pt[1])
    return True


# 1) 扫描所选范围
click_label("扫描所选范围")
time.sleep(6)
W.shot("ui04-usage-scan")
for key in ("candidateCount", "SqlUsageCoverage", "已扫描", "complete="):
    W.log("AFTER-SCAN", key, W.texts(key, 4))

# 2) 反查对象引用（requires scanned report）
click_label("反查对象引用")
time.sleep(2.5)
W.shot("ui04-usage-find")
for key in ("complete=", "DATABASE_IDENTITY_UNKNOWN", "USAGE_CONTEXT_UNKNOWN", "表名分段"):
    W.log("AFTER-FIND", key, W.texts(key, 4))

# 3) 取消
click_label("取消")
time.sleep(1.5)
W.shot("ui04-usage-cancel")
for key in ("已取消",):
    W.log("AFTER-CANCEL", key, W.texts(key, 4))

W.log("DONE")
