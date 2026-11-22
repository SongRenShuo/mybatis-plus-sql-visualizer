package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

/**
 * T-PreviewInfoLayout：结果区信息分区回归（问题二「重复」+ 问题三「性能建议被裁」）。
 *
 * ## 钉住的不变式
 *
 * 1. **警告只在横幅**：`meta` 可见行不再拼 `⚠ …`（原先与黄色横幅重复，且被窄宽截成半句）；
 *    警告本身不能丢——`currentWarning` 与 `RENDER_WARNING` 横幅仍在，tooltip 仍能看全文。
 * 2. **性能建议独占整行**：`perfLabel` 是 `topRow` 下方的独立行，宽度不再减去工具栏，
 *    所以同样的建议不会出现「…集，建议添加 LIMIT」这种残句；无建议时整行隐藏不占高度。
 *
 * 反向验证：把 `metaCoreText` 改回 `meta + warningLine`、或把 perf 塞回 `metaLabel` 的 `<br>` 第二行，
 * 本类前两条用例必红。
 *
 * 桶位：引 `SqlPreviewPanel` 与 `BasePlatformTestCase`，属**平台桶**（不进 testFast 快桶）。
 */
class SqlPreviewPanelInfoLayoutTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    private fun result(
        warning: String? = null,
        perfWarnings: List<PerfWarning>? = null,
    ) = PreviewResult(
        sql = "SELECT id\nFROM user\nWHERE username = ?",
        compactSql = "SELECT id FROM user WHERE username = ?",
        unresolvedParams = listOf("username"),
        paramTable = null,
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "user",
        warning = warning,
        columns = listOf("id"),
        perfWarnings = perfWarnings,
    )

    private fun newPanel(): SqlPreviewPanel = SqlPreviewPanel(project)

    fun test警告只在横幅_meta可见行不再重复() {
        val panel = newPanel()
        try {
            panel.showResult(result(warning = "Include refid 'Cols' not found in this mapper"))
            waitForEdt()

            val meta = panel.metaLabelForTest()
            assertTrue("meta 行不应再含 ⚠ 前缀：$meta", !meta.contains("⚠"))
            assertTrue("meta 行不应再含警告正文：$meta", !meta.contains("Include refid"))
            // 警告本身不能丢：横幅承载 + tooltip 仍可看全文
            assertEquals(
                "警告应由 RENDER_WARNING 横幅承载",
                DegradationBanner.Kind.RENDER_WARNING,
                panel.degradationBannerForTest().kindForTest(),
            )
            assertTrue(
                "完整原因仍须在 tooltip 里：${panel.metaTooltipForTest()}",
                panel.metaTooltipForTest().orEmpty().contains("Include refid"),
            )
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    fun test性能建议独立整行_不参与meta宽度预算() {
        val panel = newPanel()
        try {
            panel.showResult(
                result(
                    perfWarnings = listOf(
                        PerfWarning("order_by_without_limit", PerfSeverity.WARN, "perf.order_by_without_limit"),
                    ),
                ),
            )
            waitForEdt()

            assertTrue("有性能建议时整行应可见", panel.perfLabelVisibleForTest())
            val perf = panel.perfLabelForTest()
            assertTrue("整行应承载建议文案：$perf", perf.contains("⚠"))
            assertTrue(
                "headless 未布局时应展示全文（不省略）：$perf",
                !perf.contains("\u2026"),
            )
            // meta 行只留「是什么」，不再出现建议
            assertTrue("meta 行不应再含建议文案：${panel.metaLabelForTest()}", !panel.metaLabelForTest().contains("⚠"))
            // 结构：perf 行与 topRow 同属一个块（topRow 下方），不是 meta 的第二行
            assertNotNull("perfRow 应已挂进布局", panel.perfRowForTest().parent)
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    fun test无性能建议时整行隐藏() {
        val panel = newPanel()
        try {
            panel.showResult(result())
            waitForEdt()
            assertFalse("无建议时整行应隐藏（不占高度）", panel.perfLabelVisibleForTest())
            assertEquals("隐藏时文本应为空", "", panel.perfLabelForTest())
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    fun test清空后performance行复位() {
        val panel = newPanel()
        try {
            panel.showResult(
                result(
                    perfWarnings = listOf(
                        PerfWarning("order_by_without_limit", PerfSeverity.WARN, "perf.order_by_without_limit"),
                    ),
                ),
            )
            waitForEdt()
            assertTrue(panel.perfLabelVisibleForTest())
            panel.clear()
            assertFalse("清空后整行应收起", panel.perfLabelVisibleForTest())
            assertEquals("清空后文本应为空", "", panel.perfLabelForTest())
        } finally {
            panel.dispose()
        }
    }
}
