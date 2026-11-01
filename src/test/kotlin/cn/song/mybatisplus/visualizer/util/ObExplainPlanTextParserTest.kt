package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OceanBase TEXT 计划树解析器单测（T-ObPanelAdvice）。
 *
 * 两种真实/合成形态都要覆盖（2026-11-01 真机报障）：
 *  1. **真机形态：单列 × 多行**——计划文本一行一个 cell（含空行与 `Outputs & filters` 段），
 *     照录真机（OB CE 4.3.5.6，`EXPLAIN SELECT * FROM t_order`）；首版只测了形态 2，
 *     于是真机解析恒 null（单测假绿）；
 *  2. **兼容形态：单 cell 装整段多行文本**——首版合成样本，其它通道/旧留证口径。
 *
 * 跨方言不外泄是另一半职责——H2/SQLite/MySQL/PG 样本必须解析不出（null → 面板不进 OB 分支）。
 */
class ObExplainPlanTextParserTest {

    /**
     * 真机原文照录（OB CE 4.3.5.6，`103.36.220.245:25881/xc3_test`，IDE 控制台 `EXPLAIN SELECT * FROM t_order`）。
     * 注意：无 `===` 分隔线，表头与数据行之间有空行，`Outputs & filters` 段下是 `---` 分隔线。
     */
    private val realObLines = listOf(
        "",
        "|ID|OPERATOR |NAME |EST.ROWS|EST.TIME(us)|",
        "",
        "|0 |TABLE FULL SCAN|t_order|3 |3 |",
        "",
        "Outputs & filters:",
        "  ------------------------------------",
        "  output([t_order.id], [t_order.order_no], [t_order.amount], [t_order.status], [t_order.create_time])",
    )

    /** 真机形态 RawResult：单列 `Query Plan`，一行一个 cell。 */
    private fun realObRaw(
        lines: List<String> = realObLines,
        truncated: Boolean = false,
        notices: List<String> = emptyList(),
    ): McpExplainFormatter.RawResult =
        McpExplainFormatter.RawResult(listOf("Query Plan"), lines.map { listOf(it) }, truncated, notices)

    /** 兼容形态：一个 cell 里装整段多行文本（首版合成样本，含 `===` 分隔线与 Outputs 说明段）。 */
    private fun obPlan(operator: String, name: String, estRows: String): String =
        """
        ==========================================
        |ID|OPERATOR  |NAME|EST.ROWS|EST.TIME(us)|
        ------------------------------------------
        |0 |EXPRESSION|    |1       |1           |
        |1 |$operator|$name|$estRows|42          |
        ==========================================
        Outputs & filters:
          0 - output([*]), filter(nil), access([*])
          1 - output([$name.*]), filter(nil), access([$name.*])
        """.trimIndent()

    private fun obRaw(plan: String, truncated: Boolean = false): McpExplainFormatter.RawResult =
        McpExplainFormatter.RawResult(listOf("Query Plan"), listOf(listOf(plan)), truncated)

    // ---------- 真机形态（单列 × 多行） ----------

    @Test
    fun test真机单列多行样本解析为结构化行列() {
        val parsed = ObExplainPlanTextParser.parse(realObRaw()) ?: error("真机形态必须解析成功")
        assertEquals(listOf("ID", "OPERATOR", "NAME", "EST.ROWS", "EST.TIME(us)"), parsed.headers)
        assertEquals(1, parsed.rows.size)
        assertEquals(listOf("0", "TABLE FULL SCAN", "t_order", "3", "3"), parsed.rows[0])
    }

    @Test
    fun test真机形态truncated与notices保真() {
        val parsed = ObExplainPlanTextParser.parse(
            realObRaw(truncated = true, notices = listOf("driver notice")),
        ) ?: error("真机形态必须解析成功")
        assertEquals(true, parsed.truncated)
        assertEquals(listOf("driver notice"), parsed.notices)
    }

    @Test
    fun test真机形态多算子行() {
        // 真机多行算子（嵌套计划）：每行一个 cell，全部按列数一致取出
        val lines = listOf(
            "|ID|OPERATOR      |NAME     |EST.ROWS|EST.TIME(us)|",
            "",
            "|0 |TABLE RANGE SCAN|t_order  |90000   |42          |",
            "",
            "|1 |TABLE FULL SCAN |t_detail |500000  |99          |",
            "",
            "Outputs & filters:",
        )
        val parsed = ObExplainPlanTextParser.parse(realObRaw(lines)) ?: error("真机多行形态必须解析成功")
        assertEquals(2, parsed.rows.size)
        assertEquals(listOf("0", "TABLE RANGE SCAN", "t_order", "90000", "42"), parsed.rows[0])
        assertEquals(listOf("1", "TABLE FULL SCAN", "t_detail", "500000", "99"), parsed.rows[1])
    }

