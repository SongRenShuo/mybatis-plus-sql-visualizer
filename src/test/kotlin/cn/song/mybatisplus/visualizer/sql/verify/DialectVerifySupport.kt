package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.BatchKind
import cn.song.mybatisplus.visualizer.model.BatchModel
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.H2Dialect
import cn.song.mybatisplus.visualizer.sql.MariaDbDialect
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.OracleDialect
import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.sql.SqlServerDialect
import cn.song.mybatisplus.visualizer.sql.SqliteDialect
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 方言真实验证支撑（P-立项-05）。
 *
 * **核心原则：被执行的 SQL 一律由插件渲染链路产生**（[SqlGenerator.generate] /
 * [BatchModel.renderSql] 按方言渲染），不手写 SQL——验证的是插件真实输出在目标引擎上的
 * 可执行性，而非语法样例。断言只要求「可执行」（语法/绑定合法），不比对结果集内容
 * （那是功能测试职责）。
 *
 * `?` 占位符（BatchModel 模板的参数位）经 [PreparedStatement] 绑定测试值执行——对齐插件
 * 面板「参数回填后执行」的真实使用形态（`?` 本身就是插件设计输出）。
 *
 * 引擎矩阵（本机零 Docker 可用）：
 *  - H2 2.3.232：REGULAR（H2 方言）/ MODE=MySQL（MySQL）/ MODE=MariaDB（MariaDB）/
 *    MODE=PostgreSQL（PostgreSQL）/ MODE=Oracle（Oracle）/ MODE=MSSQLServer（SQL Server）——
 *    H2 兼容模式按官方文档模拟各库语法形态（分页/引用/UPSERT 形态级证据）；
 *  - SQLite 3.50.3（sqlite-jdbc 内嵌原生引擎）：SQLite 方言——100% 真实证据；
 *  - PostgreSQL 的 `ON CONFLICT DO UPDATE` 在 H2 任何模式不支持（引擎实证）→ 该形态只能由
 *    Docker 测试（真实 PG 容器）覆盖，H2 配对下标记 [UpsertMode.SKIP]；
 *  - **GoldenDB 无 H2 配对**（H2 无 GoldenDB 兼容模式，也不必加）：GoldenDB 渲染形态与
 *    MySQL 逐项相同（分页 LIMIT / 反引号 / ON DUPLICATE KEY UPDATE，T-GoldenDbDialect 计划
 *    §1.2.3 文档级实证），故上方 `MODE=MySQL` 配对读数只作**形态级对照证据**，非 GoldenDB
 *    引擎证据——引擎级验证只能走真机矩阵（`dialectExternalVerify`）。
 */
object DialectVerifySupport {

    /** UPSERT 执行策略（各方言原生形态在当前引擎的可验证性）。 */
    enum class UpsertMode {
        /** 插件渲染的 UPSERT 语句在引擎上实际执行（冲突更新 + 新插入两条路径）。 */
        REAL,

        /** 仅执行「无原生 upsert → 降级纯 INSERT」路径（方言 renderUpsert/renderUpsertStatement 均 null）。 */
        DEGRADE_ONLY,

        /** 引擎不支持该方言的 upsert 形态，跳过执行（由 Docker 真实环境测试覆盖）。 */
        SKIP,
    }

    /** 引擎配对：JDBC URL + 被验证的插件方言 + upsert 策略。 */
    data class EngineSpec(
        val label: String,
        val jdbcUrlTemplate: String,
        val dialect: Dialect,
        val upsertMode: UpsertMode,
        /** 种子数据是否支持多行 VALUES（12c 前的 Oracle 不支持，用 `-Dmpvs.dialect.multiRowValues=false` 回退）。 */
        val multiRowValues: Boolean = true,
    )

    /** H2 引擎配对（兼容模式见类注释；`{db}` 由 [open] 替换为唯一内存库名）。 */
    val h2Engines: List<EngineSpec> = listOf(
        EngineSpec("H2", "jdbc:h2:mem:{db};DB_CLOSE_DELAY=-1", H2Dialect, UpsertMode.REAL),
        EngineSpec("MySQL@H2", "jdbc:h2:mem:{db};MODE=MySQL;DB_CLOSE_DELAY=-1", MySqlDialect, UpsertMode.REAL),
        EngineSpec("MariaDB@H2", "jdbc:h2:mem:{db};MODE=MariaDB;DB_CLOSE_DELAY=-1", MariaDbDialect, UpsertMode.REAL),
        EngineSpec("PostgreSQL@H2", "jdbc:h2:mem:{db};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", PostgreSQLDialect, UpsertMode.SKIP),
        EngineSpec("Oracle@H2", "jdbc:h2:mem:{db};MODE=Oracle;DB_CLOSE_DELAY=-1", OracleDialect, UpsertMode.DEGRADE_ONLY),
        EngineSpec("SQLServer@H2", "jdbc:h2:mem:{db};MODE=MSSQLServer;DB_CLOSE_DELAY=-1", SqlServerDialect, UpsertMode.DEGRADE_ONLY),
    )

