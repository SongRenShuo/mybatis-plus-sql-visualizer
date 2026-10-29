package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.util.SqlStatementSplitter

/**
 * 数据库方言 SPI（T-M5-03/F-14）。
 *
 * 方言差异点（M5 实现范围）：
 *  - 分页子句：[renderLimit]（MySQL 用 LIMIT，PostgreSQL 用 LIMIT+OFFSET，Oracle 用 FETCH）
 *  - 标识符引用：[quoteIdentifier]（MySQL 反引号，PG/Oracle 双引号；默认不引用，按需启用）
 *  - 关键字判定：[reservedWords]（T-M23.4 按方言保留字集合，[quoteIfKeyword] 只判本方言）
 *  - upsert 冲突子句：[renderUpsert]（T-M43 方言差异点第 4 项；null → 无「INSERT+后缀」原生
 *    upsert，渲染降级纯 INSERT）与 [renderUpsertStatement]（P-立项-05：整语句形态，如 H2 MERGE..KEY）
 *  - identity 包装：[renderInsertWrapper]（O-52；null → 本方言无需包装，默认）
 *
 * 新增方言：实现本接口并注册进 [DialectRegistry]（六件套：分页形态、引用符号、保留字集合、
 * 折叠方向族 [identifierFolding]、upsert 形态、测试）。
 * 验证基线（P-立项-05）：各方言输出形态经 H2 兼容模式 / SQLite 真实引擎 / 官方文档验证，
 * 详见 docs/开发计划/20260828-2000-P-立项-05-方言输出真实验证-报告.md。
 */
interface Dialect {

    /** 方言名称（与 Settings 下拉项一致）。 */
    val name: String

    /**
     * 渲染完整分页子句（含关键字，如 `LIMIT 10` / `LIMIT 10 OFFSET 5`）。
     *
     * @param limit 分页模型
     * @return 追加到 SQL 末尾的分页子句
     */
    fun renderLimit(limit: LimitClause): String

    /**
     * 标识符引用（表名/列名包裹）。
     * 默认原样返回（不引用）；需要时子类覆写（如 MySQL 反引号）。
     */
    fun quoteIdentifier(identifier: String): String = identifier

    /**
     * 本方言**可接受的标识符引号字符**集合（T-DialectQuoteWarn，`dialect_quote_mismatch` 判定用）。
     *
     * 存**开引号字符**：反引号 `` ` `` / 双引号 `"` / 方括号 `[`（方括号的闭引号 `]` 不单列）。
     * 空集 = **不做引号方言判定**（基类兜底：无方言上下文时宁可不报也不误报）。
     *
     * 各方言按**实际宽容度**给集合，不按「品牌印象」一刀切：
     *  - MySQL / MariaDB / OceanBase：仅反引号（双引号默认是字符串字面量，`ANSI_QUOTES` 下才成标识符）；
     *  - PostgreSQL / Oracle / DM / KingBase / openGauss：仅双引号；
     *  - SQL Server：方括号 + 双引号（`QUOTED_IDENTIFIER` **默认 ON**，双引号合法，判错即误报）；
     *  - SQLite / H2：双引号 + 反引号 + 方括号（两者都收多种引号）。
     *
     * **不要**用 `quoteIdentifier("x").first()` 反推——基类 `quoteIdentifier` 原样返回，
     * 反推会得到字母 `x`（而非引号）。本属性是引号方言判定的唯一事实源。
     */
    val identifierQuotes: Set<Char> get() = emptySet()

    /**
     * 本方言保留字集合（T-M23.4，小写；[quoteIfKeyword] 判定用）——
     * 按方言判定：MySQL 只判 MySQL 保留字（如 `number` 是 Oracle 保留字但 MySQL 合法：MySQL 不包）。
     */
    val reservedWords: Set<String>

    /**
     * 标识符**按需引用**（T-M23.4）：命中**本方言**保留字（大小写不敏感）→ [quoteIdentifier]
     * 包裹（符号按方言：MySQL 反引号 / PG 双引号 / SQLServer 方括号）；否则原样返回。
     *
     * 语义：引用幂等安全（被引用的词在本方言是保留字，包裹后合法）——宁可多包、不漏包；
     * 仅渲染层调用，模型层列名保持原始值（参数对照/补全/勾选匹配不受影响）。
     */
    fun quoteIfKeyword(identifier: String): String =
        if (identifier.lowercase() in reservedWords) quoteIdentifier(identifier) else identifier

    /**
     * 标识符**按名字大小写来源**引用（O-42 / T-OracleQuoteCaseSource；O-51 / T-IdentifierFoldingFamily
     * 把「单方言特判」升级为按 [identifierFolding] 折叠方向族分流）。
     *
     * 与 [quoteIfKeyword] 的差别：本方法额外知道该名字**从哪来**——同一串字符在两种来源下的
     * 「正确引用形态」可以相反。默认实现按 [identifierFolding] 分流：NONE 族逐字节等价于
     * [quoteIfKeyword]（故 MySQL / MariaDB / SQLite / SQL Server / DM 产物不变），
     * UPPER / LOWER 两族只在目录来源下加判定。
     *
     * 分流口径（条件**显式含** [IdentifierCaseSource.CATALOG]）：
     *  - CATALOG + [IdentifierFolding.UPPER]：名字是**字典里的真实名字**（自省元数据返回，未归一）。
     *    Oracle 裸标识符折叠为大写，故「含小写字母」的名字**必须加引号**才能命中；全大写才可裸写；
     *  - CATALOG + [IdentifierFolding.LOWER]：裸标识符折叠为小写，故「含大写字母」的名字**必须加引号**
     *    （以引号创建的大写名裸写后被折叠为小写 → 查不到，方向与 UPPER 族相反）；
     *  - CATALOG + [IdentifierFolding.NONE]：不折叠（或大小写不敏感匹配）→ 按名判定无意义，落 [quoteIfKeyword]；
     *  - [IdentifierCaseSource.ENTITY]：名字是**小写派生名**（`@TableName`/`@TableField` 注解值，
     *    或 camelCase → snake_case 的 [cn.song.mybatisplus.visualizer.util.NamingStrategy.toSnakeCase] 产物）
     *    ——这些正是「不带引号创建」的名字，折叠后恰好命中，**加引号反而必然失败**。
     *    故实体来源**不论族**都走既有 [quoteIfKeyword] 逐字不变（这是 C 方案「无条件收紧」被否决的依据；
     *    族判定只允许写在 CATALOG 分支里——写进默认路径会连带污染 ENTITY）。
     *
     * 未接线点（[IdentifierCaseSource.ENTITY] 默认）维持旧行为——新增重载 + 逐点接线，不改既有函数语义。
     */
    fun quoteIfNeeded(identifier: String, caseSource: IdentifierCaseSource): String =
        if (caseSource == IdentifierCaseSource.CATALOG) quoteCatalogName(identifier) else quoteIfKeyword(identifier)

    /**
     * 标识符**折叠方向族**（O-51 / T-IdentifierFoldingFamily）：本方言把裸标识符折叠到哪个方向。
     *
     * [quoteIfNeeded] 的目录来源分支按本属性分流——同一串字典名在不同族下的正确引用形态相反
     * （判据落点见 [quoteIfNeeded] 的 KDoc）：
     *  - [IdentifierFolding.UPPER]（Oracle 真库实测：`FROM dual` 命中 `DUAL`、`FROM "dual"` → ORA-00942）；
     *  - [IdentifierFolding.LOWER]（PG / KingBase 真机实测：`FROM INFORMATION_SCHEMA.TABLES` 折为小写命中、
     *    `FROM information_schema."TABLES"` → relation does not exist）；
     *  - [IdentifierFolding.NONE]（不折叠或大小写不敏感匹配 → 按名判定无意义）。
     *
     * 默认 [IdentifierFolding.NONE]（保持既有行为）。维度是**折叠方向**而非数据库品牌——DM8 实测大小写
     * 不敏感（`sys.sysobjects` / `sys."sysobjects"` / `sys."SYSOBJECTS"` 三形态均 1029 行）→ NONE，
     * 与 DBX 把 `{dameng, db2}` 归 `UPPER_FOLDING` 的口径分歧按我方实测处理（计划 §八 待验证前提 1）。
     *
     * H2 2.3.232 引擎实测裸写折为大写（与 Oracle 同族）→ **UPPER**（[H2Dialect] 覆写）；
     * SQLite / SQL Server 未实测（SQL Server 大小写取决于 collation），维持 NONE。
     */
    val identifierFolding: IdentifierFolding get() = IdentifierFolding.NONE

