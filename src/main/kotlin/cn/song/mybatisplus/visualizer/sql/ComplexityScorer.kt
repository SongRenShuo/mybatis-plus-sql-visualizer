package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.sql.extract.JoinExtractor
import cn.song.mybatisplus.visualizer.sql.extract.SelectStarDetector
import cn.song.mybatisplus.visualizer.sql.extract.SubqueryExtractor
import cn.song.mybatisplus.visualizer.sql.extract.TableExtractor
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning

/**
 * SQL 复杂度评分器（F-3，纯函数，零 PSI/零 IDE 依赖）。
 *
 * 对 SQL 查询给出 0-100 的复杂度评分，帮助开发者识别高风险复杂查询。
 *
 * 评分维度：
 *  - 结构复杂度（Phase A）：表数量、JOIN 数量、子查询嵌套深度
 *  - 风险因子（Phase B）：SELECT *、前导通配符、无 WHERE、函数包裹列、
 *    ORDER BY RAND()、DISTINCT、GROUP BY、HAVING、UNION、CTE、OR 等
 *
 * 设计原则：
 *  - 纯文本分析（与 [SqlPerfAnalyzer] 同风格），后续可替换为 JSqlParser AST
 *  - 性能：评分 < 30ms（典型 SQL），零阻塞 EDT
 *  - 失败安全：解析失败返回 -1（未知），不阻塞预览
 */
object ComplexityScorer {

    /**
     * 评分等级（F-3）。
     */
    enum class ScoreLevel {
        SIMPLE,       // 0-20: 简单
        MEDIUM,       // 21-40: 中等
        COMPLEX,      // 41-60: 较复杂
        VERY_COMPLEX, // 61-80: 复杂
        EXTREME,      // 81-100: 极复杂
    }

    // ------------------------------------------------------------------
    // 评分权重常量
    // ------------------------------------------------------------------

    const val TABLE_COUNT = 3
    const val JOIN_PER_TABLE = 5
    const val SUBQUERY_DEPTH = 10
    const val UNION = 6
    const val CTE = 8
    const val OR_CONDITION = 2
    const val IN_ITEMS_OVER_100 = 10
    const val NESTED_SUBQUERY = 12
    const val SELECT_STAR = 10
    const val LEADING_WILDCARD = 8
    const val MISSING_WHERE = 15
    const val MISSING_WHERE_DML = 50
    const val NO_LIMIT = 5
    const val FUNCTION_ON_COL = 7
    const val ORDER_BY_RAND = 10
    const val DISTINCT = 3
    const val GROUP_BY = 2
    const val HAVING = 4
    const val MAX_SCORE = 100

    // ------------------------------------------------------------------
    // 评分入口
    // ------------------------------------------------------------------

    /**
     * 计算 SQL 复杂度评分（纯结构维度 + 风险因子）。
     *
     * @param sql SQL 文本
     * @return 0-100 评分；无法识别返回 -1；空 SQL 返回 0
     */
    fun computeScore(sql: String): Int {
        if (sql.isBlank()) return 0
        if (!isRecognizableSql(sql)) return -1

        var score = 0

        // 结构维度（Phase A）
        val tables = TableExtractor.extract(sql)
        val joinCount = JoinExtractor.count(sql)
        val maxSubqueryDepth = SubqueryExtractor.maxDepth(sql)

        score += tables.size * TABLE_COUNT
        score += joinCount * JOIN_PER_TABLE
        score += maxSubqueryDepth * SUBQUERY_DEPTH

        // 风险维度（Phase B）
        if (SelectStarDetector.detect(sql)) score += SELECT_STAR
        if (hasLeadingWildcard(sql)) score += LEADING_WILDCARD
        if (hasMissingWhere(sql)) {
            score += if (isUpdateOrDelete(sql)) MISSING_WHERE_DML else MISSING_WHERE
        }
        if (isSelect(sql) && !hasLimit(sql)) score += NO_LIMIT
        if (hasFunctionOnColumn(sql)) score += FUNCTION_ON_COL
        if (hasOrderByRand(sql)) score += ORDER_BY_RAND
        if (hasDistinct(sql)) score += DISTINCT
        if (hasGroupBy(sql)) score += GROUP_BY
        if (hasHaving(sql)) score += HAVING

        // UNION 计数
        val unionCount = countUnion(sql)
        score += unionCount * UNION

        // CTE 计数
        val cteCount = countCte(sql)
        score += cteCount * CTE

        // OR 条件计数
        val orCount = countOrConditions(sql)
        score += orCount * OR_CONDITION

        // 嵌套子查询加分（深度 >= 2 时的额外加分）
        if (maxSubqueryDepth >= 2) score += NESTED_SUBQUERY

        return minOf(score, MAX_SCORE)
    }

