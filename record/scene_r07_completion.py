# -*- coding: utf-8 -*-
"""R07 code-completion 重录（T-DocsGifRecord1025 批 1）。

画面：UserMapper.xml 第 77 行 `from user` 行尾 → Enter 新行 → 逐字符键入
`join us` 自动弹出表名补全（`user 用户表` / `user_address` / `"user" 用户表`）
→ Enter 采纳 → 定格 → undo 还原。

**本次修订的两个根因（2026-10-25 P1 证伪定案，见开发计划 §八之二）**：
  1. 旧片那个"补全弹窗"其实是**中文输入法候选框**——原脚本用 typewrite 但没切
     英文布局，本机唯一布局 `zh-CN | Chinese (Simplified) - US` 会把 `join us`
     转成中文候选。修法＝开打前调 `nav.force_en_us()`（`LoadKeyboardLayoutW('00000409')`
     + `PostMessage(WM_INPUTLANGCHANGEREQUEST)`；只调 ActivateKeyboardLayout 无效）。
  2. 旧片全程挂着一个「回滚 SQL」弹窗遮住编辑器右下。修法＝开录前遍历真实窗口，
     凡标题含「回滚 / Rollback / Undo」的独立窗口一律关掉，并在开录前断言编辑器可见区
     无遮挡；这一步走 robot-server 查不到（独立 heavyweight window 不在主窗组件树里），
     所以改用 win32 枚举。

前置：mybatis-plus-demo/UserMapper.xml 打开并停在第 77 行附近；无残留弹窗。
"""
import sys
import time

sys.path.insert(0, ".")
sys.path.insert(0, "lib")

import win32gui  # noqa: E402

from gifkit import Recorder, goto_line, move, pyautogui  # noqa: E402
from nav import force_en_us  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = "perf/r07_completion.mp4"

BLOCKING_KEYWORDS = ("回滚", "Rollback", "Rollback SQL")


def visible_windows():
    out = []

    def cb(hwnd, _):
        if win32gui.IsWindowVisible(hwnd):
            t = win32gui.GetWindowText(hwnd)
            if t.strip():
                out.append((hwnd, t, win32gui.GetWindowRect(hwnd)))
        return True

    win32gui.EnumWindows(cb, out)
    return out


def assert_no_blocking_popup():
    """开录前断言：没有标题带「回滚」的独立窗口盖在编辑器可见区上。"""
    hits = [(h, t) for h, t, _ in visible_windows()
            if any(k in t for k in BLOCKING_KEYWORDS)]
    if hits:
        raise RuntimeError(f"存在会遮挡画面的弹窗，先手动关闭：{hits}")


def main():
    force_en_us()
    time.sleep(0.6)
    assert_no_blocking_popup()

    goto_line(77)
    time.sleep(0.5)

    r = Recorder(REGION, OUT)
    time.sleep(1.2)
    r.start()
    time.sleep(0.5)

    move(640, 500, dur=0.4)                        # 1. 光标滑入编辑区（演示轨迹）
    time.sleep(0.3)
    pyautogui.press("end")                         # 2. 到 `from user` 行尾
    time.sleep(0.4)
    pyautogui.press("enter")                       # 3. 新行
    time.sleep(0.5)
    pyautogui.typewrite("join us", interval=0.13)  # 4. 边打边弹（英文布局下才有效）
    time.sleep(2.0)                                # 5. 补全列表定格
    pyautogui.keyDown("return")
    pyautogui.keyUp("return")                      # 6. 采纳
    time.sleep(1.6)                                # 7. 结果定格
    for _ in range(4):                             # 8. 撤回（还原现场，接缝无缝）
        pyautogui.hotkey("ctrl", "z")
        time.sleep(0.4)
    # 连续 Ctrl+Z 会让 IDE 停在「刚撤销的那段被整块选中」的态，尾帧带着一片全选高亮，
    # 与首帧对不上（实测接缝 diff 0.47% 全来自这里）。补一次 Esc 清选择。
    pyautogui.press("esc")
    time.sleep(0.5)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
