import time
import walk_util as W
import pyautogui
import robot

W.front()
time.sleep(0.3)
comps = robot.find_components("//div[contains(@class,'ActionButton')]")
target = None
for i, c in enumerate(comps, 1):
    if c.get("width", 0) <= 0:
        continue
    xp = "(//div[contains(@class,'ActionButton')])[%d]" % i
    try:
        sx, sy = robot.component_screen_center(xp, comp=c)
    except Exception:
        continue
    if 1380 <= sx <= 1420 and 130 <= sy <= 170:
        target = (sx, sy)
        break
W.log("capture button:", target)
if target:
    W.assert_front()
    pyautogui.click(target[0], target[1])
    time.sleep(1.5)
W.shot("w06-capture-clicked")
for line in W.texts("捕捉", 6):
    W.log("CAP>", line)
for line in W.texts("等待", 6):
    W.log("WAIT>", line)
