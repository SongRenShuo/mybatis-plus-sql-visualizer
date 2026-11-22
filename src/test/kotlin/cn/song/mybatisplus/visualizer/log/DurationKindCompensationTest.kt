package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.log.agent.AgentEvent
import cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter
import cn.song.mybatisplus.visualizer.log.agent.DebuggerSuspendTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-HdAbsorb D215：耗时双档语义 + 调试器挂起补偿。
 *
 * 覆盖：①[DebuggerSuspendTracker.compensatedDurationMs] 纯函数（无账本原值/全含扣除/部分交集/
 * 下限 0/开关关原值）；②档位融合 [mergedDurationKind]（EXECUTION > APPROXIMATE > null）；
 * ③agent 适配链打精确档（[AgentEventAdapter]）；④控制台通道近似档由桥通道实现（接线由
 * SqlLogConsoleBridge 源码口径承载，本类不打桩——桥通道为重型平台链，GUI 验收覆盖）。
 *
 * 反向验证锚点：[test补偿_无交集原值返回]——把 compensatedDurationMs 摘成恒返回原值（摘掉
 * 补偿接线）时，[test补偿_挂起区间全额扣除] 转红；agent 适配摘掉 EXECUTION 打标时
 * [testAgent适配_精确档] 转红。
 */
class DurationKindCompensationTest {

    @After
    fun restore() {
        DebuggerSuspendTracker.clearForTest()
        DebuggerSuspendTracker.plantIntervalsForTest(emptyList())
        DebuggerSuspendTracker.enabledProvider = {
            runCatching { com.intellij.openapi.util.registry.Registry.`is`(DebuggerSuspendTracker.REGISTRY_KEY, true) }
                .getOrDefault(true)
        }
    }

    @Test
    fun test补偿_无账本原值返回() {
        assertEquals(120L, DebuggerSuspendTracker.compensatedDurationMs(1_000L, 120L))
    }

    @Test
    fun test补偿_挂起区间全额扣除() {
        // 测量窗 [880, 1000)，挂起 [900, 990) 全含 → 扣 90
        DebuggerSuspendTracker.plantIntervalsForTest(listOf(900L..990L))
        assertEquals(30L, DebuggerSuspendTracker.compensatedDurationMs(1_000L, 120L))
    }

    @Test
    fun test补偿_部分交集只扣交集() {
        // 测量窗 [880, 1000)，挂起 [860, 900) → 交集 [880,900) = 20
        DebuggerSuspendTracker.plantIntervalsForTest(listOf(860L..900L))
        assertEquals(100L, DebuggerSuspendTracker.compensatedDurationMs(1_000L, 120L))
    }

    @Test
    fun test补偿_多区间累加() {
        // 测量窗 [880,1000)：区间一 [800,870] 无交集（窗口外）；区间二 [880,960] 交 80ms → 扣 80
        DebuggerSuspendTracker.plantIntervalsForTest(listOf(800L..870L, 880L..960L))
        assertEquals(40L, DebuggerSuspendTracker.compensatedDurationMs(1_000L, 120L))
    }

    @Test
    fun test补偿_开关关_原值返回() {
        DebuggerSuspendTracker.plantIntervalsForTest(listOf(900L..990L))
        DebuggerSuspendTracker.enabledProvider = { false }
        assertEquals(120L, DebuggerSuspendTracker.compensatedDurationMs(1_000L, 120L))
    }

    @Test
    fun test档位融合_EXECUTION优先() {
        assertEquals(DurationKind.EXECUTION, mergedDurationKind(DurationKind.EXECUTION, DurationKind.APPROXIMATE))
        assertEquals(DurationKind.EXECUTION, mergedDurationKind(null, DurationKind.EXECUTION))
        assertEquals(DurationKind.APPROXIMATE, mergedDurationKind(null, DurationKind.APPROXIMATE))
        assertEquals(DurationKind.APPROXIMATE, mergedDurationKind(DurationKind.APPROXIMATE, null))
        assertNull(mergedDurationKind(null, null))
    }

    @Test
    fun testAgent适配_精确档() {
        val e = AgentEventAdapter.toEntry(
            AgentEvent(
                sql = "SELECT 1",
                timestamp = 1_000L,
                durationNanos = 5_000_000L,
                updateCount = null,
                errorText = null,
                sourceFile = null,
                sourceLine = 0,
                methodKey = null,
                requestTag = null,
                params = emptyList(),
                threadName = "main",
            ),
        )
        assertEquals(5L, e.executionTimeMs)
        assertEquals(DurationKind.EXECUTION, e.durationKind)
        assertNotNull(e)
    }
}
