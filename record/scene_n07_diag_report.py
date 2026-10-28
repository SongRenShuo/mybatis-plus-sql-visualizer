# -*- coding: utf-8 -*-
"""N07 一键诊断报告重录（T-DemoGifFix1026 批3）。

复核判定（20261026）：旧片五段式报告本体全程不可见（只演示了菜单与保存框）。

新分镜：
  1. 预览定格 → 更多 → 分享 → 复制诊断报告（Markdown）→ 确定（复制通知）
  2. 更多 → 分享 → 导出诊断报告（HTML）→ 确定 → FileChooser 粘贴绝对路径保存
     （两步分享都在预览开时完成——浮动预览弹窗会盖住后续画面）
  3. 关预览 → Find Action → Scratch File（**Enter 直接建默认 markdown scratch，
     无语言弹窗——20261027 实测，别再贴过滤词，会打进文件**）→ Ctrl+V 贴报告
     → PageDown 扫五段
  4. Chrome 打开 HTML（启动后按标题 EnumWindows → MoveWindow 精确压到录制区，
     已有 chrome 实例会无视 --window-position）→ 渲染后的报告可见 → 定格收尾

坐标（20261027 实测，region 相对；abs = +285,+26）：
  更多 (1240,203)｜分享项 (1233,318)｜复制诊断报告 MD (505,530)｜导出诊断报告 HTML (505,559)
  分享对话框确定 (744,646)
"""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import pyautogui  # noqa: E402
import gifkit  # noqa: E402
from gifkit import Recorder, shot  # noqa: E402
from robot import find_components  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "n07_diag_report.mp4")
OX, OY = REGION[0], REGION[1]
HTML_PATH = os.path.join(HERE, "perf", "diag-report.html")
HTML_URL = "file:///" + HTML_PATH.replace("\\", "/")


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


MORE = (1240, 203)
ITEM_SHARE = (1194, 310)      # 「分享」菜单项（20261027 帧 45 实测：菜单项 x≈1180-1210，分享第 4 项）
DIAG_MD = (505, 530)
DIAG_HTML = (505, 559)
OK_BTN = (744, 646)


def preview_open() -> bool:
    return len(find_components("//div[@accessiblename='SQL Visualizer - SQL 预览']")) > 0


def hover_click(pt, hover_wait=0.35, after=0.8):
    sx, sy = abs_pt(pt)
    pyautogui.moveTo(sx, sy, duration=0.3)
    time.sleep(hover_wait)
    pyautogui.click(sx, sy)
    time.sleep(after)


def share_pick(dialog_item_pt, after=1.2):
    """更多菜单 → 「分享」（像素点；⚠️ 本下拉菜单的 ActionMenuItem 在 robot
    组件树里查不到——a15 的编辑器右键菜单才可见，闸门 4 不适用此处）
    → 分享对话框选出口。失败重试一次（菜单没开/点空时 Esc 收掉重开）。"""
    for attempt in range(2):
        hover_click(MORE, after=1.0)
        hover_click(ITEM_SHARE, hover_wait=0.6, after=1.2)
        if find_components("//div[@accessiblename='分享 SQL']"):
            break
        pyautogui.press("esc")
        time.sleep(0.5)
        pyautogui.press("esc")
        time.sleep(0.5)
    else:
        raise RuntimeError("分享对话框未打开（重试 2 次失败），中止")
    hover_click(dialog_item_pt, after=0.5)
    hover_click(OK_BTN, after=after)


