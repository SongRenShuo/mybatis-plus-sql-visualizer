import time
import walk_util as W
import pyautogui
import pyperclip

SQL = "SELECT * FROM t_order o, user u WHERE DATE(o.created_at) = '2026-01-01'"

W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.click(700, 330)
time.sleep(0.4)
pyautogui.click(700, 330)
time.sleep(0.3)
pyautogui.hotkey("ctrl", "a")
pyperclip.copy(SQL)
pyautogui.hotkey("ctrl", "v")
time.sleep(1.5)
W.shot("w43-sql-edited")
pyautogui.click(1488, 234)
time.sleep(1.0)
for _ in range(8):
    pyautogui.press("down")
    time.sleep(0.2)
pyautogui.press("enter")
time.sleep(4.0)
W.shot("w43-optimization")
