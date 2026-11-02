import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_win as WIN
import robot
import pyautogui

pid = W.sandbox_pid()
# 1) 点编辑器（主窗口）
main = None
best = 0
for hwnd, wpid, title, rect in WIN.by_pid(pid):
    area = (rect[2] - rect[0]) * (rect[3] - rect[1])
    if area > best:
        best, main = area, hwnd
WIN.foreground(main)
time.sleep(0.8)
pyautogui.click(700, 500)
time.sleep(0.6)

# 2) 选中一行 JOIN SQL
pyautogui.hotkey("ctrl", "f")
time.sleep(0.8)
pyautogui.hotkey("ctrl", "a")
time.sleep(0.2)
pyautogui.typewrite("SELECT o.id, o.order_no, o.amount, u.username", interval=0.03)
time.sleep(0.8)
pyautogui.press("return")
time.sleep(0.6)
pyautogui.press("escape")
time.sleep(0.5)

# 3) Alt+Shift+J 触发 SQL→Java
pyautogui.hotkey("alt", "shift", "j")
time.sleep(4.0)
W.shot("w3-21-java-action")
W.log("windows", [(t, r) for _, _, t, r in WIN.by_pid(pid)])
for lbl in ("选择实体", "SQL Visualizer - MP Java 代码", "复制", "转换失败"):
    W.log("TXT", lbl, W.texts(lbl, 6))
W.log("DONE")
