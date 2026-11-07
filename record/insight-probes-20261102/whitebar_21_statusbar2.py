import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui

W.front()
time.sleep(1.0)
W.shot("wb33-statusbar-now")
# 状态栏组件：TextPresentationComponent 是平台状态栏文本 widget 的载体
for cls in ("TextPresentationComponent", "StatusBar", "EditorStatusBar"):
    try:
        hits = [c for c in robot.find_components("//div[contains(@class,'%s')]" % cls) if c.get("width", 0) > 0]
    except Exception as e:
        W.log("ERR", cls, e)
        hits = []
    W.log("CLS", cls, len(hits))
    for c in hits[:6]:
        try:
            sx, sy = robot.component_screen_center("//div[contains(@class,'%s')]" % cls, comp=c)
            W.log("    ", repr(c.get("name"))[:40], repr(c.get("accessiblename"))[:40], c.get("width"), c.get("height"), sx, sy)
        except Exception as e:
            W.log("    coord err", e)
for label in ("SQL 预览", "预览就绪", "捕获", "未捕获"):
    W.log("TXT", label, W.texts(label, 8))
W.log("DONE")
