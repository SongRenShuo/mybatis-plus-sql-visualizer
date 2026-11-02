import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
for _ in range(2):
    pyautogui.press("esc")
    time.sleep(0.5)
W.assert_front()
pyautogui.click(1500, 880)   # 取消（关闭预览对话框）
time.sleep(2.0)
W.shot("w40-closed")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
