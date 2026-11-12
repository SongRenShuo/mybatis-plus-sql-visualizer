package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.update.DevUpdateMode
import cn.song.mybatisplus.visualizer.update.LocalPackage
import cn.song.mybatisplus.visualizer.update.LocalPackageScanner
import cn.song.mybatisplus.visualizer.update.LocalUpdateDir
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.CheckResult
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.InstallResult
import cn.song.mybatisplus.visualizer.update.PluginVersion
import cn.song.mybatisplus.visualizer.update.ScanResult
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
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
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

    /**
     * 本次（最近一次）检查走的通道（T-DevUpdateChannel / T-LocalUpdateDir）：决定读哪个来源
     * 与状态行后缀。LOCAL = 本地安装包目录（T-LocalUpdateDir）。
     */
    internal enum class Channel { STABLE, PREVIEW, LOCAL }

    /** 最近一次检查所用通道（渲染时给状态行加「（开发版通道）」「（本地目录）」后缀）。 */
    internal var channel: Channel = Channel.STABLE
        private set

    private var pendingManifest: UpdateManifest? = null

    /** T-LocalUpdateDir：本地通道的待装包（[runInstall] 按通道分流取用）。 */
    internal var pendingLocalPackage: LocalPackage? = null
        private set

    private var failureReason: String? = null

    /** T-DevUpdateChannel：最近一次安装失败是否「包还没发布」（404/410，友好文案 + 发布页链接）。 */
    private var notPublished: Boolean = false

    /** R62：最近一次检查失败的分类（渲染文案与兜底链接可见性分流）。 */
    private var failureKind: PluginUpdateRunner.FailureKind? = null

    /**
     * 检查编排 seam（T-M28.1）：测试注入 fake 不触网；默认走 [PluginUpdateRunner.check] 真实实现。
     * 与按钮绑定配套——交互测试经 doClick 驱动全链路。
     *
     * T-DevUpdateChannel：入参为本通道实际读取的清单 URL（正式=update.json / 开发版=preview.update.json），
     * 测试据此断言分流；既有 `{ calls++; … }` 注入写法（忽略入参）继续有效。
     */
    internal var checkRunner: (String) -> PluginUpdateRunner.CheckResult =
        { url -> PluginUpdateRunner.check(PluginVersion.current(), url) }

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
     * 本地目录扫描编排 seam（T-LocalUpdateDir）：测试注入 fake 不触盘；默认真实扫描。
     * 入参为目录原文（[localDirField] 当前文本），产出 [ScanResult]——状态行与状态机共用同一读数。
     */
    internal var localScanRunner: (String) -> ScanResult =
        { dir -> LocalPackageScanner.scan(dir, PluginVersion.current(), LocalPackageScanner.sharedCache, platformPluginsDir()) }

    /** 本地安装编排 seam（T-LocalUpdateDir：测试注入 fake 不触平台安装；默认真实 [PluginUpdateRunner.installFromLocal]）。 */
    internal var localInstallRunner: (LocalPackage, ProgressIndicator?) -> PluginUpdateRunner.InstallResult =
        { pkg, indicator -> PluginUpdateRunner.installFromLocal(pkg, indicator) }

    /**
     * 打开目录 seam（T-LocalUpdateDir：测试注入断言）。
     *
     * 默认走平台 [com.intellij.ide.actions.ShowFilePathAction.openFile]——它按操作系统的
     * **文件管理器**打开路径；此前用 `BrowserUtil.open(file: URI)` 在 Windows 上是**静默无效**
     * （真机 2026-11-12 反馈「点打开没反应」：浏览器不处理目录 URI，失败也不抛）。
     */
    internal var localDirectoryOpener: (java.nio.file.Path) -> Unit = { dir ->
        com.intellij.ide.actions.ShowFilePathAction.openFile(dir.toFile())
    }

    private fun platformPluginsDir(): java.nio.file.Path? =
        runCatching { com.intellij.openapi.application.PathManager.getPluginsDir() }.getOrNull()

    internal val versionLabel = JBLabel()
    internal val autoCheckCheck = JCheckBox()
    internal val checkButton = JButton()

    // ------------------------------------------------------------------
    // 本地安装包目录（T-LocalUpdateDir）：控件 + 校验状态行 + 防抖
    // ------------------------------------------------------------------

    internal val localDirField = JBTextField(26)

    /** 行内标签（面板统一标签列装不下嵌套行，这里自带一个；搜索索引经同 key 配对，SettingsPanelSearchableGuardTest）。 */
    internal val localDirLabel = JBLabel()

    internal val browseLocalDirButton = JButton()
    internal val openLocalDirButton = JButton()
    internal val localCheckButton = JButton()

    /** 目录合法性/扫描状态行（EDT 单点渲染 [renderLocalDirStatus]；动态文案，不进搜索索引）。 */
    internal val localDirStatusLabel = JBLabel(" ")

    /** 最近一次扫描读数（语言切换 [updateTexts] 重渲染状态行用；null = 尚未扫过）。 */
    private var lastLocalScan: ScanResult? = null

    /** 输入防抖（EDT 单发定时器；[disposeUiResources] 必须停掉，防面板销毁后回调写 UI）。 */
    private val localDirDebounceTimer = javax.swing.Timer(LOCAL_DIR_DEBOUNCE_MS) { validateLocalDirAsync() }.apply {
        isRepeats = false
    }

    /** 本地安装包目录行容器（标签 + 字段 + 浏览 + 打开 + 检测；状态行单独一行，见 [localDirStatusLabel]）。 */
    internal val localDirRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        isOpaque = false
        add(localDirLabel)
        add(localDirField)
        add(browseLocalDirButton)
        add(openLocalDirButton)
        add(localCheckButton)
    }

    /**
     * 「检测更新（开发版）」按钮（T-DevUpdateChannel）：构造即隐藏，`DevUpdateMode.isEnabled()`
     * 为真时可见。构造之后的可见性**只由 [applyDevVisibility] 决定**（[updateTexts] 与解锁回调
     * 都走它），不在别处直接赋值——否则语言切换 rebuild 会把按钮弄丢。
     */
    internal val checkPreviewButton = JButton().apply { isVisible = false }

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
        // T-DarkTheme：滚动容器换平台 JBScrollPane。自建原生 JScrollPane 的 viewport 是裸 JViewport
        // （背景取 Viewport.background，不跟随所载组件）、四角无 Corner 组件；平台 JBScrollPane 用
        // JBViewport（背景取所载 view）+ 四角 Corner。此处外层边框按原样覆盖为文本框边框，不受影响。
        add(JBScrollPane(notesArea).apply {
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
        checkButton.addActionListener { runCheck(Channel.STABLE) }
        checkPreviewButton.addActionListener { runCheck(Channel.PREVIEW) }
        installButton.addActionListener { runInstall() }
        restartButton.addActionListener { restartIde() }
        // T-LocalUpdateDir：本地目录行交互（浏览/打开/检测 + 输入防抖校验）
        browseLocalDirButton.addActionListener { browseLocalDir() }
        openLocalDirButton.addActionListener { openLocalDir() }
        localCheckButton.addActionListener { runCheck(Channel.LOCAL) }
        localDirField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = scheduleLocalDirValidation()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = scheduleLocalDirValidation()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = scheduleLocalDirValidation()
        })
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

    /** 输入防抖入口：重置单发定时器（400ms 内连续输入只触发最后一次，计划 §3.5）。 */
    private fun scheduleLocalDirValidation() {
        localDirDebounceTimer.stop()
        localDirDebounceTimer.start()
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
            add(checkPreviewButton)
            add(installButton)
            add(statusLabel)
        })
        // T-LocalUpdateDir：本地安装包目录行（版本信息正下方，计划 §3.5）
        row(localDirRow)
        row(localDirStatusLabel)
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
        notPublished = false
        when (result) {
            is CheckResult.Available -> {
                pendingManifest = result.manifest
                notesArea.text = renderNotes(result.manifest)
                // T-LocalUpdateDir：远端通道独占「变更日志 + Gitee 链接」表头；本地通道渲染时另设
                notesTitleLabel.text = MpSqlVisualizerBundle.message("settings.update.changelog")
                giteeLink.isVisible = true
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

    /**
     * 本地扫描结果 → 状态机渲染（T-LocalUpdateDir，EDT；测试直接调用）。
     * DirInvalid/Empty/NoValidPackage/Failed 都归 [State.Failed]（failureReason 用结构化原因，
     * failureKind 置 null 走通用 checkFailed 分支）；Available 时置 [pendingLocalPackage]，
     * 日志区显示本地包详情行（无 changeNotes，不放 Gitee 链接）。
     */
    internal fun renderLocalCheck(result: ScanResult) {
        notPublished = false
        failureKind = null
        pendingManifest = null
        pendingLocalPackage = null
        when (result) {
            ScanResult.Disabled -> {
                state = State.Idle
                renderState()
                return
            }
            is ScanResult.Available -> {
                pendingLocalPackage = result.latest
                notesArea.text = MpSqlVisualizerBundle.message(
                    "settings.update.local.package",
                    result.latest.fileName,
                    renderFileSize(result.latest.sizeBytes),
                )
                notesTitleLabel.text = MpSqlVisualizerBundle.message("settings.update.local.package.title")
                giteeLink.isVisible = false
                state = State.Available
            }
            is ScanResult.UpToDate -> state = State.UpToDate
            is ScanResult.DirInvalid -> {
                state = State.Failed
                failureReason = describeLocalDirCheck(result.check)
            }
            is ScanResult.Empty -> {
                state = State.Failed
                failureReason = MpSqlVisualizerBundle.message("settings.update.local.empty")
            }
            is ScanResult.NoValidPackage -> {
                state = State.Failed
                failureReason = MpSqlVisualizerBundle.message("settings.update.local.noValid", result.skipped)
            }
            is ScanResult.Failed -> {
                state = State.Failed
                failureReason = result.reason
            }
        }
        renderState()
    }

    /** 目录合法性/扫描状态行渲染（EDT 单点；只动 [localDirStatusLabel]，不翻状态机）。 */
    internal fun renderLocalDirStatus(result: ScanResult) {
        lastLocalScan = result
        val status: String
        when (result) {
            ScanResult.Disabled -> {
                localDirStatusLabel.text = MpSqlVisualizerBundle.message("settings.update.local.disabled")
                localDirStatusLabel.toolTipText = null
                return
            }
            is ScanResult.DirInvalid -> status = describeLocalDirCheck(result.check)
            is ScanResult.Empty -> status = MpSqlVisualizerBundle.message("settings.update.local.empty")
            is ScanResult.NoValidPackage -> status = MpSqlVisualizerBundle.message("settings.update.local.noValid", result.skipped)
            is ScanResult.UpToDate -> status = MpSqlVisualizerBundle.message(
                "settings.update.local.ready.upToDate", result.total, result.latest.version,
            )
            is ScanResult.Available -> status = MpSqlVisualizerBundle.message(
                "settings.update.local.ready", result.total, result.latest.version,
            )
            is ScanResult.Failed -> status = result.reason
        }
        localDirStatusLabel.text = status
        localDirStatusLabel.toolTipText = buildLocalStatusTooltip(result)
    }

    /** 状态行 tooltip：跳过明细 + 截断提示（计划 §3.3 规则 2「不静默吞」）。 */
    private fun buildLocalStatusTooltip(result: ScanResult): String? {
        val samples = when (result) {
            is ScanResult.NoValidPackage -> result.skippedSamples
            else -> emptyList()
        }
        val truncated = when (result) {
            is ScanResult.NoValidPackage -> result.truncated
            is ScanResult.UpToDate -> result.truncated
            is ScanResult.Available -> result.truncated
            else -> false
        }
        val parts = buildList {
            if (samples.isNotEmpty()) {
                add(MpSqlVisualizerBundle.message("settings.update.local.skippedDetail", samples.joinToString("；")))
            }
            if (truncated) {
                add(MpSqlVisualizerBundle.message("settings.update.local.truncated", LocalPackageScanner.MAX_CANDIDATES))
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** DirCheck 各态 → 面向用户的一句原因（i18n）。 */
    private fun describeLocalDirCheck(check: LocalUpdateDir.DirCheck): String = when (check) {
        LocalUpdateDir.DirCheck.Disabled ->
            MpSqlVisualizerBundle.message("settings.update.local.disabled")
        is LocalUpdateDir.DirCheck.InvalidPath ->
            MpSqlVisualizerBundle.message("settings.update.local.invalidPath", check.reason)
        LocalUpdateDir.DirCheck.EnvVarUnsupported ->
            MpSqlVisualizerBundle.message("settings.update.local.envVar")
        LocalUpdateDir.DirCheck.Relative ->
            MpSqlVisualizerBundle.message("settings.update.local.relative")
        LocalUpdateDir.DirCheck.NotFound ->
            MpSqlVisualizerBundle.message("settings.update.local.notFound")
        LocalUpdateDir.DirCheck.Inaccessible ->
            MpSqlVisualizerBundle.message("settings.update.local.inaccessible")
        LocalUpdateDir.DirCheck.NotDirectory ->
            MpSqlVisualizerBundle.message("settings.update.local.notDirectory")
        LocalUpdateDir.DirCheck.NotReadable ->
            MpSqlVisualizerBundle.message("settings.update.local.notReadable")
        LocalUpdateDir.DirCheck.InsidePluginsDir ->
            MpSqlVisualizerBundle.message("settings.update.local.insidePluginsDir")
        is LocalUpdateDir.DirCheck.Ok -> ""
    }

    /** 文件大小展示（KB/MB 一位小数；与通道无关的纯展示）。 */
    private fun renderFileSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * 输入防抖到期：BGT 跑一遍扫描（合法性 + 包发现），EDT 回渲染状态行。
     * 只动 [localDirStatusLabel]，**不翻状态机**——状态机只在用户点「检测本地更新」或启动检测时前进。
     */
    private fun validateLocalDirAsync() {
        val dirRaw = localDirField.text
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { localScanRunner(dirRaw) }.getOrElse {
                ScanResult.Failed(it.message ?: it.toString())
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                // 不按 isDisplayable 丢弃：面板在懒加载的配置页里可能尚未挂载，丢弃会让状态行
                // 永久停在旧读数（真机 2026-11-12 症状之一）。设置文本本身无副作用。
                renderLocalDirStatus(result)
            }
        }
    }

    /** 浏览目录（照抄 browseAutoSavePath 形态：只选目录，起始 user.home）；选完立即校验，不等防抖。 */
    private fun browseLocalDir() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(
            false, true, false, false, false, false,
        ).withTitle(MpSqlVisualizerBundle.message("settings.update.localDir.browse.tip"))
        val baseDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByIoFile(java.io.File(System.getProperty("user.home")))
        val dir = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, null, baseDir)
        if (dir != null) {
            localDirDebounceTimer.stop()
            localDirField.text = dir.path
            validateLocalDirAsync()
        }
    }

    /**
     * 打开目录（配置合法才开）。失败不再静默：异常与「平台没开成」都写回状态行，
     * 用户能区分「路径不对」与「系统没响应」。
     */
    private fun openLocalDir() {
        val check = LocalUpdateDir.parseWithPlatformPluginsDir(localDirField.text)
        if (check !is LocalUpdateDir.DirCheck.Ok) {
            renderLocalDirStatus(ScanResult.DirInvalid(check))
            return
        }
        runCatching { localDirectoryOpener(check.dir) }
            .onFailure { localDirStatusLabel.text = it.message ?: it.toString() }
    }

    /**
     * 持久化值：可解析则存归一路径（`~` 展开后、normalize 过），错误态存 trim 原文——
     * 重开页面后状态行仍能按原文报出原因（计划 §3.1）。
     */
    internal fun normalizedDirForPersist(): String {
        val raw = localDirField.text.trim()
        val check = LocalUpdateDir.parseWithPlatformPluginsDir(raw)
        return if (check is LocalUpdateDir.DirCheck.Ok) check.dir.toString() else raw
    }

    /** 面板销毁钩子（[PluginSettingsPanel.disposeResources] 调用）：停防抖定时器防销毁后回写。 */
    internal fun disposeUiResources() {
        localDirDebounceTimer.stop()
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

    /**
     * 安装结果渲染（EDT；测试直接调用）。四分支：热生效 / 回退重启 / 未发布（404 友好态）/ 失败
     * （保留 pendingManifest 区分文案）。
     */
    internal fun renderInstall(result: InstallResult) {
        failureReason = (result as? InstallResult.Failed)?.message
        notPublished = result is InstallResult.NotPublished
        state = when (result) {
            is InstallResult.InstalledDynamic -> State.InstalledLive
            is InstallResult.InstalledRestartRequired -> State.Installed
            is InstallResult.NotPublished -> State.Failed
            is InstallResult.Failed -> State.Failed
        }
        renderState()
    }

    private fun renderState() {
        val s = state
        val done = s == State.Installed || s == State.InstalledLive
        checkButton.isEnabled = !done && s != State.Checking && s != State.Downloading
        checkPreviewButton.isEnabled = checkButton.isEnabled
        localCheckButton.isEnabled = checkButton.isEnabled
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
                MpSqlVisualizerBundle.message("settings.update.available", pendingVersion() ?: "?")
            State.Downloading -> statusLabel.text = MpSqlVisualizerBundle.message("settings.update.downloading")
            State.Installed -> statusLabel.text = MpSqlVisualizerBundle.message("settings.update.installed")
            State.InstalledLive -> statusLabel.text = MpSqlVisualizerBundle.message("settings.update.installedLive")
            State.Failed -> statusLabel.text = when {
                pendingManifest == null -> {
                    // R62：检查失败按 [PluginUpdateRunner.FailureKind] 分流文案（三态可诊断）；
                    // 安装失败（pendingManifest 非空）另走下面两支
                    when (failureKind) {
                        PluginUpdateRunner.FailureKind.CHANNEL_MISSING ->
                            MpSqlVisualizerBundle.message("settings.update.fail.channel404", failureReason ?: "?")
                        PluginUpdateRunner.FailureKind.NETWORK ->
                            MpSqlVisualizerBundle.message("settings.update.fail.network", failureReason ?: "?")
                        else ->
                            MpSqlVisualizerBundle.message("settings.update.checkFailed", failureReason ?: "?")
                    }
                }
                // T-DevUpdateChannel：包没发布（HTTP 404/410）不渲染异常原文，给友好文案 + 发布页链接
                notPublished -> MpSqlVisualizerBundle.message("settings.update.notPublished")
                else -> MpSqlVisualizerBundle.message("settings.update.installFailed", failureReason ?: "?")
            }
            State.Idle -> statusLabel.text = " "
        }
        // T-DevUpdateChannel / T-LocalUpdateDir：三条通道共用同一状态机，只在文案尾巴标出通道避免误读
        when (channel) {
            Channel.PREVIEW -> if (s != State.Idle) {
                statusLabel.text = statusLabel.text + MpSqlVisualizerBundle.message("settings.update.channel.preview")
            }
            Channel.LOCAL -> if (s != State.Idle) {
                statusLabel.text = statusLabel.text + MpSqlVisualizerBundle.message("settings.update.channel.local")
            }
            Channel.STABLE -> Unit
        }
        // R62：兜底链接行在「检查失败且通道缺失」或「包未发布」态可见（网络态/其他安装失败/其他态隐藏）
        fallbackLinksContainer.isVisible = s == State.Failed && (
            (pendingManifest == null && failureKind == PluginUpdateRunner.FailureKind.CHANNEL_MISSING) || notPublished
            )
    }

    /** 版本行/开关/日志与按钮文本刷新（语言切换 rebuild 时调用，仿 ShortcutEditor.updateTexts）。 */
    internal fun updateTexts() {
        versionLabel.text = MpSqlVisualizerBundle.message("settings.update.currentVersion") +
            ": " + (PluginVersion.current() ?: "—")
        autoCheckCheck.text = MpSqlVisualizerBundle.message("settings.update.autoCheck")
        autoCheckCheck.toolTipText = MpSqlVisualizerBundle.message("settings.update.autoCheck.tip")
        checkButton.text = MpSqlVisualizerBundle.message("settings.update.check")
        checkButton.toolTipText = UpdateSource.manifestUrl()
        checkPreviewButton.text = MpSqlVisualizerBundle.message("settings.update.checkPreview")
        // tooltip 写实际读取的 URL：开发版对账「到底读了哪个文件」（预览清单常指向未发布版本）
        checkPreviewButton.toolTipText = UpdateSource.previewManifestUrl()
        installButton.text = MpSqlVisualizerBundle.message("settings.update.downloadInstall")
        installedLabel.text = MpSqlVisualizerBundle.message("settings.update.installed")
        restartButton.text = MpSqlVisualizerBundle.message("settings.update.restartNow")
        notesTitleLabel.text = MpSqlVisualizerBundle.message("settings.update.changelog")
        giteeLink.text = MpSqlVisualizerBundle.message("settings.update.viewOnGitee")
        reportProblemButton.text = MpSqlVisualizerBundle.message("settings.update.reportProblem")
        feedbackPoolLink.text = MpSqlVisualizerBundle.message("settings.update.feedbackPool")
        rollbackButton.text = MpSqlVisualizerBundle.message("settings.update.rollback")
        refreshRollbackAvailability()
        // T-LocalUpdateDir：本地目录行文案（按钮 + 状态行按最近读数重渲染）
        localDirLabel.text = MpSqlVisualizerBundle.message("settings.update.localDir")
        localDirLabel.toolTipText = MpSqlVisualizerBundle.message("settings.update.localDir.tip")
        browseLocalDirButton.text = MpSqlVisualizerBundle.message("settings.update.localDir.browse")
        browseLocalDirButton.toolTipText = MpSqlVisualizerBundle.message("settings.update.localDir.browse.tip")
        openLocalDirButton.text = MpSqlVisualizerBundle.message("settings.update.localDir.open")
        localCheckButton.text = MpSqlVisualizerBundle.message("settings.update.localDir.check")
        localCheckButton.toolTipText = MpSqlVisualizerBundle.message("settings.update.localDir.check.tip")
        val scan = lastLocalScan
        if (scan == null) {
            localDirStatusLabel.text = " "
            localDirStatusLabel.toolTipText = null
        } else {
            renderLocalDirStatus(scan)
        }
        // P-3 的钉子：rebuild 只重建文本，可见性必须按**当前解锁态**重算，否则切语言按钮消失
        applyDevVisibility()
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

    /**
     * 按 [DevUpdateMode] 重新决定开发版按钮可见性（唯一的赋值点）。
     * 解锁回调（面板连点）与 [updateTexts] 都走这里——两处任一失守都会表现为「开发模式失效」。
     */
    internal fun applyDevVisibility() {
        checkPreviewButton.isVisible = DevUpdateMode.isEnabled()
    }

    /** 解锁当场刷新（面板连点达到阈值后调用）：补文本 + 补可见性 + 让布局重算。 */
    internal fun refreshDevModeVisibility() {
        updateTexts()
        revalidate()
        repaint()
    }

    /** 从配置加载（Settings 打开/Reset 时调用）。 */
    internal fun resetState() {
        autoCheckCheck.isSelected = settings.state.autoCheckUpdate
        pendingManifest = null
        failureReason = null
        failureKind = null
        notPublished = false
        channel = Channel.STABLE
        state = State.Idle
        // UI-002 修复：备份在「会话中途」出现（如本轮升级动作落盘）后，重开设置页须重算回滚
        // 可用性——此前只在语言切换 rebuild（updateTexts）时刷新，reset 不刷导致按钮滞后灰置。
        refreshRollbackAvailability()
        // T-LocalUpdateDir：本地目录回填 + 状态行复位 + 立即校验一次（防抖 400ms 后出结果）
        localDirField.text = settings.state.localUpdateDir
        lastLocalScan = null
        pendingLocalPackage = null
        localDirStatusLabel.text = " "
        localDirStatusLabel.toolTipText = null
        scheduleLocalDirValidation()
        applyDevVisibility()
        renderState()
    }

    // ------------------------------------------------------------------
    // 异步流程（Task.Backgroundable；回调 EDT + 判活，面板销毁则静默）
    // ------------------------------------------------------------------

    /**
     * 检查更新（防重入：Checking/Downloading 中忽略）。三个按钮共用本状态机，只有 [channel] 不同：
     * 正式通道读 `update/update.json`，开发版通道读 `update/preview.update.json`，
     * 本地通道扫 [localDirField] 当前文本（T-LocalUpdateDir——用输入框现值，不要求先点应用）。
     */
    internal fun runCheck(channel: Channel = Channel.STABLE) {
        if (state == State.Checking || state == State.Downloading) return
        this.channel = channel
        if (channel == Channel.LOCAL) {
            val dirRaw = localDirField.text
            state = State.Checking
            renderState()
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                null,
                MpSqlVisualizerBundle.message("update.task.check"),
                true,
            ) {
                override fun run(indicator: ProgressIndicator) {
                    val result = runCatching { localScanRunner(dirRaw) }.getOrElse {
                        ScanResult.Failed(it.message ?: it.toString())
                    }
                    ApplicationManager.getApplication().invokeLater {
                        if (isDisplayable) {
                            renderLocalDirStatus(result)
                            renderLocalCheck(result)
                        }
                    }
                }
            })
            return
        }
        val url = if (channel == Channel.PREVIEW) UpdateSource.previewManifestUrl() else UpdateSource.manifestUrl()
        state = State.Checking
        renderState()
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            null,
            MpSqlVisualizerBundle.message("update.task.check"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                val result = checkRunner(url)
                ApplicationManager.getApplication().invokeLater {
                    if (isDisplayable) renderCheck(result)
                }
            }
        })
    }

    /** 待装版本号（远端 = 清单；本地 = 扫描出的包；状态机渲染共用）。 */
    private fun pendingVersion(): String? = pendingManifest?.version ?: pendingLocalPackage?.version

    /**
     * 下载/安装（进度条 fraction 经 [SectionProgress] 驱动；防重入：Downloading 中忽略）。
     * T-LocalUpdateDir：LOCAL 通道装 [pendingLocalPackage]（走 [localInstallRunner]），
     * 其余两通道走 [installRunner] 下载链，不改原语义。
     */
    internal fun runInstall() {
        if (state == State.Downloading) return
        if (channel == Channel.LOCAL) {
            val pkg = pendingLocalPackage ?: return
            state = State.Downloading
            renderState()
            val progress = SectionProgress(progressBar)
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                null,
                MpSqlVisualizerBundle.message("update.task.download"),
                true,
            ) {
                override fun run(indicator: ProgressIndicator) {
                    val result = localInstallRunner(pkg, progress)
                    ApplicationManager.getApplication().invokeLater {
                        if (isDisplayable) renderInstall(result)
                    }
                }
            })
            return
        }
        val manifest = pendingManifest ?: return
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

/** 本地目录输入防抖间隔（计划 §3.5：400ms 内连续输入只触发最后一次）。 */
private const val LOCAL_DIR_DEBOUNCE_MS = 400
