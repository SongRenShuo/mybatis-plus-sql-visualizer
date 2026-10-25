// 自 master src/test/kotlin/.../ui/ExplainPlanResultViewUiTest.kt 移植（R33/R61/R90 + 基线行布局守卫）
// （k2j 适配：McpExplainFormatter.RawResult 带 truncated 位置参；SqlHistoryEntry 无参构造+字段写入；
//   ExplainPlanSnapshot 公有字段；ExplainPlanResultView 调用全走位置实参重载）
package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession
import cn.song.mybatisplus.visualizer.history.ExplainPlanKey
import cn.song.mybatisplus.visualizer.history.ExplainPlanSnapshot
import cn.song.mybatisplus.visualizer.history.SqlHistoryEntry
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import javax.swing.JLabel
import javax.swing.JList

/**
 * R33 EXPLAIN 结构化渲染视图组件行为测试（渲染判别测试）。
 *
 * 覆盖：MySQL 表格格式 → 面板内只读表格（行列保真）；PG JSON 树 → 可折叠树（模型非空）；
 * 索引建议联动（有建议显示、无建议不占位）；「回到原生控制台」入口显隐与回调；
 * R61 跨次对比条；R90 基线选择与持久化状态行；基线下拉宽度有界布局守卫。
 */
class ExplainPlanResultViewUiTest : BasePlatformTestCase() {

    private val mysqlHeaders = listOf("id", "select_type", "table", "type", "key", "rows", "Extra")

    private val mysqlRows = listOf(
        listOf("1", "SIMPLE", "tb_user", "ALL", "NULL", "99500", "Using where"),
        listOf("1", "SIMPLE", "tb_order", "ref", "idx_user", "10", ""),
    )

    fun testMySQL表格格式_行列保真() {
        val view = ExplainPlanResultView(
            "MySQL", "local@db", 42L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
        )
        val model = view.tableModelForTest()
        assertNotNull("TABLE 格式应构建表格模型", model)
        assertEquals(7, model!!.columnCount)
        assertEquals(2, model.rowCount)
        assertEquals("ALL", model.getValueAt(0, 3))
        assertEquals("idx_user", model.getValueAt(1, 4))
    }

    fun testJSON格式_树模型非空且无表格() {
        val json = """[{"Plan": {"Node Type": "Seq Scan", "Relation Name": "t", "Plan Rows": 10000}}]"""
        val view = ExplainPlanResultView(
            "PostgreSQL", "pg@local", 12L,
            McpExplainFormatter.RawResult(listOf("QUERY PLAN"), listOf(listOf(json)), false),
            ExplainOutputFormat.JSON,
        )
        val treeModel = view.treeModelForTest()
        assertNotNull("JSON 格式应构建树模型", treeModel)
        assertEquals(1, treeModel!!.getChildCount(treeModel.root))
        assertNull("JSON 格式不应构建表格模型", view.tableModelForTest())
    }

    fun test索引建议_有建议显示_无建议不占位() {
        val withHints = ExplainPlanResultView(
            "MySQL", null, 1L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
            listOf(ExplainIndexAdvisor.Hint("FULL_SCAN", "tb_user", "type=ALL full table scan")),
        )
        val hintText = withHints.hintsTextForTest()
        assertNotNull("有建议应显示建议条", hintText)
        assertTrue("建议条应含规则名", hintText!!.contains("FULL_SCAN"))
        assertTrue("建议条应含目标对象", hintText.contains("tb_user"))

        val noHints = ExplainPlanResultView(
            "MySQL", null, 1L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
        )
        assertFalse("无建议不占位", noHints.hintsComponentForTest().isVisible)
    }

    fun test回到原生控制台入口_显隐与回调() {
        var clicked = 0
        val view = ExplainPlanResultView(
            "MySQL", null, 1L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
            listOf(),
            { clicked++ },
        )
        val button = view.backButtonForTest()
        assertNotNull(button)
        assertTrue(button.isVisible)
        button.doClick()
        assertEquals(1, clicked)

        val noEntry = ExplainPlanResultView(
            "MySQL", null, 1L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
        )
        val hidden = noEntry.backButtonForTest()
        assertNotNull(hidden)
        assertFalse("无回退动作时入口不可见（保守增量：不挂死按钮）", hidden.isVisible)
    }

