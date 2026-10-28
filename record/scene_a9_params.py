# -*- coding: utf-8 -*-
"""A9 参数回填/动态条件勾选（wrapper-params.gif）——T-DemoGifFix1026 重录版。

复核判定（20261026）：方向与 alt 相反（旧片是「勾上」而非「勾掉」）+ 参数值自带引号
（'tom' → SQL 渲染成 '''tom'''，看着像引号 bug）。

新分镜（T-DemoGifFix1026 §三 #10 / §2.4）：
  素材＝UserMapper.java L37 注解 SQL 预览（#username/#status 参数 + 两个动态条件），
  从**已勾选**开始：
  1. 取消勾选 username → WHERE 实时少一个条件（方向修正）
  2. 勾回 username → WHERE 还原
  3. username 参数框填 tom（**不带引号**——引号由插件按字段类型决定）
  4. status 参数框填 1 → WHERE status = 1
  收尾定格在填完实值的 WHERE。

标定（20261027，沿用 r03 同一预览的实测坐标，abs = region + (285,26)）：
  username 复选框 (411,353)｜username 输入框 (588,462)｜status 输入框 (588,510)
  「全选」按钮 (604,318)｜预览「取消」(1497,824)
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import pyautogui  # noqa: E402
import gifkit  # noqa: E402
from gifkit import Recorder, shot, goto_line  # noqa: E402
from nav import force_en_us  # noqa: E402
from robot import find_components  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "a9_params.mp4")

CHK_USER = (411, 353)        # 动态条件 username 复选框
IN_USER = (588, 462)         # #username 参数框
IN_STATUS = (588, 510)       # #status 参数框
SELECT_ALL = (604, 318)      # 「全选」
CANCEL = (1497, 824)         # 预览「取消」


def preview_open() -> bool:
    return len(find_components("//div[@accessiblename='SQL Visualizer - SQL 预览']")) > 0


def icon_checked(abs_x, abs_y):
    """10px 内芯判勾选（勾=实心蓝 >40，未勾=只剩边框 0，20261027 校准）。"""
    import numpy as np
    from PIL import Image
    path = os.path.join(HERE, "perf", "_a9_icon.png")
    shot(REGION, path)
    img = np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)
    cx, cy = abs_x - REGION[0], abs_y - REGION[1]
    box = img[cy - 5:cy + 5, cx - 5:cx + 5]
    r, g, b = box[..., 0], box[..., 1], box[..., 2]
    return int(((b - r > 60) & (b > 120)).sum()) > 40


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()
    time.sleep(0.4)

    # ---- prep：MCP 开文件 → L37 → 归一（条件全勾）→ 开好预览 ----
    try:
        from mcpnav import open_file
        open_file("mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/mapper/UserMapper.java",
                  settle=1.6)
    except Exception as e:
        print("MCP 打不开，中止:", e)
        raise
    goto_line(37)
    pyautogui.press("esc")
    time.sleep(0.4)
    pyautogui.hotkey("alt", "shift", "q")
    time.sleep(3.2)
    if not preview_open():
        raise RuntimeError("预览未出现，中止")
    for _ in range(3):
        pyautogui.click(*SELECT_ALL)  # 两个动态条件归一为全勾（重渲染有延迟，重试）
        time.sleep(1.5)
        if icon_checked(*CHK_USER):
            break
    assert icon_checked(*CHK_USER), "归一失败：username 仍未勾选"

    r = Recorder(REGION, OUT)
    r.start()
    try:
        time.sleep(0.8)

        # 1. 取消勾选 username → WHERE 实时少一个条件（方向修正）
        pyautogui.click(*CHK_USER)
        time.sleep(1.2)
        assert not icon_checked(*CHK_USER), "取消勾选落空（username 仍为勾选态）"
        time.sleep(0.8)

        # 2. 勾回 username → WHERE 还原
        pyautogui.click(*CHK_USER)
        time.sleep(1.2)
        assert icon_checked(*CHK_USER), "勾回落空（username 仍为未勾态）"
        time.sleep(0.6)

        # 3. username 参数框填 tom（不带引号，引号由插件加）
        pyautogui.click(*IN_USER)
        time.sleep(0.4)
        pyautogui.hotkey("ctrl", "a")
        time.sleep(0.15)
        pyautogui.press("delete")
        time.sleep(0.15)
        pyautogui.typewrite("tom", interval=0.05)
        time.sleep(1.5)

        # 4. status 参数框填 1
        pyautogui.click(*IN_STATUS)
        time.sleep(0.4)
        pyautogui.hotkey("ctrl", "a")
        time.sleep(0.15)
        pyautogui.press("delete")
        time.sleep(0.15)
        pyautogui.typewrite("1", interval=0.05)
        time.sleep(1.8)
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    main()
