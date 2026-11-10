# -*- coding: utf-8 -*-
"""Exit sandbox IDE: press Esc (close preview), click main frame close (X),
then confirm exit dialog (blue button, click twice - first click may be swallowed).
Usage: scene_imgR_exit.py <frame_hwnd>
"""
import ctypes, sys, time
from ctypes import wintypes
ctypes.windll.shcore.SetProcessDpiAwareness(2)
import mss
import numpy as np
import PIL.Image
import pyautogui
pyautogui.FAILSAFE = False
u32 = ctypes.windll.user32
FRAME = int(sys.argv[1])
_pid = wintypes.DWORD()
u32.GetWindowThreadProcessId(wintypes.HWND(FRAME), ctypes.byref(_pid))
FRAME_PID = _pid.value

# close preview panel first
pyautogui.press("escape")
time.sleep(1.0)
pyautogui.press("escape")
time.sleep(1.0)

r = wintypes.RECT()
u32.GetWindowRect(wintypes.HWND(FRAME), ctypes.byref(r))
l, t, rr, b = r.left, r.top, r.right, r.bottom
print("frame rect:", (l, t, rr, b))
# X button: top-right corner
x_btn = (rr - 22, t + 22)
u32.SetForegroundWindow(wintypes.HWND(FRAME))
time.sleep(0.6)
pyautogui.click(*x_btn)
time.sleep(2.5)

# find confirm-exit dialog (SunAwtDialog of same pid, small)
ENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)
dialogs = []
def cb(hwnd, _lp):
    pid = wintypes.DWORD()
    u32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    if pid.value != FRAME_PID and pid.value != 0:
        pass
    if not u32.IsWindowVisible(hwnd):
        return True
    cls = ctypes.create_unicode_buffer(64)
    u32.GetClassNameW(hwnd, cls, 64)
    if cls.value == "SunAwtDialog":
        wr = wintypes.RECT()
        u32.GetWindowRect(hwnd, ctypes.byref(wr))
        w, h = wr.right - wr.left, wr.bottom - wr.top
        if 300 < w < 900 and 100 < h < 350:
            dialogs.append((hwnd, (wr.left, wr.top, wr.right, wr.bottom)))
    return True
u32.EnumWindows(ENUMPROC(cb), 0)
print("dialogs:", dialogs)
if not dialogs:
    print("NO_CONFIRM_DIALOG")
    raise SystemExit(2)
hwnd, drect = dialogs[0]
dl, dt, dr, db = drect
img = mss.mss().grab({"left": dl, "top": dt, "width": dr - dl, "height": db - dt})
arr = np.asarray(PIL.Image.frombytes("RGB", img.size, img.rgb), dtype=int)
red, grn, blu = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
mask = (blu - red > 70) & (blu - grn > 40) & (blu > 150)
ys, xs = np.nonzero(mask)
if len(ys) == 0:
    mss.tools.to_png(img.rgb, img.size, output="F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer/build/MPSV-IbatisGap-UI243-shots/imgR5-confirm-dialog.png")
    print("NO_BLUE")
    raise SystemExit(3)
pos = (int(np.mean(xs)) + dl, int(np.mean(ys)) + dt)
print("blue at:", pos)
u32.SetForegroundWindow(wintypes.HWND(hwnd))
time.sleep(0.5)
pyautogui.click(*pos)
time.sleep(1.2)
pyautogui.click(*pos)
time.sleep(2.0)
print("CLICKED x2", pos)
