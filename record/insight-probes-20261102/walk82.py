import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(560, 500)      # 左树区域
time.sleep(0.6)
for i in range(4):
    pyautogui.press("down")
    time.sleep(1.2)
W.shot("w82-tree-keynav")
