import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(1488, 234)
time.sleep(1.2)
W.shot("w20-more-menu")
pyautogui.press("down")
time.sleep(0.3)
pyautogui.press("enter")
time.sleep(4.0)
W.shot("w20-analysis")
for key in ("分析质量", "结构", "离线", "读写", "仅离线分析"):
    hits = W.texts(key, 5)
    W.log("== %s (%d)" % (key, len(hits)))
    for h in hits:
        W.log("   ", h)
