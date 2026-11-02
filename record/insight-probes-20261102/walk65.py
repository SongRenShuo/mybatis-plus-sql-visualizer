import time
import walk_util as W
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(776, 490)     # 审阅准确SQL对（未选目标）
time.sleep(2.5)
W.shot("w65-review")
pyautogui.click(1488, 234)
time.sleep(1.0)
for _ in range(17):
    pyautogui.press("down")
    time.sleep(0.15)
pyautogui.press("enter")
time.sleep(3.5)
W.shot("w65-dml")
