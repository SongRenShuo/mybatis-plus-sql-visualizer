# -*- coding: utf-8 -*-
"""R12 执行 SQL 重录（T-DocsRefresh2023 / T-PreviewSideQuery P3a）20261027 v3。

分镜（约 9 秒）：
1. 联表预览（LEFT JOIN station_info），停 1.2s
2. 点「执行 SQL」→ 执行视图（真实结果网格），凝视
3. 点执行视图头部「返回预览」→ 回到可编辑 SQL
4. 点「取消」关预览回编辑器

坑（本轮全部踩过）：
① 预览「未绑定数据源」时点「执行 SQL」会弹「选择数据源」JBPopup——robot 组件树查不到，
   只能像素点（region 相对 572,714＝mpj_demo@localhost 行）。
② **冷控制台首连 + 首次执行要 10s+**：take2 里入镜段只等了 ~4.6s，画面全程停在
   「执行中…」，结果网格从没出现。→ 绑定+首执行挪进 prep（不入镜），轮询「返回预览」
   组件出现为止（≤40s）；入镜段第二次执行走已绑定+已建页签路径，~3s 出结果（a13 实测）。
③ 弹窗按钮行坐标随「新建态/结果态」上下移 ~40px → 一律用 a13 的 find_exec_button
   （几何标定 底边−39 亮簇）与 robot 组件点击，不写死像素。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui                                              # noqa: E402
from gifkit import Recorder                                   # noqa: E402
from locate import sandbox_pid, window_rect                   # noqa: E402
from robot import click_component as cc, require_component    # noqa: E402

BASE = os.path.dirname(os.path.abspath(__file__))
REGION = (285, 26, 1354, 974)
OUT = os.path.join(BASE, "perf", "r12_execute.mp4")

OX, OY = REGION[0], REGION[1]
DS_MENU_ROW = (905, 782)   # 全屏坐标：mpj_demo@localhost 行（20261027 实测）
BACK_BTN = (1490, 333)     # 全屏坐标：执行视图头部「返回预览」按钮中心（20261027 实测）
SAFE_CORNER = (1750, 200)  # 右上空档：任何关键点击前先到这里，避免移动路径扫过
                           # 结果工具栏的数据源选择器——它的 tooltip 会吞掉后续点击
                           #（20261027 实测：直线移过去点「返回预览」两次全被吞）          # 「选择数据源」弹层首行 mpj_demo@localhost（region 相对）
BACK_PREVIEW = "//div[@text='返回预览']"   # 精确匹配按钮（contains 会撞上横幅文案）
EXEC_SQL = "//div[@text='执行 SQL']"
CANCEL = "//div[@text='取消']"

GAZE_SQL = (1100, 500)
GAZE_GRID = (700, 600)
PARK = (577, 725)


def has(xp):
    from robot import find_components
    return len(find_components(xp)) > 0


def find_exec_button(ide_pid):
    """同 a13：预览弹窗底部按钮行「执行 SQL」＝底边−39 最左亮簇（新建态限定）。"""
    from locate import bright_band_x
    from locate import grab as locate_grab
    main_rect = window_rect(pid=ide_pid, pick="largest")
    popup = window_rect(pid=ide_pid, pick="smallest", min_size=(600, 400))
    if popup is None or popup == main_rect:
        raise RuntimeError("预览弹窗未打开")
    pw, ph = popup[2] - popup[0], popup[3] - popup[1]
    img = locate_grab((popup[0], popup[1], pw, ph))
    row_y = ph - 39
    clusters = bright_band_x(img, max(0, row_y - 14), min(ph, row_y + 14),
                             int(pw * 0.45), pw, thr=45.0, min_gap=14, min_w=8)
    if not clusters:
        raise RuntimeError("按钮行上未找到亮簇（弹窗可能不在新建态）")
    return (popup[0] + (clusters[0][0] + clusters[0][1]) // 2, popup[1] + row_y)


def click_back_preview(pyautogui):
    """两段式进场点「返回预览」：右上角空档 → 垂直接近，全程不扫过工具栏行。"""
    pyautogui.moveTo(*SAFE_CORNER, duration=0.2)
    time.sleep(0.3)
    pyautogui.moveTo(*BACK_BTN, duration=0.15)
    time.sleep(0.6)
    pyautogui.click(*BACK_BTN)


def wait_result(timeout):
    """轮询「返回预览」出现＝执行视图已就绪。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if has(BACK_PREVIEW):
            return True
        time.sleep(0.5)
    return False


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    ide_pid = sandbox_pid()

    # ---- prep（不入镜）：绑定数据源 + 首执行（冷控制台 10s+ 不能入镜等）----
    btn = find_exec_button(ide_pid)
    pyautogui.click(*btn)
    time.sleep(1.6)
    if not wait_result(2.0):                     # 弹了选源菜单＝尚未绑定
        pyautogui.click(*DS_MENU_ROW)
        print("prep: 选源 mpj_demo…")
        if not wait_result(40):
            raise RuntimeError("prep 执行 40s 未出结果（执行中… 挂死）")
    print("prep: 首执行完成，数据源已绑定")
    click_back_preview(pyautogui)                # 回新建态（顶部应显示 已绑定：mpj_demo@localhost）
    time.sleep(1.5)
    if has(BACK_PREVIEW):
        raise RuntimeError("prep: 返回预览未生效（仍停在结果视图）")

    # ---- 录制 ----
    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        pyautogui.moveTo(*GAZE_SQL, duration=0.6); time.sleep(1.2)   # 1. 联表预览凝视

        cc(EXEC_SQL)                                                  # 2. 执行 SQL
        if not wait_result(20):
            raise RuntimeError("入镜执行 20s 未出结果")
        pyautogui.moveTo(*GAZE_GRID, duration=0.8); time.sleep(2.2)  # 凝视结果网格
        pyautogui.moveTo(900, 660, duration=0.7); time.sleep(1.6)

        click_back_preview(pyautogui)                                 # 3. 返回预览
        time.sleep(1.6)
        pyautogui.moveTo(*GAZE_SQL, duration=0.7); time.sleep(1.0)

        cc(CANCEL)                                                    # 4. 关预览回编辑器
        time.sleep(1.2)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
