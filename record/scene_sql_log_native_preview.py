# -*- coding: utf-8 -*-
"""新版 SQL 日志列表综合预览：工具栏、语义色、宽度省略与同结构计数。"""
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

REGION = (277, 25, 1370, 984)
OUT = HERE / "perf" / "sql_log_list_native_preview.mp4"
LOG_TAB = '//div[contains(@class,"TabLabel") and contains(@accessiblename,"SQL 日志")]'
HISTORY_TAB = '//div[contains(@class,"TabLabel") and contains(@accessiblename,"SQL 历史")]'
CLEAR = '//div[contains(@class,"ActionButton") and contains(@accessiblename,"清空")]'
SCROLL_BOTTOM = '//div[contains(@class,"ActionButton") and contains(@accessiblename,"滚动到底部")]'
LIST = '//div[contains(@class,"ViewportWidthSqlEntryList")]'


def feed(path):
    with urllib.request.urlopen("http://127.0.0.1:8082" + path, timeout=12) as response:
        if response.status != 200:
            raise RuntimeError(f"demo endpoint failed: {path} => {response.status}")
        response.read()


def list_pixels():
    component = robot.require_component(LIST)
    center = robot.component_screen_center(LIST, comp=component)
    width, height = int(component["width"]), int(component["height"])
    area = (center[0] - width // 2, center[1] - height // 2,
            center[0] + width // 2, center[1] - height // 2 + min(height, 500))
    return Image.open(io.BytesIO(robot.screenshot())).crop(area).copy()


def main():
    pid = sandbox_pid()
    frames = [(h, r) for h, p, _, r in windows() if p == pid and r[2] - r[0] > 1200]
    if len(frames) != 1 or frames[0][1] != (REGION[0], REGION[1],
                                         REGION[0] + REGION[2], REGION[1] + REGION[3]):
        raise RuntimeError(f"sandbox viewport differs from recording baseline: {frames}")
    if not ensure_foreground(frames[0][0], click_xy=(700, 36)):
        raise RuntimeError("sandbox did not take focus")
    for xpath in (LOG_TAB, HISTORY_TAB, CLEAR, SCROLL_BOTTOM, LIST):
        robot.require_component(xpath)
    robot.require_component('//div[contains(@accessiblename,"SQL 去重")]')
    feed("/mpj/device/entityList")
    robot.click_component(CLEAR)
    feed("/mpj/device/entityList")
    time.sleep(0.7)
    pyautogui.moveTo(1510, 500, duration=0.3)
    before = list_pixels()

    recorder = Recorder(REGION, str(OUT))
    recorder.start()
    time.sleep(0.7)
    feed("/mpj/perf/n1")
    time.sleep(1.0)
    feed("/mpj/device/entityList")
    time.sleep(1.0)
    feed("/mpj/perf/n1")
    time.sleep(1.4)
    after = list_pixels()
    if ImageChops.difference(before, after).getbbox() is None:
        recorder.stop()
        raise RuntimeError("captured SQL list did not visibly change")
    robot.screenshot(out=str(HERE / "shots" / "native-preview-final.png"))
    print("frames", recorder.stop(), "output", OUT, flush=True)


if __name__ == "__main__":
    main()
