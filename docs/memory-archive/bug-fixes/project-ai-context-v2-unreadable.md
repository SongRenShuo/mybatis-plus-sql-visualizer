---
name: project-ai-context-v2-unreadable
description: 「复制为 AI 上下文」v2 输出不可读——ShareContentProjection 只保留开头语句种类；
20261019 已实施 A（结构词白名单）+ B（含表列名第二出口）dda1c656
metadata:
  node_type: memory
  type: project
  originSessionId: session_7c4f9878c3e52efd1a834ad34e2354ac
---

# AI 上下文 v2：结构词可读 + 含表列名出口（2026-10-19 已实施）

用户贴出「复制为 AI 上下文」产物问「有什么作用、是不是功能有问题」，随后两次追问。结论：**代码路径没坏，是脱敏投影把结构也铲平了**；用户拍板按推荐 A+B 一并落计划并实施。

## 关键坑：计划提交 ≠ 实施

第一轮我只写了计划文档并提交（`ce1c6927`），用户下一轮仍贴**同一段旧输出**质疑——因为代码没改、沙盒 jar 也是旧的。教训：用户报障后只交付计划时，必须明说「未改码、需重新构建并重启 runIde 才有新行为」，否则会被当成「还是坏的」。

## 根因

- `ShareContentProjection` 单词分支：只有整条 SQL 最开头的语句种类原样保留，其余字母 token 一律 `identifier_N`；`NULL/TRUE/FALSE` → `?`。
- 来自 2026-10-16 `63f37cdf`「关闭关键词同名标识符与布尔值泄漏」：删掉约 60 词白名单，理由是 `COUNT/SUM/JSON/FIRST/NEXT` 可合法当裸列名。顾虑成立，但连 `FROM/WHERE/JOIN/AS` 一起匿名，结构被铲平。
- `identifier_N` 编号空间表名/列名/别名/关键字共用且按原始大小写区分（`USER` 与 `user` 两个号）。
- v1 `AiContextExport.build()`（保留表列名、只打码字面量）代码还在但 UI 零调用方。

## 最终形态（dda1c656）

**A 默认出口**：`STRUCTURAL_KEYWORDS` 保守白名单——`SELECT/INSERT/UPDATE/DELETE/WITH/EXPLAIN/FROM/WHERE/AND/OR/NOT/AS/ON/IN/IS/JOIN/LEFT/RIGHT/INNER/OUTER/FULL/CROSS/UNION/ALL/DISTINCT/GROUP/BY/ORDER/HAVING/ASC/DESC/LIMIT/OFFSET/CASE/WHEN/THEN/ELSE/END/EXISTS/BETWEEN/LIKE/INTO/VALUES/SET`。**继续匿名**：`COUNT/SUM/AVG/MIN/MAX/COALESCE/CAST/FORMAT/JSON/FIRST/NEXT/KEY/INDEX/TYPE/NAME/VALUE/STATUS/USER/DATE`；方言保留级别不一的（`USING/NATURAL/LATERAL/OVER/PARTITION/WINDOW/FILTER/ROWS/ROW/ONLY/FETCH/TOP/ILIKE/ESCAPE/RETURNING/RECURSIVE`）不收。

**B 第二出口**：菜单第九项 `panel.aiContext.identifiers`「复制为 AI 上下文（含表列名）」→ `copyAsAiContext(includeIdentifiers = false)` → `buildV2(..., includeIdentifiers = false)` → `ShareContentProjection.sql(sql, includeIdentifiers)`。头部 `identifiers visible as written (quoted tokens stay hidden)`；静态摘要 `table=…; columns=N: …`（最多 50 列，超出 `…(+N)`）；新 toast `ai.context.v2.copied.identifiers`。**引号内容（`"user"`）两种模式都不复制**；值始终打码；不新增设置项、不记忆选择。

## 改这几个文件时的连带点

- `SqlPreviewTopRowLayoutTest` 精确锁定「更多」菜单项与顺序——加项必须同步断言、注释文案。
- `AiContextV2OfflineBoundaryTest` 用 `between("internal fun SqlPreviewPanel.copyAsAiContext(", "…exportPortableSql()")` 切片：签名加默认参数要**同步改锚点字符串**，否则切片为空、断言失效。
- 面板「含名出口」断言要盯 **SQL 段本身**（`SELECT private_column FROM private_table`），不能只断言表名出现在静态摘要——否则摘掉 `includeIdentifiers` 透传仍绿（首版就这样假绿一次，补断言后反向恰 3 红）。

## 读数与状态

定向 5 类 **33/0/0/0**（`ShareContentProjectionTest` 8 / `AiContextV2ExportTest` 10 / `AiContextV2PanelTest` 5 / `AiContextV2OfflineBoundaryTest` 1 / `SqlPreviewTopRowLayoutTest` 9）；反向：摘白名单恰 3 红、忽略 `includeIdentifiers` 透传恰 3 红。dev 提交链 **`0f06bcc4`（组 Runner）→`ce1c6927`（计划）→`dda1c656`（实施）→`eca7aecc`（影响面预演 loading）→`8bd0c478`→`9c1a929c`→`b6a88945`（FieldFill 面板三轮）**，均未 push。

相关：[[pitfall-reflection-invoke-visibility]]（组 Runner 修复）、[[pitfall-partial-submission-loading-stuck]]（影响面预演 loading）、[[project-fieldfill-panel-ux]]（FieldFill 面板细节全在该档）、[[project-dml-impact-editor-replacement]]（影响面预演换掉编辑器的交互债）、[[pitfall-fake-green-test-assertions]]、[[feedback-plan-doc-when-asked]]。
