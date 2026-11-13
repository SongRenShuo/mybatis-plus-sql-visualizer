# -*- coding: utf-8 -*-
"""Perf acceptance scene 2: inside the preview dialog SQL editor -> edit a column name -> trigger completion.

Run from an ELEVATED shell.
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


def find_dialog():
    for hwnd, pid, title, rect in windows():
        if "SQL Visualizer" in title:
            return hwnd, pid, title, rect
    return None, None, None, None


def shot(name):
    with mss.mss() as sct:
        img = sct.grab(sct.monitors[1])
        mss.tools.to_png(img.rgb, img.size, output=os.path.join(SHOTS, name))
    print("shot ->", name)


def main():
    hwnd, pid, title, rect = find_dialog()
    print("dialog:", hwnd, title, rect)
    if not hwnd:
        print("NO_DIALOG")
        return 2
    l, t, r, b = rect
    # DEL_FLAG line in the rendered SQL (user scenario); double click selects the whole word
    cx = int(l + (r - l) * 0.33)
    cy = int(t + (b - t) * 0.81)
    print("click editor at", cx, cy)
    pyautogui.click(cx, cy)
    time.sleep(0.8)
    # keyboard-only navigation to line 21 (DEL_FLAG): Ctrl+Home then 20x Down then End
    pyautogui.hotkey("ctrl", "home")
    time.sleep(0.3)
    for _ in range(20):
        pyautogui.press("down")
        time.sleep(0.03)
    pyautogui.press("end")
    time.sleep(0.3)
    shot("p4a-caret-line.png")
    pyautogui.hotkey("ctrl", "shift", "left")  # select the word under caret
    time.sleep(0.4)
    pyautogui.typewrite("DEL_F", interval=0.06)
    time.sleep(1.2)
    shot("p4b-typed-prefix.png")
    pyautogui.hotkey("ctrl", "space")
    time.sleep(2.5)
    shot("p5-after-completion.png")
    print("DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
