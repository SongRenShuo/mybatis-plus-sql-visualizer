# -*- coding: utf-8 -*-
"""T-PerfSweep33 A3：「从文件加载」大 XML——文件读取已随解析挪后台（券01）。
文件选择器走文件名框粘贴绝对路径（合成输入绕 IME）。非提权可跑。"""
import ctypes
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pyautogui
import pyperclip

from robot import click_component

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.12
ctypes.windll.shcore.SetProcessDpiAwareness(2)

u32 = ctypes.windll.user32
SHOT = r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\.worktrees\t-perfsweep33\build\MPSV-PerfSweep33-UI-shots"
FIXTURE = r"F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\record\perf33-ambiguous-fragment.xml"
os.makedirs(SHOT, exist_ok=True)
FAILS = []


def shot(tag):
    pyautogui.screenshot(os.path.join(SHOT, "img_%s.png" % tag))
    print("SHOT", tag)


def check(name, ok, detail=""):
    print("CHECK", "PASS" if ok else "FAIL", name, detail)
    if not ok:
        FAILS.append(name)


def ensure_front():
    res = []
    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(h, _):
        buf = ctypes.create_unicode_buffer(256)
        u32.GetWindowTextW(h, buf, 256)
        if buf.value and "SQL Visualizer - SQL" in buf.value:
            res.append(h)
        return True
    u32.EnumWindows(cb, None)
    if not res:
        check("找弹窗", False)
        return False
    u32.SetWindowPos(ctypes.c_void_p(res[0]), ctypes.c_void_p(-1), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010)
    u32.keybd_event(0x12, 0, 0, 0)
    u32.keybd_event(0x12, 0, 2, 0)
    u32.SetForegroundWindow(res[0])
    time.sleep(0.6)
    return True


def try_click(xpath, tag):
    try:
        ok = click_component(xpath)
        print("CLICK", tag, "OK" if ok else "MISS")
        return ok
    except Exception as e:
        print("CLICK", tag, "ERR", repr(e)[:120])
        return False


def main():
    ensure_front()
    clicked = try_click("//div[@accessiblename='从文件加载…']", "从文件加载")
    if not clicked:
        clicked = try_click("//div[@accessiblename='从文件加载…']", "从文件加载(重试)")
    check("A3 点从文件加载", clicked)
    time.sleep(2.5)
    shot("20_chooser")
    # 文件选择器默认焦点在文件名框：粘绝对路径 + Enter
    pyperclip.copy(FIXTURE)
    time.sleep(0.2)
    pyautogui.hotkey("ctrl", "v")
    time.sleep(0.5)
    shot("21_path_typed")
    pyautogui.press("enter")
    time.sleep(0.8)
    pyautogui.press("enter")
    time.sleep(3.0)
    shot("22_after_load")
    print("SCENE_%s fails=%s" % ("OK" if not FAILS else "FAIL", FAILS))
    return 0 if not FAILS else 1


if __name__ == "__main__":
    sys.exit(main())
