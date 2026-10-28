# -*- coding: utf-8 -*-
"""N03 EXPLAIN 跨次对比（explain-history-compare.gif）20261028 重写。

前置（prep，不入镜）：
1. 设置页「预览面板内结构化渲染 EXPLAIN」已开启（默认关闭，关着只能走原生控制台）；
2. **已有一条不同计划的基线快照**——本脚本用「建索引 → 执行一次 → 删索引」制造：
   有索引时 `type=ref / key=idx_share_scheme / rows=2`，删索引后 `type=index / key=PRIMARY / rows=4`，
   于是对比条出现真实差异（不是「计划无变化」）；
3. 预览面板已开（mpj DeviceInfoServiceImpl#joinListEntity，绑 mpj_demo）。

分镜（约 9 秒）：
1. 预览面板定格
2. 点「执行计划」→ 结构化计划弹窗
3. 拖大弹窗（默认宽度会把右上角的「与上次执行对比」块裁掉）
4. 凝视计划表 → 凝视对比块（`与上次执行对比` + 变化行）
5. 关闭弹窗

坑（20261028 实测）：
① 对比块右对齐，**默认 940 宽弹窗下它整块被裁到屏幕外**（robot 算出 x≈2569），
   必须把弹窗拉到 ~1330 宽才可见；
② 弹窗尺寸不跨次保留 → 只能在 take 内拖；
③ 对比基线取历史快照（每条 SQL 最多 5 份），所以「先跑一次再改环境」是造差异的通用手法。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

import pyautogui

from gifkit import Recorder
from locate import sandbox_pid, windows
from robot import find_components, click_component

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n03_explain_history.mp4")

PREVIEW = "//div[@accessiblename='SQL Visualizer - SQL 预览']"
EXPLAIN_BTN = ("(//div[@text='执行计划'])[1]",
               "(//div[contains(@accessiblename,'执行计划')])[1]")
CLOSE_BTN = "//div[@text='关闭']"

TARGET_RECT = (300, 40, 1330, 940)      # 拖大后的弹窗（落在录制区内）


def has(xp):
    return len(find_components(xp)) > 0


def dialog_rect():
    pid = sandbox_pid()
    for _h, p, t, r in windows():
        if p == pid and t.strip() == "执行计划":
            return r
    return None


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    # ---- prep（不入镜）：关掉残留弹窗，确认预览在 ----
    if has(CLOSE_BTN):
        click_component(CLOSE_BTN)
        time.sleep(1.2)
    if not has(PREVIEW):
        raise RuntimeError("prep: 预览未打开")

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        # 1. 预览定格
        pyautogui.moveTo(1150, 480, duration=0.7)
        time.sleep(1.2)

        # 2. 执行计划
        for xp in EXPLAIN_BTN:
            if has(xp):
                click_component(xp)
                break
        time.sleep(5.5)

        # 3. 放大弹窗（默认宽度会把右上角对比块裁到屏幕外）
        #    该对话框**不可拖拽改尺寸**（20261028 实测：拖右下角无效），只能 SetWindowPos。
        rect = dialog_rect()
        if rect is None:
            raise RuntimeError("执行计划弹窗未出现")
        import ctypes

        hwnd = [0]
        pid = sandbox_pid()
        for h, p, t, r in windows():
            if p == pid and t.strip() == "执行计划":
                hwnd[0] = h
        u = ctypes.windll.user32
        u.SetWindowPos.argtypes = ([ctypes.c_void_p] * 2 + [ctypes.c_int] * 4
                                   + [ctypes.c_uint])
        u.SetWindowPos(ctypes.c_void_p(hwnd[0]), ctypes.c_void_p(0),
                       TARGET_RECT[0], TARGET_RECT[1],
                       TARGET_RECT[2], TARGET_RECT[3], 0x0004)   # SWP_NOZORDER
        time.sleep(1.6)

        # 4. 凝视计划表 → 对比块（右上）
        pyautogui.moveTo(700, 480, duration=0.8)
        time.sleep(1.2)
        pyautogui.moveTo(1380, 210, duration=0.8)
        time.sleep(1.8)

        # 5. 关闭
        if has(CLOSE_BTN):
            click_component(CLOSE_BTN)
        time.sleep(1.2)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
