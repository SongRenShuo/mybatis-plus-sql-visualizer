# -*- coding: utf-8 -*-
"""N05「导出回放脚本」新增录制（T-DocsRefresh1023 / R36）。

分镜（约 8 秒）：
1. 预览面板打开（MPJ 三表 JOIN），停 1.2s
2. 「更多」→「导出回放脚本」→ 导出对话框（事务包裹勾选项），停 1.6s
3. 勾选「事务包裹（BEGIN … COMMIT）」→ 说明行变化，停 1.2s
4. 点「导出」→ 保存框（已填默认名），停 1.4s
5. 取消保存框回预览，停 0.8s

前置（prep）：mpjdemo UserServiceImpl.java 打开、goto(40)、无弹窗。

**2026-10-25 状态：仍未录成。** 本轮把窗口临时放宽到 `WIN=(150,25,1770,984)` /
`REGION=(158,26,1754,974)` 让保存框（实测 `(758,327)-(1718,867)`）能完整入镜，但放宽后
**所有按钮坐标全变**（「更多」按钮、菜单项、对话框位置），脚本里的 `MORE` /
`ITEM_REPLAY` / `WRAP_CHECK` / `OK_BTN` 需要按新构图整套重标——这一步没做完。

原始阻塞描述（保留）：
坐标值（MORE / ITEM_REPLAY / WRAP_CHECK / OK_BTN）已用实拍核对过导出对话框，
可直接用；缺的是最后一步——导出的保存框是 IDE 自绘 FileChooser，实测范围
`(758,327)-(1718,867)`，**右边界超出录制区 79px**，`MoveWindow` 移走后再置前
会被主窗盖住，「更多」菜单又是 hover 才展开。要补齐需先解决这三个约束。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui

from gifkit import (Recorder, click, find_gutter_icons, goto_line, shot,
                    wait_stable)
from robot import find_components

# 本张**临时放宽构图**：导出后的保存框实测 (758,327)-(1718,867)，右边界比标准录制区
# (285,26,1354,974) 的右界 1639 超出 79px，保存框右半截会被裁掉。用户 2026-10-25 授权
# 「窗口高度允许这两次临时放高」——这里放宽的是**宽度**（同一批临时放宽）。录完必须还原。
WIN = (150, 25, 1770, 984)
REGION = (158, 26, 1754, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n05_replay.mp4")

OX, OY = REGION[0], REGION[1]
MORE = (1238, 206)
ITEM_REPLAY = (1210, 357)
WRAP_CHECK = (458, 435)      # 事务包裹复选框（导出对话框内，2026-10-25 实测）
OK_BTN = (836, 564)          # 对话框 OK（2026-10-25 实测）


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def locate_gutter():
    path = os.path.join(BASE, "perf", "_pre_n05.png")
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(100, 860), gap=12)
    icons = [i for i in icons if 88 <= i[1] <= 130]
    if not icons:
        raise RuntimeError(f"gutter icon not found: {icons}")
    center = (100 + 860) / 2
    y, x = min(icons, key=lambda p: abs(p[0] - center))
    return x + OX, y + OY


def main():
    # prep：预览必须已打开（由调用方先开好）。脚本内不再自己开——
    # gutter 图标位置随 goto 漂移，实测会导致预览未打开而整段白录。
    if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
        raise RuntimeError("前置未满足：预览未打开。请先手工开好预览再跑本脚本")

    # 本张临时放宽窗口（见文件头 WIN 注释），录完必须还原成 (277,25,1370,984)
    import ctypes as _ct
    _u32 = _ct.WinDLL("user32", use_last_error=True)
    _u32.SetWindowPos.argtypes = [_ct.c_void_p, _ct.c_void_p,
                                  _ct.c_int, _ct.c_int, _ct.c_int, _ct.c_int, _ct.c_uint]
    _hwnd = _u32.GetForegroundWindow()
    _u32.SetWindowPos(_ct.c_void_p(_hwnd), _ct.c_void_p(0), WIN[0], WIN[1], WIN[2], WIN[3], 0x0004)
    time.sleep(1.2)

    rec = Recorder(REGION, OUT)
    time.sleep(1.2)
    rec.start()
    try:
        time.sleep(0.8)
        # 清掉可能残留的菜单：点预览标题栏空白处（Escape 会连预览一起关掉）
        click(*abs_pt((600, 161)), move_dur=0.2, pauses=(0.1, 0.6))

        click(*abs_pt(MORE), move_dur=0.3, pauses=(0.2, 1.2))           # 更多
        click(*abs_pt(ITEM_REPLAY), move_dur=0.25, pauses=(0.15, 1.8))  # 导出回放脚本
        if not has("//div[@accessiblename='导出回放脚本']"):
            raise RuntimeError("导出回放脚本对话框未打开，中止")

        click(*abs_pt(WRAP_CHECK), move_dur=0.3, pauses=(0.15, 1.2))    # 取消/勾选事务包裹
        click(*abs_pt(WRAP_CHECK), move_dur=0.3, pauses=(0.15, 1.4))    # 再勾回（演示说明行联动）

        click(*abs_pt(OK_BTN), move_dur=0.3, pauses=(0.15, 2.0))        # OK → 保存框
        pyautogui.press("escape")                                        # 取消保存框
        time.sleep(1.0)
        click(*abs_pt((1185, 806)), move_dur=0.3)                        # 关闭预览
        time.sleep(0.9)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
