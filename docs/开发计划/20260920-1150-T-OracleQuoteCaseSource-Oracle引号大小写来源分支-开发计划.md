# T-OracleQuoteCaseSource Oracle 系引号大小写来源分支 — 开发计划

- 日期：2026-09-20 11:50（**2026-09-20 12:xx 按决策定案与 Oracle 环境实测更新**）
- 任务编号：T-OracleQuoteCaseSource（第九轮报告 §9.4 **O-42**，**P2——缺陷定性升级后建议按 P1~P2 排**）
- 来源：第九轮报告 §8 DBX 增量清单 **#46**——DBX `8518a5b`（2026-09-19 09:47，6 文件 +101/−8）：Oracle 裸标识符折叠为大写，只有全大写名可不带引号，混合大小写必须双引号转义；DBX 把 `quoteSqlIdentifier` 的 oracle 分支正则从 `[A-Za-z]` 收紧为 `[A-Z]`
- 性质：**标识符引用正确性**（**有回归风险，见 §一 口径修正 2**）——不改 SQL 语义、不改保留字集合
- 实施分支：自 master 拉任务分支 t-oracle-quote-case-source
- 验证基线：master `f8c115d`（testShards 5383/0/0/6 + testDatabase 10/0/0/0 + agent 58/0/0/0）

---

## 决策定案（2026-09-20，用户认可本会话推荐）

| 决策点 | 定案 | 依据 |
|---|---|---|
| **D1** | **A——窄口径（只改目录来源路径）** | 用户明示「有（Oracle 环境）就做 A」；**环境已实测可用**（§1.6），且本轮实测把缺陷定性从「静默改语义」**升级为「ORA-00942 直接报错」**（§1.5），受益面比原估大 |
| **C（照搬 DBX 无条件收紧）** | **明确弃用**（保留在 §三 表内备查，不删除） | 会让实体来源的小写派生名被错误加引号，对以裸写创建的表**必然报错**（§一 口径修正 2） |

---

