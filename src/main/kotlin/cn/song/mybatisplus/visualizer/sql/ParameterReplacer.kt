package cn.song.mybatisplus.visualizer.sql

/**
 * 参数替换器（F-02）：处理 SQL 中残留的 `?` 占位符与参数对照表。
 *
 * 说明：绝大多数参数值在 psi 层已通过变量追踪（M2-01）内联进 SQL；
 * 残留的 `?` 表示运行时才能确定的值（如方法参数），静态插件无法取得实际值，
 * 因此提供两种兜底：
 *  - 来源注释模式：[annotate] 将 `?` 标注其来源（如 `? /*deviceId*/`）
 *  - 对照表：[buildParamTable] 生成 `-- 参数对照: [deviceId=?, stationId=?]`
 *  - 可执行 SQL：[buildExecutableSql] 将 `?` 替换为带类型处理的字面值（供复制到外部工具执行）
 */
object ParameterReplacer {

    /**
     * 将 SQL 中的 `?` 按出现顺序标注来源注释。
     *
     * @param sql    含 `?` 的 SQL
     * @param params 参数来源列表（与 [GeneratedSql.unresolvedParams] 对应）
     * @return 标注后的 SQL（`?` 数量多于参数列表时，多余的 `?` 保持原样）
     */
    fun annotate(sql: String, params: List<String>): String {
        if (params.isEmpty()) return sql
        var index = 0
        val sb = StringBuilder(sql.length + params.size * 12)
        var i = 0
        while (i < sql.length) {
            val ch = sql[i]
            if (ch == '?' && index < params.size) {
                sb.append("? /*").append(params[index++]).append("*/")
            } else {
                sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * 生成参数对照表（供 UI 展示，如 `-- 参数对照: [deviceId=?, stationId=?]`）。
     *
     * @param params 未解析参数来源列表
     * @return 对照表文本；无参数时返回 null
     */
    fun buildParamTable(params: List<String>): String? {
        if (params.isEmpty()) return null
        val body = params.joinToString(", ") { "$it=?" }
        return "-- 参数对照: [$body]"
    }

    /**
     * 将 SQL 中的 `?` 占位符替换为带类型处理的字面值，生成可执行 SQL（供复制到 DBeaver/DataGrip 等工具直接执行）。
     *
     * 替换规则（与面板回填 [SqlPreviewPanel] 一致）：
     *  - 值未填写（空/空白）→ 保留 `?`
     *  - [ParamHint.typeText] 在场 → 先走 [TypedLiteralRenderer]（F-01 类型感知：按 JDBC/Java
     *    类型 × 方言出字面量）；渲染器判不了（null）→ 落入下方既有规则，行为不变
     *  - [ParamHint.quote] = true → 单引号包裹（String/日期列）
     *  - [ParamHint.quote] = false → 原样渲染（数值列）
     *  - [ParamHint.quote] = null → 数值启发式（纯数字原样，否则引号包裹）
     *  - 值中单引号转义为 `''`（防 SQL 注入，与面板回填一致）
     *
     * T-PSG-02（N-33）：位点判定改用 [placeholderPositions]（string-aware）——字符串字面量与
     * `--` / 块注释内的 `?` 不再被当作占位符替换（改前朴素逐字符 `c == '?'` 会错位）。不含
     * 这类 `?` 的 SQL 替换结果与改前逐字节一致（替换语义与顺序不变）。
     *
     * @param sql        含 `?` 的 SQL（`?` 顺序与 [sources] 一一对应）
     * @param sources    参数来源列表（与 SQL 中 `?` 按出现顺序对应）
     * @param paramValues 参数值映射（source → 用户输入值）
     * @param hints      与 [sources] 并行的回填提示（引号规则）；缺失走启发式
     * @param dialect    F-01：目标方言注册名（`Dialect.name`）；null = 方言未知，类型感知走通用档
     * @return 替换后的可执行 SQL
     */
    fun buildExecutableSql(
        sql: String,
        sources: List<String>,
        paramValues: Map<String, String>,
        hints: List<ParamHint> = emptyList(),
        dialect: String? = null,
    ): String {
        if (sources.isEmpty()) return sql
        val positions = placeholderPositions(sql)
        if (positions.isEmpty()) return sql
        val sb = StringBuilder(sql.length + 32)
        var paramIndex = 0
        var prev = 0
        for (pos in positions) {
            sb.append(sql, prev, pos)
            val source = sources.getOrNull(paramIndex)
            val value = source?.let { paramValues[it] }
            sb.append(renderValue(value, hints.getOrNull(paramIndex), dialect))
            paramIndex++
            prev = pos + 1
        }
        sb.append(sql, prev, sql.length)
        return sb.toString()
    }

    /**
     * 单值渲染：[ParamHint.typeText] 在场先走 [TypedLiteralRenderer]（F-01，判不了返回 null
     * 落回旧规则）；再按 [ParamHint.quote] 决定引号规则；值中单引号转义为 `''`。
     * 空值/空白 → 返回 `?`（保留占位符）。
     */
    private fun renderValue(value: String?, hint: ParamHint?, dialect: String?): String {
        if (value.isNullOrBlank()) return "?"
        if (hint?.typeText != null) {
            TypedLiteralRenderer.render(value, hint.typeText, dialect)?.let { return it }
        }
        val escaped = value.replace("'", "''")
        return when {
            hint?.quote == true -> "'$escaped'"
            hint?.quote == false -> value.trim()
            else -> {
                val trimmed = value.trim()
                if (NUMBER_REGEX.matches(trimmed)) trimmed else "'$escaped'"
            }
        }
    }

    // ------------------------------------------------------------------
    // JDBC 命名参数（NamedParameterJdbcTemplate `:name`）按名回填
    // ------------------------------------------------------------------

    /**
     * 按命名参数名把 SQL 中 `:name` 替换为用户输入值（NamedParameterJdbcTemplate 语义）。
     *
     * 替换规则：
     *  - `:name` 出现在 [paramValues] 中且值非空 → 按 [ParamHint.quote] 渲染
     *  - `:name` 未出现在参数值表 / 值空 → 原样保留（与 `?` 空值保留语义一致）
     *  - `::name`（双冒号）→ 跳过（双冒号守卫，避免误标 PostgreSQL 类型转换）
     *  - `?` 占位符不受影响（由调用方走 [buildExecutableSql] 按序回填）
     *  - 同名参数在 SQL 中多次出现 → 全部替换（NamedParameterJdbcTemplate 语义：同名复用）
     *
     * 值含单引号 → 转义 `''`（与 [renderValue] 一致）。
     *
     * @param sql         含 `:name` 的 SQL（`?` 与 `:name` 可共存）
     * @param paramValues 参数值映射（source → 用户输入值）；source 即命名参数名
     * @param hints       参数名 → 引号提示；缺失走启发式（数值裸 / 字符串引号）
     * @param dialect     F-01：目标方言注册名；null = 方言未知，类型感知走通用档
     * @return 替换后的 SQL
     */
    fun replaceNamedParams(
        sql: String,
        paramValues: Map<String, String>,
        hints: Map<String, ParamHint>,
        dialect: String? = null,
    ): String {
        if (paramValues.isEmpty() || hints.isEmpty()) return sql
        return NAMED_PARAM_PATTERN.replace(sql) { m ->
            val name = m.groupValues[1]
            val value = paramValues[name]
            if (value.isNullOrBlank()) {
                m.value // 空值 → 原样保留 `:name`
            } else {
                renderNamedValue(value, hints[name], dialect)
            }
        }
    }

    /** 命名参数单值渲染：typeText 在场先走 [TypedLiteralRenderer]；再按 [ParamHint.quote] 决定引号规则。 */
    private fun renderNamedValue(value: String, hint: ParamHint?, dialect: String?): String {
        if (hint?.typeText != null) {
            TypedLiteralRenderer.render(value, hint.typeText, dialect)?.let { return it }
        }
        val escaped = value.replace("'", "''")
        return when {
            hint?.quote == true -> "'$escaped'"
            hint?.quote == false -> value.trim()
            else -> {
                val trimmed = value.trim()
                if (NUMBER_REGEX.matches(trimmed)) trimmed else "'$escaped'"
            }
        }
    }

    /** 数值启发式正则（无 [ParamHint] 时判定是否加引号）。 */
    private val NUMBER_REGEX = Regex("-?\\d+(\\.\\d+)?")

    /**
     * 命名参数正则。
     *
     * 负向前瞻 `(?<![::])` 排除双冒号（前一个字符不是 `:`），避免误标 `::msgId` /
     * `1::integer`。捕获组 1 = 参数名（字母 / 数字 / 下划线）。
     * 冒号后容忍**可选空白** `\s*`——SQL 经 [cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter] 格式化后
     * 会把 `:name` 的冒号当作运算符空格化为 `: name`（用户实测绘点），务必同样命中。
     */
    private val NAMED_PARAM_PATTERN = Regex("(?<![::])[:]\\s*([A-Za-z_][A-Za-z0-9_]*)")
}
