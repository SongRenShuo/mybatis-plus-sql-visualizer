package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import org.jetbrains.annotations.Nullable;

/**
 * master 的 {@code internal object DialectSettingsStatusText} 的 Java 承载（全 static 形态）。
 */
final class DialectSettingsStatusText {

    private DialectSettingsStatusText() {
    }

    static String describe(int projectCount, @Nullable DialectDecisionEngine.Decision decision) {
        if (projectCount == 0) {
            return MpSqlVisualizerBundle.message("settings.dialect.inferred.noProject");
        }
        if (projectCount != 1) {
            return MpSqlVisualizerBundle.message("settings.dialect.inferred.multipleProjects");
        }
        DialectDecisionEngine.Status status = decision != null ? decision.status() : null;
        if (status == DialectDecisionEngine.Status.MANUAL) {
            return MpSqlVisualizerBundle.message("settings.dialect.inferred.projectManual",
                    decision.dialectName() == null ? "" : decision.dialectName());
        }
        if (status == DialectDecisionEngine.Status.RESOLVED) {
            String source;
            if (decision.source() == DialectDecisionEngine.Source.PROJECT_CONSENSUS) {
                source = MpSqlVisualizerBundle.message("settings.dialect.inferred.projectCandidates");
            } else if (decision.source() == DialectDecisionEngine.Source.PROJECT_AGENT) {
                source = MpSqlVisualizerBundle.message("settings.dialect.inferred.projectAgent");
            } else if (decision.source() == DialectDecisionEngine.Source.PROJECT_CONFIG) {
                // T-DialectInferFix：没有 IDE 数据源时的三层证据，必须明说「据什么判的」
                source = MpSqlVisualizerBundle.message("settings.dialect.inferred.projectConfig");
            } else if (decision.source() == DialectDecisionEngine.Source.PROJECT_DEPENDENCY) {
                source = MpSqlVisualizerBundle.message("settings.dialect.inferred.projectDependency");
            } else if (decision.source() == DialectDecisionEngine.Source.SQL_SYNTAX) {
                source = MpSqlVisualizerBundle.message("settings.dialect.inferred.sqlSyntax");
            } else if (decision.source() == DialectDecisionEngine.Source.IDEA_DIALECT
                || decision.source() == DialectDecisionEngine.Source.IDEA_EFFECTIVE_DIALECT) {
                source = MpSqlVisualizerBundle.message("settings.dialect.inferred.ideaDialect");
            } else {
                source = MpSqlVisualizerBundle.message("settings.dialect.inferred.otherSource");
            }
            return MpSqlVisualizerBundle.message("settings.dialect.inferred.withSource",
                    decision.dialectName() == null ? "" : decision.dialectName(), source);
        }
        if (status == DialectDecisionEngine.Status.AMBIGUOUS) {
            return MpSqlVisualizerBundle.message("settings.dialect.inferred.ambiguous");
        }
        if (status == DialectDecisionEngine.Status.CONFLICT) {
            return MpSqlVisualizerBundle.message("settings.dialect.inferred.conflict");
        }
        return MpSqlVisualizerBundle.message("settings.dialect.inferred.unavailable");
    }
}
