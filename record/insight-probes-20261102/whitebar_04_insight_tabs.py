import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui
from PIL import Image


def more():
    xp = "//div[contains(@accessiblename,'更多')]"
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0]
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    x, y = robot.component_screen_center(xp, comp=hits[0])
    pyautogui.click(x, y)
    time.sleep(1.2)


def open_item(downs, name):
    more()
    for _ in range(downs):
        pyautogui.press("down")
        time.sleep(0.15)
    pyautogui.press("enter")
    time.sleep(3.5)
    W.shot(name)
    rect = W.window_rect(title="SQL Visualizer", pid=W.sandbox_pid(), min_size=(600, 400))
    W.log("RECT", name, rect)
    if not rect:
        return
    im = Image.open(os.path.join(W.SHOTS, name + ".png")).convert("L")
    l, t, r, b = rect
    px = im.load()
    rows, cols = [], []
    for y in range(t + 2, b - 2):
        cnt = sum(1 for x in range(l + 2, r - 2) if px[x, y] >= 100)
        if cnt > (r - l - 4) * 0.25:
            rows.append((y, cnt))
    for x in range(l + 2, r - 2):
        cnt = sum(1 for y in range(t + 2, b - 2) if px[x, y] >= 100)
        if cnt > (b - t - 4) * 0.25:
            cols.append((x, cnt))
    W.log("SCAN", name, "rows", rows[:12], "cols", cols[:12])
    im.crop(rect).save(os.path.join(W.SHOTS, name + "-crop.png"))


W.front()
time.sleep(0.4)
W.assert_front()
for downs, name in ((2, "wb04-scenarios"), (7, "wb05-schema"), (9, "wb06-comparison"), (11, "wb07-diff")):
    open_item(downs, name)
W.log("DONE")
