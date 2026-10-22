# T-UnboundedWriteGuard 执行链无界写判定 — 开发计划

- 日期：2026-09-15 08:57
- 任务编号：T-UnboundedWriteGuard（第三轮建议 **O-9**，滚动挂账本轮收口，P2）
- 来源：第三/四轮竞品调研报告——DBX 源码级验证过的无界写规则集（`sql_risk.rs:144-223`：WHERE 缺失/恒真/`LIKE '%'`/子查询/无列引用即危险，解析失败 fail closed）；第四轮报告 §8.4 给出新落点（RollbackSql 评级骨架 + 执行链 DML 确认流）
- 性质：存量安全增强（纯逻辑 + 既有确认流文案升级）
- 实施分支：基于 dev 开任务分支 t-unbounded-write
- 验证基线：dev 全量 `./gradlew testShards` 4246/0/0/6 绿

---

## 一、背景与依据

本插件执行链现状是「关键字分类 + 一次确认」：`SqlTypeClassifier`（M36，QUERY/DML/DDL 关键字级）判 DML/DDL 后，`LogToSqlPanel.kt:288` 弹一个 `showYesNoDialog`（`execute.confirm.dml`）二次确认。这条流对 `DELETE FROM t` 与 `DELETE FROM t WHERE id=1 AND created>'2026-01-01'` 给出的是**同一个问题**——用户面对高频确认会形成点击惯性，真正的全表删除被平均对待。DBX 用 sqlparser AST 静态风险分类解决了这个问题且经 19k star 社区检验；本插件已有 JSqlParser 依赖与解析权威原则（RollbackSqlGenerator 注释明确「语句类型分发按解析出的 AST 类型，parser 权威」），规则集可以直接表达。

第三轮提报后第四轮 grep 复核确认仍零实现（`无界|恒真|unbounded` 在 src/main 无命中）；T-RollbackSql 的 F2 三档评级（COMPLETE/PARTIAL/UNABLE）是回滚完整性评级，不覆盖对原语句的写风险判定。本轮收口。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| 类型分类 | 关键字级：QUERY（SELECT/SHOW/EXPLAIN/DESCRIBE/WITH）免确认；DML（INSERT/UPDATE/DELETE/MERGE）与 DDL（CREATE/ALTER/DROP/TRUNCATE）需确认；多条混合按最高风险；自带 splitStatements 状态机 | `execute/SqlTypeClassifier.kt` |
| 确认流 | LogToSqlPanel 执行入口 `showYesNoDialog`，文案键 `execute.confirm.dml.message`/`execute.confirm.dml`，NO 即中断 | `log/LogToSqlPanel.kt:288-294` |
| 解析权威 | JSqlParser parseStatements（引号内分号安全、AST 类型分发），RollbackSqlGenerator 已立先例 | `sql/rollback/RollbackSqlGenerator.kt:40-48` |
| fail-closed 先例 | MCP 侧 McpSelectGuard：非 SELECT 拒绝、解析失败拒绝（McpToolErrors.SQL_BLOCKED） | `mcp/McpSelectGuard.kt` |
| 回滚评级 | Rating COMPLETE/PARTIAL/UNABLE + RollbackWarning（messageKey+args，零 UI 依赖），PARTIAL 已覆盖「整行谓词」（rollback.warn.fullRowPredicate） | `RollbackSqlGenerator.kt:50-70` |

## 三、方案

**T-UW-01（纯逻辑核心）**：新增 `execute/UnboundedWriteDetector.kt`（object，零 PSI/零 IDE 依赖，仅 JSqlParser + SqlCommentStripper，对齐 RollbackSqlGenerator 风格）。输入 SQL 文本，输出 `data class WriteRisk(val findings: List<Finding>)`，Finding 规则集（DBX sql_risk.rs 对齐，JSqlParser 表达）：

| 规则 | 判定 |
|---|---|
| WHERE 缺失 | UPDATE/DELETE 无 WHERE 子句 |
| 恒真谓词 | `1=1`、`a=a`、`'x'='x'`、`TRUE` 字面量等值/同值比较 |
| LIKE 全匹配 | `LIKE '%'` / `'%%'` / 纯通配 |
| OR 恒真 | WHERE 顶层含 OR 且任一侧命中恒真 |
| 无列引用 | WHERE 仅常量表达式（无任何列引用） |
| 子查询 | WHERE 含子查询（提示型，不算危险，供文案分级） |

解析失败 **fail closed**：返回 `PARSE_FAILED` finding，按最高风险处理。UPDATE 与 DELETE 判定一致；INSERT 不适用（忽略）。

**T-UW-02（执行链接线，不新增弹窗）**：`LogToSqlPanel` 执行链在既有 `showYesNoDialog` 之前调用探测器；无 finding → 现确认流原样（文案不变，频率不变）；有 finding → 确认对话框消息升级为列出发现（如「检测到无 WHERE 的全表 DELETE」），`execute.confirm.dml` 复用同一对话框形态，只换 message 文案——遵守弹窗克制红线（`docs/设计规范-弹窗克制红线.md`），不新增弹窗形态、不新增步骤。