    /** SQLite 真实引擎配对（`:memory:` 每连接独立库）。 */
    val sqliteEngine: EngineSpec = EngineSpec("SQLite", "jdbc:sqlite::memory:", SqliteDialect, UpsertMode.REAL)

    /**
     * 由方言能力**推导** upsert 执行策略（T-SuiteIdempotent，2026-10-30）。
     *
     * 外部实例入口原先硬编码 `UpsertMode.REAL`——对 Oracle / SQL Server / 达梦这类**无原生 upsert**
     * 的方言，REAL 分支会去撞主键冲突（`ORA-00001` / SQL Server 2627 / DM 同族），异常直接把
     * 后面的保留字扫描整段带崩，读数变成「方言不支持」的假结论。判定口径与渲染侧一致：
     * [Dialect.renderUpsert] 与 [Dialect.renderUpsertStatement] 都返回 null ⇒ 渲染降级纯 INSERT，
     * 套件只该验降级路径。
     *
     * 注意：这只是「插件有无原生 upsert」的推导；**引擎**是否支持该形态是另一回事
     * （如 H2 任何模式都不认 `ON CONFLICT`），那种情况仍由配对表显式写 [UpsertMode.SKIP]。
     */
    fun upsertModeOf(dialect: Dialect): UpsertMode {
        val hasNative = dialect.renderUpsert("id", listOf("biz_name")) != null ||
            dialect.renderUpsertStatement("dev_info", listOf("id", "biz_name"), "id") != null
        return if (hasNative) UpsertMode.REAL else UpsertMode.DEGRADE_ONLY
    }

    /** 打开独立内存库连接（每用例独立，避免 H2 具名内存库跨用例共享）。 */
    fun open(spec: EngineSpec, dbName: String): Connection {
        val url = spec.jdbcUrlTemplate.replace("{db}", dbName)
        return if (url.startsWith("jdbc:h2:")) {
            DriverManager.getConnection(url, "sa", "")
        } else {
            DriverManager.getConnection(url)
        }
    }

    // ------------------------------------------------------------------
    // 插件渲染产物构造（SqlGenerator / BatchModel 入口）
    // ------------------------------------------------------------------

