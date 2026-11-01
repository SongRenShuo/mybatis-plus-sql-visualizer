package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OceanBase TEXT 计划树解析器单测（T-ObPanelAdvice）。
 *
 * 样本形态与 `ExplainIndexAdvisorTest` 同源（真机留证：社区镜像 oceanbase/oceanbase-ce:4.3.5-lts，
 * 单列 `Query Plan`，表头 `|ID|OPERATOR  |NAME|EST.ROWS|EST.TIME(us)|`）；跨方言不外泄是本组
 * 的另一半职责——H2/SQLite/MySQL/PG 样本必须解析不出（null → 面板侧不进 OB 结构化分支）。
 */
class ObExplainPlanTextParserTest {

    /** 与 ExplainIndexAdvisorTest.obPlan 同形态（真机留证口径），含 `---` 分隔线与 Outputs 说明段。 */
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

    @Test
    fun testOb样本解析为结构化行列() {
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
