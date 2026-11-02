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
pyautogui.click(900, 420)
time.sleep(0.5)
nav.goto_text("walkOptimizationFixture")
time.sleep(0.8)
W.shot("w50-cursor")
W.assert_front()
pyautogui.hotkey("alt", "shift", "q")
time.sleep(3.5)
W.shot("w50-preview")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
