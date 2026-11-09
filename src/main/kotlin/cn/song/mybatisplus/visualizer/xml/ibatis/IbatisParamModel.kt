package cn.song.mybatisplus.visualizer.xml.ibatis

import cn.song.mybatisplus.visualizer.model.PreviewRerenderInput

/**
 * iBatis 预览的参数/条件行清单（T-Ibatis2Gap 券 A）：`IbatisStatement` → 面板可交互行 + Map 装配。
 *
 * ## 为什么 Map 装配就够（本券的钥匙）
 *
 * `IbatisProbe` 原生支持 `Map` 参数：`parameterObject is Map` 时取值走 `map[name]`、
 * 可用性走 `containsKey`。因此**条件开关 = Map 里放不放这个 key**（`<isNotNull property="id">`
 * 在 map 含非 null `id` 时成立、不含时不成立），**值语义 = value 的类型与内容**。
 * 渲染入口不变：`SqlMapSqlRenderer.render(statement, params)`——渲染器零改动。
 *
 * ## 装配语义（拍板 D-1「条件勾选 + 值可填」）
 *
 * - **勾选 = 放 key**（值为用户输入，缺省回落 `compareValue`），**取消 = 移除 key**；
 * - 占位符行（`#x#`）：填了值才放 key；与条件行同名的 key 由**条件行的开关主导**
 *   （勾上 `isNotNull(id)` 时 `#id#` 的值进 map，取消时整段跳过、key 不放）；
 * - `$x$` 行：填了值才放 key（直替照常触发既有注入闸门，**不得绕过确认**）；
 * - `<iterate>`：值列表非空才放 key（`List<String>`），按列表长度由渲染器生成 `ids[0..n-1]`；
 *   留空 = key 不放 = 引擎语义「取值不是集合」→ 渲染降级（与无参基线现状一致）；
 * - **空串值对 `isNotEmpty` 天然为 false 是引擎语义，不做「帮用户转 null」的伪装**；
 * - 装配结果为空 map 时回退 `null`（无参基线，与现状逐字节一致）——唯一例外是勾选了
 *   `<isParameterPresent>`：它判 `params != null`，此时传空 map 才能让条件成立。
 */
object IbatisParamModelBuilder {

    // ------------------------------------------------------------------
    // 行清单
    // ------------------------------------------------------------------

    /**
     * 从语句体收集面板行（树序 DFS）。
     *
     * - 占位符行：非 postParse 文本节点的配置期解析产物（`#x#` / `#x:TYPE#` / `#x:TYPE:null#` /
     *   `#x,jdbcType=?#`）+ 声明 `parameterMap` 的语句参数（按声明序）；
     * - 条件行：全部 `isXxx` 标签（含嵌套在 `<dynamic>`/条件内的）；**iterate 子树内的条件不建行**
     *   （它们的求值随迭代逐轮走带下标的属性路径，开关语义不独立）；
     * - iterate 行：每个 `<iterate>`（含嵌套）；
     * - `$x$` 行：文本节点里的直替站点（`$$` 转义与 `${prop}` 全局属性 token 不算）。
     */
    fun build(statement: IbatisStatement): IbatisParamModel {
        val placeholders = LinkedHashMap<String, IbatisPlaceholderRow>()
        val conditions = ArrayList<IbatisConditionRow>()
        val iterates = ArrayList<IbatisIterateRow>()
        val dollars = LinkedHashMap<String, IbatisDollarRow>()

        // 声明 parameterMap 的静态语句：参数按声明序进占位符行（值只进 map，渲染静态路径本就不吃参数）
        statement.parameterMap?.parameters?.forEach { p ->
            placeholders.putIfAbsent(
                p.property,
                IbatisPlaceholderRow(name = p.property, jdbcType = p.jdbcType, nullValue = p.nullValue),
            )
        }

        fun walk(nodes: List<IbatisSqlNode>, insideIterate: Boolean) {
            for (node in nodes) {
                when (node) {
                    is IbatisTextNode -> {
                        node.mappings.forEach { m ->
                            placeholders.putIfAbsent(
                                m.propertyName,
                                IbatisPlaceholderRow(
                                    name = m.propertyName,
                                    jdbcType = m.jdbcTypeName,
                                    nullValue = m.nullValue,
                                ),
                            )
                        }
                        dollarSiteNames(node.text).forEach { name ->
                            dollars.putIfAbsent(name, IbatisDollarRow(name = name))
                        }
                    }
                    is IbatisTagNode -> when (node.kind) {
                        IbatisTagKind.ITERATE -> {
                            iterates += IbatisIterateRow(
                                id = "ibatis-iter-${iterates.size}",
                                property = node.property.orEmpty(),
                                conjunction = node.conjunction,
                            )
                            walk(node.children, true)
                        }
                        IbatisTagKind.DYNAMIC -> walk(node.children, insideIterate)
                        else -> {
                            if (!insideIterate) {
                                conditions += IbatisConditionRow(
                                    id = "ibatis-cond-${conditions.size}",
                                    kindTagName = node.kind.tagName,
                                    property = node.property?.takeIf { it.isNotEmpty() },
                                    compareValue = node.compareValue,
                                    compareProperty = node.compareProperty,
                                )
                            }
                            walk(node.children, insideIterate)
                        }
                    }
                }
            }
        }
        walk(statement.body, insideIterate = false)

        return IbatisParamModel(
            placeholderRows = placeholders.values.toList(),
            conditionRows = conditions.toList(),
            iterateRows = iterates.toList(),
            dollarRows = dollars.values.toList(),
        )
    }

