import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(1488, 234)
time.sleep(1.0)
for _ in range(6):
    pyautogui.press("down")
    time.sleep(0.2)
pyautogui.press("enter")
time.sleep(3.5)
W.shot("w27-usage-panel")
