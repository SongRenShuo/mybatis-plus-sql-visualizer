package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleTextAttributes
import java.awt.Color

class SqlEntryPresentationTest : BasePlatformTestCase() {
    fun testUI013同结构计数保留但Sharding不借用其他来源去重徽章() {
        val entry = LogSqlEntry(rawSql = "SELECT * FROM t WHERE id = ?",
            restoredSql = "SELECT * FROM t WHERE id = ?", params = emptyList(), source = SqlSource.SHARDINGSPHERE)
        val templates = structureKeyCounts(List(TEMPLATE_BADGE_MIN_COUNT) { entry.copy(actualSql = "ds_$it ::: SELECT ? ::: [$it]") })
        val drops = mapOf(entry.dedupeKey to 7)
        val sharding = SqlEntryPresentation.metadata(entry, false, false, templates, drops, true)
        val normal = SqlEntryPresentation.metadata(entry.copy(source = SqlSource.MYBATIS), false, false, templates, drops, true)
        assertFalse(sharding.any { it.text == "⨉7 " })
        assertTrue(normal.any { it.text == "⨉7 " })
        val template = formatTemplateCountFragment(entry, templates, false)
        assertNotNull(template)
        assertTrue(sharding.any { it.text == template!!.first })
        assertEquals(TEMPLATE_BADGE_MIN_COUNT, templates[entry.structureKey])
    }