    /**
     * `$x$` 站点名提取（与引擎 `SimpleDynamicSql` 的 token 化同口径：
     * `StringTokenizer(text, "$", true)`——`$$` 是字面量 `$`，不是站点；`${prop}` 是
     * 全局属性 token（首字符 `{`），本链不做替换，也不算直替参数行）。
     */
    private fun dollarSiteNames(text: String): List<String> {
        val tokens = ArrayList<String>()
        val parser = java.util.StringTokenizer(text, "$", true)
        while (parser.hasMoreTokens()) tokens.add(parser.nextToken())
        val names = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            if (tokens[i] != "$") {
                i++
                continue
            }
            if (i + 1 < tokens.size && tokens[i + 1] == "$") {
                // $$ → 字面量 $
                i += 2
                continue
            }
            var j = i + 1
            while (j < tokens.size && tokens[j] != "$") j++
            if (j >= tokens.size) break
            val name = tokens.subList(i + 1, j).joinToString("")
            if (name.isNotEmpty() && !name.startsWith("{")) names += name
            i = j + 1
        }
        return names
    }

    // ------------------------------------------------------------------
    // Map 装配
    // ------------------------------------------------------------------

    /** 面板状态 → 渲染参数（`Map` 或 null；null = 无参基线，与现状一致）。 */
    fun assembleParams(model: IbatisParamModel, input: PreviewRerenderInput): Any? {
        val map = LinkedHashMap<String, Any?>()

        // 条件行：勾选 = 放 key。值优先取用户输入（与同名占位符行共享一个输入框），缺省回落
        // compareValue（isEqual 的常见形态：勾上即按比较值参与 WHERE），再缺省空串。
        model.conditionRows.forEach { row ->
            if (row.id !in input.enabledConditionIds) return@forEach
            val property = row.property
            if (property == null) {
                // isParameterPresent 族无属性可放——是否成立只看 map 是否为 null（见函数尾）
                return@forEach
            }
            val value = input.params[property]
            map[property] = value?.takeIf { it.isNotEmpty() } ?: row.compareValue ?: ""
        }

        // 条件行「接管」的属性名：勾选时 key 已由条件行放入；**取消勾选时同名占位符/`$` 行的值
        // 也不放**——取消 = 模拟「该属性不存在」（引擎 Map 语义 containsKey 决定一切），用户填的
        // 值一并隐藏，语义自洽（否则「条件关了、占位符还绑上值」是自相矛盾的模拟）。
        val conditionControlled = model.conditionRows.mapNotNull { it.property }.toSet()

        // 占位符行：没被条件行接管的 key，填了值才放（空串 = 不放 = `?` 处 null 绑定，引擎语义）
        model.placeholderRows.forEach { row ->
            if (map.containsKey(row.name)) return@forEach
            if (row.name in conditionControlled) return@forEach
            val value = input.params[row.name]
            if (!value.isNullOrEmpty()) map[row.name] = value
        }

        // $x$ 行：填了值才放（直替；注入闸门由渲染产物的 dollarRawCount 照常置位，不得绕过）
        model.dollarRows.forEach { row ->
            if (row.name in conditionControlled) return@forEach
            val value = input.params[row.name]
            if (!value.isNullOrEmpty()) map[row.name] = value
        }

        // iterate 行：值列表非空才放（List<String> → 渲染器生成 ids[0..n-1]）；留空 = 引擎降级（现状）
        model.iterateRows.forEach { row ->
            val values = input.collections[row.property].orEmpty()
            if (values.isNotEmpty()) map[row.property] = values
        }

        if (map.isEmpty()) {
            // 空装配 = 无参基线（null，与现状逐字节一致）；唯一例外是 isParameterPresent 被勾选——
            // 它判 params != null，传空 map 才成立（isNotParameterPresent 反之，null 天然成立）
            val parameterPresentChecked = model.conditionRows.any {
                it.id in input.enabledConditionIds && it.kindTagName == IbatisTagKind.IS_PARAMETER_PRESENT.tagName
            }
            return if (parameterPresentChecked) emptyMap<String, Any?>() else null
        }
        return map
    }
}

