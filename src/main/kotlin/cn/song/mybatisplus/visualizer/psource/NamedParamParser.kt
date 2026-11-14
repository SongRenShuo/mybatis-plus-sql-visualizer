package cn.song.mybatisplus.visualizer.psource

import cn.song.mybatisplus.visualizer.sql.ParamHint

/**
 * 命名参数解析器（JDBC 模板 SQL 字符串预览）：从 SQL 文本中收集 NamedParameterJdbcTemplate
 * 风格的 `:name` 命名参数，去重保序，供面板参数行渲染与按名回填。
 *
 * 规则（对齐 NamedParameterJdbcTemplate 的参数扫描语义）：
 *  - `:CUR_USER`、`:flowKey`、`:msgId` → 识别
 *  - `::msgId`、`1::integer`（双冒号 / PostgreSQL 类型转换）→ **跳过**（双冒号守卫）
 *  - `:1`、`:2`（数字命名参数）→ 名 `1`/`2`，按 `?` 顺序语义处理
 *  - 同名参数多次出现 → 面板只渲染一条参数行，回填时全部命中
 *
 * 纯函数（正则 + 字符串处理），零 PSI / 零 IDE 依赖，单测可直接断言。
 */
object NamedParamParser {

    /**
     * 命名参数正则。
     *
     * 负向前瞻 `(?<![::])` 排除双冒号（前一个字符不是 `:`），避免误标 `::msgId` /
     * `1::integer` 等 PostgreSQL 类型转换语法。捕获组 1 = 参数名（字母 / 数字）。
     */
    private val NAMED_PARAM_PATTERN = Regex("(?<![::])[:]([A-Za-z_][A-Za-z0-9_]*)")

    /** 数字命名参数正则（`:1`、`:2` 等纯数字形式）。 */
    private val NUMERIC_PARAM_PATTERN = Regex("(?<![::])[:](\\d+)")

    /**
     * 数值语义命名参数名启发式（前缀匹配，数值/计数语义 → 裸渲染）。
     * 前缀匹配：`pageSize`/`pageNumber`/`numXxx`/`totalCount` 等 → 命中。
     */
    private val NUMERIC_LIKE_NAME = Regex("^(num|count|page|size|limit|offset|idx|index|total|version|rows?|cols?|seq|order|priority|level|type|status|flag|code|num[_-]?\\d+|page[_-]?\\d+)", RegexOption.IGNORE_CASE)

    /**
     * 布尔语义命名参数名（前缀匹配）。
     * `isActive`/`hasPermission`/`enableXxx` 等 → 命中。
     */
    private val BOOLEAN_LIKE_NAME = Regex("^(is[A-Z]|has[A-Z]|enable[A-Z]?|disabled?[A-Z]?|active[A-Z]?|deleted[A-Z]?|locked?[A-Z]?|visible[A-Z]?|required[A-Z]?|done[A-Z]?|finished[A-Z]?|closed?[A-Z]?|passed[A-Z]?|approved[A-Z]?|valid[A-Z]?|success[A-Z]?)", RegexOption.IGNORE_CASE)

    /** 带偏移的命名参数正则匹配（用于排序合并）。 */
    private data class MatchWithOrder(val name: String, val offset: Int, val numeric: Boolean)

    /**
     * 收集 SQL 中全部命名参数（去重，保序）。
     *
     * 单次遍历：合并 [NAMED_PARAM_PATTERN]（字母/下划线开头）与 [NUMERIC_PARAM_PATTERN]（纯数字）
     * 两个正则的匹配，按在 SQL 中出现位置排序，同名去重保序。
     *
     * @param sql 含 `:name` / `?` 的 SQL 文本
     * @return 去重保序的命名参数列表；无命名参数返回空列表
     */
    fun collectNamedParams(sql: String): List<NamedParam> {
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<NamedParam>()
        val allMatches = mutableListOf<MatchWithOrder>()
        // T-HiddenBugPerfScan 券 Y11：字符串字面量/注释内的 :name 不收（对齐 Spring
        // NamedParameterUtils 的参数扫描与同目录 JdbcTemplateSqlPreviewService 对 ? 的
        // string-aware 口径）——曾对聚合 SQL 裸跑正则，'提交:审核'、'a:b'、-- 见 :doc
        // 产生幻影参数行。掩码区间与 PlaceholderPositions 同口径
        // （单引号字面量含 '' 转义、-- 与 # 行注释、块注释）。
        val masked = maskLiteralsAndComments(sql)
        for (m in NAMED_PARAM_PATTERN.findAll(sql)) {
            if (masked[m.range.first]) continue
            allMatches += MatchWithOrder(m.groupValues[1], m.range.first, numeric = false)
        }
        for (m in NUMERIC_PARAM_PATTERN.findAll(sql)) {
            if (masked[m.range.first]) continue
            allMatches += MatchWithOrder(m.groupValues[1], m.range.first, numeric = true)
        }
        allMatches.sortBy { it.offset }
        for (m in allMatches) {
            if (seen.add(m.name)) {
                val quotedLike = if (m.numeric) false else isQuotedLike(m.name)
                result += NamedParam(name = m.name, quotedLike = quotedLike)
            }
        }
        return result
    }

