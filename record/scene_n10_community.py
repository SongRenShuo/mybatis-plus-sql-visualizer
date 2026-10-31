# -*- coding: utf-8 -*-
"""N10「加入交流群」重录（2026-10-31：二维码确实加载完成才进画面、才收尾）。

旧片的问题不是画质，而是**内容录错**：弹框刚打开、二维码还是「正在加载二维码…」占位文字时
就进片尾。旧脚本靠 `time.sleep(4.0)` 猜加载时间，网络一慢就录到半张。

本次改动的四道锚点（都为了「内容对」）：

  1) **加载完成判据＝像素，不看时间**：弹框打开后轮询左右两张卡片，按「白底二维码」的形态检测
     —— 逐行统计近白像素数（二维码本体是白底 + 深色模块，整行近白像素可达 180；占位态只有
     灰字，近白像素≈0），得到二维码竖直带；再在带内取列范围，要求：
     宽高 150~220px、长宽比 0.8~1.25、框内近白占比 0.3~0.7、深色模块占比 0.25~0.7。
     **左右两张都满足才算加载完**，纯白/纯空/半张都会被拒。
  2) **稳定判据**：连续 2 次采样命中且 bbox 漂移 ≤6px，才认为「画完」（防画到一半的中间态）。
  3) **结构判据**：`微信交流` 窗口在 + `作者微信`/`交流群`/`关闭` 三个组件能在 robot-server
     组件树里查到（实测该 DialogWrapper 的控件在主窗组件树内）。
  4) **前台判据**：点工具栏按钮前断言前台 PID == 沙盒 PID，不盲点。

工具条按钮怎么定位：`加入交流群` 这个 ActionButton 在 robot 树里 x/y 是 Integer.MAX_VALUE
（toolbar 延迟布局，实测），不能按组件坐标点。改成**按工具条图标簇定位**：扫面板工具条行
（region y145~178 / x780~1120）的亮像素，聚成图标簇；顺序＝capture/clear/settings/suppress/
search/preview/scroll/sort/**community**/help，取第 9 簇（0 基 8）。簇数不足 9 说明按钮被折叠进
溢出（面板太窄），直接抛错而不是乱点别的按钮（贴/清空/设置相邻，点错会毁整条 take）。

时序：面板定格 → 点「加入交流群」→ 等二维码画完（上限 30s）→ 完整定格 2.6s → 点「关闭」→ 回同态。

前置（prep）：SQL Visualizer 工具窗已开、`SQL 日志` 页签激活、工具窗已拉宽到按钮不折叠、
无其它弹窗/气球。
"""
import ctypes
import os
import sys
import time

import numpy as np

BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE)
sys.path.insert(0, os.path.join(BASE, "lib"))

import mss  # noqa: E402
import pyautogui  # noqa: E402
from gifkit import Recorder, shot  # noqa: E402
from locate import (ensure_foreground, foreground_hwnd, pid_of_hwnd,  # noqa: E402
                    sandbox_pid, window_rect, windows)
from nav import force_en_us  # noqa: E402
from robot import click_component, find_components  # noqa: E402

WIN = (277, 25, 1370, 984)
REGION = (285, 26, 1354, 974)
OUT = os.path.join(BASE, "perf", "n10_community.mp4")

DLG_TITLE = "微信交流"
TOOLBAR_BAND = (700, 145, 1120, 180)     # region 相对：面板工具条行（含左侧 ▶ 起点，实测图标 720..1025）
BTN_CLOSE = "//div[@text='关闭']"

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0


def grab_rgb(region):
    with mss.mss() as s:
        mon = {"left": region[0], "top": region[1],
               "width": region[2], "height": region[3]}
        return np.asarray(s.grab(mon))[:, :, :3][:, :, ::-1].astype(int)


