# -*- coding: utf-8 -*-
"""R09 XML 预览「回滚 SQL」重录（T-DocsRefresh1023）。

**不用 gutter**：RoleMapper.xml 的 gutter 图标很小（约 8x9 px），合成点击实测三次全部落空。
改为「先点编辑器语句行内，再按 Alt+Shift+Q」——光标落进 `<insert id="insertBatch">` 后
快捷键直接打开该语句预览，不弹候选框。

分镜（约 12 秒）：
1. 点 `<insert id="insertBatch">` 行内，`Alt+Shift+Q` → 预览面板（INSERT + foreach 参数），停 1.8s
2. 「更多」菜单 → 「回滚 SQL」（首项）→ 回滚弹窗（警示 + COUNT 自查 + 逆向 DELETE），停 2.2s
3. 点「复制」→ 复制回滚 SQL，停 0.9s
4. 关闭回滚弹窗，停 0.7s
5. 点蓝色主行动「复制 SQL 并关闭」，停 1.2s

前置（prep）：RoleMapper.xml 打开、goto(28)、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui
from gifkit import Recorder, click, goto_line, wait_stable
from robot import find_components

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "r09_xml_rollback.mp4")

OX, OY = REGION[0], REGION[1]

INSERT_LINE_CLICK = (400, 660)     # <insert id="insertBatch"> 行内（region 相对）
MORE_MENU = (1223, 208)            # 预览顶部「更多」（实测）
ROLLBACK_ITEM = (1215, 236)        # 「更多」菜单首项 = 回滚 SQL
RB_COPY = (890, 706)               # 回滚弹窗「复制」
RB_CLOSE = (987, 706)              # 回滚弹窗「关闭」
COPY_AND_CLOSE = (1115, 806)       # 预览底部蓝色主行动


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def main():
    # prep 清场（20261028 实测：残留的设置窗会整段入镜——上一版首帧就是
    # 「SQL dialect is not configured」的设置页）。设置窗在 robot 树里查不到，
    # 用「有 Apply 按钮」判存在，点 Cancel 关掉。
    if has("//div[@text='Apply']"):
        pyautogui.click(1272, 826)      # 设置窗 Cancel（本轮实测位置）
        time.sleep(1.8)
    pyautogui.press("esc")
    time.sleep(0.5)
    if has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
        pyautogui.click(700, 300)
        time.sleep(0.4)
        pyautogui.press("esc")
        time.sleep(1.0)
    goto_line(28)
    time.sleep(0.8)

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(0.4)
        # 1. 光标进语句行 -> 快捷键开预览
        click(*abs_pt(INSERT_LINE_CLICK), move_dur=0.25, pauses=(0.15, 0.5))
        pyautogui.hotkey("alt", "shift", "q")
        wait_stable(REGION)
        time.sleep(1.8)

        if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
            raise RuntimeError("预览未打开——快捷键失效，中止录制")

        # 2. 更多 -> 回滚 SQL
        #    20261028 实测：更多菜单是 JBPopup，**鼠标点菜单项会被「关闭菜单」吃掉**
        #    （点了坐标菜单就关、什么也没发生）→ 必须键盘：Home 落到首项「回滚 SQL」再 Enter。
        click(*abs_pt(MORE_MENU), move_dur=0.3, pauses=(0.2, 1.2))
        pyautogui.press("home")
        time.sleep(0.5)
        pyautogui.press("return")
        time.sleep(2.2)

        # 3. 复制回滚 SQL
        click(*abs_pt(RB_COPY), move_dur=0.3, pauses=(0.15, 0.9))
        # 4. 关闭回滚弹窗
        click(*abs_pt(RB_CLOSE), move_dur=0.3, pauses=(0.15, 0.8))

        # 5. 复制 SQL 并关闭
        click(*abs_pt(COPY_AND_CLOSE), move_dur=0.3)
        time.sleep(1.6)
        # 二次真实变化：重新唤起预览（展示可重复预览回滚 SQL）
        pyautogui.moveTo(600, 450, duration=0.6); time.sleep(0.8)
        pyautogui.hotkey("alt", "shift", "q")
        time.sleep(3.6)
        pyautogui.moveTo(900, 500, duration=0.8); time.sleep(1.8)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
