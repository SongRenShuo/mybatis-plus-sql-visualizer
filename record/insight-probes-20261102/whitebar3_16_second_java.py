import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_win as WIN
import pyautogui

pid = W.sandbox_pid()
main = None
best = 0
for hwnd, wpid, title, rect in WIN.by_pid(pid):
    area = (rect[2] - rect[0]) * (rect[3] - rect[1])
    if area > best:
        best, main = area, hwnd
WIN.foreground(main)
time.sleep(1.0)
W.shot("w3-23-second-run")

# 选中第 13 行 SQL
pyautogui.click(700, 500)
time.sleep(0.5)
pyautogui.hotkey("ctrl", "f")
time.sleep(0.8)
pyautogui.hotkey("ctrl", "a")
time.sleep(0.2)
pyautogui.typewrite("SELECT o.id, o.order_no, o.amount, u.username", interval=0.03)
time.sleep(0.8)
pyautogui.press("return")
time.sleep(0.6)
pyautogui.press("escape")
time.sleep(0.6)

# 右键菜单 → 键入 Convert 检索 → Enter
pyautogui.click(700, 377, button="right")
time.sleep(1.5)
W.shot("w3-24-context-menu")
pyautogui.typewrite("Convert SQL", interval=0.05)
time.sleep(1.0)
W.shot("w3-25-menu-typed")
pyautogui.press("enter")
time.sleep(4.0)
W.shot("w3-26-java-dialog")
W.log("windows", [(t, r) for _, _, t, r in WIN.by_pid(pid)])
for lbl in ("选择实体", "MP Java", "复制", "未找到"):
    W.log("TXT", lbl, W.texts(lbl, 6))
W.log("DONE")
