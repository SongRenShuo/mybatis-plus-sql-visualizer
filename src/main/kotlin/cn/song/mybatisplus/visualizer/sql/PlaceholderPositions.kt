package cn.song.mybatisplus.visualizer.sql

/**
 * string-aware `?` 占位符定位（T-HibDualCapture；T-PSG-01 由 `log` 包上提到 `sql` 包，
 * 供日志还原 / 复制可执行 SQL / 面板回填三条拼接链共用同一份位点口径）：单引号字符串字面量
 * （`''` 转义）与 `--` 行注释、块注释内的 `?` 不算占位符——中文问句、PG `?|` 操作符不再被
 * 误计数/误回填。还原（[cn.song.mybatisplus.visualizer.log.LogSqlRestorer.restore]）与预览
 * 识别计数（`log.countPlaceholders` 薄封装）共用本函数，避免错位。
 *
 * 双引号标识符内的 `?` 不在保护范围（标准 SQL 标识符不含 `?`，误伤面可忽略）。
 */
internal fun placeholderPositions(sql: String): List<Int> {
    val positions = mutableListOf<Int>()
    var inString = false
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        if (inString) {
            if (c == '\'') {
                if (i + 1 < sql.length && sql[i + 1] == '\'') {
                    i += 2
                    continue
                }
                inString = false
            }
        } else {
            when {
                c == '\'' -> inString = true
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    while (i < sql.length && sql[i] != '\n') i++
                }
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                    i++
                }
                c == '?' -> positions += i
            }
        }
        i++
    }
    return positions
}

/**
 * T-PSG-04：`?` 位点数与参数条数对账（**不阻断**——与我方既有 `${}` 闸门的「确认/标注」档
 * 一致，不引入拒绝档）：不等时给出一行如实标注，相等时返回 null。
 *
 * 标注形态照抄 [cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction.DOLLAR_RAW_MARKER]
 * 的既有头部标记（`--` SQL 注释行，不改产物可执行性），不新造档位。
 *
 * 两条拼接链的输入形态不同，故提供两个判定口（**有意的不对称**，见各自 KDoc）：
 * 面板回填链拼的是渲染原文本（`?` 齐全）走严格等值；复制链拼的是面板已回填文本
 * （已填参数的位置不再是 `?`）只认「占位符多于参数」这一必然残留裸 `?` 的方向。
 */
internal object PlaceholderCardinality {

    /** 严格不等时的标注行；相等返回 null（面板回填链：输入 `?` 齐全，反向不等同样是异常）。 */
    fun mismatchMarker(placeholderCount: Int, paramCount: Int): String? =
        if (placeholderCount == paramCount) null else marker(placeholderCount, paramCount)

    /**
     * 只在「占位符多于参数」时的标注行（复制链）。
     *
     * 反向（位点少于参数条数）在复制链是**常态**而非缺陷：复制链拼的是面板编辑器里的
     * 已回填文本——已填参数的位置早已不是 `?`，而 sources 仍是完整参数表，按严格等值断言
     * 会「填一个参数就报一次不等」的天天误报。真正值得如实标注的是产物里必然残留的、
     * 无参数可填的裸 `?`（可执行 SQL 不完整）。
     */
    fun excessPlaceholderMarker(placeholderCount: Int, paramCount: Int): String? =
        if (placeholderCount > paramCount) marker(placeholderCount, paramCount) else null

    private fun marker(placeholderCount: Int, paramCount: Int): String =
        "-- placeholder/param count mismatch ($placeholderCount placeholders vs $paramCount params)"
}
