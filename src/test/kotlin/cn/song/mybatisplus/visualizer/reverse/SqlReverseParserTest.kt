package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.reverse.ReverseStatementKind
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.SqlValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQL 反向解析器单元测试（P0）：SQL 文本 → [SqlReverseResult]（复用 [Condition] 模型）。
 */
class SqlReverseParserTest {

    // ------------------------------------------------------------------
    // 语句结构
    // ------------------------------------------------------------------

    @Test
    fun `无条件_表名与星号列`() {
        val result = SqlReverseParser.parse("SELECT * FROM user")
        assertEquals("user", result.tableName)
        assertNull(result.selectColumns)
        assertTrue(result.conditions.isEmpty())
        assertTrue(result.orderBy.isEmpty())
        assertNull(result.limit)
    }

    @Test
    fun `简单列清单_保留顺序`() {
        val result = SqlReverseParser.parse("SELECT id, name, age FROM user")
        assertEquals(listOf(SelectColumn("id"), SelectColumn("name"), SelectColumn("age")), result.selectColumns)
    }

    @Test
    fun `函数列_收集原文不放弃`() {
        // T-SqlToJavaPromote G5：聚合/函数列收集原文（含 AS 别名），不再整体放弃 select 生成
        assertEquals(listOf(SelectColumn("COUNT(*)")), SqlReverseParser.parse("SELECT COUNT(*) FROM user").selectColumns)
        assertEquals(
            listOf(SelectColumn("id"), SelectColumn("COUNT(*)")),
            SqlReverseParser.parse("SELECT id, COUNT(*) FROM user").selectColumns,
        )
        assertEquals(
            listOf(SelectColumn("MAX(age)", "maxAge")),
            SqlReverseParser.parse("SELECT MAX(age) AS maxAge FROM user").selectColumns,
        )
    }

    @Test
    fun `反引号与点分_剥壳取末段`() {
        // T-M37：select 列保留原文（多表归属由生成器拆分）
        val result = SqlReverseParser.parse("SELECT `id`, t.name FROM `my_db`.`user`")
        assertEquals("user", result.tableName)
        assertEquals(listOf(SelectColumn("`id`"), SelectColumn("t.name")), result.selectColumns)
    }

    @Test
    fun `反引号列紧跟表别名_不吞列`() {
        // 回归：t.`signal` 先前分词成 WORD(t.) + WORD(`signal`)，反引号列被当别名吞掉 → 只产 t.
        val result = SqlReverseParser.parse("SELECT t.`signal`, id FROM user t")
        assertEquals(listOf(SelectColumn("t.`signal`"), SelectColumn("id")), result.selectColumns)
    }

    @Test
    fun `SELECT列别名_捕获为SelectColumn别名`() {
        // T-M46：AS 别名与省略 AS 的直接别名都捕获 → 生成 selectAs
        val result = SqlReverseParser.parse("SELECT t.name AS productName, t.model productModel, id FROM user t")
        assertEquals(
            listOf(SelectColumn("t.name", "productName"), SelectColumn("t.model", "productModel"), SelectColumn("id")),
            result.selectColumns,
        )
    }

    @Test
    fun `库前缀表名_取末段`() {
        assertEquals("user", SqlReverseParser.parse("SELECT * FROM mydb.user").tableName)
    }

    @Test
    fun `表别名_消费但不影响解析`() {
        val result = SqlReverseParser.parse("SELECT * FROM user u WHERE u.id = 1")
        assertEquals("user", result.tableName)
        assertEquals(1, result.conditions.size)
    }

    @Test
    fun `多行缩进与注释_忽略`() {
        val result = SqlReverseParser.parse(
            """
            -- 查询用户
            SELECT id /* 主键 */, name
            FROM user
            WHERE age > 18
            """.trimIndent(),
        )
        assertEquals(listOf(SelectColumn("id"), SelectColumn("name")), result.selectColumns)
        assertEquals(1, result.conditions.size)
    }

    @Test
    fun `结尾分号_允许`() {
        val result = SqlReverseParser.parse("SELECT * FROM user WHERE id = 1;")
        assertEquals(1, result.conditions.size)
    }

    // ------------------------------------------------------------------
    // WHERE 条件
    // ------------------------------------------------------------------

    @Test
    fun `字符串值_保留引号原文与解转义`() {
        val result = SqlReverseParser.parse("SELECT * FROM user WHERE name = '张三'")
        val c = result.conditions.single() as Condition.Simple
        assertEquals("name", c.column.columnName)
        assertEquals("=", c.op)
        val value = c.value as SqlValue.Literal
        assertEquals("'张三'", value.raw)
        assertEquals("张三", value.unquoted)
    }

    @Test
    fun `字符串双写转义_解转义`() {
        val result = SqlReverseParser.parse("SELECT * FROM user WHERE name = 'O''Brien'")
        val c = result.conditions.single() as Condition.Simple
        assertEquals("O'Brien", (c.value as SqlValue.Literal).unquoted)
    }

