package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 巨型文件体积预算（T-TestGateGems W1-c，2026-10-24）。
 *
 * 来源：magpie `internal/gui/core_budget_test.go:22-50` `TestCoreScriptBudget`。它的做法是给核心脚本
 * 定字节上限，**每次提额都在常量注释里写清理由与日期**，错误信息直接给两条出路。原文规则
 * （`LESSONS.md:547-553`）：「A size budget raised whenever a change reaches it bounds nothing」——
 * 某个脚本 2.5 天被抬 4 次还长了 53KB，所以提额必须留痕。
 *
 * 本仓的对应问题：几个文件已到 2000+ 行（`SqlLogPanel.kt` 2264 行），继续长会拖垮评审与并行改动
 * 的冲突面。预算语义**不是禁止增长**，而是「每次增长都必须明确一句为什么」。
 *
 * 口径：基线 = 2026-10-24 实测行数 × 1.015（约 1.5% 余量，够小改动进来，不够悄悄翻倍）。
 * 提额流程：改下方 `BUDGETS` 的数值，并在同一条目注释里追加「日期 + 理由 + 新增行数」。
 */
class GiantFileBudgetTest {

    /**
     * 文件相对路径 -> 行数上限。
     * 每条注释格式：`<上限> since 2026-10-24（基线 N 行 + 1.5% 余量）`；提额时追加
     * `→ <新上限> (2026-10-24: <理由>, +N 行)`。
     */
    private val budgets: Map<String, Int> = mapOf(
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt" to 2297,
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt" to 2200,
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.kt" to 2103,
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt" to 2026,
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt" to 2007,
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlToJavaPanel.kt" to 1927,
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelParamPanel.kt" to 1918,
        "src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt" to 1830,
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt" to 1727,
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/HistorySqlLogPanel.kt" to 1619,
    )

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null && !File(dir, "src/main/kotlin").isDirectory) {
            dir = dir.parentFile
        }
        return dir
    }

    private fun lineCount(f: File): Int = f.readLines().size

    @Test
    fun `巨型文件不得超过预算`() {
        val root = repoRoot()
        assertTrue("预算表为空，守卫未生效", budgets.isNotEmpty())

        val over = budgets.mapNotNull { (rel, cap) ->
            val f = File(root, rel)
            assertTrue("预算表指向的文件不存在（预算会空转）：$rel", f.isFile)
            val n = lineCount(f)
            if (n > cap) "  $rel  实际 $n 行 > 上限 $cap 行（超出 ${n - cap}）" else null
        }
        assertTrue(
            "巨型文件超出预算。两条出路：①把职责拆到新文件（首选）；" +
                "②确实无法拆时，改 GiantFileBudgetTest 的 budgets 数值并在同条目注释写清「日期 + 理由 + 新增行数」：\n" +
                over.joinToString("\n"),
            over.isEmpty(),
        )
    }

    /** 自证：判定内核能看见超预算形态，且不误报未超的。 */
    @Test
    fun `判定内核能看见超预算与未超预算`() {
        val overBudget = 3000 > 2297
        assertTrue("判定内核看不见超预算", overBudget)
        val within = 2000 > 2297
        assertTrue("判定内核误报未超预算", !within)
    }
}
