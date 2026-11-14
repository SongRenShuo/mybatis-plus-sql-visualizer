package cn.song.mybatisplus.visualizer.config

/**
 * 设置导入/导出序列化（O-5 Phase A）。
 *
 * 将 [PluginSettings.State] 的子集序列化为 JSON 文件供团队分发；导入时解析 JSON 并恢复状态。
 * 手动 JSON 实现（无 kotlinx.serialization 依赖）——覆盖可覆盖项（方言/参数替换/表名映射/
 * 多租户/数据权限），不含全局项（界面语言/快捷键/更新通道）。
 *
 * 导出面是 State 的子集，故导入按「JSON 出现的键才覆盖、未出现的键保留现值」合并
 * （见 [importFromJson] 的 base 形参）——否则文件未承载的字段会被静默重置。
 *
 * 格式：扁平 JSON 对象（string/boolean/number/array）；版本号 [CURRENT_VERSION] 用于前向兼容。
 */
object SettingsIO {

    /** 当前序列化格式版本（前向兼容：导入时版本 > 当前则拒绝）。 */
    const val CURRENT_VERSION = 1

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    /**
     * 将状态序列化为 JSON 字符串（仅可覆盖项 + 日志相关配置）。
     *
     * @param state 要导出的状态
     * @return JSON 字符串（UTF-8，人类可读）
     */
    fun exportToJson(state: PluginSettings.State): String = buildString {
        appendLine("{")
        appendLine("  \"version\": $CURRENT_VERSION,")
        appendLine("  \"dialectAuto\": ${state.dialectAuto},")
        appendLine("  \"dialect\": ${jsonString(state.dialect)},")
        appendLine("  \"replaceParams\": ${state.replaceParams},")
        appendLine("  \"formatSql\": ${state.formatSql},")
        appendLine("  \"verticalPairAnnotation\": ${state.verticalPairAnnotation},")
        appendLine("  \"autoFoldEnabled\": ${state.autoFoldEnabled},")
        appendLine("  \"autoFoldFieldThreshold\": ${state.autoFoldFieldThreshold},")
        appendLine("  \"injectLogicDelete\": ${state.injectLogicDelete},")
        appendLine("  \"logicDeleteValue\": ${jsonString(state.logicDeleteValue)},")
        appendLine("  \"injectTenantLine\": ${state.injectTenantLine},")
        appendLine("  \"tenantColumnName\": ${jsonString(state.tenantColumnName)},")
        appendLine("  \"tenantColumnDefault\": ${jsonString(state.tenantColumnDefault)},")
        appendLine("  \"injectTenantOnInsert\": ${state.injectTenantOnInsert},")
        appendLine("  \"tablePrefix\": ${jsonString(state.tablePrefix)},")
        appendLine("  \"tableSuffix\": ${jsonString(state.tableSuffix)},")
        appendLine("  \"tableNameInference\": ${jsonString(state.tableNameInference)},")
        appendLine("  \"ignoredFields\": ${jsonString(state.ignoredFields)},")
        appendLine("  \"columnCalibrationEnabled\": ${state.columnCalibrationEnabled},")
        appendLine("  \"removeTablePrefix\": ${jsonString(state.removeTablePrefix)},")
        appendLine("  \"removeTableSuffix\": ${jsonString(state.removeTableSuffix)},")
        appendLine("  \"dataPermissionHint\": ${state.dataPermissionHint},")
        appendLine("  \"dataPermissionMockCondition\": ${jsonString(state.dataPermissionMockCondition)},")
        appendLine("  \"dynamicTableNameRules\": ${serializeRules(state.dynamicTableNameRules)}")
        appendLine("}")
    }

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------

