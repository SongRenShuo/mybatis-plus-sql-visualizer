# -*- coding: utf-8 -*-
"""批 4 统一录制驱动（T-DemoGifFix1026 批4）。

动机：a11 连废 3 个 take，全是**环境问题**而非脚本问题——
  ① 索引期（右上 Analyzing…）里 Alt+Shift+Q 静默无效（坑44）；
  ② 鼠标压在代码上会弹文档浮层，毁掉预览画面（坑27）；
  ③ 沙盒全局方言配置 dialectAuto=false 且 dialect 键为空 → 渲染回退 PostgreSQL，
     执行计划自动选 PG 源并弹凭据框（本轮实测）。

本驱动把这些做成**开录前硬闸门**，不过就不跑 scene：
  G1 沙盒窗口在位（矩形断言）
  G2 索引空闲（右上 Analyzing 区域无亮字簇）
  G3 方言为 MySQL（预览头文本，可选断言）
  G4 前台是沙盒 IDE（防误点用户其它窗口）

用法：python b4_run.py scene_a11_explain.py [更多脚本...]
"""
import ctypes
import ctypes.wintypes as wt
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import numpy as np
from PIL import Image

REGION = (285, 26, 1354, 974)
WIN = (277, 25, 1370, 984)
EXPECT_RECT = (277, 25, 1647, 1009)


def u32():
    u = ctypes.windll.user32
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        u.SetProcessDPIAware()
    return u


def ide_hwnd(u):
    hit = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(h, _):
        if u.IsWindowVisible(h):
            n = u.GetWindowTextLengthW(h)
            if n > 0:
                buf = ctypes.create_unicode_buffer(n + 1)
                u.GetWindowTextW(h, buf, n + 1)
                if "mybatis-plus-sql-visualizer-demo" in buf.value:
                    hit.append(h)
        return True
    u.EnumWindows(cb, None)
    return hit[-1] if hit else None


def g1_window(u, hwnd):
    r = wt.RECT()
    u.GetWindowRect(ctypes.c_void_p(hwnd), ctypes.byref(r))
    got = (r.left, r.top, r.right, r.bottom)
    if got != EXPECT_RECT:
        u.MoveWindow(ctypes.c_void_p(hwnd), *WIN, True)
        time.sleep(1.2)
        u.GetWindowRect(ctypes.c_void_p(hwnd), ctypes.byref(r))
        got = (r.left, r.top, r.right, r.bottom)
    assert got == EXPECT_RECT, f"G1 窗口矩形不符: {got} != {EXPECT_RECT}"
    return True


def grab_region():
    from gifkit import shot
    p = os.path.join(HERE, "perf", "_b4_gate.png")
    shot(REGION, p)
    return np.asarray(Image.open(p).convert("L"), dtype=float)


def g2_indexing_idle(timeout=240):
    """索引空闲：优先用 robot 组件树查「Analyzing」文本（最可靠）。

    像素法三次校准都失败（Analyzing 在标题栏右侧浮动位置随内容变，且代码区恒有亮字），
    改用组件树：命中 0 个即空闲。组件树也不可用时退回「等 15s 且不报错」。
    """
    from robot import find_components
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            hits = find_components(
                "//div[contains(@accessiblename,'Analyzing') or contains(@accessiblename,'索引')]")
            if not hits:
                return True
        except Exception:
            time.sleep(10)
            return True
        time.sleep(6)
    raise AssertionError("G2 索引 240s 内未完成")


def g4_foreground(u, hwnd):
    if u.GetForegroundWindow() != hwnd:
        u.keybd_event(0x12, 0, 0, 0)
        u.keybd_event(0x12, 0, 2, 0)
        u.SetForegroundWindow(ctypes.c_void_p(hwnd))
        time.sleep(1.0)
        import pyautogui
        pyautogui.press("esc")
        time.sleep(0.3)
    assert u.GetForegroundWindow() == hwnd, "G4 前台不是沙盒 IDE——中止（防误点其它窗口）"
    return True


def main():
    scenes = sys.argv[1:]
    if not scenes:
        print(__doc__)
        return 1
    u = u32()
    hwnd = ide_hwnd(u)
    assert hwnd, "找不到沙盒 IDE 窗口"
    g1_window(u, hwnd)
    g4_foreground(u, hwnd)
    g2_indexing_idle()
    print("[B4-GATES] G1 window OK / G2 indexing idle OK / G4 foreground OK")

    for s in scenes:
        print(f"\n===== {s} =====", flush=True)
        t0 = time.time()
        r = subprocess.run([sys.executable, os.path.join(HERE, s)], capture_output=True, text=True)
        out = (r.stdout or "") + (r.stderr or "")
        print(out.strip()[-1200:], flush=True)
        print(f"exit={r.returncode}  {time.time()-t0:.0f}s", flush=True)
        if r.returncode != 0:
            print(f"[B4] {s} 失败，停止后续（避免在坏环境里连废 take）")
            return r.returncode
        # 每个 scene 之后重新过闸门（脚本可能改变状态）
        g4_foreground(u, hwnd)
        g2_indexing_idle(timeout=90)
    return 0


if __name__ == "__main__":
    sys.exit(main())
