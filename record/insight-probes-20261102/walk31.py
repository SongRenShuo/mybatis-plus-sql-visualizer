import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(1100, 340)
time.sleep(0.4)
pyautogui.click(1100, 340)
time.sleep(0.4)
pyautogui.write("t_order", interval=0.09)
time.sleep(0.8)
W.shot("w31-typed")
pyautogui.click(1456, 340)
time.sleep(3.5)
W.shot("w31-scanned")
