# -*- coding: utf-8 -*-
"""A23 逻辑删除/多租户注入开关（interceptor-injection.gif）20261027 v2。

素材：`UserServiceImpl#listByLambdaQuery` L41 `return this.list(queryWrapper)`
      （mybatis-plus-demo，表 `user`）。
流程：预览（WHERE 末行带 `AND deleted = 0`）→ 进设置 > Tools > SQL Visualizer > SQL 预览
      → 关掉「逻辑删除…AND deleted = 0」、勾上「多租户…AND tenant_id = ?」→ OK
      → 再预览：`deleted = 0` 消失、`tenant_id = ?` 出现（一趟改动，两个子句一进一出）。

标定（20261027 实测，IDE @ 277,25）：
  41 行 `list` token        (568, 319)
  设置页搜索框              (647, 231)   ← robot `//div[@class='TextFieldWithProcessing']`
  左树「SQL 预览」           (589, 468)   ← 搜索 "SQL Visualizer" 后
  逻辑删除复选框            (812, 621)
  多租户复选框              (812, 688)
  设置 OK / Cancel          (1188, 826) / (1272, 826)

坑（本轮踩过，全部已修）：
① **旧标定全废**：设置窗已从 1440 宽缩到约 880 宽、位置右移 → 旧搜索框坐标 (535,162) 落在
   **编辑器**上，typewrite 把 "SQL Visualizer" 直接打进源文件（UserServiceImpl 被改），
   且左树点击 (550,446) 点在别的页上 → 录到的是 Data Editor and Viewer 的 Data Sources 页。
   现全部按当前窗口重标；搜索框走 robot 组件（相对父链累加），不再写死。
② `goto_col(41,21)` 实测把光标留在 **44 行**（行位随布局漂移）→ 预览报「未在光标处找到可预览的
   SQL 源」。改像素点 token，并在 prep 里硬断言预览已打开。
③ 预览关窗用「点弹窗体内 → Esc」（无边框窗口的按钮坐标不可靠）。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui                                              # noqa: E402
from gifkit import Recorder                                   # noqa: E402
from nav import close_preview, force_en_us, shot, trigger_preview   # noqa: E402
from robot import click_component, find_components            # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = "perf/a23_interceptor.mp4"

LIST_TOKEN = (568, 319)     # 41 行 `list` token 首字符（20261027 二次实测）
DLG_BODY = (760, 400)
SET_SEARCH = "//div[@class='TextFieldWithProcessing']"
TREE_SQL_PREVIEW = (589, 468)
CHK_LOGIC_DELETE = (812, 621)
CHK_TENANT = (812, 688)
SET_OK = (1188, 826)
SET_CANCEL = (1272, 826)
PREVIEW = "//div[contains(@accessiblename,'SQL Visualizer - SQL 预览')]"

# 凝视落点：避开标识符（压标识符会弹 Database 的 DDL tooltip，画面脏）
GAZE_A = (1150, 690)
GAZE_B = (1150, 762)


def has(xp):
    return len(find_components(xp)) > 0


def prep(pyautogui):
    """关残留设置页/预览 → 点 41 行 token → 唤起预览并断言（全部不入镜）。"""
    if has("//div[@text='Apply']") or has("//div[contains(@class,'SettingsDialog')]"):
        pyautogui.click(*SET_CANCEL)
        time.sleep(2.0)
    pyautogui.press("esc")
    time.sleep(0.5)
    if has(PREVIEW):
        close_preview(body_xy=DLG_BODY, wait=1.2)
    pyautogui.click(*LIST_TOKEN)
    time.sleep(0.6)
    trigger_preview()
    time.sleep(1.4)
    if not has(PREVIEW):
        raise RuntimeError("prep: 预览未打开（token 未压中 / 文件未在前台）")


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    prep(pyautogui)

    r = Recorder(REGION, OUT)
    r.start()
    try:
        # 0. 起手凝视：先把「关之前」的 `AND deleted = 0` 看清
        pyautogui.moveTo(*GAZE_A, duration=0.6)
        pyautogui.moveTo(*GAZE_B, duration=1.1)

        # 1. 进设置 → 搜索 → 点「SQL 预览」→ 改两个开关 → OK
        close_preview(body_xy=DLG_BODY, wait=1.1)
        pyautogui.hotkey("ctrl", "alt", "s")
        time.sleep(2.6)
        click_component(SET_SEARCH)
        time.sleep(0.6)
        pyautogui.hotkey("ctrl", "a")
        time.sleep(0.2)
        force_en_us()
        pyautogui.typewrite("SQL Visualizer", interval=0.05)
        time.sleep(1.8)
        pyautogui.click(*TREE_SQL_PREVIEW)
        time.sleep(1.8)
        pyautogui.click(*CHK_LOGIC_DELETE)     # 关掉逻辑删除追加
        time.sleep(0.9)
        pyautogui.click(*CHK_TENANT)           # 打开多租户追加
        time.sleep(1.0)
        pyautogui.click(*SET_OK)
        time.sleep(2.0)

        # 2. 同一处再预览：`deleted = 0` 消失、`tenant_id = ?` 出现
        pyautogui.click(*LIST_TOKEN)
        time.sleep(0.5)
        trigger_preview(wait=2.7)
        pyautogui.moveTo(1150, 700, duration=0.9)
        pyautogui.moveTo(*GAZE_B, duration=0.9)
        time.sleep(1.0)

        shot("perf/a23_final.png")
    finally:
        r.stop()
        print("frames:", r.frames)


if __name__ == "__main__":
    sys.exit(main())
