package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.execute.SqlAstParser
import cn.song.mybatisplus.visualizer.sql.perf.PerfContext
import cn.song.mybatisplus.visualizer.sql.perf.PerfRuleEngine
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch1
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch2
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch3
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch4
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch5
import net.sf.jsqlparser.statement.Statement

/**
 * SQL 性能分析器（F-2 SQL 性能分析建议）。
 *
 * 对 SQL 文本做**纯静态分析**，检测性能反模式并给出优化建议。
 * 零 PSI / 零 IDE 依赖（仅 JSqlParser），可用普通 JUnit 直接覆盖。
 *
 * 分析流程：
 *  1. 查缓存（同 SQL 文本 + 同方言 + 同 ANSI_QUOTES 三态不重复分析）；
 *  2. JSqlParser 解析为 Statement AST（失败降级到纯正则规则）；
 *  3. [PerfRuleEngine] 应用所有已注册规则；
 *  4. 返回 [PerfWarning] 列表（最多 10 条）。
 *
 * 规则注册在 init 块中完成（五批共 22 条规则）。
 *
 * 性能约束：
 *  - 典型 SQL（< 1000 字符）分析耗时 < 50ms；
 *  - 单规则异常隔离，不影响其他规则；
 *  - 结果缓存上限 200 条（LRU 淘汰）。
 */
object SqlPerfAnalyzer {

    /** 缓存上限（LRU）。 */
    private const val CACHE_LIMIT = 200

    /** 警告数量上限。 */
    private const val MAX_WARNINGS = 10

    /** SQL + 方言 + ANSI_QUOTES 三态 → 警告列表的缓存（ LinkedHashMap 实现 LRU）。键见 [cacheKey]。 */
    private val cache = object : LinkedHashMap<String, List<PerfWarning>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<PerfWarning>>): Boolean = size > CACHE_LIMIT
    }

    init {
        // 注册五批规则（共 22 条）
        PerfRuleEngine.registerAll(PerfRulesBatch1.all)
        PerfRuleEngine.registerAll(PerfRulesBatch2.all)
        PerfRuleEngine.registerAll(PerfRulesBatch3.all)
        PerfRuleEngine.registerAll(PerfRulesBatch4.all)
        PerfRuleEngine.registerAll(PerfRulesBatch5.all)
    }

    /**
     * 分析 SQL，返回性能警告列表。
     *
     * @param sql 完整 SQL 文本（多行或单行）
     * @param dialect 当前数据库方言；可为 null（无上下文，方言感知规则自动不判）。默认 null 保持
     *                既有调用点逐字节行为不变。
     * @param ansiQuotes 绑定数据源是否**已声明** `sql_mode` 含 `ANSI_QUOTES`（T-DialectQuoteWarn §十二）：
     *                   `true` = 可证明合法、`false` = 已知但未声明、`null` = 未知（默认）。
     *                   `false` 与 `null` 判定等价（都只能 WARN），但**参与缓存键**——三态必须分开缓存。
     * @return 警告列表（按规则注册顺序，最多 [MAX_WARNINGS] 条）；无问题返回空列表，不返回 null
     */
    fun analyze(sql: String, dialect: Dialect? = null, ansiQuotes: Boolean? = null): List<PerfWarning> {
        if (sql.isBlank()) return emptyList()
        val key = cacheKey(sql, dialect, ansiQuotes)
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        val warnings = analyzeInternal(sql, dialect, ansiQuotes)
        synchronized(cache) {
            cache[key] = warnings
        }
        return warnings
    }

    /**
     * 缓存键：`sql + '\u0000' + 方言名 + '\u0000' + ansiQuotes 三态`（T-DialectQuoteWarn 四·3 + §十二）。
     *
     * **必须带方言**：同一条 SQL 在 MySQL（`FROM "user"` 报引号告警）与 PostgreSQL（不报）下结论相反，
     * 不带方言会让后一次分析命中前一次的缓存返回错结果（串味）。**必须带 ansiQuotes 三态**（§十二）：
     * 同一 `sql + 方言` 在「URL 声明 ANSI_QUOTES」（不报）与「未知」（WARN）下结论相反，三态共键同样串味。
     * `'\u0000'` 是不会出现在 SQL 文本里的分隔符，避免 `sql="a"+方言名` 与 `sql="a<方言名>"` 撞键。
     */
    private fun cacheKey(sql: String, dialect: Dialect?, ansiQuotes: Boolean?): String =
        sql + '\u0000' + (dialect?.name ?: "") + '\u0000' + when (ansiQuotes) {
            true -> "true"
            false -> "false"
            null -> "?"
        }

    /**
     * 清空分析缓存。
     */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    /**
     * 当前缓存大小（测试用）。
     */
    fun cacheSize(): Int = synchronized(cache) { cache.size }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun analyzeInternal(sql: String, dialect: Dialect?, ansiQuotes: Boolean?): List<PerfWarning> {
        val ast = parseSql(sql)
        val warnings = PerfRuleEngine.apply(PerfContext(sql = sql, ast = ast, dialect = dialect, ansiQuotes = ansiQuotes))
        return if (warnings.size > MAX_WARNINGS) warnings.take(MAX_WARNINGS) else warnings
    }

    /**
     * 解析 SQL 为 AST（T-PS7-03 收拢：走 SqlAstParser.parseSingle，方括号引用 SQL 的
     * AST 规则不再整体降级）。
     *
     * @return 解析成功返回 Statement；失败返回 null（降级到纯正则规则）
     */
    private fun parseSql(sql: String): Statement? {
        return runCatching { SqlAstParser.parseSingle(sql) }.getOrNull()
    }
}
