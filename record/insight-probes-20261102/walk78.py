import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(575, 210)
time.sleep(0.4)
pyautogui.hotkey("ctrl", "a")
time.sleep(0.2)
pyautogui.press("delete")
time.sleep(2.0)
W.shot("w78-tree-restored")
pyautogui.click(560, 434)
time.sleep(4.5)
W.shot("w78-ui-page")