## 一、背景与依据（现状核实，含六处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「`CrudSqlGenerator`/回滚链的 Oracle 分支照 DBX 收紧」 | **回滚链不存在该分支（口径修正）**：`sql/rollback/RollbackSqlGenerator.kt` **零引号逻辑**、直接裸插值（`:232` `DELETE FROM $table WHERE …`、`:402` `INSERT INTO $table ($columnList) VALUES `、`:437`、`:496` `UPDATE $table SET $setPart WHERE …`）。受影响的是 **CRUD 生成链 + 其余 `quoteIfKeyword` 消费面** | 任务描述范围改写：不含回滚链 |
| 2 | 「按 DBX 规则收紧 Oracle 分支」 | **直接照搬会产生正确性回归（重大口径修正）**：DBX 的输入是**目录里的真实名字**，故「非全大写 → 必包」成立。我方 `CrudTableSpec` 有**两条来源、语义相反**：**目录来源**（`database/DbTableSpecAdapter.kt:44` `columnName = col.name`、`:52` `tableName = das.name`——数据库返回的真实大小写，DBX 规则**语义适用**）；**实体来源**（**实测落点在 `psi/EntityResolver.kt`（587 行）的 ColumnInfo 产出侧 `:103`/`:109`/`:151`/`:502`，四处均为 `NamingStrategy.toSnakeCase(...)`**——原计划误记为 `actions/GenerateCrudSqlFromEntityAction.kt`，该文件仅 149 行、无 `:151`/`:502`、全文 0 处 `NamingStrategy`/`toSnakeCase`；action 侧正确的只有 `:129` 的 `toCrudSpec()`，经 `:122` 的 `EntityResolver().resolve(clazz)` 取到派生名——即注解值或 camelCase→snake_case 的**小写派生名**）——这些名字**正是「不带引号创建」的名字**，Oracle 折叠成大写后命中，**恰恰是正确行为**；包成 `"biz_name"` 反而必然失败 | 落点**必须限定目录来源路径**；**不能无条件收紧** |
| 3 | 「达梦族 UPPER_FOLDING 是另一分支勿混」 | **我方无对应物可混**：`UPPER_FOLDING`/`upperFolding`/`foldToUpper` 全仓**零命中**（`uppercase`/`fold` 命中的全是 UI 自动折叠 `config/PluginSettings.kt:70-80` 等）。DM 与 Oracle 共用 `Dialect.kt:53` 同一条判定，差异仅在 `DM_RESERVED`（`sql/SqlKeywords.kt:208`）与包裹符号（`Dialect.kt:358`）。唯一相关认知在 `SqlKeywords.kt:203-204` 的**注释**（「裸用折叠大写后合法」），**未落成常量/分支** | 落地「折叠族」属**结构新增**，不是「收紧一个正则」 |
| 4 | 「`DbTypeQuoteHint` 相关」 | **同名不同义**：`generator/DbTypeQuoteHint.kt:10` 是「**DB 类型名 → 参数值**是否加字符串引号」，与**标识符引用**无关；`sql/ParamQuote.kt:15` 同理 | 立项文本不得把它算作落点 |
| 5 | 「顺带补 MERGE 多行样例单测（预期直接绿）」 | **成立**：`util/SqlStatementSplitter.kt` 全文**无 MERGE 特判**（批首合并常量只有 IF：`:40` `CONTROL_FLOW_BATCH_HEADS = setOf("IF")`，`:265-288` `mergeControlFlowBatches`）；Oracle 形态 MERGE 体内**无顶层 `;`** → 分号状态机天然整段产出；`SqlStatementSplitterTest.kt` 现有 **0 条 MERGE 用例** | 定位为**守卫用例**，不是修 bug |
| 6 | 「H2 `MODE=Oracle` 可用于验证」 | **不可用**：`sql/verify/DialectVerifySupport.kt:81`（**行号订正**：原计划误记为 `DialectH2VerificationTest.kt:81`，该处是 `private fun sweep`，全文只在 `:22` 的表头提过 MODE=Oracle）的 `MODE=Oracle` 是 H2 兼容模式，**折叠语义与真 Oracle 不等价** | 验证必须走真 Oracle（**环境已实测可用，见 §1.6**） |

### 1.1 我方引号判定现状

| file:line | 职责 |
|---|---|
| `sql/Dialect.kt:37` | `fun quoteIdentifier(identifier: String): String = identifier`——接口默认**不引用** |
| `sql/Dialect.kt:52-53` | `fun quoteIfKeyword(identifier) = if (identifier.lowercase() in reservedWords) quoteIdentifier(identifier) else identifier`——**全仓唯一的「按需引用」判定入口**；判定维度是**保留字**，不是大小写 |
| `Dialect.kt:188 / 211 / 237 / 255 / 278 / 301 / 328 / 358 / 382` | 九方言 `quoteIdentifier` 覆写（MySQL 反引号 / PG·Oracle·H2·SQLite·DM·KingBase 双引号 / SQLServer 方括号 / MariaDB 反引号）——**无条件包裹**，不含大小写或保留字判定 |
| `Dialect.kt:237` | Oracle 分支：`override fun quoteIdentifier(identifier: String): String = "\"$identifier\""` |
| `sql/SqlKeywords.kt:123` | `ORACLE_RESERVED` 保留字集合 |

**结论**：我方**没有**「Oracle 全大写才裸写」口径；当前按「**非保留字一律裸写**」放行混合大小写。

### 1.2 消费方全清单（`quoteIfKeyword` 命中 65 处 / 10 文件）

