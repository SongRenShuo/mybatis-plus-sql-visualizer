# -*- coding: utf-8 -*-
"""E2E 验收探针：打开 mapper XML 的 selectByExample 并触发预览。

用法：python record/e2e_probe.py open|reopen|shot <tag>
坐标全部来自 locate.window_rect / 键盘导航，不落手工换算。
"""
import os
import sys
import time

RECORD_DIR = os.path.dirname(os.path.abspath(__file__))
if RECORD_DIR not in sys.path:
    sys.path.insert(0, RECORD_DIR)

import mss
import pyautogui

from locate import ensure_foreground, windows, close_preview

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.05

MAIN_REPO = os.path.dirname(RECORD_DIR)
SHOTS = os.path.join(MAIN_REPO, "build", "MPSV-E2EAccept-shots")
IDE_PID = int(os.environ.get("E2E_IDE_PID", "69464"))


def ide_hwnd():
    best = None
    for hwnd, pid, title, rect in windows():
        if pid == IDE_PID and title.strip():
            w = rect[2] - rect[0]
            h = rect[3] - rect[1]
            if w > 800 and h > 500:
                if best is None or w * h > (best[3][2] - best[3][0]) * (best[3][3] - best[3][1]):
                    best = (hwnd, pid, title, rect)
    return best


def focus():
    win = ide_hwnd()
    if not win:
        raise SystemExit("sandbox IDE window not found (pid=%s)" % IDE_PID)
    ensure_foreground(win[0])
    time.sleep(0.4)
    return win


def shot(tag):
    os.makedirs(SHOTS, exist_ok=True)
    path = os.path.join(SHOTS, "%s.png" % tag)
    with mss.mss() as s:
        img = s.grab(s.monitors[1])
        mss.tools.to_png(img.rgb, img.size, output=path)
    print("shot ->", path)
    return path


def goto_file(query):
    pyautogui.hotkey("ctrl", "shift", "n")
    time.sleep(1.0)
    pyautogui.typewrite(query, interval=0.02)
    time.sleep(1.2)
    pyautogui.press("enter")
    time.sleep(2.0)


def find_and_escape(text):
    pyautogui.hotkey("ctrl", "f")
    time.sleep(0.6)
    pyautogui.typewrite(text, interval=0.02)
    time.sleep(0.8)
    pyautogui.press("esc")
    time.sleep(0.5)


def trigger_preview():
    pyautogui.hotkey("alt", "shift", "q")


def main(argv):
    cmd = argv[1] if len(argv) > 1 else "help"
    tag = argv[2] if len(argv) > 2 else cmd
    if cmd == "open":
        focus()
        goto_file("CustAddressMapper.xml")
        find_and_escape("selectByExample")
        shot("probe-10-before-preview")
        trigger_preview()
        time.sleep(3.0)
        shot("probe-11-after-preview")
    elif cmd == "reopen":
        close_preview()
        time.sleep(1.0)
        focus()
        trigger_preview()
        time.sleep(3.0)
        shot("probe-12-reopen")
    elif cmd == "preview":
        focus()
        trigger_preview()
        time.sleep(3.0)
        shot(tag)
    elif cmd == "shot":
        shot(tag)
    else:
        print("usage: open|reopen|preview|shot <tag>")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
