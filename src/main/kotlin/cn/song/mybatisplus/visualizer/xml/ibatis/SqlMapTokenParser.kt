package cn.song.mybatisplus.visualizer.xml.ibatis

import java.util.StringTokenizer

/**
 * `#` / `$` 两套占位符的解析器——逐行复刻 iBatis 2.3.4.726 的
 * `InlineParameterMapParser`（`#`）与 `SimpleDynamicSql`（`$`）。
 *
 * 必须照抄的坑（全部来自 jar 内源码实测）：
 * 1. **分派**：token 含 `:` → 简单语法；否则高级语法（`InlineParameterMapParser:47-51`）；
 * 2. **简单语法按 token 数分档**（`oldParseMapping`）：3 → `name:jdbcType`；≥5 →
 *    `name:jdbcType:nullValue`（多余 token 拼回 nullValue）；**其他一律报错**——
 *    所以 `#name:nullValue#` 的第二段永远是 `jdbcType`，**不是** nullValue；
 * 3. **高级语法** `#p,javaType=?,jdbcType=?,mode=?,nullValue=?,handler=?,numericScale=?#`
 *    （`newParseMapping`）：`StringTokenizer(token, "=,", false)`，首 token 是 propertyName，
 *    其后成对 field/value；落单的 field 与未知 field 都报错；
 * 4. **转义**：`##` → 字面量 `#`；`$$` → 字面量 `$`；未闭合的定界符在引擎里抛异常，
 *    本链按券 B 的降级口径**原文保留 + 告警**（见 [InlineParseOutcome]）；
 * 5. `$x$` 的取值：参数是标量（`hasTypeHandler` 命中）时取**整个对象**，否则按属性名 probe；
 *    **值为 null 时静默丢弃**（什么都不输出）。
 *
 * token 化一律走 [StringTokenizer]（单字符定界符、保留定界符、不产生空 token）——
 * 手写扫描器与它在合法输入上等价，但直接用 JDK 类可以省掉一类「换行/连续定界符」的边界争议。
 */
class SqlMapTokenParser {

    /** `#...#` 解析产物（含降级告警）。 */
    data class InlineParseOutcome(
        val text: String,
        val mappings: List<IbatisParamMapping>,
        val warnings: List<String>,
    )

    /** `$...$` 替换产物。 */
    data class DollarOutcome(
        val text: String,
        /** `$x$` 站点数（含取值为 null、被静默丢弃的站点）。 */
        val siteCount: Int,
        /** 实际写入 SQL 的取值（注入闸门用）。 */
        val renderedValues: List<String>,
        val warnings: List<String>,
    )

    // ------------------------------------------------------------------ # 分支

