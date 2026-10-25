# -*- coding: utf-8 -*-
"""N10「加入交流群」重录（T-DocsGifRecord1025 批 1）。

画面：SQL 日志页签（列表有内容）→ 点工具栏交流群图标 → 原生弹框（左：作者微信
二维码；右：交流群二维码）→ 停够二维码渲染完 → 点「关闭」回列表。

**2026-10-25 修订（用户报障「二维码还没加载出来就结束录制」）**：
旧脚本点开弹框后只 `time.sleep(2.4)` 就 Esc 关闭，全片 3.5s。实测二维码是异步加载的
——点开后 1s 时左卡还是「正在加载二维码…」（高对比像素占比 3.6%），3s 时已完整渲染
（23.8%），之后稳定。旧片正好卡在加载完成前，尾帧还把弹框关了，观众看不到二维码。

修法：
  1. 弹框停留拉到 4.0s（留足 3s 加载 + 1s 观赏），
  2. 关弹框改点「关闭」按钮（比 Esc 在画面里自然），
  3. 收尾回到 SQL 日志页签，与首帧同态。

前置（prep）：SQL Visualizer 工具窗已开、SQL 日志页签激活、列表有内容、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import Recorder
from robot import click_component

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n10_community.mp4")


def main():
    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(0.9)                                        # 1. 列表定格
        click_component("//div[@accessiblename='加入交流群']")   # 2. 打开弹框
        # 二维码异步加载：1s 时仍是「正在加载二维码...」，3s 才完整。留 4s 观赏。
        time.sleep(4.0)
        click_component("//div[@text='关闭']")                   # 3. 关闭
        time.sleep(1.2)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
