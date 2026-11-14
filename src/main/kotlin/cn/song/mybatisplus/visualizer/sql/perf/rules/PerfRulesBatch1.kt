package cn.song.mybatisplus.visualizer.sql.perf.rules

import cn.song.mybatisplus.visualizer.sql.perf.PerfRule
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import net.sf.jsqlparser.statement.Statement

/**
 * 第一批性能规则（正则即可，无需 AST 解析，共 6 条）。
 *
 * 规则清单：
 *  - `missing_where`：UPDATE/DELETE 无 WHERE
 *  - `leading_wildcard_like`：LIKE '%xxx' 前导通配符
 *  - `select_star`：SELECT * 多表 JOIN
 *  - `order_by_without_limit`：ORDER BY 无 LIMIT
 *  - `order_by_rand`：ORDER BY RAND()
 *  - `union_instead_of_union_all`：UNION 可改 UNION ALL
 */
object PerfRulesBatch1 {

    /** 所有第一批规则实例。 */
    val all: List<PerfRule> = listOf(
        MissingWhereRule,
        LeadingWildcardLikeRule,
        SelectStarRule,
        OrderByWithoutLimitRule,
        OrderByRandRule,
        UnionInsteadOfUnionAllRule,
    )

    /**
     * `missing_where`：UPDATE/DELETE 无 WHERE 条件（全表操作风险）。
     *
     * 检测逻辑：语句以 UPDATE 或 DELETE 开头（忽略前导空白/注释），且不含顶层 WHERE 关键字。
     */
    object MissingWhereRule : PerfRule {
        override val ruleId = "missing_where"

        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            val normalized = stripLeadingComments(sql).trim()
            val isUpdate = normalized.uppercase().startsWith("UPDATE ")
            val isDelete = normalized.uppercase().startsWith("DELETE ")
            if (!isUpdate && !isDelete) return emptyList()

            // 检测顶层 WHERE（不在括号内）。T-McpPerfWhereCase：hasTopLevelKeyword 按大写比对
            // （形参名 upperSql 即约定），此处必须传大写串——SQL 关键字大小写不敏感，而 mapper
            // 渲染产物普遍小写（`update ... where id = ?`），传原文会导致「有 WHERE 却报全表操作」。
            if (hasTopLevelKeyword(normalized.uppercase(), "WHERE")) return emptyList()

