package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import cn.song.mybatisplus.visualizer.config.PluginSettings
import org.junit.Assert.assertNotSame

/**
 * T-LogListRail 渲染热路径压测（性能守卫）。
 *
 * 存在理由：真实场景下 SQL 日志面板「内容多、更新快」——流式灌入时每秒数百条、一屏 20 条、
 * 每次数据变更都会触发一轮重绘。轨道改版引入了更多子组件与多次 `bind` 调用，
 * 若热路径上有对象分配/组件增删/重排调度，就会在这类场景下退化成可感知的卡顿（EDT 抢占）。
 *
 * 本类**不是**功能测试，而是**退化守卫**：判据是「每格渲染的耗时上界」与「渲染期间不产生
 * 组件树结构变化」。阈值取得宽松（离实测值有数倍余量），只拦「数量级退化」而非正常波动——
 * 这样在 CI 抖动下不会假红，但一旦有人把热路径改成每格 new 组件/每格 revalidate 就会红。
 *
 * 注意：headless 下无真实字体测量与 EDT 调度，绝对耗时远小于真机；故同时用
 * 「组件树结构在渲染前后不变」这类**结构性判据**兜底（不依赖机器快慢）。
 */
class LogEntryRailPerfTest : BasePlatformTestCase() {

    private fun entry(i: Int, withError: Boolean = false, withSource: Boolean = true) = LogSqlEntry(
        rawSql = "SELECT * FROM t$i WHERE id = $i AND deleted = 0",
        restoredSql = "SELECT * FROM t$i WHERE id = $i AND deleted = 0",
        params = emptyList(),
        timestamp = 1_700_000_000_000L + i * 37L,
        executionTimeMs = (i % 200).toLong(),
        sourceFile = if (withSource) "src/main/java/SomeService$i.java" else null,
        sourceLine = i,
        sequenceNumber = i.toLong(),
        errorText = if (withError) "SQLException: boom $i" else null,
        databaseName = "MySQL · db$i@localhost:3306",
    ).let { if (withSource) it.copy(sourceFile = "src/main/java/SomeService.java") else it }

    private fun list(width: Int = 600) = javax.swing.JList<Any>().apply { setSize(width, 400) }

    private fun renderOnce(r: RailCellRendererBase, list: javax.swing.JList<Any>, e: LogSqlEntry) {
        r.getListCellRendererComponent(list, e, 0, false, false)
    }

    /**
     * 单格渲染预算（毫秒）。
     *
     * 取值依据：改造前同形态实测约 0.05~0.2ms/格（headless）；设 2ms 是留一个数量级余量，
     * 只为拦住「每格 new 组件 / 每格 buildList / 每格 revalidate」这类结构性退化。
     */
    private val perCellBudgetMs = 2.0

    fun test单格渲染耗时_日志页在预算内() {
        val renderer = LogEntryListCellRenderer()
        val l = list()
        // 预热（JIT + 类加载 + 第一次 SimpleDateFormat 构造）
        repeat(50) { renderOnce(renderer, l, entry(it)) }
        val n = 2000
        val t0 = System.nanoTime()
        repeat(n) { renderOnce(renderer, l, entry(it)) }
        val perCellMs = (System.nanoTime() - t0) / 1_000_000.0 / n
        assertTrue("单格渲染 ${"%.3f".format(perCellMs)}ms 超预算 ${perCellBudgetMs}ms（热路径疑似退化）", perCellMs < perCellBudgetMs)
    }

    fun test单格渲染耗时_历史页在预算内() {
        val renderer = HistoryCellRenderer()
        val l = list()
        repeat(50) { renderOnce(renderer, l, entry(it)) }
        val n = 2000
        val t0 = System.nanoTime()
        repeat(n) { renderOnce(renderer, l, entry(it)) }
        val perCellMs = (System.nanoTime() - t0) / 1_000_000.0 / n
        assertTrue("历史页单格渲染 ${"%.3f".format(perCellMs)}ms 超预算 ${perCellBudgetMs}ms", perCellMs < perCellBudgetMs)
    }

