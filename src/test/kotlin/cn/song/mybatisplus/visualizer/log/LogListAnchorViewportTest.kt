package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.Point
import javax.swing.JScrollBar

/**
 * T-LogListAnchor 视口锚定组件级验收（原先只能人工确认的那条）。
 *
 * 判据不能只看滚动条 `value`「看起来没动」：JList **没有滚动锚定**——模型头部删一条，后面所有行
 * 整体上移一行高，而 `viewPosition` 完全可能不变，用户正在读的内容已经被顶走。因此每条用例的
 * 主判据都是**顶部可见条目身份**（`viewport.viewPosition` + `locationToIndex`）以及它相对视口的
 * 行内像素偏移，`viewPosition` 只作为辅助读数。
 *
 * 覆盖语义：
 *  - A  暂停态尾部追加（未满载）：顶部可见条目与视口位置都不动；
 *  - A2 暂停态满载头淘汰：顶部可见条目与行内偏移不动（模型整体上移一行是预期，视口随之上移补偿，
 *       故这里 `viewPosition` **应当变小**，不能拿它当「没被顶走」的判据）；
 *  - B  跟随态：新条目到达自动贴底；
 *  - C  暂停后 `onScrollToEnd()` 恢复跟随：新条目重新贴底。
 *
 * 进平台桶（`com.intellij` 依赖，需 Project 与平台 UI 状态），非 Heavy/外部进程桶。
 */
class LogListAnchorViewportTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager
    private var savedDisplayMode = PluginSettings.DEFAULT_LOG_LIST_DISPLAY_MODE
    private var savedThreshold = 0L

    override fun setUp() {
        super.setUp()
        val state = PluginSettings.getInstance().state
        savedDisplayMode = state.logListSqlDisplayMode
        savedThreshold = state.slowQueryThresholdMs
        // 多行模式才有可变行高与真实滚动（锚定补偿按行高计算，SINGLE_LINE 固定行高路径另测）
        state.logListSqlDisplayMode = SqlListDisplayMode.KEYWORD_WRAP.name
        // T-SqlListCatchup 券A（随 20261108 合并进 dev）：notifyUi 有 150ms 刷新窗，headless 泵不到
        // 未到点的 Alarm——锚定用例全部断言「动作后列表已同步刷新」，注入同步调度器（master 侧
        // SqlLogTailFollowTest 等同款修复口径，机制本身由 SqlLogManagerFlushWindowTest 覆盖）。
        manager = SqlLogManager(project).also { it.catchUpScheduler = { _, action -> action() } }
        manager.entries.dedupeEnabled = false
    }

    override fun tearDown() {
        try {
            manager.dispose()
            val state = PluginSettings.getInstance().state
            state.logListSqlDisplayMode = savedDisplayMode
            state.slowQueryThresholdMs = savedThreshold
        } finally {
            super.tearDown()
        }
    }

    // ---------- A：暂停态尾部追加（未满载） ----------

    fun testA_暂停态尾部追加_顶部可见条目与视口位置均不变() {
        val panel = openPanel()
        try {
            fill(panel, 60)
            val bar = scrollToMiddle(panel)
            val before = topView(panel)
            assertTrue("顶部行不能是首行（须真在中部）", before.index > 0)

            panel.tailFollow.onUserScroll()
            assertEquals(TailFollowMode.PAUSED, panel.tailFollowModeForTest)

            manager.entries.addEntry(entry(61))
            UIUtil.dispatchAllInvocationEvents()
            layoutTree(panel)

            val after = topView(panel)
            assertSame("新条目到达不得顶走顶部可见条目", before.entry, after.entry)
            assertEquals("未满载时顶部行索引不变", before.index, after.index)
            assertEquals("未满载时视口像素位置不变", before.viewY, after.viewY)
            assertEquals("新条目仍须进列表", 61, panel.listModel.size())
            assertEquals(entry(61).restoredSql, panel.listModel.getElementAt(60).restoredSql)
            assertFalse("暂停态不得被拉到底部", isAtBottom(bar))
        } finally {
            panel.dispose()
        }
    }

    // ---------- A2：暂停态满载头淘汰（真正的锚定缺陷面） ----------

    fun testA2_暂停态满载头淘汰_顶部可见条目与行内偏移不变() {
        manager.entries.maxEntries = 200
        val panel = openPanel()
        try {
            fill(panel, 200)
            val bar = scrollToMiddle(panel)
            val before = topView(panel)
            assertTrue("顶部行不能贴着头（须真在中部）", before.index > 2)

            panel.tailFollow.onUserScroll()
            assertEquals(TailFollowMode.PAUSED, panel.tailFollowModeForTest)

            val oldFirst = panel.listModel.getElementAt(0)
            manager.entries.addEntry(entry(201))
            UIUtil.dispatchAllInvocationEvents()
            layoutTree(panel)

            assertEquals("满载时长度不变（头淘汰 + 尾追加）", 200, panel.listModel.size())
            assertTrue(
                "头部旧条目必须被淘汰",
                (0 until 200).none { panel.listModel.getElementAt(it) === oldFirst },
            )
            val after = topView(panel)
            assertSame("头淘汰不得顶走用户正在读的顶部条目", before.entry, after.entry)
            assertEquals("条目在模型中整体上移一行", before.index - 1, after.index)
            assertEquals("行内像素偏移不变（内容原地不动）", before.offset, after.offset)
            assertFalse("暂停态不得被拉到底部", isAtBottom(bar))
            assertEquals("新条目必须出现在尾部", entry(201).restoredSql, panel.listModel.getElementAt(199).restoredSql)
        } finally {
            panel.dispose()
        }
    }

    // ---------- B：跟随态自动贴底 ----------

    fun testB_跟随态新条目到达自动贴底() {
        val panel = openPanel()
        try {
            fill(panel, 60)
            panel.resumeTailFollowForTest()
            layoutTree(panel)
            assertEquals(TailFollowMode.FOLLOWING, panel.tailFollowModeForTest)
            val bar = requireBar(panel)
            assertAtBottom(bar, "跟随态夹具应已贴底")
            val before = topView(panel)

            manager.entries.addEntry(entry(61))
            UIUtil.dispatchAllInvocationEvents()
            layoutTree(panel)

            assertAtBottom(bar, "跟随态新条目到达必须自动贴底")
            val after = topView(panel)
            assertTrue("视口必须向下推进（顶部行索引增大）", after.index > before.index)
            assertTrue("视口像素位置必须下移", after.viewY > before.viewY)
            assertEquals(entry(61).restoredSql, panel.listModel.getElementAt(panel.listModel.size() - 1).restoredSql)
            val last = requireNotNull(panel.list.getCellBounds(panel.listModel.size() - 1, panel.listModel.size() - 1))
            val viewRect = requireNotNull(panel.listScrollPaneForTest).viewport.viewRect
            assertTrue(
                "最后一行必须完整落在视口内",
                last.y + last.height <= viewRect.y + viewRect.height + SCROLL_BOTTOM_TOLERANCE_PX,
            )
        } finally {
            panel.dispose()
        }
    }

    // ---------- C：暂停后恢复跟随 ----------

    fun testC_暂停后onScrollToEnd恢复跟随_新条目重新贴底() {
        val panel = openPanel()
        try {
            fill(panel, 60)
            val bar = scrollToMiddle(panel)
            panel.tailFollow.onUserScroll()
            assertEquals(TailFollowMode.PAUSED, panel.tailFollowModeForTest)

            panel.tailFollow.onScrollToEnd()
            assertEquals(TailFollowMode.FOLLOWING, panel.tailFollowModeForTest)
            // 生产入口「滚动到底部」= scrollToBottom(resume = true)：先迁状态机再贴底。
            // 只调 onScrollToEnd() 会造出生产中不可达的「FOLLOWING + 视口中部」组合——此时刷新
            // 贴底读到的滚动条 maximum 还是旧行数（差一行高），断言的是夹具形态而非产品行为。
            panel.resumeTailFollowForTest()
            layoutTree(panel)
            assertAtBottom(bar, "恢复跟随夹具应已贴底")

            manager.entries.addEntry(entry(61))
            UIUtil.dispatchAllInvocationEvents()
            layoutTree(panel)

            assertAtBottom(bar, "恢复跟随后新条目必须贴底")
            assertEquals(entry(61).restoredSql, panel.listModel.getElementAt(panel.listModel.size() - 1).restoredSql)
        } finally {
            panel.dispose()
        }
    }

    // ---------- 夹具 ----------

    private fun openPanel(): SqlLogPanel {
        val panel = SqlLogPanel(project, manager)
        panel.setSize(640, 420)
        layoutTree(panel)
        return panel
    }

    private fun fill(panel: SqlLogPanel, count: Int) {
        (1..count).forEach { manager.entries.addEntry(entry(it)) }
        UIUtil.dispatchAllInvocationEvents()
        layoutTree(panel)
        assertEquals("夹具条目应全部进列表", count, panel.listModel.size())
    }

    private fun entry(n: Int) = LogSqlEntry(
        rawSql = "SELECT id, name FROM anchor_orders WHERE id = $n",
        restoredSql = "SELECT id, name FROM anchor_orders WHERE id = $n",
        params = emptyList(),
        timestamp = n.toLong(),
        executionTimeMs = n.toLong(),
    )

    private fun requireBar(panel: SqlLogPanel): JScrollBar =
        requireNotNull(panel.listScrollPaneForTest) { "列表必须挂在滚动容器上" }.verticalScrollBar

    /** 把视口放到中段：离开底部（暂停语义的前提）也离开顶部（头淘汰才能被观察）。 */
    private fun scrollToMiddle(panel: SqlLogPanel): JScrollBar {
        val bar = requireBar(panel)
        assertTrue("夹具必须真实超出视口", bar.maximum > bar.visibleAmount + 200)
        bar.value = (bar.maximum - bar.visibleAmount) / 2
        assertTrue("夹具必须离开底部", bar.value + bar.visibleAmount < bar.maximum - 100)
        assertTrue("夹具必须离开顶部", bar.value > 100)
        return bar
    }

    private fun isAtBottom(bar: JScrollBar): Boolean =
        bar.value + bar.visibleAmount >= bar.maximum - SCROLL_BOTTOM_TOLERANCE_PX

    private fun assertAtBottom(bar: JScrollBar, message: String) {
        assertTrue(
            "$message（value=${bar.value}, max=${bar.maximum}, visible=${bar.visibleAmount}）",
            isAtBottom(bar),
        )
    }

    /**
     * 顶部可见条目：视口像素位置 +1 处命中的行（与生产 [SqlLogPanel.captureTopAnchor] 同口径，
     * 但这里独立取 `viewport.viewPosition` 与 `locationToIndex`，不复用被验收的实现）。
     */
    private fun topView(panel: SqlLogPanel): TopView {
        val viewport = requireNotNull(panel.listScrollPaneForTest).viewport
        val list = panel.list
        val pos = viewport.viewPosition
        val index = list.locationToIndex(Point(pos.x, pos.y + 1))
        assertTrue("顶部行索引必须落在模型内：$index / ${list.model.size}", index in 0 until list.model.size)
        val bounds = requireNotNull(list.getCellBounds(index, index)) { "顶部行必须有 cell bounds" }
        // 独立复核：该行确实覆盖视口顶边（防 locationToIndex 在边界取到相邻行而假绿）
        assertTrue(
            "顶部行必须覆盖视口顶边（row=${bounds.y}..${bounds.y + bounds.height}, view=${pos.y}）",
            bounds.y <= pos.y && bounds.y + bounds.height > pos.y,
        )
        return TopView(index, list.model.getElementAt(index), bounds.y - pos.y, pos.y)
    }

    private class TopView(val index: Int, val entry: LogSqlEntry, val offset: Int, val viewY: Int)

    private fun layoutTree(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach { layoutTree(it) }
        }
    }
}
