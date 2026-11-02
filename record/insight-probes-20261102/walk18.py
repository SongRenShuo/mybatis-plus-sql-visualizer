import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(1410, 352)
time.sleep(0.4)
pyautogui.doubleClick(1410, 352)
time.sleep(3.5)
W.shot("w18-dblclick-row3")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
for line in W.texts("SQL 预览", 8):
    W.log("PREVIEW>", line)
