# -*- coding: utf-8 -*-
"""坐标定位三件套：不靠目测。

设计依据（2026-10-12 大范围录制实测）：本轮所有「点空 / 点错」都出在**目测坐标**上——
弹窗按钮行目测偏低 48px（连点三次全落空）、开文件扫掠的余点点进代码区、点错树节点；
而三种「按证据算」的定位法**一次就对**。本模块把它们固化：

  - `window_rect()`   —— 对话框/浮窗不是进程主窗口，按 PID/标题用 EnumWindows 取矩形；
  - `button_row_y()`  —— 弹窗底部按钮行恒在窗口底边上方约 39px（弹窗高度随内容变，目测必错）；
  - `bright_band_y()` —— 像素剖面：逐行取均值找最亮连续段（按钮行/工具条这类亮文字带）。

坐标系：全部是**屏幕物理像素**，与 `pyautogui` 的点击坐标同一坐标系（本模块已声明 DPI 感知）。

**更高一档的定位（2026-10-13 新增，见同目录两个模块）**——本模块是**几何推断**（窗口矩形 /
底部偏移 / 像素剖面），控件一换布局就失效；下面两条改成**按外观/按组件身份**找：

  - `anchor.py`  —— **图像锚点**：把目标控件截成小模板，`find()`/`click_anchor()` 用
    `cv2.matchTemplate`（灰度 + 多尺度）在屏上找回来。零 IDE 侧改动，**立即可用**；
    注意：需要 `opencv-python`（本机原先没装，`pyautogui.locateCenterOnScreen(confidence=)` 也因此一直不可用）。
  - `robot.py`   —— **组件树定位**：直连 IDE 内 robot-server 的 HTTP/JSON（`POST /xpath/components`），
    按 XPath/类名/文本找 Swing 组件；返回的 x/y 是**父组件内相对坐标**，沿祖先链求和成
    屏幕物理坐标后才交给 pyautogui。已用 2026.2.1 真机切换「SQL 历史」页签验证；
    装法与端口见该模块 docstring。

选型与实测对比见 `docs/调研报告/20261013-2130-演示录制点击定位提效工具调研-调研报告.md`。
"""
import ctypes
from ctypes import wintypes

import mss
import numpy as np
import PIL.Image

ctypes.windll.shcore.SetProcessDpiAwareness(2)
_u32 = ctypes.windll.user32
_ENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)


def windows():
    """枚举可见顶层窗口 → `[(hwnd, pid, title, (l, t, r, b))]`。

    对话框/浮窗**不是进程主窗口**——`Get-Process.MainWindowHandle` 看不到它们，
    只能用 EnumWindows + `GetWindowThreadProcessId` 按 PID 过滤。
    """
    out = []

    def _cb(hwnd, _lparam):
        if not _u32.IsWindowVisible(hwnd):
            return True
        n = _u32.GetWindowTextLengthW(hwnd)
        if n == 0:
            return True
        buf = ctypes.create_unicode_buffer(n + 1)
        _u32.GetWindowTextW(hwnd, buf, n + 1)
        pid = wintypes.DWORD(0)
        _u32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        r = wintypes.RECT()
        _u32.GetWindowRect(hwnd, ctypes.byref(r))
        out.append((int(hwnd), int(pid.value), buf.value, (r.left, r.top, r.right, r.bottom)))
        return True

    _u32.EnumWindows(_ENUMPROC(_cb), 0)
    return out


