package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JList

/** O-146/V179：只锁模型、滚动语义和渲染片段；不代替 Light/极窄栏真机视觉验收。 */
class SqlListV179HeadlessTest : BasePlatformTestCase() {
    private lateinit var manager: SqlLogManager
    private var savedMode = ""
    // T-MagpieGems W2：脱敏语义由 displayMaskMode 承载（保存/恢复改用模式串）
    private var savedMask = "OFF"
    private var savedCrudStyle = ""
    private var savedThreshold = 0L

    override fun setUp() {
        super.setUp()
        val s = PluginSettings.getInstance().state
        savedMode = s.logListSqlDisplayMode
        // T-MagpieGems W2：脱敏语义由 displayMaskMode 承载
        savedMask = s.displayMaskMode
        savedCrudStyle = s.crudColorStyle
        savedThreshold = s.slowQueryThresholdMs
        s.logListSqlDisplayMode = PluginSettings.DEFAULT_LOG_LIST_DISPLAY_MODE
        s.displayMaskMode = "OFF"
        s.crudColorStyle = SqlListTypeColor.STYLE_BAR
        s.slowQueryThresholdMs = 1000
        manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
    }

    override fun tearDown() {
        try {
            manager.dispose()
            val s = PluginSettings.getInstance().state
            s.logListSqlDisplayMode = savedMode
            s.displayMaskMode = savedMask
            s.crudColorStyle = savedCrudStyle
            s.slowQueryThresholdMs = savedThreshold
        } finally {
            super.tearDown()
        }
    }

    private fun entry(n: Int) = LogSqlEntry(
        rawSql = "SELECT id FROM v179_orders WHERE id = $n",
        restoredSql = "SELECT id FROM v179_orders WHERE id = $n",
        params = emptyList(), timestamp = n.toLong(), executionTimeMs = n.toLong(),
    )

    private fun layout(c: Component) {
        if (c is Container) {
            c.doLayout()
            c.components.forEach(::layout)
        }
    }

    private fun text(c: SimpleColoredComponent): String = buildString {
        val parts = c.iterator()
        while (parts.hasNext()) append(parts.next())
    }

    fun test默认200条滚动与头淘汰_模型对象身份及跟随暂停() {
        assertEquals("默认 KEYWORD_WRAP 才是被测热路径", SqlListDisplayMode.KEYWORD_WRAP,
            PluginSettings.getInstance().sqlListDisplayMode())
        assertEquals("夹具使用产品默认容量", PluginSettings.DEFAULT_LOG_MAX_COUNT, manager.entries.maxEntries)
        val panel = SqlLogPanel(project, manager)
        try {
            panel.setSize(640, 420)
            layout(panel)
            (1..200).forEach { manager.entries.addEntry(entry(it)) }
            UIUtil.dispatchAllInvocationEvents()
            layout(panel)
            val list = panel.list
            val model = list.model
            val scroll = requireNotNull(panel.listScrollPaneForTest)
            val bar = scroll.verticalScrollBar
            assertEquals(200, model.size)
            assertTrue("200 条必须真实超出视口", bar.maximum > bar.visibleAmount + 100)
            assertTrue("多行默认模式必须用可变高度", list.fixedCellHeight == -1)
            val surviving = model.getElementAt(99)
            val first = model.getElementAt(0)
            bar.value = 0
            list.ensureIndexIsVisible(170)
            assertTrue("滚动必须移动视口", bar.value > 0)
            assertSame("滚动不是重新生成模型", model, list.model)
            assertSame("滚动不能重建既有条目", surviving, model.getElementAt(99))
            panel.resumeTailFollowForTest()
            // 故意把视口放中段但不派用户滚动事件：FOLLOWING 态新尾巴必须主动贴底，
            // 不能靠 Swing 的空列表/首尾边界自动调整而假绿。
            bar.value = (bar.maximum - bar.visibleAmount) / 3
            assertTrue("夹具必须离底部有距离", bar.value + bar.visibleAmount < bar.maximum - 100)
            manager.entries.addEntry(entry(201))
            UIUtil.dispatchAllInvocationEvents()
            layout(panel)
            assertSame("满容量更新仍复用模型", model, list.model)
            assertEquals(200, model.size)
            assertFalse("头部旧对象必须淘汰", (0 until model.size).any { model.getElementAt(it) === first })
            assertSame("中段旧对象不得随头淘汰重建", surviving, model.getElementAt(98))
            assertEquals(entry(201).restoredSql, model.getElementAt(199).restoredSql)
            assertTrue("头淘汰且长度不变仍须贴底", bar.value + bar.visibleAmount >= bar.maximum - 1)

            scroll.mouseWheelListeners.forEach { it.mouseWheelMoved(MouseWheelEvent(scroll,
                MouseEvent.MOUSE_WHEEL, 1L, 0, 5, 5, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1)) }
            assertEquals(TailFollowMode.PAUSED, panel.tailFollowModeForTest)
            bar.value = 0
            manager.entries.addEntry(entry(202))
            UIUtil.dispatchAllInvocationEvents()
            layout(panel)
            assertEquals("暂停态不得被头淘汰拉到底", 0, bar.value)
            assertEquals(200, model.size)
            assertSame(surviving, model.getElementAt(97))
            assertEquals(entry(202).restoredSql, model.getElementAt(199).restoredSql)
        } finally {
            panel.dispose()
        }
    }

