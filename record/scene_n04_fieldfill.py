# -*- coding: utf-8 -*-
"""N04「FieldFill 安全模拟」新增录制（T-DocsRefresh1023 / R92 + UI-031）。

用实体侧「生成 CRUD SQL」打开 INSERT 预览：左栏出现 `FieldFill 安全模拟（默认关闭）`
开关；勾选后带 `[fill=INSERT]` / `[fill=INSERT_UPDATE]` 角标的字段下方出现「模拟值」行，
填值后 SQL 取模拟值、普通参数框置灰。

分镜（约 14 秒）：
1. 候选浮层选 INSERT → 预览打开（左栏见 FieldFill 开关，默认未勾），停 1.6s
2. 勾选「FieldFill 安全模拟」→ 模拟值行出现，停 1.4s
3. 在 create_time 的模拟值行填 `2026-10-21 12:00:00` → SQL 取该值、普通框置灰，停 1.8s
4. 取消勾选 → 模拟值行消失、恢复手填态，停 1.2s
5. 关预览，停 0.9s

前置（prep）：User.java 打开、光标在类名行、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui
from gifkit import Recorder, click, paste
from robot import find_components

REGION = (277, 25, 1370, 984)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n04_fieldfill.mp4")

OX, OY = REGION[0], REGION[1]
CLASS_LINE = (300, 383)
POPUP_INSERT = (560, 496)
FILL_CHECK = (140, 337)        # 「FieldFill 安全模拟」复选框（实测）
# 左栏需要滚到底才能看到 fill 字段（create_time / update_time）——滚轮与 PageDown
# 都无效，只有拖滚动条（录制指南第 24 条同源现象）。
SCROLLBAR = (450, 400)
SCROLL_TO = (450, 620)
SIM_CREATE = (225, 572)        # create_time 的「模拟值」输入框（滚动后实测）
SIM_UPDATE = (225, 651)        # update_time 的「模拟值」输入框
CLOSE = (1185, 806)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def main():
    rec = Recorder(REGION, OUT)
    time.sleep(1.2)
    rec.start()
    try:
        time.sleep(0.4)
        # 1. 右键 → Generate CRUD SQL → INSERT
        pyautogui.click(*abs_pt(CLASS_LINE), button="right")
        time.sleep(1.0)
        from robot import click_component
        click_component("//div[@text='Generate CRUD SQL']")
        time.sleep(1.4)
        click(*abs_pt(POPUP_INSERT), move_dur=0.28, pauses=(0.2, 1.8))

        if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
            raise RuntimeError("预览未打开，中止")

        # 2. 勾选 FieldFill 安全模拟
        click(*abs_pt(FILL_CHECK), move_dur=0.3, pauses=(0.15, 1.2))

        # 3. 拖左栏滚动条到 fill 字段可见处
        pyautogui.moveTo(*abs_pt(SCROLLBAR), duration=0.3)
        pyautogui.mouseDown(); time.sleep(0.25)
        pyautogui.moveTo(*abs_pt(SCROLL_TO), duration=0.7)
        pyautogui.mouseUp(); time.sleep(1.2)

        # 4. 填 create_time 模拟值 → SQL 取该值、普通框置灰
        click(*abs_pt(SIM_CREATE), move_dur=0.25, pauses=(0.15, 0.4))
        paste("2026-10-21 12:00:00")
        time.sleep(1.8)

        # 5. 填 update_time 模拟值
        click(*abs_pt(SIM_UPDATE), move_dur=0.25, pauses=(0.15, 0.4))
        paste("2026-10-21 12:00:00")
        time.sleep(1.4)

        # 6. 取消勾选 → 模拟值行消失
        click(*abs_pt(FILL_CHECK), move_dur=0.3, pauses=(0.15, 1.2))

        # 7. 关预览
        click(*abs_pt(CLOSE), move_dur=0.3)
        time.sleep(0.9)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