    /**
     * UPSERT 冲突子句（T-M43：设置里出现 = 承诺可用）。
     *
     * 返回 null → 本方言无「INSERT + 后缀」形态的原生 upsert 语法（Oracle/SQL Server/DM 的
     * 原生形态是复杂 MERGE；P-立项-05 修正：H2 的 `ON CONFLICT` 在其任何模式下都不可用），
     * 渲染方降级为纯 INSERT（保证 SQL 在本方言可执行）；非 null → 追加到 INSERT 语句末尾的冲突子句。
     *
     * 若本方言的原生 upsert 是**整语句形态**（如 H2 的 MERGE..KEY），则实现
     * [renderUpsertStatement] 并保持本方法返回 null。
     *
     * @param conflictKey    冲突键列名（原始值，实现内按需引用；MySQL 形态无冲突键列表，仍须传入用于勾选判定）
     * @param updateColumns  冲突后更新列名（原始值，不含冲突键；实现内按需引用）
     * @param caseSource     列名的大小写来源（O-51）：目录来源时按 [identifierFolding] 族引用，与 INSERT 基座
     *                       （[cn.song.mybatisplus.visualizer.model.BatchModel.renderSql]）同一判定——否则会产出
     *                       「基座加引号、后缀裸写」的半包形态（PG 族含大写目录名下后者被折叠为小写 → 列不存在）
     */
    fun renderUpsert(
        conflictKey: String,
        updateColumns: List<String>,
        caseSource: IdentifierCaseSource = IdentifierCaseSource.ENTITY,
    ): String? = null

    /**
     * UPSERT **整语句**形态（P-立项-05）：方言原生 upsert 非「INSERT + 后缀」结构时使用——
     * 如 H2 的 `MERGE INTO t (cols) KEY (conflict) VALUES (...)`（H2 2.3.232 引擎实证：
     * `ON CONFLICT` 后缀在任何 H2 模式均语法错误，`DO UPDATE` 仅存在于真 PostgreSQL）。
     *
     * 返回非 null → 渲染方（BatchModel UPSERT 形态）直接使用该整语句，不再渲染 INSERT 基座；
     * 默认返回 null → 走 [renderUpsert] 后缀形态（或其 null 降级纯 INSERT）。
     *
     * @param tableName   表名（原始值，实现内按需引用）
     * @param columns     全部列名（原始值，含冲突键，与 VALUES 的 `?` 顺序一致；实现内按需引用）
     * @param conflictKey 冲突键列名（原始值；必在 [columns] 内）
     * @param caseSource  列名的大小写来源（O-51）：语义同 [renderUpsert] 的同名参数
     */
    fun renderUpsertStatement(
        tableName: String,
        columns: List<String>,
        conflictKey: String,
        caseSource: IdentifierCaseSource = IdentifierCaseSource.ENTITY,
    ): String? = null

    /**
     * INSERT 的 **identity 包装**（O-52 / T-IdentityInsertWrap）：显式把值写进自增列所需的
     * 前置/后置语句（SQL Server 为 `SET IDENTITY_INSERT <t> ON/OFF`）。
     *
     * 返回 null → **本方言无需包装**（默认）——调用方按原样渲染 INSERT，
     * 故不含自增列的 INSERT 与全部非包装方言的产物**逐字节不变**。
     * 非 null → 调用方以返回值整体替换 INSERT 文本（返回值内已含 [sql] 原文）。
     *
     * **判定不在这里**：「本次 INSERT 的列清单是否含自增列」由调用方判定（自增标记只有生成侧
     * 持有：CRUD 侧 [cn.song.mybatisplus.visualizer.generator.CrudColumn.autoGenerated]、回滚侧
     * 快照登记的 identity 列），本方法只回答「本方言的包装形态长什么样」。
     *
     * 表名走 [quoteIfNeeded]（**与 INSERT 基座同一函数、同一 [caseSource]**）——包装与 INSERT
     * 里的引用形态因此一致（SQL Server 上两者是不同的语法位，引用形态不一致会指向不同对象）。
     *
     * @param tableName  表名（原始值，实现内按 [quoteIfNeeded] 引用）
     * @param sql        已渲染的 INSERT 文本（不含结尾分号）
     * @param caseSource 表名的大小写来源（O-42/O-51）：语义同 [renderUpsert] 的同名参数
     */
    fun renderInsertWrapper(
        tableName: String,
        sql: String,
        caseSource: IdentifierCaseSource = IdentifierCaseSource.ENTITY,
    ): String? = null

    /**
     * EXPLAIN 执行计划语句（M37.2 / P-立项-06 N-1）：把查询包装为本方言的 EXPLAIN 形态。
     *
     * **单一事实源已迁移到 [ExplainSupport] 表**（T-ExplainChainHardening T-ECH-02 / O-48）：
     * 本方法默认实现读表，各方言**不再逐处覆写**——此前「没覆写」同时承担「不支持」与「忘了写」
     * 两种含义，且新增方言时无从判定该不该有 EXPLAIN。支持面、输出格式与真机实证出处见
     * [ExplainSupport.entries]；渲染产物逐字节不变（模板占位符替换即旧覆写的字符串拼接，
     * `DialectExplainTest` 逐方言断言表与实现一致）。
     *
     * 返回 null → 本方言不支持（调用方按钮降级提示）。
     *
     * 被包语句的合法性由 [ExplainGate] 判定（T-ECH-01，两链同调；本方法自身不做首关键字判定——
     * 直接调用者如 MCP 链在调用点接门）。
     *
     * @param sql 不含结尾分号的查询语句
     * @return 可直接提交执行的 EXPLAIN 语句，null = 不支持
     */
    fun renderExplainStatement(sql: String): String? = ExplainSupport.render(name, sql)

    /**
     * T-ExplainDbl：幂等 EXPLAIN 包装——文本已以 EXPLAIN 关键字开头（词边界、忽略大小写）时
     * 原样返回（去首尾空白与结尾分号后），否则委托 [renderExplainStatement]。
     *
     * 根因场景：首次点击「执行计划」后包装好的 EXPLAIN 语句经执行链写入控制台文档，
     * 再点一次在已包装文本上二次加前缀 → `EXPLAIN FORMAT=JSON EXPLAIN FORMAT=JSON SELECT ...`
     * → MySQL 1064 语法错误。
     *
     * 被包语句的首关键字边界判定（T-ECH-01）**不在此处**：本方法返回 null 的语义是「方言不支持」，
     * 与「语句不可 EXPLAIN」需要不同的用户提示，故由调用方（`SqlPreviewPanel.explainSql` /
     * `SqlVisualizerExplainTool`）先经 [ExplainGate.isExplainableSource] 判定后再调用本方法。
     * **T-ECH-01 订正（独立复核旁路）**：已包装文本（幂等分支）此前**无条件**原样返回，于是
     * `EXPLAIN SELECT 1; DROP TABLE t` 这类「已包装 + 追加语句」的文本绕过调用方的门被整段交回
     * 执行链（IDE 链只有 confirmDollarRawSegments/confirmUnknownParams 两闸，没有 executeSql 的
     * DML 确认闸）——第二条 DDL 会真实执行，正是 D1=A 要堵的形态；且首条包装成功后控制台文档恰是
     * EXPLAIN 文本，用户追加一句即可命中。故已包装文本只在**单条语句**时原样返回（T-ExplainDbl
     * 防双前缀的既有价值保留），多条一律拒（fail closed）。
     * 注：不复用 [ExplainGate.isExplainableSource] 判已包装文本——它认的是被包语句首词，而
     * PG/KingBase/H2/SQLite 的已包装文本首词是包装选项（`(FORMAT JSON, VERBOSE)` 等），直接判会
     * 把合法已包装文本误拒，打掉幂等短路本身。
     */
    fun renderExplainStatementIdempotent(sql: String): String? {
        val trimmed = sql.trim().trimEnd(';').trim()
        if (ExplainGate.startsWithExplainKeyword(trimmed)) {
            if (SqlStatementSplitter.splitStripped(trimmed).size > 1) return null
            return trimmed
        }
        return renderExplainStatement(trimmed)
    }

