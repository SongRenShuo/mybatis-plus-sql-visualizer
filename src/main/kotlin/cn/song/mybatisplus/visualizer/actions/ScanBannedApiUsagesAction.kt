package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.config.BannedApiConfigurable
import cn.song.mybatisplus.visualizer.inspection.BannedApiProjectScanner
import cn.song.mybatisplus.visualizer.inspection.BannedApiRuleManager
import cn.song.mybatisplus.visualizer.inspection.BannedApiScanReport
import cn.song.mybatisplus.visualizer.inspection.BannedApiScanResult
import cn.song.mybatisplus.visualizer.util.FatalErrors
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.io.File
import java.nio.file.Files

/**
 * 「统计禁用 API 存量」动作（FR-0045 第 2 项 T-BannedApiCheck S4，Tools 菜单）：
 * 对全项目 Java 文件做**只读** PSI 扫描，按规则统计命中，报告写到临时文件并在编辑器打开。
 *
 * ## 纪律（对应平台踩坑清单）
 *
 *  - **DumbService.isDumb 期间不跑**：索引构建期 resolve 不可靠，直接给提示让用户稍后再试
 *    （选择「不跑」而不是「runWhenSmart 排队」——全项目扫描排队到索引完成才开始，用户会以为
 *    动作没响应）；
 *  - **不在 EDT 扫**：进度任务 `Task.Backgroundable` 后台线程 + 可取消进度；PSI 读取整体包在
 *    `runReadAction` 内（后台线程持读锁，不占 EDT）；
 *  - **扫描失败可降级不崩**：异常吞掉并给错误通知；报告文件写盘失败时退化为**内存编辑器**展示
 *    全文（`LightVirtualFile`，不落盘也不弹框）；
 *  - **明细截断**：[BannedApiScanReport.MAX_DETAIL_ENTRIES] 上限，超出在报告尾部标注「已截断」。
 *
 * **反馈通道＝平台通知，零模态弹窗**（弹窗克制红线，见 `convention.BannedApiSourceScanTest`）：
 * 本动作的五条反馈（索引未就绪 / 无规则 / 扫描失败 / 无命中 / 报告落盘失败）全部走独立通知组
 * `cn.song.mybatisplus.visualizer.inspection`；「无规则」带一个「打开设置页」直达动作。
 *
 * 规则为空时零扫描，直接提示去设置页配置（默认安装不产生任何行为）。
 */
class ScanBannedApiUsagesAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (DumbService.isDumb(project)) {
            notify(
                project,
                MpSqlVisualizerBundle.message("scan.bannedApi.dumb"),
                NotificationType.WARNING,
            )
            return
        }
        val rules = BannedApiRuleManager.getInstance().compiledRules
        if (rules.isEmpty) {
            notify(
                project,
                MpSqlVisualizerBundle.message("scan.bannedApi.noRules"),
                NotificationType.INFORMATION,
            ) { notification ->
                notification.addAction(
                    NotificationAction.createSimpleExpiring(
                        MpSqlVisualizerBundle.message("scan.bannedApi.openSettings"),
                    ) {
                        if (project.isDisposed) return@createSimpleExpiring
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, BannedApiConfigurable::class.java)
                    },
                )
            }
            return
        }
        val title = MpSqlVisualizerBundle.message("scan.bannedApi.title")
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, title, true) {
                override fun run(indicator: ProgressIndicator) {
                    val result = try {
                        ApplicationManager.getApplication().runReadAction(
                            Computable { BannedApiProjectScanner.scan(project, rules, indicator) },
                        )
                    } catch (canceled: ProcessCanceledException) {
                        throw canceled
                    } catch (failure: Throwable) {
                        if (FatalErrors.isFatal(failure)) throw failure
                        LOG.warn("Banned API 存量扫描失败", failure)
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed) return@invokeLater
                            notify(
                                project,
                                MpSqlVisualizerBundle.message(
                                    "scan.bannedApi.failed",
                                    failure.message ?: failure.javaClass.simpleName,
                                ),
                                NotificationType.ERROR,
                            )
                        }
                        return
                    }
                    // 报告组装/写盘留在后台线程（大量字符串拼接 + 磁盘 IO 都不该占 EDT）
                    val report = BannedApiScanReport.build(
                        projectName = project.name,
                        scannedFiles = result.scannedFiles,
                        totalHits = result.totalHits,
                        hits = result.hits,
                        canceled = result.canceled,
                    )
                    val reportFile = try {
                        writeTempReport(report)
                    } catch (failure: Throwable) {
                        if (FatalErrors.isFatal(failure)) throw failure
                        LOG.warn("Banned API 报告写盘失败，降级为内存编辑器展示", failure)
                        null
                    }
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        present(project, result, report, reportFile)
                    }
                }
            },
        )
    }

    /**
     * EDT：无命中给通知；有命中优先打开磁盘报告，落盘失败则用内存编辑器（`LightVirtualFile`）
     * 直出全文——**不为展示报告弹模态框**（弹窗克制红线）。
     */
    private fun present(
        project: Project,
        result: BannedApiScanResult,
        report: String,
        reportFile: File?,
    ) {
        if (result.totalHits == 0 && !result.canceled) {
            notify(
                project,
                MpSqlVisualizerBundle.message("scan.bannedApi.noHits", result.scannedFiles.toString()),
                NotificationType.INFORMATION,
            )
            return
        }
        val onDisk: VirtualFile? = reportFile?.let {
            runCatching { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }.getOrNull()
        }
        val target: VirtualFile = onDisk ?: LightVirtualFile(REPORT_FILE_NAME, report)
        FileEditorManager.getInstance(project).openFile(target, true)
    }

    /**
     * 统一通知出口（一域一组：`cn.song.mybatisplus.visualizer.inspection`）。
     * 通知内容在 headless（测试 JVM）下也能构造，不依赖 EDT 之外的平台状态。
     */
    private fun notify(
        project: Project,
        content: String,
        type: NotificationType,
        customize: ((Notification) -> Unit)? = null,
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                MpSqlVisualizerBundle.message("scan.bannedApi.title"),
                content,
                type,
            )
        customize?.invoke(notification)
        notification.notify(project)
    }

    private fun writeTempReport(report: String): File {
        val file = Files.createTempFile("banned-api-report-", ".txt").toFile()
        file.writeText(report, Charsets.UTF_8)
        return file
    }

    private companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ScanBannedApiUsagesAction::class.java)

        /** 「禁用 API 存量统计」独立通知组（一域一组惯例，见 plugin.xml 的 notificationGroup 清单）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.inspection"

        /** 报告落盘失败时内存编辑器的文件名。 */
        private const val REPORT_FILE_NAME = "banned-api-report.txt"
    }
}
