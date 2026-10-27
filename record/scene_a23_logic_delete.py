# -*- coding: utf-8 -*-
"""A23 逻辑删除注入开/关（interceptor-injection.gif）20261012。

素材：项目级设置页 `设置 > Tools > SQL Visualizer (Project)` 的
      「逻辑删除实体查询/删除自动追加 `AND deleted = 0`」开关（项目页 10 项之一）。
流程：光标压在 wrapper 调用 token 上 → 预览（WHERE 末行带 `AND deleted = 0`）
      → 打开设置（停在项目页）→ 取消勾选该开关 → 确定 → 再预览 → 末行消失。

标定（20261012，IDE @ 277,25）：
  编辑器取焦落点       screen (600,500)
  预览浮窗体内（关窗） screen (760,400)
  设置页「使用项目设置」screen (737,205)
  设置页「逻辑删除…AND deleted = 0」screen (736,397)
  设置 OK / Cancel     screen (1260,916) / (1434,917)
  SQL 末行（WHERE 区） screen (880,760)

坑：① **项目页没有「多租户」开关**——项目级数据类虽持久化 injectTenantLine /
    tenantColumnName / injectTenantOnInsert，但页面只渲染 10 项且不含租户项，
    故租户注入这半无法在项目页演示（已登记为发现）；本片只演逻辑删除那半。
    ② 项目页各行在「使用项目设置」未勾时是置灰只读的，要改先勾总开关。
    ③ 该对话框会记住上次停留的页，故本脚本不再点左树，直接依赖「停在 SQL Preview 页」。
    ④ 拍在**全局** SQL Preview 页而非项目页：项目页那版会把参数存储域一起切到项目作用域，
       同一屏上前帧 `AND status = 41`、后帧 `AND status = ?`，引入与逻辑删除无关的变化；
       全局页作用域不变，前后两帧只有被演示的那一项不同（已实测踩到并重录）。
    ⑤ 项目页**没有**「多租户」开关（10 项里不含租户项），多租户只能在全局页演示。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from gifkit import Recorder
from nav import close_preview, force_en_us, goto_col, shot, trigger_preview

REGION = (285, 26, 1354, 974)
OUT = "perf/a23_interceptor.mp4"

LINE, COL = 41, 21          # return this.list(queryWrapper)
EDITOR_SPOT = (600, 500)
DLG_BODY = (760, 400)
CHK_LOGIC_DELETE = (917, 595)   # 全局 SQL Preview 页「逻辑删除实体查询/删除自动追加 AND deleted = 0」
CHK_TENANT = (917, 669)         # 同页「多租户实体查询/更新/删除自动追加 AND tenant_id = ?」
SET_OK = (1245, 913)
SET_CANCEL = (1434, 917)
SET_SEARCH = (535, 162)         # 设置页左上搜索框
TREE_SQL_PREVIEW = (550, 446)   # 过滤出插件各页后，SQL Preview 行的屏位
SQL_TAIL = (880, 760)


def main():
    import pyautogui
    import gifkit

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）：用 Cancel 关设置页（不落盘任何改动）→ 光标压 token → 开好「关之前」的预览 ----
    pyautogui.click(SET_CANCEL)
    time.sleep(2.2)
    pyautogui.click(*EDITOR_SPOT)
    time.sleep(0.5)
    goto_col(LINE, COL)
    trigger_preview()

    r = Recorder(REGION, OUT)
    r.start()

    # 0. 起手凝视：先把「关之前」的 `AND deleted = 0` 看清（assemble 会裁静止段，用移动撑时长；
    #    落点避开标识符——压在标识符上会弹 Database 的 DDL tooltip，画面脏）
    pyautogui.moveTo(1150, 690, duration=0.6)
    pyautogui.moveTo(1150, 762, duration=1.1)

    # 1. 进设置 → **显式导航**到 SQL Preview（不依赖对话框「记住上次停留页」，实测不可靠）
    #    → 关掉「逻辑删除实体查询/删除自动追加 AND deleted = 0」→ 确定
    close_preview(body_xy=DLG_BODY, wait=1.1)
    pyautogui.hotkey("ctrl", "alt", "s")
    time.sleep(2.4)
    pyautogui.click(*SET_SEARCH)
    time.sleep(0.6)
    pyautogui.hotkey("ctrl", "a")
    time.sleep(0.2)
    force_en_us()
    pyautogui.typewrite("SQL Visualizer", interval=0.05)
    time.sleep(1.6)
    pyautogui.click(*TREE_SQL_PREVIEW)
    time.sleep(1.6)
    pyautogui.click(*CHK_LOGIC_DELETE)   # 关掉逻辑删除追加
    time.sleep(0.8)
    pyautogui.click(*CHK_TENANT)         # 打开多租户追加 —— 一趟设置改动，两个子句一进一出
    time.sleep(0.9)
    pyautogui.click(SET_OK)
    time.sleep(1.9)

    # 2. 同一处再预览：末行的 `AND deleted = 0` 消失
    trigger_preview(wait=2.7)
    # 凝视落点避开标识符（压在标识符上会弹 Database 的 DDL tooltip，画面脏）
    pyautogui.moveTo(1150, 700, duration=0.9)
    pyautogui.moveTo(1150, 762, duration=0.9)
    time.sleep(1.0)

    shot("perf/a23_final.png")
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