    /**
     * DELETE 语句是否支持 `LIMIT n` 尾段（O-8 缺陷 C）：MySQL 语法族支持
     * （`DELETE FROM t WHERE ... LIMIT n`），PostgreSQL/Oracle/SQL Server 等不支持——
     * DELETE 的 `last("LIMIT n")` 尾段仅在本方言为 true 时渲染，否则静默省略
     * （与分页子句的方言渲染策略一致）。
     */
    val supportsDeleteLimit: Boolean get() = false

    /**
     * R36（V46 证伪①）：**显式事务开始语句**（回放脚本的事务包裹用；不含结尾分号）。
     *
     * 语义：非 null = 本方言有「显式开启事务」的语句，回放脚本可用 `<本值>;`（前）+
     * `COMMIT;`（后）包裹；null = **本方言无显式事务开始语句**——事务随首条 DML 隐式开启，
     * 中途回退须由客户端持有同一连接/会话执行 ROLLBACK（脚本层无从表达）。此时回放脚本
     * **诚实省略**包裹，UI 禁用该选项并说明原因（不产出 `.sql` 里必然语法错误的伪包裹）。
     *
     * 边界与证据（三档，逐档标级别；核对先例照 O-9 闸门边界）：
     *  - **本机真引擎实测**（`H2 2.3.232` 六模式 REGULAR/MySQL/MariaDB/PostgreSQL/Oracle/
     *    MSSQLServer + `SQLite 3.50.3`）：两引擎均取 `BEGIN`，且**语义成立**——`BEGIN` + INSERT +
     *    `ROLLBACK` → 行不存在；同形 `COMMIT` → 行存在；对照「不开显式事务时 ROLLBACK 对自动
     *    提交行无效」。两引擎的 `START TRANSACTION` 均为语法错误（H2 报 expected
     *    SAVEPOINT/SCRIPT/SHUTDOWN），故不取。
     *  - **官方文法（族/兼容面推定）**：MySQL/MariaDB 与 OceanBase（D1=A 只接 MySQL 模式）取
     *    `START TRANSACTION`；PostgreSQL 族取 `BEGIN`（官方 `BEGIN [WORK|TRANSACTION]`），
     *    KingBase / openGauss 随族取 `BEGIN`；SQL Server 取 `BEGIN TRANSACTION`（`BEGIN TRAN` 缩写）。
     *  - **判无（保守禁用）**：Oracle——`BEGIN` 在 Oracle 是 PL/SQL 块起始，SQL Reference 无
     *    `BEGIN [TRANSACTION]` 语句形态；DM（达梦）默认 Oracle 兼容模式同形。两款均无本机引擎
     *    （DBX MCP 拒事务语句、Docker 未启）→ 依据「宁可少包，不可产出语法错误脚本」判无，
     *    真机核对后增量。
     */
    val transactionBegin: String? get() = null
}

/**
 * 标识符名字的**大小写来源**（O-42 / T-OracleQuoteCaseSource）：[Dialect.quoteIfNeeded] 的分流依据。
 *
 * 引用判定需要它，是因为同一串字符在两种来源下的正确形态可能相反——Oracle 把裸标识符折叠为
 * 大写，于是「字典里含小写字母的真实名字」必须加引号才命中，而「以裸写创建的小写派生名」
 * 恰恰不能加引号。
 *
 * 判据落点（实测定位，非计划初稿所写）：目录来源的**唯一**入口是
 * [cn.song.mybatisplus.visualizer.database.DbTableSpecAdapter.toSpec]（`das.name` / `col.name`
 * 直取自省元数据）；实体来源经 [cn.song.mybatisplus.visualizer.psi.EntityResolver] 产出
 * `ColumnInfo`（`:103`/`:109`/`:151`/`:502` 走 `NamingStrategy.toSnakeCase`），再由
 * `GenerateCrudSqlFromEntityAction.entitySpec` 的 `toCrudSpec()`（`:129`）适配进
 * [cn.song.mybatisplus.visualizer.generator.CrudTableSpec]——该路径**逐字不变**。
 */
enum class IdentifierCaseSource {
    /** 目录来源：自省元数据（DbTable/DasTable）返回的**字典真实名字**，未做大小写归一。 */
    CATALOG,

    /** 实体来源：注解值或 camelCase → snake_case 的**小写派生名**（也是默认值，保持既有行为）。 */
    ENTITY,
}

/**
 * 标识符**折叠方向族**（O-51 / T-IdentifierFoldingFamily）：[Dialect.quoteIfNeeded] 的目录来源分流依据。
 *
 * 与 [IdentifierCaseSource] 正交——后者说「名字从哪来」，本枚举说「本方言把裸标识符折到哪个方向」；
 * 二者相乘才决定引用形态：同一串字典名在 UPPER 族要「含小写即包」，在 LOWER 族要「含大写即包」，
 * 在 NONE 族则不判。
 *
 * 归属依据（真机/引擎实测，2026-09-22）：
 *  - [UPPER] Oracle（DBX MCP 只读通道）：`SELECT COUNT(*) FROM dual` → 1（裸写折为大写命中）、
 *    `FROM "dual"` → `ORA-00942`；H2 2.3.232（引擎级对照实验，见
 *    `DialectH2VerificationTest.标识符折叠方向_H2引擎实测对照`）：引号创建的 `"fold_lower"` 裸写
 *    访问失败（引擎折为 `FOLD_LOWER`）、引号创建的 `"FOLD_UPPER"` 裸写命中 → 折为大写；
 *  - [LOWER] PostgreSQL / KingBase：`SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES` → 366 / 492 行
 *    （裸写折为小写命中小写字典名）、`FROM information_schema."TABLES"` → `relation ... does not exist`
 *    （引号保留原样 → 大写名不存在）；
 *  - [NONE] MySQL / MariaDB / SQLite / SQL Server / DM：DM8 实测三形态同命中（大小写不敏感）。
 */
enum class IdentifierFolding {
    /** 裸标识符折叠为**大写**（Oracle / H2）：字典名含小写字母时必须加引号。 */
    UPPER,

    /** 裸标识符折叠为**小写**（PostgreSQL / KingBase）：字典名含大写字母时必须加引号。 */
    LOWER,

    /** 不折叠（或大小写不敏感匹配）：目录名按名判定无意义，逐字走 [Dialect.quoteIfKeyword]。 */
    NONE,
}

/**
 * 目录来源名字的按族引用（O-51）：[Dialect.identifierFolding] 决定「哪种大小写形态必须加引号」。
 *
 * 不满足折叠命中条件时落回 [Dialect.quoteIfKeyword]——**保留字命中即包的既有行为不变**
 * （Oracle 的 `NUMBER` / `LEVEL`、PG 的 `table` 等仍包）。
 */
private fun Dialect.quoteCatalogName(identifier: String): String = when (identifierFolding) {
    IdentifierFolding.UPPER ->
        if (identifier.any { it.isLowerCase() }) quoteIdentifier(identifier) else quoteIfKeyword(identifier)

    IdentifierFolding.LOWER ->
        if (identifier.any { it.isUpperCase() }) quoteIdentifier(identifier) else quoteIfKeyword(identifier)

    IdentifierFolding.NONE -> quoteIfKeyword(identifier)
}

/**
 * T-ExplainDbl：判断语句是否已以 EXPLAIN 关键字开头——实现已迁至 [ExplainGate.startsWithExplainKeyword]
 * （T-ECH-01 把两处词法边界判定收口一处），此处不再保留第二份实现。
 */

/**
 * 共享渲染辅助：MySQL 形态 `LIMIT [offset,] count`（无 offset 时 `LIMIT count`）。
 * 供 MySQL / MariaDB / DM 复用。
 */
