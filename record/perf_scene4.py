# -*- coding: utf-8 -*-
"""Perf acceptance scene 4: measure steady-state preview open latency (open -> Esc -> open).

Run from an ELEVATED shell. Scenario 1/3 must have run before (mapper file open, caret on statement).
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
pyautogui.PAUSE = 0.03
U32 = ctypes.windll.user32
SHOTS = r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\build\MPSV-E2EAccept-shots"
os.makedirs(SHOTS, exist_ok=True)


def shot(name):
    with mss.mss() as sct:
        img = sct.grab(sct.monitors[1])
        mss.tools.to_png(img.rgb, img.size, output=os.path.join(SHOTS, name))
    print("shot ->", name)


def find(fragment):
    for hwnd, pid, title, rect in windows():
        if fragment in title:
            return hwnd, title, rect
    return None, None, None


def main():
    hwnd, title, rect = find("cust-big-demo")
    if not hwnd:
        print("NO_IDE")
        return 2
    U32.keybd_event(0x12, 0, 0, 0)
    U32.keybd_event(0x12, 0, 2, 0)
    U32.SetForegroundWindow(ctypes.c_void_p(hwnd))
    time.sleep(0.8)

    # close any dialog left open by earlier scenes
    dlg, dtitle, drect = find("SQL Visualizer")
    if dlg:
        U32.SetForegroundWindow(ctypes.c_void_p(dlg))
        time.sleep(0.3)
        pyautogui.press("esc")
        time.sleep(1.0)

    for i in (1, 2, 3):
        pyautogui.hotkey("alt", "shift", "q")
        time.sleep(4.0)
        shot("r%d-open.png" % i)
        pyautogui.press("esc")
        time.sleep(1.5)
    print("DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
