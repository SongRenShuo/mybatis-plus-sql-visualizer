# -*- coding: utf-8 -*-
"""UI-046 真机复验探针：读 u046_step.txt 逐行执行（提权跑，见 record/_run_elev.bat）。

命令集（每行一条，# 开头忽略）：
  front                 置前 IDE 窗口
  max                   最大化 IDE 窗口
  rect                  打印 IDE 窗口 rect
  click X Y             左键单击
  dclick X Y            左键双击
  key a+b+c             组合键
  type TEXT             逐字输入（ASCII）
  sleep S               等待
  shot NAME             全屏截图 -> build/MPSV-B3-shots/NAME.png
  find FILE              Ctrl+Shift+N 打开文件
  findtext TEXT          Ctrl+F 查找并把光标停在命中处（Enter 后 Esc）
  list                  打印可见窗口清单
"""
import ctypes
import ctypes.wintypes as w
import io
import sys
import time

try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    pass

import pyautogui

pyautogui.FAILSAFE = False

SHOTS = r"F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev/build/MPSV-B3-shots"
STEP_FILE = r"F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev/record/b3_step.txt"
MATCH = "mybatis-plus-demo"

u32 = ctypes.windll.user32
k32 = ctypes.windll.kernel32
EnumProc = ctypes.WINFUNCTYPE(ctypes.c_bool, w.HWND, w.LPARAM)


def windows():
    out = []

    def cb(h, l):
        if not u32.IsWindowVisible(h):
            return True
        n = u32.GetWindowTextLengthW(h)
        if n == 0:
            return True
        b = ctypes.create_unicode_buffer(n + 1)
        u32.GetWindowTextW(h, b, n + 1)
        r = w.RECT()
        u32.GetWindowRect(h, ctypes.byref(r))
        pid = w.DWORD()
        u32.GetWindowThreadProcessId(h, ctypes.byref(pid))
        out.append((b.value, pid.value, h, (r.left, r.top, r.right, r.bottom)))
        return True

    u32.EnumWindows(EnumProc(cb), 0)
    return out


def all_windows():
    out = []

    def cb(h, l):
        if not u32.IsWindowVisible(h):
            return True
        r = w.RECT()
        u32.GetWindowRect(h, ctypes.byref(r))
        n = u32.GetWindowTextLengthW(h)
        b = ctypes.create_unicode_buffer(n + 1) if n else None
        if n:
            u32.GetWindowTextW(h, b, n + 1)
        out.append((b.value if b else "", w.DWORD(), h, (r.left, r.top, r.right, r.bottom)))
        return True

    u32.EnumWindows(EnumProc(cb), 0)
    return out


def ide_window():
    cands = [x for x in windows() if MATCH in x[0]]
    if not cands:
        for t, p, h, r in windows():
            print("WIN", repr(t), r)
        raise SystemExit("NO_IDE_WINDOW")
    return cands[0]


def front(h=None):
    if h is None:
        found = [x for x in windows() if MATCH in x[0]]
        if not found:
            return False
        h = found[0][2]
    if u32.GetForegroundWindow() != h:
        fg = u32.GetForegroundWindow()
        tid_fg = u32.GetWindowThreadProcessId(fg, None) if fg else 0
        tid_self = k32.GetCurrentThreadId()
        u32.AttachThreadInput(tid_self, tid_fg, True)
        u32.BringWindowToTop(h)
        u32.SetForegroundWindow(h)
        u32.AttachThreadInput(tid_self, tid_fg, False)
    time.sleep(0.4)
    return True


def shot(name):
    from PIL import ImageGrab
    path = "%s/%s.png" % (SHOTS, name)
    ImageGrab.grab().save(path)
    print("SHOT", path)


