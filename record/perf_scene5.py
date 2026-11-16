# -*- coding: utf-8 -*-
"""Perf acceptance scene 5: lowercase input must insert/propose snake_case (del_f -> del_flag).

Run from an ELEVATED shell, after the preview dialog is open on CustChannelMapper.xml.
Uses scene3 as the opener, then re-edits line 21 with lowercase prefix and accepts the first item.
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
    dlg, dtitle, drect = find("SQL Visualizer")
    print("dialog:", dlg, dtitle, drect)
    if not dlg:
        print("NO_DIALOG")
        return 2
    U32.SetForegroundWindow(ctypes.c_void_p(dlg))
    time.sleep(0.5)
    l, t, r, b = drect
    pyautogui.click(int(l + (r - l) * 0.33), int(t + (b - t) * 0.81))
    time.sleep(0.6)
    pyautogui.hotkey("ctrl", "home")
    time.sleep(0.3)
    for _ in range(20):
        pyautogui.press("down")
        time.sleep(0.03)
    pyautogui.press("end")
    time.sleep(0.3)
    # select current word and replace with lowercase prefix
    pyautogui.hotkey("ctrl", "shift", "left")
    time.sleep(0.3)
    for ch in "del_f":
        pyautogui.typewrite(ch, interval=0.02)
        time.sleep(0.45)
    time.sleep(1.2)
    shot("s1-lowercase-popup.png")
    pyautogui.press("enter")  # accept first item
    time.sleep(1.0)
    shot("s2-lowercase-accepted.png")
    print("DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
