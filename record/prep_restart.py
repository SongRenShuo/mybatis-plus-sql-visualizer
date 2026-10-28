# -*- coding: utf-8 -*-
"""沙盒重启后的现场归一（T-DemoGifFix1026 重启轮）：气球清掉 → MoveWindow → 关页签 → 同步 → 隐藏工具窗。"""
import os, sys, time, ctypes
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); sys.path.insert(0, os.path.join(HERE, "lib"))
import pyautogui
from gifkit import shot
pyautogui.FAILSAFE = False; pyautogui.PAUSE = 0

u32 = ctypes.windll.user32
ide = []
@ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
def cb(h, _):
    if u32.IsWindowVisible(h):
        n = u32.GetWindowTextLengthW(h)
        if n > 0:
            buf = ctypes.create_unicode_buffer(n + 1)
            u32.GetWindowTextW(h, buf, n + 1)
            if "mybatis-plus-sql-visualizer-demo" in buf.value:
                ide.append(h)
    return True
u32.EnumWindows(cb, None)
assert ide, "IDE 窗口未找到"
hwnd = ide[-1]
u32.keybd_event(0x12, 0, 0, 0); u32.keybd_event(0x12, 0, 2, 0)
u32.SetForegroundWindow(ctypes.c_void_p(hwnd))
time.sleep(1.0)

# 硬前置：前台窗口必须是沙盒 IDE（2026-10-27 误点用户日常 IDE 后的加固）
fg = u32.GetForegroundWindow()
if fg != hwnd:
    u32.keybd_event(0x12, 0, 0, 0); u32.keybd_event(0x12, 0, 2, 0)
    u32.SetForegroundWindow(ctypes.c_void_p(hwnd))
    time.sleep(1.0)
    pyautogui.press("esc"); time.sleep(0.3)
fg = u32.GetForegroundWindow()
assert fg == hwnd, f"前台不是沙盒 IDE（fg={fg} hwnd={hwnd}）——中止，避免误点其它窗口"

# 气球：两枚「不再提示」链接各点两次（首击可能被吞）
for xy in ((1500, 820), (1500, 931)):
    pyautogui.click(*xy); time.sleep(0.5)
    pyautogui.click(*xy); time.sleep(0.8)

# 归位
u32.MoveWindow(ctypes.c_void_p(hwnd), 277, 25, 1370, 984, True)
time.sleep(1.2)
import ctypes.wintypes as wt
r = wt.RECT()
u32.GetWindowRect(ctypes.c_void_p(hwnd), ctypes.byref(r))
assert (r.left, r.top, r.right, r.bottom) == (277, 25, 1647, 1009), f"rect={(r.left,r.top,r.right,r.bottom)}"

# 关页签
for _ in range(10):
    if u32.GetForegroundWindow() != hwnd:
        break
    pyautogui.hotkey("ctrl", "F4"); time.sleep(0.3)
# 同步 VFS + 隐藏工具窗
pyautogui.hotkey("ctrl", "alt", "y"); time.sleep(1.2)
pyautogui.hotkey("ctrl", "shift", "F12"); time.sleep(1.2)
shot((285, 26, 1354, 974), os.path.join(HERE, "perf", "_prep_restart.png"))
print("prep done")
