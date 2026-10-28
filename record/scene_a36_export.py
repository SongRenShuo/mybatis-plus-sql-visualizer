# -*- coding: utf-8 -*-
"""A36 历史 SQL 导出（history-export.gif）20261028 重写。

前置（prep，不入镜）：SQL Visualizer 工具窗停在「SQL 历史」页、已双击打开某个历史文件
      （列表里显示 #1..#N 条目）、最近一次导出目录已设为 demo 工程根。

分镜（约 12 秒）：
1. 历史条目列表定格（277 条 SQL）
2. 右键第 1 条 → 「导出 SQL」→ 格式对话框（默认 SQL）→ OK
3. 原生保存框（文件名自动带时间戳、目录为 demo 根）→ 保存
4. 写盘完成 → 打开导出的 .sql，展示条目内容

**20261028 重写的根因**（旧版全部失效）：
① 旧脚本在「SQL 历史」页**文件列表**上右键找「导出」——该页首屏只是 `sql-history-*.sql`
   文件清单，右键无菜单；必须**双击文件**进入条目列表，导出动作才在条目的右键菜单里
   （`HistorySqlLogPanel.buildSqlContextMenu` → `ExportSqlAction`）。
② **右键菜单是 JBPopup，鼠标点击会被「关闭菜单」吃掉**（先用坐标点第 5 项，菜单关了却
   什么都没发生）→ 必须键盘导航：`Home` 归位 + `Down×4` 落到「导出 SQL」+ `Return`。
③ 首次单击条目会弹「单击现在已选中 SQL…」引导浮层，**压住右键菜单** → prep 里先点「知道了」。
④ MCP `open_file_in_editor` **只能开项目内文件**，导出到 record/perf 打不开 →
   保存框里把目录改成 demo 工程根（一次性 prep 后，之后默认就落在这里）。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

import pyautogui

from gifkit import Recorder
from locate import sandbox_pid, windows
from robot import click_component, find_components, menu_items

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "a36_export_native.mp4")
EXPORTED = "sql-export-demo.sql"          # 相对 demo 工程根

OX, OY = REGION[0], REGION[1]
ROW1 = (1400, 213)                        # 条目 #1（全屏）
HISTORY_TAB = '//div[contains(@class,"TabLabel") and contains(@accessiblename,"SQL 历史")]'
HISTORY_FILE_ROW = (1400, 197)            # 历史文件列表首行（全屏）
SAVE_BTN = '//div[@accessiblename="保存(S)"]'
FILENAME_OFF = (245, 393)                 # 保存框内「文件名」输入框（相对窗口左上角）


def has(xp):
    return len(find_components(xp)) > 0


def save_dialog():
    """原生「导出 SQL」保存框是**独立窗口**，robot 组件树看不到（20261028 实测），
    只能按窗口标题判存在、按窗口矩形算控件位置。"""
    pid = sandbox_pid()
    for _h, p, t, r in windows():
        if p == pid and t.strip() == "导出 SQL":
            return r
    return None


def wait_save_dialog(timeout=6.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        r = save_dialog()
        if r:
            return r
        time.sleep(0.3)
    return None


def dismiss_onboarding():
    """首次单击条目会弹引导浮层，压住右键菜单。"""
    for xp in ('//div[@text="知道了"]', '//div[@accessiblename="知道了"]'):
        if has(xp):
            click_component(xp)
            time.sleep(1.2)
            return True
    return False


def prep():
    """停在历史条目列表，且不许有浮层/菜单残留。"""
    dismiss_onboarding()
    if not has('//div[contains(@text,"网络结构")]'):     # 条目列表的特征文案
        click_component(HISTORY_TAB)
        time.sleep(1.2)
        pyautogui.doubleClick(*HISTORY_FILE_ROW)
        time.sleep(2.4)
    pyautogui.press("esc")
    time.sleep(0.5)


def export_menu():
    """右键第 1 条 → 键盘导航到「导出 SQL」（鼠标点击会被菜单关闭吃掉）。"""
    pyautogui.click(*ROW1)
    time.sleep(0.5)
    pyautogui.rightClick(*ROW1)
    time.sleep(1.2)
    n = len(menu_items())
    if n < 5:
        raise RuntimeError("右键菜单项不足 5 项（实际 %d）——条目未选中？" % n)
    pyautogui.press("home")
    time.sleep(0.3)
    for _ in range(4):
        pyautogui.press("down")
        time.sleep(0.22)
    pyautogui.press("return")
    time.sleep(1.9)


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    prep()

    rec = Recorder(REGION, OUT)
    rec.start()
    try:
        pyautogui.moveTo(1400, 240, duration=0.5)
        pyautogui.moveTo(1400, 330, duration=0.9)      # 1. 列表定格
        time.sleep(0.8)

        export_menu()                                   # 2. 右键 → 导出 SQL
        if not has('//div[@accessiblename="OK"]'):
            raise RuntimeError("格式对话框未出现")
        time.sleep(1.2)                                 # 格式四选定格（默认 SQL）
        click_component('//div[@accessiblename="OK"]')
        time.sleep(2.4)

        # 3. 原生保存框：目录已是 demo 根，把自动时间戳名改成固定名再保存
        rect = wait_save_dialog(8.0)
        if rect is None:
            raise RuntimeError("原生保存框未出现（未找到标题为「导出 SQL」的窗口）")
        x0, y0, x1, y1 = rect
        time.sleep(0.8)
        pyautogui.click(x0 + FILENAME_OFF[0], y0 + FILENAME_OFF[1])   # 文件名输入框
        time.sleep(0.4)
        pyautogui.hotkey("ctrl", "a")
        time.sleep(0.2)
        pyautogui.typewrite(EXPORTED, interval=0.03)
        time.sleep(0.9)
        pyautogui.press("return")                                     # 默认按钮＝保存
        time.sleep(2.6)
        if save_dialog() is not None:
            raise RuntimeError("保存框未关闭（保存可能失败）")

        # 4. 打开导出的 .sql 展示内容（MCP 相对 demo 工程根）
        from mcpnav import open_file
        open_file(EXPORTED, settle=2.0)
        time.sleep(0.6)
        pyautogui.moveTo(900, 420, duration=0.8)
        pyautogui.moveTo(1000, 560, duration=1.2)
        time.sleep(1.6)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
