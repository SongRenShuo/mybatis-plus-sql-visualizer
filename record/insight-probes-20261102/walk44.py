import time
import walk_util as W
import pyautogui
import pyperclip

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(1496, 840)      # 关预览
time.sleep(2.0)
W.assert_front()
pyautogui.hotkey("ctrl", "shift", "n")
time.sleep(1.2)
pyperclip.copy("walk-fixture-candidate")
pyautogui.hotkey("ctrl", "v")
time.sleep(1.5)
pyautogui.press("enter")
time.sleep(3.0)
W.shot("w44-fixture-open")
W.assert_front()
pyautogui.click(900, 300)
time.sleep(0.4)
pyautogui.hotkey("ctrl", "a")
time.sleep(0.4)
pyautogui.hotkey("alt", "shift", "q")
time.sleep(3.5)
W.shot("w44-fixture-preview")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
