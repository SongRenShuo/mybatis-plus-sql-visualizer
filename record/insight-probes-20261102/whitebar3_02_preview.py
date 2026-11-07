import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import robot
import pyautogui
import nav

W.front()
time.sleep(0.5)
W.assert_front()

# 1) 打开 OrderMapper.xml：优先点已存在的页签（robot 文本定位），否则 Ctrl+Shift+N
opened = False
try:
    hits = [c for c in robot.find_components("//div[contains(@accessiblename,'OrderMapper.xml')]")
            if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    W.log("tab cands", [(c.get("className", "").split(".")[-1], c.get("width"), c.get("height")) for c in hits[:5]])
    if hits:
        hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
        x, y = robot.component_screen_center("//div[contains(@accessiblename,'OrderMapper.xml')]", comp=hits[0])
        W.log("click tab", x, y)
        pyautogui.click(x, y)
        time.sleep(1.5)
        opened = True
except Exception as e:
    W.log("tab lookup failed", e)

if not opened:
    pyautogui.hotkey("ctrl", "shift", "n")
    time.sleep(1.2)
    W.shot("w3-02-goto-file")
    pyautogui.typewrite("OrderMapper.xml", interval=0.06)
    time.sleep(1.2)
    pyautogui.keyDown("return"); pyautogui.keyUp("return")
    time.sleep(2.0)

W.shot("w3-02-file-open")

# 2) 光标落到 selectOrdersByCondition 上，触发预览
nav.goto_text("selectOrdersByCondition")
time.sleep(0.6)
W.assert_front()
pyautogui.hotkey("alt", "shift", "q")
time.sleep(4.0)
W.shot("w3-02-preview")
rect = W.window_rect(title="SQL Visualizer", pid=W.sandbox_pid(), min_size=(600, 400))
W.log("preview rect", rect)
for lbl in ("SQL 预览", "未在光标处", "参数场景", "更多"):
    W.log("TXT", lbl, W.texts(lbl, 5))
W.log("DONE")
