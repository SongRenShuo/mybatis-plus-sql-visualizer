import time
import walk_util as W
import pyautogui
import pyperclip

W.front()
time.sleep(0.3)
pyautogui.press("esc")
time.sleep(0.4)
W.assert_front()
pyautogui.hotkey("ctrl", "shift", "a")
time.sleep(1.2)
pyperclip.copy("Open File")
pyautogui.hotkey("ctrl", "v")
time.sleep(1.5)
W.shot("w60-open-action")
pyautogui.press("enter")
time.sleep(2.5)
W.shot("w60-chooser")