| file:line | 消费面 |
|---|---|
| `generator/CrudSqlGenerator.kt:146`（`quoted()`）→ `:53/60/87/88/118/134/148/152` | **CRUD 四件套生成器**（SELECT/UPDATE/DELETE/逻辑删除） |
| `actions/GenerateCrudSqlFromDbTableAction.kt:46`、`actions/GenerateCrudSqlFromEntityAction.kt:57`、`mcp/SqlVisualizerCrudSqlTool.kt:90` | CRUD 生成器的三个入口（IDE 两动作 + MCP `sqlv_crud_sql`） |
| `converter/jpa/JpaCrudOperationConverter.kt`（25 处，336–711） | JPA CRUD 转换链 |
| `psi/BatchOperationConverter.kt`（16 处） | 批量转换链 |
| `model/BatchModel.kt:106-122` | 批量 UPSERT/INSERT/UPDATE 渲染 |
| `model/ColumnRef.kt:32-33` | 解析期 raw 拼接 + SqlGenerator 共用 |
| `converter/jpa/JpaQueryEnhancer.kt:450-451` | JOIN ON 渲染 |
| `sql/SqlGenerator.kt:493`（`renderTableName`）→ `:150/229/276/279/313/363` | **日志转 SQL 的 FROM/UPDATE/DELETE/JOIN 表名** |
| `converter/jpa/DerivedQueryConverter.kt:398` | 仅注释引用 |
| `Dialect.kt` 内部 `:164/171/172/283/284` | upsert 子句 + H2 `MERGE INTO` 整语句 |

**不使用引号判定的链（不受影响）**：`sql/rollback/RollbackSqlGenerator.kt`（零引号）、`sql/JoinDiagramGenerator.kt` 与 `ui/diagram/nativefw/**`、`ui/highlight/SqlCompletion*.kt` 与 `ui/completion/**`、`mcp/**` 工具层（仅经 `CrudSqlGenerator`）。

### 1.3 两条来源路径（决定 D1 的核心事实）

| 来源 | 构造点 | 名字语义 | DBX 规则是否适用 |
|---|---|---|---|
| **目录来源** | `database/DbTableSpecAdapter.kt:44`、`:52` | 数据库返回的**真实大小写** | **适用** |
| **实体来源** | **`psi/EntityResolver.kt:103`/`:109`/`:151`/`:502`**（四处 `NamingStrategy.toSnakeCase`；经 `:122` `EntityResolver().resolve(clazz)` → action `:129` `toCrudSpec()` 适配进 `CrudTableSpec`）——**行号订正见 §一 #2 与 §九** | **小写派生名** | **不适用，收紧即回归** |

`generator/CrudTableSpec.kt:41` 当前**无承载「名字大小写来源」的字段**。

### 1.4 测试影响面

| 类 | 是否会被改动弄红 |
|---|---|
| `sql/DialectTest.kt:91-103`、`sql/SqlKeywordsTest.kt:37-52`/`:54-64`/`:66-80` | 否（`quoteIdentifier` 无条件包裹、保留字命中路径不变；`:54-64` 只断言 MySQL/PG 集合） |
| `generator/CrudSqlGeneratorTest.kt` | **可能红**——若含「Oracle 小写标识符裸写」断言；**须逐条核对该断言属目录来源（则该断言本身错、要改）还是实体来源（则正确、不能改）** |
| `sql/rollback/RollbackSqlGeneratorTest.kt` | 否（该文件零 `OracleDialect` 引用） |
| `sql/verify/DialectDocGoldenTest.kt` / `DialectRenderDumpTest.kt:44-60` / `DialectVerifySupport.kt:238/293-294/326-327` / `DialectVerifySupport.kt:81`（**行号订正**，原记为 `DialectH2VerificationTest.kt:81`） | 否（用无条件包裹函数 / 不判大小写） |

引用 `OracleDialect` 的测试文件共 **9 个**：`config/ProjectPluginSettingsTest.kt`、`model/BatchModelTest.kt`、`sql/DialectExplainTest.kt`、`sql/DialectTest.kt`、`sql/SqlKeywordsTest.kt`、`sql/verify/DialectDockerVerificationTest.kt`、`sql/verify/DialectRenderDumpTest.kt`、`sql/verify/DialectVerifySupport.kt`、`ui/SqlPreviewServiceProjectSettingsTest.kt`。

### 1.5 真 Oracle 实测（**本轮新增，缺陷定性升级**）