def window_rect(title=None, pid=None, min_size=(300, 300), max_size=(4000, 3000), pick="largest"):
    """按标题关键字（子串、大小写不敏感）或 PID 取窗口屏幕矩形 `(l, t, r, b)`；找不到返回 None。

    同一 PID 下常同时有主窗口与对话框：主窗口大、对话框小。
    `pick` 取 `"largest"`（主窗口）或 `"smallest"`（对话框）；`min_size`/`max_size` 按宽高过滤，
    用来把浮窗、提示气泡这类小窗排除掉。

    ```python
    rect = window_rect(pid=ide_pid, pick="smallest", min_size=(800, 600))  # 设置对话框
    ```
    """
    cands = []
    for hwnd, wpid, wtitle, rect in windows():
        w, h = rect[2] - rect[0], rect[3] - rect[1]
        if pid is not None and wpid != pid:
            continue
        if title is not None and title.lower() not in wtitle.lower():
            continue
        if not (min_size[0] <= w <= max_size[0] and min_size[1] <= h <= max_size[1]):
            continue
        cands.append(rect)
    if not cands:
        return None
    key = (lambda r: (r[2] - r[0]) * (r[3] - r[1]))
    return max(cands, key=key) if pick == "largest" else min(cands, key=key)


def foreground_hwnd():
    """当前前台窗口句柄。"""
    import ctypes
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
    return int(ctypes.windll.user32.GetForegroundWindow())


def pid_of_hwnd(hwnd):
    """取窗口所属进程 PID。"""
    import ctypes
    from ctypes import wintypes
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
    pid = wintypes.DWORD(0)
    ctypes.windll.user32.GetWindowThreadProcessId(ctypes.c_void_p(hwnd), ctypes.byref(pid))
    return int(pid.value)


def ensure_foreground(hwnd, tries=5, click_xy=None, wait=0.6):
    """把 `hwnd` 弄到前台并验证；成功返回 True。

    **判据按 PID 比、不按句柄比**（2026-10-12 修正）：目标 IDE 弹出模态对话框时，
    `GetForegroundWindow()` 返回的是**那个对话框**（同进程的另一个窗口），按句柄比会误判成「没置前」——
    本轮 A13 就因此把「其实已在最前」的 IDE 判成失败，白折腾一轮。

    **为什么必须有这一步**：鼠标点击只看 z 序（TOPMOST 即可），但**键盘快捷键要求焦点**——
    沙盒不在前台时，`Ctrl+Shift+N` / `Alt+Shift+Q` 这些键会送给别的窗口，于是「开文件没反应、
    预览不出」而**不报任何错**（本轮 A13 的 prep 就栽在这：前台是任务栏，开文件后标题仍是原文件）。
    置顶 ≠ 前台，两者要分别保证。

    处置顺序：`SetWindowPos(TOPMOST)` → 点一次窗口内无害位置取焦（默认标题栏）→ 校验前台句柄；
    不中就重试。点击取焦比 `SetForegroundWindow` 可靠——后者受 Windows 前台锁定限制，常静默失败。
    """
    import ctypes
    import time
    from ctypes import wintypes
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
    u32 = ctypes.windll.user32
    u32.SetWindowPos.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                 ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
    # ① 置顶（保证 z 序在上，鼠标点击不会被别的窗口截走）
    u32.SetWindowPos(ctypes.c_void_p(hwnd), ctypes.c_void_p(-1), 0, 0, 0, 0,
                     0x0001 | 0x0002 | 0x0010)   # NOSIZE|NOMOVE|NOACTIVATE
    time.sleep(0.3)
    r = wintypes.RECT()
    u32.GetWindowRect(ctypes.c_void_p(hwnd), ctypes.byref(r))
    spot = click_xy or (r.left + (r.right - r.left) // 2, r.top + 12)   # 标题栏中点
    # ② 点击取焦 + 校验
    import pyautogui
    want_pid = pid_of_hwnd(hwnd)
    for _ in range(max(1, tries)):
        if foreground_hwnd() == hwnd or pid_of_hwnd(foreground_hwnd()) == want_pid:
            return True
        pyautogui.click(*spot)
        time.sleep(wait)
    return foreground_hwnd() == hwnd or pid_of_hwnd(foreground_hwnd()) == want_pid
def pid_by_cmdline(marker, name="java.exe"):
    """按命令行特征找进程 PID（返回最小者，找不到返回 None）。

    **为什么必须限定 PID**：`window_rect(pid=None, pick='smallest')` 会把**别人的**同尺寸窗口也纳入
    候选——本轮 A13 就因此把 ZCode 自己的 `ZCodeTpsFooter`（993×519，与预览弹窗尺寸相近）当成了预览弹窗，
    后续按它算出的按钮坐标全错，点击还打进了那个窗口。

    ⚠️ **找沙盒 PID 请改用 `sandbox_pid()`**（2026-10-12）：沙盒改走 `IDEA_VM_OPTIONS` 后命令行里
    既没有沙盒路径也没有 `-Didea.*.path`，本函数对沙盒恒返回 None。
    """
    import subprocess
    ps = ("Get-CimInstance Win32_Process -Filter \"Name='%s'\" | "
          "Where-Object { $_.CommandLine -like '*%s*' } | "
          "Select-Object -ExpandProperty ProcessId" % (name, marker))
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                             capture_output=True, text=True, timeout=20).stdout
    except Exception:
        return None
    pids = [int(x) for x in out.split() if x.strip().isdigit()]
    return min(pids) if pids else None
