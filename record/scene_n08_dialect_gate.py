# -*- coding: utf-8 -*-
"""N08 方言闸门重录（T-DemoGifFix1026 批3，按 master 实际语义）。

**master 当前语义（20261027 实测，P2/P7 手工走查证伪了计划的「放行送达」预期）**：
预览由手动方言 PostgreSQL 渲染、目标源为 MySQL 时，点「执行 SQL」→ **已阻止执行**——
红色说明行（含「切换数据源」「方言设置」「复制错误信息」三个出口）。cff1e4f9 的
「手动选源放行」在 master 上未生效于该路径（切换数据源出口打开的是 Database 工具窗，
无弹窗选择器）。按实际行为录制，文案回填以本片画面为准（批6）。

分镜（约 6s）：
  1. 预览态定格：头部 `方言: PostgreSQL（手动指定）` + `FROM "user"` PG 引号形态
  2. 点「执行 SQL」→ 红色「已阻止执行」说明行 + 两个出口，定格
  3. 收尾停在拦截态（结构性跳变：首帧预览态、尾帧拦截态）

前置：预览已开（PG 手动方言 + wrapper 链）；批6 回填 alt 时把「只提示不拦」改写为
「已阻止执行 + 两出口」。
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import pyautogui  # noqa: E402
from gifkit import Recorder, shot  # noqa: E402
from nav import force_en_us  # noqa: E402
from robot import find_components  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "n08_dialect_gate.mp4")

# 说明行区域（region 相对）：红色「已阻止执行…」出现在预览底部 y≈745-775
MSG_REGION = (95, 735, 1180, 55)


def abs_reg(r):
    return (r[0] + REGION[0], r[1] + REGION[1], r[2], r[3])


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()
    time.sleep(0.5)

    from nav import goto_col
    from robot import click_component
    from verify import Guard

    # prep：预览收起（让「唤起预览」成为入镜的第一个真实事件）。
    # ⚠️ nav.close_preview 的默认取消坐标是旧弹窗位（点偏会把光标点进编辑器
    # 随机行）——用实测取消位 (1212,798)，并在唤起前把光标压回链 token。
    pyautogui.click(285 + 1212, 26 + 798)
    time.sleep(1.0)
    from mcpnav import open_file
    open_file("mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/service/impl/"
              "WrapperAdvancedCoverageServiceImpl.java", settle=1.4)
    goto_col(44, 18)
    pyautogui.press("esc")
    time.sleep(0.4)
    # 关闭 Database 工具窗（探针残留）——点右缘点亮条带按钮切换，按「Database」
    # 标题亮带判开合（Ctrl+Shift+F12 对它不确定，20261027 实测）
    import numpy as np
    from PIL import Image as _Img

    def db_open():
        shot(REGION, os.path.join(HERE, "perf", "_n08_pre.png"))
        a = np.asarray(_Img.open(os.path.join(HERE, "perf", "_n08_pre.png")).convert("L"), dtype=float)
        # 内容区判据：窗开=树行白字（max 高），窗关=编辑器右侧空白（均匀暗）
        return float(a[120:800, 1300:1325].max()) > 120

    for _ in range(3):
        if not db_open():
            break
        pyautogui.click(285 + 1332, 26 + 120)          # 右缘点亮条带的 Database 按钮
        time.sleep(1.2)
    assert not db_open(), "右侧 Database 工具窗未能关闭，中止"

    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 1. Alt+Shift+Q 唤起预览（PG 方言头 + FROM "user" 形态逐帧渲染）
        pyautogui.hotkey("alt", "shift", "q")
        time.sleep(3.4)
        from robot import find_components as fc
        if not fc("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
            raise RuntimeError("预览面板未出现，中止本次 take")
        time.sleep(0.8)

        # 2. 执行 SQL → 红色「已阻止执行」说明行 + 两出口
        g = Guard(region=abs_reg(MSG_REGION))
        g.mark("msg")
        click_component("//div[@text='执行 SQL']")
        time.sleep(2.5)
        ok, score = g.changed("msg", wait=0.3, thr=1.5)
        if not ok:
            raise AssertionError(f"拦截说明行未出现（差异 {score}），中止")
        time.sleep(1.6)

        # 3. 悬停两个出口（链接悬停态入镜）→ 挪回空白处再定格
        #    （停在出口上会弹 tooltip 盖住按钮排——20261027 尾帧实测）
        pyautogui.moveTo(285 + 1035, 26 + 756, duration=0.3)   # 切换数据源
        time.sleep(0.6)
        pyautogui.moveTo(285 + 1105, 26 + 756, duration=0.3)   # 方言设置
        time.sleep(0.6)
        pyautogui.moveTo(285 + 700, 26 + 730, duration=0.4)    # 空白处，tooltip 散去
        time.sleep(1.8)
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    main()
