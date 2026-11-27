package cn.song.mybatisplus.visualizer.sql.perf

import cn.song.mybatisplus.visualizer.execute.SqlAstParser
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.DmDialect
import cn.song.mybatisplus.visualizer.sql.KingBaseDialect
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.OpenGaussDialect
import cn.song.mybatisplus.visualizer.sql.OracleDialect
import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect
import cn.song.mybatisplus.visualizer.sql.SqlServerDialect
import cn.song.mybatisplus.visualizer.sql.GaussDbDialect
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch5
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import net.sf.jsqlparser.statement.Statement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第五批性能规则测试（T-ParamLimit / D272）：`param_limit_exceeded` 的方言上限判定与两条实现路径。
 *
 * 覆盖重点（对应计划 §四 / §六）：
 *  - **S2 方言名对账**：`IN_LIST_LIMIT` 的 key 必须全部是
 *    [DialectRegistry.dialects] 里的**真实** `Dialect.name`——**从注册表遍历真名去比**，不手写字符串。
 *    达梦与人大金仓的名字带全角括号，手写短名会让规则静默失效而测试照样绿。
 *  - **S5 反向验证**：Oracle 1001 → ERROR、1000 → 不报、PG 1001 → 不报。
 *  - **S6 降级路径**：`ast == null` 时 1001 → WARN（不是 ERROR）；`IN (SELECT …)` → 不报。
 *  - **计划 §六.4 保守档**：达梦 / 金仓即使 AST 路径且超限也只报 WARN，文案自曝「未真机验证」。
 *  - **探针实测的 AST 形态**：`IN (...) AND 其它条件` 的右值是 AndExpression、列表在左分支——
 *    「IN + 其它条件」这个最常见的真实形态必须能被数出来。
 */
class PerfRulesBatch5Test {

    // ------------------------------------------------------------------
    // S2：方言名对账（防「名称对不上 → 静默失效 → 空绿」）
    // ------------------------------------------------------------------

    @Test
    fun `S2_限值表的方言名全部来自DialectRegistry的真实name`() {
        val realNames = DialectRegistry.dialects.map { it.name }.toSet()
        val unknown = PerfRulesBatch5.IN_LIST_LIMIT.keys.filter { it !in realNames }
        assertTrue(
            "IN_LIST_LIMIT 里有 DialectRegistry 不认识的方言名（规则会永不触发，且测试若同样写错仍绿）: $unknown",
            unknown.isEmpty(),
        )
    }

    @Test
    fun `S2_达梦与金仓的key带全角括号且能按名取回同一条目`() {
        // 不用手写字面量：从注册表拿对象，用它的 name 去查表，必须能查到
        for (dialect in listOf<Dialect>(DmDialect, KingBaseDialect)) {
            val limit = PerfRulesBatch5.IN_LIST_LIMIT[dialect.name]
            assertNotNull("按 ${dialect.name} 查不到限值（key 与 Dialect.name 不一致）", limit)
            assertEquals(1000, limit)
            assertTrue("${dialect.name} 应带全角括号", dialect.name.contains('（') && dialect.name.contains('）'))
        }
    }

    @Test
    fun `S2_七个有硬限的方言按真实name全部可查`() {
        val expected = mapOf(
            OracleDialect to 1000,
            DmDialect to 1000,
            KingBaseDialect to 1000,
            SqlServerDialect to 2100,
            PostgreSQLDialect to 65535,
            OpenGaussDialect to 65535,
            GaussDbDialect to 65535,
        )
        assertEquals("限值表条数（7 个方言有明确 IN 列表硬限）", 7, PerfRulesBatch5.IN_LIST_LIMIT.size)
        for ((dialect, limit) in expected) {
            assertEquals("${dialect.name} 的限值", limit, PerfRulesBatch5.IN_LIST_LIMIT[dialect.name])
        }
    }

    @Test
    fun `S2_MySQL不在限值表内`() {
        val limit = PerfRulesBatch5.IN_LIST_LIMIT[MySqlDialect.name]
        assertTrue("MySQL 无明确 IN 列表项数硬限，不应入表（实际 $limit）", limit == null)
    }

