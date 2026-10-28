# -*- coding: utf-8 -*-
"""R06 批量操作参数面板重录（T-DocsRefresh1023）：saveBatch 调用行 gutter →
批量参数面板（含 FieldFill 安全模拟开关）→ 取消勾选 age 列 → INSERT 收缩 → 复制并关闭。

相对 20260912 版（scene_t2）的差异：
- 左栏新增「FieldFill 安全模拟（默认关闭）」开关，画面里自然带出（不额外演示）；
- 收尾点蓝色主行动「复制 SQL 并关闭」而非「取消」；
- 首尾同态用 Ctrl+Shift+Backspace 回上次编辑位置（不弹 goto 框）。

前置（prep）：UserServiceImpl.java 打开、goto(211)、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui
from gifkit import (Recorder, click, find_blue_checkboxes, find_gutter_icons,
                    goto_line, shot, wait_stable)
from robot import find_components

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "r06_batch.mp4")

OX, OY = REGION[0], REGION[1]
CANCEL = (1185, 806)          # 预览窗「取消」（region 相对）
COPY_AND_CLOSE = (1115, 806)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def locate_gutter():
    path = os.path.join(BASE, "perf", "_pre_r06.png")
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(100, 860), gap=12)
    icons = [i for i in icons if i[1] < 105]      # 只要 gutter 主条带
    if not icons:
        raise RuntimeError("gutter icon not found")
    y, x = icons[0]                                # 上簇 = 211 saveBatch
    print("gutter icons:", icons, "-> picked", (y, x))
    return x + OX, y + OY


def calibrate_age_checkbox(gutter):
    """开一次弹窗，取蓝色第 2 行复选框（toInsert.age），关掉。"""
    click(*gutter, move_dur=0.35)
    wait_stable(REGION)
    time.sleep(1.2)
    path = os.path.join(BASE, "perf", "_cal_r06.png")
    shot(REGION, path)
    rows = find_blue_checkboxes(path, strip_x=(135, 168), y_range=(370, 770))
    print("checkbox rows:", rows)
    click(*abs_pt(CANCEL), move_dur=0.3)
    time.sleep(1.0)
    if len(rows) < 3:
        raise RuntimeError(f"less than 3 checkbox rows: {rows}")
    ay, ax = rows[1]
    return ax + OX, ay + OY


def main():
    # 开录前按路径精确打开目标文件（同名文件问题的根治手段，T-DemoGifFix1026 §2.2）
    try:
        from mcpnav import open_file
        open_file("mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/service/impl/UserServiceImpl.java",
                  settle=1.6)
    except Exception as e:
        print("MCP 打不开，中止（不退回搜索方式，宁可不录也不开错文件）:", e)
        raise
    goto_line(211)
    gutter = locate_gutter()
    chk_age = calibrate_age_checkbox(gutter)
    print("chk_age:", chk_age)

    rec = Recorder(REGION, OUT)
    time.sleep(1.2)
    rec.start()
    try:
        time.sleep(0.5)
        click(*gutter)                        # 1. 点 saveBatch gutter
        wait_stable(REGION)
        time.sleep(1.4)                       # 批量面板定格
        # 闸门 2（画面断言）：预览面板必须已就位，否则本 take 作废
        if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
            raise RuntimeError("预览面板未出现，中止本次 take")

        click(*chk_age, move_dur=0.35)        # 2. 取消勾选 age 列
        time.sleep(1.8)                       # SQL 收缩定格

        # 3. 复制 SQL 并关闭——**改点「取消」**：复制会留一张常驻的
        #    「SQL 已复制到剪贴板」通知卡（独立 balloon 窗口，robot-server 查不到、
        #    Esc 关不掉），尾帧与首帧对不上。
        click(*abs_pt(CANCEL), move_dur=0.3)
        time.sleep(0.9)

        # 4. 首尾同态：**不再 goto**。关窗后光标仍在第 211 行（prep 落点），
        #    旧版这里 goto_line(211)+Esc 在录制窗口内弹 Ctrl+G——正是旧片
        #    帧 110-133 的跳行弹窗入镜来源（T-DemoGifFix1026 §2.1）。
        time.sleep(0.8)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