连接：DBX MCP → `Oracle Free 23 / FREEPDB1`（`dbx-test-host:20095`，connection_id `311778f3-3f03-4e13-b561-74ca36873af1`）。四组查询的双向证据：

| # | 查询 | 结果 | 证明 |
|---|---|---|---|
| 1 | `SELECT dummy FROM dual` | 成功（1 行） | 裸写小写 `dual` **折叠为 `DUAL`** 后命中字典对象 |
| 2 | `SELECT dummy FROM "dual"` | **ORA-00942: table or view "DBX_TEST"."dual" does not exist** | 引号**保留原样、大小写敏感** |
| 3 | `SELECT COUNT(*) FROM dbx_test.issue_new` | **ORA-00942: "DBX_TEST"."ISSUE_NEW" does not exist** | 裸写小写折叠为大写后，**找不到以引号创建的小写表** |
| 4 | `SELECT COUNT(*) FROM dbx_test."issue_new"` | 成功（100 行） | 引号引用命中小写表 |

另：`SELECT 1 AS Abc, 2 AS "Abc" FROM dual` → 列标签分别为 `ABC` 与 `Abc`（同一语义的列别名侧印证）。

**口径修正 5（缺陷定性升级）**：原报告写的是「混合大小写列名裸写会被 Oracle **静默改语义**」。实测表明更严重的一档存在——**字典里含小写字母的对象名（即以引号创建的 `issue_new` 这类表）被裸写引用时，直接抛 `ORA-00942`，属功能失效而非静默错误**。而目录来源（`DbTableSpecAdapter`）返回的正是字典里的真实名字，**当前会被裸写**（`quoteIfKeyword` 只在保留字命中时加引号，`issue_new` 非保留字）→ 即：**从目录生成 CRUD 到一个带引号创建的小写表，当前会产出必然报错的 SQL**。这使本任务的受益面从「窄 + 静默」升级为「窄 + 直接报错」，值得按 **P1~P2** 排。

**口径修正 6（判据形态）**：正确的判据不是 DBX 的「非全大写 → 必包」在字面上照抄，而是**「字典返回的名字若含小写字母（非全大写）→ 必须加引号；全大写 → 裸写」**——对**目录来源**适用；实体来源走既有逻辑不动。两者恰好同构，但**必须按来源分流**才能避免回归。

### 1.6 环境可用性（D1 定案的依据）

| 连接 | 状态 |
|---|---|
| **Oracle Free 23 / FREEPDB1（20101… 实为 20095）** | ✅ **可用**（上表四组查询实测） |
| Oracle 19c / ORCLCDB（`13174cc5-…`） | ❌ 服务名不通（`ORA-12514`；SID 回退 `ORA-12505`） |
| Oracle XE 11g / XE（`7a283e84-…`）、XE 18c / XEPDB1（`055a17f8-…`）、XE 21c / XEPDB1（`2045d348-…`） | 未逐个试（Free 23 已满足验证需求） |

→ 本计划原「无真 Oracle 环境则退 D1=B」的前提**已消除**；验证走 DBX MCP 的 Free 23 连接。

## 二、目标与非目标

**目标**：让**目录来源**的 Oracle 标识符按「含小写字母即加引号、全大写才裸写」引用，消除「带引号创建的小写对象名被裸写 → ORA-00942」这一功能失效；同时**不改变实体来源路径的既有行为**。

**非目标**：不改回滚链（零引号逻辑，不在范围）；不改 `DbTypeQuoteHint`/`ParamQuote`（参数值引号）；不改保留字集合与 `quoteIdentifier` 的无条件包裹语义；不新建「折叠族」结构除非窄口径不可行；不追 DBX 的 `dataGridConditionCompletion` 等无对应面；**不动日志转 SQL 路径除非其名字来源可判定**（见待验证前提 2）。

## 三、方案

### D1 选项表（A 已定案；C 弃用；B 保留备查）

