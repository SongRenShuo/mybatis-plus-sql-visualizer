package cn.song.mybatisplus.visualizer.sql.rollback

/**
 * T-RollbackSnapshotPreExec P1：执行前快照采集的**纯逻辑判定**（零 IDE 依赖，可进 testFast）。
 *
 * 输入是「将要提交给控制台的同一份可执行文本」（经 [cn.song.mybatisplus.visualizer.sql.SqlCommentStripper.strip]
 * 剥注释）——预览链上运行期占位与 `?` 回填都发生在取词口（T-RuntimeSlotFill 券 C），所以文本里的
 * 字面量就是执行用的字面量；这里只判「形态能不能采集」，不做任何数据库交互。
 *
 * 降级矩阵见计划 §3.3：只认首关键字 UPDATE/DELETE 的单表形态；解析不出目标表、UPDATE..JOIN/
 * 多表、参数标记（`?` / `{{占位}}`）未填值的一律 [Plan.NotCapturable]，编排层静默跳过（与
 * registerRollbackSnapshotIfNeeded 的「targetTable 为空即静默」同口径，但不吞告警——原因随结果
 * 带回，供状态行如实提示）。
 */
object RollbackSnapshotCapture {

    /** 单次采集行上限（计划 §3.1 固定值，超出截断并标 [RollbackSnapshotRecord.rowLimitHit]）。 */
    const val ROW_LIMIT = 500

    /** 采集查询超时（秒）：在设置页执行超时之上另设的更紧闸，防快照 SELECT 拖慢执行链。 */
    const val TIMEOUT_SECONDS = 2

    /** 快照目录（相对项目根，计划 §3.4）。 */
    const val SNAPSHOT_DIR_RELATIVE = ".sql-visualizer/snapshots"

    sealed interface Plan {

        /** 可采集：编排层拿 [selectSql] 走只读旁路查询。 */
        data class Capturable(
            val statementKind: String,
            val targetTable: String,
            val whereText: String?,
            val selectSql: String,
        ) : Plan

        /** 不可采集；[reason] 直接可展示（状态行/日志），不做二次翻译。 */
        data class NotCapturable(val reason: String) : Plan
    }

    /**
     * 判定 cleanedSql 能否自动采集改前快照。
     *
     * @param cleanedSql 已剥注释、已填运行期占位与参数的可执行文本（与提交同一份）。
     */
    fun analyze(cleanedSql: String): Plan {
        val keyword = firstKeyword(cleanedSql)
        if (keyword != "UPDATE" && keyword != "DELETE") {
            return Plan.NotCapturable("statement is not UPDATE/DELETE ($keyword)")
        }
        val result = RollbackSqlGenerator.generate(cleanedSql)
        val table = result.targetTable ?: return Plan.NotCapturable(
            "cannot resolve a single target table (multi-table/parse failure): ${firstWarning(result)}",
        )
        val select = result.snapshotSelectSql ?: return Plan.NotCapturable(
            "no snapshot SELECT template produced: ${firstWarning(result)}",
        )
        // 快照 SELECT 直接复用原 WHERE 文本——若 WHERE 里仍有未填值的参数标记，只读旁路无法执行，
        // 与其到库端报错不如在源头拒绝（计划 §3.3「填不全就拒绝并说明缺哪个参数」的落地形态）。
        // 反向验证读数（2026-11-14）：本守卫短路成恒放行时 unfilledParameterMarkerRejected 与
        // testSwitchOnUnfilledParameterSkipsCapture 恰 2 红，恢复后全绿；关键字判定（RED-PROBE-1）
        // 无独立红面——snapshotSelectSql 只在 UPDATE/DELETE AST 分支产出，INSERT/SELECT 走不到
        // Capturable 分支，属结构性双重守卫（探针读数已登记计划）。
        if (hasUnfilledParameterMarker(cleanedSql)) {
            return Plan.NotCapturable("SQL still contains unfilled ?/placeholder markers")
        }
        return Plan.Capturable(
            statementKind = keyword,
            targetTable = table,
            whereText = result.whereText,
            selectSql = select,
        )
    }

    /** 首关键字（无注释文本的第一个词，大写化；空文本返回空串）。 */
    fun firstKeyword(sql: String): String =
        sql.trimStart().takeWhile { it.isLetterOrDigit() || it == '_' }.uppercase()

    private fun firstWarning(result: RollbackSqlGenerator.RollbackResult): String =
        result.warnings.firstOrNull()?.let { "${it.messageKey}: ${it.messageArgs.joinToString(",")}" }
            ?: "no warning detail"

    /**
     * 是否残留未填值的参数标记：剥掉单/双引号字符串字面量后含 `?`，或含 `{{…}}` 模拟占位
     * （T-CopyPlaceholderMarker：双大括号不是注释，剥注释剥不掉）。
     * 字符串剥离是保守近似——嵌套引号/反斜杠转义的极端形态可能误判，宁可误拒（不采集）也不误放
     * （把带 `?` 的 SELECT 打到库端）。
     */
    fun hasUnfilledParameterMarker(sql: String): Boolean {
        val noStrings = stripQuoted(sql)
        if (noStrings.contains('?')) return true
        if (noStrings.contains("{{")) return true
        return false
    }

    /** 剥单/双引号字符串字面量（含 SQL 风格双单引号转义），保留其余文本。 */
    private fun stripQuoted(sql: String): String {
        val out = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (c == '\'' || c == '"') {
                val quote = c
                i++
                while (i < sql.length) {
                    if (sql[i] == quote) {
                        // SQL 风格双引号转义（'' / ""）：吞掉成对引号继续在字符串内
                        if (i + 1 < sql.length && sql[i + 1] == quote) {
                            i += 2
                            continue
                        }
                        i++
                        break
                    }
                    i++
                }
                out.append(' ')
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
