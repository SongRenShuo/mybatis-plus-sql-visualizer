package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI 文案本地化守卫（T-TestGateGems W1-a，2026-10-24）。
 *
 * 来源：magpie `internal/gui/intl_lang_test.go:17-38` `TestDatesFollowGUILanguage`——用正则扫全部
 * 调用点，而不是只靠一个贵的端到端用例证行为；注释原文「this test guards every other call,
 * because CI doesn't run the browser tests」。
 *
 * 为什么要有这条：本仓历史上出现过「中文 bundle 缺键静默回退英文」（m41 工具栏 i18n），
 * 但那是**键缺失**；本条防的是另一半——**直接把中文字面量写进控件，英文界面下照显中文**。
 * `BundleParityGuardTest` 覆盖不到这种形态（那个键根本不存在，没有 parity 可言）。
 *
 * 判定口径：剥注释后，在 `JLabel(` / `JBLabel(` / `JButton(` / `JCheckBox(` / `JRadioButton(` /
 * `.text = "` / `.setText("` / `toolTipText = "` 等构造位，紧跟的字面量里含 CJK 即命中。
 *
 * 存量已清零（实施时全量扫描 516 个 .kt 仅 6 处，已全部改走 bundle），故本守卫**无白名单**——
 * 白名单是给「暂时改不动」留的口子，这里不需要；将来若真需要，必须在本文件常量旁写豁免理由。
 */
class HardcodedUiTextGuardTest : BannedApiScanSupport() {

    /** UI 构造位：捕获紧跟的第一个字符串字面量。 */
    private val uiTextPatterns = listOf(
        Regex("""JLabel\s*\(\s*"([^"]*)""""),
        Regex("""JBLabel\s*\(\s*"([^"]*)""""),
        Regex("""JButton\s*\(\s*"([^"]*)""""),
        Regex("""JCheckBox\s*\(\s*"([^"]*)""""),
        Regex("""JRadioButton\s*\(\s*"([^"]*)""""),
        Regex("""\.text\s*=\s*"([^"]*)""""),
        Regex("""\.setText\s*\(\s*"([^"]*)""""),
        Regex("""toolTipText\s*=\s*"([^"]*)""""),
    )

    private val cjk = Regex("[\\u4e00-\\u9fff]")

    /** 逐文件剥注释后找命中，返回 `文件:行 片段`。 */
    private fun scanHardcoded(sources: List<Pair<String, String>>): List<String> {
        val hits = mutableListOf<String>()
        for ((path, raw) in sources) {
            val code = stripComments(raw)
            for (pattern in uiTextPatterns) {
                for (m in pattern.findAll(code)) {
                    if (cjk.containsMatchIn(m.groupValues[1])) {
                        hits += "$path:${lineOf(code, m.range.first)}  ${m.value.take(60)}"
                    }
                }
            }
        }
        return hits
    }

    @Test
    fun `UI 控件文案不得硬编码中文，必须走 bundle`() {
        val sources = mainSources()
        // 防空跑：扫描面为空时本守卫毫无判别力，必须显式失败（magpie 同款 `n == 0` 防线）。
        assertTrue("主源码扫描面为空，守卫未生效", sources.size > 100)

        val hits = scanHardcoded(sources)
        assertTrue(
            "UI 文案硬编码中文（英文界面下会照显中文），改走 MpSqlVisualizerBundle.message：\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /**
     * 自证：判定内核能看见该形态（T-GuardSelfTest 惯例）。
     * 若这条失败，说明上面的正则写坏了——而不是生产代码有问题。
     */
    @Test
    fun `判定内核能看见硬编码中文形态`() {
        val synthetic = listOf(
            "probe/A.kt" to """
                fun build() = JLabel("测试中文")
                fun build2() = JButton("确定")
                fun build3() = JBTextField().apply { toolTipText = "最近搜索" }
            """.trimIndent(),
        )
        val hits = scanHardcoded(synthetic)
        assertTrue("判定内核看不见 JLabel 硬编码", hits.any { it.contains("JLabel") })
        assertTrue("判定内核看不见 JButton 硬编码", hits.any { it.contains("JButton") })
        assertTrue("判定内核看不见 toolTipText 硬编码", hits.any { it.contains("toolTipText") })
    }

    /** 自证反向：纯英文文案、bundle 调用、注释里的中文都不得命中（防误报）。 */
    @Test
    fun `英文文案与注释中文不得命中`() {
        val synthetic = listOf(
            "probe/B.kt" to """
                // JLabel("注释里的中文不该命中")
                fun build() = JLabel("OK")
                fun build2() = JLabel(MpSqlVisualizerBundle.message("settings.landing.capture"))
                fun build3() = JBTextField().apply { toolTipText = MpSqlVisualizerBundle.message("search.history.tooltip") }
            """.trimIndent(),
        )
        val hits = scanHardcoded(synthetic)
        assertTrue("误报了不该命中的形态：\n${hits.joinToString("\n")}", hits.isEmpty())
    }
}