def sandbox_pid(name="MPSV-GifFresh"):
    """沙盒 IDE 的 PID：读沙盒 `config/.lock`（IDE 自己写入的实例 PID）；找不到返回 None。

    **为什么不再用命令行判据**（2026-10-12 修正）：沙盒改用 `IDEA_VM_OPTIONS` 环境变量启动后，
    命令行只剩一个裸 `idea64.exe`（路径全在 vmoptions 文件里），
    `pid_by_cmdline("MPSV-GifFresh")` 与 `-Didea.*.path` 判据**双双恒返回 None**。
    `.lock` 由 IDE 自身维护、与配置目录一一对应，天然把日常 IDE 排除在外。
    """
    import os
    here = os.path.dirname(os.path.abspath(__file__))
    lock = os.path.join(here, os.pardir, "build", name,
                        "mybatis-plus-sql-visualizer", "IU-2026.2.1", "config", ".lock")
    try:
        with open(lock) as f:
            txt = f.read().strip()
    except OSError:
        return None
    return int(txt) if txt.isdigit() else None


def close_preview(pid=None, wait=1.0):
    """关掉 SQL 预览弹窗并**校验真的关了**（EnumWindows 里那个窗口消失）；成功返回 True。

    **为什么不点「取消」按钮**（2026-10-12 实测）：预览弹窗是**无边框窗口**，窗口矩形比可见
    边界大约 50px，按钮行目测与旧标定都会落空（旧坐标 `(1485,828)` 实测点空）。落空的代价
    不是「没关」这么轻——旧弹窗会留着上一轮的结果页签，下一轮的「执行计划」被塞进同一个弹窗
    成 `Result 2`，内嵌结果网格列被截断。
    改「点弹窗体内 → Esc」（弹窗拿到焦点时 Esc 才关得掉），判据＝窗口消失，不看画面像不像。
    """
    import time
    import pyautogui
    pid = pid or sandbox_pid()
    main = window_rect(pid=pid, pick="largest", min_size=(800, 600))
    pop = window_rect(pid=pid, pick="smallest", min_size=(600, 400))
    if not pop or pop == main:
        return True
    pyautogui.click(pop[0] + 120, pop[1] + 600)      # 弹窗左栏空白，只取焦点
    time.sleep(0.4)
    pyautogui.keyDown("esc")
    pyautogui.keyUp("esc")
    time.sleep(wait)
    pop2 = window_rect(pid=pid, pick="smallest", min_size=(600, 400))
    return not pop2 or pop2 == main


def button_row_y(rect, offset=39):
    """弹窗底部按钮行的屏幕 y ＝ 窗口底边 − offset。

    **为什么不能目测**：弹窗高度随内容变（SQL 预览面板这类），按钮行位置随之浮动；
    本轮目测写死的值偏低 48px，连点三次全落空。`offset=39` 为实测口径（A13/A29 两次吻合）。
    取到 `window_rect(...)` 后即可算出按钮行，再用 `bright_band_y()` 复核。
    """
    return rect[3] - offset


