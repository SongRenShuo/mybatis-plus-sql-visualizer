import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
w = W.ide_window()[3]
W.assert_front()
pyautogui.click(1457, 117)   # 工具窗「日志 → SQL」页签
time.sleep(2.0)
W.shot("w46-log2sql")