    @Test
    fun `数字与布尔值_原样字面量`() {
        val result = SqlReverseParser.parse("SELECT * FROM user WHERE age > 18 AND flag = TRUE AND off = FALSE")
        val conditions = result.conditions
        assertEquals("18", ((conditions[0] as Condition.Simple).value as SqlValue.Literal).raw)
        assertEquals("true", ((conditions[1] as Condition.Simple).value as SqlValue.Literal).raw)
        assertEquals("false", ((conditions[2] as Condition.Simple).value as SqlValue.Literal).raw)
    }
    @Test
    fun `比较操作符全家`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM t WHERE a = 1 AND b != 2 AND c <> 3 AND d > 4 AND e >= 5 AND f < 6 AND g <= 7",
        )
        val ops = result.conditions.map { (it as Condition.Simple).op }
        assertEquals(listOf("=", "!=", "<>", ">", ">=", "<", "<="), ops)
    }

    @Test
    fun `等号NULL_映射IS NULL`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = NULL")
        val c = result.conditions.single() as Condition.Unary
        assertEquals("a", c.column.columnName)
        assertEquals("IS NULL", c.op)
    }

    @Test
    fun `不等号NULL_映射IS NOT NULL`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a != NULL")
        assertEquals("IS NOT NULL", (result.conditions.single() as Condition.Unary).op)
    }

    @Test
    fun `IS NULL与IS NOT NULL`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a IS NULL AND b IS NOT NULL")
        val a = result.conditions[0] as Condition.Unary
        val b = result.conditions[1] as Condition.Unary
        assertEquals("IS NULL", a.op)
        assertEquals("IS NOT NULL", b.op)
    }

    @Test
    fun `IN与NOT IN_多值`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE status IN (1, 2, 3) AND name NOT IN ('a', 'b')")
        val in1 = result.conditions[0] as Condition.In
        assertTrue(!in1.negated)
        assertEquals(listOf("1", "2", "3"), in1.values.map { (it as SqlValue.Literal).raw })
        val in2 = result.conditions[1] as Condition.In
        assertTrue(in2.negated)
        assertEquals(listOf("a", "b"), in2.values.map { (it as SqlValue.Literal).unquoted })
    }

    @Test
    fun `BETWEEN与NOT BETWEEN`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a BETWEEN 1 AND 5 AND b NOT BETWEEN 1 AND 2")
        val between = result.conditions[0] as Condition.Between
        assertTrue(!between.negated)
        assertEquals("1", (between.lo as SqlValue.Literal).raw)
        assertEquals("5", (between.hi as SqlValue.Literal).raw)
        assertTrue((result.conditions[1] as Condition.Between).negated)
    }

    @Test
    fun `LIKE通配符位置_映射LikePosition`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM t WHERE a LIKE '%abc%' AND b LIKE 'abc%' AND c LIKE '%abc'",
        )
        val a = result.conditions[0] as Condition.Like
        assertEquals(LikePosition.ANYWHERE, a.position)
        assertEquals("abc", (a.pattern as SqlValue.Literal).unquoted)
        assertEquals(LikePosition.RIGHT, (result.conditions[1] as Condition.Like).position)
        assertEquals(LikePosition.LEFT, (result.conditions[2] as Condition.Like).position)
    }

    @Test
    fun `NOT LIKE_negated标记`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a NOT LIKE '%abc%'")
        val a = result.conditions.single() as Condition.Like
        assertTrue(a.negated)
        assertEquals(LikePosition.ANYWHERE, a.position)
    }

    @Test
    fun `LIKE无通配符_降级Raw并记warning`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a LIKE 'abc'")
        val raw = result.conditions.single() as Condition.Raw
        assertEquals("a LIKE 'abc'", raw.sqlText)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `LIKE中间百分号或下划线_降级Raw`() {
        assertTrue(SqlReverseParser.parse("SELECT * FROM t WHERE a LIKE 'a%b'").conditions.single() is Condition.Raw)
        assertTrue(SqlReverseParser.parse("SELECT * FROM t WHERE a LIKE 'a_b'").conditions.single() is Condition.Raw)
        assertTrue(SqlReverseParser.parse("SELECT * FROM t WHERE a LIKE 'ab'").conditions.single() is Condition.Raw)
    }

    @Test
    fun `别名列_剥离前缀`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE u.a = 1 AND `v`.`b` = 2")
        assertEquals("a", (result.conditions[0] as Condition.Simple).column.columnName)
        assertEquals("b", (result.conditions[1] as Condition.Simple).column.columnName)
    }

    @Test
    fun `顶层AND连接_顺序保留`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = 1 AND b = 2 AND c = 3")
        assertEquals(3, result.conditions.size)
        assertEquals(listOf("a", "b", "c"), result.conditions.map { (it as Condition.Simple).column.columnName })
    }

    // ------------------------------------------------------------------
    // ORDER BY / LIMIT
    // ------------------------------------------------------------------

    @Test
    fun `ORDER BY_默认ASC与显式DESC`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = 1 ORDER BY id DESC, name")
        assertEquals(2, result.orderBy.size)
        assertTrue(!result.orderBy[0].asc)
        assertEquals("id", result.orderBy[0].column.columnName)
        assertTrue(result.orderBy[1].asc)
    }

    @Test
    fun `LIMIT_单值与偏移形态`() {
        val result = SqlReverseParser.parse("SELECT * FROM t LIMIT 10")
        assertEquals("10", (result.limit?.count as SqlValue.Literal).raw)
        assertNull(result.limit?.offset)

        val paged = SqlReverseParser.parse("SELECT * FROM t LIMIT 5, 10")
        assertEquals("5", (paged.limit?.offset as SqlValue.Literal).raw)
        assertEquals("10", (paged.limit?.count as SqlValue.Literal).raw)
    }

    @Test
    fun `LIMIT_方言形态_宽容解析`() {
        // T-M43 输入侧方言宽容：PG 系 `LIMIT count OFFSET offset`
        val pg = SqlReverseParser.parse("SELECT * FROM t LIMIT 10 OFFSET 5")
        assertEquals("5", (pg.limit?.offset as SqlValue.Literal).raw)
        assertEquals("10", (pg.limit?.count as SqlValue.Literal).raw)

        // SQL Server / Oracle 12c 系 `OFFSET n ROWS FETCH NEXT m ROWS ONLY`
        val offsetFetch = SqlReverseParser.parse("SELECT * FROM t OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY")
        assertEquals("5", (offsetFetch.limit?.offset as SqlValue.Literal).raw)
        assertEquals("10", (offsetFetch.limit?.count as SqlValue.Literal).raw)

        // Oracle 无 offset 形态 `FETCH FIRST n ROWS ONLY`（FETCH NEXT 亦接受）
        val fetchFirst = SqlReverseParser.parse("SELECT * FROM t FETCH FIRST 10 ROWS ONLY")
        assertNull(fetchFirst.limit?.offset)
        assertEquals("10", (fetchFirst.limit?.count as SqlValue.Literal).raw)
        val fetchNext = SqlReverseParser.parse("SELECT * FROM t FETCH NEXT 10 ROWS ONLY")
        assertNull(fetchNext.limit?.offset)
        assertEquals("10", (fetchNext.limit?.count as SqlValue.Literal).raw)

        // WHERE 之后的分页同样识别（OFFSET 作子句边界不吞进条件）
        val withWhere = SqlReverseParser.parse("SELECT * FROM t WHERE a = 1 OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY")
        assertEquals(1, withWhere.conditions.size)
        assertEquals("5", (withWhere.limit?.offset as SqlValue.Literal).raw)
        assertEquals("10", (withWhere.limit?.count as SqlValue.Literal).raw)

        // 列名 offset / fetch 不误判为分页子句
        val columnOffset = SqlReverseParser.parse("SELECT * FROM t WHERE offset = 1")
        assertEquals("offset", (columnOffset.conditions.single() as Condition.Simple).column.columnName)
        val columnFetch = SqlReverseParser.parse("SELECT * FROM t WHERE fetch = 1")
        assertEquals("fetch", (columnFetch.conditions.single() as Condition.Simple).column.columnName)
        // 列名 offset 参与表达式降级（LIKE 无通配符）同样不吞分页后续
        val likeOffset = SqlReverseParser.parse("SELECT * FROM t WHERE offset LIKE 'x'")
        assertEquals("offset LIKE 'x'", (likeOffset.conditions.single() as Condition.Raw).sqlText)
    }

    @Test
    fun `LIMIT_方言形态_占位符`() {
        // T-M43：方言形态 + `?` 占位符（日志分页参数场景）
        val pg = SqlReverseParser.parse("SELECT * FROM t LIMIT ? OFFSET ?")
        assertTrue(pg.limit?.offset is SqlValue.Placeholder)
        assertTrue(pg.limit?.count is SqlValue.Placeholder)

        val offsetFetch = SqlReverseParser.parse("SELECT * FROM t OFFSET ? ROWS FETCH NEXT ? ROWS ONLY")
        assertTrue(offsetFetch.limit?.offset is SqlValue.Placeholder)
        assertTrue(offsetFetch.limit?.count is SqlValue.Placeholder)

        val fetchFirst = SqlReverseParser.parse("SELECT * FROM t FETCH FIRST ? ROWS ONLY")
        assertNull(fetchFirst.limit?.offset)
        assertTrue(fetchFirst.limit?.count is SqlValue.Placeholder)
    }

    // ------------------------------------------------------------------
    // 错误场景（P1+ 范围明确报错）
    // ------------------------------------------------------------------

    @Test
    fun `非CRUD语句_报错`() {
        val ex = assertThrows { SqlReverseParser.parse("CREATE TABLE t (id INT)") }
        assertEquals(SqlReverseParser.KEY_NOT_SELECT, ex.key)
    }

    @Test
    fun `顶层OR_还原为Group`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = 1 OR b = 2")
        val group = result.conditions.single() as Condition.Group
        assertEquals(Connector.OR, group.connector)
        assertEquals(2, group.children.size)
        assertEquals("a", (group.children[0] as Condition.Simple).column.columnName)
        assertEquals("b", (group.children[1] as Condition.Simple).column.columnName)
    }

    @Test
    fun `混合优先级_AND段包Group`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = 1 AND b = 2 OR c = 3")
        val group = result.conditions.single() as Condition.Group
        assertEquals(Connector.OR, group.connector)
        val andGroup = group.children[0] as Condition.Group
        assertEquals(Connector.AND, andGroup.connector)
        assertEquals(2, andGroup.children.size)
        assertEquals("c", (group.children[1] as Condition.Simple).column.columnName)
    }

    @Test
    fun `括号组_还原Group保留括号`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE (a = 1 AND b = 2) AND c = 3")
        assertEquals(2, result.conditions.size)
        val group = result.conditions[0] as Condition.Group
        assertEquals(Connector.AND, group.connector)
        assertEquals(2, group.children.size)
        assertEquals("c", (result.conditions[1] as Condition.Simple).column.columnName)
    }

    @Test
    fun `括号组内OR_单组直接复用`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE (a = 1 OR b = 2) AND c = 3")
        val group = result.conditions[0] as Condition.Group
        assertEquals(Connector.OR, group.connector)
        assertEquals(2, group.children.size)
    }

    @Test
    fun `括号组内单条件_解包不产生Group`() {
        // T-ReverseParenWira：MPJ 渲染自带括号（ON (x=y)、WHERE (x=1)），单条件括号
        // 无语义增量 → 解包。否则 ON 侧 extractJoinKey 认不出列对致 MPJ lambda 退化，
        // WHERE 侧多渲染一层 nested
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE (t.id = 1)")
        assertEquals(1, result.conditions.size)
        assertTrue(result.conditions[0] is Condition.Simple)

        val on = SqlReverseParser.parse("SELECT * FROM a t LEFT JOIN b st ON (st.station_id = t.id)")
        val onConditions = on.joins.single().onConditions
        assertEquals(1, onConditions.size)
        assertTrue(onConditions[0] is Condition.ColumnComparison)
    }

    @Test
    fun `NOT单条件括号_保留negated组`() {
        // 解包例外：NOT (a = 1) 的 negated 标记须由 Group 承载
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE NOT (a = 1)")
        val group = result.conditions.single() as Condition.Group
        assertTrue(group.negated)
        assertEquals(1, group.children.size)
    }

    @Test
    fun `NOT括号组_negated标记`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE NOT (a = 1 OR b = 2)")
        val group = result.conditions.single() as Condition.Group
        assertTrue(group.negated)
        assertEquals(Connector.OR, group.connector)
    }

    @Test
    fun `嵌套组_递归还原`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM t WHERE (a = 1 OR b = 2) AND (c = 3 OR (d = 4 AND e = 5))",
        )
        assertEquals(2, result.conditions.size)
        val outer = result.conditions[1] as Condition.Group
        assertEquals(Connector.OR, outer.connector)
        val inner = outer.children[1] as Condition.Group
        assertEquals(Connector.AND, inner.connector)
        assertEquals(2, inner.children.size)
    }

    @Test
    fun `DISTINCT_建模且收集列`() {
        // T-SqlToJavaPromote G3：DISTINCT 建模 distinct=true，列正常收集（不再放弃 select 生成）
        val result = SqlReverseParser.parse("SELECT DISTINCT id, name FROM t WHERE a = 1")
        assertTrue(result.distinct)
        assertEquals(listOf(SelectColumn("id"), SelectColumn("name")), result.selectColumns)
    }

    // ------------------------------------------------------------------
    // 降级透传（T-M36.1：表达式形态 → apply() 原文）
    // ------------------------------------------------------------------

    @Test
    fun `函数条件_降级Raw透传`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE DATE_FORMAT(create_time, '%Y') = '2024'")
        val raw = result.conditions.single() as Condition.Raw
        assertEquals("DATE_FORMAT(create_time, '%Y') = '2024'", raw.sqlText)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `右侧函数_降级Raw透传`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = DATE_FORMAT(now(), '%Y')")
        assertTrue(result.conditions.single() is Condition.Raw)
    }

    @Test
    fun `列对列_解析为ColumnComparison`() {
        // T-M37：`a = b` 从降级 Raw 改为结构化列对列（WHERE/ON 均适用）
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = b")
        val cc = result.conditions.single() as Condition.ColumnComparison
        assertEquals("a", cc.left.columnName)
        assertEquals("b", cc.right.columnName)
    }

    @Test
    fun `右侧函数_仍降级Raw透传`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = DATE_FORMAT(now(), '%Y')")
        assertTrue(result.conditions.single() is Condition.Raw)
    }

    // ------------------------------------------------------------------
    // 多表 JOIN（T-M37）
    // ------------------------------------------------------------------

    @Test
    fun `LEFT JOIN_单等值ON解析为ColumnComparison`() {
        val result = SqlReverseParser.parse(
            "SELECT t.id, t1.city FROM user t LEFT JOIN address t1 ON t1.user_id = t.id WHERE t.status = 1",
        )
        assertEquals(1, result.joins.size)
        val join = result.joins[0]
        assertEquals("address", join.tableName)
        assertEquals("t1", join.alias)
        val on = join.onConditions.single() as Condition.ColumnComparison
        assertEquals("user_id", on.left.columnName)
        assertEquals("t1", on.left.tableAlias)
        assertEquals("id", on.right.columnName)
        assertEquals("t", on.right.tableAlias)
        // WHERE 条件列保留别名
        val cond = result.conditions.single() as Condition.Simple
        assertEquals("t", cond.column.tableAlias)
        // SELECT 列保留原文
        assertEquals(listOf(SelectColumn("t.id"), SelectColumn("t1.city")), result.selectColumns)
        assertNotNull(join.rawOn)
        assertNotNull(join.rawOn)
        assertTrue(join.rawOn!!.contains("t1.user_id = t.id"))
    }

    @Test
    fun `多JOIN链与ON多条件`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM a t LEFT JOIN b t1 ON t1.a_id = t.id AND t1.type = 1 INNER JOIN c t2 ON t2.b_id = t1.id",
        )
        assertEquals(2, result.joins.size)
        assertEquals("LEFT", result.joins[0].type.name)
        assertEquals(2, result.joins[0].onConditions.size)
        assertTrue(result.joins[0].onConditions[0] is Condition.ColumnComparison)
        assertTrue(result.joins[0].onConditions[1] is Condition.Simple)
        assertEquals("INNER", result.joins[1].type.name)
        assertEquals("t2", result.joins[1].alias)
    }

    @Test
    fun `JOIN无别名_不归属副实体`() {
        val result = SqlReverseParser.parse("SELECT * FROM a LEFT JOIN b ON b.a_id = a.id")
        assertEquals(1, result.joins.size)
        assertNull(result.joins[0].alias)
    }

    @Test
    fun `RIGHT与FULL JOIN_类型解析`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM a RIGHT JOIN b ON b.a_id = a.id FULL OUTER JOIN c ON c.b_id = b.id",
        )
        assertEquals("RIGHT", result.joins[0].type.name)
        assertEquals("FULL", result.joins[1].type.name)
    }

    @Test
    fun `子查询_降级Raw透传`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a IN (SELECT id FROM u)")
        val raw = result.conditions.single() as Condition.Raw
        assertEquals("a IN (SELECT id FROM u)", raw.sqlText)
    }

    @Test
    fun `参数占位符_建模Placeholder`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = ?")
        val simple = result.conditions.single() as Condition.Simple
        assertEquals("=", simple.op)
        assertTrue(simple.value is SqlValue.Placeholder)
        assertEquals(0, result.warnings.size)
    }

    @Test
    fun `参数占位符_各比较算子`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a != ? AND b > ? AND c >= ? AND d < ? AND e <= ? AND f <> ?")
        val ops = listOf("!=", ">", ">=", "<", "<=", "<>")
        assertEquals(6, result.conditions.size)
        result.conditions.forEachIndexed { i, c ->
            val simple = c as Condition.Simple
            assertEquals(ops[i], simple.op)
            assertTrue("第 $i 个条件应为占位符", simple.value is SqlValue.Placeholder)
        }
        assertEquals(0, result.warnings.size)
    }

    @Test
    fun `参数占位符_IN与BETWEEN`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a IN (?, ?) AND b BETWEEN ? AND ?")
        val inCond = result.conditions[0] as Condition.In
        assertTrue(inCond.values.all { it is SqlValue.Placeholder })
        val between = result.conditions[1] as Condition.Between
        assertTrue(between.lo is SqlValue.Placeholder)
        assertTrue(between.hi is SqlValue.Placeholder)
    }

    @Test
    fun `参数占位符_LIKE映射NONE`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a LIKE ?")
        val like = result.conditions.single() as Condition.Like
        assertEquals(LikePosition.NONE, like.position)
        assertTrue(like.pattern is SqlValue.Placeholder)
        val negated = SqlReverseParser.parse("SELECT * FROM t WHERE a NOT LIKE ?")
        assertTrue((negated.conditions.single() as Condition.Like).negated)
    }

    @Test
    fun `参数占位符_UPDATE_SET值`() {
        val result = SqlReverseParser.parse("UPDATE t SET a = ? WHERE b = ?")
        assertEquals(ReverseStatementKind.UPDATE, result.kind)
        assertTrue(result.sets.single().value is SqlValue.Placeholder)
        assertTrue((result.conditions.single() as Condition.Simple).value is SqlValue.Placeholder)
    }

    @Test
    fun `参数占位符_INSERT多行值`() {
        val result = SqlReverseParser.parse("INSERT INTO t (a, b) VALUES (?, ?), (?, ?)")
        assertEquals(ReverseStatementKind.INSERT, result.kind)
        assertEquals(2, result.insertValues.size)
        result.insertValues.forEach { row ->
            assertEquals(2, row.size)
            assertTrue(row.all { it is SqlValue.Placeholder })
        }
    }

    @Test
    fun `参数占位符_LIMIT偏移形态`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = ? LIMIT ?, ?")
        assertTrue(result.limit?.offset is SqlValue.Placeholder)
        assertTrue(result.limit?.count is SqlValue.Placeholder)
        val single = SqlReverseParser.parse("SELECT * FROM t LIMIT ?")
        assertNull(single.limit?.offset)
        assertTrue(single.limit?.count is SqlValue.Placeholder)
    }

    @Test
    fun `参数占位符与函数降级_混合边界`() {
        val result = SqlReverseParser.parse("SELECT * FROM t WHERE a = ? AND DATE_FORMAT(create_time, '%Y') = '2024'")
        assertEquals(2, result.conditions.size)
        assertTrue((result.conditions[0] as Condition.Simple).value is SqlValue.Placeholder)
        assertTrue(result.conditions[1] is Condition.Raw)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `降级条件与普通条件混合_边界正确`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM t WHERE DATE_FORMAT(create_time, '%Y') = '2024' AND a = 1 OR b = 2",
        )
        // 降级条件结束于 AND；剩余 a = 1 OR b = 2 → 段1=[Raw, a] 包 Group(AND)、段2=[b]
        val group = result.conditions.single() as Condition.Group
        assertEquals(Connector.OR, group.connector)
        val andGroup = group.children[0] as Condition.Group
        assertTrue(andGroup.children[0] is Condition.Raw)
        assertEquals("a", (andGroup.children[1] as Condition.Simple).column.columnName)
        assertEquals("b", (group.children[1] as Condition.Simple).column.columnName)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `GROUP BY与HAVING_建模`() {
        // T-SqlToJavaPromote G1/G2：不再报错——GROUP BY 建模 groupBy 列表；HAVING 聚合表达式降级 Raw
        val g = SqlReverseParser.parse("SELECT * FROM t GROUP BY a, b")
        assertEquals(listOf("a", "b"), g.groupBy.map { it.columnName })
        val h = SqlReverseParser.parse("SELECT * FROM t GROUP BY a HAVING COUNT(*) > 1")
        assertEquals(listOf("a"), h.groupBy.map { it.columnName })
        assertEquals("COUNT(*) > 1", (h.having as Condition.Raw).sqlText)
    }

    @Test
    fun `UPDATE含JOIN_报错`() {
        // T-M37：仅 SELECT/DELETE 支持 JOIN；UPDATE 联表保持报错不变
        assertThrows { SqlReverseParser.parse("UPDATE t SET a = 1 LEFT JOIN u ON t.id = u.tid") }
    }

    @Test
    fun `多条语句_报错`() {
        val ex = assertThrows { SqlReverseParser.parse("SELECT * FROM t; SELECT * FROM u") }
        assertTrue(ex.detail.contains("多条"))
    }

    @Test
    fun `缺少FROM_报错`() {
        assertThrows { SqlReverseParser.parse("SELECT 1") }
    }

    @Test
    fun `空文本与未闭合字符串_报错`() {
        assertThrows { SqlReverseParser.parse("   ") }
        val ex = assertThrows { SqlReverseParser.parse("SELECT * FROM t WHERE a = 'abc") }
        assertEquals(SqlReverseParser.KEY_PARSE, ex.key)
    }

    // ------------------------------------------------------------------
    // 增删改查（T-M36.7）
    // ------------------------------------------------------------------

    @Test
    fun `UPDATE_解析SET与条件`() {
        val result = SqlReverseParser.parse("UPDATE user SET name = '李四', age = 20 WHERE id = 1")
        assertEquals(ReverseStatementKind.UPDATE, result.kind)
        assertEquals("user", result.tableName)
        assertEquals(2, result.sets.size)
        val set1 = result.sets[0]
        assertEquals("name", set1.column)
        assertEquals("李四", (set1.value as SqlValue.Literal).unquoted)
        assertEquals("age", result.sets[1].column)
        val cond = result.conditions.single() as Condition.Simple
        assertEquals("id", cond.column.columnName)
        assertEquals("1", (cond.value as SqlValue.Literal).raw)
    }

    @Test
    fun `UPDATE_SET函数值_降级rawSql透传`() {
        val result = SqlReverseParser.parse("UPDATE user SET update_time = NOW(), name = 'x' WHERE id = 1")
        assertEquals(2, result.sets.size)
        val raw = result.sets[0]
        assertEquals("update_time", raw.column)
        assertTrue(raw.rawSql!!.contains("update_time = NOW()"))
        assertEquals("name", result.sets[1].column)
    }

    @Test
    fun `UPDATE_SET NULL_值解析为null`() {
        val result = SqlReverseParser.parse("UPDATE user SET deleted = NULL WHERE id = 1")
        assertNull(result.sets[0].value)
    }

    @Test
    fun `DELETE_解析条件`() {
        val result = SqlReverseParser.parse("DELETE FROM user WHERE id = 1")
        assertEquals(ReverseStatementKind.DELETE, result.kind)
        assertEquals("user", result.tableName)
        assertEquals(1, result.conditions.size)
        assertTrue(result.deleteTargets.isEmpty())
    }

    @Test
    fun `DELETE_多表目标_解析目标清单与JOIN`() {
        val result = SqlReverseParser.parse(
            "DELETE t, t1 FROM user t LEFT JOIN address t1 ON t1.user_id = t.id WHERE t.status = 1",
        )
        assertEquals(ReverseStatementKind.DELETE, result.kind)
        assertEquals("user", result.tableName)
        assertEquals(listOf("t", "t1"), result.deleteTargets)
        assertEquals(1, result.joins.size)
        assertEquals("t1", result.joins[0].alias)
        val cond = result.conditions.single() as Condition.Simple
        assertEquals("t", cond.column.tableAlias)
    }

    @Test
    fun `DELETE_多表目标_无JOIN`() {
        val result = SqlReverseParser.parse("DELETE t FROM user t WHERE t.id = 1")
        assertEquals(listOf("t"), result.deleteTargets)
        assertTrue(result.joins.isEmpty())
        assertEquals(1, result.conditions.size)
    }

    @Test
    fun `DELETE_多表目标_反引号剥壳`() {
        val result = SqlReverseParser.parse(
            "DELETE `t`, `t1` FROM user t LEFT JOIN address t1 ON t1.user_id = t.id",
        )
        assertEquals(listOf("t", "t1"), result.deleteTargets)
    }

    @Test
    fun `DELETE_多表目标_缺少FROM_报错`() {
        val ex = assertThrows { SqlReverseParser.parse("DELETE t, t1 WHERE id = 1") }
        assertEquals(SqlReverseParser.KEY_PARSE, ex.key)
    }

    @Test
    fun `INSERT_解析列与值`() {
        val result = SqlReverseParser.parse("INSERT INTO user (name, age) VALUES ('张三', 18)")
        assertEquals(ReverseStatementKind.INSERT, result.kind)
        assertEquals(listOf("name", "age"), result.insertColumns)
        assertEquals(1, result.insertValues.size)
        assertEquals("张三", (result.insertValues[0][0] as SqlValue.Literal).unquoted)
        assertEquals("18", (result.insertValues[0][1] as SqlValue.Literal).raw)
    }

    @Test
    fun `INSERT_多行VALUES_解析为行列表`() {
        val result = SqlReverseParser.parse("INSERT INTO user (name, age) VALUES ('张三', 18), ('李四', 20)")
        assertEquals(2, result.insertValues.size)
        assertEquals(2, result.insertValues[0].size)
        assertEquals("张三", (result.insertValues[0][0] as SqlValue.Literal).unquoted)
        assertEquals("18", (result.insertValues[0][1] as SqlValue.Literal).raw)
        assertEquals("李四", (result.insertValues[1][0] as SqlValue.Literal).unquoted)
        assertEquals("20", (result.insertValues[1][1] as SqlValue.Literal).raw)
    }

    @Test
    fun `INSERT_多行VALUES_列数不齐_报错`() {
        val ex = assertThrows { SqlReverseParser.parse("INSERT INTO user (name, age) VALUES ('张三', 18), ('李四')") }
        assertEquals(SqlReverseParser.KEY_PARSE, ex.key)
        assertTrue(ex.detail.contains("列数"))
    }

    @Test
    fun `INSERT_列值数量不一致_报错`() {
        assertThrows { SqlReverseParser.parse("INSERT INTO user (name, age) VALUES ('张三')") }
    }

    @Test
    fun `UPDATE缺少SET_报错`() {
        assertThrows { SqlReverseParser.parse("UPDATE user WHERE id = 1") }
    }

    // ------------------------------------------------------------------
    // 子查询 JOIN / FROM 子查询
    // ------------------------------------------------------------------

    @Test
    fun `子查询JOIN_解析为rawJoin`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM t LEFT JOIN (SELECT id FROM u WHERE u.type = 1) sub ON sub.id = t.id",
        )
        assertEquals(1, result.joins.size)
        val join = result.joins[0]
        assertTrue("tableName 应包含子查询文本", join.tableName.contains("(SELECT"))
        assertNull("子查询 JOIN 的 alias 应为 null", join.alias)
        assertNotNull(join.rawOn)
        assertTrue(join.rawOn!!.contains("sub.id = t.id"))
    }

    @Test
    fun `子查询JOIN_嵌套括号`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM t LEFT JOIN (SELECT * FROM (SELECT id FROM v) x) sub ON sub.id = t.id",
        )
        assertEquals(1, result.joins.size)
        val join = result.joins[0]
        assertTrue("应正确解析嵌套括号", join.tableName.contains("(SELECT"))
        assertNull(join.alias)
    }

    @Test
    fun `子查询JOIN_无别名`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM t LEFT JOIN (SELECT id FROM u) ON u.id = t.id",
        )
        assertEquals(1, result.joins.size)
        val join = result.joins[0]
        assertTrue("tableName 应包含子查询文本", join.tableName.contains("(SELECT"))
        assertNull(join.alias)
    }

    @Test
    fun `FROM子查询_解析`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM (SELECT id, name FROM user WHERE status = 1) t LEFT JOIN addr ON addr.user_id = t.id",
        )
        assertTrue("FROM 子查询 tableName 应包含子查询文本", result.tableName.contains("(SELECT"))
        assertEquals(1, result.joins.size)
        assertEquals("addr", result.joins[0].tableName)
    }

    @Test
    fun `子查询JOIN_多表链`() {
        val result = SqlReverseParser.parse(
            "SELECT t.id, t.station_id, t.share_scheme_id, t.device_name\n" +
                "FROM car_device_info t\n" +
                "LEFT JOIN station_info stationInfoEntity ON stationInfoEntity.id = t.station_id\n" +
                "    AND stationInfoEntity.station_name = '中心站'\n" +
                "LEFT JOIN (\n" +
                "    SELECT tt.id, tt.station_id, tt.station_name, tt.merchant_id\n" +
                "    FROM station_info tt\n" +
                "    WHERE tt.merchant_id >= 0\n" +
                ") tt ON tt.station_id = t.station_id\n" +
                "WHERE t.id = 1",
        )
        assertEquals(2, result.joins.size)
        // 第一个 JOIN 是普通 JOIN
        assertEquals("station_info", result.joins[0].tableName)
        assertEquals("stationInfoEntity", result.joins[0].alias)
        // 第二个 JOIN 是子查询 JOIN
        val secondTableName = result.joins[1].tableName
        assertTrue("第二个 JOIN 应为子查询, 实际: $secondTableName", secondTableName.contains("(") && secondTableName.contains("SELECT"))
        assertNull(result.joins[1].alias)
        // WHERE 条件正常
        assertEquals(1, result.conditions.size)
    }

    @Test
    fun `子查询JOIN_紧跟WHERE`() {
        val result = SqlReverseParser.parse(
            "SELECT * FROM t LEFT JOIN (SELECT id FROM u) sub ON sub.id = t.id WHERE t.status = 1",
        )
        assertEquals(1, result.joins.size)
        assertEquals(1, result.conditions.size)
        val cond = result.conditions.single() as Condition.Simple
        assertEquals("status", cond.column.columnName)
    }

    // ------------------------------------------------------------------
    // MConsumer 子查询结构解析
    // ------------------------------------------------------------------

    @Test
    fun `子查询JOIN_解析MConsumer结构`() {
        val result = SqlReverseParser.parse(
            "SELECT t.id FROM t LEFT JOIN (SELECT tt.id, tt.station_id FROM station_info tt WHERE tt.merchant_id >= 0) tt ON tt.station_id = t.station_id",
        )
        assertEquals(1, result.joins.size)
        val join = result.joins[0]
        assertNotNull("子查询结构应被解析", join.subQuery)
        val subQuery = join.subQuery!!
        assertEquals("station_info", subQuery.tableName)
        assertEquals("tt", subQuery.alias)
        assertFalse("显式列 → selectAll=false", subQuery.selectAll)
        assertEquals(listOf("id", "station_id"), subQuery.selectColumns)
        assertEquals(1, subQuery.whereConditions.size)
    }

    @Test
    fun `子查询JOIN_selectAll`() {
        val result = SqlReverseParser.parse(
            "SELECT t.id FROM t LEFT JOIN (SELECT * FROM station_info tt WHERE tt.merchant_id >= 0) tt ON tt.station_id = t.station_id",
        )
        val join = result.joins[0]
        assertNotNull(join.subQuery)
        assertTrue("SELECT * → selectAll=true", join.subQuery!!.selectAll)
    }

    @Test
    fun `子查询JOIN_含嵌套子查询_回落null`() {
        val result = SqlReverseParser.parse(
            "SELECT t.id FROM t LEFT JOIN (SELECT * FROM (SELECT id FROM u) x) tt ON tt.id = t.id",
        )
        val join = result.joins[0]
        // 嵌套子查询 → parseSubqueryStructure 失败 → subQuery 为 null
        assertNull("嵌套子查询应回落 null", join.subQuery)
    }

    // ------------------------------------------------------------------
    // 递归深度守卫（T-SAF-18：sql-reverse-parser-unbounded-recursion）
    // ------------------------------------------------------------------

    @Test
    fun `深度括号嵌套_结构化拒绝而非栈溢出`() {
        // 修复前：20000 层括号驱动条件递归环 parseConditionInternal→parseGroup→
        // parseConditionList→parseAndSegment→parseCondition（每层 4-5 帧）打爆线程栈，
        // StackOverflowError 逃出 SqlReverseException 通道直达 sqlv_reverse_sql 调用方
        // （MCP 审计边界按 VirtualMachineError 致命透传）；
        // 修复后：深度守卫（上限 256）超限抛 SqlReverseException，结构化拒绝
        val sql = "SELECT * FROM t WHERE " + "(".repeat(20000) + "a = 1" + ")".repeat(20000)
        val e = assertThrows { SqlReverseParser.parse(sql) }
        assertEquals(SqlReverseParser.KEY_PARSE, e.key)
        assertTrue("错误应提示嵌套过深：${e.detail}", e.detail.contains("嵌套过深"))
    }

    @Test
    fun `深度FROM子查询嵌套_有界处理而非栈溢出`() {
        // 深度取 500（> 256 上限）：20000 层会在 token 化阶段先 OOM（每层重复分配，守卫来不及
        // 触发），500 层既有足够深度越过守卫、又远离 token 化的内存放大区。
        // 修复前：深层 FROM 子查询驱动 parseSubqueryTable→parseSubqueryStructure→parse
        // 递归环打爆线程栈（StackOverflowError）。修复后深度守卫（上限 256）超限抛
        // SqlReverseException，该异常在既有子查询回落通道（parseSubqueryStructure
        // catch → null 字符串形态）被收编——结局要么整体正常返回（深层子查询降级原文承载）、
        // 要么结构化拒绝，两者皆有界；唯独不允许 StackOverflowError 逃逸
        val n = 500
        val sql = "SELECT * FROM (".repeat(n) + "SELECT id FROM t" + ")".repeat(n)
        try {
            SqlReverseParser.parse(sql)
        } catch (e: SqlReverseException) {
            // 结构化拒绝：有界结局，接受
        }
    }

    @Test
    fun `现实嵌套深度_守卫不误杀`() {
        // 防误杀回归：20 层括号 / 10 层 FROM 子查询（现实上限远高于此）照常解析
        val parens = "(".repeat(20) + "a = 1" + ")".repeat(20)
        val r1 = SqlReverseParser.parse("SELECT * FROM t WHERE $parens")
        assertEquals(1, r1.conditions.size)
        val sub = "SELECT * FROM (".repeat(10) + "SELECT id FROM u" + ")".repeat(10)
        val r2 = SqlReverseParser.parse(sub)
        // 深层子查询无法建简单单表结构 → 回落表名原文承载，但解析本身不报错
        assertTrue("应正常解析：${r2.tableName}", r2.tableName.contains("SELECT id FROM u"))
    }

    private fun assertThrows(block: () -> Unit): SqlReverseException =
        try {
            block()
            throw AssertionError("expected SqlReverseException")
        } catch (e: SqlReverseException) {
            e
        }
}
