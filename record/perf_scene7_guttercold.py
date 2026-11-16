# -*- coding: utf-8 -*-
"""Scene 7 (T-GutterFirstOpenDelay): cold-open gutter icon timeline vs warm reopen.

Purpose: reproduce the user report -- open a mapper XML right after project open (before
IDE initialization completes) and the preview gutter icon lands ~5s later; after the IDE
is fully initialized the same file shows icons immediately.

Evidence channels:
  1. full-screen shots every 1s during a "cold" open and a "warm" reopen (gutter region
     cropped post-hoc; first-render moment cross-checked against idea.log
     `[open-perf] ctx warm done in Tms` -- warm is kicked at first getLineMarkerInfo).
  2. idea.log dumb/smart timestamps for correlation.

Run from the normal (non-elevated) shell AFTER the runIde window appears (or start it
and let it wait -- it polls for the window up to 6 minutes).
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
FILE = "CtAccount0002Mapper.xml"


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def shot(name):
    with mss.mss() as sct:
        img = sct.grab(sct.monitors[1])
        mss.tools.to_png(img.rgb, img.size, output=os.path.join(SHOTS, name))
    log("shot -> " + name)


def find_ide():
    for hwnd, pid, title, rect in windows():
        t = title or ""
        if ("cust-big-demo" in t or "mybatis-plus-sql-visualizer-demo" in t
                or ("IntelliJ IDEA" in t and "Welcome" not in t)):
            return hwnd, pid, t
    return None, None, None


def focus(hwnd):
    U32.SetWindowPos(ctypes.c_void_p(hwnd), ctypes.c_void_p(-1), 0, 0,
                     U32.GetSystemMetrics(0), U32.GetSystemMetrics(1), 0x0040 | 0x0001)
    U32.keybd_event(0x12, 0, 0, 0)
    U32.keybd_event(0x12, 0, 2, 0)
    U32.SetForegroundWindow(ctypes.c_void_p(hwnd))
    time.sleep(0.6)


def title_of(hwnd):
    buf = ctypes.create_unicode_buffer(512)
    U32.GetWindowTextW(ctypes.c_void_p(hwnd), buf, 512)
    return buf.value


def open_file(timeout=180):
    """Ctrl+Shift+N -> type -> enter; retry until the IDE title shows the .xml tab."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        pyautogui.press("esc")
        time.sleep(0.5)
        pyautogui.hotkey("ctrl", "shift", "n")
        time.sleep(1.5)
        pyautogui.typewrite(FILE, interval=0.02)
        time.sleep(1.2)
        pyautogui.press("enter")
        time.sleep(3.0)
        for hwnd, pid, t, rect in windows():
            if FILE in (t or ""):
                return hwnd
        log("file not open yet, retry...")
    return None


def wait_for_window(max_wait=360):
    deadline = time.time() + max_wait
    while time.time() < deadline:
        hwnd, pid, t = find_ide()
        if hwnd:
            return hwnd
        time.sleep(1.0)
    return None


def burst(tag, seconds, interval=1.0):
    t0 = time.time()
    i = 0
    while time.time() - t0 < seconds:
        shot("%s_%02d.png" % (tag, i))
        i += 1
        time.sleep(interval)


def main():
    hwnd = wait_for_window()
    if not hwnd:
        log("NO_IDE_WINDOW")
        return 2
    log("window found: " + title_of(hwnd)[:90])
    focus(hwnd)
    # v2: the project restores the last-open mapper tab by itself -- that IS the cold open.
    # No interaction at all during startup; capture the gutter timeline passively.
    log("COLD timeline start (restored tab, no interaction)")
    burst("cold", 45)

    # let IDE finish initializing, then warm reopen via the search popup (reliable by now)
    log("waiting 90s for full initialization")
    time.sleep(90)
    focus(hwnd)
    pyautogui.hotkey("ctrl", "f4")
    time.sleep(2.0)
    log("WARM reopen start")
    h = open_file()
    if not h:
        log("REOPEN_FAILED")
        return 3
    log("file reopened (warm); shooting 15s timeline")
    burst("warm", 15)
    log("DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
