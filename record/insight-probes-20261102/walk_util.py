# -*- coding: utf-8 -*-
"""T-SqlInsight GUI 走查（20261031）共用胶水。

纪律：
- 坐标一律动态定位：robot 组件树 > anchor 模板 > 像素带 > window_rect；不写死目测坐标。
- 截图全屏落 build/MPSV-InsightWalk-shots/（build 生命周期，不入 git）。
- 每次输入前断言前台窗口 PID 是沙盒（elevated 探针由 _run_elev.bat 启动）。
"""
import os
import sys
import time
import glob

RECORD_DIR = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(RECORD_DIR)
SHOTS = os.path.join(REPO, "build", "MPSV-InsightWalk-shots")
SANDBOX_NAME = "MPSV-InsightWalk"
DEMO = os.path.join(os.path.dirname(REPO), "mybatis-plus-sql-visualizer-demo")

for _p in (RECORD_DIR, os.path.join(RECORD_DIR, "lib")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import pyautogui  # noqa: E402
pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

from locate import windows, window_rect, ensure_foreground, foreground_hwnd, pid_of_hwnd  # noqa: E402
import robot  # noqa: E402
import gifkit  # noqa: E402


def sandbox_pid():
    locks = glob.glob(os.path.join(REPO, "build", SANDBOX_NAME, "*", "*", "config", ".lock"))
    if not locks:
        return None
    txt = open(sorted(locks, key=os.path.getmtime)[-1]).read().strip()
    return int(txt) if txt.isdigit() else None


def ide_window():
    pid = sandbox_pid()
    if not pid:
        raise RuntimeError("未找到沙盒 IDE（%s 无 config/.lock）" % SANDBOX_NAME)
    for hwnd, wpid, title, rect in windows():
        if wpid == pid and (rect[2] - rect[0]) > 1100 and (rect[3] - rect[1]) > 650:
            return pid, hwnd, title, rect
    raise RuntimeError("沙盒 PID=%s 在，但没有主窗口" % pid)


def front():
    pid, hwnd, title, rect = ide_window()
    if not ensure_foreground(hwnd, tries=6):
        raise RuntimeError("沙盒无法置前")
    return rect


def assert_front():
    pid, hwnd, title, rect = ide_window()
    if pid_of_hwnd(foreground_hwnd()) != pid:
        raise RuntimeError("前台窗口不是沙盒（PID=%s），拒绝输入" % pid)
    return rect


def shot(name):
    os.makedirs(SHOTS, exist_ok=True)
    path = os.path.join(SHOTS, name + ".png")
    pyautogui.screenshot().save(path)
    log("shot " + path)
    return path


def log(*parts):
    """UTF-8 落盘日志（提权 bat 重定向的 cp936 会碎中文，走文件更可靠）。"""
    os.makedirs(SHOTS, exist_ok=True)
    line = " ".join(str(p) for p in parts)
    print(line.encode("utf-8", "replace").decode("utf-8", "replace"))
    with open(os.path.join(SHOTS, "probe.log"), "a", encoding="utf-8") as f:
        f.write(line + "\n")


def texts(contains=None, limit=40):
    """列出现有组件里 accessibleName/name 含给定片段的项（用于核对文案在场）。"""
    xp = "//div"
    if contains:
        xp = "//div[contains(@accessiblename,'%s') or contains(@name,'%s')]" % (contains, contains)
    out = []
    try:
        comps = robot.find_components(xp)
    except Exception as e:
        print("robot 查询失败:", e)
        return out
    for c in comps:
        nm = c.get("name") or c.get("accessiblename") or ""
        if c.get("width", 0) > 0 and c.get("height", 0) > 0:
            out.append("%s | %s | %dx%d" % (c.get("className", "").split(".")[-1], nm, c.get("width", 0), c.get("height", 0)))
    return out[:limit]


def click_text(contains, exact=False, offset=(0, 0)):
    """按 accessible name 点组件（robot 唯一命中才点）。"""
    if exact:
        xp = "//div[@accessiblename='%s']" % contains
    else:
        xp = "//div[contains(@accessiblename,'%s')]" % contains
    comps = [c for c in robot.find_components(xp) if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    if not comps:
        print("未命中组件:", contains)
        return False
    if len(comps) > 1:
        print("命中多个（%d），取最小面积：" % len(comps))
        comps.sort(key=lambda c: c.get("width", 0) * c.get("height", 0))
    c = comps[0]
    x, y = robot.component_screen_center(xp, comp=c)
    pyautogui.moveTo(x + offset[0], y + offset[1], duration=0.2)
    pyautogui.click(x + offset[0], y + offset[1])
    return True
