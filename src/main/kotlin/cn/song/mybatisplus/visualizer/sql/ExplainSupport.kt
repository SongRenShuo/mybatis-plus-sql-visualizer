package cn.song.mybatisplus.visualizer.sql

/**
 * EXPLAIN 计划输出格式（T-ExplainChainHardening T-ECH-02 / O-48）。
 *
 * 引入原因（第十轮报告 §8 #51）：此前「格式」概念不存在——包装语句与输出形态都硬编码在
 * 各方言的 `renderExplainStatement` 覆写里，消费方（[cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor]）
 * 只能按**方言名**猜格式，于是 PG/KingBase 的 `FORMAT JSON` 被当成文本树解析 → 建议永不触发
 * （真缺陷，T-ECH-03 修复）。
 *
 * 取值按「计划文本的物理形态」分类，不按数据库品牌——同一品牌不同格式（如 PG 的
 * `FORMAT TEXT` 与 `FORMAT JSON`）分属不同取值，消费方按形态选解析器：
 *  - [ExplainOutputFormat.TABLE]：行列二维表（MySQL 家族 `EXPLAIN` 的原生表格行）；
 *  - [ExplainOutputFormat.TEXT]：单列文本计划树/文本行（DM8 `#NSET2`、H2 `PLAN FOR`、SQLite `QUERY PLAN`、
 *    Oracle DBMS_XPLAN 文本树）；
 *  - [ExplainOutputFormat.JSON]：单列 JSON 文本（PG/KingBase `FORMAT JSON`）；
 *  - [ExplainOutputFormat.XML]：SQL Server `SET STATISTICS XML` 的 Showplan XML（R23 接入时补的
 *    常量与表项——「接入时补一个常量」即本取值）。
 */
enum class ExplainOutputFormat {
    TABLE,
    TEXT,
    JSON,
    XML,
}

/**
 * EXPLAIN 支持面与输出格式的**显式单一事实源**（T-ECH-02 / O-48）。
 *
 * 此前支持面散在 7 处 `Dialect.renderExplainStatement` 覆写里（默认 null），Oracle/SQL Server
 * 靠「没覆写」隐式表达不支持——「没写」与「故意不接」在代码上同形，新增方言时也无从判定
 * 该不该有 EXPLAIN。本表把两件事一次说清：
 *  1. **支持面**：`template == null` 即不接入（调用方降级提示）；R23 起 11/11 全接入
 *     （Oracle/SQL Server 此前显式登记「不接入」，本轮改为带实证留账的真实接入）；
 *  2. **输出格式**：[outputFormat]，供建议器等消费方按形态选解析器。
 *
 * 键是 [Dialect.name]（设置下拉显示名）——**不是**品牌短名：MCP 链的
 * [cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor.advise] 收到的就是显示名。
 * 查找大小写不敏感（兼容调用方传 `mysql` 这类小写形态）。
 *
 * 渲染产物**逐字节不变**是硬约束：模板里的 [SQL_PLACEHOLDER] 替换即旧覆写里的字符串拼接。
 * 守卫用例（`DialectExplainTest`）逐方言断言「本表渲染结果 == `Dialect.renderExplainStatement`
 * 结果」并钉住字面量，防止将来有人加回覆写后与表漂移。
 */
object ExplainSupport {

    /** 模板里的语句占位符（替换为字面量，非正则——SQL 里的 `$`/`\` 不受影响）。 */
    const val SQL_PLACEHOLDER = "{sql}"

    /**
     * 单方言支持项。
     *
     * @param dialectName 方言显示名（[Dialect.name]，表的键）
     * @param template    包装模板（含 [SQL_PLACEHOLDER]）；null = 本方言不接入 EXPLAIN
     * @param outputFormat 计划输出格式；null 与 `template == null` 同时成立（不支持即无格式）
     * @param evidence    支持形态的依据出处（真机实证来源 / 官方文法），随表留证便于复核
     */
    data class Entry(
        val dialectName: String,
        val template: String?,
        val outputFormat: ExplainOutputFormat?,
        val evidence: String,
        /** 二段式方言：计划另查语句（Oracle：`SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY)`）；
         *  null = 单语句通道即取到计划（默认）。 */
        val postPlanQuery: String? = null,
        /**
         * MCP 单语句通道的诚实降级说明（英文——MCP 输出口径不进 bundle；随执行结果以
         * notices 节透出）：仅计划不随本通道行返回的方言在册。null（默认）= 不追加，
         * 输出与无此字段时逐字节一致（防回归硬门禁的判据）。
         */
        val channelNote: String? = null,
    ) {
        /** 本方言是否接入 EXPLAIN。 */
        val supported: Boolean get() = template != null
    }

