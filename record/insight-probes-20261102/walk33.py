import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(443, 377)
time.sleep(2.5)
W.shot("w33-usage-find")
