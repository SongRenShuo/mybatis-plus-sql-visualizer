# -*- coding: utf-8 -*-
"""S2-8 真机验收公共件：窗口归一、截图、robot 组件定位。"""
import ctypes, ctypes.wintypes as w, os, time

try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    pass

import pyautogui
pyautogui.FAILSAFE = False

SHOTS = r"F:/code/Java/Gitee/SongRenShuo/mpsv-sql-insight/build/MPSV-RealMachine-shots"
u32 = ctypes.windll.user32

EnumProc = ctypes.WINFUNCTYPE(ctypes.c_bool, w.HWND, w.LPARAM)


def windows():
    out = []

    def cb(h, l):
        if not u32.IsWindowVisible(h):
            return True
        n = u32.GetWindowTextLengthW(h)
        if n == 0:
            return True
        b = ctypes.create_unicode_buffer(n + 1)
        u32.GetWindowTextW(h, b, n + 1)
        r = w.RECT()
        u32.GetWindowRect(h, ctypes.byref(r))
        pid = w.DWORD()
        u32.GetWindowThreadProcessId(h, ctypes.byref(pid))
        out.append((b.value, pid.value, h, (r.left, r.top, r.right, r.bottom)))
        return True

    u32.EnumWindows(EnumProc(cb), 0)
    return out


def ide_window(match="mybatis-plus-sql-visualizer-demo"):
    cands = [x for x in windows() if match in x[0]]
    if not cands:
        raise SystemExit("NO_IDE_WINDOW")
    return cands[0]


def front(hwnd=None):
    """置前：不用 Alt 技巧（单按 Alt 会弹出窗口系统菜单，吃掉后续按键）。"""
    if hwnd is None:
        hwnd = ide_window()[2]
    if u32.GetForegroundWindow() != hwnd:
        k32 = ctypes.windll.kernel32
        fg = u32.GetForegroundWindow()
        tid_fg = u32.GetWindowThreadProcessId(fg, None) if fg else 0
        tid_self = k32.GetCurrentThreadId()
        u32.AttachThreadInput(tid_self, tid_fg, True)
        u32.BringWindowToTop(hwnd)
        u32.SetForegroundWindow(hwnd)
        u32.AttachThreadInput(tid_self, tid_fg, False)
    time.sleep(0.3)
    u32.SetWindowPos(hwnd, -1, 0, 0, 0, 0, 0x2 | 0x1)  # TOPMOST, NOSIZE|NOMOVE
    time.sleep(0.2)
    u32.SetWindowPos(hwnd, -2, 0, 0, 0, 0, 0x2 | 0x1)  # NOTOPMOST
    fg = u32.GetForegroundWindow()
    return fg == hwnd


def maximize(hwnd=None):
    if hwnd is None:
        hwnd = ide_window()[2]
    u32.ShowWindow(hwnd, 3)  # SW_MAXIMIZE
    time.sleep(0.5)


def shot(name):
    os.makedirs(SHOTS, exist_ok=True)
    p = os.path.join(SHOTS, name if name.endswith(".png") else name + ".png")
    pyautogui.screenshot().save(p)
    return p


def rect_of(hwnd):
    r = w.RECT()
    u32.GetWindowRect(hwnd, ctypes.byref(r))
    return (r.left, r.top, r.right, r.bottom)


if __name__ == "__main__":
    t, pid, h, r = ide_window()
    print("title=%r pid=%s hwnd=%s rect=%s" % (t, pid, h, r))
    print("front ok:", front(h))