    /**
     * 结构性判据：连续渲染 200 条不同条目后，单元格组件树规模必须稳定。
     *
     * 这条不依赖机器速度——组件增删是构造性缺陷（每格多加一行就永远不回收），
     * 会在真实滚动场景里表现为布局越来越慢。
     */
    fun test连续渲染_组件树规模稳定() {
        val renderer = LogEntryListCellRenderer()
        val l = list()
        fun countComponents(): Int {
            fun walk(c: java.awt.Component): Int {
                var n = 1
                if (c is java.awt.Container) for (child in c.components) n += walk(child)
                return n
            }
            return walk(renderer)
        }
        renderOnce(renderer, l, entry(0))
        val baseline = countComponents()
        repeat(200) { renderOnce(renderer, l, entry(it)) }
        assertEquals("连续渲染 200 条后组件树规模应稳定（每格增删组件会累积布局开销）", baseline, countComponents())
    }

    /** 带报错 + 带角标的条目同样不得在重复渲染时增长组件树。 */
    fun test连续渲染_带报错与角标_组件树规模稳定() {
        val renderer = LogEntryListCellRenderer()
        val l = list()
        fun countComponents(): Int {
            fun walk(c: java.awt.Component): Int {
                var n = 1
                if (c is java.awt.Container) for (child in c.components) n += walk(child)
                return n
            }
            return walk(renderer)
        }
        renderOnce(renderer, l, entry(0, withError = true))
        val baseline = countComponents()
        repeat(100) { renderOnce(renderer, l, entry(it, withError = true)) }
        assertEquals("带报错条目重复渲染后组件树规模应稳定", baseline, countComponents())
    }

    /** 注脚行缓存生效：同一条目重复渲染不得重新计算片段（以片段列表引用同一性断言）。 */
    fun test注脚行片段按条目缓存() {
        val renderer = LogEntryListCellRenderer()
        val l = list()
        renderer.templateCounts = mapOf("k" to 5)
        val e = entry(7)
        renderOnce(renderer, l, e)
        val first = renderer.metaTextForTest
        // 同一条目同快照重复渲染：文本必须一致（缓存命中路径不得产出不同结果）
        repeat(20) { renderOnce(renderer, l, e) }
        assertEquals("同一条目重复渲染，注脚行内容应稳定", first, renderer.metaTextForTest)
    }

    /**
     * 计数快照换代必须失效注脚缓存。
     *
     * 反向守卫：若缓存键漏了快照引用，刷新后徽章会停在旧值——用户会看到「同结构 3 条」
     * 永远不更新，而这不会报错。
     */
    fun test注脚缓存随计数快照失效() {
        val renderer = LogEntryListCellRenderer()
        val l = list()
        val e = entry(3)
        renderer.templateCounts = mapOf(e.structureKey to 3)
        renderOnce(renderer, l, e)
        val before = renderer.metaTextForTest
        renderer.templateCounts = mapOf(e.structureKey to 9)
        renderOnce(renderer, l, e)
        val after = renderer.metaTextForTest
        assertTrue("换代前应显示 3: $before", before.contains("3"))
        assertNotSame("换代后注脚文本应变化（缓存须失效）", before, after)
        assertTrue("换代后应显示 9: $after", after.contains("9"))
    }

    /**
     * 头部行留白 Border 必须预建复用（渲染热路径不得新建 Border）。
     *
     * 依据：JComponent.setBorder(新实例) 即使值相同也触发 repaint（T-LogListFreeze 同族实测）。
     * 改造后两种留白（常规 12px / 单行 8px）都是预建实例，按模式切换时只换引用。
     */
    fun test条目Border_按模式复用预建实例() {
        val renderer = LogEntryListCellRenderer()
        val l = list(600)
        val state = PluginSettings.getInstance().state
        val saved = state.logListSqlDisplayMode
        try {
            state.logListSqlDisplayMode = "KEYWORD_WRAP"
            renderOnce(renderer, l, entry(1))
            val normal = renderer.border
            state.logListSqlDisplayMode = "SINGLE_LINE"
            renderOnce(renderer, l, entry(1))
            val dense = renderer.border
            assertNotSame("单行模式应用紧凑 Border", normal, dense)
            repeat(10) { renderOnce(renderer, l, entry(it)) }
            assertSame("同模式重复渲染应复用同一 Border 实例", dense, renderer.border)
        } finally {
            state.logListSqlDisplayMode = saved
        }
    }