    fun test200条搜索排序去重_真实面板管线的反例() {
        val panel = SqlLogPanel(project, manager)
        try {
            (1..199).forEach { manager.entries.addEntry(entry(it)) }
            manager.entries.addEntry(entry(199).copy(timestamp = 200L))
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("去重关时两个相同 SQL 均须保留", 200, panel.visibleEntryCount)
            assertEquals(0, manager.entries.dedupeCount(entry(199).restoredSql))
            panel.searchFieldForTest.text = "id = 199"
            panel.applySearchTextForTest()
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("搜索应保留两条而不是首条或全部", 2, panel.visibleEntryCount)
            assertEquals(listOf(entry(199).restoredSql, entry(199).restoredSql), panel.visibleEntries)
            panel.searchFieldForTest.text = "v179_not_found"
            panel.applySearchTextForTest()
            assertEquals("负例应为零命中", 0, panel.visibleEntryCount)
            panel.searchFieldForTest.text = ""
            panel.applySearchTextForTest()
            UIUtil.dispatchAllInvocationEvents()
            panel.selectSortModeForTest(SortMode.DURATION_DESC)
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(200, panel.visibleEntryCount)
            assertEquals("耗时降序第一条是 199ms", entry(199).restoredSql, panel.visibleEntries.first())
            assertEquals("耗时降序末条是 1ms", entry(1).restoredSql, panel.visibleEntries.last())
            panel.dedupeCheckBoxForTest.doClick()
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("两条相同 SQL 只应裁去一条", 199, panel.visibleEntryCount)
            assertEquals(1, manager.entries.dedupeCount(entry(199).restoredSql))
            assertEquals("去重不应改变无关行", 199, panel.visibleEntries.toSet().size)
            panel.selectSortModeForTest(SortMode.TIME_ASC)
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(entry(1).restoredSql, panel.visibleEntries.first())
            assertEquals(entry(199).restoredSql, panel.visibleEntries.last())
        } finally {
            panel.dispose()
        }
    }

    fun test真实FontMetrics_中文与补充平面字符裁切不越预算或切断代理对() {
        val list = JList<LogSqlEntry>().apply { setSize(220, 100) }
        val attrs = com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES
        val original = "SELECT 😀 中文字段, 😀 中文字段 FROM v179_orders WHERE id = 199"
        val fragments = SqlListFragmentRenderer.renderFragments(original)
        val font = list.font
        val metrics = list.getFontMetrics(font)
        val budget = metrics.stringWidth("SELECT 😀 中文字段")
        assertTrue("夹具应比预算更宽", fragments.sumOf { metrics.stringWidth(it.text) } > budget)
        val clipped = clipSqlFragments(fragments, budget) { value, _ -> metrics.stringWidth(value) }
        val visible = clipped.joinToString("") { it.text }
        assertTrue("裁切须能辨别完整展示与省略", visible.endsWith("…"))
        assertTrue("以实际 FontMetrics 断言，而非字符数或耗时", clipped.sumOf { metrics.stringWidth(it.text) } <= budget)
        assertFalse("不能留下半个 emoji 代理字符", visible.indices.any { i ->
            Character.isHighSurrogate(visible[i]) && (i + 1 == visible.length || !Character.isLowSurrogate(visible[i + 1])) ||
                Character.isLowSurrogate(visible[i]) && (i == 0 || !Character.isHighSurrogate(visible[i - 1]))
        })
        assertEquals("裁切不修改原 SQL", "SELECT 😀 中文字段, 😀 中文字段 FROM v179_orders WHERE id = 199", original)
        assertTrue("源片段不能就地覆盖", fragments.joinToString("") { it.text } == original)
        assertTrue("预算外内容不允许误报全显示", visible != original)
    }

