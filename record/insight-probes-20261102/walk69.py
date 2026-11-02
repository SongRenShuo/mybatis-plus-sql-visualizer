import time
import walk_util as W
import pyautogui
import pyperclip

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(585, 210)
time.sleep(0.5)
pyperclip.copy("SQL Visualizer")
pyautogui.hotkey("ctrl", "v")
time.sleep(2.5)
W.shot("w69-settings-search")
