package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.WrapperArg

/**
 * 逐列展开条件的参数键命名（D255 T-SetEntityParamKeyCollision）。
 *
 * `setEntity(entity)`、`setUpdateEntity(entity)`、`updatable(entity)` 这类「实体全列」API 在静态近似
 * 下会展开成多条 `col = ?`。这些占位符如果共用匿名键 `"?"`，预览参数面板会为每列各建一个输入框，
 * 却全部读写同一个 `paramValues["?"]`：改一个框全列跟着变，清除只能写回第一个框。
 *
 * 这里统一生成「实体实参前缀 + 列名」的参数键（与 [BatchOperationConverter.idSource] 的既有惯例一致）：
 * `setEntity(query)` → `query.id`、`setEntity(condition)` → `condition.status`。
 * 实参不可命名（字面量、`new Xxx()`、静态可求值表达式等）时回退 `entity.<列名>`。
 */
internal object ParamSourceNaming {

    /** 实参无法充当前缀时的兜底值。 */
    const val FALLBACK_PREFIX = "entity"

    /**
     * 可安全充当参数键前缀的形态：标识符或点链（`query`、`this.query`、`req.user`）。
     * 带括号/空格/`new` 之类的表达式文本一律不接受——它们既不适合出现在 tooltip 的 `#source`
     * 里，也会让导入参数的末段匹配产生难以预期的歧义。
     */
    private val PREFIX_REGEX = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$""")

    /** 从事先解析好的实参里取实体前缀（wrapper 调用链形态）。 */
    fun prefixOf(args: List<WrapperArg>): String =
        sanitize(((args.firstOrNull() as? WrapperArg.ValueArg)?.value as? SqlValue.Placeholder)?.source)

    /** 清洗前缀文本：合法标识符/点链原样保留，其余回退 [FALLBACK_PREFIX]。 */
    fun sanitize(raw: String?): String {
        val text = raw?.trim().orEmpty()
        return if (PREFIX_REGEX.matches(text)) text else FALLBACK_PREFIX
    }

    /** 单列参数键：`prefix.columnName`。 */
    fun source(prefix: String, columnName: String): String = "$prefix.$columnName"

    /**
     * 同一条语句里多次展开同一实体时的去重前缀：第一次用原前缀，之后 `query#2`、`query#3`。
     * MP 运行时后一次 `setEntity` 覆盖前一次，静态近似会展开两份；键必须区分开，
     * 否则两份同名条件又回到「改一个全变」。
     */
    fun uniquePrefix(base: String, sequence: Int): String =
        if (sequence <= 1) base else "$base#$sequence"
}