    /**
     * 计算评分（含 F-2 警告严重度叠加）。
     *
     * @param sql SQL 文本
     * @param warnings F-2 性能警告列表
     * @return 0-100 评分
     */
    fun computeScoreWithWarnings(sql: String, warnings: List<PerfWarning>): Int {
        val baseScore = computeScore(sql)
        if (baseScore < 0) return baseScore

        var score = baseScore
        for (w in warnings) {
            score += when (w.severity) {
                PerfSeverity.ERROR -> 10
                PerfSeverity.WARN -> 5
                PerfSeverity.INFO -> 1
            }
        }
        return minOf(score, MAX_SCORE)
    }

    /**
     * 将分数转换为评分等级。
     *
     * @param score 评分（0-100）
     * @return 评分等级
     */
    fun scoreLevel(score: Int): ScoreLevel = when {
        score <= 20 -> ScoreLevel.SIMPLE
        score <= 40 -> ScoreLevel.MEDIUM
        score <= 60 -> ScoreLevel.COMPLEX
        score <= 80 -> ScoreLevel.VERY_COMPLEX
        else -> ScoreLevel.EXTREME
    }

    /**
     * 返回评分详情 breakdown（各维度得分）。
     *
     * @param sql SQL 文本
     * @return 维度名 → 得分 映射（含 "total" 键）
     */
    fun scoreBreakdown(sql: String): Map<String, Int> {
        if (sql.isBlank()) return mapOf("total" to 0)
        if (!isRecognizableSql(sql)) return mapOf("total" to -1)

        val breakdown = linkedMapOf<String, Int>()

        val tables = TableExtractor.extract(sql)
        val joinCount = JoinExtractor.count(sql)
        val maxSubqueryDepth = SubqueryExtractor.maxDepth(sql)

        breakdown["tableCount"] = tables.size * TABLE_COUNT
        breakdown["joinCount"] = joinCount * JOIN_PER_TABLE
        breakdown["subqueryDepth"] = maxSubqueryDepth * SUBQUERY_DEPTH

        if (SelectStarDetector.detect(sql)) breakdown["selectStar"] = SELECT_STAR
        if (hasLeadingWildcard(sql)) breakdown["leadingWildcard"] = LEADING_WILDCARD
        if (hasMissingWhere(sql)) {
            breakdown["missingWhere"] = if (isUpdateOrDelete(sql)) MISSING_WHERE_DML else MISSING_WHERE
        }
        if (isSelect(sql) && !hasLimit(sql)) breakdown["noLimit"] = NO_LIMIT
        if (hasFunctionOnColumn(sql)) breakdown["functionOnCol"] = FUNCTION_ON_COL
        if (hasOrderByRand(sql)) breakdown["orderByRand"] = ORDER_BY_RAND
        if (hasDistinct(sql)) breakdown["distinct"] = DISTINCT
        if (hasGroupBy(sql)) breakdown["groupBy"] = GROUP_BY
        if (hasHaving(sql)) breakdown["having"] = HAVING

        val unionCount = countUnion(sql)
        if (unionCount > 0) breakdown["union"] = unionCount * UNION
        val cteCount = countCte(sql)
        if (cteCount > 0) breakdown["cte"] = cteCount * CTE
        val orCount = countOrConditions(sql)
        if (orCount > 0) breakdown["orCondition"] = orCount * OR_CONDITION
        if (maxSubqueryDepth >= 2) breakdown["nestedSubquery"] = NESTED_SUBQUERY

        breakdown["total"] = breakdown.filterKeys { it != "total" }.values.sum()
        return breakdown
    }

    // ------------------------------------------------------------------
    // 私有检测函数
    // ------------------------------------------------------------------

    /**
     * 判断是否为可识别的 SQL（含基本 SQL 关键字）。
     */
    private fun isRecognizableSql(sql: String): Boolean {
        val upper = sql.uppercase()
        return upper.contains("SELECT") || upper.contains("UPDATE") ||
            upper.contains("DELETE") || upper.contains("INSERT")
    }

    /**
     * 判断是否为 SELECT 语句。
     */
    private fun isSelect(sql: String): Boolean {
        return sql.uppercase().trimStart().startsWith("SELECT")
    }

    /**
     * 判断是否为 UPDATE 或 DELETE 语句。
     */
    private fun isUpdateOrDelete(sql: String): Boolean {
        val upper = sql.uppercase().trimStart()
        return upper.startsWith("UPDATE") || upper.startsWith("DELETE")
    }

    /**
     * 检测前导通配符 LIKE。
     */
    private fun hasLeadingWildcard(sql: String): Boolean {
        val likePattern = Regex(
            """(?:NOT\s+)?LIKE\s+['"](%[^'"]*)['"]""",
            RegexOption.IGNORE_CASE,
        )
        return likePattern.findAll(sql).any { match ->
            match.groupValues[1].startsWith("%")
        }
    }

    /**
     * 检测无 WHERE 条件。
     */
    private fun hasMissingWhere(sql: String): Boolean {
        val upper = sql.uppercase()
        return !findTopLevelKeyword(upper, "WHERE")
    }

    /**
     * 检测是否含有 LIMIT。
     */
    private fun hasLimit(sql: String): Boolean {
        return findTopLevelKeyword(sql.uppercase(), "LIMIT")
    }

