# -*- coding: utf-8 -*-
"""IB3 运行期填值（T-DemoGifIbatis GIF 3，扩容）。

分镜（约 15s）：
  1. RuntimeConcatReproController.listByOptionalIf 预览唤起（光标落 `sql` token +
     Alt+Shift+Q 定型链路，计划 §4.3）→ 参数区「运行时值 #1 keyword / #2 minAmount」
     两行 + 「包含这段」勾选（值框需宽参数栏：分栏比例跨预览持久化，已拉宽）
  2. keyword 行填 111 → 点「刷新」→ SQL 出现 AND order_no LIKE '%111%'
  3. minAmount 行填 111 → 点「刷新」→ AND amount >= 111 就地出现
  4. 取消勾 minAmount「包含这段」→ 点「刷新」→ 该整段消失、ORDER BY 仍在
  5. 收尾鼠标停预览面板左栏空白区

断言（计划 P8/P10）：参数区出现「运行时值 #N」行；全程无 #param1；每步刷新后 SQL
指纹变化。填值全部走剪贴板（坑 41）。
区域＝内缩 (285,26,1354,974)。
"""
import os
import sys
import time
import ctypes

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import numpy as np  # noqa: E402
import pyautogui  # noqa: E402
from PIL import Image  # noqa: E402
from gifkit import Recorder, shot, paste  # noqa: E402
from nav import force_en_us  # noqa: E402
from locate import ensure_foreground  # noqa: E402
from mcpnav import open_file  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "ib3_runtime_fill.mp4")
TMP = os.path.join(HERE, "perf", "_ib3_probe.png")

DLG = (367, 166, 1557, 868)

# 宽参数栏布局（region 相对；值框中心取 robot 实测+分栏拉宽后帧实测）
FIELD_KEYWORD = (560, 405)
FIELD_MINAMOUNT = (560, 463)
CB_INCLUDE_MIN = (367, 463)            # minAmount 行「包含这段」勾选框
BTN_REFRESH = (1100, 227)
BTN_CANCEL = (1201, 802)
SQL_BOX = (660, 280, 1260, 560)
BLUE_CB = (378, 405)                   # keyword 行「包含这段」蓝勾（面板就位断言锚）


def abs_pt(rx, ry):
    return (rx + REGION[0], ry + REGION[1])


def grab_rgb():
    shot(REGION, TMP)
    return np.asarray(Image.open(TMP).convert("RGB"), dtype=int)


def fingerprint(box):
    img = grab_rgb()
    x0, y0, x1, y1 = box
    a = img[y0:y1, x0:x1].mean(axis=2)
    h, w = a.shape
    ys = np.linspace(0, h, 9).astype(int)
    xs = np.linspace(0, w, 9).astype(int)
    return np.array([a[ys[i]:ys[i + 1], xs[j]:xs[j + 1]].mean()
                     for i in range(8) for j in range(8)])


def diff_score(a, b):
    return float(np.abs(a - b).max())


def wait_sql_change(prev_fp, max_wait=6.0, thr=3.0):
    deadline = time.time() + max_wait
    last = prev_fp
    while time.time() < deadline:
        time.sleep(0.3)
        last = fingerprint(SQL_BOX)
        if diff_score(prev_fp, last) > thr:
            return last
    return last


def checkbox_blue(img, rx, ry):
    box = img[ry - 10:ry + 10, rx - 10:rx + 10]
    r, g, b = box[:, :, 0], box[:, :, 1], box[:, :, 2]
    return ((b - r > 40) & (b > 110)).sum() > 20


def sandbox_java_pids():
    import subprocess
    ps = ("Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | "
          "Where-Object { $_.CommandLine -like '*MPSV-GifFresh*' } | "
          "Select-Object -ExpandProperty ProcessId")
    out = subprocess.check_output(['powershell', '-NoProfile', '-Command', ps],
                                  text=True, errors='ignore')
    return set(int(x) for x in out.split() if x.strip().isdigit())


def preview_dialog_rect():
    from ctypes import wintypes
    u32 = ctypes.windll.user32
    pids = sandbox_java_pids()
    res = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def cb(h, l):
        if u32.IsWindowVisible(h):
            owner = wintypes.DWORD()
            u32.GetWindowThreadProcessId(h, ctypes.byref(owner))
            txt = ctypes.create_unicode_buffer(256)
            u32.GetWindowTextW(h, txt, 256)
            if owner.value in pids and 'SQL' in txt.value and '预览' in txt.value:
                r = wintypes.RECT()
                u32.GetWindowRect(h, ctypes.byref(r))
                res.append((r.left, r.top, r.right, r.bottom))
        return True

    u32.EnumWindows(cb, 0)
    return res


def sandbox_hwnd():
    from ctypes import wintypes
    u32 = ctypes.windll.user32
    pids = sandbox_java_pids()
    res = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def cb(h, l):
        if u32.IsWindowVisible(h):
            owner = wintypes.DWORD()
            u32.GetWindowThreadProcessId(h, ctypes.byref(owner))
            txt = ctypes.create_unicode_buffer(256)
            u32.GetWindowTextW(h, txt, 256)
            if owner.value in pids and 'mybatis-plus-sql-visualizer-demo' in txt.value:
                res.append(h)
        return True

    u32.EnumWindows(cb, 0)
    return res[0]


