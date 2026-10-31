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
 *    Docker 测试（真实 PG 容器）覆盖，H2 配对下标记 [UpsertMode.SKIP]。
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
        EngineSpec("H2", "jdbc:h2:mem:{db};DB_CLOSE_DELAY=-1", H2Dialect.INSTANCE, UpsertMode.REAL),
        EngineSpec("MySQL@H2", "jdbc:h2:mem:{db};MODE=MySQL;DB_CLOSE_DELAY=-1", MySqlDialect.INSTANCE, UpsertMode.REAL),
        EngineSpec("MariaDB@H2", "jdbc:h2:mem:{db};MODE=MariaDB;DB_CLOSE_DELAY=-1", MariaDbDialect.INSTANCE, UpsertMode.REAL),
        EngineSpec("PostgreSQL@H2", "jdbc:h2:mem:{db};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", PostgreSQLDialect.INSTANCE, UpsertMode.SKIP),
        EngineSpec("Oracle@H2", "jdbc:h2:mem:{db};MODE=Oracle;DB_CLOSE_DELAY=-1", OracleDialect.INSTANCE, UpsertMode.DEGRADE_ONLY),
        EngineSpec("SQLServer@H2", "jdbc:h2:mem:{db};MODE=MSSQLServer;DB_CLOSE_DELAY=-1", SqlServerDialect.INSTANCE, UpsertMode.DEGRADE_ONLY),
    )

    /** SQLite 真实引擎配对（`:memory:` 每连接独立库）。 */
    val sqliteEngine: EngineSpec = EngineSpec("SQLite", "jdbc:sqlite::memory:", SqliteDialect.INSTANCE, UpsertMode.REAL)

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
    fun businessTable(tableName: String = "dev_info"): TableInfo = TableInfo("DeviceInfoEntity", tableName, listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("bizName", "biz_name"),
            ColumnInfo("order", "order"),
            ColumnInfo("table", "table"),
            ColumnInfo("status", "status"),
        ))

    /** TC-01/02：插件渲染的分页 SELECT。 */
    fun renderPagedSelect(dialect: Dialect, offset: Long?, count: Long): String =
        SqlGenerator.generate(
            SelectStatement(businessTable(), emptyList(), null, emptyList(), false, emptyList(), emptyList(), null, emptyList(), LimitClause(offset?.let { SqlValue.Literal(it.toString()) }, SqlValue.Literal(count.toString())), null, null, null, emptyList(), null, null),
            false,
            dialect,
            null,
        )

    /** TC-03/04：插件渲染的保留字列 SELECT（列清单 + WHERE + LIKE + ORDER BY 全走 quoteIfKeyword）。 */
    fun renderReservedColumnSelect(dialect: Dialect, tableName: String = "dev_info"): String =
        SqlGenerator.generate(
            SelectStatement(businessTable(tableName), listOf(
                    ColumnRef("order"),
                    ColumnRef("table"),
                    ColumnRef("biz_name"),
                ), null, emptyList(), false, listOf(
                    Condition.Simple(ColumnRef("order"), "=", SqlValue.Literal("10"), null),
                    Condition.Like(ColumnRef("table"), false, SqlValue.Literal("t"), LikePosition.RIGHT, null),
                ), emptyList(), null, listOf(OrderByItem(ColumnRef("order"), true)), null, null, null, null, emptyList(), null, null),
            false,
            dialect,
            null,
        )

    /** TC-07：IN 子句（保留字列 + 多值）。 */
    fun renderInSelect(dialect: Dialect): String =
        SqlGenerator.generate(
            SelectStatement(businessTable(), listOf(ColumnRef("id")), null, emptyList(), false, listOf(
                    Condition.In(ColumnRef("order"), false, listOf(SqlValue.Literal("10"), SqlValue.Literal("20"), SqlValue.Literal("30")), null),
                ), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null),
            false,
            dialect,
            null,
        )

    /** TC-08：聚合函数（COUNT(status) + GROUP BY）。 */
    fun renderAggregateSelect(dialect: Dialect): String =
        SqlGenerator.generate(
            SelectStatement(businessTable(), listOf(
                    ColumnRef(null, "status", "cnt", "COUNT"),
                ), null, emptyList(), false, emptyList(), listOf(ColumnRef("status")), null, emptyList(), null, null, null, null, emptyList(), null, null),
            false,
            dialect,
            null,
        )

    /** UPDATE 语句（SET 保留字列 + WHERE 主键）。 */
    fun renderUpdate(dialect: Dialect): String =
        SqlGenerator.generate(
            UpdateStatement(businessTable(), listOf(
                    SetClause(ColumnRef("order"), SqlValue.Literal("20")),
                    SetClause(ColumnRef("biz_name"), SqlValue.Literal("'updated'")),
                ), listOf(Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null)), null, null, emptyList()),
            false,
            dialect,
            null,
        )

    /** DELETE 语句（WHERE 主键）。 */
    fun renderDelete(dialect: Dialect, tableName: String = "dev_info"): String =
        SqlGenerator.generate(
            DeleteStatement(businessTable(tableName), listOf(Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("3"), null)), emptyList(), null, emptyList()),
            false,
            dialect,
            null,
        )

    /** INSERT / UPSERT 批量模板（BatchModel 渲染，含保留字列；`?` 占位由执行侧绑定）。 */
    fun renderBatch(dialect: Dialect, kind: BatchKind, tableName: String = "dev_info"): String =
        BatchModel(kind, tableName, listOf(
                BatchColumn("id", "entity.id", null, true),
                BatchColumn("biz_name", "entity.bizName", null, true),
                BatchColumn("order", "entity.order", null, true),
                BatchColumn("table", "entity.table", null, true),
            ), emptyList(), null, "id", dialect).renderSql(setOf("id", "biz_name", "order", "table"))

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

    /**
     * 保留字扫描：对 [words] 中每个词构造「插件渲染的 SELECT + INSERT + UPDATE + UPSERT」
     * 并在引擎上执行（列名经 dialect.quoteIfKeyword 包裹）。
     * UPSERT 执行遵循 [spec.upsertMode]：SKIP = 引擎不支持该方言的 upsert 形态则跳过该步
     * （如 H2 各模式均不支持 PG 的 `ON CONFLICT DO UPDATE`——该形态由 Docker 真 PG 覆盖）。
     * 返回失败明细（词 → 异常首行），空列表 = 全部通过。
     */
    fun sweepReservedWords(connection: Connection, spec: EngineSpec, words: Collection<String>): List<String> {
        val dialect = spec.dialect
        val failures = mutableListOf<String>()
        for (word in words) {
            val table = "kw_probe"
            val kwTable = TableInfo("KwEntity", table, listOf(ColumnInfo("id", "id", true), ColumnInfo(word, word)))
            try {
                // 夹具 DDL：方言引用符号包裹保留字列（与插件渲染的引用一致）；DROP 兼容无 IF EXISTS 引擎
                runCatching { exec(connection, "DROP TABLE $table") }
                exec(connection, "CREATE TABLE $table (id INT PRIMARY KEY, ${dialect.quoteIdentifier(word)} INT)")
                exec(connection, "INSERT INTO $table (id, ${dialect.quoteIdentifier(word)}) VALUES (1, 1)")

                // 插件渲染：SELECT（WHERE + LIMIT）
                val select = SqlGenerator.generate(
                    SelectStatement(kwTable, listOf(ColumnRef(word)), null, emptyList(), false, listOf(Condition.Simple(ColumnRef(word), "=", SqlValue.Literal("1"), null)), emptyList(), null, emptyList(), LimitClause(null, SqlValue.Literal("10")), null, null, null, emptyList(), null, null),
                    false,
                    dialect,
                    null,
                )
                exec(connection, select)

                // 插件渲染：INSERT 模板（全列，`?` 绑定）
                val insert = BatchModel(BatchKind.INSERT, table, listOf(
                        BatchColumn("id", "entity.id", null, true),
                        BatchColumn(word, "entity.$word", null, true),
                    ), emptyList(), null, null, dialect).renderSql(setOf("id", word))
                execParams(connection, insert, listOf(50, 1))

                // 插件渲染：UPDATE（SET 保留字列）
                val update = SqlGenerator.generate(
                    UpdateStatement(kwTable, listOf(SetClause(ColumnRef(word), SqlValue.Literal("2"))), listOf(Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null)), null, null, emptyList()),
                    false,
                    dialect,
                    null,
                )
                exec(connection, update)

                // 插件渲染：UPSERT（冲突键 = id；保留字列进更新列表——EXCLUDED./VALUES()/MERGE 引用路径）
                // SKIP：引擎不支持该方言 upsert 形态（PG@H2 的 ON CONFLICT DO UPDATE）→ 跳过该步
                if (spec.upsertMode == UpsertMode.SKIP) continue
                val upsert = BatchModel(BatchKind.UPSERT, table, listOf(
                        BatchColumn("id", "entity.id", null, true),
                        BatchColumn(word, "entity.$word", null, true),
                    ), emptyList(), null, "id", dialect).renderSql(setOf("id", word))
                if (upsert.startsWith("INSERT") && !upsert.contains("ON ")) {
                    // 降级纯 INSERT（Oracle/SQL Server/DM）：id=1 已存在 → 用新主键
                    execParams(connection, upsert, listOf(60, 2))
                } else {
                    // 原生 upsert：id=1 冲突 → 更新分支（VALUES()/EXCLUDED./MERGE KEY 引用保留字列）
                    execParams(connection, upsert, listOf(1, 2))
                }
            } catch (e: Exception) {
                failures.add("$word -> ${e.message?.lineSequence()?.firstOrNull()}")
            } finally {
                runCatching { exec(connection, "DROP TABLE $table") }
            }
        }
        return failures
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
}
