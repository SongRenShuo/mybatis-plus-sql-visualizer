package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEW-21 信创兼容性体检编排层定向测试。
 *
 * 覆盖：①P-5 证伪（逐方言渲染是否需 PSI/工程上下文 → 不需要，纯 JUnit 即证）；
 * ②14 方言渲染矩阵（行数/方言集合/产物形态）+ 耗时实测（判据 < 200ms）；
 * ③四档差异各配正例 + 反例（不误报）；
 * ④P-6 隐式转换的可判定子集（数值列 vs 非数值字面量）+ 无类型证据时的诚实降级。
 * 纯逻辑——无 IDE 类型。
 */
class MultiDialectRenderPlanTest {

    /** 判据夹具：14 个注册方言。 */
    private val allDialects = DialectRegistry.dialects

    private val table = TableInfo(
        entityClassName = "UserEntity",
        tableName = "t_user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true, fieldType = "java.lang.Long"),
            ColumnInfo("status", "status", fieldType = "java.lang.Integer"),
            ColumnInfo("name", "name", fieldType = "java.lang.String"),
        ),
    )

    // ------------------------------------------------------------------
    // P-5 证伪：渲染是否需要 PSI/工程上下文 → 不需要
    // ------------------------------------------------------------------

    @Test
    fun `P5证伪_逐方言渲染不需要工程上下文_纯JUnit即可跑满14方言`() {
        // 本类无 BasePlatformTestCase、无 Project 实例，plan() 直接跑通即证伪「需要 project」
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Model(SelectStatement(table = table, limit = LimitClause(count = 10))),
            allDialects,
        )
        assertEquals("14 方言各一行", 14, result.rows.size)
        assertTrue("每行都有渲染产物", result.rows.all { it.sql.isNotBlank() })
    }

    // ------------------------------------------------------------------
    // 判据：14 方言矩阵 + 耗时
    // ------------------------------------------------------------------

    @Test
    fun `14方言渲染矩阵_行数与方言集合与注册表一致`() {
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Model(
                SelectStatement(
                    table = table,
                    conditions = listOf(
                        Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1")),
                    ),
                    limit = LimitClause(count = 10),
                ),
            ),
            allDialects,
        )
        assertEquals(14, result.rows.size)
        assertEquals(allDialects.map { it.name }, result.rows.map { it.dialectName })
    }

    @Test
    fun `14方言渲染矩阵_分页形态随方言变化`() {
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Model(
                SelectStatement(
                    table = table,
                    orderBy = emptyList(),
                    limit = LimitClause(count = 10),
                ),
            ),
            allDialects,
        )
        fun sqlOf(name: String) = result.rows.first { it.dialectName == name }.sql
        assertTrue("MySQL 用 LIMIT", sqlOf("MySQL").contains("LIMIT 10"))
        assertTrue("Oracle 用 FETCH FIRST", sqlOf("Oracle").contains("FETCH FIRST 10 ROWS ONLY"))
        assertTrue(
            "SQL Server 无排序 + 分页须补 ORDER BY (SELECT NULL)",
            sqlOf("SQL Server").contains("ORDER BY (SELECT NULL)"),
        )
        assertTrue(
            "SQL Server 用 OFFSET/FETCH",
            sqlOf("SQL Server").contains("OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY"),
        )
    }

    @Test
    fun `渲染总耗时_14方言在200ms内`() {
        // 预热一次（JIT + 类初始化），再连测 5 次取均值——判据是「不阻塞 EDT」，取稳定读数。
        // 用 nanoTime 直接量（elapsedMillis 是整型，亚毫秒读数会被压成 0，贴不出可信数）。
        val source = MultiDialectRenderPlan.Source.Model(
            SelectStatement(
                table = table,
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "status"), "=", SqlValue.Literal("1")),
                ),
                limit = LimitClause(count = 20),
            ),
        )
        MultiDialectRenderPlan.plan(source, allDialects)
        val nanos = (1..5).map {
            val started = System.nanoTime()
            MultiDialectRenderPlan.plan(source, allDialects)
            System.nanoTime() - started
        }
        val avgMs = nanos.average() / 1_000_000.0
        println(
            "NEW-21 14 方言渲染耗时实测：样本=${nanos.map { "%.3f".format(it / 1_000_000.0) }}ms " +
                "均值=${"%.3f".format(avgMs)}ms 最大=${"%.3f".format(nanos.max() / 1_000_000.0)}ms",
        )
        assertTrue("14 方言渲染均值应 < 200ms，实测样本 $nanos ns", avgMs < 200.0)
    }

    // ------------------------------------------------------------------
    // 档一：保留字冲突（正例 + 反例）
    // ------------------------------------------------------------------

    @Test
    fun `档一保留字_正例_number是Oracle保留字但MySQL合法`() {
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Text("SELECT number, name FROM t_user"),
            allDialects,
        )
        val oracle = result.rows.first { it.dialectName == "Oracle" }
        assertTrue(
            "Oracle 行应报 number 保留字冲突",
            oracle.differences.any { it.category == MultiDialectRenderPlan.Category.RESERVED_WORD &&
                it.args.first() == "number" },
        )
        val mysql = result.rows.first { it.dialectName == "MySQL" }
        assertTrue(
            "MySQL 行不得报 number（MySQL 中合法）",
            mysql.differences.none { it.category == MultiDialectRenderPlan.Category.RESERVED_WORD &&
                it.args.first() == "number" },
        )
    }

    @Test
    fun `档一保留字_反例_普通列名不命中`() {
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Text("SELECT id, name FROM t_user"),
            allDialects,
        )
        assertTrue(
            "id/name 非保留字，不得报保留字冲突",
            result.rows.none { row -> row.differences.any { it.category == MultiDialectRenderPlan.Category.RESERVED_WORD } },
        )
    }

    @Test
    fun `档一保留字_模型路径精确命中列名`() {
        val t = TableInfo(
            entityClassName = "T",
            tableName = "t_probe",
            columns = listOf(ColumnInfo("user", "user")),
        )
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Model(
                SelectStatement(
                    table = t,
                    conditions = listOf(Condition.Simple(ColumnRef(columnName = "user"), "=", SqlValue.Literal("1"))),
                ),
            ),
            allDialects,
        )
        val mysql = result.rows.first { it.dialectName == "MySQL" }
        val hit = mysql.differences.firstOrNull { it.category == MultiDialectRenderPlan.Category.RESERVED_WORD }
        assertNotNull("MySQL 中 user 是保留字，应命中", hit)
        assertEquals("MySQL 引用符号是反引号", "`user`", hit!!.args[2])
    }

    // ------------------------------------------------------------------
    // 档二：分页语法（正例 + 反例）
    // ------------------------------------------------------------------

    @Test
    fun `档二分页_正例_SQLServer无排序强制补ORDER BY`() {
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Model(
                SelectStatement(table = table, limit = LimitClause(count = 10)),
            ),
            allDialects,
        )
        val sqlServer = result.rows.first { it.dialectName == "SQL Server" }
        assertTrue(
            "SQL Server 行应报 limitRequiresOrderBy 差异",
            sqlServer.differences.any {
                it.category == MultiDialectRenderPlan.Category.PAGINATION &&
                    it.messageKey == "compat.diff.paginationRequiresOrderBy"
            },
        )
    }

    @Test
    fun `档二分页_正例_分页形态差异随方言出`() {
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Model(
                SelectStatement(table = table, limit = LimitClause(count = 10)),
            ),
            listOf(MySqlDialect, OracleDialect),
            MySqlDialect,
        )
        val oracle = result.rows.first { it.dialectName == "Oracle" }
        val form = oracle.differences.firstOrNull {
            it.category == MultiDialectRenderPlan.Category.PAGINATION &&
                it.messageKey == "compat.diff.paginationForm"
        }
        assertNotNull("Oracle 分页形态与 MySQL 基线不同，应出差异", form)
        assertTrue(form!!.args[1].contains("FETCH FIRST 10 ROWS ONLY"))
        val mysql = result.rows.first { it.dialectName == "MySQL" }
        assertTrue(
            "基线方言自身不得报分页形态差异",
            mysql.differences.none { it.messageKey == "compat.diff.paginationForm" },
        )
    }

    @Test
    fun `档二分页_正例_DELETE_LIMIT在非MySQL族被静默丢弃`() {
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Model(
                DeleteStatement(
                    table = table,
                    conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
                    limit = LimitClause(count = 1),
                ),
            ),
            listOf(MySqlDialect, PostgreSQLDialect),
            MySqlDialect,
        )
        val pg = result.rows.first { it.dialectName == "PostgreSQL" }
        assertTrue(
            "PG 不支持 DELETE LIMIT，应报静默丢弃",
            pg.differences.any { it.messageKey == "compat.diff.deleteLimitDropped" },
        )
        assertTrue("PG 产物确无 LIMIT 尾段", !pg.sql.contains("LIMIT 1"))
    }

    @Test
    fun `档二分页_反例_无分页不报分页差异`() {
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Model(SelectStatement(table = table)),
            allDialects,
        )
        assertTrue(
            "源里无分页，不得报任何分页差异",
            result.rows.none { row ->
                row.differences.any { it.category == MultiDialectRenderPlan.Category.PAGINATION }
            },
        )
    }

    // ------------------------------------------------------------------
    // 档三：函数不存在（正例 + 反例）
    // ------------------------------------------------------------------

    @Test
    fun `档三函数_正例_GROUP_CONCAT在PG与Oracle不可用`() {
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Text("SELECT GROUP_CONCAT(name) FROM t_user"),
            allDialects,
        )
        val pg = result.rows.first { it.dialectName == "PostgreSQL" }
        assertTrue(
            "PG 应报 GROUP_CONCAT 不存在",
            pg.differences.any { it.category == MultiDialectRenderPlan.Category.FUNCTION &&
                it.args.first() == "GROUP_CONCAT" },
        )
        val oracle = result.rows.first { it.dialectName == "Oracle" }
        assertTrue(
            "Oracle 应报 GROUP_CONCAT 不存在",
            oracle.differences.any { it.category == MultiDialectRenderPlan.Category.FUNCTION },
        )
        val mysql = result.rows.first { it.dialectName == "MySQL" }
        assertTrue(
            "MySQL 支持 GROUP_CONCAT，不得报",
            mysql.differences.none { it.category == MultiDialectRenderPlan.Category.FUNCTION },
        )
    }

    @Test
    fun `档三函数_正例_NVL在MySQL不可用`() {
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Text("SELECT NVL(name, 'x') FROM t_user"),
            allDialects,
        )
        val mysql = result.rows.first { it.dialectName == "MySQL" }
        assertTrue(
            "MySQL 应报 NVL 不存在",
            mysql.differences.any { it.category == MultiDialectRenderPlan.Category.FUNCTION &&
                it.args.first() == "NVL" },
        )
        val oracle = result.rows.first { it.dialectName == "Oracle" }
        assertTrue(
            "Oracle 支持 NVL，不得报",
            oracle.differences.none { it.category == MultiDialectRenderPlan.Category.FUNCTION },
        )
    }

    @Test
    fun `档三函数_反例_未收录函数一律不判`() {
        // 目录未收录 MYSTERY_FUNC → 不该凭空报「不存在」（宁可少报，不拿猜测当结论）
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Text("SELECT MYSTERY_FUNC(name) FROM t_user"),
            allDialects,
        )
        assertTrue(
            "未收录函数不得报函数不存在",
            result.rows.none { row ->
                row.differences.any { it.category == MultiDialectRenderPlan.Category.FUNCTION }
            },
        )
    }

    @Test
    fun `档三函数_反例_结构关键字后随括号不算函数`() {
        // COUNT 未收录；IN (...) 的 IN 是结构关键字，不得被当成函数名
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Text("SELECT COUNT(id) FROM t_user WHERE id IN (1, 2)"),
            allDialects,
        )
        assertTrue(
            "结构关键字 IN 不得被当成函数",
            result.rows.none { row ->
                row.differences.any { it.category == MultiDialectRenderPlan.Category.FUNCTION &&
                    it.args.first() == "IN" }
            },
        )
    }

    // ------------------------------------------------------------------
    // 档四：隐式转换（P-6 的可判定子集）
    // ------------------------------------------------------------------

    @Test
    fun `档四隐式转换_正例_数值列与非数值字面量比较`() {
        val t = TableInfo(
            entityClassName = "T",
            tableName = "t_probe",
            columns = listOf(ColumnInfo("id", "id", fieldType = "java.lang.Long")),
        )
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Model(
                SelectStatement(
                    table = t,
                    conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("'abc'", "abc"))),
                ),
            ),
            listOf(MySqlDialect, PostgreSQLDialect),
            MySqlDialect,
        )
        val mysql = result.rows.first { it.dialectName == "MySQL" }
        val hit = mysql.differences.firstOrNull { it.category == MultiDialectRenderPlan.Category.IMPLICIT_CONVERSION }
        assertNotNull("数值列 vs 'abc' 应命中隐式转换", hit)
        assertEquals("id", hit!!.args[1])
        assertEquals("'abc'", hit.args[2])
        assertEquals("MySQL 侧行为=静默转换", "compat.implicit.coerce", hit.args[3])
        val pg = result.rows.first { it.dialectName == "PostgreSQL" }
        val pgHit = pg.differences.first { it.category == MultiDialectRenderPlan.Category.IMPLICIT_CONVERSION }
        assertEquals("PG 侧行为=直接报错", "compat.implicit.error", pgHit.args[3])
    }

    @Test
    fun `档四隐式转换_反例_数值字面量不命中`() {
        val t = TableInfo(
            entityClassName = "T",
            tableName = "t_probe",
            columns = listOf(ColumnInfo("id", "id", fieldType = "java.lang.Long")),
        )
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Model(
                SelectStatement(
                    table = t,
                    conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("'123'", "123"))),
                ),
            ),
            allDialects,
        )
        assertTrue(
            "数值串字面量不算隐式转换",
            result.rows.none { row ->
                row.differences.any { it.category == MultiDialectRenderPlan.Category.IMPLICIT_CONVERSION }
            },
        )
    }

    @Test
    fun `档四隐式转换_反例_无列类型证据时降级不判并如实标注`() {
        val result = MultiDialectRenderPlan.plan(
            MultiDialectRenderPlan.Source.Text("SELECT id FROM t_user WHERE id = 'abc'"),
            allDialects,
        )
        assertTrue(
            "纯文本无列类型，不得凭空判隐式转换",
            result.rows.none { row ->
                row.differences.any { it.category == MultiDialectRenderPlan.Category.IMPLICIT_CONVERSION }
            },
        )
        assertTrue(
            "应如实标注「无列类型证据」",
            result.notes.contains("compat.note.textInputNoTypes"),
        )
    }

    // ------------------------------------------------------------------
    // 目录自身的健全性
    // ------------------------------------------------------------------

    @Test
    fun `函数目录_未收录返回null而已收录返回集合`() {
        assertNull("未收录函数返回 null（不判）", DialectFunctionCatalog.dialectsFor("MYSTERY"))
        val supported = DialectFunctionCatalog.dialectsFor("group_concat")
        assertNotNull("已收录函数（大小写不敏感）返回集合", supported)
        assertTrue(supported!!.contains("MySQL"))
    }

    @Test
    fun `函数目录_登记的方言名必须真实存在于注册表`() {
        val known = DialectRegistry.dialects.map { it.name }.toSet()
        for (function in DialectFunctionCatalog.cataloguedFunctions) {
            val dialects = DialectFunctionCatalog.dialectsFor(function)!!
            assertTrue("函数 $function 登记的方言名必须真实存在：$dialects", known.containsAll(dialects))
        }
    }
}