def wheel_to_line106(hwnd):
    pyautogui.hotkey('ctrl', 'home')
    time.sleep(0.8)
    u32 = ctypes.windll.user32
    for _ in range(34):
        lp = (700 << 16) | (500 & 0xFFFF)
        u32.PostMessageW(hwnd, 0x020A, ((-120 & 0xFFFF) << 16), lp)
        time.sleep(0.03)
    time.sleep(1.2)


def open_preview(hwnd):
    """光标落 sql token + Alt+Shift+Q（JdbcTemplate 链定型链路）。"""
    pyautogui.click(617, 489)
    time.sleep(0.8)
    pyautogui.hotkey('alt', 'shift', 'q')
    time.sleep(3.6)
    dlg = preview_dialog_rect()
    assert dlg and abs(dlg[0][0] - DLG[0]) < 25 and abs(dlg[0][1] - DLG[1]) < 25, \
        '预览弹窗未就位/漂移: %s' % (dlg,)


def clear_field(pt):
    pyautogui.click(*abs_pt(*pt))
    time.sleep(0.5)
    pyautogui.hotkey('ctrl', 'a')
    time.sleep(0.3)
    pyautogui.press('backspace')
    time.sleep(0.6)


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0.15
    force_en_us()
    time.sleep(0.5)

    hwnd = sandbox_hwnd()
    assert ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7), 'IDE 未置前'

    # 存量预览关掉
    if preview_dialog_rect():
        pyautogui.click(*abs_pt(*BTN_CANCEL))
        time.sleep(1.2)
        pyautogui.click(*abs_pt(*BTN_CANCEL))
        time.sleep(1.0)
        assert not preview_dialog_rect(), '存量预览关不掉'

    # ---- prep：开文件滚到 106、清两个值框、关预览、回 106 落 token ----
    open_file('mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/controller/RuntimeConcatReproController.java', settle=2.5)
    time.sleep(1.0)
    wheel_to_line106(hwnd)
    open_preview(hwnd)
    clear_field(FIELD_KEYWORD)
    clear_field(FIELD_MINAMOUNT)
    pyautogui.click(*abs_pt(*BTN_CANCEL))
    time.sleep(1.2)
    pyautogui.click(*abs_pt(*BTN_CANCEL))
    time.sleep(1.0)
    assert not preview_dialog_rect(), '归一后预览关不掉'
    wheel_to_line106(hwnd)
    pyautogui.moveTo(REGION[0] + 700, REGION[1] + 300, duration=0.3)
    time.sleep(0.4)

    # ---- take ----
    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 1. 唤起预览
        pyautogui.click(617, 489)
        time.sleep(0.8)
        pyautogui.hotkey('alt', 'shift', 'q')
        time.sleep(3.6)
        dlg = preview_dialog_rect()
        assert dlg and abs(dlg[0][0] - DLG[0]) < 25 and abs(dlg[0][1] - DLG[1]) < 25, \
            '预览弹窗 rect 漂移: %s' % (dlg,)
        time.sleep(0.6)
        img = grab_rgb()
        assert checkbox_blue(img, *BLUE_CB), '「包含这段」勾选框不在场（面板未就位）'
        base_fp = fingerprint(SQL_BOX)

        # 2. keyword 填 111 → 刷新 → LIKE '%111%'
        pyautogui.moveTo(abs_pt(*FIELD_KEYWORD), duration=0.4)
        time.sleep(0.3)
        pyautogui.click(*abs_pt(*FIELD_KEYWORD))
        time.sleep(0.5)
        paste('111')
        time.sleep(0.8)
        pyautogui.click(*abs_pt(*BTN_REFRESH))
        fp1 = wait_sql_change(base_fp)
        assert diff_score(base_fp, fp1) > 3, 'keyword 刷新后 SQL 无变化'
        time.sleep(0.8)

        # 3. minAmount 填 111 → 刷新 → AND amount >= 111
        pyautogui.moveTo(abs_pt(*FIELD_MINAMOUNT), duration=0.4)
        time.sleep(0.3)
        pyautogui.click(*abs_pt(*FIELD_MINAMOUNT))
        time.sleep(0.5)
        paste('111')
        time.sleep(0.8)
        pyautogui.click(*abs_pt(*BTN_REFRESH))
        fp2 = wait_sql_change(fp1)
        assert diff_score(fp1, fp2) > 3, 'minAmount 刷新后 SQL 无变化'
        time.sleep(0.8)

        # 4. 取消勾 minAmount「包含这段」→ 刷新 → 整段消失（ORDER BY 仍在）
        pyautogui.moveTo(abs_pt(*CB_INCLUDE_MIN), duration=0.4)
        time.sleep(0.3)
        pyautogui.click(*abs_pt(*CB_INCLUDE_MIN))
        time.sleep(0.8)
        pyautogui.click(*abs_pt(*BTN_REFRESH))
        fp3 = wait_sql_change(fp2)
        assert diff_score(fp2, fp3) > 3, '取消勾后刷新 SQL 无变化'
        time.sleep(1.0)

        # 5. 收尾：鼠标停预览面板左栏空白区
        pyautogui.moveTo(abs_pt(280, 620), duration=0.6)
        time.sleep(1.8)
        shot(REGION, os.path.join(HERE, "perf", "ib3_final.png"))
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    main()
