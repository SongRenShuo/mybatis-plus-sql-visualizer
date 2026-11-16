# -*- coding: utf-8 -*-
"""Perf acceptance scene 1: fullscreen sandbox IDE -> open CustChannelMapper.xml -> trigger preview.

Run from an ELEVATED shell (the sandbox IDE is elevated; SendInput from a non-elevated
process is blocked by UIPI).
"""
import ctypes
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import mss  # noqa: E402
import pyautogui  # noqa: E402
from locate import windows  # noqa: E402

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.02

U32 = ctypes.windll.user32
SHOTS = r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\build\MPSV-E2EAccept-shots"
os.makedirs(SHOTS, exist_ok=True)


def pick_ide():
    for hwnd, pid, title, rect in windows():
        if "cust-big-demo" in title:
            return hwnd, pid, title, rect
    return None, None, None, None


def shot(name):
    with mss.mss() as sct:
        img = sct.grab(sct.monitors[1])
        mss.tools.to_png(img.rgb, img.size, output=os.path.join(SHOTS, name))
    print("shot ->", name)


def main():
    hwnd, pid, title, rect = pick_ide()
    print("ide:", hwnd, pid, title[:70] if title else None, rect)
    if not hwnd:
        print("NO_IDE_WINDOW")
        return 2
    sw, sh = U32.GetSystemMetrics(0), U32.GetSystemMetrics(1)
    # fullscreen + topmost
    U32.SetWindowPos(ctypes.c_void_p(hwnd), ctypes.c_void_p(-1), 0, 0, sw, sh, 0x0040 | 0x0001)
    time.sleep(0.6)
    # alt tap then foreground (foreground lock workaround)
    U32.keybd_event(0x12, 0, 0, 0)
    U32.keybd_event(0x12, 0, 2, 0)
    U32.SetForegroundWindow(ctypes.c_void_p(hwnd))
    time.sleep(1.2)
    shot("p1-fullscreen.png")

    # open the mapper xml
    pyautogui.hotkey("ctrl", "shift", "n")
    time.sleep(1.2)
    pyautogui.typewrite("CustChannelMapper.xml", interval=0.03)
    time.sleep(1.5)
    pyautogui.press("enter")
    time.sleep(4.0)
    shot("p2-file-open.png")

    # put caret on a statement id
    pyautogui.hotkey("ctrl", "f")
    time.sleep(0.8)
    pyautogui.typewrite("selectByExample", interval=0.03)
    time.sleep(0.5)
    pyautogui.press("enter")
    time.sleep(0.4)
    pyautogui.press("esc")
    time.sleep(1.0)

    # trigger preview (Alt+Shift+Q)
    pyautogui.hotkey("alt", "shift", "q")
    time.sleep(9.0)
    shot("p3-preview.png")
    print("DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
