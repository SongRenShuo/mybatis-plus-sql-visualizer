import time
import walk_util as W
import pyautogui
import pyperclip

W.front()
time.sleep(0.3)
pyautogui.press("esc")
time.sleep(0.5)
W.assert_front()
pyautogui.hotkey("ctrl", "shift", "n")
time.sleep(1.5)
pyperclip.copy("walk-fixture-candidate.sql")
pyautogui.hotkey("ctrl", "v")
time.sleep(3.0)
W.shot("w45-goto-file")
pyautogui.press("enter")
time.sleep(3.0)
W.shot("w45-opened")
W.log("title:", W.ide_window()[2])
