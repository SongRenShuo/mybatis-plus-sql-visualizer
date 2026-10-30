---
name: project-merge-master-to-dev-round4
description: T-MergeMasterToDev4（2026-10-13）master 55 笔并入 dev——A49
  控制台渲染/HQL/嵌入列前缀修复 + W1 区间级高亮的冲突裁决与 F79 缓存重挂
metadata:
  node_type: memory
  type: project
  originSessionId: sess_2b5062e9-21ee-418e-83b4-1ca64f07a665
---

2026-10-13 用户令「再次将主分支合并到 dev，testShards 通过即可，verifyGate 不跑」。master 又推进 **55 笔**：
★A49 原生控制台结果网格不渲染三连（`f5c91c23`/`571a8f8c`/`602e813a`）+ 归因更正（`d9d80eb0` 非插件缺陷）、
SQL 快捷模板光标处表名与失败反馈（`03b7e0ea`/`254a3670`/`61ef337e`）、T-HqlBufConcat（StringBuffer 拼接串）、
T-HqlHelperReturn（首参为同工程方法返回的字符串）、T-EmbeddedColPrefix 三连（嵌入列前缀）、增强捕获气泡收敛、
v2026.10.13 发行面二次同步、录制脚本批次与文档。**冲突 6 文件**：

- 文档 4：CHANGELOG（取 dev 侧 `[Unreleased]`；master 新条目在其 `[2026.10.13]` 段已自动并入 ⇒ 两侧都在）、
  docs/MEMORY.md（**整文件级冲突**，实测两侧「文档覆盖缺口为 0」⇒ 取 dev 侧，保留更多索引行）、
  docs/变更记录.md（union）、全景盘点（取 master 侧）。
- 代码 2（`ui/inject/`）：两文件都是「dev 旧形态 vs master 新形态」——master 的 **T-XmlGap W1 区间级互补**
  （`occupiedRanges` + `InjectRangePlan`）取代 dev 的「命中即整宿主让位」⇒ **整取 master 版**，并把 dev 的
  **F79 探针结论缓存**（master 侧缺）重挂到 W1 探针上；**memo 必须覆盖「布尔缝 + 区间探针」两条路径**
  （首版只包区间探针 ⇒ 测试注入的布尔缝 3 问 3 探，`XmlSynHiGateProbeTaxTest` 红）。

**读数**：编译门（主+测试+agent）exit 0；定向 inject 包 173 例 0 失败；**权威 testShards 7287-0-0-8**；
verifyGate 本轮未跑（用户令）。提交链：`aee56deb`（合并）→ F79 修复笔。**未 push**。

**过程两条教训**（详见 [[pitfall-refactor-move-missing-line-check]]）：① 首跑 645 红中 618 是
`PluginSettings$State.<init>` 的**陈旧测试类假失败**（第 0 条，`compileTestKotlin --rerun-tasks` 后即消）；
② 自查「dev 独有行」必须用**合并前 tip** 作参照——`dev` 分支名在合并后已指向合并提交，直接比等于拿结果跟
自己比（本轮先误判「master 改动全丢」，改用 `e7f6947` 重做后确认无丢失）。关联 [[project-merge-master-to-dev-round3]]。
