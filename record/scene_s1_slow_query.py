# -*- coding: utf-8 -*-
"""慢查询红色耗时、普通查询对照和类型下拉中的慢查询筛选。"""
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
OUT = HERE / "perf" / "s1_slow_query_native.mp4"
TYPE_ALL = '//div[contains(@accessiblename,"全部")]'
SLOW_ONLY = '//div[contains(@class,"JCheckBox") and contains(@accessiblename,"只看慢查询")]'
CLEAR = '//div[contains(@class,"ActionButton") and contains(@accessiblename,"清空")]'
BOTTOM = '//div[contains(@class,"ActionButton") and contains(@accessiblename,"滚动到底部")]'
LIST = '//div[contains(@class,"ViewportWidthSqlEntryList")]'


def get(path):
    with urllib.request.urlopen("http://127.0.0.1:8082" + path, timeout=20) as response:
        if response.status != 200:
            raise RuntimeError(f"mpj endpoint failed: {path} => {response.status}")
        response.read()


def list_pixels():
    component = robot.require_component(LIST)
    center = robot.component_screen_center(LIST, comp=component)
    width, height = int(component["width"]), int(component["height"])
    left, top = center[0] - width // 2, center[1] - height // 2
    return Image.open(io.BytesIO(robot.screenshot())).crop((left, top, left+width, top+height)).copy()


def main():
    pid = sandbox_pid()
    frames = [(h, r) for h, p, _, r in windows() if p == pid and r[2]-r[0]>1200]
    if len(frames) != 1 or frames[0][1] != (277, 25, 1647, 1009):
        raise RuntimeError(f"sandbox viewport mismatch: {frames}")
    if not ensure_foreground(frames[0][0]):
        raise RuntimeError("sandbox did not take focus")
    pyautogui.press("esc")
    if not robot.click_component('//div[contains(@class,"EditorTabLabel") and contains(@accessiblename,"UserServiceImpl.java")]'):
        raise RuntimeError("safe source editor tab unavailable")
    time.sleep(.5)
    if not any(p == pid and "UserServiceImpl.java" in title for _, p, title, _ in windows()):
        raise RuntimeError("unsafe editor background")
    for xpath in (CLEAR, BOTTOM, TYPE_ALL, LIST):
        robot.require_component(xpath)
    get("/mpj/device/entityList")
    if not robot.click_component(CLEAR):
        raise RuntimeError("clear action unavailable")
    time.sleep(.5)
    get("/mpj/device/entityList")
    get("/mpj/ext/chain")
    time.sleep(.8)
    robot.click_component(BOTTOM)
    pyautogui.moveTo(1510, 600, duration=.2)
    before = list_pixels()
    recorder = Recorder(REGION, str(OUT))
    filtered = False
    recorder.start()
    try:
        time.sleep(.8)
        get("/mpj/perf/slow")
        time.sleep(1.3)
        if ImageChops.difference(before, list_pixels()).getbbox() is None:
            raise RuntimeError("slow query did not visibly enter list")
        robot.screenshot(out=str(HERE / "shots" / "native-slow-red.png"))
        if not robot.click_component(TYPE_ALL):
            raise RuntimeError("type dropdown missing")
        robot.require_component(SLOW_ONLY)
        time.sleep(1)
        robot.screenshot(out=str(HERE / "shots" / "native-slow-menu.png"))
        if not robot.click_component(SLOW_ONLY):
            raise RuntimeError("slow-only checkbox missing")
        filtered = True
        pyautogui.press("esc")
        pyautogui.moveTo(1510,600,duration=.2)
        time.sleep(1.6)
        robot.screenshot(out=str(HERE / "shots" / "native-slow-final.png"))
    finally:
        print("frames", recorder.stop(), "output", OUT, flush=True)
        if filtered:
            if not robot.click_component(TYPE_ALL):
                raise RuntimeError("cannot reopen type dropdown for reset")
            if not robot.click_component(SLOW_ONLY):
                raise RuntimeError("cannot reset slow-only checkbox")
            pyautogui.press("esc")
            time.sleep(.5)
            robot.screenshot(out=str(HERE / "shots" / "native-slow-reset.png"))


if __name__ == "__main__":
    main()