            val stmtType = if (isUpdate) "UPDATE" else "DELETE"
            return listOf(
                PerfWarning(
                    ruleId = ruleId,
                    severity = PerfSeverity.ERROR,
                    messageKey = "perf.missing_where",
                    messageArgs = listOf(stmtType),
                    line = 1,
                ),
            )
        }
    }

    /**
     * `leading_wildcard_like`：LIKE '%xxx' 前导通配符（无法走索引）。
     *
     * 检测逻辑：正则匹配 `LIKE '...'` / `NOT LIKE '...'`，模式以 `%` 开头。
     */
    object LeadingWildcardLikeRule : PerfRule {
        override val ruleId = "leading_wildcard_like"

        private val LIKE_PATTERN = Regex(
            """(?:NOT\s+)?LIKE\s+['"](%[^'"]*)['"]""",
            RegexOption.IGNORE_CASE,
        )

        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            val hasLeading = LIKE_PATTERN.findAll(sql).any { match ->
                match.groupValues[1].startsWith("%")
            }
            return if (hasLeading) {
                listOf(
                    PerfWarning(
                        ruleId = ruleId,
                        severity = PerfSeverity.WARN,
                        messageKey = "perf.leading_wildcard",
                    ),
                )
            } else emptyList()
        }
    }

    /**
     * `select_star`：SELECT * 多表 JOIN（字段冗余 + 列变更风险）。
     *
     * 检测逻辑：SELECT * 或 SELECT alias.*，且含至少一个 JOIN 子句。
     * 单表查询的 SELECT * 不报（反而不如明确列名风险低，但仍提示 INFO）。
     */
    object SelectStarRule : PerfRule {
        override val ruleId = "select_star"

        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            val upper = sql.uppercase()
            val selectIdx = upper.indexOf("SELECT")
            val fromIdx = upper.indexOf("FROM")
            if (selectIdx < 0 || fromIdx < 0 || fromIdx <= selectIdx) return emptyList()

            val selectSegment = sql.substring(selectIdx + "SELECT".length, fromIdx).trim()
            val cleaned = selectSegment.removePrefix("DISTINCT").removePrefix("ALL").trim()
            val isStar = cleaned == "*" || cleaned.matches(Regex("^[\\w]+\\.\\*$"))
            if (!isStar) return emptyList()

            // 有 JOIN → WARN（多表字段冗余），无 JOIN → INFO
            val hasJoin = upper.contains(" JOIN ")
            return listOf(
                PerfWarning(
                    ruleId = ruleId,
                    severity = if (hasJoin) PerfSeverity.WARN else PerfSeverity.INFO,
                    messageKey = if (hasJoin) "perf.select_star_with_join" else "perf.select_star",
                ),
            )
        }
    }

    /**
     * `order_by_without_limit`：ORDER BY 无 LIMIT（可能返回大量数据）。
     */
    object OrderByWithoutLimitRule : PerfRule {
        override val ruleId = "order_by_without_limit"

        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            val upper = sql.uppercase()
            if (!upper.contains("ORDER BY")) return emptyList()
            // 有 LIMIT 时不报
            if (hasTopLevelKeyword(upper, "LIMIT")) return emptyList()
            return listOf(
                PerfWarning(
                    ruleId = ruleId,
                    severity = PerfSeverity.INFO,
                    messageKey = "perf.order_by_without_limit",
                ),
            )
        }
    }

    /**
     * `order_by_rand`：ORDER BY RAND()（全表排序，性能杀手）。
     */
    object OrderByRandRule : PerfRule {
        override val ruleId = "order_by_rand"

        private val RAND_PATTERN = Regex(
            """ORDER\s+BY\s+[^()]*\bRAND\s*\(""",
            RegexOption.IGNORE_CASE,
        )

        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            return if (RAND_PATTERN.containsMatchIn(sql)) {
                listOf(
                    PerfWarning(
                        ruleId = ruleId,
                        severity = PerfSeverity.WARN,
                        messageKey = "perf.order_by_rand",
                    ),
                )
            } else emptyList()
        }
    }

    /**
     * `union_instead_of_union_all`：UNION 可改 UNION ALL（去重有额外开销）。
     *
     * 检测逻辑：存在 `UNION` 但非 `UNION ALL`。
     */
    object UnionInsteadOfUnionAllRule : PerfRule {
        override val ruleId = "union_instead_of_union_all"

        override fun check(sql: String, ast: Statement?): List<PerfWarning> {
            val upper = sql.uppercase()
            // 匹配 UNION 但排除 UNION ALL
            val unionPattern = Regex("""\bUNION\b(?!\s+ALL\b)""")
            return if (unionPattern.containsMatchIn(upper)) {
                listOf(
                    PerfWarning(
                        ruleId = ruleId,
                        severity = PerfSeverity.INFO,
                        messageKey = "perf.union_instead_of_union_all",
                    ),
                )
            } else emptyList()
        }
    }

    // 辅助函数已移至文件底部（顶层函数，避免嵌套对象访问限制）
}

// ------------------------------------------------------------------
// 顶层辅助函数（文件私有）
// ------------------------------------------------------------------

/** 剥离前导 SQL 注释（单行注释 + 块注释）。 */
private fun stripLeadingComments(sql: String): String {
    var s = sql.trim()
    while (true) {
        s = s.trim()
        if (s.startsWith("--")) {
            val nl = s.indexOf('\n')
            s = if (nl >= 0) s.substring(nl + 1) else ""
        } else if (s.startsWith("/*")) {
            val end = s.indexOf("*/")
            s = if (end >= 0) s.substring(end + 2) else ""
        } else {
            break
        }
    }
    return s
}

/** 检测顶层关键字（不在括号内）。 */
private fun hasTopLevelKeyword(upperSql: String, keyword: String): Boolean {
    var depth = 0
    var i = 0
    while (i < upperSql.length) {
        when (upperSql[i]) {
            '(' -> depth++
            ')' -> depth--
            else -> {
                // T-HiddenBugPerfScan 券 Q：substring(i) 每字符位整段复制 O(n2) 改 startsWith(fromIndex) 零分配，语义一致
                if (depth == 0 && upperSql.startsWith(keyword, i)) {
                    val before = upperSql.getOrNull(i - 1)
                    val after = upperSql.getOrNull(i + keyword.length)
                    if ((before == null || !before.isLetterOrDigit() && before != '_') &&
                        (after == null || !after.isLetterOrDigit() && after != '_')
                    ) {
                        return true
                    }
                }
            }
        }
        i++
    }
    return false
}
