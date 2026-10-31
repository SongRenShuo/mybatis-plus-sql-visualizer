package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.history.ExplainPlanSnapshot
import cn.song.mybatisplus.visualizer.history.SqlHistoryEntry
import cn.song.mybatisplus.visualizer.share.format.TraceDocFormatter
import cn.song.mybatisplus.visualizer.share.format.TraceDocInput
import cn.song.mybatisplus.visualizer.share.format.TracePlanSnapshot
import cn.song.mybatisplus.visualizer.util.DisplayMasker
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * T-TraceDocExport S2：优化留痕 Markdown 的**出口装配**（取数 → [TraceDocInput] → 选文件 → 后台写盘）。
 *
 * 组装与渲染在纯逻辑 [TraceDocFormatter]；本对象只做平台侧投影与写盘，形态照抄
 * [HistorySqlLogPanel.exportEntries]（FileSaverDescriptor + pooled thread + 通知），不自建异步框架。
 *
 * 取数口径：
 *  - 历史条目（[SqlHistoryEntry]）：sql / executionTimeMs / explainPlans；方言取最新计划快照的
 *    `dialect`（历史条目本身不存方言）。
 *  - 日志/历史页条目（[LogSqlEntry]）：restoredSql（参数已回填）/ executionTimeMs / 来源位置 /
 *    dialectName；计划由调用方按「同项目 + 同 SQL」从 [cn.song.mybatisplus.visualizer.history.SqlHistoryManager]
 *    精确取回（跨项目不借用，R91 口径）。
 *
 * fail-closed：payload 解析不出（[ExplainPlanSnapshot.raw] 为 null）的快照**整份丢弃**，
 * 绝不把空壳当计划渲染。
 */
object TraceDocExport {

    /** 复用历史/预览面板既有通知组（plugin.xml 已注册）。 */
    private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

    /** 历史域条目 → 留痕输入。 */
    fun fromHistoryEntry(entry: SqlHistoryEntry): TraceDocInput = TraceDocInput(
        sql = entry.sql,
        dialect = entry.explainPlans.maxByOrNull { it.capturedAt }?.dialect?.takeIf { it.isNotBlank() },
        source = entry.sourceLabel().takeIf { it.isNotBlank() },
        capturedAt = entry.timestamp,
        executionTimeMs = entry.executionTimeMs,
        plans = entry.explainPlans.mapNotNull(::planSnapshot).sortedBy { it.capturedAt },
    )

    /** 日志/历史页条目 → 留痕输入（计划由调用方精确匹配后传入）。 */
    fun fromLogEntry(entry: LogSqlEntry, plans: List<ExplainPlanSnapshot>): TraceDocInput = TraceDocInput(
        sql = entry.restoredSql.ifBlank { entry.rawSql },
        dialect = entry.dialectName?.takeIf { it.isNotBlank() },
        source = buildString {
            if (!entry.sourceFile.isNullOrBlank()) {
                append(entry.sourceFile)
                if (entry.sourceLine > 0) append(':').append(entry.sourceLine)
            }
        }.takeIf { it.isNotBlank() },
        capturedAt = entry.timestamp,
        executionTimeMs = entry.executionTimeMs,
        plans = plans.mapNotNull(::planSnapshot).sortedBy { it.capturedAt },
    )

    /** 持久化快照 → 纯数据快照；payload 不可解析返回 null（调用方丢弃）。 */
    fun planSnapshot(snapshot: ExplainPlanSnapshot): TracePlanSnapshot? =
        snapshot.raw()?.let { raw ->
            TracePlanSnapshot(
                capturedAt = snapshot.capturedAt,
                format = snapshot.format,
                headers = raw.headers,
                rows = raw.rows,
                truncated = raw.truncated,
            )
        }

    /** 选文件 + 后台写盘 + 结果通知（取消则静默返回）。 */
    fun export(project: Project, input: TraceDocInput) {
        // 与 SqlExportService 同口径：显示脱敏开关开时只对本次导出的 SQL 做投影级打码，
        // 调用方数据层条目不动（计划文本不打码——打码器会把数字一并抹掉，失去诊断价值）。
        // 2026-10-31 修正：改用 displayMaskExportOn()（离开 IDE 面的从严口径，mode != OFF 即打码），
        // 不再读旧布尔 state.displayMaskEnabled——后者是迁移源，用户在新三档设置里选 WARN/BLOCK
        // 时它仍为 false，会让留痕导出完全不打码（敏感数据离开 IDE 面），与同族三条导出链口径分叉。
        val effective = if (PluginSettings.getInstance().displayMaskExportOn()) {
            input.copy(sql = DisplayMasker.maskDisplay(input.sql, true))
        } else {
            input
        }
        val content = TraceDocFormatter.format(effective)
        val descriptor = FileSaverDescriptor(
            MpSqlVisualizerBundle.message("log.traceDoc.export"),
            MpSqlVisualizerBundle.message("log.traceDoc.export.descriptor"),
            "md",
        )
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val saved = dialog.save(null as VirtualFile?, TraceDocFormatter.defaultFileName()) ?: return
        val target = saved.file
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { SqlExportService.exportToFile(target, content) }
                .onSuccess {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP_ID)
                        .createNotification(
                            MpSqlVisualizerBundle.message("log.traceDoc.export.done", target.absolutePath),
                            NotificationType.INFORMATION,
                        ).notify(project)
                }
                .onFailure {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP_ID)
                        .createNotification(
                            MpSqlVisualizerBundle.message("log.traceDoc.export.failed", it.message ?: ""),
                            NotificationType.ERROR,
                        ).notify(project)
                }
        }
    }
}
