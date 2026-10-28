# -*- coding: utf-8 -*-
"""N11 回滚快照双态（rollback-snapshot-dual.gif）20261028 重写。

分镜（约 11 秒）：
1. UPDATE 预览（`UserMapper.updateUserByXml`，参数 #id=2）
2. 「更多」→「回滚 SQL」→ 弹窗（说明 + 快照查询 SQL）
3. 「执行快照查询」→ 结果网格（1 行 × 6 列：id/name/age/sex/head_img/deleted）
4. 切「文本（粘贴/编辑）」→ 文本态
5. 关闭弹窗与预览

前置（prep，不入镜）：
- 预览已开、光标在 `<update id="updateUserByXml">`、参数 #id 已填 2；
- 执行控制台可用（本图依赖真实查询，见验收文档 §11.4 的控制台绑定说明）。

标定（20261028 实测）：
  「更多」(1499,216)；「执行快照查询」(363,524)；「文本（粘贴/编辑）」(908,492)；
  回滚弹窗「关闭」(949,702)；预览「取消」(1237,838)；参数 #id 输入框 (583,627)

坑：
① 「更多」菜单是 JBPopup，鼠标点菜单项会被关闭动作吃掉 → 键盘 Home + Enter；
② 参数面板四个输入框纵向排列，**#id 是第 4 个（y≈627）**，按 y≈599 点的是空档；
③ 快照 SQL 由 XML 里的 `and deleted = 0` 决定，与「逻辑删除注入」开关无关。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

import pyautogui

from gifkit import Recorder
from robot import click_component, find_components

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n11_rollback_snapshot.mp4")

MORE = (1499, 216)
SNAPSHOT = (363, 524)
TEXT_TOGGLE = (908, 492)
RB_CLOSE = (949, 702)
PREVIEW_CANCEL = (1237, 838)


def has(xp):
    return len(find_components(xp)) > 0


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    if not has("//div[contains(@accessiblename,'SQL Visualizer - SQL 预览')]"):
        raise RuntimeError("前置未满足：UPDATE 预览未打开")

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        # 1. 预览定格
        pyautogui.moveTo(1150, 480, duration=0.7)
        time.sleep(1.2)

        # 2. 更多 → 回滚 SQL（JBPopup 必须键盘选）
        pyautogui.click(*MORE); time.sleep(1.2)
        pyautogui.press("home"); time.sleep(0.5)
        pyautogui.press("return"); time.sleep(2.2)
        pyautogui.moveTo(900, 380, duration=0.6); time.sleep(1.2)

        # 3. 执行快照查询 → 结果网格（按钮位置随弹窗漂，用 robot 按文本点）
        for xp in ("//div[@text='执行快照查询']",
                   "//div[contains(@accessiblename,'执行快照查询')]"):
            if has(xp):
                click_component(xp)
                break
        time.sleep(5.5)
        pyautogui.moveTo(760, 600, duration=0.7); time.sleep(1.6)

        # 4. 文本态
        for xp in ("//div[@text='文本（粘贴/编辑）']",
                   "//div[contains(@accessiblename,'文本（粘贴/编辑）')]"):
            if has(xp):
                click_component(xp)
                break
        time.sleep(1.8)
        pyautogui.moveTo(900, 640, duration=0.6); time.sleep(1.2)

        # 5. 关弹窗与预览
        if has("//div[@text='关闭']"):
            click_component("//div[@text='关闭']")
        time.sleep(1.0)
        for xp in ("//div[@text='取消']", "//div[@accessiblename='取消']"):
            if has(xp):
                click_component(xp)
                break
        time.sleep(1.2)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
