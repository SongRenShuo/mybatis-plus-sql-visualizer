package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

internal object DialectSettingsStatusText {
    fun describe(projectCount: Int, decision: DialectDecisionEngine.Decision?): String {
        if (projectCount == 0) return MpSqlVisualizerBundle.message("settings.dialect.inferred.noProject")
        if (projectCount != 1) return MpSqlVisualizerBundle.message("settings.dialect.inferred.multipleProjects")
        return when (decision?.status) {
            DialectDecisionEngine.Status.MANUAL -> MpSqlVisualizerBundle.message(
                "settings.dialect.inferred.projectManual", decision.dialectName.orEmpty(),
            )
            DialectDecisionEngine.Status.RESOLVED -> {
                val source = when (decision.source) {
                    DialectDecisionEngine.Source.PROJECT_CONSENSUS ->
                        MpSqlVisualizerBundle.message("settings.dialect.inferred.projectCandidates")
                    DialectDecisionEngine.Source.PROJECT_AGENT ->
                        MpSqlVisualizerBundle.message("settings.dialect.inferred.projectAgent")
                    // T-DialectInferFix：没有 IDE 数据源时的三层证据，必须明说「据什么判的」
                    DialectDecisionEngine.Source.PROJECT_CONFIG ->
                        MpSqlVisualizerBundle.message("settings.dialect.inferred.projectConfig")
                    DialectDecisionEngine.Source.PROJECT_DEPENDENCY ->
                        MpSqlVisualizerBundle.message("settings.dialect.inferred.projectDependency")
                    DialectDecisionEngine.Source.SQL_SYNTAX ->
                        MpSqlVisualizerBundle.message("settings.dialect.inferred.sqlSyntax")
                    DialectDecisionEngine.Source.IDEA_DIALECT,
                    DialectDecisionEngine.Source.IDEA_EFFECTIVE_DIALECT ->
                        MpSqlVisualizerBundle.message("settings.dialect.inferred.ideaDialect")
                    else -> MpSqlVisualizerBundle.message("settings.dialect.inferred.otherSource")
                }
                MpSqlVisualizerBundle.message("settings.dialect.inferred.withSource", decision.dialectName.orEmpty(), source)
            }
            DialectDecisionEngine.Status.AMBIGUOUS -> MpSqlVisualizerBundle.message("settings.dialect.inferred.ambiguous")
            DialectDecisionEngine.Status.CONFLICT -> MpSqlVisualizerBundle.message("settings.dialect.inferred.conflict")
            else -> MpSqlVisualizerBundle.message("settings.dialect.inferred.unavailable")
        }
    }
}
