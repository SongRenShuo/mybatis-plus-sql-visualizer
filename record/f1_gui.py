# -*- coding: utf-8 -*-
"""Round-F (T-IbatisFix2) GUI acceptance probe.

Reuses the process conventions of previous rounds (elevated SendInput, English
layout via WM_INPUTLANGCHANGEREQUEST, clipboard paste for CJK-proof typing,
full-screen frames as default evidence).

Usage:
  python f1_gui.py preflight
  python f1_gui.py shot <name>
  python f1_gui.py zoom <name> <L> <T> <W> <H>
  python f1_gui.py key <combo> [combo2 ...]
  python f1_gui.py click <x> <y> [count]
  python f1_gui.py open <line>
  python f1_gui.py preview
  python f1_gui.py copy                      # click SQL editor, Esc, Ctrl+C, print clipboard
  python f1_gui.py clip                      # print clipboard only
"""
import ctypes
import sys
import time
from ctypes import wintypes

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

sys.path.insert(0, r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\record")
from clipread import read_clip  # noqa: E402

pyautogui.FAILSAFE = False

SHOTS = r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\build\MPSV-IbatisGap-UI243-shots"
TITLE_HINT = "mybatis-plus-sql-visualizer-demo"

u32 = ctypes.windll.user32
k32 = ctypes.windll.kernel32

WM_INPUTLANGCHANGEREQUEST = 0x0050
ENGLISH_LAYOUT = 0x04090409
GMEM_MOVEABLE = 0x0002
SW_MAXIMIZE = 3
SW_RESTORE = 9
HWND_TOPMOST = -1
GWL_EXSTYLE = -20
WS_EX_TOPMOST = 0x8

ENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)


def find_ide_hwnd():
    found = []

    def cb(hwnd, _lp):
        if not u32.IsWindowVisible(hwnd):
            return True
        n = u32.GetWindowTextLengthW(hwnd)
        if n <= 0:
            return True
        buf = ctypes.create_unicode_buffer(n + 2)
        u32.GetWindowTextW(hwnd, buf, n + 2)
        if TITLE_HINT in buf.value:
            pid = wintypes.DWORD()
            u32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            found.append((hwnd, buf.value, pid.value))
        return True

    u32.EnumWindows(ENUMPROC(cb), 0)
    return found


def set_clipboard_text(text):
    k32.GlobalAlloc.restype = ctypes.c_void_p
    k32.GlobalLock.restype = ctypes.c_void_p
    k32.GlobalLock.argtypes = [ctypes.c_void_p]
    k32.GlobalUnlock.argtypes = [ctypes.c_void_p]
    if not u32.OpenClipboard(None):
        raise RuntimeError("OpenClipboard failed")
    try:
        u32.EmptyClipboard()
        handle = k32.GlobalAlloc(GMEM_MOVEABLE, (len(text) + 1) * 2)
        target = k32.GlobalLock(handle)
        if not target:
            raise RuntimeError("GlobalLock failed")
        ctypes.memmove(target, ctypes.create_unicode_buffer(text), (len(text) + 1) * 2)
        k32.GlobalUnlock(handle)
        u32.SetClipboardData.argtypes = [wintypes.UINT, ctypes.c_void_p]
        if not u32.SetClipboardData(13, handle):
            raise RuntimeError("SetClipboardData failed")
    finally:
        u32.CloseClipboard()


def switch_to_english():
    hwnd = u32.GetForegroundWindow()
    u32.PostMessageW(wintypes.HWND(hwnd), WM_INPUTLANGCHANGEREQUEST, 0, ENGLISH_LAYOUT)
    time.sleep(0.8)
    print("layout now: 0x%x" % (u32.GetKeyboardLayout(0) & 0xFFFF))


def shot(name):
    out = SHOTS + "\\" + name + ".png"
    with mss.mss() as sct:
        img = sct.grab(sct.monitors[1])
        mss.tools.to_png(img.rgb, img.size, output=out)
    print("shot ->", out)
    return out


def zoom(name, box):
    out = SHOTS + "\\" + name + ".png"
    left, top, width, height = box
    with mss.mss() as sct:
        img = sct.grab({"left": left, "top": top, "width": width, "height": height})
        mss.tools.to_png(img.rgb, img.size, output=out)
    print("zoom ->", out, box)
    return out


