package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * UI-048（2026-11-01 真机实证）：空 Runner 归因 → 用户文案的唯一出口。
 *
 * **缺陷**：单源工程「已连接 + 已表级内省 + 本文件未绑定」时点「执行 SQL」，面板报「已检测到 1 个
 * 数据源，但它们都还没有连接成功或尚未完成内省……」——这句在该状态下是假的。
 *
 * **成因**：平台 `DatabaseRunners.getAttachDataSourceRunners` 按「文件 → 数据源」绑定筛 Runner，
 * 未绑定必空（[DataSourceSelector] 的 W4 KDoc 早已记录「Database 视图已连接仍为 0」）；旧归因把
 * 「枚举不出 Runner」直接写成「没连上」的因果。
 *
 * **修法**：归因补一维**绑定态**——[ExecFileBinding.probeExecBind] 只在 [message] 这一层调用，
 * 判定内核仍是 [ExecDialectRouting.classifyEmptyRunners] 的可注入纯函数；文案每档只给用户能做的
 * 事，不断言未验证的因果。
 *
 * 之所以独占一个文件而不是留在 `DataSourceSelectorResults.kt` 里：后者已贴 2000 行巨文件红线
 * （`GiantFileBudgetTest`），归因→文案这段是天然可整体搬走的树枝。
 */
internal object EmptyRunnersHint {

    /**
     * 生产入口：探绑定态 → 判归因 → 出文案。调用方只需把结果交给 onError。
     *
     * @param execFile exec 草稿文件；null = 不探绑定（绑定态未知，退回既有口径）
     */
    internal fun message(
        project: Project,
        registeredDataSources: List<Any>,
        renderedDialectName: String?,
        execFile: VirtualFile?,
    ): String = messageFor(
        reason = ExecDialectRouting.classifyEmptyRunners(project, registeredDataSources, renderedDialectName, execFile),
        registeredCount = registeredDataSources.size,
        registeredNames = { registeredDataSources.joinToString(", ") { DataSourceSettings.getName(it) } },
        dialectDisplay = {
            // runners 为空若因「渲染方言在在册数据源里找不到匹配」，文案必须报渲染方言：此前从目标
            // 数据源反推会把 PG 预览说成「没有与该 SQL 方言（MySQL）匹配的数据源」（2026-10-25 实测）。
            renderedDialectName ?: run {
                val targetDs = ExecDialectRouting.resolveTargetDataSourceForDialect(project, renderedDialectName)
                val targetLanguage = ExecDialectRouting.resolveTargetLanguage(project, targetDs)
                (targetLanguage as? com.intellij.lang.Language)?.id ?: ExecDialectRouting.MYSQL_LANGUAGE_ID
            }
        },
    )

    /**
     * 归因 → 文案（纯装配；实参按需惰性求值，只有方言不匹配档才用得到方言名与源清单）。
     *
     * 分档口径（UI-048）：
     *  - 零在册源 → `execute.datasource.empty`（既有引导不变）；
     *  - 判不出方言 → `execute.datasource.notConnected`（措辞只说「平台此刻定位不到」，不把没连上当事实）；
     *  - 方言对得上但**未绑定** → `execute.datasource.notBound`（给绑定动作，与预览面板绑定行同口径）；
     *  - 方言对得上且**已绑定**仍无 Runner → `execute.datasource.notConnected`（此时连接/内省才站得住）；
     *  - 方言都对不上 → `execute.datasource.dialectMismatch`（报渲染方言）。
     */
    internal fun messageFor(
        reason: ExecDialectRouting.EmptyRunnersReason,
        registeredCount: Int,
        registeredNames: () -> String,
        dialectDisplay: () -> String,
    ): String = when (reason) {
        ExecDialectRouting.EmptyRunnersReason.NO_DATA_SOURCE ->
            MpSqlVisualizerBundle.message("execute.datasource.empty")
        ExecDialectRouting.EmptyRunnersReason.DIALECT_UNKNOWN,
        ExecDialectRouting.EmptyRunnersReason.DIALECT_MATCHED_BUT_UNUSABLE,
        -> MpSqlVisualizerBundle.message("execute.datasource.notConnected", registeredCount)
        ExecDialectRouting.EmptyRunnersReason.DIALECT_MATCHED_BUT_UNBOUND ->
            MpSqlVisualizerBundle.message("execute.datasource.notBound", registeredCount)
        ExecDialectRouting.EmptyRunnersReason.DIALECT_MISMATCH ->
            MpSqlVisualizerBundle.message("execute.datasource.dialectMismatch", dialectDisplay(), registeredNames())
    }
}
