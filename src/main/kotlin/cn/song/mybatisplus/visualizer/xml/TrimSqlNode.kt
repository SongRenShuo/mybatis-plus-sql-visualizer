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
         * 相邻两段既不以空白开头、也不以空白结尾时补一个空格。
         *
         * 理由：本引擎的 foreach 会把每轮迭代 trim 成紧凑段（T-M25.5），两次迭代的 `and`
         * 在此处直接相接就成了单词 `andand`，`<trim prefixOverrides="and">` 只裁掉首个，
         * 残余成了括号里的幽灵条件。真实 MyBatis 因 XML 换行保留空白而不会粘词。
         * 只影响「trim 体内相邻段紧贴且无空白」的形态；段间原本有空白时行为不变。
         */
        override fun appendSql(sql: String) {
            if (sql.isEmpty()) return
            if (sqlBuffer.isNotEmpty() &&
                !sqlBuffer.last().isWhitespace() &&
                !sql.first().isWhitespace()
            ) {
                sqlBuffer.append(' ')
            }
            sqlBuffer.append(sql)
        }

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
