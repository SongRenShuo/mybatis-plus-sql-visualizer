# -*- coding: utf-8 -*-
"""A34 分页拦截器模拟（pagination-sim.gif）20261012。

素材：`UserMapper.xml` 两条带 `IPage` 形参的语句（对应 mapper 方法首个参数是 IPage）——
      ①`selectUserPageXml`（L161，无手写 LIMIT）→ 预览应「尾追 LIMIT + 并列产出 COUNT 包裹」
      ②`selectUserPageLimitXml`（L220，语句尾部已带手写 LIMIT）→ 预览「只产 COUNT，不再追加第二条 LIMIT」
流程：光标压在 XML 语句上 → 预览 → **点进 SQL 面板再 Ctrl+End 滚到底**（关键：COUNT 是附在
      原语句之后的第二条产物，面板默认停在顶部看不见，别误判成「没产出」）→ 凝视 COUNT 块。

标定（20261012，IDE @ 277,25）：
  SQL 面板内取焦落点 screen (1000,420)
  预览浮窗体内（关窗）   screen (760,400)

坑：① COUNT 产物在面板底部，**必须滚到底**才可见（本轮就因没滚而先误判「XML 没附加产物」）；
    ② XML 里光标压在 `<select` 的 `select` 上即直接预览该语句（不是弹语句选择器）；
    ③ 关预览用「点进浮窗体内 → Esc」，别在编辑器里按 Esc。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from gifkit import Recorder
from nav import close_preview, force_en_us, goto_col, shot, trigger_preview

REGION = (285, 26, 1354, 974)
OUT = "perf/a34_pagination.mp4"

FILE_QUERY = "mybatis-plus-demo/mapper/UserMapper.xml"
RESULT_ROW = (777, 342)
SQL_PANE = (1000, 420)
DLG_BODY = (760, 400)

LINE_A, COL_A = 161, 6      # selectUserPageXml（尾追 LIMIT + COUNT）
LINE_B, COL_B = 220, 6      # selectUserPageLimitXml（手写 LIMIT → 只产 COUNT）


def to_bottom():
    """点进 SQL 面板取焦后 Ctrl+End：把面板滚到底，露出附在后面的 COUNT 产物。

    坑：点一下取焦不够稳（第二轮实测 Ctrl+End 未生效、面板停在原处）——点两次 + 拉长等待。
    """
    import pyautogui
    pyautogui.click(*SQL_PANE)
    time.sleep(0.6)
    pyautogui.click(*SQL_PANE)
    time.sleep(0.5)
    pyautogui.hotkey("ctrl", "end")
    time.sleep(1.2)


def main():
    import pyautogui
    import gifkit

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）：开 XML + 光标压语句 + 开好第一张预览 ----
    close_preview(body_xy=DLG_BODY)
    # 开文件改 MCP 按路径精确打开（20261027：Ctrl+Shift+N + typewrite 必撞同名文件——
    # native-mybatis-demo 也有 NativeUserMapper.xml，实测开错到那个文件，
    # LINE_A=161 落在错误文件里 → 弹「选择要预览的 SQL」候选框，整条 take 报废）
    try:
        from mcpnav import open_file
        open_file("mybatis-plus-demo/src/main/resources/mapper/UserMapper.xml", settle=2.0)
    except Exception as e:
        print("MCP 打不开，中止:", e)
        raise
    goto_col(LINE_A, COL_A)
    trigger_preview()

    r = Recorder(REGION, OUT)
    r.start()

    # 1. 起手凝视：先看原语句（WHERE / ORDER BY）
    pyautogui.moveTo(1150, 400, duration=0.6)
    pyautogui.moveTo(1150, 700, duration=1.0)

    # 2. 滚到底：露出尾追 LIMIT 与附在后面的 COUNT 包裹
    to_bottom()

    # 3. 凝视 COUNT 块（`-- 分页 COUNT 查询（PaginationInnerInterceptor）` + `SELECT COUNT(*) FROM (…) TOTAL`）
    pyautogui.moveTo(1150, 560, duration=0.9)
    pyautogui.moveTo(1150, 760, duration=1.0)
    time.sleep(0.5)

    # 收尾：在 COUNT 块上多停一会儿，让「注释出处 + COUNT(*) / FROM ( / ) TOTAL」读清
    pyautogui.moveTo(1150, 660, duration=0.9)
    time.sleep(1.2)

    shot("perf/a34_final.png")
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
