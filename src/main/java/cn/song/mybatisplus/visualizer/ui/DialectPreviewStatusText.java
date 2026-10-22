// 自 master ui/DialectPreviewStatusText.kt 移植（T-DialectStatusLiveRefresh：绑定指示行方言状态文案）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.StringJoiner;

/** Kotlin object → Java 静态类（{@code DialectPreviewStatusText.kt}）。 */
public final class DialectPreviewStatusText {

    private DialectPreviewStatusText() {
    }

    /** 指示行可见文案 + 详情多行文案。 */
    public record Text(@NotNull String visible, @NotNull String details) {
    }

    @NotNull
    public static Text describe(@NotNull DialectDecisionEngine.Decision decision) {
        String title = MpSqlVisualizerBundle.message("panel.meta.dialectStatus");
        String status;
        switch (decision.status()) {
            case MANUAL -> status = MpSqlVisualizerBundle.message("panel.meta.dialectStatus.manual");
            case RESOLVED -> status = source(decision.source());
            case AMBIGUOUS -> status = MpSqlVisualizerBundle.message("panel.meta.dialectStatus.ambiguous");
            case CONFLICT -> status = MpSqlVisualizerBundle.message("panel.meta.dialectStatus.conflict");
            case UNAVAILABLE -> status = MpSqlVisualizerBundle.message(
                    decision.source() == DialectDecisionEngine.Source.IDEA_CONFIGURED_SCOPE
                            ? "panel.meta.dialectStatus.scopeUnavailable"
                            : "panel.meta.dialectStatus.unavailable");
            default -> status = MpSqlVisualizerBundle.message("panel.meta.dialectStatus.unavailable");
        }
        String rendered = decision.dialectName() != null
                ? decision.dialectName()
                : decision.compatibilityFallbackName() != null
                ? decision.compatibilityFallbackName()
                : "MySQL";
        String visible = decision.dialectName() == null
                ? MpSqlVisualizerBundle.message("panel.meta.dialectStatus.fallback", rendered, status)
                : title + ": " + rendered + " (" + status + ")";
        String confidence = MpSqlVisualizerBundle.message(
                "panel.meta.dialectStatus.confidence." + decision.confidence().name());
        StringJoiner joiner = new StringJoiner(", ");
        for (String id : decision.dataSourceIds()) {
            joiner.add(id);
        }
        String ids = joiner.length() > 0
                ? joiner.toString()
                : MpSqlVisualizerBundle.message("panel.meta.dialectStatus.noCandidates");
        return new Text(visible, MpSqlVisualizerBundle.message("panel.meta.dialectStatus.details",
                title, rendered, status, confidence, ids));
    }

    @NotNull
    private static String source(@Nullable DialectDecisionEngine.Source source) {
        if (source == null) {
            return MpSqlVisualizerBundle.message("panel.meta.dialectStatus.unavailable");
        }
        switch (source) {
            case MANUAL:
                return MpSqlVisualizerBundle.message("panel.meta.dialectStatus.manual");
            case CAPTURED_URL:
                return MpSqlVisualizerBundle.message("panel.meta.dialectStatus.runtime");
            case IDEA_SCOPE:
            case IDEA_CONFIGURED_SCOPE:
                return MpSqlVisualizerBundle.message("panel.meta.dialectStatus.scope");
            case IDEA_PROVIDER:
                return MpSqlVisualizerBundle.message("panel.meta.dialectStatus.provider");
            case IDEA_DIALECT:
            case IDEA_EFFECTIVE_DIALECT:
                return MpSqlVisualizerBundle.message("panel.meta.dialectStatus.ideaDialect");
            case LEARNED_MODULE:
                return MpSqlVisualizerBundle.message("panel.meta.dialectStatus.module");
            case BOUND_DATASOURCE:
                return MpSqlVisualizerBundle.message("panel.meta.dialectStatus.boundSource");
            case PROJECT_AGENT:
                return MpSqlVisualizerBundle.message("panel.meta.dialectStatus.agent");
            case PROJECT_CONSENSUS:
                return MpSqlVisualizerBundle.message("panel.meta.dialectStatus.projectConsensus");
            default:
                return MpSqlVisualizerBundle.message("panel.meta.dialectStatus.unavailable");
        }
    }
}
