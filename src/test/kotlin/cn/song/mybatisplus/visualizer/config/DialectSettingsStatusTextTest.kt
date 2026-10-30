package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialectSettingsStatusTextTest {
    private fun decision(
        status: DialectDecisionEngine.Status,
        dialect: String? = null,
        source: DialectDecisionEngine.Source? = null,
    ) = DialectDecisionEngine.Decision(status, dialect, DialectDecisionEngine.Confidence.FALLBACK, source)

    @Test
    fun `no project and multiple projects do not claim any single project's dialect`() {
        val oracle = decision(DialectDecisionEngine.Status.RESOLVED, "Oracle", DialectDecisionEngine.Source.PROJECT_CONSENSUS)
        assertFalse(DialectSettingsStatusText.describe(0, oracle).contains("Oracle"))
        assertFalse(DialectSettingsStatusText.describe(2, oracle).contains("Oracle"))
        assertFalse(DialectSettingsStatusText.describe(2, oracle).contains("MySQL"))
    }

    @Test
    fun `a sole project displays its actual source or manual override`() {
        val resolved = decision(DialectDecisionEngine.Status.RESOLVED, "Oracle", DialectDecisionEngine.Source.PROJECT_CONSENSUS)
        val agent = decision(DialectDecisionEngine.Status.RESOLVED, "PostgreSQL", DialectDecisionEngine.Source.PROJECT_AGENT)
        assertTrue(DialectSettingsStatusText.describe(1, resolved).contains("Oracle"))
        assertTrue(DialectSettingsStatusText.describe(1, agent).contains("PostgreSQL"))
        assertTrue(DialectSettingsStatusText.describe(1, decision(DialectDecisionEngine.Status.MANUAL, "H2")).contains("H2"))
    }

    @Test
    fun `ambiguity conflict and no evidence never claim an inferred dialect`() {
        // T-DialectInferFix：未判定时的口径从「预览兼容按 MySQL」改为「预览沿用 IDEA 的 SQL 方言设置」——
        // 插件不再把 MySQL 当成事实，状态行也不得再暗示推断出了 MySQL。
        for (status in listOf(DialectDecisionEngine.Status.AMBIGUOUS,
            DialectDecisionEngine.Status.CONFLICT, DialectDecisionEngine.Status.UNAVAILABLE)) {
            val text = DialectSettingsStatusText.describe(1, decision(status))
            assertFalse("$status must not claim MySQL as inferred", text.contains("MySQL"))
            assertFalse("$status must not claim Oracle", text.contains("Oracle"))
        }
        for (status in listOf(DialectDecisionEngine.Status.AMBIGUOUS, DialectDecisionEngine.Status.UNAVAILABLE)) {
            val text = DialectSettingsStatusText.describe(1, decision(status))
            assertTrue(
                "$status must disclose the inheritance fallback: $text",
                text.contains("IDE SQL dialect", ignoreCase = true) || text.contains("IDEA 的 SQL 方言设置"),
            )
        }
    }
}
