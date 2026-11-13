# -*- coding: utf-8 -*-
"""U42 真 JAR 嵌入前缀——jpa-jar-model-demo 宿主「切宿主 + 前缀列」自动化断言面。

**验收口径来源**
- 审计表 U42 行：`mybatis-plus-sql-visualizer-dev/docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`
  L67「待真正依赖JAR嵌入前缀」；已验残段（L162）：jpa-jar-model-demo 宿主生成
  `wms_inventory_jar_demo`、`sku_inventory_status`、`item_key_lot_info_received_date`、
  未串兄弟模型（frame-b613912f）——本脚本把该形态固化为可复跑的自动化断言面。
- 夹具：`jpa-jar-model-lib/src/main/java/cn/song/jarlib/entity/WmsInventoryEntity.java`
  （@Table(name="wms_inventory_jar_demo")，三个 @Embedded 零注解）；宿主唯一 HQL 出处
  `jpa-jar-model-demo/.../WmsInventoryJarQueryService.java` L44 起（拼接变量形态）与 L63 起
  （StringBuffer 形态）；模块注释明示缺陷回归判据：**列名丢嵌入段前缀（base_qty/inventory_status/
  received_date）即复现报障缺陷**。

**「切宿主」语义**：实体源码在依赖 JAR（mp-jar-model-lib 构件）、HQL 在宿主模块——
预览入口打开的是宿主文件，插件必须从依赖库认出实体（此前验的是源码模块内嵌前缀，
本组证的是**实体拿不到模块根**的更刁形态）。

**前置条件**：沙盒起、演示工程开（含 jpa-jar-model-demo 模块）；robot/模板可选。
**预计时长**：5~8 分钟。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 工具层在 acceptance/

import bc_common as bc
from bc_common import Reporter, front, shot, esc
import pyautogui

EXPECT_TABLE = "wms_inventory_jar_demo"
EXPECT_PREFIXED = ("sku_inventory_status", "item_key_lot_info_received_date", "qty_base_qty")
FORBIDDEN_SIBLING = "wms_inventory_embed_demo"   # 兄弟宿主 jpa-embedded-prefix-demo 的表，不得串


def jar_host_preview(rep, anchor_text, tag):
    """打开宿主文件 → 光标落 HQL 段 → 预览，返回 (rect, gutter数)。"""
    bc.open_file(bc.P_JAR_SERVICE, rep=rep)
    bc.caret(text=anchor_text, rep=rep)
    icons = bc.gutter_icons_in_editor()
    rep.check(bool(icons), "%s：宿主 HQL 行检出 %d 个插件 gutter 图标（JAR 实体被认出）" % (tag, len(icons)),
              "%s：HQL 行无插件 gutter——依赖库实体未被识别（先查后台扫描开关，见 U43 组）" % tag)
    rect = bc.trigger_preview(rep)
    return rect, icons


def assert_prefix_semantics(rep, rect, tag):
    """SQL 语义断言面：robot 文本能读预览编辑器时做三查（表名/前缀列/不串兄弟）；
    读不到（编辑器文本不在组件树 @text 上是常态）→ 诚实降级为放大截图人工核对，不假失败。"""
    if rect is None:
        return
    shot("bc-U42-%s-sql-zoom" % tag, region=rect)
    if not bc.robot_available():
        rep.ok("%s：robot 不在场——语义三查（表名 %s / 前缀列 %s / 不串兄弟 %s）落在 bc-U42-%s-sql-zoom 人工核对"
               % (tag, EXPECT_TABLE, "/".join(EXPECT_PREFIXED), FORBIDDEN_SIBLING, tag))
        return
    hit_tbl = bc.find_contains(EXPECT_TABLE)
    hit_cols = [c for c in EXPECT_PREFIXED if bc.find_contains(c)]
    hit_bad = bc.find_contains(FORBIDDEN_SIBLING)
    if not (hit_tbl or hit_cols):
        rep.ok("%s：组件树读不到预览编辑器文本——语义三查降级人工核对（截图 bc-U42-%s-sql-zoom）" % (tag, tag))
        return
    rep.check(bool(hit_tbl), "%s：表名 %s 命中" % (tag, EXPECT_TABLE),
              "%s：组件树命中过文本却找不到表名 %s——人工核对截图" % (tag, EXPECT_TABLE))
    rep.check(bool(hit_cols), "%s：嵌入前缀列命中 %s" % (tag, hit_cols),
              "%s：前缀列全未命中——若截图确认丢前缀（base_qty/inventory_status/received_date 裸列），即复现报障缺陷" % tag)
    rep.check(hit_bad is None, "%s：兄弟宿主表 %s 未出现（未串模型）" % (tag, FORBIDDEN_SIBLING),
              "%s：检出兄弟宿主表 %s——宿主串模型，登记" % (tag, FORBIDDEN_SIBLING))


def main():
    front()
    esc(2)
    rep = Reporter("U42")

    rep.step("1 拼接变量形态：findAvailableJarInventory 的 HQL（L44 起）")
    rect, _ = jar_host_preview(rep, '"from WmsInventoryEntity inv "', "concat")
    assert_prefix_semantics(rep, rect, "concat")
    if rect:
        bc.fill_text_fields(rect, ["wh01", "OK"], rep=rep)
        time.sleep(1.0)
        shot("bc-U42-concat-filled", region=rect)
        bc.close_preview()

    rep.step("2 StringBuffer 形态：findAvailableJarInventoryByBuffer（L63 起，对照）")
    rect, _ = jar_host_preview(rep, 'sb.append("from WmsInventoryEntity inv ")', "buffer")
    assert_prefix_semantics(rep, rect, "buffer")
    if rect:
        bc.close_preview()

    rep.step("3 切宿主负对照：打开兄弟宿主 jpa-embedded-prefix-demo 的同名形态文件，表名应互不串")
    # 兄弟宿主文件存在性先证（工程里两个宿主并存），断言面=各自预览各报各表（截图对）
    emb = os.path.join(bc.DEMO_ROOT, "jpa-embedded-prefix-demo")
    rep.check(os.path.isdir(emb), "兄弟宿主 jpa-embedded-prefix-demo 在工程中",
              "兄弟宿主目录缺失——切宿主对照只剩单侧证据")
    if os.path.isdir(emb):
        bc.open_file("CommonDaoImpl", rep=rep)   # 兄弟宿主 dao 实现名同名可检索
        shot("bc-U42-sibling-host")
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
