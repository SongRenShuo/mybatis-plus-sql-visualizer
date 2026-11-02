import time
import walk_util as W
import pyautogui
import pyperclip

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(575, 210)
time.sleep(0.4)
pyautogui.hotkey("ctrl", "a")
pyperclip.copy("gutter")
pyautogui.hotkey("ctrl", "v")
time.sleep(2.5)
W.shot("w77-search-gutter")
