package cn.song.mybatisplus.visualizer.model

import cn.song.mybatisplus.visualizer.sql.PreQuotedLiteral

/**
 * R92: explicit, literal-only preview overlay. This is NOT a MetaObjectHandler emulator:
 * no user class loading, scripts, clock, account lookup or implicit column selection.
 */
object FieldFillSimulation {
    const val SNAPSHOT_PREFIX = "__field_fill__:"
    // Deliberately do not persist the enable switch: every new preview starts disabled.
    private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    data class Overlay(val literals: Map<Int, String>, val invalidSources: Set<String>)

    /** Null means invalid/unprovided; callers retain the placeholder rather than raw-inserting input. */
    fun literal(value: String, quote: Boolean?): String? {
        if (value.isBlank() || value.any { it == ';' || it == '\\' || it.isISOControl() }) return null
        val trimmed = value.trim()
        // T-PreQuotedLiteral：用户已手工加引号的值原样（放在安全闸门之后——分号/反斜杠拒绝语义不变）
        if (quote != false && PreQuotedLiteral.isComplete(trimmed)) return trimmed
        return if (quote == false) {
            trimmed.takeIf(number::matches)
        } else {
            "'${trimmed.replace("'", "''")}'"
        }
    }

    fun overlay(
        model: BatchModel,
        active: Set<String>,
        simulationValues: Map<String, String>,
        enabled: Boolean,
    ): Overlay {
        if (!enabled) return Overlay(emptyMap(), emptySet())
        // T-FieldFillPriority（用户真机反馈）：UPSERT 的运行期分支不可知，仍不模拟（UI 文案已披露）；
        // INSERT/UPDATE 下用户手填的模拟值是显式意图，不再按字段 fill 策略过滤——面板对所有 fill
        // 字段都提供模拟行并置灰普通输入（tooltip「已由模拟值覆盖」），按 fill 过滤会让模拟值被
        // 静默忽略、SQL 回落灰框值（UPDATE 预览里的 [fill=INSERT] 字段即此形态）。
        if (model.kind == BatchKind.UPSERT) return Overlay(emptyMap(), emptySet())
        val literals = linkedMapOf<Int, String>()
        val invalid = linkedSetOf<String>()
        model.orderedColumns(active).forEachIndexed { index, col ->
            // Fixed columns belong to WHERE (id/version), never to the write overlay.
            val writableIndex = when (model.kind) {
                BatchKind.UPDATE -> index < model.toggleColumns.count { it.columnName in active }
                BatchKind.INSERT -> index >= model.fixedColumns.size
                BatchKind.UPSERT -> false
            }
            if (!writableIndex || col.columnName !in active) return@forEachIndexed
            // 模拟值优先：非空即覆盖普通参数（普通输入已置灰并给出覆盖提示）。
            val raw = simulationValues[col.source] ?: return@forEachIndexed
            if (raw.isEmpty()) return@forEachIndexed
            val literal = literal(raw, col.quote)
            literals[index] = literal ?: "?"
            if (literal == null) invalid += col.source
        }
        return Overlay(literals, invalid)
    }
}
