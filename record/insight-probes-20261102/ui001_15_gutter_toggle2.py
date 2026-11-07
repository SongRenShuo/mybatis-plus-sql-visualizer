import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui


def center_of(label):
    xp = "//div[contains(@accessiblename,'%s')]" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    if not hits:
        return None
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    return robot.component_screen_center(xp, comp=hits[0])


def dbl(pt, gap=0.6):
    pyautogui.click(pt[0], pt[1])
    time.sleep(gap)
    pyautogui.click(pt[0], pt[1])


W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.hotkey("ctrl", "alt", "s")
time.sleep(3.0)
W.shot("ui16-settings-reopen")
# 若没回到 UI and Actions 页，点左树该节点两次
if not W.texts("Gutter", 3):
    pyautogui.click(574, 571)
    time.sleep(0.7)
    pyautogui.click(574, 571)
    time.sleep(2.5)
    W.shot("ui16-ui-actions")
W.log("Gutter label", W.texts("Gutter", 3))

pt = center_of("全部")
W.log("summary", pt)
if not pt:
    raise SystemExit(1)
dbl(pt)
time.sleep(1.8)
W.shot("ui16-popup")
pt_none = center_of("全不选")
W.log("select-none btn", pt_none)
if pt_none:
    dbl(pt_none)
    time.sleep(1.5)
    W.shot("ui17-gutter-none")
    W.log("summary after none: 无?", W.texts("无", 4), "全部?", W.texts("全部", 4))
    pt_all = center_of("全选")
    W.log("select-all btn", pt_all)
    if pt_all:
        dbl(pt_all)
        time.sleep(1.5)
        W.shot("ui18-gutter-all")
        W.log("summary after all: 全部?", W.texts("全部", 4))
# 关浮层，再取消设置
pyautogui.press("esc")
time.sleep(0.8)
pyautogui.press("esc")
time.sleep(1.0)
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
W.log("DONE")
