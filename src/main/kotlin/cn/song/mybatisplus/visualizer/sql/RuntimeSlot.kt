package cn.song.mybatisplus.visualizer.sql

/**
 * 运行期占位的语义类别（T-RuntimeSlotFill 券 A）。
 *
 * 「运行期占位」= 预览 SQL 里那条以 `运行时值: ` 开头、整条被块注释符包住的占位：它代表一段只有到运行期
 * 才能确定内容的 Java 拼接片段（见 [cn.song.mybatisplus.visualizer.psource.SqlStringConcatenator]）。
 * 类别决定面板给它什么控件、回填时按什么形态展开：
 *
 *  - [VALUE]    单值——渲染一个字面量；
 *  - [LOOP]     循环体内追加——运行期重复 N 次，用户给一组值，按模板首项/其余拼接；
 *  - [TERNARY]  三元两支——运行期只走一支，用户挑一支；
 *  - [OPTIONAL] 可选片段（`if` / `switch` / 三元 / 回调内追加）——用户决定这段在不在。
 *
 * 类别只表达**形态**，不含任何回填逻辑：渲染与替换属于券 B（`ParameterReplacer`），
 * 本模型仅承载「这个占位是什么、展开成什么样」的事实。
 */
enum class SlotKind {
    VALUE,
    LOOP,
    TERNARY,
    OPTIONAL,
}

/**
 * 一个可回填的运行期占位（T-RuntimeSlotFill 券 A 模型）。
 *
 * ## 为什么身份写在注释文本里
 *
 * 预览 SQL 在呈现前要过 [cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter]（重排空白、
 * 关键字大写、注释单独成行），字符区间（char range）在格式化后必然错位；而块注释的**文本**
 * 被格式化器原样保留（实测见 `RuntimeSlotTest` 的 P-1 用例）。因此产物里的占位统一写成
 *
 * 占位正文（整条由 SQL 块注释符包住，[token] 是含注释符的完整文本）：
 *
 * ```
 * 运行时值: <表达式摘要> · <id>
 * ```
 *
 * [token] 就是这段**精确的**占位文本（含 id），回填时按它定位（重复 token 再按出现序号，从后往前替换）。
 * 代价是 id 会显示在屏幕上——它本来就在一条注释里，且「复制可执行 SQL」路径会整段替换掉注释。
 *
 * ## 模板：只报事实，不猜
 *
 * [template] 是**展开形态**的结构化原文模板，只由源码里真实存在的字符串字面量拼成，非字面量
 * 位置一律是 [VALUE_MARK]（如 `id='%s' `）。取不到时给 `null`，含义是「展开形态未知」——
 * 此时面板与回填必须明确告知用户，**不许**按猜测的形态拼 SQL。
 *
 * 两支形态（LOOP 的「首项 / 其余」、TERNARY 的「then / else」）用 [BRANCH_SEPARATOR] 拼在同一个
 * 字符串里：它是 NUL 码位，正常 SQL 字面量里不会出现；构造时若某一支里含该字符，
 * [joinBranches] 直接返回 `null`（宁可报「未知」也不做有损编码）。解码请用
 * [templateBranches] / [firstTemplate] / [restTemplate]，不要自己 split。
 *
 * @param id         同一次预览内唯一的稳定标识（`rt1`..`rtN`，按产物中首次出现顺序分配）
 * @param kind       语义类别，决定面板控件与展开方式
 * @param expression 清洗后的表达式摘要（面板显示用，已是安全文本：无反引号问题、引号换同形字符）
 * @param template   展开形态模板；`null` = 展开形态未知（不许猜）
 * @param hint       上下文推断出的回填语义（`quote` / `inList`），推不出就留空
 * @param token      产物里那段精确的占位注释文本（含 id），回填据此定位
 */
data class RuntimeSlot(
    val id: String,
    val kind: SlotKind,
    val expression: String,
    val template: String?,
    val hint: ParamHint,
    val token: String,
) {

    /**
     * 模板的分支列表（解码 [template]）。
     *
     *  - 空表 = 无模板（形态未知）；
     *  - 一个元素 = 单形态（循环体每次追加同样的片段）；
     *  - 两个元素 = 两支形态（LOOP 首项/其余、TERNARY then/else）。
     */
    val templateBranches: List<String>
        get() = template?.split(BRANCH_SEPARATOR) ?: emptyList()

    /**
     * 首项（LOOP）/ 真分支（TERNARY）模板；单形态时与 [restTemplate] 相同。
     *
     * `null` = 无模板，或形态无法无损表达。
     */
    val firstTemplate: String?
        get() = when (templateBranches.size) {
            1, 2 -> templateBranches[0]
            else -> null
        }

    /**
     * 其余项（LOOP）/ 假分支（TERNARY）模板；单形态时与 [firstTemplate] 相同。
     *
     * `null` = 无模板，或形态无法无损表达。
     */
    val restTemplate: String?
        get() = when (templateBranches.size) {
            1 -> templateBranches[0]
            2 -> templateBranches[1]
            else -> null
        }

    companion object {

        /** 模板里「非字面量位置」的取值标记：回填时由真实值替换。 */
        const val VALUE_MARK = "%s"

        /**
         * 两支模板的分隔符（NUL 码位）。
         *
         * 选它是因为正常 SQL 字符串字面量里不会出现；一旦出现，[joinBranches] 判为「无法无损
         * 表达」并返回 `null`，而不是把两支拼错。
         */
        const val BRANCH_SEPARATOR: String = "\u0000"

        /**
         * 把若干模板分支拼成 [RuntimeSlot.template] 的编码形态。
         *
         * @param branches 分支原文（1 个 = 单形态；2 个 = 首项/其余 或 then/else）
         * @return 编码后的模板；分支为空、超过 2 支、或某一支含 [BRANCH_SEPARATOR] 时返回 `null`
         *         （= 展开形态未知，调用方不得自行补形态）
         */
        fun joinBranches(branches: List<String>): String? {
            if (branches.isEmpty() || branches.size > 2) return null
            if (branches.any { it.contains(BRANCH_SEPARATOR) }) return null
            return branches.joinToString(BRANCH_SEPARATOR)
        }
    }
}
