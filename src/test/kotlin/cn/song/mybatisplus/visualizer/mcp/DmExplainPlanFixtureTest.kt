package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.history.ExplainPlanKey
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.sql.ExplainSupport
import cn.song.mybatisplus.visualizer.ui.ExplainPlanDiff
import cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-Fr45DmGuard S2（FR-0045 第 4 项 a）：DM8 EXPLAIN 正文字段的真机夹具回归。
 *
 * 真机读数（2026-10-31，DM8 DBMS 8.1.3.12 + 仓内 `DmJdbcDriver18-8.1.3.140`，MCP 裸 JDBC 通道）：
 *  - `EXPLAIN <sql>` **不返回结果集**：`executeQuery` 0 列 0 行；`execute()` 得
 *    `isResultSet=false, updateCount=0`，`getMoreResults()` 随即 -1——**这就是用户报障
 *    「计划正文为空、只剩 `dialect: DM（达梦）`」的形态**；
 *  - `EXPLAIN FOR <sql>` 返回 **19 列结构化计划表**（PLAN_ID…OPERATION/TAB_NAME/IDX_NAME/
 *    SCAN_TYPE/ROW_NUMS/COST/FILTER…），`SELECT …` 与 `DELETE … WHERE 1=0` 都得 4 行。
 *
 * 本类用该真实列集做夹具，钉住三件事：
 *  1. 档位与模板（TEXT/`EXPLAIN {sql}` → TABLE/`EXPLAIN FOR {sql}`）；
 *  2. 格式化输出非空且带 `OPERATION`/`TAB_NAME`（用户可见的「有正文」）；
 *  3. TABLE 档下游（结构化面板通道 / 历史落盘 / 计划对比 / 索引建议 / AI 摘要）对 DM 列集
 *     只降级不崩——`ROW_NUMS` 等 DM 专有列名不命中时跳过，`COST` 命中数值增量。
 *
 * 反向验证：把 `ExplainSupport` 的 DM 条目改回 `EXPLAIN {sql}` + TEXT → 模板/档位断言与
 * 空结果复现断言一起变红（说明用例拦得住用户报障形态）。
 */
class DmExplainPlanFixtureTest {

    /** DM8 `EXPLAIN FOR` 真机返回的 19 列（顺序即真机列序）。 */
    private val headers = listOf(
        "PLAN_ID", "PLAN_NAME", "CREATE_TIME", "LEVEL_ID", "OPERATION", "TAB_NAME", "IDX_NAME",
        "SCAN_TYPE", "SCAN_RANGE", "ROW_NUMS", "BYTES", "COST", "CPU_COST", "IO_COST",
        "FILTER", "JOIN_COND", "ADVICE_INFO", "PSTART", "PSTOP",
    )

    private fun row(operation: String, table: String, cost: String, rowNums: String): List<String> =
        headers.map { header ->
            when (header) {
                "PLAN_ID" -> "1"
                "LEVEL_ID" -> "0"
                "OPERATION" -> operation
                "TAB_NAME" -> table
                "ROW_NUMS" -> rowNums
                "COST" -> cost
                "CPU_COST" -> cost
                "IO_COST" -> "0"
                else -> ""
            }
        }

    private fun fixture(cost: String = "0", rowNums: String = "4"): McpExplainFormatter.RawResult =
        McpExplainFormatter.RawResult(
            headers,
            listOf(
                row("DELETE", "ARTICLE", cost, rowNums),
                row("INDEX SCAN", "ARTICLE", cost, rowNums),
                row("PRJT2", "", cost, rowNums),
                row("NSET2", "", cost, rowNums),
            ),
        )

    // ---------- 模板与档位 ----------

    @Test
    fun `DM 模板为 EXPLAIN FOR 且档位为 TABLE`() {
        assertEquals(
            "EXPLAIN FOR SELECT * FROM article WHERE id = 1",
            ExplainSupport.render("DM（达梦）", "SELECT * FROM article WHERE id = 1"),
        )
        assertEquals(ExplainOutputFormat.TABLE, ExplainSupport.formatFor("DM（达梦）"))
    }

    // ---------- 用户可见正文 ----------

    @Test
    fun `19 列真机夹具经格式化后有正文且含 OPERATION 与 TAB_NAME`() {
        val text = McpExplainFormatter.format(fixture())
        assertTrue("计划正文不得为空（用户报障形态的回归面）: '$text'", text.isNotBlank())
        assertTrue("列头应含 OPERATION: $text", text.contains("OPERATION"))
        assertTrue("列头应含 TAB_NAME: $text", text.contains("TAB_NAME"))
        assertTrue("行内容应含表名 ARTICLE: $text", text.contains("ARTICLE"))
        assertTrue("行内容应含算子 DELETE: $text", text.contains("DELETE"))
        assertEquals("19 列每行应有 18 个分隔符", 18, text.lines().first().count { it == '|' })
    }

    @Test
    fun `旧形态空结果集复现用户报障的空正文`() {
        // `EXPLAIN <sql>` 在 DM8 上返回 0 列 0 行——format 输出空串，工具输出只剩 dialect 行。
        assertEquals("", McpExplainFormatter.format(McpExplainFormatter.RawResult(emptyList(), emptyList())))
    }

    // ---------- TABLE 档下游语义（只降级不崩） ----------

    @Test
    fun `索引建议在 DM 列集上降级为空且不抛`() {
        // DM 没有 MySQL 的 type/key/Extra 列 → adviseMysql 不猜，返回空清单（不噪音）。
        val hints = ExplainIndexAdvisor.advise("DM（达梦）", headers, fixture().rows)
        assertTrue("DM 计划不应命中 MySQL 专属规则: $hints", hints.isEmpty())
    }

    @Test
    fun `计划对比能识别 COST 增量且对 ROW_NUMS 优雅降级`() {
        val diff = ExplainPlanDiff.diff(fixture(cost = "0"), fixture(cost = "12.5"), ExplainOutputFormat.TABLE)
        assertNotNull("TABLE 档两侧都有行时必须可比", diff)
        val costChange = diff!!.changed.firstOrNull()?.costDelta
        assertNotNull("COST 列（小写 cost）应产出数值增量", costChange)
        assertEquals(12.5, costChange!!.new, 0.0)
        assertTrue("ROW_NUMS 不在数值列白名单 → 走字段级变化，不崩", diff.changed.all { it.rowsDelta == null })
    }

    @Test
    fun `TABLE 档让 DM 计划可落历史`() {
        val format = ExplainSupport.formatFor("DM（达梦）")!!
        val key = ExplainPlanKey.create(
            projectIdentity = "project-a",
            uuid = "eb03ba8b-a414-4453-b6e0-5bf55b34fed2",
            dialect = "DM（达梦）",
            format = format,
            sql = "SELECT * FROM article WHERE id = 1",
        )
        assertTrue("TABLE 档应满足 persistable（4a 的附加收益：DM 计划进历史）", key.persistable)
    }
}
