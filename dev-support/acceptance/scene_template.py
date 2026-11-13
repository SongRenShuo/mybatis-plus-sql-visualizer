# -*- coding: utf-8 -*-
"""场景脚本模板：复制本文件为 scene_<任务>.py，填常量与动作序列。

硬规则（违反必返工）：
- 自包含就位：open_file/goto_line/图标检测全在 Recorder.start() 之前；
  take 中途只留演示动作（外部鼠标/焦点扰动会让漂移混进成片）
- **开录前先自检**（Guard.require）：浮层已清 / 目标面板确实开着 / 停在预期页——不符就中止，别录坏画面
- **每次点击后验变化**（Guard.after_click）：无可见变化即判落空（坐标偏 / 子窗口吞首击 / 上游开关置灰）
- **坐标别目测**：弹窗按钮行用 locate.button_row_y（底边−39）；对话框/浮窗用 locate.window_rect 按 PID 取；
  亮文字带用 locate.bright_band_y。本构图的现成数值查项目侧 record/BASELINE.md，算完回填
- **take 内必须有真实 UI 变化**：裸屏捕获不含光标，纯凝视的鼠标移动会被静段裁剪整段吃掉
- 每个分镜动作先手动戳一遍验证真实存在，别照文档摆拍
- 录制期系统 IME 切英文态
- 循环无缝：结尾回跳起始行或 undo 还原现场
- 转制后核对 assemble decoded 帧数 == Recorder 打印的 frames

用法：
  python scene_xxx.py
"""
import sys
import time

from gifkit import (
    Recorder, click, find_gutter_icons, goto_line, move, open_file,
    pyautogui, shot, typewrite, wait_stable,
)
from locate import bright_band_y, button_click_point, window_rect
from verify import Guard

# --- 常量区：换构图全部重标（先查 record/BASELINE.md）---------------------
REGION = (277, 25, 1370, 984)     # 录制区=窗口外框（物理像素）
OUT = "perf/<任务名>.mp4"          # 无损中间产物

# GUTTER = (x, y)               # 写死坐标仅限演示滑动类；图标类一律动态检测
# CHK_XXX = (x, y)              # 弹窗内控件坐标必须弹窗打开态现标
CANCEL = (1485, 827)              # 弹窗「取消」（基线构图值）

WATCH = (367, 166, 1190, 702)     # 自检观察区＝目标弹窗矩形（随内容变，用 window_rect 取）


def locate_gutter():
    """gutter 图标像素检测：窄 y_range 直取，或取最接近视口中心者。

    find_gutter_icons 返回 region 相对坐标，点击前 +origin(277,25)。
    """
    path = "perf/_pre.png"
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(150, 700), gap=12)
    if not icons:
        raise RuntimeError("gutter icon not found")
    center = (150 + 700) / 2
    y, x = min(icons, key=lambda p: abs(p[0] - center))
    print("icons:", icons, "-> picked", (y, x))
    return x + REGION[0], y + REGION[1]


def locate_popup_button(x_in_window, pid=None, title=None):
    """弹窗按钮的屏幕点击点：矩形按 PID 取，y 按底边−39 算（别目测）。"""
    rect = window_rect(title=title, pid=pid, pick="smallest", min_size=(600, 400))
    if rect is None:
        raise RuntimeError("未找到弹窗（window_rect 返回 None）")
    print("popup rect:", rect, "-> button row y:", rect[3] - 39)
    return button_click_point(rect, x_in_window)


def main():
    # --- 就位（录屏外）：开文件、跳行、定位图标 -------------------------
    # open_file("路径关键字")   # ctrl+shift+n 搜索 + 双击首行，先 shot 核对首行即目标
    # goto_line(N)
    gutter = locate_gutter()

    # --- 开录前自检：不符就中止（AssertionError 会让脚本直接失败）--------
    g = Guard()
    g.mark("target", region=WATCH)          # 记下「就该长这样」的基准
    # g.require("target", msg="目标面板未开/被浮层遮挡/停错页")

    # --- 录制 -----------------------------------------------------------
    r = Recorder(REGION, OUT)
    time.sleep(1.2)                  # 让前一操作画面稳定
    r.start()
    time.sleep(0.5)

    move(*[gutter[0] - 40, gutter[1] + 60], dur=0.3)   # 演示滑动（可选，不点击）
    # 动作 1：点击 + 当场验变化（落空即抛错，别等录完才发现）
    g.after_click(gutter, name="target", region=WATCH, msg="gutter 点击落空")
    w = wait_stable(REGION)          # 弹窗稳定（超时返回 -1，查产物别静默）
    print("popup stable wait:", w)
    time.sleep(1.5)                  # 关键结果定格 1.5~2s

    # 动作 2..N：点击/勾选/键入（Enter 用 keyDown/keyUp('return')）
    # g.after_click(CHK_XXX, name="target", region=WATCH, msg="勾选落空"); time.sleep(1.8)

    # --- 收尾：关闭弹窗 / undo 还原现场 / 回跳起始行 ---------------------
    # 收尾鼠标停「无浮层空白区」（预览左栏 / 原生控制台），别停代码（弹文档浮层）也别停结果网格
    # click(*CANCEL, move_dur=0.3)
    time.sleep(0.9)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
