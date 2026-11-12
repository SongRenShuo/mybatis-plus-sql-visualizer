package cn.song.mybatisplus.visualizer.xml

/**
 * XML 预览条件行（T-M24-04）：一个可勾选开关的动态条件（if / choose-when）。
 *
 * @param id             条件标识 = test 表达式原文（启用集合元素；同表达式多处出现共享开关）
 * @param label          人读条件文本（复选框标签 = 表达式）
 * @param defaultEnabled 默认勾选态（启发式：含 `== null` → false，其余 true——保守展开）
 * @param support        表达式支持面分档（T-ExprAstPrecheck T-EP-03 预检产物，xml 层字符串，零 PSI）
 * @param supportReason  分档理由码（标注 tooltip 用；SUPPORTED 时为 null）
 * @param supportDetail  理由细节（方法名 / `@类@字段` / 解析错误消息）
 */
data class XmlConditionView(
    val id: String,
    val label: String,
    val defaultEnabled: Boolean,
    val support: ExprSupport = ExprSupport.SUPPORTED,
    val supportReason: ExprReason? = null,
    val supportDetail: String? = null,
)

/** foreach 视图：collection 表达式（面板集合值/null 控件按表达式定位）+ item 名（T-M25.4 集合值回填键前缀）。 */
data class XmlForEachView(val collectionExpr: String, val item: String? = null)

/** bind 视图：变量名 + 表达式（面板覆盖值输入，空 = 自动求值）。 */
data class XmlBindView(val name: String, val expression: String)

/**
 * 条件求值结果三态（R30 探镜）：面板条件行按本轮渲染标注。
 * TRUE / FALSE = 该 `<if>` 本轮被走到且判定；UNEVALUATED = 未被走到（假 if 体内层 / choose 短路后的
 * 其余 when）——探针只读判定结果，不改变勾选与渲染行为。
 */
enum class ConditionOutcome { TRUE, FALSE, UNEVALUATED }

/**
 * XML 预览渲染状态（面板勾选产物）：
 *
 * @param enabledIds    启用中的条件开关 id（test 表达式原文）
 * @param forEachCounts collection 表达式 → 展开次数（0 = 空集合 → 整段空输出）
 * @param forEachNulls  勾选为 null 的 collection 表达式集合（nullable=false 时渲染错误提示）
 * @param bindOverrides bind 名 → 覆盖值（优先于表达式求值）
 */
data class XmlPreviewState(
    val enabledIds: Set<String> = emptySet(),
    val forEachCounts: Map<String, Int> = emptyMap(),
    val forEachNulls: Set<String> = emptySet(),
    val bindOverrides: Map<String, String> = emptyMap(),
)

/**
 * choose 互斥组（T-MbgExamplePreview R4）：同一个 `<choose>` 的 when 分支是「只能成立一个」的
 * 语义，但默认启发式把它们全部置真，渲染按短路顺序永远只走第一个——MBG Example 里第一个恰好是
 * 唯一不带 `#{}` 的 `noValue` 分支。本表记录组内成员与默认分支，[XmlPreviewModel.defaultState]
 * 每组只启用一个。
 *
 * @param branchIds       组内 when 的 test 表达式原文（顺序 = 短路顺序）
 * @param defaultBranchId 默认启用的分支（含 `#{}` 参数的分支里最先出现者；全无参数时为首个）
 */
data class XmlChooseGroup(val branchIds: List<String>, val defaultBranchId: String?)

/**
 * 语句形态档（T-MbgExamplePreview R5）：已知框架模板的默认勾选修正。
 *
 * 目前只有 MBG 生成器一种形态——Example 参数对象里 `distinct` 默认 false、`orderByClause`
 * 默认 null，集合（oredCriteria / criteria.criteria）在典型调用里各只有 1 项。无档时不改任何
 * 默认值，行为与升级前逐字节一致。
 *
 * @param suppressedConditionIds 不默认启用的条件 id（test 表达式原文）
 * @param forEachCount           集合默认展开次数覆盖（null = 沿用 [XmlPreviewModel.DEFAULT_FOREACH_SIZE]）
 */
