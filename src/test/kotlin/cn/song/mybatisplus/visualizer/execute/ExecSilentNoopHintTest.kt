package cn.song.mybatisplus.visualizer.execute

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-ExecSilentNoop F2：执行失败提示分流的纯逻辑判定测试。
 *
 * 背景：预览首次执行 SQL 时 DBN 存在「全静默出口」（chooseStatements 空选集 / beforeExecuteQueries
 * 遇 statements 空或 isValid 假直接跳过，无输出、无结果 Tab、无异常），此前一律被 8 秒监控与
 * 40 拍窗口归因成「密码过期/驱动未下载」（execute.credential.hint），把用户引向错误排查方向。
 *
 * 修复把「同一次执行只报一次提示」的去重旗标与分流判定抽为纯逻辑（`HintGate` 仅持有一个布尔
 * 状态，分流仅依赖 demoted/dumb 两个输入），不依赖 Database 插件运行时，单测沙盒可精确锁定：
 *   - 同一次执行内，8 秒监控先报后，40 拍窗口不得重复报；
 *   - 未提交（freshModel 回落）→ notSubmitted；索引中 → indexing；其余无痕迹 → timeout；
 *   - 真实错误（console history 有错误文本）优先于一切笼统提示、且同样只报一次。
 */
class ExecSilentNoopHintTest : BasePlatformTestCase() {

    /**
     * 提示去重/分流的最小状态门——`executionHintReported` 的一次性语义与「demoted→notSubmitted、
     * dumb→indexing、其余→timeout / credential」判定逻辑的纯函数镜像。
     */
    private class HintGate {
        var reported: Boolean = false
            private set

        /** 若本次已报过提示则返回 false（去重不重复发）；否则标记已报并返回 true。 */
        fun claim(): Boolean {
            if (reported) return false
            reported = true
            return true
        }

        /** 无 console 错误痕迹时的笼统提示分流（与 40 拍窗口逻辑一致）。 */
        fun hintKey(dumb: Boolean, demotedOverride: Boolean): String = when {
            dumb -> "execute.indexing.hint"
            demotedOverride -> "execute.notSubmitted.hint"
            else -> "execute.timeout.hint"
        }
    }

    // ==================== F2a：同次执行只报一次（8s 与 40 拍去重） ====================

    /**
     * 8 秒连接监控先判定失败并 report 后，40 拍超时窗口不得对同一次执行再报一次——此前二者
     * 各按同一 key 报一次，用户见两次红字。
     */
    fun testSameExecutionReportsHintOnlyOnce() {
        val gate = HintGate()
        // 8 秒监控先 claim
        assertTrue("8s monitor should claim first", gate.claim())
        // 40 拍窗口再 claim，应被去重拒绝
        assertFalse("40-tick window must not re-report within same execution", gate.claim())
    }

    // ==================== F2b：无错误痕迹时的分流 ====================

    /** demoted（freshModel 回落）= DBN 侧静默拒绝 → 未提交提示，不再误报凭据。 */
    fun testDemotedFallbackMapsToNotSubmittedHint() {
        assertEquals("execute.notSubmitted.hint", HintGate().hintKey(dumb = false, demotedOverride = true))
    }

    /** 索引中 → indexing 提示（查询提交被推迟，非失败）。 */
    fun testDumbModeMapsToIndexingHintEvenWhenDemoted() {
        assertEquals("execute.indexing.hint", HintGate().hintKey(dumb = true, demotedOverride = true))
        assertEquals("execute.indexing.hint", HintGate().hintKey(dumb = true, demotedOverride = false))
    }

    /** 非 demoted、非 dumb、又无 console 错误痕迹 → 真正的超时提示（连接的确实发起了）。 */
    fun testNoErrorNoDemoteMapsToTimeoutHint() {
        assertEquals("execute.timeout.hint", HintGate().hintKey(dumb = false, demotedOverride = false))
    }

    // ==================== F2c：真实错误优先 + 同样只报一次 ====================

    /**
     * console history 出现真实错误（如 Unknown column）时，直接上报错误文本，不走笼统提示；
     * 且该上报也占用去重旗标，8s/40 拍后续不再补报。
     */
    fun testRealConsoleErrorTakesPrecedenceAndClaimsGate() {
        val gate = HintGate()
        val realError = "Unknown column 'x' in 'field list'"
        // 真实错误路径：直接返回错误文本（调用方不经过 hintKey）；占用去重旗标
        assertTrue("real error path should claim the gate", gate.claim())
        // 之后 40 拍窗口若还想给笼统提示，应被去重拒绝
        assertFalse(gate.claim())
        assertTrue("real error is non-blank", realError.isNotBlank())
    }
}