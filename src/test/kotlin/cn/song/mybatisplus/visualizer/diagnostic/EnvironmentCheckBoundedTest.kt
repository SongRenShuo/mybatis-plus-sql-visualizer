package cn.song.mybatisplus.visualizer.diagnostic

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutorService

/**
 * UI-005：环境体检**有界采集**测试（非沙盒，testFast 桶）。
 *
 * 立项动因（真机缺陷）：体检取数没有任何时间上界，任一项卡住 → 整单不出 → 设置页按钮永久灰、
 * 文案永久停在「体检中…」。本组用例钉住四条不变式：
 *  - 单项超时只降级该项，其余项照常，整单仍产出，且**总耗时被预算封住**（不随卡住项的时长增长）；
 *  - 整单预算耗尽时，后续项标「整体超时」而不是继续排队；
 *  - 采集抛异常/Error → 该项标「失败：类名 + 消息首行」，其余项不受影响；
 *  - 渲染里能看到总耗时与逐项耗时（用户贴工单时「哪一项慢」一眼可见）。
 *
 * 全部走注入的小预算 + 注入线程池，不依赖真平台、不真等 3s/9s。
 */
class EnvironmentCheckBoundedTest {

    private fun okItem(title: String) = EnvironmentCheckItem(
        title = title,
        level = CheckLevel.OK,
        facts = listOf("$title ok"),
    )

    private fun task(titleKey: String, body: () -> EnvironmentCheckItem) =
        EnvironmentCheckTask(titleKey, body)

