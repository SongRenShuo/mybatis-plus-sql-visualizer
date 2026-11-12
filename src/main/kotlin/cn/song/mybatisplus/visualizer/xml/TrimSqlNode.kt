package cn.song.mybatisplus.visualizer.xml

import java.util.Locale

/**
 * trim 节点（T-M24-01）：复刻 MyBatis 3.5.19 `org.apache.ibatis.scripting.xmltags.TrimSqlNode`。
 *
 * 算法（FilteredDynamicContext.applyAll）：
 *  1. 子内容整体 trim()（去首尾空白含换行）；
 *  2. 裁剪后为空 → 什么都不输出（prefix/suffix 都不加）；
 *  3. 前缀：内容转大写，按 prefixOverrides（`|` 分隔，转大写）顺序 startsWith，命中**第一个**即停、
 *     按 `toRemove.trim().length()` 长度删除（大小写不敏感、空格/制表符为字面量）；
 *     prefix 追加 = 先插空格再插 prefix（`prefix + " " + 内容`，恒有一个空格）；
 *  4. 后缀：同理 endsWith 或 endsWith(trim 后)，只删第一个命中；suffix 前插空格后追加；
 *  5. bind / 唯一编号转发外层上下文（filtered 只拦截 appendSql 到自缓冲）。
 */
