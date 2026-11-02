import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(585, 434)     # 左树 Ui and Actions（crop y294 + 140）
time.sleep(2.5)
W.shot("w75-ui-page")
