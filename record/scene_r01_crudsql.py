# -*- coding: utf-8 -*-
"""R01 CRUD SQL 生成重录（T-DocsRefresh1023）：实体侧右键单主线。

相对 20260914 版（scene_t9）的差异：
- 底部按钮行现在是四枚，含蓝色主行动「复制 SQL 并关闭」——末段改成点它（复制并关窗）；
- 左栏新增「FieldFill 安全模拟」开关（默认关），画面里自然带出，不额外演示；
- 定位优先走 robot 组件树（按按钮文本/页签身份），坐标只作兜底。

分镜（约 12 秒）：
1. 光标在类名行，右键 → 菜单展开（Generate CRUD SQL 可见），停 0.6s
2. 点 Generate CRUD SQL → 候选浮层六项，停 0.8s
3. 点 user · INSERT → 预览面板打开（SQL + 列勾选），停 1.2s
4. 取消勾选 password → INSERT 列清单收缩，停 1.0s
5. 点「复制 SQL 并关闭」→ 面板关闭、剪贴板含完整 INSERT，停 0.8s

前置（prep，不进录制段）：打开 User.java、光标定位类名行、关闭多余工具窗、清气泡。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import Recorder, click, move, wait_stable
from robot import click_component, find_components

REGION = (277, 25, 1370, 984)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "perf", "r01_crudsql.mp4")

# gifkit.click 收的是**屏幕绝对坐标**（region 左上角 277,25）。下面按 region 相对标注，
# 用时加偏移——混用是录制里最大的时间坑（见录制指南第 6 条）。
OX, OY = REGION[0], REGION[1]

CLASS_LINE = (300, 383)         # 类名行（第 27 行），region 相对
MENU_CRUD = (360, 944)          # Generate CRUD SQL 菜单项
POPUP_INSERT = (560, 496)       # 候选浮层 user · INSERT
PASSWORD_CHECKBOX = (140, 507)  # 参数列 password 复选框


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def prep():
    """就位：确认 User.java 在前台、光标在类名行、无弹窗。"""
    if not has("//div[@accessiblename='User.java']"):
        raise RuntimeError("User.java 未打开——prep 失败，中止录制")
    # 关掉可能残留的预览窗
    if has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
        click_component("//div[@accessiblename='取消']")
        time.sleep(1.0)
    # 光标落在类名行
    click(*abs_pt(CLASS_LINE), move_dur=0.2, pauses=(0.1, 0.3))
    wait_stable(REGION)


def main():
    prep()
    rec = Recorder(REGION, OUT)
    rec.start()
    try:
        time.sleep(0.6)

        # 1. 右键呼出菜单
        import pyautogui
        move(*abs_pt(CLASS_LINE), 0.3)
        time.sleep(0.15)
        pyautogui.click(*abs_pt(CLASS_LINE), button="right")
        time.sleep(0.9)

        # 2. Generate CRUD SQL（按菜单项文本定位，坐标会随菜单内容漂移）
        click_component("//div[@text='Generate CRUD SQL']")
        time.sleep(1.0)

        # 3. 选 INSERT
        click(*abs_pt(POPUP_INSERT), move_dur=0.28, pauses=(0.2, 1.4))

        # 4. 取消勾选 password（INSERT 列清单收缩）
        click(*abs_pt(PASSWORD_CHECKBOX), move_dur=0.25, pauses=(0.15, 1.1))

        # 5. 复制 SQL 并关闭（蓝色主行动）
        click_component("//div[@accessiblename='复制 SQL 并关闭']")
        time.sleep(1.0)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
