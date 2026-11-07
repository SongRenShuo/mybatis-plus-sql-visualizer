package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import java.text.SimpleDateFormat
import java.util.Date

/**
 * T-PerfSweep3 #3：formatTimestampFragment 的 SimpleDateFormat 复用测试。
 *
 * 该函数是「每可见单元格渲染 + 每条目搜索匹配/批量复制」的双热路径（SqlLogPanel 与
 * HistorySqlLogPanel 两面板共用此单点），复用 formatter 后硬门禁是**格式输出与现状
 * 逐字节一致**——本测试用「与改造前同构的逐次新建参照实现」逐字节比对输出，并覆盖
 * 设置变更跟随（缓存按格式串分键，无陈旧）与非法格式串回落（catch 语义不变）。
 */
class TimestampFormatterReuseTest : BasePlatformTestCase() {

    private var savedEnabled: Boolean = true
    private var savedSource: String = ""
    private var savedFormat: String = ""

    override fun setUp() {
        super.setUp()
        val s = PluginSettings.getInstance().state
        savedEnabled = s.logTimestampEnabled
        savedSource = s.logTimestampSource
        savedFormat = s.logTimestampFormat
    }

    override fun tearDown() {
        val s = PluginSettings.getInstance().state
        s.logTimestampEnabled = savedEnabled
        s.logTimestampSource = savedSource
        s.logTimestampFormat = savedFormat
        super.tearDown()
    }

    /** 固定时间戳条目（2024-01-15 10:30:45.123，测试 JVM 时区下与显示一致）。 */
    private fun entry() = LogSqlEntry(
        rawSql = "SELECT 1",
        restoredSql = "SELECT 1",
        params = emptyList(),
        timestamp = 1705285845123L,
        logTimestamp = 1705285845123L,
    )

    /** 参照实现：与改造前逐字节同构的「逐次新建 SimpleDateFormat」路径。 */
    private fun legacyFormat(format: String, ts: Long): String = SimpleDateFormat(format).format(Date(ts))

    fun test输出与逐次新建逐字节一致() {
        val e = entry()
        val fragment = formatTimestampFragment(e)
            ?: throw AssertionError("默认开启时间戳，片段不应为 null")
        assertEquals(
            "复用 formatter 后输出须与逐次新建逐字节一致",
            "[${legacyFormat(savedFormat, e.timestamp)}] ",
            fragment.first,
        )
        // 连续第二次调用走缓存命中路径，输出必须稳定
        assertEquals(fragment.first, formatTimestampFragment(e)!!.first)
    }

    fun test设置变更跟随_缓存按格式串分键不陈旧() {
        val e = entry()
        PluginSettings.getInstance().state.logTimestampFormat = "HH:mm:ss"
        assertEquals("[${legacyFormat("HH:mm:ss", e.timestamp)}] ", formatTimestampFragment(e)!!.first)
        PluginSettings.getInstance().state.logTimestampFormat = "yyyy/MM/dd"
        assertEquals(
            "换格式后缓存不陈旧（键=格式串，新格式=新条目）",
            "[${legacyFormat("yyyy/MM/dd", e.timestamp)}] ",
            formatTimestampFragment(e)!!.first,
        )
    }

    fun test非法格式回落默认格式_语义与现状一致() {
        // 未闭合单引号 = SimpleDateFormat 构造即抛 IllegalArgumentException（'?' 等非字母字符
        // 是合法字面量，不会抛——不可用作非法样本）
        PluginSettings.getInstance().state.logTimestampFormat = "HH:mm:ss'"
        val e = entry()
        val fragment = formatTimestampFragment(e)
            ?: throw AssertionError("片段不应为 null")
        assertEquals(
            "非法格式串构造即抛不进缓存，回落 DEFAULT_TIMESTAMP_FORMAT（与改造前同构）",
            "[${legacyFormat(SqlLogPanel.DEFAULT_TIMESTAMP_FORMAT, e.timestamp)}] ",
            fragment.first,
        )
    }
}