data class XmlPreviewProfile(
    val suppressedConditionIds: Set<String> = emptySet(),
    val forEachCount: Int? = null,
) {
    companion object {
        /** MBG 生成器模板的固定 test 文本（`distinct` 与 `orderByClause != null`）。 */
        val MBG_EXAMPLE = XmlPreviewProfile(
            suppressedConditionIds = setOf("distinct", "orderByClause != null"),
            forEachCount = 1,
        )
    }
}

/**
 * XML 预览模型（T-M24-04）：驱动预览面板的动态条件勾选与参数输入。
 *
 * [renderSql] 是**唯一渲染源**——初始渲染与面板勾选重建共用，保证一致；
 * 纯逻辑（依赖 [OgnlEvaluator] / [XmlSqlRenderer]），不依赖 PSI / IDE，可独立单测。
 *
 * 模拟参数策略（M24 版，文档化）：
 *  - 顶层变量（bind / `${}` 表达式引用的根标识符，排除 `_parameter`/`_databaseId`）
 *    统一占位为字符串 `"1"`（非 null 非空，使 `x != null`、`x != ''`、`'%' + x + '%'` 等常见形态可求值）；
 *  - if/when 真假由面板开关直接决定（启发式默认），表达式求值仅在无开关覆盖时发生；
 *  - foreach 展开次数由面板输入（默认 [DEFAULT_FOREACH_SIZE]），collection 表达式全文作为 mock 键
 *    （[ForeachSqlNode] 精确命中优先）；
 *  - bind 覆盖值优先（[DynamicContext.isProtected]）。
 */
