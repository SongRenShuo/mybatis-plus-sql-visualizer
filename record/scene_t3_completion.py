# -*- coding: utf-8 -*-
"""T3 code-completion（2026-10-31 重录：真补全弹窗 + 候选收窄）。

旧片的问题不是画质/帧数，而是**画面内容错**：所谓「补全弹窗」实际是系统输入法的中文候选框
（候选是汉字），不是插件的表名补全；而且全片只有一次候选态，看不到「敲几个字符后候选收窄」。

本次重录的六道锚点（每道都为了「内容对」，不是「好看」）：

  1) **语言/文件锚点**：只在 `UserMapper.xml`（XML 语言，plugin.xml 注册了 XML / MpSqlMapper
     补全贡献器）里敲 SQL。Java 文件不注册该补全，敲字母只会出 IME 候选。
  2) **输入法锚点**：开录前与每次键入前 `force_en_us()`，并用 `GetKeyboardLayout` 断言前台 IDE
     线程布局低 16 位 == 0x0409（en-US）。不是英文布局直接抛错停录，绝不让 IME 候选入镜。
  3) **弹窗锚点（像素）**：Islands Dark 下补全弹层底色 RGB=(39,40,43)、选中行=(51,53,59)，
     与编辑器/工具窗底色 (25,26,28) 明显不同。在光标下方观察区按这两个色相找矩形，再按亮文字
     行聚类**数候选行数**；`join u` 起要求 ≥3 行、收窄态要求 ≥1 行，判不到即抛错（不录坏画面）。
  4) **收窄锚点**：`join u`（7 行）→ `us`/`use`/`user`（5 行）→ `user_`（2 行＝1 候选 + 提示行）
     → 退格回 `user`（5 行）→ Tab 采纳。成片里能肉眼看到候选行数随键入递减。
  5) **内容锚点**：开录前空跑一次「键入 → 采纳」，把结果行复制出来断言含 `join user`（ASCII 表名，
     IME 候选不可能产出），再撤回并断言第 77 行回到 `from user`（文件没被弄脏）。
  6) **前台锚点**：每次键入前断言前台 PID == 沙盒 PID；不一致先置前，不盲敲。

**两个已证伪的旧做法（别再回去）**：
  · 导航不用 `Ctrl+G`（Go to Line）：本机它会往文档里塞杂散空行（skill 坑 61），且 IME 态下
    Enter 被吞会让弹窗残留。改用 `Ctrl+F` 搜唯一 token `selectUserByTrim` → Esc → `Down×3`
    （74→77 行）→ End，并复制当前行断言 `from user`。
  · 采纳不用 `Enter`：在注入片段里按 Enter 会触发平台
    `AsyncCompletion ... MpSqlMapper:UserMapper.xml != ...` 的 AssertionError（弹 "IDE error occurred"
    气球污染画面，本次实测日志可查）。改用 `Tab` 采纳，无异常。

前置：沙盒 IDE（MPSV-GifFresh）已打开 demo 工程；UserMapper.xml 已在编辑器里（可先 Ctrl+E 打开）；
Services 面板已隐藏、工具窗拉宽（面板左缘 region≈872）——这些属 prep，脚本不代劳。
"""
import ctypes
import os
import sys
import time

import numpy as np

BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE)
sys.path.insert(0, os.path.join(BASE, "lib"))

import mss  # noqa: E402
import pyautogui  # noqa: E402
from clipread import read_clip  # noqa: E402
from gifkit import Recorder, move  # noqa: E402
from locate import (ensure_foreground, foreground_hwnd, pid_of_hwnd,  # noqa: E402
                    sandbox_pid, windows)
from nav import force_en_us  # noqa: E402

# 构图（skill §成品默认规格 + 坑 49）：窗口外框 (277,25,1370,984)，录制区再内缩 8px 去圆角阴影
WIN = (277, 25, 1370, 984)
REGION = (285, 26, 1354, 974)
OUT = os.path.join(BASE, "perf", "t3_completion.mp4")

