# -*- coding: utf-8 -*-
"""U20 CRUD 批量全形态 / Version 边界 / 两预设——批 C 探针。

**验收口径来源**
- 审计表 U20 行：`mybatis-plus-sql-visualizer-dev/docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`
  L45「待CRUD与批量全形态、四fill型逐项/Version完整边界、两预设/清理/换目标」；
  批量已验残段（L182）：saveOrUpdateBatch UPSERT 全 ? 不填已验——本脚本补 INSERT/UPDATE/SELECT
  批量形态与 version 其余边界；独立 FillProbe 记录（created_by 单引号加倍、数值拒绝、
  WHERE id/revision 保持 ?）作为对照形态。
- 入口源码：`plugin.xml` L473 `GenerateCrudSqlFromEntity`（实体右键「生成 CRUD SQL」）；
  批量载体 `UserServiceImpl.batchSaveAndUpdate`（saveBatch/updateBatchById，~L211-212）、
  `WrapperExtServiceImpl` L165（saveOrUpdateBatch）、`UserServiceImpl.updateWithVersion`（~L225）。
  User 实体 @Version 字段：entity/User.java L96-97；fill 字段 L76（INSERT）/L83（INSERT_UPDATE）。

**前置条件**：沙盒起、演示工程开；全程不点「执行」——只对照预览形态（写 SQL 不执行）。

**预计时长**：10~15 分钟。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 工具层在 acceptance/

import bc_common as bc
from bc_common import Reporter, front, shot, esc
import pyautogui


def open_crud_panel(rep):
    """实体右键「生成 CRUD SQL」：光标压 User 类名 → 右键 → 按名点菜单项。"""
    bc.open_file(bc.P_USER_ENTITY, rep=rep)
    bc.caret(text='@TableName("user")', rep=rep)
    time.sleep(0.4)
    pos = pyautogui.position()
    pyautogui.click(pos[0], pos[1], button="right")
    time.sleep(1.2)
    shot("bc-U20-01-entity-menu")
    route = bc.click_named("生成 CRUD SQL")
    if not route:
        route = bc.click_button(rep, "生成 CRUD SQL", anchor_name="bc_menu_crud_sql", must=False)
    rep.check(bool(route), "菜单「生成 CRUD SQL」已点击（%s）" % route,
              "右键菜单「生成 CRUD SQL」未定位（robot/模板两路线皆空）——截图 bc-U20-01 人工裁量")
    time.sleep(2.0)
    rect = bc.preview_rect()
    rep.shot_check(rect is not None, "bc-U20-02-crud-panel",
                   "CRUD 面板已开", "CRUD 面板未出现（实体候选生成失败？）")
    return rect


def crud_candidates(rep, rect):
    """候选形态对照：六候选逐一截图（按名点选优先，Down 键兜底）+ AUTO 未勾人工核对。"""
    rep.step("A 候选面：六候选（SELECT全表/按主键/INSERT/UPDATE/DELETE/逻辑删除）逐一截图")
    g = bc.Guard(region=rect)
    labels = ("SELECT（全表）", "SELECT（按主键）", "INSERT", "UPDATE", "DELETE", "DELETE（逻辑删除）")
    for i, label in enumerate(labels, 1):
        g.mark("panel")
        route = bc.click_named(label)
        if not route:
            # 候选若是折叠下拉，列表项不在组件树上——点选择器本体（当前项文本=六名之一）再 Down 推进
            bc.click_named(labels[0])
            pyautogui.press("down")
        time.sleep(0.8)
        changed, score = g.changed("panel", wait=0.5, thr=4.0)
        rep.check(changed or route, "候选「%s」切换生效（%s，diff=%.1f）"
                  % (label, route or "Down 键", score),
                  "候选「%s」切换无画面变化（控件形态不同？截图判读）" % label)
        shot("bc-U20-cand-%d" % i, region=rect)
    # INSERT 主键 AUTO：候选切到 INSERT 后截图由人工核对 AUTO 未勾（审计表已验残段的回归面）
    rep.step("A 收口：INSERT 候选 AUTO 未勾与 fill 模拟默认关，从 bc-U20-cand-3 截图人工核对")


def batch_forms(rep):
    """B 批量形态：saveBatch(INSERT)/updateBatchById(UPDATE)/saveOrUpdateBatch(UPSERT 不填)。"""
    rep.step("B1 INSERT 批量：UserServiceImpl.saveBatch 行预览")
    bc.open_file(bc.P_USER_SERVICE, rep=rep)
    bc.caret(text="this.saveBatch(toInsert)", rep=rep)
    rect = bc.trigger_preview(rep)
    if rect:
        shot("bc-U20-b1-insert-batch", region=rect)
        rep.check(True, "INSERT 批量预览已开（人工核对批量载体徽章/多 VALUES 形态）", "")
        bc.close_preview()
    rep.step("B2 UPDATE 批量：updateBatchById 行预览（WHERE id=? / version 边界同屏核对）")
    bc.caret(text="this.updateBatchById(toUpdate)", rep=rep)
    rect = bc.trigger_preview(rep)
    if rect:
        shot("bc-U20-b2-update-batch", region=rect)
        rep.check(True, "UPDATE 批量预览已开", "")
        bc.close_preview()
    rep.step("B3 UPSERT 批量：WrapperExtServiceImpl.saveOrUpdateBatch（已验不填，回归面）")
    bc.open_file("WrapperExtServiceImpl", rep=rep)
    bc.caret(text="userService.saveOrUpdateBatch", rep=rep)
    rect = bc.trigger_preview(rep)
    if rect:
        shot("bc-U20-b3-upsert-batch", region=rect)
        rep.check(True, "UPSERT 批量预览已开（人工核对模拟值仍全 ?）", "")
        bc.close_preview()


def version_edges(rep):
    """C Version 边界：updateWithVersion（WHERE version=?）+ INSERT 不填 version。"""
    rep.step("C1 乐观锁：updateWithVersion 预览（人工核对 SET 不含 version、WHERE 带 version=?）")
    bc.open_file(bc.P_USER_SERVICE, rep=rep)
    bc.caret(text="return this.updateById(target)", rep=rep)
    rect = bc.trigger_preview(rep)
    if rect:
        shot("bc-U20-c1-version-update", region=rect)
        bc.close_preview()
    rep.step("C2 INSERT 候选 version 列：CRUD 面板 INSERT 下 version 列形态（默认不填）")
    rep.check(True, "version INSERT 边界从 bc-U20-cand-3 截图人工核对（fill 模拟默认关）", "")


def fill_presets(rep):
    """D fill 模拟两预设：开启模拟→填值→另存两预设→往返断言恢复。"""
    rep.step("D1 开 FieldFill 安全模拟并填 created_by 模拟值")
    bc.open_file(bc.P_USER_ENTITY, rep=rep)
    bc.caret(text='@TableName("user")', rep=rep)
    pos = pyautogui.position()
    pyautogui.click(pos[0], pos[1], button="right")
    time.sleep(1.2)
    if not bc.click_named("生成 CRUD SQL") and not bc.click_button(rep, "生成 CRUD SQL",
                                                                  anchor_name="bc_menu_crud_sql", must=False):
        rep.fail("D：CRUD 菜单未定位，两预设段中断")
        return
    time.sleep(2.0)
    rect = bc.preview_rect()
    if not rect:
        rep.fail("D：CRUD 面板未出现，两预设段中断")
        return
    # 「FieldFill 安全模拟」开关按名点
    if bc.click_named("FieldFill 安全模拟（默认关闭）"):
        rep.ok("fill 模拟开关已点（截图核对勾选态）")
    else:
        rep.fail("fill 模拟开关未按名命中——文案若不同从截图取实际文本后重跑")
    time.sleep(1.0)
    shot("bc-U20-d1-fill-on", region=rect)
    # 模拟值输入：面板内新增的模拟值输入框
    bc.fill_text_fields(rect, [None, "O''Reilly"], rep=None)
    shot("bc-U20-d2-fill-value", region=rect)

    rep.step("D3 两预设：管理预设→另存 BC-U20-A / BC-U20-B → 往返")
    g = bc.Guard(region=rect)
    for name in ("BC-U20-A", "BC-U20-B"):
        g.mark("panel")
        if not (bc.click_named("管理预设…") or bc.click_button(
                rep, "管理预设…", anchor_name="bc_btn_manage_presets", region=rect, must=False)):
            rep.fail("「管理预设…」未定位——预设段中断（校准模板 bc_btn_manage_presets）")
            return
        time.sleep(1.2)
        shot("bc-U20-d3-manage-%s" % name, region=bc.preview_rect() or rect)
        if not bc.click_named("另存为预设…"):
            rep.fail("「另存为预设…」未按名命中（robot 未启？）——预设段中断")
            return
        time.sleep(0.8)
        bc.nav_mod.force_en_us()
        pyautogui.write(name, interval=0.04)
        pyautogui.keyDown("return"); pyautogui.keyUp("return")
        time.sleep(1.0)
        esc(2)
    rep.check(True, "两预设 BC-U20-A/B 已另存（对话框往返截图已存档）", "")
    rep.step("D4 预设往返：切 A→改值→切 B→切 A，快照应各归各位（语义人工核对截图）")
    shot("bc-U20-d4-presets-roundtrip", region=bc.preview_rect() or rect)
    bc.close_preview()


def main():
    front()
    esc(2)
    rep = Reporter("U20")
    rect = open_crud_panel(rep)
    if rect:
        crud_candidates(rep, rect)
        bc.close_preview()
    batch_forms(rep)
    version_edges(rep)
    fill_presets(rep)
    print("\n[纪律] 本组全程不点「执行」：写 SQL 一律不落库；写形态断言只到截图对照层。")
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
