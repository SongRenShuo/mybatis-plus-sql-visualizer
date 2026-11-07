import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui

W.front()
time.sleep(0.4)
W.assert_front()

xp_more = "//div[contains(@accessiblename,'更多')]"
comps = [c for c in robot.find_components(xp_more) if c.get("width", 0) > 0]
if not comps:
    W.log("no more button")
    raise SystemExit(1)
comps.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
x, y = robot.component_screen_center(xp_more, comp=comps[0])
W.log("click more", x, y)
pyautogui.click(x, y)
time.sleep(1.2)
# 比较SELECT结果 = moreActions 第 10 项 → 初始选中第 1 项，Down×9
for _ in range(9):
    pyautogui.press("down")
    time.sleep(0.15)
pyautogui.press("enter")
time.sleep(4.0)
W.shot("ui06-comparison")

LABELS = ("确认两段SQL方言适用于所选目标", "已审阅SELECT函数副作用", "两段SQL均已填入明确字面参数",
          "批准存在的原样替换片段", "单次批准此SQL对", "执行已批准比较", "审阅准确SQL对",
          "发现目标", "使用明确选中的目标")
for label in LABELS:
    xp = "//div[contains(@accessiblename,'%s')]" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    W.log("CMP", label, "n=%d" % len(hits), [(c.get("className", "").split(".")[-1], c.get("width"), c.get("height")) for c in hits[:3]])
    for c in hits[:1]:
        try:
            sx, sy = robot.component_screen_center(xp, comp=c)
            W.log("   screen", sx, sy)
        except Exception as e:
            W.log("   coord err", e)
W.log("DONE")