    fun test头部含方言与数据源() {
        val view = ExplainPlanResultView(
            "MariaDB", "prod@10.0.0.3", 88L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
        )
        val header = view.headerTextForTest()
        assertNotNull(header)
        assertTrue("头部应含方言", header!!.contains("MariaDB"))
        assertTrue("头部应含数据源名", header.contains("prod@10.0.0.3"))
    }

    fun testR90_任意历史基线切换不重新执行SQL() {
        var executed = 0
        fun baseline(rows: String) = ExplainPlanHistorySession.Baseline(
            rows,
            McpExplainFormatter.RawResult(
                mysqlHeaders,
                listOf(mysqlRows.first().toMutableList().apply { this[5] = rows }),
                false,
            ),
        )
        val view = ExplainPlanResultView(
            "MySQL", null, 1L,
            McpExplainFormatter.RawResult(mysqlHeaders, listOf(mysqlRows.first()), false),
            ExplainOutputFormat.TABLE,
            listOf(),
            { executed++ },
            null,
            listOf(baseline("100"), baseline("200"), baseline("300")),
            cn.song.mybatisplus.visualizer.history.ExplainPlanSaveResult.SAVED,
        )
        val selector = view.baselineSelectorForTest()
        assertEquals(3, selector.itemCount)
        selector.selectedIndex = 2
        assertTrue(view.compareTextForTest()!!.contains("300"))
        selector.selectedIndex = 1
        assertTrue(view.compareTextForTest()!!.contains("200"))
        assertEquals(0, executed)
        assertEquals("99500", view.tableModelForTest()!!.getValueAt(0, 5))
    }

    fun testBaselineLabelsChineseShanghai() = assertBaselineLabels("zh", "Asia/Shanghai", "19:20:35")

    fun testBaselineLabelsEnglishShanghai() = assertBaselineLabels("en", "Asia/Shanghai", "19:20:35")

    fun testBaselineLabelsChineseLosAngeles() = assertBaselineLabels("zh", "America/Los_Angeles", "04:20:35")

    fun testBaselineLabelsEnglishLosAngeles() = assertBaselineLabels("en", "America/Los_Angeles", "04:20:35")

