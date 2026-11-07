import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui
import nav
from PIL import Image


def scan_window(title, name, min_size=(300, 200)):
    W.shot(name)
    rect = W.window_rect(title=title, pid=W.sandbox_pid(), min_size=min_size)
    W.log("RECT", name, title, rect)
    if not rect:
        return
    im = Image.open(os.path.join(W.SHOTS, name + ".png")).convert("RGB")
    l, t, r, b = rect
    px = im.load()
    rows = [(y, sum(1 for x in range(l + 1, r - 1) if px[x, y][0] >= 200)) for y in range(t + 1, b - 1)]
    cols = [(x, sum(1 for y in range(t + 1, b - 1) if 100 <= px[x, y][0] <= 200)) for x in range(l + 1, r - 1)]
    W.log("  light rows", [q for q in rows if q[1] > (r - l) * 0.3][:10])
    W.log("  light cols", [q for q in cols if q[1] > (b - t) * 0.3][:10])
    im.crop(rect).save(os.path.join(W.SHOTS, name + "-crop.png"))


W.front()
time.sleep(0.3)
W.assert_front()
for _ in range(3):
    pyautogui.press("esc")
    time.sleep(0.8)
W.log("windows", [t for _, pid, t, _ in W.windows() if pid == W.sandbox_pid()])
W.assert_front()
nav.goto_text("selectOrdersByCondition")
time.sleep(0.6)
pyautogui.hotkey("alt", "shift", "j")
time.sleep(4.0)
W.shot("wb13-java-preview")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
for label in ("Java", "预览", "转换"):
    W.log("TXT", label, W.texts(label, 5))
for title in ("Java", "SQL", "预览"):
    scan_window(title, "wb13-java-" + title)
W.log("DONE")
