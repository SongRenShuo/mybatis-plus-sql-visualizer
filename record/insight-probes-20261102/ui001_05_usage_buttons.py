import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui

W.front()
time.sleep(0.4)
W.assert_front()
# 先关掉上一轮误点状态栏弹出的 Line Separator 浮层
pyautogui.press("esc")
time.sleep(0.8)


def center(label):
    xp = "//div[contains(@accessiblename,'%s')]" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    if not hits:
        return None, None
    return robot.component_screen_center(xp, comp=hits[0]), hits[0]


# 打开选中引用（表为空 → 按实现应无反应；点它证明按钮未遮挡、事件可送达另见其他按钮）
pt, c_open = center("打开选中引用")
W.log("open-selected btn", pt, c_open.get("width"), c_open.get("height"))
if pt:
    pyautogui.click(pt[0], pt[1])
    time.sleep(1.5)
W.shot("ui05-usage-open-selected")

# 面板内「取消」没有 accessibleName（robot 只命中预览对话框底部的取消），
# 用同一行相邻按钮的边界求它：取消 left = 打开选中引用 right + hgap(5)，
# 取消 right = 关闭分析 left - hgap(5)；中心取中点。
pt_close, c_close = center("关闭分析")
W.log("close btn", pt_close, c_close.get("width") if c_close else None)
if pt and pt_close and c_open and c_close:
    open_right = pt[0] + int(c_open["width"]) // 2
    close_left = pt_close[0] - int(c_close["width"]) // 2
    cancel_x = (open_right + 5 + close_left - 5) // 2
    cancel_y = pt[1]
    W.log("derived cancel", open_right, close_left, cancel_x, cancel_y)
    pyautogui.click(cancel_x, cancel_y)
    time.sleep(1.5)
    W.shot("ui05-usage-cancel")
else:
    W.log("cannot derive cancel; abort")
    raise SystemExit(1)

# 关闭分析 → 面板页签应关闭
pyautogui.click(pt_close[0], pt_close[1])
time.sleep(2.0)
W.shot("ui05-usage-closed")
W.log("DONE")
