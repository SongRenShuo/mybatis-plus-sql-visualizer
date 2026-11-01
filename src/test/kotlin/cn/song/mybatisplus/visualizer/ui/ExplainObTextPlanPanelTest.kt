package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate
import cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.OceanBaseDialect
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.sql.ExplainSupport
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-ObPanelAdvice：OB TEXT 计划进结构化面板的接线行为测试。
 *
 * 成功路径：单列 `Query Plan` ASCII 树注入 → 解析成结构化行列进 [ExplainPlanResultView]
 * （表格模型列名 = OB 表头），索引建议条同时亮起（建议按原始行列计算——建议器既有输入形态）。
 * 失败路径：非 OB 形态文本注入 → 对话框仍被捕获但内容不是结构化视图（fail-closed 回退
 * 原生链的可观察收口——`submitExplainDirect` 的零源分支经 showExplainErrorDialog 走同一 sink）。
 */
class ExplainObTextPlanPanelTest : BasePlatformTestCase() {

    /** 必须是 public：生产反射按 getUrl 取目标方言，private 嵌套类会让 invoke 失败。 */
    class ExplainTarget {
        @Suppress("unused")
        fun getUrl(): String = "jdbc:oceanbase://localhost:2881/demo"
    }

    /** 与 ExplainIndexAdvisorTest/ObExplainPlanTextParserTest 同源的真机留证形态。 */
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
        """.trimIndent()

    /**
     * 真机原文照录（2026-11-01 真机：OB CE 4.3.5.6 `103.36.220.245:25881/xc3_test`，
     * `EXPLAIN SELECT * FROM t_order`，IDE 控制台单列 `Query Plan`）：**一行一个 cell**。
     * 首版面板用例只喂了合成形态（单 cell 多行），真机形态恒 null → toast「解析不出结构化行列」。
     */
    private val realObLines = listOf(
        "",
        "|ID|OPERATOR |NAME |EST.ROWS|EST.TIME(us)|",
        "",
        "|0 |TABLE FULL SCAN|t_order|500000 |3 |",
        "",
        "Outputs & filters:",
        "  ------------------------------------",
        "  output([t_order.id], [t_order.order_no], [t_order.amount])",
    )

    /** 路由钉住：OceanBase 必须仍登记 TEXT 档 + structuredTextPlan（否则本组测的是别的链）。 */
    fun testOb路由前提() {
        assertEquals(ExplainOutputFormat.TEXT, ExplainSupport.formatFor("OceanBase"))
        assertTrue(ExplainSupport.entryFor("OceanBase")!!.structuredTextPlan)
    }

    /** 真机形态端到端：单列 × 多行 → 解析成结构化行 → 进面板（表格 + 建议条）。 */
    fun testOb真机单列多行解析成功进结构化面板() {
        val sql = "SELECT * FROM t_order"
        val p = SqlPreviewPanel(project)
        Disposer.register(testRootDisposable, p)
        val settings = PluginSettings.getInstance().state
        val previousStructured = settings.explainStructuredRender
        val history = SqlHistoryManager.getInstance()
        history.clear()
        var captured: ExplainPlanDialog? = null
        try {
            p.showResult(PreviewResult(sql, sql, emptyList(), null, null, null, renderedDialect = OceanBaseDialect))
            p.nativeEditorDoc = EditorFactory.getInstance().createDocument(sql)
            UIUtil.dispatchAllInvocationEvents()

            settings.explainStructuredRender = true
            explainBoundDsForTest = { ExplainTarget() }
            explainDatabaseAvailableForTest = { true }
            explainDialogSinkForTest = { captured = it }
            ExplainPanelChannel.structuredRunnerForTest = { _, _, _, _, onPlan, _ ->
                onPlan(
                    ExplainPanelChannel.StructuredOutcome(
                        McpExplainFormatter.RawResult(
                            listOf("Query Plan"),
                            realObLines.map { listOf(it) },
                        ),
                        5,
                        "demo",
                    ),
                )
            }

            p.submitExplainOrStructured(
                project,
                OceanBaseDialect,
                "EXPLAIN $sql",
                ExecutionDialectGate.Request(
                    renderMode = ExecutionDialectGate.RenderMode.AUTO,
                    renderedDialectName = "OceanBase",
                    intent = ExecutionDialectGate.Intent.EXPLAIN,
                    canLosslesslyRerender = false,
                    userTextUntouched = true,
                ),
            )

            val view = captured?.contentForTest()
            assertTrue("真机形态解析成功必须进结构化视图（首版在此回退原生）", view is ExplainPlanResultView)
            val model = (view as ExplainPlanResultView).tableModelForTest()
            assertNotNull("真机形态必须按 TABLE 渲染", model)
            assertEquals(
                listOf("ID", "OPERATOR", "NAME", "EST.ROWS", "EST.TIME(us)"),
                (0 until model!!.columnCount).map { model.getColumnName(it) },
            )
            assertEquals(1, model.rowCount)
            assertEquals("TABLE FULL SCAN", model.getValueAt(0, 1))
            assertEquals("t_order", model.getValueAt(0, 2))
            val hints = view.hintsTextForTest()
            assertNotNull("真机形态建议条必须亮起（TABLE FULL SCAN 超阈）", hints)
            assertTrue(hints!!.contains("TABLE_FULL_SCAN") && hints.contains("t_order"))
        } finally {
            explainBoundDsForTest = null
            explainDatabaseAvailableForTest = null
            explainDialogSinkForTest = null
            ExplainPanelChannel.structuredRunnerForTest = null
            settings.explainStructuredRender = previousStructured
            history.clear()
            p.clear()
        }
    }

    fun testObTextPlan解析成功进结构化面板() {
        val sql = "SELECT * FROM bench_items WHERE id = 1"
        val p = SqlPreviewPanel(project)
        Disposer.register(testRootDisposable, p)
        val settings = PluginSettings.getInstance().state
        val previousStructured = settings.explainStructuredRender
        val history = SqlHistoryManager.getInstance()
        history.clear()
        var captured: ExplainPlanDialog? = null
        try {
            p.showResult(PreviewResult(sql, sql, emptyList(), null, null, null, renderedDialect = OceanBaseDialect))
            p.nativeEditorDoc = EditorFactory.getInstance().createDocument(sql)
            UIUtil.dispatchAllInvocationEvents()
            val beforeDoc = p.nativeEditorDoc?.text
            val beforeConsole = p.embeddedConsole

            settings.explainStructuredRender = true
            explainBoundDsForTest = { ExplainTarget() }
            explainDatabaseAvailableForTest = { true }
            explainDialogSinkForTest = { captured = it }
            ExplainPanelChannel.structuredRunnerForTest = { _, _, _, _, onPlan, _ ->
                onPlan(
                    ExplainPanelChannel.StructuredOutcome(
                        McpExplainFormatter.RawResult(
                            listOf("Query Plan"),
                            listOf(listOf(obPlan("TABLE FULL SCAN", "bench_items", "500000"))),
                        ),
                        5,
                        "demo",
                    ),
                )
            }

            p.submitExplainOrStructured(
                project,
                OceanBaseDialect,
                "EXPLAIN $sql",
                ExecutionDialectGate.Request(
                    renderMode = ExecutionDialectGate.RenderMode.AUTO,
                    renderedDialectName = "OceanBase",
                    intent = ExecutionDialectGate.Intent.EXPLAIN,
                    canLosslesslyRerender = false,
                    userTextUntouched = true,
                ),
            )

            assertEquals("OB 结构化 EXPLAIN 不得改写预览编辑器", beforeDoc, p.nativeEditorDoc?.text)
            assertNotNull("OB 解析成功必须经对话框展示", captured)
            val view = captured!!.contentForTest()
            assertTrue("内容应为结构化计划视图", view is ExplainPlanResultView)
            val model = (view as ExplainPlanResultView).tableModelForTest()
            assertNotNull("解析产物应按 TABLE 渲染（表格模型非空）", model)
            assertEquals(
                listOf("ID", "OPERATOR", "NAME", "EST.ROWS", "EST.TIME(us)"),
                (0 until model!!.columnCount).map { model.getColumnName(it) },
            )
            assertEquals("解析后行数 = 计划行数", 2, model.rowCount)
            val hints = view.hintsTextForTest()
            assertNotNull("OB 建议条必须亮起（TABLE FULL SCAN 超阈）", hints)
            assertTrue("建议条应含规则码 TABLE_FULL_SCAN", hints!!.contains("TABLE_FULL_SCAN"))
            assertTrue("建议条目标应为 OB NAME 列取值", hints.contains("bench_items"))
        } finally {
            explainBoundDsForTest = null
            explainDatabaseAvailableForTest = null
            explainDialogSinkForTest = null
            ExplainPanelChannel.structuredRunnerForTest = null
            settings.explainStructuredRender = previousStructured
            history.clear()
            p.clear()
        }
    }

    fun testObTextPlan解析失败回退原生不进结构化视图() {
        val sql = "SELECT * FROM bench_items WHERE id = 1"
        val p = SqlPreviewPanel(project)
        Disposer.register(testRootDisposable, p)
        val settings = PluginSettings.getInstance().state
        val previousStructured = settings.explainStructuredRender
        val history = SqlHistoryManager.getInstance()
        history.clear()
        var captured: ExplainPlanDialog? = null
        try {
            p.showResult(PreviewResult(sql, sql, emptyList(), null, null, null, renderedDialect = OceanBaseDialect))
            p.nativeEditorDoc = EditorFactory.getInstance().createDocument(sql)
            UIUtil.dispatchAllInvocationEvents()

            settings.explainStructuredRender = true
            explainBoundDsForTest = { ExplainTarget() }
            explainDatabaseAvailableForTest = { true }
            explainDialogSinkForTest = { captured = it }
            // 注入非 OB 形态计划文本（PG 文本树）→ 解析必须失败 → 回退原生链。
            // explainTargetForTest = { null }：回退链 submitExplainDirect 的零源分支收口
            // （showExplainErrorDialog 走同一 dialog sink，不触发真实执行链）。
            explainTargetForTest = { null }
            ExplainPanelChannel.structuredRunnerForTest = { _, _, _, _, onPlan, _ ->
                onPlan(
                    ExplainPanelChannel.StructuredOutcome(
                        McpExplainFormatter.RawResult(
                            listOf("Query Plan"),
                            listOf(listOf("Seq Scan on big_table  (cost=0.00..35.50 rows=2550 width=8)")),
                        ),
                        5,
                        "demo",
                    ),
                )
            }

            p.submitExplainOrStructured(
                project,
                OceanBaseDialect,
                "EXPLAIN $sql",
                ExecutionDialectGate.Request(
                    renderMode = ExecutionDialectGate.RenderMode.AUTO,
                    renderedDialectName = "OceanBase",
                    intent = ExecutionDialectGate.Intent.EXPLAIN,
                    canLosslesslyRerender = false,
                    userTextUntouched = true,
                ),
            )

            assertNotNull("解析失败回退仍应有对话框收口（无源引导）", captured)
            assertTrue(
                "解析失败不得展示结构化计划视图（回退原生呈现计划原文）",
                captured!!.contentForTest() !is ExplainPlanResultView,
            )
        } finally {
            explainBoundDsForTest = null
            explainDatabaseAvailableForTest = null
            explainDialogSinkForTest = null
            explainTargetForTest = null
            ExplainPanelChannel.structuredRunnerForTest = null
            settings.explainStructuredRender = previousStructured
            history.clear()
            p.clear()
        }
    }

    /** MySQL/PG 回归面钉住：非 OB 方言不进解析分支，视图仍按提交档渲染。 */
    fun testMysql表格行渲染行为不变() {
        val sql = "SELECT * FROM t_order WHERE id = 1"
        val p = SqlPreviewPanel(project)
        Disposer.register(testRootDisposable, p)
        val settings = PluginSettings.getInstance().state
        val previousStructured = settings.explainStructuredRender
        val history = SqlHistoryManager.getInstance()
        history.clear()
        var captured: ExplainPlanDialog? = null
        try {
            p.showResult(PreviewResult(sql, sql, emptyList(), null, null, null, renderedDialect = cn.song.mybatisplus.visualizer.sql.MySqlDialect))
            p.nativeEditorDoc = EditorFactory.getInstance().createDocument(sql)
            UIUtil.dispatchAllInvocationEvents()

            settings.explainStructuredRender = true
            explainBoundDsForTest = { ExplainTarget() }
            explainDatabaseAvailableForTest = { true }
            explainDialogSinkForTest = { captured = it }
            ExplainPanelChannel.structuredRunnerForTest = { _, _, _, _, onPlan, _ ->
                onPlan(
                    ExplainPanelChannel.StructuredOutcome(
                        McpExplainFormatter.RawResult(
                            listOf("id", "select_type"),
                            listOf(listOf("1", "SIMPLE")),
                        ),
                        5,
                        "demo",
                    ),
                )
            }

            p.submitExplainOrStructured(
                project,
                cn.song.mybatisplus.visualizer.sql.MySqlDialect,
                "EXPLAIN $sql",
                ExecutionDialectGate.Request(
                    renderMode = ExecutionDialectGate.RenderMode.AUTO,
                    renderedDialectName = "MySQL",
                    intent = ExecutionDialectGate.Intent.EXPLAIN,
                    canLosslesslyRerender = false,
                    userTextUntouched = true,
                ),
            )

            val view = captured?.contentForTest()
            assertTrue("MySQL 仍应走结构化视图", view is ExplainPlanResultView)
            val model = (view as ExplainPlanResultView).tableModelForTest()
            assertEquals(
                listOf("id", "select_type"),
                (0 until model!!.columnCount).map { model.getColumnName(it) },
            )
            assertNull("MySQL 样本无索引建议", view.hintsTextForTest())
        } finally {
            explainBoundDsForTest = null
            explainDatabaseAvailableForTest = null
            explainDialogSinkForTest = null
            ExplainPanelChannel.structuredRunnerForTest = null
            settings.explainStructuredRender = previousStructured
            history.clear()
            p.clear()
        }
    }
}
