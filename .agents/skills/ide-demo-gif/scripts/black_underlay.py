# -*- coding: utf-8 -*-
"""录制辅助：纯黑垫底窗。

窗口 rect 底部 ~9px 是 DWM 阴影/隐形边框区，会透出 z 序在其下方的窗口内容；
本窗垫在沙盒 IDE 窗口正下方（z 序紧随其后），让透出的部分是纯黑，
对齐上一轮成片「底部阴影带为暗色」的构图。窗口自身保持在 z 序中不置顶。

用法：python black_underlay.py [沙盒IDE窗口标题关键字]（默认 mybatis-plus-sql-visualizer-demo）
"""
import ctypes
import sys
import time

import tkinter as tk

X, Y, W, H = 277, 996, 1370, 16
IDE_TITLE_KEY = sys.argv[1] if len(sys.argv) > 1 else "mybatis-plus-sql-visualizer-demo"


def find_ide_hwnd():
    u32 = ctypes.WinDLL("user32", use_last_error=True)
    found = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(h, l):
        if u32.IsWindowVisible(h):
            n = ctypes.create_unicode_buffer(256)
            u32.GetWindowTextW(h, n, 256)
            if IDE_TITLE_KEY in n.value:
                found.append(h)
        return True
    u32.EnumWindows(cb, 0)
    if not found:
        raise RuntimeError(f"IDE window not found by title key: {IDE_TITLE_KEY}")
    return found[0]


def main():
    hwnd = find_ide_hwnd()
    root = tk.Tk()
    root.geometry(f"{W}x{H}+{X}+{Y}")
    root.overrideredirect(True)
    root.config(bg="black")
    root.update_idletasks()
    me = ctypes.windll.user32.GetParent(root.winfo_id()) or root.winfo_id()
    u32 = ctypes.WinDLL("user32", use_last_error=True)
    u32.SetWindowPos.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                 ctypes.c_int, ctypes.c_int,
                                 ctypes.c_int, ctypes.c_int, ctypes.c_uint]
    # 插到沙盒 IDE 窗口正下方（hWndInsertAfter = 沙盒 hwnd）
    u32.SetWindowPos(ctypes.c_void_p(me), ctypes.c_void_p(hwnd),
                     0, 0, 0, 0, 0x0001 | 0x0002)  # NOSIZE | NOMOVE
    print("underlay hwnd:", me, "below IDE hwnd:", hwnd, flush=True)
    while True:
        time.sleep(3600)
        root.update()


if __name__ == "__main__":
    sys.exit(main())
