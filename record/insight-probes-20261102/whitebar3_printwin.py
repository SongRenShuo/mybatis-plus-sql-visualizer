# -*- coding: utf-8 -*-
"""PrintWindow 直取被遮挡窗口内容（PW_RENDERFULLCONTENT=2）。"""
import ctypes
from ctypes import wintypes
import os, sys

from PIL import Image

u32 = ctypes.windll.user32
g32 = ctypes.windll.gdi32
u32.SetProcessDPIAware()


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [("biSize", wintypes.DWORD), ("biWidth", wintypes.LONG), ("biHeight", wintypes.LONG),
                ("biPlanes", wintypes.WORD), ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD),
                ("biSizeImage", wintypes.DWORD), ("biXPelsPerMeter", wintypes.LONG),
                ("biYPelsPerMeter", wintypes.LONG), ("biClrUsed", wintypes.DWORD), ("biClrImportant", wintypes.DWORD)]


def capture(hwnd, path):
    r = wintypes.RECT()
    u32.GetWindowRect(hwnd, ctypes.byref(r))
    w, h = r.right - r.left, r.bottom - r.top
    hdc = u32.GetWindowDC(hwnd)
    mdc = g32.CreateCompatibleDC(hdc)
    bmp = g32.CreateCompatibleBitmap(hdc, w, h)
    g32.SelectObject(mdc, bmp)
    ok = u32.PrintWindow(hwnd, mdc, 2)
    bi = BITMAPINFOHEADER()
    bi.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bi.biWidth = w
    bi.biHeight = -h
    bi.biPlanes = 1
    bi.biBitCount = 32
    buf = ctypes.create_string_buffer(w * h * 4)
    got = g32.GetDIBits(mdc, bmp, 0, h, buf, ctypes.byref(bi), 0)
    img = Image.frombuffer("RGB", (w, h), buf, "raw", "BGRX", 0, 1)
    img.save(path)
    g32.DeleteObject(bmp)
    g32.DeleteDC(mdc)
    u32.ReleaseDC(hwnd, hdc)
    return ok, got, (r.left, r.top, r.right, r.bottom), (w, h)


if __name__ == "__main__":
    hwnd = int(sys.argv[1])
    out = sys.argv[2]
    print(capture(hwnd, out))