    // ------------------------------------------------------------------
    // S5：反向验证（Oracle 1001 ERROR / 1000 不报 / PG 1001 不报）
    // ------------------------------------------------------------------

    @Test
    fun `S5_Oracle_1001个占位符_报ERROR`() {
        val sql = selectWithIn(1001)
        val warnings = check(sql, OracleDialect, astOf(sql))
        assertEquals("Oracle 1001 项必报: $warnings", 1, warnings.size)
        assertEquals("param_limit_exceeded", warnings[0].ruleId)
        assertEquals(PerfSeverity.ERROR, warnings[0].severity)
        assertEquals("perf.param_limit_exceeded", warnings[0].messageKey)
        // 文案带三层信息：实际个数、方言、上限
        assertEquals("1001", warnings[0].messageArgs[0])
        assertEquals("Oracle", warnings[0].messageArgs[1])
        assertEquals("1000", warnings[0].messageArgs[2])
    }

    @Test
    fun `S5_Oracle_1000个占位符_恰好不报`() {
        val sql = selectWithIn(1000)
        assertTrue("Oracle 1000 项是上限内、不得报: ${check(sql, OracleDialect, astOf(sql))}", check(sql, OracleDialect, astOf(sql)).isEmpty())
    }

    @Test
    fun `S5_Oracle_1001个字面量_也报ERROR`() {
        val sql = selectWithIn(1001, "1")
        val warnings = check(sql, OracleDialect, astOf(sql))
        assertEquals("字面量形态同样受 1000 限制: $warnings", 1, warnings.size)
        assertEquals(PerfSeverity.ERROR, warnings[0].severity)
    }

    @Test
    fun `S5_PostgreSQL_1001个参数_不报_未超65535`() {
        val sql = selectWithIn(1001)
        assertTrue("PG 上限 65535，1001 项不得报: ${check(sql, PostgreSQLDialect, astOf(sql))}", check(sql, PostgreSQLDialect, astOf(sql)).isEmpty())
    }

    @Test
    fun `S5_SQLServer_2099不报_2101报ERROR`() {
        val under = selectWithIn(2099)
        assertTrue("SQL Server 2099 项未超 2100: ${check(under, SqlServerDialect, astOf(under))}", check(under, SqlServerDialect, astOf(under)).isEmpty())
        val over = selectWithIn(2101)
        val warnings = check(over, SqlServerDialect, astOf(over))
        assertEquals("SQL Server 2101 项必报: $warnings", 1, warnings.size)
        assertEquals(PerfSeverity.ERROR, warnings[0].severity)
        assertEquals("2100", warnings[0].messageArgs[2])
    }

    @Test
    fun `S5_PostgreSQL_66000个参数_报ERROR`() {
        val sql = selectWithIn(66000)
        val warnings = check(sql, PostgreSQLDialect, astOf(sql))
        assertEquals("PG 超过 65535 必报: $warnings", 1, warnings.size)
        assertEquals(PerfSeverity.ERROR, warnings[0].severity)
        assertEquals("65535", warnings[0].messageArgs[2])
    }

    @Test
    fun `S5_MySQL_10001个参数_不报_无明确硬限`() {
        val sql = selectWithIn(10001)
        assertTrue("MySQL 不在表内，一律不报: ${check(sql, MySqlDialect, astOf(sql))}", check(sql, MySqlDialect, astOf(sql)).isEmpty())
    }

    @Test
    fun `S5_无方言上下文_1001个参数_不报`() {
        val sql = selectWithIn(1001)
        assertTrue("无方言上下文宁可不报: ${check(sql, null, astOf(sql))}", check(sql, null, astOf(sql)).isEmpty())
    }

    // ------------------------------------------------------------------
    // 计划 §六.4：达梦 / 金仓保守档（AST 路径也只 WARN，且文案自曝未验证）
    // ------------------------------------------------------------------

    @Test
    fun `保守档_达梦AST路径超限_只报WARN且文案自曝未验证`() {
        val sql = selectWithIn(1001)
        val warnings = check(sql, DmDialect, astOf(sql))
        assertEquals("达梦应命中: $warnings", 1, warnings.size)
        assertEquals("未真机验证的方言不得用 ERROR", PerfSeverity.WARN, warnings[0].severity)
        assertEquals("perf.param_limit_exceeded_unverified", warnings[0].messageKey)
        val text = MpSqlVisualizerBundle.messageEn(warnings[0].messageKey, *warnings[0].messageArgs.toTypedArray())
        assertTrue("文案须自曝该限制未在真机验证: $text", text.contains("not been verified"))
    }

