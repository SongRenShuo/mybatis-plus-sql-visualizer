# -*- coding: utf-8 -*-
"""N08 方言不一致：提示但不拦执行（T-DocsGifRecord1025，2026-10-25 用户定案重写）。

**分镜与旧版完全不同**。旧版按「阻断态 + 切换数据源/方言设置两出口」拍，但用户
2026-10-25 定案「方言不能影响最终的执行——哪怕方言是错的，用户手动选了数据源，
可以正常提示方言不一致，但还是要能成功执行」，插件随之改成放行。所以本张改为：

1. 预览态：头部 `方言: PostgreSQL（手动指定）` + `数据源（本模块上次使用）: mpj_demo@localhost`
   —— 渲染方言与目标库方言**不一致**（这就是要演示的冲突）
2. 点「执行 SQL」→ 弹出原生数据源选择器（PG 三条源）→ 选中 `dbx_test`
3. 执行真的送到数据库 → PostgreSQL 自己报 `[42P01] ERROR: relation "user" does not exist`
   （提示方言/对象不符，而不是插件拦死）
4. 收尾回预览态

**前置（硬）**：
- 插件方言 = PostgreSQL（`mp-sql-visualizer.xml` 的 `dialectAuto=false` + `dialect=PostgreSQL`）
- 目标源为 MySQL（mpj_demo@localhost），构造冲突
- PG 源 `dbx_test@114.66.55.245` 已填凭据并 introspect（树里显示 `1 of 3`）

**为什么这张能录成**：插件三处修复（`8619a3af` + `e7a27700`）——
闸门对已知冲突放行、报错文案报渲染方言、runner 枚举为空时按目标源方言重映射 exec 文件。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

import pyautogui
from gifkit import Recorder, click
from nav import force_en_us
from robot import click_component

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n08_dialect_gate.mp4")

OX, OY = REGION[0], REGION[1]

# 「执行 SQL」按钮（预览底部，region 相对）
EXECUTE = (623, 779)
# 数据源选择器里的 dbx_test 行（弹出位置固定，region 相对）
POPUP_DBX = (825, 769)
# 「返回预览」按钮（执行视图右上，region 相对）
BACK_TO_PREVIEW = (1195, 284)
# 预览底部「取消」（收尾关面板，避免复制通知卡）
CANCEL = (1212, 798)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def main():
    force_en_us()
    time.sleep(0.5)

    # 前置硬检查：预览必须已打开。缺这一步时整段会录成「编辑器 + Services」的空片
    # （2026-10-25 踩过：录制跑了 205 帧全是收尾态，因为开录前预览就没开）。
    from robot import find_components
    if not find_components("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
        raise RuntimeError("前置未满足：预览未打开。请先手工开好预览再跑本脚本")

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(1.2)                                    # 1. 预览态定格（PG 方言 vs MySQL 源）

        # 「执行 SQL」用 robot 按组件名点：坐标法在预览面板高度变化时会落空
        click_component("//div[@text='执行 SQL']")
        time.sleep(2.8)                                    # 2. 执行 → 弹源选择
        click(*abs_pt(POPUP_DBX), move_dur=0.3, pauses=(0.2, 1.0))  # 3. 选中 dbx_test
        pyautogui.press("down")
        time.sleep(0.3)
        pyautogui.press("return")                          #    键盘确认（原生选择器双击不生效）
        time.sleep(7.0)                                    # 4. 执行 + 结果回填定格

        # 5. 回预览态（首尾同态）。
        #    只点「返回预览」，**不点取消**——取消会关掉整个面板，尾帧变成编辑器态，
        #    与首帧的预览态差 12%（实测）。执行留痕（Services 里多一条 dbx_test 控制台）
        #    属于真实反馈，保留。
        click_component("//div[@text='返回预览']")
        time.sleep(2.0)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
