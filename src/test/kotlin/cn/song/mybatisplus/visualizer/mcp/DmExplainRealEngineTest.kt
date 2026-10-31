package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.sql.ExplainSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * T-Fr45DmGuard S2 的真机闭环：**插件渲染出来的**达梦计划语句在真 DM8 引擎上确实返回计划正文。
 *
 * 与 [DmExplainPlanFixtureTest] 的分工：那个用 19 列夹具证明「有正文时格式化层不丢内容」，
 * 本类用**真实引擎**证明「渲染出来的语句本身能取到正文」——即 FR-0045 报障（计划正文为空）
 * 的修复在插件产物层面成立，而不是只在夹具层面成立。
 *
 * 外部实例模式（与 `sql.verify.DialectDockerVerificationTest` 同款协议，缺省跳过）：
 * ```
 * ./gradlew test --tests "*DmExplainRealEngineTest" --no-configuration-cache \
 *   -Dmpvs.dialect.jdbcUrl="jdbc:dm://host:5236" \
 *   -Dmpvs.dialect.user=… -Dmpvs.dialect.password=…
 * ```
 * 指向非达梦实例时同样跳过（`-Dmpvs.dialect.name` 必须含 DM），避免拿别的引擎冒充达梦读数。
 *
 * ## 为什么三个断言要一起看
 *  - 「渲染产物是 `EXPLAIN FOR`」钉模板选择；
 *  - 「真机执行得到 19 列且含 OPERATION/COST」钉「计划正文非空」；
 *  - 「裸 `EXPLAIN` 在这个引擎上列数为 0」钉**根因**——它与第二条合起来才能说清
 *    「用户看到的空正文不是格式化层的锅」。只留前两条，将来有人把模板改回去，
 *    第三条会独立变红并把原因写在失败消息里。
 */
class DmExplainRealEngineTest {

    private val dialectName = "DM（达梦）"

    private fun connectOrSkip(): Connection {
        val url = System.getProperty("mpvs.dialect.jdbcUrl")
        assumeTrue("未提供 -Dmpvs.dialect.jdbcUrl，跳过达梦真机验证", !url.isNullOrBlank())
        val name = System.getProperty("mpvs.dialect.name").orEmpty()
        assumeTrue(
            "外部实例不是达梦（-Dmpvs.dialect.name=$name），跳过达梦真机验证",
            name.replace(" ", "").contains("DM"),
        )
        val user = System.getProperty("mpvs.dialect.user").orEmpty()
        val password = System.getProperty("mpvs.dialect.password").orEmpty()
        return DriverManager.getConnection(url, user, password)
    }

    @Test
    fun `渲染产物必须是 EXPLAIN FOR 且档位为 TABLE`() {
        val entry = ExplainSupport.entryFor(dialectName)
        assertNotNull("达梦应在 EXPLAIN 支持表内", entry)
        assertTrue(
            "达梦模板应为 EXPLAIN FOR（裸 EXPLAIN 在 DM8 上不返回结果集）：${entry!!.template}",
            entry.template!!.contains("EXPLAIN FOR", ignoreCase = true),
        )
        assertEquals("达梦档位应为 TABLE（结构化计划表）", ExplainOutputFormat.TABLE, entry.outputFormat)

        val rendered = DialectRegistry.byName(dialectName).renderExplainStatement("SELECT 1")
        assertNotNull("达梦应能渲染 EXPLAIN 语句", rendered)
        assertTrue("渲染产物应以 EXPLAIN FOR 开头：$rendered", rendered!!.trim().uppercase().startsWith("EXPLAIN FOR"))
    }

    @Test
    fun `真机执行渲染产物应返回非空计划正文`() {
        connectOrSkip().use { connection ->
            val rendered = DialectRegistry.byName(dialectName)
                .renderExplainStatement("SELECT 1")
                ?: error("达梦应能渲染 EXPLAIN 语句")
            val raw = connection.createStatement().use { statement ->
                statement.executeQuery(rendered).use { rs ->
                    val meta = rs.getMetaData()
                    val headers = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                    val rows = mutableListOf<List<String>>()
                    while (rs.next() && rows.size < 50) {
                        rows += (1..meta.columnCount).map { rs.getString(it).orEmpty() }
                    }
                    McpExplainFormatter.RawResult(headers, rows)
                }
            }
            assertEquals("达梦 EXPLAIN FOR 应返回 19 列计划表，实际列：${raw.headers}", 19, raw.headers.size)
            for (expected in listOf("OPERATION", "TAB_NAME", "COST", "ROW_NUMS")) {
                assertTrue("计划表缺少列 $expected：${raw.headers}", raw.headers.any { it.equals(expected, true) })
            }
            assertTrue("计划正文不能为空（FR-0045 报障形态）", raw.rows.isNotEmpty())
            val text = McpExplainFormatter.format(raw)
            assertTrue("格式化后的正文不能为空", text.isNotBlank())
            assertTrue("正文应含算子列 OPERATION：$text", text.contains("OPERATION"))
        }
    }

    @Test
    fun `裸 EXPLAIN 在本引擎上确实不返回结果集（根因钉子）`() {
        connectOrSkip().use { connection ->
            try {
                connection.createStatement().use { statement ->
                    statement.executeQuery("EXPLAIN SELECT 1").use { rs ->
                        assertEquals(
                            "裸 EXPLAIN 若返回了列，说明本实例形态已变，需重评 T-Fr45DmGuard 的根因结论",
                            0,
                            rs.metaData.columnCount,
                        )
                    }
                }
            } catch (expected: SQLException) {
                // 也算「拿不到计划」：不同小版本可能直接报错而不是返回空结果集
                assertTrue(
                    "报错也应属「无法从裸 EXPLAIN 取计划」的形态：${expected.message}",
                    expected.message.orEmpty().isNotEmpty(),
                )
            }
        }
    }
}
