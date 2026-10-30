package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.inspection.BannedApiProjectScanner
import cn.song.mybatisplus.visualizer.inspection.BannedApiRuleManager
import cn.song.mybatisplus.visualizer.inspection.BannedApiScanReport
import cn.song.mybatisplus.visualizer.inspection.BannedApiScanResult
import cn.song.mybatisplus.visualizer.util.FatalErrors
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
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
 *  - **扫描失败可降级不崩**：异常吞掉并给错误弹窗；报告文件写盘失败时退化为对话框展示全文；
 *  - **明细截断**：[BannedApiScanReport.MAX_DETAIL_ENTRIES] 上限，超出在报告尾部标注「已截断」。
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
        val title = MpSqlVisualizerBundle.message("scan.bannedApi.title")
        if (DumbService.isDumb(project)) {
            Messages.showInfoMessage(project, MpSqlVisualizerBundle.message("scan.bannedApi.dumb"), title)
            return
        }
        val rules = BannedApiRuleManager.getInstance().compiledRules
        if (rules.isEmpty) {
            Messages.showInfoMessage(project, MpSqlVisualizerBundle.message("scan.bannedApi.noRules"), title)
            return
        }
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
                            Messages.showErrorDialog(
                                project,
                                MpSqlVisualizerBundle.message(
                                    "scan.bannedApi.failed",
                                    failure.message ?: failure.javaClass.simpleName,
                                ),
                                title,
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
                        LOG.warn("Banned API 报告写盘失败，降级为对话框展示", failure)
                        null
                    }
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        present(project, result, report, reportFile, title)
                    }
                }
            },
        )
    }

    /** EDT：无命中给提示；有命中优先打开报告文件，打不开则对话直出全文（降级）。 */
    private fun present(
        project: Project,
        result: BannedApiScanResult,
        report: String,
        reportFile: File?,
        title: String,
    ) {
        if (result.totalHits == 0 && !result.canceled) {
            Messages.showInfoMessage(
                project,
                MpSqlVisualizerBundle.message("scan.bannedApi.noHits", result.scannedFiles.toString()),
                title,
            )
            return
        }
        val openedFile: VirtualFile? = reportFile?.let {
            runCatching { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }.getOrNull()
        }
        if (openedFile != null) {
            FileEditorManager.getInstance(project).openFile(openedFile, true)
        } else {
            Messages.showInfoMessage(project, report, title)
        }
    }

    private fun writeTempReport(report: String): File {
        val file = Files.createTempFile("banned-api-report-", ".txt").toFile()
        file.writeText(report, Charsets.UTF_8)
        return file
    }

    private companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ScanBannedApiUsagesAction::class.java)
    }
}
