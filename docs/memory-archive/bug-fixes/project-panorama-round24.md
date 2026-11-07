---
name: project-panorama-round24
description: 第二十四轮全景审计（20261031 晚）——合流拓扑坍缩为两棒、328 笔零冲突、竞品 CHP 已压上 MCP+agent 两根柱子
metadata:
  node_type: memory
  type: project
  originSessionId: sess_52a69e95-5c05-4e56-a116-83125031b550
---

2026-10-31 20:45 交付第二十四轮全景审计：报告 `docs/调研报告/20261031-2040-*第二十四轮调研报告.md`（223 行）、计划 `docs/开发计划/20261031-2100-*第二十四轮落地与长期演进-开发计划.md`（95 行），已入 `docs/索引/开发计划.md`。基线 master `afd3d091`。

**三条与第二十三轮相反的事实（主控亲测，引用前重测）**：
1. **合流三棒坍缩为两棒**：`t-fr45-integration`/`t-magpie-gems`/`t-settings-converge` 全部 `--is-ancestor` 于 `dev`（`dev..分支`=0）；master 缺 328 笔 = dev 117 + insight 216（重叠 5）。三组 `merge-tree --write-tree` 预演**全部零文本冲突**。
2. **顺序是硬约束**：`t-sql-insight` 缺 dev 的设置收敛 5 笔（`config/GutterGroupMultiSelect.kt`、`config/SettingsScenarioGroups.kt` 在 insight 上 `git cat-file -e` 失败）。必须 `master ← dev ← insight`，绕过 dev 会**静默丢功能且不报错**。
3. **第二十三轮处方已在 dev 全量执行**：D201/D202 五件拆分（全收 2000 红线内）/R45 转正默认开/NEW-18/NEW-19/UX 两小券/设置收敛四阶段/FR-0045 四项/magpie 四件——master 一件没有。

**新风险**：远端债 189 笔纯本地（dev 8／insight 131／k2j 50）+ `t-settings-converge` 整支无远端；insight 自 `bef08646` 起无全量门禁读数，历史 `8522/9/0/11` 有 9 红未归因。

**竞品反转（推翻第三十四轮「零逼近」结论）**：CHP 3.5.7（2026-07-02）起发 MCP（`run_mybatis_sql`/`validate_mybatis_mapper` 等 6+ 工具）、3.6.8（2026-10-27）加 Java Agent 零配置日志注入。护城河收缩为三条：14 方言含 7 信创、EXPLAIN 解读、跨 ORM。真实缺口：代码生成（`generator/` 仅 3 文件出 SQL 不出 Java）、编辑器内 Inspection（master `LocalInspectionTool` **0 个**）。CHP 付费版 id 是 **14522**（10081 是 JetBrains Academy）。

**新功能池**：NEW-21 信创兼容性体检／NEW-22 执行计划基线回归／NEW-23 MCP 安全写路径三件套／NEW-24 动态 SQL 分支覆盖矩阵／NEW-25 SQL→Java 补全闭环／NEW-26 编辑器内 Inspection 体系（最高性价比）。明确不做：方法名→SQL。

**master 质量底数（代码级 104 条清点）**：src/main 605 文件/140,317 行、测试 831 文件/5,283 @Test、守卫 53 件、i18n 1311 键、Boolean 54、lineMarker 17。五处新发现：①两处生产死代码 `sql/ComplexityScorer.kt`（387 行）+`log/SqlTableExtractor.kt`（35 行）零调用；②非法正则两页行为相反（`LogEntryFiltering.kt:323` 静默回退 vs `HistorySqlLogPanel.kt:764` 显式报错）；③`ExplainIndexAdvisor.ruleInventory()` 零消费；④四条导出出口静默 `return`；⑤`log/ProjectTypeDetector.kt:122` 原生非 Spring Boot 注入链未实现。

**Why**：这轮的核心不是「又多了什么功能」，而是「做完了却一件没交付」——执行率与变现率剪刀差拉到极限，且竞品第一次在两根柱子上正面跟进。
**How to apply**：引用合流笔数/分支吸收关系前先 `git rev-list --count` 重测（并行会话高频推进）；合流动作按 S1-1 留底 → S1-2 dev → S1-3 insight 顺序，每棒前重跑 `merge-tree`。相关：[[project-panorama-round23]]、[[project-competitive-round34]]、[[project-pending-plans]]。
