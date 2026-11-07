import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(411, 264)   # SQL 页签
time.sleep(1.2)
W.shot("w52-sql-tab")