    @Test
    fun `保守档_金仓AST路径超限_只报WARN`() {
        val sql = selectWithIn(1001)
        val warnings = check(sql, KingBaseDialect, astOf(sql))
        assertEquals("金仓应命中: $warnings", 1, warnings.size)
        assertEquals(PerfSeverity.WARN, warnings[0].severity)
        assertEquals("perf.param_limit_exceeded_unverified", warnings[0].messageKey)
    }

    @Test
    fun `保守档_达梦1000项不报`() {
        val sql = selectWithIn(1000)
        assertTrue(check(sql, DmDialect, astOf(sql)).isEmpty())
    }

    // ------------------------------------------------------------------
    // 探针实测的 AST 形态：「IN + 其它条件」必须数得出来
    // ------------------------------------------------------------------

    @Test
    fun `AST形态_IN后接AND_列表藏在AndExpression左分支_必须数到`() {
        // 探针实测：此形态 rightExpression 是 AndExpression，列表在左分支。
        // 只判 `right is ExpressionList` 会静默漏报——这是最常见的真实批量删除形态。
        val sql = "SELECT id FROM t WHERE id IN (" + units(1001, "?") + ") AND deleted = 0"
        val warnings = check(sql, OracleDialect, astOf(sql))
        assertEquals("IN + AND 形态必须命中: $warnings", 1, warnings.size)
        assertEquals(PerfSeverity.ERROR, warnings[0].severity)
        assertEquals("1001", warnings[0].messageArgs[0])
    }

    @Test
    fun `AST形态_IN在AND右侧_必须数到`() {
        val sql = "SELECT id FROM t WHERE deleted = 0 AND id IN (" + units(1001, "?") + ")"
        val warnings = check(sql, OracleDialect, astOf(sql))
        assertEquals("IN 在 AND 右分支也要命中: $warnings", 1, warnings.size)
    }

    @Test
    fun `AST形态_IN后接OR_必须数到`() {
        val sql = "SELECT id FROM t WHERE id IN (" + units(1001, "?") + ") OR a = 1"
        val warnings = check(sql, OracleDialect, astOf(sql))
        assertEquals("IN + OR 形态必须命中: $warnings", 1, warnings.size)
    }

    @Test
    fun `AST形态_子查询里的IN也要数到`() {
        val sql = "SELECT * FROM (SELECT * FROM t WHERE id IN (" + units(1001, "?") + ")) x"
        val warnings = check(sql, OracleDialect, astOf(sql))
        assertEquals("深层子查询里的 IN 也受同一限制: $warnings", 1, warnings.size)
    }

    @Test
    fun `AST形态_NOT_IN超限也报`() {
        val sql = "SELECT id FROM t WHERE id NOT IN (" + units(1001, "?") + ")"
        val warnings = check(sql, OracleDialect, astOf(sql))
        assertEquals("NOT IN 同样受 1000 限制: $warnings", 1, warnings.size)
        assertEquals(PerfSeverity.ERROR, warnings[0].severity)
    }

    @Test
    fun `AST形态_IN子查询不算参数列表_不报`() {
        val sql = "SELECT id FROM t WHERE id IN (SELECT uid FROM t2)"
        assertTrue("IN (SELECT …) 是子查询、不是参数列表: ${check(sql, OracleDialect, astOf(sql))}", check(sql, OracleDialect, astOf(sql)).isEmpty())
    }

    @Test
    fun `AST形态_字符串字面量里的IN不误伤`() {
        val sql = "SELECT id FROM t WHERE note = 'id IN (" + units(1001, "1") + ")'"
        assertTrue(
            "字符串字面量里的 IN 不是节点: ${check(sql, OracleDialect, astOf(sql))}",
            check(sql, OracleDialect, astOf(sql)).isEmpty(),
        )
    }

