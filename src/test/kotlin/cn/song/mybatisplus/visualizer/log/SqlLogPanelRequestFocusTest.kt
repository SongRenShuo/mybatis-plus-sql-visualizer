package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-M55-04：请求级聚合过滤视图面板测试——「按请求聚焦」页签与 03 共用基础设施：
 * 请求页签过滤（扁平时间序）、无请求标签条目隐藏 + 计数提示、页签 K 有界截断（面板层）。
 * 「最近 K 个请求标签下拉」不另做（右键任意同标签条目等价可达，计划内二选一取轻）。
 */
class SqlLogPanelRequestFocusTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager
    private lateinit var panel: SqlLogPanel

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
        panel = SqlLogPanel(project, manager)
    }

    override fun tearDown() {
        panel.dispose()
        manager.dispose()
        super.tearDown()
    }

    private fun addEntry(sql: String, requestTag: String? = null) {
        assertTrue(manager.entries.addEntry(LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList(), requestTag = requestTag)))
    }

    fun test请求页签_单请求视图_扁平时间序() {
        addEntry("SELECT a1", "GET /api/user")
        addEntry("SELECT a2", "GET /api/user")
        addEntry("SELECT b1", "GET /api/order")
        addEntry("SELECT no-tag")

        panel.addFocusTabForTest(FocusTab.Request("GET /api/user"))
        assertEquals("仅该请求的 SQL（保持捕获序=扁平时间序）", listOf("SELECT a1", "SELECT a2"), panel.visibleEntries)
        assertTrue("无标签/异请求条目隐藏有计数提示", panel.focusHiddenLabelForTest.isVisible)
        assertTrue(panel.focusHiddenLabelForTest.text.contains("2"))

        panel.removeFocusTabForTest(FocusTab.Request("GET /api/user"))
        assertFalse("关闭后提示隐藏", panel.focusHiddenLabelForTest.isVisible)
    }

    fun test请求页签通过addRequestTab准入_K有界面板层收敛() {
        addEntry("SELECT a", "GET /api/user")
        // 连续添加超过 K 个不同请求页签：面板层收敛到 K，最早的请求页签被挤掉
        repeat(FOCUS_REQUEST_TAB_MAX + 2) { i -> panel.addFocusTabForTest(FocusTab.Request("req-$i")) }
        val tabs = panel.focusTabsForTest()
        val requestTabs = tabs.filterIsInstance<FocusTab.Request>()
        assertEquals("请求页签数收敛到 K", FOCUS_REQUEST_TAB_MAX, requestTabs.size)
        assertEquals("最旧请求页签逐次被挤（req-0/req-1 已落）", "req-2", requestTabs.first().requestTag)
        assertEquals("最新在末尾", "req-${FOCUS_REQUEST_TAB_MAX + 1}", requestTabs.last().requestTag)
        assertEquals("页签栏 chips = 前缀标签 + K + 计数标签", FOCUS_REQUEST_TAB_MAX + 2, panel.focusTabBarComponentCountForTest)
    }

    fun test重复请求页签_幂等不重复占位() {
        addEntry("SELECT a", "GET /api/user")
        panel.addFocusTabForTest(FocusTab.Request("GET /api/user"))
        panel.addFocusTabForTest(FocusTab.Request("GET /api/user"))
        assertEquals(1, panel.focusTabsForTest().size)
        assertEquals(listOf("SELECT a"), panel.visibleEntries)
    }
}
