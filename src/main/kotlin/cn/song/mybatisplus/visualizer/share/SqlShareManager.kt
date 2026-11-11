package cn.song.mybatisplus.visualizer.share

import cn.song.mybatisplus.visualizer.share.format.ExecutableSqlFormatter
import cn.song.mybatisplus.visualizer.share.format.MarkdownFormatter
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.io.File

/**
 * 分享管理器（F-8）：各格式分发 + 剪贴板 / 文件操作。
 *
 * 从 [SqlPreviewPanel] 取当前 SQL + 参数快照，委托各 Formatter 生成文本，
 * 再经剪贴板或文件交付给用户。
 */
class SqlShareManager(private val project: Project?) {

    companion object {
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

        /** SQL 长度截断阈值（>10KB 时 Markdown 截断 + 提示）。 */
        private const val SQL_TRUNCATE_THRESHOLD = 10 * 1024
    }

    /**
     * 复制 Markdown 代码块到剪贴板。
     *
     * @param panel 预览面板（取 SQL / 参数）
     * @param source 来源位置（文件名:行号，可 null）
     */
    fun copyAsMarkdown(panel: SqlPreviewPanel, source: String? = null) {
        val sql = panel.editorDocumentText()?.takeIf { it.isNotBlank() } ?: run {
            showToast(MpSqlVisualizerBundle.message("preview.copyExecutableSql.nothing"))
            return
        }
        val truncated = truncateIfNeeded(sql)
        val md = MarkdownFormatter.format(
            sql = truncated,
            sources = panel.unresolvedParams(),
            paramValues = panel.paramValuesSnapshot(),
            tableName = panel.currentTableName(),
            dialect = panel.currentDialect(),
            source = source,
        )
        copyToClipboard(md)
        showToast(MpSqlVisualizerBundle.message("share.markdown.copied"))
    }

    /**
     * 复制 Markdown 表格到剪贴板。
     */
    fun copyAsMarkdownTable(panel: SqlPreviewPanel, source: String? = null) {
        val sql = panel.editorDocumentText()?.takeIf { it.isNotBlank() } ?: run {
            showToast(MpSqlVisualizerBundle.message("preview.copyExecutableSql.nothing"))
            return
        }
        val truncated = truncateIfNeeded(sql)
        val md = MarkdownFormatter.formatAsTable(
            sql = truncated,
            sources = panel.unresolvedParams(),
            paramValues = panel.paramValuesSnapshot(),
            tableName = panel.currentTableName(),
            dialect = panel.currentDialect(),
            source = source,
        )
        copyToClipboard(md)
        showToast(MpSqlVisualizerBundle.message("share.markdown.table.copied"))
    }

    /**
     * 复制可执行 SQL 到剪贴板（替换 `?` 为带类型处理的字面值）。
     *
     * @param panel  预览面板（取 SQL / 参数）
     * @param source 来源位置（可 null）
     */
    fun copyExecutableSql(panel: SqlPreviewPanel, source: String? = null) {
        val sql = panel.editorDocumentText()?.takeIf { it.isNotBlank() } ?: run {
            showToast(MpSqlVisualizerBundle.message("preview.copyExecutableSql.nothing"))
            return
        }
        val executable = ExecutableSqlFormatter.format(
            sql = sql,
            sources = panel.unresolvedParams(),
            paramValues = panel.paramValuesSnapshot(),
        )
        // T-CopyPlaceholderMarker：产物里残留的 {{…}} 模拟占位会跟着走——补一条带计数的头部注释，
        // 与预览面板复制链同源（分享面板此前一条标记都没有）
        copyToClipboard(CopyExecutableSqlAction.withPlaceholderMarker(executable))
        showToast(MpSqlVisualizerBundle.message("share.executableSql.copied"))
    }

    /**
     * 保存 HTML 报告到文件。
     *
     * @param html     HTML 全文
     * @param filePath 目标文件路径
     */
    fun saveHtmlReport(html: String, filePath: String) {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeText(html, Charsets.UTF_8)
    }

    /** 截断超长 SQL（>10KB 时保留前 8KB + 截断提示）。 */
    private fun truncateIfNeeded(sql: String): String {
        if (sql.length <= SQL_TRUNCATE_THRESHOLD) return sql
        val cut = sql.substring(0, 8 * 1024)
        return "$cut\n-- ... [SQL truncated, original length: ${sql.length} chars]"
    }

    /** 复制文本到系统剪贴板（统一走 UiTextClipboard 收口）。 */
    private fun copyToClipboard(text: String) {
        UiTextClipboard.copy(text)
    }

    /** Toast 通知（仅在非测试模式下显示）。 */
    private fun showToast(message: String) {
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode) return
        val project = project ?: return
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(message, NotificationType.INFORMATION)
                .notify(project)
        }
    }
}
