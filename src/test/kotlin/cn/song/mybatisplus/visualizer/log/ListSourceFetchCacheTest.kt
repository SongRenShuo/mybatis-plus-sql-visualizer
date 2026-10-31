package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 券01（T-PerfSweep29-01，R29-1）多行模式取源惰性化守卫。
 *
 * 存在理由：`sqlLineFragmentsFor` 改造前在缓存检查之前无条件取源（脱敏开时
 * `displaySqlForList` 内部付 O(SQL 长) 全量词法打码）——多行模式（KEYWORD_WRAP/FORMATTED）
 * 缓存命中时该值被整段丢弃，即脱敏开＋多行模式下每次重绘×每个可见格白付一次打码。
 *
 * 判据与先例：
 *  - 取源计数缝 = 1：同条目连续两次渲染只允许未命中首帧取源一次（计数缝
 *    [RailCellRendererBase.listSourceFetchCount]，形态对齐 HeaderFragmentCacheTest）；
 *  - 渲染输出逐字一致：三模式×脱敏开/关的渲染行 == 直算链（displaySqlForList →
 *    withTerminator → semanticLineFragments），钉住改造前后输出不变（P-1/P-2 夹具）；
 *  - 脱敏翻转即重算：缓存键含 masked，翻转后首帧必须重取源且输出跟随（P-3 夹具）；
 *  - 历史页同链覆盖：RailCellRendererBase 是日志页/历史页共同基类，两页都要验。
 *
 * k2j 互操作注（master afd3d091 终态移植）：条目 copy(timestamp=…) 改 withTimestamp(...)；
 * displaySqlForList/withTerminator 分别为 SqlListFragmentRenderer/SqlLogPanelKt 静态方法（显式限定）。
 */
class ListSourceFetchCacheTest : BasePlatformTestCase() {

    private val renderer = LogEntryListCellRenderer()
    private val historyRenderer = HistoryCellRenderer()
    private val list = javax.swing.JList<Any>().apply { setSize(600, 400) }

    private fun entry() = LogSqlEntry(
        "SELECT name FROM user WHERE password = 'SECRET_PW' AND id = 1",
        "SELECT name FROM user WHERE password = 'SECRET_PW' AND id = 1",
        emptyList(),
        1_700_000_000_000L,
    )

    private fun renderOnce(r: RailCellRendererBase, e: LogSqlEntry) {
        r.getListCellRendererComponent(list, e, 0, false, false)
    }

    private fun joinedLines(r: RailCellRendererBase): List<String> =
        r.sqlLines.map { c ->
            buildString {
                val it = c.iterator()
                while (it.hasNext()) append(it.next())
            }
        }

    private fun withSettings(mode: String, mask: Boolean, block: () -> Unit) {
        val state = PluginSettings.getInstance().state
        val savedMode = state.logListSqlDisplayMode
        val savedMask = state.displayMaskEnabled
        state.logListSqlDisplayMode = mode
        state.displayMaskEnabled = mask
        try {
            block()
        } finally {
            state.logListSqlDisplayMode = savedMode
            state.displayMaskEnabled = savedMask
        }
    }

    /** 判据①（先红主判据）：多行模式＋脱敏开，同条目连续两次渲染，取源只允许首帧一次。 */
    fun test关键字换行脱敏开_同条目两次渲染_取源只一次() {
        withSettings("KEYWORD_WRAP", true) {
            // 预热用另一条目实例（类加载/字体等一次性成本不计缝）——不得用被测条目，
            // 否则其条目级片段缓存被预热填上，修后计数窗口内全命中（=0 假绿）。
            renderOnce(renderer, entry().withTimestamp(1_700_000_001_000L))
            val e = entry()
            RailCellRendererBase.listSourceFetchCount = 0
            renderOnce(renderer, e)
            renderOnce(renderer, e)
            assertEquals(
                "多行模式缓存命中路径必须零取源（两次渲染只算未命中首帧 1 次）；" +
                    "=2 说明命中路径每帧重取源，=0 说明渲染未走取源链",
                1,
                RailCellRendererBase.listSourceFetchCount,
            )
        }
    }

    /** 判据②（P-1/P-2 夹具）：三模式×脱敏开/关，渲染行与直算链逐字一致。 */
    fun test三模式脱敏开关_渲染行与直算链逐字一致() {
        val settings = PluginSettings.getInstance()
        for (mode in listOf("SINGLE_LINE", "KEYWORD_WRAP", "FORMATTED")) {
            for (mask in listOf(false, true)) {
                withSettings(mode, mask) {
                    val e = entry()
                    renderOnce(renderer, e)
                    val displayMode = settings.sqlListDisplayMode()
                    val expected = SqlLogPanelKt.withTerminator(
                        SqlListFragmentRenderer.displaySqlForList(e, displayMode, mask))
                    val expectedLines = if (displayMode == SqlListDisplayMode.SINGLE_LINE) {
                        listOf(expected)
                    } else {
                        SqlListFragmentRenderer.semanticLineFragments(expected, displayMode)
                            .map { line -> line.joinToString("") { it.text } }
                    }
                    val actual = joinedLines(renderer)
                    assertEquals("[$mode mask=$mask] 渲染行数", expectedLines.size, actual.size)
                    expectedLines.forEachIndexed { i, exp ->
                        assertEquals("[$mode mask=$mask] 第 ${i + 1} 行", exp, actual[i])
                    }
                }
            }
        }
    }

    /** 判据③（P-3 夹具）：脱敏开关翻转后缓存键失效——重取源且输出跟随切换。 */
    fun test脱敏开关翻转_缓存键失效重算且输出跟随() {
        withSettings("KEYWORD_WRAP", true) {
            val e = entry()
            renderOnce(renderer, e) // 未命中首帧：取源并写入条目级缓存
            RailCellRendererBase.listSourceFetchCount = 0
            renderOnce(renderer, e)
            assertEquals("同键重渲染应命中缓存（零取源）", 0, RailCellRendererBase.listSourceFetchCount)
            assertTrue("脱敏开不应泄漏敏感字面量", joinedLines(renderer).joinToString("\n").contains("***"))

            PluginSettings.getInstance().state.displayMaskEnabled = false
            renderOnce(renderer, e)
            assertEquals("脱敏切换后必须重取源重算", 1, RailCellRendererBase.listSourceFetchCount)
            val text = joinedLines(renderer).joinToString("\n")
            assertTrue("关闭脱敏后应见原始字面量", text.contains("SECRET_PW"))
            assertFalse("关闭脱敏后不得再打码", text.contains("***"))
        }
    }

    /** 判据④：历史页渲染器（共同基类另一消费者）同判据成立。 */
    fun test历史页渲染器_同条目两次渲染_取源只一次() {
        withSettings("FORMATTED", true) {
            renderOnce(historyRenderer, entry().withTimestamp(1_700_000_001_000L))
            val e = entry()
            RailCellRendererBase.listSourceFetchCount = 0
            renderOnce(historyRenderer, e)
            renderOnce(historyRenderer, e)
            assertEquals(
                "历史页多行模式缓存命中路径同样必须零取源",
                1,
                RailCellRendererBase.listSourceFetchCount,
            )
        }
    }
}
