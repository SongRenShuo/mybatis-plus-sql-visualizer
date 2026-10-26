# -*- coding: utf-8 -*-
"""批0 prep（20261026 T-DemoGifFix1026）：现场归一，不入镜。

顺序（计划 §5.2 闸门 0 的死顺序）：
1. MoveWindow 归位 + GetWindowRect 断言；
2. 关掉全部编辑器页签（脏文档此时自动保存＝与磁盘一致，随后立刻 checkout 覆盖）；
3. `git checkout` 恢复 demo 仓 6 个被散空行污染的文件（pom.xml / OrderController.java
   是早前轮次的有意改动，**保留不还原**）；
4. IDE 内 Ctrl+Alt+Y（Synchronize）让 VFS 重新读盘；
5. Ctrl+Shift+F12 隐藏全部工具窗（工程树 + AI 面板；批1 分镜不含工具窗）；
6. 截基线帧 perf/_prep_b0.png 供断言。
"""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import ctypes
import pyautogui
from gifkit import shot

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

WIN_X, WIN_Y, WIN_W, WIN_H = 277, 25, 1370, 984
DEMO = "F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo"
KEEP = {  # 早前轮次的有意改动，闸门 0 白名单
    "pom.xml",
    "mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/controller/OrderController.java",
}


def win32():
    u32 = ctypes.windll.user32
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        u32.SetProcessDPIAware()
    return u32


def find_ide_hwnd(u32):
    import ctypes.wintypes as wt
    EnumWindows = u32.EnumWindows
    EnumWindowsProc = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    found = []

    @EnumWindowsProc
    def cb(h, _):
        if u32.IsWindowVisible(h):
            n = u32.GetWindowTextLengthW(h)
            if n > 0:
                buf = ctypes.create_unicode_buffer(n + 1)
                u32.GetWindowTextW(h, buf, n + 1)
                pid = ctypes.c_uint(0)
                u32.GetWindowThreadProcessId(h, ctypes.byref(pid))
                if "mybatis-plus-sql-visualizer-demo" in buf.value:
                    found.append((h, buf.value))
        return True
    EnumWindows(cb, None)
    return found[0] if found else (None, None)


def main():
    u32 = win32()
    hwnd, title = find_ide_hwnd(u32)
    if not hwnd:
        raise RuntimeError("找不到沙盒 IDE 窗口")
    print("hwnd:", hwnd, title)

    # 1. 归位 + 断言
    u32.MoveWindow(ctypes.c_void_p(hwnd), WIN_X, WIN_Y, WIN_W, WIN_H, True)
    time.sleep(1.0)
    rect = ctypes.wintypes.RECT() if hasattr(ctypes, "wintypes") else None
    import ctypes.wintypes as wt
    r = wt.RECT()
    u32.GetWindowRect(ctypes.c_void_p(hwnd), ctypes.byref(r))
    got = (r.left, r.top, r.right, r.bottom)
    want = (WIN_X, WIN_Y, WIN_X + WIN_W, WIN_Y + WIN_H)
    assert got == want, f"窗口矩形断言失败: {got} != {want}"
    print("窗口矩形 OK:", got)

    # 前台夺回（Alt 按放 + SetForegroundWindow）。
    # ⚠️ Alt 按放会激活 Swing 主菜单栏——后续按键全被菜单吞掉（20261026 实测，
    # 页签一个没关）。必须再按一次 Esc 收掉菜单，并断言前台窗口确实是 IDE。
    u32.keybd_event(0x12, 0, 0, 0)
    u32.keybd_event(0x12, 0, 2, 0)
    u32.SetForegroundWindow(ctypes.c_void_p(hwnd))
    time.sleep(0.6)
    fg = u32.GetForegroundWindow()
    assert fg == hwnd, f"前台窗口不是 IDE: {fg} != {hwnd}"
    pyautogui.press("esc")
    time.sleep(0.4)

    # 2. 关掉全部编辑器页签（Ctrl+F4 ×16，隔 0.35s；关完会停在空编辑态），
    #    每轮断言画面确实在变，防「按键被吞还盲跑到底」。
    import numpy as np
    from PIL import Image
    last = None
    for i in range(16):
        pyautogui.hotkey("ctrl", "F4")
        time.sleep(0.35)
        shot((285, 26, 1354, 974), os.path.join(HERE, "perf", "_b0_closing.png"))
        cur = np.asarray(Image.open(os.path.join(HERE, "perf", "_b0_closing.png")).convert("L"))
        if last is not None and np.abs(cur.astype(int) - last.astype(int)).mean() < 0.05:
            print(f"第 {i} 轮画面不再变化，页签已关完")
            break
        last = cur

    # 3. git checkout 恢复污染文件
    out = subprocess.run(["git", "status", "--short"], cwd=DEMO,
                         capture_output=True, text=True).stdout
    print("恢复前 demo 状态:\n", out)
    paths = []
    for line in out.splitlines():
        st, p = line[:2], line[3:].strip().strip('"')
        if st.strip() == "M" and p not in KEEP and not p.endswith(".ai/"):
            paths.append(p)
    if paths:
        subprocess.run(["git", "checkout", "--"] + paths, cwd=DEMO, check=True)
    out2 = subprocess.run(["git", "status", "--short"], cwd=DEMO,
                          capture_output=True, text=True).stdout
    print("恢复后 demo 状态:\n", out2)

    # 4. Ctrl+Alt+Y 同步 VFS
    time.sleep(0.5)
    pyautogui.hotkey("ctrl", "alt", "y")
    time.sleep(1.5)

    # 5. 隐藏全部工具窗
    pyautogui.hotkey("ctrl", "shift", "F12")
    time.sleep(1.5)

    # 6. 基线帧
    shot((285, 26, 1354, 974), os.path.join(HERE, "perf", "_prep_b0.png"))
    print("prep done")


if __name__ == "__main__":
    main()
