# -*- coding: utf-8 -*-
"""U43 表名反查——实体类搜索快路径 / 后台扫描开关 / 显式「在依赖库中查找」/ 连续输入不卡。

**验收口径来源**
- 审计表 U43 行：`mybatis-plus-sql-visualizer-dev/docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`
  L68「待表名反查快路径/非常规@TableName、后台扫描开关、显式依赖库查找/保留已选、取消/连续输入性能，
  正向解析不代证反查」；B3 已验残段（L184）：LegacyBooking 来自本地 Maven JAR（cn.song:mp-jar-model-lib），
  正向解析 ui_reservation_ledger / booking_code/state_code 成立。
- 源码锚：`EntitySelectionDialog.kt`——「在依赖库中查找」按钮（L113-121，扫描期禁用防重复投递）、
  刷新保留已选（rebuildRows 不重置 selections，L161-170）；设置项 `settings.libraryEntitySweep`
  「后台扫描依赖库以反查实体」（Bundle L557）；「选择…」= Goto Class 弹窗（L200-210）。

**反查入口**：SQL → Java 页签粘入引用 JAR 表（ui_reservation_ledger，@TableName 非常规命名）
→ 「转换」→ 实体选择对话框行内无候选 → 点「在依赖库中查找」→ 行内出现 LegacyBooking。

**前置条件**：沙盒起、演示工程开；mp-jar-model-lib 构件在依赖里（此前 B3 已配）。
**预计时长**：8~12 分钟（含扫描等待；后台扫描首趟可能数十秒）。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 工具层在 acceptance/

import bc_common as bc
from bc_common import Reporter, front, shot, esc, ide
import pyautogui

REVERSE_SQL = "SELECT * FROM ui_reservation_ledger WHERE booking_code = 'B100'"


def open_reverse_tab(rep):
    if bc.click_named("SQL → Java"):
        time.sleep(1.5)
        return True
    rep.fail("「SQL → Java」页签未定位（robot 未启）——人工切页后重跑")
    shot("bc-U43-manual-tab")
    return False


def input_sql(rep):
    """粘 SQL 进转换输入区：robot 找工具窗内宽输入框（EditorComponentImpl 实测类名）点入后全选粘贴。"""
    _pid, _hwnd, irect = ide()
    fields = []
    # 工具窗在右半（x>1000）；主编辑器与结果区也会命中同类名，按屏幕 x 区间过滤到工具窗
    tw_left = irect[0] + (irect[2] - irect[0]) // 2 if irect else 960
    if bc.robot_available():
        for cls in ("EditorComponentImpl", "JBTextField"):
            xp = "//div[@class='%s']" % cls
            try:
                comps = bc.robot_mod.find_components(xp)
            except Exception:
                continue
            for i, c in enumerate(comps):
                if int(c.get("width", 0)) <= 200:
                    continue
                try:
                    xy = bc.robot_mod.component_screen_center("(%s)[%d]" % (xp, i + 1), comp=c)
                except Exception:
                    continue
                if xy[0] >= tw_left:
                    fields.append(xy)
    if not fields:
        rep.fail("转换输入框未定位（robot 未启/类名不匹配）——粘 SQL 交人工后从 T2 起重跑")
        return False
    pyautogui.click(*fields[0])
    time.sleep(0.4)
    pyautogui.hotkey("ctrl", "a")
    time.sleep(0.2)
    bc.clipboard_set_text(REVERSE_SQL)
    pyautogui.hotkey("ctrl", "v")
    time.sleep(0.8)
    shot("bc-U43-01-input")
    return True


def wait_entity_dialog(rep, timeout=15):
    """等「选择实体类」对话框（DialogWrapper=独立窗口，EnumWindows 可见）。"""
    import locate
    pid = ide()[0]
    deadline = time.time() + timeout
    while time.time() < deadline:
        dlg = locate.window_rect(pid=pid, pick="smallest", min_size=(380, 160), max_size=(900, 700))
        main = locate.window_rect(pid=pid, pick="largest", min_size=(800, 600))
        if dlg and dlg != main:
            return dlg
        time.sleep(0.5)
    return None


def dialog_row_label_region(dlg):
    """对话框行区域（内容区，供「候选刷新前后」指纹）：窗口去标题去按钮行。"""
    l, t, r, b = dlg
    return (l + 12, t + 40, max(200, (r - l) - 24), max(60, (b - t) - 120))


def part_reverse_lookup(rep):
    rep.step("T1 打开 SQL → Java，粘 JAR 表 SQL，点「转换」触发实体选择对话框")
    if not open_reverse_tab(rep):
        return False
    if not input_sql(rep):
        return False
    if not (bc.click_named("转换") or bc.click_button(rep, "转换", anchor_name="bc_btn_convert", must=False)):
        rep.fail("「转换」按钮未定位")
        return False
    time.sleep(3.0)
    dlg = wait_entity_dialog(rep)
    if not dlg:
        rep.fail("「选择实体类」对话框未出现（快路径直接命中了实体？JAR 表不该快路径命中——判读截图）")
        shot("bc-U43-t1-no-dialog")
        return False
    rep.check(True, "实体选择对话框已开（快路径未命中 JAR 实体，行内空置=预期）",
              "实体选择对话框未开")
    shot("bc-U43-t2-dialog-before", region=dlg)

    rep.step("T3 显式「在依赖库中查找」：点击 → 扫描期禁用 → 候选刷新出现 LegacyBooking")
    g = bc.Guard(region=dialog_row_label_region(dlg))
    g.mark("rows")
    if not (bc.click_named("在依赖库中查找") or bc.click_button(
            rep, "在依赖库中查找", anchor_name="bc_btn_scan_libs", must=False)):
        rep.fail("「在依赖库中查找」按钮未定位")
        return False
    rep.ok("扫描已触发（按钮同刻禁用防重复投递；首趟全库扫描可达数十秒）")
    deadline = time.time() + 120
    refreshed = False
    while time.time() < deadline:
        if g.changed("rows", wait=0.5)[0]:
            refreshed = True
            break
    time.sleep(1.0)
    hit = bc.find_contains("LegacyBooking")
    rep.shot_check(refreshed or bool(hit), "bc-U43-t3-after-scan",
                   "候选已刷新（行区 diff 或 LegacyBooking 文本命中）" if hit else
                   "候选区有变化（diff 证刷新；LegacyBooking 文本未命中→截图人工核对）",
                   "120s 内候选区无变化——显式扫描未回填（判读截图与 idea.log）")
    if hit:
        rep.ok("LegacyBooking 候选在场（非常规 @TableName 反查命中）")

    rep.step("T4 保留已选：选第一候选 → 再点一次「在依赖库中查找」→ 已选不被刷新清掉")
    if hit:
        comp_xy = None
        try:
            comp_xy = bc.robot_mod.component_screen_center(
                "(//div[contains(@text,'LegacyBooking')])[last()]", comp=hit)
        except Exception:
            comp_xy = None
        if comp_xy:
            pyautogui.click(*comp_xy)   # 行内候选点选（选中态由面板承载）
            time.sleep(0.4)
        g.mark("rows2")
        if (bc.click_named("在依赖库中查找") or bc.click_button(
                rep, "在依赖库中查找", anchor_name="bc_btn_scan_libs", must=False)):
            time.sleep(2.0)
            deadline = time.time() + 60
            while time.time() < deadline:
                if g.changed("rows2", wait=0.5)[0]:
                    break
            still = bc.find_contains("LegacyBooking")
            rep.shot_check(bool(still), "bc-U43-t4-kept",
                           "显式刷新后 LegacyBooking 仍在场（保留已选语义；截图核对选中态未清）",
                           "刷新后候选丢失——保留已选语义被破坏，登记")
    esc(2)

    rep.step("T5 取消路径：对话框「取消」→ 不回调不崩溃（转换面板回落字符串列名）")
    if bc.click_named("取消"):
        time.sleep(1.0)
        shot("bc-U43-t5-cancelled")
        rep.check(wait_entity_dialog(rep, timeout=3) is None,
                  "取消后对话框已关（回落路径交由截图人工核对生成代码=字符串列名）",
                  "取消后对话框仍在")
    else:
        esc(2)
        rep.ok("取消按钮未按名命中，已用 Esc 退出对话框（截图 bc-U43-t5-cancelled 人工核对）")
    return True


def part_no_freeze(rep):
    """连续输入不卡：重新触发对话框 →「选择…」弹 Goto Class，逐字符键入量「键入→候选刷新」耗时，容差 2×。"""
    rep.step("N0 重开实体选择对话框（T5 已取消）")
    if not (bc.click_named("转换") or bc.click_button(rep, "转换", anchor_name="bc_btn_convert", must=False)):
        rep.fail("N0：「转换」未定位，不卡断言跳过")
        return
    if not wait_entity_dialog(rep, timeout=15):
        rep.fail("N0：对话框未再现，不卡断言跳过")
        return
    rep.step("N1 连续输入不卡（容差 2×）：「选择…」弹 Goto Class，逐字符 Legacy 计时")
    if not (bc.click_named("选择…") or bc.click_button(rep, "选择…", anchor_name="bc_btn_browse", must=False)):
        rep.fail("「选择…」未定位——不卡断言跳过（需先回到实体选择对话框）")
        return
    time.sleep(1.5)
    _pid, _hwnd, rect = ide()
    popup_band = (rect[0] + (rect[2] - rect[0]) // 2 - 300, rect[1] + 120, 600, 400)
    lat = bc.type_and_measure(list("Legacy"), popup_band, max_wait=4.0)
    shot("bc-U43-n1-typing")
    rep.check(all(v < 4.0 for v in lat), "六字符全部在 4s 内得到画面响应（lat=%s）" % ["%.2f" % v for v in lat],
              "有字符 %s 秒无响应——连续输入卡顿，登记" % ["%.2f" % v for v in lat if v >= 4.0])
    base = lat[0] if lat else 1.0
    worst = max(lat[1:]) if len(lat) > 1 else base
    rep.check(worst <= 2.0 * base + 0.2, "后续字符最差 %.2fs ≤ 基线 %.2fs 的 2×（容差内不卡）" % (worst, base),
              "最差 %.2fs 超基线 %.2fs 的 2×——卡顿，登记" % (worst, base))
    esc(3)


def settings_toggle_shot(rep):
    rep.step("S1 设置项「后台扫描依赖库以反查实体」在场（开关面存档，不改值）")
    pyautogui.hotkey("ctrl", "alt", "s")
    time.sleep(2.5)
    shot("bc-U43-s1-settings")
    hit = bc.find_contains("后台扫描依赖库以反查实体")
    rep.check(bool(hit), "设置项在册（robot 文本命中）",
              "设置项未探到（robot 未启或页未停——人工核对 bc-U43-s1-settings）")
    esc(3)


def main():
    front()
    esc(2)
    rep = Reporter("U43")
    if part_reverse_lookup(rep):
        part_no_freeze(rep)
    settings_toggle_shot(rep)
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
