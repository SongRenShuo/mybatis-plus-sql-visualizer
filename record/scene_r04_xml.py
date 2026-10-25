# -*- coding: utf-8 -*-
"""R04 XML 动态 SQL 预览重录（T-DocsGifRecord1025 批 1）。

画面：UserMapper.xml 第 74 行 `selectUserByTrim` 上按 Alt+Shift+Q → 候选框列出六条
语句 → 选中 `update updateUserByXml (第 56 行)` → OK → 预览面板打开（UPDATE + SET
段）→ 逐个取消 email / status 条件，SET 段实时收缩 → 取消关闭 → 回跳到起始行。

**2026-10-25 修订的三处（旧片 93% 时长卡在候选框、从未渲染出预览）**：
  1. `POPUP_UPDATE_ROW` / `POPUP_OK` 坐标全错。实测（本机 120% 缩放、目录树收起）：
     候选框第 3 项 `updateUserByXml (第 56 行)` 在屏幕 y≈477，OK 在 (1116, 669)。
  2. 方向键在候选框里**不生效**（连按两次 down 仍停在首项），只能鼠标点行。
  3. 点行之后必须再点 OK，不能按 Enter——Enter 确认的是列表当前高亮项，
     而高亮并不跟随鼠标点击，实测会打开错误语句。
  4. 收尾改点「取消」：旧版点「复制 SQL 并关闭」会留一张常驻通知卡（独立 balloon
     窗口，robot-server 查不到、Esc 关不掉），尾帧与首帧对不上。

前置（prep）：UserMapper.xml 打开、goto(74)、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

import pyautogui
from gifkit import Recorder, click, goto_line
from nav import force_en_us

REGION = (285, 26, 1354, 974)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "r04_xml.mp4")

OX, OY = REGION[0], REGION[1]


def abs_pt(p):
    """region 相对坐标 → 屏幕绝对坐标。"""
    return (p[0] + OX, p[1] + OY)


# 「选择要预览的 SQL」候选框：updateUserByXml 是第 3 项，行中心 region 相对 y=452
POPUP_UPDATE_ROW = (623, 452)
# 候选框 OK 按钮（region 相对）
POPUP_OK = (839, 644)
# 预览面板里的动态条件复选框（email / status，region 相对）
COND_EMAIL = (296, 339)
COND_STATUS = (296, 367)
# 收尾「取消」按钮（region 相对）
CANCEL = (1212, 798)


def main():
    force_en_us()
    time.sleep(0.4)
    goto_line(74)
    time.sleep(0.4)
    # 首帧必须与尾帧同态：光标停在第 74 行（当前行有高亮底色）、gutter 图标已渲染。
    # 这里**不能点编辑器**——点一次就把光标挪到点击所在行（新构图下 (700,383) 落在
    # 第 73 行注释上，尾帧与首帧差 12.96%）。Esc 只关 goto 框，不动光标。
    pyautogui.press("esc")
    time.sleep(1.8)

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(0.5)
        pyautogui.hotkey("alt", "shift", "q")                              # 1. 唤起候选框
        time.sleep(1.4)

        click(*abs_pt(POPUP_UPDATE_ROW), move_dur=0.3, pauses=(0.2, 0.7))   # 2. 点中 updateUserByXml 行
        click(*abs_pt(POPUP_OK), move_dur=0.25, pauses=(0.15, 1.8))         # 3. OK → 面板打开

        click(*abs_pt(COND_EMAIL), move_dur=0.28, pauses=(0.2, 1.3))        # 4. 取消 email
        click(*abs_pt(COND_STATUS), move_dur=0.28, pauses=(0.2, 1.3))       # 5. 取消 status

        click(*abs_pt(CANCEL), move_dur=0.3)                                # 6. 取消关面板
        time.sleep(1.0)

        # 7. 首尾同态：回到起始行
        #    不能用 Ctrl+Shift+Backspace——它跳的是「上次编辑位置」，实测落在第 77 行
        #    （首帧在第 74 行），接缝差 22.93%。也不能用「点编辑器空白」收 goto 框——
        #    点击会把光标挪到点的位置（实测尾帧跑到第 87 行）。正解：goto_line 之后
        #    按 Esc 关掉 goto 框，光标留在第 74 行不动（2026-10-25 实测）。
        # 7. 首尾同态：**完整复刻开录前的建立序列**
        #    goto_line(74) → 点击行内同一位置。只做 goto_line 不够：录制途中点复选框
        #    会在编辑器里留下整行选中态（实测第 92 行带选中底色），
        #    Ctrl+Shift+Backspace / 右方向键 / Esc 都清不掉，只有重新点一次编辑器行才复位。
        goto_line(74)
        time.sleep(0.5)
        # 关 goto 框；**不要点编辑器**（点击会把光标挪走）。Esc 两次清掉可能残留的选择态。
        pyautogui.press("esc")
        time.sleep(0.3)
        pyautogui.press("esc")
        time.sleep(1.2)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