def grab(region, path=None):
    """抓 `region=(left, top, width, height)` → 灰度 numpy 数组；给了 `path` 同时存 PNG。"""
    with mss.mss() as sct:
        img = sct.grab({'left': region[0], 'top': region[1],
                        'width': region[2], 'height': region[3]})
        if path:
            mss.tools.to_png(img.rgb, img.size, output=path)
    arr = np.frombuffer(img.rgb, dtype=np.uint8)
    arr = arr.reshape(img.size[1], img.size[0], len(arr) // (img.size[0] * img.size[1]))
    return arr[:, :, :3].mean(axis=2)


def bright_band_y(src, x0, x1, y0, y1, thr=35.0, min_len=4):
    """像素剖面定位：在 `x0..x1` 列、`y0..y1` 行内逐行取灰度均值，返回**最亮连续段**的 `(y_start, y_end)`。

    `src` 传灰度数组（`grab()`）或 PNG 路径。用于「文字比背景亮」的水平条带——
    实测按钮文字行均值 47~60、深色面板背景 26，故默认阈值 35 能干净分开；
    暗色主题下**别用「空白≈0」的直觉设阈值**（收起态本底噪声就有 13）。

    找不到亮行返回 `None`。
    """
    a = np.asarray(PIL.Image.open(src).convert('L'), dtype=float) if isinstance(src, str) else src
    prof = a[y0:y1, x0:x1].mean(axis=1)
    bright = prof > thr
    best, start = None, None
    for i, on in enumerate(bright):
        if on and start is None:
            start = i
        if (not on or i == len(bright) - 1) and start is not None:
            end = i - 1 if not on else i
            if best is None or (end - start) > (best[1] - best[0]):
                best = (start, end)
            start = None
    if best is None or best[1] - best[0] + 1 < min_len:
        return None
    return y0 + best[0], y0 + best[1]


def bright_band_x(src, y0, y1, x0, x1, thr=45.0, min_gap=12, min_w=8):
    """按钮行上的**亮簇**（按钮文字）→ 返回 [(x_start, x_end), ...]（屏幕 x，左→右）。

    `bright_band_y()` 的列向对偶：给定按钮行的 y 带，在 x0..x1 内逐列取灰度均值，
    把超过 thr 的列聚成簇（间隔 < min_gap 归并），丢掉宽度 < min_w 的噪点。
    用于**不写死按钮 x**——弹窗宽度随内容变，右对齐的按钮相对 x 也跟着变；
    本轮 A13 就因为按「窗口相对 979」硬算，把偏移算了两遍、点到空白处（after_click 当场报落空）。
    """
    a = np.asarray(PIL.Image.open(src).convert('L'), dtype=float) if isinstance(src, str) else src
    prof = a[y0:y1, x0:x1].mean(axis=0)
    cols = prof > thr
    runs, start = [], None
    for i, on in enumerate(cols):
        if on and start is None:
            start = i
        if (not on or i == len(cols) - 1) and start is not None:
            runs.append((start, i - 1 if not on else i))
            start = None
    merged = []
    for s0, e0 in runs:
        if merged and s0 - merged[-1][1] < min_gap:
            merged[-1] = (merged[-1][0], e0)
        else:
            merged.append((s0, e0))
    return [(x0 + s0, x0 + e0) for s0, e0 in merged if e0 - s0 + 1 >= min_w]
def button_click_point(rect, x_in_window, offset=39):
    """弹窗按钮的屏幕点击点：`x_in_window` 传窗口相对 x（按钮中心），y 由 `button_row_y()` 算。"""
    return rect[0] + x_in_window, button_row_y(rect, offset)


if __name__ == '__main__':
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == 'windows':
        for hwnd, pid, title, rect in windows():
            print('%-8d %-8d %-52s %s' % (hwnd, pid, title[:50], rect))
    else:
        print(__doc__)
