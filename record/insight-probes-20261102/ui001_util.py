# -*- coding: utf-8 -*-
"""UI-001 复验（20261031）共用胶水：复用走查轮 walk_util，只换沙盒名与证据帧目录。

纪律沿用 walk_util：坐标一律动态定位（robot 组件树 > anchor > 像素带 > 几何），
每次输入前断言前台窗口 PID 是沙盒；截图全屏落 build/MPSV-Ui001Recheck-shots/。
"""
import os
import sys

RECORD_DIR = os.path.dirname(os.path.abspath(__file__))
if RECORD_DIR not in sys.path:
    sys.path.insert(0, RECORD_DIR)
LIB = os.path.join(RECORD_DIR, "lib")
if LIB not in sys.path:
    sys.path.insert(0, LIB)

import walk_util as W  # noqa: E402

REPO = W.REPO
W.SANDBOX_NAME = "MPSV-Ui001Recheck"
W.SHOTS = os.path.join(REPO, "build", "MPSV-Ui001Recheck-shots")

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
