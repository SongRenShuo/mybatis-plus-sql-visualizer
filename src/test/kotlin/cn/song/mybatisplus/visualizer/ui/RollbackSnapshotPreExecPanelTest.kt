package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotCapture
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotFileStore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * T-RollbackSnapshotPreExec P2 编排测试（平台桶）：执行前快照采集的「确认 → 采集 → 提交」
 * 编排与 fail-open 语义。采集成功落盘走 [RollbackSnapshotProjectStore.persist]（池线程异步），
 * 本测试只断言编排层的**可同步观察面**——查询通道调用计数、onProceed 时序、状态行提示；
 * 落盘内容断言归纯逻辑层（RollbackSnapshotCaptureAndStoreTest）。
 */
class RollbackSnapshotPreExecPanelTest : BasePlatformTestCase() {

    private fun panel() = SqlPreviewPanel(project)

    private fun settings(): PluginSettings.State = PluginSettings.getInstance().state

    /**
     * 假数据源：`ExecDialectRouting.dataSourceUrl` 反射 `getUrl()`、`DataSourceSettings`
     * 反射 `getName()/getUniqueId()`（jdbc:mysql 子协议 → 方言名 MySQL，与下方显式闸请求 MATCH）。
     */
    private val fakeTarget = object {
        fun getUrl(): String = "jdbc:mysql://localhost:3306/fake"
        fun getName(): String = "fake-ds"
        fun getUniqueId(): String = "fake-1"
    }

    private fun matchGateRequest() = cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request(
        renderMode = cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.RenderMode.AUTO,
        renderedDialectName = "MySQL",
        intent = cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Intent.QUERY,
        canLosslesslyRerender = true,
        userTextUntouched = true,
    )

    override fun setUp() {
        super.setUp()
        settings().rollbackSnapshotBeforeExec = false
    }

    override fun tearDown() {
        settings().rollbackSnapshotBeforeExec = false
        captureTargetForTest = null
        captureGateRequestForTest = null
        PreviewSideQuery.runnerForTest = null
        super.tearDown()
    }

    private class Counter {
        var queries = 0
        var proceedCalls = 0
        var proceedAfterQuery = false
        var querySeenBeforeProceed = false
    }

    /** 驱动一次采集编排；runner 同步回调（测试线程即回调线程），断言时序可精确观察。 */
    private fun drive(sql: String): Counter {
        val counter = Counter()
        val oldRunner = PreviewSideQuery.runnerForTest
        PreviewSideQuery.runnerForTest = { _, _, _, _, cb ->
            counter.queries++
            counter.querySeenBeforeProceed = counter.proceedCalls == 0
            cb(PreviewSideQuery.Result.Table(McpExplainFormatter.RawResult(listOf("id"), listOf(listOf("1")))))
        }
        val oldTarget = captureTargetForTest
        captureTargetForTest = { fakeTarget }
        val oldGate = captureGateRequestForTest
        captureGateRequestForTest = { matchGateRequest() }
        try {
            panel().captureSnapshotBeforeExecThen(project, sql) {
                counter.proceedCalls++
                counter.proceedAfterQuery = counter.queries > 0
            }
        } finally {
            PreviewSideQuery.runnerForTest = oldRunner
            captureTargetForTest = oldTarget
            captureGateRequestForTest = oldGate
        }
        return counter
    }

    fun testSwitchOffMeansZeroQueriesAndImmediateProceed() {
        settings().rollbackSnapshotBeforeExec = false
        val counter = drive("UPDATE t_order SET a = 1 WHERE id = 5")
        assertEquals("开关关闭必须零查询", 0, counter.queries)
        assertEquals("开关关闭必须立即提交执行", 1, counter.proceedCalls)
    }

    fun testSwitchOnCapturableRunsQueryBeforeProceed() {
        settings().rollbackSnapshotBeforeExec = true
        val counter = drive("UPDATE t_order SET a = 1 WHERE id = 5")
        assertEquals("开关开启且可采集必须发起一次查询", 1, counter.queries)
        assertTrue("提交必须发生在查询之后（改前镜像顺序契约）", counter.proceedAfterQuery)
    }

    fun testSwitchOnInsertSkipsCapture() {
        settings().rollbackSnapshotBeforeExec = true
        val counter = drive("INSERT INTO t (id) VALUES (1)")
        assertEquals("INSERT 不采集", 0, counter.queries)
        assertEquals(1, counter.proceedCalls)
    }

    fun testSwitchOnUnfilledParameterSkipsCapture() {
        settings().rollbackSnapshotBeforeExec = true
        val counter = drive("UPDATE t SET a = 1 WHERE id = ?")
        assertEquals("未填值参数不采集（只读旁路无法执行带 ? 的 SELECT）", 0, counter.queries)
        assertEquals(1, counter.proceedCalls)
    }

    fun testSwitchOnWithoutDataSourceStillProceeds() {
        settings().rollbackSnapshotBeforeExec = true
        val counter = Counter()
        val oldRunner = PreviewSideQuery.runnerForTest
        PreviewSideQuery.runnerForTest = { _, _, _, _, _ ->
            org.junit.Assert.fail("无数据源时不应发起查询")
        }
        val oldTarget = captureTargetForTest
        captureTargetForTest = null // 不注入假目标 → boundConsoleDataSource() 在 headless 恒 null
        try {
            panel().captureSnapshotBeforeExecThen(project, "UPDATE t SET a = 1 WHERE id = 5") {
                counter.proceedCalls++
            }
        } finally {
            PreviewSideQuery.runnerForTest = oldRunner
            captureTargetForTest = oldTarget
        }
        assertEquals("无数据源时 fail-open：不采集但照常提交", 1, counter.proceedCalls)
        assertEquals("无数据源时不发起查询", 0, counter.queries)
    }

    fun testCapturePlanConstantsMatchPlan() {
        // 计划 §3.1 固定值的钉子：改这些常量必须同批改设置文案与计划
        assertEquals(500, RollbackSnapshotCapture.ROW_LIMIT)
        assertEquals(2, RollbackSnapshotCapture.TIMEOUT_SECONDS)
        assertEquals(".sql-visualizer/snapshots", RollbackSnapshotCapture.SNAPSHOT_DIR_RELATIVE)
        assertEquals(7, RollbackSnapshotFileStore.KEEP_DAYS)
        assertEquals(200, RollbackSnapshotFileStore.MAX_ENTRIES)
    }
}
