# -*- coding: utf-8 -*-
"""N12「展示与导出脱敏」重录 20261028 v3。

分镜（约 12 秒）：
1. SQL 日志列表：INSERT 条目里 password 字面量是明文（`Secret123`），停 1.5s
2. 打开设置 → **真实滚轮滚到「展示与导出脱敏」**（约 2.5s，页面滚动本身入镜）
3. 勾选该开关 → 确定
4. 回日志列表：同一批条目的 password 变成 `***`，停 2.0s

前置（prep，不入镜）：
- SQL 日志列表里已有含 password 参数的条目（POST /user 造数即可）；
- 「展示与导出脱敏」初始为**关**（脚本结束时还原成关）。

标定（20261028 实测）：
  滚动：`pyautogui.scroll(-100)` × 40 ≈ 2.5s 从顶部滚到脱敏行（真实滚轮，PostMessage 无效）
  复选框/OK：**滚动后用 robot 实时取中心**（滚动会改父链偏移，写死坐标会点偏）

坑：
① 设置页滚动位置**不跨「关→开」保留**（多开几次会回顶部）→ 滚动只能放进 take。
② 三行复选框间距仅 28px，整图目测必点偏（本轮白点两次）→ 一律用 robot 的
   `component_screen_center` 取中心。
③ 脱敏只改**列表渲染**，控制台仍显示明文——产品行为，不是 bug。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

import pyautogui

from gifkit import Recorder
from robot import component_screen_center, find_components, click_component

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n12_display_mask.mp4")

LOG_TAB = '//div[contains(@class,"TabLabel") and contains(@accessiblename,"SQL 日志")]'
MASK_CB = "//div[contains(@accessiblename,'展示与导出脱敏')]"
OK_BTN = "//div[@text='OK']"
SCROLL_AT = (1200, 700)      # 设置页内容区内的悬停点
SCROLL_STEPS = 40


def has(xp):
    return len(find_components(xp)) > 0


def close_settings():
    """关掉设置窗（Esc 关不掉它，20261028 实测）→ 用 WM_CLOSE，等同 Cancel。"""
    import win32con
    import win32gui

    target = [0]

    def cb(h, _):
        t = win32gui.GetWindowText(h)
        if win32gui.IsWindowVisible(h) and t.startswith("Settings – "):
            target[0] = h
        return True

    win32gui.EnumWindows(cb, None)
    if target[0]:
        win32gui.PostMessage(target[0], win32con.WM_CLOSE, 0, 0)
        time.sleep(1.4)
        return True
    return False


def scroll_to_mask():
    """真实滚轮滚到脱敏行（PostMessage 滚轮在设置页无效）。"""
    pyautogui.moveTo(*SCROLL_AT, duration=0.25)
    time.sleep(0.2)
    # 滚动尽量快：每帧变化少 → GIF 体积小（慢滚 2.5s 时成片 3.6MB，超 2MB 上限）
    for _ in range(SCROLL_STEPS):
        pyautogui.scroll(-100)
        time.sleep(0.01)
    time.sleep(0.6)


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    # ---- prep（不入镜）：确保停在日志页、设置页已关 ----
    if not has(LOG_TAB):
        raise RuntimeError("找不到「SQL 日志」页签（工具窗没开？）")
    click_component(LOG_TAB)
    time.sleep(1.2)
    close_settings()

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        # 1. 列表明文态
        pyautogui.moveTo(1180, 470, duration=0.6)
        pyautogui.moveTo(1180, 540, duration=0.8)
        time.sleep(1.4)

        # 2. 打开设置并滚到脱敏行（滚动过程入镜）
        pyautogui.hotkey("ctrl", "alt", "s")
        time.sleep(2.4)
        scroll_to_mask()
        if not has(MASK_CB):
            raise RuntimeError("滚动后仍未看到「展示与导出脱敏」")
        cb = component_screen_center(MASK_CB)

        # 3. 勾选 → 确定
        pyautogui.moveTo(*cb, duration=0.4)
        time.sleep(0.4)
        pyautogui.click(*cb)
        time.sleep(1.0)
        ok = component_screen_center(OK_BTN)
        pyautogui.click(*ok)
        time.sleep(2.2)

        # 4. 列表打码态
        pyautogui.moveTo(1180, 470, duration=0.6)
        pyautogui.moveTo(1180, 540, duration=0.9)
        time.sleep(1.8)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")

    # ---- 还原：改回关（不入镜）----
    pyautogui.hotkey("ctrl", "alt", "s")
    time.sleep(2.2)
    scroll_to_mask()
    if has(MASK_CB):
        pyautogui.click(*component_screen_center(MASK_CB))
        time.sleep(0.8)
    if has(OK_BTN):
        pyautogui.click(*component_screen_center(OK_BTN))
    time.sleep(1.2)


if __name__ == "__main__":
    main()
