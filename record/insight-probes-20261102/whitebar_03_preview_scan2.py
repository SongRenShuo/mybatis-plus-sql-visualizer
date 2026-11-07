import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui
import nav
import pyperclip
from PIL import Image

W.front()
time.sleep(0.3)
W.assert_front()
# 打开 OrderMapper.xml（Ctrl+Shift+N，避免依赖上次会话）
pyautogui.hotkey("ctrl", "shift", "n")
time.sleep(1.2)
pyperclip.copy("OrderMapper.xml")
pyautogui.hotkey("ctrl", "v")
time.sleep(1.0)
pyautogui.press("enter")
time.sleep(3.0)
W.shot("wb03-file-open")
nav.goto_text("selectOrdersByCondition")
time.sleep(0.8)
W.assert_front()
pyautogui.hotkey("alt", "shift", "q")
time.sleep(4.0)
W.shot("wb03-preview")
rect = W.window_rect(title="SQL Visualizer", pid=W.sandbox_pid(), min_size=(600, 400))
W.log("preview rect", rect)
if rect:
    im = Image.open(os.path.join(W.SHOTS, "wb03-preview.png")).convert("L")
    l, t, r, b = rect
    px = im.load()
    W.log("dialog size", r - l, b - t)
    rows = []
    for y in range(t + 2, b - 2):
        cnt = sum(1 for x in range(l + 2, r - 2) if px[x, y] >= 110)
        if cnt > (r - l - 4) * 0.4:
            rows.append((y, cnt))
    cols = []
    for x in range(l + 2, r - 2):
        cnt = sum(1 for y in range(t + 2, b - 2) if px[x, y] >= 110)
        if cnt > (b - t - 4) * 0.4:
            cols.append((x, cnt))
    W.log("light rows", rows[:20])
    W.log("light cols", cols[:20])
    im.crop(rect).save(os.path.join(W.SHOTS, "wb03-preview-crop.png"))
W.log("DONE")
