package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数收集 / 替换器 / 单行模式单元测试（M2）。
 */
class ParameterReplacerTest {

    private val table = TableInfo(
        entityClassName = "DeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("stationId", "station_id"),
            ColumnInfo("shareSchemeId", "share_scheme_id"),
        ),
    )

    @Test
    fun `生成SQL收集未解析参数`() {
        val statement = SelectStatement(
            table = table,
            conditions = listOf(
                Condition.Simple(
                    ColumnRef(columnName = "station_id"),
                    "=",
                    SqlValue.Placeholder("stationId"),
                ),
                Condition.Simple(
                    ColumnRef(columnName = "id"),
                    "=",
                    SqlValue.Literal("1001"),
                ),
            ),
        )
        val generated = SqlGenerator.generateWithParams(statement)
        // 字面量内联，占位符收集
        assertEquals(listOf("stationId"), generated.unresolvedParams)
        assertEquals("?", generated.sql.substringAfter("= ").substringBefore("\n"))
    }

    @Test
    fun `参数对照表_有参数`() {
        val tableText = ParameterReplacer.buildParamTable(listOf("stationId", "deviceId"))
        assertEquals("-- 参数对照: [stationId=?, deviceId=?]", tableText)
    }

    @Test
    fun `参数对照表_无参数返回null`() {
        assertNull(ParameterReplacer.buildParamTable(emptyList()))
    }

    @Test
    fun `来源注释_按序标注`() {
        val sql = "WHERE station_id = ? AND id = ?"
        val annotated = ParameterReplacer.annotate(sql, listOf("stationId", "deviceId"))
        assertEquals("WHERE station_id = ? /*stationId*/ AND id = ? /*deviceId*/", annotated)
    }

    @Test
    fun `来源注释_参数不足时保留问号`() {
        val sql = "WHERE a = ? AND b = ? AND c = ?"
        val annotated = ParameterReplacer.annotate(sql, listOf("a"))
        assertEquals("WHERE a = ? /*a*/ AND b = ? AND c = ?", annotated)
    }

    @Test
    fun `buildExecutableSql_数值参数_不加引号`() {
        val sql = "SELECT id FROM tbl WHERE id = ? AND age > ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("id", "age"),
            paramValues = mapOf("id" to "1001", "age" to "18"),
            hints = listOf(ParamHint(quote = false), ParamHint(quote = false)),
        )
        assertEquals("SELECT id FROM tbl WHERE id = 1001 AND age > 18", result)
    }

    @Test
    fun `buildExecutableSql_字符串参数_加引号`() {
        val sql = "SELECT id FROM tbl WHERE name = ? AND city = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("name", "city"),
            paramValues = mapOf("name" to "John", "city" to "Beijing"),
            hints = listOf(ParamHint(quote = true), ParamHint(quote = true)),
        )
        assertEquals("SELECT id FROM tbl WHERE name = 'John' AND city = 'Beijing'", result)
    }

    @Test
    fun `buildExecutableSql_空值保留占位符`() {
        val sql = "SELECT id FROM tbl WHERE id = ? AND name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("id", "name"),
            paramValues = mapOf("id" to "1001"),
            hints = listOf(ParamHint(quote = false), ParamHint(quote = true)),
        )
        assertEquals("SELECT id FROM tbl WHERE id = 1001 AND name = ?", result)
    }

    @Test
    fun `buildExecutableSql_单引号转义防注入`() {
        val sql = "SELECT id FROM tbl WHERE name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("name"),
            paramValues = mapOf("name" to "O'Brien"),
            hints = listOf(ParamHint(quote = true)),
        )
        assertEquals("SELECT id FROM tbl WHERE name = 'O''Brien'", result)
    }

    @Test
    fun `buildExecutableSql_无参数原样返回`() {
        val sql = "SELECT id FROM tbl"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = emptyList(),
            paramValues = emptyMap(),
        )
        assertEquals("SELECT id FROM tbl", result)
    }

    @Test
    fun `buildExecutableSql_启发式数值判定_无hint时`() {
        val sql = "SELECT id FROM tbl WHERE id = ? AND name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("id", "name"),
            paramValues = mapOf("id" to "42", "name" to "Alice"),
            hints = emptyList(),
        )
        // 无 hint 时走启发式：纯数字不加引号，非数字加引号
        assertEquals("SELECT id FROM tbl WHERE id = 42 AND name = 'Alice'", result)
    }

    @Test
    fun `buildExecutableSql_参数多于占位符_忽略多余参数`() {
        val sql = "SELECT id FROM tbl WHERE id = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("id"),
            paramValues = mapOf("id" to "1", "extra" to "x"),
            hints = listOf(ParamHint(quote = false)),
        )
        assertEquals("SELECT id FROM tbl WHERE id = 1", result)
    }

    // ------------------------------------------------------------------
    // T-PSG（N-33）：串感知替换 + 基数对账
    // ------------------------------------------------------------------

    @Test
    fun `buildExecutableSql_引号字面量内问号_不替换`() {
        // 参数来源按**真占位符**计（字面量内 `?` 不是参数）：sources 只有 id
        val sql = "SELECT * FROM t WHERE note = 'a?b' AND id = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("id"),
            paramValues = mapOf("id" to "7"),
            hints = listOf(ParamHint(quote = false)),
        )
        // 引号内的 `?` 是字面量，不是占位符：不应被 id 的值替换（改前朴素替换会错位）
        assertEquals("SELECT * FROM t WHERE note = 'a?b' AND id = 7", result)
    }

    @Test
    fun `buildExecutableSql_行注释内问号_不替换`() {
        val sql = "SELECT id FROM t -- 备注?\nWHERE id = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("id"),
            paramValues = mapOf("id" to "7"),
            hints = listOf(ParamHint(quote = false)),
        )
        assertEquals("SELECT id FROM t -- 备注?\nWHERE id = 7", result)
    }

    @Test
    fun `buildExecutableSql_块注释内问号_不替换`() {
        val sql = "SELECT id FROM t /* 占?位 */ WHERE id = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("id"),
            paramValues = mapOf("id" to "7"),
            hints = listOf(ParamHint(quote = false)),
        )
        assertEquals("SELECT id FROM t /* 占?位 */ WHERE id = 7", result)
    }

    @Test
    fun `buildExecutableSql_正常多占位符_逐字节一致`() {
        // T-PSG 验收 1：不含引号/注释内 `?` 的 SQL，替换结果与改前逐字节相同（硬编码钉住）
        val sql = "SELECT id FROM t WHERE note = 'hello world' AND id = ? AND age > ? AND name LIKE ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("id", "age", "name"),
            paramValues = mapOf("id" to "1", "age" to "18", "name" to "Alice"),
            hints = listOf(ParamHint(quote = false), ParamHint(quote = false), ParamHint(quote = true)),
        )
        assertEquals(
            "SELECT id FROM t WHERE note = 'hello world' AND id = 1 AND age > 18 AND name LIKE 'Alice'",
            result,
        )
    }

    @Test
    fun `单行模式_子句空格连接`() {
        val statement = SelectStatement(
            table = table,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1")),
                Condition.Simple(ColumnRef(columnName = "station_id"), "=", SqlValue.Literal("101")),
            ),
            orderBy = listOf(cn.song.mybatisplus.visualizer.model.OrderByItem(ColumnRef(columnName = "sort"), true)),
        )
        val sql = SqlGenerator.generate(statement, compact = true)
        assertEquals(
            "SELECT id, station_id, share_scheme_id FROM car_device_info WHERE id = 1 AND station_id = 101 ORDER BY sort ASC",
            sql,
        )
    }

    @Test
    fun `多行模式_与单行模式一致内容`() {
        val statement = SelectStatement(
            table = table,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1")),
            ),
        )
        val multi = SqlGenerator.generate(statement, compact = false)
        val single = SqlGenerator.generate(statement, compact = true)
        // 去掉空白后应一致
        assertEquals(multi.replace(Regex("\\s+"), " ").trim(), single)
    }

    // ------------------------------------------------------------------
    // T-RuntimeSlotFill 券 B：运行期占位回填
    // ------------------------------------------------------------------

    /** 造一个占位：token 与券 A 产物同形（`运行时值: <摘要> · <id>` 包在块注释里）。 */
    private fun slot(
        id: String,
        kind: SlotKind,
        expression: String = "expr",
        template: String? = null,
        hint: ParamHint = ParamHint(),
    ) = RuntimeSlot(
        id = id,
        kind = kind,
        expression = expression,
        template = template,
        hint = hint,
        token = "/* 运行时值: $expression · $id */",
    )

    private fun render(
        sql: String,
        slots: List<RuntimeSlot>,
        values: RuntimeSlotValues,
        dialectName: String? = null,
    ) = ParameterReplacer.replaceRuntimeSlots(sql, slots, values, dialectName)

    // ---- VALUE -------------------------------------------------------

    @Test
    fun `运行期占位_VALUE_落在引号内只转义不重复加引号`() {
        val target = slot("rt2", SlotKind.VALUE, "startTime", hint = ParamHint(quote = true))
        val sql = "SELECT id FROM t WHERE create_time >= '${target.token}'"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt2" to SlotValue.text("2026-01-01 00:00:00")))

        assertEquals(
            "SELECT id FROM t WHERE create_time >= '2026-01-01 00:00:00'",
            result.sql,
        )
        assertEquals("占位应在账上", listOf("rt2"), result.appliedSlotIds)
        assertFalse("不应残留占位注释", result.sql.contains("运行时值"))
    }

    @Test
    fun `运行期占位_VALUE_单引号转义`() {
        val target = slot("rt1", SlotKind.VALUE, "name", hint = ParamHint(quote = true))
        val sql = "SELECT id FROM t WHERE name = '${target.token}'"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.text("O'Brien")))
        assertEquals("SELECT id FROM t WHERE name = 'O''Brien'", result.sql)
    }

    @Test
    fun `运行期占位_VALUE_注入串仍是一个字符串字面量`() {
        val target = slot("rt1", SlotKind.VALUE, "name", hint = ParamHint(quote = true))
        val sql = "SELECT id FROM t WHERE name = '${target.token}'"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.text("1' OR '1'='1")))
        assertEquals("SELECT id FROM t WHERE name = '1'' OR ''1''=''1'", result.sql)
        // 第二个条件没有被造出来：引号全部成对（转义后）
        assertEquals("内部条件分隔符应被转义成字面量", 1, result.sql.split(" OR ").size - 1)
    }

    @Test
    fun `运行期占位_VALUE_中文与无 hint 时的数值启发式`() {
        val zh = slot("rt1", SlotKind.VALUE, "keyword")
        val num = slot("rt2", SlotKind.VALUE, "id")
        val sql = "SELECT id FROM t WHERE name = '${zh.token}' AND id = ${num.token}"
        val result = render(
            sql,
            listOf(zh, num),
            RuntimeSlotValues.of(
                "rt1" to SlotValue.text("张三"),
                "rt2" to SlotValue.text("1001"),
            ),
        )
        assertEquals("SELECT id FROM t WHERE name = '张三' AND id = 1001", result.sql)
    }

    @Test
    fun `运行期占位_VALUE_NULL_裸出并提示判空写法`() {
        val target = slot("rt1", SlotKind.VALUE, "x")
        val sql = "SELECT id FROM t WHERE x = ${target.token}"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.sqlNull()))
        assertEquals("SELECT id FROM t WHERE x = NULL", result.sql)
        assertTrue(
            "应提示 = NULL 恒不成立: ${result.warnings.map { it.message }}",
            result.warnings.any { it.message.contains("IS NULL") },
        )
    }

    @Test
    fun `运行期占位_VALUE_选了数字但输入非数字_按字符串渲染并提示`() {
        val target = slot("rt1", SlotKind.VALUE, "amount")
        val sql = "SELECT id FROM t WHERE amount > ${target.token}"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.number("12abc")))
        assertEquals("SELECT id FROM t WHERE amount > '12abc'", result.sql)
        assertTrue(
            "应提示已按字符串处理: ${result.warnings.map { it.message }}",
            result.warnings.any { it.message.contains("非数字") || it.message.contains("字符串") },
        )
    }

    @Test
    fun `运行期占位_VALUE_布尔按方言_Oracle拒绝并提示改1或0`() {
        val target = slot("rt1", SlotKind.VALUE, "flag")
        val sql = "SELECT id FROM t WHERE flag = ${target.token}"
        val values = RuntimeSlotValues.of("rt1" to SlotValue.text("true", ValueType.BOOLEAN))

        val mysql = render(sql, listOf(target), values, "MySQL")
        assertEquals("SELECT id FROM t WHERE flag = TRUE", mysql.sql)

        val oracle = render(sql, listOf(target), values, "Oracle")
        assertEquals("Oracle 无布尔字面量 → 拒绝展开，占位原样保留", sql, oracle.sql)
        assertTrue(
            "应提示改成 1/0: ${oracle.warnings.map { it.message }}",
            oracle.warnings.any { it.message.contains("1/0") },
        )
    }

    // ---- LOOP --------------------------------------------------------

    @Test
    fun `运行期占位_LOOP_首项其余两支展开`() {
        val target = slot(
            "rt1",
            SlotKind.LOOP,
            "循环内追加",
            template = RuntimeSlot.joinBranches(listOf("id='%s' ", "or id='%s' ")),
        )
        val sql = "SELECT id FROM t WHERE (${target.token}) and x = 1"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.text("1, 2\n3")))

        assertEquals("SELECT id FROM t WHERE (id='1' or id='2' or id='3' ) and x = 1", result.sql)
        assertEquals(listOf("rt1"), result.appliedSlotIds)
    }

    @Test
    fun `运行期占位_LOOP_值里带引号在模板引号内转义`() {
        val target = slot(
            "rt1",
            SlotKind.LOOP,
            "循环内追加",
            template = RuntimeSlot.joinBranches(listOf("name='%s' ", "or name='%s' ")),
        )
        val sql = "SELECT id FROM t WHERE (${target.token})"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.text("张三, O'Brien")))

        assertEquals("SELECT id FROM t WHERE (name='张三' or name='O''Brien' )", result.sql)
    }

    @Test
    fun `运行期占位_LOOP_模板里出现多个标记_拒绝展开并提示`() {
        // 券 A 报告第①条坑：源码自带 %s（LIKE '%s%'）会让标记计数虚高
        val target = slot(
            "rt1",
            SlotKind.LOOP,
            "循环内追加",
            template = RuntimeSlot.joinBranches(listOf("v LIKE '%s%s%' ")),
        )
        val sql = "SELECT id FROM t WHERE (${target.token})"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.text("1,2")))

        assertEquals("计数不符必须拒绝展开（占位原样保留）", sql, result.sql)
        assertEquals("不算已回填", emptyList<String>(), result.appliedSlotIds)
        assertTrue(
            "应说明为什么拒绝: ${result.warnings.map { it.message }}",
            result.warnings.any { it.message.contains("拒绝展开") && it.message.contains("不符") },
        )
    }

    @Test
    fun `运行期占位_LOOP_形态未知但落在IN列表_按逗号连接`() {
        val target = slot("rt1", SlotKind.LOOP, "ids", hint = ParamHint(inList = true))
        val sql = "SELECT id FROM t WHERE id IN (${target.token})"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.text("1,2,3")))
        assertEquals("SELECT id FROM t WHERE id IN (1, 2, 3)", result.sql)
    }

    @Test
    fun `运行期占位_LOOP_形态未知且不在IN列表_拒绝猜形态`() {
        val target = slot("rt1", SlotKind.LOOP, "ids")
        val sql = "SELECT id FROM t WHERE x = ${target.token}"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.text("1,2")))
        assertEquals(sql, result.sql)
        assertTrue(result.warnings.any { it.message.contains("展开形态未知") })
    }

    // ---- TERNARY -----------------------------------------------------

    @Test
    fun `运行期占位_TERNARY_空支选真分支_片段整段消失`() {
        val target = slot(
            "rt1",
            SlotKind.TERNARY,
            "(status == null ? 空 : 追加)",
            template = RuntimeSlot.joinBranches(listOf("", " AND status = %s")),
        )
        val sql = "SELECT id FROM t WHERE 1 = 1 ${target.token} AND order_no LIKE 'x'"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.branch(0)))

        assertEquals("SELECT id FROM t WHERE 1 = 1 AND order_no LIKE 'x'", result.sql)
        assertFalse("不应残留占位", result.sql.contains("运行时值"))
    }

    @Test
    fun `运行期占位_TERNARY_选假分支_按值填模板`() {
        val target = slot(
            "rt1",
            SlotKind.TERNARY,
            "(status == null ? 空 : 追加)",
            template = RuntimeSlot.joinBranches(listOf("", " AND status = %s")),
        )
        val sql = "SELECT id FROM t WHERE 1 = 1 ${target.token} AND order_no LIKE 'x'"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.branch(1, "1")))

        assertTrue("应出现 AND status = 1: ${result.sql}", result.sql.contains("AND status = 1"))
        assertTrue("ORDER BY 之前的其余条件应保留", result.sql.contains("AND order_no LIKE 'x'"))
        assertFalse("不应残留占位", result.sql.contains("运行时值"))
    }

    @Test
    fun `运行期占位_TERNARY_没选分支_原样保留并提示`() {
        val target = slot(
            "rt1",
            SlotKind.TERNARY,
            "三元",
            template = RuntimeSlot.joinBranches(listOf("A", "B")),
        )
        val sql = "SELECT id FROM t WHERE ${target.token}"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.text("x")))
        assertEquals(sql, result.sql)
        assertTrue(result.warnings.any { it.message.contains("没选分支") })
    }

    // ---- OPTIONAL ----------------------------------------------------

    @Test
    fun `运行期占位_OPTIONAL_包含_按模板填值`() {
        val target = slot(
            "rt1",
            SlotKind.OPTIONAL,
            "条件追加",
            template = RuntimeSlot.joinBranches(listOf("AND order_no LIKE '%%s%' ")),
            hint = ParamHint(quote = true),
        )
        val sql = "SELECT id FROM t WHERE deleted = 0 ${target.token}ORDER BY id DESC"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.text("abc")))

        assertEquals("SELECT id FROM t WHERE deleted = 0 AND order_no LIKE '%abc%' ORDER BY id DESC", result.sql)
    }

    @Test
    fun `运行期占位_OPTIONAL_不包含_整段删除且空白收敛`() {
        val target = slot(
            "rt1",
            SlotKind.OPTIONAL,
            "条件追加",
            template = RuntimeSlot.joinBranches(listOf("AND amount >= %s ")),
        )
        val sql = "SELECT id FROM t WHERE deleted = 0 ${target.token}  ORDER BY id DESC"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.excluded()))

        assertEquals("SELECT id FROM t WHERE deleted = 0 ORDER BY id DESC", result.sql)
    }

    @Test
    fun `运行期占位_OPTIONAL_无模板_值位置就地填且字符串内只转义`() {
        // T-RuntimeSlotOptionalFill R2 的多值回退形态：token 只是值的位置（template=null），
        // 静态前缀/后缀在 token 外——必须就地填值，不能展开整段片段（否则重复静态前缀）。
        val target = slot(
            "rt1",
            SlotKind.OPTIONAL,
            "keyword",
            template = null,
            hint = ParamHint(quote = true),
        )
        val sql = "SELECT id FROM t WHERE name LIKE '%${target.token}%' ORDER BY id"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.string("A01")))

        assertEquals("SELECT id FROM t WHERE name LIKE '%A01%' ORDER BY id", result.sql)
        assertFalse("不应残留占位注释", result.sql.contains("运行时值"))

        // 取消包含：值位置形态只移除当前值 token，静态前后缀保留——必须给提示，不静默
        val excluded = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.excluded()))
        assertFalse("值 token 应被移除", excluded.sql.contains("运行时值"))
        assertTrue(
            "多值回退形态取消包含必须提示静态片段保留: ${excluded.warnings}",
            excluded.warnings.any { it.message.contains("静态片段会保留") },
        )
    }

    // ---- 顺序 / 安全 / 留空 -------------------------------------------

    @Test
    fun `运行期占位_留空逐字一致_且与改前替换口径相同`() {
        val target = slot("rt1", SlotKind.VALUE, "a")
        val sql = "SELECT * FROM t WHERE a = ${target.token} AND b = ? AND c = 'x'"
        val sources = listOf("b")
        val paramValues = mapOf("b" to "7")
        val hints = listOf(ParamHint(quote = false))

        val legacy = ParameterReplacer.buildExecutableSql(sql, sources, paramValues, hints)
        val unifiedEmpty = ParameterReplacer.buildExecutableSqlWithSlots(
            sql = sql,
            slots = listOf(target),
            slotValues = RuntimeSlotValues.EMPTY,
            sources = sources,
            paramValues = paramValues,
            hints = hints,
        )
        assertEquals("空值表与改前逐字节一致", legacy, unifiedEmpty.sql)
        assertEquals("空值表不算回填", emptyList<String>(), unifiedEmpty.appliedSlotIds)

        val unset = ParameterReplacer.buildExecutableSqlWithSlots(
            sql = sql,
            slots = listOf(target),
            slotValues = RuntimeSlotValues.of("rt1" to SlotValue.UNSET),
            sources = sources,
            paramValues = paramValues,
            hints = hints,
        )
        assertEquals("登记了但未动作，同样逐字节一致", legacy, unset.sql)

        val onlySlots = ParameterReplacer.replaceRuntimeSlots(sql, listOf(target), RuntimeSlotValues.EMPTY)
        assertEquals("只做占位替换时空表也逐字一致", sql, onlySlots.sql)
        assertFalse("不应出现 NULL", onlySlots.sql.contains("NULL"))
    }

    @Test
    fun `运行期占位_与问号命名参数混用_三样都正确且互不二次替换`() {
        val loop = slot("rt1", SlotKind.LOOP, "ids", hint = ParamHint(inList = true))
        val note = slot("rt2", SlotKind.VALUE, "note", hint = ParamHint(quote = true))
        val sql = "SELECT * FROM t WHERE id IN (${loop.token}) AND name = :nm " +
            "AND age = ? AND note = '${note.token}'"
        val values = RuntimeSlotValues.of(
            "rt1" to SlotValue.text("1,2"),
            "rt2" to SlotValue.text("a?b :nm"),
        )
        val result = ParameterReplacer.buildExecutableSqlWithSlots(
            sql = sql,
            slots = listOf(loop, note),
            slotValues = values,
            sources = listOf("age"),
            paramValues = mapOf("nm" to "O'Brien", "age" to "18"),
            hints = listOf(ParamHint(quote = false)),
            namedHints = mapOf("nm" to ParamHint(quote = true)),
        )
        assertEquals(
            "SELECT * FROM t WHERE id IN (1, 2) AND name = 'O''Brien' AND age = 18 AND note = 'a?b :nm'",
            result.sql,
        )
        assertFalse("不应残留占位", result.sql.contains("运行时值"))
        assertTrue("值里的 ? 不应被当占位二次填", result.sql.contains("'a?b :nm'"))
        assertTrue("真正的 ? 应被真实回填", result.sql.contains("age = 18"))
        assertFalse("不应残留待填的 age = ?", result.sql.contains("age = ?"))
    }

    @Test
    fun `运行期占位_未回填时占位注释里的冒号不被命名参数误伤`() {
        val target = slot("rt1", SlotKind.VALUE, "k == 0 ? x : y")
        val sql = "SELECT * FROM t WHERE a = ${target.token} AND b = :y"
        val result = ParameterReplacer.buildExecutableSqlWithSlots(
            sql = sql,
            slots = listOf(target),
            slotValues = RuntimeSlotValues.EMPTY,
            paramValues = mapOf("y" to "V"),
            namedHints = mapOf("y" to ParamHint(quote = true)),
        )
        assertEquals("SELECT * FROM t WHERE a = ${target.token} AND b = 'V'", result.sql)
    }

    @Test
    fun `运行期占位_找不到占位_提示且不改文本`() {
        val target = slot("rt9", SlotKind.VALUE, "x")
        val sql = "SELECT id FROM t"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt9" to SlotValue.text("1")))
        assertEquals(sql, result.sql)
        assertTrue(result.warnings.any { it.message.contains("找不到") })
    }

    @Test
    fun `运行期占位_同一占位出现两次_两处都填且区间不重叠`() {
        val target = slot("rt1", SlotKind.VALUE, "x")
        val sql = "SELECT * FROM t WHERE (${target.token}) = (${target.token})"
        val result = render(sql, listOf(target), RuntimeSlotValues.of("rt1" to SlotValue.text("9")))
        assertEquals("SELECT * FROM t WHERE (9) = (9)", result.sql)
        assertEquals(listOf("rt1"), result.appliedSlotIds)
        assertTrue("应记下两段替换区间", result.protectedSpans.isNotEmpty())
    }
}