    @Test
    fun `AST形态_多个IN取最大值报告`() {
        val sql = "SELECT id FROM t WHERE id IN (" + units(1001, "?") + ") AND gid IN (" + units(500, "?") + ")"
        val warnings = check(sql, OracleDialect, astOf(sql))
        assertEquals("应只报一条: $warnings", 1, warnings.size)
        assertEquals("报最大的那个列表（1001 而非 500）", "1001", warnings[0].messageArgs[0])
    }

    // ------------------------------------------------------------------
    // S6：降级路径（ast == null）
    // ------------------------------------------------------------------

    @Test
    fun `S6_降级路径_1001个参数_报WARN而非ERROR`() {
        val sql = selectWithIn(1001)
        val warnings = check(sql, OracleDialect, ast = null)
        assertEquals("降级路径也应命中: $warnings", 1, warnings.size)
        assertEquals("正则计数可能不准，只能是 WARN", PerfSeverity.WARN, warnings[0].severity)
        assertEquals("perf.param_limit_exceeded_regex", warnings[0].messageKey)
        assertEquals("1001", warnings[0].messageArgs[0])
    }

    @Test
    fun `S6_降级路径_IN子查询不报`() {
        val sql = "SELECT id FROM t WHERE id IN (SELECT a, b, c FROM t2)"
        assertTrue(
            "降级路径必须排除 IN (SELECT …): ${check(sql, OracleDialect, ast = null)}",
            check(sql, OracleDialect, ast = null).isEmpty(),
        )
    }

    @Test
    fun `S6_降级路径_1000个参数不报`() {
        val sql = selectWithIn(1000)
        assertTrue(check(sql, OracleDialect, ast = null).isEmpty())
    }

    @Test
    fun `S6_降级路径_字符串字面量里的IN不误伤`() {
        val sql = "SELECT id FROM t WHERE note = 'id IN (" + units(1001, "1") + ")'"
        assertTrue(
            "字面量必须先剥离: ${check(sql, OracleDialect, ast = null)}",
            check(sql, OracleDialect, ast = null).isEmpty(),
        )
    }

    @Test
    fun `S6_降级路径_行注释里的IN不误伤`() {
        val sql = "SELECT id FROM t -- id IN (" + units(1001, "1") + ")\nWHERE id = 1"
        assertTrue(
            "注释必须先剥离: ${check(sql, OracleDialect, ast = null)}",
            check(sql, OracleDialect, ast = null).isEmpty(),
        )
    }

    @Test
    fun `S6_降级路径_块注释里的IN不误伤`() {
        val sql = "SELECT id FROM t /* id IN (" + units(1001, "1") + ") */ WHERE id = 1"
        assertTrue(
            "块注释必须先剥离: ${check(sql, OracleDialect, ast = null)}",
            check(sql, OracleDialect, ast = null).isEmpty(),
        )
    }

    @Test
    fun `S6_降级路径_IN后接AND_也能数到`() {
        val sql = "SELECT id FROM t WHERE id IN (" + units(1001, "?") + ") AND deleted = 0"
        val warnings = check(sql, OracleDialect, ast = null)
        assertEquals("降级路径也要处理 IN + AND: $warnings", 1, warnings.size)
        assertEquals(PerfSeverity.WARN, warnings[0].severity)
    }

    @Test
    fun `S6_降级路径_PG_1001不报`() {
        val sql = selectWithIn(1001)
        assertTrue(check(sql, PostgreSQLDialect, ast = null).isEmpty())
    }

    @Test
    fun `S6_降级路径_MySQL不报`() {
        val sql = selectWithIn(1001)
        assertTrue(check(sql, MySqlDialect, ast = null).isEmpty())
    }

    // ------------------------------------------------------------------
    // i18n：三层信息与双语键
    // ------------------------------------------------------------------

    @Test
    fun `文案含三层信息_实际个数与方言与上限`() {
        val warnings = check(selectWithIn(1001), OracleDialect, astOf(selectWithIn(1001)))
        val en = MpSqlVisualizerBundle.messageEn(warnings[0].messageKey, *warnings[0].messageArgs.toTypedArray())
        assertTrue("英文文案须含实际个数 1001: $en", en.contains("1001"))
        assertTrue("英文文案须含方言名 Oracle: $en", en.contains("Oracle"))
        assertTrue("英文文案须含上限 1000: $en", en.contains("1000"))
        assertTrue("英文文案须点出 ORA-01795 这个可检索的错误码: $en", en.contains("ORA-01795"))
    }