open class TrimSqlNode(
    val contents: SqlNode,
    private val prefix: String?,
    prefixesToOverride: List<String>?,
    private val suffix: String?,
    suffixesToOverride: List<String>?,
) : SqlNode {

    private val prefixesToOverride = parseOverrides(prefixesToOverride)
    private val suffixesToOverride = parseOverrides(suffixesToOverride)

    override fun apply(context: DynamicContext): Boolean {
        val filtered = FilteredDynamicContext(context)
        val result = contents.apply(filtered)
        filtered.applyAll()
        return result
    }

    /** `|` 分隔 + 大写化（大小写不敏感匹配的载体）。 */
    private fun parseOverrides(raw: List<String>?): List<String> =
        raw.orEmpty().map { it.uppercase(Locale.ENGLISH) }

    /** 子内容缓冲上下文：appendSql 攒入自缓冲；bind/唯一编号/条件覆盖转发外层。 */
    private inner class FilteredDynamicContext(private val delegate: DynamicContext) : DynamicContext(emptyMap()) {

        private val sqlBuffer = StringBuilder()

        /**
         * 与 MyBatis 的 `sqlBuffer.append(sql)` 有一处刻意差异（T-MbgExamplePreview R2）：
         * **且仅当**粘连会拼出一个词（左侧收尾是操作数、右侧起笔是标识符字符）时补一个空格。
         *
         * 理由：本引擎的 foreach 会把每轮迭代 trim 成紧凑段（T-M25.5），两次迭代的 `and`
         * 在此处直接相接就成了单词 `andand`，`<trim prefixOverrides="and">` 只裁掉首个，
         * 残余成了括号里的幽灵条件。真实 MyBatis 因 XML 换行保留空白而不会粘词。
         *
         * 收窄到「会拼成词」是必须的：R2 首版对任何「两侧都无空白」的相邻段一律补空格，
         * 于是 foreach 的 `open`/`separator`/`close`（`(`、`,`、`)`）与 `?` 之间也被撑开，
         * `<where>` 里的 `IN (?,?)` 变成 `IN ( ? , ? )`——而真实 MyBatis 的 trim 缓冲是
         * 裸 append（其根上下文才按 StringJoiner(" ") 补空格），该形态本应保持紧凑。
         * 判据只取「左收尾 ∈ {标识符字符, `)`, `]`, `?`, 引号} 且 右起笔 = 标识符字符」，
         * 故 `(`/`,` 之后的段、以及右起笔为标点的段一律不补——`IN (?,?)`/`IN (1,2,4,5)` 原样保留。
         */
        override fun appendSql(sql: String) {
            if (sql.isEmpty()) return
            if (sqlBuffer.isNotEmpty() && needsSeparator(sqlBuffer.last(), sql.first())) {
                sqlBuffer.append(' ')
            }
            sqlBuffer.append(sql)
        }

        /**
         * 两个相邻段之间是否需要一个空格。
         *
         * 1. **粘连成词才补**（`6212f24e0` 收窄后的口径，也是 `IN (?,?)` 保持紧凑的约束）：
         *    左侧以操作数收尾且右侧以标识符起笔时补，否则不补。
         * 2. **操作符与左括号之间补**（T-MbgPreviewParityAndOpenPerf P2）：`= (` 而不是 `=(`。
         *    只认「左侧是操作符字符」这一种情形，所以 `count(*)`（左为标识符）、`( ?`（左为左括号）
         *    都不受影响——MBG 的 `and ${condition}` 与内层 `<foreach open="(">` 会被撑成
         *    `CUSTOMERNUMBER = (?)`，与真实 MyBatis 保留 XML 换行的形态一致。
         */
        private fun needsSeparator(last: Char, first: Char): Boolean =
            (endsOperand(last) && startsIdentifier(first)) ||
                (last in OPERATOR_CHARS && first == '(')

        /** 运算符字符（`= < > ! + - * / % | & ^`：其后紧跟 `(` 属于「条件 + 列表/分组」形态）。 */
        private val OPERATOR_CHARS = "=<>!+-*/%|&^"

        /** 标识符字符（`\w` 面 + `$`：列名/关键字/数字，粘连即拼成一个词）。 */
        private fun startsIdentifier(c: Char): Boolean =
            c.isLetterOrDigit() || c == '_' || c == '$'

        /**
         * 左侧是否以「操作数」收尾——其后紧跟标识符就需要一个空格。
         * 含右括号/右方括号/引号（子表达式、字面量、反引号标识符）、绑定占位 `?`，
         * 以及 `}`（T-MbgPreviewParityAndOpenPerf R1）。
         *
         * **`}` 为什么必须在**：本缓冲里放的是**渲染前文本**——`#{criterion.value}` 的收尾是 `}`，
         * 而 `#{}` → `?` 的替换发生在渲染最后一步（`XmlSqlRenderer.parseParameterMappings` 扫全文），
         * 所以判据里那个 `?` 在这一层**永远不可能出现**。漏掉 `}` 时两轮 foreach 迭代会拼成
         * `?and`（真跑不了的错 SQL），比 `andand` 更隐蔽。
         *
         * 左括号、逗号、运算符、空白仍不在内：它们本身就是分隔符，补空格是多余噪音。
         */
        private fun endsOperand(c: Char): Boolean =
            startsIdentifier(c) || c == ')' || c == ']' || c == '}' || c == '?' ||
                c == '\'' || c == '"' || c == '`'

        override fun bind(name: String, value: Any?) = delegate.bind(name, value)

        override fun countDollarSubstitution() = delegate.countDollarSubstitution()

        override fun getUniqueNumber(): Int = delegate.getUniqueNumber()

        override fun conditionResult(test: String): Boolean? = delegate.conditionResult(test)

        override fun isProtected(name: String): Boolean = delegate.isProtected(name)

        override val staticFieldValues: Map<String, Any?>
            get() = delegate.staticFieldValues

        // T-MbgExamplePreview R1：占位模式必须转发——否则 trim/where/set 体内的 `${}` 仍按空串输出
        override val emptyDollarPlaceholder: Boolean
            get() = delegate.emptyDollarPlaceholder

        override val bindings: MutableMap<String, Any?>
            get() = delegate.bindings

        // T-FS-07：errors 必须转发——否则 where/trim/set 体内 `<if test="非法表达式">` 的求值失败被丢弃
        // （与 Foreach 的 Prefixed/Filtered 同一缺陷形态，本次一并收口）
        override val errors: MutableList<String>
            get() = delegate.errors

        // R30 探镜：体内条件判定结果转发根上下文（同 errors 的转发缺陷形态，缺席即丢）
        override val conditionOutcomes: MutableMap<String, Boolean>
            get() = delegate.conditionOutcomes

        fun applyAll() {
            var sql = sqlBuffer.toString().trim()
            val upper = sql.uppercase(Locale.ENGLISH)
            if (upper.isNotEmpty()) {
                applyPrefix(sql, upper)?.let { sql = it }
                applySuffix(sql, upper)?.let { sql = it }
            }
            delegate.appendSql(sql)
        }

        private fun applyPrefix(sql: String, upper: String): String? {
            var result = sql
            for (toRemove in prefixesToOverride) {
                if (upper.startsWith(toRemove)) {
                    result = result.substring(toRemove.trim().length)
                    break
                }
            }
            return if (prefix != null) "$prefix $result" else result
        }

        private fun applySuffix(sql: String, upper: String): String? {
            var result = sql
            for (toRemove in suffixesToOverride) {
                if (upper.endsWith(toRemove) || upper.endsWith(toRemove.trim())) {
                    result = result.substring(0, result.length - toRemove.trim().length)
                    break
                }
            }
            return if (suffix != null) "$result $suffix" else result
        }
    }
}

/**
 * where 节点（T-M24-01）：复刻 MyBatis 3.5.19 `WhereSqlNode`——
 * `trim(prefix="WHERE", prefixOverrides=["AND ","OR ","AND\n","OR\n","AND\r","OR\r","AND\t","OR\t"])`，
 * 内置换行/回车/制表符变体（官方文档未写、引擎必须支持）。
 */
class WhereSqlNode(contents: SqlNode) : TrimSqlNode(
    contents,
    "WHERE",
    listOf("AND ", "OR ", "AND\n", "OR\n", "AND\r", "OR\r", "AND\t", "OR\t"),
    null,
    null,
)

/**
 * set 节点（T-M24-01）：复刻 MyBatis 3.5.19 `SetSqlNode`——
 * `trim(prefix="SET", prefixOverrides=[","], suffixOverrides=[","])`。
 */
class SetSqlNode(contents: SqlNode) : TrimSqlNode(contents, "SET", listOf(","), null, listOf(","))
