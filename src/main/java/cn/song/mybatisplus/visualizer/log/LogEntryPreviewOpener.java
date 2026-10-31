package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 反查条目打开预览弹窗（M37.1）——与 SqlLogPanel.openPreview / HistorySqlLogPanel 同链路。
 *
 * <p>T-InParamRec：识别通道收编为 {@link LogEntryPreviewRecognizerKt#toPreviewResult} 统一入口
 * （此前是 M37.1 时代的独立识别/回退双分支实现，T-HistParam F3 统一识别通道时漏接本入口）——
 * 字面量恢复（{@code literalParamRecovery} 开关）与零参数/数量不匹配分流警告自此三口同源。
 */
public final class LogEntryPreviewOpener {

    public static final LogEntryPreviewOpener INSTANCE = new LogEntryPreviewOpener();

    private LogEntryPreviewOpener() {
    }

    public static void open(@NotNull Project project, @NotNull LogSqlEntry entry) {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        // M37.1：携带来源位置——预览面板可感知/回显 SQL 出处
        SqlPreviewService.showResult(project, LogEntryPreviewRecognizerKt.toPreviewResult(
                        entry, state.getLogFormatSql(), state.getLiteralParamRecovery(), entry.dialectName()),
                entry.sourceFile());
    }
}
