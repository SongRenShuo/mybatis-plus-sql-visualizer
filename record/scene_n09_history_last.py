# -*- coding: utf-8 -*-
"""N09「历史列表直达末条」新增录制（T-DocsRefresh1023 / 6cd42588）。

分镜（约 6 秒）：
1. SQL 历史二级列表（709 条，列表在顶部），停 1.0s
2. 点工具栏「直达末条」→ 列表一次补齐并滚到最后一条，停 1.8s
3. 点「返回」回文件列表，停 0.9s

前置（prep）：SQL 历史页签、已进入二级列表（某文件的条目视图）、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import Recorder, click
from robot import find_components

REGION = (277, 25, 1370, 984)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n09_history_last.mp4")

OX, OY = REGION[0], REGION[1]
JUMP_LAST = (946, 131)     # 「直达末条」图标（↓≡，region 相对，按导航栏裁剪实测）
BACK = (878, 131)          # 「返回」（←）


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def main():
    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(0.9)
        click(*abs_pt(JUMP_LAST), move_dur=0.35, pauses=(0.2, 2.0))
        click(*abs_pt(BACK), move_dur=0.3, pauses=(0.15, 1.0))
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
