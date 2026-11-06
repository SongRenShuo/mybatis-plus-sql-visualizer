package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R53：双通道捕获状态栏纯函数测试（运行期，非源码扫描）。
 *
 * 覆盖状态栏四态：非捕捉态（清空）/ 总开关关 / 已挂载（含已收事件 N）/ 暂停中（含已收事件 N）。
 * bundle 在无平台服务环境下兜底中文（见 [cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle]
 * KDoc），故断言用中文特征串；若文案键缺失，message() 返回 key 本身，断言即失败（防「键被删」假绿）。
 */
class CaptureStatusBarTest {

    private fun line(
        capturing: Boolean = true,
        consolesAttached: Int = 1,
        linesSeen: Long = 10L,
        agentStatus: String? = "已挂载 pid=11",
        agentLivePids: List<Long> = listOf(11L),
        agentPaused: Boolean = false,
        agentEventsReceived: Long = 3L,
        unregistered: List<String> = emptyList(),
    ) = CaptureStatusLine(
        capturing, consolesAttached, linesSeen, agentStatus, agentLivePids, agentPaused, agentEventsReceived,
        unregistered,
    )

    @Test
    fun test非捕捉态无状态栏文案() {
        assertNull("非捕捉态状态栏应清空（返回 null）", captureStatusLineText(line(capturing = false)))
    }

    @Test
    fun test总开关关显示未开启() {
        val (console, agent) = captureStatusLineText(line(agentStatus = null))!!
        assertTrue("控制台段应带已挂载数与扫描行数：$console", console.contains("控制台"))
        assertTrue("控制台段应带行数读数：$console", console.contains("10"))
        assertEquals("总开关关 → 增强段「未开启」", "增强：未开启", agent)
    }

    @Test
    fun test已挂载显示pid与已收事件() {
        val (_, agent) = captureStatusLineText(line())!!
        assertTrue("应带已挂载叙事：$agent", agent.contains("已挂载"))
        assertTrue("应带 pid：$agent", agent.contains("pid=11"))
        assertTrue("应带已收事件：$agent", agent.contains("已收事件"))
        assertTrue("应带事件数：$agent", agent.contains("3"))
    }

    @Test
    fun test暂停中显示暂停且已收事件照常() {
        val (_, agent) = captureStatusLineText(line(agentPaused = true))!!
        assertTrue("暂停态应明示「暂停中」：$agent", agent.contains("暂停中"))
        assertTrue("暂停态应保留挂载叙事：$agent", agent.contains("已挂载"))
        assertTrue("暂停态应带已收事件（暂停≠通道断）：$agent", agent.contains("已收事件"))
    }

    @Test
    fun test未挂载显示等待叙事() {
        val (_, agent) = captureStatusLineText(
            line(agentStatus = "已授权，等待应用进程启动后挂载", agentLivePids = emptyList()),
        )!!
        assertTrue("未挂载应透传既有等待文案：$agent", agent.contains("等待应用进程启动"))
        assertFalse("未挂载态不得说成已挂载：$agent", agent.contains("已挂载"))
    }

    @Test
    fun test控制台段行数读数透传() {
        val (console, _) = captureStatusLineText(line(consolesAttached = 2, linesSeen = 88L))!!
        assertTrue("控制台段应带已挂载数 2：$console", console.contains("2"))
        assertTrue("控制台段应带已扫描行数 88：$console", console.contains("88"))
    }

    // ---------- T-IbatisPanwei 券 D：未登记 JDBC 实现落状态栏 ----------

    /**
     * 券 D 守卫：控制台有产出时空态诊断行整条不显示（entryCount>0 早退），
     * 「驱动没登记」这条事实只能靠常驻状态栏被看见。
     *
     * 反向验证：摘掉 [captureStatusLineText] 里的 unregistered 拼接，本用例必须红。
     */
    @Test
    fun test未登记JDBC实现挂到增强段且保留既有叙事() {
        val (_, agent) = captureStatusLineText(
            line(unregistered = listOf("org.panweidb.jdbc.PgPreparedStatement")),
        )!!
        assertTrue("必须带出未登记类名：$agent", agent.contains("org.panweidb.jdbc.PgPreparedStatement"))
        assertTrue("必须保留既有增强叙事（已挂载/已收事件）：$agent", agent.contains("已挂载"))
        assertTrue("必须保留已收事件读数：$agent", agent.contains("已收事件"))
        assertTrue("必须明示「未登记」：$agent", agent.contains("未登记 JDBC 实现"))
    }

    @Test
    fun test未登记列表为空时不改变既有文案() {
        val (_, agent) = captureStatusLineText(line())!!
        assertTrue("无未登记实现不得出现该段：$agent", !agent.contains("未登记"))
    }

    @Test
    fun test增强未开启时不挂未登记段() {
        val (_, agent) = captureStatusLineText(
            line(agentStatus = null, unregistered = listOf("a.b.C")),
        )!!
        assertEquals("增强未开启时无 attach 事实可报", "增强：未开启", agent)
    }
}
