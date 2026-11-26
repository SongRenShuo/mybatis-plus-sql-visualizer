# -*- coding: utf-8 -*-
"""T-PerfSweep33 券01 弹窗链沙盒验收（A1 贴入识别后台化 + A2 编辑不被冲掉）。

经 record/_run_elev.bat 提权运行（沙盒 IDE 是提权进程，UIPI 拦非提权输入）。
证据帧落 build/MPSV-PerfSweep33-UI-shots/。每步打印判定行，末尾打 SCENE_OK/SCENE_FAIL。
"""
import ctypes
import io
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pyautogui
import pyperclip

from robot import click_component, find_components, require_component

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.12
ctypes.windll.shcore.SetProcessDpiAwareness(2)

u32 = ctypes.windll.user32
SHOT = r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\.worktrees\t-perfsweep33\build\MPSV-PerfSweep33-UI-shots"
FIXTURE_PATH = r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\record\perf33-ambiguous-fragment.xml"
os.makedirs(SHOT, exist_ok=True)
FAILS = []


def shot(tag):
    p = os.path.join(SHOT, "img_%s.png" % tag)
    pyautogui.screenshot(p)
    print("SHOT", tag)
    return p


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


def dump_windows(tag):
    res = []
    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(h, _):
        buf = ctypes.create_unicode_buffer(256)
        u32.GetWindowTextW(h, buf, 256)
        if u32.IsWindowVisible(h) and buf.value.strip():
            res.append(buf.value)
        return True
    u32.EnumWindows(cb, None)
    print("WINDOWS[%s]:" % tag, " || ".join(res[:14]))


def ensure_front_fullscreen():
    hwnd, title = find_ide_window()
    if not hwnd:
        check("找到沙盒 IDE 主窗", False)
        return False
    u32.ShowWindow(hwnd, 3)  # SW_MAXIMIZE = 全屏化
    time.sleep(0.4)
    u32.SetWindowPos(ctypes.c_void_p(hwnd), ctypes.c_void_p(-1), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010)
    time.sleep(0.3)
    ex = u32.GetWindowLongW(hwnd, -20)
    u32.keybd_event(0x12, 0, 0, 0)
    u32.keybd_event(0x12, 0, 2, 0)
    u32.SetForegroundWindow(hwnd)
    time.sleep(0.6)
    fg = u32.GetForegroundWindow()
    check("IDE 全屏化+TOPMOST+前台", (ex & 0x8) != 0 and fg == hwnd,
          "ex=%x fgmatch=%s" % (ex & 0x8, fg == hwnd))
    return True


def try_click(xpath, tag):
    try:
        ok = click_component(xpath)
        print("CLICK", tag, "OK" if ok else "MISS")
        return ok
    except Exception as e:
        print("CLICK", tag, "ERR", repr(e)[:120])
        return False


def find_dialog_windows():
    res = []
    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(h, _):
        buf = ctypes.create_unicode_buffer(256)
        u32.GetWindowTextW(h, buf, 256)
        r = ctypes.wintypes.RECT()
        if u32.IsWindowVisible(h) and buf.value.strip() and u32.GetWindowRect(h, ctypes.byref(r)):
            res.append((h, buf.value, (r.left, r.top, r.right, r.bottom)))
        return True
    u32.EnumWindows(cb, None)
    return res


def click_dialog_editor_center():
    """弹窗内编辑器取点：弹窗 rect（600~1600 宽视口过滤）中心偏上，按钮行在 bottom-39 之上。"""
    import ctypes.wintypes  # noqa: F401
    cands = [w for w in find_dialog_windows()
             if w[1] and ("SQL" in w[1] or "预览" in w[1])
             and (w[2][2] - w[2][0]) >= 600 and (w[2][2] - w[2][0]) <= 1600]
    print("DIALOG_CANDS:", [(t, r) for _, t, r in cands])
    if not cands:
        return None
    hwnd, title, (l, t, r, b) = cands[0]
    cx = (l + r) // 2
    cy = t + int((b - t) * 0.40)
    pyautogui.click(cx, cy)
    time.sleep(0.4)
    print("CLICK editor center", cx, cy, "title=", title[:40])
    return (cx, cy)


def paste_text(text):
    pyperclip.copy(text)
    time.sleep(0.2)
    pyautogui.hotkey("ctrl", "v")
    time.sleep(0.5)


def main():
    fixture = io.open(FIXTURE_PATH, "r", encoding="utf-8").read()

    if not ensure_front_fullscreen():
        print("SCENE_FAIL")
        return 1
    shot("00_desktop")

    # ---------- A1 前置：开独立工作台（Alt+Shift+P） ----------
    pyautogui.hotkey("alt", "shift", "p")
    time.sleep(3.0)
    shot("01_workbench")

    # ---------- A1：工作台「在弹框中打开」→ 空弹窗（armed） ----------
    opened = try_click("//div[@accessiblename='在弹框中打开']", "在弹框中打开")
    if not opened:
        # 兜底：按钮名带省略号或提示后缀的形态
        cands = find_components("//div[@accessiblename]")
        hits = [c for c in cands if (c.get("accessiblename") or "").find("弹框") >= 0]
        print("CANDS:", [c.get("accessiblename") for c in hits][:6])
        if hits:
            opened = try_click("//div[@accessiblename='%s']" % hits[0]["accessiblename"], "在弹框中打开(兜底)")
    check("A1 点开在弹框中打开", opened)
    time.sleep(2.0)
    dump_windows("after_open_dialog")
    shot("02_dialog_empty")

    # ---------- A1：贴入 70KB 歧义片段 → 立即点复制 SQL（EDT 阳性对照） ----------
    pos = click_dialog_editor_center()
    check("A1 弹窗编辑器落点", pos is not None)
    if not pos:
        print("SCENE_FAIL")
        return 1
    paste_text(fixture)
    shot("03_pasted_big")
    t_paste = time.time()
    copied = try_click("//div[@accessiblename='复制 SQL']", "复制SQL(阳性对照)")
    check("A1 阳性对照-解析待命期点击复制 SQL", copied)
    time.sleep(1.2)
    shot("04_toast_during_parse")

    # ---------- A1：静默后自动识别完成 ----------
    time.sleep(2.5)
    shot("05_after_reparse")
    # 判据在主控侧做像素比对与日志取证，这里记录时序
    print("A1_TIMING paste_at=%.3f" % t_paste)

    # ---------- A2：新弹窗 → 贴入后立即编辑 → 不被冲掉 ----------
    try_click("//div[@accessiblename='关闭']", "关闭弹窗A1")
    time.sleep(1.0)
    opened2 = try_click("//div[@accessiblename='在弹框中打开']", "在弹框中打开(A2)")
    check("A2 重开弹窗", opened2)
    time.sleep(2.0)
    pos2 = click_dialog_editor_center()
    check("A2 弹窗编辑器落点", pos2 is not None)
    if pos2:
        paste_text("SELECT 1 FROM DUAL")
        shot("06_a2_first_paste")
        time.sleep(0.25)  # 400ms 轮询间隔内立即追加编辑
        paste_text("X")
        shot("07_a2_edited")
        # 编辑窗内（<0.9s）不应触发重解析覆盖；静默 2 拍后识别消耗的是**最终文本** DUALX
        time.sleep(3.2)
        shot("08_a2_after_reparse")
        pyperclip.copy("MARKER-READONLY")  # 清剪贴板语义污染

    print("SCENE_%s fails=%s" % ("OK" if not FAILS else "FAIL", FAILS))
    return 0 if not FAILS else 1


if __name__ == "__main__":
    sys.exit(main())
