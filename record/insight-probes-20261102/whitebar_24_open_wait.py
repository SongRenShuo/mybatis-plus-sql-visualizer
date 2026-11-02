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
# 等主窗（标题非空）
for _ in range(25):
    try:
        pid, hwnd, title, rect = W.ide_window()
        W.log("main window", pid, repr(title)[:60], rect)
        break
    except Exception:
        time.sleep(3)
time.sleep(8)
W.shot("wb39-project-open")
for label in ("检测到 MyBatis mapper 工程", "SQL 预览", "捕获", "打开 SQL 日志"):
    W.log("TXT", label, W.texts(label, 6))
# 检查持久化文件是否已写（primer 领取即写）
W.log("DONE")