def sandbox_frame():
    pid = sandbox_pid()
    if pid is None:
        raise RuntimeError("找不到沙盒 PID（build/MPSV-GifFresh/.../config/.lock）")
    wins = [r for r in windows() if r[1] == pid and (r[3][2] - r[3][0]) > 1200]
    if not wins:
        raise RuntimeError("沙盒主窗口不在可见窗口列表里（pid=%s）" % pid)
    hwnd, _, title, rect = wins[0]
    print("sandbox pid=%s hwnd=%s rect=%s" % (pid, hwnd, rect))
    return pid, hwnd


def position_window(hwnd):
    u32 = ctypes.windll.user32
    u32.MoveWindow(hwnd, WIN[0], WIN[1], WIN[2], WIN[3], True)
    time.sleep(1.0)
    rect = [r[3] for r in windows() if r[0] == hwnd][0]
    if tuple(rect) != (277, 25, 1647, 1009):
        raise RuntimeError("窗口定位不符：%s（期望 (277, 25, 1647, 1009)）" % (rect,))
    img = grab_rgb(REGION)
    h, w = img.shape[:2]
    edges = {"top": img[:4].mean(), "bottom": img[h - 4:].mean(),
             "left": img[:, :4].mean(), "right": img[:, w - 4:].mean()}
    bad = {k: round(float(v), 1) for k, v in edges.items() if v > 120}
    if bad:
        raise RuntimeError("录制区有白边（%s）" % bad)
    print("window rect OK + 四边无白边 (%.0f/%.0f/%.0f/%.0f)"
          % (edges["top"], edges["bottom"], edges["left"], edges["right"]))


def toolbar_icons():
    """面板工具条行的图标簇中心（region 相对 x 列表）。"""
    x0, y0, x1, y1 = TOOLBAR_BAND
    band = grab_rgb(REGION)[y0:y1, x0:x1].mean(axis=2)
    col = band.max(axis=0)
    xs = np.where(col > 80)[0]
    if len(xs) == 0:
        return []
    groups, cur = [], [xs[0]]
    for x in xs[1:]:
        if x - cur[-1] > 6:
            groups.append(cur)
            cur = []
        cur.append(x)
    groups.append(cur)
    return [int(np.mean(g)) + x0 for g in groups]


def click_community(pid, hwnd, tries=2):
    icons = toolbar_icons()
    print("toolbar icons:", icons)
    if len(icons) < 9:
        raise RuntimeError("工具条图标簇只有 %d 个（按钮被折叠进溢出？面板太窄）——停录" % len(icons))
    x = 277 + icons[8]          # 第 9 个图标＝加入交流群（见模块注释的顺序）
    y = 25 + (TOOLBAR_BAND[1] + TOOLBAR_BAND[3]) // 2
    for _ in range(tries):
        if pid_of_hwnd(foreground_hwnd()) != pid:
            ensure_foreground(hwnd, tries=4, click_xy=(600, 400), wait=0.5)
        pyautogui.moveTo(x, y, duration=0.25)
        time.sleep(0.2)
        pyautogui.click(x, y)
        t0 = time.time()
        while time.time() - t0 < 2.5:
            if window_rect(title=DLG_TITLE, pick="largest", min_size=(300, 250)):
                print("dialog opened via icon #9 at", (x, y))
                return
            time.sleep(0.15)
    raise RuntimeError("点交流群图标后弹框未出现（图标簇=%s）——停录" % icons)


def qr_bbox(img):
    """在卡片区域内找二维码：返回 (x, y, w, h, 近白占比, 深色占比) 或 None。"""
    lum = img.mean(axis=2)
    white = lum > 235
    rc = white.sum(axis=1)
    rows = np.where(rc > 40)[0]                 # 二维码整行近白像素 60~180；文字行≈0
    if len(rows) < 100:
        return None
    y0, y1 = int(rows.min()), int(rows.max())
    cc = white[y0:y1 + 1].sum(axis=0)
    cols = np.where(cc > 20)[0]
    if len(cols) < 100:
        return None
    x0, x1 = int(cols.min()), int(cols.max())
    box = lum[y0:y1 + 1, x0:x1 + 1]
    w, h = x1 - x0 + 1, y1 - y0 + 1
    wf = float((box > 235).mean())
    return (x0, y0, w, h, wf, 1.0 - wf)


