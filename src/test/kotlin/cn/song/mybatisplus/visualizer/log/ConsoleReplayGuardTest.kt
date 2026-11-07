package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.log.ConsoleReplayGuard.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ConsoleReplayGuard 纯逻辑测试（T-PreviewFlash）。
 *
 * 四态判定覆盖：流式递增 / 纯重放 / 控制台重置 / 重放叠加新增混合态 / 显式复位 / 流式热路径 waterLevel 前移。
 */
class ConsoleReplayGuardTest {

    private fun decide(
        guard: ConsoleReplayGuard,
        endOffset: Int,
        docStamp: Long = 1L,
        lineText: String = "line",
        line0Text: String? = null,
        docLength: Int? = null,
    ): Decision = guard.decide(endOffset, docStamp, lineText, { line0Text }, docLength?.let { { it } })

    @Test
    fun `流式递增逐行 Process 且水位前移`() {
        val g = ConsoleReplayGuard()
        assertEquals(Decision.Process, decide(g, endOffset = 10, lineText = "a"))
        assertEquals(Decision.Process, decide(g, endOffset = 20, lineText = "b"))
        assertEquals(Decision.Process, decide(g, endOffset = 30, lineText = "c"))
        assertEquals(30, g.watermark)
    }

    @Test
    fun `纯重放 endOffset 回落且 stamp 未变判 Replay 且水位不动`() {
        val g = ConsoleReplayGuard()
        decide(g, endOffset = 100, lineText = "real")
        val before = g.watermark
        repeat(5) { i ->
            assertEquals(Decision.Replay, decide(g, endOffset = 10 + i, lineText = "replay-$i", docStamp = 1L))
        }
        assertEquals(before, g.watermark)
    }

    @Test
    fun `控制台重置 stamp 变且行文本非首行判 Reset 并处理`() {
        val g = ConsoleReplayGuard()
        decide(g, endOffset = 100, lineText = "old", line0Text = "old")
        // clear 后新日志从小的 endOffset 起步，stamp 变，文本不是当前首行 → Reset
        val r = decide(g, endOffset = 5, docStamp = 2L, lineText = "fresh", line0Text = "old")
        assertEquals(Decision.Reset, r)
        assertEquals(5, g.watermark)
    }

    @Test
    fun `重放叠加新增混合态 stamp 变且行文本等于首行判 Replay`() {
        val g = ConsoleReplayGuard()
        decide(g, endOffset = 100, lineText = "X", line0Text = "X")
        // stamp 变但首行文本一致（重放从行 0 开始）→ 仍判重放
        assertEquals(Decision.Replay, decide(g, endOffset = 10, docStamp = 2L, lineText = "X", line0Text = "X"))
        assertEquals(100, g.watermark)
    }

    @Test
    fun `显式 reset 清空水位与 stamp`() {
        val g = ConsoleReplayGuard()
        decide(g, endOffset = 100, lineText = "a")
        g.reset()
        assertEquals(0, g.watermark)
        // reset 后同样 endOffset=10 视为流式新增（不再误判重放）
        assertEquals(Decision.Process, decide(g, endOffset = 10, docStamp = 5L, lineText = "b"))
    }

    @Test
    fun `docStamp 不可得按未初始化处理等价首次流式`() {
        val g = ConsoleReplayGuard()
        // endOffset 从 0 起步（首行），docStamp = MIN_VALUE
        assertEquals(Decision.Process, decide(g, endOffset = 3, docStamp = Long.MIN_VALUE, lineText = "a"))
        // 后续正常递增
        assertEquals(Decision.Process, decide(g, endOffset = 5, docStamp = Long.MIN_VALUE, lineText = "ab"))
    }

    // ---------- T-SqlListCatchup 券 G2：clear/截断后的陈旧水位核销 ----------

    /**
     * clear 后重印：首个新行必然「等于行 0」（新文档的第一行就是它自己），旧判据把它连同
     * 后续全部新行判成 Replay 丢到旧水位之外——控制台通道静默到新文档长度追平旧水位为止。
     * 券 G2 修后：文档长度掉到水位之下 → 核销陈旧水位（Reset），首行按流式处理。
     *
     * 反向验证（S2）：摘掉 decide 的文档缩水分支重跑，本用例与「clear后重印_后续新行」必红。
     */
    @Test
    fun `clear后重印_首行等于行0_判Reset不再误判Replay`() {
        val g = ConsoleReplayGuard()
        // 风暴期水位已推到 100（旧文档很大）
        decide(g, endOffset = 100, lineText = "old-tail", docLength = 100)
        // console.clear()（stamp 变、文档只剩刚重印的首行，长度 8 < 旧水位 100）
        val d = decide(g, endOffset = 8, docStamp = 2L, lineText = "new-line", line0Text = "new-line", docLength = 8)
        assertEquals("clear 后首个重印行不得判 Replay（丢数据缺陷）", Decision.Reset, d)
        assertEquals("陈旧水位核销为当前行尾", 8, g.watermark)
    }

    @Test
    fun `clear后重印_后续新行单调Process`() {
        val g = ConsoleReplayGuard()
        decide(g, endOffset = 100, lineText = "old-tail", docLength = 100)
        assertEquals(Decision.Reset, decide(g, endOffset = 8, docStamp = 2L, lineText = "new-line", line0Text = "new-line", docLength = 8))
        // 后续行：水位已被核销，endOffset 单调递增 → 正常流式
        assertEquals(Decision.Process, decide(g, endOffset = 20, docStamp = 2L, lineText = "next", docLength = 20))
        assertEquals(20, g.watermark)
    }

    @Test
    fun `文档未缩水_混合态维持Replay语义`() {
        // 券 G2 不得回破「重放叠加新增混合态」既有语义：文档长度 ≥ 水位时行0比对照旧
        val g = ConsoleReplayGuard()
        decide(g, endOffset = 100, lineText = "X", docLength = 100)
        // stamp 变但文档仍比水位长（非 clear/截断形态）→ 首行一致的行照旧判 Replay
        assertEquals(Decision.Replay, decide(g, endOffset = 10, docStamp = 2L, lineText = "X", line0Text = "X", docLength = 200))
        assertEquals(100, g.watermark)
    }

    @Test
    fun `文档长度不可得_回退旧行为判Replay`() {
        // docLengthProvider 缺省（null）= 守卫回退旧行为——桥侧反射不可得的降级口径不受影响
        val g = ConsoleReplayGuard()
        decide(g, endOffset = 100, lineText = "old")
        assertEquals(Decision.Replay, decide(g, endOffset = 10, docStamp = 2L, lineText = "X", line0Text = "X"))
        assertEquals(100, g.watermark)
    }

    @Test
    fun `行0提供者惰性求值_流式与纯重放零调用_混合态恰一次`() {
        // T-PerfSweep F1：行 0 文本只在混合态分支才需要——提供者必须惰性求值
        val g = ConsoleReplayGuard()
        var calls = 0
        val provider = { calls += 1; "X" }
        // 流式推进（水位前移）
        assertEquals(Decision.Process, g.decide(10, 1L, "a", provider))
        // 纯重放（endOffset 回落、stamp 未变）
        assertEquals(Decision.Replay, g.decide(5, 1L, "r", provider))
        assertEquals("流式/纯重放路径不得求值行 0 文本", 0, calls)
        // 混合态（stamp 变化）——恰求值一次
        assertEquals(Decision.Replay, g.decide(3, 2L, "X", provider))
        assertEquals("混合态分支恰求值一次", 1, calls)
    }
}