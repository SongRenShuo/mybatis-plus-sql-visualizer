# -*- coding: utf-8 -*-
"""T-MapperPreviewOrder 沙盒复现探针（多 mapper 连拍版）。

用途：验证「Mapper 接口方法行 gutter 上，SQL 预览图标恒在跳转箭头左侧」。
做法：对 demo 里每个 mapper「打开 → 立即连拍 → 下一个 → 逆序重开再连拍」，
连拍是为了捕捉首次渲染窗口（用户报障是「首次在右、稍后跳到左」）。

判据见 prevorder_judge.py：左=预览(紫)右=跳转(红) 为 OK，红在左即复现。

用法：
    python record/prevorder_probe.py <tag> [mapper1 mapper2 ...]
    MPSV_SANDBOX=<沙盒名> python record/prevorder_probe.py <tag>      # 换沙盒
    MPSV_PREVORDER_DIR=<目录> python record/prevorder_judge.py        # 换帧目录

前置：沙盒 IDE 已启动（含 robot-server，端口 8580）、demo 项目已建完索引。
第一次跑若截到的还是 Search Everywhere 弹窗，说明索引未完成或 Ctrl+N 被吞——
先等索引（看沙盒 idea.log 的 UnindexedFilesIndexer finished）再跑。
"""
import ctypes
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "lib"))

try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    pass

import ac
import locate

# 沙盒名可用 MPSV_SANDBOX 覆盖（默认 T-MapperPreviewOrder 验收沙盒）
SANDBOX = os.environ.get("MPSV_SANDBOX", "MPSV-PrevOrder")
OUT = os.environ.get("MPSV_PREVORDER_DIR") or os.path.join(HERE, "tmp", "prevorder-multi")
os.makedirs(OUT, exist_ok=True)
SW_MAXIMIZE = 3

DEFAULT_MAPPERS = [
    "OrderMapper", "RoleMapper", "UserMapper", "DepartmentMapper",
    "SysConfigMapper", "MixedProfileMapper", "GoldenDbDeviceMapper",
]

BURST = [0.5, 1.0, 1.8, 3.0, 5.0]


def max_ide():
    pid = ac.sandbox_pid(SANDBOX)
    if not pid:
        raise SystemExit("sandbox not running")
    rect = locate.window_rect(pid=pid, pick="largest", min_size=(800, 600))
    hwnd = None
    for h, p, title, r in locate.windows():
        if p == pid and r == rect:
            hwnd = h
            break
    if hwnd:
        ctypes.windll.user32.ShowWindow(hwnd, SW_MAXIMIZE)
        time.sleep(1.0)
        locate.ensure_foreground(hwnd)
        time.sleep(1.0)
    return pid


def goto_class(simple_name):
    ac.ensure_ide_front(SANDBOX)
    for _ in range(2):
        ac.key("escape")
        time.sleep(0.3)
    ac.key("ctrl", "n")
    time.sleep(1.0)
    ac.key("ctrl", "a")
    ac.paste_text(simple_name)
    time.sleep(1.5)
    ac.key("enter")


def burst(tag):
    t0 = time.time()
    shots = []
    for off in BURST:
        wait = t0 + off - time.time()
        if wait > 0:
            time.sleep(wait)
        p = ac.shot("%s-t%04dms" % (tag, int(off * 1000)), out_dir=OUT)
        shots.append(p)
    return shots


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else "rev"
    mappers = sys.argv[2:] or DEFAULT_MAPPERS
    max_ide()

    # 轮 1：逐个小开
    for i, m in enumerate(mappers):
        print("[r1] %s" % m)
        goto_class(m)
        burst("%s-r1-%02d-%s" % (tag, i, m))

    # 轮 2：回头重开（用户说重开后位置会调换）
    for i, m in enumerate(reversed(mappers)):
        print("[r2] %s" % m)
        goto_class(m)
        burst("%s-r2-%02d-%s" % (tag, i, m))

    print("done")


if __name__ == "__main__":
    main()