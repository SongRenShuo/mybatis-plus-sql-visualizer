package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.sql.RuntimeSlotValues
import cn.song.mybatisplus.visualizer.sql.RuntimeSlot
import cn.song.mybatisplus.visualizer.sql.SlotKind
import cn.song.mybatisplus.visualizer.sql.SlotValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 参数收集 / 替换器 / 单行模式单元测试（M2）。
 */
class ParameterReplacerTest {

    private val table = TableInfo("DeviceInfoEntity", "car_device_info", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("stationId", "station_id"),
            ColumnInfo("shareSchemeId", "share_scheme_id"),
        ))

    @Test
    fun `生成SQL收集未解析参数`() {
        val statement = SelectStatement(table, emptyList(), null, emptyList(), false, listOf(
                Condition.Simple(ColumnRef("station_id"), "=", SqlValue.Placeholder("stationId"), null),
                Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1001"), null),
            ), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
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
            sql,
            listOf("id", "age"),
            mapOf("id" to "1001", "age" to "18"),
            listOf(ParamHint(false), ParamHint(false)),
        )
        assertEquals("SELECT id FROM tbl WHERE id = 1001 AND age > 18", result)
    }

    @Test
    fun `buildExecutableSql_字符串参数_加引号`() {
        val sql = "SELECT id FROM tbl WHERE name = ? AND city = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("name", "city"),
            mapOf("name" to "John", "city" to "Beijing"),
            listOf(ParamHint(true), ParamHint(true)),
        )
        assertEquals("SELECT id FROM tbl WHERE name = 'John' AND city = 'Beijing'", result)
    }

    @Test
    fun `buildExecutableSql_空值保留占位符`() {
        val sql = "SELECT id FROM tbl WHERE id = ? AND name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("id", "name"),
            mapOf("id" to "1001"),
            listOf(ParamHint(false), ParamHint(true)),
        )
        assertEquals("SELECT id FROM tbl WHERE id = 1001 AND name = ?", result)
    }

    @Test
    fun `buildExecutableSql_单引号转义防注入`() {
        val sql = "SELECT id FROM tbl WHERE name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("name"),
            mapOf("name" to "O'Brien"),
            listOf(ParamHint(true)),
        )
        assertEquals("SELECT id FROM tbl WHERE name = 'O''Brien'", result)
    }

    // ------------------------------------------------------------------
    // T-PreQuotedLiteral：值已是完整引号字面量 → 原样渲染，不再转义包裹（引号翻倍前科）
    // ------------------------------------------------------------------

    @Test
    fun `buildExecutableSql_已引号字符串原样不翻倍`() {
        val sql = "SELECT id FROM tbl WHERE name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("name"),
            mapOf("name" to "'abc'"),
            listOf(ParamHint(true)),
        )
        assertEquals("SELECT id FROM tbl WHERE name = 'abc'", result)
    }

    @Test
    fun `buildExecutableSql_已引号输入走启发式档同样原样`() {
        val sql = "SELECT id FROM tbl WHERE name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("name"),
            mapOf("name" to "'abc'"),
            emptyList(),
        )
        assertEquals("SELECT id FROM tbl WHERE name = 'abc'", result)
    }

    @Test
    fun `buildExecutableSql_数值列收到已引号非数字_原样不翻倍`() {
        val sql = "SELECT id FROM tbl WHERE id = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("id"),
            mapOf("id" to "'abc'"),
            listOf(ParamHint(false)),
        )
        assertEquals("SELECT id FROM tbl WHERE id = 'abc'", result)
    }

    @Test
    fun `运行期占位_VALUE_已引号输入落在引号内_剥外引号按内层转义`() {
        val target = slot("rt9", SlotKind.VALUE, "kw", null, ParamHint(true))
        val sql = "SELECT id FROM t WHERE name LIKE '%${target.token}%'"
        val result = render(sql, listOf(target), RuntimeSlotValues.of(java.util.Map.entry("rt9", SlotValue.text("'abc'"))))
        assertEquals("SELECT id FROM t WHERE name LIKE '%abc%'", result.sql)
    }

    @Test
    fun `buildExecutableSql_已转义内层引号完整字面量原样`() {
        val sql = "SELECT id FROM tbl WHERE name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("name"),
            mapOf("name" to "'a''b'"),
            listOf(ParamHint(true)),
        )
        assertEquals("SELECT id FROM tbl WHERE name = 'a''b'", result)
    }

    @Test
    fun `buildExecutableSql_无参数原样返回`() {
        val sql = "SELECT id FROM tbl"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            emptyList(),
            emptyMap(),
        )
        assertEquals("SELECT id FROM tbl", result)
    }

    @Test
    fun `buildExecutableSql_启发式数值判定_无hint时`() {
        val sql = "SELECT id FROM tbl WHERE id = ? AND name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("id", "name"),
            mapOf("id" to "42", "name" to "Alice"),
            emptyList(),
        )
        // 无 hint 时走启发式：纯数字不加引号，非数字加引号
        assertEquals("SELECT id FROM tbl WHERE id = 42 AND name = 'Alice'", result)
    }

    @Test
    fun `buildExecutableSql_参数多于占位符_忽略多余参数`() {
        val sql = "SELECT id FROM tbl WHERE id = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("id"),
            mapOf("id" to "1", "extra" to "x"),
            listOf(ParamHint(false)),
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
            sql,
            listOf("id"),
            mapOf("id" to "7"),
            listOf(ParamHint(false)),
        )
        // 引号内的 `?` 是字面量，不是占位符：不应被 id 的值替换（改前朴素替换会错位）
        assertEquals("SELECT * FROM t WHERE note = 'a?b' AND id = 7", result)
    }

    @Test
    fun `buildExecutableSql_行注释内问号_不替换`() {
        val sql = "SELECT id FROM t -- 备注?\nWHERE id = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("id"),
            mapOf("id" to "7"),
            listOf(ParamHint(false)),
        )
        assertEquals("SELECT id FROM t -- 备注?\nWHERE id = 7", result)
    }

    @Test
    fun `buildExecutableSql_块注释内问号_不替换`() {
        val sql = "SELECT id FROM t /* 占?位 */ WHERE id = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("id"),
            mapOf("id" to "7"),
            listOf(ParamHint(false)),
        )
        assertEquals("SELECT id FROM t /* 占?位 */ WHERE id = 7", result)
    }

    @Test
    fun `buildExecutableSql_正常多占位符_逐字节一致`() {
        // T-PSG 验收 1：不含引号/注释内 `?` 的 SQL，替换结果与改前逐字节相同（硬编码钉住）
        val sql = "SELECT id FROM t WHERE note = 'hello world' AND id = ? AND age > ? AND name LIKE ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("id", "age", "name"),
            mapOf("id" to "1", "age" to "18", "name" to "Alice"),
            listOf(ParamHint(false), ParamHint(false), ParamHint(true)),
        )
        assertEquals(
            "SELECT id FROM t WHERE note = 'hello world' AND id = 1 AND age > 18 AND name LIKE 'Alice'",
            result,
        )
    }

    @Test
    fun `单行模式_子句空格连接`() {
        val statement = SelectStatement(table, emptyList(), null, emptyList(), false, listOf(
                Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null),
                Condition.Simple(ColumnRef("station_id"), "=", SqlValue.Literal("101"), null),
            ), emptyList(), null, listOf(cn.song.mybatisplus.visualizer.model.OrderByItem(ColumnRef("sort"), true)), null, null, null, null, emptyList(), null, null)
        val sql = SqlGenerator.generate(statement, true)
        assertEquals(
            "SELECT id, station_id, share_scheme_id FROM car_device_info WHERE id = 1 AND station_id = 101 ORDER BY sort ASC",
            sql,
        )
    }

    @Test
    fun `多行模式_与单行模式一致内容`() {
        val statement = SelectStatement(table, emptyList(), null, emptyList(), false, listOf(
                Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null),
            ), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val multi = SqlGenerator.generate(statement, false)
        val single = SqlGenerator.generate(statement, true)
        // 去掉空白后应一致
        assertEquals(multi.replace(Regex("\\s+"), " ").trim(), single)
    }

    // ------------------------------------------------------------------
    // T-RuntimeSlotFill 券 B：运行期占位回填（本轮 T-PreQuotedLiteral 用例引入）
    // ------------------------------------------------------------------

    /** 造一个占位：token 与券 A 产物同形（`运行时值: <摘要> · <id>` 包在块注释里）。 */
    private fun slot(
        id: String,
        kind: SlotKind,
        expression: String = "expr",
        template: String? = null,
        hint: ParamHint = ParamHint(),
    ) = RuntimeSlot(
        id,
        kind,
        expression,
        template,
        hint,
        "/* 运行时值: $expression · $id */",
    )

    private fun render(
        sql: String,
        slots: List<RuntimeSlot>,
        values: RuntimeSlotValues,
        dialectName: String? = null,
    ) = ParameterReplacer.replaceRuntimeSlots(sql, slots, values, dialectName)
}
