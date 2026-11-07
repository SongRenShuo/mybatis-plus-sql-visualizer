---
name: pitfall-runtime-placeholder-must-be-one-comment
description: SQL 字符串预览的运行时占位 `/*运行时值*/` + 原样 Java 源码 + `*/` 不是注释——源码变活 SQL、末尾悬空、引号带偏词法器致括号丢失；`--` 行注释内联会吃掉后续 SQL；正解是单条 `/* 运行时值: 清洗后表达式 */`
metadata:
  node_type: memory
  type: project
---

`SqlStringConcatenator` 对静态不可求值片段输出 `/*运行时值*/` + 原样 Java 源码 + `*/`。前缀 `/*运行时值*/` 当场闭合，源码因此是活 SQL；末尾 `*/` 无对应 `/*`；源码里的 `"`/`'` 又把 SQL 词法器带偏——用户截图里 `WHERE (...)` 的右括号丢失、引号错位。

正解：单条合法块注释 `/* 运行时值: <清洗后表达式> */`——`*/`→`* /`，`'`→`’`，`"`→`”`，空白折叠为单空格，长度上限 120（超出加 …）。`--` 行注释不能用于内联占位（会把同一行后面的 `) AND time >= ...` 全注释掉）；`?` + 参数面板是备选，但拼接器分不清「值位置」与「语句片段位置」，`WHERE (?)` 语义不对。占位落在字符串字面量内部时 DB 看到的是字符串而非注释，靠面板「未完整还原」提示兜底。

同一拼接器被 JdbcTemplate / CustomDaoHql / HqlQuery 三处共用，改一处三处生效；既有断言 `contains("/*运行时值*/")` 需同批改为新前缀。

**复现与钉住（20261106）**：demo 仓新增 `RuntimeConcatReproController`（`mybatis-plus-demo/.../controller/`，三个方法 `listByLoopTernary` / `listByTernaryBranch` / `listByOptionalIf`，类注释写了修复前后预期）——用户原例骨架是「循环内 `append(k == 0 ? "id='…" : "or id='…")` + `WHERE (" + idStr + ") and create_time >= '…'`」。该骨架由**两条测试**钉住：`SqlStringConcatenatorTest`（R2 三元用例逐字含 `k == 0 ?`）与 `JdbcTemplateSqlPreviewServiceTest`（端到端断言「三元源码不应以活 SQL 形态出现」），定向 2 类 **25/0/0/0**；`SqlStringConcatenatorTest` 另有循环降级「只占位一次」断言。验收文档 B2-7 指到该 demo 用例。

**版本先验（20261106 教训）**：用户拿一张「Java 源码仍在活 SQL 里」的截图问「效果不是更好」，实际那张图是 **beta.1**——修复 `53de1ee4` **不是** tag `2026.11.6-beta.1` 的祖先（`git merge-base --is-ancestor 53de1ee4 2026.11.6-beta.1` 非零退出；对 HEAD 则通过）。判「用户装的版本含不含这个修复」一律用这条祖先判定，比读代码/猜构建态快，也避免把旧版截图当回归。

**beta.2 实测产物（同一段用户代码，临时探针跑出来的原文）**：
```
SELECT
    *
FROM hplt.autogen.calpoint
WHERE (
/* 运行时值: 循环内追加，运行时重复执行 */
)
    AND time >= '/* 运行时值: startTime */' tz ('Asia/Shanghai');
```
要点：Java 三元源码消失、`WHERE (...)` 括号完整、无悬空结束符；`startTime` 的占位**被包在引号里**是该位置本来要字符串值（位置语义，不是缺陷）；`tz (` 的空格是格式化器对函数调用的统一处理（`to_date (` 同形），不要为此特判。

**可再优化（20261106 用户追问，记为 T-RuntimeConcat-Polish 候选，均非 bug）**：①占位文案只写「循环内追加，运行时重复执行」，没说明追加的是什么片段——可在清洗后带模板摘要（仍走同一套 `*/`/引号安全化）；②引号内占位宜在**面板**加底色/角标提示，而不是改 SQL 文本；③对「固定模板 + 循环」做符号展开示例（展开两项并标「示例」）可读性最好，但属猜测式渲染，须单独立项评估。

**已派工（20261106，用户令「不阻塞 beta.2，排子智能体处理这个优化」）**：工作树 `.worktrees/concat-polish`、分支 `t-runtimeconcat-polish`，只做三件——**P1 必做**：循环占位带**清洗后的模板摘要**（如 `循环内追加 id='…' / or id='…'（运行时重复）`），来源只能是 append 里的字符串字面量片段、变量/三元条件一律替换 `…`，硬约束是既有 `assertFalse(contains("k == 0 ?"))` 继续绿；**P2 评估后定**：引号内占位只做面板提示、不改 SQL；**P3/P4 只写决策**：`tz (` 空格不特判、符号展开列为待评估不实现。

**Why**：降级占位的第一要求是「产物仍是合法 SQL」，否则预览展示的是比原 SQL 更糟的东西；原始源码里的引号/注释符会连带破坏词法与括号配对。
**How to apply**：改占位/降级文本时先写「注释内 / 字符串字面量内」两种位置的用例，断言 `*/` 只有一个、引号平衡；别用 `--` 做内联占位。关联 [[project-runtime-concat-delete-anchor-20261106]]。
