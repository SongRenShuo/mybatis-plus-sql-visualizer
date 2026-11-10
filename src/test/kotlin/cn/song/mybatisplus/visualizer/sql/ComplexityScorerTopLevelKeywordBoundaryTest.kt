package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 Q：ComplexityScorer 顶格关键字扫描回归。
 *
 * ①substring(i) 每字符位整段复制 O(n²) → startsWith(fromIndex) 零分配（语义一致，
 *   结构性证据，反向探针不适用）；
 * ②边界判定补 '_'（与 PerfRulesBatch1.hasTopLevelKeyword 同口径）——
 *   `my_or_col` 里的 OR 曾被计入顶层 OR 数，标识符内嵌关键字（order_total 的 ORDER）
 *   同族误判词边界。
 */
class ComplexityScorerTopLevelKeywordBoundaryTest {

    @Test
    fun `标识符内的OR不计入顶层OR数`() {
        val base = ComplexityScorer.scoreBreakdown("SELECT * FROM t WHERE status = 1 OR status = 2")
        val embedded = ComplexityScorer.scoreBreakdown("SELECT * FROM t WHERE my_or_col = 1 OR status = 2")
        assertEquals(
            "my_or_col 内嵌 OR 不得计入（修复前 embedded 比 base 多计 1）",
            base["orCondition"],
            embedded["orCondition"],
        )
        assertEquals(2, base["orCondition"]) // OR_CONDITION=2 × 1 个顶层 OR
    }

    @Test
    fun `真顶层多个OR计数不回归`() {
        val two = ComplexityScorer.scoreBreakdown("SELECT * FROM t WHERE a = 1 OR b = 2 OR c = 3")
        assertEquals(2 * 2, two["orCondition"]) // 2 个顶层 OR × OR_CONDITION=2
    }
}
