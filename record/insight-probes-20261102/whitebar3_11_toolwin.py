import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_win as WIN
import pyautogui

# 关掉预览对话框：点右下角「取消」用键盘 Esc（先聚焦对话框）
pid = W.sandbox_pid()
prev = None
for hwnd, wpid, title, rect in WIN.by_pid(pid):
    if "SQL 预览" in title:
        prev = hwnd
if prev:
    WIN.foreground(prev)
    time.sleep(0.8)
    pyautogui.press("escape")
    time.sleep(1.5)
W.log("windows", [(t, r) for _, _, t, r in WIN.by_pid(pid)])
W.shot("w3-16-after-esc")

# 打开 SQL 日志工具窗：Find Action 搜工具窗名
pyautogui.hotkey("ctrl", "shift", "a")
time.sleep(1.2)
pyautogui.typewrite("SQL 日志", interval=0.06)
time.sleep(1.2)
W.shot("w3-17-find-action")
pyautogui.press("enter")
time.sleep(2.5)
W.shot("w3-18-toolwin")
for lbl in ("SQL → Java", "SQL 日志", "转换"):
    W.log("TXT", lbl, W.texts(lbl, 6))
W.log("DONE")
