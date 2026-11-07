import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(1488, 234)
time.sleep(1.0)
for _ in range(2):
    pyautogui.press("down")
    time.sleep(0.2)
pyautogui.press("enter")
time.sleep(3.5)
W.shot("w21-param-scenario")
for key in ("参数场景", "XML", "脱敏", "条件遵循"):
    hits = W.texts(key, 6)
    W.log("== %s (%d)" % (key, len(hits)))
    for h in hits:
        W.log("   ", h)
