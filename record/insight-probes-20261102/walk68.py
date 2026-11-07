import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
pyautogui.press("esc")      # 取消文件选择
time.sleep(1.2)
W.assert_front()
pyautogui.hotkey("ctrl", "alt", "s")
time.sleep(4.0)
W.shot("w68-settings")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
