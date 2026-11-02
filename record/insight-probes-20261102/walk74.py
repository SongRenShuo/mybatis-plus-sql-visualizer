import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.5)
W.assert_front()
pyautogui.hotkey("ctrl", "alt", "s")
time.sleep(4.0)
pyautogui.click(560, 294)     # 左树 Ui and Actions
time.sleep(2.5)
W.shot("w74-ui-page")