def run():
    for raw in io.open(STEP_FILE, encoding="utf-8"):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(None, 1)
        cmd = parts[0].lower()
        arg = parts[1] if len(parts) > 1 else ""
        print("CMD", line)
        if cmd == "front":
            front()
        elif cmd == "max":
            h = ide_window()[2]
            u32.ShowWindow(h, 3)
            front(h)
            time.sleep(0.6)
        elif cmd == "rect":
            t, p, h, r = ide_window()
            print("RECT", t, p, r)
        elif cmd == "list":
            for t, p, h, r in windows():
                print("WIN", repr(t), r)
        elif cmd == "click" or cmd == "dclick":
            x, y = [int(v) for v in arg.split()[:2]]
            front()
            pyautogui.moveTo(x, y)
            time.sleep(0.15)
            if cmd == "click":
                pyautogui.click()
            else:
                pyautogui.doubleClick()
            time.sleep(0.5)
        elif cmd == "key":
            front()
            keys = [k.strip() for k in arg.split("+")]
            pyautogui.hotkey(*keys)
            time.sleep(0.4)
        elif cmd == "type":
            front()
            pyautogui.typewrite(arg, interval=0.03)
            time.sleep(0.3)
        elif cmd == "sleep":
            time.sleep(float(arg))
        elif cmd == "shot":
            shot(arg)
        elif cmd == "find":
            front()
            pyautogui.hotkey("ctrl", "shift", "n")
            time.sleep(1.2)
            pyautogui.typewrite(arg, interval=0.03)
            time.sleep(0.9)
            pyautogui.press("enter")
            time.sleep(2.0)
        elif cmd == "findtext":
            front()
            pyautogui.hotkey("ctrl", "f")
            time.sleep(0.8)
            pyautogui.typewrite(arg, interval=0.03)
            time.sleep(0.6)
            pyautogui.press("enter")
            time.sleep(0.5)
            pyautogui.press("enter")
            time.sleep(0.4)
            pyautogui.press("esc")
            time.sleep(0.4)
        elif cmd == "hover":
            x, y = [int(v) for v in arg.split()[:2]]
            front()
            pyautogui.moveTo(x, y)
            time.sleep(0.6)
        elif cmd == "presshold":
            x, y = [int(v) for v in arg.split()[:2]]
            front()
            pyautogui.moveTo(x, y)
            time.sleep(0.15)
            pyautogui.mouseDown()
            time.sleep(0.18)
            pyautogui.mouseUp()
            time.sleep(0.6)
        elif cmd == "drag":
            parts = arg.split()
            x1, y1, x2, y2 = [int(v) for v in parts[:4]]
            pyautogui.moveTo(x1, y1)
            time.sleep(0.2)
            pyautogui.mouseDown()
            time.sleep(0.2)
            pyautogui.moveTo(x2, y2, duration=0.5)
            time.sleep(0.2)
            pyautogui.mouseUp()
            time.sleep(0.6)
        elif cmd == "wheel":
            parts = arg.split()
            x, y = int(parts[0]), int(parts[1])
            amt = int(parts[2]) if len(parts) > 2 else -3
            pyautogui.moveTo(x, y)
            time.sleep(0.2)
            pyautogui.scroll(amt)
            time.sleep(0.4)
        elif cmd == "nclick":
            x, y = [int(v) for v in arg.split()[:2]]
            pyautogui.moveTo(x, y)
            time.sleep(0.15)
            pyautogui.click()
            time.sleep(0.5)
        elif cmd == "nkey":
            keys = [k.strip() for k in arg.split("+")]
            pyautogui.hotkey(*keys)
            time.sleep(0.4)
        elif cmd == "focusrect":
            l, t, ww, hh = [int(v) for v in arg.split()[:4]]
            hit = None
            for t2, p2, h2, r2 in all_windows():
                if abs(r2[0] - l) <= 12 and abs(r2[1] - t) <= 12 and abs((r2[2] - r2[0]) - ww) <= 24 and abs((r2[3] - r2[1]) - hh) <= 24:
                    hit = (t2, h2, r2)
                    break
            print("FOCUS_HIT", hit)
            if hit:
                h2 = hit[1]
                fg = u32.GetForegroundWindow()
                tid_fg = u32.GetWindowThreadProcessId(fg, None) if fg else 0
                tid_self = k32.GetCurrentThreadId()
                u32.AttachThreadInput(tid_self, tid_fg, True)
                u32.SetWindowPos(h2, -1, 0, 0, 0, 0, 0x0001 | 0x0002)
                u32.BringWindowToTop(h2)
                u32.SetForegroundWindow(h2)
                u32.AttachThreadInput(tid_self, tid_fg, False)
                time.sleep(0.6)
                print("FOCUSED", u32.GetForegroundWindow() == h2)
        elif cmd == "sh":
            import subprocess
            p = subprocess.run(arg, shell=True, capture_output=True, text=True, timeout=120)
            print("RC", p.returncode)
            print((p.stdout or "")[:4000])
            print((p.stderr or "")[:1500])
        else:
            print("UNKNOWN", cmd)
    print("DONE")


if __name__ == "__main__":
    run()
