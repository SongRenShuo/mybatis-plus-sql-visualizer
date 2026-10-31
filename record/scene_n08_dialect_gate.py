# -*- coding: utf-8 -*-
"""N08 R1 dialect-gate 重录（R15 补录计划，放行口径）。

语义（T-DialectGatePass / 4d9fed44 之后）：预览按手动方言 PostgreSQL 渲染
（SQL 呈 `FROM "user"`），点「执行 SQL」后弹出数据源选择器 → 选 MySQL 源
（mp_demo@localhost）→ 结果区给出「方言不一致：…」信息提示但**继续执行**，
SQL 照常提交，返回真实数据行。

分镜（约 10s）：
  1. Alt+Shift+Q 唤起预览：`方言: PostgreSQL（手动指定）` + `FROM "user"`
  2. 点「执行 SQL」→ 数据源选择器弹出（6 个 MySQL 源）
  3. 选末项 mp_demo@localhost → 执行
  4. 结果区：方言不一致信息行 + Result 数据行（证明放行且真的跑通了）

前置（批 0 已定）：
  · 沙盒全局方言 = PostgreSQL（mp-sql-visualizer.xml 的 dialect）
  · 远端 PG 源已临时移出 .idea（否则平台只给它出 runner，见计划 §实施记录）
  · last-used 数据源 = mp_demo@localhost；mp_demo URL 带 ANSI_QUOTES
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import numpy as np  # noqa: E402
import pyautogui  # noqa: E402
from PIL import Image  # noqa: E402
from gifkit import Recorder, shot  # noqa: E402
from nav import force_en_us, goto_col  # noqa: E402
from robot import click_component  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "n08_dialect_gate.mp4")
TMP = os.path.join(HERE, "perf", "_n08_probe.png")

# 数据源选择器：面板大致在右下（批 0 实测 x 715..945 / y 675..925），
# 但位置随弹窗尺寸漂，故动态探测：在右下象限找「亮底面板 + 多行文字」。
# 扫描带只框弹层本体（框太宽会被两侧编辑器深底稀释，band 判不出来）。
SEARCH = (700, 640, 960, 960)       # region 相对 (x0, y0, x1, y1)


def abs_pt(rx, ry):
    return (rx + REGION[0], ry + REGION[1])


def grab_gray():
    shot(REGION, TMP)
    return np.asarray(Image.open(TMP).convert("L"), dtype=int)


def find_popup_last_item(max_wait=8.0):
    """在 SEARCH 区找数据源选择器面板，返回末项点击点（region 相对）。"""
    x0, y0, x1, y1 = SEARCH
    deadline = time.time() + max_wait
    while time.time() < deadline:
        img = grab_gray()
        sub = img[y0:y1, x0:x1]
        rows = sub.mean(axis=1)
        bands, inb, st = [], False, 0
        for i, v in enumerate(rows):
            if v > 52 and not inb:
                st, inb = i, True
            elif v <= 52 and inb:
                if i - st >= 5:
                    bands.append((y0 + st, y0 + i))
                inb = False
        if inb and (y1 - y0) - st >= 5:
            bands.append((y0 + st, y1))
        # 面板至少有 4 行文字（标题 + 3 项以上）
        if len(bands) >= 4:
            # 末项文字带 → 取带中心
            b0, b1 = bands[-1]
            cy = (b0 + b1) // 2
            # 列向：取该带最左文字簇中心（图标在左，点图标或文字都可）
            band = img[b0:b1, x0:x1]
            cols = (band > 52).sum(axis=0)
            xs = np.where(cols > 0)[0]
            cx = x0 + int((xs.min() + xs.max()) / 2) if len(xs) else (x0 + x1) // 2
            return (cx, cy), len(bands)
        time.sleep(0.4)
    raise RuntimeError("数据源选择器未出现")


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()
    time.sleep(0.5)

    from verify import assert_no_dialog, assert_panel_open  # noqa: E402

    # ---- prep（不入镜）----
    try:
        assert_no_dialog()
    except AssertionError:
        pyautogui.press("esc")
        time.sleep(0.8)

    from mcpnav import open_file
    open_file("mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/service/impl/"
              "WrapperAdvancedCoverageServiceImpl.java", settle=1.8)
    goto_col(44, 18)
    pyautogui.press("esc")
    time.sleep(0.5)

    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 1. 唤起预览：PG 方言头 + FROM "user"
        pyautogui.hotkey("alt", "shift", "q")
        time.sleep(3.6)
        assert_panel_open()
        pyautogui.moveTo(600, 300, duration=0.8)
        time.sleep(1.8)
        pyautogui.moveTo(700, 460, duration=0.7)
        time.sleep(1.2)

        # 2. 执行 SQL → 数据源选择器
        click_component("//div[@text='执行 SQL']")
        time.sleep(1.6)
        pt, nbands = find_popup_last_item()
        print("chooser bands:", nbands, "pick:", pt)
        pyautogui.moveTo(abs_pt(pt[0] - 60, pt[1])[0], abs_pt(pt[0] - 60, pt[1])[1], duration=0.5)
        time.sleep(0.5)
        pyautogui.click(*abs_pt(*pt))

        # 3. 放行：信息行「方言不一致：…」+ 执行继续（SQL 已提交到所选源）
        time.sleep(3.4)
        pyautogui.moveTo(430, 640, duration=0.6)   # 收尾停在左栏空白，别压住信息行文字
        time.sleep(2.2)
        pyautogui.moveTo(430, 700, duration=0.5)
        time.sleep(2.0)
        shot(REGION, os.path.join(HERE, "perf", "n08_final.png"))
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    main()
