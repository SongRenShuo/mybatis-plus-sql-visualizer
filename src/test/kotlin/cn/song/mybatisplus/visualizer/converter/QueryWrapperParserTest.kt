package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QueryWrapperParser 单元测试（M1-03）：验证调用链 → SELECT 语句模型。
 */
class QueryWrapperParserTest {

    private val table = TableInfo(
        entityClassName = "DeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("stationId", "station_id"),
            ColumnInfo("shareSchemeId", "share_scheme_id"),
            ColumnInfo("name", "name"),
        ),
    )

    private val parser = QueryWrapperParser(table)

    /** 便捷构造：eq(column, value)。 */
    private fun eqCall(column: String, value: String) = WrapperCall(
        "eq",
        listOf(WrapperArg.ColumnArg(ColumnRef(columnName = column)), WrapperArg.ValueArg(SqlValue.Literal(value))),
    )

    // ------------------------------------------------------------------
    // 布尔条件重载 / 无参 or() / apply（清单 P02/P10/P14）
    // ------------------------------------------------------------------

    @Test
    fun `布尔条件重载_字面量true保留_false忽略`() {
        // P02：eq(true, col, val) 保留；eq(false, col, val) 整体忽略
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("1")),
                )),
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("false")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("2")),
                )),
                WrapperCall("like", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
                    WrapperArg.ValueArg(SqlValue.Literal("abc")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE id = 1
                AND name LIKE '%abc%'
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `无参or分隔符`() {
        // P14：eq(a).or().eq(b) → (a OR b)
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                eqCall("id", "1"),
                WrapperCall("or", emptyList()),
                eqCall("id", "2"),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(1, statement.conditions.size)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE (id = 1 OR id = 2)
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `apply参数化片段`() {
        // P10：apply("1 = 0") 原样透传；apply(condition, "(rel.a, rel.id) > ({0}, {1})", a, b) 参数替换
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("apply", listOf(WrapperArg.ValueArg(SqlValue.Literal("1 = 0")))),
                WrapperCall("apply", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    WrapperArg.ValueArg(SqlValue.Literal("(rel.apply_id, rel.id) > ({0}, {1})")),
                    WrapperArg.ValueArg(SqlValue.Literal("100")),
                    WrapperArg.ValueArg(SqlValue.Literal("0")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE 1 = 0
                AND (rel.apply_id, rel.id) > (100, 0)
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `apply字面量false忽略`() {
        // P10：apply(false, "sql", ...) 条件禁用
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("apply", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("false")),
                    WrapperArg.ValueArg(SqlValue.Literal("1 = 0")),
                )),
                eqCall("id", "1"),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE id = 1
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `空调用链_生成全列查询`() {
        val chain = WrapperChain(WrapperType.LAMBDA_QUERY, "DeviceInfoEntity", emptyList())
        val statement = parser.parse(chain)
        assertTrue(statement.selectColumns.isEmpty())
        assertTrue(statement.conditions.isEmpty())
        assertEquals("car_device_info", statement.table.tableName)
    }

    @Test
    fun `条件解析_比较与空值`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                eqCall("id", "1001"),
                WrapperCall("gt", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")), WrapperArg.ValueArg(SqlValue.Literal("0")))),
                WrapperCall("isNotNull", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "share_scheme_id")))),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(3, statement.conditions.size)
        val first = statement.conditions[0] as Condition.Simple
        assertEquals("id", first.column.columnName)
        assertEquals("=", first.op)
        assertEquals("1001", (first.value as SqlValue.Literal).raw)
        val third = statement.conditions[2] as Condition.Unary
        assertEquals("IS NOT NULL", third.op)
    }

    @Test
    fun `范围解析_in与between`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                WrapperCall(
                    "in",
                    listOf(
                        WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                        WrapperArg.ValueArg(SqlValue.Literal("1")),
                        WrapperArg.ValueArg(SqlValue.Literal("2")),
                    ),
                ),
                WrapperCall(
                    "between",
                    listOf(
                        WrapperArg.ColumnArg(ColumnRef(columnName = "sort")),
                        WrapperArg.ValueArg(SqlValue.Literal("1")),
                        WrapperArg.ValueArg(SqlValue.Literal("10")),
                    ),
                ),
            ),
        )
        val statement = parser.parse(chain)
        val inCond = statement.conditions[0] as Condition.In
        assertEquals(listOf("1", "2"), inCond.values.map { (it as SqlValue.Literal).raw })
        val betweenCond = statement.conditions[1] as Condition.Between
        assertEquals("1", (betweenCond.lo as SqlValue.Literal).raw)
        assertEquals("10", (betweenCond.hi as SqlValue.Literal).raw)
    }

    @Test
    fun `like解析_位置与取反`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                WrapperCall(
                    "likeLeft",
                    listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "name")), WrapperArg.ValueArg(SqlValue.Literal("张"))),
                ),
                WrapperCall(
                    "notLike",
                    listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "name")), WrapperArg.ValueArg(SqlValue.Literal("李"))),
                ),
            ),
        )
        val statement = parser.parse(chain)
        val like = statement.conditions[0] as Condition.Like
        assertEquals(LikePosition.LEFT, like.position)
        val notLike = statement.conditions[1] as Condition.Like
        assertTrue(notLike.negated)
        assertEquals(LikePosition.ANYWHERE, notLike.position)
    }

    @Test
    fun `逻辑分组_and或嵌套`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                eqCall("id", "1"),
                WrapperCall(
                    "or",
                    listOf(
                        WrapperArg.NestedArg(
                            WrapperChain(
                                WrapperType.NESTED,
                                "DeviceInfoEntity",
                                listOf(
                                    eqCall("station_id", "101"),
                                    eqCall("share_scheme_id", "5"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val statement = parser.parse(chain)
        // M38.2 语义修正：or(Consumer) 以 OR 连接前条件（修复前组内 OR/外层 AND 内外颠倒），
        // 结构为 Group(OR, [前条件, Group(AND, lambda 内条件)])
        assertEquals(1, statement.conditions.size)
        val merge = statement.conditions[0] as Condition.Group
        assertEquals(Connector.OR, merge.connector)
        assertEquals(2, merge.children.size)
        val inner = merge.children[1] as Condition.Group
        assertEquals(Connector.AND, inner.connector)
    }

    // ------------------------------------------------------------------
    // T-M29-01：分组方法布尔条件重载（and/or/nested/not/func 的 condition 首参）
    // ------------------------------------------------------------------

    /** 便捷构造：and/or 嵌套链（NESTED 类型，实体名与主链一致）。 */
    private fun nested(calls: List<WrapperCall>) = WrapperArg.NestedArg(
        WrapperChain(WrapperType.NESTED, "DeviceInfoEntity", calls),
    )

    /** 条件树全部 toggle id（含嵌套组内子条件；enabledConditionIds 集合构造用）。 */
    private fun Condition.allToggleIds(): List<String> = when (this) {
        is Condition.Group -> listOfNotNull(toggle?.id) + children.flatMap { it.allToggleIds() }
        else -> listOfNotNull(toggle?.id)
    }

    @Test
    fun `分组布尔重载_and运行时布尔组默认勾选渲染`() {
        // 用户实测形态：and(condition, w -> w.likeRight(code).or().likeRight(user_phone))
        // 此前首参布尔被当 NestedArg 取 → 整组静默丢弃；修复后组挂开关、默认渲染
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                eqCall("id", "1"),
                WrapperCall("and", listOf(
                    WrapperArg.ValueArg(SqlValue.Placeholder("isNotBlank(keyword)")),
                    nested(listOf(
                        WrapperCall("likeRight", listOf(
                            WrapperArg.ColumnArg(ColumnRef(columnName = "code")),
                            WrapperArg.ValueArg(SqlValue.Placeholder("keyword")),
                        )),
                        WrapperCall("or", emptyList()),
                        WrapperCall("likeRight", listOf(
                            WrapperArg.ColumnArg(ColumnRef(columnName = "user_phone")),
                            WrapperArg.ValueArg(SqlValue.Placeholder("keyword")),
                        )),
                    )),
                )),
            ),
        )
        val statement = parser.parse(chain)
        val group = statement.conditions[1] as Condition.Group
        assertEquals(Connector.OR, group.connector)
        assertEquals("isNotBlank(keyword)", group.toggle?.flagSource)
        assertTrue(group.toggle!!.defaultEnabled)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE id = 1
                AND (code LIKE ? OR user_phone LIKE ?)
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `分组布尔重载_and字面量false默认不渲染_勾选后渲染`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                eqCall("id", "1"),
                WrapperCall("and", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("false")),
                    nested(listOf(eqCall("station_id", "101"))),
                )),
            ),
        )
        val statement = parser.parse(chain)
        val group = statement.conditions[1] as Condition.Group
        assertEquals("false", group.toggle?.flagSource)
        assertFalse(group.toggle!!.defaultEnabled)
        // 默认（enabledIds=null 按 defaultEnabled）：组不渲染（对齐 MP false 条件跳过）
        val defaultSql = SqlGenerator.generate(statement)
        assertFalse("默认不渲染：$defaultSql", defaultSql.contains("station_id = 101"))
        // 勾选后渲染（enabledConditionIds 语义为「仅渲染集合内动态条件」——须含全部条件 id，含组内子条件）
        val allIds = statement.conditions.flatMap { it.allToggleIds() }.toSet()
        val enabledSql = SqlGenerator.generate(statement, compact = false, enabledConditionIds = allIds)
        assertTrue("勾选后渲染：$enabledSql", enabledSql.contains("AND (station_id = 101)"))
    }

    @Test
    fun `分组布尔重载_or与nested`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                eqCall("id", "1"),
                WrapperCall("or", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    nested(listOf(eqCall("id", "2"))),
                )),
                WrapperCall("nested", listOf(
                    WrapperArg.ValueArg(SqlValue.Placeholder("nestedFlag")),
                    nested(listOf(eqCall("name", "'x'"))),
                )),
            ),
        )
        val statement = parser.parse(chain)
        // M38.2 语义修正：or(true, lambda) 以 OR 连接前条件（flag 保留在 lambda 组上），
        // nested 组仍以 AND 平铺。修复前 or 组被错误地以 AND 平铺为独立条件。
        assertEquals(2, statement.conditions.size)
        val orMerge = statement.conditions[0] as Condition.Group
        assertEquals(Connector.OR, orMerge.connector)
        val flaggedGroup = orMerge.children[1] as Condition.Group
        assertEquals("true", flaggedGroup.toggle?.flagSource)
        val nestedGroup = statement.conditions[1] as Condition.Group
        assertEquals(Connector.AND, nestedGroup.connector)
        assertEquals("nestedFlag", nestedGroup.toggle?.flagSource)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE (id = 1 OR (id = 2))
                AND (name = 'x')
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `分组布尔重载_not`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("not", listOf(
                    WrapperArg.ValueArg(SqlValue.Placeholder("notFlag")),
                    nested(listOf(eqCall("id", "1"))),
                )),
            ),
        )
        val statement = parser.parse(chain)
        val group = statement.conditions[0] as Condition.Group
        assertTrue(group.negated)
        assertEquals("notFlag", group.toggle?.flagSource)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE NOT (id = 1)
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `分组布尔重载_func子条件组化挂开关`() {
        // func(condition, consumer)：子条件展开为 AND 组挂开关（无标志形态保持展平，见回归用例）
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("func", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    nested(listOf(eqCall("id", "1"), eqCall("name", "'x'"))),
                )),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(1, statement.conditions.size)
        val group = statement.conditions[0] as Condition.Group
        assertEquals(2, group.children.size)
        assertEquals("true", group.toggle?.flagSource)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE (id = 1 AND name = 'x')
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `分组无标志回归_不挂toggle`() {
        // and(w -> ...) 无布尔首参：组照常解析、不挂开关（回归不变）
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("and", listOf(nested(listOf(eqCall("id", "1"))))),
            ),
        )
        val statement = parser.parse(chain)
        val group = statement.conditions[0] as Condition.Group
        assertNull(group.toggle)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE (id = 1)
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `结构解析_select排序分页`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                WrapperCall(
                    "select",
                    listOf(
                        WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                        WrapperArg.ColumnArg(ColumnRef(columnName = "share_scheme_id")),
                    ),
                ),
                WrapperCall("orderByDesc", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "sort")))),
                WrapperCall(
                    "last",
                    listOf(WrapperArg.ValueArg(SqlValue.Literal("limit 10"))),
                ),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(listOf("station_id", "share_scheme_id"), statement.selectColumns.map { it.columnName })
        assertEquals(1, statement.orderBy.size)
        assertEquals("sort", statement.orderBy[0].column.columnName)
        assertEquals(false, statement.orderBy[0].asc)
        assertEquals(LimitClause(count = 10), statement.limit)
    }

    @Test
    fun `last带偏移量分页解析`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                WrapperCall("last", listOf(WrapperArg.ValueArg(SqlValue.Literal("limit 5, 10")))),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(LimitClause(offset = 5, count = 10), statement.limit)
    }

    @Test
    fun `未知方法_安全忽略`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                WrapperCall("someUnknownMethod", listOf(WrapperArg.ValueArg(SqlValue.Literal("x")))),
                eqCall("id", "1"),
            ),
        )
        val statement = parser.parse(chain)
        // 未知方法不抛异常且不影响后续条件
        assertEquals(1, statement.conditions.size)
        // D264 修5：契约保持不变（不改条件条数），但未知方法名必须**可见**——面板据此渲染红字
        assertEquals(listOf("someUnknownMethod"), statement.unknownMethods)
    }

    /**
     * D264 修5：未知方法名按出现顺序去重收集，且**不影响**条件条数（只加可见性，不改语义）。
     * 自研 wrapper 的语义化链方法（`statusEq`）就落在这里：收集层能过、解析层不认。
     */
    @Test
    fun `修5_未知方法名去重收集且不改条件条数`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                WrapperCall("statusEq", listOf(WrapperArg.ValueArg(SqlValue.Literal("1")))),
                eqCall("id", "1"),
                WrapperCall("statusEq", listOf(WrapperArg.ValueArg(SqlValue.Literal("2")))),
                WrapperCall("byKeyword", listOf(WrapperArg.ValueArg(SqlValue.Literal("abc")))),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals("条件条数不得因可见性改造而变（只收 eq）", 1, statement.conditions.size)
        assertEquals(
            "未知方法名按源码顺序去重",
            listOf("statusEq", "byKeyword"),
            statement.unknownMethods,
        )
    }

    /**
     * D264 修5：MPJ 的 `lambda()` / `stringQuery()` 是类型互转中间节点（既有语义就是静默忽略），
     * 不得进 unknownMethods——否则用户会看到「无法解析 lambda」这类噪音。
     */
    @Test
    fun `修5_类型互转中间节点不进未知方法清单`() {
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                WrapperCall("select", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "id")))),
                WrapperCall("lambda", emptyList()),
                eqCall("id", "1"),
                WrapperCall("stringQuery", emptyList()),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(emptyList<String>(), statement.unknownMethods)
    }

    /** D264 修5：全部方法都识别时清单为空（面板不挂红字）。 */
    @Test
    fun `修5_全部识别时未知方法清单为空`() {
        val statement = parser.parse(
            WrapperChain(WrapperType.LAMBDA_QUERY, "DeviceInfoEntity", listOf(eqCall("id", "1"))),
        )
        assertEquals(emptyList<String>(), statement.unknownMethods)
    }

    @Test
    fun `ne渲染与MP语义一致用尖括号等号`() {
        // M38.2（语义对照矩阵实证）：MP SqlKeyword.NE = "<>"，原 "!=" 与 MP 不一致
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("ne", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("2")),
                )),
            ),
        )
        val statement = parser.parse(chain)
        val cond = statement.conditions[0] as Condition.Simple
        assertEquals("station_id", cond.column.columnName)
        assertEquals("<>", cond.op)
        assertEquals("2", (cond.value as SqlValue.Literal).raw)
        val sql = SqlGenerator.generate(statement)
        assertTrue(
            "ne 应渲染 <>：$sql",
            sql.contains("station_id <> 2") && !sql.contains("!="),
        )
    }

    @Test
    fun `orConsumer外层OR组内AND`() {
        // M38.2：or(w -> w.eq(2).eq(3)) = 外层 OR 连接 + 组内恒 AND（MP 语义）。
        // 修复前组内连接词误用 OR → 渲染 `AND (2 OR 3)`（内外颠倒）
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                eqCall("id", "1"),
                WrapperCall("or", listOf(
                    nested(listOf(eqCall("id", "2"), eqCall("id", "3"))),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE (id = 1 OR (id = 2 AND id = 3))
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `having字符串版透传Raw条件`() {
        // M38.2：字符串版 having("count(*) > 3")（MP 官方 API）——修复前字符串片段被静默丢弃
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("groupBy", listOf(WrapperArg.ValueArg(SqlValue.Literal("status")))),
                WrapperCall("having", listOf(WrapperArg.ValueArg(SqlValue.Literal("count(*) > 3")))),
            ),
        )
        val statement = parser.parse(chain)
        val cond = statement.having as Condition.Raw
        assertEquals("count(*) > 3", cond.sqlText)
        val sql = SqlGenerator.generate(statement)
        assertTrue("having 字符串应透传：$sql", sql.contains("HAVING count(*) > 3"))
    }

    @Test
    fun `lastLimitOffset形态OFFSET回填`() {
        // M38.2：last("limit 5 OFFSET 10")（MySQL 另一形态，MP last 常用）——修复前 OFFSET 被静默丢弃
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("last", listOf(WrapperArg.ValueArg(SqlValue.Literal("limit 5 OFFSET 10")))),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(LimitClause(offset = 10, count = 5), statement.limit)
    }

    // ------------------------------------------------------------------
    // M12：动态条件（布尔重载 → toggle 元数据 + 判空语义 + 静态求值 + 启用过滤）
    // ------------------------------------------------------------------

    /** 便捷构造：eq(布尔标志占位符, name, 值) 动态条件。 */
    private fun flaggedEq(flag: String, value: SqlValue) = WrapperChain(
        WrapperType.LAMBDA_QUERY, "E",
        listOf(WrapperCall("eq", listOf(
            WrapperArg.ValueArg(SqlValue.Placeholder(flag)),
            WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
            WrapperArg.ValueArg(value),
        ))),
    )

    @Test
    fun `布尔条件重载_toggle元数据`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("1")),
                )),
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("false")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("2")),
                )),
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Placeholder("isActive")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
                    WrapperArg.ValueArg(SqlValue.Placeholder("nameVar")),
                )),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(3, statement.conditions.size)

        val t0 = (statement.conditions[0] as Condition.Simple).toggle!!
        assertEquals("c0", t0.id)
        assertEquals("true", t0.flagSource)
        assertTrue(t0.defaultEnabled)
        assertNull(t0.valueSource)
        assertEquals("id = 1", t0.label)

        val t1 = (statement.conditions[1] as Condition.Simple).toggle!!
        assertEquals("c1", t1.id)
        assertEquals("false", t1.flagSource)
        assertFalse(t1.defaultEnabled)
        assertEquals("station_id = 2", t1.label)

        val t2 = (statement.conditions[2] as Condition.Simple).toggle!!
        assertEquals("c2", t2.id)
        assertEquals("isActive", t2.flagSource)
        assertTrue(t2.defaultEnabled)
        assertEquals("nameVar", t2.valueSource)
        assertEquals("name = ?", t2.label)
    }

    @Test
    fun `布尔重载_defaultEnabled三态_运行时布尔一律默认勾选`() {
        fun toggle(flag: String) =
            parser.parse(flaggedEq(flag, SqlValue.Placeholder("name"))).conditions[0].toggle!!

        // M13 简化：不再识别判空语义——判空/判非 null 的运行时布尔表达式一律默认勾选（复选框纯手动）
        assertTrue(toggle("isActive").defaultEnabled)
        assertTrue(toggle("StringUtils.isNotBlank(name)").defaultEnabled)
        assertTrue(toggle("StrUtil.isNotEmpty(name)").defaultEnabled)
        assertTrue(toggle("name != null").defaultEnabled)
        assertTrue(toggle("Objects.nonNull(name)").defaultEnabled)
        assertTrue("判空方法表达式默认勾选", toggle("hasText(name)").defaultEnabled)

        // 字面量 true/false 仍按字面量定默认勾选态
        fun literalEq(flag: String) = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(WrapperCall("eq", listOf(
                WrapperArg.ValueArg(SqlValue.Literal(flag)),
                WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
                WrapperArg.ValueArg(SqlValue.Literal("1")),
            ))),
        )
        assertTrue(parser.parse(literalEq("true")).conditions[0].toggle!!.defaultEnabled)
        assertFalse(parser.parse(literalEq("false")).conditions[0].toggle!!.defaultEnabled)
    }

    @Test
    fun `生成层_enabledIds过滤`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("1")),
                )),
                WrapperCall("eq", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
                    WrapperArg.ValueArg(SqlValue.Literal("'张三'", "张三")),
                )),
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("false")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("2")),
                )),
            ),
        )
        val statement = parser.parse(chain)

        // 默认（enabledIds=null）：eq(true) 保留、eq(false) 排除、普通条件保留 → 与 M11 基线一致
        val base = SqlGenerator.generate(statement)
        assertTrue(base.contains("WHERE id = 1"))
        assertTrue(base.contains("AND name = '张三'"))
        assertFalse("false 条件默认不应拼接", base.contains("station_id = 2"))

        // 显式启用 c0+c2（M19.6：普通条件 name 也占 id c1）→ 三个条件都在
        val all = SqlGenerator.generate(statement, compact = false, enabledConditionIds = setOf("c0", "c2"))
        assertTrue(all.contains("AND station_id = 2"))

        // 全禁用（空集合）→ 所有条件均关闭（M19.6：普通条件同样挂 toggle 可勾选，原"普通条件恒保留"语义扩展）
        val none = SqlGenerator.generate(statement, compact = false, enabledConditionIds = emptySet())
        assertFalse(none.contains("id = 1"))
        assertFalse(none.contains("name = '张三'"))
        assertFalse(none.contains("station_id = 2"))
    }

    @Test
    fun `嵌套组内动态条件_toggle与整组跳过`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("1")),
                )),
                WrapperCall("and", listOf(
                    WrapperArg.NestedArg(WrapperChain(WrapperType.NESTED, "E", listOf(
                        WrapperCall("eq", listOf(
                            WrapperArg.ValueArg(SqlValue.Literal("false")),
                            WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                            WrapperArg.ValueArg(SqlValue.Literal("2")),
                        )),
                    ))),
                )),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(2, statement.conditions.size)
        val group = statement.conditions[1] as Condition.Group
        val nested = group.children[0] as Condition.Simple
        assertEquals("c1", nested.toggle?.id)
        assertFalse(nested.toggle?.defaultEnabled!!)

        // 默认渲染：组内 false 条件排除 → 组空 → 整组不渲染（无空括号）
        val sql = SqlGenerator.generate(statement)
        assertTrue(sql.contains("WHERE id = 1"))
        assertFalse(sql.contains("("))
    }

    @Test
    fun `字符串列名参数_兼容字符串版`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                WrapperCall(
                    "eq",
                    listOf(WrapperArg.ValueArg(SqlValue.Literal("share_scheme_id")), WrapperArg.ValueArg(SqlValue.Literal("5"))),
                ),
            ),
        )
        val statement = parser.parse(chain)
        val cond = statement.conditions[0] as Condition.Simple
        assertEquals("share_scheme_id", cond.column.columnName)
    }
}
