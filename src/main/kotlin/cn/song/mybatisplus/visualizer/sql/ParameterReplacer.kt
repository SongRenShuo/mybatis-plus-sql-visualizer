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
     *  - [ParamHint.quote] = true → 单引号包裹（String/日期列）
     *  - [ParamHint.quote] = false → 原样渲染（数值列）
     *  - [ParamHint.quote] = null → 数值启发式（纯数字原样，否则引号包裹）
     *  - 值中单引号转义为 `''`（防 SQL 注入，与面板回填一致）
     *
     * T-PSG-02（N-33）：位点判定改用 [placeholderPositions]（string-aware）——字符串字面量与
     * `--` / 块注释内的 `?` 不再被当作占位符替换（改前朴素逐字符 `c == '?'` 会错位）。不含
     * 这类 `?` 的 SQL 替换结果与改前逐字节一致（替换语义与顺序不变）。
     *
     * T-RuntimeSlotFill 券 B：[protectedSpans] 是**已被运行期占位回填占用**的区间（入参 [sql]
     * 的坐标）。落在这些区间里的 `?` 一律不当参数占位符——用户填的值里带 `?` 时不会被这一步
     * 二次当占位（P-2）。默认空表 = 既有行为逐字节不变。
     *
     * @param sql        含 `?` 的 SQL（`?` 顺序与 [sources] 一一对应）
     * @param sources    参数来源列表（与 SQL 中 `?` 按出现顺序对应）
     * @param paramValues 参数值映射（source → 用户输入值）
     * @param hints      与 [sources] 并行的回填提示（引号规则）；缺失走启发式
     * @param protectedSpans 不回填的区间（运行期占位已占用的文本范围）
     * @param dialectName 当前方言名（日期族 ANSI 字面量门禁；null=未知，按支持处理）
     * @return 替换后的可执行 SQL
     */
    fun buildExecutableSql(
        sql: String,
        sources: List<String>,
        paramValues: Map<String, String>,
        hints: List<ParamHint> = emptyList(),
        protectedSpans: List<IntRange> = emptyList(),
        dialectName: String? = null,
    ): String {
        if (sources.isEmpty()) return sql
        val allPositions = placeholderPositions(sql)
        if (allPositions.isEmpty()) return sql
        val positions = if (protectedSpans.isEmpty()) {
            allPositions
        } else {
            allPositions.filter { p -> protectedSpans.none { p >= it.first && p <= it.last } }
        }
        if (positions.isEmpty()) return sql
        val sb = StringBuilder(sql.length + 32)
        var paramIndex = 0
        var prev = 0
        for (pos in positions) {
            sb.append(sql, prev, pos)
            val source = sources.getOrNull(paramIndex)
            val value = source?.let { paramValues[it] }
            sb.append(renderValue(value, hints.getOrNull(paramIndex), dialectName))
            paramIndex++
            prev = pos + 1
        }
        sb.append(sql, prev, sql.length)
        return sb.toString()
    }

    /**
     * 单值渲染：日期族（[ParamHint.temporal]）先试 ANSI 类型字面量；否则按 [ParamHint.quote]
     * 决定引号规则；值中单引号转义为 `''`。空值/空白 → 返回 `?`（保留占位符）。
     */
    private fun renderValue(value: String?, hint: ParamHint?, dialectName: String? = null): String {
        if (value.isNullOrBlank()) return "?"
        // LIKE 是字符串语义，优先于日期族（面板 renderParamValue 已先行分流；此处防御直接调用）
        if (hint?.temporal == true && hint.likePosition == null) {
            SqlDateLiteral.renderByShape(value, dialectName)?.let { return it }
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
     * @param protectedSpans T-RuntimeSlotFill 券 B：不回填的区间（运行期占位已占用的文本范围）
     * @param dialectName 当前方言名（日期族 ANSI 字面量门禁；null=未知，按支持处理）
     * @return 替换后的 SQL
     */
    fun replaceNamedParams(
        sql: String,
        paramValues: Map<String, String>,
        hints: Map<String, ParamHint>,
        protectedSpans: List<IntRange> = emptyList(),
        dialectName: String? = null,
    ): String {
        if (paramValues.isEmpty() || hints.isEmpty()) return sql
        if (protectedSpans.isEmpty()) {
            return NAMED_PARAM_PATTERN.replace(sql) { m ->
                val name = m.groupValues[1]
                val value = paramValues[name]
                if (value.isNullOrBlank()) {
                    m.value // 空值 → 原样保留 `:name`
                } else {
                    renderNamedValue(value, hints[name], dialectName)
                }
            }
        }
        val matcher = NAMED_PARAM_PATTERN.toPattern().matcher(sql)
        val sb = StringBuilder(sql.length + 32)
        var prev = 0
        while (matcher.find()) {
            if (protectedSpans.any { matcher.start() >= it.first && matcher.start() <= it.last }) continue
            val name = matcher.group(1)
            val value = paramValues[name]
            if (value.isNullOrBlank()) continue
            sb.append(sql, prev, matcher.start()).append(renderNamedValue(value, hints[name], dialectName))
            prev = matcher.end()
        }
        sb.append(sql, prev, sql.length)
        return sb.toString()
    }

    /**
     * 命名参数单值渲染：日期族（[ParamHint.temporal]）先试 ANSI 类型字面量；否则按
     * [ParamHint.quote] 决定引号规则；值中单引号转义为 `''`。
     */
    private fun renderNamedValue(value: String, hint: ParamHint?, dialectName: String? = null): String {
        if (hint?.temporal == true && hint.likePosition == null) {
            SqlDateLiteral.renderByShape(value, dialectName)?.let { return it }
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

    // ------------------------------------------------------------------
    // T-RuntimeSlotFill 券 B：运行期占位回填（slot → ? → :name）
    // ------------------------------------------------------------------

    /**
     * 一次「运行期占位回填」的产物。
     *
     * @param sql          回填后的文本（未回填的占位**原样保留**，与 β.2 行为一致）
     * @param appliedSlotIds 真正被填进 SQL 的占位 id（去重、按首次替换顺序）；空表 = 与输入逐字一致
     * @param warnings     逐条提示（没填进去的原因 / 需要用户知道的风险）
     * @param protectedSpans 已替换区间的**输出坐标**（[sql] 的坐标）。
     *
     * 「区间记账」是 P-2 的落实：占位替换与 `?` / `:name` 回填共用一次扫描，替换进来的用户值
     * 立即成为不可再扫描的成品区间；[buildExecutableSqlWithSlots] 的产物把区间记在这里，
     * 供后续步骤（或诊断）跳过。单次扫描本身已经杜绝二次误伤，区间是它的可核对凭据。
     */
    data class RuntimeSlotRender(
        val sql: String,
        val appliedSlotIds: List<String> = emptyList(),
        val warnings: List<SlotWarning> = emptyList(),
        val protectedSpans: List<IntRange> = emptyList(),
    ) {
        val hasWarnings: Boolean get() = warnings.isNotEmpty()

        /** 是否有占位被替换/删除；false = 产物与输入逐字一致（留空语义 D-5）。 */
        val changed: Boolean get() = appliedSlotIds.isNotEmpty()
    }

    /**
     * 只把运行期占位替换成字面量/片段（**不碰** `?` 与 `:name`）。
     *
     * 用于「只想看占位展开成什么」的场景（导出回放脚本、预览渲染），以及复制链在
     * 剥注释**之前**先把占位填掉（占位本身是块注释，先剥就再也找不到了）。
     *
     * 定位口径：按 [RuntimeSlot.token] 精确匹配（token 内含唯一 id，正常只出现一次；重复出现
     * 则每处都填同一个值）。找不到 token（用户手工改过文本）→ 保留原文并提示，不猜。
     *
     * @param sql        预览 SQL（含占位注释）
     * @param slots      本次预览的占位表
     * @param values     用户填的值；空表 / 某占位未登记 / 登记为「未动作」→ 该处原样保留
     * @param dialectName 当前方言名（只用于布尔字面量判定；null = 按有布尔字面量处理）
     */
    fun replaceRuntimeSlots(
        sql: String,
        slots: List<RuntimeSlot>,
        values: RuntimeSlotValues = RuntimeSlotValues.EMPTY,
        dialectName: String? = null,
    ): RuntimeSlotRender {
        if (slots.isEmpty() || values.isEmpty) return RuntimeSlotRender(sql)
        val effective = values.effective()
        if (effective.isEmpty()) return RuntimeSlotRender(sql)
        val mask = stringMask(sql)
        val context = RenderContext(dialectName)
        val warnings = mutableListOf<SlotWarning>()
        val applied = mutableListOf<String>()
        val edits = mutableListOf<Edit>()
        for (slot in slots) {
            val value = effective[slot.id] ?: continue
            val occurrences = occurrencesOf(sql, slot.token)
            if (occurrences.isEmpty()) {
                warnings += SlotWarning(slot.id, "SQL 里找不到这段占位（可能已被手工编辑），未回填")
                continue
            }
            var ok = true
            for (start in occurrences) {
                val filled = renderSlot(slot, value, start < mask.size && mask[start], context)
                if (filled.text == null) {
                    warnings += SlotWarning(slot.id, filled.error ?: "无法回填")
                    ok = false
                    break
                }
                filled.notice?.let { warnings += SlotWarning(slot.id, it) }
                edits += Edit(start, start + slot.token.length, filled.text, filled.text.isEmpty())
            }
            if (ok) applied += slot.id
        }
        if (edits.isEmpty()) return RuntimeSlotRender(sql, applied.distinct(), warnings.distinct())

        // 从后往前替换的等价形态：按起点排序后单次前向重建。索引位移问题在构造上消失
        // （不存在「先改前面再看后面」的坐标），并且删除动作可当场收敛相邻空白。
        val sb = StringBuilder(sql.length + 64)
        val spans = mutableListOf<IntRange>()
        var cursor = 0
        for (edit in edits.sortedBy { it.start }) {
            if (edit.start < cursor) continue // 理论不可达：token 带唯一 id，区间不会重叠
            sb.append(sql, cursor, edit.start)
            var end = edit.end
            if (edit.deletion && sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\t')) {
                while (end < sql.length && (sql[end] == ' ' || sql[end] == '\t')) end++
            }
            val at = sb.length
            sb.append(edit.text)
            if (edit.text.isNotEmpty()) spans += at until (at + edit.text.length)
            cursor = end
        }
        sb.append(sql, cursor, sql.length)
        return RuntimeSlotRender(sb.toString(), applied.distinct(), warnings.distinct(), spans)
    }

    /**
     * 统一回填（T-RuntimeSlotFill 券 B 的入口）：**先占位、再 `?`、再 `:name`**，全程一次扫描。
     *
     * 为什么必须一次扫描：占位值、`?` 值、`:name` 值都是用户输入——分三步串起来做，前一阶段的
     * 产物里就带着用户值，后一阶段的定位会把值里的 `?` / `:x` 当成占位二次填（P-2 的真实风险）。
     * 单次前向扫描里每种替换都是「原子整段产出」，产出不再被扫描，因此不发生二次误伤；替换区间
     * 仍记账在 [RuntimeSlotRender.protectedSpans] 里，便于核对。
     *
     * 零新语义保证：[slots] 与 [namedHints] 都为空时**直接走既有** [buildExecutableSql]
     * （逐字节一致，既有链路零风险）。
     *
     * @param sql        预览 SQL
     * @param slots      运行期占位表（[cn.song.mybatisplus.visualizer.model.PreviewResult.runtimeSlots]）
     * @param slotValues 占位值
     * @param sources    `?` 的参数来源（按出现顺序）
     * @param paramValues 参数值（`?` 的来源名与 `:name` 的名字共用这张表）
     * @param hints      与 [sources] 并行的 `?` 回填提示
     * @param namedHints `:name` 的引号提示；空表 = 不做命名参数回填
     * @param dialectName 当前方言名（布尔字面量判定）
     */
    fun buildExecutableSqlWithSlots(
        sql: String,
        slots: List<RuntimeSlot> = emptyList(),
        slotValues: RuntimeSlotValues = RuntimeSlotValues.EMPTY,
        sources: List<String> = emptyList(),
        paramValues: Map<String, String> = emptyMap(),
        hints: List<ParamHint> = emptyList(),
        namedHints: Map<String, ParamHint> = emptyMap(),
        dialectName: String? = null,
    ): RuntimeSlotRender {
        val hasNamed = namedHints.isNotEmpty() && paramValues.isNotEmpty()
        if (slots.isEmpty() && !hasNamed) {
            return RuntimeSlotRender(buildExecutableSql(sql, sources, paramValues, hints, dialectName = dialectName))
        }
        val context = RenderContext(dialectName)
        val warnings = mutableListOf<SlotWarning>()
        val applied = mutableListOf<String>()
        val spans = mutableListOf<IntRange>()
        // 长 token 优先，避免一个 token 是另一个前缀时错配
        val tokens = slots.filter { it.token.isNotEmpty() }.sortedByDescending { it.token.length }
        val namedMatcher = NAMED_PARAM_PATTERN.toPattern().matcher(sql)
        // 正则带 `(?<![::])` 前视断言：按位置窗口匹配时必须让断言看得到窗口外的前一个字符，
        // 否则 region 起点的 lookbehind 恒失败（`::` 双冒号守卫会连正常 `:name` 一起挡掉）
        namedMatcher.useTransparentBounds(true)
        val out = StringBuilder(sql.length + 64)
        var paramIndex = 0
        var inString = false
        var i = 0
        while (i < sql.length) {
            // 1) 运行期占位优先：它在字符串字面量里也要认（`'%/* 运行时值: keyword · rt2 */%'`）
            val slot = tokens.firstOrNull { sql.startsWith(it.token, i) }
            if (slot != null) {
                val value = slotValues[slot.id]
                if (value == null || value.isUnset) {
                    out.append(slot.token) // 留空 = 原样保留（D-5）
                } else {
                    val filled = renderSlot(slot, value, inString, context)
                    if (filled.text == null) {
                        warnings += SlotWarning(slot.id, filled.error ?: "无法回填")
                        out.append(slot.token)
                    } else {
                        filled.notice?.let { warnings += SlotWarning(slot.id, it) }
                        val at = out.length
                        out.append(filled.text)
                        if (filled.text.isNotEmpty()) spans += at until (at + filled.text.length)
                        if (slot.id !in applied) applied += slot.id
                    }
                }
                i += slot.token.length
                continue
            }
            val c = sql[i]
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < sql.length && sql[i + 1] == '\'') {
                        out.append("''")
                        i += 2
                        continue
                    }
                    inString = false
                }
                out.append(c)
                i++
                continue
            }
            when {
                c == '\'' -> {
                    inString = true
                    out.append(c)
                    i++
                }
                c == '?' -> {
                    val source = sources.getOrNull(paramIndex)
                    val rendered = renderValue(source?.let { paramValues[it] }, hints.getOrNull(paramIndex), context.dialectName)
                    paramIndex++
                    out.append(rendered)
                    i++
                }
                c == ':' && (i == 0 || sql[i - 1] != ':') && hasNamed -> {
                    namedMatcher.region(i, sql.length)
                    if (namedMatcher.lookingAt()) {
                        val value = paramValues[namedMatcher.group(1)]
                        if (value.isNullOrBlank()) {
                            out.append(sql, i, namedMatcher.end())
                        } else {
                            out.append(renderNamedValue(value, namedHints[namedMatcher.group(1)], context.dialectName))
                        }
                        i = namedMatcher.end()
                    } else {
                        out.append(c)
                        i++
                    }
                }
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    var end = i
                    while (end < sql.length && sql[end] != '\n') end++
                    out.append(sql, i, end)
                    i = end
                }
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    var end = i + 2
                    while (end + 1 < sql.length && !(sql[end] == '*' && sql[end + 1] == '/')) end++
                    end = minOf(sql.length, end + 2)
                    out.append(sql, i, end)
                    i = end
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return RuntimeSlotRender(out.toString(), applied, warnings.distinct(), spans)
    }

    // ---- 内部：渲染 ----------------------------------------------------

    /** 渲染成败（[text] null = 未回填，[error] 说明原因；[notice] 是成功时也要说的风险）。 */
    private data class Filled(val text: String?, val error: String? = null, val notice: String? = null)

    /** 一次替换编辑（[sql] 原坐标；[deletion] 用于收敛相邻空白）。 */
    private data class Edit(val start: Int, val end: Int, val text: String, val deletion: Boolean)

    /** 渲染上下文（方言相关口径）。 */
    private data class RenderContext(val dialectName: String?) {
        val supportsBooleanLiteral: Boolean
            get() = dialectName?.let { name -> NO_BOOLEAN_LITERAL_DIALECTS.none { name.startsWith(it) } } ?: true
    }

    /** 无布尔字面量的方言（P-7：Oracle / 达梦不造 `TRUE`）。 */
    private val NO_BOOLEAN_LITERAL_DIALECTS = listOf("Oracle", "DM")

    private fun renderSlot(
        slot: RuntimeSlot,
        value: SlotValue,
        inQuoted: Boolean,
        context: RenderContext,
    ): Filled = when (slot.kind) {
        SlotKind.VALUE -> fillValueSlot(slot, value, inQuoted, context)
        SlotKind.LOOP -> fillLoopSlot(slot, value, inQuoted, context)
        SlotKind.TERNARY -> fillTernarySlot(slot, value, inQuoted, context)
        SlotKind.OPTIONAL -> fillOptionalSlot(slot, value, inQuoted, context)
    }

    private fun fillValueSlot(
        slot: RuntimeSlot,
        value: SlotValue,
        inQuoted: Boolean,
        context: RenderContext,
    ): Filled {
        val isList = value.type == ValueType.LIST || slot.hint.inList
        if (!isList) {
            if (value.type != ValueType.NULL && value.text.isBlank()) {
                return Filled(null, "选了值类型但没有填值，已保留占位原文")
            }
            return literal(value.text, value.type, slot.hint.quote, inQuoted, context)
        }
        if (value.type == ValueType.LIST && !slot.hint.inList) {
            return Filled(null, "列表值只能用于 IN (...) 上下文；当前占位不是 IN 列表，已保留占位原文")
        }
        if (inQuoted) return Filled(null, "占位落在字符串字面量内，列表值无法直接填入")
        val items = RuntimeSlotValues.splitList(value.text)
        if (items.isEmpty()) return Filled(null, "列表占位没有值，已保留占位原文")
        if (items.size > RuntimeSlotValues.MAX_LOOP_ITEMS) {
            return Filled(null, "列表值过多（${items.size} 项，上限 ${RuntimeSlotValues.MAX_LOOP_ITEMS}）")
        }
        val itemType = if (value.type == ValueType.LIST) ValueType.AUTO else value.type
        val parts = items.map { literal(it, itemType, slot.hint.quote, false, context) }
        parts.firstOrNull { it.text == null }?.let { return Filled(null, it.error) }
        return Filled(parts.joinToString(", ") { it.text!! })
    }

    private fun fillLoopSlot(
        slot: RuntimeSlot,
        value: SlotValue,
        inQuoted: Boolean,
        context: RenderContext,
    ): Filled {
        val items = RuntimeSlotValues.splitList(value.text)
        if (items.isEmpty()) return Filled(null, "循环占位没有值，已保留占位原文")
        if (items.size > RuntimeSlotValues.MAX_LOOP_ITEMS) {
            return Filled(null, "循环值过多（${items.size} 项，上限 ${RuntimeSlotValues.MAX_LOOP_ITEMS}）")
        }
        if (inQuoted) return Filled(null, "占位落在字符串字面量内，循环片段无法直接填入")
        val first = slot.firstTemplate
        val rest = slot.restTemplate
        if (first == null || rest == null) {
            if (slot.hint.inList) {
                // IN 列表的连接符由 SQL 语法决定（不是猜），可以安全展开
                val parts = items.map { literal(it, value.type, slot.hint.quote, false, context) }
                parts.firstOrNull { it.text == null }?.let { return Filled(null, it.error) }
                return Filled(parts.joinToString(", ") { it.text!! })
            }
            return Filled(null, "循环展开形态未知（源码里没取到模板），拒绝按猜测的形态拼接")
        }
        val sb = StringBuilder()
        items.forEachIndexed { index, item ->
            val filled = fillTemplate(if (index == 0) first else rest, item, value.type, context, "第 ${index + 1} 项")
            if (filled.text == null) return Filled(null, filled.error)
            sb.append(filled.text)
        }
        return Filled(sb.toString())
    }

    private fun fillTernarySlot(
        slot: RuntimeSlot,
        value: SlotValue,
        inQuoted: Boolean,
        context: RenderContext,
    ): Filled {
        val branch = value.branch ?: return Filled(null, "三元占位还没选分支，已保留占位原文")
        if (branch != 0 && branch != 1) return Filled(null, "三元分支只能是 0（真）或 1（假）")
        val template = (if (branch == 0) slot.firstTemplate else slot.restTemplate)
            ?: return Filled(null, "该分支的展开形态未知（源码里没取到模板）")
        if (template.isEmpty()) return Filled("")
        if (inQuoted) return Filled(null, "占位落在字符串字面量内，分支片段无法直接填入")
        return fillTemplate(template, value.text, value.type, context, "三元值")
    }

    private fun fillOptionalSlot(
        slot: RuntimeSlot,
        value: SlotValue,
        inQuoted: Boolean,
        context: RenderContext,
    ): Filled {
        val template = slot.firstTemplate
        if (!value.include) {
            val notice = when {
                // 多值片段回退形态（template=null）：token 只是值的位置，取消包含只删当前值
                template == null -> "该片段含多个运行期值，取消「包含这段」只移除当前值，静态片段会保留"
                value.text.isNotBlank() -> "已取消「包含这段」，填的值被忽略"
                else -> null
            }
            return Filled("", notice = notice)
        }
        if (template == null) {
            // 值位置模式（T-RuntimeSlotOptionalFill R2 的多值回退）：token 只是值的位置，
            // 就地填值、不展开整段片段——整段模板会把 token 外的静态前缀重复一遍。
            if (value.text.isBlank() && value.type != ValueType.NULL) {
                return Filled(null, "选了值类型但没有填值，已保留占位原文")
            }
            return literal(value.text, value.type, slot.hint.quote, inQuoted, context)
        }
        if (inQuoted) return Filled(null, "占位落在字符串字面量内，片段无法直接填入")
        if (countMarks(template) == 0) return Filled(template)
        return fillTemplate(template, value.text, value.type, context, "片段值")
    }

    /**
     * 把单个值填进一段模板。
     *
     * **`%s` 计数校验**（券 A 报告第①条坑）：模板里的 `%s` 是字面标记，用户源码本身也可能含
     * `%s`（`LIKE '%s%'` 之类）。个数不等于 1 就**拒绝展开并提示**，绝不静默少填/多填。
     * 0 个 = 片段是纯字面量，原样使用；>1 个 = 无法判定哪个该填，拒绝。
     */
    private fun fillTemplate(
        template: String,
        rawValue: String,
        type: ValueType,
        context: RenderContext,
        label: String,
    ): Filled {
        val marks = countMarks(template)
        if (marks > 1) {
            return Filled(
                null,
                "模板里有 $marks 个 ${RuntimeSlot.VALUE_MARK}，与预期 1 个值不符（源码本身可能含 " +
                    "${RuntimeSlot.VALUE_MARK}），拒绝展开",
            )
        }
        if (marks == 0) return Filled(template)
        if (rawValue.isBlank()) return Filled(null, "$label 为空，而模板需要一个值")
        val markAt = template.indexOf(RuntimeSlot.VALUE_MARK)
        val mask = stringMask(template)
        val filled = literal(rawValue, type, null, markAt < mask.size && mask[markAt], context)
        if (filled.text == null) return Filled(null, "$label：${filled.error}")
        val text = template.substring(0, markAt) + filled.text +
            template.substring(markAt + RuntimeSlot.VALUE_MARK.length)
        return Filled(text, notice = filled.notice)
    }

    /**
     * 单个字面量渲染（**绝不拼裸文本**）。
     *
     * [inQuoted] = 这个位置已经在字符串字面量里：只做转义（`'` → `''`），不再补外层引号——
     * 外层引号来自用户自己的 SQL（`'%/* 运行时值: keyword */%'`）。
     */
    private fun literal(
        raw: String,
        type: ValueType,
        quoteHint: Boolean?,
        inQuoted: Boolean,
        context: RenderContext,
    ): Filled {
        val trimmed = raw.trim()
        val numeric = NUMBER_REGEX.matches(trimmed)
        val effective = when (type) {
            ValueType.AUTO -> if (quoteHint == true || !numeric) ValueType.STRING else ValueType.NUMBER
            else -> type
        }
        val notice = if (type == ValueType.AUTO && quoteHint == false && !numeric) {
            "上下文看起来是数值列，但输入「$trimmed」不是数字，已按字符串渲染"
        } else {
            null
        }
        val escaped = trimmed.replace("'", "''")
        return when (effective) {
            ValueType.STRING, ValueType.LIST, ValueType.AUTO ->
                Filled(if (inQuoted) escaped else "'$escaped'", notice = notice)
            ValueType.NUMBER -> if (numeric) {
                Filled(trimmed, notice = notice)
            } else {
                Filled(
                    if (inQuoted) escaped else "'$escaped'",
                    notice = "「数字」类型收到非数字输入「$trimmed」，已按字符串渲染",
                )
            }
            ValueType.NULL -> if (inQuoted) {
                Filled(null, "占位落在字符串字面量内，NULL 不能填进去")
            } else {
                Filled("NULL", notice = "填了 NULL：`= NULL` 恒不成立，判空请改用 IS NULL")
            }
            ValueType.BOOLEAN -> {
                val text = booleanLiteral(trimmed)
                when {
                    text == null -> Filled(null, "布尔值只认 true/false 或 1/0（输入：「$trimmed」）")
                    inQuoted -> Filled(null, "占位落在字符串字面量内，布尔字面量不能直接填入")
                    !context.supportsBooleanLiteral ->
                        Filled(null, "当前方言（${context.dialectName}）没有布尔字面量，请把值改成 1/0")
                    else -> Filled(text)
                }
            }
        }
    }

    private fun booleanLiteral(trimmed: String): String? = when (trimmed.lowercase()) {
        "true" -> "TRUE"
        "false" -> "FALSE"
        "1" -> "1"
        "0" -> "0"
        else -> null
    }

    /** 模板里 `%s` 标记的个数（不重叠计数）。 */
    private fun countMarks(template: String): Int {
        val mark = RuntimeSlot.VALUE_MARK
        var count = 0
        var from = 0
        while (true) {
            val at = template.indexOf(mark, from)
            if (at < 0) return count
            count++
            from = at + mark.length
        }
    }

    /** [token] 在 [sql] 里的全部出现位置（token 自带唯一 id，正常只有一个）。 */
    private fun occurrencesOf(sql: String, token: String): List<Int> {
        if (token.isEmpty()) return emptyList()
        val result = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = sql.indexOf(token, from)
            if (at < 0) return result
            result += at
            from = at + token.length
        }
    }

    /**
     * 逐字符标记「是否落在字符串字面量内」（`''` 转义按一个字符处理）。
     *
     * 用于判断占位/模板标记的上下文：落在引号内 → 只做转义、不补引号。行注释与块注释内的
     * 字符不算字面量（占位 token 本身是块注释，判断它是否被引号包住靠的是**外层**引号状态）。
     */
    private fun stringMask(sql: String): BooleanArray {
        val mask = BooleanArray(sql.length)
        var inString = false
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (inString) {
                mask[i] = true
                if (c == '\'') {
                    if (i + 1 < sql.length && sql[i + 1] == '\'') {
                        mask[i + 1] = true
                        i += 2
                        continue
                    }
                    inString = false
                }
                i++
                continue
            }
            when {
                c == '\'' -> {
                    inString = true
                    mask[i] = true
                    i++
                }
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    while (i < sql.length && sql[i] != '\n') i++
                }
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                    i = minOf(sql.length, i + 2)
                }
                else -> i++
            }
        }
        return mask
    }
}
