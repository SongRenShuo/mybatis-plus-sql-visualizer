package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.PreviewResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-DialectInferFix 券 03：预览方言决策的唯一入口。
 *
 * 重点钉两件事：①捕获方言（本条 SQL 运行时连接的库）优先于一切推断；
 * ②完全没有外部证据时，SQL 文本的方言特征兜底——而不是直接掉进 MySQL。
 *
 * k2j 移植注（生产为 Java）：`withResolvedDialect` 是 PreviewDialectResolver 的同类静态方法
 * （master 的成员扩展形态改为 `PreviewDialectResolver.withResolvedDialect(result, project)`）；
 * PreviewResult 构造用 34 参规范构造器位置实参（master 具名参不适用于 Java 构造器）；
 * Decision 是 Java record，按本仓既有惯例显式调用 source()/confidence()/status()/dialectName()。
 */
class PreviewDialectResolverTest {

    @Test
    fun `known dialect normalizes case and rejects unknown names`() {
        assertEquals("PostgreSQL", PreviewDialectResolver.knownDialect("postgresql"))
        assertEquals("DM（达梦）", PreviewDialectResolver.knownDialect("DM（达梦）"))
        assertNull(PreviewDialectResolver.knownDialect("HSQL"))
        assertNull(PreviewDialectResolver.knownDialect(" "))
        assertNull(PreviewDialectResolver.knownDialect(null))
    }

    @Test
    fun `dialect from persisted badge takes only the dialect head`() {
        assertEquals(
            "PostgreSQL",
            PreviewDialectResolver.dialectFromBadge("PostgreSQL · cyzc_db1@121.40.45.91:5432"),
        )
        // 旧角标只有连接事实（没有方言）→ 不猜
        assertNull(PreviewDialectResolver.dialectFromBadge("cyzc_db1@121.40.45.91:5432"))
        // 无注册方言的显示名（HSQL/GBASE）→ 不猜
        assertNull(PreviewDialectResolver.dialectFromBadge("HSQL · local:h2"))
    }

    @Test
    fun `captured dialect is exact and wins over sql syntax`() {
        val result = PreviewDialectResolver.withResolvedDialect(
            preview(
                sql = "select `id` from t limit 1, 2",
                decision = PreviewDialectResolver.capturedDecision("PostgreSQL"),
            ),
            null,
        )
        assertEquals("PostgreSQL", result.renderedDialect?.name)
        assertEquals(DialectDecisionEngine.Source.CAPTURED_URL, result.dialectDecision?.source())
        assertEquals(DialectDecisionEngine.Confidence.EXACT, result.dialectDecision?.confidence())
        assertEquals(DialectDecisionEngine.Status.RESOLVED, result.dialectDecision?.status())
    }

    @Test
    fun `already resolved result is returned as is`() {
        val original = preview(
            sql = "select 1",
            rendered = DialectRegistry.INSTANCE.byName("PostgreSQL"),
            decision = PreviewDialectResolver.capturedDecision("PostgreSQL"),
        )
        assertSame(original, PreviewDialectResolver.withResolvedDialect(original, null))
    }

    @Test
    fun `sql syntax hint is the last resort without project or data source`() {
        val mysql = PreviewDialectResolver.withResolvedDialect(preview("select `id` from t limit 10, 20"), null)
        assertEquals("MySQL", mysql.renderedDialect?.name)
        assertEquals(DialectDecisionEngine.Source.SQL_SYNTAX, mysql.dialectDecision?.source())
        assertEquals(DialectDecisionEngine.Confidence.FALLBACK, mysql.dialectDecision?.confidence())

        val pg = PreviewDialectResolver.withResolvedDialect(preview("select id::bigint from t"), null)
        assertEquals("PostgreSQL", pg.renderedDialect?.name)
    }

    @Test
    fun `ambiguous sql syntax leaves dialect unresolved instead of guessing mysql`() {
        val result = PreviewDialectResolver.withResolvedDialect(preview("select `id`::bigint from t"), null)
        assertNull(result.renderedDialect)
        assertEquals(DialectDecisionEngine.Confidence.FALLBACK, result.dialectDecision?.confidence())
        assertTrue(result.dialectDecision?.dialectName() == null)
    }

    private fun preview(
        sql: String,
        rendered: Dialect? = null,
        decision: DialectDecisionEngine.Decision? = null,
    ): PreviewResult = PreviewResult(
        sql, sql, emptyList(), null, null, null, null, emptyList(), emptyMap(), null, null, emptyList(),
        emptyList(), null, null, emptyMap(), emptyList(), emptyList(), null, emptyList(), emptyMap(),
        null, false, null, null, emptyList(), emptySet(), null, null, emptyList(), rendered, decision, "", null,
    )
}
