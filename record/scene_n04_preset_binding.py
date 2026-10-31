# -*- coding: utf-8 -*-
"""N04 参数预设永久绑定方法（R15 补录计划 N4，P3 可裁项）。

分镜（约 10s）：
  1. 预览弹窗「预设」行展开，选一条预设 → 参数填入、SQL 实时改写
  2. 手工改写 SQL 文本（如把 WHERE 条件值改掉/增删条件）→ 预设行仍命中（仍高亮/仍可一键回填）
  3. 再点该预设 → 参数照常回填（证明绑定在方法而非 SQL 文本）

前置（批 0 踩点）：
  · 已有一条保存的参数预设（锚点方法用 baseActiveUserWrapper 或含双动态条件的方法）
  · 预设行展开态跨浮窗保留（技能坑表）——take 前先归一成收起态
  · 「改写 SQL 文本」用预览内置编辑器直接敲字符（坑 61：别用 Ctrl+G 定位）
风险：画面区分度小（P3 的原因）；改写幅度太大会让预设失配（就达不到「仍命中」的演示点），
改写幅度须控制在预设键容忍范围内——录前先手工试一轮确认「改后仍命中」的幅度。
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import pyautogui  # noqa: E402
from gifkit import Recorder, goto_col, shot  # noqa: E402
from nav import force_en_us  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "n04_preset_binding.mp4")

# TODO(批0 踩点后回填)：
PRESET_ROW = None        # 预设行（region 相对）
PRESET_ITEM = None       # 目标预设条目
SQL_EDIT_SPOT = None     # 预览 SQL 编辑器内可安全改写的空白落点


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()
    time.sleep(0.5)

    # ---- prep（不入镜）：开链文件 → 光标压 token → 唤起预览（3.4s，坑 51）→ 预设行收起态归一 ----
    from mcpnav import open_file
    from verify import assert_panel_open
    open_file("mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/service/impl/"
              "WrapperAdvancedCoverageServiceImpl.java", settle=1.8)
    goto_col(44, 18)
    pyautogui.press("esc")
    time.sleep(0.5)

    r = Recorder(REGION, OUT)
    r.start()
    try:
        pyautogui.hotkey("alt", "shift", "q")
        time.sleep(3.6)
        assert_panel_open()
        time.sleep(1.0)
        # 1. 展开预设行并点目标预设 → 参数回填、SQL 实时改写
        pyautogui.click(*PRESET_ROW)
        time.sleep(1.0)
        pyautogui.click(*PRESET_ITEM)
        time.sleep(1.8)
        # 2. 改写 SQL 文本（小幅度）
        pyautogui.click(*SQL_EDIT_SPOT)
        time.sleep(0.4)
        pyautogui.typewrite("1", interval=0.15)
        time.sleep(1.6)
        # 3. 预设行仍命中 → 再点一次预设 → 参数照常回填
        pyautogui.click(*PRESET_ITEM)
        time.sleep(1.8)
        shot(REGION, os.path.join(HERE, "perf", "n04_final.png"))
    finally:
        print("frames:", r.stop())
    # ---- 收尾（不入镜）：Ctrl+Z 还原改写、取消关预览 ----


if __name__ == "__main__":
    main()
