import time
import walk_util as W
import pyautogui
import pyperclip

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.hotkey("ctrl", "shift", "a")
time.sleep(1.5)
pyperclip.copy("Synchronize")
pyautogui.hotkey("ctrl", "v")
time.sleep(2.0)
W.shot("w59-sync-action")
pyautogui.press("enter")
time.sleep(3.0)
W.shot("w59-synced")
