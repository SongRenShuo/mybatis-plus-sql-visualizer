# -*- coding: utf-8 -*-
"""N01 Mapper 双向跳转（R15 补录计划 N1）。

分镜（约 9s）：
  1. UserMapper.xml 停在 `<select id="selectUserListByXml">` 行（光标行 25）
  2. 点该行 mapperStatement 跳转图标（黑+青）→ 编辑器落到 UserMapper.java 接口方法行
  3. 点方法行 mapperMethod 跳转图标（黑+红）→ 跳回 XML 语句行
  4. 收尾停在 XML 语句行（与起始同区）

定位纪律：不写死坐标——图标在编辑区 gutter x≈95~152 的窄带里，按「行高 12px 的亮簇」
探测；跳转后目标行被居中，故取最接近编辑区垂直中心的那一簇（scene_t8 同款思路）。
灯泡（💡）在 x>152，天然排除；行号在 x<92，同样排除。
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
from gifkit import Recorder, goto_line, shot  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "n01_mapper_nav.mp4")
TMP = os.path.join(HERE, "perf", "_n01_probe.png")

ICON_X0, ICON_X1 = 94, 153
Y_LO, Y_HI = 150, 800
EDIT_CENTER = 470


def abs_pt(rx, ry):
    return (rx + REGION[0], ry + REGION[1])


def grab_rgb():
    shot(REGION, TMP)
    return np.asarray(Image.open(TMP).convert("RGB"), dtype=int)


def icon_clusters(img):
    """返回 [(cy, cx)]：gutter 窄带里的**彩色**簇（图标）。

    用饱和度（max-min）而不是亮度：跳转图标是黑+青/黑+红，黑色部分亮度只有 20~40，
    按亮度阈值 55 会整块漏掉（实测 clusters 为空）；彩色部分饱和度 > 25 稳定命中。
    """
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
        # 同一行可能并排两个图标（左＝SQL 预览图标，右＝跳转图标），按列间空档切成子簇
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
        g0, g1 = groups[-1]          # 最右子簇＝跳转图标
        cy = Y_LO + (b0 + b1) // 2
        cx = ICON_X0 + (g0 + g1) // 2
        out.append((cy, cx))
    return out


def wait_nav_icon(max_wait=25, prefer_y=EDIT_CENTER):
    deadline = time.time() + max_wait
    last = []
    while time.time() < deadline:
        cl = icon_clusters(grab_rgb())
        last = cl
        if cl:
            cy, cx = min(cl, key=lambda c: abs(c[0] - prefer_y))
            return (cx, cy)
        time.sleep(1.0)
    raise RuntimeError("nav icon not found; clusters=%s" % last[:6])


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    time.sleep(0.5)

    # ---- prep（不入镜）：开 XML、定位语句行、等图标 ----
    from mcpnav import open_file
    open_file("mybatis-plus-demo/src/main/resources/mapper/UserMapper.xml", settle=2.0)
    goto_line(25)
    time.sleep(1.2)
    xml_click = wait_nav_icon(max_wait=30)
    print("xml icon:", xml_click)

    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 1. XML 语句行点跳转图标 → 接口方法行
        pyautogui.moveTo(abs_pt(xml_click[0] - 45, xml_click[1])[0], abs_pt(xml_click[0] - 45, xml_click[1])[1],
                         duration=0.5)
        time.sleep(0.4)
        pyautogui.click(*abs_pt(*xml_click))
        time.sleep(2.4)

        # 2. Java 侧：目标行被居中，取最近中心的图标点回
        java_click = wait_nav_icon(max_wait=15)
        print("java icon:", java_click)
        pyautogui.moveTo(abs_pt(java_click[0] - 45, java_click[1])[0], abs_pt(java_click[0] - 45, java_click[1])[1],
                         duration=0.5)
        time.sleep(0.4)
        pyautogui.click(*abs_pt(*java_click))
        time.sleep(2.4)

        # 3. 收尾：鼠标停到右侧 SQL Visualizer 面板空白区（停代码上会弹粘性文档浮层）
        pyautogui.moveTo(abs_pt(950, 700)[0], abs_pt(950, 700)[1], duration=0.6)
        time.sleep(1.4)
        shot(REGION, os.path.join(HERE, "perf", "n01_final.png"))
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    main()
