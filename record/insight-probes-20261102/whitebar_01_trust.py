import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui

# 首启 Trust 弹层：主窗标题尚空，robot 的 component_screen_center 缺 IdeFrameImpl，
# 沿该组件自己的祖先链累加到对话框根窗口（MyDialog 的 x/y 已是屏幕坐标）。
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
W.log("click Trust Project at", x, y)
pyautogui.click(x, y)
time.sleep(8)
W.shot("wb28-trust2-after")
time.sleep(20)
W.shot("wb29-second-open-primer")
for label in ("检测到 MyBatis mapper 工程", "SQL 预览", "捕获", "打开 SQL 日志"):
    W.log("TXT", label, W.texts(label, 6))
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
W.log("DONE")
