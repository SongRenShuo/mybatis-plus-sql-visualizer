# -*- coding: utf-8 -*-
"""批0 验证片：确认 Recorder/assemble 管线在当前沙盒可用。

不产出正式素材，只验证：录制段有真实 UI 变化、帧数对账、转制可跑。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import gifkit
from gifkit import Recorder, wait_stable
from robot import click_component, find_components

REGION = (285, 26, 1354, 974)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "perf", "b0_verify.mp4")


def prep():
    """就位：打开 SQL Visualizer 工具窗，切到 SQL 日志页签。"""
    click_component("//div[@accessiblename='SQL Visualizer']")
    time.sleep(1.2)
    for xp in ("(//div[@accessiblename='SQL 日志'])[1]", "(//div[@accessiblename='SQL 历史'])[1]"):
        r = find_components(xp)
        if r:
            click_component(xp)
            time.sleep(0.8)
            break
    wait_stable(REGION)


def main():
    prep()
    rec = Recorder(REGION, OUT)
    rec.start()
    time.sleep(0.8)
    # 真实 UI 变化：两个页签来回切（裸屏捕获不含光标，必须有画面变化）
    click_component("(//div[@accessiblename='SQL 历史'])[1]")
    time.sleep(1.4)
    click_component("(//div[@accessiblename='SQL 日志'])[1]")
    time.sleep(1.4)
    click_component("(//div[@accessiblename='SQL 历史'])[1]")
    time.sleep(1.0)
    rec.stop()
    print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
