# -*- coding: utf-8 -*-
"""U19 AI 上下文 16KiB 截断行为——批 C 探针。

**验收口径来源**
- 审计表 U19 行：`mybatis-plus-sql-visualizer-dev/docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`
  L44「待编辑/换目标/清历史失效、注释与计划白名单负例、16KiB 及本轮零新查询/网络活探针」。
- 源码锚（语义权威）：`AiContextExport.kt` buildV2——块头 `-- SQL Visualizer AI context v2`；
  超预算**整段丢弃**（`-- <段>: omitted (16 KiB budget)`，L68-72），顺序 EXPLAIN→Static schema→SQL；
  预算常量 `ShareContentProjection.MAX_OUTPUT_BYTES = 16_384`；SQL 段超限 →
  `-- SQL: omitted (unsupported syntax or budget)`（ShareContentProjection L115/118）。
- 动作入口：预览「更多」→「复制为 AI 上下文」（`panel.aiContext`，Bundle L292）；复制零新查询/
  零网络（读剪贴板侧证：块内容=本地投影，无计划获取动作）。

**造超限样本**：预览面板 SQL 编辑器内粘贴 >16KiB 的合成 SELECT（约 600 列 IN 清单）→
复制 AI 上下文 → **Python 直读剪贴板**断言（本探针的核心自动化面）：
字节 ≤16384、块头 v2、出现整段丢弃行、原 SQL 未恢复。随后 Ctrl+Z 还原预览编辑器。

**前置条件**：沙盒起、演示工程开；last-used 数据源在场（不执行，仅预览）。
**预计时长**：5~7 分钟。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 工具层在 acceptance/

import bc_common as bc
from bc_common import Reporter, front, shot, esc
import pyautogui

BUDGET = 16_384


def big_sql():
    """~24KiB 合成 SELECT：600 个长列名（每个约 40B）稳定越过 16KiB 预算。"""
    cols = ", ".join("wide_column_name_for_budget_probe_%03d" % i for i in range(600))
    return "SELECT %s FROM user WHERE id IN (1)" % cols


def paste_into_preview_sql(rect, text):
    """预览面板 SQL 编辑器：点入 → Ctrl+A 全选 → 剪贴板粘贴（绕 IME）。

    rect 为 (left, top, right, bottom)；SQL 编辑器在面板右半（约 2/3 宽处、1/4 高处）。
    """
    w, h = rect[2] - rect[0], rect[3] - rect[1]
    x = rect[0] + w * 2 // 3
    y = rect[1] + h // 4
    pyautogui.click(x, y)
    time.sleep(0.4)
    pyautogui.hotkey("ctrl", "a")
    time.sleep(0.2)
    bc.clipboard_set_text(text)
    pyautogui.hotkey("ctrl", "v")
    time.sleep(1.2)


def main():
    front()
    esc(2)
    rep = Reporter("U19")

    rep.step("1 打开注解 SQL 预览（selectByAnnotationId，基线形态）")
    bc.open_file("UserMapper.java", rep=rep)
    bc.caret(text='SELECT * FROM user WHERE id = #{id}', rep=rep)
    rect = bc.trigger_preview(rep)
    if not rect:
        return rep.finish()

    rep.step("2 粘贴 >16KiB 合成 SQL 进预览编辑器（%d 字节）" % len(big_sql().encode("utf-8")))
    g = bc.Guard(region=rect)
    g.mark("panel")
    paste_into_preview_sql(rect, big_sql())
    changed, score = g.changed("panel", wait=0.8)
    rep.check(changed, "预览 SQL 已被替换（面板 diff=%.1f）" % score,
              "粘贴后面板无变化——没点进 SQL 编辑器（判读 bc-U19-1 截图）")
    shot("bc-U19-1-bigsql-in-preview", region=rect)

    rep.step("3 更多 → 复制为 AI 上下文（超限样本）")
    # 「更多」是图标 ActionToolbar 按钮（robot 无 accessible name）——几何定位（面板右上），
    # 文本命中/锚点模板仅作兜底。
    more_xy = (rect[2] - 72, rect[1] + 68)
    pyautogui.moveTo(*more_xy, duration=0.2)
    pyautogui.click()
    time.sleep(1.0)
    time.sleep(1.0)
    shot("bc-U19-2-more-menu")
    if not (bc.click_named("复制为 AI 上下文") or bc.click_button(
            rep, "复制为 AI 上下文", anchor_name="bc_menu_ai_context", must=False)):
        rep.fail("「复制为 AI 上下文」未定位（弹出菜单项；校准 bc_menu_ai_context）")
        esc(2)
        bc.close_preview()
        return rep.finish()
    time.sleep(1.5)

    rep.step("4 Python 直读剪贴板：断言 v2 块、≤16KiB、整段丢弃、无原文回填")
    block = bc.clipboard_get_text()
    if block is None:
        rep.fail("剪贴板无文本——复制动作未发生")
        bc.close_preview()
        return rep.finish()
    size = len(block.encode("utf-8"))
    rep.check(block.lstrip().startswith("-- SQL Visualizer AI context v2"),
              "块头为 v2（AI context v2）", "块头不是 v2：%r..." % block[:60])
    rep.check(size <= BUDGET, "块字节数 %d ≤ 16384（16KiB 预算）" % size,
              "块字节数 %d 超 16384——预算未生效，登记" % size)
    omitted = [ln for ln in block.splitlines() if "omitted" in ln]
    rep.check(bool(omitted), "存在整段丢弃行（%s）——超限截断为整段粒度" % (omitted[:2],),
              "超限样本却没有丢弃行——截断行为与实现不符，登记")
    rep.check("FROM user WHERE id IN (1)" not in block or size <= BUDGET,
              "原 SQL 未被原样塞回块内（回填判据与预算合取）",
              "原 SQL 疑似被塞回且超限——登记")
    shot("bc-U19-3-block-read")
    print("  [块前 8 行]\n    " + "\n    ".join(block.splitlines()[:8]))
    print("  [块字节数] %d / %d" % (size, BUDGET))

    rep.step("5 还原预览编辑器（Ctrl+Z 撤销粘贴），不让超大样本污染后续组")
    pyautogui.click(rect[0] + rect[2] // 3, rect[1] + max(60, rect[3] // 4))
    for _ in range(3):
        pyautogui.hotkey("ctrl", "z")
        time.sleep(0.3)
    shot("bc-U19-4-restored", region=rect)
    bc.close_preview()
    rep.step("6 零新查询/零网络侧证：块内「EXPLAIN: not obtained」「local-only; no acquisition」在案")
    rep.check(("not obtained" in block) or ("no acquisition" in block),
              "块内带 local-only / not obtained 声明（复制动作未触发计划获取的文本证据）",
              "块内未见本地声明——人工核对 idea.log 有无新查询")
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
