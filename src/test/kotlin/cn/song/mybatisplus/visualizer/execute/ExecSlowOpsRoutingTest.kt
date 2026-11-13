package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-ExecSlowOps：方言两段式映射判定纯函数回归（[DialectPreMapping.shouldSkip]）。
 *
 * 真机双 SEVERE 的线程编排核心判定——BGT 读探测后「要不要派 EDT 做 setMapping」：
 *  - 进程内同 vf 已设过 → 跳过（T-PreviewFlash 幂等短路语义保留）；
 *  - workspace.xml 持久映射幸存（getConfiguredMapping 已 MySQL）→ 跳过（IDE 重启稳态零 EDT 占用）；
 *  - 探测失败 null 保守不跳过——宁可多设一次同值映射，不冒 GenericSQL PSI 静默失败的险（M36.5.3）。
 */
class ExecSlowOpsRoutingTest {

    @Test
    fun `进程内已映射直接跳过`() {
        assertTrue(DialectPreMapping.shouldSkip(inProcessMapped = true, configuredIsMySql = null))
        assertTrue(DialectPreMapping.shouldSkip(inProcessMapped = true, configuredIsMySql = false))
        assertTrue(DialectPreMapping.shouldSkip(inProcessMapped = true, configuredIsMySql = true))
    }

    @Test
    fun `读探测确认已配置MySQL跳过`() {
        assertTrue(DialectPreMapping.shouldSkip(inProcessMapped = false, configuredIsMySql = true))
    }

    @Test
    fun `未映射且探测为否或未决时派EDT`() {
        assertFalse(DialectPreMapping.shouldSkip(inProcessMapped = false, configuredIsMySql = false))
        assertFalse(DialectPreMapping.shouldSkip(inProcessMapped = false, configuredIsMySql = null))
    }

    // ------------------------------------------------------------------
    // T-PreviewNoReindex：探测不可用时凭「已写过」的备忘跳过重写
    // （每次重写 → SqlDialectPusher push-all → 工程进 dumb；真机 7/7 复现）
    // ------------------------------------------------------------------

    @Test
    fun `探测可用时不看备忘_以平台为准`() {
        // 该函数只负责「探测不可用」这一支：探测可用时一律返回 false，把判定权交回 shouldSkip
        assertFalse(
            "探测可用（哪怕等于目标方言）时本函数不得代为跳过",
            DialectPreMapping.skipByMemo("MySQL", "MySQL", "MySQL", 0),
        )
        // 探测说是别的方言 → 更不能被备忘挡住（用户可能手动改过映射）
        assertFalse(
            "探测可用时不得采信备忘",
            DialectPreMapping.skipByMemo("PostgreSQL", "MySQL", "MySQL", 0),
        )
    }

    @Test
    fun `探测不可用时备忘命中才跳过`() {
        assertTrue(
            "刚才写过 MySQL、且未过期 → 跳过（这正是不再 push-all dumb 的关键）",
            DialectPreMapping.skipByMemo(null, "MySQL", "MySQL", 1_000),
        )
        assertTrue(
            "目标为空按 MySQL 旧语义处理",
            DialectPreMapping.skipByMemo(null, null, "mysql", 1_000),
        )
    }

    @Test
    fun `备忘过期或不同目标时照写`() {
        assertFalse(
            "备忘过期必须重写一次（用户可能改过映射）",
            DialectPreMapping.skipByMemo(null, "MySQL", "MySQL", DialectPreMapping.MAPPING_MEMO_TTL_MS + 1),
        )
        assertFalse(
            "目标方言变了必须重写",
            DialectPreMapping.skipByMemo(null, "PostgreSQL", "MySQL", 1_000),
        )
        assertFalse("没有备忘必须写", DialectPreMapping.skipByMemo(null, "MySQL", null, 1_000))
    }

    /**
     * 接线守卫：备忘必须**真的**接在 ensureMapped 的跳过判定上，且写入成功后落账——
     * 只测纯函数测不到「有没有接上」（摘掉调用点，纯函数用例照样绿，dumb 照旧每次发生）。
     */
    @Test
    fun `备忘接线仍在ensureMapped的跳过判定与写入落账处`() {
        val candidates = listOf(
            java.io.File("src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DialectPreMapping.kt"),
            java.io.File("../src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DialectPreMapping.kt"),
        )
        val source = candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("找不到 DialectPreMapping.kt（工作目录=${java.io.File(".").absolutePath}）")
        val codeOnly = source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")
        assertTrue("跳过判定必须用上备忘", codeOnly.contains("skipByOwnMemo"))
        assertTrue("写入成功后必须落备忘", codeOnly.contains("mappedMemo[key] ="))
    }
}