private fun renderMySqlStyleLimit(limit: LimitClause): String {
    val offsetPart = if (limit.offset != null) "${renderLimitValue(limit.offset)}, " else ""
    return "LIMIT $offsetPart${renderLimitValue(limit.count)}"
}

/**
 * 共享渲染辅助：PostgreSQL 形态 `LIMIT {count} OFFSET {offset}`（无 offset 时仅 `LIMIT {count}`）。
 * 供 PostgreSQL / H2 / SQLite / KingBase 复用。
 */
private fun renderPgStyleLimit(limit: LimitClause): String {
    val offsetPart = if (limit.offset != null) " OFFSET ${renderLimitValue(limit.offset)}" else ""
    return "LIMIT ${renderLimitValue(limit.count)}$offsetPart"
}

/** T-M29-03：分页子句数值渲染——字面量原样、运行时值 `?`（service.page 的 pageNum/pageSize 变量）。 */
private fun renderLimitValue(value: SqlValue): String = when (value) {
    is SqlValue.Literal -> value.raw
    is SqlValue.Placeholder -> "?"
}

/**
 * 共享渲染辅助：MySQL 形态 `ON DUPLICATE KEY UPDATE col = VALUES(col), ...`。
 * 供 MySQL / MariaDB 复用（VALUES() 对 5.7~8.0+/MariaDB 全版本可用）。
 *
 * O-51：列名引用走 [Dialect.quoteIfNeeded]（而非裸 [Dialect.quoteIfKeyword]），与 INSERT 基座同一判定；
 * MySQL / MariaDB 为 NONE 族，实体/目录来源产物逐字节不变。
 *
 * T-DialectXcFamily（O-63）扩员：OceanBase（MySQL 模式）与 openGauss 也以本形态为原生 upsert——
 * 前者为 MySQL 兼容面（官方《与 MySQL 兼容性对比》+《唯一键冲突》）；后者**真机实证**仅本形态可用
 * （openGauss 5.0.0：`ON DUPLICATE KEY UPDATE` 解析通过、PG 的 `ON CONFLICT` 语法错误，2026-09-23
 * DBX 只读通道）——「PG 族=ON CONFLICT」的族推定在 openGauss 不成立（详见 [OpenGaussDialect]）。
 */
private fun Dialect.renderMySqlStyleUpsert(
    updateColumns: List<String>,
    caseSource: IdentifierCaseSource,
): String = "ON DUPLICATE KEY UPDATE " +
    updateColumns.joinToString(", ") { "${quoteIfNeeded(it, caseSource)} = VALUES(${quoteIfNeeded(it, caseSource)})" }

/**
 * 共享 upsert 辅助：PostgreSQL 形态 `ON CONFLICT (pk) DO UPDATE SET col = EXCLUDED.col, ...`。
 * 供 PostgreSQL / H2 / SQLite / KingBase 复用。
 *
 * O-51：列名引用走 [Dialect.quoteIfNeeded]——PG 族（LOWER）目录来源含大写时必须加引号，否则会产出
 * 「基座加引号、后缀裸写」的半包形态：`INSERT INTO "Issue_New" ("Id") ... ON CONFLICT (Id) DO UPDATE
 * SET Id = EXCLUDED.Id` 里的裸写 `Id` 被折叠为小写 → 列不存在。SQLite 为 NONE 族，产物逐字节不变。
 */
private fun Dialect.renderPgStyleUpsert(
    conflictKey: String,
    updateColumns: List<String>,
    caseSource: IdentifierCaseSource,
): String =
    "ON CONFLICT (${quoteIfNeeded(conflictKey, caseSource)}) DO UPDATE SET " +
        updateColumns.joinToString(", ") {
            "${quoteIfNeeded(it, caseSource)} = EXCLUDED.${quoteIfNeeded(it, caseSource)}"
        }

/**
 * MySQL 方言：`LIMIT [offset,] count`。
 *
 * 验证来源（P-立项-05）：分页/引用/UPSERT 经 H2 MySQL 兼容模式实测（`DialectH2VerificationTest`，
 * `LIMIT 5, 10` / 反引号 / `ON DUPLICATE KEY UPDATE ... VALUES()` 全通过）；
 * 保留字 [MYSQL_RESERVED] 为 MySQL 8.0 官方保留字（R）全量。
 */
object MySqlDialect : Dialect {
    override val name: String = "MySQL"

    /** R36：MySQL 官方 `START TRANSACTION`（`BEGIN` 亦可，取无歧义的长形态；见 [transactionBegin]）。 */
    override val transactionBegin: String = "START TRANSACTION"

    override fun renderLimit(limit: LimitClause): String = renderMySqlStyleLimit(limit)

    override val supportsDeleteLimit: Boolean = true

    override fun quoteIdentifier(identifier: String): String = "`$identifier`"

    override val identifierQuotes: Set<Char> = setOf('`')

    override val reservedWords: Set<String> = MYSQL_RESERVED

    override fun renderUpsert(
        conflictKey: String,
        updateColumns: List<String>,
        caseSource: IdentifierCaseSource,
    ): String = renderMySqlStyleUpsert(updateColumns, caseSource)
}

/**
 * PostgreSQL 方言：`LIMIT count OFFSET offset`。
 *
 * 验证来源（P-立项-05）：分页/引用经 H2 PostgreSQL 兼容模式实测（`DialectH2VerificationTest`）；
 * 保留字 [PG_RESERVED] 为 PG 官方附录全量（含 reserved (can be function or type name) 类别，
 * 原集合漏 binary/isnull/notnull 等 7 词已补）；UPSERT `ON CONFLICT (k) DO UPDATE SET col =
 * EXCLUDED.col` 为 PG 9.5+ 官方文法（真 PG 验证在 Docker 测试，本机 H2 不支持 DO UPDATE 无法模拟）。
 */
object PostgreSQLDialect : Dialect {
    override val name: String = "PostgreSQL"

    /** R36：PG 官方 `BEGIN [WORK|TRANSACTION]`（见 [transactionBegin]）。 */
    override val transactionBegin: String = "BEGIN"

    override fun renderLimit(limit: LimitClause): String = renderPgStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val identifierQuotes: Set<Char> = setOf('"')

    /**
     * O-51：PG 裸标识符**折叠为小写**，故目录来源（字典真实名字）按「含大写字母即加引号」判定
     * ——以引号创建的大写名裸写后被折叠为小写 → 查不到。
     *
     * 真机依据（DBX MCP 只读实测，2026-09-22）：`SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES`
     * → 366 行（裸写折为小写，命中小写字典名）；`FROM information_schema."tables"` → 366 行；
     * `FROM information_schema."TABLES"` → `relation "information_schema.TABLES" does not exist`。
     */
    override val identifierFolding: IdentifierFolding = IdentifierFolding.LOWER

    override val reservedWords: Set<String> = PG_RESERVED

    override fun renderUpsert(
        conflictKey: String,
        updateColumns: List<String>,
        caseSource: IdentifierCaseSource,
    ): String = renderPgStyleUpsert(conflictKey, updateColumns, caseSource)
}

/**
 * Oracle 方言（12c+）：`OFFSET n ROWS FETCH NEXT m ROWS ONLY`（无 offset 时 `FETCH FIRST m ROWS ONLY`）。
 *
 * 验证来源（P-立项-05）：分页/引用经 H2 Oracle 兼容模式实测（`DialectH2VerificationTest`）；
 * 保留字 [ORACLE_RESERVED] 为 Oracle 官方保留字全量；UPSERT 无原生简洁形态
 * （官方仅 MERGE INTO）→ null 降级纯 INSERT。
 */
object OracleDialect : Dialect {
    override val name: String = "Oracle"

    /**
     * R36 判无（**显式声明**，非遗漏）：`BEGIN` 在 Oracle 是 PL/SQL 块起始而非事务语句，
     * SQL Reference 无 `BEGIN [TRANSACTION]` 形态——脚本里写 `BEGIN;` 必语法错误，
     * 故回放脚本不包裹、UI 禁用该选项（[transactionBegin] 三档证据见接口 KDoc）。
     */
    override val transactionBegin: String? = null

