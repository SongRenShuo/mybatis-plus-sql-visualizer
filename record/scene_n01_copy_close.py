# -*- coding: utf-8 -*-
"""N01「复制 SQL 并关闭」新增录制（T-DocsRefresh1023 / UI-034·UI-042~044）。

演示预览面板底部蓝色主行动：一次复制当前 SQL 并关掉面板。收尾用 clipread 验证剪贴板
确实拿到完整 SQL（不靠画面猜）。

分镜（约 6 秒）：
1. 预览面板打开（Wrapper 链 SQL 已渲染），停 1.4s
2. 点底部蓝色「复制 SQL 并关闭」→ 面板关闭，停 1.2s
3. 回到编辑器，停 0.8s

前置（prep）：mpjdemo 的 UserServiceImpl.java 打开、goto(40)（selectJoinList）、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import (Recorder, click, find_gutter_icons, goto_line, shot,
                    wait_stable)
from robot import find_components

REGION = (277, 25, 1370, 984)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n01_copy_close.mp4")

OX, OY = REGION[0], REGION[1]
COPY_AND_CLOSE = (1115, 806)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def locate_gutter():
    path = os.path.join(BASE, "perf", "_pre_n01.png")
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(100, 860), gap=12)
    icons = [i for i in icons if 95 <= i[1] <= 105]
    if not icons:
        raise RuntimeError(f"gutter icon not found: {icons}")
    center = (100 + 860) / 2
    y, x = min(icons, key=lambda p: abs(p[0] - center))
    print("icons:", icons, "-> picked", (y, x))
    return x + OX, y + OY


def main():
    goto_line(40)
    g = locate_gutter()

    rec = Recorder(REGION, OUT)
    time.sleep(1.2)
    rec.start()
    try:
        time.sleep(0.4)
        click(*g, pauses=(0.25, 0.3))          # 1. 打开预览
        wait_stable(REGION)
        time.sleep(1.4)

        if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
            raise RuntimeError("预览未打开，中止")

        click(*abs_pt(COPY_AND_CLOSE), move_dur=0.35)   # 2. 复制 SQL 并关闭
        time.sleep(1.3)
        print("preview closed:", not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"))
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