def move_chrome_over_region():
    """按标题找 diag-report 的 chrome 窗口并压到录制区（chrome 已在跑时
    --window-position 会被无视，必须事后 MoveWindow）。"""
    import ctypes
    import ctypes.wintypes as wt
    u32 = ctypes.windll.user32
    hit = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(h, _):
        if u32.IsWindowVisible(h):
            n = u32.GetWindowTextLengthW(h)
            if n > 0:
                buf = ctypes.create_unicode_buffer(n + 1)
                u32.GetWindowTextW(h, buf, n + 1)
                if "诊断报告" in buf.value or "diag-report" in buf.value:
                    hit.append((h, buf.value))
        return True
    u32.EnumWindows(cb, None)
    if not hit:
        return False
    hwnd = hit[0][0]
    u32.MoveWindow(ctypes.c_void_p(hwnd), 277, 0, 1386, 1024, True)
    time.sleep(0.4)
    u32.keybd_event(0x12, 0, 0, 0)
    u32.keybd_event(0x12, 0, 2, 0)
    u32.SetForegroundWindow(ctypes.c_void_p(hwnd))
    time.sleep(0.5)
    pyautogui.press("esc")          # 收可能被 Alt 激活的菜单
    time.sleep(0.3)
    return True


def ensure_ide_foreground():
    """IDE 不在前台时首击会被「点击聚焦」吞掉（坑13）——先夺前台。"""
    import ctypes
    u32 = ctypes.windll.user32
    hit = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(h, _):
        if u32.IsWindowVisible(h):
            n = u32.GetWindowTextLengthW(h)
            if n > 0:
                buf = ctypes.create_unicode_buffer(n + 1)
                u32.GetWindowTextW(h, buf, n + 1)
                if "mybatis-plus-sql-visualizer-demo" in buf.value:
                    hit.append(h)
        return True
    u32.EnumWindows(cb, None)
    if not hit:
        raise RuntimeError("找不到 IDE 主窗口")
    hwnd = hit[0]
    if u32.GetForegroundWindow() == hwnd:
        return
    u32.keybd_event(0x12, 0, 0, 0)
    u32.keybd_event(0x12, 0, 2, 0)
    u32.SetForegroundWindow(ctypes.c_void_p(hwnd))
    time.sleep(0.6)
    pyautogui.press("esc")          # 收可能被 Alt 激活的菜单
    time.sleep(0.3)


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    ensure_ide_foreground()

    from nav import trigger_preview
    if not preview_open():
        print("预览未开，自动唤起…")
        trigger_preview(wait=3.4)
        if not preview_open():
            raise RuntimeError("前置未满足：预览唤起失败")

    r = Recorder(REGION, OUT)
    r.start()
    try:
        time.sleep(0.8)                                   # 1. 预览态定格

        # 2. 复制诊断报告（Markdown）
        share_pick(DIAG_MD, after=1.0)                    # 复制通知定格

        # 3. 导出诊断报告（HTML）→ FileChooser 粘贴绝对路径 → 保存
        #    复制通知还在画面上就直接开第二次分享（省一拍静置）
        share_pick(DIAG_HTML, after=1.4)
        gifkit.paste(HTML_PATH)
        time.sleep(0.6)
        pyautogui.keyDown("return"); pyautogui.keyUp("return")   # 保存（只按一次，坑59）
        # ⚠️ 保存后必须等 FileChooser 完全关闭再发任何键——它还开着时后续按键
        # 全部落进对话框（20261027 实测：报告被 Ctrl+V 粘进 UserMapper.xml，
        # 闸门 0 抓到）。chooser 关闭用「编辑器画面回归」判据太脆，直接给足 3s。
        time.sleep(3.0)

        # 4. 关预览（给浏览器画面让位；也让尾段聚焦在报告本体上）
        hover_click((1212, 798), after=0.8)               # 预览「取消」
        time.sleep(0.6)

        # 5. Chrome --app 打开 HTML 并压到录制区（渲染后的报告各段可见；
        #    --app 无地址栏，不暴露本机路径）
        subprocess.Popen([
            "cmd", "/c", "start", "chrome", "--app=" + HTML_URL,
        ], shell=False)
        time.sleep(2.8)
        if not move_chrome_over_region():
            raise RuntimeError("未找到 diag-report 的 Chrome 窗口，中止")
        time.sleep(1.8)                                   # 渲染定格
        shot(REGION, os.path.join(HERE, "perf", "n07_final.png"))
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    main()