def qr_ready(card):
    if card is None:
        return False
    _, _, w, h, wf, ink = card
    # 微信二维码是绿底模块（亮度 ≈100），交流群是黑模块；共同点＝白底 + 25~65% 非白模块。
    return (150 <= w <= 230 and 150 <= h <= 230 and 0.8 <= w / h <= 1.25
            and 0.35 <= wf <= 0.75 and 0.25 <= ink <= 0.65)


def wait_qr_loaded(timeout=30.0):
    """等两张二维码都画完并稳定（连续 2 次 bbox 漂移 ≤6px）。"""
    t0 = time.time()
    prev, stable, last = None, 0, None
    while time.time() - t0 < timeout:
        rect = window_rect(title=DLG_TITLE, pick="largest", min_size=(300, 250))
        if rect is None:
            raise RuntimeError("「%s」弹框不见了" % DLG_TITLE)
        l, t, r, b = rect
        img = grab_rgb((l, t, r - l, b - t))
        half = img.shape[1] // 2
        cards = [qr_bbox(img[:, :half]), qr_bbox(img[:, half:])]
        if all(qr_ready(c) for c in cards):
            boxes = [(c[0], c[1]) for c in cards]
            if prev and all(abs(a[0] - b[0]) <= 6 and abs(a[1] - b[1]) <= 6
                            for a, b in zip(boxes, prev)):
                stable += 1
            else:
                stable = 1
            prev, last = boxes, cards
            if stable >= 2:
                print("QR loaded in %.1fs: %s" % (time.time() - t0,
                      [(c[2], c[3], round(c[4], 2), round(c[5], 2)) for c in cards]))
                return last
        else:
            stable, prev, last = 0, None, cards
        time.sleep(0.15)
    raise RuntimeError("二维码 %.0fs 内未画完（最后采样=%s）——停录，绝不录半张加载态"
                       % (timeout, [(c[2], c[3], round(c[4], 2)) if c else None
                                    for c in (last or [None, None])]))


def assert_dialog_structure():
    missing = [xp for xp in ("//div[@text='作者微信']", "//div[@text='交流群']", BTN_CLOSE)
               if not find_components(xp)]
    if missing:
        raise RuntimeError("弹框结构不完整，缺：%s" % missing)
    print("dialog structure OK: 作者微信 / 交流群 / 关闭")


def main():
    pid, hwnd = sandbox_frame()
    if not ensure_foreground(hwnd, tries=6, click_xy=(600, 400), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    position_window(hwnd)
    force_en_us(hwnd)
    time.sleep(0.3)

    # 开录前自检：按钮在工具条上可定位（不满足直接停，别盲点相邻按钮）
    icons = toolbar_icons()
    if len(icons) < 9:
        raise RuntimeError("工具条只有 %d 个图标簇，交流群按钮被折叠——先把工具窗拉宽" % len(icons))
    print("preflight icons:", icons)

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(1.0)                              # 1. 面板定格
        click_community(pid, hwnd)                   # 2. 打开弹框
        wait_qr_loaded(30.0)                         # 3. 二维码像素闸门（加载完才继续）
        assert_dialog_structure()                    # 4. 结构闸门
        time.sleep(2.6)                              # 5. 完整二维码定格
        if not click_component(BTN_CLOSE):           # 6. 关闭
            pyautogui.press("esc")
        time.sleep(1.2)
        if window_rect(title=DLG_TITLE, pick="largest", min_size=(300, 250)) is not None:
            pyautogui.press("esc")
            time.sleep(0.8)
    finally:
        rec.stop()
        print("frames=%d out=%s" % (rec.frames, OUT))

    if window_rect(title=DLG_TITLE, pick="largest", min_size=(300, 250)) is not None:
        raise RuntimeError("收尾时弹框仍开着——take 不能用")


if __name__ == "__main__":
    sys.exit(main())
