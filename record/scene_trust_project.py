# -*- coding: utf-8 -*-
"""Find the sandbox IDE 'Trust and Open Project' dialog and click the blue
'Trust Project' button. All coordinates are computed from pixels, no manual
guessing. Run elevated (UIPI: IDE is an elevated process).
"""
import ctypes
import subprocess
import time
from ctypes import wintypes

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import numpy as np
import PIL.Image
import pyautogui

pyautogui.FAILSAFE = False

u32 = ctypes.windll.user32
ENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)


def is_java_pid(pid):
    try:
        out = subprocess.check_output(
            ["tasklist", "/FI", "PID eq %d" % pid, "/FO", "CSV", "/NH"],
            stderr=subprocess.STDOUT, timeout=15)
        return b"java.exe" in out
    except Exception:
        return False


def find_dialog():
    found = []

    def cb(hwnd, _lp):
        if not u32.IsWindowVisible(hwnd):
            return True
        cls = ctypes.create_unicode_buffer(64)
        u32.GetClassNameW(hwnd, cls, 64)
        if cls.value != "SunAwtDialog":
            return True
        pid = wintypes.DWORD()
        u32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        r = wintypes.RECT()
        u32.GetWindowRect(hwnd, ctypes.byref(r))
        w, h = r.right - r.left, r.bottom - r.top
        # dialog is roughly 600x200 physical px
        if 400 < w < 900 and 120 < h < 400 and is_java_pid(pid.value):
            found.append((hwnd, pid.value, (r.left, r.top, r.right, r.bottom)))
        return True

    u32.EnumWindows(ENUMPROC(cb), 0)
    return found


def click_blue_button(rect):
    l, t, r, b = rect
    with mss.mss() as sct:
        img = sct.grab({"left": l, "top": t, "width": r - l, "height": b - t})
    arr = np.asarray(PIL.Image.frombytes("RGB", img.size, img.rgb), dtype=int)
    # search the lower half for the blue accent button
    region = arr[(b - t) // 2:, :, :]
    rr, gg, bb = region[:, :, 0], region[:, :, 1], region[:, :, 2]
    mask = (bb - rr > 70) & (bb - gg > 40) & (bb > 150)
    ys, xs = np.nonzero(mask)
    if len(ys) == 0:
        return None
    # the blue blob is one contiguous button; take its center
    cx = int(np.mean(xs)) + l
    cy = int(np.mean(ys)) + t + (b - t) // 2
    return cx, cy


def main():
    dialogs = find_dialog()
    print("dialogs:", dialogs)
    if not dialogs:
        print("NO_TRUST_DIALOG")
        return 2
    hwnd, pid, rect = dialogs[0]
    pos = click_blue_button(rect)
    print("blue button at:", pos)
    if pos is None:
        # dump the dialog for diagnosis
        l, t, r, b = rect
        with mss.mss() as sct:
            img = sct.grab({"left": l, "top": t, "width": r - l, "height": b - t})
            mss.tools.to_png(img.rgb, img.size, output="record/perf/trust_dialog.png")
        print("NO_BLUE_BUTTON")
        return 3
    # foreground the dialog first, then click twice (first click may be swallowed)
    u32.SetForegroundWindow(wintypes.HWND(hwnd))
    time.sleep(0.5)
    pyautogui.click(pos[0], pos[1])
    time.sleep(1.2)
    pyautogui.click(pos[0], pos[1])
    time.sleep(2.0)
    print("CLICKED", pos)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