    override fun renderLimit(limit: LimitClause): String = if (limit.offset != null) {
        "OFFSET ${renderLimitValue(limit.offset)} ROWS FETCH NEXT ${renderLimitValue(limit.count)} ROWS ONLY"
    } else {
        "FETCH FIRST ${renderLimitValue(limit.count)} ROWS ONLY"
    }

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val identifierQuotes: Set<Char> = setOf('"')

    /**
     * O-42 的「含小写字母即加引号」规则自 O-51 起由基类按族实现（[Dialect.quoteIfNeeded]），
     * 本类**不再覆写**该方法——只声明族归属，判据落在 `IdentifierFolding.UPPER` 分支。
     *
     * 真库依据（Oracle Free 23 / FREEPDB1，DBX MCP 实测）：
     *  - `SELECT COUNT(*) FROM dbx_test.issue_new` → `ORA-00942: table or view "DBX_TEST"."ISSUE_NEW"
     *    does not exist`（裸写折叠成大写，命不中以引号创建的小写表）；
     *  - `SELECT COUNT(*) FROM dbx_test."issue_new"` → 成功（100 行）；
     *  - `SELECT COUNT(*) FROM dual` → 1，`FROM "dual"` → `ORA-00942`（裸写折叠为 DUAL）。
     */
    override val identifierFolding: IdentifierFolding = IdentifierFolding.UPPER

    override val reservedWords: Set<String> = ORACLE_RESERVED
}

/**
 * SQL Server 方言（2012+）：`OFFSET {offset|0} ROWS FETCH NEXT {count} ROWS ONLY`。
 *
 * 依据：T-M22-03，同 MyBatis-Plus SqlServerDialect 的 2012+ OFFSET/FETCH 语法，
 * 无 offset 时按 0 渲染。标识符引用方括号 `[x]`。
 * 验证来源（P-立项-05）：分页/引用经 H2 MSSQLServer 兼容模式实测（`DialectH2VerificationTest`）。
 */
object SqlServerDialect : Dialect {
    override val name: String = "SQL Server"

    /** R36：T-SQL 官方 `BEGIN TRANSACTION`（缩写 `BEGIN TRAN`；见 [transactionBegin]）。 */
    override val transactionBegin: String = "BEGIN TRANSACTION"

    override fun renderLimit(limit: LimitClause): String =
        "OFFSET ${renderLimitValue(limit.offset ?: SqlValue.Literal("0"))} ROWS FETCH NEXT ${renderLimitValue(limit.count)} ROWS ONLY"

    override fun quoteIdentifier(identifier: String): String = "[$identifier]"

    /** T-DialectQuoteWarn：`QUOTED_IDENTIFIER` 默认 ON → 方括号与双引号都合法（判双引号错即误报）。 */
    override val identifierQuotes: Set<Char> = setOf('[', '"')

    override val reservedWords: Set<String> = SQLSERVER_RESERVED

    /**
     * O-52 / T-IdentityInsertWrap：SQL Server 拒绝向 identity 列显式写值（错误 544
     * `Cannot insert explicit value for identity column in table ... when IDENTITY_INSERT is set to OFF`），
     * 必须先 `SET IDENTITY_INSERT <表> ON`，语句结束后 OFF。
     *
     * 该开关是**会话级**且同一时刻每会话只能有一个表为 ON，故包装必须**逐条 INSERT 成对**出现
     * （多条 INSERT 合批时不能只在整段首尾各包一次）。
     *
     * 表名与 INSERT 基座共用 [quoteIfNeeded]（同一 [caseSource]）——`SET IDENTITY_INSERT` 的目标位
     * 与 `INSERT INTO` 的目标位是两处独立语法位，引用形态不一致会指向不同对象。
     * **未真机验证**：本环境无 SQL Server 实例（DBX MCP 在册连接无 sqlserver 类型），形态取自
     * DBX `d715b16b` 同款实现与 T-SQL 文法；真机端到端登记为环境受限未验证。
     */
    override fun renderInsertWrapper(
        tableName: String,
        sql: String,
        caseSource: IdentifierCaseSource,
    ): String = identityInsertWrapper(tableName, sql, caseSource)
}

/**
 * identity 包装的**共用形态**（SQL Server 与 DM8 逐字符相同）：`SET IDENTITY_INSERT <表> ON;` +
 * INSERT + `SET IDENTITY_INSERT <表> OFF;`。
 *
 * 抽成一处而非两处复写：两库语法位与关键字逐字相同（DM8 的报错文案本身就点名 `SET IDENTITY_INSERT`），
 * 复写会让「改一处忘一处」的形态差异无人发现。
 *
 * **真机证据（2026-09-23，DM8）**：DM8 对「显式给自增列赋值」的拒绝与 SQL Server 同形——
 * `dm.jdbc.driver.DMException: 仅当指定列列表，且SET IDENTITY_INSERT为ON时，才能对自增列赋值`
 * （vendorCode -2723）；加本包装后同一语句实测**成功**（`SET … ON` → INSERT 影响 1 行 → `SET … OFF`），
 * 且「不指定自增列」的 INSERT 无需包装即可成功（对照）。SQL Server 侧仍无真机实例（形态取自 DBX 同款
 * 实现与 T-SQL 文法），故本函数的**共用形态在 DM8 上已真机验证、SQL Server 上仍未验**。
 */
private fun Dialect.identityInsertWrapper(
    tableName: String,
    sql: String,
    caseSource: IdentifierCaseSource,
): String {
    val ref = quoteIfNeeded(tableName, caseSource)
    return "SET IDENTITY_INSERT $ref ON;\n$sql\nSET IDENTITY_INSERT $ref OFF;"
}

/**
 * H2 方言：`LIMIT {count} OFFSET {offset}`（无 offset 时仅 `LIMIT {count}`）。
 *
 * 标识符引用双引号 `"x"`。
 *
 * 验证来源（P-立项-05，移除原「≈PG 关键字」假设性标注）：
 *  - 分页/引用：H2 2.3.232 引擎实测（`DialectH2VerificationTest` regular 模式 TC-01~08 全通过）；
 *  - 保留字：H2 官方关键字表全量（[H2_RESERVED]），引擎实测核对（原 PG 集合缺
 *    key/value/values/day/year/interval/row/if/minus/qualify/rownum/top 等 20+ 词，裸用即语法错误）；
 *  - upsert：H2 原生形态为整语句 `MERGE INTO t (cols) KEY (conflict) VALUES (...)`
 *    （[renderUpsertStatement]）——`ON CONFLICT` 在 H2 任何模式均语法错误（引擎实证），
 *    原 `ON CONFLICT (k) DO UPDATE SET ...` 后缀输出已删除。
 */
object H2Dialect : Dialect {
    override val name: String = "H2"

    /** R36：H2 2.3.232 六模式（含 MySQL/PG/Oracle/MSSQLServer 兼容模式）引擎实测取 `BEGIN`。 */
    override val transactionBegin: String = "BEGIN"

    override fun renderLimit(limit: LimitClause): String = renderPgStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    /** T-DialectQuoteWarn：H2 收双引号 / 反引号 / 方括号三种标识符引号（引擎宽容）。 */
    override val identifierQuotes: Set<Char> = setOf('"', '`', '[')

    /**
     * O-51：H2 裸标识符**折叠为大写**（与 Oracle 同族）→ UPPER 族，目录来源的字典名
     * 「含小写字母即加引号」。
     *
     * 引擎级对照实验依据（H2 2.3.232 REGULAR，`DialectH2VerificationTest.标识符折叠方向_H2引擎实测对照`
     * 逐次复现）：引号创建的 `"fold_lower"` **裸写**访问失败（引擎折为 `FOLD_LOWER` 去找，
     * `Table "FOLD_LOWER" not found`）、引号创建的 `"FOLD_UPPER"` 裸写命中 → 折为大写。
     * 此前归 NONE（按「其余方言」继承默认）与实测相反，本轮按实测订正——NONE 下带引号创建的小写
     * 字典名会被裸写 → 折叠为大写 → 对象不存在。
     */
    override val identifierFolding: IdentifierFolding = IdentifierFolding.UPPER

