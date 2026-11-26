# -*- coding: utf-8 -*-
"""T-PerfSweep33 A2 重做：先取消关掉旧弹窗，再开新空弹窗——贴入后立即编辑，识别消耗最终文本。
非提权可跑（本沙盒 IDE 非提权）。证据帧落 build/MPSV-PerfSweep33-UI-shots/。"""
import ctypes
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pyautogui
import pyperclip

from robot import click_component, find_components

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.12
ctypes.windll.shcore.SetProcessDpiAwareness(2)

u32 = ctypes.windll.user32
SHOT = r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\.worktrees\t-perfsweep33\build\MPSV-PerfSweep33-UI-shots"
os.makedirs(SHOT, exist_ok=True)
FAILS = []


def shot(tag):
    p = os.path.join(SHOT, "img_%s.png" % tag)
    pyautogui.screenshot(p)
    print("SHOT", tag)


def check(name, ok, detail=""):
    print("CHECK", "PASS" if ok else "FAIL", name, detail)
    if not ok:
        FAILS.append(name)


def find_ide_window():
    res = []
    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(h, _):
        buf = ctypes.create_unicode_buffer(256)
        u32.GetWindowTextW(h, buf, 256)
        if buf.value and "mybatis-plus-sql-visualizer-demo" in buf.value:
            res.append((h, buf.value))
        return True
    u32.EnumWindows(cb, None)
    return res[0] if res else (None, None)


def ensure_front():
    hwnd, _ = find_ide_window()
    if not hwnd:
        check("找 IDE 主窗", False)
        return False
    u32.SetWindowPos(ctypes.c_void_p(hwnd), ctypes.c_void_p(-1), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010)
    u32.keybd_event(0x12, 0, 0, 0)
    u32.keybd_event(0x12, 0, 2, 0)
    u32.SetForegroundWindow(hwnd)
    time.sleep(0.6)
    return u32.GetForegroundWindow() == hwnd


def try_click(xpath, tag):
    try:
        ok = click_component(xpath)
        print("CLICK", tag, "OK" if ok else "MISS")
        return ok
    except Exception as e:
        print("CLICK", tag, "ERR", repr(e)[:120])
        return False


def paste_text(text, gap_after=0.4):
    pyperclip.copy(text)
    time.sleep(0.15)
    pyautogui.hotkey("ctrl", "v")
    time.sleep(gap_after)


def editor_center():
    """弹窗 rect 中心偏上（复用探针 1 的窗口判定口径）。"""
    import ctypes.wintypes  # noqa: F401
    res = []
    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(h, _):
        buf = ctypes.create_unicode_buffer(256)
        u32.GetWindowTextW(h, buf, 256)
        r = ctypes.wintypes.RECT()
        if u32.IsWindowVisible(h) and buf.value.strip() and u32.GetWindowRect(h, ctypes.byref(r)):
            res.append((buf.value, (r.left, r.top, r.right, r.bottom)))
        return True
    u32.EnumWindows(cb, None)
    cands = [w for w in res if w[0] == "SQL Visualizer - SQL 预览"
             and 600 <= (w[1][2] - w[1][0]) <= 1600]
    if not cands:
        return None
    _, (l, t, r, b) = cands[0]
    cx, cy = (l + r) // 2, t + int((b - t) * 0.40)
    pyautogui.click(cx, cy)
    time.sleep(0.4)
    return (cx, cy)


def main():
    if not ensure_front():
        print("SCENE_FAIL")
        return 1

    # 关闭 A1 遗留弹窗（取消按钮）
    closed = try_click("//div[@accessiblename='取消']", "取消(关 A1 弹窗)")
    time.sleep(1.2)
    shot("10_after_cancel")
    check("A2 关闭遗留弹窗", closed)

    # 重开空弹窗
    opened = try_click("//div[@accessiblename='在弹框中打开']", "在弹框中打开(A2)")
    check("A2 开新空弹窗", opened)
    time.sleep(2.0)
    shot("11_a2_new_dialog")

    pos = editor_center()
    check("A2 编辑器落点", pos is not None)
    if not pos:
        print("SCENE_FAIL")
        return 1

    # 贴入后 250ms 内立即追加编辑（400ms 轮询间隔内文本持续变化）
    paste_text("SELECT 1 FROM DUAL", gap_after=0.25)
    shot("12_a2_first")
    paste_text("X", gap_after=0.3)
    shot("13_a2_edited")
    # 静默 2 拍（~800ms）后识别触发，消耗的应是最终文本 DUALX
    time.sleep(3.2)
    shot("14_a2_after_reparse")
    print("SCENE_%s fails=%s" % ("OK" if not FAILS else "FAIL", FAILS))
    return 0 if not FAILS else 1


if __name__ == "__main__":
    sys.exit(main())
