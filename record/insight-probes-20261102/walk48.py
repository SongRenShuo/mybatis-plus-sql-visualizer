import time
import walk_util as W
import pyautogui
import sys
import os
sys.path.insert(0, os.path.join(W.RECORD_DIR, "lib"))
import nav

W.front()
time.sleep(0.5)
W.assert_front()
pyautogui.click(1457, 117)   # 回到 SQL 日志页签，避免焦点在工具窗编辑器
time.sleep(0.8)
pyautogui.click(900, 420)    # 编辑器正文（OrderMapper.xml）
time.sleep(0.6)
nav.goto_text("walkOptimizationFixture")
time.sleep(0.8)
W.shot("w48-fixture-cursor")
W.assert_front()
pyautogui.hotkey("alt", "shift", "q")
time.sleep(3.5)
W.shot("w48-fixture-preview")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
