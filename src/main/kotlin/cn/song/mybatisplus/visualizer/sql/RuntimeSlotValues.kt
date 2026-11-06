package cn.song.mybatisplus.visualizer.sql

/**
 * 运行期占位回填值的**类型档**（T-RuntimeSlotFill 券 B）。
 *
 * 面板让用户为某个占位选一种类型，渲染时按类型决定字面量形态（引号 / 裸数字 / NULL / 布尔）。
 * [AUTO] 表示「让引擎按上下文推断」（[RuntimeSlot.hint] 的 `quote` / `inList` 语义），推不出按字符串。
 */
enum class ValueType {
    /** 按上下文推断（默认）。 */
    AUTO,

    /** 字符串字面量：`'…'`，值内 `'` → `''`。 */
    STRING,

    /** 数值：裸出；输入不是数字时**按字符串渲染**并给提示（绝不拼裸文本）。 */
    NUMBER,

    /** 布尔：按方言渲染；无布尔字面量的方言（Oracle / 达梦）拒绝并提示改 `1` / `0`。 */
    BOOLEAN,

    /** SQL `NULL`（裸出）；面板应同时提示「`= NULL` 恒不成立，判空请用 `IS NULL`」。 */
    NULL,

    /** 多值列表（按逗号 / 换行拆分），只在 `IN (...)` 或循环模板里展开。 */
    LIST,
}

/**
 * 一个运行期占位的用户输入（面板 ↔ 回填引擎之间的唯一值载体）。
 *
 * 语义分工：
 *  - [type]  决定字面量形态（见 [ValueType]）；
 *  - [text]  用户输入的原文。`LOOP` 按逗号 / 换行拆成多值；`LIST` 同；其余按单值；
 *  - [branch] 仅 `TERNARY` 用：`0` = 真分支（[RuntimeSlot.firstTemplate]），`1` = 假分支；
 *  - [include] 仅 `OPTIONAL` 用：`false` = 这段删掉。
 *
 * ## 「未动作」与「填了空」的区分
 *
 * [isUnset] 为 true 表示**用户什么都没做**，回填引擎必须原样保留占位（D-5：留空 = 保留占位，
 * 不是 NULL）。判断依据是三个字段都停在默认值上。
 *
 * 由此带来一处**有意的**取舍：`OPTIONAL` 的「包含这段」复选框默认是勾上的，靠
 * `include == true` 无法区分「用户勾了」与「用户没动」——因此 `OPTIONAL` 必须**带值**
 * （或显式取消勾选）才算一次动作；纯字面量片段（模板里没有 `%s`）请在值里填任意非空文本，
 * 引擎只用它满足「已动作」判定，不影响产物。这条限制写进面板文案（券 C）。
 */
data class SlotValue(
    val type: ValueType = ValueType.AUTO,
    val text: String = "",
    val branch: Int? = null,
    val include: Boolean = true,
) {

    /** 用户什么都没做（引擎据此保留占位原文）。 */
    val isUnset: Boolean
        get() = type == ValueType.AUTO && text.isBlank() && branch == null && include

    companion object {

        /** 未动作。 */
        val UNSET: SlotValue = SlotValue()

        /** 单值（默认按上下文推断类型）。 */
        fun text(value: String, type: ValueType = ValueType.AUTO): SlotValue =
            SlotValue(type = type, text = value)

        /**
         * 多值列表（**一项一行**拼成 [text]，面板「每行一个」形态）。
         *
         * 注意：回填引擎拆分时逗号与换行**都是**分隔符（[splitList]，与用户手输 `1,2,3` 同口径），
         * 因此项本身含逗号的值会被拆成两项——这种值请用 [SlotValue.string] 走单值（`IN` 列表里
         * 只能放互不包含逗号的原子值）。
         */
        fun list(values: List<String>): SlotValue =
            SlotValue(type = ValueType.LIST, text = values.joinToString("\n"))

        fun list(vararg values: String): SlotValue = list(values.toList())

        /** 数值。 */
        fun number(value: String): SlotValue = SlotValue(type = ValueType.NUMBER, text = value)

        /** 字符串。 */
        fun string(value: String): SlotValue = SlotValue(type = ValueType.STRING, text = value)

        /** SQL NULL。 */
        fun sqlNull(): SlotValue = SlotValue(type = ValueType.NULL)

        /** 三元挑支。 */
        fun branch(index: Int, value: String = ""): SlotValue = SlotValue(text = value, branch = index)

        /** 可选片段：不包含。 */
        fun excluded(): SlotValue = SlotValue(include = false)
    }
}

/**
 * 一次预览内的**全部**占位值（`slotId -> SlotValue`）。
 *
 * 面板持有一份，应用/清空都是整表替换；回填引擎只读。默认 [EMPTY] = 什么都没填
 * （产物必须与不回填逐字一致）。
 */
