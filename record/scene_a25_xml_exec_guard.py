# -*- coding: utf-8 -*-
"""A25 `${}` 直替提示与执行确认（xml-exec-guard.gif）20261013。

素材：`OrderMapper.xml`（mybatis-plus-demo）L55 `selectOrderByRawColumn`：
      `SELECT id, order_no, amount FROM t_order ORDER BY ${orderBy}` —— **raw 段**（`${}` 直接替换）。
      （仓里原有的 `${ew.customSqlSegment}` 是 MP 占位，走的是「预览未完整还原」那条路径，
      不产生 `${}` 直替横幅，故按「缺什么素材补什么」补了本语句。）
成片一幕：take 内唤起预览 → 面板顶部出现非模态**提示横幅**
      「⚠ 预览含 1 处 ${} 直接替换——替换文本未转义，注意 SQL 注入面」（右侧带可关闭 ✕），
      SQL 里 `${orderBy}` 以**模拟值**渲染（实测 `ORDER BY 1`）→ 凝视横幅与 SQL →
      点「执行 SQL」→ **执行前确认闸门**弹出 → 收尾停在闸门上（闸门本身就是要展示的内容）。

标定（20261013，IDE @ 277,25，弹窗 (367,166,1557,868)）：
  提示横幅        screen (700,315)   ← region 423,290
  SQL 文本区      screen (1000,480)
  执行 SQL 按钮   由 find_exec_button 现算（底边−39 + 列向亮簇，最左簇）
  闸门对话框      take 末帧即为闸门，不点按钮（避免误确认执行）
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import Recorder                                                # noqa: E402
from locate import (bright_band_x, close_preview, ensure_foreground,        # noqa: E402
                    grab as locate_grab, sandbox_pid, window_rect, windows)
from nav import force_en_us, goto_col, trigger_preview                      # noqa: E402

REGION = (285, 26, 1354, 974)
OUT = "perf/a25_xml_exec_guard.mp4"

FILE_QUERY = "mapper/OrderMapper.xml"
FILE_TITLE_KEY = "OrderMapper.xml"
STMT_LINE, STMT_COL = 55, 6              # <select id="selectOrderByRawColumn" …>
ROW_X = 600
FILE_ROW_YS = (341, 333, 349, 357)

BANNER = (700, 315)                      # ${} 直替提示横幅
GAZE_SQL = (1000, 480)
PARK = (577, 760)


def ide_hwnd_and_title(pid):
    for h, p, t, r in windows():
        if p == pid and (r[2] - r[0]) > 1200:
            return h, t
    return None, ""


def find_exec_button(ide_pid):
    """预览弹窗底部「执行 SQL」按钮（底边−39 取行 + 列向亮簇取最左簇）。"""
    main_rect = window_rect(pid=ide_pid, pick="largest")
    popup = window_rect(pid=ide_pid, pick="smallest", min_size=(600, 400))
    if popup is None or popup == main_rect:
        raise RuntimeError("预览弹窗未打开")
    pw, ph = popup[2] - popup[0], popup[3] - popup[1]
    img = locate_grab((popup[0], popup[1], pw, ph))
    rx0 = int(pw * 0.45)
    row_y = ph - 39
    clusters = bright_band_x(img, max(0, row_y - 14), min(ph, row_y + 14), rx0, pw,
                             thr=45.0, min_gap=14, min_w=8)
    if not clusters:
        raise RuntimeError("按钮行上未找到亮簇")
    return (popup[0] + (clusters[0][0] + clusters[0][1]) // 2, popup[1] + row_y)


def main():
    import pyautogui

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    ide_pid = sandbox_pid()
    hwnd, _ = ide_hwnd_and_title(ide_pid)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    print("沙盒 pid=%s 已置前" % ide_pid)

    # ---- prep（不入镜）：开文件 + 光标压到 <select 行，预览留给 take 内唤起 ----
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.7)
    print("关旧预览:", close_preview(ide_pid))
    pyautogui.click(600, 500); time.sleep(0.5)
    # 开文件改 MCP 按路径精确打开（20261027：Ctrl+Shift+N + typewrite 必撞同名文件）
    try:
        from mcpnav import open_file
        open_file("mybatis-plus-demo/src/main/resources/mapper/OrderMapper.xml", settle=2.0)
    except Exception as e:
        print("MCP 打不开，中止:", e)
        raise
    t1 = ide_hwnd_and_title(ide_pid)[1]
    if FILE_TITLE_KEY not in t1:
        raise RuntimeError("开文件失败：标题仍为 %s" % t1[:70])
    print("开文件后标题 = %s" % t1[:62])
    goto_col(STMT_LINE, STMT_COL)
    pyautogui.moveTo(*PARK, duration=0.4); time.sleep(0.6)

    # ---- 录制：唤起预览（${} 横幅随预览出现）→ 凝视横幅与 SQL → 点执行 SQL 引出闸门 ----
    r = Recorder(REGION, OUT)
    r.start()
    pyautogui.moveTo(900, 500, duration=0.5); time.sleep(0.4)
    trigger_preview(wait=1.2)
    time.sleep(1.8)
    pyautogui.moveTo(*BANNER, duration=0.7); time.sleep(1.4)
    pyautogui.moveTo(*GAZE_SQL, duration=0.8); time.sleep(1.4)
    btn = find_exec_button(ide_pid)
    print("执行 SQL 按钮 = %s" % (btn,))
    pyautogui.click(*btn); time.sleep(2.4)          # 引出执行前确认闸门
    # 凝视闸门弹窗（警告图标 + 文案 + Yes/No）
    pyautogui.moveTo(900, 500, duration=0.8); time.sleep(2.0)
    pyautogui.moveTo(780, 540, duration=0.7); time.sleep(1.6)
    # 复核要求：演示「选 No 即中止」——点 No 关闭闸门，SQL 不执行
    pyautogui.click(810, 550); time.sleep(2.2)   # No 按钮（放大裁剪实测，旧值 1025 偏右 215px）
    pyautogui.moveTo(900, 560, duration=0.8); time.sleep(1.8)
    # 二次真实变化：再点一次「执行 SQL」，闸门复现（证明 No 只是当次取消，可重试）
    pyautogui.click(*btn); time.sleep(2.6)
    pyautogui.moveTo(900, 500, duration=0.8); time.sleep(2.0)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
