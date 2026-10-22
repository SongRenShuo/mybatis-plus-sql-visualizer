package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * M56 T-M56-01 门禁④：attach 失败 → 回落桥零劣化（闸门不闭合、桥继续捕获）；
 * 幂等表与惰性挂载入口的守卫分支（增强未开启时空操作）。
 *
 * T-EnhancedCaptureDefaultOn（2026-10-19）：注入确认流已移除，本类不再覆盖「未确认 → 弹框/拒绝」
 * 分支；新增「旧 confirmed=false 不得再拦挂载」的行为守卫。
 */
class AgentManagerFallbackTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        PluginSettings.getInstance().state.enhancedCaptureEnabled = false
        // 防御性还原默认时间源（实例可能跨测试类复用）
        manager.clockMs = { System.currentTimeMillis() }
    }

    override fun tearDown() {
        manager.shutdownAll()
        PluginSettings.getInstance().state.enhancedCaptureEnabled = false
        super.tearDown()
    }

    fun test增强未开启_attach入口与进程回调均为空操作() {
        val fakeHandler = FakeProcessHandler(nativePid = 424242L)
        manager.onProcessAppeared(fakeHandler) // 守卫分支：不 attach 不抛错
        assertFalse("增强关闭时闸门不得闭合", SqlCaptureGate.paused.get())
        assertFalse("增强关闭时不在线", manager.online)
    }

    // ---------- 默认值（2026-09-16 定案：增强捕获默认开、脱敏默认关） ----------

    fun test默认值_增强捕获开_脱敏关_确认字段已归一() {
        val fresh = PluginSettings.State()
        assertTrue("增强捕获默认应开启", fresh.enhancedCaptureEnabled)
        assertFalse("增强捕获脱敏默认应关闭", fresh.enhancedCaptureDesensitize)
        assertTrue(
            "T-EnhancedCaptureDefaultOn：确认字段默认已归一为 true（不再作为授权闸门）",
            fresh.enhancedCaptureConfirmed,
        )
        assertEquals("拒绝冷却默认应为 0", 0L, fresh.enhancedCaptureRejectedAtMs)
    }

    /**
     * G2 行为守卫：旧 XML 残留 confirmed=false 时不得再拦挂载。
     * 反向：恢复 `if (!isEnhancedConfirmed()) { requestConsentThenAttach(...) }` → 本用例必红。
     */
    fun test旧未确认态不再拦挂载_直接入队() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_991L))
        assertTrue(
            "confirmed=false 不得再拦挂载（应直接进入 attach 排队）",
            manager.pendingPids.containsKey(999_999_991L),
        )
    }

    fun test开启但attach不可达_回落桥零劣化() {
        PluginSettings.getInstance().state.enhancedCaptureEnabled = true
        // 不存在的 pid（本机不可能有 4 个 9 的 pid 场景按失败处理）→ 回落桥
        val attached = manager.attachToPid(999_999_999L)
        assertFalse("不可达 pid 应 attach 失败", attached)
        assertFalse("失败后闸门不得闭合（桥继续捕获）", SqlCaptureGate.paused.get())
        assertFalse(manager.online)
    }

    fun testpid解析_getNativePid能力探测与回退() {
        assertEquals(424242L, manager.resolvePid(FakeProcessHandler(nativePid = 424242L)))
        assertEquals(777L, manager.resolvePid(FakeProcessHandler(processPid = 777L)))
        assertEquals(null, manager.resolvePid(FakeProcessHandler()))
        assertEquals(null, manager.resolvePid(null))
    }

    fun test会话与幂等表_关闭开关回落() {
        // 无会话时 shutdownAll 安全（幂等）
        manager.shutdownAll()
        assertFalse(SqlCaptureGate.paused.get())
        assertFalse(manager.online)
    }

    /** 反射缝假 ProcessHandler：可控暴露 getNativePid / getProcess().pid()。 */
    private class FakeProcessHandler(
        private val nativePid: Long? = null,
        private val processPid: Long? = null,
    ) {
        fun getNativePid(): Long? = nativePid

        fun getProcess(): Process? = processPid?.let { pid ->
            object : Process() {
                override fun pid(): Long = pid
                override fun getOutputStream() = throw UnsupportedOperationException()
                override fun getInputStream() = throw UnsupportedOperationException()
                override fun getErrorStream() = throw UnsupportedOperationException()
                override fun waitFor() = 0
                override fun exitValue() = 0
                override fun destroy() = Unit
            }
        }
    }
}
