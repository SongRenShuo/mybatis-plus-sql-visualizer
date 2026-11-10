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
        catchUpBehind: Int = 0,
        agentDropped: Long = 0L,
        suppressedSqlLines: Long = 0L,
        agentAssembleFailures: Long = 0L,
        agentNestSuppressed: Long = 0L,
        agentUnresolvedBlind: Long = 0L,
        agentBlindClasses: List<String> = emptyList(),
        ibatisBatchHint: Boolean = false,
    ) = CaptureStatusLine(
        capturing, consolesAttached, linesSeen, agentStatus, agentLivePids, agentPaused, agentEventsReceived,
        unregistered, catchUpBehind, agentDropped, suppressedSqlLines, agentAssembleFailures,
        agentNestSuppressed, agentUnresolvedBlind, agentBlindClasses, ibatisBatchHint,
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

    // ---------- T-PanweiInsertCapture F1/F2/F3/F6：四条新增读数 ----------

    @Test
    fun test让位丢弃SQL行_大于零才挂控制台段() {
        val (console, _) = captureStatusLineText(line(suppressedSqlLines = 7L))!!
        // 反向验证：摘掉 captureStatusLineText 里的 suppressedSqlLines 拼接，本断言必红
        assertTrue("让位丢弃可解析 SQL 行必须可见：$console", console.contains("让位丢弃 SQL 行 7"))

        val (quiet, _) = captureStatusLineText(line())!!
        assertFalse("零值不添噪：$quiet", quiet.contains("让位丢弃"))
    }

    @Test
    fun test组装失败计数_大于零才挂增强段() {
        val (_, agent) = captureStatusLineText(line(agentAssembleFailures = 3L))!!
        assertTrue("组装失败计数必须可见：$agent", agent.contains("组装失败 3"))

        val (_, quiet) = captureStatusLineText(line())!!
        assertFalse("零值不添噪：$quiet", quiet.contains("组装失败"))
    }

    @Test
    fun test门禁抑制计数_大于零才挂增强段() {
        val (_, agent) = captureStatusLineText(line(agentNestSuppressed = 12L))!!
        assertTrue("门禁抑制计数必须可见：$agent", agent.contains("门禁抑制 12"))
    }

    @Test
    fun test登记盲区_带类名后缀() {
        val (_, agent) = captureStatusLineText(
            line(
                agentUnresolvedBlind = 5L,
                agentBlindClasses = listOf("org.panweidb.jdbc.PgPreparedStatement"),
            ),
        )!!
        assertTrue("盲区次数必须可见：$agent", agent.contains("登记盲区 5 次"))
        assertTrue("盲区类名必须点名：$agent", agent.contains("org.panweidb.jdbc.PgPreparedStatement"))
    }

    @Test
    fun test登记盲区_无类名时不带空括号() {
        val (_, agent) = captureStatusLineText(line(agentUnresolvedBlind = 2L))!!
        assertTrue("盲区次数仍要报：$agent", agent.contains("登记盲区 2 次"))
        assertFalse("无类名不得出现空括号：$agent", agent.contains("（）"))
    }

    @Test
    fun test增强未开启时三条agent读数不挂段() {
        val (_, agent) = captureStatusLineText(
            line(
                agentStatus = null,
                agentAssembleFailures = 3L,
                agentNestSuppressed = 4L,
                agentUnresolvedBlind = 5L,
            ),
        )!!
        assertEquals("增强未开启时无 agent 事实可报", "增强：未开启", agent)
    }

    @Test
    fun test_iBatis批处理提示_仅在增强未开启时出现() {
        val (_, off) = captureStatusLineText(line(agentStatus = null, ibatisBatchHint = true))!!
        assertTrue("iBatis + 增强未开启：应提示批处理不可见：$off", off.contains("iBatis 批处理"))

        val (_, on) = captureStatusLineText(line(ibatisBatchHint = true))!!
        assertFalse("增强已开启时由 agent 兜住，不再提示：$on", on.contains("iBatis 批处理"))

        val (_, absent) = captureStatusLineText(line(agentStatus = null, ibatisBatchHint = false))!!
        assertFalse("未识别到 iBatis 形态时不提示：$absent", absent.contains("iBatis 批处理"))
    }

    // ---------- T-SqlListCatchup 券 F2a：agent 侧事件队列丢弃累计落状态栏 ----------

    /**
     * F2a 守卫：丢弃累计 >0 时增强段必须带出「已丢弃 N 条」且保留既有叙事。
     * 真机背景：第三轮压测「已收事件 2,227 条」在控制台停输出后仍缓慢上涨（≈队列容量+排空尾量），
     * 丢弃本身非阻塞是对的，但「丢了多少」必须用户可见。
     * 反向验证：摘掉 [captureStatusLineText] 里的 agentDropped 拼接，本用例必须红。
     */
    @Test
    fun test丢弃累计大于零时挂增强段尾() {
        val (_, agent) = captureStatusLineText(line(agentDropped = 138L))!!
        assertTrue("必须明示丢弃：$agent", agent.contains("已丢弃"))
        assertTrue("必须带出丢弃条数 138：$agent", agent.contains("138"))
        assertTrue("必须保留既有增强叙事（已挂载/已收事件）：$agent", agent.contains("已收事件"))
    }

    @Test
    fun test丢弃为零时不挂段() {
        val (_, agent) = captureStatusLineText(line(agentDropped = 0L))!!
        assertFalse("零丢弃不得出现丢弃段：$agent", agent.contains("已丢弃"))
    }

    @Test
    fun test增强未开启时不挂丢弃段() {
        val (_, agent) = captureStatusLineText(line(agentStatus = null, agentDropped = 5L))!!
        assertEquals("增强未开启无 attach 事实可报", "增强：未开启", agent)
    }

    // ---------- T-SqlListCatchup 券 D：追赶读数落状态栏 ----------

    /**
     * 券 D 守卫：落后数 >0 时控制台段必须带出「追赶中：落后 N 条」。
     * 反向验证：摘掉 [captureStatusLineText] 里的 catchUpBehind 拼接，本用例必须红。
     */
    @Test
    fun test追赶读数大于零时挂控制台段尾() {
        val (console, _) = captureStatusLineText(line(catchUpBehind = 37))!!
        assertTrue("必须明示追赶中：$console", console.contains("追赶中"))
        assertTrue("必须带出落后条数 37：$console", console.contains("37"))
        assertTrue("必须保留既有控制台叙事：$console", console.contains("控制台"))
    }

    @Test
    fun test追赶读数为零时不挂段() {
        val (console, _) = captureStatusLineText(line(catchUpBehind = 0))!!
        assertFalse("追平态不得出现追赶读数：$console", console.contains("追赶中"))
    }

    @Test
    fun test默认无追赶读数_既有文案不变() {
        val (console, _) = captureStatusLineText(line())!!
        assertFalse("默认构造（catchUpBehind=0）不得出现追赶读数：$console", console.contains("追赶中"))
    }

    // ---------- T-SqlListCatchup 券 D：追赶徽章一拍节流状态机（防闪烁） ----------

    private fun beats(vararg behinds: Int): List<Int> {
        val st = CatchUpBadgeState()
        return behinds.map { st.onNextBeat(it) }
    }

    @Test
    fun test节流_升起立即显示() {
        assertEquals("落后 >0 第一拍立即显示（升起不等拍）", listOf(5), beats(5))
    }

    @Test
    fun test节流_追平持有一拍后消失() {
        // 5 → 0 → 0：第一拍显示 5；归零第一拍持有 5（=0 持续一拍）；归零第二拍消失
        assertEquals(listOf(5, 5, 0), beats(5, 0, 0))
    }

    @Test
    fun test节流_短暂归零又升起不闪烁() {
        // 5 → 0 → 7：持有拍吸收归零抖动，续显新数——全程无 0（不熄灭）
        assertEquals(listOf(5, 5, 7), beats(5, 0, 7))
    }

    @Test
    fun test节流_持续落后每拍透传最新读数() {
        assertEquals("风暴期每拍显示当下落后数", listOf(3, 12, 9, 1), beats(3, 12, 9, 1))
    }

    @Test
    fun test节流_从零起步的孤立抖动不升起() {
        // 从未显示过：孤立一个 0 前后无落后 → 恒隐藏（持有语义只在「已显示」后生效）
        assertEquals(listOf(0, 0, 0), beats(0, 0, 0))
    }

    @Test
    fun test节流_reset后从干净态起算() {
        val st = CatchUpBadgeState()
        assertEquals(5, st.onNextBeat(5))
        st.reset()
        // reset 后第一拍归零不再持有（lastShown 已清）——非捕捉态复位语义
        assertEquals("reset 后归零拍不持有旧读数", 0, st.onNextBeat(0))
        assertEquals("reset 后再升起立即显示", 8, st.onNextBeat(8))
    }
}
