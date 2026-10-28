# -*- coding: utf-8 -*-
"""枚举可见顶层窗口（标题+rect），录制前核对沙盒窗口与置顶覆盖层。"""
import ctypes
from ctypes import wintypes
import sys

ctypes.windll.shcore.SetProcessDpiAwareness(2)
u32 = ctypes.windll.user32

ENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)
rows = []

def cb(hwnd, lparam):
    if not u32.IsWindowVisible(hwnd):
        return True
    length = u32.GetWindowTextLengthW(hwnd)
    if length == 0:
        return True
    buf = ctypes.create_unicode_buffer(length + 1)
    u32.GetWindowTextW(hwnd, buf, length + 1)
    r = wintypes.RECT()
    u32.GetWindowRect(hwnd, ctypes.byref(r))
    ex = u32.GetWindowLongW(hwnd, -20)  # GWL_EXSTYLE
    topmost = bool(ex & 8)
    rows.append((hwnd, buf.value, r.left, r.top, r.right - r.left, r.bottom - r.top, topmost))
    return True

u32.EnumWindows(ENUMPROC(cb), 0)
kw = sys.argv[1] if len(sys.argv) > 1 else ""
for hwnd, title, l, t, w, h, tm in rows:
    if kw and kw.lower() not in title.lower():
        continue
    print(f"hwnd={hwnd} topmost={tm} rect=({l},{t},{w}x{h}) title={title!r}")
