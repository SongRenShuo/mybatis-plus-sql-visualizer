# -*- coding: utf-8 -*-
"""N05「导出回放脚本」重录（T-DocsGifRecord1025，2026-10-25 二次重写）。

分镜（约 13 秒）：
1. 预览面板打开（MPJ 三表 JOIN），停 1.2s
2. 「更多」→ 键盘导航到「导出回放脚本」→ 导出对话框（事务包裹勾选项），停 1.2s
3. 取消 / 重勾「事务包裹」→ 说明行联动，停
4. 点 OK → 原生保存框（文件名已填 replay.sql 或提示「已导出」）
5. 保存 → 编辑器打开导出的 .sql，展示注释头（生成时间 / 方言 / 语句条数 / 事务包裹说明），停 2.5s

**2026-10-25 二次重录要点**（上一版成片污染，本轮全部修掉）：
- `replay.sql` 必须在沙盒**项目设置里配过 PostgreSQL 方言**，否则每次打开都弹
  「SQL dialect is not configured.」橙色横幅。配置路径：横幅 → `Change dialect to...`
  → 选 PostgreSQL → OK（写进 demo 工程 `.idea/sqlDataSources.xml` 同级配置，一次生效）。
- **保存框不要补 Enter**：点「保存(S)」已经生效，补的 Return 会落到保存后新激活的
  Chrome 上，把浏览器抢到前台（上一版就是这么污染的）。只点按钮。
- 「更多」菜单**必须键盘导航**（`End` 后 `Up×3`）：悬停态下鼠标点菜单项会先关菜单、
  点击穿透到编辑器。
- 打开 .sql 后用 robot 点「取消」关预览浮窗——它是 JDialog，不关就一直压着 .sql。
- 坐标全部按标准构图 REGION=(285,26,1354,974) 重测（窗口未放宽）。

前置（prep）：mpjdemo UserServiceImpl.java 打开、第 41 行、预览已开、replay.sql 未打开、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

import pyautogui

from gifkit import Recorder, click, shot
from nav import force_en_us
from robot import click_component, find_components

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n05_replay.mp4")

OX, OY = REGION[0], REGION[1]

# 预览头部「更多」（region 相对）
MORE = (1189, 205)
# 导出对话框「事务包裹」复选框（region 相对）
WRAP_CHECK = (464, 460)
# 导出对话框 OK（region 相对）
OK_BTN = (790, 559)
# 导出产物路径（MCP open_file_in_editor 相对 IDE 工程根，工程根就是 demo 仓）
EXPORTED_SQL = "replay.sql"


def focus_ide():
    """把沙盒 IDE 拉回前台。

    浏览器类窗口会在保存框关闭的空档抢前台（Chrome/magpie 仪表盘都犯过），
    保存后必须显式夺回，否则录制区全是浏览器画面。
    先按放 Alt 再 SetForegroundWindow，绕开 Windows 前台锁定。
    """
    import ctypes
    import win32gui

    hwnd = [0]

    def cb(h, _):
        t = win32gui.GetWindowText(h)
        if win32gui.IsWindowVisible(h) and "mybatis-plus-sql-visualizer-demo – " in t:
            hwnd[0] = h
        return True

    win32gui.EnumWindows(cb, None)
    if not hwnd[0]:
        raise RuntimeError("找不到沙盒 IDE 窗口")
    u32 = ctypes.windll.user32
    u32.keybd_event(0x12, 0, 0, 0)
    u32.keybd_event(0x12, 0, 2, 0)
    time.sleep(0.15)
    win32gui.SetForegroundWindow(hwnd[0])
    time.sleep(0.8)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def main():
    force_en_us()
    focus_ide()
    time.sleep(0.4)
    if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
        raise RuntimeError("前置未满足：预览未打开。请先手工开好预览再跑本脚本")

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(1.2)                                    # 1. 预览定格

        # 2. 「更多」→ 键盘导航到「导出回放脚本」
        click(*abs_pt(MORE), move_dur=0.3, pauses=(0.2, 1.5))
        pyautogui.press("end")
        time.sleep(0.4)
        for _ in range(3):
            pyautogui.press("up")
            time.sleep(0.25)
        pyautogui.press("return")
        time.sleep(2.0)
        if not has("//div[@accessiblename='导出回放脚本']"):
            raise RuntimeError("导出回放脚本对话框未打开，中止")

        # 3. 取消 / 重勾「事务包裹」——说明行跟随联动
        click(*abs_pt(WRAP_CHECK), move_dur=0.3, pauses=(0.15, 1.0))
        click(*abs_pt(WRAP_CHECK), move_dur=0.3, pauses=(0.15, 1.2))

        # 4. OK → 原生保存框（等待列表加载完，加载期按键会被吞）
        click(*abs_pt(OK_BTN), move_dur=0.3, pauses=(0.2, 3.0))

        # 5. 保存：保存框位置会在两种形态间漂移（保存(S)/打开(O)），坐标不可靠；
        #    「保存(S)」是默认按钮带焦点，直接 Enter 提交。**只按一次**——
        #    多按的 Return 会在对话框关闭后落到抢前台的浏览器上。
        pyautogui.press("return")
        time.sleep(1.2)
        focus_ide()                                        # 夺回被浏览器抢走的前台
        time.sleep(1.5)

        # 6. 打开导出的 .sql 展示注释头；先关预览浮窗（JDialog 会压住 .sql）
        from mcpnav import open_file
        open_file(EXPORTED_SQL, settle=2.2)
        time.sleep(0.6)
        click_component("//div[@text='取消']")
        time.sleep(3.0)                                    # 注释头定格
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
