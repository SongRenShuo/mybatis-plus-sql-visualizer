import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui


def trust_if_needed():
    xp = "(//div[contains(@accessiblename,'Trust')])[2]"
    comp = robot.find_one(xp)
    if comp is None:
        return False
    chain = robot.find_components("(%s)/ancestor::div" % xp)
    sx = sum(int(p["x"]) for p in chain)
    sy = sum(int(p["y"]) for p in chain)
    pyautogui.click(sx + int(comp["x"]) + int(comp["width"]) // 2,
                    sy + int(comp["y"]) + int(comp["height"]) // 2)
    W.log("clicked trust")
    return True


trust_if_needed()
time.sleep(30)
# 等主窗口出现（标题非空）
pid, hwnd, title, rect = None, None, None, None
for _ in range(20):
    try:
        pid, hwnd, title, rect = W.ide_window()
        break
    except Exception:
        time.sleep(3)
W.log("main window", pid, repr(title)[:60] if title else None, rect)
time.sleep(5)
W.shot("wb36-second-run-final")
W.log("primer notification present?",
      W.texts("检测到 MyBatis mapper 工程", 4),
      W.texts("mapper 工程", 4))
W.log("version highlights present?", W.texts("新版本亮点", 4))
W.log("status widget", W.texts("SQL 预览", 6))
W.log("DONE")
