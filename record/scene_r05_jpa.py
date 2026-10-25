# -*- coding: utf-8 -*-
"""R05 JPA 派生查询预览重录（T-DocsRefresh1023）：派生方法 gutter → 预览 → 复制并关闭。

相对 20260912 版（scene_t1）的差异：
- 收尾点蓝色主行动「复制 SQL 并关闭」而非「取消」；
- 只保留一个主场景（派生方法），旧片双弹窗（派生 + @Query）在 11 秒里信息过载；
- gutter 只要主条带（x<105），避开编辑器折叠箭头。

前置（prep）：jpa-hibernate-demo 的 UserRepository.java 打开、goto(53)、无弹窗。
"""
import os
import sys
import time

import pyautogui

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import (Recorder, click, find_gutter_icons, goto_line, shot,
                    wait_stable)

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "r05_jpa.mp4")

OX, OY = REGION[0], REGION[1]
COPY_AND_CLOSE = (1115, 806)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def locate_gutter():
    path = os.path.join(BASE, "perf", "_pre_r05.png")
    shot(REGION, path)
    # 条带必须覆盖图标本体：目录树收起后插件图标落在 region 相对 x≈70~82
    # （2026-10-25 实测；旧的 95..130 只框到灯泡/折叠箭头，检测为空）
    icons = find_gutter_icons(path, strip_x=(85, 115), y_range=(100, 860), gap=12)
    if not icons:
        raise RuntimeError("gutter icon not found")
    # 必须选**离光标行最近**的图标，不能选「离画面中心最近」——后者会点到别的语句
    # （2026-10-25 实测：中心法选中第 58 行 findByAgeGreaterThan，画面成了 WHERE age > ?）。
    # 光标行由 goto_line(53) 决定。当前布局（目录树收起、编辑器占满）实测第 53 行
    # 的 gutter 图标落在 region 相对 y≈386。
    caret_y = 396
    y, x = min(icons, key=lambda p: abs(p[0] - caret_y))
    print("icons:", icons, f"caret_y≈{caret_y} -> picked", (y, x))
    return x + OX, y + OY


def main():
    goto_line(53)                              # findByUsernameOrEmail
    gutter = locate_gutter()

    rec = Recorder(REGION, OUT)
    time.sleep(1.2)
    rec.start()
    try:
        time.sleep(0.5)
        click(*gutter, pauses=(0.25, 0.3))     # 1. 派生方法 gutter
        wait_stable(REGION)
        # JPA 派生查询的预览形态：完整预览弹窗（左侧参数框 #username/#email，
        # 右侧 SQL `WHERE (username = ? OR email = ?)`）。停 2.4s 让两边都看清，
        # 再点「取消」收尾——不用「复制 SQL 并关闭」，那会留一张常驻通知卡
        # （独立 balloon 窗口，robot-server 查不到、Esc 关不掉），尾帧与首帧对不上。
        time.sleep(2.4)

        click(*abs_pt((1212, 798)), move_dur=0.3)  # 2. 取消（region 相对）
        time.sleep(0.9)

        # 3. 不跳转：关窗后光标仍在 53 行，与首帧同态（跳转会引入新的滚动/弹窗差异）
        time.sleep(1.2)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