def bring_front():
    wins = find_ide_hwnd()
    print("matches:", wins)
    if not wins:
        print("NO_IDE_WINDOW")
        return None
    hwnd = wins[0][0]
    u32.ShowWindow(wintypes.HWND(hwnd), SW_RESTORE)
    time.sleep(0.3)
    u32.ShowWindow(wintypes.HWND(hwnd), SW_MAXIMIZE)
    time.sleep(0.6)
    # Alt press/release first (front-lock workaround), then SetForegroundWindow
    pyautogui.keyDown("alt")
    time.sleep(0.05)
    pyautogui.keyUp("alt")
    time.sleep(0.2)
    u32.SetForegroundWindow(wintypes.HWND(hwnd))
    time.sleep(0.3)
    u32.SetWindowPos.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int,
                                 ctypes.c_int, ctypes.c_int, ctypes.c_uint, ctypes.c_uint]
    u32.SetWindowPos(ctypes.c_void_p(hwnd), ctypes.c_void_p(HWND_TOPMOST), 0, 0, 0, 0,
                     0x0001 | 0x0002 | 0x0010)
    time.sleep(0.6)
    fg = u32.GetForegroundWindow()
    buf = ctypes.create_unicode_buffer(160)
    u32.GetWindowTextW(fg, buf, 160)
    ex = u32.GetWindowLongW(wintypes.HWND(hwnd), GWL_EXSTYLE)
    print("fg hwnd=%d title=%r" % (fg, buf.value))
    print("topmost bit=0x%x (expect 0x8)" % (ex & WS_EX_TOPMOST))
    return hwnd


def press(key, n=1):
    for _ in range(n):
        pyautogui.keyDown(key)
        time.sleep(0.05)
        pyautogui.keyUp(key)
        time.sleep(0.12)


def paste(text):
    set_clipboard_text(text)
    time.sleep(0.3)
    pyautogui.hotkey("ctrl", "v")
    time.sleep(1.0)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    cmd = sys.argv[1]

    if cmd == "preflight":
        bring_front()
        switch_to_english()
        shot("imgF-00-preflight")
        return 0

    if cmd == "bring":
        bring_front()
        return 0

    if cmd == "shot":
        shot(sys.argv[2])
        return 0

    if cmd == "zoom":
        zoom(sys.argv[2], [int(x) for x in sys.argv[3:7]])
        return 0

    if cmd == "key":
        bring_front()
        for combo in sys.argv[2:]:
            parts = combo.split("+")
            if len(parts) == 1:
                press(parts[0])
            else:
                pyautogui.hotkey(*parts)
                time.sleep(0.6)
            print("key ->", combo)
        return 0

    if cmd == "click":
        bring_front()
        x, y = int(sys.argv[2]), int(sys.argv[3])
        count = int(sys.argv[4]) if len(sys.argv) > 4 else 1
        pyautogui.moveTo(x, y)
        time.sleep(0.25)
        for _ in range(count):
            pyautogui.click(x, y)
            time.sleep(0.35)
        print("click ->", (x, y), "x", count)
        return 0

    if cmd == "open":
        line = sys.argv[2]
        bring_front()
        switch_to_english()
        press("escape", 2)
        time.sleep(0.5)
        pyautogui.hotkey("ctrl", "shift", "n")
        time.sleep(2.0)
        switch_to_english()
        paste("User.xml")
        time.sleep(2.2)
        shot("imgF-open-typed")
        for _ in range(8):
            pyautogui.press("up")
            time.sleep(0.10)
        time.sleep(0.4)
        press("return")
        time.sleep(3.0)
        shot("imgF-open-committed")
        pyautogui.hotkey("ctrl", "g")
        time.sleep(1.2)
        paste(line)
        time.sleep(0.8)
        press("return")
        time.sleep(2.0)
        shot("imgF-open-at-line")
        return 0

    if cmd == "preview":
        bring_front()
        pyautogui.hotkey("alt", "shift", "q")
        time.sleep(4.0)
        shot("imgF-preview-opened")
        return 0

    if cmd == "copy":
        # click the SQL editor, select-all, copy. No Esc (Esc closes the preview dialog).
        if len(sys.argv) >= 4:
            pyautogui.moveTo(int(sys.argv[2]), int(sys.argv[3]))
            time.sleep(0.2)
            pyautogui.click(int(sys.argv[2]), int(sys.argv[3]))
            time.sleep(0.6)
            pyautogui.click(int(sys.argv[2]), int(sys.argv[3]))
            time.sleep(0.6)
        set_clipboard_text("__EMPTY__")
        time.sleep(0.2)
        pyautogui.hotkey("ctrl", "a")
        time.sleep(0.5)
        pyautogui.hotkey("ctrl", "c")
        time.sleep(1.2)
        text = read_clip(maxlen=8000)
        print("CLIP_START")
        print(text)
        print("CLIP_END")
        return 0

    if cmd == "clip":
        text = read_clip(maxlen=8000)
        print("CLIP_START")
        print(text)
        print("CLIP_END")
        return 0

    print("unknown cmd", cmd)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())