package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 券04（T-PerfSweep27-04）审计旁路表清扫语义单测。
 *
 * 背景：AUDIT_CTX 的双路径记账（AuditGate.resumeWith / onFailure，含取消 INTERRUPTED，O-86）
 * 覆盖全部**可 resume** 形态；唯一残留缝隙是「工具协程被取消且 continuation 永不 resume」——
 * 该形态的 JVM 单测不可行（见 [McpAuditGateTest] 头注：取消/失败路径由 262 沙盒 [MCP-AUDIT]
 * 对账），故本类只验清扫函数语义与 audit() 接线的存量回归：
 *  - 过期条目（started 超 [McpToolsetSupport.AUDIT_CTX_STALE_MS]）被移除，新条目保留；
 *  - 超限时按 started 移除最旧至限内；
 *  - 接线回归：audit() 成功路径记账后表内无残留（McpAuditGateTest 同形态）。
 *
 * k2j 适配：sweepAuditContexts/AUDIT_CTX_MAX/AUDIT_CTX_STALE_MS 为 Java 包私有静态承载
 * （Kotlin internal 等价），同包 .kt 测试直调；audit 为同步 Java 承载（ToolBlock 无挂起形态），
 * 探针块不需挂起点，直接同步返回。
 */
class McpAuditCtxSweepTest {

    @Test
    fun `过期条目被清除_新条目保留`() {
        McpToolsetSupport.AUDIT_CTX.clear()
        val now = 1_000_000_000L
        McpToolsetSupport.AUDIT_CTX[1] = McpToolsetSupport.AuditContext("sqlv_x", "old", now - McpToolsetSupport.AUDIT_CTX_STALE_MS - 1)
        McpToolsetSupport.AUDIT_CTX[2] = McpToolsetSupport.AuditContext("sqlv_x", "fresh", now - 1_000)
        McpToolsetSupport.sweepAuditContexts(now)
        assertFalse("过期条目应被移除", McpToolsetSupport.AUDIT_CTX.containsKey(1L))
        assertTrue("未过期条目应保留", McpToolsetSupport.AUDIT_CTX.containsKey(2L))
    }

    @Test
    fun `超限按 started 移除最旧至限内`() {
        McpToolsetSupport.AUDIT_CTX.clear()
        val now = 2_000_000_000L
        // 70 条未过期（started 递增，1 最旧），上限 64 → 应移除最旧 6 条
        repeat(70) { i ->
            McpToolsetSupport.AUDIT_CTX[i.toLong() + 10] =
                McpToolsetSupport.AuditContext("sqlv_x", "e$i", now - (70_000L - i))
        }
        McpToolsetSupport.sweepAuditContexts(now)
        assertEquals("超限后应收敛到上限 64", McpToolsetSupport.AUDIT_CTX_MAX, McpToolsetSupport.AUDIT_CTX.size)
        assertFalse("最旧条目（token=10）应被移除", McpToolsetSupport.AUDIT_CTX.containsKey(10L))
        assertTrue("最新条目（token=79）应保留", McpToolsetSupport.AUDIT_CTX.containsKey(79L))
    }

    /** 探针工具：形态照抄 McpAuditGateTest.ProbeTool——k2j 同步承载下无需挂起桥接（见类 KDoc）。 */
    private class ProbeTool : McpToolsetSupport("sqlv_probe") {
        fun run(): String = audit("sqlv_probe", "sweep-probe") { "ok" }.text
    }

    @Test
    fun `audit 成功路径接线回归_记账后表内无残留`() {
        McpToolsetSupport.AUDIT_CTX.clear()
        assertEquals("ok", ProbeTool().run())
        assertTrue("成功记账后 AUDIT_CTX 不应残留本次 token", McpToolsetSupport.AUDIT_CTX.isEmpty())
    }
}
