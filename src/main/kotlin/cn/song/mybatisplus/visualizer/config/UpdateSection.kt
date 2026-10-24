package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.CheckResult
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.InstallResult
import cn.song.mybatisplus.visualizer.update.PluginVersion
import cn.song.mybatisplus.visualizer.update.UpdateManifest
import cn.song.mybatisplus.visualizer.update.UpdateSource
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.SafeBrowser
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.RestartDialogImpl
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane

/**
 * 设置页「更新」分区（M28）：当前版本 / 启动自动检查开关 / 检查更新 / 下载并安装 /
 * 重启提示——状态机渲染（Idle→Checking→UpToDate|Available→Downloading→Installed|Failed）。
 *
 * 异步流程全部经 [PluginUpdateRunner]（check/downloadAndInstall），本类只做 UI 编排与
 * 渲染（网络/平台安装不在本类）；测试可直接调用 [renderCheck]/[renderInstall] 驱动状态
 * （仿 ShortcutEditor 的组件内嵌模式，随语言切换经 [updateTexts] 刷新）。
 */
class UpdateSection(private val settings: PluginSettings) : JPanel(GridBagLayout()) {

    /** 分区状态（internal 供测试断言；状态渲染集中 [renderState]）。 */
    internal var state: State = State.Idle
        private set

    internal enum class State { Idle, Checking, UpToDate, Available, Downloading, Installed, InstalledLive, Failed }

    private var pendingManifest: UpdateManifest? = null

    private var failureReason: String? = null

    /** R62：最近一次检查失败的分类（渲染文案与兜底链接可见性分流）。 */
    private var failureKind: PluginUpdateRunner.FailureKind? = null

    /**
     * 检查编排 seam（T-M28.1）：测试注入 fake 不触网；默认走 [PluginUpdateRunner.check] 真实实现。
     * 与按钮绑定配套——交互测试经 doClick 驱动全链路。
     */
    internal var checkRunner: () -> PluginUpdateRunner.CheckResult =
        { PluginUpdateRunner.check(PluginVersion.current()) }

    /** 下载安装编排 seam（T-M28.1：测试注入 fake；默认真实实现，indicator 驱动进度条）。 */
    internal var installRunner: (UpdateManifest, ProgressIndicator?) -> PluginUpdateRunner.InstallResult =
        { manifest, indicator -> PluginUpdateRunner.downloadAndInstall(manifest, indicator) }

    /** 重启回调 seam（T-M28.1：测试注入 fake 不弹原生重启确认框）。 */
    internal var restartAction: () -> Unit = { RestartDialogImpl.restartWithConfirmation() }

    /**
     * 回滚编排 seam（T-SelfUpdateRollback W2，O-150）：测试注入 fake；默认走
     * [UpdateBackupStore.scheduleRestore]（校验备份→调度恢复命令→压忽略表，重启生效）。
     */
    internal var rollbackRunner: () -> cn.song.mybatisplus.visualizer.update.UpdateBackupStore.RestoreResult =
        { cn.song.mybatisplus.visualizer.update.UpdateBackupStore.scheduleRestore() }

    /** 回滚确认 seam（默认弹一次 Yes/No——回滚是重启级动作，属弹窗克制红线的「重大不可逆需确认」档）。 */
    internal var rollbackConfirm: () -> Boolean = {
        com.intellij.openapi.ui.Messages.showYesNoDialog(
            MpSqlVisualizerBundle.message("settings.update.rollback.confirm"),
            MpSqlVisualizerBundle.message("settings.update.rollback"),
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
        ) == com.intellij.openapi.ui.Messages.YES
    }

    /** 备份可用性查询 seam（按钮可用态刷新用；测试注入免盘）。 */
    internal var rollbackAvailability: () -> cn.song.mybatisplus.visualizer.update.UpdateBackupStore.BackupInfo? =
        { cn.song.mybatisplus.visualizer.update.UpdateBackupStore.currentBackup() }