    /** 真实 session → 真实 JComboBox renderer，不能只验证 bundle 或合成标签。 */
    private fun assertBaselineLabels(language: String, zone: String, localTime: String) {
        val settings = PluginSettings.getInstance()
        val oldLanguage = settings.state.language
        val oldZone = TimeZone.getDefault()
        val oldLocale = Locale.getDefault()
        try {
            settings.state.language = language
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            // 插件语言必须覆盖系统语言；固定环境且 finally 恢复，不依赖测试机配置。
            Locale.setDefault(if (language == "zh") Locale.US else Locale.SIMPLIFIED_CHINESE)
            val sql = "select * from tb_user"
            val key = ExplainPlanKey.create(
                "project-a", "eb03ba8b-a414-4453-b6e0-5bf55b34fed2",
                "MySQL", ExplainOutputFormat.TABLE, sql,
            )
            fun raw(rows: String, notices: List<String> = emptyList()) = McpExplainFormatter.RawResult(
                mysqlHeaders, listOf(mysqlRows.first().toMutableList().apply { this[5] = rows }), false, notices,
            )
            val manager = SqlHistoryManager()
            val entry = SqlHistoryEntry()
            entry.sql = sql
            entry.projectIdentity = key.projectIdentity()
            manager.add(entry)
            val session = ExplainPlanHistorySession()
            val target = Any()
            // notices 不持久化：复现真机同时出现会话项和已保存项的路径。
            val previous = raw("100", listOf("driver notice"))
            session.complete(manager, session.begin(manager, sql, key, target, null, true), previous, true)
            val capturedAtEpoch = Instant.parse("2026-10-16T11:20:35.827Z").toEpochMilli()
            val retained = entry.explainPlans.single().apply {
                id = "bc6000e5-1111-4111-8111-111111111111"
                capturedAt = capturedAtEpoch
            }
            val older = ExplainPlanSnapshot.create(key, raw("50")).apply {
                id = "aa7000f6-2222-4222-8222-222222222222"
                capturedAt = capturedAtEpoch
            }
            entry.explainPlans.add(older)
            val originalSnapshots = listOf(retained.id to retained.capturedAt, older.id to older.capturedAt)
            val current = raw("200")
            val completion = session.complete(
                manager,
                session.begin(manager, sql, key, target, null, true), current, true,
            )
            val view = ExplainPlanResultView(
                "MySQL", "mp_demo", 1L, current, ExplainOutputFormat.TABLE,
                listOf(), null, null, completion.baselines, null,
            )
            val selector = view.baselineSelectorForTest()
            val previousLabel = if (language == "zh") "上次执行（当前会话）" else "Previous execution (current session)"
            fun snapshotLabel(id: String) = if (language == "zh")
                "历史快照 2026-10-16 $localTime（本地时间） · #$id"
            else "Saved plan 2026-10-16 $localTime (local time) · #$id"
            val expected = listOf(previousLabel, snapshotLabel("bc6000e5"), snapshotLabel("aa7000f6"))
            assertEquals(3, selector.itemCount)
            val list = JList<String>()
            fun rendered(value: String, index: Int): String =
                (selector.renderer.getListCellRendererComponent(list, value, index, true, false) as JLabel).text
            // index=-1 是收起时的默认显示；其余为下拉列表实际 renderer 输出。
            assertEquals(previousLabel, rendered(selector.selectedItem as String, -1))
            expected.forEachIndexed { index, label ->
                val text = rendered(selector.getItemAt(index), index)
                assertEquals(label, text)
                assertFalse(text.contains("R61"))
                assertFalse(text.contains("T11:20:35.827Z"))
                assertFalse(text.contains(retained.id))
                selector.selectedIndex = index
                assertEquals(label, rendered(selector.selectedItem as String, -1))
                val comparedRows = listOf("100", "100", "50")[index]
                assertTrue(view.compareTextForTest()!!.contains(comparedRows))
            }
            assertEquals(previous, completion.baselines.first().raw)
            assertEquals(
                originalSnapshots,
                entry.explainPlans.filter { plan -> plan.id in originalSnapshots.map { it.first } }
                    .map { it.id to it.capturedAt },
            )
            assertEquals(3, entry.explainPlans.size) // 切换下拉没有保存/执行第四次。
            assertEquals("200", view.tableModelForTest()!!.getValueAt(0, 5))

            // 无 notices 差异时仍按原规则去重，仅给已保存项补来源，不增删/重排基线。
            val deduplicatedSession = ExplainPlanHistorySession()
            val first = raw("300")
            deduplicatedSession.complete(
                manager,
                deduplicatedSession.begin(manager, sql, key, target, null, true), first, true,
            )
            val deduplicated = deduplicatedSession.complete(
                manager,
                deduplicatedSession.begin(manager, sql, key, target, null, true), raw("400"), true,
            )
            assertEquals(1, deduplicated.baselines.count { it.raw == first })
            val deduplicatedView = ExplainPlanResultView(
                "MySQL", null, 1L, raw("400"), ExplainOutputFormat.TABLE,
                listOf(), null, null, deduplicated.baselines, null,
            )
            val deduplicatedSelector = deduplicatedView.baselineSelectorForTest()
            val selected = (deduplicatedSelector.renderer.getListCellRendererComponent(
                list,
                deduplicatedSelector.selectedItem as String, -1, true, false,
            ) as JLabel).text
            assertTrue(selected.startsWith("$previousLabel · "))
            assertFalse(selected.contains("R61"))
        } finally {
            settings.state.language = oldLanguage
            TimeZone.setDefault(oldZone)
            Locale.setDefault(oldLocale)
        }
    }

    fun testR90_超限拒绝明确显示且当前结果不截断() {
        val cell = "x".repeat(270000)
        val view = ExplainPlanResultView(
            "MySQL", null, 1L,
            McpExplainFormatter.RawResult(listOf("plan"), listOf(listOf(cell)), false), ExplainOutputFormat.TABLE,
            listOf(), null, null, listOf(),
            cn.song.mybatisplus.visualizer.history.ExplainPlanSaveResult.TOO_LARGE,
        )
        assertTrue(view.historyStatusForTest().contains("256 KiB"))
        assertEquals(cell, view.tableModelForTest()!!.getValueAt(0, 0))
        assertFalse(view.baselineSelectorForTest().isVisible)
    }

