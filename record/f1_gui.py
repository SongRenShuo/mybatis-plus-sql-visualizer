# -*- coding: utf-8 -*-
"""GUI 验收 CLI 驱动（提权跑；关停在前台抢窗口、防 CJK 输入、定帧取证三件事上）。

约定：提权 SendInput（沙盒 IDE 是提权进程，非提权输入被 UIPI 拦）、
`WM_INPUTLANGCHANGEREQUEST` 切英文布局、剪贴板粘贴替代键入（免疫中文输入法）、
全屏帧为默认证据形态。**坐标一律由命令行传入，文件内不写死坐标与 PID。**

命令：
  preflight              -- 找 IDE 窗口、置前台置顶、切英文布局、拍一帧
  bring                  -- 只做置前台
  shot <name>            -- 全屏帧 -> <shots>/<name>.png
  zoom <name> <L> <T> <W> <H>  -- 区域帧
  key <combo> [combo2 ...]     -- 按键/组合键（如 ctrl+shift+a、escape）
  click <x> <y> [count]        -- 点击（默认 1 次）
  ctrlclick <x> <y>            -- Ctrl+Click（跳声明）
  copyref                      -- Ctrl+Alt+Shift+C（Copy Reference）后打印剪贴板
  goto <frag> [downs]          -- Ctrl+Shift+N 打开文件，可选 Down xN 后 Enter
  gotoline <line>              -- Ctrl+G 跳行号
  pressn <key> <n>             -- 连按某键 n 次
  scroll <amount> [x] [y]      -- 滚轮
  gotob / gotolineb / rightb <args>  -- 上面几条的 Ctrl+B / 右键菜单变体
  action <text>                -- Ctrl+Shift+A 找动作（插件自定义动作不在索引里，慎用）
  paste <text> / esc <n>       -- 粘贴 / 连按 Esc
  copy <x> <y>                 -- 点 SQL 区、全选、复制后打印剪贴板
  clip                         -- 只打印剪贴板

用法（示例）：
  python f1_gui.py preflight
  python f1_gui.py goto User.xml 8
  python f1_gui.py ctrlclick 123 456
  python f1_gui.py clip

取证目录：默认 `<repo>/build/MPSV-GUI-shots`，可用环境变量 `MPV_SHOTS` 覆盖为
本轮的独立目录（每轮验收各用一个，避免跨轮帧混在一起）。窗口标题关键字可用
`MPV_TITLE_HINT` 覆盖（默认指向 demo 工程窗口）。
"""
import ctypes
import os
import sys
import time
from ctypes import wintypes

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clipread import read_clip  # noqa: E402

pyautogui.FAILSAFE = False

_REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.environ.get("MPV_SHOTS", os.path.join(_REPO, "build", "MPSV-GUI-shots"))
TITLE_HINT = os.environ.get("MPV_TITLE_HINT", "mybatis-plus-sql-visualizer-demo")

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
    pyautogui.keyDown("alt")
    time.sleep(0.05)
    pyautogui.keyUp("alt")
    time.sleep(0.2)
    u32.SetForegroundWindow(wintypes.HWND(hwnd))
    time.sleep(0.3)
    # A bare Alt press/release activates the window system menu (Alt+Space) on some
    # apps; that menu then swallows every later keystroke. Close it defensively.
    pyautogui.press("escape")
    time.sleep(0.2)
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