**T-UW-03（RollbackSqlGenerator 评级接入）**：PARTIAL 评级补两类触发面——恒真谓词、LIKE 全匹配（复用 T-UW-01 探测器），warnings 追加对应 RollbackWarning 键；WHERE 缺失已有 fullRowPredicate 覆盖不动。

**T-UW-04（i18n + 测试）**：双语新键 `execute.confirm.unbounded.summary` 及按 finding 的明细键（文案中性，说事实不说教）；测试表驱动——每条规则 ≥3 正例 + 反例 + 解析失败样例 + 多语句取最高风险；LogToSqlPanelExecuteTest 补确认文案分支；RollbackSqlGeneratorTest 补新 warning 断言。

**T-UW-05（文档同步）**：CHANGELOG [Unreleased] Added、变更记录总册行、第四轮报告 §8.4 O-9 状态回填。

## 四、验收标准

1. 规则集表驱动测试全绿（正例/反例/解析失败/多语句四级）。
2. 执行链行为：无 finding 时确认流零变化（原文案键不动）；有 finding 时同一对话框列出发现；**不出现任何新弹窗形态**。
3. RollbackSqlGenerator 新增两类 PARTIAL 触发面有测试覆盖，既有 63 用例零回归。
4. 全量 test 绿（基线 4246/0/0/6 + 新增数），跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 真机执行 `DELETE FROM t`（确认对话框出现「无 WHERE」发现）与 `DELETE FROM t WHERE id=1`（原文案）对比。
2. 恒真/`LIKE '%'` 样例各一条走执行确认流。
3. 回滚面板对带恒真 WHERE 的 UPDATE 显示 PARTIAL + 新警示。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 误报疲劳 | 规则过敏感把正常语句也标危险，用户开始无脑点确认 | 子查询只提示不算危险；文案说事实；表驱动样例含真实业务形态反例 |
| JSqlParser 方言差异 | 反引号/转义/方言函数导致解析失败被 fail closed 误伤 | PARSE_FAILED 走既有确认流（不阻断），文案注明「无法解析，请人工确认」 |
| 与 MCP 边界混淆 | 探测器可能被误认为要接进 sqlv_explain | MCP 侧维持 SELECT 白名单不动；探测器仅 execute/rollback 两消费者，KDoc 写明 |

## 七、实施顺序

```
T-UW-01（探测器+表驱动测试）→ T-UW-02（执行链文案接线）
→ T-UW-03（回滚评级接入）→ T-UW-04（i18n+测试补齐）
→ T-UW-05（文档同步）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

**2026-09-15 实施完毕（基于 74f7842，任务相关定向套件全绿）**

- **T-UW-01**：`execute/UnboundedWriteDetector.kt` 交付——七规则（MISSING_WHERE / TAUTOLOGY / OR_TAUTOLOGY / LIKE_WILDCARD / NO_COLUMN_REF / SUBQUERY 提示型 / PARSE_FAILED fail closed），多语句取并集，INSERT 忽略；SUBQUERY 不算危险（dangerous 仅对其余类别）。实施中两处 JSqlParser 5.3 实证修正：① OR 链无论结合方向摊平 `collectOrSides`（首版把 OrExpression 整个当 side 传导致 OR 恒真恒漏报，表驱动测试当场抓获）；② 括号组在 5.3 解析为 `ParenthesedExpressionList`（单元素列表）而非 `Parenthesis`——`unwrapGroup` 双形态递归解包，`(a=1 AND 1=1)` 括号内 AND 组也摊平不漏报。
- **T-UW-02**：三处确认流共用新 `execute/ExecuteConfirmMessages.build`（纯函数可测）——LogToSqlPanel.executeStatement / SqlPreviewPanel.executeSql / SqlPreviewPanel.executeAdhocSql（计划只点名 LogToSqlPanel，三处同对话框同键，只升级一处会造成不一致，故全接；dmlConfirmed 豁免路径不动）。无危险发现文案逐字节不变；有发现 → 摘要 + 类别去重清单（每类一行）。零新弹窗形态/步骤。
- **T-UW-03 偏差定案（诚实原则）**：计划原文「PARTIAL 评级补两类触发面」——实际盘点后 UPDATE/DELETE 回滚路径恒为 UNABLE（无 before-image，P1 设计如此），不存在「COMPLETE/PARTIAL 降级」落点；两类警示（rollback.warn.tautology / rollback.warn.likeWildcard）落 `unableForDml` 的 warnings（快照 SELECT 复用原 WHERE，弱谓词=快照全表——正是需要警示的用户瞬间），不把 UNABLE 虚标成 PARTIAL。复用 analyzeWhere 单点判定。
- **T-UW-04**：i18n 双语 9+2 键（execute.confirm.unbounded.* 7 键 + summary + rollback.warn.* 2 键）；测试三件套——UnboundedWriteDetectorTest 22 例（每规则 ≥3 正例 + 业务形态反例 + 解析失败 + 多语句 + 注释干扰）、ExecuteConfirmMessagesTest 6 例（零变化红线/精确全等/类别去重/仅子查询不算危险）、RollbackSqlGeneratorTest 追加 4 例（两类警示正反例，既有 63 用例零回归）。