    /** 业务表模型：id(主键) + biz_name(普通) + order/table(保留字) + status(普通)。 */
    fun businessTable(tableName: String = "dev_info"): TableInfo = TableInfo(
        entityClassName = "DeviceInfoEntity",
        tableName = tableName,
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("bizName", "biz_name"),
            ColumnInfo("order", "order"),
            ColumnInfo("table", "table"),
            ColumnInfo("status", "status"),
        ),
    )

    /** TC-01/02：插件渲染的分页 SELECT。 */
    fun renderPagedSelect(dialect: Dialect, offset: Long?, count: Long): String =
        SqlGenerator.generate(
            SelectStatement(
                table = businessTable(),
                limit = LimitClause(
                    offset = offset?.let { SqlValue.Literal(it.toString()) },
                    count = SqlValue.Literal(count.toString()),
                ),
            ),
            compact = false,
            dialect = dialect,
        )

    /** TC-03/04：插件渲染的保留字列 SELECT（列清单 + WHERE + LIKE + ORDER BY 全走 quoteIfKeyword）。 */
    fun renderReservedColumnSelect(dialect: Dialect, tableName: String = "dev_info"): String =
        SqlGenerator.generate(
            SelectStatement(
                table = businessTable(tableName),
                selectColumns = listOf(
                    ColumnRef(columnName = "order"),
                    ColumnRef(columnName = "table"),
                    ColumnRef(columnName = "biz_name"),
                ),
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "order"), "=", SqlValue.Literal("10")),
                    Condition.Like(
                        ColumnRef(columnName = "table"),
                        negated = false,
                        pattern = SqlValue.Literal("t"),
                        position = LikePosition.RIGHT,
                    ),
                ),
                orderBy = listOf(OrderByItem(ColumnRef(columnName = "order"), asc = true)),
            ),
            compact = false,
            dialect = dialect,
        )

    /** TC-07：IN 子句（保留字列 + 多值）。 */
    fun renderInSelect(dialect: Dialect): String =
        SqlGenerator.generate(
            SelectStatement(
                table = businessTable(),
                selectColumns = listOf(ColumnRef(columnName = "id")),
                conditions = listOf(
                    Condition.In(
                        ColumnRef(columnName = "order"),
                        negated = false,
                        values = listOf(SqlValue.Literal("10"), SqlValue.Literal("20"), SqlValue.Literal("30")),
                    ),
                ),
            ),
            compact = false,
            dialect = dialect,
        )

    /** TC-08：聚合函数（COUNT(status) + GROUP BY）。 */
    fun renderAggregateSelect(dialect: Dialect): String =
        SqlGenerator.generate(
            SelectStatement(
                table = businessTable(),
                selectColumns = listOf(
                    ColumnRef(columnName = "status", function = "COUNT", alias = "cnt"),
                ),
                groupBy = listOf(ColumnRef(columnName = "status")),
            ),
            compact = false,
            dialect = dialect,
        )

    /** UPDATE 语句（SET 保留字列 + WHERE 主键）。 */
    fun renderUpdate(dialect: Dialect): String =
        SqlGenerator.generate(
            UpdateStatement(
                table = businessTable(),
                sets = listOf(
                    SetClause(column = ColumnRef(columnName = "order"), value = SqlValue.Literal("20")),
                    SetClause(column = ColumnRef(columnName = "biz_name"), value = SqlValue.Literal("'updated'")),
                ),
                conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
            ),
            compact = false,
            dialect = dialect,
        )

    /** DELETE 语句（WHERE 主键）。 */
    fun renderDelete(dialect: Dialect, tableName: String = "dev_info"): String =
        SqlGenerator.generate(
            DeleteStatement(
                table = businessTable(tableName),
                conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("3"))),
            ),
            compact = false,
            dialect = dialect,
        )

    /** INSERT / UPSERT 批量模板（BatchModel 渲染，含保留字列；`?` 占位由执行侧绑定）。 */
    fun renderBatch(dialect: Dialect, kind: BatchKind, tableName: String = "dev_info"): String =
        BatchModel(
            kind = kind,
            tableName = tableName,
            toggleColumns = listOf(
                BatchColumn("id", "entity.id", quote = null, checked = true),
                BatchColumn("biz_name", "entity.bizName", quote = null, checked = true),
                BatchColumn("order", "entity.order", quote = null, checked = true),
                BatchColumn("table", "entity.table", quote = null, checked = true),
            ),
            conflictColumn = "id",
            dialect = dialect,
        ).renderSql(activeColumns = setOf("id", "biz_name", "order", "table"))

    /** dev_info 表 BatchModel 模板的绑定值（列序 id, biz_name, order, table）。 */
    fun batchValues(id: Int): List<Any?> = listOf(id, "v", 1, "v")

    /**
     * TC-01~08 全套执行（插件渲染 SQL 在引擎上逐条执行，任一失败 AssertionError 汇总报告）。
     *
     * 夹具 DDL（非插件产物）仅对保留字列使用方言引用符号包裹——与引擎内对象名保持一致，
     * 使插件渲染的按需引用（quoteIfKeyword）命中后能正确解析。
     */
    fun runTcSuite(connection: Connection, spec: EngineSpec) {
        val dialect = spec.dialect
        val q = dialect::quoteIdentifier
        // 夹具：业务表（order/table 为保留字列）+ 种子数据
        // T-SuiteIdempotent（2026-10-30 D2 实测）：**先删后建**，否则持久库（真库外部实例）第二次跑必红
        // （首轮 HighGo/Vastbase 就是被上一轮残留的 dev_info 挡住）。DDL 无 IF EXISTS 的引擎靠 runCatching 兜。
        runCatching { exec(connection, "DROP TABLE dev_info") }
        assertExecutable(
            connection,
            listOf(
                "CREATE TABLE dev_info (id INT PRIMARY KEY, biz_name VARCHAR(50), ${q("order")} INT, " +
                    "${q("table")} VARCHAR(50), status INT)",
            ),
        )
        assertExecutableParams(
            connection,
            seedRows(spec).map { it to emptyList() },
        )

        val statements = mutableListOf<Pair<String, List<Any?>>>(
            // TC-01 基础分页 / TC-02 带偏移分页
            renderPagedSelect(dialect, offset = null, count = 10) to emptyList(),
            renderPagedSelect(dialect, offset = 1, count = 2) to emptyList(),
            // TC-03/04 保留字引用（列清单 + WHERE + LIKE + ORDER BY）
            renderReservedColumnSelect(dialect) to emptyList(),
            // TC-07 IN 子句 / TC-08 聚合
            renderInSelect(dialect) to emptyList(),
            renderAggregateSelect(dialect) to emptyList(),
            // UPDATE（保留字列进 SET）/ INSERT 模板（BatchModel，`?` 绑定执行）
            renderUpdate(dialect) to emptyList(),
            renderBatch(dialect, BatchKind.INSERT) to batchValues(id = 100),
        )

        // TC-05/06 UPSERT：按方言原生形态执行（SKIP = 引擎不支持该形态，Docker 测试覆盖）
        when (spec.upsertMode) {
            UpsertMode.REAL -> {
                // 冲突路径（id=1 已存在 → DO UPDATE/ON DUPLICATE/MERGE 更新分支）
                statements.add(renderBatch(dialect, BatchKind.UPSERT) to batchValues(id = 1))
                // 插入路径（id=200 不存在 → 插入分支）
                statements.add(renderBatch(dialect, BatchKind.UPSERT) to batchValues(id = 200))
            }
            UpsertMode.DEGRADE_ONLY -> {
                // 降级路径必须可执行：方言无原生 upsert（renderUpsert/renderUpsertStatement 均 null）→ 纯 INSERT
                if (dialect.renderUpsert("id", listOf("name")) == null &&
                    dialect.renderUpsertStatement("t", listOf("id", "name"), "id") == null
                ) {
                    statements.add(renderBatch(dialect, BatchKind.UPSERT) to batchValues(id = 200))
                }
            }
            UpsertMode.SKIP -> Unit
        }

        assertExecutableParams(connection, statements)

        // DELETE 最后执行
        assertExecutableParams(connection, listOf(renderDelete(dialect) to emptyList()))

        // T-SuiteIdempotent：收尾清场——外部实例（真库）不留夹具表，重复跑与人工复查都干净。
        runCatching { exec(connection, "DROP TABLE dev_info") }
    }

    /**
     * 种子数据。
     *
     * T-SuiteIdempotent 注释订正（2026-10-30）：原写「Oracle 不支持多行 VALUES」**已过期**——
     * Oracle Free 23 实测支持 `INSERT … VALUES (…),(…)`（12c 起支持，11g 不支持）。
     * 外部实例默认按支持处理，指向老版本（11g）时用 `-Dmpvs.dialect.multiRowValues=false` 回退。
     */
    private fun seedRows(spec: EngineSpec): List<String> {
        val base = "INSERT INTO dev_info (id, biz_name, ${spec.dialect.quoteIdentifier("order")}, " +
            "${spec.dialect.quoteIdentifier("table")}, status) VALUES "
        return if (spec.multiRowValues) {
            listOf("$base (1, 'alpha', 10, 't1', 0), (2, 'beta', 20, 't2', 1), (3, 'gamma', 30, 't3', 0)")
        } else {
            listOf(
                "$base (1, 'alpha', 10, 't1', 0)",
                "$base (2, 'beta', 20, 't2', 1)",
                "$base (3, 'gamma', 30, 't3', 0)",
            )
        }
    }

    /** 多语句探测结论（[supported] = 分号分隔的多语句被接受**且每条都真的执行**）。 */
    data class BatchProbe(val supported: Boolean, val evidence: String)

    /** 多语句探测表（一次性，探测完即删；与保留字探测表分开，互不影响）。 */
    private const val BATCH_PROBE_TABLE = "mpv_batch_probe"

    /**
     * 探测引擎是否接受「分号分隔的多语句」**且每条都真的执行**（T-DialectSweepSpeed，2026-11-06）。
     *
     * 只判「有没有抛异常」不够：部分驱动会在分号处**静默截断**、只执行第一条而不报错，那样批处理会让
     * 后面几条悄悄不执行——扫描照旧全绿，覆盖已经掉了。所以探测用「CREATE + INSERT 两条，再单独
     * SELECT 数行」：读到 1 行才证明第二条真的生效；0 行 = 被截断，按不支持处理（宁可少省往返，不可假绿）。
     *
     * 探测本身也顺带产出 P-3 的读数：引擎拒绝时把它的原话首行带出来（记进 summary 明细，见
     * [sweepReservedWords] 的 `notes`），「不支持」因此可审计，而不是靠猜。
     */
    fun probeMultiStatement(connection: Connection): BatchProbe {
        runCatching { exec(connection, "DROP TABLE $BATCH_PROBE_TABLE") }
        return try {
            execBatch(
                connection,
                "CREATE TABLE $BATCH_PROBE_TABLE (id INT PRIMARY KEY); " +
                    "INSERT INTO $BATCH_PROBE_TABLE (id) VALUES (7)",
            )
            val rows = exec(connection, "SELECT id FROM $BATCH_PROBE_TABLE")
            if (rows == 1) {
                BatchProbe(true, "多语句受支持且两条都生效")
            } else {
                BatchProbe(false, "多语句只生效了第一条（期望 1 行，实得 $rows 行）")
            }
        } catch (e: Exception) {
            BatchProbe(false, "引擎拒绝多语句：${e.message?.lineSequence()?.firstOrNull()}")
        } finally {
            runCatching { exec(connection, "DROP TABLE $BATCH_PROBE_TABLE") }
        }
    }

    /** 单实例内词级并行的默认 worker 数上限（实际取 min(该值, 词数)；`-Dmpvs.external.wordWorkers` 覆盖）。 */
    const val DEFAULT_WORD_WORKERS = 4

    /**
     * 保留字扫描：对 [words] 中每个词构造「插件渲染的 SELECT + INSERT + UPDATE + UPSERT」
     * 并在引擎上执行（列名经 dialect.quoteIfKeyword 包裹）。
     * UPSERT 执行遵循 [spec.upsertMode]：SKIP = 引擎不支持该方言的 upsert 形态则跳过该步
     * （如 H2 各模式均不支持 PG 的 `ON CONFLICT DO UPDATE`——该形态由 Docker 真 PG 覆盖）。
     * 返回失败明细（词 → 异常首行，**按词表顺序**），空列表 = 全部通过。
     *
     * 往返压缩（T-DialectSweepSpeed，2026-11-06；判据与覆盖口径一字未改）：
     *  1. **去掉每词预备 DROP**：表名按词序唯一（`kw_probe_<idx>`），只在本词 `finally` 收尾时删。
     *     唯一表名也挡不住「同轮重跑/上一轮崩溃」的同名残留，所以 `CREATE` 失败时 DROP 后**重试一次**
     *     （只在失败路径多一个往返）。逐词往返 8 → 7。
     *  2. **渲染 DML 合成一次执行**：探测通过时把「夹具种子 INSERT + SELECT / INSERT(带参) / UPDATE /
     *     UPSERT(带参)」以分号拼接成一次 `execute`（参数按面板回填形态内联成字面量）。
     *     种子并入批是本轮（T-DialectSweepSpeed2，2026-11-07）的改动：它此前是独立一个往返。
     *     批处理失败即**清表重建后按逐条 `?` 绑定重跑**，拿到该词精确明细（批内已部分生效时直接续跑，
     *     会把「主键已存在」误记成该词的失败，故必须重建）；若逐条重跑通过，说明是批处理形态不被接受，
     *     该词照 PASS 记账、后续词回退逐条，并把降级写进 [notes]。
     *
     * 词级并行（T-DialectSweepSpeed2，2026-11-07）：
     *  - 词与词天然隔离（每词唯一表名 `kw_probe_<idx>`），可把词表切成 [workerCount] 份并发跑；
     *  - **每 worker 一条独立连接**（[openWorkerConnection]）：同一 Connection 上并发执行不安全（语句
     *    与游标状态都在连接上）。worker 0 复用调用方传入的 [connection]（套件与探测已跑完，状态干净），
     *    其余由工厂新开；
     *  - 并发只压缩**词表内部**，实例级预算/看门狗语义不变（仍按整实例计时）；
     *  - 失败仍**逐词归属**（`word -> 异常首行`）：汇总按下标预分配数组，输出顺序与串行一致；
     *  - 没有工厂（H2 / SQLite / Docker 等单连接入口）时强制 worker=1，行为与改动前一致。
     *
     * @param notes 可选诊断出口：追加 `batch=on` / `batch=off(<引擎原话>)` / `batch=degraded(<原因>)` /
     *              `wordWorkers=<N>`，供真机矩阵把「这轮批处理与词级并行到底生效没有」写进 summary——
     *              省了多少往返必须能取证。
     * @param workerCount 词级并行度上限；实际生效值 = min(该值, 词数)，无工厂时为 1。
     * @param openWorkerConnection 额外 worker 的连接工厂（同一实例）；调用方负责让看门狗能处置这些连接。
     */
    fun sweepReservedWords(
        connection: Connection,
        spec: EngineSpec,
        words: Collection<String>,
        notes: MutableList<String>? = null,
        workerCount: Int = DEFAULT_WORD_WORKERS,
        openWorkerConnection: (() -> Connection)? = null,
    ): List<String> {
        val ordered = words.toList()
        val probe = probeMultiStatement(connection)
        val batchEnabled = AtomicBoolean(probe.supported)
        val localNotes = Collections.synchronizedList(mutableListOf<String>())
        localNotes += if (probe.supported) "batch=on" else "batch=off(${probe.evidence})"

        val workers = if (openWorkerConnection == null) 1 else workerCount.coerceIn(1, maxOf(1, ordered.size))
        localNotes += "wordWorkers=$workers"
        if (ordered.isEmpty()) {
            notes?.addAll(localNotes)
            return emptyList()
        }

        // 按下标预分配：并发完成顺序不影响输出（失败明细按词表顺序，可与串行读数逐行比对）
        val failures = arrayOfNulls<String>(ordered.size)

        fun runShard(indices: List<Int>, conn: Connection) {
            for (index in indices) {
                val word = ordered[index]
                try {
                    val mode = sweepOneWord(conn, spec, word, "kw_probe_$index", batchEnabled)
                    // CAS 同时承担「关掉批处理」与「只记一次降级」：并行下可能多个 worker 同时撞上
                    if (mode == SweepMode.SERIAL && batchEnabled.compareAndSet(true, false)) {
                        // 「批处理失败但逐条通过」不是该词的缺陷：插件渲染 SQL 是好的，不被接受的是批处理形态
                        localNotes += "batch=degraded(批处理执行失败、逐条通过，后续词回退逐条)"
                    }
                } catch (e: Exception) {
                    failures[index] = "$word -> ${e.message?.lineSequence()?.firstOrNull()}"
                }
            }
        }

        if (workers == 1) {
            runShard(ordered.indices.toList(), connection)
        } else {
            val pool = Executors.newFixedThreadPool(workers) { runnable ->
                Thread(runnable, "mpv-word-sweep-${wordWorkerSeq.incrementAndGet()}").apply { isDaemon = true }
            }
            val extraConnections = Collections.synchronizedList(mutableListOf<Connection>())
            try {
                val futures = (0 until workers).map { worker ->
                    val indices = (worker until ordered.size step workers).toList()
                    pool.submit(Callable {
                        if (worker == 0) {
                            runShard(indices, connection)
                        } else {
                            val extra = try {
                                openWorkerConnection!!.invoke()
                            } catch (e: Exception) {
                                // 连接开不出来：该 shard 的每个词都记失败（不能静默少扫——空绿）
                                indices.forEach { index ->
                                    failures[index] = "${ordered[index]} -> 无法建立词级并行连接：" +
                                        (e.message?.lineSequence()?.firstOrNull() ?: e::class.java.simpleName)
                                }
                                null
                            }
                            if (extra != null) {
                                extraConnections += extra
                                runShard(indices, extra)
                            }
                        }
                    })
                }
                futures.forEach { it.get() }
            } finally {
                pool.shutdownNow()
                extraConnections.forEach { runCatching { it.close() } }
            }
        }
        notes?.addAll(localNotes)
        return failures.mapNotNull { it }
    }

    /** 词级并行 worker 线程序号（线程名取证用）。 */
    private val wordWorkerSeq = AtomicInteger()

    /** 单个词的执行形态：批处理成功 / 逐条（含批处理失败后的回退）。 */
    private enum class SweepMode { BATCH, SERIAL }

    /**
     * 单个保留字（一轮 1 张唯一表）。
     *
     * @return [SweepMode.BATCH] = 走批处理且成功；[SweepMode.SERIAL] = 走逐条（或批处理失败后回退到逐条），
     *         本词本身无失败。
     * @throws Exception 该词在引擎上确实执行失败（异常首行即失败明细）。
     */
    private fun sweepOneWord(
        connection: Connection,
        spec: EngineSpec,
        word: String,
        table: String,
        batchEnabled: AtomicBoolean,
    ): SweepMode {
        val dialect = spec.dialect
        val quoted = dialect.quoteIdentifier(word)
        val kwTable = TableInfo(
            entityClassName = "KwEntity",
            tableName = table,
            columns = listOf(ColumnInfo("id", "id", isId = true), ColumnInfo(word, word)),
        )
        // 夹具 DDL：方言引用符号包裹保留字列（与插件渲染的引用一致）
        val ddl = "CREATE TABLE $table (id INT PRIMARY KEY, $quoted INT)"
        createWithRetry(connection, table, ddl)
        try {
            // 夹具种子（非插件产物，但**不是**可省项：UPSERT 的 id=1 冲突分支靠它存在）。
            // T-DialectSweepSpeed2：并入批处理语句序列，省掉独立的一个往返（覆盖不变）。
            val seed = SweepStatement("INSERT INTO $table (id, $quoted) VALUES (1, 1)", emptyList())

            // 插件渲染：SELECT（WHERE + LIMIT）
            val select = SqlGenerator.generate(
                SelectStatement(
                    table = kwTable,
                    selectColumns = listOf(ColumnRef(columnName = word)),
                    conditions = listOf(Condition.Simple(ColumnRef(columnName = word), "=", SqlValue.Literal("1"))),
                    limit = LimitClause(count = SqlValue.Literal("10")),
                ),
                compact = false,
                dialect = dialect,
            )
            // 插件渲染：INSERT 模板（全列，`?` 绑定）
            val insert = BatchModel(
                kind = BatchKind.INSERT,
                tableName = table,
                toggleColumns = listOf(
                    BatchColumn("id", "entity.id", quote = null, checked = true),
                    BatchColumn(word, "entity.$word", quote = null, checked = true),
                ),
                dialect = dialect,
            ).renderSql(setOf("id", word))
            // 插件渲染：UPDATE（SET 保留字列）
            val update = SqlGenerator.generate(
                UpdateStatement(
                    table = kwTable,
                    sets = listOf(SetClause(column = ColumnRef(columnName = word), value = SqlValue.Literal("2"))),
                    conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
                ),
                compact = false,
                dialect = dialect,
            )
            val statements = mutableListOf(
                seed,
                SweepStatement(select, emptyList()),
                SweepStatement(insert, listOf(50, 1)),
                SweepStatement(update, emptyList()),
            )

            // 插件渲染：UPSERT（冲突键 = id；保留字列进更新列表——EXCLUDED./VALUES()/MERGE 引用路径）
            // SKIP：引擎不支持该方言 upsert 形态（PG@H2 的 ON CONFLICT DO UPDATE）→ 跳过该步
            if (spec.upsertMode != UpsertMode.SKIP) {
                val upsert = BatchModel(
                    kind = BatchKind.UPSERT,
                    tableName = table,
                    toggleColumns = listOf(
                        BatchColumn("id", "entity.id", quote = null, checked = true),
                        BatchColumn(word, "entity.$word", quote = null, checked = true),
                    ),
                    conflictColumn = "id",
                    dialect = dialect,
                ).renderSql(setOf("id", word))
                val values = if (upsert.startsWith("INSERT") && !upsert.contains("ON ")) {
                    // 降级纯 INSERT（Oracle/SQL Server/DM）：id=1 已存在 → 用新主键
                    listOf(60, 2)
                } else {
                    // 原生 upsert：id=1 冲突 → 更新分支（VALUES()/EXCLUDED./MERGE KEY 引用保留字列）
                    listOf(1, 2)
                }
                statements += SweepStatement(upsert, values)
            }

            if (!batchEnabled.get()) {
                statements.forEach { runSweepStatement(connection, it) }
                return SweepMode.SERIAL
            }

            val batched = runCatching { execBatch(connection, mergeStatements(statements)) }
            if (batched.isSuccess) return SweepMode.BATCH

            // 批处理失败 → 清表重建再逐条重跑：批内可能已部分生效（如 INSERT 成功、UPDATE 失败），
            // 直接续跑会把「主键已存在」记成该词失败，读数就成了假的。
            runCatching { exec(connection, "DROP TABLE $table") }
            createWithRetry(connection, table, ddl)
            statements.forEach { runSweepStatement(connection, it) }
            return SweepMode.SERIAL
        } finally {
            runCatching { exec(connection, "DROP TABLE $table") }
        }
    }

    /** 一条待执行的渲染语句（[params] 非空即带 `?` 占位，逐条路径走 PreparedStatement 绑定）。 */
    private class SweepStatement(val sql: String, val params: List<Any?>)

    private fun runSweepStatement(connection: Connection, statement: SweepStatement) {
        if (statement.params.isEmpty()) exec(connection, statement.sql)
        else execParams(connection, statement.sql, statement.params)
    }

    /**
     * 把多条语句拼成一次执行（参数内联成字面量——对齐面板「参数回填后执行」的形态）。
     *
     * 占位符个数与参数个数不一致时直接抛错（→ 调用方回退逐条），不做「猜着填」。
     */
    private fun mergeStatements(statements: List<SweepStatement>): String =
        statements.joinToString("; ") { inlineParams(it.sql, it.params) }

    private fun inlineParams(sql: String, values: List<Any?>): String {
        if (values.isEmpty()) {
            require(!sql.contains('?')) { "批处理拼接：无参数语句里出现 `?`（无法内联）" }
            return sql
        }
        val expected = sql.count { it == '?' }
        require(expected == values.size) { "批处理拼接：占位符 $expected 个、参数 ${values.size} 个，无法内联" }
        val out = StringBuilder(sql.length + 32)
        var next = 0
        sql.forEach { ch ->
            if (ch == '?') out.append(sqlLiteral(values[next++])) else out.append(ch)
        }
        return out.toString()
    }

    /** 字面量化（只用于扫描套件的中小整数与短字符串；null → NULL）。 */
    private fun sqlLiteral(value: Any?): String = when (value) {
        null -> "NULL"
        is Number -> value.toString()
        is Boolean -> if (value) "1" else "0"
        else -> "'" + value.toString().replace("'", "''") + "'"
    }

    /** 建表：失败时清掉同名残留后重试一次（唯一表名也可能撞上一轮崩溃的残留）。 */
    private fun createWithRetry(connection: Connection, table: String, ddl: String) {
        try {
            exec(connection, ddl)
        } catch (_: Exception) {
            runCatching { exec(connection, "DROP TABLE $table") }
            exec(connection, ddl)
        }
    }

    /**
     * 引擎保留字覆盖探测（反向验证）：对候选词**裸用**（不引用）建列 + 查询，
     * 返回引擎拒绝的词清单——调用方断言「拒绝词 ⊆ 方言保留字集合」（漏包检测）。
     */
    fun probeUnquotedRejected(connection: Connection, candidates: Collection<String>): List<String> {
        val rejected = mutableListOf<String>()
        for (word in candidates) {
            val table = "p_$word"
            var ok = true
            try {
                exec(connection, "CREATE TABLE $table ($word INT)")
                exec(connection, "SELECT $word FROM $table")
            } catch (_: SQLException) {
                ok = false
            } finally {
                runCatching { exec(connection, "DROP TABLE $table") }
            }
            if (!ok) rejected.add(word)
        }
        return rejected
    }

    /**
     * 批量执行（无参语句）：任一失败抛 AssertionError（汇总 SQL 与异常，一次看到全部失败项）。
     */
    fun assertExecutable(connection: Connection, sqls: List<String>) {
        assertExecutableParams(connection, sqls.map { it to emptyList() })
    }

    /**
     * 批量执行（`?` 占位语句带绑定值）：任一失败抛 AssertionError。
     */
    fun assertExecutableParams(connection: Connection, statements: List<Pair<String, List<Any?>>>) {
        val failures = mutableListOf<String>()
        for ((sql, values) in statements) {
            try {
                if (values.isEmpty()) exec(connection, sql) else execParams(connection, sql, values)
            } catch (e: Exception) {
                failures.add("$sql\n    -> ${e.message}")
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("插件渲染 SQL 在引擎上执行失败 ${failures.size} 条:\n" + failures.joinToString("\n"))
        }
    }

    /** 执行单条无参 SQL（返回结果行数；无结果集返回 -1）。 */
    fun exec(connection: Connection, sql: String): Int =
        connection.createStatement().use { st ->
            val hasRs = st.execute(sql)
            if (!hasRs) -1 else st.resultSet.use { rs ->
                var n = 0
                while (rs.next()) n++
                n
            }
        }

    /** 执行带 `?` 占位的 SQL 并绑定参数（对齐面板回填后的执行形态）。 */
    fun execParams(connection: Connection, sql: String, values: List<Any?>): Int =
        connection.prepareStatement(sql).use { ps: PreparedStatement ->
            values.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
            val hasRs = ps.execute()
            if (!hasRs) -1 else ps.resultSet.use { rs ->
                var n = 0
                while (rs.next()) n++
                n
            }
        }

    /**
     * 执行「分号分隔的多语句」（一次往返）。
     *
     * 结果集在这里不是断言对象，但必须**读干净**：不消费的游标会让部分驱动把连接留在半开状态，
     * 后续 DROP 就会撞上「上一个结果集还没关」这类与目标无关的报错。
     *
     * drain **不吞异常**：部分驱动（SQL Server 实测形态）把后续语句的错误留到 `getMoreResults()` 才抛，
     * 吞掉就等于把「第二条其实失败了」读成成功（假绿）。抛出去 → 调用方回退逐条，代价是往返，
     * 换来的是不会漏判。
     */
    fun execBatch(connection: Connection, sql: String): Int =
        connection.createStatement().use { st ->
            st.execute(sql)
            var isResultSet = true
            var guard = 0
            while (guard++ < 64) {
                if (isResultSet) {
                    st.getResultSet()?.use { rs -> while (rs.next()) Unit }
                } else if (st.updateCount == -1) {
                    break
                }
                isResultSet = st.getMoreResults()
            }
            -1
        }
}