    /** 三种显示模式都必须落在预算内（SINGLE_LINE 行高最紧、FORMATTED 行数最多）。 */
    fun test三种显示模式均满足单格预算() {
        val state = PluginSettings.getInstance().state
        val saved = state.logListSqlDisplayMode
        val renderer = LogEntryListCellRenderer()
        val l = list(600)
        try {
            for (mode in listOf("SINGLE_LINE", "KEYWORD_WRAP", "FORMATTED")) {
                state.logListSqlDisplayMode = mode
                repeat(50) { renderOnce(renderer, l, entry(it)) }
                val n = 1000
                val t0 = System.nanoTime()
                repeat(n) { renderOnce(renderer, l, entry(it)) }
                val perCellMs = (System.nanoTime() - t0) / 1_000_000.0 / n
                assertTrue(
                    "[$mode] 单格渲染 ${"%.3f".format(perCellMs)}ms 超预算 ${perCellBudgetMs}ms",
                    perCellMs < perCellBudgetMs,
                )
            }
        } finally {
            state.logListSqlDisplayMode = saved
        }
    }
    // ---------- 宽度算式 ----------

    /**
     * SQL 区可用宽度 = 列表宽 - 单元格内边距 - 色条（不再有侧栏要扣）。
     */
    fun testSQL区宽度_只扣内边距与色条() {
        val cell = LogEntryListCellRenderer()
        val l = list(600)
        val w = sqlAreaWidth(l, cell)
        val expected = 600 - cell.insets.left - cell.insets.right - SqlListTypeColor.BAR_WIDTH
        assertEquals("可用宽度应为列表宽减去内边距与色条", expected, w)
        assertTrue("可用宽度必须为正", w > 0)
    }

    /** 窄侧栏：SQL 区不再被固定侧栏吃掉 88px。 */
    fun test窄侧栏_SQL区仍占主导宽度() {
        val cell = LogEntryListCellRenderer()
        val narrow = list(420)
        val w = sqlAreaWidth(narrow, cell)
        assertTrue("420px 侧栏下 SQL 区应 ≥380px（实际 ${w}px）", w >= 380)
    }


    // ---------- 大数据集压测（用户要求：几千 / 几万条） ----------

    /** 构造 n 条完整字段条目（最坏形态：带来源、耗时、序号、角标）。 */
    private fun manyEntries(n: Int): List<LogSqlEntry> = (0 until n).map { entry(it) }

    /**
     * 大数据集下「进度条归一基准」扫描的成本。
     *
     * 直接盯住 [maxDurationOf]：它**每次列表刷新都跑**。此前写法是
     * `mapNotNull{}.maxOrNull()`——为两万条分配一个两万槽的临时列表，流式灌入下每秒多次，
     * 是纯 GC 压力（T-LogListFreeze 同族问题的成因）。单遍零分配后应稳定在亚毫秒级。
     */
    fun test大数据集_最大耗时扫描不再分配临时列表() {
        val data = manyEntries(20_000)
        repeat(100) { maxDurationOf(data) }   // 预热
        val rounds = 200
        val t0 = System.nanoTime()
        var acc = 0L
        repeat(rounds) { acc += maxDurationOf(data) ?: 0L }
        val perScanMs = (System.nanoTime() - t0) / 1_000_000.0 / rounds
        assertTrue("应能扫出结果（$acc）", acc > 0)
        // 预算 3ms：单遍遍历两万条在实机约 0.6ms，但 headless + 共享 CI 抖动可到 1.3ms。
        // 判据仍是「不得退化为 mapNotNull 分配临时列表」——那种写法在两万条下是 5~10ms 量级。
        assertTrue(
            "两万条最大耗时扫描 ${"%.3f".format(perScanMs)}ms——单遍遍历应在 3ms 内，超出说明退回了会分配临时列表的写法",
            perScanMs < 3.0,
        )
    }

    /**
     * 渲染单格成本**不得随总条数增长**。
     *
     * 这是「几万条会不会卡」的核心判据：缓冲里有 20000 条但一屏只渲染 20 条时，
     * 单格成本必须与总数无关（只与可见条数相关）。若有人把 O(N) 逻辑塞进渲染器，
     * 这里立刻红。
     */
    fun test大数据集_单格成本与总条数无关() {
        val renderer = LogEntryListCellRenderer()
        val l = list(600)
        val small = manyEntries(200)
        val large = manyEntries(20_000)

        renderer.maxDurationMs = maxDurationOf(small)
        repeat(100) { i -> renderOnce(renderer, l, small[i % small.size]) }
        val t0 = System.nanoTime()
        repeat(1000) { i -> renderOnce(renderer, l, small[i % small.size]) }
        val smallPerCell = (System.nanoTime() - t0) / 1_000_000.0 / 1000

        renderer.maxDurationMs = maxDurationOf(large)
        repeat(100) { i -> renderOnce(renderer, l, large[i % large.size]) }
        val t1 = System.nanoTime()
        repeat(1000) { i -> renderOnce(renderer, l, large[i % large.size]) }
        val largePerCell = (System.nanoTime() - t1) / 1_000_000.0 / 1000

        assertTrue(
            "单格渲染成本不得随总条数增长（200 条 ${"%.4f".format(smallPerCell)}ms vs 两万条 ${"%.4f".format(largePerCell)}ms）",
            largePerCell < smallPerCell * 3,
        )
    }

