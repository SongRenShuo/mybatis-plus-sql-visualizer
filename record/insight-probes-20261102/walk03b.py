import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
pyautogui.press("esc")
time.sleep(0.3)
pyautogui.hotkey("alt", "v")
time.sleep(0.8)
pyautogui.press("down")       # highlight Tool Windows
time.sleep(0.4)
pyautogui.press("right")      # expand submenu
time.sleep(0.9)
W.shot("w03b-toolwindows")