# 补全弹层观察区（屏幕坐标）：光标（≈屏幕 y 600）下方，覆盖弹层全宽（实测弹层 x 587..1233）
WATCH = (560, 525, 680, 300)

# 弹层配色（2026-10-31 沙盒实测，Islands Dark 120%）
POPUP_BG = (39, 40, 43)
POPUP_SEL = (51, 53, 59)

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0


# ---------------------------------------------------------------------------
# 锚点工具
# ---------------------------------------------------------------------------

def grab_rgb(region):
    """全分辨率抓屏 → RGB ndarray（mss 给 BGRA，这里翻转通道）。"""
    with mss.mss() as s:
        mon = {"left": region[0], "top": region[1],
               "width": region[2], "height": region[3]}
        return np.asarray(s.grab(mon))[:, :, :3][:, :, ::-1].astype(int)


def popup_metrics(img):
    """(是否有弹层, 候选文字行数, 弹层矩形)。矩形/行数都相对 WATCH 左上角。"""
    r, g, b = img[:, :, 0], img[:, :, 1], img[:, :, 2]
    bg = (np.abs(r - POPUP_BG[0]) <= 2) & (np.abs(g - POPUP_BG[1]) <= 2) & \
         (np.abs(b - POPUP_BG[2]) <= 2)
    sel = (np.abs(r - POPUP_SEL[0]) <= 3) & (np.abs(g - POPUP_SEL[1]) <= 3) & \
          (np.abs(b - POPUP_SEL[2]) <= 3)
    mask = bg | sel
    rows = np.where(mask.sum(axis=1) > 250)[0]     # 一行弹层底 ≈600px 宽
    if len(rows) < 20:
        return False, 0, None
    y0, y1 = int(rows.min()), int(rows.max())
    cols = np.where(mask[y0:y1 + 1].sum(axis=0) > (y1 - y0) * 0.3)[0]
    if len(cols) < 50:
        return False, 0, None
    x0, x1 = int(cols.min()), int(cols.max())
    lum = img.mean(axis=2)
    tr = np.where((lum[y0:y1 + 1, x0:x1 + 1] > 150).sum(axis=1) > 3)[0]
    n_rows, prev = 0, -99
    for t in tr:
        if t - prev > 6:
            n_rows += 1
        prev = t
    return True, n_rows, (x0, y0, x1, y1)


def wait_popup(min_rows, timeout=3.0, label=""):
    """等补层出现且候选行数达标；超时抛错（不录坏画面）。"""
    t0 = time.time()
    shot = (False, 0, None)
    while time.time() - t0 < timeout:
        shot = popup_metrics(grab_rgb(WATCH))
        if shot[0] and shot[1] >= min_rows:
            print("popup %-9s OK rows=%d rect=%s (+%.2fs)"
                  % (label, shot[1], shot[2], time.time() - t0))
            return shot
        time.sleep(0.1)
    raise RuntimeError("补全弹层未达标（%s）：present=%s rows=%d —— 停录，不录坏画面"
                       % (label, shot[0], shot[1]))


def layout_id(hwnd):
    """窗口线程的键盘布局 ID 低 16 位（0x0409 = en-US）。"""
    u32 = ctypes.windll.user32
    tid = u32.GetWindowThreadProcessId(hwnd, None)
    return u32.GetKeyboardLayout(tid) & 0xFFFF


def require_en_input(hwnd=None):
    """键入前闸门：前台是沙盒 + 布局是 en-US。"""
    if hwnd is None:
        hwnd = foreground_hwnd()
    elif pid_of_hwnd(foreground_hwnd()) != pid_of_hwnd(hwnd):
        if not ensure_foreground(hwnd, tries=4, click_xy=(600, 400), wait=0.6):
            raise RuntimeError("沙盒 IDE 未能置前，停止图输入")
    force_en_us(hwnd)
    time.sleep(0.25)
    lid = layout_id(hwnd)
    if lid != 0x0409:
        force_en_us(hwnd)
        time.sleep(0.4)
        lid = layout_id(hwnd)
    if lid != 0x0409:
        raise RuntimeError("前台输入法布局 0x%04x 不是 en-US(0x0409)——IME 候选会入镜，停录" % lid)


