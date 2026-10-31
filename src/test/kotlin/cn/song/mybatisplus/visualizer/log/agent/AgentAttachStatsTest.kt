package cn.song.mybatisplus.visualizer.log.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-CaptureCatchAll 券03：agent 侧 attach 覆盖自检计数的宽容解析守卫。
 *
 * 守卫目标：agent 只在宿主 stderr 打一行 `matched=N wove=M …`，IDE 侧此前零出口——
 * 白名单漏驱动时用户只看到「插件坏了」。本组用例锁住：
 *  - 新形态（心跳 body 带键）能解析；
 *  - 旧版 agent（`{}` / 事件 JSON）优雅降级为 null（零行为变化）；
 *  - 单键缺失按 0 处理（C1 未落地 `matchedByInterface` 时不炸）；
 *  - 畸形数值/超长 body 不抛异常（红线：诊断不得影响事件主链）。
 *
 * k2j 互操作注（master afd3d091 终态移植）：AgentAttachStats 为 Java record，
 * 组件与派生判据均显式调用（weavable()/catchAllKilled() 等）。
 */
class AgentAttachStatsTest {

    @After
    fun tearDown() {
        AgentAttachReport.reset()
    }

    @Test
    fun `完整心跳计数可解析`() {
        // C1 路真实心跳形态（键序固定、无空格）：无 matched 键，命中视角走 weavable
        val stats = AgentAttachStats.parse(
            """{"weavable":12,"woven":10,"failed":1,"matchedByInterface":3,"interfaceWoven":2,"skippedByVersion":4,"catchAll":2}""",
        )!!
        assertEquals(12L, stats.weavable())
        assertEquals(10L, stats.woven())
        assertEquals(1L, stats.failed())
        assertEquals(3L, stats.matchedByInterface())
        assertEquals(2L, stats.interfaceWoven())
        assertEquals(4L, stats.skippedByVersion())
        assertEquals("熔断态可识别", 2, stats.catchAll())
        assertTrue("熔断态判定", stats.catchAllKilled())
        assertEquals("命中数取 weavable（心跳无 matched 键）", 12L, stats.matchedTotal())
        assertTrue("有织入产物", stats.anyWoven())
    }

    @Test
    fun `心跳只带weavable时不得报命中0`() {
        // C1 心跳不含 matched 键——若按 matched 判零命中会误报「本次 attach 命中 0 个驱动」
        val stats = AgentAttachStats.parse("""{"weavable":5,"woven":5,"failed":0}""")!!
        assertEquals(0L, stats.matched())
        assertEquals(5L, stats.matchedTotal())
        assertTrue("weavable>0 即非零命中", !stats.zeroMatched())
    }

    @Test
    fun `旧版agent心跳空body返回null_零行为变化`() {
        assertNull("旧版心跳 body 为 {}：不得产生任何覆盖结论", AgentAttachStats.parse("{}"))
        assertNull("空 body", AgentAttachStats.parse(""))
    }

    @Test
    fun `事件帧不含attach键时返回null`() {
        val eventBody = """{"sql":"SELECT * FROM matched_order WHERE id = ?","p":[[1,"1","Long"]]}"""
        assertNull("事件帧（含 SQL 文本）不得被误读成 attach 计数", AgentAttachStats.parse(eventBody))
    }

    @Test
    fun `字段缺失按0处理_C1未落地matchedByInterface时优雅降级`() {
        val stats = AgentAttachStats.parse("""{"matched":5,"weavable":5,"woven":4}""")!!
        assertEquals(5L, stats.matched())
        assertEquals("未上报的接口兜底计数按 0（旧版 agent）", 0L, stats.matchedByInterface())
        assertEquals(0L, stats.failed())
        assertTrue("命中 >0 不算零命中", !stats.zeroMatched())
    }

    @Test
    fun `零命中可被识别`() {
        val stats = AgentAttachStats.parse("""{"matched":0,"weavable":0,"woven":0,"matchedByInterface":0}""")!!
        assertTrue("matched=0 即零命中（无事件来源的硬事实）", stats.zeroMatched())
    }

    @Test
    fun `畸形数值不抛异常且不误读`() {
        // 全部值畸形 → 无有效键 → null
        assertNull(AgentAttachStats.parse("""{"matched":"12","weavable":null,"woven":}"""))
        // 部分畸形 → 有效键照常解析
        val partial = AgentAttachStats.parse("""{"matched":"x","woven":7}""")!!
        assertEquals("畸形 matched 按缺省 0", 0L, partial.matched())
        assertEquals(7L, partial.woven())
    }

    @Test
    fun `超长body直接跳过_避免逐事件正则扫描`() {
        val huge = "{\"matched\":1,\"pad\":\"" + "x".repeat(9000) + "\"}"
        assertNull("超过 8KB 的 body 不扫描", AgentAttachStats.parse(huge))
    }

    @Test
    fun `最近一次上报整体覆盖而非累加`() {
        AgentAttachReport.updateFromFrame("""{"matched":3,"woven":3}""")
        assertEquals(3L, AgentAttachReport.latest()!!.matched())
        // 重连/重附后 agent 侧从零起算：整体覆盖（加法会把上一轮带进来）
        AgentAttachReport.updateFromFrame("""{"matched":1,"woven":1}""")
        assertEquals(1L, AgentAttachReport.latest()!!.matched())
        // 不含 attach 键的帧不覆盖现值
        AgentAttachReport.updateFromFrame("{}")
        assertEquals("普通心跳不得清掉已有覆盖事实", 1L, AgentAttachReport.latest()!!.matched())
    }
}
