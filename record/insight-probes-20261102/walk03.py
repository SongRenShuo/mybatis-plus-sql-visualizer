import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.4)
pyautogui.press("esc")
time.sleep(0.3)
pyautogui.hotkey("alt", "v")
time.sleep(1.0)
W.shot("w03-viewmenu")
