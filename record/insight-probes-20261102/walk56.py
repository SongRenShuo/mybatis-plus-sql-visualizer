import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
for i in range(2):
    pyautogui.click(411, 264)
    time.sleep(0.8)
W.shot("w56-sql-tab")
