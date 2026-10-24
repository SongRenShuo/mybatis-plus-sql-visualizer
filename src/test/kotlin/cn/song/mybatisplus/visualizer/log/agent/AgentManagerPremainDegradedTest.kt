package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicInteger

/**
 * T-JRebelPremainCrash C 案：premain 降级的 IDE 侧感知与一次性告知。
 *
 * 用户现场（2026-10-22，Corretto 21 + JRebel）：第三方 agent 接管 premain 类加载后重定位 ASM
 * 不可见，agent 侧 `install` 抛 `NoClassDefFoundError`。修复（A 案）后 agent 不再外抛、不再回连
 * ——IDE 侧唯一可观测信号就是**通道始终未被认领**。若无本机制的告知，用户只会看到「增强捕获
 * 悄悄少了一半」，误以为插件坏了。
 *
 * 本类钉住四件事：
 * 1. 通道过了抑制窗仍未认领 ⇒ 判定为降级；
 * 2. 窗口内（可能只是启动慢）**不得**误判；
 * 3. 提示**每项目会话一次**，不随运行结束复位（避免开发期反复弹窗）；
 * 4. 判定后**不阻断** attach 回落（降级恰恰是必须回落的场景）。
 */
class AgentManagerPremainDegradedTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager
    private var fakeNow = 1_000_000L

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        fakeNow = 1_000_000L
        manager.clockMs = { fakeNow }
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        manager.premainDegradedNotifiedThisSession.set(false)
        manager.attachFallbackNotifiedThisSession.set(false)
    }

    override fun tearDown() {
        try {
            manager.shutdownAll()
            PluginSettings.getInstance().state.enhancedCaptureEnabled = false
            manager.clockMs = { System.currentTimeMillis() }
        } finally {
            super.tearDown()
        }
    }

    /** 开一个真实 loopback 通道（preparePremainVmOption 的产物），返回端口。 */
    private fun openChannel(): Int {
        val port = manager.openPremainChannel()
        assertTrue("通道未打开（否则用例无意义）", port != null && port > 0)
        return port!!
    }

    fun test通道超过抑制窗未认领_判定为降级并提示一次() {
        val counter = AtomicInteger()
        manager.premainDegradedNotifier = { counter.incrementAndGet() }
        openChannel()

        // 抑制窗内：不得误判（可能只是应用启动慢）
        fakeNow += AgentManager.PREMAIN_SKIP_ATTACH_WINDOW_MS - 1
        assertFalse("抑制窗内不得判定降级（启动慢属正常）", manager.detectPremainDegraded())
        assertEquals("抑制窗内不得发提示", 0, counter.get())

        // 过窗：判定降级并提示
        fakeNow += 2
        assertTrue("超过抑制窗仍未认领必须判定为降级", manager.detectPremainDegraded())
        assertEquals("降级提示应发一次", 1, counter.get())
    }

    fun test降级提示每项目会话仅一次() {
        val counter = AtomicInteger()
        manager.premainDegradedNotifier = { counter.incrementAndGet() }
        openChannel()
        fakeNow += AgentManager.PREMAIN_SKIP_ATTACH_WINDOW_MS + 1

        repeat(5) { manager.detectPremainDegraded() }
        assertEquals("同一项目会话内降级提示只发一次（避免反复打扰）", 1, counter.get())
    }

    fun test运行结束后不复位降级提示标记() {
        val counter = AtomicInteger()
        manager.premainDegradedNotifier = { counter.incrementAndGet() }
        openChannel()
        fakeNow += AgentManager.PREMAIN_SKIP_ATTACH_WINDOW_MS + 1
        manager.detectPremainDegraded()
        assertEquals(1, counter.get())

        // 模拟一轮运行结束（通道关闭）
        manager.onRunEnded()
        // 新一轮再开通道并再次过窗
        openChannel()
        fakeNow += AgentManager.PREMAIN_SKIP_ATTACH_WINDOW_MS + 1
        manager.detectPremainDegraded()

        assertEquals(
            "降级提示应每项目会话一次，不随运行结束复位（开发期反复重启不该反复弹窗）",
            1, counter.get(),
        )
    }

    fun test无通道时不判定降级() {
        val counter = AtomicInteger()
        manager.premainDegradedNotifier = { counter.incrementAndGet() }
        fakeNow += AgentManager.PREMAIN_SKIP_ATTACH_WINDOW_MS + 1

        assertFalse("没有任何 premain 通道时不得判定降级（未注入不是降级）", manager.detectPremainDegraded())
        assertEquals(0, counter.get())
    }

    fun test提示出口异常不得打断判定链路() {
        manager.premainDegradedNotifier = { throw IllegalStateException("balloon unavailable") }
        openChannel()
        fakeNow += AgentManager.PREMAIN_SKIP_ATTACH_WINDOW_MS + 1

        // 通知失败必须被吞掉（否则会打断 attach 回落链路），且判定结果仍为 true
        assertTrue("通知出口抛异常不得影响降级判定", manager.detectPremainDegraded())
    }
}
