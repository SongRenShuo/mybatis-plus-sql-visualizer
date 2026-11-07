import time
import walk_util as W
import pyautogui
import pyperclip

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.hotkey("ctrl", "shift", "a")
time.sleep(1.2)
pyperclip.copy("SQL 日志")
pyautogui.hotkey("ctrl", "v")
time.sleep(1.5)
W.shot("w02-findaction")
pyautogui.press("enter")
time.sleep(2.5)
W.shot("w02-opened")
W.log("window title now:", W.ide_window()[2])
