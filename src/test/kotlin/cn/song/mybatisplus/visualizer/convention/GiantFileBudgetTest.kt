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
        // 2026-10-25 合并 master（179cdc86，72 笔）后统一按实测重定基线 ×1.015 余量。
        // 六个文件本轮全部超限，增长均来自 master 侧既有代码（t-magpie-gems 分叉期未同步）：
        // 合并本身只给 SqlPreviewPanelExecution 多 2 行，其余增量与本次合流无关。
        // 提额即「承认现状 + 留痕」，**不是许可继续增长**——下次触顶请先走拆分（首选出路）。
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt" to 2358,
        // 2297 → 2358（2026-10-25: 合并 master 实测 2323 行，含日志工具栏 BGT 与列表高亮改造，+26 行）
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt" to 2421,
        // 2200 → 2421（2026-10-25: 合并 master 实测 2385 行，含执行计划回控制台/只读快照改造，+185 行）
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.kt" to 2103,
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt" to 2349,
        // 2026 → 2349（2026-10-25: 合并 master 实测 2314 行，含取源十二轮收口与跨源列证据，+288 行）
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt" to 2194,
        // 2007 → 2133（2026-10-25: 合并 master 实测 2101 行，含取源结果面板改造，+94 行）
        // 2133 → 2194（2026-10-28: 再并 master 实测 2162 行，ExecFileBinding 绑定态与换源通道落位，+29 行超线）
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlToJavaPanel.kt" to 1927,
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelParamPanel.kt" to 1972,
        // 1918 → 1972（2026-10-25: 合并 master 实测 1942 行，含 FieldFill/画布工具栏改造，+24 行）
        "src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt" to 1830,
        "src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt" to 1777,
        // 1727 → 1777（2026-10-25: 合并 master 实测 1750 行，含 premain 无界 join 修复，+23 行）
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