    internal val versionLabel = JBLabel()
    internal val autoCheckCheck = JCheckBox()
    internal val checkButton = JButton()
    internal val statusLabel = JBLabel()
    internal val progressBar = JProgressBar()
    internal val notesArea = JBTextArea(5, 42)
    internal val installButton = JButton()
    internal val installedLabel = JBLabel()
    internal val restartButton = JButton()

    /** T-SelfUpdateRollback W2：回滚上一版按钮 + 结果状态行（备份可用才启用，tooltip 带旧版本号）。 */
    internal val rollbackButton = JButton()
    internal val rollbackStatusLabel = JBLabel()

    /** 变更日志标题（T-M28.4：标题行 = 左标题 + 右 Gitee 发布页链接）。 */
    internal val notesTitleLabel = JBLabel().apply { name = "notesTitle" }

    /** 变更日志 → Gitee 发布页链接（用户要求可点击跳转）。 */
    internal val giteeLink = LinkLabel<Any>(MpSqlVisualizerBundle.message("settings.update.viewOnGitee"), null) { _, _ ->
        SafeBrowser.browse(UpdateSource.GITEE_RELEASES_URL)
    }

    private val notesContainer = JPanel(BorderLayout(0, 2)).apply {
        add(JPanel(BorderLayout(8, 0)).apply {
            add(notesTitleLabel, BorderLayout.WEST)
            add(giteeLink, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JScrollPane(notesArea).apply {
            border = UIUtil.getTextFieldBorder()
            isVisible = false
        }, BorderLayout.CENTER)
    }

    private val doneContainer = JPanel(BorderLayout(8, 0)).apply {
        add(installedLabel, BorderLayout.WEST)
        add(restartButton, BorderLayout.EAST)
    }

    /** T-ErrReport：需求池报告入口（手动形态对话框 + 需求池网页链接）。 */
    internal val reportProblemButton = JButton()

    /** 需求池网页直达链接（浏览反馈/投票/进度）。 */
    internal val feedbackPoolLink = LinkLabel<Any>("", null) { _, _ ->
        SafeBrowser.browse(cn.song.mybatisplus.visualizer.feedback.FeedbackApi.SITE_BASE + "/about/feedback")
    }

    private val feedbackContainer = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        isOpaque = false
        add(reportProblemButton)
        add(feedbackPoolLink)
    }

    /**
     * R62：通道 404 态兜底链接行（Marketplace + Gitee Releases，SafeBrowser 打开）。
     * 仅 `Failure(CHANNEL_MISSING)` 且检查失败时可见——网络态不给（通道本身没断，
     * 给链接是误导）；常量复用 [UpdateSource.MARKETPLACE_URL]（本任务新增）与
     * [UpdateSource.GITEE_RELEASES_URL]（既有）。
     */
    internal val fallbackLinksContainer = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        isOpaque = false
        isVisible = false
        add(
            LinkLabel<Any>(MpSqlVisualizerBundle.message("settings.update.fallback.marketplace"), null) { _, _ ->
                SafeBrowser.browse(UpdateSource.MARKETPLACE_URL)
            },
        )
        add(
            LinkLabel<Any>(MpSqlVisualizerBundle.message("settings.update.fallback.gitee"), null) { _, _ ->
                SafeBrowser.browse(UpdateSource.GITEE_RELEASES_URL)
            },
        )
    }

    init {
        notesArea.isEditable = false
        notesArea.lineWrap = true
        notesArea.wrapStyleWord = true
        // T-M28.1：三按钮事件绑定（此前缺失——用户实测点击检查更新无反应；测试盲区：只测渲染未测交互）
        checkButton.addActionListener { runCheck() }
        installButton.addActionListener { runInstall() }
        restartButton.addActionListener { restartIde() }
        // T-ErrReport：报告问题（手动形态，无异常预填）
        reportProblemButton.addActionListener {
            cn.song.mybatisplus.visualizer.feedback.FeedbackReporting.openManualDialog()
        }
        // T-SelfUpdateRollback W2：回滚上一版（确认→调度恢复命令→提示重启）
        rollbackButton.addActionListener { runRollback() }
        buildLayout()
        resetState()
        updateTexts()
    }

