import time
import walk_util as W
import robot
import pyautogui

W.front()
time.sleep(0.3)
for key in ("确认两段SQL方言", "两段SQL均已填入", "已审阅SELECT函数"):
    xp = "//div[contains(@class,'JCheckBox') and (contains(@accessiblename,'%s') or contains(@name,'%s'))]" % (key, key)
    comps = [c for c in robot.find_components(xp) if c.get("width", 0) > 0]
    W.log("== %s : %d" % (key, len(comps)))
    for c in comps[:3]:
        try:
            x, y = robot.component_screen_center(xp, comp=c)
        except Exception:
            x, y = -1, -1
        W.log("   ", x, y, c.get("width"), c.get("height"))
W.assert_front()
pyautogui.click(641, 490)     # 使用明确选中的目标（未选目标）
time.sleep(2.0)
W.shot("w64-target-required")