    /**
     * 全方言支持表（**覆盖 [DialectRegistry.dialects] 全量**——守卫用例断言不漏项）。
     *
     * 形态依据（沿用 Dialect 既有 KDoc 的真机实证留证，未新增未经验证的形态）：
     *  - MySQL 5.7/8.4、MariaDB：`EXPLAIN <sql>`，原生表格行（T-ExplainFirstNative 对齐 IDEA
     *    原生 `MysqlBaseRawPlanData`——`FORMAT=JSON` 属自创形态且全树无渲染器消费）；
     *  - PostgreSQL 16：`EXPLAIN (FORMAT JSON, VERBOSE)`；
     *  - KingbaseES V8/V9：`EXPLAIN (FORMAT JSON)`（PG 兼容）；
     *  - DM8：`EXPLAIN <sql>`，返回 `#NSET2` 计划文本；
     *  - H2：`EXPLAIN PLAN FOR`；SQLite：`EXPLAIN QUERY PLAN`（引擎级单测覆盖）；
     *  - OceanBase（T-DialectXcFamily 接入，T-OBExplainFormat 纠档）：`EXPLAIN <sql>`，**TEXT 档**——
     *    官方 V4.x《SQL 执行计划简介》称缺省 `FORMAT = TRADITIONAL` 为表格行，但**真机实证推翻**：
     *    社区镜像引擎级（oceanbase/oceanbase-ce:4.3.5-lts，OB CE 4.3.5.6，`ob_compatibility_mode=MYSQL`）
     *    `EXPLAIN SELECT 1` 返回**单列 `Query Plan`**，值是 OB 自有的 ASCII 计划树
     *    （`|ID|OPERATOR|NAME|EST.ROWS|EST.TIME(us)|` 段 + `Outputs & filters` 段；真实表得
     *    `TABLE RANGE SCAN`，带 `partitions(p0)` / `is_index_back` 等 OB 专有字段），**不是** MySQL
     *    的 `id/select_type/table/type/...` 多列表格；`EXPLAIN FORMAT = JSON` 为 OB 专用紧凑 JSON
     *    （`ID/OPERATOR/EST.ROWS`），**不是** MySQL `query_block` 结构。档位误登 TABLE 会让
     *    [cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor] 走 MySQL 列名解析分支（找不到
     *    `type/key/rows/Extra` 列即提前返回）→ OceanBase 索引建议永不触发（T-ECH-03 同型假绿）；
     *  - openGauss（T-DialectXcFamily）：`EXPLAIN (FORMAT JSON)`，JSON 档——openGauss 5.0.0
     *    **真机实证**（DBX 只读通道 2026-09-23：FORMAT JSON 返回单列 `QUERY PLAN` JSON 文本）；
     *  - Oracle（R23 接入）：`EXPLAIN PLAN FOR <sql>`，TEXT 档——**真机实证** Oracle Free 23 +
     *    XE 11g（DBX 只读通道 2026-09-30）：单语句执行成功（0 行），计划登记进会话级 PLAN_TABLE
     *    （PUBLIC 同义词 → SYS.PLAN_TABLE$，免建表；写入仅诊断行、随会话消散，无 DDL/DML）；
     *    **跨连接不可见**（另一连接 `DBMS_XPLAN.DISPLAY` 返回 "cannot fetch last explain plan"），
     *    而本插件两条链都是单语句通道（IDE 控制台一/script、MCP 一 execute）→ 二段式在插件内
     *    不可达，走 [Entry.postPlanQuery]/[Entry.channelNote] 诚实降级：语句照发（登记真实发生），
     *    计划行指引到数据库端同连接另查；
     *  - SQL Server（R23 接入）：`SET STATISTICS XML ON; <sql>; SET STATISTICS XML OFF`，XML 档——
     *    **官方镜像引擎级实证**（2026-10-30，`mcr.microsoft.com/mssql/server:2022-latest`，
     *    16.0.4305.5 CU27-GDR Developer on Linux）：
     *    ①**多结果集假设成立**——`SET STATISTICS XML ON; SELECT …; SET STATISTICS XML OFF` 返回三集：
     *    RS#0 查询行 → RS#1 计划 XML（列标签 `Microsoft SQL Server 2005 XML Showplan`，nvarchar）→
     *    RS#2 `updateCount = -1`；四种缓冲模式（adaptive/full/cursor）读数一致；
     *    ②**选型被真机证实**——`SET SHOWPLAN_XML ON` 批内混写直接报
     *    `Msg 1067 The SET SHOWPLAN statements must be the only statements in the batch.`，
     *    正是本表取 STATISTICS XML 而非 SHOWPLAN_XML 的理由；
     *    ③**末尾 OFF 是必需项**（实测非「防御性」）：漏 OFF 会污染同连接后续语句，后续普通查询
     *    也附带计划结果集；
     *    ④**无 FROM 的语句引擎不产生计划**：`SELECT 1` 只回查询行、没有 RS#1——而 [ExplainGate]
     *    放行 `SELECT` 白名单，故用户在 `SELECT 1` 上点执行计划会得到「有结果、没计划」，
     *    属引擎侧事实而非插件缺陷（MCP 侧 channelNote 已按此澄清）；
     *    ⑤DML（INSERT/UPDATE）首结果是 updateCount、计划在第二集——「MCP 首结果集＝查询行」
     *    的说法只对 SELECT 成立；
     *  - 计划 XML 是语句执行后的**附加结果集**，MCP 单结果集通道返回的是查询行而非计划
     *    （[Entry.channelNote] 澄清），IDE 控制台多结果页签可见；
     *  - HighGo（T-XcDialects3 券 04）：`EXPLAIN (FORMAT JSON)`，JSON 档——社区镜像引擎级实证
     *    （HighGo V6.0.1 / PostgreSQL 12.3，镜像 `xuxuclassmate/highgo:6.0.1`，非厂商官方发布物）；
     *  - Vastbase（T-XcDialects3 券 04）：`EXPLAIN (FORMAT JSON)`，JSON 档——官方文档级
     *    （G100 V3.0.9《EXPLAIN》文法，FORMAT 默认 TEXT）；B 模式下 EXPLAIN 兼作 DESC（首关键字
     *    白名单由 [ExplainGate] 保证，此处仅登记）。
     */
    private val table: List<Entry> = listOf(
        Entry("MySQL", "EXPLAIN $SQL_PLACEHOLDER", ExplainOutputFormat.TABLE, "真机实证 MySQL 5.7/8.4（DBX 通道 2026-09-02）"),
        Entry("MariaDB", "EXPLAIN $SQL_PLACEHOLDER", ExplainOutputFormat.TABLE, "真机实证 MariaDB 10.x（DBX 通道 2026-09-02）"),
        Entry("PostgreSQL", "EXPLAIN (FORMAT JSON, VERBOSE) $SQL_PLACEHOLDER", ExplainOutputFormat.JSON, "真机实证 PG 16（2026-09-02）+ 2026-09-22 复验返回形态"),
        Entry("KingBase（人大金仓）", "EXPLAIN (FORMAT JSON) $SQL_PLACEHOLDER", ExplainOutputFormat.JSON, "真机实证 KingbaseES V8（PG 兼容）"),
        Entry("DM（达梦）", "EXPLAIN $SQL_PLACEHOLDER", ExplainOutputFormat.TEXT, "真机实证 DM8（返回 #NSET2 计划文本）"),
        Entry("H2", "EXPLAIN PLAN FOR $SQL_PLACEHOLDER", ExplainOutputFormat.TEXT, "H2 2.3.232 引擎级单测"),
        Entry("SQLite", "EXPLAIN QUERY PLAN $SQL_PLACEHOLDER", ExplainOutputFormat.TEXT, "SQLite 引擎级单测"),
        Entry(
            "OceanBase",
            "EXPLAIN $SQL_PLACEHOLDER",
            ExplainOutputFormat.TEXT,
            "社区镜像引擎级实证：OceanBase CE 4.3.5.6（ob_compatibility_mode=MYSQL）EXPLAIN SELECT 1 返回单列 Query Plan ASCII 树" +
                "（算子 TABLE RANGE SCAN、含 partitions/is_index_back），非官方文档所述的 TRADITIONAL 表格行；" +
                "EXPLAIN FORMAT=JSON 为 OB 专用紧凑 JSON（ID/OPERATOR/EST.ROWS），非 MySQL query_block。" +
                "档位误登 TABLE 会让索引建议走 MySQL 列名分支而永不触发（T-OBExplainFormat）",
        ),
        Entry("openGauss", "EXPLAIN (FORMAT JSON) $SQL_PLACEHOLDER", ExplainOutputFormat.JSON, "真机实证 openGauss 5.0.0（DBX 只读通道 2026-09-23，FORMAT JSON 单列 JSON 文本）"),
        Entry(
            "GaussDB",
            "EXPLAIN (FORMAT JSON) $SQL_PLACEHOLDER",
            ExplainOutputFormat.JSON,
            "官方文档级：GaussDB 集中式 V2.0-10.x《EXPLAIN》文法 `EXPLAIN [ ( option [, ...] ) ] statement`，" +
                "FORMAT {TEXT|XML|JSON|YAML}；与 openGauss 5.0.0 真机同形（单列 QUERY PLAN、`[{\"Plan\":…}]`）。" +
                "GaussDB 商用版无公开容器镜像、无真机——真机读数到位后升档",
        ),
        Entry(
            "Oracle",
            "EXPLAIN PLAN FOR $SQL_PLACEHOLDER",
            ExplainOutputFormat.TEXT,
            "真机实证 Oracle Free 23 + XE 11g（DBX 通道 2026-09-30）：EXPLAIN PLAN FOR 单语句成功（0 行）；计划入会话级 PLAN_TABLE（PUBLIC 同义词→SYS.PLAN_TABLE$ 免建表），跨连接不可见——单语句通道拿不到计划行，postPlanQuery 指引数据库端同连接另查",
            postPlanQuery = "SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY)",
            channelNote = "Oracle registers the plan into PLAN_TABLE and returns no rows; run SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY) on the same session/connection to view the plan (plan rows are session-scoped, so a fresh connection cannot read them).",
        ),
        Entry(
            "SQL Server",
            "SET STATISTICS XML ON; $SQL_PLACEHOLDER; SET STATISTICS XML OFF",
            ExplainOutputFormat.XML,
            "官方镜像引擎级实证：SQL Server 2022 CU27-GDR Developer on Linux（mcr.microsoft.com/mssql/server:2022-latest，16.0.4305.5）" +
                "多结果集确认——RS#0 查询行 → RS#1 计划 XML（列标签 Microsoft SQL Server 2005 XML Showplan）→ RS#2 updateCount=-1，四种缓冲模式一致；" +
                "SET SHOWPLAN_XML ON 批内混写报 Msg 1067（本表取 STATISTICS XML 的理由真机成立）；末尾 OFF 必需（漏 OFF 会污染同连接后续语句）；" +
                "无 FROM 的 SELECT 引擎不产生计划（只有查询行）；DML 首结果为 updateCount、计划在第二集",
            channelNote = "SET STATISTICS XML ON returns the plan XML as an additional result set AFTER the query rows; the rows above are the query result, not the plan - use the IDE database console to see the plan tab. Note: a statement without FROM (e.g. SELECT 1) produces no plan result set at all, and for DML the first result is an update count instead of rows.",
        ),
        Entry("HighGo", "EXPLAIN (FORMAT JSON) $SQL_PLACEHOLDER", ExplainOutputFormat.JSON, "社区镜像引擎级实证：HighGo V6.0.1 / PostgreSQL 12.3，FORMAT JSON 单列 QUERY PLAN JSON 文本（镜像 xuxuclassmate/highgo:6.0.1，非厂商官方）"),
        Entry("Vastbase", "EXPLAIN (FORMAT JSON) $SQL_PLACEHOLDER", ExplainOutputFormat.JSON, "社区镜像引擎级实证：Vastbase G100 V3.0 Build 9（openGauss 6.0.0）A 模式 FORMAT JSON 单列 JSON 文本（镜像 apecloud/vastbase，非厂商官方）"),
    )

    /** 按方言显示名的查找索引（键小写——查找大小写不敏感）。 */
    private val byName: Map<String, Entry> = table.associateBy { it.dialectName.lowercase() }

    /** 全量支持项（守卫用例与文档生成用；顺序即表内声明顺序）。 */
    val entries: List<Entry> get() = table

    /**
     * 取方言支持项。
     *
     * @param dialectName 方言显示名（大小写不敏感）；未知方言返回 null
     */
    fun entryFor(dialectName: String?): Entry? =
        dialectName?.trim()?.takeIf { it.isNotEmpty() }?.let { byName[it.lowercase()] }

    /**
     * 渲染 EXPLAIN 语句。
     *
     * @return 包装后的语句；null = 方言未知或不接入 EXPLAIN（调用方降级提示）
     */
    fun render(dialectName: String?, sql: String): String? =
        entryFor(dialectName)?.template?.replace(SQL_PLACEHOLDER, sql)

    /** 计划输出格式；null = 方言未知或不接入（与 [render] 的 null 同源）。 */
    fun formatFor(dialectName: String?): ExplainOutputFormat? = entryFor(dialectName)?.outputFormat
}