    fun test共同元信息顺序与日志独有字段() {
        val entry = LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            timestamp = 1L,
            sequenceNumber = 17,
            executionTimeMs = 21,
            databaseName = "PostgreSQL",
            sourceFile = "src/main/OrderService.java",
            sourceLine = 42,
            updateCount = 2,
        )
        val settings = PluginSettings.getInstance()
        val old = settings.state.logTimestampEnabled
        try {
            settings.state.logTimestampEnabled = true
            val common = SqlEntryPresentation.metadata(entry, false, true, emptyMap())
            val log = SqlEntryPresentation.metadata(entry, false, true, emptyMap(), showLogDetails = true)
            val commonTexts = common.map { it.text }
            assertEquals("#17 ", commonTexts.first())
            assertTrue(commonTexts.indexOfFirst { it.contains("21ms") } < commonTexts.indexOfFirst { it.contains("PostgreSQL") })
            assertTrue(commonTexts.last().contains("OrderService.java:42"))
            assertEquals("共同片段顺序不得随日志独有字段改变", commonTexts.dropLast(1), log.map { it.text }.take(commonTexts.size - 1))
            assertTrue(log.any { it.text.contains("2 rows") })
        } finally {
            settings.state.logTimestampEnabled = old
        }
    }

    fun test列表耗时按阈值分档且无耗时保持灰色() {
        val settings = PluginSettings.getInstance()
        val oldThreshold = settings.state.slowQueryThresholdMs
        try {
            settings.state.slowQueryThresholdMs = 1000
            fun duration(ms: Long?, selected: Boolean = false): SqlTextFragment {
                val entry = LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(), executionTimeMs = ms)
                return SqlEntryPresentation.metadata(entry, selected, true, emptyMap())
                    .single { it.text == (ms?.let { value -> "${value}ms " } ?: "— ") }
            }
            assertSame(MpvsUiColors.DURATION_OK, duration(1).attributes.fgColor)
            assertSame(MpvsUiColors.DURATION_OK, duration(499).attributes.fgColor)
            assertSame(MpvsUiColors.DURATION_WARN, duration(500).attributes.fgColor)
            assertSame(MpvsUiColors.DURATION_SLOW, duration(1000).attributes.fgColor)
            assertSame(MpvsUiColors.DURATION_NONE, duration(null).attributes.fgColor)
            assertSame(MpvsUiColors.DURATION_SELECTED, duration(1, selected = true).attributes.fgColor)
        } finally {
            settings.state.slowQueryThresholdMs = oldThreshold
        }
    }

    fun test子句概览三行实内容_剩余计数作末行尾巴() {
        // T-LogListRail 改判：原「前 2 行 + 第三行整行占位符」只读到 2 个子句，
        // 用户实测反馈 WHERE 之后全不可见。现改为 3 个子句全渲染，剩余计数附在末行行尾。
        val lines = (1..5).map { listOf(SqlTextFragment("line $it", SimpleTextAttributes.REGULAR_ATTRIBUTES)) }
        val preview = visibleSqlLines(lines, SqlListDisplayMode.KEYWORD_WRAP)
        assertEquals("仍为 3 行（行高不变）", 3, preview.size)
        assertEquals("第 1 个子句", "line 1", preview[0].single().text)
        assertEquals("第 2 个子句", "line 2", preview[1].single().text)
        assertEquals("第 3 个子句 + 剩余计数尾巴", "line 3 … (+2)", preview[2].joinToString("") { it.text })
        assertSame("格式化模式不封顶", lines, visibleSqlLines(lines, SqlListDisplayMode.FORMATTED))
    }

    fun test子句概览_恰三行时不加尾巴() {
        val lines = (1..3).map { listOf(SqlTextFragment("line $it", SimpleTextAttributes.REGULAR_ATTRIBUTES)) }
        val preview = visibleSqlLines(lines, SqlListDisplayMode.KEYWORD_WRAP)
        assertEquals(3, preview.size)
        assertTrue("无隐藏行时不出现尾巴", preview.none { it.any { f -> f.text.contains("(+") } })
    }

    fun test子句概览_三行尾巴不再是整行占位符() {
        // 反向守卫：禁止退回「第三行只剩 … (+N)、实内容全丢」的旧形态
        val lines = (1..7).map { listOf(SqlTextFragment("clause$it", SimpleTextAttributes.REGULAR_ATTRIBUTES)) }
        val preview = visibleSqlLines(lines, SqlListDisplayMode.KEYWORD_WRAP)
        assertEquals("末行必须含第三个子句实内容", "clause3 … (+4)", preview[2].joinToString("") { it.text })
    }

    fun test宽度裁切保留片段属性与完整模型内容() {
        val attrs = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Color.BLUE)
        val entry = LogSqlEntry(rawSql = "SELECT 😀 FROM t", restoredSql = "SELECT 😀 FROM t", params = emptyList())
        val fragments = listOf(SqlTextFragment("SELECT ", attrs), SqlTextFragment("😀 FROM t", attrs))
        val clipped = clipSqlFragments(fragments, 90) { text, _ -> text.codePointCount(0, text.length) * 10 }
        val visible = clipped.joinToString("") { it.text }
        assertTrue(visible.endsWith("…"))
        assertFalse(visible.any { Character.isSurrogate(it) && !visible.contains("😀") })
        assertTrue(clipped.all { it.attributes === attrs })
        assertEquals("SELECT 😀 FROM t", entry.restoredSql)
    }
    // ---------- T-LogListHeader：头部行优先级裁剪（本次唯一新算法） ----------

    /** 每个字符按 10px 计，便于构造精确的宽度档位。 */
    private fun measure10(text: String): Int = text.length * 10

    private fun frag(text: String) = SqlTextFragment(text, SimpleTextAttributes.REGULAR_ATTRIBUTES)

    /** 完整头部片段集：序号 / 日期时刻 / 耗时 / 库 / 来源 / 徽章。 */
    private fun headerSet(): List<SqlTextFragment> = listOf(
        frag("#1 "),                       // 3 字 = 30px
        frag("[2026-10-26 09:42:02] "),    // 22 字 = 220px（真实形态带方括号）
        frag("1ms "),                      // 4 字 = 40px
        frag("[MySQL] "),                  // 8 字 = 80px
        frag("User:41 "),                  // 8 字 = 80px
        frag("同结构 24 条 "),              // 8 字 ≈ 80px（中文按字符计，测试里只看长度）
    )

    fun test头部裁剪_宽度充足时原样返回() {
        val set = headerSet()
        val out = clipFragmentsByPriority(set, 10_000) { t, _ -> measure10(t) }
        assertSame("放得下就原样返回（零拷贝）", set, out)
    }

    fun test头部裁剪_宽度为零视为未测量原样返回() {
        val set = headerSet()
        val out = clipFragmentsByPriority(set, 0) { t, _ -> measure10(t) }
        assertSame("未测量（headless 首帧）不得丢片段", set, out)
    }

    /**
     * 关键语义修正（用户 2026-10-26 定案）：**时间格式严格跟随设置，不因宽度而改写**。
     *
     * 一度实现过「宽度不足时剥掉日期只留时刻」，但那是按宽度偷偷改写用户配置的
     * `logTimestampFormat`——同一份配置在宽栏与窄栏显示成两种格式，用户无从察觉。
     * 现改为：时间整段不可拆，宽度不足时按优先级丢其他片段，全丢完才顺序截断。
     */
    fun test头部裁剪_绝不改写时间格式() {
        val set = headerSet()
        // 给一个「去掉时间会富余、但必须优先丢库角标」的窄档
        val out = clipFragmentsByPriority(set, 440) { t, _ -> measure10(t) }
        val joined = out.joinToString("") { it.text }
        assertTrue(
            "时间必须原样保留完整格式（不得剥成只剩时刻）: $joined",
            joined.contains("[2026-10-26 09:42:02]"),
        )
    }

    /** 窄档下先丢库角标与徽章，时间与序号保留。 */
    fun test头部裁剪_极窄时丢库角标与徽章保留时间() {
        val set = headerSet()
        val out = clipFragmentsByPriority(set, 300) { t, _ -> measure10(t) }
        val joined = out.joinToString("") { it.text }
        assertTrue("时刻应保留: $joined", joined.contains("09:42:02"))
        assertTrue("序号应保留: $joined", joined.contains("#1"))
        assertFalse("极窄下库角标应被丢: $joined", joined.contains("[MySQL]"))
        assertFalse("极窄下徽章应被丢: $joined", joined.contains("同结构"))
    }

    /** 序号是最后才丢的锚点：即使很窄也要尽量保住。 */
    fun test头部裁剪_序号最后才丢() {
        val set = headerSet()
        val out = clipFragmentsByPriority(set, 380) { t, _ -> measure10(t) }
        val joined = out.joinToString("") { it.text }
        assertTrue("序号应保留: $joined", joined.contains("#1"))
    }

    /** 全部丢完仍放不下时，兜底退回顺序截断（不得死循环、不得返回超宽结果）。 */
    fun test头部裁剪_全丢完仍超宽时退化为截断() {
        val set = headerSet()
        val out = clipFragmentsByPriority(set, 50) { t, _ -> measure10(t) }
        val width = out.sumOf { measure10(it.text) }
        assertTrue("结果不得超过给定宽度（实际 ${width}px）", width <= 50 + 10)
    }

    /** 优先级识别：影响行数先于库角标被丢。 */
    fun test头部裁剪_影响行数先于库角标被丢() {
        val set = listOf(
            frag("#1 "), frag("[09:42:02] "), frag("3ms "), frag("[MySQL] "),
            frag("Order:18 "), frag("(3 rows) "), frag("同结构 7 条 "),
        )
        val full = set.sumOf { measure10(it.text) }
        // 给「刚好要省掉 (3 rows) 的宽度」
        val out = clipFragmentsByPriority(set, full - measure10("(3 rows) ")) { t, _ -> measure10(t) }
        val joined = out.joinToString("") { it.text }
        assertFalse("影响行数应先丢: $joined", joined.contains("(3 rows)"))
        assertTrue("库角标应还在: $joined", joined.contains("[MySQL]"))
    }

    /** 片段属性必须原样保留（着色不丢）。 */
    fun test头部裁剪_保留片段属性() {
        val attrs = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Color.BLUE)
        val set = listOf(
            SqlTextFragment("#1 ", attrs), SqlTextFragment("[2026-10-26 09:42:02] ", attrs),
            SqlTextFragment("1ms ", attrs), SqlTextFragment("[MySQL] ", attrs),
        )
        val out = clipFragmentsByPriority(set, 250) { t, _ -> measure10(t) }
        assertTrue("所有保留片段属性应与输入同一实例", out.all { it.attributes === attrs })
    }
    // ---------- 头部行字段顺序（用户定案方案④ 库前置） ----------

    /**
     * 库角标必须排在来源类名之前（方案④）。
     *
     * 为什么这条要单独立守卫：顺序是纯视觉决策，改错了不报错、不崩溃，
     * 只是「库角标跑到类名后面」——没有断言就永远不会被发现。
     */
    fun test头部行顺序_库角标在来源类名之前() {
        val entry = LogSqlEntry(
            rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(),
            timestamp = 1_700_000_000_000L, executionTimeMs = 5L,
            databaseName = "MySQL", sourceFile = "src/main/java/UserMapper.java", sourceLine = 41,
            sequenceNumber = 3L,
        )
        val fragments = SqlEntryPresentation.headerFragments(entry, false, true, emptyMap())
        val joined = fragments.joinToString("") { it.text }
        val idxDb = joined.indexOf("[MySQL]")
        val idxSrc = joined.indexOf("User")
        assertTrue("两者都应出现: $joined", idxDb >= 0 && idxSrc >= 0)
        assertTrue("库角标应在来源类名之前（方案④）: $joined", idxDb < idxSrc)
        // 序号最前
        assertTrue("序号应在最前: $joined", joined.trimStart().startsWith("#3"))
    }

    /** 影响行数与徽章排在来源之后（诊断类信息靠后）。 */
    fun test头部行顺序_诊断信息在来源之后() {
        val entry = LogSqlEntry(
            rawSql = "UPDATE t SET a = 1", restoredSql = "UPDATE t SET a = 1", params = emptyList(),
            timestamp = 1_700_000_000_000L, executionTimeMs = 5L, updateCount = 3L,
            databaseName = "MySQL", sourceFile = "src/main/java/UserMapper.java", sourceLine = 41,
        )
        val fragments = SqlEntryPresentation.headerFragments(entry, false, true, mapOf(entry.structureKey to 4))
        val joined = fragments.joinToString("") { it.text }
        val idxSrc = joined.indexOf("User:41")
        val idxRows = joined.indexOf("(3 rows)")
        val idxBadge = joined.indexOf("同结构")
        assertTrue("影响行数与徽章都应出现: $joined", idxRows >= 0 && idxBadge >= 0)
        assertTrue("影响行数应在来源之后: $joined", idxSrc < idxRows)
        assertTrue("同结构徽章应在影响行数之后: $joined", idxRows < idxBadge)
    }
    // ---------- 窄宽度退化梯度（含用户定案的丢序） ----------

    /** 构造带优先级的真实片段集（与 headerFragments 同口径）。 */
    private fun realHeaderSet(entry: LogSqlEntry): List<SqlTextFragment> =
        SqlEntryPresentation.headerFragments(entry, false, true, emptyMap())

    private fun entryForRail() = LogSqlEntry(
        rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(),
        timestamp = 1_700_000_000_000L, logTimestamp = 1_700_000_000_000L,
        executionTimeMs = 5L, sequenceNumber = 42L,
        sourceFile = "src/main/java/BizCarStationOccupyInfoServiceImpl.java", sourceLine = 183,
        databaseName = "MySQL",
    )

    /**
     * 用户定案的丢序（2026-10-26，经两轮澄清）：**方言与类位置先丢，序号绝不先丢**。
     *
     * 关键区分：**捕获序号 `#42`** 与 **源码行号 `:183`** 是两个不同字段——
     * 前者是引用锚点（「#42 那条」）不可先丢；后者属「类位置」，可随类名一起先丢。
     * 首轮把两者混为一谈，才产生「序号不能先丢」与「行号不能先丢」的表面冲突。
     */
    fun test丢序_类位置先于序号被丢() {
        val p = HeaderFragmentPriority
        assertTrue("类名应早于序号丢: ${p.SOURCE_NAME} < ${p.SEQUENCE}", p.SOURCE_NAME < p.SEQUENCE)
        assertTrue("源码行号应早于序号丢: ${p.SOURCE_LINE} < ${p.SEQUENCE}", p.SOURCE_LINE < p.SEQUENCE)
        assertTrue("库角标应早于序号丢: ${p.DATABASE} < ${p.SEQUENCE}", p.DATABASE < p.SEQUENCE)
        assertTrue(
            "序号应是所有可丢片段中最后丢的（数值最大）",
            listOf(p.UPDATE_COUNT, p.DATABASE, p.SOURCE_NAME, p.SOURCE_LINE, p.TEMPLATE_BADGE)
                .all { it < p.SEQUENCE },
        )
    }

    /** 方言与类位置排在最前面丢（仅次于影响行数）；类位置内部先丢行号。 */
    fun test丢序_方言与类位置靠前() {
        val p = HeaderFragmentPriority
        assertTrue("库角标应先于源码行号丢", p.DATABASE < p.SOURCE_LINE)
        assertTrue(
            "**源码行号应先于类名丢**：':183' 脱离类名毫无意义，而类名单独出现仍能定位到文件",
            p.SOURCE_LINE < p.SOURCE_NAME,
        )
        assertTrue("类位置应先于徽章丢", p.SOURCE_NAME < p.TEMPLATE_BADGE)
    }

    /**
     * 窄档下：类位置已被丢，但**捕获序号仍在**（用户明确要求序号不先丢）。
     *
     * 只在「类位置刚被丢、其余仍放得下」的区间断言——极窄（≤200px）时所有可丢片段丢光后
     * 会进入 [clipSqlFragments] 的像素级截断，连序号一起切短，属兜底行为而非优先级错误。
     */
    fun test窄档_类位置已丢但序号仍在() {
        val e = entryForRail()
        val set = realHeaderSet(e)
        var checked = 0
        for (w in 240..360 step 10) {
            val out = clipFragmentsByPriority(set, w) { t, _ -> t.length * 8 }
            val joined = out.joinToString("") { it.text }
            // 仅当片段仍完整（未被兜底截断加省略号）时检查序号
            if (!joined.contains("ionOccupyInfo") && !joined.endsWith("…")) {
                checked++
                assertTrue("[$w] 类位置已丢时序号必须仍在: $joined", joined.contains("#42"))
            }
        }
        assertTrue("应至少命中一个「类位置已丢」的宽度档（当前 $checked）", checked > 0)
    }

    /**
     * 时间与耗时**不会作为「可丢片段」被主动整段丢弃**（时间是用户配置格式的投影）。
     *
     * 说明：极窄时它们仍可能被 [clipSqlFragments] 的**像素级截断**切短（如 `5m…`），
     * 那是所有可丢片段都丢光后的兜底行为，不是本断言要拦的对象。本断言只拦
     * 「被当成低优先级片段直接消失」——那才是配置被悄悄改写。
     */
    fun test时间与耗时不被主动整段丢弃() {
        val e = entryForRail()
        val set = realHeaderSet(e)
        for (w in 260..480 step 20) {
            val out = clipFragmentsByPriority(set, w) { t, _ -> t.length * 8 }
            val joined = out.joinToString("") { it.text }
            assertTrue("[$w] 时间不得整段消失: $joined", joined.contains("2023-11-15"))
            assertTrue("[$w] 耗时不得整段消失: $joined", joined.contains("5m"))
        }
    }
}
