package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-CaptureTruth T-CT-01：增强捕获状态行纯函数测试（运行期，非源码扫描）。
 *
 * 立项动因回归守卫：原 `statusSummary()` 把「开关开着但从未授权」与「开着且正常」一起收敛成
 * null（设置页渲染成空白而复选框仍勾选），用户据此判断「增强是开着的」，实际一条 SQL 都捕不到。
 *
 * T-EnhancedCaptureDefaultOn（2026-10-19）：注入确认与拒绝冷却已移除，状态行不再有「未授权」
 * 两态；本测试相应收敛为「关闭 / 已挂载 / 等待进程 / 回落 / 心跳两因」。
 *
 * bundle 在无平台服务环境下兜底中文（见 [cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle]
 * KDoc），故断言用中文特征串；若文案键缺失，message() 返回 key 本身，断言即失败（防「键被删」假绿）。
 */
class AgentStatusTextTest {

    private fun snap(
        enabled: Boolean = true,
        livePids: List<Long> = emptyList(),
        everAttached: Boolean = false,
        heartbeatExpired: Boolean = false,
        firstHeartbeatPending: Boolean = false,
        heartbeatWindowSeconds: Long = 30L,
        activityFallbackActive: Boolean = false,
    ) = AgentStatusSnapshot(
        enabled, livePids, everAttached, heartbeatExpired,
        firstHeartbeatPending, heartbeatWindowSeconds, activityFallbackActive,
    )

    @Test
    fun test关闭态无叙事() {
        assertNull("增强关闭时状态行应为空（复选框未勾选，无需叙事）", AgentManagerKt.agentStatusText(snap(enabled = false)))
    }

    @Test
    fun test有活会话显示已挂载pid() {
        val text = AgentManagerKt.agentStatusText(snap(livePids = listOf(11L, 22L)))
        assertTrue("应含「已挂载」：$text", text!!.contains("已挂载"))
        assertTrue("应含 pid=11：$text", text.contains("pid=11"))
        assertTrue("应含 pid=22：$text", text.contains("pid=22"))
    }

    @Test
    fun test已开启从未挂载提示等待进程() {
        val text = AgentManagerKt.agentStatusText(snap(everAttached = false))
        assertTrue("应含「已开启」：$text", text!!.contains("已开启"))
        assertTrue("应提示等待应用进程启动：$text", text.contains("等待应用进程启动"))
    }

    @Test
    fun test曾挂载后中断显示回落() {
        val text = AgentManagerKt.agentStatusText(snap(everAttached = true))
        assertEquals("连接中断，已回落控制台捕获", text)
    }

    @Test
    fun test心跳过期回落文案与断连区分() {
        val heartbeat = AgentManagerKt.agentStatusText(snap(everAttached = true, heartbeatExpired = true))
        val disconnect = AgentManagerKt.agentStatusText(snap(everAttached = true))
        assertTrue("心跳停摆应明示「未收到应用侧心跳」：$heartbeat", heartbeat!!.contains("未收到应用侧心跳"))
        assertFalse("心跳态不得与断连态同文案：$heartbeat", heartbeat == disconnect)
    }

    // ---------- T-AgentHeartbeat T-05：文案只报可核实的事实，且「首条心跳未到」独立成态 ----------

    @Test
    fun test首条心跳未到走等待中文案不得说成心跳中断() {
        val pending = AgentManagerKt.agentStatusText(snap(everAttached = true, firstHeartbeatPending = true))!!
        assertTrue("应叙述「连接已建立」这一可核实事实：$pending", pending.contains("连接已建立"))
        assertTrue("应说明尚未收到心跳：$pending", pending.contains("尚未收到应用侧心跳"))
        assertFalse("连接早期不得说成心跳中断（现场错误叙事的形态）：$pending", pending.contains("心跳中断"))
        assertFalse("连接早期不得说成心跳停摆：$pending", pending.contains("心跳停摆"))
        assertFalse("连接早期不得断言进程卡死：$pending", pending.contains("卡死"))
    }

