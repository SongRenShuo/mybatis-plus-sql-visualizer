# -*- coding: utf-8 -*-
"""A45 原生 MyBatis 拦截器自动注入（interceptor-inject.gif）20261028 重写。

分镜（约 10 秒）：
1. 设置页 SQL Log 的「源码位置捕获」区：勾选「自动注入源码捕获拦截器」
2. 弹出「选择注入模块」对话框（三个模块全勾）→ 只保留 native-mybatis-demo（取消另外两个）
3. OK → 生成拦截器与 mybatis-config.xml 注册 → 设置关闭
4. 打开生成的 SqlSourceInterceptor.java 展示类头与 beforeQuery/beforeUpdate

前置（prep，不入镜）：
- 设置页停在 SQL Log 页并滚到「源码位置捕获」区（滚动在 take 内做，约 1.5s）；
- 自动注入开关初始为**关**；
- demo 仓工作树干净（注入会写文件，录后必须还原）。

标定（20261028 实测，设置窗 1130 宽）：
  自动注入复选框本体 (992, 723)   ← 滚到位后；滚动前在 y≈1299
  模块对话框复选框 x=852，行 y=518/548/578；模块 OK (947,694)、Cancel (1029,694)

坑：
① 「自动注入」在 **SQL Log 页**的「源码位置捕获」区，不在 SQL 预览页——找错页会一直 0 命中；
② 该复选框在首屏之下，必须真实滚轮滚（合成滚动无效）；
③ 勾选后模块对话框是**独立窗口**，会盖在设置页上，两个 OK 别点混（模块 OK 在 947,694）。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

import pyautogui

from gifkit import Recorder
from robot import click_component, component_screen_center, find_components

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "a45_inject.mp4")

INJECT_CB = "//div[contains(@accessiblename,'自动注入源码捕获拦截器')]"
SCROLL_AT = (1200, 700)

MOD_ALL = (885, 643)       # 全选
MOD_NONE = (970, 643)      # 全不选
MOD_NATIVE = (851, 541)    # native-mybatis-demo
MOD_KOTLIN = (851, 576)    # mybatis-plus-kotlin-demo
MOD_PLUS = (851, 611)      # mybatis-plus-demo
MOD_OK = (1198, 689)

PRODUCT = ("native-mybatis-demo/src/main/java/cn/song/nativedemo/config/"
           "SqlSourceInterceptor.java")


def has(xp):
    return len(find_components(xp)) > 0


def close_settings():
    """关设置窗（等同 Cancel，不影响已落盘的注入产物）。"""
    import win32con
    import win32gui

    target = [0]

    def cb(h, _):
        if win32gui.IsWindowVisible(h) and win32gui.GetWindowText(h).startswith("Settings – "):
            target[0] = h
        return True

    win32gui.EnumWindows(cb, None)
    if target[0]:
        win32gui.PostMessage(target[0], win32con.WM_CLOSE, 0, 0)
        time.sleep(1.6)
        return True
    return False


def close_window(title, timeout=3.0):
    """按标题关掉独立窗口（气泡 / Add Files to Git）。这两个窗口都会**抢前台并吞点击**。"""
    import win32con
    import win32gui

    deadline = time.time() + timeout
    while time.time() < deadline:
        target = [0]

        def cb(h, _):
            if win32gui.IsWindowVisible(h) and win32gui.GetWindowText(h).strip() == title:
                target[0] = h
            return True

        win32gui.EnumWindows(cb, None)
        if target[0]:
            win32gui.PostMessage(target[0], win32con.WM_CLOSE, 0, 0)
            time.sleep(0.9)
            return True
        time.sleep(0.3)
    return False


def box_checked(box_xy):
    """勾选框是否已勾：采样蓝底像素占比（JBCheckBox 勾选态是蓝色填充）。

    20261028 实测：robot 组件树**不暴露 selected 状态**，而开关跨 take 会漂
    （上一 take 结束时是开，下一次 prep 再点就变成关 → 模块对话框永不出现）。
    """
    import numpy as np
    from PIL import ImageGrab

    x, y = box_xy
    im = ImageGrab.grab(bbox=(x - 8, y - 8, x + 8, y + 8)).convert("RGB")
    a = np.asarray(im, dtype=np.int16)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    blue = ((b > 110) & (b - r > 40)).sum()
    return int(blue) > 40


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    # ---- prep（不入镜）：设置页停在 SQL Log 并滚到「源码位置捕获」 ----
    pyautogui.hotkey("ctrl", "alt", "s")
    time.sleep(2.4)
    # 先搜索过滤左树（树行位置随上次停留页漂移，直接按坐标点会点到别的页）
    from robot import click_component as _cc
    if has("//div[@class='TextFieldWithProcessing']"):
        _cc("//div[@class='TextFieldWithProcessing']")
        time.sleep(0.5)
        pyautogui.hotkey("ctrl", "a")
        time.sleep(0.2)
        pyautogui.typewrite("SQL Visualizer", interval=0.05)
        time.sleep(1.8)
    pyautogui.click(580, 497)          # 过滤后左树 SQL Log
    time.sleep(1.6)
    pyautogui.moveTo(*SCROLL_AT, duration=0.2)
    for _ in range(45):
        pyautogui.scroll(-100)
        time.sleep(0.02)
    time.sleep(0.5)
    if not has(INJECT_CB):
        raise RuntimeError("prep: 未滚到「自动注入」复选框")

    # ---- prep 续：把开关归一化成「关」（不入镜）----
    cb = component_screen_center(INJECT_CB)
    box = (cb[0] - 124, cb[1])
    close_window("源码跳转配置")
    if box_checked(box):                    # 已开 → 先关掉
        pyautogui.click(*box)
        time.sleep(1.4)
        close_window("源码跳转配置")
        if has("//div[@text='Cancel']"):
            pyautogui.click(1283, 689)
            time.sleep(0.8)

    rec = Recorder(REGION, OUT)
    time.sleep(0.6)
    rec.start()
    try:
        # 1. 勾选开关 → 弹模块对话框
        cb = component_screen_center(INJECT_CB)
        pyautogui.click(cb[0] - 124, cb[1])
        time.sleep(2.6)
        close_window("源码跳转配置", timeout=2.0)     # 气泡会抢前台，先关掉

        # 2. 「全选」归零，再取消另外两个
        #    （对话框初始勾选状态不固定——上一轮可能是空的，直接按「勾 native」会全空）
        # 「全不选」→ 只勾 native：不依赖对话框初始状态，也不需要按行取消
        pyautogui.click(*MOD_NONE, duration=0.3)
        time.sleep(0.6)
        pyautogui.click(*MOD_NATIVE, duration=0.3)
        time.sleep(0.9)

        # 3. 注入（IDE 可能弹「Add Files to Git」，它也会抢前台）
        pyautogui.click(*MOD_OK, duration=0.3)
        time.sleep(2.6)
        close_window("Add Files to Git", timeout=4.0)
        close_window("源码跳转配置", timeout=1.5)
        time.sleep(1.2)
        close_settings()          # 设置窗不关会挡住产物文件（末帧全是设置页）
        # 预览弹窗也会盖住编辑器（上一张 n11 留下的），关掉再展示产物
        for xp in ("//div[@text='取消']", "//div[@accessiblename='取消']"):
            if has(xp):
                click_component(xp)
                time.sleep(1.4)
                break

        # 4. 打开生成的拦截器
        try:
            from mcpnav import open_file
            open_file(PRODUCT, settle=2.4)
        except Exception as e:
            print("(打开产物跳过)", str(e)[:60])
        time.sleep(1.2)
        pyautogui.moveTo(1150, 400, duration=0.8)
        time.sleep(1.8)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
