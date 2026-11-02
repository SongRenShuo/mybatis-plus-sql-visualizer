# -*- coding: utf-8 -*-
"""把沙盒 IDE 置前（含无标题窗口），并最小化误开的浏览器窗口。"""
import ctypes
from ctypes import wintypes

u32 = ctypes.windll.user32
u32.SetProcessDPIAware()

ENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)


def all_windows():
    out = []

    def cb(hwnd, _l):
        if not u32.IsWindowVisible(hwnd):
            return True
        n = u32.GetWindowTextLengthW(hwnd)
        buf = ctypes.create_unicode_buffer(n + 2)
        u32.GetWindowTextW(hwnd, buf, n + 2)
        pid = wintypes.DWORD(0)
        u32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        r = wintypes.RECT()
        u32.GetWindowRect(hwnd, ctypes.byref(r))
        out.append((int(hwnd), int(pid.value), buf.value, (r.left, r.top, r.right, r.bottom)))
        return True

    u32.EnumWindows(ENUMPROC(cb), 0)
    return out


def minimize(hwnd):
    u32.ShowWindow(hwnd, 6)  # SW_MINIMIZE


def foreground(hwnd):
    u32.ShowWindow(hwnd, 9)  # SW_RESTORE
    # Alt 按放破解前台锁
    u32.keybd_event(0x12, 0, 0, 0)
    u32.keybd_event(0x12, 0, 2, 0)
    return bool(u32.SetForegroundWindow(hwnd))


def by_pid(pid):
    return [w for w in all_windows() if w[1] == pid]