    /**
     * 从 JSON 字符串解析状态。
     *
     * 合并语义（T-Fix-SettingsScope）：以 [base] 为底，仅覆盖 JSON 中**实际出现**的键；
     * JSON 未出现的键保留 [base] 的现值，未知字段忽略。
     *
     * 为什么必须以现值为底：[exportToJson] 只写 23 个可覆盖项，而 [PluginSettings.State]
     * 有 81 个 var 字段（O-58 起含 columnCalibrationEnabled）。若以新建出厂默认态为底，一次导入会把
     * 参数历史（paramHistoryJson）、增强捕获授权记忆、反馈身份、日志自动保存目录等**文件里根本没有的字段**静默重置为
     * 出厂默认——用户侧表现为不可见的数据丢失（本文件 KDoc 自称「不含全局项」，却又把
     * autoCheckUpdate/ignoredUpdateVersions 一并重置，自相矛盾）。
     *
     * @param json JSON 字符串
     * @param base 未出现在 JSON 中的键的取值来源（默认出厂默认态，供「只要文件值」的调用方使用）
     * @return 恢复后的 State（不含全局项如 language/shortcut，保持 base 现值）
     * @throws ImportException JSON 格式错误、版本不兼容，或 SQL 位形字段值级校验未通过
     */
    fun importFromJson(json: String, base: PluginSettings.State = PluginSettings.State()): PluginSettings.State {
        val map = parseJson(json)
        val version = (map["version"] as? Number)?.toInt()
            ?: throw ImportException("缺少 version 字段")
        if (version > CURRENT_VERSION) {
            throw ImportException("配置文件版本不匹配（文件版本 $version > 当前支持版本 $CURRENT_VERSION）")
        }
        // T-HiddenBugPerfScan 券 Y14：version<1（0/-1 手改坏文件）曾照常导入
        if (version < 1) {
            throw ImportException("配置文件版本不合法（version=$version，应为 >= 1）")
        }
        // T-SAF-08（settings-import.sql-fragment-unvalidated）：SQL 位形字段值级校验——
        // 任一字段被拒时整体拒绝导入并逐字段列出原因（不静默丢弃）。共享设置文件/项目 XML
        // 曾可把任意 SQL 文本塞进 WHERE 字面量/标识符/表名位置（渲染层按设计直通）。
        val violations = buildList {
            map.getString("logicDeleteValue")?.let { SettingsValueValidator.checkLogicDeleteValue(it)?.let(::add) }
            map.getString("tenantColumnName")?.let { SettingsValueValidator.checkTenantColumnName(it)?.let(::add) }
            map.getString("tenantColumnDefault")?.let { SettingsValueValidator.checkTenantColumnDefault(it)?.let(::add) }
            map.getString("tablePrefix")?.let { SettingsValueValidator.checkTablePrefix(it)?.let(::add) }
            map.getString("tableSuffix")?.let { SettingsValueValidator.checkTableSuffix(it)?.let(::add) }
            map.getString("dataPermissionMockCondition")?.let {
                SettingsValueValidator.checkDataPermissionMockCondition(it)?.let(::add)
            }
            map.getArray("dynamicTableNameRules")?.let { rules ->
                deserializeRules(rules).forEach { rule ->
                    SettingsValueValidator.checkTableNameRulePattern(rule)?.let(::add)
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw ImportException(
                "导入已拒绝：以下设置字段的值未通过 SQL 位形安全校验（共享设置文件不得改动语句语义）：\n" +
                    violations.joinToString("\n") { "- ${it.field}: ${it.reason}" },
            )
        }
        // copy() 而非直接改写 base：调用方传入的多为活状态（instance.state.copy()），
        // 就地改写会污染它
        val state = base.copy()
        // 版本 <= CURRENT_VERSION：逐字段读取，缺失则保留 base 现值
        map.getBoolean("dialectAuto")?.let { state.dialectAuto = it }
        map.getString("dialect")?.let { state.dialect = it }
        map.getBoolean("replaceParams")?.let { state.replaceParams = it }
        map.getBoolean("formatSql")?.let { state.formatSql = it }
        map.getBoolean("verticalPairAnnotation")?.let { state.verticalPairAnnotation = it }
        map.getBoolean("autoFoldEnabled")?.let { state.autoFoldEnabled = it }
        map.getInt("autoFoldFieldThreshold")?.let { state.autoFoldFieldThreshold = it }
        map.getBoolean("injectLogicDelete")?.let { state.injectLogicDelete = it }
        map.getString("logicDeleteValue")?.let { state.logicDeleteValue = it }
        map.getBoolean("injectTenantLine")?.let { state.injectTenantLine = it }
        map.getString("tenantColumnName")?.let { state.tenantColumnName = it }
        map.getString("tenantColumnDefault")?.let { state.tenantColumnDefault = it }
        map.getBoolean("injectTenantOnInsert")?.let { state.injectTenantOnInsert = it }
        map.getString("tablePrefix")?.let { state.tablePrefix = it }
        map.getString("tableSuffix")?.let { state.tableSuffix = it }
        map.getString("tableNameInference")?.let { state.tableNameInference = it }
        map.getString("ignoredFields")?.let { state.ignoredFields = it }
        map.getBoolean("columnCalibrationEnabled")?.let { state.columnCalibrationEnabled = it }
        map.getString("removeTablePrefix")?.let { state.removeTablePrefix = it }
        map.getString("removeTableSuffix")?.let { state.removeTableSuffix = it }
        map.getBoolean("dataPermissionHint")?.let { state.dataPermissionHint = it }
        map.getString("dataPermissionMockCondition")?.let { state.dataPermissionMockCondition = it }
        map.getArray("dynamicTableNameRules")?.let { state.dynamicTableNameRules = deserializeRules(it) }
        return state
    }

    /** 导入异常（格式错误/版本不兼容）。 */
    class ImportException(message: String) : Exception(message)

    // ------------------------------------------------------------------
    // 序列化辅助
    // ------------------------------------------------------------------

    /** JSON 字符串值转义（转义引号/反斜杠/控制字符）。 */
    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u${c.code.toString(16).padStart(4, '0')}")
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** 序列化动态表名规则为 JSON 数组。 */
    private fun serializeRules(rules: List<TableNameRule>): String {
        if (rules.isEmpty()) return "[]"
        return buildString {
            append("[")
            rules.forEachIndexed { i, rule ->
                if (i > 0) append(", ")
                append("{")
                append("\"pattern\": ${jsonString(rule.pattern)}, ")
                append("\"replacement\": ${jsonString(rule.replacement)}, ")
                append("\"enabled\": ${rule.enabled}")
                append("}")
            }
            append("]")
        }
    }

    // ------------------------------------------------------------------
    // JSON 解析（轻量递归下降）
    // ------------------------------------------------------------------

    /**
     * 解析 JSON 对象字符串为 Map（仅支持 object/array/string/number/boolean/null）。
     */
    private fun parseJson(json: String): Map<String, Any?> {
        val parser = JsonParser(json)
        val result = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) {
            throw ImportException("JSON 解析错误：末尾有多余字符")
        }
        @Suppress("UNCHECKED_CAST")
        return result as? Map<String, Any?>
            ?: throw ImportException("JSON 顶层应为对象")
    }

    /** 简易 JSON 解析器（递归下降）。 */
    private class JsonParser(private val input: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWhitespace()
            return when {
                atEnd() -> throw ImportException("JSON 意外结束")
                peek() == '{' -> parseObject()
                peek() == '[' -> parseArray()
                peek() == '"' -> parseString()
                peek() == 't' || peek() == 'f' -> parseBoolean()
                peek() == 'n' -> parseNull()
                peek().isDigit() || peek() == '-' -> parseNumber()
                else -> throw ImportException("JSON 解析错误：位置 $pos 处意外字符 '${peek()}'")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val map = linkedMapOf<String, Any?>()
            expect('{')
            skipWhitespace()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                when {
                    peek() == ',' -> { pos++; continue }
                    peek() == '}' -> { pos++; return map }
                    else -> throw ImportException("JSON 解析错误：对象中期望 ',' 或 '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val list = mutableListOf<Any?>()
            expect('[')
            skipWhitespace()
            if (peek() == ']') { pos++; return list }
            while (true) {
                skipWhitespace()
                list += parseValue()
                skipWhitespace()
                when {
                    peek() == ',' -> { pos++; continue }
                    peek() == ']' -> { pos++; return list }
                    else -> throw ImportException("JSON 解析错误：数组中期望 ',' 或 ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (!atEnd()) {
                val c = peek()
                pos++
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw ImportException("JSON 字符串转义不完整")
                        val esc = peek()
                        pos++
                        sb.append(
                            when (esc) {
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'u' -> {
                                    if (pos + 4 > input.length) throw ImportException("JSON unicode 转义不完整")
                                    val hex = input.substring(pos, pos + 4)
                                    pos += 4
                                    hex.toInt(16).toChar()
                                }
                                else -> throw ImportException("JSON 未知转义序列 '\\$esc'")
                            }
                        )
                    }
                    else -> sb.append(c)
                }
            }
            throw ImportException("JSON 字符串未闭合")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd() && peek().isDigit()) pos++
            val isFloat = if (!atEnd() && peek() == '.') {
                pos++
                while (!atEnd() && peek().isDigit()) pos++
                true
            } else false
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                pos++
                if (!atEnd() && (peek() == '+' || peek() == '-')) pos++
                while (!atEnd() && peek().isDigit()) pos++
            }
            val text = input.substring(start, pos)
            // T-HiddenBugPerfScan 券 Y14：病态数字（"1."、"-"、"1e"）曾抛裸
            // NumberFormatException——契约是 ImportException（parseBoolean/parseNull
            // 同文件其余分支均抛 ImportException，唯数字路径不一致）
            return if (isFloat || text.contains('e', ignoreCase = true)) {
                text.toDoubleOrNull() ?: throw ImportException("非法数字字面量：$text")
            } else {
                text.toLongOrNull() ?: text.toDoubleOrNull()
                    ?: throw ImportException("非法数字字面量：$text")
            }
        }

        private fun parseBoolean(): Boolean {
            if (input.startsWith("true", pos)) { pos += 4; return true }
            if (input.startsWith("false", pos)) { pos += 5; return false }
            throw ImportException("JSON 解析错误：位置 $pos 处期望 boolean")
        }

        private fun parseNull() {
            if (input.startsWith("null", pos)) { pos += 4; return }
            throw ImportException("JSON 解析错误：位置 $pos 处期望 null")
        }

        fun skipWhitespace() {
            while (!atEnd() && peek().isWhitespace()) pos++
        }

        private fun expect(c: Char) {
            if (atEnd() || peek() != c) {
                throw ImportException("JSON 解析错误：位置 $pos 处期望 '$c'")
            }
            pos++
        }

        private fun peek(): Char = input[pos]
        fun atEnd(): Boolean = pos >= input.length
    }

    // ------------------------------------------------------------------
    // Map 扩展辅助（类型安全取值）
    // ------------------------------------------------------------------

    private fun Map<String, Any?>.getString(key: String): String? = this[key] as? String
    private fun Map<String, Any?>.getBoolean(key: String): Boolean? = this[key] as? Boolean
    private fun Map<String, Any?>.getInt(key: String): Int? = (this[key] as? Number)?.toInt()
    private fun Map<String, Any?>.getArray(key: String): List<Any?>? = this[key] as? List<*>

    /** 解析动态表名规则数组。 */
    private fun deserializeRules(list: List<Any?>): List<TableNameRule> {
        return list.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val map = item as? Map<String, Any?> ?: return@mapNotNull null
            val pattern = map["pattern"] as? String ?: return@mapNotNull null
            val replacement = map["replacement"] as? String ?: return@mapNotNull null
            val enabled = map["enabled"] as? Boolean ?: true
            TableNameRule(pattern, replacement, enabled)
        }
    }
}
