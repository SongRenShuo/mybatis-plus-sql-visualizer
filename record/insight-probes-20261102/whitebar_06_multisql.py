import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui
import nav
from PIL import Image


def scan(name, title="SQL Visualizer"):
    W.shot(name)
    rect = W.window_rect(title=title, pid=W.sandbox_pid(), min_size=(300, 200))
    W.log("RECT", name, rect)
    if not rect:
        return
    im = Image.open(os.path.join(W.SHOTS, name + ".png")).convert("RGB")
    l, t, r, b = rect
    px = im.load()
    for y in range(t + 1, b - 1):
        cnt = sum(1 for x in range(l + 1, r - 1) if px[x, y][0] >= 200)
        if cnt > (r - l) * 0.3:
            W.log("  LIGHT ROW", y, cnt, px[l + 50, y])
    for x in range(l + 1, r - 1):
        cnt = sum(1 for y in range(t + 1, b - 1) if 100 <= px[x, y][0] <= 200)
        if cnt > (b - t) * 0.3:
            W.log("  LIGHT COL", x, cnt, px[x, t + 40])
    im.crop(rect).save(os.path.join(W.SHOTS, name + "-crop.png"))


W.front()
time.sleep(0.3)
W.assert_front()
# 关掉预览对话框，回到编辑器
pyautogui.press("esc")
time.sleep(1.5)
W.log("windows after esc", [t for _, pid, t, _ in W.windows() if pid == W.sandbox_pid()])
W.assert_front()
# 多 SQL 候选：全选文件后 Alt+Shift+Q
pyautogui.hotkey("ctrl", "a")
time.sleep(0.8)
pyautogui.hotkey("alt", "shift", "q")
time.sleep(4.0)
scan("wb11-multisql", title="SQL Visualizer")
for label in ("选择", "候选", "取消", "确定"):
    W.log("TXT", label, W.texts(label, 5))
W.log("DONE")
