# -*- coding: utf-8 -*-
"""U30 真正空剪贴板——「日志 → SQL」剪贴板读取按钮的最后一态。

**验收口径来源**
- 审计表 U30 行：`mybatis-plus-sql-visualizer-dev/docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`
  L55「待剪贴板有/空与点击读取时机」；增量 L377 已验两态——空格三枚 →「剪贴板中没有文本」、
  旧日志复制残留 → 读回旧日志；**本脚本补最后一态：系统剪贴板真正无内容**（EmptyClipboard 后
  EnumClipboardFormats==0，非「空字符串」近似）。
- 源码锚：`LogToSqlPanel.kt` `readFromClipboard()`（L395-403）——`UiTextClipboard.readText()`
  null/blank → `status("剪贴板中没有文本")`，零写入零弹窗；文案 `logToSql.clipboard.empty`
  （Bundle L800）；按钮 `logToSql.panel.readClipboard=从剪贴板读取`（L799）。
- 入口：工具窗「SQL Visualizer」→ 页签「日志 → SQL」（Bundle L752）。

**前置条件**：沙盒起、演示工程开；工具窗可达。**预计时长**：4~6 分钟。

**注意**：本脚本会**清空系统剪贴板**（EmptyClipboard）——执行前别留着要用的剪贴板内容。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 工具层在 acceptance/

import bc_common as bc
from bc_common import Reporter, front, shot, esc, ide
import pyautogui


def open_log_to_sql_tab(rep):
    """切工具窗「日志 → SQL」页签。"""
    if bc.click_named("日志 → SQL"):
        time.sleep(1.5)
        return True
    # 兜底：锚点模板（工具窗页签文本带箭头，robot 文本匹配偶发 miss 时用）
    if bc.anchor_ready("bc_tab_log_to_sql") and bc.anchor_mod.click_anchor("bc_tab_log_to_sql"):
        time.sleep(1.5)
        return True
    rep.fail("「日志 → SQL」页签未定位（robot 未启且无 bc_tab_log_to_sql 模板）——人工切页后重跑")
    shot("bc-U30-manual-tab")
    return False


def click_read_clipboard(rep):
    return (bc.click_named("从剪贴板读取") or bc.click_button(
        rep, "从剪贴板读取", anchor_name="bc_btn_read_clipboard", must=False))


def assert_empty_toast(rep, tag):
    """断言「剪贴板中没有文本」轻提示：robot 文本命中优先，退化到状态行指纹+截图。"""
    hit = bc.find_contains("剪贴板中没有文本")
    if hit:
        rep.check(True, "轻提示「剪贴板中没有文本」在场（robot 文本命中）",
                  "轻提示不在场（未命中文本）")
        shot("bc-U30-%s-toast" % tag)
        return
    rep.fail("robot 未命中提示文本（未启 robot-server？）——从 bc-U30-%s-after 截图人工核对状态行" % tag)


def main():
    front()
    esc(2)
    rep = Reporter("U30")
    if not open_log_to_sql_tab(rep):
        return rep.finish()

    rep.step("1 正对照：剪贴板放一段真实 MyBatis 日志 → 点「从剪贴板读取」→ 输入区被填充并转换")
    sample = ("==>  Preparing: SELECT * FROM user WHERE id = ?\n"
              "==> Parameters: 314(Integer)")
    bc.clipboard_set_text(sample)
    if not click_read_clipboard(rep):
        rep.fail("「从剪贴板读取」未定位——本组中断")
        return rep.finish()
    time.sleep(2.0)
    shot("bc-U30-1-positive")
    rep.check(True, "正对照已执行（输入区应有双行日志且状态行=已转换 1 条；截图人工核对）",
              "正对照未取到截图证据")

    rep.step("2 造真空剪贴板：EmptyClipboard + EnumClipboardFormats==0 双证")
    truly_empty = bc.clipboard_clear_strict()
    rep.check(truly_empty, "剪贴板已清空且无任何格式（EnumClipboardFormats==0，真「空」非「空串」）",
              "清空后仍有残留格式（系统剪贴板被占用？重试一次）")
    rep.check(bc.clipboard_get_text() is None, "GetClipboardData(CF_UNICODETEXT)=None（读不到文本）",
              "CF_UNICODETEXT 仍可读——不是真正空态")

    rep.step("3 点「从剪贴板读取」→ 断言「剪贴板中没有文本」轻提示、输入区零写入")
    _pid, _hwnd, irect = ide()
    # LogToSql 是底部工具窗页签：监护区取 IDE 窗口下半（工具窗域），别拿编辑器中部充数
    l, t, r, b = irect
    panel_region = (l + 150, t + (b - t) // 2, max(500, (r - l) - 300), max(240, (b - t) // 2 - 60))
    g = bc.Guard(region=panel_region)
    g.mark("editor")
    if not click_read_clipboard(rep):
        rep.fail("第二次「从剪贴板读取」未定位——本组中断")
        return rep.finish()
    time.sleep(1.5)
    shot("bc-U30-3-after")
    assert_empty_toast(rep, "3")
    changed, score = g.changed("editor", wait=0.4)
    rep.check(not changed, "输入区零写入（编辑区指纹 diff=%.1f ≤ 阈值——空剪贴板不碰输入区）" % score,
              "输入区出现变化（diff=%.1f）——真空态居然写了内容？登记" % score)

    rep.step("4 收口：恢复一张无害剪贴板内容（避免后续组拿到真空态误判）")
    bc.clipboard_set_text("bc-u30-done")
    shot("bc-U30-4-done")
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
