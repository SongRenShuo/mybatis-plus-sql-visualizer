package cn.song.mybatisplus.visualizer.diagnostic

import cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEW-18：环境体检单纯函数测试（非沙盒，testFast 桶）。
 *
 * 守卫目标（逐条对应立项验收项）：
 *  - 五项事实一项不能少，且每项非绿灯时必须带修复指引（只有红灯没有下一步等于没说）；
 *  - 读数缺失一律显式「不可用」，不得把缺失编成 0 或绿灯（P-7：attach 计数在旧版 agent
 *    未上报时必须显示不可用）；
 *  - premain 零命中不误判成故障（premain 早于驱动加载，时序原因）；
 *  - 总评取最严重项。
 */
class EnvironmentCheckReportTest {

    private fun sampleReport(vararg items: EnvironmentCheckItem) = EnvironmentCheckReport(
        generatedAt = "2026-10-31 12:00:00",
        pluginVersion = "2026.10.31",
        ideName = "IntelliJ IDEA 2026.2.1 (IC-262.1)",
        projects = listOf("demo"),
        items = items.toList(),
    )

    /** 五项齐全的最小报告（全绿样本；不触平台，纯规则组装）。 */
    private fun fullReport(): EnvironmentCheckReport = sampleReport(
        EnvironmentCheckRules.dataSource(true, 1, "demo", null),
        EnvironmentCheckRules.dialect("候选方言: MySQL (运行时)", "候选数据源: demo", weak = false),
        EnvironmentCheckRules.agent(
            enabled = true,
            statusText = "已挂载 pid=1",
            stats = AgentAttachStats(matched = 2, weavable = 2, woven = 2),
            premainSession = false,
        ),
        EnvironmentCheckRules.capture(true, 1, 1, 10, 1, 0, 0),
        EnvironmentCheckRules.community(true),
    )

    @Test
    fun test五项事实齐全且渲染含标题与总评() {
        val report = fullReport()
        assertEquals("体检单必须固定五项", 5, report.items.size)
        val text = report.render()
        assertTrue("应含标题：$text", text.contains("SQL Visualizer 环境体检单"))
        assertTrue("应含数据源项", text.contains("数据源识别"))
        assertTrue("应含方言项", text.contains("方言判定"))
        assertTrue("应含增强捕获项", text.contains("增强捕获"))
        assertTrue("应含捕获覆盖项", text.contains("捕获覆盖"))
        assertTrue("应含 Community 项", text.contains("Community 可用性"))
        assertTrue("应含总评", text.contains("总评："))
        assertTrue("应声明只读", text.contains("未执行任何 SQL"))
    }

    @Test
    fun test数据源无Database时不可用且不冒充零个正常() {
        val item = EnvironmentCheckRules.dataSource(
            databaseAvailable = false,
            count = 0,
            defaultName = null,
            lastUsedName = null,
        )
        assertEquals(CheckLevel.UNAVAILABLE, item.level)
        assertNotNull("不可用必须给修复指引", item.guidance)
    }

    @Test
    fun test数据源零个为需注意且给指引() {
        val item = EnvironmentCheckRules.dataSource(true, 0, null, null)
        assertEquals(CheckLevel.WARN, item.level)
        assertNotNull(item.guidance)
    }

    @Test
    fun test数据源正常时展示默认与上次使用() {
        val item = EnvironmentCheckRules.dataSource(true, 3, "mp_demo@localhost", "mp_demo@localhost")
        assertEquals(CheckLevel.OK, item.level)
        assertNull("绿灯不添噪", item.guidance)
        assertTrue(item.facts.any { it.contains("3") })
        assertTrue(item.facts.any { it.contains("mp_demo@localhost") })
    }

    @Test
    fun test方言未判定与弱证据均给指引() {
        val unresolved = EnvironmentCheckRules.dialect(null, null, weak = true)
        assertEquals(CheckLevel.WARN, unresolved.level)
        assertNotNull(unresolved.guidance)

        val ambiguous = EnvironmentCheckRules.dialect("候选方言: MySQL (歧义)", "候选数据源: a, b", weak = true)
        assertEquals(CheckLevel.WARN, ambiguous.level)
        assertNotNull(ambiguous.guidance)

        val resolved = EnvironmentCheckRules.dialect("候选方言: MySQL (运行时)", "候选数据源: a", weak = false)
        assertEquals(CheckLevel.OK, resolved.level)
        assertNull(resolved.guidance)
    }

    @Test
    fun test增强捕获关闭时不可用() {
        val item = EnvironmentCheckRules.agent(enabled = false, statusText = null, stats = null, premainSession = false)
        assertEquals(CheckLevel.UNAVAILABLE, item.level)
        assertNotNull(item.guidance)
    }