| 选项 | 内容 | 状态 |
|---|---|---|
| **A（窄口径）** | 只对**目录来源**路径收紧：新增「名字大小写来源」标记，按来源分流判定 | **✅ 定案** |
| B（不做） | 只落 MERGE 守卫用例与核实结论 | 备查（环境已可用，不再是退路） |
| C（照搬 DBX 无条件收紧） | 把 Oracle 分支改成「非全大写必包」 | **❌ 弃用**（会破坏实体来源路径，§一 口径修正 2） |

**T-OQCS-01（大小写来源标记）**：给 `generator/CrudTableSpec.kt`（`:41` 附近）增加「名字来源」承载（新增字段，或按调用点分派——见待验证前提 1）；`database/DbTableSpecAdapter.kt:44`/`:52` 标为**目录来源**，**实体来源侧实测落点为 `psi/EntityResolver.kt:103`/`:109`/`:151`/`:502` 的 `NamingStrategy.toSnakeCase` 派生（见 §一 #2 行号订正），实际承载形态为「默认值 = 实体来源」**——实体路径零改动即天然落 ENTITY。

**T-OQCS-02（引用判定分支）**：`sql/Dialect.kt` 的按需引用入口（`:52-53`）增加来源形参或新增重载 `quoteIfNeeded(identifier, caseSource)`：
- **目录来源**：Oracle 族按「**含小写字母即加引号**」（判据 = 名字非全大写；同时保留保留字命中即包的既有行为）；
- **实体来源**：走既有 `quoteIfKeyword` 逻辑（**逐字不变**）。

**T-OQCS-03（消费面接线）**：`generator/CrudSqlGenerator.kt:146` 的 `quoted()` 传入来源标记；JPA CRUD（`converter/jpa/JpaCrudOperationConverter.kt`）、批量转换（`psi/BatchOperationConverter.kt`、`model/BatchModel.kt`）按各自名字来源传入；**日志转 SQL**（`sql/SqlGenerator.kt:493`）的名字来自运行期 SQL 文本，属第三类（**原样文本**），**默认维持现状不动**（见待验证前提 2）。

**T-OQCS-04（MERGE 守卫用例）**：`SqlStatementSplitterTest.kt` 新增 1 条**多行 MERGE** 样例（`MERGE INTO … USING … ON (…) WHEN MATCHED THEN UPDATE SET … WHEN NOT MATCHED THEN INSERT (…) VALUES (…);`），断言整段产出为**单条**语句——**预期直接绿**，定位为守卫。

**T-OQCS-05（测试与文档）**：`generator/CrudSqlGeneratorTest.kt` 新增两组 Oracle 用例：
- **目录来源正向**：字典含小写字母的名字 → 加引号；全大写名字 → 裸写；
- **实体来源防回归**（比正向更重要）：小写派生名（`NamingStrategy.toSnakeCase` 产物）在 Oracle 下**仍裸写**。
文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第九轮报告 §8 #46 落地备注。

## 四、验收标准

1. **目录来源**：Oracle 下字典含小写字母的标识符被双引号包裹；全大写标识符裸写（新增用例）。
2. **实体来源不变（防回归硬要求）**：小写派生名在 Oracle 下**仍然裸写**（新增用例钉住——这是 C 方案否决依据）。
3. 其他方言（PG / MySQL / DM / KingBase / SQLServer / H2 / SQLite / MariaDB）产物**逐字节不变**（既有测试全绿）。
4. MERGE 多行样例切分为单条语句（守卫用例，预期直接绿）。
5. **真 Oracle 端到端**：在 Free 23 连接上，对 `dbx_test."issue_new"` 这类带引号创建的小写对象，目录来源生成的 CRUD **能实际执行成功**（改前会 ORA-00942）——这是本任务的**决定性命据**。
6. 既有测试面全绿（`DialectTest`、`SqlKeywordsTest`、`RollbackSqlGeneratorTest`、`DialectRenderDumpTest`、`DialectVerifySupport`；`CrudSqlGeneratorTest` 按新增用例调整，**须先逐条核对既有 Oracle 断言的来源归属**）；全量 testShards + testDatabase + agent 绿。