    /**
     * 判断命名参数是否应按引号渲染（String/时间语义）。
     *
     * 启发式（近似，与 [cn.song.mybatisplus.visualizer.sql.ParamHint] 面板兜底一致）：
     *  - 纯数字名（`:1`）→ 裸渲染（false）
     *  - 布尔语义名（`isXxx`/`hasXxx`）→ 裸渲染（false）
     *  - 数值语义名（`page`/`size`/`count` 等）→ 裸渲染（false）
     *  - 其余（`CUR_USER`/`flowKey`/`msgId` 等语义常量）→ 引号（true）
     */
    private fun isQuotedLike(name: String): Boolean {
        // 前缀匹配（containsMatchIn）：`pageSize`/`isActive` 等语义前缀命中 → 裸渲染
        if (NUMERIC_LIKE_NAME.containsMatchIn(name)) return false
        if (BOOLEAN_LIKE_NAME.containsMatchIn(name)) return false
        return true
    }

    /**
     * 生成参数回填提示（引号规则）。
     *
     * 对齐 [cn.song.mybatisplus.visualizer.log.LogSqlRestorer.PLAIN_TYPES] 的引号判定经验：
     * 数值/布尔语义名 → `ParamHint(quote = false)`，其余 → `ParamHint(quote = true)`。
     * 列类型未知时返回 `ParamHint()` 走面板启发式（NUMBER_REGEX）。
     */
    fun quoteHint(param: NamedParam): ParamHint = when {
        param.quotedLike -> ParamHint(quote = true)
        else -> ParamHint(quote = false)
    }

    /**
     * T-HiddenBugPerfScan 券 Y11：标记字符串字面量与注释区间（true = 参数扫描应跳过）。
     * 口径：单引号字面量（'' 转义成对消费）、-- 与 # 行注释、/* */ 块注释。
     */
    private fun maskLiteralsAndComments(sql: String): BooleanArray {
        val masked = BooleanArray(sql.length)
        var i = 0
        while (i < sql.length) {
            when {
                sql[i] == '\'' -> {
                    masked[i] = true
                    i++
                    while (i < sql.length) {
                        masked[i] = true
                        if (sql[i] == '\'') {
                            if (i + 1 < sql.length && sql[i + 1] == '\'') {
                                masked[i + 1] = true
                                i += 2
                            } else {
                                i++
                                break
                            }
                        } else i++
                    }
                }
                sql[i] == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    while (i < sql.length && sql[i] != '\n') {
                        masked[i] = true
                        i++
                    }
                }
                sql[i] == '#' -> {
                    while (i < sql.length && sql[i] != '\n') {
                        masked[i] = true
                        i++
                    }
                }
                sql[i] == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    while (i < sql.length) {
                        masked[i] = true
                        if (sql[i] == '*' && i + 1 < sql.length && sql[i + 1] == '/') {
                            masked[i + 1] = true
                            i += 2
                            break
                        }
                        i++
                    }
                }
                else -> i++
            }
        }
        return masked
    }
}

/**
 * 命名参数（NamedParameterJdbcTemplate `:name` 风格）。
 *
 * @param name        参数名（去冒号，如 `CUR_USER`、`flowKey`、`1`）
 * @param quotedLike  引号提示：true=String/时间语义（引号），false=数值/语义名（裸渲染）
 */
data class NamedParam(
    val name: String,
    val quotedLike: Boolean,
)
