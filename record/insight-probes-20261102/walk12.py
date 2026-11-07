import time
import os
import walk_util as W
import robot
import pyautogui
from PIL import Image, ImageChops

W.front()
time.sleep(0.3)
# 关掉提示气泡（popup 对 robot 不可达，走 Esc）
pyautogui.press("esc")
time.sleep(0.6)

xp = "//div[contains(@class,'SqlLogPanel')]"
comps = [c for c in robot.find_components(xp) if c.get("width", 0) > 0]
px, py = robot.component_screen_center(xp, comp=comps[0])
W.log("panel center", px, py)
W.assert_front()
pyautogui.click(px, py - 100)
time.sleep(0.5)

# 像素带：两次帧差的亮带即选中行
a = pyautogui.screenshot()
pyautogui.press("down")
time.sleep(0.5)
b = pyautogui.screenshot()
diff = ImageChops.difference(a.convert("RGB"), b.convert("RGB"))
bbox = diff.getbbox()
W.log("diff bbox:", bbox)
if bbox:
    l, t, r, bo = bbox
    # 限制在列表区（避免其它动画干扰）
    if t > py - 260 and bo < py + 300:
        cx, cy = (l + r) // 2, (t + bo) // 2
        W.log("double-click at", cx, cy)
        pyautogui.click(cx, cy)
        time.sleep(0.3)
        pyautogui.doubleClick(cx, cy)
        time.sleep(3.0)
W.shot("w12-preview-open")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