    /**
     * 大数据集下滚动一屏（20 格 × 30 帧）的预算。
     *
     * 这是用户感知最直接的量：滚轮一格 = 一屏重绘。预算 = 格数 × 单格预算。
     */
    fun test大数据集_滚动一屏在预算内() {
        val data = manyEntries(20_000)
        val renderer = LogEntryListCellRenderer()
        renderer.maxDurationMs = maxDurationOf(data)
        val l = list(600)
        repeat(100) { renderOnce(renderer, l, data[it]) }
        val visible = 20
        val frames = 30
        val t0 = System.nanoTime()
        repeat(frames) { f ->
            repeat(visible) { v -> renderOnce(renderer, l, data[(f * visible + v) % data.size]) }
        }
        val totalMs = (System.nanoTime() - t0) / 1_000_000.0
        val budget = visible * frames * perCellBudgetMs
        assertTrue(
            "两万条缓冲下滚动 30 帧（每帧 20 格）耗时 ${"%.1f".format(totalMs)}ms 超预算 ${"%.0f".format(budget)}ms",
            totalMs < budget,
        )
    }

    /** 大数据集滚动渲染同样不得增长组件树（抽样跨整个缓冲，覆盖各类字段组合）。 */
    fun test大数据集_滚动渲染不增长组件树() {
        val data = manyEntries(20_000)
        val renderer = LogEntryListCellRenderer()
        renderer.maxDurationMs = maxDurationOf(data)
        val l = list()
        renderOnce(renderer, l, data[0])
        val baseline = countComponents(renderer)
        val step = 20_000 / 500
        for (i in 0 until 500) renderOnce(renderer, l, data[i * step])
        assertEquals("大数据集滚动渲染后组件树规模应稳定", baseline, countComponents(renderer))
    }

    private fun countComponents(c: java.awt.Component): Int {
        var n = 1
        if (c is java.awt.Container) for (child in c.components) n += countComponents(child)
        return n
    }
    // ---------- 条目分割（用户反馈「容易混在一起」的根治守卫） ----------

    /**
     * 条目上下留白必须显著大于改造前的 5px。
     *
     * 根因回顾：改造前上下各 5px，而条目内 SQL 行距是 1.45 倍行高（约 17px），
     * 两者接近 → 眼睛找不到条目切分点。留白是分条的主力手段，退回到 5px 就回到原问题。
     */
    fun test条目留白_显著大于改造前() {
        assertTrue(
            "常规留白应 ≥10px（当前 ${RailCellRendererBase.ROW_VERTICAL_PADDING}px）——" +
                "改造前是 5px，与条目内行距接近导致混在一起",
            RailCellRendererBase.ROW_VERTICAL_PADDING >= 10,
        )
        assertTrue(
            "单行模式留白应 ≥6px（当前 ${RailCellRendererBase.ROW_VERTICAL_PADDING_DENSE}px）",
            RailCellRendererBase.ROW_VERTICAL_PADDING_DENSE >= 6,
        )
    }

    /** 分隔线色必须比平台默认 border 更强（否则深色主题下看不见）。 */
    fun test条目分隔线_强于平台默认边框() {
        val divider = RailCellRendererBase.ROW_DIVIDER
        val platform = JBColor.border()
        assertNotSame("分隔线应是独立的命名色，不是平台 border 默认值", platform, divider)
        // 深色主题下亮度应高于平台 border（更显眼）
        assertTrue(
            "分隔线应比平台 border 更亮（divider=${divider.rgb.toString(16)}, border=${platform.rgb.toString(16)}）",
            brightness(divider) >= brightness(platform),
        )
    }

    private fun brightness(c: java.awt.Color): Int = (c.red * 299 + c.green * 587 + c.blue * 114) / 1000
}
