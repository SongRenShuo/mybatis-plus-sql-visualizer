package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * T-SqlListCatchup 券A：notifyUi 刷新时间窗下限行为测试。
 *
 *  - 窗口外（首条/从未刷过/距上次完成超窗）第一条通知立即刷——保住「单条 SQL 秒显」；
 *  - 窗口内连发 N 条合并成有限拍（≤2：立即拍 + 至多一个 Alarm 补刷拍），不是 N 拍；
 *  - Alarm 到点竞态：已有更新在途（uiFlushPending）时不重复刷；
 *  - detach/dispose 后挂起的补刷拍不再产生刷新（无泄漏路径）。
 *
 * 调度值注入缝对齐 SenderLoop BackoffSleeper 同款思路（L18：断言「被请求调度的值」，
 * 不赌墙钟时序）：[SqlLogManager.catchUpScheduler] 注入记录器手动触发；
 * 窗口起点经 [SqlLogManager.lastFlushAtNanos] 钉住，不受测试机调度抖动影响。
 * EDT 泵空对齐 DedupeToggleTest 惯例（invokeLater 调度的 flush 须 dispatch 才执行）。
 */
class SqlLogManagerFlushWindowTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    /** 补刷调度记录器：(延迟 ms, 动作)——手动触发模拟 Alarm 到点。 */
    private val scheduledCatchUps = mutableListOf<Pair<Long, () -> Unit>>()

    private var flushCalls = 0

    private lateinit var uiListener: () -> Unit

    override fun setUp() {
        super.setUp()
        flushCalls = 0
        scheduledCatchUps.clear()
        manager = SqlLogManager(project)
        manager.catchUpScheduler = { delayMs, action -> scheduledCatchUps.add(delayMs to action) }
        uiListener = { flushCalls++ }
        manager.addUiListener(uiListener)
    }

    override fun tearDown() {
        try {
            manager.dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun entry(sql: String) =
        LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList())

    /** 泵空 EDT 队列（DedupeToggleTest 同款）。 */
    private fun pumpEdt() {
        UIUtil.dispatchAllInvocationEvents()
    }

    /** 建立一次已完成 flush 并把窗口起点钉在「刚刚」（确定性：后续通知必落窗口内，不赌测试机速度）。 */
    private fun pinWindowAtNow() {
        manager.entries.addEntry(entry("SELECT base"))
        pumpEdt()
        assertEquals("precondition：基准拍已刷", 1, flushCalls)
        manager.lastFlushAtNanos = System.nanoTime()
    }

    // ===== 窗口外第一条通知立即刷 =====

    fun test01_FirstNotificationAfterFreshStateFlushesImmediately() {
        // 全新状态 lastFlushAtNanos=0（从未刷过的哨兵）→ 立即刷，不挂补刷拍
        manager.entries.addEntry(entry("SELECT 1"))
        pumpEdt()
        assertEquals("首条通知立即刷（单条 SQL 秒显语义）", 1, flushCalls)
        assertTrue("窗口外不挂补刷拍", scheduledCatchUps.isEmpty())
    }

    fun test02_WindowElapsedNextNotificationFlushesImmediately() {
        pinWindowAtNow()
        // 把基准拨回窗口外（> 150ms 前）→ 下一条通知立即刷
        manager.lastFlushAtNanos = System.nanoTime() - SqlLogManager.MIN_FLUSH_INTERVAL_MS * 1_000_000L * 2
        manager.entries.addEntry(entry("SELECT 2"))
        pumpEdt()
        assertEquals("窗口外通知立即刷", 2, flushCalls)
        assertTrue("窗口外不挂补刷拍", scheduledCatchUps.isEmpty())
    }

    // ===== 窗口内连发 N 条合并成有限拍 =====

    fun test03_BurstWithinWindowCoalescesToBoundedFlushes() {
        pinWindowAtNow()
        // 窗口内连发 5 条（不泵 EDT——模拟风暴期通知密于刷新）
        repeat(5) { manager.entries.addEntry(entry("SELECT burst$it")) }
        pumpEdt()
        assertEquals("窗口内连发不产生立即拍", 1, flushCalls)
        assertEquals("只挂一个补刷拍（不随条数叠加）", 1, scheduledCatchUps.size)
        val (delayMs, action) = scheduledCatchUps[0]
        assertTrue(
            "补刷延迟落在 (0, 窗口值] 内（剩余延迟口径），实际=$delayMs",
            delayMs in 1..SqlLogManager.MIN_FLUSH_INTERVAL_MS,
        )
        // Alarm 到点（手动触发模拟剩余延迟已流逝）→ 补刷一拍
        action()
        pumpEdt()
        assertEquals("补刷恰好一拍：5 条通知合计 2 拍（非 5 拍）", 2, flushCalls)
    }

    fun test04_RepeatedBurstsStayWithinOneCatchUpPerWindow() {
        pinWindowAtNow()
        repeat(3) { manager.entries.addEntry(entry("SELECT a$it")) }
        repeat(3) { manager.entries.addEntry(entry("SELECT b$it")) }
        pumpEdt()
        assertEquals("窗口内多波次连发仍无立即拍", 1, flushCalls)
        assertEquals("窗口内只挂一个补刷拍", 1, scheduledCatchUps.size)
    }

    // ===== Alarm 在途竞态 =====

    fun test05_AlarmFiresWhileFlushInFlightSkipsDuplicate() {
        pinWindowAtNow()
        manager.entries.addEntry(entry("SELECT race"))
        assertEquals("precondition：补刷拍已挂", 1, scheduledCatchUps.size)
        // 模拟普通 flush 已在途（notifyUi→startFlush 之间 EDT 尚未执行）
        manager.setUiFlushPendingForTest(true)
        scheduledCatchUps[0].second()
        pumpEdt()
        assertEquals("Alarm 到点时已有更新在途——不重复刷", 1, flushCalls)
        // 在途解除后再到点 → 恢复补刷
        manager.setUiFlushPendingForTest(false)
        scheduledCatchUps[0].second()
        pumpEdt()
        assertEquals("在途解除后补刷恢复", 2, flushCalls)
    }

    // ===== detach/dispose 后补刷拍不再触发 =====

    fun test06_AfterDisposePendingCatchUpDoesNotFlush() {
        pinWindowAtNow()
        manager.entries.addEntry(entry("SELECT dispose"))
        assertEquals(1, scheduledCatchUps.size)
        manager.dispose()
        // dispose 后即使补刷拍再被触发（注入缝绕过真实 Alarm 的取消），notifyUi 空订阅守卫挡住
        scheduledCatchUps[0].second()
        pumpEdt()
        assertEquals("dispose 后不再刷新", 1, flushCalls)
    }

    fun test07_RemoveLastUiListenerDetachesCatchUp() {
        pinWindowAtNow()
        manager.entries.addEntry(entry("SELECT detach"))
        assertEquals(1, scheduledCatchUps.size)
        manager.removeUiListener(uiListener)
        // 移除最后一个订阅者后补刷拍即使被触发也不产生刷新（生产路径由 cancelAllRequests 先行取消）
        scheduledCatchUps[0].second()
        pumpEdt()
        assertEquals("removeUiListener 后不再刷新", 1, flushCalls)
    }
}
