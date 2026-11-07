import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui001_util as W
import robot
import pyautogui

W.front()
time.sleep(0.3)
W.assert_front()
px = W.window_rect(title="Settings", pid=W.sandbox_pid(), min_size=(800, 500))
W.log("settings rect", px)
# 设置左树的节点不暴露 accessible name（robot 查 0），只能按截图量得的行位置点。
# 截图 ui09-search-gutter.png（全屏 1920x1080）里 "UI and Actions" 行位于 (574, 571)。
pyautogui.click(574, 571)
time.sleep(3.0)
W.shot("ui10-ui-actions")
for label in ("Gutter", "来源", "分组", "全部", "无", "已启用", "界面与操作"):
    W.log("TXT", label, W.texts(label, 6))
# 整页组件里找摘要按钮（JButton 且宽高像按钮的）
for cls in ("JBLabel", "JButton", "JCheckBox"):
    try:
        hits = [c for c in robot.find_components("//div[contains(@class,'%s')]" % cls) if c.get("width", 0) > 0]
        W.log("CLS", cls, len(hits), [(c.get("width"), c.get("height")) for c in hits[:8]])
    except Exception as e:
        W.log("CLS ERR", cls, e)
W.log("DONE")