    /**
     * `#...#` → `?`，并**按出现顺序**记录参数名与 jdbcType
     * （引擎 `InlineParameterMapParser.parseInlineParameterMap`）。
     */
    fun parseInlineParameterMap(sqlStatement: String): InlineParseOutcome {
        val tokens = tokenize(sqlStatement, PARAMETER_TOKEN)
        val out = StringBuilder()
        val mappings = ArrayList<IbatisParamMapping>()
        val warnings = ArrayList<String>()
        var last: String? = null
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (last == PARAMETER_TOKEN) {
                if (token == PARAMETER_TOKEN) {
                    // `##` → 字面量 `#`
                    out.append(PARAMETER_TOKEN)
                    last = null
                    index++
                    continue
                }
                val closed = index + 1 < tokens.size && tokens[index + 1] == PARAMETER_TOKEN
                if (closed) {
                    val mapping = parseMapping(token, warnings)
                    if (mapping != null) {
                        mappings.add(mapping)
                        out.append('?')
                    } else {
                        out.append('#').append(token).append('#')
                    }
                    index += 2
                } else {
                    warnings.add("未闭合的内联参数（原文保留）：#${token}")
                    out.append('#').append(token)
                    index++
                }
                last = null
                continue
            }
            if (token != PARAMETER_TOKEN) out.append(token)
            last = token
            index++
        }
        return InlineParseOutcome(out.toString(), mappings, warnings)
    }

    /** 分派：含 `:` 走简单语法，否则走高级语法。 */
    private fun parseMapping(token: String, warnings: MutableList<String>): IbatisParamMapping? =
        if (token.indexOf(PARAM_DELIM) > -1) oldParseMapping(token, warnings)
        else newParseMapping(token, warnings)

    /**
     * 高级语法：`#propertyName,javaType=string,jdbcType=VARCHAR,mode=IN,nullValue=N/A,#`
     * （无冒号时走这条——**注意 `handler=` 的取值在本链不实例化任何类**，只记名）。
     */
    private fun newParseMapping(token: String, warnings: MutableList<String>): IbatisParamMapping? {
        val fields = tokenize(token, "=,", returnDelims = false)
        if (fields.isEmpty()) {
            warnings.add("内联参数格式错误（没有属性名）：#$token#")
            return null
        }
        var propertyName: String? = null
        var javaType: String? = null
        var jdbcType: String? = null
        var mode: String? = null
        var nullValue: String? = null
        var numericScale: Int? = null
        var index = 0
        while (index < fields.size) {
            val field = fields[index]
            if (propertyName == null) {
                propertyName = field
                index++
                continue
            }
            if (index + 1 >= fields.size) {
                warnings.add("内联参数格式错误（name=value 不成对）：#$token#")
                return null
            }
            val value = fields[index + 1]
            when (field) {
                "javaType" -> javaType = value
                "jdbcType" -> jdbcType = value
                "mode" -> mode = value
                "nullValue" -> nullValue = value
                "numericScale" -> {
                    val scale = value.toIntOrNull()
                    if (scale == null || scale < 0) {
                        warnings.add("numericScale 不是有效的非负整数：#$token#")
                        return null
                    }
                    numericScale = scale
                }
                // handler= 只记名不实例化（引擎会 Resources.instantiate，静态渲染无此需要）
                "handler" -> Unit
                else -> {
                    warnings.add("未识别的内联参数字段 '$field'：#$token#")
                    return null
                }
            }
            index += 2
        }
        return IbatisParamMapping(
            propertyName = propertyName ?: return null,
            jdbcTypeName = jdbcType,
            nullValue = nullValue,
            javaTypeName = javaType,
            mode = mode,
            numericScale = numericScale,
        )
    }

    /**
     * 简单语法（含 `:`）：按 `StringTokenizer(token, ":", true)` 的 **token 数**分档。
     *
     * 3 → 两段；≥5 → 三段（多余的 token 逐个拼回 nullValue，故 nullValue 内可以再含 `:`）；
     * 其他 → 格式错误。**`#p:nullValue#` 在这里是 `jdbcType=nullValue`**。
     */
    private fun oldParseMapping(token: String, warnings: MutableList<String>): IbatisParamMapping? {
        val fields = tokenize(token, PARAM_DELIM)
        return when {
            fields.size == 3 -> IbatisParamMapping(propertyName = fields[0], jdbcTypeName = fields[2])
            fields.size >= 5 -> {
                val nullValue = StringBuilder(fields[4])
                var index = 5
                while (index < fields.size) {
                    nullValue.append(fields[index])
                    index++
                }
                IbatisParamMapping(
                    propertyName = fields[0],
                    jdbcTypeName = fields[2],
                    nullValue = nullValue.toString(),
                )
            }
            else -> {
                warnings.add("内联参数格式错误（冒号分段数必须是 3 或 >=5）：#$token#")
                null
            }
        }
    }

    // ------------------------------------------------------------------ $ 分支

    /**
     * `$x$` 直替（引擎 `SimpleDynamicSql.processDynamicElements`）。
     *
     * **永远在动态标签求值之后执行**（`DynamicSql.process` 末尾才 new `SimpleDynamicSql`），
     * 所以调用方只能在整篇 SQL 组装完成后调用本方法。
     */
    fun processDollarSubstitution(sql: String, params: Any?): DollarOutcome {
        val tokens = tokenize(sql, ELEMENT_TOKEN)
        val out = StringBuilder()
        val rendered = ArrayList<String>()
        val warnings = ArrayList<String>()
        var siteCount = 0
        var last: String? = null
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (last == ELEMENT_TOKEN) {
                if (token == ELEMENT_TOKEN) {
                    // `$$` → 字面量 `$`
                    out.append(ELEMENT_TOKEN)
                    last = null
                    index++
                    continue
                }
                val closed = index + 1 < tokens.size && tokens[index + 1] == ELEMENT_TOKEN
                if (!closed) {
                    warnings.add("未闭合的 \$ 动态元素（原文保留）：\$${token}")
                    out.append('$').append(token)
                    index++
                    last = null
                    continue
                }
                siteCount++
                val value = dollarValue(params, token)
                if (value != null) {
                    val text = value.toString()
                    out.append(text)
                    rendered.add(text)
                }
                index += 2
                last = null
                continue
            }
            if (token != ELEMENT_TOKEN) out.append(token)
            last = token
            index++
        }
        return DollarOutcome(out.toString(), siteCount, rendered, warnings)
    }

    /** `$x$` 取值：标量参数取整个对象（`hasTypeHandler` 分支），否则按属性名 probe。 */
    private fun dollarValue(params: Any?, name: String): Any? {
        if (params == null) return null
        if (IbatisProbe.hasTypeHandler(params.javaClass)) return params
        return IbatisProbe.getObject(params, name)
    }

    // ------------------------------------------------------------------ token 化

    /**
     * [StringTokenizer] 语义化的 token 列表（单字符定界符）：
     * [returnDelims] 为 true 时定界符各成一个 token；**不产生空 token**。
     * [delimiters] 为 false 时等价于 `StringTokenizer(s, delims, false)`（跳过定界符取词）。
     */
    private fun tokenize(text: String, delims: String, returnDelims: Boolean = true): List<String> {
        val result = ArrayList<String>()
        val parser = StringTokenizer(text, delims, returnDelims)
        while (parser.hasMoreTokens()) result.add(parser.nextToken())
        return result
    }

    private companion object {
        /** 引擎 `InlineParameterMapParser.PARAMETER_TOKEN`。 */
        const val PARAMETER_TOKEN = "#"

        /** 引擎 `InlineParameterMapParser.PARAM_DELIM`。 */
        const val PARAM_DELIM = ":"

        /** 引擎 `SimpleDynamicSql.ELEMENT_TOKEN`。 */
        const val ELEMENT_TOKEN = "$"
    }
}
