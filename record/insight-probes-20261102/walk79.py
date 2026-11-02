import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.moveTo(560, 600)
pyautogui.scroll(-12)
time.sleep(1.5)
W.shot("w79-tree-scrolled")
