# -*- coding: utf-8 -*-
"""N02 mapper XML 编辑辅助（R15 补录计划 N2，T-XmlBraceNested 修复后）。

分镜（约 8s，全部在 UserMapper.xml 第 34 行 `<where><if>` 体内）：
  1. 行尾敲 `#` → 自动补出 `{}`（T-XmlBraceNested 修复的主镜头）
  2. 续敲 `status,` + Ctrl+Space → 补全弹窗（列名带中文注释）
  3. Esc 收弹窗 → Ctrl+Z 还原正文 → 定格回到起始态

已知不在本片内（登记于验收文档 §四）：
  - `#{…,` 后的结构属性键（jdbcType=/javaType=…）在 2026.2 沙盒不出，伴随平台
    AsyncCompletion SEVERE（注入身份断言），待单独排查；
  - 折叠为平台 XmlFoldingBuilder 既有行为，坐标不可靠不录。
收尾纪律：鼠标停右侧 SQL Visualizer 面板空白区。
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

import pyautogui  # noqa: E402
from gifkit import Recorder, goto_line, shot  # noqa: E402
from nav import force_en_us  # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = os.path.join(HERE, "perf", "n02_xml_editing.mp4")

IF_LINE = 34      # `<where><if test="status != null">and status = #{status}` 体内行


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()
    time.sleep(0.5)

    # ---- prep（不入镜）----
    from mcpnav import open_file
    from verify import assert_no_dialog
    try:
        assert_no_dialog()
    except AssertionError:
        pyautogui.press("esc")
        time.sleep(0.8)
    open_file("mybatis-plus-demo/src/main/resources/mapper/UserMapper.xml", settle=2.0)
    goto_line(IF_LINE)
    time.sleep(1.0)
    pyautogui.press("end")
    time.sleep(0.5)

    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 1. 敲 # 自动闭合 {}（修复主镜头）
        pyautogui.typewrite("#", interval=0.15)
        time.sleep(1.8)
        # 2. 补全弹窗：status, + Ctrl+Space
        pyautogui.typewrite("status,", interval=0.12)
        time.sleep(0.5)
        pyautogui.hotkey("ctrl", "space")
        time.sleep(2.4)
        pyautogui.press("esc")
        time.sleep(0.6)
        # 3. 还原正文（#{status,} 共 9 个插入字符，多撤几次）
        for _ in range(10):
            pyautogui.hotkey("ctrl", "z")
            time.sleep(0.25)
        pyautogui.press("esc")
        time.sleep(1.0)
        # 收尾：鼠标停右侧面板空白区
        pyautogui.moveTo(REGION[0] + 950, REGION[1] + 700, duration=0.5)
        time.sleep(1.0)
        shot(REGION, os.path.join(HERE, "perf", "n02_final.png"))
    finally:
        print("frames:", r.stop())


if __name__ == "__main__":
    main()