## 五、验证清单（**经 DBX MCP 执行，不再是「留人工」**）

连接：`Oracle Free 23 / FREEPDB1`（connection_id `311778f3-3f03-4e13-b561-74ca36873af1`）。**测试库为共享环境，纪律：不新建/不删除对象，只用既有对象做只读验证**（`issue_new` 已是带引号创建的小写表，正好是天然夹具）。

| # | 场景 | 判据 |
|---|---|---|
| 1 | 目录来源 → 字典含小写字母的名字（如 `dbx_test.issue_new`） | 生成的 SQL 中该标识符**带双引号**；**实际执行返回 100 行**（改前 ORA-00942） |
| 2 | 目录来源 → 全大写名字 | 生成的 SQL 中**裸写**；执行成功 |
| 3 | 实体来源 → 小写派生名（`@TableName` 小写注解） | 生成的 SQL 中**仍裸写**（防回归）；对以裸写创建的表执行成功 |
| 4 | 保留字名（如 `number`/`level`） | 仍按既有保留字规则加引号（`ORACLE_RESERVED` 路径不变） |
| 5 | 其他方言回归 | PG/MySQL/DM/KingBase 产物与改前逐字节一致（既有测试覆盖，无需真库） |
| 6 | 裸写/引号语义对照（**实施前先做**） | 复现 §1.5 的四组读数，作为「判据成立」的基线证据留档 |

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **实体来源回归**（最大风险） | 无条件收紧会让「以裸写创建的小写派生名」被加引号 → 精确匹配失败 | C 方案已弃用；验收 2 用「实体来源仍裸写」用例钉住；T-OQCS-03 对每条消费面单独确认名字来源 |
| 共享测试库被改 | 验证时若建/删对象会影响他人（该库为 DBX 开发测试共享） | §五 纪律：只读验证，用既有对象；**禁止建删对象** |
| 日志转 SQL 来源不明 | `sql/SqlGenerator.kt:493` 的名字来自运行期 SQL 文本（第三类） | 默认**维持现状不动**（宁可不改也不猜）；若日后要改须先定来源口径 |
| 既有断言被误改 | `CrudSqlGeneratorTest` 可能含 Oracle 小写裸写断言，属目录来源则该断言本身错（要改）、属实体来源则正确（不能改） | **逐条核对构造路径**并产出核对表后再改 |
| 消费面 65 处波及 | 改 `Dialect.kt:52-53` 会波及 10 文件 65 处 | 采用「**新增重载 + 逐点接线**」而非「改既有函数语义」，未接线点维持旧行为 |
| DM/DB2 同族未覆盖 | 达梦族（大写折叠）是否同规则未核实 | 本计划只覆盖 Oracle；扩展须先建族标记（结构新增，另议） |

## 七、实施顺序

