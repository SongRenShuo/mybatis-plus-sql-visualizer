// T-SyncK2JMaster180 测试移植自 master src/test/kotlin/cn/song/mybatisplus/visualizer/log/NplusOneSuspectTest.kt
// 互操作适配：isSuspect/windowSuspect 具名实参（explicitThreshold=）→位置实参；WindowEntry record 具名实参→位置构造。
package cn.song.mybatisplus.visualizer.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HdAbsorb D216（N+1 嫌疑判定，第一刀）纯逻辑：阈值门 + Registry 回退通道。
 *
 * 反向验证锚点：test开关关_恒不判嫌疑——摘掉 enabledProvider 门（或整段嫌疑判定）时，
 * 该用例与 NplusOneBadgeFragmentTest 的接线断言转红。
 */
class NplusOneSuspectTest {

    @After
    fun restore() {
        NplusOneSuspect.enabledProvider = {
            runCatching { com.intellij.openapi.util.registry.Registry.`is`(NplusOneSuspect.REGISTRY_KEY, true) }
                .getOrDefault(true)
        }
        NplusOneSuspect.thresholdProvider = { NplusOneSuspect.DEFAULT_MIN_COUNT }
    }

    @Test
    fun test默认阈值_同模板计数达标判嫌疑() {
        assertTrue(NplusOneSuspect.isSuspect(5))
        assertTrue(NplusOneSuspect.isSuspect(9))
        assertFalse(NplusOneSuspect.isSuspect(4))
        assertFalse(NplusOneSuspect.isSuspect(null))
        assertFalse(NplusOneSuspect.isSuspect(0))
    }

    @Test
    fun test显式阈值_覆盖默认() {
        assertTrue(NplusOneSuspect.isSuspect(2, 2))
        assertFalse(NplusOneSuspect.isSuspect(2, 3))
        // 显式阈值也受开关门（关 = 恒 false）
        NplusOneSuspect.enabledProvider = { false }
        assertFalse(NplusOneSuspect.isSuspect(9, 2))
    }

    @Test
    fun test开关关_恒不判嫌疑() {
        NplusOneSuspect.enabledProvider = { false }
        assertFalse(NplusOneSuspect.isSuspect(100))
    }

    @Test
    fun test生产阈值提供方_无Registry环境回落默认() {
        // 纯 JUnit 下 Registry 不可用（或键未改）→ 归一为 DEFAULT_MIN_COUNT；非法（<2）同样回落
        assertEquals(NplusOneSuspect.DEFAULT_MIN_COUNT, NplusOneSuspect.thresholdProvider.invoke())
    }

    // ------------------------------------------------------------------
    // 第二刀（线程 + 滑动窗退化口径）
    // ------------------------------------------------------------------

    private fun w(ts: Long, thread: String?, key: String = "k") =
        NplusOneSuspect.WindowEntry(ts, thread, key)

    @Test
    fun test窗口判定_同线程短窗重复判嫌疑() {
        val target = w(1_000L, "http-nio")
        val siblings = (0 until 4).map { w(1_000L + it * 100L, "http-nio") } + target
        // 窗内同线程同模板 5 条（含目标）→ 嫌疑
        assertTrue(NplusOneSuspect.windowSuspect(target, siblings, 5))
        // 阈值 6 不够
        assertFalse(NplusOneSuspect.windowSuspect(target, siblings, 6))
    }

    @Test
    fun test窗口判定_异线程不计数() {
        val target = w(1_000L, "http-nio")
        val siblings = (0 until 9).map { w(1_000L + it * 100L, "pool-thread") } + target
        assertFalse(NplusOneSuspect.windowSuspect(target, siblings, 5))
    }

    @Test
    fun test窗口判定_窗外不计数() {
        val target = w(1_000_000L, "http-nio")
        val siblings = (0 until 4).map { w(900_000L + it * 100L, "http-nio") } + target
        assertFalse(NplusOneSuspect.windowSuspect(target, siblings, 5))
    }

    @Test
    fun test窗口判定_异模板不计数() {
        val target = w(1_000L, "http-nio")
        val siblings = (0 until 4).map { w(1_000L + it * 100L, "http-nio", "other") } + target
        assertFalse(NplusOneSuspect.windowSuspect(target, siblings, 5))
    }
}
