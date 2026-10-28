# -*- coding: utf-8 -*-
"""R02 Wrapper → SQL 预览重录（T-DocsRefresh1023）：LambdaQueryWrapper 链 gutter →
预览 → 取消布尔条件 → SQL 重渲染 → 复制并关闭。**导出宽 1440**（首页共用）。

相对 20260912 版（scene_t4）的差异：
- 收尾点蓝色主行动「复制 SQL 并关闭」；
- 不跳转收尾（关窗后光标仍在原行，与首帧同态）；
- gutter 条带按 x 范围过滤，避开折叠箭头。

前置（prep）：UserServiceImpl.java 打开、goto(36)（listByLambdaQuery）、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import (Recorder, click, find_gutter_icons, goto_line, shot,
                    wait_stable)

REGION = (277, 25, 1370, 984)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "r02_wrapper.mp4")

OX, OY = REGION[0], REGION[1]
CHK_STATUS = (140, 364)       # 动态条件 status 复选框（region 相对，实测）
COPY_AND_CLOSE = (1115, 806)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def locate_gutter():
    path = os.path.join(BASE, "perf", "_pre_r02.png")
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(100, 860), gap=12)
    icons = [i for i in icons if 95 <= i[1] <= 105]   # 只要 gutter 主条带（x≈95）
    if not icons:
        raise RuntimeError("gutter icon not found")
    center = (100 + 860) / 2
    y, x = min(icons, key=lambda p: abs(p[0] - center))
    print("icons:", icons, "-> picked", (y, x))
    return x + OX, y + OY


def prep():
    """就位：确认无残留预览窗（首帧必须是编辑器态，否则接缝必然高）。"""
    from robot import find_components
    if find_components("//div[@accessiblename='取消']"):
        import pyautogui
        pyautogui.click(OX + 1185, OY + 186)   # 弹窗右上角关闭
        time.sleep(1.2)
    goto_line(36)
    time.sleep(0.8)


def main():
    prep()
    gutter = locate_gutter()

    rec = Recorder(REGION, OUT)
    time.sleep(1.2)
    rec.start()
    try:
        time.sleep(0.5)
        click(*gutter)                        # 1. gutter 点开预览
        wait_stable(REGION)
        time.sleep(1.4)                       # 弹窗定格（SQL 全文开窗即可见）

        click(*abs_pt(CHK_STATUS), move_dur=0.3, pauses=(0.2, 1.8))  # 2. 取消 status 条件

        click(*abs_pt(COPY_AND_CLOSE), move_dur=0.3)                 # 3. 复制 SQL 并关闭
        time.sleep(1.2)                       # 关窗后光标仍在原行（首尾同态）
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
