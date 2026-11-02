import time
import walk_util as W
import robot
import pyautogui
from PIL import ImageChops

W.front()
time.sleep(0.3)
pyautogui.press("esc")
time.sleep(0.5)
xp = "//div[contains(@class,'SqlLogPanel')]"
comps = [c for c in robot.find_components(xp) if c.get("width", 0) > 0]
px, py = robot.component_screen_center(xp, comp=comps[0])
pid, hwnd, title, rect = W.ide_window()
# 列表区 = 面板中心 x 附近，工具窗内的可见范围
region = (int(px) - 120, rect[1] + 150, int(px) + 130, rect[1] + 600)
W.log("region", region)

W.assert_front()
pyautogui.click(px, py - 100)
time.sleep(0.5)
a = pyautogui.screenshot().crop(region)
pyautogui.press("down")
time.sleep(0.6)
b = pyautogui.screenshot().crop(region)
bbox = ImageChops.difference(a.convert("RGB"), b.convert("RGB")).getbbox()
W.log("cropped diff bbox:", bbox)
if bbox and (bbox[3] - bbox[1]) < 120:
    cx = region[0] + (bbox[0] + bbox[2]) // 2
    cy = region[1] + (bbox[1] + bbox[3]) // 2
    W.log("double-click at", cx, cy)
    pyautogui.click(cx, cy)
    time.sleep(0.3)
    pyautogui.doubleClick(cx, cy)
    time.sleep(3.0)
W.shot("w13-preview-try")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