    @Test
    fun test增强捕获计数缺失显示不可用而不是零() {
        val item = EnvironmentCheckRules.agent(
            enabled = true,
            statusText = "已挂载 pid=123",
            stats = null,
            premainSession = false,
        )
        assertEquals(CheckLevel.WARN, item.level)
        assertTrue(
            "必须显式说计数不可用：${item.facts}",
            item.facts.any { it.contains("不可用") },
        )
        assertNotNull(item.guidance)
    }

    @Test
    fun test增强捕获零命中非premain判失败() {
        val item = EnvironmentCheckRules.agent(
            enabled = true,
            statusText = "已挂载 pid=123",
            stats = AgentAttachStats(matched = 0, weavable = 0, woven = 0),
            premainSession = false,
        )
        assertEquals(CheckLevel.FAIL, item.level)
        assertNotNull(item.guidance)
    }

    @Test
    fun test增强捕获premain零命中只判需注意并说明时序() {
        val item = EnvironmentCheckRules.agent(
            enabled = true,
            statusText = "已挂载 pid=123",
            stats = AgentAttachStats(matched = 0, weavable = 0, woven = 0),
            premainSession = true,
        )
        assertEquals(CheckLevel.WARN, item.level)
        assertTrue("应说明 premain 时序：${item.facts}", item.facts.any { it.contains("premain") })
    }

    @Test
    fun test增强捕获正常命中织入为绿灯() {
        val item = EnvironmentCheckRules.agent(
            enabled = true,
            statusText = "已挂载 pid=123",
            stats = AgentAttachStats(matched = 5, weavable = 5, woven = 5),
            premainSession = false,
        )
        assertEquals(CheckLevel.OK, item.level)
        assertNull(item.guidance)
    }

    @Test
    fun test增强捕获兜底熔断与失败计数需注意() {
        val killed = EnvironmentCheckRules.agent(
            enabled = true,
            statusText = "已挂载 pid=123",
            stats = AgentAttachStats(matched = 3, weavable = 3, woven = 3, catchAll = 2),
            premainSession = false,
        )
        assertEquals(CheckLevel.WARN, killed.level)
        assertTrue(killed.facts.any { it.contains("熔断") })

        val failed = EnvironmentCheckRules.agent(
            enabled = true,
            statusText = "已挂载 pid=123",
            stats = AgentAttachStats(matched = 3, weavable = 3, woven = 2, failed = 1),
            premainSession = false,
        )
        assertEquals(CheckLevel.WARN, failed.level)
    }

    @Test
    fun test捕获未开启与零产出均需注意() {
        val off = EnvironmentCheckRules.capture(false, 0, 0, 0, 0, 0, 0)
        assertEquals(CheckLevel.WARN, off.level)
        assertNotNull(off.guidance)

        val noSql = EnvironmentCheckRules.capture(true, 0, 1, 120, 0, 0, 0)
        assertEquals(CheckLevel.WARN, noSql.level)
        assertTrue("有行没解析应指向日志级别：${noSql.guidance}", noSql.guidance!!.contains("debug"))
    }

    @Test
    fun test捕获有产出即绿灯并展示丢弃计数() {
        val item = EnvironmentCheckRules.capture(true, 7, 2, 1500, 8, 3, 1)
        assertEquals(CheckLevel.OK, item.level)
        assertNull(item.guidance)
        assertTrue(item.facts.any { it.contains("7") })
        assertTrue("非零丢弃必须可见：${item.facts}", item.facts.any { it.contains("重放守卫丢弃") })
    }

    @Test
    fun testCommunity不可用给升级指引() {
        val unavailable = EnvironmentCheckRules.community(false)
        assertEquals(CheckLevel.UNAVAILABLE, unavailable.level)
        assertNotNull(unavailable.guidance)

        val available = EnvironmentCheckRules.community(true)
        assertEquals(CheckLevel.OK, available.level)
        assertNull(available.guidance)
    }

    @Test
    fun test总评取最严重项() {
        val warnOnly = sampleReport(
            EnvironmentCheckRules.community(false),
            EnvironmentCheckRules.dataSource(true, 2, null, null),
        )
        assertEquals("不可用不应压过需注意", CheckLevel.UNAVAILABLE, warnOnly.overallLevel)

        val withFail = sampleReport(
            EnvironmentCheckRules.community(false),
            EnvironmentCheckRules.agent(
                enabled = true,
                statusText = "已挂载",
                stats = AgentAttachStats(),
                premainSession = false,
            ),
        )
        assertEquals(CheckLevel.FAIL, withFail.overallLevel)
    }

    @Test
    fun test渲染逐项标注等级() {
        val report = sampleReport(EnvironmentCheckRules.community(false))
        val text = report.render()
        assertTrue("应标注不可用：$text", text.contains("不可用"))
        assertTrue("应带修复指引前缀：$text", text.contains("修复指引："))
    }
}
