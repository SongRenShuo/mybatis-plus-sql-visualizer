import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(575, 210)
time.sleep(0.6)
pyautogui.click(575, 210)
time.sleep(0.4)
pyautogui.write("SQL Visualizer", interval=0.06)
time.sleep(2.5)
W.shot("w70-settings-search")
