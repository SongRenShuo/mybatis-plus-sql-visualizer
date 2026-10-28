# -*- coding: utf-8 -*-
"""R11 SQL 日志主图重录（T-DocsRefresh1023）。

相对 20261016 版（scene_t5）的差异：
- 工具栏图标已换成插件自带 SVG（T-ToolbarIconSemantics，243/262 同形）；
- 捕捉期间底部有双通道状态栏（R53）；
- 末段多切一次页签，让单行页签（O-149）在画面里出现。

前置：demo 应用已在 8081 运行；SQL Visualizer 工具窗已开。"""
import io
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "lib"))

from PIL import Image, ImageChops
import pyautogui

import robot
from gifkit import Recorder
from locate import ensure_foreground, sandbox_pid, windows

REGION = (285, 26, 1354, 974)
OUT = HERE / "perf" / "r11_sqllog.mp4"
LOG_TAB = '//div[contains(@class,"TabLabel") and contains(@accessiblename,"SQL 日志")]'
HISTORY_TAB = '//div[contains(@class,"TabLabel") and contains(@accessiblename,"SQL 历史")]'
# 「清空」在日志页与「日志 → SQL」页各有一枚：取坐标靠上、且在当前可见页内的那个
CLEAR = '//div[contains(@class,"ActionButton") and contains(@accessiblename,"清空")]'
def _clear_xpath():
    """按 y 坐标选日志页那枚（日志页工具栏在顶部，y 更小）。"""
    comps = robot.find_components(CLEAR)
    if not comps:
        raise RuntimeError("clear button not found")
    return min(comps, key=lambda c: c.get("y", 0))["id"]
# 列表区域：T-SqlListNativeVisual 重写后组件类名不再是 ViewportWidthSqlEntryList，
# 且 robot 的 XPath 不支持后代轴——改用固定像素区域做「列表是否变化」的对比。
# 区域取工具窗中部（页签下方、状态栏上方），region 相对坐标。
LIST_AREA = (860, 110, 1290, 700)


def feed(path):
    with urllib.request.urlopen("http://127.0.0.1:8081" + path, timeout=12) as response:
        if response.status != 200:
            raise RuntimeError(f"demo endpoint failed: {path} => {response.status}")
        response.read()


def list_pixels():
    """截列表固定区域（region 相对坐标）用于「是否可见变化」对比。"""
    x0, y0, x1, y1 = LIST_AREA
    full = Image.open(io.BytesIO(robot.screenshot())).copy()
    return full.crop((REGION[0] + x0, REGION[1] + y0, REGION[0] + x1, REGION[1] + y1))


def main():
    pid = sandbox_pid()
    frames = [(h, r) for h, p, _, r in windows() if p == pid and r[2] - r[0] > 1200]
    # 窗口外框实测 (277,25,1647,1009)——REGION 是内缩 8px 的录制区（坑 49）
    WANT = (277, 25, 1647, 1009)
    if len(frames) != 1 or frames[0][1] != WANT:
        raise RuntimeError(f"sandbox viewport differs from baseline: {frames} != {WANT}")
    if not ensure_foreground(frames[0][0], click_xy=(700, 36)):
        raise RuntimeError("sandbox did not take focus")
    for xpath in (LOG_TAB, HISTORY_TAB):
        robot.require_component(xpath)
    # 20261027：关掉可能开着的 SQL 预览弹窗——它会盖住日志列表，
    # 让「捕获入列 + 双通道状态栏」这两个本页主角看不到（复核 MINOR 项）
    try:
        from robot import find_components, click_component
        PREVIEW = "//div[@accessiblename='SQL Visualizer - SQL 预览']"
        if find_components(PREVIEW):
            # 「取消」按钮在预览弹窗底部；robot 按文本点（坐标会随弹窗宽度漂）
            closed = False
            for xp in ("//div[@accessiblename='取消']",
                       "//div[@text='取消']"):
                if find_components(xp):
                    click_component(xp); time.sleep(1.5); closed = True; break
            if not closed:
                import pyautogui as _pg
                _pg.click(REGION[0] + 1212, REGION[1] + 798); time.sleep(1.5)
            print("[prep] 预览已关闭:", not find_components(PREVIEW))
        # 断言：确认已切到「SQL 日志」页签
        LOG_TAB_XP = ("//div[contains(@class,'TabLabel') and "
                      "contains(@accessiblename,'SQL 日志')]")
        if find_components(LOG_TAB_XP):
            click_component(LOG_TAB_XP); time.sleep(1.2)
        # 确保「捕捉」已开（工具窗重开后开关会重置；坑 36：悬停工具栏才现形的绿三角）
        import pyautogui as _pg
        _pg.moveTo(REGION[0] + 960, REGION[1] + 129, duration=0.4)
        time.sleep(0.9)
        cap = find_components("//div[contains(@accessiblename,'捕捉')]")
        if cap:
            click_component("//div[contains(@accessiblename,'捕捉')]")
            time.sleep(1.5)
            print("[prep] 已点捕捉开关")
    except Exception as e:
        print("(关预览跳过)", str(e)[:40])
    clear_id = _clear_xpath()
    feed("/user/list")
    robot.click_component(f"//div[@id='{clear_id}']")
    pyautogui.moveTo(1510, 500, duration=0.3)
    before = list_pixels()

    recorder = Recorder(REGION, str(OUT))
    recorder.start()
    time.sleep(1.2)
    feed("/user/list")
    time.sleep(1.6)
    feed("/user/nested?username=ab&minAge=20&maxAge=30")
    time.sleep(1.6)
    feed("/user/page?current=1&size=2")
    time.sleep(1.4)
    feed("/user/list")
    time.sleep(1.4)
    after = list_pixels()
    diff = ImageChops.difference(before, after)
    bbox = diff.getbbox()
    if bbox is None:
        recorder.stop()
        raise RuntimeError("captured SQL list did not visibly change")
    print("list changed, bbox:", bbox, flush=True)
    robot.click_component(HISTORY_TAB)
    time.sleep(0.8)
    robot.click_component(LOG_TAB)
    time.sleep(1.4)
    robot.screenshot(out=str(HERE / "shots" / "r11-sql-log-final.png"))
    print("frames", recorder.stop(), "output", OUT, flush=True)


if __name__ == "__main__":
    main()
