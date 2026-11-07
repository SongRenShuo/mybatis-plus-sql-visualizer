import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui
import pyperclip
from PIL import Image


def scan(name, title="Settings", min_size=(800, 500)):
    W.shot(name)
    rect = W.window_rect(title=title, pid=W.sandbox_pid(), min_size=min_size)
    W.log("RECT", name, rect)
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
scan("wb17-about-clean")
# 搜「脱敏白名单」跳到 SQL 预览页
pyautogui.click(583, 205)
time.sleep(0.4)
pyautogui.hotkey("ctrl", "a")
pyperclip.copy("脱敏白名单")
pyautogui.hotkey("ctrl", "v")
time.sleep(2.5)
scan("wb18-mask-whitelist")
for label in ("脱敏白名单", "Masking"):
    W.log("TXT", label, W.texts(label, 5))
W.log("DONE")
