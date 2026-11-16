package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier

/**
 * T-FullTableWriteRisk：无 WHERE 的写语句风险类型（横幅文案走 i18n 键，判据留纯文本层）。
 *
 * 与 Wrapper/JPA/Querydsl 各转换器里既有的「⚠ 无 WHERE 条件，将删除全表数据！」同语义——
 * 那些链在转换器里直接产出 `PreviewResult.warning`，XML 预览链此前**没有这一档**：
 * 产物随勾选实时变化（把 `_parameter != null` 这类 `<if>` 取消勾选，`delete from t where …`
 * 立刻变成 `delete from t`），而面板只挂「预览未完整还原」，看不出这是**全表写**
 * （真机 2026-11-12 截图）。
 */
internal enum class FullTableWriteRisk(val bundleKey: String) {
    DELETE("panel.banner.writeRiskDelete"),
    UPDATE("panel.banner.writeRiskUpdate"),
}

/** 字词级 WHERE 判定：`where` 必须独立成词（列名 `whereabouts` / `anywhere` 不误配）。 */
private val WHERE_WORD_REGEX = Regex("\\bWHERE\\b", RegexOption.IGNORE_CASE)

/**
 * 全表写风险判据（纯函数）：首条语句是 DELETE / UPDATE 且**没有 WHERE 子句**时给出风险类型。
 *
 * 判据细节与边界：
 *  - 只看**首条语句**——分页 COUNT 是并列渲染的第二条，风险恒在主语句上；
 *  - 首关键字不是 DELETE/UPDATE（空文本、以未还原占位开头的骨架）→ 无风险，不加噪声；
 *  - 字面量先挖空：[maskSqlLiterals] —— `update t set c = 'where'` 不是条件（该报），
 *    `delete from t where c = 'x'` 是条件（不该报）；
 *  - 注释由 [SqlTypeClassifier.splitStatements] 同源剥掉，`delete from t -- where x` 该报。
 */
internal fun fullTableWriteRisk(sql: String): FullTableWriteRisk? {
    val kind = when (SqlTypeClassifier.getFirstKeyword(sql)) {
        "DELETE" -> FullTableWriteRisk.DELETE
        "UPDATE" -> FullTableWriteRisk.UPDATE
        else -> return null
    }
    val statement = SqlTypeClassifier.splitStatements(sql).firstOrNull() ?: return null
    return if (WHERE_WORD_REGEX.containsMatchIn(maskSqlLiterals(statement))) null else kind
}

/**
 * 把 SQL 里的字符串字面量（`'...'` / `"..."`，含 `''` 双写转义）整体挖成空格。
 *
 * 只服务 [fullTableWriteRisk] 的关键词判定：长度保持不保证，只保证「字面量内部的词不再被看见」。
 */
internal fun maskSqlLiterals(sql: String): String {
    val sb = StringBuilder(sql.length)
    var i = 0
    while (i < sql.length) {
        val quote = sql[i]
        if (quote != '\'' && quote != '"') {
            sb.append(quote)
            i++
            continue
        }
        sb.append(' ')
        i++
        while (i < sql.length) {
            val c = sql[i]
            if (c == quote) {
                // 双写引号 = 转义，仍在字面量内
                if (i + 1 < sql.length && sql[i + 1] == quote) {
                    i += 2
                    continue
                }
                i++
                break
            }
            i++
        }
        sb.append(' ')
    }
    return sb.toString()
}
