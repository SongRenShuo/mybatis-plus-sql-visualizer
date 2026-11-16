# -*- coding: utf-8 -*-
"""IB2 iBatis 双向跳转（T-DemoGifIbatis GIF 2）。

分镜（约 9s）：
  1. User.xml 停在 `<select id="selectById">` 行（~:88，光标行 88）
  2. 点该行跳转图标（gutter 右簇）→ 编辑器落到 UserDaoImpl.java 调用行
     `queryForObject("User.selectById", id)`（~:32，落点居中）
  3. 点调用行跳转图标 → 跳回 XML 语句行
  4. 收尾停在 XML 语句行；鼠标停编辑区右侧空白

断言：跳转后 MCP open_paths activeFile 变为 UserDaoImpl.java；跳回后回到 User.xml。
定位纪律：图标= gutter 窄带彩色簇取最右子簇（n01 同款，饱和度判定）。
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
from gifkit import Recorder, shot  # noqa: E402
from nav import force_en_us  # noqa: E402
from locate import ensure_foreground  # noqa: E402
from mcpnav import open_file, open_paths  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "ib2_ibatis_nav.mp4")
TMP = os.path.join(HERE, "perf", "_ib2_probe.png")

ICON_X0, ICON_X1 = 94, 153
Y_LO, Y_HI = 150, 800
EDIT_CENTER = 226   # selectById(:62) 就位后所在 region y


def abs_pt(rx, ry):
    return (rx + REGION[0], ry + REGION[1])


def grab_rgb():
    shot(REGION, TMP)
    return np.asarray(Image.open(TMP).convert("RGB"), dtype=int)


def icon_clusters():
    """gutter 窄带彩色簇；返回 [(band_cy, groups)]，groups=该行并排图标 x 区间列表。

    只有「预览+跳转」双图标行（有 DAO 调用者的语句行）groups>=2；单图标行只有预览。
    """
    img = grab_rgb()
    sub = img[Y_LO:Y_HI, ICON_X0:ICON_X1]
    sat = sub.max(axis=2) - sub.min(axis=2)
    mask = sat > 25
    rows = mask.sum(axis=1)
    bands, inb, st = [], False, 0
    for i, v in enumerate(rows):
        if v > 0 and not inb:
            st, inb = i, True
        elif v == 0 and inb:
            if 7 <= i - st <= 30:
                bands.append((st, i))
            inb = False
    out = []
    for b0, b1 in bands:
        seg = mask[b0:b1]
        colhit = seg.sum(axis=0) > 0
        groups, ing, gst = [], False, 0
        for x, hit in enumerate(colhit):
            if hit and not ing:
                gst, ing = x, True
            elif not hit and ing:
                if x - gst >= 3:
                    groups.append((gst, x))
                ing = False
        if ing and len(colhit) - gst >= 3:
            groups.append((gst, len(colhit)))
        if not groups:
            continue
        groups = [(ICON_X0 + g0, ICON_X0 + g1) for g0, g1 in groups]
        out.append((Y_LO + (b0 + b1) // 2, groups))
    return out


def cluster_sig(img, cy, g0, g1):
    """簇颜色签名：purple（预览图标 Q 部）/ dark_ratio（插件黑鸟箭头主体）/ green_circle（IDEA implement）。"""
    box = img[max(0, cy - 11):cy + 11, max(0, g0 - 8):g1 + 8]
    if box.size == 0:
        return (0.0, 0.0, 0.0)
    r, gg, b = box[:, :, 0], box[:, :, 1], box[:, :, 2]
    gray = box.mean(axis=2)
    total = box.shape[0] * box.shape[1]
    purple = float((((b - r) > 30) & (r > 90)).sum()) / total
    dark = float((gray < 60).sum()) / total
    green = float((((gg - r) > 30) & ((gg - b) > 15) & (gg > 90)).sum()) / total
    return (purple, dark, green)


def icon_clusters_from(img):
    """icon_clusters 的带图版本（避免重复截屏）。"""
    sub = img[Y_LO:Y_HI, ICON_X0:ICON_X1]
    sat = sub.max(axis=2) - sub.min(axis=2)
    mask = sat > 25
    rows = mask.sum(axis=1)
    bands, inb, st = [], False, 0
    for i, v in enumerate(rows):
        if v > 0 and not inb:
            st, inb = i, True
        elif v == 0 and inb:
            if 7 <= i - st <= 30:
                bands.append((st, i))
            inb = False
    out = []
    for b0, b1 in bands:
        seg = mask[b0:b1]
        colhit = seg.sum(axis=0) > 0
        groups, ing, gst = [], False, 0
        for x, hit in enumerate(colhit):
            if hit and not ing:
                gst, ing = x, True
            elif not hit and ing:
                if x - gst >= 3:
                    groups.append((gst, x))
                ing = False
        if ing and len(colhit) - gst >= 3:
            groups.append((gst, len(colhit)))
        if not groups:
            continue
        out.append((Y_LO + (b0 + b1) // 2, [(ICON_X0 + g0, ICON_X0 + g1) for g0, g1 in groups]))
    return out


def wait_nav_icon(max_wait=25, prefer_y=EDIT_CENTER, side='xml'):
    """等跳转图标（语义分类，不假设左右序）：
    side='xml'：配对行（groups>=2）里不带紫色的那枚＝跳转箭头；
    side='java'：x<130 且 dark_ratio>0.2 的簇＝黑鸟反向箭头（IDEA implement 绿圈被排除）。"""
    deadline = time.time() + max_wait
    last = []
    while time.time() < deadline:
        img = grab_rgb()
        cl = icon_clusters_from(img)
        last = [(c[0], c[1]) for c in cl]
        cands = []
        for cy, groups in cl:
            for g0, g1 in groups:
                purple, dark, green = cluster_sig(img, cy, g0, g1)
                if side == 'xml':
                    if len(groups) >= 2 and purple < 0.02:
                        cands.append((cy, g0, g1))
                else:
                    if g0 < 130 and dark > 0.2 and green < 0.05:
                        cands.append((cy, g0, g1))
        if cands:
            cy, g0, g1 = min(cands, key=lambda t: abs(t[0] - prefer_y))
            return ((g0 + g1) // 2, cy)
        time.sleep(0.8)
    raise RuntimeError("nav icon not found (side=%s); clusters=%s" % (side, last[:6]))


def sandbox_java_pids():
    import subprocess
    ps = ("Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | "
          "Where-Object { $_.CommandLine -like '*MPSV-GifFresh*' } | "
          "Select-Object -ExpandProperty ProcessId")
    out = subprocess.check_output(['powershell', '-NoProfile', '-Command', ps],
                                  text=True, errors='ignore')
    return set(int(x) for x in out.split() if x.strip().isdigit())


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


def active_file():
    import json
    raw = open_paths()
    if isinstance(raw, str):
        raw = json.loads(raw)
    return raw.get('activeFilePath', '')


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


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0.15
    force_en_us()
    time.sleep(0.5)

    hwnd = sandbox_hwnd()
    assert ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7), 'IDE 未置前'

    # 存量预览弹窗先关（GIF1 收尾预览未关；弹窗开着时 gutter 探测全是参数区假簇）
    if preview_dialog_rect():
        pyautogui.click(285 + 1201, 26 + 802)
        time.sleep(1.2)
        pyautogui.click(285 + 1201, 26 + 802)
        time.sleep(1.0)
        assert not preview_dialog_rect(), '存量预览关不掉'

    # ---- prep（不入镜） ----
    open_file('ibatis2-demo/src/main/resources/sqlmap/User.xml', settle=2.5)
    time.sleep(1.0)
    pyautogui.hotkey('ctrl', 'home')
    time.sleep(0.8)
    u32 = ctypes.windll.user32
    for _ in range(18):
        lp = (700 << 16) | (500 & 0xFFFF)
        u32.PostMessageW(hwnd, 0x020A, ((-120 & 0xFFFF) << 16), lp)
        time.sleep(0.03)
    time.sleep(1.2)
    xml_click = wait_nav_icon(max_wait=30, side='xml')
    print('xml nav icon:', xml_click)
    assert 'User.xml' in active_file(), 'active file 不是 User.xml'

    pyautogui.moveTo(REGION[0] + 700, REGION[1] + 300, duration=0.3)
    time.sleep(0.4)

    # ---- take ----
    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 1. XML 语句行点跳转图标 → UserDaoImpl 调用行
        pyautogui.moveTo(abs_pt(xml_click[0] - 45, xml_click[1]), duration=0.5)
        time.sleep(0.4)
        pyautogui.moveTo(abs_pt(xml_click[0] - 12, xml_click[1]), duration=0.4)
        time.sleep(0.5)
        pyautogui.click(*abs_pt(*xml_click))
        time.sleep(3.4)
        java_click = wait_nav_icon(max_wait=15, side='java', prefer_y=470)
        print('java nav icon:', java_click)
        af = active_file()
        assert 'UserDaoImpl' in af, '正向跳转后 active file=%s' % af

        # 2. Java 调用行点跳转图标 → 跳回 XML 语句行
        pyautogui.moveTo(abs_pt(java_click[0] - 45, java_click[1]), duration=0.5)
        time.sleep(0.4)
        pyautogui.moveTo(abs_pt(java_click[0] + 12, java_click[1]), duration=0.4)
        time.sleep(0.5)
        pyautogui.click(*abs_pt(*java_click))
        time.sleep(3.4)
        back_icon = wait_nav_icon(max_wait=15, side='xml', prefer_y=470)
        print('back xml icon:', back_icon)
        af2 = active_file()
        assert 'User.xml' in af2, '反向跳转后 active file=%s' % af2

        # 3. 收尾：鼠标停编辑区右侧空白（stop selectById 语句行附近）
        pyautogui.moveTo(abs_pt(1050, 640), duration=0.6)
        time.sleep(2.2)
        shot(REGION, os.path.join(HERE, "perf", "ib2_final.png"))
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    main()
