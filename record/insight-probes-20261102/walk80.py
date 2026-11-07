import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(560, 600)
time.sleep(0.5)
pyautogui.write("SQL Visualizer", interval=0.03)
time.sleep(3.0)
W.shot("w80-typeahead")
