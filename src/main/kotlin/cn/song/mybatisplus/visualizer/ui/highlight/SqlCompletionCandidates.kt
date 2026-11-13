package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder

/**
 * T-DbSchemaCompletion：列补全候选的合并与展示（纯逻辑，可表驱动测试）。
 *
 * 此前列候选只有实体列（[SqlCompletionContext.columns]），数据源 schema 读出来的列**仅用于覆盖
 * 同名列注释**——库里存在而实体类没有的列（实体未映射的审计列、库表根本没有对应实体等）完全补不出来。
 * 本文件把两个来源合成一份候选清单：
 *  - 候选 = 实体列 ∪ 数据源列（列名去重）；
 *  - **顺序**：实体列在前（保持实体/SELECT 顺序，用户视线不跳），数据源独有列按库返回顺序追加；
 *  - **信息优先级**：同名列的注释与类型取数据源（M35 口径——DB COMMENT 比实体注解权威）。
 *
 * 展示层 [columnLookupElement] 把注释与 DB 类型串一起放进 tailText，让「这条来自库」在弹窗里可见；
 * 注释仍注册为 lookup string（输入「姓名」可命中 `username`，M16 语义保留）。
 */
internal data class ColumnCandidate(
    val name: String,
    /** 注释（数据源优先，回落实体注解；null/空白 = 无注释）。 */
    val comment: String?,
    /** DB 真实类型串（如 `varchar(64)`；仅数据源来源有值，实体列无类型信息时为 null）。 */
    val typeSpec: String?,
)

/** 合并列候选：实体列在前，数据源独有列追加；同名列数据源信息优先。 */
internal fun mergeColumnCandidates(context: SqlCompletionContext): List<ColumnCandidate> {
    val merged = LinkedHashMap<String, ColumnCandidate>()
    context.columns.forEach { column ->
        if (column.isNotBlank()) {
            merged[column] = ColumnCandidate(column, context.columnComments[column], null)
        }
    }
    context.schemaColumns.forEach { (column, meta) ->
        if (column.isBlank()) return@forEach
        val fromEntity = merged[column]
        merged[column] = ColumnCandidate(
            name = column,
            comment = meta.comment?.takeIf { it.isNotBlank() } ?: fromEntity?.comment,
            typeSpec = meta.typeSpec?.takeIf { it.isNotBlank() } ?: fromEntity?.typeSpec,
        )
    }
    return merged.values.toList()
}

/**
 * 候选的 lookup 展示：tailText = 注释 + DB 类型串（各自非空才拼，空格分隔）；注释可搜索。
 *
 * 与表名候选的 `withComment`（仅注释）分开：列候选多一段类型串，类型正是「来自数据源」的可视标记。
 */
internal fun columnLookupElement(candidate: ColumnCandidate): LookupElementBuilder {
    val tail = listOfNotNull(
        candidate.comment?.takeIf { it.isNotBlank() },
        candidate.typeSpec?.takeIf { it.isNotBlank() },
    ).joinToString("  ")
    var builder = LookupElementBuilder.create(candidate.name)
    if (tail.isNotEmpty()) builder = builder.withTailText("  $tail")
    if (!candidate.comment.isNullOrBlank()) builder = builder.withLookupString(candidate.comment)
    return builder
}
