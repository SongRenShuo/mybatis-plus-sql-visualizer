import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui
from PIL import Image


def scan_window(title, name, min_size=(300, 200)):
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
# 先点左树行（可见的 Appearance 行）拿到树焦点，再用 speed search 跳 About
pyautogui.click(555, 265)
time.sleep(0.8)
pyautogui.click(555, 265)
time.sleep(0.8)
pyautogui.typewrite("About", interval=0.15)
time.sleep(1.5)
pyautogui.press("enter")
time.sleep(3.0)
scan_window("Settings", "wb15-about-page", min_size=(800, 500))
for label in ("环境体检", "变更日志", "生成体检报告", "更新与诊断"):
    W.log("TXT", label, W.texts(label, 5))
W.log("DONE")
