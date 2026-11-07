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


W.front()
time.sleep(0.3)
W.assert_front()
pyautogui.hotkey("ctrl", "alt", "s")
time.sleep(3.0)
if not W.texts("Gutter", 3):
    pyautogui.click(574, 571)
    time.sleep(0.7)
    pyautogui.click(574, 571)
    time.sleep(2.5)
W.log("Gutter label", W.texts("Gutter", 3))
pt = center_of("全部")
W.log("summary", pt)
if pt:
    pyautogui.click(pt[0], pt[1])
    time.sleep(1.8)
W.shot("ui21-popup-open")
toggle = center_of("全不选") or center_of("全选")
W.log("toggle", toggle)
if toggle:
    pyautogui.click(toggle[0], toggle[1])
    time.sleep(1.6)
    W.shot("ui21-after-none")
    W.log("state after toggle: 无?", W.texts("无", 4), "全部?", W.texts("全部", 4))
    back = center_of("全选") or center_of("全不选")
    W.log("toggle back", back)
    if back:
        pyautogui.click(back[0], back[1])
        time.sleep(1.5)
        W.shot("ui22-restored")
        W.log("state restored: 全部?", W.texts("全部", 4))
# 关浮层 + 关设置（Esc 两次；不点 OK 即不落盘）
pyautogui.press("esc")
time.sleep(0.8)
pyautogui.press("esc")
time.sleep(1.2)
open_windows = [t for _, pid, t, _ in W.windows() if pid == W.sandbox_pid()]
W.log("windows after close", open_windows)
W.log("DONE")
