import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(140, 294)     # 左树：Ui and Actions
time.sleep(2.5)
W.shot("w73-ui-page")
