import time
import walk_util as W
import robot
import pyautogui

W.front()
time.sleep(0.3)
# 焦点移到编辑器，再 Esc 关掉提示气球
try:
    comps = [c for c in robot.find_components("//div[contains(@class,'EditorImpl')]") if c.get("width", 0) > 0]
    W.log("editors:", len(comps))
    x, y = robot.component_screen_center("//div[contains(@class,'EditorImpl')]", comp=comps[0])
    W.assert_front()
    pyautogui.click(x, y)
    time.sleep(0.5)
except Exception as e:
    W.log("editor click failed:", e)
pyautogui.press("esc")
time.sleep(1.0)
W.shot("w15-after-esc")