    override val reservedWords: Set<String> = H2_RESERVED

    override fun renderUpsertStatement(
        tableName: String,
        columns: List<String>,
        conflictKey: String,
        caseSource: IdentifierCaseSource,
    ): String? {
        val cols = columns.joinToString(", ") { quoteIfNeeded(it, caseSource) }
        return "MERGE INTO ${quoteIfNeeded(tableName, caseSource)} ($cols) KEY (${quoteIfNeeded(conflictKey, caseSource)}) " +
            "VALUES (${columns.joinToString(", ") { "?" }})"
    }
}

/**
 * SQLite 方言：同 H2 的 `LIMIT {count} OFFSET {offset}`。
 *
 * 依据：T-M22-03，SQLite 原生支持 `LIMIT count OFFSET offset`。标识符引用双引号 `"x"`。
 */
object SqliteDialect : Dialect {
    override val name: String = "SQLite"

    /** R36：SQLite 3.50.3 引擎实测取 `BEGIN`（`START TRANSACTION` 语法错误）。 */
    override val transactionBegin: String = "BEGIN"

    override fun renderLimit(limit: LimitClause): String = renderPgStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    /** T-DialectQuoteWarn：SQLite 收双引号 / 反引号 / 方括号三种标识符引号（官方文法宽容）。 */
    override val identifierQuotes: Set<Char> = setOf('"', '`', '[')

    override val reservedWords: Set<String> = SQLITE_RESERVED

    override fun renderUpsert(
        conflictKey: String,
        updateColumns: List<String>,
        caseSource: IdentifierCaseSource,
    ): String = renderPgStyleUpsert(conflictKey, updateColumns, caseSource)
}

/**
 * MariaDB 方言：同 MySQL 的 `LIMIT [offset,] count`。
 *
 * 标识符引用反引号。关键字判定：[MARIA_RESERVED]（P-立项-05 移除原「= MySQL 集合」假设——
 * MariaDB 官方保留字含 except/intersect/binary/offset/returning 等 MySQL 8.0 非保留词，
 * 裸用作列名在 MariaDB 语法错误）。
 *
 * 验证来源：分页/引用/UPSERT 形态经 H2 MariaDB 兼容模式实测（`DialectH2VerificationTest`）；
 * 保留字集合为 MariaDB 官方保留字全量（mariadb.com/kb/en/reserved-words/ 逐词核对）。
 */
object MariaDbDialect : Dialect {
    override val name: String = "MariaDB"

    /** R36：MariaDB 同 MySQL 官方 `START TRANSACTION`（见 [transactionBegin]）。 */
    override val transactionBegin: String = "START TRANSACTION"

    override fun renderLimit(limit: LimitClause): String = renderMySqlStyleLimit(limit)

    override val supportsDeleteLimit: Boolean = true

    override fun quoteIdentifier(identifier: String): String = "`$identifier`"

    override val identifierQuotes: Set<Char> = setOf('`')

    override val reservedWords: Set<String> = MARIA_RESERVED

    override fun renderUpsert(
        conflictKey: String,
        updateColumns: List<String>,
        caseSource: IdentifierCaseSource,
    ): String = renderMySqlStyleUpsert(updateColumns, caseSource)
}

/**
 * DM（达梦）方言：同 MySQL 的 `LIMIT [offset,] count`。
 *
 * 标识符引用双引号 `"x"`。关键字判定：[DM_RESERVED]（P-立项-05 移除原「≈Oracle 集合」假设——
 * 达梦官方附录 1 保留字与 Oracle 双向差异大：DM 额外保留 top/section/domain/identity/if/int/
 * index/array 等（Oracle 集合漏包）；Oracle 保留的 number/size/level/raw 等 DM 仅关键字
 * （错包后双引号大小写敏感易失配））。
 * upsert：无原生简洁形态（DM 官方仅支持 MERGE INTO 复杂形态，ON CONFLICT/ON DUPLICATE KEY
 * UPDATE 均不支持——官方文档证实）→ 默认 null 降级纯 INSERT。
 *
 * 验证来源：达梦官方文档（eco.dameng.com《DM8 SQL 语言使用手册》分页文法 + 附录 1 保留字 +
 * FAQ），真实环境验证待后续（`DialectDockerVerificationTest` 无 DM 容器，需用户侧执行）。
 */
object DmDialect : Dialect {
    override val name: String = "DM（达梦）"

    /**
     * R36 判无（**显式声明**，保守）：DM 默认 Oracle 兼容模式，`BEGIN` 为块起始；
     * 本机无 DM 引擎（DBX MCP 拒事务语句、Docker 未启）不可实测 → 依据「宁可少包，
     * 不可产出语法错误脚本」判无，真机核对后增量（见 [transactionBegin]）。
     */
    override val transactionBegin: String? = null

    override fun renderLimit(limit: LimitClause): String = renderMySqlStyleLimit(limit)

    override val supportsDeleteLimit: Boolean = true

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val identifierQuotes: Set<Char> = setOf('"')

    /**
     * **DM8 需要 identity 包装（2026-09-23 真机实测新增，原默认「不包装」是缺陷）**。
     *
     * 该默认值此前是**无证据的保守选择**（O-52 计划 §五-3 明写「DBX 有 DM 实现，但我方无 DM identity
     * 真机证据 → 默认不给 DM 造包装，不照抄；若 §五-3 证伪为需要，另立增量」）。本轮按该条真机复核，
     * 结论是**证伪为需要**：DM8 对显式给自增列赋值的拒绝与 SQL Server 同形——
     * `dm.jdbc.driver.DMException: 仅当指定列列表，且SET IDENTITY_INSERT为ON时，才能对自增列赋值`
     * （vendorCode -2723，库 `DBX_TEST` 表 `T_AUTOINC_7658`，自增列 `ID`）。
     * 故 DM 与 SQL Server 一样走 [identityInsertWrapper]（两库语法逐字相同，实测通过）。
     *
     * **真机读数**：同一 INSERT 不加包装 → 报上述异常；加 `SET IDENTITY_INSERT … ON/OFF` 成对包装 →
     * `SET ON` 无影响行、INSERT **影响 1 行**、`SET OFF` 无影响行（探针行随后按 ID 删除并复验为 0 行，
     * 未留残留）；对照：**不指定**自增列的 INSERT 无需包装即成功（即包装只在自增列被勾回时需要）。
     */
    override fun renderInsertWrapper(
        tableName: String,
        sql: String,
        caseSource: IdentifierCaseSource,
    ): String = identityInsertWrapper(tableName, sql, caseSource)

    override val reservedWords: Set<String> = DM_RESERVED
}

/**
 * KingBase（人大金仓）方言：同 PostgreSQL 的 `LIMIT {count} OFFSET {offset}`。
 *
 * 标识符引用双引号 `"x"`。关键字判定：[KINGBASE_RESERVED]（P-立项-05 移除原「≈PG 集合」
 * 假设——金仓 V9 官方保留字含 PG 没有的 auto_increment/binary/collation/ilike/minus/nvl/
 * online/rowid/rownum/synonym/top 等，裸用作列名在金仓语法错误）。
 * upsert：同 PG 的 `ON CONFLICT` 形态——金仓官方 INSERT 文法含 on_conflict_clause，
 * 官方示例即 `ON CONFLICT (did) DO UPDATE SET dname = EXCLUDED.dname`。
 *
 * 验证来源：金仓官方文档（help.kingbase.com.cn V8/V9《SQL 语句:INSERT》+《SQL保留字和关键字》
 * 章节 22），真实环境验证待后续。
 */
object KingBaseDialect : Dialect {
    override val name: String = "KingBase（人大金仓）"

    /** R36：金仓 PG 族兼容，取 PG 形态 `BEGIN`（文档级/族推定，见 [transactionBegin]）。 */
    override val transactionBegin: String = "BEGIN"

    override fun renderLimit(limit: LimitClause): String = renderPgStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val identifierQuotes: Set<Char> = setOf('"')

