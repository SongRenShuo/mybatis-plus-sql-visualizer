package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * 历史条目一键重跑（F-12 / DBX #316）：历史面板右键「重新执行」，把条目**原样**送回既有执行链。
 *
 * 口径三要点：
 *  - **原样**：SQL 文本逐字取 [LogSqlEntry.restoredSql]（参数已按捕获时还原），方言证据取
 *    [LogSqlEntry.dialectName]——重跑组装的请求与原条目逐字段一致，绝不二次改写；
 *  - **既有执行链**：经 [cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport]（临时 SQL
 *    控制台 + 原生数据源选择器，Ultimate/DataGrip；Community 由它给出明确提示）。方言/库标
 *    已在条目角标可见，用户按原库选源即「同方言同数据源上下文」；
 *  - **不可解析给明确提示**：restoredSql 里仍留有未绑定 `?`（缺绑定行 / 旧条目无 pd 载荷）时
 *    不送执行，弹通知说明原因——把注定失败的 SQL 送进控制台比「不执行」更糟。
 *
 * 三态一视同仁：成功条目（[LogSqlEntry.errorText] == null）、失败条目（errorText != null）与
 * 旧格式条目（字段缺省）都可重跑——重跑正是排查「当时为什么失败」的入口。
 *
 * 隐私铁律：本类不写盘、不落任何新文件；重跑数据全部来自既有历史条目内存副本。
 */
object HistorySqlRerunner {

    private const val LOG_TAG = "[F-12]"
    private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

    /**
     * 重跑请求（组装产物，与原条目逐字段同源；纯数据可测）。
     *
     * @param sql 还原后 SQL 原文（restoredSql 逐字）
     * @param dialectName 原执行方言名（EXACT 证据；null = 旧条目未携带）
     * @param sourceLabel 来源位置描述（`文件:行`，空串 = 未知）
     * @param unboundPlaceholders 未绑定占位符计数（> 0 = 不可解析，须提示不执行）
     */
    data class Request(
        val sql: String,
        val dialectName: String?,
        val sourceLabel: String,
        val unboundPlaceholders: Int,
    )

    /**
     * 组装重跑请求：条目三态一视同仁；SQL 空白返回 null（空条目无重跑意义）。
     * internal 供定向测试逐字段断言。
     */
    internal fun buildRequest(entry: LogSqlEntry): Request? {
        val sql = entry.restoredSql
        if (sql.isBlank()) return null
        return Request(
            sql = sql,
            dialectName = entry.dialectName,
            sourceLabel = sourceLabelOf(entry),
            unboundPlaceholders = countUnboundPlaceholders(sql),
        )
    }

    /** 来源描述（与 [LogSqlEntry.sourceFile]/[sourceLine] 同源；null 文件 = 空串）。 */
    internal fun sourceLabelOf(entry: LogSqlEntry): String {
        val file = entry.sourceFile ?: return ""
        return if (entry.sourceLine > 0) "$file:${entry.sourceLine}" else file
    }

    /**
     * 引号感知的未绑定占位符计数：`'...'` 字符串字面量内的 `?` 不计（`''` 转义跳过）；
     * PostgreSQL 的 `?|` / `?&` 运算符不误报（`?` 后紧跟 `|`/`&` 视为运算符）。
     * 反引号/双引号标识符内的 `?` 同样不计（MySQL 反引号内、标准双引号标识符内不会出现占位符）。
     */
    internal fun countUnboundPlaceholders(sql: String): Int {
        var count = 0
        var i = 0
        while (i < sql.length) {
            when (val c = sql[i]) {
                '\'' -> i = skipQuoted(sql, i, '\'')
                '"' -> i = skipQuoted(sql, i, '"')
                '`' -> i = skipQuoted(sql, i, '`')
                '?' -> {
                    val next = sql.getOrNull(i + 1)
                    if (next != '|' && next != '&') count++
                    i++
                }
                else -> i++
            }
        }
        return count
    }

    /** 跳过引号段：返回段结束引号后的下标。未闭合（防御形态）：只越过开头引号本身照常续扫——
     *  未闭合引号本就是断句 SQL，其中的 `?` 保守计入未绑定（宁拦截不送残句进执行链）。 */
    private fun skipQuoted(sql: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < sql.length) {
            if (sql[i] == quote) {
                if (quote == '\'' && i + 1 < sql.length && sql[i + 1] == '\'') {
                    i += 2 // '' 转义
                    continue
                }
                return i + 1
            }
            i++
        }
        return start + 1
    }

    /** 重跑入口：组装 → 可解析送既有执行链 / 不可解析或空条目给明确提示。 */
    fun rerun(project: Project, entry: LogSqlEntry) {
        val request = buildRequest(entry)
        if (request == null) {
            notify(project, MpSqlVisualizerBundle.message("log.history.rerun.empty"), NotificationType.WARNING)
            return
        }
        if (request.unboundPlaceholders > 0) {
            // 不可解析：缺绑定行的条目原样执行必然失败——明确提示而非送进控制台
            notify(
                project,
                MpSqlVisualizerBundle.message("log.history.rerun.unbound", request.unboundPlaceholders),
                NotificationType.WARNING,
            )
            return
        }
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            com.intellij.openapi.diagnostic.Logger.getInstance(HistorySqlRerunner::class.java)
                .info("$LOG_TAG 重跑历史条目（方言=${request.dialectName ?: "未知"}, 来源=${request.sourceLabel.ifBlank { "未知" }}）→ 既有执行链")
            cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.execute(project, request.sql)
        }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            Notification(NOTIFICATION_GROUP_ID, MpSqlVisualizerBundle.message("log.history.rerun.title"), message, type)
                .notify(project)
        }
    }
}
