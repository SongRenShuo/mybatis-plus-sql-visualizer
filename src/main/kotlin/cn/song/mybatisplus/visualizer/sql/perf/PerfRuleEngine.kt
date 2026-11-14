package cn.song.mybatisplus.visualizer.sql.perf

/**
 * 性能规则引擎（F-2 SQL 性能分析）。
 *
 * 注册 + 执行所有规则。每条规则在独立的 try-catch 中执行——单规则异常不阻塞其他规则。
 *
 * 执行流程：
 *  1. 遍历所有已注册规则；
 *  2. 每条规则独立 try-catch，异常时记录并跳过；
 *  3. 合并所有规则的警告列表返回。
 */
object PerfRuleEngine {

    private val rules = mutableListOf<PerfRule>()

    /**
     * 注册一条规则（幂等：同 ruleId 重复注册覆盖旧规则）。
     */
    fun register(rule: PerfRule) {
        rules.removeAll { it.ruleId == rule.ruleId }
        rules.add(rule)
    }

    /**
     * 批量注册规则。
     */
    fun registerAll(newRules: List<PerfRule>) {
        newRules.forEach { register(it) }
    }

    /**
     * 应用所有规则到 SQL。
     *
     * @param ctx 执行上下文（SQL 文本 / AST / 方言 / ANSI_QUOTES 三态）；AST 可为 null（降级模式），
     *            方言可为 null（无上下文）。仅实现 [DialectAwarePerfRule] 的规则收到整个 [PerfContext]，
     *            其余规则一律按 [PerfRule.check] 调用（只取 `ctx.sql` / `ctx.ast`）。
     * @return 所有规则的警告列表（按规则注册顺序）
     */
    fun apply(ctx: PerfContext): List<PerfWarning> {
        val warnings = mutableListOf<PerfWarning>()
        for (rule in rules) {
            try {
                val result = if (rule is DialectAwarePerfRule) {
                    rule.check(ctx)
                } else {
                    rule.check(ctx.sql, ctx.ast)
                }
                if (result.isNotEmpty()) warnings.addAll(result)
            } catch (e: Exception) {
                // 规则隔离：单规则异常不阻塞其他规则。
                // T-HiddenBugPerfScan 券 Y3：KDoc 承诺「异常时记录并跳过」，曾空 catch 零日志——
                // 规则内部抛错只表现为「该规则静默不再产出告警」，性能分析能力静默退化不可归因。
                com.intellij.openapi.diagnostic.Logger.getInstance(PerfRuleEngine::class.java)
                    .warn("perf rule '" + (runCatching { rule.ruleId }.getOrDefault("?")) + "' failed", e)
            }
        }
        return warnings
    }

    /** 当前注册规则数量（测试用）。 */
    fun ruleCount(): Int = rules.size
}
