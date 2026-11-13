---
name: project-runtime-slot-fill-20261107
description: 运行期占位可填值（T-RuntimeSlotFill）A/B/C 三券已合 master；占位身份内嵌注释、模板 %s
  歧义、四链路一致；顺带修括号内三元不被识别
metadata:
  node_type: memory
  type: project
  originSessionId: sess_33620d7f-a4ec-4f92-8fbe-dcd146afc43f
---

# T-RuntimeSlotFill：运行期拼接的 SQL 可填值（2026-11-07 已交付）

用户需求「运行期 sql 还得优化，最好让用户自己填参数，然后 sql 动态变」。三券串行实施后已合 master（`3af48ede` / `971f202a` / `ddc69c8e`，CHANGELOG `08e78946`，守卫修复 `f2d865b7`）。

## 设计要点（后续改动必须守住）

- **身份内嵌在注释文本里**：`/* 运行时值: <摘要> · rtN */`。不能用 char range——`SqlFormatter` 只动空白、会重排，字符位置必失效；这条由 `testP1_formatterKeepsBlockCommentTextVerbatim` 钉住。
- **结构化模板而非人读摘要**：循环给「首项/其余」两支（`id='%s' ` / `or id='%s' `）、三元两支、可选片段。模板 `%s` 个数 ≠ 预期值个数时**拒绝展开并提示**（用户源码本身可能含 `%s`，如 `LIKE '%s%'`）。
- **一次扫描 + 区间记账**：占位 / `?` / `:name` 三种替换在同一遍里原子产出，产出不再被扫描。真正会二次误伤的是 `:name`（那个正则不是串/注释感知的），所以不能串行三遍。
- **四链路一致**：预览 / 复制可执行 / 导出回放 / 执行必须取同一份填值后文本；`exportReplayScript` 走 `previewSqlForExport()`（快照 + 填占位），守卫 `ExplainPanelWiringGuardTest W2` 已放宽为「快照或其封装」，但**必须同时断言封装里不得出现 `currentEditorSql()`**——否则 body 会吞到后面调用快照的函数而假绿（本轮实证）。
- **留空即原样**：不填值与改动前逐字节一致（多条用例钉住），所以默认行为零变化。
- `PAIRED`（if/else 互斥）**没有**可填值对象，别假装能填。

## 顺带修的真 bug

被括号包住的三元（`(status == null ? "" : " AND status = " + status)`）此前不被识别为三元——polyadic 操作数是 `PsiParenthesizedExpression`，`concat` 返回 null 导致整段降级。修法：先 `PsiUtil.skipParenthesizedExprDown` 再聚合，占位文本仍用外层原文。

## 相关坑

- `ValueType.BOOLEAN` 一度「有消费零产出」被 `EnumReachabilityGuardTest` 判红 → 补 `SlotValue.bool()` 真产出点，**不要**加白名单。
- 合流后整类 `NoSuchMethodError`（data class 构造器）＝ Kotlin 增量没重编该测试类，见 [[pitfall-kotlin-incremental-stale-test-class]]。

## 已知缺陷：OPTIONAL 回填错位 + 幻影 `param1`（归 beta.2，不归 beta.1）

计划册 `docs/开发计划/20261107-0851-T-RuntimeSlotOptionalFill-可选片段回填错位与幻影param1-开发计划.md`（**另一会话的产物**）：报「包含这段」勾选/填值后替换位置错位，且参数行冒出不该有的 `param1`。本券只做到「四链路一致 + 留空逐字节原样」，**没有专门覆盖 OPTIONAL 与 `?`/`:name` 混排的错位**，所以大概率是真缺陷。

用户 2026-11-07 定：**该修复由并行会话直接在 master 实施，不影响当天 beta.1 的发行，随当天第二个内测版（`2026.11.7-beta.2`）发出去**。给后续改这块的人：OPTIONAL 与位置参数混排要有专门用例（勾选/取消 × 有值/空值 × 同语句含 `?`）。

相关：[[project-runtime-concat-delete-anchor-20261106]]、[[pitfall-runtime-placeholder-must-be-one-comment]]。
