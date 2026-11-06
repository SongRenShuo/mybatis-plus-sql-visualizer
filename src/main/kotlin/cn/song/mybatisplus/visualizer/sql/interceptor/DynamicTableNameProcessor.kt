package cn.song.mybatisplus.visualizer.sql.interceptor

import cn.song.mybatisplus.visualizer.config.TableNameRule
import cn.song.mybatisplus.visualizer.util.BoundedRegex
import com.intellij.openapi.diagnostic.Logger

/**
 * 动态表名替换引擎（F-6 Phase B）：按用户配置的正则规则替换预览 SQL 中的表名。
 *
 * 对齐 MP DynamicTableNameInnerInterceptor 运行时语义——运行时动态替换表名
 * （如 `order_2024` → `order_202401`）；预览时按规则静态近似。
 *
 * 性能：正则编译缓存（[regexCache]，避免重复编译）；处理 < 20ms。
 * 规则按列表顺序应用（首个匹配即停）；正则编译失败时跳过该规则并日志警告。
 *
 * T-SAF-25（dynamic-tablename-rule.regex-unbounded）：规则 pattern 可经设置导入安装
 * （低信任来源），本处为运行时兜底——
 *  - 编译缓存改 ConcurrentHashMap（原 mutableMapOf 跨线程读写有竞态：渲染线程读 /
 *    PluginCacheManager.clearAll 清空）并设容量上限（超限整体清空重建）；
 *  - 匹配求值带步数预算（[BoundedRegex.find]，字符读代理），超预算视为无匹配
 *    （fail-safe）并至多告警一次——导入侧（T-SAF-08 模式筛查）之外的纵深。
 *
 * 替换模板支持：
 *  - `$1` / `$2` ... 引用正则捕获组
 *  - `$tableName` 引用完整原始表名（变量引用，优先于捕获组解析）
 *
 * 本类为纯函数，不依赖 PSI / IDE，可独立单测。
 */
object DynamicTableNameProcessor {

    private val LOG = Logger.getInstance(DynamicTableNameProcessor::class.java)

    /** T-SAF-25：编译缓存容量上限——pattern 多样性有限（用户规则条数有限），超限整体清空重建。 */
    private const val REGEX_CACHE_MAX = 256

    /** 正则编译缓存（pattern → Regex?）；值 null 表示编译失败（缓存避免重复日志警告）。 */
    private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /**
     * T-SAF-25 修正：编译失败的 pattern 记入 [invalidPatterns]（负缓存）——ConcurrentHashMap
     * 不能存 null（旧 mutableMapOf 口径靠缓存 null 跳过重复编译/告警），负缓存同样封顶防膨胀。
     */
    private val invalidPatterns = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** T-SAF-25 测试缝：缓存当前条数（容量封顶断言用）。 */
    internal fun regexCacheSizeForTest(): Int = regexCache.size

    /**
     * 按规则列表替换表名。
     *
     * @param tableName 原始表名（预览 SQL 中的表名，已含前缀/后缀修饰）
     * @param rules     启用的规则列表（按顺序应用，首个匹配即停）
     * @return 替换后的表名（无规则命中时返回 null）
     */
    fun process(tableName: String, rules: List<TableNameRule>): String? {
        for (rule in rules) {
            if (!rule.enabled || rule.pattern.isBlank()) continue
            val regex = compileRegex(rule.pattern) ?: continue
            // T-SAF-25：有界求值——超预算（灾难性回溯）视为该规则无匹配，继续后续规则
            val match = BoundedRegex.find(regex, tableName) ?: continue
            return resolveReplacement(tableName, rule.replacement, match)
        }
        return null
    }

    /**
     * 编译正则（带缓存）；编译失败返回 null 并日志警告（仅首次，后续命中缓存跳过）。
     * T-SAF-25：缓存超 [REGEX_CACHE_MAX] 时整体清空（防低信任来源以海量 distinct pattern
     * 撑爆缓存；容量与规则规模同量级，正常使用不触发）。
     */
    private fun compileRegex(pattern: String): Regex? {
        regexCache[pattern]?.let { return it }
        if (pattern in invalidPatterns) return null
        if (regexCache.size >= REGEX_CACHE_MAX) {
            LOG.warn("DynamicTableName: 正则编译缓存超上限（$REGEX_CACHE_MAX），整体清空重建")
            regexCache.clear()
        }
        if (invalidPatterns.size >= REGEX_CACHE_MAX) invalidPatterns.clear()
        val regex = runCatching { Regex(pattern) }.getOrElse { e ->
            LOG.warn("DynamicTableName: 正则编译失败，跳过规则 pattern=$pattern, error=${e.message}")
            invalidPatterns += pattern
            return null
        }
        regexCache[pattern] = regex
        return regex
    }

    /**
     * 解析替换模板：先处理 `$tableName` 变量引用，再处理 `$1`/`$2` 捕获组引用。
     */
    private fun resolveReplacement(tableName: String, replacement: String, match: MatchResult): String {
        var result = replacement.replace("\$tableName", tableName)
        // 捕获组替换（$1 → groupValues[1]，以此类推）
        for (i in 1 until match.groupValues.size) {
            result = result.replace("\$$i", match.groupValues[i])
        }
        return result
    }

    /**
     * 清空正则缓存（设置变更时调用，防规则修改后命中旧缓存）。
     */
    fun clearCache() {
        regexCache.clear()
    }
}