    // ==== R61：跨次对比条（照抄索引建议条形态——null 不占位、有内容才可见） ====

    fun testR61_无基线无对比条() {
        val view = ExplainPlanResultView(
            "MySQL", null, 1L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
        )
        assertNull("无 diff 参数不应有对比条", view.compareTextForTest())
    }

    fun testR61_对比条渲染增量与增删行() {
        val diff = ExplainPlanDiff.PlanDiff(
            listOf(
                ExplainPlanDiff.NodeChange(
                    "id=1 table=tb_user#0",
                    "id=1 table=tb_user (rows=99500)",
                    ExplainPlanDiff.MetricDelta<Long>(99500L, 500L),
                    null,
                    listOf(ExplainPlanDiff.FieldChange("type", "ALL", "ref")),
                ),
            ),
            listOf("id=1 table=tb_order (rows=10)"),
            emptyList(),
        )
        val view = ExplainPlanResultView(
            "MySQL", null, 1L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
            listOf(), null, diff, listOf(), null,
        )
        val text = view.compareTextForTest()
        assertNotNull("有 diff 应有对比条", text)
        assertTrue("应含节点标签", text!!.contains("id=1 table=tb_user"))
        assertTrue("应含行数增量（旧值）", text.contains("99500"))
        assertTrue("应含行数增量（新值）", text.contains("500"))
        assertTrue("应含字段变化", text.contains("type"))
        assertTrue("应含新增行", text.contains("tb_order"))
        // 渲染行清单本体（bundle 文案 + Locale.ROOT 数字格式）
        val lines = view.renderCompareLines(diff)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("99500"))
        assertTrue(lines[1].contains("tb_order"))
    }

    fun testR61_计划等价对比条显式告知() {
        val view = ExplainPlanResultView(
            "MySQL", null, 1L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
            listOf(), null, ExplainPlanDiff.PlanDiff(emptyList(), emptyList(), emptyList()), listOf(), null,
        )
        val text = view.compareTextForTest()
        assertNotNull("等价 diff 也应有对比条（显式无变化）", text)
        assertTrue(text!!.contains("unchanged") || text.contains("无变化"))
    }

    // ==== 布局守卫：基线下拉不得被 BoxLayout 拉伸到整屏宽（20261023 实测缺陷） ====

    private fun viewWithOneBaseline(): ExplainPlanResultView = ExplainPlanResultView(
        "MySQL", null, 1L,
        McpExplainFormatter.RawResult(mysqlHeaders, listOf(mysqlRows.first()), false),
        ExplainOutputFormat.TABLE,
        listOf(), null, null,
        listOf(
            ExplainPlanHistorySession.Baseline(
                "100",
                McpExplainFormatter.RawResult(mysqlHeaders, listOf(mysqlRows.first()), false),
            ),
        ),
        null,
    )

    fun testBaselineSelectorHasBoundedWidth() {
        val selector = viewWithOneBaseline().baselineSelectorForTest()
        assertTrue("有基线时下拉应可见", selector.isVisible)
        // 判据必须是 maximumSize：纵向 BoxLayout 拉伸子组件读的是 maximumSize，
        // 只设 preferredSize 时该断言会通过但实际渲染仍横跨整屏（20261023 反向验证实证）。
        assertTrue(
            "基线下拉最大宽度应有界（实际 ${selector.maximumSize.width}）",
            selector.maximumSize.width in 1..600,
        )
    }

    fun testBaselineSelectorSitsInHorizontalRowWithLabel() {
        val selector = viewWithOneBaseline().baselineSelectorForTest()
        // 下拉的父容器必须是横向布局，且同一行里还有一个标签（「选择历史基线对比…」）。
        // 旧实现把标签与下拉分别纵向堆叠，且下拉被撑满容器宽度。
        val parent = selector.parent
        assertNotNull("下拉必须有父容器", parent)
        assertTrue("下拉应放在横向布局容器里", parent.layout is java.awt.FlowLayout)
        val labels = parent.components.filterIsInstance<JBLabel>().map { it.text }
        assertTrue("同一行应有基线说明标签", labels.any { it.isNotBlank() })
    }
}
