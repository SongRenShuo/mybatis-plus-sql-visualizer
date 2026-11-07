import time
import walk_util as W
import pyautogui
import pyperclip

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.hotkey("ctrl", "shift", "n")
time.sleep(1.5)
pyperclip.copy("walk-fixture")
pyautogui.hotkey("ctrl", "v")
time.sleep(4.0)
W.shot("w47-goto-file")
