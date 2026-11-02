import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
pyautogui.press("esc")
time.sleep(0.5)
W.assert_front()
pyautogui.click(450, 228)
time.sleep(1.2)
W.shot("w39-dialect-popup")