```
§五-6（复现基线读数，确认判据成立）→ T-OQCS-01（来源标记）→ T-OQCS-02（判定分支）
  → T-OQCS-03（消费面逐点接线 + 逐条核对表）→ T-OQCS-05（含防回归用例）
  → T-OQCS-04（MERGE 守卫用例，与主线无关可并行先落）
  → §五 1~4（真 Oracle 端到端验证）→ 收口
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| D1 定案 | 本次更新 | A 窄口径；C 弃用 |
| 环境实测 | 本次更新 | Free 23 可用；19c 服务名不通；§1.5 四组读数已留档 |
| 行号订正 | 见本次提交 | §一 #2、§一 #6、§1.3、§1.4、T-OQCS-01 六处引用按实测改正（详见 §九） |
| T-OQCS-01 | 见本次提交 | 承载形态定案：**新增字段 + 默认值 = 实体来源**。`CrudTableSpec.identifierCaseSource: IdentifierCaseSource = ENTITY`（新增第 6 个构造参数）、`BatchModel.identifierCaseSource`（同默认）。实体来源零改动即天然落 ENTITY——不是「按调用点分派」，而是「默认即实体、目录侧单点显式标记」 |
| T-OQCS-02 | 见本次提交 | 定案为**新增方法** `Dialect.quoteIfNeeded(identifier, caseSource)`，接口默认实现委托 `quoteIfKeyword`（未接线点与其余 8 方言**逐字不变**）；**唯一覆写者 = `OracleDialect`**，判据 `caseSource == CATALOG && identifier.any { it.isLowerCase() }` → `quoteIdentifier`，否则落回 `quoteIfKeyword`（**保留字命中即包的既有行为完整保留**） |
| T-OQCS-03 | 见本次提交 | 接线点**只有 1 处**（`DbTableSpecAdapter.toSpec` 标 CATALOG）；逐条核对表与未接线点清单见 §九 |
| T-OQCS-04 | 见本次提交 | MERGE 守卫用例实测**直接绿**（`Oracle形态多行MERGE整段一条` + `MERGE后接独立语句不误并`），全文无 MERGE 特判的结论成立 |
| T-OQCS-05 | 见本次提交 | `CrudSqlGeneratorTest` **11 → 17 用例（+6）**（**计数订正**：本行与提交信息原写「10 → 17（+7）」，独立复核实测改前 **11** 条、新增 **6** 条）：目录正向（含小写→加引号 / 全大写→裸写 / 保留字路径保持 / Oracle 是唯一产物变化方言）+ **实体来源防回归** + **其他 8 方言双来源逐字节一致**；`CrudSqlGeneratorTest` 既有 **11** 条**零改动**（无 Oracle 小写裸写断言——待验证前提 4 的核对结论）。**注**：提交信息 `d3d0a9f` 内的旧读数无法就地更正（不改写历史），以本行与 §八 待验证前提 4 的实测读数为准 |
| §五 端到端 | 见本次提交 | 改前/改后读数见 §十（**决定性证据**） |

### 九、消费面逐点核对表（T-OQCS-03 回填）

`quoteIfKeyword` 在 `src/main` 的真实调用点（排除 `Dialect.kt`/`SqlKeywords.kt` 的 KDoc 提及）：

| 来源类别 | 落点 | 处置 |
|---|---|---|
| **目录来源** | `database/DbTableSpecAdapter.kt:52`（`tableName = das.name`、`columnName = col.name`） | **接线**：标 `IdentifierCaseSource.CATALOG`（唯一接线点） |
| **实体来源** | `converter/jpa/JpaCrudOperationConverter.kt`（25 处，参数 `table: TableInfo` ← EntityResolver）、`psi/BatchOperationConverter.kt`（16 处，同）、`converter/jpa/JpaQueryEnhancer.kt:450-451`（`association.targetTable`/`joinColumn`/`referencedColumnName` ← JPA 注解值）、`actions/GenerateCrudSqlFromEntityAction.kt:129` `toCrudSpec()`、`mcp/SqlVisualizerCrudSqlTool.kt`、`model/BatchModel.kt`（默认值） | **不接线**（走默认 ENTITY，逐字不变） |
| **第三类：原样文本/解析期** | `sql/SqlGenerator.kt:493` `renderTableName`（运行期 SQL 文本）、`model/ColumnRef.kt:33`（解析期拼接）、`converter/jpa/DerivedQueryConverter.kt:398`（仅注释） | **维持现状不动**（计划 §六 定案：宁可不改也不猜） |
| **方言内部辅助** | `sql/Dialect.kt:205/212-213`（upsert 后缀）、`:343-344`（H2 MERGE 整语句） | **不接线**；`BatchModel` 已就地注释说明「当前无行为差异 + 日后扩展须一并接线」 |

未接线点之所以安全：`quoteIfNeeded` 的接口默认实现**就是** `quoteIfKeyword`，且只有 `OracleDialect` 覆写；Oracle 的两条 upsert 路径均返回 null（不进入渲染）。

### 十、真库端到端读数（决定性证据回填）

连接 `Oracle Free 23 / FREEPDB1`（connection_id `311778f3-3f03-4e13-b561-74ca36873af1`），只读验证（未建/删任何对象）。

| # | 读向 | 语句 | 改前（裸写） | 改后（目录来源引用） |
|---|---|---|---|---|
| 1 | 目录来源小写对象 | `SELECT COUNT(*) FROM dbx_test.issue_new` vs `dbx_test."issue_new"` | **ORA-00942** `"DBX_TEST"."ISSUE_NEW" does not exist` | **100 行** |
| 2 | 目录来源生成 SQL 逐字执行 | 插件对 50 列（`id` + `field_01..field_49`）目录 spec 生成的 `SELECT_ALL` 全文 | 同 #1 裸写形态 → ORA-00942 | **成功返回真实行**（`FETCH FIRST 3 ROWS ONLY` 仅用于收窄输出） |
| 3 | DML 标识符解析（`DBMS_SQL.PARSE` 只解析不执行，**零数据改动**） | `DELETE FROM issue_new WHERE id = :b1` vs `DELETE FROM "issue_new" WHERE "id" = :b1` | **ORA-00942** | **解析通过**；UPDATE / INSERT 同（三条一次调用全通过） |
| 4 | **实体来源反向对照**（C 方案否决依据） | `SELECT COUNT(*) FROM user_tables`（裸写创建 → 字典存 `USER_TABLES`）vs `"user_tables"` | 裸写**成功**（3 行） | 加引号 **ORA-00942** `"DBX_TEST"."user_tables" does not exist` |

**判据结论**：同一串小写字符在两种来源下正确形态**相反**（#1/#2/#3 vs #4），按来源分流是唯一正确解；C 方案（无条件收紧）会在 #4 形态上必然报错。

**待验证前提 5 的实测结论（目录来源大小写忠实性）**：`SELECT table_name FROM all_tables WHERE owner='DBX_TEST'` 对以引号创建的表返回**小写 `issue_new`**（`LENGTH=9`，与输入逐字一致），列侧 `all_tab_columns.column_name` 同样返回小写 `field_01..field_49`——**字典未做大小写归一，前提成立**。`DbTableSpecAdapter` 的 `das.name`/`col.name` 是该值的直通（无归一代码），故 DAS 层忠实。


**待验证前提（实施者先证伪再动手）**：

1. 「名字来源可在 `CrudTableSpec` 承载」——**已证实**：新增第 6 个构造参数 `identifierCaseSource: IdentifierCaseSource = ENTITY`；默认值取**实体来源**使全部既有构造点零改动，目录侧仅 `DbTableSpecAdapter` 一处显式标 CATALOG（比「按调用点分派」少 10 处改动面）。
2. **日志转 SQL 路径（`sql/SqlGenerator.kt:493`）的名字来源未判定**——既非目录也非实体；**默认不动**（**已执行**：未接线，`SqlGeneratorTest` 21 条全绿）。
3. ~~「无真 Oracle 环境」~~ → **已解决**：Free 23 连接实测可用（§1.6）。
4. 「`CrudSqlGeneratorTest` 里哪些 Oracle 断言属目录来源」——**已逐条核对，结论：一条都没有**。该文件改前 **11** 条用例中零处引用 `OracleDialect`（只有 `MySqlDialect` 与默认方言断言），故**零改动**，新增 **6** 条独立用例（11 → 17；§八 T-OQCS-05）。**计数订正**：本行原写「改前 10 条 / 新增 7 条」，独立复核按 `git show d3d0a9f^:<文件> | grep -c @Test` 实测改前 11 条、改后 17 条，即新增 6 条（`+7` 系把「既有 10 条」这一旧读数一并算错所致）。
5. 「目录来源返回的名字是否忠实保留字典大小写」——**已真库实测证实成立**，读数见 §十 末段（`issue_new` 小写、长度 9，列名同样小写；字典未归一）。
6. 「Oracle Free 23 的折叠语义可代表 11g/19c」——**仍未验证**：本任务所有真库读数只出自 Free 23 单连接，未在 11g/18c/19c/21c 上复测（在册连接未逐个试）。跨版本差异风险登记为未验证面。

