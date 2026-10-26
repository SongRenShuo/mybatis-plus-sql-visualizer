# -*- coding: utf-8 -*-
"""wrapper-preview.gif 重录（T-DemoGifFix1026 批2，hero 片，6 处引用，导出 1440 宽）。

素材：`WrapperAdvancedCoverageServiceImpl#baseActiveUserWrapper`——
      `Wrappers.<User>lambdaQuery().eq(User::getStatus, 1).isNotNull(User::getEmail)`，
      与文档「快速开始」代码块同形。

复核判定（20261026）：旧入库片拍成了 XML mapper 预览（同名文件开错）。本版：
  - MCP 按路径精确开文件 + 预览面板 robot 断言 + 元信息 LAMBDA_QUERY 由主控抽帧目视；
  - 当前面板**已无「预设」行**（20261027 实测，旧 preset_expanded 判据全假阳性，删除）；
  - 分镜：光标压链 → Alt+Shift+Q 唤起 → SQL 展开 → 取消「email IS NOT NULL」
    动态条件（WHERE 实时收缩）→ 勾回（WHERE 还原）→ 定格收尾。

标定（20261027，IDE @ (277,25)）：
  链 token L44 col 18；「email IS NOT NULL」复选框 abs (411,384)；
  「全选」按钮 abs (604,318)。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

import pyautogui  # noqa: E402
from gifkit import Recorder  # noqa: E402
from nav import close_preview, force_en_us, goto_col  # noqa: E402
from robot import find_components  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "perf", "wrapper_preview.mp4")

FILE_REL = ("mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/service/impl/"
            "WrapperAdvancedCoverageServiceImpl.java")
CHAIN_LINE = 44          # .eq(User::getStatus, 1)
CHAIN_COL = 18

CHK_EMAIL = (411, 384)       # 动态条件「email IS NOT NULL」复选框
SELECT_ALL = (604, 318)      # 「全选」按钮（归一动态条件为全勾）
GAZE_CHAIN = (600, 344)      # 编辑器里的链
GAZE_SQL_A = (1100, 500)     # 预览 SQL 区
GAZE_SQL_B = (1100, 620)
GAZE_COND = (450, 411)       # 左侧「动态条件」行


def preview_open() -> bool:
    return len(find_components("//div[@accessiblename='SQL Visualizer - SQL 预览']")) > 0


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）：关预览/弹窗 → MCP 开文件 → 光标压链 token ----
    close_preview()
    try:
        from mcpnav import open_file
        open_file(FILE_REL, settle=1.6)
    except Exception as e:
        print("MCP 打不开，中止（宁可不录也不开错文件）:", e)
        raise
    goto_col(CHAIN_LINE, CHAIN_COL)
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.5)

    # 归一：唤起一次预览，点「全选」保证动态条件全勾，再关掉（勾选态跨预览持久化）
    pyautogui.hotkey("alt", "shift", "q"); time.sleep(3.0)
    if not preview_open():
        raise RuntimeError("归一阶段预览未出现，中止")
    pyautogui.click(*SELECT_ALL); time.sleep(0.8)
    close_preview()
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.6)
    pyautogui.moveTo(*GAZE_CHAIN, duration=0.4); time.sleep(0.6)

    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 1. 编辑器里链上静止一拍（「光标压在链上」）
        pyautogui.moveTo(*GAZE_CHAIN, duration=0.6)
        time.sleep(1.0)

        # 2. Alt+Shift+Q 唤起预览
        pyautogui.hotkey("alt", "shift", "q")
        time.sleep(3.0)
        if not preview_open():
            raise RuntimeError("预览面板未出现，中止本次 take")

        # 3. 看 SQL（SELECT 逐列 + WHERE 两条件）
        pyautogui.moveTo(*GAZE_SQL_A, duration=0.8)
        pyautogui.moveTo(*GAZE_SQL_B, duration=1.0)
        time.sleep(0.6)

        # 4. 取消「email IS NOT NULL」→ WHERE 实时少一行
        from verify import Guard
        g = Guard(region=(780, 270, 700, 380))
        g.mark("sql")
        pyautogui.click(*CHK_EMAIL)
        time.sleep(1.2)
        ok, score = g.changed("sql", wait=0.3, thr=1.5)
        if not ok:
            raise AssertionError(f"取消 email 条件后 SQL 无变化（{score}）——点击落空")
        time.sleep(0.8)
        pyautogui.moveTo(*GAZE_COND, duration=0.7)
        time.sleep(0.8)

        # 5. 勾回 → WHERE 还原（演示实时双向）。基线重置为「已取消」态，
        #    否则勾回后的 SQL 与「取消前」基线相同，changed() 恒判 False
        g.mark("sql")
        pyautogui.click(*CHK_EMAIL)
        time.sleep(1.2)
        ok2, score2 = g.changed("sql", wait=0.3, thr=1.5)
        if not ok2:
            raise AssertionError(f"勾回 email 条件后 SQL 无变化（{score2}）——点击落空")
        time.sleep(1.0)

        # 6. 收尾凝视：SQL 全貌定格
        pyautogui.moveTo(*GAZE_SQL_A, duration=0.8)
        time.sleep(1.2)
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
