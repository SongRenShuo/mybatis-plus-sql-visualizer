package cn.song.mybatisplus.visualizer.share;

import cn.song.mybatisplus.visualizer.share.format.ExecutableSqlFormatter;
import cn.song.mybatisplus.visualizer.share.format.MarkdownFormatter;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel;
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 分享管理器（F-8）：各格式分发 + 剪贴板 / 文件操作。
 *
 * 从 {@link SqlPreviewPanel} 取当前 SQL + 参数快照，委托各 Formatter 生成文本，
 * 再经剪贴板或文件交付给用户。
 */
public final class SqlShareManager {

    /** 通知组 ID。 */
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /** SQL 长度截断阈值（>10KB 时 Markdown 截断 + 提示）。 */
    private static final int SQL_TRUNCATE_THRESHOLD = 10 * 1024;

    private final Project project;

    public SqlShareManager(@Nullable Project project) {
        this.project = project;
    }

    /**
     * 复制 Markdown 代码块到剪贴板（source 取默认 null）。
     *
     * @param panel 预览面板（取 SQL / 参数）
     */
    public void copyAsMarkdown(@NotNull SqlPreviewPanel panel) {
        copyAsMarkdown(panel, null);
    }

    /**
     * 复制 Markdown 代码块到剪贴板。
     *
     * @param panel  预览面板（取 SQL / 参数）
     * @param source 来源位置（文件名:行号，可 null）
     */
    public void copyAsMarkdown(@NotNull SqlPreviewPanel panel, @Nullable String source) {
        String sql = panel.editorDocumentText();
        if (sql == null || sql.isBlank()) {
            showToast(MpSqlVisualizerBundle.message("preview.copyExecutableSql.nothing"));
            return;
        }
        String truncated = truncateIfNeeded(sql);
        String md = MarkdownFormatter.INSTANCE.format(
                truncated,
                panel.unresolvedParams(),
                panel.paramValuesSnapshot(),
                panel.currentTableName(),
                panel.currentDialect(),
                source
        );
        copyToClipboard(md);
        showToast(MpSqlVisualizerBundle.message("share.markdown.copied"));
    }

    /**
     * 复制 Markdown 表格到剪贴板（source 取默认 null）。
     */
    public void copyAsMarkdownTable(@NotNull SqlPreviewPanel panel) {
        copyAsMarkdownTable(panel, null);
    }

    /**
     * 复制 Markdown 表格到剪贴板。
     */
    public void copyAsMarkdownTable(@NotNull SqlPreviewPanel panel, @Nullable String source) {
        String sql = panel.editorDocumentText();
        if (sql == null || sql.isBlank()) {
            showToast(MpSqlVisualizerBundle.message("preview.copyExecutableSql.nothing"));
            return;
        }
        String truncated = truncateIfNeeded(sql);
        String md = MarkdownFormatter.INSTANCE.formatAsTable(
                truncated,
                panel.unresolvedParams(),
                panel.paramValuesSnapshot(),
                panel.currentTableName(),
                panel.currentDialect(),
                source
        );
        copyToClipboard(md);
        showToast(MpSqlVisualizerBundle.message("share.markdown.table.copied"));
    }

    /**
     * 复制可执行 SQL 到剪贴板（替换 `?` 为带类型处理的字面值，source 取默认 null）。
     *
     * @param panel 预览面板（取 SQL / 参数）
     */
    public void copyExecutableSql(@NotNull SqlPreviewPanel panel) {
        copyExecutableSql(panel, null);
    }

    /**
     * 复制可执行 SQL 到剪贴板（替换 `?` 为带类型处理的字面值）。
     *
     * @param panel  预览面板（取 SQL / 参数）
     * @param source 来源位置（可 null）
     */
    public void copyExecutableSql(@NotNull SqlPreviewPanel panel, @Nullable String source) {
        String sql = panel.editorDocumentText();
        if (sql == null || sql.isBlank()) {
            showToast(MpSqlVisualizerBundle.message("preview.copyExecutableSql.nothing"));
            return;
        }
        String executable = ExecutableSqlFormatter.INSTANCE.format(
                sql,
                panel.unresolvedParams(),
                panel.paramValuesSnapshot(),
                List.of()
        );
        // T-CopyPlaceholderMarker：产物里残留的 {{…}} 模拟占位会跟着走——补一条带计数的头部注释，
        // 与预览面板复制链同源（分享面板此前一条标记都没有）
        copyToClipboard(CopyExecutableSqlAction.withPlaceholderMarker(executable));
        showToast(MpSqlVisualizerBundle.message("share.executableSql.copied"));
    }

    /**
     * 保存 HTML 报告到文件。
     *
     * @param html     HTML 全文
     * @param filePath 目标文件路径
     */
    public void saveHtmlReport(@NotNull String html, @NotNull String filePath) {
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try {
            Files.writeString(file.toPath(), html, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // 写文件失败不阻断主流程
        }
    }

    /** 截断超长 SQL（>10KB 时保留前 8KB + 截断提示）。 */
    private static String truncateIfNeeded(@NotNull String sql) {
        if (sql.length() <= SQL_TRUNCATE_THRESHOLD) return sql;
        String cut = sql.substring(0, 8 * 1024);
        return cut + "\n-- ... [SQL truncated, original length: " + sql.length() + " chars]";
    }

    /** 复制文本到系统剪贴板（统一走 UiTextClipboard 收口）。 */
    private static void copyToClipboard(@NotNull String text) {
        UiTextClipboard.copy(text);
    }

    /** Toast 通知（仅在非测试模式下显示）。 */
    private void showToast(@NotNull String message) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        Project p = project;
        if (p == null) return;
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(message, NotificationType.INFORMATION)
                    .notify(p);
        } catch (Throwable ignored) {
            // 通知失败不影响主流程
        }
    }
}
