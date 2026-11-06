package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.text.SimpleDateFormat

/**
 * T-AgentLogTime（L3 嗅探与显示解耦）守卫：日志时间是**数据**——「时间显示开关」与
 * 「时间源」只决定渲染，不得决定采不采。
 *
 * 旧实现把 `ParserConfig.sniffLogTime` 接到 `logTimestampEnabled && source == LOG` 两个显示偏好上，
 * 用户选「捕获时间」时相关器不填、之后切回「日志时间」历史条目一片空白。本用例需要真实
 * [PluginSettings]（故用平台用例，与纯逻辑的 `SqlCaptureGateSniffTest` 分家）：
 * 关掉显示 / 切成捕获时间后，接管态嗅探仍须把日志时间写进 [LogTimeCorrelator]。
 */
class AgentSniffDecoupleTest : BasePlatformTestCase() {

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

    private var savedEnabled: Boolean = true
    private var savedSource: String = ""

    override fun setUp() {
        super.setUp()
        // 跨用例隔离（与 SqlCaptureGateSniffTest / SqlErrorLineBridgeTest 同款护栏）：本类假定
        // 接管态下 processConsoleLine 必走嗅探路径，而 CaptureActivityFallback 是全局状态——
        // 其它用例遗留的让位起点/解封态会让它直接判定「已解封」并跳过嗅探（采集数 0）。
        // 全量定向（log.*）里这类污染随分叉/顺序变化才会显形：单类跑必绿、全量跑必红。
        CaptureActivityFallback.resetForTest()
        CaptureActivityFallback.enabledOverride = false
        val state = PluginSettings.getInstance().state
        savedEnabled = state.logTimestampEnabled
        savedSource = state.logTimestampSource
    }

    override fun tearDown() {
        try {
            val state = PluginSettings.getInstance().state
            state.logTimestampEnabled = savedEnabled
            state.logTimestampSource = savedSource
            SqlCaptureGate.paused.set(false)
            LogTimeCorrelator.clear()
            CaptureActivityFallback.resetForTest()
        } finally {
            super.tearDown()
        }
    }

    /** 接管态喂一对日志行（时间为当前时刻往前 500ms，与墙钟同源）。 */
    private fun feedPreparedPair() {
        val cfg = ParserConfig("Preparing:", "Parameters:", true, 20000)
        val ctx = BridgeLineContext(
            myBatisParser = MyBatisLogParser(
                sqlPrefix = cfg.sqlPrefix,
                paramPrefix = cfg.paramPrefix,
                mixedPrefix = cfg.mixedPrefix,
                maxParseLength = cfg.maxParseLength,
            ),
            hibernateParser = HibernateLogParser(
                sqlPrefix = cfg.hibernateSqlPrefix,
                paramPrefix = cfg.hibernateParamPrefix,
                mixedPrefix = cfg.mixedPrefix,
                maxParseLength = cfg.maxParseLength,
            ),
            jdbcParser = JdbcTemplateLogParser(enabled = cfg.jdbcEnabled, maxParseLength = cfg.maxParseLength),
            configProvider = { cfg },
            hibernateEnabled = cfg.hibernateEnabled,
        )
        val ts = sdf.format(java.util.Date(System.currentTimeMillis() - 500))
        SqlCaptureGate.paused.set(true)
        listOf(
            "$ts [main] ==>  Preparing: SELECT * FROM user WHERE id = ?",
            "$ts [main] ==>  Parameters: 1(Long)",
        ).forEach { processConsoleLine(it, ctx, null, {}) }
    }

    fun test时间显示关闭时嗅探仍采集() {
        PluginSettings.getInstance().state.logTimestampEnabled = false
        feedPreparedPair()
        assertEquals("时间显示关不影响采集（数据 vs 渲染）", 1, LogTimeCorrelator.size())
    }

    fun test时间源为捕获时间时嗅探仍采集() {
        PluginSettings.getInstance().state.logTimestampSource = PluginSettings.LOG_TIMESTAMP_SOURCE_CAPTURE
        feedPreparedPair()
        assertEquals("时间源=捕获时仍采集日志时间（切回日志源即可显示）", 1, LogTimeCorrelator.size())
    }
}
