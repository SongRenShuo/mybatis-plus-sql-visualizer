package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
        val first = renderer.detailTextForTest
        // 同一条目同快照重复渲染：文本必须一致（缓存命中路径不得产出不同结果）
        repeat(20) { renderOnce(renderer, l, e) }
        assertEquals("同一条目重复渲染，注脚行内容应稳定", first, renderer.detailTextForTest)
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
        val before = renderer.detailTextForTest
        renderer.templateCounts = mapOf(e.structureKey to 9)
        renderOnce(renderer, l, e)
        val after = renderer.detailTextForTest
        assertTrue("换代前应显示 3: $before", before.contains("3"))
        assertNotSame("换代后注脚文本应变化（缓存须失效）", before, after)
        assertTrue("换代后应显示 9: $after", after.contains("9"))
    }

    /**
     * 轨道宽度只在变化时重设 preferredSize（每格重设会调度重排）。
     *
     * JComponent.getPreferredSize() 返回的是内部对象的**拷贝**，故不能用实例同一性断言；
     * 改为「同宽不产生 propertyChange」。监听器计数是比实例比较更直接的判据。
     */
    fun test轨道宽度_值不变不重设首选宽() {
        val rail = LogEntryRail()
        rail.setRailWidth(88)
        var changes = 0
        rail.addPropertyChangeListener("preferredSize") { changes++ }
        repeat(10) { rail.setRailWidth(88) }
        assertEquals("同宽重复设置不得触发 preferredSize 变更", 0, changes)
        // 换宽必须真的写（否则窄侧栏降档失效）
        rail.setRailWidth(76)
        assertEquals(76, rail.railWidth)
        assertEquals("换宽应触发一次变更", 1, changes)
    }

    /** 形态切换幂等：同形态重复切换不重建行结构。 */
    fun test轨道形态_同形态重复设置幂等() {
        val rail = LogEntryRail()
        rail.setForm(LogEntryRail.Form.PACKED)
        val e = entry(1)
        rail.bind(e, false, 100L, java.awt.Color.BLACK, true)
        fun countComponents(): Int {
            fun walk(c: java.awt.Component): Int {
                var n = 1
                if (c is java.awt.Container) for (child in c.components) n += walk(child)
                return n
            }
            return walk(rail)
        }
        val baseline = countComponents()
        repeat(50) { rail.setForm(LogEntryRail.Form.PACKED) }
        assertEquals("同形态重复设置不得改变组件树", baseline, countComponents())
    }

    /** 进度条：值未变时不触发 repaint（渲染热路径守卫）。 */
    fun test进度条_值未变不重复重绘() {
        val track = DurationTrackPanel()
        track.setSize(80, 6)
        track.bind(50L, 100L)
        val firstFraction = track.fractionForTest()
        repeat(20) { track.bind(50L, 100L) }
        assertTrue("同值重复绑定，比例不变", kotlin.math.abs(firstFraction - track.fractionForTest()) <= 1e-9)
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
    // ---------- 宽度算式（计划 P3：最隐蔽、症状最轻的一条） ----------

    /**
     * SQL 区可用宽度必须扣除轨道宽度。
     *
     * 反向守卫：若算式漏扣轨道，SQL 会按偏大的宽度测量——末尾字符被裁掉却**不报错、不告警**，
     * 用户只会觉得「怎么少几个字」。这类静默缺陷只能靠算式层面的断言拦住。
     */
    fun testSQL区宽度_必须扣除轨道宽度() {
        val cell = LogEntryListCellRenderer()
        val l = list(600)
        val withRail = sqlAreaWidth(l, cell, LogEntryRail.RAIL_WIDTH)
        val withoutRail = sqlAreaWidth(l, cell, 0)
        assertEquals(
            "扣轨道与不扣轨道应恰好差一个轨道宽度",
            LogEntryRail.RAIL_WIDTH,
            withoutRail - withRail,
        )
        assertTrue("可用宽度必须为正（600px 列表 - 轨道后仍有空间）", withRail > 0)
    }

    /** 窄侧栏：轨道降档到 76px，SQL 区随之变宽（给 SQL 让空间）。 */
    fun test窄侧栏_轨道降档且SQL区变宽() {
        val cell = LogEntryListCellRenderer()
        val narrow = list(460)
        val wide = list(700)
        val narrowSql = sqlAreaWidth(narrow, cell, railWidthFor(narrow.width))
        val wideSql = sqlAreaWidth(wide, cell, railWidthFor(wide.width))
        assertEquals("460px 应降档到窄轨道", LogEntryRail.RAIL_NARROW_WIDTH, railWidthFor(460))
        assertEquals("700px 用常规轨道", LogEntryRail.RAIL_WIDTH, railWidthFor(700))
        assertTrue("宽列表的 SQL 区应更宽", wideSql > narrowSql)
    }

    /** 轨道宽度预算：SQL 区不得被轨道挤到不可用（590px 侧栏下仍应 ≥ 400px）。 */
    fun test常规侧栏_SQL区仍占主导宽度() {
        val cell = LogEntryListCellRenderer()
        val l = list(590)
        val sqlWidth = sqlAreaWidth(l, cell, railWidthFor(590))
        assertTrue("590px 侧栏下 SQL 区应 ≥400px（实际 ${sqlWidth}px）——否则轨道过宽挤占正文", sqlWidth >= 400)
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
        assertTrue(
            "两万条最大耗时扫描 ${"%.3f".format(perScanMs)}ms——单遍遍历应在 1ms 内，超出说明退回了会分配临时列表的写法",
            perScanMs < 1.0,
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
}
