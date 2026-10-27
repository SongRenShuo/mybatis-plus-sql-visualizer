# -*- coding: utf-8 -*-
"""N12「展示脱敏」新增录制（T-DocsRefresh1023 / R60）。

前置（硬）：SQL 日志列表里已有含 **password / 手机号** 的条目
（喂 `http://127.0.0.1:8081/user/list` 即可，demo 应用需在跑）。

分镜（约 10 秒）：
1. SQL 日志列表（明文 password 值），停 1.6s
2. 设置页 → SQL Log → 勾选「展示与导出脱敏」，停 1.8s
3. 关设置回列表 → 敏感值已变 `***`，停 2.0s
4. 再回设置取消勾选 → 恢复明文，停 1.6s

录制后必须还原勾选状态（脚本末尾负责）。

坑（本轮实测）：设置树滚动（滚轮/拖动/PageDown）全部无效；内容区过滤框是补全输入、
两词查询返回 "Nothing to show"。定位靠「设置搜索框输单词 Visualizer → 点 SQL Log」。

**坐标待现场标定**：MASK_CHECKBOX / SETTINGS_OK 是估算值，首次运行前必须先手工开一次设置页
并截图量取（上一轮没走到这一步，因为设置树导航卡住了）。若坐标仍不准，退路是：
手工开着设置页、脚本只录「勾选 → 确定 → 列表变化」这一段，不做页面导航。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui
from gifkit import Recorder, click
from robot import find_components

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n12_display_mask.mp4")

OX, OY = REGION[0], REGION[1]

MASK_CHECKBOX = (520, 480)       # 设置页「展示与导出脱敏」复选框（进入 SQL Log 页后实测校正）
SETTINGS_OK = (920, 706)         # 设置页 OK


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def main():
    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(1.6)                       # 列表明文态定格

        pyautogui.hotkey("ctrl", "alt", "s")  # 开设置
        time.sleep(3.0)
        # 设置页内导航（树滚动失效 → 用搜索框输单词）
        pyautogui.moveTo(*abs_pt((150, 78)), duration=0.3)
        pyautogui.click(*abs_pt((150, 78)))
        time.sleep(0.6)
        pyautogui.write("Visualizer", interval=0.06)
        time.sleep(2.0)
        pyautogui.press("escape")             # 收补全下拉
        time.sleep(0.6)

        click(*abs_pt(MASK_CHECKBOX), move_dur=0.3, pauses=(0.2, 1.6))
        click(*abs_pt(SETTINGS_OK), move_dur=0.3, pauses=(0.2, 2.2))   # 确定 → 回列表（已打码）

        pyautogui.hotkey("ctrl", "alt", "s")  # 再开设置还原
        time.sleep(3.0)
        click(*abs_pt(MASK_CHECKBOX), move_dur=0.3, pauses=(0.2, 1.2))
        click(*abs_pt(SETTINGS_OK), move_dur=0.3, pauses=(0.2, 1.6))
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