    @Test
    fun test真机形态仅表头判失败() {
        // 表头在、其后无任何列数一致的数据行 → 仍判失败（fail-closed，不猜）
        val lines = listOf("|ID|OPERATOR |NAME |EST.ROWS|EST.TIME(us)|", "", "Outputs & filters:")
        assertNull(ObExplainPlanTextParser.parse(realObRaw(lines)))
    }

    @Test
    fun testplanLines按行序展平不拼接行内结构() {
        val lines = ObExplainPlanTextParser.planLines(realObRaw().rows)
        assertEquals(realObLines, lines)
        // 兼容形态：单 cell 多行展平后成同一份行序列
        val compat = ObExplainPlanTextParser.planLines(obRaw(obPlan("TABLE SCAN", "t", "3")).rows)
        assertEquals(obPlan("TABLE SCAN", "t", "3").lines(), compat)
    }

    // ---------- 兼容形态（单 cell 装整段多行） ----------

    @Test
    fun test兼容单cell多行样本解析为结构化行列() {
        val parsed = ObExplainPlanTextParser.parse(obRaw(obPlan("TABLE FULL SCAN", "bench_items", "500000")))
            ?: error("OB 样本必须解析成功")
        assertEquals(listOf("ID", "OPERATOR", "NAME", "EST.ROWS", "EST.TIME(us)"), parsed.headers)
        assertEquals(2, parsed.rows.size)
        // 表头单元格去尾随空格；数据行逐单元格 trim
        assertEquals(listOf("0", "EXPRESSION", "", "1", "1"), parsed.rows[0])
        assertEquals(listOf("1", "TABLE FULL SCAN", "bench_items", "500000", "42"), parsed.rows[1])
    }

    @Test
    fun testTruncated与notices保真() {
        val parsed = ObExplainPlanTextParser.parse(
            McpExplainFormatter.RawResult(
                listOf("Query Plan"), listOf(listOf(obPlan("TABLE SCAN", "t", "3"))),
                truncated = true, notices = listOf("driver notice"),
            ),
        ) ?: error("OB 样本必须解析成功")
        assertEquals(true, parsed.truncated)
        assertEquals(listOf("driver notice"), parsed.notices)
    }

    @Test
    fun testH2文本计划不命中() {
        // H2 `EXPLAIN PLAN FOR`：单列文本树，无 OB 表头
        val h2 = """
            SELECT
                "T"."ID"
            FROM "PUBLIC"."T" "T"
            /* PUBLIC.PRIMARY_KEY_C: ID = 1 */
        """.trimIndent()
        assertNull(ObExplainPlanTextParser.parse(obRaw(h2)))
    }

    @Test
    fun testSQLite查询计划不命中() {
        // SQLite `EXPLAIN QUERY PLAN`：`SCAN t` 形态，无 OB 表头
        val sqlite = "SCAN t\nUSE TEMP B-TREE FOR ORDER BY"
        assertNull(ObExplainPlanTextParser.parse(obRaw(sqlite)))
    }

    @Test
    fun testMySQL表格行不命中() {
        // MySQL TABLE 档是真正的多列表格（raw.headers 多列），不是 ASCII 树——防御性断言：
        // 即便把整表拼进单 cell，无 OB 表头也解析不出
        val mysql = "id | select_type | table | type | rows\n1 | SIMPLE | t | ALL | 100"
        assertNull(ObExplainPlanTextParser.parse(obRaw(mysql)))
    }

    @Test
    fun testPG文本树不命中() {
        assertNull(ObExplainPlanTextParser.parse(obRaw("Seq Scan on big_table  (cost=0.00..35.50 rows=2550 width=8)")))
    }

    @Test
    fun test仅表头零数据行判失败() {
        // 表头命中但无任何列数一致的数据行 = 解析失败（fail-closed → 面板回退原生）
        val headerOnly = """
            ==========================================
            |ID|OPERATOR  |NAME|EST.ROWS|EST.TIME(us)|
            ==========================================
            Outputs & filters:
              0 - output([*]), filter(nil)
        """.trimIndent()
        assertNull(ObExplainPlanTextParser.parse(obRaw(headerOnly)))
    }

    @Test
    fun test空结果判失败() {
        assertNull(ObExplainPlanTextParser.parse(McpExplainFormatter.RawResult(listOf("Query Plan"), emptyList())))
        assertNull(ObExplainPlanTextParser.parse(McpExplainFormatter.RawResult(listOf("Query Plan"), listOf(listOf("")))))
    }

    @Test
    fun test多cell扫描命中即返回() {
        // 防御性：OB 实际 1 行 1 列，容忍 cell 拆分形态变化（首个命中 cell 出结果）
        val parsed = ObExplainPlanTextParser.parse(
            McpExplainFormatter.RawResult(
                listOf("Query Plan"),
                listOf(listOf(obPlan("TABLE RANGE SCAN", "orders", "90000"))),
            ),
        ) ?: error("OB 样本必须解析成功")
        assertEquals(listOf("1", "TABLE RANGE SCAN", "orders", "90000", "42"), parsed.rows[1])
    }
}
