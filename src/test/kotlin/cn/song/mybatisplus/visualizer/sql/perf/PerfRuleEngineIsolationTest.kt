package cn.song.mybatisplus.visualizer.sql.perf

import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch1
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 Y3：规则隔离 + 失败可归因回归。
 *
 * apply 曾空 catch 零日志（KDoc 承诺「异常时记录并跳过」）——规则内部抛错只表现为
 * 「该规则静默不再产出告警」，性能分析能力静默退化不可归因。修复后 WARN 留痕；
 * 本测试钉隔离语义（异常规则不阻塞其他规则）。
 *
 * 引擎是共享单例（SqlPerfAnalyzer 启动期 registerAll），测试用「同 ruleId 覆盖注入
 * 破坏规则 → 断言 → 注册原规则还原」的零污染方案。
 */
class PerfRuleEngineIsolationTest {

    private class BrokenRule(override val ruleId: String) : PerfRule {
        override fun check(sql: String, ast: net.sf.jsqlparser.statement.Statement?): List<PerfWarning> {
            throw IllegalStateException("rule exploded")
        }
    }

    @Test
    fun `异常规则被隔离_其他规则照常产出`() {
        // 引擎规则由 SqlPerfAnalyzer init 注册（单例惰性初始化）——先触碰触发注册
        cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer
        val victim = PerfRulesBatch1.all.first()
        PerfRuleEngine.register(BrokenRule(victim.ruleId))
        try {
            val warnings = PerfRuleEngine.apply(
                PerfContext(sql = "SELECT * FROM t WHERE a = 1", ast = null, dialect = null),
            )
            assertTrue(
                "破坏规则被隔离跳过，其余规则照常产出（SQL 含 SELECT * 等可检反模式）",
                warnings.isNotEmpty(),
            )
        } finally {
            PerfRuleEngine.register(victim) // 还原：同 ruleId 覆盖，引擎状态零残留
        }
    }
}