    @Test
    fun test心跳停摆文案不再断言进程卡死且带窗口秒数() {
        val lost = AgentManagerKt.agentStatusText(
            snap(everAttached = true, heartbeatExpired = true, heartbeatWindowSeconds = 30L),
        )!!
        assertTrue("应带出在线窗口秒数 30：$lost", lost.contains("30"))
        assertTrue("应叙述「未收到应用侧心跳」：$lost", lost.contains("未收到应用侧心跳"))
        assertFalse("不得再断言「进程可能已卡死」（代码无从得知该因果）：$lost", lost.contains("卡死"))
        assertFalse("不得再断言「进程可能已卡死」：$lost", lost.contains("进程可能"))
    }

    @Test
    fun test心跳停摆秒数取注入窗口值而非硬编码() {
        val lost = AgentManagerKt.agentStatusText(
            snap(everAttached = true, heartbeatExpired = true, heartbeatWindowSeconds = 7L),
        )!!
        assertTrue("秒数应取注入窗口值 7：$lost", lost.contains("7"))
        assertFalse("不得硬编码 30：$lost", lost.contains("30"))
    }

    @Test
    fun test首条心跳未到与心跳停摆两态文案互不相同() {
        val pending = AgentManagerKt.agentStatusText(snap(everAttached = true, firstHeartbeatPending = true))
        val lost = AgentManagerKt.agentStatusText(snap(everAttached = true, heartbeatExpired = true))
        assertFalse("两态不得收敛成同一句话：pending=$pending lost=$lost", pending == lost)
    }

    @Test
    fun test心跳过期与首条心跳未到同时置位时以停摆为准() {
        // 多会话同时摘除（一真停摆 + 一连接早期）时不得把真停摆降级成「等待首条心跳」
        val text = AgentManagerKt.agentStatusText(
            snap(
                everAttached = true,
                heartbeatExpired = true, firstHeartbeatPending = true,
            ),
        )!!
        assertTrue("应以「未收到应用侧心跳」为准：$text", text.contains("未收到应用侧心跳"))
        assertFalse("不得降级成连接早期叙述：$text", text.contains("连接已建立"))
    }

    @Test
    fun test各态文案互不重复() {
        val texts = listOf(
            AgentManagerKt.agentStatusText(snap(enabled = false)),
            AgentManagerKt.agentStatusText(snap(livePids = listOf(1L))),
            AgentManagerKt.agentStatusText(snap(everAttached = false)),
            AgentManagerKt.agentStatusText(snap(everAttached = true)),
            AgentManagerKt.agentStatusText(snap(everAttached = true, heartbeatExpired = true)),
            AgentManagerKt.agentStatusText(snap(everAttached = true, firstHeartbeatPending = true)),
        )
        val nonNull = texts.filterNotNull()
        assertEquals("五个非空态文案应互不相同（防两态收敛成同一句）", nonNull.size, nonNull.toSet().size)
    }

    /** T-EnhancedCaptureDefaultOn：未授权/冷却两态已删除，不得再出现相关文案。 */
    @Test
    fun test不再出现未授权与冷却叙事() {
        val texts = listOf(
            AgentManagerKt.agentStatusText(snap(everAttached = false)),
            AgentManagerKt.agentStatusText(snap(everAttached = true)),
        ).filterNotNull()
        texts.forEach { text ->
            assertFalse("状态行不得再出现「未授权」：$text", text.contains("未授权"))
            assertFalse("状态行不得再出现「冷却」：$text", text.contains("冷却"))
            assertFalse("状态行不得再出现「重新授权」：$text", text.contains("重新授权"))
        }
    }

    // ---------- T-CaptureCatchAll 券02：活性回落解封态 ----------

    @Test
    fun test活性回落解封态与已挂载同时出现() {
        val text = AgentManagerKt.agentStatusText(snap(livePids = listOf(11L), activityFallbackActive = true))!!
        assertTrue("会话还在这一事实不能丢（已挂载）：$text", text.contains("已挂载 pid=11"))
        assertTrue("必须如实披露已回退控制台解析：$text", text.contains("增强捕获疑似空转，已回退控制台解析"))
        val normal = AgentManagerKt.agentStatusText(snap(livePids = listOf(11L)))!!
        assertEquals("未解封时状态行保持原样", "已挂载 pid=11", normal)
        assertFalse("未解封时不得出现回落文案：$normal", normal.contains("增强捕获疑似空转"))
    }
}
