package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.SqlSource
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ui.SimpleColoredComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.awt.Color

/**
 * 日志列表单元格渲染器 UI 测试（F-44-2/T-LogListFreeze-04 回归锁）：
 *
 * 片段通道（双 [SimpleColoredComponent]，T-TwoLine 两行结构）下锁定：
 *  - 通道根治断言：渲染行为 SimpleColoredComponent 系（JLabel-HTML 通道即 BasicHTML 解析 = Freeze 根因，不允许回潮）
 *  - 两行结构：元信息行在上（时间戳/耗时/来源/徽章）、SQL 高亮行在下，SQL 不混入元信息行
 *  - 超长 SQL（>160 摘要截断）渲染不异常
 *  - 耗时着色分档：慢查询红 `0xD32F2F`、偏慢橙 `0xE67E22`、正常灰 `0x9E9E9E`、选中白 `0xFFFFFF`（原 HTML 配色语义平移）
 *  - 去重 ⨉N 徽章随 dedupeCounts 渲染
 */
class SqlLogPanelRendererUiTest : BasePlatformTestCase() {

    private val renderer = LogEntryListCellRenderer()
    /** 真实（非空）列表实例；渲染器取 selectionBackground 等需 list 非空（生产环境 list 恒非空）。 */
    private val list = javax.swing.JList<Any>()

    /** 既有用例锁定单行语义（T-ListMode 默认关键字换行——模式相关行为由专区用例显式切模式锁定）。 */
    private var savedDisplayMode: String? = null

    override fun setUp() {
        super.setUp()
        val state = PluginSettings.getInstance().state
        savedDisplayMode = state.logListSqlDisplayMode
        state.logListSqlDisplayMode = "SINGLE_LINE"
    }

    override fun tearDown() {
        PluginSettings.getInstance().state.logListSqlDisplayMode =
            savedDisplayMode ?: PluginSettings.DEFAULT_LOG_LIST_DISPLAY_MODE
        super.tearDown()
    }

    /** 触发渲染并返回渲染组件（片段通道：元信息行/SQL 行经 [LogEntryListCellRenderer.metaLine]/[LogEntryListCellRenderer.sqlLine] 断言）。 */
    private fun render(entry: LogSqlEntry, selected: Boolean = false): LogEntryListCellRenderer {
        val c = renderer.getListCellRendererComponent(list, entry, 0, selected, false)
        return c as LogEntryListCellRenderer
    }

    /** 切显示模式渲染（T-ListMode 专区用例；自动随测试收尾还原）。 */
    private fun renderInMode(mode: String, entry: LogSqlEntry): LogEntryListCellRenderer {
        PluginSettings.getInstance().state.logListSqlDisplayMode = mode
        return render(entry)
    }

    private fun joinedText(component: SimpleColoredComponent): String {
        val sb = StringBuilder()
        val it = component.iterator()
        while (it.hasNext()) sb.append(it.next())
        return sb.toString()
    }

    /** 头部行文本（T-LogListHeader：注脚行已并入头部行，全部元信息都在这里）。 */
    private fun detail(r: RailCellRendererBase): String = r.metaTextForTest