def sandbox_frame():
    pid = sandbox_pid()
    if pid is None:
        raise RuntimeError("找不到沙盒 PID（build/MPSV-GifFresh/.../config/.lock）")
    wins = [r for r in windows() if r[1] == pid and (r[3][2] - r[3][0]) > 1200]
    if not wins:
        raise RuntimeError("沙盒主窗口不在可见窗口列表里（pid=%s）" % pid)
    hwnd, _, title, rect = wins[0]
    print("sandbox pid=%s hwnd=%s rect=%s" % (pid, hwnd, rect))
    return pid, hwnd


def position_window(hwnd):
    """窗口定位到外框 (277,25,1370,984) 并断言；再查四边 8px 无白边（skill 坑 49）。"""
    u32 = ctypes.windll.user32
    u32.MoveWindow(hwnd, WIN[0], WIN[1], WIN[2], WIN[3], True)
    time.sleep(1.0)
    rect = [r[3] for r in windows() if r[0] == hwnd][0]
    if tuple(rect) != (277, 25, 1647, 1009):
        raise RuntimeError("窗口定位不符：%s（期望 (277, 25, 1647, 1009)）" % (rect,))
    img = grab_rgb(REGION)
    h, w = img.shape[:2]
    edges = {"top": img[:4].mean(), "bottom": img[h - 4:].mean(),
             "left": img[:, :4].mean(), "right": img[:, w - 4:].mean()}
    bad = {k: round(float(v), 1) for k, v in edges.items() if v > 120}
    if bad:
        raise RuntimeError("录制区有白边（边带均值 %s）" % bad)
    print("window rect OK + 四边无白边 (%.0f/%.0f/%.0f/%.0f)"
          % (edges["top"], edges["bottom"], edges["left"], edges["right"]))


def copy_current_line():
    """Home / Shift+End / Ctrl+C → 读剪贴板（会临时选中一行，只在片外用）。"""
    pyautogui.press("home")
    pyautogui.keyDown("shift"); pyautogui.press("end"); pyautogui.keyUp("shift")
    pyautogui.hotkey("ctrl", "c")
    time.sleep(0.45)
    text = read_clip()
    pyautogui.press("esc")
    time.sleep(0.15)
    return text


def caret_to_line_77():
    """定位到第 77 行（`from user`）行尾，不用 Ctrl+G（它会塞空行）。

    Ctrl+F 搜唯一 token `selectUserByTrim`（第 74 行）→ Esc → Down×3 → End；
    再用剪贴板断言当前行确实是 `from user`，不符就抛错。
    """
    pyautogui.click(600, 400)
    time.sleep(0.25)
    pyautogui.hotkey("ctrl", "f")
    time.sleep(0.9)
    pyautogui.hotkey("ctrl", "a")
    pyautogui.press("backspace")
    pyautogui.typewrite("selectUserByTrim", interval=0.05)
    time.sleep(0.8)
    pyautogui.keyDown("return"); pyautogui.keyUp("return")
    time.sleep(0.5)
    pyautogui.press("esc")
    time.sleep(0.4)
    for _ in range(3):
        pyautogui.press("down")
        time.sleep(0.06)
    pyautogui.press("end")
    time.sleep(0.3)
    text = copy_current_line()
    if "from user" not in text:
        raise RuntimeError("定位失败：第 77 行实际是 %r" % text)
    print("caret at line 77:", repr(text))


def undo_restore(hwnd, tries=12):
    """撤回本次录入，并断言第 77 行回到 `from user`（只在片外用：会开查找框）。"""
    pyautogui.keyDown("esc"); pyautogui.keyUp("esc")
    for _ in range(tries):
        pyautogui.hotkey("ctrl", "z")
        time.sleep(0.28)
    pyautogui.press("esc")
    caret_to_line_77()


