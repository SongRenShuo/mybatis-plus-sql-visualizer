package cn.song.mybatisplus.visualizer.xml

/**
 * 参数映射（T-M24-01）：`#{...}` token 解析产物，属性名顺序对应 SQL 中 `?` 顺序。
 *
 * @param property      参数属性名（foreach 内为重写后的 `__frch_item_0.name` 形态）
 * @param attributes    token 附加属性（jdbcType / typeHandler / javaType / mode / numericScale 等，首段无 `=` 者为 property）
 * @param originalToken token 原文（面板展示用）
 */
data class ParameterMapping(
    val property: String,
    val attributes: Map<String, String>,
    val originalToken: String,
) {
    companion object {
        /** 解析 token 内容（对齐 SqlSourceBuilder.buildParameterMapping 的简化形态）。 */
        fun parse(token: String): ParameterMapping {
            var property = ""
            val attributes = mutableMapOf<String, String>()
            token.split(',').forEach { segment ->
                val part = segment.trim()
                if (part.isEmpty()) return@forEach
                val eq = part.indexOf('=')
                if (eq > 0) {
                    val key = part.substring(0, eq).trim()
                    val value = part.substring(eq + 1).trim()
                    if (key.isNotEmpty()) attributes[key] = value
                } else if (property.isEmpty()) {
                    property = part
                }
            }
            return ParameterMapping(property, attributes, token)
        }
    }
}

/** 渲染产物：SQL（`#{}` 已替换为 `?`）+ 参数映射 + 错误提示 + bind 自动求值结果（T-M25.5）。 */
data class RenderedXmlSql(
    val sql: String,
    val mappings: List<ParameterMapping>,
    val errors: List<String>,
    /** bind 名 → 求值结果字符串（含面板覆盖值；供参数行自动回填显示）。 */
    val bindValues: Map<String, String> = emptyMap(),
    /**
     * `${}` 直替次数（T-DollarInjectMark）：默认 0，既有消费者无感——预览横幅据 >0 显示
     * 「直接替换未转义」提示。只加元数据不改渲染文本（防回归断言见任务测试）。
     */
    val dollarSubstitutions: Int = 0,
    /**
     * 条件求值结果（R30 探镜）：test 表达式原文 → 本轮判定真值；**只含本轮被走到的条件**——
     * 假 if 体内层 / choose 短路后的 when 不在表中（调用方按缺席映射「未求值」）。
     * 默认空表：直接构造的既有调用点与消费者无感。
     */
    val conditionResults: Map<String, Boolean> = emptyMap(),
)

/**
 * 渲染器（T-M24-01）：复刻 MyBatis 3.5.19 `DynamicSqlSource.getBoundSql` 的前两遍——
 *
 *  1. SqlNode 树 apply（分支判定 / `${}` 替换 / bind 累积，bindings 由调用方提供）；
 *  2. `#{}` → `?` + ParameterMapping 清单（[TokenParser] 等价 GenericTokenParser；
 *     `\#{` 转义；无 `}` 原样保留）。
 *
 * 第三遍（参数值填充 PreparedStatement）预览场景不需要——参数值由面板回填输入。
 */
class XmlSqlRenderer {

    fun render(
        node: SqlNode,
        bindings: Map<String, Any?>,
        conditionOverrides: Map<String, Boolean> = emptyMap(),
        protectedNames: Set<String> = emptySet(),
        staticFieldValues: Map<String, Any?> = emptyMap(),
        bindNames: Set<String> = emptySet(),
        /** T-MbgExamplePreview R1：`${}` 空值输出 `{{表达式}}` 占位（预览链开启，默认关）。 */
        emptyDollarPlaceholder: Boolean = false,
    ): RenderedXmlSql {
        val context = DynamicContext(bindings, conditionOverrides, protectedNames, staticFieldValues, emptyDollarPlaceholder)
        node.apply(context)
        // T-M25.5：渲染完成后提取 bind 求值结果（含覆盖值）——参数行自动回填显示
        val bindValues = bindNames.mapNotNull { name ->
            context.bindings[name]?.toString()?.let { name to it }
        }.toMap()
        val (sql, mappings) = parseParameterMappings(context.getSql(), context.errors)
        // R30 探镜：条件判定结果随渲染产物出栈（只登记被走到的条件，见 DynamicContext.conditionOutcomes）
        return RenderedXmlSql(sql, mappings, context.errors, bindValues, context.dollarSubstitutions, context.conditionOutcomes.toMap())
    }

    private fun parseParameterMappings(sql: String, errors: List<String>): Pair<String, List<ParameterMapping>> {
        val mappings = mutableListOf<ParameterMapping>()
        val rendered = TokenParser.parse(sql, "#{", "}") { token ->
            mappings += ParameterMapping.parse(token)
            "?"
        }
        return rendered to mappings
    }
}
