package cn.song.mybatisplus.visualizer.xml

/**
 * foreach 节点（T-M24-01）：复刻 MyBatis 3.5.19 `org.apache.ibatis.scripting.xmltags.ForEachSqlNode`。
 *
 * 关键语义：
 * - collection 求值为 null/空 → 整段输出空串（**open/close 都不输出**）；
 *   null 且 `nullable=false`（默认）→ 错误记录（对齐 BuilderException 场景，预览不中断）；
 * - 拼接顺序：open + (每项: [separator] + 项内容) + close；separator 由 [PrefixedContext] 惰性插入
 *   （首项无实际内容时顺延到首个有内容的迭代）；
 * - Map 迭代：index = 键、item = 值（OGNL 求值为 entrySet 时的 Map.Entry 分支）；
 * - 变量双写：裸名 + `__frch_<名>_<唯一编号>`（[FilteredContext] 把 `#{item.x}` 重写为
 *   `#{__frch_item_0.x}`，正则 `^\s*item(?![^.,:\s])` —— 只重写独立变量名，`#{itemName}` 不重写）；
 * - 循环结束清理裸 item/index（`__frch_*` 保留，供参数对照）。
 */
class ForeachSqlNode(
    val contents: SqlNode,
    val collectionExpression: String,
    val separator: String?,
    val open: String?,
    val close: String?,
    val item: String?,
    val index: String?,
    private val nullable: Boolean,
) : SqlNode {

    override fun apply(context: DynamicContext): Boolean {
        val bindings = context.bindings
        val iterable = evaluateIterable(context) ?: return true
        if (!iterable.iterator().hasNext()) return true

        var first = true
        open?.let { context.appendSql(it) }
        var i = 0
        for (o in iterable) {
            val scoped = PrefixedContext(context, if (first || separator == null) "" else separator)
            val uniqueNumber = scoped.getUniqueNumber()
            if (o is Map.Entry<*, *>) {
                applyIndex(scoped, o.key, uniqueNumber)
                applyItem(scoped, o.value, uniqueNumber)
            } else {
                applyIndex(scoped, i, uniqueNumber)
                applyItem(scoped, o, uniqueNumber)
            }
            contents.apply(FilteredContext(scoped, index, item, uniqueNumber))
            if (first) first = !scoped.hasContent()
            i++
        }
        close?.let { context.appendSql(it) }
        item?.let { bindings.remove(it) }
        index?.let { bindings.remove(it) }
        return true
    }

    /**
     * collection 求值（预览适配）：bindings 中**表达式全文**精确命中（面板 mock 值）优先；
     * 否则 OGNL 求值 → Iterable / 数组 / Map(entrySet)；null → nullable 时 null，否则错误记录。
     */
    private fun evaluateIterable(context: DynamicContext): Iterable<*>? {
        val direct = context.bindings[collectionExpression]
        val value = direct ?: when (val r = OgnlEvaluator.evaluate(collectionExpression, context.bindings, context.staticFieldValues)) {
            is EvalResult.Ok -> r.value
            is EvalResult.Err -> {
                context.errors += "无法解析 foreach collection '$collectionExpression'（${r.message}）"
                return null
            }
        }
        return when (value) {
            null -> {
                if (nullable) null
                else {
                    // T-MbgExamplePreview R6：原文是 MyBatis 英文异常文案，中文 UI 直接透传读不懂；
                    // 改成中文并给可操作指引（集合为 null 多半是面板勾了「为 null」）
                    context.errors += "foreach 集合 '$collectionExpression' 求值为 null" +
                        "（模拟参数没给这个集合；若真实调用中它必有值，请取消该集合的『为 null』勾选）"
                    null
                }
            }
            is Iterable<*> -> value
            is Array<*> -> value.asList()
            is Map<*, *> -> value.entries
            else -> {
                context.errors += "foreach 集合 '$collectionExpression' 的求值结果不可迭代" +
                    "（${value::class.simpleName}）"
                null
            }
        }
    }

    private fun applyIndex(context: DynamicContext, o: Any?, i: Int) {
        if (index != null) {
            context.bind(index, o)
            context.bind(itemizeItem(index, i), o)
        }
    }

    private fun applyItem(context: DynamicContext, o: Any?, i: Int) {
        if (item != null) {
            context.bind(item, o)
            context.bind(itemizeItem(item, i), o)
        }
    }

    private fun itemizeItem(item: String, i: Int): String = "__frch_$item" + "_$i"

    /** separator 惰性插入上下文：首段有实际内容时才插入 prefix（即 separator），其后原样转发。 */
    private class PrefixedContext(
        private val delegate: DynamicContext,
        private val prefix: String,
    ) : DynamicContext(emptyMap()) {

        private var prefixApplied = false

        override fun appendSql(sql: String) {
            if (!prefixApplied && sql.isNotBlank()) {
                delegate.appendSql(prefix)
                prefixApplied = true
            }
            delegate.appendSql(sql)
        }

        /** 本轮迭代是否产出过内容（separator 顺延判据，见 [ForeachSqlNode.apply]）。 */
        fun hasContent(): Boolean = prefixApplied

        override fun bind(name: String, value: Any?) = delegate.bind(name, value)

        override fun countDollarSubstitution() = delegate.countDollarSubstitution()

        override fun getUniqueNumber(): Int = delegate.getUniqueNumber()

        override fun conditionResult(test: String): Boolean? = delegate.conditionResult(test)

        override fun isProtected(name: String): Boolean = delegate.isProtected(name)

        override val staticFieldValues: Map<String, Any?>
            get() = delegate.staticFieldValues

        // T-MbgExamplePreview R1：占位模式必须转发——否则 foreach 体内的 `${}` 仍按空串输出
        override val emptyDollarPlaceholder: Boolean
            get() = delegate.emptyDollarPlaceholder

        override val bindings: MutableMap<String, Any?>
            get() = delegate.bindings

        // T-FS-07：errors 必须转发——否则体内 `<if test="非法表达式">` 的求值失败写进本层临时列表后被丢弃
        override val errors: MutableList<String>
            get() = delegate.errors

        // R30 探镜：体内条件判定结果转发根上下文（同 errors 的转发缺陷形态，缺席即丢）
        override val conditionOutcomes: MutableMap<String, Boolean>
            get() = delegate.conditionOutcomes
    }

    /** `#{item.x}` / `#{index.x}` 重写上下文：把 token 内容开头的 item/index 名重写为 `__frch_` 唯一名。 */
    private class FilteredContext(
        private val delegate: DynamicContext,
        private val index: String?,
        private val item: String?,
        private val uniqueNumber: Int,
    ) : DynamicContext(emptyMap()) {

        private val itemPattern: Regex? = item?.let { Regex("""^\s*${Regex.escape(it)}(?![^.,:\s])""") }
        private val indexPattern: Regex? = index?.let { Regex("""^\s*${Regex.escape(index)}(?![^.,:\s])""") }

        override fun appendSql(sql: String) {
            // 逐 token 判断（对齐 MyBatis：item 改写未命中该 token 时才尝试 index 改写）
            var newSql = TokenParser.parse(sql, "#{", "}") { content ->
                var newContent = content
                val p = itemPattern
                if (p != null) newContent = p.replaceFirst(newContent, "__frch_${item}_$uniqueNumber")
                if (newContent == content && indexPattern != null) {
                    newContent = indexPattern.replaceFirst(newContent, "__frch_${index}_$uniqueNumber")
                }
                "#{$newContent}"
            }
            // T-M25.5（用户实测）：迭代内容 trim——mapper 里 foreach 体的换行缩进（"\n  #{x}\n"）
            // 若原样输出会使 IN 每项换行一行；trim 后预览紧凑（SQL 语义不变，MyBatis 原样保留）
            //
            // T-MbgExamplePreview R2：段间分隔改由下游缓冲负责（[DynamicContext.appendSql] 与
            // [TrimSqlNode] 的 filtered 缓冲都会补空格）——此处再加空格会叠加成双空格。
            delegate.appendSql(newSql.trim())
        }

        override fun bind(name: String, value: Any?) = delegate.bind(name, value)

        override fun countDollarSubstitution() = delegate.countDollarSubstitution()

        override fun getUniqueNumber(): Int = delegate.getUniqueNumber()

        override fun conditionResult(test: String): Boolean? = delegate.conditionResult(test)

        override fun isProtected(name: String): Boolean = delegate.isProtected(name)

        override val staticFieldValues: Map<String, Any?>
            get() = delegate.staticFieldValues

        // T-MbgExamplePreview R1：占位模式必须转发——否则 foreach 体内的 `${}` 仍按空串输出
        override val emptyDollarPlaceholder: Boolean
            get() = delegate.emptyDollarPlaceholder

        override val bindings: MutableMap<String, Any?>
            get() = delegate.bindings

        // T-FS-07：同上（本层 delegate 是 PrefixedContext，逐级转发到根上下文）
        override val errors: MutableList<String>
            get() = delegate.errors

        // R30 探镜：逐级转发（本层 delegate 是 PrefixedContext，最终落根上下文）
        override val conditionOutcomes: MutableMap<String, Boolean>
            get() = delegate.conditionOutcomes
    }
}