    private fun entry(
        sql: String,
        ms: Long? = null,
        sourceFile: String? = null,
        sourceLine: Int = 0,
    ) = LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList(), timestamp = 1_700_000_000_000L, executionTimeMs = ms, sourceFile = sourceFile, sourceLine = sourceLine)

    fun test渲染组件为片段通道_非HTML标签() {
        val r = render(entry("SELECT * FROM user"))
        assertTrue("元信息行应为 SimpleColoredComponent（JLabel-HTML 通道不允许回潮）", r.metaLine is SimpleColoredComponent)
        assertTrue("SQL 行应含 SELECT", joinedText(r.sqlLine).contains("SELECT"))
        assertFalse("渲染文本不应为 HTML 包裹", joinedText(r.sqlLine).startsWith("<html>"))
        assertFalse("无耗时不应出现 ms", r.metaTextForTest.contains("ms"))
    }

    fun test两行结构_轨道在左SQL在右() {
        // T-LogListRail：原「元信息在上 / SQL 在下」改为「信息轨在左 / SQL 区在右」。
        // 来源类名随耗时进轨道；去重徽章宽度不可控，迁到 SQL 区下方注脚行。
        val sql = "SELECT * FROM repeated WHERE id = 1"
        renderer.dedupeCounts = mapOf(sqlFingerprint(sql) to 2)
        val r = render(entry(sql, sourceFile = "src/main/java/A.java", sourceLine = 42))
        val meta = r.metaTextForTest
        assertTrue("头部行含来源类名 A: $meta", meta.contains("A"))
        assertTrue("头部行含行号 :42: $meta", meta.contains(":42"))
        assertTrue("头部行含去重徽章", detail(r).contains("⨉2"))
        assertFalse("轨道不应混入 SQL", meta.contains("SELECT"))
        assertTrue("SQL 行完整保留语句（含分号结束符）", joinedText(r.sqlLine).contains("SELECT * FROM repeated WHERE id = 1;"))
    }

    fun test目标库角标_有库名渲染到元信息行() {
        // T-AgentDialectLoop：agent 条目带 databaseName → 元信息行出现 [MySQL] 角标
        val e = LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            timestamp = 1_700_000_000_000L,
            source = SqlSource.AGENT,
            databaseName = "MySQL",
        )
        val d = render(e).metaTextForTest
        assertTrue("注脚行应含目标库角标: $d", d.contains("[MySQL]"))
    }

    fun test目标库角标_未知库名不显示() {
        val e = LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            timestamp = 1_700_000_000_000L,
            source = SqlSource.AGENT,
        )
        val d = render(e).metaTextForTest
        assertFalse("无 databaseName 不应显示角标", d.contains("[MySQL]"))
    }

    fun testMCP徽标_来源MCP渲染到元信息行() {
        // T-McpAuditTrail T-MAT-05：MCP 执行类工具落历史的条目带 [MCP] 徽标（目标库角标后、来源前）
        val e = LogSqlEntry(
            rawSql = "SELECT id FROM user WHERE id = 1",
            restoredSql = "SELECT id FROM user WHERE id = 1",
            params = emptyList(),
            timestamp = 1_700_000_000_000L,
            source = SqlSource.MCP,
            databaseName = "MySQL",
            executionTimeMs = 7L,
        )
        val r = render(e)
        val d = r.metaTextForTest
        val meta = r.metaTextForTest
        assertTrue("注脚行应含 MCP 徽标: $d", d.contains("[MCP]"))
        assertTrue("MCP 徽标与目标库角标共存: $d", d.contains("[MySQL]"))
        assertTrue("耗时展示在轨道: $meta", meta.contains("7ms"))
    }

    fun testMCP徽标_非MCP来源不显示() {
        val e = LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            timestamp = 1_700_000_000_000L,
            source = SqlSource.MYBATIS,
        )
        val meta = joinedText(render(e).metaLine)
        assertFalse("非 MCP 来源不应显示 MCP 徽标: $meta", meta.contains("[MCP]"))
    }

    fun test特殊字符SQL_原文保真() {
        // 片段通道无 HTML 解析面——< > & 原样绘制即安全（原通道需转义，此处断言不转义不破版）
        val r = render(entry("SELECT * FROM t WHERE a < 5 AND b > 3 AND c & d"))
        val sqlText = joinedText(r.sqlLine)
        assertTrue("小于号原文保留", sqlText.contains("< 5"))
        assertTrue("大于号原文保留", sqlText.contains("> 3"))
        assertTrue("与号原文保留", sqlText.contains("& d"))
    }

    fun testSQL含标签样文本_不构成HTML结构() {
        val r = render(entry("SELECT '</span><script>'"))
        assertTrue("标签样文本原样保留（片段通道无注入面）", joinedText(r.sqlLine).contains("</span><script>"))
    }

    fun test窄侧栏按像素裁切SQL而复制源文本不变() {
        val original = "SELECT " + (1..40).joinToString(", ") { "column_$it" } + " FROM wide_table"
        list.setSize(170, 80)
        try {
            val entry = entry(original)
            val sql = joinedText(render(entry).sqlLine)
            assertTrue("可见 SQL 应有宽度省略号", sql.endsWith("…"))
            assertTrue("省略只发生在展示层", entry.restoredSql.endsWith("FROM wide_table"))
        } finally {
            list.setSize(0, 0)
        }
    }

    fun test超长SQL_渲染不异常且含截断摘要() {
        val longSql = "SELECT " + (1..200).joinToString(", ") { "col_$it" } + " FROM wide_table WHERE id = 1"
        assertTrue("构造的 SQL 超过 160 字符", longSql.length > 160)
        val r = render(entry(longSql))
        val sqlText = joinedText(r.sqlLine)
        assertTrue("摘要截断开头仍是 SELECT", sqlText.contains("SELECT"))
        assertTrue("超长 SQL 摘要含省略号", sqlText.contains("…"))
    }

    fun test慢查询耗时着色分档_红橙灰() {
        val pluginSettings = PluginSettings.getInstance().state
        val saved = pluginSettings.slowQueryThresholdMs
        try {
            pluginSettings.slowQueryThresholdMs = 1000
            assertEquals("慢查询(2000ms)应红色", Color(0xD32F2F), formatDurationFragment(2000L, isSelected = false).second.fgColor)
            assertEquals("偏慢(600ms >= 阈值/2)应橙色", Color(0xE67E22), formatDurationFragment(600L, isSelected = false).second.fgColor)
            // 分档语义：ms==null 才灰色；正常（< 阈值/2）为绿（原通道该断言蹭到时间戳灰，片段直读属性后修正）
            assertEquals("正常(50ms)应绿色", Color(0x4CAF50), formatDurationFragment(50L, isSelected = false).second.fgColor)
        } finally {
            pluginSettings.slowQueryThresholdMs = saved
        }
    }

    fun test选中态耗时着色用白色_不抢SQL高亮() {
        val f = formatDurationFragment(2000L, isSelected = true)
        assertEquals("选中态耗时套白色（避免与选中背景/SQL 高亮冲突）", Color(0xFFFFFF), f.second.fgColor)
    }

    fun test去重徽章随dedupeCounts渲染() {
        val sql = "SELECT * FROM repeated WHERE id = 1"
        renderer.dedupeCounts = mapOf(sqlFingerprint(sql) to 4)
        assertTrue("去重 ⨉4 徽章应渲染", render(entry(sql)).metaTextForTest.contains("⨉4"))

        renderer.dedupeCounts = mapOf(sqlFingerprint(sql) to 0)
        assertFalse("计数 0 不显示徽章", render(entry(sql)).metaTextForTest.contains("⨉"))

        renderer.dedupeCounts = emptyMap()
        assertFalse("无计数不显示徽章", render(entry(sql)).metaTextForTest.contains("⨉"))
    }

    fun test渲染返回值非空_组件有效() {
        val c = renderer.getListCellRendererComponent(list, entry("SELECT 1"), 0, false, false)
        assertNotNull("渲染组件不应为 null", c)
        assertTrue("渲染组件应为渲染器自身（含双行组件面板）", c is java.awt.Component)
    }

    // ---------- O-90：同模板 ×N 徽章 ----------

    fun test同模板徽章_达阈值渲染到元信息行() {
        val sql = "SELECT * FROM big_orders WHERE uid = 7"
        renderer.templateCounts = mapOf(structureFingerprint(sql) to 3)
        val d = render(entry(sql)).metaTextForTest
        assertTrue("达阈值应渲染同模板徽章: $d", d.contains(MpSqlVisualizerBundle.message("log.panel.templateBadge", 3)))
        // 与去重徽章相邻共存（本条无去重计数，仅模板徽章）
        renderer.templateCounts = mapOf(structureFingerprint(sql) to 5)
        assertTrue(
            "计数 5 显示 ×5",
            render(entry(sql)).metaTextForTest.contains(MpSqlVisualizerBundle.message("log.panel.templateBadge", 5)),
        )
    }

    fun test同模板徽章_未达阈值不渲染() {
        val sql = "SELECT * FROM small_orders WHERE uid = 7"
        renderer.templateCounts = mapOf(structureFingerprint(sql) to TEMPLATE_BADGE_MIN_COUNT - 1)
        assertFalse(
            "计数 ${TEMPLATE_BADGE_MIN_COUNT - 1} 未达阈值不渲染",
            joinedText(render(entry(sql)).metaLine)
                .contains(MpSqlVisualizerBundle.message("log.panel.templateBadge", TEMPLATE_BADGE_MIN_COUNT - 1)),
        )
        renderer.templateCounts = emptyMap()
        assertFalse(
            "无计数不渲染",
            joinedText(render(entry(sql)).metaLine).contains(MpSqlVisualizerBundle.message("log.panel.templateBadge", 3)),
        )
    }

    fun test同模板徽章片段_选中白未选中琥珀() {
        val sql = "SELECT * FROM t WHERE id = 1"
        val e = entry(sql)
        val counts = mapOf(structureFingerprint(sql) to 3)
        assertEquals(
            "未选中琥珀（与耗时偏慢同族）",
            Color(0xE67E22),
            formatTemplateCountFragment(e, counts, false)!!.second.fgColor,
        )
        assertEquals("选中白（不抢 SQL 高亮）", Color(0xFFFFFF), formatTemplateCountFragment(e, counts, true)!!.second.fgColor)
        assertNull(
            "未达阈值返回 null",
            formatTemplateCountFragment(e, mapOf(structureFingerprint(sql) to 2), false),
        )
        assertNull("无计数返回 null", formatTemplateCountFragment(e, emptyMap(), false))
    }

    // ---------- T-ListMode：三种显示模式（嫁接片段管线） ----------

    fun test单行模式_恒单行_完整SQL含结束符() {
        val r = renderInMode("SINGLE_LINE", entry("SELECT * FROM user WHERE id = 1"))
        assertEquals("单行模式 SQL 行组件恒 1 个", 1, r.sqlLines.size)
        assertTrue("完整 SQL 保留（含分号结束符）", joinedText(r.sqlLine).contains("SELECT * FROM user WHERE id = 1;"))
        val e2 = entry("SELECT 1")
        renderInMode("SINGLE_LINE", e2)
        assertNull("单行模式不走条目级片段缓存", e2.cachedListLineFragments)
    }

    fun test关键字换行模式_多子句分行且缓存生效() {
        val e = entry("SELECT * FROM orders WHERE id = 1")
        val r = renderInMode("KEYWORD_WRAP", e)
        assertTrue("关键字换行应多行（实际 ${r.sqlLines.size} 行）", r.sqlLines.size >= 3)
        assertTrue("首行 SELECT 开头", joinedText(r.sqlLines[0]).startsWith("SELECT"))
        assertTrue("FROM 独立成行", r.sqlLines.any { joinedText(it).startsWith("FROM") })
        assertTrue("WHERE 独立成行", r.sqlLines.any { joinedText(it).startsWith("WHERE") })
        assertNotNull("多行模式应写条目级片段缓存", e.cachedListLineFragments)
        assertEquals("缓存键记录当前模式", "KEYWORD_WRAP", e.cachedListLineFragmentsMode?.name)
    }

    fun test格式化模式_缩进行保真() {
        val r = renderInMode("FORMATTED", entry("SELECT * FROM t WHERE a = 1 AND b = 2"))
        assertTrue("格式化应多行", r.sqlLines.size >= 3)
        assertTrue(
            "AND 应独立成行且顶层缩进 4 空格（片段文本不折叠空格）",
            r.sqlLines.any { joinedText(it).startsWith("    AND") },
        )
    }

    fun test格式化模式不再只格式化首行摘要() {
        val full = "SELECT " + (1..40).joinToString(", ") { "c$it" } + " FROM record WHERE id = 42"
        val r = renderInMode("FORMATTED", entry(full))
        val visible = r.sqlLines.joinToString("\n") { joinedText(it) }
        assertTrue("全文最后的 WHERE 子句应在格式化模式可见", visible.contains("WHERE id = 42"))
    }

    fun test模式切换_缓存按模式键自动失效() {
        val e = entry("SELECT * FROM orders WHERE id = 1")
        val kw = renderInMode("KEYWORD_WRAP", e)
        val kwLines = kw.sqlLines.size
        val fm = renderInMode("FORMATTED", e)
        assertEquals("模式键已更新", "FORMATTED", e.cachedListLineFragmentsMode?.name)
        assertTrue(
            "格式化与关键字换行行数不同（缓存重算而非复用）",
            fm.sqlLines.size != kwLines,
        )
    }

    fun test元信息行不受显示模式影响() {
        val sql = "SELECT * FROM user WHERE id = 1"
        for (mode in listOf("SINGLE_LINE", "KEYWORD_WRAP", "FORMATTED")) {
            renderer.dedupeCounts = mapOf(sqlFingerprint(sql) to 2)
            val r = renderInMode(mode, entry(sql, sourceFile = "src/main/java/A.java", sourceLine = 42))
            val meta = r.metaTextForTest
            // T-LogListRail：来源类名在轨道、去重徽章在注脚行；两者都不得混入 SQL 文本
            assertTrue("[$mode] 头部行含来源类名: $meta", meta.contains("A"))
            assertTrue("[$mode] 头部行含去重徽章", detail(r).contains("⨉2"))
            assertFalse("[$mode] 轨道不混入 SQL", meta.contains("SELECT"))
        }
    }

    // ---------- T-LogRowNumber：行号片段 ----------

    fun test行号片段在元信息行最前() {
        val e = entry("SELECT 1").copy(sequenceNumber = 12L)
        val r = render(e)
        val meta = r.metaTextForTest
        assertTrue("元信息行含行号 #12", meta.contains("#12 "))
        assertTrue("行号应在片段序列最前", meta.startsWith("#12 "))
        assertFalse("SQL 不混入元信息行", meta.contains("SELECT"))
    }

    fun test无行号条目不渲染行号片段() {
        // sequenceNumber=0：历史文件读回 / round-trip 条目
        val r = render(entry("SELECT 1"))
        assertFalse("无行号不渲染 # 前缀", r.metaTextForTest.contains("#"))
    }

    // ---------- T-CrudColor：CRUD 类型颜色标记 ----------

    /** 在给定标记样式 + 固定四色下执行断言（其余状态原样恢复）。 */
    private fun withCrudColors(style: String, block: () -> Unit) {
        val s = PluginSettings.getInstance().state
        val savedStyle = s.crudColorStyle
        val saved = listOf(s.crudColorSelect, s.crudColorInsert, s.crudColorUpdate, s.crudColorDelete)
        try {
            s.crudColorStyle = style
            s.crudColorSelect = "#4A86E8"
            s.crudColorInsert = "#4CAF50"
            s.crudColorUpdate = "#E67E22"
            s.crudColorDelete = "#D32F2F"
            block()
        } finally {
            s.crudColorStyle = savedStyle
            s.crudColorSelect = saved[0]
            s.crudColorInsert = saved[1]
            s.crudColorUpdate = saved[2]
            s.crudColorDelete = saved[3]
        }
    }

    /** 渲染后取色条色（未着色 → null）。 */
    private fun barColorAfterRender(sql: String, selected: Boolean = false): Color? {
        render(entry(sql), selected)
        return renderer.colorBar.takeIf { it.isOpaque }?.background
    }

    fun test色条样式_四类型着色且OTHER不标记() {
        withCrudColors(SqlListTypeColor.STYLE_BAR) {
            assertEquals(Color(0x4A86E8), barColorAfterRender("SELECT * FROM user"))
            assertEquals(Color(0x4CAF50), barColorAfterRender("INSERT INTO user VALUES (1)"))
            assertEquals(Color(0xE67E22), barColorAfterRender("UPDATE user SET a = 1"))
            assertEquals(Color(0xD32F2F), barColorAfterRender("DELETE FROM user"))
            assertEquals("OTHER 不应着色（避免满屏色条稀释信号）",
                null, barColorAfterRender("MERGE INTO t USING s ON (1=1)"))
        }
    }

    fun test色条样式_选中态色条不丢色() {
        withCrudColors(SqlListTypeColor.STYLE_BAR) {
            // 选型核心依据：选中行仍保留类型色（平台填充只覆盖单元格背景，色条是独立子组件）
            assertEquals("选中态色条应仍为类型色", Color(0x4A86E8), barColorAfterRender("SELECT * FROM user", true))
        }
    }

    fun test关闭样式_色条不可见且背景走原逻辑() {
        withCrudColors(SqlListTypeColor.STYLE_OFF) {
            assertEquals(null, barColorAfterRender("SELECT * FROM user"))
            assertEquals("关闭态应与改造前一致（非选中取列表背景）", list.background, renderer.background)
            render(entry("SELECT * FROM user"), true)
            assertEquals("关闭态选中仍走平台填充", list.selectionBackground, renderer.background)
        }
    }

    fun test背景样式_未选中淡背景混色_选中走平台填充() {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND) {
            val bg = list.background
            val update = Color(0xE67E22)
            render(entry("UPDATE user SET a = 1"))
            assertEquals("未选中应为 14% 混色（类型淡背景标记保留）",
                SqlListTypeColor.blend(bg, update, SqlListTypeColor.MIX_NORMAL), renderer.background)
            render(entry("UPDATE user SET a = 1"), true)
            assertEquals("选中态交给平台选中色（T-UN-09 描边退役）",
                list.selectionBackground, renderer.background)
        }
    }

    fun test背景样式_选中态不再安装描边边框() {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND) {
            // T-LogListHeader：Border 按模式预建两个实例（常规 12px / 单行 8px）。
            // 断言口径改为「同一模式内实例恒定」——选中态不得换 Border（描边已退役）。
            render(entry("UPDATE user SET a = 1"))
            val unselected = renderer.border
            render(entry("UPDATE user SET a = 1"), true)
            assertSame("选中态与未选中必须共用同一 Border 实例（描边已退役）", unselected, renderer.border)
        }
    }

    fun test背景样式与色条样式_选中态前景均走selectionForeground() {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND) {
            render(entry("SELECT 1", ms = 5L), true)
            assertEquals("选中态底色已是平台选中填充，前景应转 selectionForeground（T-UN-09）",
                list.selectionForeground, renderer.metaLine.foreground)
        }
        withCrudColors(SqlListTypeColor.STYLE_BAR) {
            render(entry("SELECT 1", ms = 5L), true)
            assertEquals("色条样式选中前景仍为 selectionForeground（现状不变）",
                list.selectionForeground, renderer.metaLine.foreground)
        }
    }

    // ---------- T-CrudColor：渲染热路径无副作用（性能守卫） ----------

    /**
     * 同状态连续渲染不得产生 Swing 属性实例变更。
     *
     * 依据（JBR 21 实测）：`setBorder(新实例)` 即使同值也 `repaint() +1`、`setBackground(新 Color 同值)`
     * 同样 `+1`；传**同一实例**则零副作用。逐格无脑赋值 = 每格一次重绘调度，
     * 与 T-LogListFreeze 的成因同类，故用**实例同一性**锁定。
     */
    fun test色条样式_重复渲染同类型条目不产生属性实例变更() {
        withCrudColors(SqlListTypeColor.STYLE_BAR) {
            render(entry("SELECT 1"))
            val barBg = renderer.colorBar.background
            val border = renderer.border
            val cellBg = renderer.background
            render(entry("SELECT 2"))
            assertSame("同色格应复用同一 Color 实例", barBg, renderer.colorBar.background)
            assertSame("同状态应复用同一 Border 实例", border, renderer.border)
            assertSame("同状态单元格背景不应换实例", cellBg, renderer.background)
        }
    }

    fun test背景样式_重复渲染不替换背景与边框实例() {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND) {
            render(entry("UPDATE t SET a = 1"))
            val bg = renderer.background
            val border = renderer.border
            // 混色每次都会算出「新 Color 实例但同值」——守卫必须拦下它，否则每格一次 repaint
            render(entry("UPDATE t SET a = 1"))
            assertSame("同值混色结果不应重新赋值背景", bg, renderer.background)
            assertSame("同状态不应换边框实例", border, renderer.border)
        }
    }

    fun test背景样式_选中与未选中边框恒同一实例() {
        withCrudColors(SqlListTypeColor.STYLE_BACKGROUND) {
            render(entry("UPDATE t SET a = 1"))
            val normal = renderer.border
            render(entry("UPDATE t SET a = 1"), true)
            assertSame("描边退役后选中/未选中共用同一 Border（不再切换）", normal, renderer.border)
            render(entry("UPDATE t SET a = 1"))
            assertSame("回到未选中仍是同一实例", normal, renderer.border)
        }
    }

    fun test行号与后续片段拼接顺序正确() {
        // 时间戳开启时：#N 在时间戳之前（行号是最高频引用锚点，视觉最左）
        val pluginSettings = PluginSettings.getInstance().state
        val savedTs = pluginSettings.logTimestampEnabled
        try {
            pluginSettings.logTimestampEnabled = true
            val e = entry("SELECT 1").copy(sequenceNumber = 3L)
            val r = render(e)
            val meta = r.metaTextForTest
            // T-LogListRail：轨道首行 = `#N HH:mm:ss`。时间戳不再带方括号（格式串已剥日期、
            // 由 SimpleDateFormat 直接产出时段），故按「#3 后紧跟时刻」而非「找 [ 」断言。
            val idxRow = meta.indexOf("#3 ")
            val idxTime = meta.indexOf(":")
            assertTrue("应同时含行号与时刻: $meta", idxRow >= 0 && idxTime >= 0)
            assertTrue("行号在时刻之前: $meta", idxRow < idxTime)
        } finally {
            pluginSettings.logTimestampEnabled = savedTs
        }
    }

    // ---------- M56 T-M56-02/05：来源角标（已移除）/ 影响行数 / 报错同列 ----------

    fun test增强来源角标已移除_两通道均不显示() {
        // 用户要求移除：增强为默认捕获通道，逐行标来源属噪音
        val agent = entry("SELECT 1").copy(source = SqlSource.AGENT)
        assertFalse("AGENT 来源不应再显示增强角标", joinedText(render(agent).metaLine).contains("[⚡增强]"))
        val bridge = entry("SELECT 1")
        assertFalse("桥来源同样不显示", joinedText(render(bridge).metaLine).contains("[⚡增强]"))
    }

    fun test影响行数负值不显示_负行数守卫() {
        // JDBC getUpdateCount 在结果为 ResultSet 时返回 -1（无有效计数）→ 不得渲染「(-1 rows)」
        val neg = entry("SELECT 1").copy(source = SqlSource.AGENT, updateCount = -1L)
        assertFalse("负值影响行数应不显示", render(neg).metaTextForTest.contains("rows)"))
        val zero = entry("UPDATE t SET a = 1").copy(source = SqlSource.AGENT, updateCount = 0L)
        assertTrue("0 行是合法计数，应显示", render(zero).metaTextForTest.contains("(0 rows)"))
    }

    fun test影响行数与报错片段同列() {
        val err = entry("UPDATE t SET a = 1").copy(
            source = SqlSource.AGENT,
            updateCount = 3L,
            errorText = "SQLException: Data too long for column 'a'",
        )
        val r = render(err)
        assertTrue("头部行应含影响行数片段", detail(r).contains("(3 rows)"))
        assertTrue("错误行含报错片段（单行截断）", r.errorTextForTest.contains("⛔ SQLException: Data too long"))
        // 无报错无行数的普通条目不显示
        val ok = render(entry("SELECT 1"))
        assertFalse("普通条目不应有行数片段", ok.metaTextForTest.contains("rows)"))
        assertFalse("普通条目不应有报错片段", ok.errorTextForTest.contains("⛔"))
    }

    // ---------- R60（T-LaneB D96）：日志列表链脱敏（判别断言两态，走真实渲染器链路） ----------

    fun test列表脱敏开_渲染文本打码() {
        val state = PluginSettings.getInstance().state
        val saved = state.displayMaskEnabled
        try {
            state.displayMaskEnabled = true
            val sqlText = joinedText(render(entry("SELECT * FROM t WHERE phone = '13812345678'")).sqlLine)
            assertTrue("脱敏开应打码: $sqlText", sqlText.contains("***"))
            assertFalse("原文手机号不得出现: $sqlText", sqlText.contains("13812345678"))
        } finally {
            state.displayMaskEnabled = saved
        }
    }

    fun test列表脱敏关_原文渲染() {
        val state = PluginSettings.getInstance().state
        val saved = state.displayMaskEnabled
        try {
            state.displayMaskEnabled = false
            val sqlText = joinedText(render(entry("SELECT * FROM t WHERE phone = '13812345678'")).sqlLine)
            assertTrue("脱敏关原文渲染: $sqlText", sqlText.contains("13812345678"))
        } finally {
            state.displayMaskEnabled = saved
        }
    }

    fun test摘要截断在敏感字面量中间_日志展示仍须脱敏() {
        val state = PluginSettings.getInstance().state
        val saved = state.displayMaskEnabled
        try {
            state.displayMaskEnabled = true
            val secret = "PRIVATE_MARKER_" + "x".repeat(200)
            val entry = entry("SELECT * FROM account WHERE password = '$secret'")
            assertTrue("夹具须在闭合引号前截断", entry.summary.contains("PRIVATE_MARKER_"))
            assertFalse(entry.summary.contains("'$secret'"))
            val displayed = joinedText(render(entry).sqlLine)
            assertTrue("密码应按完整字面量打码：$displayed", displayed.contains("password = '***'"))
            assertFalse("截断的敏感片段不得泄漏：$displayed", displayed.contains("PRIVATE_MARKER_"))
        } finally {
            state.displayMaskEnabled = saved
        }
    }

    // ---------- T-DataSourceBadgeMode：数据源角标默认仅显示数据库类型（显示侧门控） ----------

    /** 构造带目标库的 agent 条目（badgeName 全量形态：方言 · 连接事实）。 */
    private fun dbEntry(db: String?): LogSqlEntry =
        entry("SELECT 1").copy(source = SqlSource.AGENT, databaseName = db)

    /** 在给定开关值下执行断言（收尾原样恢复）。 */
    private fun withBadgeDetail(enabled: Boolean, block: () -> Unit) {
        val s = PluginSettings.getInstance().state
        val saved = s.dataSourceBadgeDetail
        try {
            s.dataSourceBadgeDetail = enabled
            block()
        } finally {
            s.dataSourceBadgeDetail = saved
        }
    }

    fun test数据源角标_默认关仅渲染方言段() {
        // OFF（默认）：badgeName 全量串「MySQL · jpa_demo@localhost:3306」只显方言段（D135 显示侧门控）
        withBadgeDetail(enabled = false) {
            val full = "MySQL · jpa_demo@localhost:3306"
            assertEquals("OFF 态片段应切出纯方言段", "[MySQL] ", formatDatabaseFragment(dbEntry(full), false)!!.first)
            val d = render(dbEntry(full)).metaTextForTest
            assertTrue("注脚行应含方言角标: $d", d.contains("[MySQL]"))
            assertFalse("注脚行不应含连接事实: $d", d.contains("jpa_demo"))
        }
    }

    fun test数据源角标_开启后渲染全量上下文() {
        withBadgeDetail(enabled = true) {
            val full = "MySQL · jpa_demo@localhost:3306"
            assertEquals("ON 态片段应原样全量", "[$full] ", formatDatabaseFragment(dbEntry(full), false)!!.first)
            val d = render(dbEntry(full)).metaTextForTest
            assertTrue("注脚行应含完整上下文: $d", d.contains("[$full]"))
        }
    }

    fun test数据源角标_无分隔符串关闭态原样保留() {
        // 已知边界：方言解析失败仅存连接事实（无 · 分隔符）→ OFF 态原样保留最后上下文
        withBadgeDetail(enabled = false) {
            assertEquals(
                "无 · 串 OFF 态应原样保留",
                "[localhost:5236] ",
                formatDatabaseFragment(dbEntry("localhost:5236"), false)!!.first,
            )
        }
    }

    fun test数据源角标_未知库名恒不显示() {
        // null databaseName 恒 null 不显示（既有语义不变；渲染器级 null 用例见 test目标库角标_未知库名不显示）
        withBadgeDetail(enabled = false) {
            assertNull(formatDatabaseFragment(dbEntry(null), false))
        }
        withBadgeDetail(enabled = true) {
            assertNull("ON 态同样恒 null", formatDatabaseFragment(dbEntry(null), true))
        }
    }

    fun test数据源角标_历史读回条目随开关切换() {
        // P4 口径：历史读回条目的 databaseName 为自由文本全量串，显示门控对它同样生效
        val readBack = dbEntry("DM（达梦） · localhost:5236")
        withBadgeDetail(enabled = false) {
            assertEquals("[DM（达梦）] ", formatDatabaseFragment(readBack, false)!!.first)
        }
        withBadgeDetail(enabled = true) {
            assertEquals("[DM（达梦） · localhost:5236] ", formatDatabaseFragment(readBack, false)!!.first)
        }
    }
}