// ----------------------------------------------------------------------
// 行数据
// ----------------------------------------------------------------------

/** 占位符行（`#x#` 家族 / parameterMap 声明参数）：参数名 + 类型提示 + 值输入（面板侧持有）。 */
data class IbatisPlaceholderRow(
    val name: String,
    val jdbcType: String? = null,
    val nullValue: String? = null,
)

/**
 * 条件行（`isXxx`）：开关 + 摘要 + 引入的参数名。
 *
 * [id] 是面板勾选键（`ibatis-cond-N`，单次 build 内稳定）；[label] 由 [IbatisParamModel.labelOf]
 * 生成（`isNotNull: id` / `isEqual: status == "1"` 形态）。
 */
data class IbatisConditionRow(
    val id: String,
    val kindTagName: String,
    val property: String?,
    val compareValue: String? = null,
    val compareProperty: String? = null,
)

/** iterate 行：集合属性名 + 值列表输入（逗号/换行分隔，面板侧持有）。 */
data class IbatisIterateRow(
    val id: String,
    val property: String,
    val conjunction: String? = null,
)

/** `$x$` 直替行：站点名 + 风险标注（面板侧展示「原文直替（注入面）」）。 */
data class IbatisDollarRow(
    val name: String,
)

/**
 * 一条 iBatis 语句的参数/条件行清单（[IbatisParamModelBuilder.build] 产物）。
 *
 * 纯数据（无 PSI、无引擎引用），随 [cn.song.mybatisplus.visualizer.model.PreviewResult] 进面板。
 */
data class IbatisParamModel(
    val placeholderRows: List<IbatisPlaceholderRow>,
    val conditionRows: List<IbatisConditionRow>,
    val iterateRows: List<IbatisIterateRow>,
    val dollarRows: List<IbatisDollarRow>,
) {

    /** 面板是否有任何可交互控件（决定 rebuildLeftPanel 是否早退为「无参数」）。 */
    val hasControls: Boolean
        get() = placeholderRows.isNotEmpty() || conditionRows.isNotEmpty() ||
            iterateRows.isNotEmpty() || dollarRows.isNotEmpty()

    /** 条件行摘要（面板复选框标签；标签名是引擎标识符，保持原文不本地化）。 */
    fun labelOf(row: IbatisConditionRow): String = when {
        row.compareProperty != null -> "${row.kindTagName}: ${row.property ?: "parameter"} == ${row.compareProperty}"
        row.compareValue != null && row.property != null ->
            "${row.kindTagName}: ${row.property} ${compareOpOf(row.kindTagName)} ${row.compareValue}"
        row.property != null -> "${row.kindTagName}: ${row.property}"
        else -> "${row.kindTagName}:"
    }

    /** compare 族标签里的比较符号（仅展示用，不影响求值）。 */
    private fun compareOpOf(tagName: String): String = when (tagName) {
        IbatisTagKind.IS_EQUAL.tagName -> "=="
        IbatisTagKind.IS_NOT_EQUAL.tagName -> "!="
        IbatisTagKind.IS_GREATER_THAN.tagName -> ">"
        IbatisTagKind.IS_GREATER_EQUAL.tagName -> ">="
        IbatisTagKind.IS_LESS_THAN.tagName -> "<"
        IbatisTagKind.IS_LESS_EQUAL.tagName -> "<="
        else -> "cmp"
    }
}