def print_clip(tag="CLIP"):
    text = read_clip(maxlen=20000)
    print(tag + "_START")
    print(text)
    print(tag + "_END")
    return text


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    cmd = sys.argv[1]

    if cmd == "preflight":
        bring_front()
        switch_to_english()
        shot("scene-00-preflight")
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

    if cmd == "ctrlclick":
        bring_front()
        x, y = int(sys.argv[2]), int(sys.argv[3])
        pyautogui.moveTo(x, y)
        time.sleep(0.5)
        pyautogui.keyDown("ctrl")
        time.sleep(0.2)
        pyautogui.click(x, y)
        time.sleep(0.8)
        pyautogui.keyUp("ctrl")
        time.sleep(2.5)
        print("ctrlclick ->", (x, y))
        return 0

    if cmd == "copyref":
        bring_front()
        set_clipboard_text("__EMPTY__")
        time.sleep(0.25)
        pyautogui.hotkey("ctrl", "alt", "shift", "c")
        time.sleep(1.2)
        print_clip("COPYREF")
        return 0

    if cmd == "goto":
        frag = sys.argv[2]
        downs = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        bring_front()
        switch_to_english()
        press("escape", 2)
        time.sleep(0.5)
        pyautogui.hotkey("ctrl", "shift", "n")
        time.sleep(2.0)
        switch_to_english()
        paste(frag)
        time.sleep(2.5)
        shot("scene-goto-typed")
        for _ in range(downs):
            pyautogui.press("down")
            time.sleep(0.12)
        time.sleep(0.4)
        press("return")
        time.sleep(3.5)
        shot("scene-goto-committed")
        return 0

    if cmd == "gotoline":
        line = sys.argv[2]
        bring_front()
        pyautogui.hotkey("ctrl", "g")
        time.sleep(1.2)
        paste(line)
        time.sleep(0.8)
        press("return")
        time.sleep(1.8)
        shot("scene-gotoline")
        return 0

    if cmd == "pressn":
        # pressn <key> <n> : press a single key n times (caret column is preserved)
        key = sys.argv[2]
        n = int(sys.argv[3])
        bring_front()
        press(key, n)
        time.sleep(0.5)
        shot("scene-pressn")
        print("pressn ->", key, n)
        return 0

    if cmd == "scroll":
        # scroll <notches> : negative = down. Moves the pointer into the editor first.
        n = int(sys.argv[2])
        bring_front()
        pyautogui.moveTo(800, 600)
        time.sleep(0.3)
        step = 3 if n > 0 else -3
        for _ in range(abs(n)):
            pyautogui.scroll(step)
            time.sleep(0.05)
        time.sleep(0.6)
        print("scroll ->", n)
        return 0

    if cmd == "gotob":
        # gotob <line> <col> : Ctrl+G "line:col" then Ctrl+B (goto declaration)
        target = sys.argv[2] + ":" + sys.argv[3]
        bring_front()
        pyautogui.hotkey("ctrl", "g")
        time.sleep(1.2)
        paste(target)
        time.sleep(0.8)
        press("return")
        time.sleep(1.8)
        shot("scene-gotob-caret")
        pyautogui.hotkey("ctrl", "b")
        time.sleep(3.5)
        shot("scene-gotob-goto")
        print("gotob ->", target)
        return 0

    if cmd == "gotolineb":
        # gotolineb <line> <col> : Ctrl+G line, Right x(col-1), then Ctrl+B
        line, col = sys.argv[2], int(sys.argv[3])
        bring_front()
        pyautogui.hotkey("ctrl", "g")
        time.sleep(1.3)
        paste(line)
        time.sleep(0.8)
        press("return")
        time.sleep(1.8)
        shot("scene-gotolineb-caret1")
        for _ in range(col - 1):
            pyautogui.press("right")
            time.sleep(0.08)
        time.sleep(0.6)
        shot("scene-gotolineb-caret2")
        pyautogui.hotkey("ctrl", "b")
        time.sleep(3.5)
        shot("scene-gotolineb-goto")
        print("gotolineb ->", line, col)
        return 0

    if cmd == "rightb":
        # rightb <n> : press Right n times then Ctrl+B (goto declaration) -- atomic
        n = int(sys.argv[2])
        bring_front()
        press("right", n)
        time.sleep(0.8)
        shot("scene-rightb-caret")
        pyautogui.hotkey("ctrl", "b")
        time.sleep(3.5)
        shot("scene-rightb-goto")
        print("rightb ->", n)
        return 0

    if cmd == "action":
        # action <text> : Ctrl+Shift+A (Find Action), type, Enter
        text = sys.argv[2]
        bring_front()
        pyautogui.hotkey("ctrl", "shift", "a")
        time.sleep(1.6)
        paste(text)
        time.sleep(1.6)
        shot("scene-action-typed")
        press("return")
        time.sleep(3.0)
        shot("scene-action-done")
        print("action ->", text)
        return 0

    if cmd == "paste":
        bring_front()
        paste(sys.argv[2])
        return 0

    if cmd == "esc":
        bring_front()
        press("escape", int(sys.argv[2]) if len(sys.argv) > 2 else 1)
        return 0

    if cmd == "copy":
        bring_front()
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
        print_clip("COPY")
        return 0

    if cmd == "clip":
        print_clip("CLIP")
        return 0

    print("unknown cmd", cmd)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
