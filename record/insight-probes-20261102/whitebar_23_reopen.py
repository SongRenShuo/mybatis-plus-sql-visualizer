import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui


def click_label(label, exact=True):
    xp = ("//div[@accessiblename='%s']" % label) if exact else ("//div[contains(@accessiblename,'%s')]" % label)
    try:
        hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    except Exception as e:
        W.log("ERR", label, e)
        return False
    W.log("cands", label, [(c.get("className", "").split(".")[-1], c.get("width"), c.get("height")) for c in hits[:4]])
    if not hits:
        return False
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    sx, sy = robot.component_screen_center(xp, comp=hits[0])
    W.log("click", label, sx, sy)
    pyautogui.click(sx, sy)
    time.sleep(0.6)
    pyautogui.click(sx, sy)
    return True


W.log("windows", [t for _, pid, t, _ in W.windows() if pid == W.sandbox_pid()])
click_label("mybatis-plus-sql-visualizer-demo", exact=False)
time.sleep(25)
W.shot("wb37-reopen-project")
for hwnd, pid, title, rect in W.windows():
    if pid == W.sandbox_pid():
        W.log("WIN", title, rect)
for label in ("检测到 MyBatis mapper 工程", "新版本亮点", "SQL 预览", "打开 SQL 日志", "Trust"):
    W.log("TXT", label, W.texts(label, 6))
W.log("DONE")
