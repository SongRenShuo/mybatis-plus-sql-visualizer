# -*- coding: utf-8 -*-
"""Enumerate ALL windows of a PID (visible and hidden). Usage: scene_imgR_enumwins.py <PID>"""
import ctypes, sys
from ctypes import wintypes
ctypes.windll.shcore.SetProcessDpiAwareness(2)
u32 = ctypes.windll.user32
ENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)
PID = int(sys.argv[1])
found = []
def cb(hwnd, _lp):
    pid = wintypes.DWORD()
    u32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    if pid.value != PID:
        return True
    cls = ctypes.create_unicode_buffer(64)
    u32.GetClassNameW(hwnd, cls, 64)
    n = u32.GetWindowTextLengthW(hwnd)
    buf = ctypes.create_unicode_buffer(n + 2)
    u32.GetWindowTextW(hwnd, buf, n + 2)
    r = wintypes.RECT()
    u32.GetWindowRect(hwnd, ctypes.byref(r))
    vis = u32.IsWindowVisible(hwnd)
    found.append((hwnd, cls.value, buf.value[:60], bool(vis), (r.left, r.top, r.right, r.bottom)))
    return True
u32.EnumWindows(ENUMPROC(cb), 0)
for f in found:
    print(f)
print("total:", len(found))
