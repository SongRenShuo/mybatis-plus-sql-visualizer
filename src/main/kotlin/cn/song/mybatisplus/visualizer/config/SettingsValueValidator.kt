package cn.song.mybatisplus.visualizer.config

/**
 * SQL 位形设置值校验器（T-SAF-08，settings-import.sql-fragment-unvalidated）。
 *
 * 背景与信任边界：设置到 SQL 的边界只有键级白名单（SettingsIO 导入合并、项目级
 * `.idea/mp-sql-visualizer-project.xml` 覆盖通道），值曾无任何内容校验，而渲染层
 * （SqlGenerator）按设计直通——SqlValue.Literal.raw / Condition.Raw.sqlText 原样渲染进
 * 语句文本。因此团队共享的设置文件或 VCS 共享的项目 XML 可以把任意 SQL 文本放进
 * WHERE 字面量、标识符与表名位置，翻转语句语义（如 `'0 OR 1=1'` 借 AND/OR 优先级把
 * 逻辑删除守卫翻成恒真）。
 *
 * 本校验器在**单一实现源**上服务两条低信任读取路径：
 *  1. [SettingsIO.importFromJson]（设置文件导入）——校验失败的字段整体拒绝导入，
 *     并在异常消息中**逐字段**列出拒绝原因（不静默丢弃）；
 *  2. [ProjectPluginSettings.effectiveLogicDeleteValue]/[effectiveTablePrefix]/
 *     [effectiveTableSuffix]（项目级覆盖读取）——项目值未通过校验时 warn 回退全局值
 *     （覆盖被拒绝），合法值照常生效。
 *
 * 校验口径（fail-closed，宁可误伤奇异但无害的值）：
 *  - 标识符位（tenantColumnName/tablePrefix/tableSuffix）：`^[A-Za-z_][A-Za-z0-9_$]*$`
 *    形状白名单；tablePrefix/tableSuffix 允许空串（= 无前缀/后缀的安全语义，出厂默认
 *    导出必须可往返）；tenantColumnName 必须非空且完整匹配。
 *  - 单字面量位（logicDeleteValue/tenantColumnDefault）：拒绝 SQL 注释序列（双连字符
 *    `--` 与斜杠星注释起结）、分号与裸 OR/AND 连接词（引号外的部分扫描，引号内文本
 *    视为字面量的一部分）。
 *  - dataPermissionMockCondition：长度上限 + 注释/分号拒绝（该字段追加为原始 WHERE 片段）。
 *  - dynamicTableNameRules pattern sanity：可编译 + 长度上限（运行时另有 BoundedRegex
 *    步数预算兜底，T-SAF-25）。
 */
object SettingsValueValidator {

    /** 标识符形状白名单。 */
    private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_$]*$")

    /** 裸 OR/AND 连接词（对去引号后的文本做词边界扫描）。 */
    private val OR_OR_AND = Regex("""\b(?:OR|AND)\b""", RegexOption.IGNORE_CASE)

    /** dataPermissionMockCondition 长度上限（原始 WHERE 片段，无需更长）。 */
    const val MAX_MOCK_CONDITION_LENGTH = 512

    /** 动态表名规则 pattern/replacement 长度上限。 */
    const val MAX_RULE_PATTERN_LENGTH = 256

    /** 单字段校验结论：field=被拒字段名，reason=拒绝原因（进导入报告 / warn 日志）。 */
    data class Violation(val field: String, val reason: String)

    /** logicDeleteValue：单字面量。 */
    fun checkLogicDeleteValue(value: String): Violation? = checkLiteralValue("logicDeleteValue", value)

    /** tenantColumnDefault：单字面量。 */
    fun checkTenantColumnDefault(value: String): Violation? = checkLiteralValue("tenantColumnDefault", value)

    /** tenantColumnName：非空标识符形状。 */
    fun checkTenantColumnName(value: String): Violation? =
        checkIdentifier("tenantColumnName", value, allowBlank = false)

    /** tablePrefix：标识符形状（空串放行 = 无前缀）。 */
    fun checkTablePrefix(value: String): Violation? =
        checkIdentifier("tablePrefix", value, allowBlank = true)

    /** tableSuffix：标识符形状（空串放行 = 无后缀）。 */
    fun checkTableSuffix(value: String): Violation? =
        checkIdentifier("tableSuffix", value, allowBlank = true)

    /** dataPermissionMockCondition：长度上限 + 注释/分号拒绝。 */
    fun checkDataPermissionMockCondition(value: String): Violation? {
        if (value.length > MAX_MOCK_CONDITION_LENGTH) {
            return Violation(
                "dataPermissionMockCondition",
                "长度 ${value.length} 超上限 $MAX_MOCK_CONDITION_LENGTH",
            )
        }
        return rejectCommentOrSemicolon("dataPermissionMockCondition", value)
    }

    /** 动态表名规则 pattern sanity：长度上限 + 可编译（规则关闭时跳过）。 */
    fun checkTableNameRulePattern(rule: TableNameRule): Violation? {
        if (!rule.enabled || rule.pattern.isBlank()) return null
        if (rule.pattern.length > MAX_RULE_PATTERN_LENGTH) {
            return Violation(
                "dynamicTableNameRules",
                "pattern 长度 ${rule.pattern.length} 超上限 $MAX_RULE_PATTERN_LENGTH：${abbreviate(rule.pattern)}",
            )
        }
        runCatching { Regex(rule.pattern) }.getOrElse { e ->
            return Violation("dynamicTableNameRules", "pattern 不可编译：${abbreviate(rule.pattern)}（${e.message}）")
        }
        return null
    }

    // ------------------------------------------------------------------

    private fun checkIdentifier(field: String, value: String, allowBlank: Boolean): Violation? {
        if (value.isEmpty()) {
            return if (allowBlank) null else Violation(field, "不允许为空（需为合法标识符）")
        }
        if (!IDENTIFIER.matches(value)) {
            return Violation(field, "非标识符形状（白名单 ^[A-Za-z_][A-Za-z0-9_$]*\$）：${abbreviate(value)}")
        }
        return null
    }

    private fun checkLiteralValue(field: String, value: String): Violation? {
        rejectCommentOrSemicolon(field, value)?.let { return it }
        // 裸 OR/AND 连接词扫描：按单引号分段（奇数段在引号内，属字面量文本），只扫引号外
        val outsideQuotes = value.split('\'').filterIndexed { i, _ -> i % 2 == 0 }.joinToString(" ")
        if (OR_OR_AND.containsMatchIn(outsideQuotes)) {
            return Violation(field, "含裸 OR/AND 连接词（须为单字面量）：${abbreviate(value)}")
        }
        return null
    }

    private fun rejectCommentOrSemicolon(field: String, value: String): Violation? {
        val offender = when {
            value.contains("--") -> "--"
            value.contains("/*") -> "/*"
            value.contains("*/") -> "*/"
            value.contains(';') -> ";"
            else -> null
        } ?: return null
        return Violation(field, "含 SQL 注释/分号（$offender）：${abbreviate(value)}")
    }

    private fun abbreviate(value: String, max: Int = 64): String =
        if (value.length <= max) value else value.take(max) + "…"
}
