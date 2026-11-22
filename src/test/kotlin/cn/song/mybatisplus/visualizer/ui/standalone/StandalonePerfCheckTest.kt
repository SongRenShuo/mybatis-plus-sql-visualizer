package cn.song.mybatisplus.visualizer.ui.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/**
 * 独立工作台「粘贴即体检」测试（D234/T-StandaloneExecPhase2）。
 *
 * 覆盖计划册 §五 B1/B2：
 *  - 正例：SELECT * / 缺 WHERE 形态 → resolver 产物补填 perfWarnings（内嵌面板既有性能
 *    建议行直接消费，**本类只证「数据到了载荷上」**，渲染面属既有 F2/T-SqlPerf 行为）；
 *  - 负例：`SELECT 1` 零建议（占位形态/无结构片段不误报）；
 *  - 回退：Registry 开关关=不填（旧行为，载荷不带 perfWarnings）。
 *
 * 反向验证口径：摘掉 [StandaloneSqlSourceResolver] 两处 `withPerfCheck(...)` 调用（XML 路径
 * 与纯 SQL 路径），本类前两条正例必须变红（读数记计划册 §六）。
 *
 * 纯 JUnit 无平台依赖（resolver 本身 project=null 零 PSI 读）；总开关走测试缝 provider，
 * 不依赖真实 Registry。
 */
class StandalonePerfCheckTest {

    @After
    fun restoreSwitch() {
        StandaloneSqlSourceResolver.perfCheckEnabledProvider =
            StandaloneSqlSourceResolver.defaultPerfCheckEnabledProvider
    }

    // ---------- 正例 ----------

    @Test
    fun `纯SQL路径_SELECT星号补填体检`() {
        val resolved = StandaloneSqlSourceResolver.resolve("SELECT * FROM t_user WHERE id = ?")
        assertNotNull(resolved)
        assertEquals(StandaloneSqlSourceResolver.Kind.PLAIN_SQL, resolved!!.kind)
        val warnings = resolved.result.perfWarnings
        assertNotNull("纯 SQL 产物必须补填 perfWarnings（null=体检没跑）", warnings)
        assertTrue(
            "SELECT * 应至少命中一条建议，实际：${warnings?.map { it.ruleId }}",
            warnings!!.any { it.ruleId == "select_star" },
        )
    }

    @Test
    fun `XML路径_SELECT星号补填体检`() {
        val text = """
            <mapper namespace="n">
              <select id="q1" resultType="map">SELECT * FROM t WHERE a = #{aa}</select>
            </mapper>
        """.trimIndent()
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals(StandaloneSqlSourceResolver.Kind.XML_STATEMENT, resolved!!.kind)
        val warnings = resolved.result.perfWarnings
        assertNotNull("XML 产物必须补填 perfWarnings", warnings)
        assertTrue(
            "XML 的 SELECT * 应命中 select_star，实际：${warnings?.map { it.ruleId }}",
            warnings!!.any { it.ruleId == "select_star" },
        )
    }

    // ---------- 负例（不误报） ----------

    @Test
    fun `无结构片段零建议`() {
        val resolved = StandaloneSqlSourceResolver.resolve("SELECT 1")
        assertNotNull(resolved)
        val warnings = resolved!!.result.perfWarnings
        assertTrue(
            "`SELECT 1` 不应触发任何建议（空绿≠不占行：载荷带空表，渲染面按空处理），实际：${warnings?.map { it.ruleId }}",
            warnings.isNullOrEmpty(),
        )
    }

    @Test
    fun `带WHERE的等值查询不报缺WHERE`() {
        val resolved = StandaloneSqlSourceResolver.resolve("SELECT * FROM t_user WHERE id = ?")
        assertNotNull(resolved)
        val ruleIds = resolved!!.result.perfWarnings?.map { it.ruleId }.orEmpty()
        assertTrue("等值 WHERE 不应命中 missing_where，实际：$ruleIds", "missing_where" !in ruleIds)
    }

    // ---------- 回退通道 ----------

    @Test
    fun `开关关_不填perfWarnings`() {
        StandaloneSqlSourceResolver.perfCheckEnabledProvider = { false }
        val resolved = StandaloneSqlSourceResolver.resolve("SELECT * FROM t_user WHERE id = ?")
        assertNotNull(resolved)
        assertNull("回退开关关=旧行为（不填 perfWarnings）", resolved!!.result.perfWarnings)
    }
}
