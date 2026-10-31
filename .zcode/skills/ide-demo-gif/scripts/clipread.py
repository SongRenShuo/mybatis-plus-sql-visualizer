# -*- coding: utf-8 -*-
"""剪贴板读取助手（CF_UNICODETEXT）。gifkit 只写不读，录制验证用。"""
import ctypes

_k32 = ctypes.windll.kernel32
_u32 = ctypes.windll.user32
_k32.GlobalAlloc.restype = ctypes.c_void_p
_k32.GlobalAlloc.argtypes = [ctypes.c_uint, ctypes.c_size_t]
_k32.GlobalLock.restype = ctypes.c_void_p
_k32.GlobalLock.argtypes = [ctypes.c_void_p]
_k32.GlobalUnlock.argtypes = [ctypes.c_void_p]
_k32.GlobalSize.restype = ctypes.c_size_t
_k32.GlobalSize.argtypes = [ctypes.c_void_p]
_u32.SetClipboardData.restype = ctypes.c_void_p
_u32.SetClipboardData.argtypes = [ctypes.c_uint, ctypes.c_void_p]
_u32.GetClipboardData.restype = ctypes.c_void_p
_u32.GetClipboardData.argtypes = [ctypes.c_uint]


def read_clip(maxlen=2000):
    text = ""
    if _u32.OpenClipboard(0):
        try:
            h = _u32.GetClipboardData(13)  # CF_UNICODETEXT
            if h:
                p = _k32.GlobalLock(h)
                size = _k32.GlobalSize(h)
                if p and size:
                    text = ctypes.wstring_at(p, min(size // 2 - 1, maxlen))
                    _k32.GlobalUnlock(h)
        finally:
            _u32.CloseClipboard()
    return text


if __name__ == "__main__":
    print(repr(read_clip()))