    @Test
    fun `三个文案键在中英bundle都存在且占位符一致`() {
        val keys = listOf(
            "perf.param_limit_exceeded",
            "perf.param_limit_exceeded_unverified",
            "perf.param_limit_exceeded_regex",
        )
        for (key in keys) {
            val en = MpSqlVisualizerBundle.messageEn(key, "1001", "Oracle", "1000")
            assertTrue("英文键缺失或被原样回显（真机会显示裸键名）: $key -> $en", !en.startsWith(key))
            assertTrue("英文文案须回填 {0}: $key -> $en", en.contains("1001"))
        }
    }

    // ------------------------------------------------------------------
    // 契约：规则隔离与性能
    // ------------------------------------------------------------------

    @Test
    fun `契约_无方言上下文的check返回空列表`() {
        // DialectAwarePerfRule 要求实现 check(sql, ast) 作为无方言降级，语义即「不报」
        val sql = selectWithIn(1001)
        assertTrue(PerfRulesBatch5.ParamLimitRule.check(sql, astOf(sql)).isEmpty())
        assertTrue(PerfRulesBatch5.ParamLimitRule.check(sql, null).isEmpty())
    }

    @Test
    fun `契约_畸形输入不上抛异常`() {
        // 规则契约：内部异常必须捕获返回空列表
        val odd = listOf(
            "SELECT id FROM t WHERE id IN (",
            "IN (((",
            "SELECT 'unclosed string",
            "/* unterminated comment",
            "",
        )
        for (sql in odd) {
            val warnings = PerfRulesBatch5.ParamLimitRule.check(PerfContext(sql, null, OracleDialect))
            assertTrue("畸形输入不得上抛、也不得误报: [$sql] -> $warnings", warnings.isEmpty())
        }
    }

    @Test
    fun `契约_闸门经方言分派_不是无条件执行`() {
        // 第四批的范式：DialectAwarePerfRule 由引擎在拿到方言时改调 check(ctx)。
        // 直接 new 出来的规则实例必须实现该子接口，否则引擎会走 check(sql, ast) 恒空。
        assertTrue(
            "ParamLimitRule 必须实现 DialectAwarePerfRule，否则引擎永远走 emptyList 的降级分支",
            PerfRulesBatch5.ParamLimitRule is DialectAwarePerfRule,
        )
    }

    @Test
    fun `规则条数_Batch5只含一条规则`() {
        assertEquals(1, PerfRulesBatch5.all.size)
        assertEquals("param_limit_exceeded", PerfRulesBatch5.all[0].ruleId)
    }

    @Test
    fun `性能_1001项判定耗时低于5ms`() {
        val sql = selectWithIn(1001)
        val ast = astOf(sql)
        // 预热（JIT + JSqlParser 类加载不在计时范围内）
        repeat(20) { PerfRulesBatch5.ParamLimitRule.check(PerfContext(sql, ast, OracleDialect)) }
        val start = System.nanoTime()
        repeat(100) { PerfRulesBatch5.ParamLimitRule.check(PerfContext(sql, ast, OracleDialect)) }
        val perCallMs = (System.nanoTime() - start) / 100.0 / 1_000_000.0
        assertTrue("单次规则执行应 < 5ms，实测 ${"%.3f".format(perCallMs)}ms", perCallMs < 5.0)
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun astOf(sql: String): Statement = SqlAstParser.parseSingle(sql)

    private fun check(
        sql: String,
        dialect: Dialect?,
        ast: Statement?,
    ): List<PerfWarning> = PerfRulesBatch5.ParamLimitRule.check(PerfContext(sql, ast, dialect))

    /** 构造 `SELECT id FROM t WHERE id IN (?,?,…)`。 */
    private fun selectWithIn(count: Int, unit: String = "?"): String =
        "SELECT id FROM t WHERE id IN (" + units(count, unit) + ")"

    private fun units(count: Int, unit: String): String = (1..count).joinToString(",") { unit }
}
