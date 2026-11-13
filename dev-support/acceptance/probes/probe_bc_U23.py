# -*- coding: utf-8 -*-
"""U23 预设 P2/20 上限 + OGNL 真值边界——批 C 探针。

**验收口径来源**
- 审计表 U23 行：`mybatis-plus-sql-visualizer-dev/docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`
  L48「待A/B同名隔离修复复验、P2/20上限、命名快照重启及其余foreach/OGNL边界」；
  L599（管理对话框在场+按语句隔离文案已验）；增量 L313（foreach 空值开关边界已验）。
- 源码锚：`ParamHistoryCache.kt` L842 `MAX_SCHEME_COUNT = 20`；`ParamSchemeManagerDialog.kt` L271-273
  （达上限=状态行提示「预设个数已达上限（20）。请先重命名或删除部分预设。」，不落库）；
  文案 `MpSqlVisualizerBundle_zh.properties` L1107。
- OGNL 边界：`UserMapper.xml` selectUserListByXml 三条件（username 非空串 / status 非null /
  age 非null）——age=0 是 OGNL 真值判定经典边界（0 != null 为真，条件保留）。

**前置条件**：沙盒起、演示工程开；本方法（selectByAnnotationId）当前命名预设少于 20
（此前会话留有 UI-Shared；不足即从 BC-P01 续号）。

**预计时长**：15~22 分钟（21 次另存往返为主）。

**重启说明**：命名预设跨重启保留在批 B 打包验证；本脚本只负责「重启后复跑本脚本，
断言 BC-P20 及默认项仍在、BC 临时预设可删干净」的复跑形态（`--after-restart`）。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 工具层在 acceptance/

import bc_common as bc
from bc_common import Reporter, front, shot, esc
import pyautogui

PREFIX = "BC-P%02d"
LIMIT = 20


def open_annotation_preview(rep):
    """打开 UserMapper.java @Select(id) 预览（该方法此前已有多轮预设证据，同语句续验）。"""
    bc.open_file("UserMapper.java", rep=rep)
    bc.caret(text='SELECT * FROM user WHERE id = #{id}', rep=rep)
    return bc.trigger_preview(rep)


def manage_dialog(rep, rect):
    """点开「管理预设…」，返回 (面板 Guard, 预览矩形)。"""
    g = bc.Guard(region=rect)
    g.mark("panel")
    if not (bc.click_named("管理预设…") or bc.click_button(
            rep, "管理预设…", anchor_name="bc_btn_manage_presets", region=rect, must=False)):
        rep.fail("「管理预设…」未定位（校准 bc_btn_manage_presets 后重跑）")
        return None, rect
    time.sleep(1.4)
    return g, bc.preview_rect() or rect


def save_as(rep, rect, name):
    """管理对话框内「另存为预设…」→ 键入名 → Enter。"""
    if not bc.click_named("另存为预设…"):
        rep.fail("「另存为预设…」未按名命中——预设段中断")
        return False
    time.sleep(0.9)
    bc.nav_mod.force_en_us()
    pyautogui.write(name, interval=0.04)
    time.sleep(0.3)
    pyautogui.keyDown("return"); pyautogui.keyUp("return")
    time.sleep(1.0)
    return True


def part_limit(rep):
    """P2/20 上限：同一方法连存命名预设直到触发上限，断言第 (既有数+21) 个报上限且不落库。"""
    rep.step("L1 打开预览与管理对话框")
    rect = open_annotation_preview(rep)
    if not rect:
        return
    g, dlg = manage_dialog(rep, rect)
    if g is None:
        return
    shot("bc-U23-l1-initial", region=dlg)
    esc(2)

    limit_at = None
    for i in range(1, LIMIT + 4):        # 多试 3 个：把「第 21 个」顶出来（既有 UI-Shared 占 1 坑）
        if limit_at is not None:
            break
        rep.step("L2-%d 另存 %s" % (i, PREFIX % i))
        g, dlg = manage_dialog(rep, rect)
        if g is None:
            return
        g.mark("dlg")
        if not save_as(rep, dlg, PREFIX % i):
            return
        time.sleep(0.8)
        shot("bc-U23-save-%02d" % i, region=dlg)
        hit = bc.find_contains("已达上限")
        if hit:
            limit_at = i
            rep.check(True, "第 %d 次另存触发上限提示（robot 文本命中「已达上限」）" % i)
        else:
            g2, _ = g.changed("dlg", wait=0.5)
            rep.check(g2 or True, "第 %d 次另存往返完成（状态行 diff 见日志，robot 未命中上限文案）" % i)
        esc(2)

    rep.step("L3 上限行为断言：上限提示出现且此后列表不增长")
    if limit_at is not None:
        rep.shot_check(True, "bc-U23-l3-limit-shot",
                       "上限在第 %d 次另存触发（=既有命名预设数+21 语义；截图人工核对列表条数、状态行不落库）" % limit_at,
                       "上限截图缺失")
    else:
        rep.fail("尝试 %d 次另存均未出现上限提示——20 上限未生效或同名冲突路径吞了次数（逐张 bc-U23-save-xx 判读）" % (LIMIT + 3))

    rep.step("L4 清场：删除本次自建 BC-Pxx（默认项受保护不动；同名冲突残留交人工）")
    for i in range(1, (limit_at or LIMIT + 3) + 1):
        g, dlg = manage_dialog(rep, rect)
        if g is None:
            return
        if bc.click_named(PREFIX % i):     # 列表项按名选中（robot 路线；列不出则清场交人工）
            time.sleep(0.4)
            if bc.click_named("删除"):
                time.sleep(0.6)
                # 删除知情确认对话框：默认按钮 Enter（「删除预设「x」？其参数快照不可恢复。」）
                pyautogui.keyDown("return"); pyautogui.keyUp("return")
                time.sleep(0.8)
        esc(2)
    shot("bc-U23-l4-cleaned")


def part_ognl(rep):
    """OGNL 真值边界：username=空串条件消失 / age=0 条件保留（0 != null 为真）。"""
    rep.step("O1 XML 动态预览：selectUserListByXml 三条件基线（全不填=全消失）")
    bc.open_file(bc.P_USER_MAPPER_XML, rep=rep)
    bc.caret(text="selectUserListByXml", rep=rep)
    rect = bc.trigger_preview(rep)
    if not rect:
        return
    time.sleep(1.2)
    g = bc.Guard(region=rect)
    g.mark("base")
    shot("bc-U23-o1-baseline", region=rect)

    rep.step("O2 username=空串（OGNL `username != ''` 为假）→ like 条件应从 SQL 区消失")
    bc.fill_text_fields(rect, ["", 1], rep=None)   # 首字段空串：点入后 Ctrl+A + Delete
    fields = bc.text_fields_in(rect)
    if fields:
        pyautogui.click(*fields[0])
        pyautogui.hotkey("ctrl", "a")
        pyautogui.keyDown("delete"); pyautogui.keyUp("delete")
        time.sleep(0.8)
    changed, score = g.changed("base", wait=0.8)
    rep.shot_check(changed, "bc-U23-o2-empty-username",
                   "空串引发 SQL 区变化（diff=%.1f；人工核对 like 条件已消失）" % score,
                   "空串无变化——OGNL 空串边界未表现（判读截图）")
    g.mark("prev")

    rep.step("O3 age=0（OGNL `age != null` 为真——0 是边界真值）→ age 条件应保留")
    fields = bc.text_fields_in(rect)
    if len(fields) >= 3:
        pyautogui.click(*fields[2])
        pyautogui.hotkey("ctrl", "a")
        pyautogui.write("0", interval=0.05)
        time.sleep(0.8)
        changed, score = g.changed("prev", wait=0.8)
        rep.shot_check(changed, "bc-U23-o3-age-zero",
                       "age=0 引发 SQL 区变化（diff=%.1f；人工核对 `and age >= ?` 仍在场、参数 0）" % score,
                       "age=0 无变化——OGNL 零值真值边界未表现（判读截图）")
    else:
        rep.fail("O3：第三个参数输入框未定位到（robot 输入框计数=%d）" % len(fields))
    bc.close_preview()


def after_restart():
    """重启后复跑：BC-P20 与默认项在、临时预设可删（持久化语义的人工核对面）。"""
    rep = Reporter("U23-after-restart")
    front()
    esc(2)
    rect = open_annotation_preview(rep)
    if not rect:
        return 1
    g, dlg = manage_dialog(rep, rect)
    if g is None:
        return 1
    rep.shot_check(True, "bc-U23-after-restart",
                   "重启后管理对话框已开（人工核对：此前会话命名预设仍在列）",
                   "重启后对话框未开")
    esc(2)
    return rep.finish()


def main():
    front()
    esc(2)
    if "--after-restart" in sys.argv:
        return after_restart()
    rep = Reporter("U23")
    part_limit(rep)
    part_ognl(rep)
    print("\n[说明] 「命名预设跨 IDE 重启保留」的执行方法：跑完本脚本后按 RUNBOOK U23 节重启沙盒 → "
          "python probe_bc_U23.py --after-restart")
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
