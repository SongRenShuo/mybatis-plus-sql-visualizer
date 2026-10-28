# -*- coding: utf-8 -*-
"""增强捕获：真实绑定参数、耗时、源码与只读 SQL 语法错误。"""
import io
import sys
import time
import urllib.error
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
OUT = HERE / "perf" / "a4_enhanced_native.mp4"
CLEAR = '//div[contains(@class,"ActionButton") and contains(@accessiblename,"清空")]'
LIST = '//div[contains(@class,"ViewportWidthSqlEntryList")]'
LOG_TAB = '//div[contains(@class,"TabLabel") and contains(@accessiblename,"SQL 日志")]'


def get(path, expect_error=False):
    try:
        with urllib.request.urlopen("http://127.0.0.1:8081" + path, timeout=12) as response:
            if response.status != 200 or expect_error:
                raise RuntimeError(f"unexpected demo result: {path} => {response.status}")
            response.read()
    except urllib.error.HTTPError as exc:
        if not expect_error or exc.code != 500:
            raise


def list_pixels():
    component = robot.require_component(LIST)
    center = robot.component_screen_center(LIST, comp=component)
    width, height = int(component["width"]), int(component["height"])
    area = (center[0] - width // 2, center[1] - height // 2,
            center[0] + width // 2, center[1] - height // 2 + min(height, 650))
    return Image.open(io.BytesIO(robot.screenshot())).crop(area).copy()


def main():
    pid = sandbox_pid()
    frames = [(h, r) for h, p, _, r in windows() if p == pid and r[2] - r[0] > 1200]
    if len(frames) != 1 or frames[0][1] != (REGION[0], REGION[1],
                                         REGION[0] + REGION[2], REGION[1] + REGION[3]):
        raise RuntimeError(f"sandbox viewport mismatch: {frames}")
    if not ensure_foreground(frames[0][0], click_xy=(700, 36)):
        raise RuntimeError("sandbox did not take focus")
    services_panel = '//div[contains(@class,"InternalDecoratorImpl") and contains(@accessiblename,"Services")]'
    services_stripe = '//div[contains(@class,"SquareStripeButton") and contains(@accessiblename,"Services")]'
    if robot.find_one(services_panel):
        robot.click_component(services_stripe)
        time.sleep(0.5)
        if robot.find_one(services_panel):
            raise RuntimeError("Services panel still covers recording area")
    for xpath in (CLEAR, LIST, LOG_TAB):
        robot.require_component(xpath)
    get("/user/list")
    robot.click_component(CLEAR)
    pyautogui.moveTo(1510, 500, duration=0.3)
    before = list_pixels()

    recorder = Recorder(REGION, str(OUT))
    recorder.start()
    time.sleep(0.7)
    get("/user/nested?username=ab&minAge=20&maxAge=30")
    time.sleep(1.0)
    # id 未提供时 Wrapper 生成 id = NULL，零匹配，避免改变现有演示数据。
    request = urllib.request.Request("http://127.0.0.1:8081/user/updateByWrapper?status=1", method="PUT")
    with urllib.request.urlopen(request, timeout=12) as response:
        if response.read().strip() != b"false":
            recorder.stop()
            raise RuntimeError("Expected zero-row update")
    time.sleep(1.0)
    get("/logFeed/sqlError", expect_error=True)
    time.sleep(2.0)
    if ImageChops.difference(before, list_pixels()).getbbox() is None:
        recorder.stop()
        raise RuntimeError("enhanced SQL list did not visibly change")
    robot.screenshot(out=str(HERE / "shots" / "native-enhanced-final.png"))
    print("frames", recorder.stop(), "output", OUT, flush=True)


if __name__ == "__main__":
    main()