class XmlPreviewModel private constructor(
    val statementId: String,
    val kind: XmlStatementKind,
    val conditionViews: List<XmlConditionView>,
    val forEachViews: List<XmlForEachView>,
    val bindViews: List<XmlBindView>,
    val parameterType: String?,
    /** 解析期错误（include 缺失 / 循环等），渲染期错误在 RenderedXmlSql.errors。 */
    val errors: List<String>,
    /** 顶层变量（bind / `${}` 表达式引用的根标识符，排除保留键）——mock 参数对象属性。 */
    val variables: Set<String>,
    /** `_parameter` 的直接属性（`_parameter.title` 形态）——mock 参数对象属性。 */
    val paramProps: Set<String>,
    /** 静态字段值（T-M25.2）：`@class@field` → 值（PSI 层解析用户项目常量类注入）。 */
    val staticFieldValues: Map<String, Any?>,
    /** MP 占位 mock（T-M26-B）：`ew` → MockObject(customSqlSegment/sqlSelect/sqlSet 片段)，覆盖变量占位。 */
    val extraBindings: Map<String, Any?>,
    /** 参数回填引号映射（T-M29.5）：参数路径（`@Param` 值.字段 / 参数名）→ 是否引号——
     * 由 XmlParamTypeResolver 按 mapper 方法签名参数 Java 类型解析（DTO 字段类型 → ParamQuote）；
     * Map/无类型信息参数不含键（面板兜底引号）。 */
    val paramQuotes: Map<String, Boolean> = emptyMap(),
    /** T-InjectionGate T-IG-01：语句文本含未转义的 `${}` 直替 token（解析期事实，与替换成功与否无关——
     *  成功替换为模拟值后产物文本里已无 `${}` 痕迹，最终文本 regex 会漏掉全部成功场景）。
     *  执行/EXPLAIN 前的 `${}` 确认闸门据此判定「产物含 raw 原文拼接段」。默认 false，既有调用点零影响。 */
    val hasDollarRawSegments: Boolean = false,

    /**
     * 参数类型类别映射（T-PT-01）：参数路径（`@Param` 值.字段 / 参数名）→ [ParamTypeInfo]——
     * 由 XmlParamTypeResolver 按 mapper 方法签名参数 Java 类型映射（psi 层只做映射，类别枚举在 xml 层）。
     * 无类型信息不含键（面板按 UNKNOWN 兜底：无占位提示、mock 占位 "1"、引号兜底）。
     */
    val paramTypes: Map<String, ParamTypeInfo> = emptyMap(),
    /** choose 互斥组（T-MbgExamplePreview R4）——[defaultState] 每组只启用一个分支。 */
    val chooseGroups: List<XmlChooseGroup> = emptyList(),
    /** 语句形态档（T-MbgExamplePreview R5）——null 时不改任何默认值（升级前行为）。 */
    val profile: XmlPreviewProfile? = null,
    /**
     * 集合表达式 → 元素级模拟值（T-MbgExamplePreview M2）。键与 [forEachViews] 的
     * `collectionExpr` 同源（`oredCriteria` / `criteria.criteria`），值为调用点反推出的
     * Criteria / Criterion；缺席时退回「展开 N 次的无属性元素」。
     */
    val collectionElements: Map<String, List<MockObject>> = emptyMap(),
    private val rootNode: SqlNode,
) {

    /**
     * T-M29.5：参数路径 → 引号策略（精确匹配优先，前缀匹配兜底——
     * DTO 参数键为 `p.settlementStartDate` 精确路径；Map 参数键为 `p`，任意 `p.*` 属性命中）。
     * 未命中返回 null（面板兜底引号）。
     *
     * O-78 T-ForeachItemRender：paramQuotes 派生表未含 foreach item 键，末位再经 [paramTypeFor]
     * 兜底派生（item 键 → 元素类别 → quoteRequired）；paramTypes 空的旧布尔通道 paramTypeFor 恒
     * null，行为不变。
     */
    fun quoteFor(source: String): Boolean? {
        paramQuotes[source]?.let { return it }
        paramQuotes.entries.firstOrNull { (key, _) -> source.startsWith("$key.") }?.value?.let { return it }
        return paramTypeFor(source)?.kind?.quoteRequired
    }

    /**
     * T-PT-01：参数路径 → 类型信息（精确匹配优先、前缀匹配兜底，命中规则与 [quoteFor] 一致）。
     * 未命中返回 null（调用方按 [ParamKind.UNKNOWN] 兜底）。
     *
     * O-78 T-ForeachItemRender：末位追加 foreach item 占位键解析（[foreachItemTypeInfo]）。
     */
    fun paramTypeFor(source: String): ParamTypeInfo? {
        paramTypes[source]?.let { return it }
        paramTypes.entries.firstOrNull { (key, _) -> source.startsWith("$key.") }?.let { return it.value }
        return foreachItemTypeInfo(source)
    }

    /**
     * O-78 T-ForeachItemRender：foreach item 占位键（`__frch_<item>_<n>`，[ForeachSqlNode] 变量双写）
     * → 集合元素类别。item 键不在 paramTypes（表按方法签名生成），借 [forEachViews] 反查
     * collection 表达式取 [ParamTypeInfo.elementKind]；仅裸键命中（`__frch_st_0.name` 子路径不接）、
     * 无元素类别信息返回 null（宁缺勿错，调用方按 UNKNOWN 兜底=升级前行为）。
     */
    private fun foreachItemTypeInfo(source: String): ParamTypeInfo? {
        if (!source.startsWith(FRCH_PREFIX)) return null
        val match = FOREACH_ITEM_KEY.matchEntire(source) ?: return null
        val view = forEachViews.firstOrNull { it.item == match.groupValues[1] } ?: return null
        val elementKind = paramTypes[view.collectionExpr]?.elementKind ?: return null
        return ParamTypeInfo(kind = elementKind)
    }

    /** T-PT-01：参数路径 → 类型类别（无类型信息 → [ParamKind.UNKNOWN]）。 */
    fun kindFor(source: String): ParamKind = paramTypeFor(source)?.kind ?: ParamKind.UNKNOWN

    /**
     * T-PC2-04（D1=B 定案）：执行 / EXPLAIN 前提示所需的「签名类型未能识别」参数清单
     * （按 [sources] 顺序去重）。
     *
     * 判定口径：**在 [paramTypes] 中有条目且类别为 [ParamKind.UNKNOWN]**（boolean / `byte[]` /
     * 枚举等——类型读到了但映射不到类别，执行时以字面 `?` 提交）。**无类型信息**（键不在
     * [paramTypes]：mapper 接口不可解析 / 参数未参与签名解析）**不计入**——与 T-PT-02
     * 「无类型信息不提示」同向，也避免「接口解析不到 → 每次执行都提示」的噪音面。
     *
     * @param sources 面板参数行来源（`PreviewResult.unresolvedParams`，即渲染出的 `#{}` 属性序列）
     */
    fun unknownParamSources(sources: Collection<String>): List<String> =
        sources.filter { paramTypeFor(it)?.kind == ParamKind.UNKNOWN }.distinct()

    fun defaultState(): XmlPreviewState {
        val suppressed = profile?.suppressedConditionIds.orEmpty()
        val enabled = conditionViews
            .filter { it.defaultEnabled && it.id !in suppressed }
            .map { it.id }
            .toMutableSet()
        // T-MbgExamplePreview R4：同一 choose 的 when 互斥——组内多于一个时只留默认分支。
        // T-MbgPreviewParityAndOpenPerf (d)：M2 命中时优先按**反推到的真实 kind**选默认分支
        // （用户不动勾选即得到真实语义；手动改仍完全生效——开关仍是唯一真值源）。
        chooseGroups.forEach { group ->
            val candidates = group.branchIds.filter { it in enabled }
            if (candidates.size > 1) {
                val keep = realKindPreferredBranch(group)?.takeIf { it in candidates }
                    ?: group.defaultBranchId?.takeIf { it in candidates }
                    ?: candidates.first()
                candidates.filter { it != keep }.forEach { enabled.remove(it) }
            }
        }
        val count = profile?.forEachCount ?: DEFAULT_FOREACH_SIZE
        // D13（真机 2026-11-12 16:26，`CustAddressMapper#deleteByExample`）：拿不到调用点时
        // `Example_Where_Clause` 的四个 kind 分支可能一个都没被组内收敛掉（真机复选框四个全勾），
        // 渲染按短路顺序永远走第一个 `noValue`——产物就成了 `WHERE ( {{criterion.condition}} )`，
        // 连 `?` 都没有，和 select 的默认形态不一致，用户会以为预览坏了。
        // MBG 无元素注入时统一收敛到 `singleValue`：形态 `WHERE ( {{criterion.condition}} ? )`。
        if (profile == XmlPreviewProfile.MBG_EXAMPLE && collectionElements.isEmpty()) {
            // 按「kind 分支」特征收敛，不依赖 choose 组是否被完整识别——真机上 deleteByExample 的
            // `<choose>` 没被识别成组，listValue 因此漏在组外一直显示勾选（勾着却永远轮不到）。
            val kindIds = conditionViews.map { it.id }.filter { isKindBranchId(it) }
            val keep = kindIds.firstOrNull { it.endsWith(KIND_FLAGS[1]) }
            if (keep != null) {
                kindIds.forEach { id -> if (id == keep) enabled.add(id) else enabled.remove(id) }
            }
        }
        return XmlPreviewState(
            enabledIds = enabled,
            forEachCounts = forEachViews.associate { it.collectionExpr to count },
        )
    }

    /** 分支 id 是否属于 MBG Criterion 的四个 kind 标志（`criterion.singleValue` 等）。 */
    private fun isKindBranchId(id: String): Boolean = KIND_FLAGS.any { id == it || id.endsWith(".$it") }

    /**
     * T-MbgPreviewParityAndOpenPerf (d)：从 M2 反推的元素里读**真实 kind 的多数票**，映射回该组
     * 对应的 when 分支 id；拿不到（M2 未命中 / 元素没有这四个布尔 / 该 id 不在组里）返回 null，
     * 调用方退回既有启发式。
     *
     * MBG 的 `addCriterion("CUSTOMERNUMBER =", value, "custNumber")` 形态让 M2 知道每个 criterion
     * 究竟是 noValue / singleValue / betweenValue / listValue——面板默认值据此对齐即可，
     * 不必新增交互或提示。
     */
    private fun realKindPreferredBranch(group: XmlChooseGroup): String? {
        val elements = collectionElements.values.flatten()
        if (elements.isEmpty()) return null
        val best = KIND_FLAGS
            .mapNotNull { flag -> elements.count { it[flag] == true }.takeIf { it > 0 }?.let { flag to it } }
            .maxByOrNull { it.second }
            ?.first ?: return null
        return group.branchIds.firstOrNull { it == best || it.endsWith(".$best") }
    }

    fun renderSql(): RenderedXmlSql = renderSql(defaultState())

    fun renderSql(state: XmlPreviewState): RenderedXmlSql = XmlSqlRenderer().render(
        node = rootNode,
        bindings = mockBindings(state),
        // 全部条件都有明确真值（开关缺席 = false）——开关是分支唯一真值源，表达式仅无开关时兜底求值。
        // 例外（D5）：M2 反推到的元素带**逐条真实 kind**，choose 的四个 kind 分支不能再按「组内只开一个」
        // 强制——否则一个 between 条件会被 singleValue 模板渲染成 `between ?`（丢第二个占位，真机表现为
        // `BETWEEN '11'`）。这类 id 直接交给表达式按每条 criterion 自己的标志求值。
        conditionOverrides = conditionViews.associate { it.id to (it.id in state.enabledIds) }
            .filterKeys { it !in kindDrivenConditionIds() },
        protectedNames = state.bindOverrides.keys.toSet(),
        staticFieldValues = staticFieldValues,
        bindNames = bindViews.map { it.name }.toSet(),
        // T-MbgExamplePreview R1：预览链开启 `${}` 空值占位（真实 SQL 产物路径不受影响）
        emptyDollarPlaceholder = true,
    )

    /**
     * 由数据（而非开关）决定真值的条件 id：M2 注入了元素级 kind 时，choose 里那四个 kind 分支。
     *
     * 判据只看组内分支是否**全是** `KIND_FLAGS`——其它形态的 choose（业务自写的 `status != null` 等）
     * 仍走「组内只开一个」的既有语义，不受影响。
     */
    private fun kindDrivenConditionIds(): Set<String> {
        if (collectionElements.isEmpty()) return emptySet()
        return chooseGroups
            .filter { group ->
                group.branchIds.isNotEmpty() &&
                    group.branchIds.all { id -> KIND_FLAGS.any { id == it || id.endsWith(".$it") } }
            }
            .flatMap { it.branchIds }
            .toSet()
    }

    /**
     * R30 探镜：本轮渲染的条件求值结果（三态，键与 [conditionViews] 的 id 同源——面板条件行
     * 按 id 定位标注）。判定结果取 [RenderedXmlSql.conditionResults]（只含被走到的条件），
     * 缺席映射 [ConditionOutcome.UNEVALUATED]。
     */
    fun conditionOutcomes(rendered: RenderedXmlSql): Map<String, ConditionOutcome> = conditionViews.associate { view ->
        val recorded = rendered.conditionResults[view.id]
        view.id to when (recorded) {
            true -> ConditionOutcome.TRUE
            false -> ConditionOutcome.FALSE
            null -> ConditionOutcome.UNEVALUATED
        }
    }

    private fun mockBindings(state: XmlPreviewState): Map<String, Any?> {
        val bindings = mutableMapOf<String, Any?>()
        // 变量/参数名占位双写：_parameter 对象属性 + bindings 顶层（对齐 MyBatis ContextMap →
        // _parameter fallback：`${param1}` 与 `_parameter.param1` 均可取到占位 "1"）
        // T-PT-03：占位值按类型类别分派（有类型信息才分派，无类型信息维持 "1"——行为=升级前）
        val mockProps = (variables + paramProps).associateWith { mockValueOf(it) }
        bindings[DynamicContext.PARAMETER_OBJECT_KEY] = MockObject(mockProps)
        mockProps.keys.forEach { bindings[it] = mockValueOf(it) }
        bindings[DynamicContext.DATABASE_ID_KEY] = null
        forEachViews.forEach { view ->
            val elements = collectionElements[view.collectionExpr]
            // D7：M2 注入（有元素级模拟值）时，点号表达式（`criteria.criteria`）不再绑成全局集合——
            // 否则内层 foreach 走的是这份全局列表，外层每条 Criteria 都会把全部条件再渲染一遍
            //（真机 `( a ) or( b )` 变成 `( a and b ) or ( a and b )`）。跳过绑定，让路径解析落到
            // 每条 Criteria 自己的 `criteria` 属性上（注入的 Criteria 必带该属性）。
            if (collectionElements.isNotEmpty() && '.' in view.collectionExpr) return@forEach
            bindings[view.collectionExpr] = when {
                view.collectionExpr in state.forEachNulls -> null
                // T-MbgExamplePreview M2：调用点反推到的元素级模拟值优先（Criteria / Criterion）
                elements != null -> MockIterable(elements.size, elements)
                else -> MockIterable(state.forEachCounts[view.collectionExpr] ?: DEFAULT_FOREACH_SIZE)
            }
        }
        state.bindOverrides.forEach { (name, value) -> bindings[name] = value }
        // T-M26-B：MP 占位 mock 覆盖（ew → 片段对象，置于最后优先级最高）
        bindings.putAll(extraBindings)
        return bindings
    }

    /**
     * T-PT-03：变量/参数占位值按类型类别分派——NUMBER→`1`、TEMPORAL→[TEMPORAL_MOCK_VALUE]、
     * COLLECTION→[MockIterable]、OBJECT→[MockObject]；TEXT/UNKNOWN（无类型信息）→ `"1"`（升级前行为）。
     */
    private fun mockValueOf(name: String): Any? = when (paramTypeFor(name)?.kind ?: ParamKind.UNKNOWN) {
        ParamKind.NUMBER -> 1
        ParamKind.TEMPORAL -> TEMPORAL_MOCK_VALUE
        ParamKind.COLLECTION -> MockIterable(DEFAULT_FOREACH_SIZE)
        ParamKind.OBJECT -> MockObject()
        ParamKind.TEXT, ParamKind.UNKNOWN -> "1"
    }

    companion object {
        /** foreach 默认展开次数（IN 场景两元素可直观展示 separator）。 */
        const val DEFAULT_FOREACH_SIZE = 2

        /**
         * T-MbgPreviewParityAndOpenPerf (d)：MBG Criterion 的四个互斥 kind 标志
         * （M2 反推产物里的布尔键，也是 choose 组 when 分支 id 的后缀）。
         */
        private val KIND_FLAGS = listOf("noValue", "singleValue", "betweenValue", "listValue")

        /** foreach item 占位键前缀（变量双写形态 `__frch_<item>_<唯一编号>`，见 [ForeachSqlNode]）。 */
        private const val FRCH_PREFIX = "__frch_"

        /** item 占位键裸键形态：前缀 + item 名（贪婪）+ `_数字`；dotted 子路径（`__frch_st_0.x`）不匹配。 */
        private val FOREACH_ITEM_KEY = Regex("""^__frch_(.+)_(\d+)$""")

        /**
         * T-PT-03：TEMPORAL 参数占位值——带引号的日期字面量（`${}` 直替进 SQL 后即为合法字面量；
         * `test` 表达式里为非空字符串，判真与升级前的 "1" 一致）。
         */
        const val TEMPORAL_MOCK_VALUE = "'2026-01-01 00:00:00'"

        /** 启发式默认勾选态：含 `== null` → false（变量默认非 null），其余 true（保守展开）。 */
        fun defaultTruth(test: String): Boolean = !test.contains("== null")

        /**
         * 统计节点树里的 `#{}` token 数（T-MbgExamplePreview R4：choose 组默认分支按「含参数」优先——
         * 带 `#{}` 的分支在预览里至少能给出 `?` 占位，不带参数的 `and ${...}` 分支往往只剩一个裸 and）。
         * 转义 `\#{` 不计（与 [TokenParser] 语义一致）。
         */
        fun countParameterTokens(node: SqlNode): Int = when (node) {
            is MixedSqlNode -> node.children.sumOf { countParameterTokens(it) }
            is TextSqlNode -> {
                var n = 0
                TokenParser.parse(node.text, "#{", "}") { n++; it }
                n
            }
            is IfSqlNode -> countParameterTokens(node.contents)
            is TrimSqlNode -> countParameterTokens(node.contents)
            is ForeachSqlNode -> countParameterTokens(node.contents)
            is ChooseSqlNode -> node.whenNodes.sumOf { countParameterTokens(it) } +
                (node.defaultNode?.let { countParameterTokens(it) } ?: 0)
            else -> 0
        }

        /** 由解析出的语句构建预览模型（变量收集 + 条件/foreach/bind 视图）。
         *  T-M25-02：注解场景额外注入方法参数名（@Param 值/实际名/param1..N，MyBatis 多参数 Map 键）。
         *  T-M25.2：staticFieldValues 为 `@class@field` → 值（PSI 层解析用户项目常量类）。
         *  T-M26-B：extraBindings 为 MP 占位 mock（`ew` → 片段对象）。
         *  T-PT-01：paramTypes 为参数类型类别映射；非空时 [paramQuotes] 由其派生
         *  （NUMBER→false、其余 true），升级前显式传入的布尔通道继续可用（既有单测形态）。 */
        fun from(
            statement: XmlStatement,
            errors: List<String>,
            extraParamProps: Set<String> = emptySet(),
            staticFieldValues: Map<String, Any?> = emptyMap(),
            extraBindings: Map<String, Any?> = emptyMap(),
            paramQuotes: Map<String, Boolean> = emptyMap(),
            paramTypes: Map<String, ParamTypeInfo> = emptyMap(),
            /** T-MbgExamplePreview R5：语句形态档（如 [XmlPreviewProfile.MBG_EXAMPLE]）；null = 不改默认。 */
            profile: XmlPreviewProfile? = null,
            /** T-MbgExamplePreview M2：集合表达式 → 调用点反推的元素级模拟值。 */
            collectionElements: Map<String, List<MockObject>> = emptyMap(),
        ): XmlPreviewModel {
            val conditionViews = mutableListOf<XmlConditionView>()
            val forEachViews = mutableListOf<XmlForEachView>()
            val bindViews = mutableListOf<XmlBindView>()
            val chooseGroups = mutableListOf<XmlChooseGroup>()
            val variables = mutableSetOf<String>()
            val paramProps = mutableSetOf<String>()
            // T-InjectionGate T-IG-01：解析期 `${}` token 收集（非空即「含 raw 原文拼接段」）
            val dollarTokens = mutableListOf<String>()
            collect(statement.node, conditionViews, forEachViews, bindViews, variables, paramProps, dollarTokens, chooseGroups)
            variables.remove(DynamicContext.PARAMETER_OBJECT_KEY)
            variables.remove(DynamicContext.DATABASE_ID_KEY)
            paramProps += extraParamProps
            return XmlPreviewModel(
                statementId = statement.id,
                kind = statement.kind,
                conditionViews = conditionViews,
                forEachViews = forEachViews,
                bindViews = bindViews,
                parameterType = statement.parameterType,
                errors = errors,
                variables = variables,
                paramProps = paramProps,
                staticFieldValues = staticFieldValues,
                extraBindings = extraBindings,
                // T-PT-01：布尔引号通道降级为派生视图——有类型类别时由类别派生（NUMBER→false 其余 true）
                paramQuotes = if (paramTypes.isNotEmpty()) {
                    paramTypes.mapValues { (_, info) -> info.kind.quoteRequired }
                } else {
                    paramQuotes
                },
                paramTypes = paramTypes,
                chooseGroups = chooseGroups,
                profile = profile,
                collectionElements = collectionElements,
                hasDollarRawSegments = dollarTokens.isNotEmpty(),
                rootNode = statement.node,
            )
        }

        /** 表达式根路径分派：`_parameter.x` → 模拟参数对象属性；其余根（排除保留键）→ 变量。 */
        private fun collectExpressionPaths(
            expression: String,
            variables: MutableSet<String>,
            paramProps: MutableSet<String>,
        ) {
            OgnlEvaluator.rootPaths(expression).forEach { path ->
                if (path.startsWith("${DynamicContext.PARAMETER_OBJECT_KEY}.")) {
                    paramProps += path.removePrefix("${DynamicContext.PARAMETER_OBJECT_KEY}.")
                } else if (path != DynamicContext.PARAMETER_OBJECT_KEY && path != DynamicContext.DATABASE_ID_KEY) {
                    variables += path
                }
            }
        }

        private fun collect(
            node: SqlNode,
            conditionViews: MutableList<XmlConditionView>,
            forEachViews: MutableList<XmlForEachView>,
            bindViews: MutableList<XmlBindView>,
            variables: MutableSet<String>,
            paramProps: MutableSet<String>,
            dollarTokens: MutableList<String>,
            chooseGroups: MutableList<XmlChooseGroup>,
        ) {
            when (node) {
                is MixedSqlNode ->
                    node.children.forEach {
                        collect(it, conditionViews, forEachViews, bindViews, variables, paramProps, dollarTokens, chooseGroups)
                    }
                is StaticTextSqlNode -> Unit
                is TextSqlNode -> {
                    val tokens = mutableListOf<String>()
                    TokenParser.parse(node.text, "\${", "}") { token ->
                        tokens += token
                        token
                    }
                    dollarTokens += tokens
                    tokens.forEach { collectExpressionPaths(it, variables, paramProps) }
                }
                is IfSqlNode -> {
                    // T-ExprAstPrecheck T-EP-03：构建条件行时对 test 表达式跑支持面预检（纯 AST 遍历，
                    // 零 PSI）——标注只是提示，展开行为仍由面板开关与 T-EP-02 的错误记录管
                    val classification = OgnlEvaluator.classifyDetail(node.test)
                    conditionViews += XmlConditionView(
                        node.test,
                        node.test,
                        defaultTruth(node.test),
                        classification.support,
                        classification.reason,
                        classification.detail,
                    )
                    collect(node.contents, conditionViews, forEachViews, bindViews, variables, paramProps, dollarTokens, chooseGroups)
                }
                is ChooseSqlNode -> {
                    // T-MbgExamplePreview R4：组内 when 归组（顺序 = 短路顺序），默认只启用一个——
                    // 含 `#{}` 参数的分支里最先出现者优先，全无参数时取首个。否则四个互斥分支同真，
                    // MBG Example 会永远短路到唯一不带参数的 noValue 分支。
                    val branchIds = mutableListOf<String>()
                    val paramCounts = mutableListOf<Int>()
                    node.whenNodes.forEach {
                        collect(it, conditionViews, forEachViews, bindViews, variables, paramProps, dollarTokens, chooseGroups)
                        (it as? IfSqlNode)?.let { branch ->
                            branchIds += branch.test
                            paramCounts += countParameterTokens(branch.contents)
                        }
                    }
                    if (branchIds.size > 1) {
                        val defaultBranch = branchIds
                            .filterIndexed { i, _ -> paramCounts[i] > 0 }
                            .firstOrNull() ?: branchIds.first()
                        chooseGroups += XmlChooseGroup(branchIds, defaultBranch)
                    }
                    node.defaultNode?.let {
                        collect(it, conditionViews, forEachViews, bindViews, variables, paramProps, dollarTokens, chooseGroups)
                    }
                }
                is TrimSqlNode ->
                    collect(node.contents, conditionViews, forEachViews, bindViews, variables, paramProps, dollarTokens, chooseGroups)
                is ForeachSqlNode -> {
                    forEachViews += XmlForEachView(node.collectionExpression, node.item)
                    collect(node.contents, conditionViews, forEachViews, bindViews, variables, paramProps, dollarTokens, chooseGroups)
                }
                is VarDeclSqlNode -> {
                    bindViews += XmlBindView(node.name, node.expression)
                    collectExpressionPaths(node.expression, variables, paramProps)
                }
            }
        }
    }
}
