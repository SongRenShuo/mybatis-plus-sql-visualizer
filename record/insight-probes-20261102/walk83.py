import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
for _ in range(3):
    pyautogui.press("up")
    time.sleep(1.0)
time.sleep(1.5)
W.shot("w83-ui-and-actions")
