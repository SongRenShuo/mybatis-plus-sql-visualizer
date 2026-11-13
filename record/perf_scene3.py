# -*- coding: utf-8 -*-
"""Perf acceptance scene 3: open CustChannelMapper.xml (user's file) -> preview -> edit DEL_FLAG -> DEL_F (autopopup only).

Run from an ELEVATED shell. No Ctrl+Space (system IME swallows it); rely on autopopup while typing.
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


def find_by_title(fragment):
    for hwnd, pid, title, rect in windows():
        if fragment in title:
            return hwnd, pid, title, rect
    return None, None, None, None


def main():
    hwnd, pid, title, rect = find_by_title("cust-big-demo")
    print("ide:", hwnd, title[:70] if title else None)
    if not hwnd:
        print("NO_IDE")
        return 2
    sw, sh = U32.GetSystemMetrics(0), U32.GetSystemMetrics(1)
    U32.SetWindowPos(ctypes.c_void_p(hwnd), ctypes.c_void_p(-1), 0, 0, sw, sh, 0x0040 | 0x0001)
    time.sleep(0.5)
    U32.keybd_event(0x12, 0, 0, 0)
    U32.keybd_event(0x12, 0, 2, 0)
    U32.SetForegroundWindow(ctypes.c_void_p(hwnd))
    time.sleep(1.0)

    # 1) open the user's mapper file and trigger preview
    pyautogui.hotkey("ctrl", "shift", "n")
    time.sleep(1.2)
    pyautogui.typewrite("CustChannelMapper.xml", interval=0.03)
    time.sleep(1.5)
    pyautogui.press("enter")
    time.sleep(3.5)
    pyautogui.hotkey("ctrl", "f")
    time.sleep(0.8)
    pyautogui.typewrite("selectByExample", interval=0.03)
    time.sleep(0.5)
    pyautogui.press("enter")
    time.sleep(0.4)
    pyautogui.press("esc")
    time.sleep(0.8)
    pyautogui.hotkey("alt", "shift", "q")
    time.sleep(8.0)
    shot("q1-preview.png")

    # 2) focus the dialog SQL editor, navigate to line 21 (DEL_FLAG)
    dlg, dpid, dtitle, drect = find_by_title("SQL Visualizer")
    print("dialog:", dlg, dtitle, drect)
    if not dlg:
        print("NO_DIALOG")
        return 3
    l, t, r, b = drect
    cx = int(l + (r - l) * 0.33)
    cy = int(t + (b - t) * 0.81)
    pyautogui.click(cx, cy)
    time.sleep(0.8)
    pyautogui.hotkey("ctrl", "home")
    time.sleep(0.3)
    for _ in range(20):
        pyautogui.press("down")
        time.sleep(0.03)
    pyautogui.press("end")
    time.sleep(0.3)
    pyautogui.hotkey("ctrl", "shift", "left")
    time.sleep(0.4)
    shot("q2-word-selected.png")

    # 3) type the prefix char by char (autopopup), no Ctrl+Space
    for ch in "DEL_F":
        pyautogui.typewrite(ch, interval=0.02)
        time.sleep(0.45)
    time.sleep(1.5)
    shot("q3-completion.png")
    print("DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
