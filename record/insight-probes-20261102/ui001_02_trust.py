import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui

# 主窗尚未建立（标题空）时 robot.component_screen_center 因缺 IdeFrameImpl 拒绝算坐标；
# 这里直接沿该组件自己的祖先链累加到对话框根窗口（MyDialog 的 x/y 已是屏幕坐标）。
xp = "(//div[contains(@accessiblename,'Trust')])[2]"
comp = robot.find_one(xp)
if comp is None:
    W.log("trust button not found; abort")
    raise SystemExit(1)
chain = robot.find_components("(%s)/ancestor::div" % xp)
sx = sum(int(p["x"]) for p in chain)
sy = sum(int(p["y"]) for p in chain)
x = sx + int(comp["x"]) + int(comp["width"]) // 2
y = sy + int(comp["y"]) + int(comp["height"]) // 2
W.log("click Trust Project at", x, y, "chain_top", chain[0].get("className", "").split(".")[-1] if chain else None)
pyautogui.click(x, y)
time.sleep(30)
W.shot("ui02-after-trust")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
W.log("DONE")