    /** 注入自建池并保证收尾（被放弃的任务可能在跑，shutdownNow 只发中断）。 */
    private fun <T> withPool(threads: Int, block: (ExecutorService) -> T): T {
        val pool = EnvironmentCheckBounded.newPool(threads)
        try {
            return block(pool)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun runBounded(
        tasks: List<EnvironmentCheckTask>,
        itemTimeoutMs: Long,
        totalTimeoutMs: Long,
    ): List<EnvironmentCheckItem> = withPool(tasks.size) { pool ->
        EnvironmentCheckBounded.run(
            tasks = tasks,
            itemTimeoutMs = itemTimeoutMs,
            totalTimeoutMs = totalTimeoutMs,
            executorFactory = { pool },
        )
    }

    @Test
    fun test单项超时只降级该项且其余项照常采集() {
        val tasks = listOf(
            task(EnvironmentCheckRules.TITLE_KEY_DATA_SOURCE) {
                Thread.sleep(10_000)
                okItem("数据源")
            },
            task(EnvironmentCheckRules.TITLE_KEY_DIALECT) { okItem("方言") },
        )
        val startedMs = System.currentTimeMillis()
        val items = runBounded(tasks, itemTimeoutMs = 200, totalTimeoutMs = 8_000)
        val wallMs = System.currentTimeMillis() - startedMs

        assertEquals("项数与顺序必须保持", 2, items.size)
        assertEquals("卡住项必须降级为超时", CheckOutcome.ITEM_TIMEOUT, items[0].outcome)
        assertEquals("超时项如实显示不可用", CheckLevel.UNAVAILABLE, items[0].level)
        assertTrue(
            "超时文案要写明「未采集 + 单项超时」：${items[0].facts}",
            items[0].facts.single().contains("未采集") && items[0].facts.single().contains("单项超时"),
        )
        assertNotNull("降级项也要给下一步", items[0].guidance)
        assertEquals(
            "降级不得改变项名（与正常项同源）",
            MpSqlVisualizerBundle.message(EnvironmentCheckRules.TITLE_KEY_DATA_SOURCE),
            items[0].title,
        )
        assertNotNull("超时项也要有耗时读数", items[0].elapsedMs)

        assertEquals("后续项不得被级联拖死", CheckOutcome.COLLECTED, items[1].outcome)
        assertEquals(CheckLevel.OK, items[1].level)
        assertNotNull(items[1].elapsedMs)

        assertTrue(
            "总耗时必须被预算封住（卡住项 sleep 10s，实测 ${wallMs}ms）",
            wallMs < 3_000,
        )
    }

    @Test
    fun test整体超时仍产出且后续项标整体超时() {
        val tasks = listOf(
            task(EnvironmentCheckRules.TITLE_KEY_DATA_SOURCE) { okItem("数据源") },
            task(EnvironmentCheckRules.TITLE_KEY_DIALECT) {
                Thread.sleep(10_000)
                okItem("方言")
            },
            task(EnvironmentCheckRules.TITLE_KEY_AGENT) { okItem("增强捕获") },
        )
        val startedMs = System.currentTimeMillis()
        val items = runBounded(tasks, itemTimeoutMs = 10_000, totalTimeoutMs = 300)
        val wallMs = System.currentTimeMillis() - startedMs

        assertEquals(3, items.size)
        assertEquals("首项应在预算内采到", CheckOutcome.COLLECTED, items[0].outcome)
        assertEquals("总预算耗尽时该项按整体超时降级", CheckOutcome.TOTAL_TIMEOUT, items[1].outcome)
        assertTrue(
            "文案要能区分「整体超时」：${items[1].facts}",
            items[1].facts.single().contains("整体超时"),
        )
        assertEquals("预算已耗尽，后续项不再提交", CheckOutcome.TOTAL_TIMEOUT, items[2].outcome)
        assertNotNull("降级项仍需可读标题", items[2].title.takeIf { it.isNotBlank() })

        assertTrue("整单耗时应接近总预算（实测 ${wallMs}ms）", wallMs < 3_000)
    }

    @Test
    fun test采集异常标失败原因且其余项正常() {
        val tasks = listOf(
            task(EnvironmentCheckRules.TITLE_KEY_DATA_SOURCE) {
                error("boom-line-1\nsecond line must be trimmed")
            },
            task(EnvironmentCheckRules.TITLE_KEY_COMMUNITY) { okItem("Community") },
        )
        val items = runBounded(tasks, itemTimeoutMs = 3_000, totalTimeoutMs = 8_000)

        assertEquals(CheckOutcome.FAILED, items[0].outcome)
        assertEquals(CheckLevel.UNAVAILABLE, items[0].level)
        val fact = items[0].facts.single()
        assertTrue("必须带异常类名：$fact", fact.contains("IllegalStateException"))
        assertTrue("必须带消息首行：$fact", fact.contains("boom-line-1"))
        assertFalse("只取首行，不把多行 message 全塞进体检单：$fact", fact.contains("second line"))
        assertNotNull("失败项也要给下一步", items[0].guidance)

        assertEquals(CheckOutcome.COLLECTED, items[1].outcome)
        assertEquals(CheckLevel.OK, items[1].level)
    }

    @Test
    fun test采集抛Error也降级不炸整单() {
        val tasks = listOf(
            task(EnvironmentCheckRules.TITLE_KEY_CAPTURE) { throw AssertionError("fatal-error") },
            task(EnvironmentCheckRules.TITLE_KEY_COMMUNITY) { okItem("Community") },
        )
        val items = runBounded(tasks, itemTimeoutMs = 3_000, totalTimeoutMs = 8_000)

        assertEquals("Error 同样降级，不得整单失败", CheckOutcome.FAILED, items[0].outcome)
        assertTrue(
            "Error 也要写清类名：${items[0].facts}",
            items[0].facts.single().contains("AssertionError"),
        )
        assertEquals(CheckOutcome.COLLECTED, items[1].outcome)
    }

    @Test
    fun test报告渲染包含总耗时与逐项耗时() {
        val items = runBounded(
            listOf(
                task(EnvironmentCheckRules.TITLE_KEY_DATA_SOURCE) { okItem("数据源") },
                task(EnvironmentCheckRules.TITLE_KEY_COMMUNITY) { okItem("Community") },
            ),
            itemTimeoutMs = 3_000,
            totalTimeoutMs = 8_000,
        )
        val report = EnvironmentCheckReport(
            generatedAt = "2026-11-04 10:00:00",
            pluginVersion = "2026.11.04",
            ideName = "IntelliJ IDEA 2026.2.1 (IC-262.1)",
            projects = listOf("demo"),
            items = items,
            totalElapsedMs = 1234L,
        )
        val text = report.render()
        assertTrue("报告头部要带总耗时：$text", text.contains("总耗时：1234 ms"))
        assertTrue("逐项要带采集耗时：$text", text.contains("采集耗时："))
        assertTrue("原有标题仍在：$text", text.contains("SQL Visualizer 环境体检单"))
    }

    @Test
    fun test降级项标题与五项规则同源() {
        val pairs = listOf(
            EnvironmentCheckRules.TITLE_KEY_DATA_SOURCE to
                EnvironmentCheckRules.dataSource(true, 1, null, null).title,
            EnvironmentCheckRules.TITLE_KEY_DIALECT to
                EnvironmentCheckRules.dialect("候选方言: MySQL", null, weak = false).title,
            EnvironmentCheckRules.TITLE_KEY_AGENT to
                EnvironmentCheckRules.agent(false, null, null, premainSession = false).title,
            EnvironmentCheckRules.TITLE_KEY_CAPTURE to
                EnvironmentCheckRules.capture(false, 0, 0, 0, 0, 0, 0).title,
            EnvironmentCheckRules.TITLE_KEY_COMMUNITY to EnvironmentCheckRules.community(true).title,
        )
        pairs.forEach { (key, ruleTitle) ->
            val degraded = EnvironmentCheckRules.degraded(
                titleKey = key,
                outcome = CheckOutcome.ITEM_TIMEOUT,
                elapsedMs = 12L,
                budgetMs = 3_000L,
            )
            assertEquals("标题键 $key 与规则产出的标题必须逐字一致", ruleTitle, degraded.title)
        }
    }

    @Test
    fun test空任务表返回空结果且不建线程() {
        assertTrue(EnvironmentCheckBounded.run(emptyList()).isEmpty())
    }

    @Test
    fun test失败原因摘要取类名与首行() {
        assertEquals(
            "IllegalStateException: boom",
            EnvironmentCheckBounded.briefReason(IllegalStateException("boom\ntrace")),
        )
        assertEquals(
            "无消息时只留类名",
            "IllegalStateException",
            EnvironmentCheckBounded.briefReason(IllegalStateException()),
        )
        assertNull("合法采集不该带降级标记", okItem("x").takeIf { it.degraded })
    }
}