    fun test复合条目_日志与历史选择失焦以及脱敏缓存键() {
        val sql = "SELECT phone FROM v179_orders WHERE phone = '13812345678'"
        val e = LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList(),
            timestamp = 1_700_000_000_000L, sequenceNumber = 77, source = SqlSource.MCP,
            executionTimeMs = 2000, databaseName = "MySQL · audit@localhost:3306",
            sourceFile = "src/main/OrderMapper.java", sourceLine = 42,
            errorText = "SQLException: denied", captureProvenance = CaptureProvenance.MERGED)
        val list = JList<LogSqlEntry>().apply { setSize(1200, 400) }
        val live = LogEntryListCellRenderer().apply {
            dedupeCounts = mapOf(e.dedupeKey to 3)
            templateCounts = mapOf(e.structureKey to 4)
        }
        val history = HistoryCellRenderer().apply { templateCounts = mapOf(e.structureKey to 4) }
        val expectedCluster = MpSqlVisualizerBundle.message("log.panel.templateBadge", 4)
        fun render(selected: Boolean, masked: Boolean, historical: Boolean): Pair<String, String> {
            PluginSettings.getInstance().state.displayMaskMode = if (masked) "MASK" else "OFF"
            val r = if (historical) history else live
            r.getListCellRendererComponent(list, e, 0, selected, false) // 失焦选中态
            val meta = if (historical) history.metaLine else live.metaLine
            val lines = if (historical) history.sqlLines else live.sqlLines
            return text(meta) to lines.joinToString("\n") { text(it) }
        }
        val (liveMeta, liveSql) = render(false, false, false)
        val (histMeta, histSql) = render(false, false, true)
        for (meta in listOf(liveMeta, histMeta)) {
            assertTrue(meta, meta.startsWith("#77 "))
            assertTrue(meta, meta.contains("2000ms"))
            assertTrue(meta, meta.contains("[MySQL]"))
            assertTrue(meta, meta.contains("[MCP]"))
            assertTrue(meta, meta.contains("[OrderMapper.java:42]"))
            assertTrue(meta, meta.contains(expectedCluster))
            assertFalse("正文不得混入元信息", meta.contains("SELECT phone"))
        }
        assertTrue(liveMeta, liveMeta.contains("⨉3"))
        assertTrue(liveMeta, liveMeta.contains("⛔ SQLException"))
        assertFalse("历史没有实时去重和错误片段", histMeta.contains("⨉3") || histMeta.contains("⛔"))
        assertTrue(liveSql, liveSql.contains("13812345678"))
        assertTrue(histSql, histSql.contains("13812345678"))
        assertTrue("默认模式确实按子句换行", live.sqlLines.size >= 2)
        assertTrue(live.colorBar.isOpaque && history.colorBar.isOpaque)
        val cached = requireNotNull(e.cachedListLineFragments)
        assertEquals(SqlListDisplayMode.KEYWORD_WRAP, e.cachedListLineFragmentsMode)
        render(false, false, false)
        assertSame("跨两种渲染器的同态缓存可复用", cached, e.cachedListLineFragments)
        val (selectedMeta, selectedSql) = render(true, false, false)
        assertTrue(selectedMeta, selectedMeta.contains("⨉3") && selectedMeta.contains(expectedCluster))
        assertTrue(selectedSql, selectedSql.contains("13812345678"))
        assertEquals("失焦选中仍使用平台选中填充", list.selectionBackground, live.background)
        assertEquals(list.selectionForeground, live.metaLine.foreground)
        assertEquals(list.selectionBackground, history.apply {
            render(true, false, true)
        }.background)
        assertTrue("失焦选中不丢 CRUD 标记", live.colorBar.isOpaque && history.colorBar.isOpaque)

        for (historical in listOf(false, true)) {
            val (_, maskedSql) = render(false, true, historical)
            assertTrue(maskedSql, maskedSql.contains("***"))
            assertFalse("开启脱敏不能复用明文缓存", maskedSql.contains("13812345678"))
            // T-MagpieGems W2：缓存键由布尔脱敏态改为 MaskMode 名（OFF/WARN/MASK/BLOCK 四态互异）
            assertEquals("MASK", e.cachedListLineFragmentsMaskMode)
        }
        assertFalse("脱敏态需重新计算语义缓存", cached === e.cachedListLineFragments)
        for (historical in listOf(true, false)) {
            val (_, plainSql) = render(false, false, historical)
            assertTrue("关闭脱敏不能沿用打码缓存", plainSql.contains("13812345678"))
            assertEquals("OFF", e.cachedListLineFragmentsMaskMode)
        }
    }
}
