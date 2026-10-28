# -*- coding: utf-8 -*-
"""O-148 尾随语义真机验证母片；授权和画面验收前不覆盖文档站动图。

前置：沙盒由标准 runIde 命令启动并已开捕捉，8081 demo 从 IDE Run 启动。
通过 robot-server 找「清空」「滚动到底部」按钮，不写死两者坐标。
"""
import ctypes
import sys
import threading
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "lib"))
import pyautogui
import robot
from gifkit import Recorder
from locate import sandbox_pid, windows, window_rect, ensure_foreground

OUT = str(HERE / "perf" / "o148_tail_follow_probe.mp4")
CLEAR = '//div[contains(@accessiblename,"清空")]'
SCROLL_BOTTOM = '//div[contains(@accessiblename,"滚动到底部")]'


def feed(path):
    with urllib.request.urlopen("http://127.0.0.1:8081" + path, timeout=25) as response:
        response.read()


def wheel_up(hwnd, x, y, notches=6):
    message = (y << 16) | (x & 0xFFFF)
    for _ in range(notches):
        ctypes.windll.user32.PostMessageW(ctypes.c_void_p(hwnd), 0x020A,
                                            (120 & 0xFFFF) << 16, message)
        time.sleep(0.04)


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    pid = sandbox_pid()
    frames = [(h, r) for h, p, t, r in windows() if p == pid and r[2]-r[0] > 1200]
    if len(frames) != 1:
        raise RuntimeError("沙盒 IDE 主窗口不唯一：%s" % frames)
    hwnd, rect = frames[0]
    if rect != (277, 25, 1647, 1009):
        raise RuntimeError("窗口偏离录制构图：%s" % (rect,))
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 400)):
        raise RuntimeError("未能将沙盒置前")
    if robot.find_one(CLEAR) is None or robot.find_one(SCROLL_BOTTOM) is None:
        raise RuntimeError("找不到日志工具栏的清空或滚底动作")

    robot.click_component(CLEAR)
    for path in ("/user/list", "/user/nested?username=ab&minAge=20&maxAge=30"):
        feed(path)
        time.sleep(0.4)
    robot.click_component(SCROLL_BOTTOM)
    list_point = (1480, 410)
    pyautogui.click(*list_point)
    time.sleep(0.4)
    pyautogui.press("escape")
    time.sleep(0.8)
    recorder = Recorder((277, 25, 1370, 984), OUT)
    recorder.start()
    errors = []
    def storm():
        try:
            # JdbcTemplate 的 /storm 在当前沙盒未产列表条目；MyBatis /n1 已实测入列。
            for _ in range(6):
                feed("/logFeed/n1?ids=1,2,3,4,5")
                time.sleep(0.5)
        except Exception as exc:
            errors.append(str(exc))
    producer = threading.Thread(target=storm, daemon=True)
    producer.start()
    time.sleep(2.8)
    wheel_up(hwnd, *list_point)
    time.sleep(2.0)
    robot.click_component(SCROLL_BOTTOM)
    time.sleep(2.5)
    producer.join(timeout=18)
    time.sleep(1.0)
    count = recorder.stop()
    print("frames", count, "storm_errors", errors)
    if errors:
        raise RuntimeError("演示喂料失败：%s" % errors)


if __name__ == "__main__":
    main()
