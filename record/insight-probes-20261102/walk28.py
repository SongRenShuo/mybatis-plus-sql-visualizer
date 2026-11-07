import time
import walk_util as W
import pyautogui
import pyperclip

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(1110, 470)      # 表名输入框
time.sleep(0.3)
pyperclip.copy("t_order")
pyautogui.hotkey("ctrl", "v")
time.sleep(0.4)
pyautogui.click(1457, 470)      # 扫描所选范围
time.sleep(3.0)
W.shot("w28-usage-scanned")
