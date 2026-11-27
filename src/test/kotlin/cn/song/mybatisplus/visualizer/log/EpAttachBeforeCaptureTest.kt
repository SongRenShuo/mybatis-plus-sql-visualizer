package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.testutil.AlreadyDisposedDiagnostics
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-EpAttachBeforeCapture 非 GUI 复现：
 *
 * 用户现象：两个应用单独启动都能捕获，并存时「先启动的那个」零捕获、只剩下后启动的。
 * 机制：EP 通道（consoleFilterProvider）只在控制台创建时装配，捕获服务尚未创建时
 * `getDefaultFilters` 返回空数组 → 旧控制台永远没有 EP；桥是捕获开启后补挂的，单独跑时
 * 靠桥能捕获。后建控制台的 EP 一旦产出条目就把**进程级** `epActive` 置位，旧控制台的桥
 * 随即被全局短路 → 旧控制台零产出、面板只剩后启动的那个。
 *
 * 与 [EpBridgeSinglePathTest] 的差别：那边 setUp 先建 manager，覆盖不到「EP 装配早于捕获
 * 服务创建」这一时序——正是缺陷盲区。本类用 provider 的 manager 查找缝模拟：
 * 先以 null 取 filter（控制台 A 创建时捕获未开），再把缝切到 manager（用户点捕获），
 * 断言同一个 filter 实例自动生效。
 */
class EpAttachBeforeCaptureTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        SqlCaptureGate.epActive = false
        SqlCaptureGate.paused.set(false)
    }

    override fun tearDown() {
        SqlCaptureGate.epActive = false
        super.tearDown()
    }

    /** 捕获未开（lookup 恒 null）时，控制台创建也必须拿到 EP 实例——旧实现返回空数组。 */
    fun test控制台先建_捕获未开_EP仍装配() {
        val provider = SqlConsoleBridgeFilterProvider({ null }, 0L)
        val filters = provider.getDefaultFilters(project)
        assertEquals("manager 未创建也必须有 EP（旧实现返回空数组，旧控制台永远没有 EP）", 1, filters.size)
    }

    /** 未开捕获时零处理：不产出、不抛、不创建服务（lookup 拿到 null 即放行）。 */
    fun test未开捕获时EP零处理() {
        var lookups = 0
        val provider = SqlConsoleBridgeFilterProvider({ lookups++; null }, 0L)
        val ep = provider.getDefaultFilters(project).single()

        assertNull(ep.applyFilter("==>  Preparing: SELECT 1", 30))
        assertNull(ep.applyFilter("==> Parameters: ", 16))
        assertTrue("未拿到 manager 时 filter 仍会惰性查找", lookups >= 1)
        assertTrue("未开捕获不得产出条目", SqlCaptureGate.epActive.not())
    }

    /**
     * 缺陷主链：先取 filter（控制台 A 创建，捕获未开）→ 用户开捕获（manager 出现）→
     * 同一 filter 实例处理行，必须产出条目并置位 epActive。
     */
    fun test先取Filter后开捕获_同一实例生效() {
        var manager: SqlLogManager? = null
        val provider = SqlConsoleBridgeFilterProvider({ manager }, 0L)
        val ep = provider.getDefaultFilters(project).single()

        // 捕获未开：行被忽略
        assertNull(ep.applyFilter("==>  Preparing: SELECT * FROM gm_item_a WHERE id = ?", 60))

        manager = AlreadyDisposedDiagnostics.projectService(
            project, SqlLogManager::class.java, "EpAttachBeforeCaptureTest.manager",
        )
        manager.entries.dedupeEnabled = false
        manager.entries.clear()
        manager.setCapturingForTest(true)

        ep.applyFilter("==>  Preparing: SELECT * FROM gm_item_a WHERE id = ?", 60)
        ep.applyFilter("==> Parameters: 1(Long)", 24)

        assertEquals("旧控制台在捕获开启后必须自动生效", 1, manager.entries.snapshot().size)
        assertTrue("EP 实证产出后置位（既有语义不变）", SqlCaptureGate.epActive)
    }

    /** 并存形态：先建 filter（旧控制台）与后建 filter（新控制台）都必须产出，互不吞并。 */
    fun test并存时先建与后建Filter都产出() {
        var manager: SqlLogManager? = null
        val provider = SqlConsoleBridgeFilterProvider({ manager }, 0L)
        val oldEp = provider.getDefaultFilters(project).single()

        manager = AlreadyDisposedDiagnostics.projectService(
            project, SqlLogManager::class.java, "EpAttachBeforeCaptureTest.manager2",
        )
        manager.entries.dedupeEnabled = false
        manager.entries.clear()
        manager.setCapturingForTest(true)
        val newEp = provider.getDefaultFilters(project).single()

        oldEp.applyFilter("==>  Preparing: SELECT * FROM gm_item_a WHERE id = ?", 60)
        oldEp.applyFilter("==> Parameters: 1(Long)", 24)
        newEp.applyFilter("==>  Preparing: SELECT * FROM gm_item_b WHERE id = ?", 60)
        newEp.applyFilter("==> Parameters: 2(Long)", 24)

        assertEquals("并存时两个控制台各产一条，不许只剩后启动的", 2, manager.entries.snapshot().size)
    }
}