    /**
     * O-51：金仓与 PG 同折叠方向（裸标识符折叠为小写）→ LOWER 族。
     *
     * 真机依据（KingbaseES V8，DBX MCP 只读实测，2026-09-22）：`SELECT COUNT(*) FROM
     * INFORMATION_SCHEMA.TABLES` → 492 行（裸写折为小写命中）；`FROM information_schema."TABLES"`
     * → `kb: relation "information_schema.TABLES" does not exist`。此前为「按 PG 兼容推断」，
     * 本轮实测证实（计划 §八 待验证前提 2 已可升级为已证实）。
     */
    override val identifierFolding: IdentifierFolding = IdentifierFolding.LOWER

    override val reservedWords: Set<String> = KINGBASE_RESERVED

    override fun renderUpsert(
        conflictKey: String,
        updateColumns: List<String>,
        caseSource: IdentifierCaseSource,
    ): String = renderPgStyleUpsert(conflictKey, updateColumns, caseSource)
}

/**
 * OceanBase（MySQL 模式）方言（T-DialectXcFamily / O-63）：同 MySQL 的 `LIMIT [offset,] count`。
 *
 * 标识符引用反引号 `` `x` ``。upsert 同 MySQL 的 `ON DUPLICATE KEY UPDATE ... VALUES()` 形态。
 * D1=A 定案：**只接 MySQL 模式**（主流装机面），Oracle 模式不做。
 *
 * 验证来源（**文档级**，无真机实例——环境受限未验证，真机核对后增量）：
 *  - JDBC URL：OceanBase Connector/J 官方《Database URL》`jdbc:oceanbase://host:port/db`（V2.4.16）；
 *  - EXPLAIN：官方 V4.x《SQL 执行计划简介》`EXPLAIN [explain_type] dml_statement`，
 *    `FORMAT = {TRADITIONAL | JSON}`，缺省 TRADITIONAL **表格行** → 与 MySQL 同形态
 *    （[ExplainSupport] 表登记 TABLE 档）；
 *  - 兼容面：官方《与 MySQL 兼容性对比》声明 MySQL 5.7/8.0 绝大部分语法兼容（反引号 / LIMIT /
 *    `ON DUPLICATE KEY UPDATE` 官方《唯一键冲突》在册）。
 *
 * 保留字：沿用 [MYSQL_RESERVED]（MySQL 8.0 全量）为基线——OB MySQL 模式的兼容性声明覆盖语法面，
 * OB 特有保留字差异未逐词核对（真机核对后增量，参照 P-立项-05 的 DM/KingBase 黄金清单先例）。
 * 折叠方向：推定 [IdentifierFolding.NONE]（MySQL 模式大小写不敏感兼容面）——真机打脸按 H2 先例订正。
 */
object OceanBaseDialect : Dialect {
    override val name: String = "OceanBase"

    /** R36：OceanBase 只接 MySQL 模式（D1=A），取 MySQL 形态 `START TRANSACTION`（文档级，见 [transactionBegin]）。 */
    override val transactionBegin: String = "START TRANSACTION"

    override fun renderLimit(limit: LimitClause): String = renderMySqlStyleLimit(limit)

    override val supportsDeleteLimit: Boolean = true

    override fun quoteIdentifier(identifier: String): String = "`$identifier`"

    override val identifierQuotes: Set<Char> = setOf('`')

    override val reservedWords: Set<String> = MYSQL_RESERVED

    override fun renderUpsert(
        conflictKey: String,
        updateColumns: List<String>,
        caseSource: IdentifierCaseSource,
    ): String = renderMySqlStyleUpsert(updateColumns, caseSource)
}

/**
 * openGauss 方言（T-DialectXcFamily / O-63）：PG 族形态——`LIMIT {count} OFFSET {offset}`、
 * 双引号引用、[IdentifierFolding.LOWER] 折叠。D2=A 定案：GaussDB（华为云）与 openGauss 社区版
 * 共用一个 object，两个 JDBC URL 前缀（`jdbc:opengauss://` 与 `jdbc:gaussdb://`）都归本方言
 * （两前缀各有官方文档出处，见 [UrlSubprotocolDialects]）。
 *
 * **验证来源（openGauss 引擎级，DBX MCP 只读通道，GaussDB/openGauss 5.0.0，2026-09-23）**：
 *  - 折叠 LOWER：`SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES` → 455 行（裸大写折小写命中）、
 *    `FROM information_schema."TABLES"` → `relation "information_schema.TABLES" does not exist on gaussdb`；
 *  - 反引号不可用：``SELECT 1 AS `x` `` → `syntax error`（双引号族实锤）；
 *  - LIMIT 形态：`SELECT 1 LIMIT 1 OFFSET 0` → 1 行；`SELECT 1 LIMIT 1, 2` → **0 行**——
 *    openGauss 把 `LIMIT a, b` 按 **PG 语义**（OFFSET a LIMIT b）解析，MySQL 形态会被静默错解，
 *    故分页必须渲染 PG 形态（真机对照读数）；
 *  - EXPLAIN：`EXPLAIN (FORMAT JSON) SELECT 1` → 单列 `QUERY PLAN` JSON 文本（JSON 档实锤）；
 *    `EXPLAIN SELECT 1` → 文本计划树。
 *
 * **upsert 特例（真机对照对实锤）**：openGauss 的原生 upsert 是 **MySQL 形态**
 * `ON DUPLICATE KEY UPDATE col = VALUES(col)`（EXPLAIN 规划期通过），而 PG 的
 * `ON CONFLICT (k) DO UPDATE` 直接语法错误——「PG 族=ON CONFLICT」的族推定在本方言被打脸
 * （KingBase 同为 PG 系但 upsert 相反），照抄 KingBase 先例会产出语法错误 SQL。
 * 官方文档同向（openGauss《INSERT》on_duplicate_key 文法，`VALUES(column_name)` 引用在册）。
 *
 * 保留字：**[OPENGAUSS_RESERVED]**（T-GaussDbDialect 券 01 订正）。此前沿用 `PG_RESERVED`
 * 是缺陷——真机 `pg_get_keywords()` 实测 openGauss 比 PG 多 17 个保留字
 * （`rownum` / `minus` / `modify` / `nocycle` / `performance` / `shrink` / `verify` / `sysdate` /
 * `excluded` / `buckets` / `authid` / `less` / `maxvalue` / `groupparent` / `reject` /
 * `procedure` / `is`）。其中 `rownum` 实测报 `ROWNUM cannot be used as an alias`——
 * 列名为 `rownum` 的表在旧集合下不会被引用，产物直接语法报错。
 */
object OpenGaussDialect : Dialect {
    override val name: String = "openGauss"

    /** R36：openGauss 取 PG 形态 `BEGIN`（族推定；本方言 upsert 曾反族，真机核对后增量，见 [transactionBegin]）。 */
    override val transactionBegin: String = "BEGIN"

    override fun renderLimit(limit: LimitClause): String = renderPgStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val identifierQuotes: Set<Char> = setOf('"')

    /**
     * 折叠方向 LOWER：openGauss 5.0.0 真机实测（2026-09-23，DBX 只读通道），与 PG/KingBase 同族
     * ——目录来源「含大写字母即加引号」。
     */
    override val identifierFolding: IdentifierFolding = IdentifierFolding.LOWER

    override val reservedWords: Set<String> = OPENGAUSS_RESERVED

    /**
     * MySQL 形态 upsert（真机对照对实锤，见类 KDoc）——复用 [renderMySqlStyleUpsert]，
     * 列名引用符号随本方言的双引号（`VALUES()` 引用形态 openGauss 官方 INSERT 文法在册）。
     */
    override fun renderUpsert(
        conflictKey: String,
        updateColumns: List<String>,
        caseSource: IdentifierCaseSource,
    ): String = renderMySqlStyleUpsert(updateColumns, caseSource)
}

/**
 * 方言注册表：按名称获取方言，未知名称回退 MySQL。
 */
object DialectRegistry {

    /** 全部支持方言（顺序即 Settings 下拉顺序）。 */
    val dialects: List<Dialect> = listOf(
        MySqlDialect,
        PostgreSQLDialect,
        OracleDialect,
        SqlServerDialect,
        H2Dialect,
        SqliteDialect,
        MariaDbDialect,
        DmDialect,
        KingBaseDialect,
        OceanBaseDialect,
        OpenGaussDialect,
    )

