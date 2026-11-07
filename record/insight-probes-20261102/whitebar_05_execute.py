import time
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import whitebar_util as W
import robot
import pyautogui
from PIL import Image


def center_of(label):
    xp = "//div[contains(@accessiblename,'%s')]" % label
    hits = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    if not hits:
        return None
    hits.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    return robot.component_screen_center(xp, comp=hits[0])


W.front()
time.sleep(0.4)
W.assert_front()
# 先回到 SQL 页签（点对话框内页签，位置由截图量得：SQL 在 (399,295) 相对窗口 (365,165)）
pyautogui.click(365 + 34, 165 + 130)
time.sleep(1.5)
W.shot("wb08-sql-tab")
pt = center_of("执行 SQL")
W.log("execute button", pt)
if pt:
    pyautogui.click(pt[0], pt[1])
    time.sleep(3.0)
    W.shot("wb09-exec-clicked")
    for label in ("选择数据源", "数据源", "MySQL", "jpa_demo", "未绑定数据源", "执行失败"):
        W.log("TXT", label, W.texts(label, 5))
    # 若出现选择器：键盘选第一项
    if W.texts("选择数据源", 3):
        pyautogui.press("down")
        time.sleep(0.3)
        pyautogui.press("enter")
        W.log("picked first source")
    time.sleep(10)
    W.shot("wb10-exec-result")
    for label in ("执行失败", "选择数据源", "SQL 控制台", "结果"):
        W.log("AFTER", label, W.texts(label, 5))
    rect = W.window_rect(title="SQL Visualizer", pid=W.sandbox_pid(), min_size=(600, 400))
    W.log("rect", rect)
    if rect:
        im = Image.open(os.path.join(W.SHOTS, "wb10-exec-result.png")).convert("RGB")
        l, t, r, b = rect
        px = im.load()
        for y in range(t + 2, b - 2):
            cnt = sum(1 for x in range(l + 2, r - 2) if px[x, y][0] >= 200)
            if cnt > 400:
                W.log("LIGHT ROW", y, cnt, px[l + 400, y])
        for x in range(l + 2, r - 2):
            cnt = sum(1 for y in range(t + 2, b - 2) if 100 <= px[x, y][0] <= 200)
            if cnt > 250:
                W.log("LIGHT COL", x, cnt, px[x, t + 300])
W.log("DONE")
