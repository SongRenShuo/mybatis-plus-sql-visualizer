import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar3_util as W
import whitebar3_win as WIN
import robot
import pyautogui

pid = W.sandbox_pid()
W.log("pid", pid)

# 1) 最小化误开的浏览器（JetBrains 定价页）
for hwnd, wpid, title, rect in WIN.all_windows():
    if "购买 IntelliJ IDEA" in title or "jetbrains.com" in title:
        W.log("minimize browser", title)
        WIN.minimize(hwnd)

# 2) IDE 所有窗口（含无标题）
ws = WIN.by_pid(pid)
W.log("ide windows", [(t, r) for _, _, t, r in ws])
if ws:
    ws.sort(key=lambda w: (w[3][2] - w[3][0]) * (w[3][3] - w[3][1]), reverse=True)
    main = ws[0][0]
    W.log("foreground", WIN.foreground(main))
    time.sleep(1.0)
    # 小窗（Trust 对话框）再置前
    for hwnd, wpid, title, rect in ws:
        w, h = rect[2] - rect[0], rect[3] - rect[1]
        if 450 < w < 1000 and 150 < h < 600:
            W.log("fg dlg", title, rect, WIN.foreground(hwnd))
            time.sleep(0.8)
            break
W.shot("w3-01e-front")

XP = "//div[@accessiblename='Trust Project']"
hits = [c for c in robot.find_components(XP) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
W.log("trust cands", [(c.get("className", "").split(".")[-1], c.get("width"), c.get("height")) for c in hits])
if hits:
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    comp = hits[0]
    chain = robot.find_components("(%s)/ancestor::div" % XP)
    sx = sum(int(p["x"]) for p in chain)
    sy = sum(int(p["y"]) for p in chain)
    x = sx + int(comp["x"]) + int(comp["width"]) // 2
    y = sy + int(comp["y"]) + int(comp["height"]) // 2
    W.log("click trust", x, y)
    pyautogui.click(x, y)
    time.sleep(1.2)
    pyautogui.click(x, y)
time.sleep(20)
W.shot("w3-01e-after")
for label in ("Trust", "新版本亮点", "检测到 MyBatis", "SQL 预览"):
    W.log("TXT", label, W.texts(label, 6))
W.log("ide windows after", [(t, r) for _, _, t, r in WIN.by_pid(pid)])
W.log("DONE")
