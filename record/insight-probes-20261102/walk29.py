import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(1150, 475)
time.sleep(0.5)
pyautogui.write("t_order", interval=0.08)
time.sleep(0.8)
W.shot("w29-typed")
pyautogui.click(1470, 470)
time.sleep(3.0)
W.shot("w29-scanned")
