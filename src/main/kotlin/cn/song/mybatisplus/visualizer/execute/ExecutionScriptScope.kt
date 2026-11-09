package cn.song.mybatisplus.visualizer.execute

/**
 * T-DateLiteralBackfill R10（PG 回归）：预览执行是否按「整批」提交。
 *
 * 背景：DBN 控制台默认按「光标所在语句 / 选区」提交。SQL 里出现
 * `TIMESTAMP '…'` / `DATE '…'` 这类 ANSI 类型字面量后，长行被控制台折行、光标落在
 * 以 `TIMESTAMP` 开头的片段上时，`getSelectedStatementsRange` 会把该片段当成一条语句，
 * 只提交 `TIMESTAMP` 本身——PostgreSQL 报 `42601 syntax error at or near "TIMESTAMP"`。
 *
 * 预览面板的「执行 SQL」语义是**执行当前预览的 SQL**；单语句预览没有「只想执行其中一段」
 * 的需求，整批提交与「执行当前语句」等价，却绕开光标/选区这个变量。多语句预览
 * （主 SQL + 分页 COUNT 等）保持既有「当前语句」逻辑，避免把 COUNT 段一起执行。
 *
 * @param sql       预览 SQL（已回填）
 * @param requested 调用方已显式要求整批（如 SQL Server identity 包装）时直接透传
 */
internal fun shouldExecWholeScript(sql: String, requested: Boolean = false): Boolean {
    if (requested) return true
    return SqlTypeClassifier.splitStatements(sql).size <= 1
}
