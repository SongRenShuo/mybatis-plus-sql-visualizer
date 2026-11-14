package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.psi.currentDialect
import com.intellij.openapi.project.Project

/**
 * LambdaUpdateWrapper 转换器（F-09）：`UPDATE 表 SET col = ? ... WHERE ...`。
 *
 * 处理：
 *  - `set(Entity::getX, value)` → [SetClause]（列 + 值）
 *  - `set(boolean, Entity::getX, value)` → 布尔条件前缀剥离（U03，T-M15-04）
 *  - `setSql("col = expr")` → [SetClause]（原始 SQL）
 *  - `setSql("col = expr {0}", value)` → `{i}` 绑定参数替换（U04-③，T-M15-04）
 *  - `last("AND ...")` → 非 limit 尾缀透传（U05，T-M15-04；如防超发兜底条件）
 *  - 其余调用（eq 等）复用 [QueryWrapperParser] 解析为 WHERE 条件
 *
 * 无 WHERE 条件时 conditions 为空，UI 层需警示"全表更新"风险。
 */
object LambdaUpdateConverter : WrapperConverter {

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.LAMBDA_UPDATE)

    override fun convert(chain: WrapperChain, table: TableInfo, project: Project?): SqlStatement {
        // T-UpdateSetSqlConcat：解析失败的 SET 调用原文（UI 警示用，避免静默产出无 SET 的非法 SQL）
        val unparsedSets = mutableListOf<String>()
        val sets = chain.calls
            .filter { it.methodName in SET_METHODS }
            .mapNotNull { parseSet(it, unparsedSets, project) }

        // 条件部分：过滤 set/setSql/setIncrBy/setDecrBy/last 调用后复用 Query 解析（QueryWrapperParser 忽略未知方法，天然兼容）
        val conditionCalls = chain.calls.filterNot { it.methodName in SET_METHODS || it.methodName == LAST }
        val conditions = QueryWrapperParser(table)
            .parse(WrapperChain(chain.wrapperType, chain.entityClass, conditionCalls))
            .conditions

        // last 非 limit 尾缀（U05）：`last("AND (last_total - n >= 0)")` 原样追加到语句末尾
        // T-UpdateSetSqlConcat：片段位取 asSqlFragment——`last("AND (last_total - " + n + " >= 0)")` 不再整段丢弃
        val tailFragment = chain.calls
            .filter { it.methodName == LAST }
            .mapNotNull { it.args.firstOrNull().asSqlFragment() }
            .firstOrNull { !it.text.startsWith("limit", ignoreCase = true) }

        return UpdateStatement(
            table, sets, conditions, tailFragment?.text,
            unparsedSets = unparsedSets, rawTailParams = tailFragment?.params.orEmpty(),
        )
    }

    /**
     * 解析 set / setSql / setIncrBy / setDecrBy / setIfExists 调用为赋值子句（T-M15-04 起支持布尔条件前缀与 `{i}` 参数；
     * T-M21-09：setIfExists（MPJ 1.4.9+，「值存在才 set」的运行时语义）归一为 set 处理——静态渲染普通赋值）。
     *
     * @param unparsedSets 解析失败的调用原文收集器（T-UpdateSetSqlConcat：UI 警示用）；
     *                     布尔条件为 false 的主动跳过不计入（那是 MP 语义，不是解析失败）
     */
    private fun parseSet(call: WrapperCall, unparsedSets: MutableList<String>, project: Project?): SetClause? = when (call.methodName) {
        SET, SET_IF_EXISTS -> {
            val args = call.args
            // 布尔条件前缀剥离（U03）：`set(StrUtil.isNotBlank(x), col, val)` / `set(true, col, val)`
            val startIndex = if (args.size >= 3 && isFlagArgument(args[0])) 1 else 0
            // M38.2（语义对照矩阵实证）：MP 运行时语义 condition=false → 该赋值整体不拼接，
            // 原实现剥离布尔参后照常渲染（false 被当 true），静态渲染须同步忽略
            if (isFalseFlag(args, startIndex == 1)) return null
            val column = args.getOrNull(startIndex).asColumn()
            val value = args.getOrNull(startIndex + 1).asValue()
            if (column == null || value == null) {
                unparsedSets += setCallSummary(call)
                return null
            }
            SetClause(column = column, value = value)
        }
        SET_SQL -> {
            val args = call.args
            // 布尔条件前缀剥离（同 set）：`setSql(condition, "col = expr")`
            val startIndex = if (args.size >= 2 && isFlagArgument(args[0])) 1 else 0
            // T-UpdateSetSqlConcat：片段位取 asSqlFragment——`setSql("amount = amount * " + rate)`
            // 静态前缀保留、运行期值写 `?`（原实现只认完全静态字面量，整段 SET 被丢弃）
            val fragment = args.getOrNull(startIndex).asSqlFragment()
            if (fragment == null) {
                unparsedSets += setCallSummary(call)
                return null
            }
            // `{i}` 绑定参数替换（U04-③）+ 按最终文本顺序登记占位符来源
            val rendered = renderSqlFragment(fragment, args.drop(startIndex + 1).map { it.asValue() })
            SetClause(rawSql = rendered.text, rawParams = rendered.params)
        }
        // setIncrBy(col, val) → SET col = col + val；setDecrBy → SET col = col - val（3.5.6+ 官方 API）
        SET_INCR, SET_DECR -> {
            val args = call.args
            val startIndex = if (args.size >= 3 && isFlagArgument(args[0])) 1 else 0
            val column = args.getOrNull(startIndex).asColumn()
            val value = args.getOrNull(startIndex + 1).asValue()
            if (column == null || value == null) {
                unparsedSets += setCallSummary(call)
                return null
            }
            val op = if (call.methodName == SET_INCR) "+" else "-"
            // T-M23.5：列名按方言引用（setIncrBy/setDecrBy 解析期拼 rawSql）
            // T-HiddenBugPerfScan 券 Y6：project 透传（T-FS-04）——曾调无参 currentDialect()
            // 落全局手动值/MySQL 兜底，多项目同开且项目级方言不同时保留字引用错族。
            val qualified = column.qualifiedName(currentDialect(project))
            SetClause(
                rawSql = "$qualified = $qualified $op ${renderSetValue(value)}",
                // T-UpdateSetSqlConcat：rawSql 里的 `?` 也要登记来源（否则后续条件整体错位）
                rawParams = if (value is SqlValue.Placeholder) listOf(value.source) else emptyList(),
            )
        }
        else -> null
    }

    /** SET 调用原文摘要（警示文案用）：`setSql("amount = amount * " + rate)`。 */
    private fun setCallSummary(call: WrapperCall): String {
        val args = call.args.joinToString(", ") { arg ->
            when (arg) {
                is WrapperArg.ColumnArg -> arg.column.columnName
                is WrapperArg.ValueArg -> when (val v = arg.value) {
                    is SqlValue.Literal -> v.unquoted
                    is SqlValue.Placeholder -> v.source
                }
                else -> "…"
            }
        }
        return "${call.methodName}($args)"
    }

    /** 首参是否为布尔条件前缀（true/false 字面量或运行时布尔表达式占位符）；列引用（ColumnArg）恒为列。 */
    private fun isFlagArgument(arg: WrapperArg): Boolean {
        if (arg is WrapperArg.ColumnArg) return false
        val value = arg.asValue()
        // T-UpdateSetSqlConcat：带片段形态的运行期实参是 SQL 模板（`setSql("a = " + x, ...)`），
        // 不是布尔前缀——否则模板会被当 flag 吞掉，真正的模板位被错取成第二参
        if (value is SqlValue.Placeholder && arg.asSqlFragment() != null) return false
        return when (value) {
            is SqlValue.Literal -> value.unquoted == "true" || value.unquoted == "false"
            is SqlValue.Placeholder -> true
            else -> false
        }
    }

    /** M38.2：布尔前缀是否为 false 字面量（MP 运行时语义——condition=false 整个子句不拼接）。 */
    private fun isFalseFlag(args: List<WrapperArg>, hasFlag: Boolean): Boolean {
        if (!hasFlag) return false
        val flag = args[0].asValue()
        return flag is SqlValue.Literal && flag.unquoted == "false"
    }

    /** setSql 绑定参数渲染：字面量原样（raw），运行时值渲染为 `?`。 */
    private fun renderSetValue(value: SqlValue): String = when (value) {
        is SqlValue.Literal -> value.raw
        is SqlValue.Placeholder -> "?"
    }

    private const val SET = "set"
    private const val SET_SQL = "setSql"
    private const val SET_INCR = "setIncrBy"
    private const val SET_DECR = "setDecrBy"
    private const val SET_IF_EXISTS = "setIfExists" // T-M21-09：MPJ 1.4.9+，「值存在才 set」→ 归一为 set
    private const val LAST = "last"

    /** SET 类方法全集（set / setSql / setIncrBy / setDecrBy / setIfExists）。 */
    private val SET_METHODS = setOf(SET, SET_SQL, SET_INCR, SET_DECR, SET_IF_EXISTS)
}
