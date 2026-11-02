import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.4)
W.assert_front()
pyautogui.click(1488, 234)
time.sleep(1.0)
for _ in range(8):
    pyautogui.press("down")
    time.sleep(0.2)
pyautogui.press("enter")
time.sleep(4.0)
W.shot("w51-optimization")