    /**
     * 检测 WHERE 子句中是否有函数包裹列（如 WHERE FUNC(col) = ?）。
     */
    private fun hasFunctionOnColumn(sql: String): Boolean {
        val upper = sql.uppercase()
        val whereIdx = upper.indexOf("WHERE")
        if (whereIdx < 0) return false
        val whereClause = upper.substring(whereIdx)
        // 检测 WHERE 后紧跟函数调用的模式：WHERE FUNC(
        return Regex("""WHERE\s+\w+\s*\(""").containsMatchIn(whereClause)
    }

    /**
     * 检测 ORDER BY RAND()。
     */
    private fun hasOrderByRand(sql: String): Boolean {
        return Regex("""ORDER\s+BY\s+RAND\s*\(""", RegexOption.IGNORE_CASE).containsMatchIn(sql)
    }

    /**
     * 检测 DISTINCT。
     */
    private fun hasDistinct(sql: String): Boolean {
        return Regex("""\bDISTINCT\b""", RegexOption.IGNORE_CASE).containsMatchIn(sql)
    }

    /**
     * 检测 GROUP BY。
     */
    private fun hasGroupBy(sql: String): Boolean {
        return findTopLevelKeyword(sql.uppercase(), "GROUP BY")
    }

    /**
     * 检测 HAVING。
     */
    private fun hasHaving(sql: String): Boolean {
        return findTopLevelKeyword(sql.uppercase(), "HAVING")
    }

    /**
     * 统计 UNION 数量。
     */
    private fun countUnion(sql: String): Int {
        return Regex("""\bUNION\b""", RegexOption.IGNORE_CASE).findAll(sql).count()
    }

    /**
     * 统计 CTE 数量（WITH 子句中的 CTE 定义数）。
     */
    private fun countCte(sql: String): Int {
        val upper = sql.uppercase()
        if (!upper.trimStart().startsWith("WITH")) return 0
        // 统计 WITH 后的逗号数 + 1 = CTE 数量
        val withIdx = upper.indexOf("WITH")
        val afterWith = upper.substring(withIdx + 4)
        // 找到 FROM（CTE 定义结束）
        val fromIdx = findTopLevelKeywordIndex(afterWith, "FROM")
        val cteSection = if (fromIdx >= 0) afterWith.substring(0, fromIdx) else afterWith
        return cteSection.split(",").size
    }

    /**
     * 统计 OR 条件数量（仅顶层 WHERE 子句中的 OR）。
     */
    private fun countOrConditions(sql: String): Int {
        val upper = sql.uppercase()
        val whereIdx = upper.indexOf("WHERE")
        if (whereIdx < 0) return 0
        val whereClause = upper.substring(whereIdx)
        // 统计顶层的 OR（不在括号内）
        var count = 0
        var depth = 0
        var i = 0
        while (i < whereClause.length) {
            when (whereClause[i]) {
                '(' -> depth++
                ')' -> depth--
                else -> {
                    // T-HiddenBugPerfScan 券 Q：substring(i) 每字符位整段复制 O(n²) → startsWith(fromIndex) 零分配；
                    // 边界判定补 '_'（与 PerfRulesBatch1.hasTopLevelKeyword 对齐——my_or_col 里的 OR 曾被误计）
                    if (depth == 0 && whereClause.startsWith("OR", i)) {
                        val before = whereClause.getOrNull(i - 1)
                        val after = whereClause.getOrNull(i + 2)
                        if ((before == null || !before.isLetterOrDigit() && before != '_') &&
                            (after == null || !after.isLetterOrDigit() && after != '_')
                        ) {
                            count++
                        }
                    }
                }
            }
            i++
        }
        return count
    }

    // ------------------------------------------------------------------
    // 辅助函数
    // ------------------------------------------------------------------

    /**
     * 检查顶层关键字是否存在（不在括号内）。
     */
    private fun findTopLevelKeyword(upperSql: String, keyword: String): Boolean {
        return findTopLevelKeywordIndex(upperSql, keyword) >= 0
    }

    /**
     * 查找顶层关键字索引（不在括号内）。
     * @return 找到返回索引；未找到返回 -1
     */
    private fun findTopLevelKeywordIndex(upperSql: String, keyword: String): Int {
        var depth = 0
        var i = 0
        while (i < upperSql.length) {
            when (upperSql[i]) {
                '(' -> depth++
                ')' -> depth--
                else -> {
                    // T-HiddenBugPerfScan 券 Q：substring(i) O(n²) → startsWith(fromIndex) 零分配；
                    // 边界判定补 '_'（与 PerfRulesBatch1 同口径——标识符内嵌关键字不再误判词边界）
                    if (depth == 0 && upperSql.startsWith(keyword, i)) {
                        val before = upperSql.getOrNull(i - 1)
                        val after = upperSql.getOrNull(i + keyword.length)
                        if ((before == null || !before.isLetterOrDigit() && before != '_') &&
                            (after == null || !after.isLetterOrDigit() && after != '_')
                        ) {
                            return i
                        }
                    }
                }
            }
            i++
        }
        return -1
    }
}
