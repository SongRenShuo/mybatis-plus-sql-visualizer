package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

/**
 * 券01（T-PerfSweep27-01）头部行静态片段条目级缓存守卫。
 *
 * 存在理由：`bindHeaderLine` 每次**可见格重绘**（滚动/选中变化/流式灌入 refresh）都重跑
 * 静态段构建（时间格式化 + 子串切割 + 字符串拼接），两万条流式灌入期反复 repaint 时是
 * 列表渲染链上唯一的每帧重复计算——静态段只依赖条目不可变字段，应按条目缓存一次。
 *
 * 三条判据各防一类假绿：
 *  - 静态段只算一次（计数缝；修复前每次渲染 +1，先红）；
 *  - 动态徽章不被缓存吞（templateCounts 是会话级可变输入，进缓存会吞更新）；
 *  - 设置变更键失效（缓存键含设置值——「显示格式严格跟随设置」红线）。
 */
class HeaderFragmentCacheTest : BasePlatformTestCase() {

    private fun entry() = LogSqlEntry(
        rawSql = "SELECT * FROM user WHERE id = 1",
        restoredSql = "SELECT * FROM user WHERE id = 1",
        params = emptyList(),
        timestamp = 1_700_000_000_000L,
        logTimestamp = 1_700_000_000_000L,
        executionTimeMs = 33L,
        updateCount = 3L,
        sequenceNumber = 42L,
        databaseName = "MySQL · db@localhost:3306",
        sourceFile = "src/main/java/UserMapper.java",
        sourceLine = 41,
    )

    private fun list() = javax.swing.JList<Any>().apply { setSize(600, 400) }

    private fun renderOnce(r: RailCellRendererBase, l: javax.swing.JList<Any>, e: LogSqlEntry, selected: Boolean = false) {
        r.getListCellRendererComponent(l, e, 0, selected, false)
    }

    /** 判据①：同一条目连续 N 次渲染，静态段只计算一次（修复前每次渲染都算，先红）。 */
    fun test同条目重复渲染_静态段只计算一次() {
        val renderer = LogEntryListCellRenderer()
        val l = list()
        // 预热：走通 renderer 首渲染路径（类加载/字体等一次性成本）
        renderOnce(renderer, l, entry())
        SqlEntryPresentation.headerCoreComputeCount = 0
        // 新条目渲染 5 次：第 1 次未命中计算（+1），后 4 次命中缓存（+0）
        val e = entry()
        repeat(5) { renderOnce(renderer, l, e) }
        assertEquals(
            "头部行静态段应按条目缓存（5 次重绘只算 1 次）；=0 说明渲染未走静态段，=N 说明缓存失效回退",
            1,
            SqlEntryPresentation.headerCoreComputeCount,
        )
    }

    /** 判据②：⨉去重/×N 同结构徽章不进缓存——面板快照变化后下一次渲染立即生效。 */
    fun test徽章计数不缓存_快照更新即生效() {
        val renderer = LogEntryListCellRenderer()
        val l = list()
        val e = entry()
        renderer.templateCounts = emptyMap()
        renderer.dedupeCounts = emptyMap()
        renderOnce(renderer, l, e)
        val withoutBadge = renderer.metaTextForTest

        val badgeText = MpSqlVisualizerBundle.message("log.panel.templateBadge", 4)
        renderer.templateCounts = mapOf(e.structureKey to 4)
        renderer.dedupeCounts = mapOf(e.dedupeKey to 2)
        renderOnce(renderer, l, e)
        val withBadge = renderer.metaTextForTest

        assertNotEquals("徽章快照变化后头部行必须更新（缓存不得吞动态徽章）", withoutBadge, withBadge)
        assertTrue("应含同结构徽章「$badgeText」: $withBadge", withBadge.contains(badgeText.trim()))
        assertTrue("应含去重徽章 ⨉2: $withBadge", withBadge.contains("⨉2"))
    }

    /** 判据③：设置变更（键维度）后静态段失效重算——显示格式严格跟随设置。 */
    fun test设置变更后缓存键失效重算() {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.state.copy()
        try {
            val renderer = LogEntryListCellRenderer()
            val l = list()
            val e = entry()
            renderOnce(renderer, l, e)
            SqlEntryPresentation.headerCoreComputeCount = 0

            settings.state.slowQueryThresholdMs = 1L // 33ms 由「正常绿」变「慢红」（颜色档位，文本不变）
            renderOnce(renderer, l, e)
            assertEquals(
                "设置（缓存键维度）变更后静态段必须重算",
                1,
                SqlEntryPresentation.headerCoreComputeCount,
            )
        } finally {
            settings.loadState(snapshot)
        }
    }

    /** 判据④：选中态渲染不另起「一套」缓存——选中/非选中共用同一份非选中态片段（绑定层统一着色）。 */
    fun test选中态与非选中态共用缓存() {
        val renderer = LogEntryListCellRenderer()
        val l = list()
        val e = entry()
        renderOnce(renderer, l, e)
        val unselected = renderer.metaTextForTest
        SqlEntryPresentation.headerCoreComputeCount = 0
        renderOnce(renderer, l, e, selected = true)
        assertEquals(
            "选中态渲染应复用非选中态片段（着色由绑定层 selectedAttributes 覆盖）",
            0,
            SqlEntryPresentation.headerCoreComputeCount,
        )
        assertEquals("选中/非选中的头部行文本内容一致（仅颜色差异，颜色不进 metaTextForTest）", unselected, renderer.metaTextForTest)
    }
}
