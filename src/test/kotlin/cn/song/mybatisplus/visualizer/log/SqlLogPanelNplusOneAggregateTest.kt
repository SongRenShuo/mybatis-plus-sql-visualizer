package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.awt.Point
import java.awt.event.MouseEvent

/**
 * T-Nplus1Aggregate D235：N+1 嫌疑徽章聚合视图 UI 接线（计划册 §四 A4/A5，headless 驱动
 * internal 测试缝，不触真实鼠标）。
 *
 * 反向验证锚点：[test接线守卫_点击与Esc都接了聚合过滤态]（源码扫描）＋
 * [test点击徽章_进入聚合过滤态]（行为）——摘掉 tryEnterNplusOneAggregate 内的 addFocusTab
 * 接线（或 buildListPane mouseClicked 的调用行）二者必红。
 */
class SqlLogPanelNplusOneAggregateTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager
    private lateinit var panel: SqlLogPanel

    private val TEMPLATE = "SELECT * FROM t_user WHERE id = ?"
    private val OTHER = "SELECT * FROM t_other WHERE id = 1"

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
        panel = SqlLogPanel(project, manager)
    }

    override fun tearDown() {
        panel.dispose()
        manager.dispose()
        super.tearDown()
    }

    private fun nplusOneEntry(n: Int, ts: Long, thread: String?) = LogSqlEntry(
        rawSql = TEMPLATE.replace("?", "$n"),
        restoredSql = TEMPLATE.replace("?", "$n"),
        params = emptyList(),
        timestamp = ts,
        threadName = thread,
    )

    /**
     * 灌 6 条同模板异字面量同线程（N+1 形态，达默认阈值 5）+ 1 条异模板；同步跑一拍刷新
     * 让嫌疑键集/计数/模型落位。返回 (structureKey, 嫌疑组首条目)。
     *
     * **必须泵 EDT**：刷新拍的重建链分批走 `invokeLater { appendRebuildChunk() }`
     * （SqlLogPanel 既有设计，防长冻结），只调一次 refreshListForTest 时模型可能仍为空。
     */
    private fun addNplusOneFixtures(): Pair<String, LogSqlEntry> {
        val now = System.currentTimeMillis()
        val entries = (1..6).map { nplusOneEntry(n = it, ts = now - 6_000 + it * 500, thread = "http-nio") } +
            LogSqlEntry(
                rawSql = OTHER, restoredSql = OTHER, params = emptyList(),
                timestamp = now - 1_000, threadName = "http-nio",
            )
        entries.forEach { assertTrue(manager.entries.addEntry(it)) }
        panel.refreshListForTest()
        pumpEdtUntil({ panel.list.model.size >= entries.size }, "列表模型应填充到 ${entries.size} 条")
        return entries.first().structureKey to entries.first()
    }

    /** 泵 EDT 直到条件成立（重建链异步分批，单次 dispatch 不够——对齐 DialogTest 轮询先例）。 */
    private fun pumpEdtUntil(cond: () -> Boolean, what: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (cond()) return
            Thread.sleep(10)
        }
        fail("$what（超时 ${timeoutMs}ms，实际 model=${panel.list.model.size}）")
    }

    /** 泵到可见条数达期望（过滤态进出的模型重建同样分批异步）。 */
    private fun pumpVisible(expected: Int, what: String) =
        pumpEdtUntil({ panel.visibleEntryCount == expected }, "$what（期望可见 $expected）")

    /**
     * 定位被点条目上徽章片段的点击坐标：独立扫描已渲染 headerLine 的片段 tag（命中判定自身
     * 在 tryEnterNplusOneAggregate 内，此处仅按其渲染产物反查几何——非循环论证）。
     */
    private fun badgeClickPoint(entry: LogSqlEntry): Point {
        val list = panel.list
        list.setSize(600, 400)
        list.doLayout()
        var index = -1
        for (i in 0 until list.model.size) {
            // 值匹配而非引用匹配：刷新拍重建模型时条目可能不是同一个实例
            val candidate = list.model.getElementAt(i) as? LogSqlEntry ?: continue
            if (candidate.restoredSql == entry.restoredSql && candidate.timestamp == entry.timestamp) {
                index = i
                break
            }
        }
        assertTrue("被点条目应在过滤前列表中（model=${list.model.size}）", index >= 0)
        // **必须用 model 里的实例渲染**：buffer 入队时分发序号（`#N`），原始 entry 实例没有序号，
        // 渲染宽度与列表实况不同 → 算出的徽章 x 偏移（实测 2026-11-19：偏差 2px 即命中失败）。
        val modelEntry = list.model.getElementAt(index) as LogSqlEntry
        val rendered = panel.renderEntryForTest(modelEntry, index)
        val header = rendered.headerLine
        var badgeX = -1
        for (x in 0 until header.preferredSize.width) {
            if (header.getFragmentTagAt(x) == NPLUS_ONE_BADGE_TAG) { badgeX = x; break }
        }
        assertTrue("N+1 徽章片段应已渲染（6 条同模板达阈值）", badgeX >= 0)
        val bounds = list.getCellBounds(index, index)!!
        return Point(bounds.x + rendered.insets.left + SqlListTypeColor.BAR_WIDTH + badgeX + 2, bounds.y + 5)
    }

    private fun clickAt(p: Point): MouseEvent =
        MouseEvent(panel.list, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, p.x, p.y, 1, false)

    /** A4 主行为：点击徽章 → 进入过滤态，组内条目数 == 完整列表该键条目数，摘要行可见。 */
    fun test点击徽章_进入聚合过滤态_组数恰等完整列表() {
        val (key, entry) = addNplusOneFixtures()
        val entered = panel.tryEnterNplusOneAggregate(clickAt(badgeClickPoint(entry)))
        assertTrue("点击 N+1 徽章应进入过滤态", entered)
        val expectedCount = manager.entries.snapshot().count { it.structureKey == key }
        pumpVisible(expectedCount, "过滤态应只显示该模板组")
        val tabs = panel.focusTabsForTest()
        assertEquals("过滤态 = 恰一个同结构聚焦页签（复用 O-90 形态）", 1, tabs.size)
        assertEquals("过滤键 == 徽章判定同一 structureKey", key, (tabs[0] as FocusTab.Template).structureKey)
        assertEquals(
            "组内条目数 == 完整列表该键条目数（口径生命线）",
            expectedCount,
            panel.visibleEntryCount,
        )
        assertTrue("聚合摘要行可见", panel.nplusOneSummaryLabelForTest.isVisible)
        assertTrue("摘要行带 N+1 标签", panel.nplusOneSummaryLabelForTest.text.contains("N+1"))
        assertTrue("「返回全部」按钮在场", panel.nplusOneBackButtonForTest.isVisible)
    }

    /** A5：进入 → 「返回全部」→ 列表恢复完整（模型条数恢复、过滤态组件退场）。 */
    fun test返回全部_列表恢复完整() {
        val (_, entry) = addNplusOneFixtures()
        panel.tryEnterNplusOneAggregate(clickAt(badgeClickPoint(entry)))
        pumpVisible(6, "过滤态应显示 6 条")
        assertEquals(6, panel.visibleEntryCount)
        panel.nplusOneBackButtonForTest.doClick()
        assertTrue("过滤态退出", panel.focusTabsForTest().isEmpty())
        pumpVisible(manager.entries.snapshot().size, "退出过滤态应恢复完整列表")
        assertEquals("恢复完整列表（6+1）", manager.entries.snapshot().size, panel.visibleEntryCount)
        assertFalse("摘要行退场", panel.nplusOneSummaryLabelForTest.isVisible)
        assertFalse("返回全部按钮退场", panel.nplusOneBackButtonForTest.isVisible)
    }

    /** A5：Esc 通道同效退出（无过滤态时 no-op，不影响既有 Esc 通道）。 */
    fun testEsc退出_恢复完整列表() {
        val (_, entry) = addNplusOneFixtures()
        panel.tryEnterNplusOneAggregate(clickAt(badgeClickPoint(entry)))
        pumpVisible(6, "进入过滤态")
        assertTrue(panel.focusTabsForTest().isNotEmpty())
        panel.exitNplusOneAggregate()
        assertTrue(panel.focusTabsForTest().isEmpty())
        pumpVisible(manager.entries.snapshot().size, "Esc 退出后恢复完整列表")
        assertEquals(manager.entries.snapshot().size, panel.visibleEntryCount)
        // 无过滤态时 Esc no-op（不抛、不改状态）
        panel.exitNplusOneAggregate()
    }

    /** 回退开关：`sqlvis.log.nplusOneSuspect.aggregate` 关＝点击无过滤态（徽章纯行级）。 */
    fun test回退开关_关_点击无效() {
        val (_, entry) = addNplusOneFixtures()
        val old = NplusOneAggregate.enabledProvider
        NplusOneAggregate.enabledProvider = { false }
        try {
            assertFalse("开关关：点击徽章不进入过滤态", panel.tryEnterNplusOneAggregate(clickAt(badgeClickPoint(entry))))
            assertTrue(panel.focusTabsForTest().isEmpty())
        } finally {
            NplusOneAggregate.enabledProvider = old
        }
    }

    /** 命中区=徽章文本片段：SQL 区/条目空白处点击不进入过滤态（单击预览职责保留）。 */
    fun test非徽章区点击_不进入过滤态() {
        val (_, entry) = addNplusOneFixtures()
        val p = badgeClickPoint(entry)
        // 同一行的 SQL 区（cell 内、但超出 header 高 20+4 容差）——不能跨到下一行的 header 带
        val sqlArea = Point(p.x, p.y + 40)
        assertFalse("SQL 区点击不进入过滤态", panel.tryEnterNplusOneAggregate(clickAt(sqlArea)))
        assertTrue(panel.focusTabsForTest().isEmpty())
        // 异模板条目（无徽章）：借嫌疑条目反查出的徽章 x，点到该条目行的同一 x 上——无 tag 不命中
        val otherIndex = (0 until panel.list.model.size)
            .firstOrNull { (panel.list.model.getElementAt(it) as LogSqlEntry).restoredSql == OTHER }
        assertTrue("异模板条目应在列表中", otherIndex != null)
        val list = panel.list
        val otherBounds = list.getCellBounds(otherIndex!!, otherIndex)!!
        val otherClick = Point(p.x, otherBounds.y + 5)
        assertFalse("无徽章条目点击不进入过滤态", panel.tryEnterNplusOneAggregate(clickAt(otherClick)))
    }

    /** 源码扫描守卫（NplusOneBadgeFragmentTest 范式）：接线被摘即红。 */
    fun test接线守卫_点击与Esc都接了聚合过滤态() {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".")
        while (dir != null && !java.io.File(dir, "settings.gradle.kts").exists() &&
            !java.io.File(dir, "settings.gradle").exists()
        ) {
            dir = dir.parentFile
        }
        val src = java.io.File(dir, "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt").readText()
        assertTrue(
            "buildListPane mouseClicked 应接线 tryEnterNplusOneAggregate（D235）",
            src.contains("if (tryEnterNplusOneAggregate(e)) return"),
        )
        assertTrue(
            "Esc 应接线 exitNplusOneAggregate（D235）",
            src.contains("KeyEvent.VK_ESCAPE) exitNplusOneAggregate()"),
        )
        assertTrue(
            "返回全部按钮应接线 exitNplusOneAggregate（D235）",
            src.contains("addActionListener { exitNplusOneAggregate() }"),
        )
    }
}
