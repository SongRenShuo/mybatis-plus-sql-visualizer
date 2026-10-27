# -*- coding: utf-8 -*-
"""N08 方言闸门重录（T-DemoGifFix1026，按放行语义——4d9fed44 修复后）。

**语义（20261027 修复后）**：预览由手动方言 PostgreSQL 渲染、目标源为 MySQL 时，
点「执行 SQL」→ 显示「方言不一致」提示但**继续执行**，SQL 送达 MySQL，
由数据库返回原生错误（`[42S02] Table 'user' doesn't exist` 或语法错）。
这与站点正文「只提示、不拦执行」一致。

对应插件修复：`4d9fed44`（SqlPreviewPanelExecution.onExecutionDialectGate 的
MANUAL_CONFLICT / RERENDER_UNAVAILABLE 由阻断改为放行 + 文案「已阻止执行」→「方言不一致」）。

分镜（约 9s）：
  1. Alt+Shift+Q 唤起预览：`方言: PostgreSQL（手动指定）` + `FROM "user"` PG 引号形态
  2. 点「执行 SQL」→ 方言不一致提示行出现（**不再阻断**）
  3. SQL 送达 MySQL → 数据库原生报错入镜（证明「执行真的到了库」）
  4. 收尾定格在结果/报错态

前置：沙盒全局方言 = PostgreSQL（`mp-sql-visualizer.xml` 的 dialect），
目标源 = mpj_demo@localhost（MySQL）。
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import pyautogui  # noqa: E402
from gifkit import Recorder, shot  # noqa: E402
from nav import force_en_us, goto_col  # noqa: E402
from robot import click_component, find_components  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "n08_dialect_gate.mp4")

MSG_REGION = (95, 735, 1180, 55)     # 提示行区域（region 相对）


def abs_reg(r):
    return (r[0] + REGION[0], r[1] + REGION[1], r[2], r[3])


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()
    time.sleep(0.5)

    from verify import Guard, assert_panel_open, assert_no_dialog

    # ---- prep（不入镜）：收起旧预览 → 开目标文件 → 光标压链 token ----
    try:
        assert_no_dialog()
    except AssertionError:
        pyautogui.press("esc")
        time.sleep(0.8)
    pyautogui.click(REGION[0] + 1212, REGION[1] + 798)   # 关预览（实测取消位）
    time.sleep(1.2)

    from mcpnav import open_file
    open_file("mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/service/impl/"
              "WrapperAdvancedCoverageServiceImpl.java", settle=1.8)
    goto_col(44, 18)
    pyautogui.press("esc")
    time.sleep(0.5)

    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 1. 唤起预览：PG 方言头 + FROM "user" 形态
        pyautogui.hotkey("alt", "shift", "q")
        time.sleep(3.6)
        assert_panel_open()
        pyautogui.moveTo(600, 300, duration=0.8); time.sleep(1.6)
        pyautogui.moveTo(600, 420, duration=0.7); time.sleep(1.4)

        # 2. 执行 SQL → 方言不一致提示（放行，不阻断）
        g = Guard(region=abs_reg(MSG_REGION))
        g.mark("msg")
        click_component("//div[@text='执行 SQL']")
        time.sleep(2.6)
        ok, _ = g.changed("msg", wait=0.3, thr=1.5)
        if not ok:
            raise AssertionError("方言提示行未出现，中止")
        time.sleep(1.8)      # 提示行定格

        # 3. SQL 真的送达 MySQL → 数据库原生报错（这是「执行成功到达」的证据）
        time.sleep(5.0)
        pyautogui.moveTo(600, 500, duration=0.8); time.sleep(1.8)
        pyautogui.moveTo(700, 620, duration=0.7); time.sleep(1.8)
        shot(REGION, os.path.join(HERE, "perf", "n08_final.png"))
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    main()