def undo_in_take(tries=12):
    """take 内的收尾：只做 Ctrl+Z + Esc（不导航、不读剪贴板，避免把查找框录进画面）。"""
    pyautogui.keyDown("esc"); pyautogui.keyUp("esc")
    for _ in range(tries):
        pyautogui.hotkey("ctrl", "z")
        time.sleep(0.28)
    pyautogui.press("esc")


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def dry_run_accept(hwnd):
    """开录前空跑：键入 join u → Tab 采纳 → 复制行断言 `join user` → 撤回。

    这一步是**内容锚点的主证**：证明该位置采纳得到的是 ASCII 表名（插件真补全），
    而不是 IME 候选；同时证明撤回后文档回到 from user（没弄脏）。
    """
    require_en_input(hwnd)
    pyautogui.press("enter")
    time.sleep(0.35)
    require_en_input(hwnd)
    pyautogui.typewrite("join u", interval=0.1)
    rows = wait_popup(3, label="dry-run")
    pyautogui.press("tab")
    time.sleep(1.0)
    text = copy_current_line()
    print("dry-run accepted:", repr(text), "rows=", rows[1])
    if "join user" not in text:
        raise RuntimeError("空跑采纳结果不是 `join user`（实际 %r）——补全未生效或被 IME 截胡" % text)
    undo_restore(hwnd)
    print("dry-run undo OK")


def main():
    pid, hwnd = sandbox_frame()
    if not ensure_foreground(hwnd, tries=6, click_xy=(600, 400), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    position_window(hwnd)
    require_en_input(hwnd)

    caret_to_line_77()
    dry_run_accept(hwnd)
    require_en_input(hwnd)
    caret_to_line_77()

    r = Recorder(REGION, OUT)
    time.sleep(1.2)
    r.start()
    time.sleep(0.5)

    move(600, 450, dur=0.4)                         # 1. 光标滑入编辑区（演示轨迹，不点击）
    time.sleep(0.3)
    require_en_input(hwnd)
    pyautogui.press("end")                          # 2. 到 `from user` 行尾
    time.sleep(0.4)
    pyautogui.press("enter")                        # 3. 新行
    time.sleep(0.5)
    require_en_input(hwnd)
    pyautogui.typewrite("join ", interval=0.1)      # 4. join + 空格（空格不触发弹层）
    time.sleep(0.5)
    require_en_input(hwnd)
    pyautogui.typewrite("u", interval=0.12)         # 5. 首字母 → 弹层出现（6 候选 + 提示行）
    wait_popup(4, label="join u")
    time.sleep(1.3)                                 # 6. 全量候选定格
    require_en_input(hwnd)
    pyautogui.typewrite("ser_", interval=0.14)      # 7. 逐字符收窄（5 行 → 2 行）
    wait_popup(1, label="join user_")
    time.sleep(1.4)                                 # 8. 收窄后定格（只剩 user_address）
    pyautogui.press("backspace")                    # 9. 退格回 user（候选恢复）
    time.sleep(1.0)
    require_en_input(hwnd)
    wait_popup(4, label="join user")
    pyautogui.press("tab")                          # 10. Tab 采纳（Enter 会触发平台断言）
    time.sleep(1.6)                                 # 11. 结果定格
    undo_in_take()                                  # 12. 撤回还原（循环无缝，片内只做 Ctrl+Z）
    time.sleep(0.6)

    print("frames:", r.stop())

    # 片外收口：确认撤回干净（第 77 行仍是 from user、78 行仍是 trim，没有残留空行）
    caret_to_line_77()
    pyautogui.press("down")
    time.sleep(0.2)
    l78 = copy_current_line()
    if "<trim" not in l78:
        raise RuntimeError("撤回后第 78 行不是 <trim>（实际 %r）——可能存在残留空行，需还原文件" % l78)
    print("post-take restore OK")


if __name__ == "__main__":
    sys.exit(main())
