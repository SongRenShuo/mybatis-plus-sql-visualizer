# -*- coding: utf-8 -*-
"""IB1 iBatis 预览 hero（T-DemoGifIbatis GIF 1）。

分镜（约 13s）：
  1. 点 selectByCondition 语句行 gutter 预览图标 → 预览弹出（四条件已勾，
     WHERE 含 ID = 1 / STATUS = 1 / AGE > 18 三段；username 空 → 无 LIKE 段）
  2. 取消勾 isNotNull: id → WHERE 少 `AND ID = 1`（SQL 区指纹分块差断言）
  3. 勾回 → 段还原
  4. username 条件值框填 tom（剪贴板）→ SQL 出现 USER_NAME LIKE '%tom%'
  5. 收尾鼠标停预览面板左栏空白区（坑 27 落点）

prep：MCP 开 User.xml → 滚到 129 → 开一次预览归一初始态（全选按钮勾满 + 填
#id=1/#status=1/#age=18 + 清空 username 值框 + 像素断言四框已勾）→ 取消关预览
→ 回 129 行。

坐标口径＝**宽参数栏**布局（runtime-fill 轮拉宽的分栏比例跨预览持久化，2026-11-11 实测）。
区域＝内缩 (285,26,1354,974)（坑 49 / 用户黑边反馈）。
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
OUT = os.path.join(HERE, "perf", "ib1_ibatis_preview.mp4")
TMP = os.path.join(HERE, "perf", "_ib1_probe.png")

DLG = (367, 166, 1557, 868)            # 预览弹窗基准（screen，漂移>25px 中止）

# 宽参数栏布局控件（region 相对）
CB_ROWS = [(133, 379), (133, 422), (133, 468), (133, 503)]
CB_ISNOTNULL = (133, 379)
SEL_ALL = (503, 318)                   # 「全选」按钮（2026-11-11 帧实测中心）
USERNAME_FIELD = (390, 422)            # isNotEmpty 值框（宽栏）
PARAM_ID = (398, 588)
PARAM_STATUS = (398, 645)
PARAM_AGE = (398, 702)
BTN_CANCEL = (1201, 802)
SQL_BOX = (740, 280, 1260, 560)        # SQL 区（x0,y0,x1,y1）region 相对


def abs_pt(rx, ry):
    return (rx + REGION[0], ry + REGION[1])


def grab_rgb():
    shot(REGION, TMP)
    return np.asarray(Image.open(TMP).convert("RGB"), dtype=int)


def fingerprint(box):
    """8x8 分块指纹（灰度）。入参=(x0,y0,x1,y1) region 相对。"""
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


def wait_sql_change(prev_fp, max_wait=8.0, thr=3.0):
    """轮询 SQL 区指纹直到与 prev 不同（连续切换时渲染有节流，固定 1.6s 不够）。"""
    deadline = time.time() + max_wait
    last = prev_fp
    while time.time() < deadline:
        time.sleep(0.3)
        last = fingerprint(SQL_BOX)
        if diff_score(prev_fp, last) > thr:
            return last
    return last


def click_toggle_checked(cb, want_checked):
    """点条件复选框并像素验证勾选态到位（未到位补击，防吞击）。"""
    for _ in range(3):
        pyautogui.click(*abs_pt(*cb))
        time.sleep(0.8)
        img = grab_rgb()
        if checked_blue(img, *cb) == want_checked:
            return
    raise RuntimeError('复选框 %s 切换不到位（want_checked=%s）' % (cb, want_checked))


def checked_blue(img, rx, ry):
    box = img[ry - 10:ry + 10, rx - 10:rx + 10]
    r, g, b = box[:, :, 0], box[:, :, 1], box[:, :, 2]
    return ((b - r > 40) & (b > 110)).sum() > 20


def icon_clusters():
    img = grab_rgb()
    sub = img[150:800, 94:153]
    sat = sub.max(axis=2) - sub.min(axis=2)
    mask = sat > 25
    rows = mask.sum(axis=1)
    bands, inb, st = [], False, 0
    for i, v in enumerate(rows):
        if v > 0 and not inb:
            st, inb = i, True
        elif v == 0 and inb:
            if 7 <= i - st <= 30:
                bands.append((150 + st, 150 + i))
            inb = False
    out = []
    for b0, b1 in bands:
        seg = mask[b0 - 150:b1 - 150]
        colhit = seg.sum(axis=0) > 0
        groups, ing, gst = [], False, 0
        for x, hit in enumerate(colhit):
            if hit and not ing:
                gst, ing = x, True
            elif not hit and ing:
                if x - gst >= 3:
                    groups.append((94 + gst, 94 + x))
                ing = False
        if ing and len(colhit) - gst >= 3:
            groups.append((94 + gst, 94 + len(colhit)))
        out.append(((b0 + b1) // 2, groups))
    return out


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


def wheel_to_line129(hwnd):
    pyautogui.hotkey('ctrl', 'home')
    time.sleep(0.8)
    u32 = ctypes.windll.user32
    for _ in range(38):
        lp = (700 << 16) | (500 & 0xFFFF)
        u32.PostMessageW(hwnd, 0x020A, ((-120 & 0xFFFF) << 16), lp)
        time.sleep(0.03)
    time.sleep(1.2)


def close_existing_preview():
    if preview_dialog_rect():
        pyautogui.click(*abs_pt(*BTN_CANCEL))
        time.sleep(1.2)
        pyautogui.click(*abs_pt(*BTN_CANCEL))
        time.sleep(1.0)
        assert not preview_dialog_rect(), '存量预览关不掉'


def open_preview_from_gutter():
    for _ in range(3):
        cl = icon_clusters()
        cands = [c for c in cl if c[1]]
        assert cands, 'gutter 图标不在场'
        target = min(cands, key=lambda t: abs(t[0] - 520))
        y, groups = target
        g0, g1 = groups[0]
        pyautogui.click((g0 + g1) // 2 + REGION[0], y + REGION[1])
        time.sleep(3.6)
        if preview_dialog_rect():
            dlg = preview_dialog_rect()[0]
            assert abs(dlg[0] - DLG[0]) < 25 and abs(dlg[1] - DLG[1]) < 25, \
                '预览弹窗漂移: %s' % (dlg,)
            return
    raise RuntimeError('预览未弹出')


def prep_normalize_state():
    """只归一参数值与 username 值框（复选框改动会被「取消」回滚——2026-11-11 实测，
    条件勾选叙事放进 take 内用「全选」按钮完成）。"""
    open_preview_from_gutter()
    # 填参数（age 用 19：isGreaterThan 语义 age > compareValue 18，填 18 会被引擎裁段）
    for pt, val in ((PARAM_ID, '1'), (PARAM_STATUS, '1'), (PARAM_AGE, '19')):
        pyautogui.click(*abs_pt(*pt))
        time.sleep(0.5)
        pyautogui.hotkey('ctrl', 'a')
        time.sleep(0.3)
        pyautogui.press('backspace')
        time.sleep(0.4)
        paste(val)
        time.sleep(0.6)
    # 清空 username 值框
    pyautogui.click(*abs_pt(*USERNAME_FIELD))
    time.sleep(0.5)
    pyautogui.hotkey('ctrl', 'a')
    time.sleep(0.3)
    pyautogui.press('backspace')
    time.sleep(0.8)
    # 关预览（复选框态不重要；参数值走 paramHistory 持久化）
    pyautogui.click(*abs_pt(*BTN_CANCEL))
    time.sleep(1.2)
    pyautogui.click(*abs_pt(*BTN_CANCEL))
    time.sleep(1.0)
    assert not preview_dialog_rect(), '归一后预览关不掉'


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0.15
    force_en_us()
    time.sleep(0.5)

    hwnd = sandbox_hwnd()
    assert ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7), 'IDE 未置前'

    close_existing_preview()
    open_file('ibatis2-demo/src/main/resources/sqlmap/User.xml', settle=2.5)
    time.sleep(1.0)
    wheel_to_line129(hwnd)
    prep_normalize_state()
    wheel_to_line129(hwnd)
    assert any(c[1] for c in icon_clusters()), '开录自检：gutter 图标不在场'
    pyautogui.moveTo(REGION[0] + 700, REGION[1] + 300, duration=0.3)
    time.sleep(0.5)

    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 1. 点预览图标
        cl = icon_clusters()
        cands = [c for c in cl if c[1]]
        target = min(cands, key=lambda t: abs(t[0] - 520))
        y, groups = target
        g0, g1 = groups[0]
        pyautogui.moveTo(((g0 + g1) // 2) + REGION[0] - 40, y + REGION[1], duration=0.5)
        time.sleep(0.4)
        pyautogui.click(((g0 + g1) // 2) + REGION[0], y + REGION[1])
        time.sleep(3.6)
        dlg = preview_dialog_rect()
        assert dlg and abs(dlg[0][0] - DLG[0]) < 25 and abs(dlg[0][1] - DLG[1]) < 25, \
            '预览弹窗 rect 漂移: %s' % (dlg,)
        time.sleep(0.6)
        base_fp = fingerprint(SQL_BOX)

        # 2. 逐框勾选 → WHERE 逐段生长（全选按钮不触发实时重渲染，2026-11-11 实测）
        fp_prev = base_fp
        for name, cb in (('isNotNull:id', CB_ISNOTNULL),
                         ('isEqual:status', (133, 468)),
                         ('isGreaterThan:age', (133, 503))):
            pyautogui.moveTo(abs_pt(*cb), duration=0.35)
            time.sleep(0.25)
            click_toggle_checked(cb, True)
            fp_now = wait_sql_change(fp_prev)
            assert diff_score(fp_prev, fp_now) > 3, '勾 %s 后 SQL 无变化' % name
            fp_prev = fp_now

        # 3. username 值框填 tom → LIKE '%tom%'
        pyautogui.moveTo(abs_pt(*USERNAME_FIELD), duration=0.4)
        time.sleep(0.3)
        pyautogui.click(*abs_pt(*USERNAME_FIELD))
        time.sleep(0.6)
        paste('tom')
        fp_tom = wait_sql_change(fp_prev)
        assert diff_score(fp_prev, fp_tom) > 3, "填 tom 后 SQL 无变化"
        img = grab_rgb()
        seg = img[SQL_BOX[1]:SQL_BOX[3], SQL_BOX[0]:SQL_BOX[2]]
        rr, gg = seg[:, :, 0], seg[:, :, 1]
        tom_hits = ((rr > 150) & (gg > 150)).sum()
        assert tom_hits > 20, "SQL 区未见 '%tom%' 亮色文本（hits=%d）" % tom_hits

        # 5. 收尾：鼠标停预览面板左栏空白区（坑 27）
        pyautogui.moveTo(abs_pt(280, 640), duration=0.6)
        time.sleep(1.6)
        shot(REGION, os.path.join(HERE, "perf", "ib1_final.png"))
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    main()
