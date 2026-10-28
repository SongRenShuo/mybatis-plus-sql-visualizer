# -*- coding: utf-8 -*-
"""A22 11 种方言分页渲染（dialect-pagination.gif）20261012。

素材：`PageSimServiceImpl#countOnPage`（L55）→ `userMapper.selectPage(new Page<>(2, 5), wrapper)`
      ——分页子句尾追在语句末尾且落在可见行内，换方言即换形态。
流程：基线方言＝MySQL（`` FROM `user` `` + `LIMIT 5, 5`）→ 预览
      → 进设置把方言改成 **Oracle** → 确定 → 同一处再预览，分页子句变 Oracle 形态。

标定（20261012，IDE @ 277,25）：
  设置页搜索框 (535,162)｜左树 SQL Preview (550,446)｜OK (1245,913)｜Cancel (1434,917)
  方言下拉 (917,265)｜预览浮窗体内 (760,400)｜SQL 末行凝视 (1150,762)

坑（本轮全部踩过）：
  ①**下拉项位置随当前选中项浮动**——点同一坐标两次会拿到不同方言（实测出现「前帧 PostgreSQL
    形态、后帧 MySQL 形态」的诡异结果就是这么来的）。改**键入方言名**选择：开下拉 →
    typewrite('Oracle') → Enter。副标题「手动指定：X」是**上次已保存**的值，不是当前下拉值。
  ②设置页记住上次停留页不可靠 → 一律显式导航（搜索框输英文页名 + 点左树行）。
  ③`goto_col` 有落空风险（文件刚打开时实测把光标留在 34:6）→ 用 `goto_text` 按唯一片段定位。
  ④`goto_text` 的命中是**选中**状态，而预览会走「选中文本里找 SQL」那条路而失败 → helper 内已按
    一下 Left 把选中塌成光标落到 token 起点。
  ⑤录完必须把方言**复位回「智能推断」**，否则后续所有预览都被钉在固定方言上。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from gifkit import Recorder
from nav import close_preview, force_en_us, goto_text, shot, trigger_preview

REGION = (285, 26, 1354, 974)
OUT = "perf/a22_dialect.mp4"

TOKEN = "selectPage(new Page<>(2, 5)"       # PageSimServiceImpl L55，文件内唯一

EDITOR_SPOT = (600, 500)
DLG_BODY = (760, 400)
SET_SEARCH = (535, 162)
TREE_SQL_PREVIEW = (550, 446)
DIALECT_DROPDOWN = (917, 265)
SET_OK = (1245, 913)
SET_CANCEL = (1434, 917)
SQL_TAIL = (1150, 762)


def open_settings_sql_preview(pyautogui):
    """Ctrl+Alt+S → 显式导航到 SQL Preview（不依赖「记住上次停留页」）。"""
    pyautogui.hotkey("ctrl", "alt", "s")
    time.sleep(2.4)
    pyautogui.click(*SET_SEARCH); time.sleep(0.6)
    pyautogui.hotkey("ctrl", "a"); time.sleep(0.2)
    force_en_us()
    pyautogui.typewrite("SQL Visualizer", interval=0.05)
    time.sleep(1.6)
    pyautogui.click(*TREE_SQL_PREVIEW); time.sleep(1.6)


def set_dialect(pyautogui, name):
    """开方言下拉 → 键入方言名（按名选，免疫项位浮动）→ Enter 收起。"""
    pyautogui.click(*DIALECT_DROPDOWN); time.sleep(1.0)
    force_en_us()
    pyautogui.typewrite(name, interval=0.08)
    time.sleep(0.8)
    pyautogui.keyDown("return"); pyautogui.keyUp("return")
    time.sleep(0.9)


def main():
    import pyautogui
    import gifkit

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）----
    # 1) 先把方言基线钉成 MySQL（前面探测点歪过，当前残留值不可信）
    pyautogui.keyDown("esc"); pyautogui.keyUp("esc"); time.sleep(0.6)
    pyautogui.click(SET_CANCEL); time.sleep(1.8)
    pyautogui.click(*EDITOR_SPOT); time.sleep(0.4)
    open_settings_sql_preview(pyautogui)
    set_dialect(pyautogui, "MySQL")
    pyautogui.click(SET_OK); time.sleep(1.9)
    # 2) 开文件、按片段定位光标、开好「换方言前」的预览
    pyautogui.click(600, 500); time.sleep(0.4)
    # 开文件改 MCP 按路径精确打开（20261027：Ctrl+Shift+N + typewrite 必撞同名文件）
    try:
        from mcpnav import open_file
        open_file("mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/service/impl/PageSimServiceImpl.java", settle=2.0)
    except Exception as e:
        print("MCP 打不开，中止:", e)
        raise
    goto_text(TOKEN)
    trigger_preview()

    r = Recorder(REGION, OUT)
    r.start()

    # 0. 起手凝视：看清 MySQL 形态（`` FROM `user` `` + `LIMIT 5, 5 ;`）
    pyautogui.moveTo(1150, 690, duration=0.6)
    pyautogui.moveTo(*SQL_TAIL, duration=1.1)

    # 1. 进设置 → 方言切 Oracle → 确定
    close_preview(body_xy=DLG_BODY, wait=1.1)
    open_settings_sql_preview(pyautogui)
    set_dialect(pyautogui, "Oracle")
    pyautogui.click(SET_OK); time.sleep(1.9)

    # 2. 同一处再预览：分页子句换成 Oracle 形态
    trigger_preview(wait=2.7)
    pyautogui.moveTo(1150, 690, duration=0.9)
    pyautogui.moveTo(*SQL_TAIL, duration=1.0)
    time.sleep(1.0)

    shot("perf/a22_final.png")
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