    /**
     * 按名称取方言。
     *
     * @param name 方言名称（对应 [Dialect.name]）
     * @return 匹配方言；未知名称返回 [MySqlDialect]
     */
    fun byName(name: String?): Dialect =
        dialects.firstOrNull { it.name == name } ?: MySqlDialect

    /**
     * T-DialectAutoInfer：**渲染方言统一读取**（全部渲染消费点收口此函数）。
     *
     * T-TS-FixC：auto/manual 的**模式判定与手动值解析的唯一事实源在本函数内部**，按
     * 「项目级 > 全局」逐层解析（项目级语义由
     * [cn.song.mybatisplus.visualizer.config.ProjectPluginSettings] 的 effective* 方法承载）：
     *
     *  1. 模式：项目级 [cn.song.mybatisplus.visualizer.config.ProjectPluginSettings.effectiveDialectAuto]
     *     （启用项目设置且 dialectAuto 非 null 时取项目值）> 全局
     *     [cn.song.mybatisplus.visualizer.config.PluginSettings.State.dialectAuto]（默认 true）。
     *     为什么项目级是权威：项目级「智能推断」是用户对该项目的**显式声明**，全局值只是**默认值**
     *     （与 ProjectPluginSettings.State 的「空/未覆盖=跟随全局」语义同构）。此前本函数直读全局
     *     auto，于是「项目 true + 全局 false」组合下项目声明被静默忽略——调用方已按项目级判为 auto
     *     进入本函数，却被全局 false 拉回手动分支返回全局手动值（T-TS-FixC 验证者实测口径缺陷）。
     *  2. auto=true → 项目数据源候选的方言共识；异方言/未知候选不取首个。
     *     仅能证明是单模块且无候选时才参考最近 agent URL；无结论兼容回退 [MySqlDialect]。
     *     原始 SQL PSI/模块作用域尚未接线，[decision] 才能区分真实结论与兼容回退。
     *  3. auto=false → 手动值：项目级
     *     [cn.song.mybatisplus.visualizer.config.ProjectPluginSettings.effectiveDialect]
     *     （启用且非空）> 全局 [cn.song.mybatisplus.visualizer.config.PluginSettings.State.dialect]。
     *
     * 边界（O-93 修订）：[project]=null 时推断链不可用——agent 会话与数据源候选均要求项目上下文。
     * 已接线的 PSI 入口显式传所属项目；尚未传项目的路径仍兼容回退 [MySqlDialect]，
     * **不取全局手动残值**：
     * 旧实现此处返回 `byName(manualName)`，等于让 auto 模式被手动残值劫持，任何曾设过手动方言又切回
     * 智能推断的环境（生产用户切回 auto、测试前序类泄漏残值）都会把残值泄漏给 psi 渲染链
     * （O-93 实证：PaginationSimTest 泄漏 "Oracle" 致 SqlPreviewServiceBatchTest 5 例方言/引用
     * ComparisonFailure 随装箱顺序间歇爆发）。auto=false 的手动语义不变（仍取手动值）。
     *
     * @param project 数据源探测与项目级设置读取用项目（null=无项目上下文，见上「边界」）
     */
    fun decision(
        project: com.intellij.openapi.project.Project? = null,
        sourceFile: com.intellij.openapi.vfs.VirtualFile? = null,
        sqlPsiFile: com.intellij.psi.PsiFile? = null,
        moduleKey: String? = null,
        boundDataSourceDialect: String? = null,
    ): DialectDecisionEngine.Decision {
        val settings = runCatching {
            cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        }.getOrNull()
        val manualName = projectManualDialectName(project) ?: settings?.dialect
        val auto = projectDialectAuto(project) ?: settings?.dialectAuto ?: true
        if (!auto) return DialectDecisionEngine.decide(DialectDecisionEngine.Input(manualDialect = manualName))
        if (project == null) return DialectDecisionEngine.decide(DialectDecisionEngine.Input())
        val candidates = DataSourceUrlProbe.candidateDialects(project)
        val singleModule = runCatching {
            com.intellij.openapi.module.ModuleManager.getInstance(project).modules.size == 1
        }.getOrDefault(false)
        val agentDialect = UrlSubprotocolDialects.dialectName(
            cn.song.mybatisplus.visualizer.log.agent.AgentUrlRegistry.lastConnectionUrl(project),
        )
        val learnedModule = moduleKey?.let {
            runCatching { ModuleDataSourceBindingStore.getInstance(project).learnedCandidate(project, it) }.getOrNull()
        }
        val dialectFile = sqlPsiFile?.virtualFile ?: sourceFile
        if (sqlPsiFile != null && dialectFile != null) {
            val snapshot = IdeaSqlContextBridge.resolve(project, sqlPsiFile, dialectFile)
            if (snapshot.availability != IdeaSqlContextBridge.Availability.DATABASE_TOOLS_UNAVAILABLE) {
                return IdeaSqlDialectDecision.decide(
                    snapshot = snapshot,
                    learnedModule = learnedModule,
                    fallbackProjectCandidates = candidates,
                    lastAgentDialect = agentDialect,
                    singleModuleProven = singleModule,
                    boundDataSourceDialect = boundDataSourceDialect,
                )
            }
        }
        return DialectDecisionEngine.decide(DialectDecisionEngine.Input(
            ideaDialect = sourceFile?.let { IdeaSourceDialectProbe.dialectName(project, it) },
            ideaDialectSource = DialectDecisionEngine.Source.IDEA_EFFECTIVE_DIALECT,
            learnedModule = learnedModule,
            projectCandidates = candidates, lastAgentDialect = agentDialect, singleModuleProven = singleModule,
            boundDataSourceDialect = boundDataSourceDialect,
        ))
    }

    fun decision(context: cn.song.mybatisplus.visualizer.context.PreviewSourceContext): DialectDecisionEngine.Decision =
        decision(context.project, context.hostVirtualFile, context.sqlPsiFile, context.canonicalModuleKey)

    fun effective(context: cn.song.mybatisplus.visualizer.context.PreviewSourceContext): Dialect =
        decision(context).dialectName?.let { byName(it) } ?: MySqlDialect

    fun effective(
        project: com.intellij.openapi.project.Project? = null,
        sourceFile: com.intellij.openapi.vfs.VirtualFile? = null,
        sqlPsiFile: com.intellij.psi.PsiFile? = null,
        moduleKey: String? = null,
    ): Dialect = decision(project, sourceFile, sqlPsiFile, moduleKey).dialectName?.let { byName(it) } ?: MySqlDialect

    /**
     * T-TS-FixC：项目级有效方言模式（null=无项目上下文/项目级服务不可用，调用方按全局判定）。
     *
     * 复用 [cn.song.mybatisplus.visualizer.config.ProjectPluginSettings.effectiveDialectAuto] 而非直读
     * `state.dialectAuto`：项目级方法内部已内置「总开关关闭 / dialectAuto=null → 跟随全局」回退语义，
     * 直读会把这层回退丢掉（T-TS-06 已在 SqlPreviewService 侧踩过同款）。
     */
    private fun projectDialectAuto(project: com.intellij.openapi.project.Project?): Boolean? =
        project?.let { p ->
            runCatching {
                cn.song.mybatisplus.visualizer.config.ProjectPluginSettings.getInstance(p).effectiveDialectAuto()
            }.getOrNull()
        }

    /**
     * T-TS-FixC：项目级有效手动方言名（null=无项目上下文/项目级服务不可用，调用方按全局判定）。
     *
     * 同样复用 [cn.song.mybatisplus.visualizer.config.ProjectPluginSettings.effectiveDialect]
     * （启用且非空才取项目值，否则已回退全局）。
     */
    private fun projectManualDialectName(project: com.intellij.openapi.project.Project?): String? =
        project?.let { p ->
            runCatching {
                cn.song.mybatisplus.visualizer.config.ProjectPluginSettings.getInstance(p).effectiveDialect()
            }.getOrNull()
        }
}
