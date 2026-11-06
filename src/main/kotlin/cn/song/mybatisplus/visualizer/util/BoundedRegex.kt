package cn.song.mybatisplus.visualizer.util

import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 有界正则求值（T-SAF-25，dynamic-tablename-rule.regex-unbounded）：
 * 给 java.util.regex 的求值加**字符读预算**，防止导入/低信任来源的正则模式
 * （嵌套量词、病态交替）在现实长度输入上灾难性回溯挂死调用线程（ReDoS）。
 *
 * 原理：java.util.regex 的 Matcher 经 `CharSequence.charAt/length` 逐字符读输入——
 * 用计数 CharSequence 包装输入，读次数超预算即抛内部控制流异常，调用方把超预算
 * 视为「无匹配」（fail-safe，不放大成异常外漏）。读次数是引擎步数的上界代理
 * （每个引擎步至少读一个字符），对回溯型病态模式的封顶足够可靠。
 *
 * 预算口径：默认 [DEFAULT_MAX_CHAR_READS]。注：mcp HistorySearchService（T-SAF-24，
 * 另一车道）自带内联预算——两处实现统一登记为 followup，本类不越界改 mcp/。
 *
 * 本类为纯 JVM 实现，可独立单测。
 */
object BoundedRegex {

    /** 单次求值默认字符读预算（≈ 引擎步数上界代理）。 */
    const val DEFAULT_MAX_CHAR_READS: Int = 200_000

    /** 求值超预算（内部控制流；调用方不应看到本异常外漏）。 */
    internal class BudgetExceeded : RuntimeException()

    private val warnedOnce = AtomicBoolean(false)

    /**
     * 有界 [Regex.find]：超预算返回 null（视为无匹配）并至多告警一次。
     */
    fun find(regex: Regex, input: CharSequence, maxCharReads: Int = DEFAULT_MAX_CHAR_READS): MatchResult? =
        runBounded(regex.toPattern(), input, maxCharReads) { it.find() }

    /**
     * 有界 [Regex.matches]：超预算返回 false（视为不匹配）并至多告警一次。
     */
    fun matches(regex: Regex, input: CharSequence, maxCharReads: Int = DEFAULT_MAX_CHAR_READS): Boolean =
        runBounded(regex.toPattern(), input, maxCharReads) { it.matches() } == true

    private fun <T> runBounded(pattern: Pattern, input: CharSequence, maxCharReads: Int, op: (Matcher) -> T): T? {
        val bounded = CountingCharSequence(input, maxCharReads)
        return try {
            op(pattern.matcher(bounded))
        } catch (_: BudgetExceeded) {
            // over-budget = no match；告警至多一次（风暴期不刷日志）
            if (warnedOnce.compareAndSet(false, true)) {
                com.intellij.openapi.diagnostic.Logger.getInstance(BoundedRegex::class.java)
                    .warn("BoundedRegex: 正则求值超预算（maxCharReads=$maxCharReads），视为无匹配（仅告警一次）")
            }
            null
        }
    }

    /**
     * 计数 CharSequence：每次 charAt/length 计一次读，超预算抛 [BudgetExceeded]。
     * Matcher 不经 subSequence/toString 取输入（仅 compile 期会），二者如实委托不计数，
     * 防意外的全量复制绕过预算。
     */
    private class CountingCharSequence(
        private val delegate: CharSequence,
        private val maxReads: Int,
    ) : CharSequence {
        private var reads = 0

        private fun charge() {
            if (++reads > maxReads) throw BudgetExceeded()
        }

        override val length: Int
            get() { charge(); return delegate.length }

        override fun get(index: Int): Char {
            charge(); return delegate[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            delegate.subSequence(startIndex, endIndex)

        override fun toString(): String = delegate.toString()
    }
}
