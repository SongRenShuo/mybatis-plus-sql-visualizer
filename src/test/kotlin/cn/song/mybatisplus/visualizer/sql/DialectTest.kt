package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方言 SPI 单元测试（M5-02，F-14；T-M22-03 扩展 SQL Server / H2 / SQLite / MariaDB / DM / KingBase）。
 */
class DialectTest {

    private val table = TableInfo(
        entityClassName = "DeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(ColumnInfo("id", "id")),
    )

    @Test
    fun `mysql_分页`() {
        assertEquals("LIMIT 10", MySqlDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 5, 10", MySqlDialect.renderLimit(LimitClause(offset = 5, count = 10)))
    }

    @Test
    fun `postgresql_分页`() {
        assertEquals("LIMIT 10", PostgreSQLDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 10 OFFSET 5", PostgreSQLDialect.renderLimit(LimitClause(offset = 5, count = 10)))
    }

    @Test
    fun `oracle_分页`() {
        assertEquals("FETCH FIRST 10 ROWS ONLY", OracleDialect.renderLimit(LimitClause(count = 10)))
        assertEquals(
            "OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY",
            OracleDialect.renderLimit(LimitClause(offset = 5, count = 10)),
        )
    }

    @Test
    fun `分页占位符_运行时值渲染问号`() {
        // T-M29-03：service.page 的运行时 pageNum/pageSize → `?` 占位（各方言形态）
        val placeholder = LimitClause(
            offset = SqlValue.Placeholder("(pageNum - 1) * pageSize"),
            count = SqlValue.Placeholder("pageSize"),
        )
        assertEquals("LIMIT ?, ?", MySqlDialect.renderLimit(placeholder))
        assertEquals("LIMIT ? OFFSET ?", PostgreSQLDialect.renderLimit(placeholder))
        assertEquals("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", OracleDialect.renderLimit(placeholder))
        assertEquals("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", SqlServerDialect.renderLimit(placeholder))
        // 字面量 + 占位符混合（SQL Server 无 offset 恒渲染 0）
        assertEquals(
            "LIMIT 0, ?",
            MySqlDialect.renderLimit(LimitClause(offset = SqlValue.Literal("0"), count = SqlValue.Placeholder("pageSize"))),
        )
        assertEquals(
            "OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY",
            SqlServerDialect.renderLimit(LimitClause(count = SqlValue.Placeholder("pageSize"))),
        )
    }

    @Test
    fun `注册表_按名称与回退`() {
        assertEquals(MySqlDialect, DialectRegistry.byName("MySQL"))
        assertEquals(PostgreSQLDialect, DialectRegistry.byName("PostgreSQL"))
        assertEquals(OracleDialect, DialectRegistry.byName("Oracle"))
        // T-M22-03 新增方言按名称命中
        assertEquals(SqlServerDialect, DialectRegistry.byName("SQL Server"))
        assertEquals(H2Dialect, DialectRegistry.byName("H2"))
        assertEquals(SqliteDialect, DialectRegistry.byName("SQLite"))
        assertEquals(MariaDbDialect, DialectRegistry.byName("MariaDB"))
        assertEquals(DmDialect, DialectRegistry.byName("DM（达梦）"))
        assertEquals(KingBaseDialect, DialectRegistry.byName("KingBase（人大金仓）"))
        // T-DialectXcFamily（O-63）信创方言补全族
        assertEquals(OceanBaseDialect, DialectRegistry.byName("OceanBase"))
        assertEquals(OpenGaussDialect, DialectRegistry.byName("openGauss"))
        // 未知名称/空值回退 MySQL
        assertEquals(MySqlDialect, DialectRegistry.byName("Unknown"))
        assertEquals(MySqlDialect, DialectRegistry.byName(null))
        // 全部方言枚举完整（顺序即 Settings 下拉顺序）
        assertEquals(
            listOf(
                "MySQL", "PostgreSQL", "Oracle", "SQL Server", "H2", "SQLite",
                "MariaDB", "DM（达梦）", "KingBase（人大金仓）", "OceanBase", "openGauss", "GaussDB",
                "HighGo", "Vastbase",
            ),
            DialectRegistry.dialects.map { it.name },
        )
    }

    @Test
    fun `标识符引用差异`() {
        assertEquals("`id`", MySqlDialect.quoteIdentifier("id"))
        assertEquals("\"id\"", PostgreSQLDialect.quoteIdentifier("id"))
        assertEquals("\"id\"", OracleDialect.quoteIdentifier("id"))
        // T-M22-03 新增方言
        assertEquals("[id]", SqlServerDialect.quoteIdentifier("id"))
        assertEquals("\"id\"", H2Dialect.quoteIdentifier("id"))
        assertEquals("\"id\"", SqliteDialect.quoteIdentifier("id"))
        assertEquals("`id`", MariaDbDialect.quoteIdentifier("id"))
        assertEquals("\"id\"", DmDialect.quoteIdentifier("id"))
        assertEquals("\"id\"", KingBaseDialect.quoteIdentifier("id"))
    }

    /**
     * 标识符折叠方向族（O-51 / T-IdentifierFoldingFamily）：方言逐库声明——Oracle / H2 = UPPER、
     * PostgreSQL / KingBase / openGauss / GaussDB / HighGo / Vastbase = LOWER、其余 6 方言 = NONE。
     *
     * 归属依据（真机/引擎实测，2026-09-22）：
     *  - Oracle：`FROM dual` → 1（裸写折为大写命中）、`FROM "dual"` → `ORA-00942`；
     *  - PostgreSQL：`FROM INFORMATION_SCHEMA.TABLES` → 366 行（折为小写命中）、`FROM information_schema."TABLES"`
     *    → `relation "information_schema.TABLES" does not exist`；
     *  - KingBase：同向（裸写 492 行、引号大写 `kb: relation ... does not exist`）；
     *  - H2 2.3.232：引擎级对照实验（`DialectH2VerificationTest.标识符折叠方向_H2引擎实测对照`
     *    逐次复现）——引号创建的 `"fold_lower"` 裸写访问失败、引号创建的 `"FOLD_UPPER"` 裸写命中
     *    → 折为大写（与 Oracle 同族）。**此前归 NONE 与实测相反**，本轮按实测订正（该条经实测
     *    推翻原结论：`DialectTest.kt:125` 那条 `assertEquals(NONE, H2Dialect...)` 已解冻）；
     *  - DM8：三形态（`sys.sysobjects` / `sys."sysobjects"` / `sys."SYSOBJECTS"`）均 1029 行 → 大小写不敏感 → NONE。
     */
    @Test
    fun `标识符折叠方向族`() {
        assertEquals(IdentifierFolding.UPPER, OracleDialect.identifierFolding)
        assertEquals(IdentifierFolding.LOWER, PostgreSQLDialect.identifierFolding)
        assertEquals(IdentifierFolding.LOWER, KingBaseDialect.identifierFolding)
        assertEquals(IdentifierFolding.NONE, MySqlDialect.identifierFolding)
        assertEquals(IdentifierFolding.NONE, SqlServerDialect.identifierFolding)
        assertEquals(IdentifierFolding.UPPER, H2Dialect.identifierFolding)
        assertEquals(IdentifierFolding.NONE, SqliteDialect.identifierFolding)
        assertEquals(IdentifierFolding.NONE, MariaDbDialect.identifierFolding)
        assertEquals(IdentifierFolding.NONE, DmDialect.identifierFolding)
        // T-DialectXcFamily（O-63）：openGauss=LOWER（openGauss 5.0.0 真机实测 2026-09-23）、
        // OceanBase=NONE（MySQL 模式推定，真机核对后增量）
        assertEquals(IdentifierFolding.LOWER, OpenGaussDialect.identifierFolding)
        assertEquals(IdentifierFolding.NONE, OceanBaseDialect.identifierFolding)
        // T-XcDialects3 券 01/02：HighGo=LOWER（社区镜像 V6.0.1 引擎实测 2026-10-30）、
        // Vastbase=LOWER（官方《开发规范》推定，真机在途）
        assertEquals(IdentifierFolding.LOWER, HighGoDialect.identifierFolding)
        assertEquals(IdentifierFolding.LOWER, VastbaseDialect.identifierFolding)
        // 逐库点名族成员（注册表顺序），新增方言漏声明或归错族时先红
        assertEquals(
            listOf("Oracle", "H2"),
            DialectRegistry.dialects.filter { it.identifierFolding == IdentifierFolding.UPPER }.map { it.name },
        )
        assertEquals(
            listOf("PostgreSQL", "KingBase（人大金仓）", "openGauss", "GaussDB", "HighGo", "Vastbase"),
            DialectRegistry.dialects.filter { it.identifierFolding == IdentifierFolding.LOWER }.map { it.name },
        )
    }

    /**
     * 目录来源按族引用（O-51）：同一方法在 UPPER / LOWER 两族下「哪种大小写形态必须加引号」相反，
     * NONE 族不判（[Dialect.quoteIfNeeded] 落回 [Dialect.quoteIfKeyword]）。
     *
     * 实体来源（[IdentifierCaseSource.ENTITY]）三族都**逐字不变**——族判定只允许写在 CATALOG 分支里。
     */
    @Test
    fun `目录来源按折叠族引用`() {
        // UPPER 族（Oracle）：含小写 → 包；全大写 → 裸写（保留字仍包）
        assertEquals("\"issue_new\"", OracleDialect.quoteIfNeeded("issue_new", IdentifierCaseSource.CATALOG))
        assertEquals("ISSUE_NEW", OracleDialect.quoteIfNeeded("ISSUE_NEW", IdentifierCaseSource.CATALOG))
        assertEquals("\"NUMBER\"", OracleDialect.quoteIfNeeded("NUMBER", IdentifierCaseSource.CATALOG))
        // LOWER 族（PG / KingBase）：含大写 → 包；全小写 → 裸写
        assertEquals("\"Issue_New\"", PostgreSQLDialect.quoteIfNeeded("Issue_New", IdentifierCaseSource.CATALOG))
        assertEquals("issue_new", PostgreSQLDialect.quoteIfNeeded("issue_new", IdentifierCaseSource.CATALOG))
        assertEquals("\"Issue_New\"", KingBaseDialect.quoteIfNeeded("Issue_New", IdentifierCaseSource.CATALOG))
        assertEquals("issue_new", KingBaseDialect.quoteIfNeeded("issue_new", IdentifierCaseSource.CATALOG))
        // NONE 族：目录来源也不按大小写判定（保留字行为不变）
        assertEquals("Issue_New", DmDialect.quoteIfNeeded("Issue_New", IdentifierCaseSource.CATALOG))
        assertEquals("Issue_New", MySqlDialect.quoteIfNeeded("Issue_New", IdentifierCaseSource.CATALOG))
        assertEquals("`table`", MySqlDialect.quoteIfNeeded("table", IdentifierCaseSource.CATALOG))
        // 实体来源三族逐字不变（含大写派生名也不得被包）
        assertEquals("Issue_New", OracleDialect.quoteIfNeeded("Issue_New", IdentifierCaseSource.ENTITY))
        assertEquals("Issue_New", PostgreSQLDialect.quoteIfNeeded("Issue_New", IdentifierCaseSource.ENTITY))
        assertEquals("Issue_New", KingBaseDialect.quoteIfNeeded("Issue_New", IdentifierCaseSource.ENTITY))
    }

    /**
     * UPSERT 后缀的列名引用与 INSERT 基座同一判定（O-51 接线）：目录来源含大写时后缀列名同样加引号
     * ——否则产出「基座加引号、后缀裸写」的半包形态（裸写被折叠为小写 → 列不存在）。
     *
     * NONE 族（MySQL / SQLite）两来源产物逐字节一致（无行为变化）；H2 自本轮起为 UPPER 族
     * （引擎实测折为大写），目录来源含小写的名字**加引号**。
     */
    @Test
    fun `upsert列名按目录来源同一判定`() {
        assertEquals(
            "ON CONFLICT (\"Id\") DO UPDATE SET \"Name\" = EXCLUDED.\"Name\"",
            PostgreSQLDialect.renderUpsert("Id", listOf("Name"), IdentifierCaseSource.CATALOG),
        )
        assertEquals(
            "ON CONFLICT (Id) DO UPDATE SET Name = EXCLUDED.Name",
            PostgreSQLDialect.renderUpsert("Id", listOf("Name"), IdentifierCaseSource.ENTITY),
        )
        assertEquals(
            "ON CONFLICT (Id) DO UPDATE SET Name = EXCLUDED.Name",
            SqliteDialect.renderUpsert("Id", listOf("Name"), IdentifierCaseSource.CATALOG),
        )
        // H2 = UPPER 族：目录来源含小写 → 加引号（与 Oracle 同判定）
        assertEquals(
            "MERGE INTO \"Issue_New\" (\"Id\", \"Name\") KEY (\"Id\") VALUES (?, ?)",
            H2Dialect.renderUpsertStatement("Issue_New", listOf("Id", "Name"), "Id", IdentifierCaseSource.CATALOG),
        )
        assertEquals(
            "MERGE INTO Issue_New (Id, Name) KEY (Id) VALUES (?, ?)",
            H2Dialect.renderUpsertStatement("Issue_New", listOf("Id", "Name"), "Id", IdentifierCaseSource.ENTITY),
        )
    }

    /**
     * UPSERT 冲突子句（T-M43）：MySQL 系 ON DUPLICATE KEY UPDATE + VALUES()；
     * PG 系 ON CONFLICT + EXCLUDED；Oracle/SQL Server/DM 无原生形态 → null（渲染方降级纯 INSERT）。
     * 列名按需引用（`table` 保留字命中本方言符号）。
     */
    @Test
    fun `upsert冲突子句`() {
        // MySQL / MariaDB：VALUES()（冲突键不入子句；5.7~8.0+/MariaDB 全版本可用）
        assertEquals(
            "ON DUPLICATE KEY UPDATE `table` = VALUES(`table`), name = VALUES(name)",
            MySqlDialect.renderUpsert("id", listOf("table", "name")),
        )
        assertEquals(
            "ON DUPLICATE KEY UPDATE `table` = VALUES(`table`)",
            MariaDbDialect.renderUpsert("id", listOf("table")),
        )
        // PostgreSQL / SQLite / KingBase：ON CONFLICT (冲突键) + EXCLUDED（`table` 命中 PG 保留字 → 双引号）
        assertEquals(
            "ON CONFLICT (\"table\") DO UPDATE SET id = EXCLUDED.id",
            PostgreSQLDialect.renderUpsert("table", listOf("id")),
        )
        // SQLite/KingBase 与 PG 同形态（各自官方集合），抽查引用符号（id 非保留字 → 不包）
        assertEquals(
            "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name",
            SqliteDialect.renderUpsert("id", listOf("name")),
        )
        assertEquals(
            "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name",
            KingBaseDialect.renderUpsert("id", listOf("name")),
        )
        // Oracle / SQL Server / DM：无原生简洁 upsert → null（MERGE 复杂，渲染降级纯 INSERT）
        assertNull(OracleDialect.renderUpsert("id", listOf("name")))
        assertNull(SqlServerDialect.renderUpsert("id", listOf("name")))
        assertNull(DmDialect.renderUpsert("id", listOf("name")))
        // H2（P-立项-05）：ON CONFLICT 在 H2 任何模式均语法错误（引擎实证）→ 后缀 null；
        // 原生 upsert 走整语句 MERGE INTO..KEY..VALUES（H2 2.3.232 实测可用）
        assertNull(H2Dialect.renderUpsert("id", listOf("name")))
        assertEquals(
            "MERGE INTO t (id, name) KEY (id) VALUES (?, ?)",
            H2Dialect.renderUpsertStatement("t", listOf("id", "name"), "id"),
        )
        assertEquals(
            "MERGE INTO t (\"table\", name) KEY (\"table\") VALUES (?, ?)",
            H2Dialect.renderUpsertStatement("t", listOf("table", "name"), "table"),
        )
    }

    /**
     * SQL Server 分页（T-M22-03）：无 offset 时按 0 渲染 OFFSET。
     */
    @Test
    fun `sqlserver_分页`() {
        assertEquals(
            "OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY",
            SqlServerDialect.renderLimit(LimitClause(count = 10)),
        )
        assertEquals(
            "OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY",
            SqlServerDialect.renderLimit(LimitClause(offset = 5, count = 10)),
        )
    }

    /**
     * T-SqlServerLimitOrder（2026-10-30 真机实证）：SQL Server 的 `OFFSET … FETCH` **必须**跟在
     * `ORDER BY` 之后，否则引擎报 `Incorrect syntax near '0'`（SQL Server 2022 CU27 实测）。
     *
     * <p>旧用例没抓到，是因为只在 `renderLimit` 层断言子句文本、且兼容面验证走 H2 的
     * `MODE=MSSQLServer`——H2 不强制这条规则（假绿，与 T-OBExplainFormat 同型）。
     *
     * <p>三条判据：①无排序 + 分页 → 补 `ORDER BY (SELECT NULL)` 且位于 OFFSET 之前；
     * ②已有排序 → **不**补（不改变用户语义）；③其他方言无排序 + 分页 → **不**引入 ORDER BY。
     */
    @Test
    fun `sqlserver_无排序分页必须补ORDER BY占位`() {
        val noOrder = SelectStatement(table = table, limit = LimitClause(offset = 5, count = 10))
        val sql = SqlGenerator.generate(noOrder, compact = false, dialect = SqlServerDialect)
        assertTrue("SQL Server 无排序 + 分页必须补 ORDER BY (SELECT NULL)，实际: " + sql,
            sql.contains("ORDER BY (SELECT NULL)"))
        assertTrue("ORDER BY 占位必须在 OFFSET 之前（T-SQL 语法位），实际: " + sql,
            sql.indexOf("ORDER BY (SELECT NULL)") < sql.indexOf("OFFSET 5 ROWS"))

        // 已有排序：不补占位，保留用户语义
        val withOrder = noOrder.copy(orderBy = listOf(OrderByItem(ColumnRef(columnName = "id"), asc = false)))
        val sql2 = SqlGenerator.generate(withOrder, compact = false, dialect = SqlServerDialect)
        assertTrue("已有 ORDER BY 时不得再补占位，实际: " + sql2, !sql2.contains("ORDER BY (SELECT NULL)"))
        assertTrue("用户 ORDER BY 应保留，实际: " + sql2, sql2.contains("ORDER BY id DESC"))

        // 其他方言：不得引入 ORDER BY
        val mysql = SqlGenerator.generate(noOrder, compact = false, dialect = MySqlDialect)
        assertTrue("MySQL 不得引入 ORDER BY，实际: " + mysql, !mysql.contains("ORDER BY"))
        val pg = SqlGenerator.generate(noOrder, compact = false, dialect = PostgreSQLDialect)
        assertTrue("PostgreSQL 不得引入 ORDER BY，实际: " + pg, !pg.contains("ORDER BY"))
    }

    /** H2 分页（T-M22-03）：无 offset 时仅 `LIMIT {count}`。 */
    @Test
    fun `h2_分页`() {
        assertEquals("LIMIT 10", H2Dialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 10 OFFSET 5", H2Dialect.renderLimit(LimitClause(offset = 5, count = 10)))
    }

    /** SQLite 分页（T-M22-03）：同 H2 形态。 */
    @Test
    fun `sqlite_分页`() {
        assertEquals("LIMIT 10", SqliteDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 10 OFFSET 5", SqliteDialect.renderLimit(LimitClause(offset = 5, count = 10)))
    }

    /** MariaDB 分页（T-M22-03）：同 MySQL 形态。 */
    @Test
    fun `mariadb_分页`() {
        assertEquals("LIMIT 10", MariaDbDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 5, 10", MariaDbDialect.renderLimit(LimitClause(offset = 5, count = 10)))
    }

    /** DM（达梦）分页（T-M22-03）：同 MySQL 形态。 */
    @Test
    fun `dm_分页`() {
        assertEquals("LIMIT 10", DmDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 5, 10", DmDialect.renderLimit(LimitClause(offset = 5, count = 10)))
    }

    /** KingBase（人大金仓）分页（T-M22-03）：同 PostgreSQL 形态。 */
    @Test
    fun `kingbase_分页`() {
        assertEquals("LIMIT 10", KingBaseDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 10 OFFSET 5", KingBaseDialect.renderLimit(LimitClause(offset = 5, count = 10)))
    }

    @Test
    fun `生成器_方言分页联动`() {
        val statement = SelectStatement(table = table, limit = LimitClause(offset = 5, count = 10))

        val mysql = SqlGenerator.generate(statement, compact = false, dialect = MySqlDialect)
        assertEquals(
            """
            SELECT id
            FROM car_device_info
            LIMIT 5, 10
            """.trimIndent(),
            mysql,
        )

        val pg = SqlGenerator.generate(statement, compact = false, dialect = PostgreSQLDialect)
        assertEquals(
            """
            SELECT id
            FROM car_device_info
            LIMIT 10 OFFSET 5
            """.trimIndent(),
            pg,
        )

        val oracle = SqlGenerator.generate(statement, compact = false, dialect = OracleDialect)
        assertEquals(
            """
            SELECT id
            FROM car_device_info
            OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY
            """.trimIndent(),
            oracle,
        )
    }

    @Test
    fun `默认方言为MySQL_旧调用兼容`() {
        val statement = SelectStatement(table = table, limit = LimitClause(count = 10))
        // 不带方言参数（默认 MySQL）
        assertEquals("10", SqlGenerator.generate(statement).substringAfter("LIMIT "))
    }

    /**
     * T-TS-FixC：`DialectRegistry.effective` 的兜底分支直接用例（此前零直接引用）。
     *
     * 纯环境（无平台 Application）下 PluginSettings 服务不可用 → 按全局默认 auto=true 进推断链，
     * 而 project=null 时探测链不可用 → 回退 `byName(null)`。psi 侧无参 `currentDialect()` 调用点
     * 全靠这条兜底，不得抛异常。
     *
     * auto/manual 的「项目级 × 全局」矩阵需要真实 app 服务与项目级服务，落在
     * ProjectPluginSettingsTest.testDialectRegistryEffectiveMatrix。
     */
    @Test
    fun `effective_无平台设置上下文_兜底MySQL`() {
        assertEquals(MySqlDialect, DialectRegistry.effective())
        assertEquals(MySqlDialect, DialectRegistry.effective(project = null))
    }
}
