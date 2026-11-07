import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.4)
W.assert_front()
pyautogui.click(1410, 352)
time.sleep(0.4)
pyautogui.doubleClick(1410, 352)
time.sleep(3.5)
W.shot("w63-preview")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
pyautogui.click(1488, 234)
time.sleep(1.0)
for _ in range(9):
    pyautogui.press("down")
    time.sleep(0.2)
pyautogui.press("enter")
time.sleep(3.5)
W.shot("w63-comparison")