data class RuntimeSlotValues(private val values: Map<String, SlotValue> = emptyMap()) {

    /** 某个占位的值；null = 该占位没有任何输入。 */
    operator fun get(slotId: String): SlotValue? = values[slotId]

    /** 是否登记了「用户动作」（[SlotValue.isUnset] 为 false）。 */
    fun isSet(slotId: String): Boolean = values[slotId]?.let { !it.isUnset } == true

    /** 已登记的占位 id（含「未动作」条目）。 */
    val ids: Set<String> get() = values.keys

    val isEmpty: Boolean get() = values.isEmpty()

    /** 只保留 [SlotValue.isSet] 的子集（引擎内部用）。 */
    fun effective(): Map<String, SlotValue> = values.filterValues { !it.isUnset }

    /**
     * 保存前的**静态校验**（面板「应用」按钮用；不涉及方言，方言相关判定在渲染期给提示）。
     *
     * 只报问题，不修正：返回值非空时面板可以仍允许应用（渲染期会再校验一次并逐条提示），
     * 也可以直接展示给用户。
     *
     * @param slots 当前预览的全部占位（用于识别未知 id 与类别相关的空值）
     * @return 问题清单（空表 = 无需提示）
     */
    fun validate(slots: List<RuntimeSlot>): List<SlotWarning> {
        if (values.isEmpty()) return emptyList()
        val known = slots.associateBy { it.id }
        val result = mutableListOf<SlotWarning>()
        for ((id, value) in values) {
            val slot = known[id]
            if (slot == null) {
                result += SlotWarning(id, "未知的运行期占位 id（当前预览里没有这个占位）")
                continue
            }
            if (value.isUnset) continue
            if (value.text.length > MAX_VALUE_LENGTH) {
                result += SlotWarning(id, "值过长（${value.text.length} 字符，上限 $MAX_VALUE_LENGTH）")
            }
            when (slot.kind) {
                SlotKind.LOOP -> {
                    val items = splitList(value.text)
                    when {
                        items.isEmpty() -> result += SlotWarning(id, "循环占位没有值（留空将保留占位原文）")
                        items.size > MAX_LOOP_ITEMS ->
                            result += SlotWarning(id, "循环值过多（${items.size} 项，上限 $MAX_LOOP_ITEMS）")
                    }
                }
                SlotKind.TERNARY -> {
                    val branch = value.branch
                    when {
                        branch == null -> result += SlotWarning(id, "三元占位还没选分支（留空将保留占位原文）")
                        branch != 0 && branch != 1 -> result += SlotWarning(id, "三元分支只能是 0（真）或 1（假）")
                    }
                }
                SlotKind.OPTIONAL -> {
                    if (!value.include && value.text.isNotBlank()) {
                        result += SlotWarning(id, "已取消「包含这段」，值不会生效")
                    }
                }
                SlotKind.VALUE -> Unit
            }
            if (value.type == ValueType.NUMBER && !NUMBER_PATTERN.matches(value.text.trim())) {
                result += SlotWarning(id, "选了「数字」，但输入不是数字 —— 将按字符串渲染")
            }
        }
        return result
    }

    companion object {

        /** 空表（默认行为：原样保留全部占位）。 */
        val EMPTY: RuntimeSlotValues = RuntimeSlotValues()

        /** LOOP / LIST 展开项数上限（D-7：防手滑粘贴一大坨把面板卡死）。 */
        const val MAX_LOOP_ITEMS: Int = 200

        /** 单值长度上限（字符）。 */
        const val MAX_VALUE_LENGTH: Int = 2000

        /** 数值形态（与 [ParameterReplacer] 的启发式同口径）。 */
        private val NUMBER_PATTERN = Regex("-?\\d+(\\.\\d+)?")

        /** 构造：`RuntimeSlotValues.of("rt1" to SlotValue.string("a"))`。 */
        fun of(vararg pairs: Pair<String, SlotValue>): RuntimeSlotValues = RuntimeSlotValues(pairs.toMap())

        /**
         * 多值拆分（LOOP / LIST 共用）：逗号与换行，两端空白丢弃，空项丢弃。
         *
         * 值本身含逗号时请用换行分隔（[SlotValue.list] 就是这么编码的）。
         */
        fun splitList(text: String): List<String> =
            text.split(',', '\n', '\r', '，').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * 一条占位相关的提示（面板展示 / 回填产物诊断共用）。
 *
 * @param slotId 出问题的占位 id；null = 整表层面的提示（如未知 id）
 * @param message 面向用户的中文说明（为什么没填进去、下一步该怎么做）
 */
data class SlotWarning(
    val slotId: String?,
    val message: String,
) {
    override fun toString(): String = if (slotId == null) message else "$slotId: $message"
}
