# -*- coding: utf-8 -*-
"""白条第三轮复验（20261101）共用胶水：复用走查轮 walk_util，只换沙盒名与证据帧目录。

沿用纪律：坐标动态定位（robot > anchor > 像素带 > 几何），输入前断言前台是沙盒，
截图全屏落 build/MPSV-WhitebarVerify3-shots/。
"""
import os
import sys

RECORD_DIR = os.path.dirname(os.path.abspath(__file__))
if RECORD_DIR not in sys.path:
    sys.path.insert(0, RECORD_DIR)

import walk_util as W  # noqa: E402

REPO = W.REPO
W.SANDBOX_NAME = "MPSV-WhitebarVerify3"
W.SHOTS = os.path.join(REPO, "build", "MPSV-WhitebarVerify3-shots")

SANDBOX_NAME = W.SANDBOX_NAME
SHOTS = W.SHOTS

front = W.front
assert_front = W.assert_front
shot = W.shot
log = W.log
texts = W.texts
click_text = W.click_text
sandbox_pid = W.sandbox_pid
windows = W.windows
window_rect = W.window_rect