    // ------------------------------------------------------------------
    // 布局（GridBag 行：版本/开关/按钮+状态/进度/日志/安装完成）
    // ------------------------------------------------------------------

    private fun buildLayout() {
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(2, 0, 2, 0)
        }
        fun row(component: javax.swing.JComponent) {
            gbc.gridx = 0
            gbc.gridy++
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            add(component, gbc)
        }

        // 版本内容行（当前版本 + 检查/安装按钮 + 状态反馈）；「版本信息」标签由设置面板
        // addRow 提供（统一标签列——与插件缓存/内部构建号左对齐，用户反馈对齐问题）
        row(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(versionLabel)
            add(checkButton)
            add(installButton)
            add(statusLabel)
        })
        row(autoCheckCheck)
        row(progressBar)
        row(notesContainer)
        row(doneContainer)
        // T-SelfUpdateRollback W2：回滚上一版行（按钮 + 结果状态）
        row(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(rollbackButton)
            add(rollbackStatusLabel)
        })
        // R62：通道 404 兜底链接行（仅该态可见）
        row(fallbackLinksContainer)
        // T-ErrReport：需求池报告入口（报告问题按钮 + 需求池网页链接）
        row(feedbackContainer)
        // 底部占位（与设置页整体一致：内容顶置）
        gbc.gridy++
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        add(JPanel(), gbc)
    }

    // ------------------------------------------------------------------
    // 状态机渲染
    // ------------------------------------------------------------------

    /** 检查结果渲染（EDT；测试直接调用）。 */
    internal fun renderCheck(result: CheckResult) {
        when (result) {
            is CheckResult.Available -> {
                pendingManifest = result.manifest
                notesArea.text = renderNotes(result.manifest)
                state = State.Available
            }
            CheckResult.UpToDate -> {
                pendingManifest = null
                state = State.UpToDate
            }
            is CheckResult.Failure -> {
                pendingManifest = null
                state = State.Failed
                failureReason = result.message
                failureKind = result.kind
            }
        }
        renderState()
    }

    /** 日志区内容：changeNotes + 兼容性警告（sinceBuild 高于当前 IDE 构建时提示不阻塞）。 */
    private fun renderNotes(manifest: UpdateManifest): String = buildString {
        append(manifest.changeNotes ?: "—")
        val current = currentBuildMajor()
        if (manifest.warnsIncompatible(current)) {
            append("\n").append(
                MpSqlVisualizerBundle.message("settings.update.incompatible", manifest.sinceBuild, current)
            )
        }
    }

    /** 当前 IDE 构建主号（如 `243`）；读取失败返回 null。 */
    private fun currentBuildMajor(): Int? =
        Regex("^\\d+").find(ApplicationInfo.getInstance().build.asStringWithoutProductCode())
            ?.value?.toIntOrNull()

    /** 安装结果渲染（EDT；测试直接调用）。三分支：热生效 / 回退重启 / 失败（保留 pendingManifest 区分文案）。 */
    internal fun renderInstall(result: InstallResult) {
        failureReason = (result as? InstallResult.Failed)?.message
        state = when (result) {
            is InstallResult.InstalledDynamic -> State.InstalledLive
            is InstallResult.InstalledRestartRequired -> State.Installed
            is InstallResult.Failed -> State.Failed
        }
        renderState()
    }

    private fun renderState() {
        val s = state
        val done = s == State.Installed || s == State.InstalledLive
        checkButton.isEnabled = !done && s != State.Checking && s != State.Downloading
        installButton.isVisible = s == State.Available || s == State.Downloading
        installButton.isEnabled = s == State.Available
        restartButton.isVisible = s == State.Installed
        installedLabel.isVisible = s == State.Installed
        progressBar.isVisible = s == State.Downloading
        progressBar.isIndeterminate = true
        notesContainer.isVisible = s == State.Available
        doneContainer.isVisible = s == State.Installed
        when (s) {
            State.Checking -> statusLabel.text = MpSqlVisualizerBundle.message("settings.update.checking")
            State.UpToDate -> statusLabel.text = MpSqlVisualizerBundle.message("settings.update.upToDate")
            State.Available -> statusLabel.text =
                MpSqlVisualizerBundle.message("settings.update.available", pendingManifest?.version ?: "?")
            State.Downloading -> statusLabel.text = MpSqlVisualizerBundle.message("settings.update.downloading")
            State.Installed -> statusLabel.text = MpSqlVisualizerBundle.message("settings.update.installed")
            State.InstalledLive -> statusLabel.text = MpSqlVisualizerBundle.message("settings.update.installedLive")
            State.Failed -> statusLabel.text = if (pendingManifest == null) {
                // R62：检查失败按 [PluginUpdateRunner.FailureKind] 分流文案（三态可诊断）；
                // 安装失败（pendingManifest 非空）维持原文案
                when (failureKind) {
                    PluginUpdateRunner.FailureKind.CHANNEL_MISSING ->
                        MpSqlVisualizerBundle.message("settings.update.fail.channel404", failureReason ?: "?")
                    PluginUpdateRunner.FailureKind.NETWORK ->
                        MpSqlVisualizerBundle.message("settings.update.fail.network", failureReason ?: "?")
                    else ->
                        MpSqlVisualizerBundle.message("settings.update.checkFailed", failureReason ?: "?")
                }
            } else {
                MpSqlVisualizerBundle.message("settings.update.installFailed", failureReason ?: "?")
            }
            State.Idle -> statusLabel.text = " "
        }
        // R62：兜底链接行只在「检查失败且通道缺失」态可见（网络态/安装失败/其他态一律隐藏）
        fallbackLinksContainer.isVisible =
            s == State.Failed && pendingManifest == null && failureKind == PluginUpdateRunner.FailureKind.CHANNEL_MISSING
    }

    /** 版本行/开关/日志与按钮文本刷新（语言切换 rebuild 时调用，仿 ShortcutEditor.updateTexts）。 */
    internal fun updateTexts() {
        versionLabel.text = MpSqlVisualizerBundle.message("settings.update.currentVersion") +
            ": " + (PluginVersion.current() ?: "—")
        autoCheckCheck.text = MpSqlVisualizerBundle.message("settings.update.autoCheck")
        autoCheckCheck.toolTipText = MpSqlVisualizerBundle.message("settings.update.autoCheck.tip")
        checkButton.text = MpSqlVisualizerBundle.message("settings.update.check")
        installButton.text = MpSqlVisualizerBundle.message("settings.update.downloadInstall")
        installedLabel.text = MpSqlVisualizerBundle.message("settings.update.installed")
        restartButton.text = MpSqlVisualizerBundle.message("settings.update.restartNow")
        notesTitleLabel.text = MpSqlVisualizerBundle.message("settings.update.changelog")
        giteeLink.text = MpSqlVisualizerBundle.message("settings.update.viewOnGitee")
        reportProblemButton.text = MpSqlVisualizerBundle.message("settings.update.reportProblem")
        feedbackPoolLink.text = MpSqlVisualizerBundle.message("settings.update.feedbackPool")
        rollbackButton.text = MpSqlVisualizerBundle.message("settings.update.rollback")
        refreshRollbackAvailability()
        renderState()
    }

    /**
     * 回滚按钮可用态（T-SelfUpdateRollback W2）：备份可用→启用+tooltip 带旧版本号；
     * 不可用→禁用+tooltip 说明（诚实降级：无回滚源绝不假装可点）。设置页打开/语言切换时刷新。
     */
    internal fun refreshRollbackAvailability() {
        val backup = runCatching { rollbackAvailability() }.getOrNull()
        if (backup != null) {
            rollbackButton.isEnabled = true
            rollbackButton.toolTipText =
                MpSqlVisualizerBundle.message("settings.update.rollback.tooltip.available", backup.fromVersion)
        } else {
            rollbackButton.isEnabled = false
            rollbackButton.toolTipText = MpSqlVisualizerBundle.message("settings.update.rollback.tooltip.none")
        }
    }

    /** 执行回滚（确认→seam 调度→按结果渲染）。 */
    internal fun runRollback() {
        if (!rollbackButton.isEnabled) return
        if (!runCatching { rollbackConfirm() }.getOrDefault(false)) return
        when (val result = runCatching { rollbackRunner() }.getOrNull()) {
            is cn.song.mybatisplus.visualizer.update.UpdateBackupStore.RestoreResult.Scheduled -> {
                rollbackStatusLabel.text =
                    MpSqlVisualizerBundle.message("settings.update.rollback.scheduled", result.ignoredVersion)
                rollbackButton.isEnabled = false
            }
            is cn.song.mybatisplus.visualizer.update.UpdateBackupStore.RestoreResult.NoBackup -> {
                rollbackStatusLabel.text =
                    MpSqlVisualizerBundle.message("settings.update.rollback.noBackup")
            }
            is cn.song.mybatisplus.visualizer.update.UpdateBackupStore.RestoreResult.Failed -> {
                rollbackStatusLabel.text =
                    MpSqlVisualizerBundle.message("settings.update.rollback.failed", result.reason)
            }
            null -> {
                rollbackStatusLabel.text =
                    MpSqlVisualizerBundle.message("settings.update.rollback.failed", "unexpected error")
            }
        }
    }

    /** 从配置加载（Settings 打开/Reset 时调用）。 */
    internal fun resetState() {
        autoCheckCheck.isSelected = settings.state.autoCheckUpdate
        pendingManifest = null
        failureReason = null
        failureKind = null
        state = State.Idle
        renderState()
    }

    // ------------------------------------------------------------------
    // 异步流程（Task.Backgroundable；回调 EDT + 判活，面板销毁则静默）
    // ------------------------------------------------------------------

    /** 检查更新（防重入：Checking/Downloading 中忽略）。 */
    internal fun runCheck() {
        if (state == State.Checking || state == State.Downloading) return
        state = State.Checking
        renderState()
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            null,
            MpSqlVisualizerBundle.message("update.task.check"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                val result = checkRunner()
                ApplicationManager.getApplication().invokeLater {
                    if (isDisplayable) renderCheck(result)
                }
            }
        })
    }

    /** 下载并安装（进度条 fraction 经 [SectionProgress] 驱动；防重入：Downloading 中忽略）。 */
    internal fun runInstall() {
        val manifest = pendingManifest ?: return
        if (state == State.Downloading) return
        state = State.Downloading
        renderState()
        val progress = SectionProgress(progressBar)
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            null,
            MpSqlVisualizerBundle.message("update.task.download"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                val result = installRunner(manifest, progress)
                ApplicationManager.getApplication().invokeLater {
                    if (isDisplayable) renderInstall(result)
                }
            }
        })
    }

    /** 重启 IDE（IDE 原生重启确认框，可选「稍后」——用户拍板，不自动重启）。 */
    internal fun restartIde() {
        restartAction()
    }

    /** 进度条适配：下载 fraction → 确定态进度（EDT 更新）。 */
    private class SectionProgress(private val bar: JProgressBar) : ProgressIndicatorBase() {
        override fun setFraction(fraction: Double) {
            super.setFraction(fraction)
            val value = (fraction * 100).toInt().coerceIn(0, 100)
            ApplicationManager.getApplication().invokeLater {
                bar.isIndeterminate = fraction <= 0
                bar.value = value
            }
        }
    }
}
