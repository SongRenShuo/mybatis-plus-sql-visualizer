package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.feedback.FeedbackApi;
import cn.song.mybatisplus.visualizer.feedback.FeedbackReporting;
import cn.song.mybatisplus.visualizer.update.DevUpdateMode;
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner;
import cn.song.mybatisplus.visualizer.update.PluginVersion;
import cn.song.mybatisplus.visualizer.update.UpdateManifest;
import cn.song.mybatisplus.visualizer.update.UpdateSource;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.SafeBrowser;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.util.ui.RestartDialogImpl;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import kotlin.Unit;
import java.util.function.Function;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;

/**
 * 设置页「更新」分区（M28）：当前版本 / 启动自动检查开关 / 检查更新 / 下载并安装 /
 * 重启提示——状态机渲染（Idle→Checking→UpToDate|Available→Downloading→Installed|Failed）。
 *
 * 异步流程全部经 {@link PluginUpdateRunner}（check/downloadAndInstall），本类只做 UI 编排与
 * 渲染（网络/平台安装不在本类）；测试可直接调用 {@link #renderCheck} / {@link #renderInstall}
 * 驱动状态（仿 ShortcutEditor 的组件内嵌模式，随语言切换经 {@link #updateTexts} 刷新）。
 */
public class UpdateSection extends JPanel {

    /** 分区状态（internal 供测试断言；状态渲染集中 {@link #renderState}）。 */
    private State state = State.Idle;

    public enum State { Idle, Checking, UpToDate, Available, Downloading, Installed, InstalledLive, Failed }

    /**
     * 本次（最近一次）检查走的通道（T-DevUpdateChannel / T-LocalUpdateDir）：决定读哪个来源
     * 与状态行后缀。LOCAL = 本地安装包目录（T-LocalUpdateDir）。
     */
    public enum Channel { STABLE, PREVIEW, LOCAL }

    /** 最近一次检查所用通道（渲染时给状态行加「（开发版通道）」「（本地目录）」后缀）。 */
    private Channel channel = Channel.STABLE;

    private UpdateManifest pendingManifest = null;

    /** T-LocalUpdateDir：本地通道的待装包（runInstall 按通道分流取用）。 */
    private cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.LocalPackage pendingLocalPackage = null;

    public cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.LocalPackage getPendingLocalPackage() {
        return pendingLocalPackage;
    }

    private String failureReason = null;

    /** T-DevUpdateChannel：最近一次安装失败是否「包还没发布」（404/410，友好文案 + 发布页链接）。 */
    private boolean notPublished = false;

    /**
     * 检查编排 seam（T-M28.1）：测试注入 fake 不触网；默认走 {@link PluginUpdateRunner#check} 真实实现。
     * 与按钮绑定配套——交互测试经 doClick 驱动全链路。
     *
     * <p>T-DevUpdateChannel：入参为本通道实际读取的清单 URL（正式=update.json / 开发版=preview.update.json），
     * 测试据此断言分流；既有「忽略入参」的注入写法继续有效。</p>
     */
    private Function1<String, PluginUpdateRunner.CheckResult> checkRunner =
        url -> PluginUpdateRunner.check(PluginVersion.current(), url);

    /** 下载安装编排 seam（T-M28.1：测试注入 fake；默认真实实现，indicator 驱动进度条）。 */
    private Function2<UpdateManifest, ProgressIndicator, PluginUpdateRunner.InstallResult> installRunner =
        (manifest, indicator) -> PluginUpdateRunner.downloadAndInstall(manifest, indicator);

    /** 重启回调 seam（T-M28.1：测试注入 fake 不弹原生重启确认框）。 */
    private Function0<Unit> restartAction = () -> {
        RestartDialogImpl.restartWithConfirmation();
        return Unit.INSTANCE;
    };

    private final JBLabel versionLabel = new JBLabel();
    private final JCheckBox autoCheckCheck = new JCheckBox();
    private final JButton checkButton = new JButton();

    /**
     * 「检测更新（开发版）」按钮（T-DevUpdateChannel）：构造即隐藏，{@code DevUpdateMode.isEnabled()}
     * 为真时可见。构造之后的可见性<b>只由 {@link #applyDevVisibility()} 决定</b>（{@link #updateTexts()} 与解锁回调
     * 都走它），不在别处直接赋值——否则语言切换 rebuild 会把按钮弄丢。
     */
    private final JButton checkPreviewButton = new JButton();

    private final JBLabel statusLabel = new JBLabel();
    private final JProgressBar progressBar = new JProgressBar();
    private final JBTextArea notesArea = new JBTextArea(5, 42);
    private final JButton installButton = new JButton();
    private final JBLabel installedLabel = new JBLabel();
    private final JButton restartButton = new JButton();

    /** 变更日志标题（T-M28.4：标题行 = 左标题 + 右 Gitee 发布页链接）。 */
    private final JBLabel notesTitleLabel = new JBLabel();

    /** 变更日志 → Gitee 发布页链接（用户要求可点击跳转）。 */
    private final LinkLabel<Object> giteeLink = new LinkLabel<>(
        MpSqlVisualizerBundle.message("settings.update.viewOnGitee"),
        null,
        (link, data) -> SafeBrowser.browse(UpdateSource.GITEE_RELEASES_URL));

    private final JPanel notesContainer;
    private final JPanel doneContainer;

    /** T-ErrReport：需求池报告入口（手动形态对话框 + 需求池网页链接）。 */
    private final JButton reportProblemButton = new JButton();

    /** 需求池网页直达链接（浏览反馈/投票/进度）。 */
    private final LinkLabel<Object> feedbackPoolLink = new LinkLabel<>("", null,
        (link, data) -> SafeBrowser.browse(FeedbackApi.SITE_BASE + "/about/feedback"));

    private final JPanel feedbackContainer;

    // ------------------------------------------------------------------
    // 本地安装包目录（T-LocalUpdateDir）：控件 + 校验状态行 + 防抖
    // ------------------------------------------------------------------

    /** 本地目录扫描编排 seam（T-LocalUpdateDir）：测试注入 fake 不触盘；默认真实扫描。 */
    private Function<String, cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.ScanResult> localScanRunner =
        dir -> cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.scan(dir,
                cn.song.mybatisplus.visualizer.update.PluginVersion.current(),
                cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.SHARED_CACHE,
                platformPluginsDir());

    public void setLocalScanRunner(
            Function<String, cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.ScanResult> runner) {
        this.localScanRunner = runner;
    }

    /** 本地安装编排 seam（T-LocalUpdateDir：测试注入 fake 不触平台安装；默认真实 installFromLocal）。 */
    private java.util.function.BiFunction<cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.LocalPackage,
            ProgressIndicator, PluginUpdateRunner.InstallResult> localInstallRunner =
        (pkg, indicator) -> PluginUpdateRunner.installFromLocal(pkg, indicator);

    public void setLocalInstallRunner(
            java.util.function.BiFunction<cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.LocalPackage,
                    ProgressIndicator, PluginUpdateRunner.InstallResult> runner) {
        this.localInstallRunner = runner;
    }

    /**
     * 打开目录 seam（T-LocalUpdateDir：测试注入断言）。默认走平台 ShowFilePathAction.openFile——
     * 它按操作系统的<b>文件管理器</b>打开路径；此前用 BrowserUtil.open(URI) 在 Windows 上是
     * <b>静默无效</b>（真机 2026-11-12 反馈「点打开没反应」：浏览器不处理目录 URI，失败也不抛）。
     */
    private java.util.function.Consumer<java.nio.file.Path> localDirectoryOpener = dir ->
        com.intellij.ide.actions.ShowFilePathAction.openFile(dir.toFile());

    public void setLocalDirectoryOpener(java.util.function.Consumer<java.nio.file.Path> opener) {
        this.localDirectoryOpener = opener;
    }

    private static java.nio.file.Path platformPluginsDir() {
        try {
            return com.intellij.openapi.application.PathManager.getPluginsDir();
        } catch (Throwable t) {
            return null;
        }
    }

    public final javax.swing.JTextField localDirField = new com.intellij.ui.components.JBTextField(26);

    /** 行内标签（面板统一标签列装不下嵌套行，这里自带一个；搜索索引经同 key 配对）。 */
    public final JBLabel localDirLabel = new JBLabel();

    public final JButton browseLocalDirButton = new JButton();
    public final JButton openLocalDirButton = new JButton();
    public final JButton localCheckButton = new JButton();

    /** 目录合法性/扫描状态行（EDT 单点渲染 renderLocalDirStatus；动态文案，不进搜索索引）。 */
    public final JBLabel localDirStatusLabel = new JBLabel(" ");

    /** 最近一次扫描读数（语言切换 updateTexts 重渲染状态行用；null = 尚未扫过）。 */
    private cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.ScanResult lastLocalScan = null;

    /** 输入防抖（EDT 单发定时器；disposeUiResources 必须停掉，防面板销毁后回调写 UI）。 */
    private final javax.swing.Timer localDirDebounceTimer;

    /** 本地安装包目录行容器（标签 + 字段 + 浏览 + 打开 + 检测；状态行单独一行）。 */
    public final JPanel localDirRow;

    /** R62：最近一次检查失败的分类（渲染文案与兜底链接可见性分流）。 */
    private PluginUpdateRunner.FailureKind failureKind = null;

    /** R62：兜底链接行（Marketplace + Gitee 双源）——只在「检查失败且通道缺失」态可见。 */
    private final JPanel fallbackLinksContainer = buildFallbackLinks();

    private JPanel buildFallbackLinks() {
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.setVisible(false);
        LinkLabel<Object> marketplace = new LinkLabel<>(
            MpSqlVisualizerBundle.message("settings.update.fallback.marketplace"), null,
            (link, data) -> SafeBrowser.browse(UpdateSource.MARKETPLACE_URL));
        LinkLabel<Object> gitee = new LinkLabel<>(
            MpSqlVisualizerBundle.message("settings.update.fallback.gitee"), null,
            (link, data) -> SafeBrowser.browse(UpdateSource.GITEE_RELEASES_URL));
        row.add(marketplace);
        row.add(gitee);
        return row;
    }

    private final PluginSettings settings;

    public UpdateSection(PluginSettings settings) {
        super(new GridBagLayout());
        this.settings = settings;
        notesTitleLabel.setName("notesTitle");

        // 变更日志容器：标题行（左标题 + 右链接）+ 滚动日志区（初始隐藏）
        notesContainer = new JPanel(new BorderLayout(0, 2));
        JPanel notesTitleRow = new JPanel(new BorderLayout(8, 0));
        notesTitleRow.add(notesTitleLabel, BorderLayout.WEST);
        notesTitleRow.add(giteeLink, BorderLayout.EAST);
        notesContainer.add(notesTitleRow, BorderLayout.NORTH);
        JScrollPane notesScroll = new JScrollPane(notesArea);
        notesScroll.setBorder(UIUtil.getTextFieldBorder());
        notesScroll.setVisible(false);
        notesContainer.add(notesScroll, BorderLayout.CENTER);

        // 安装完成容器：左「已安装」标签 + 右「立即重启」按钮
        doneContainer = new JPanel(new BorderLayout(8, 0));
        doneContainer.add(installedLabel, BorderLayout.WEST);
        doneContainer.add(restartButton, BorderLayout.EAST);

        // T-ErrReport：需求池报告容器（报告问题按钮 + 需求池网页链接）
        feedbackContainer = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        feedbackContainer.setOpaque(false);
        feedbackContainer.add(reportProblemButton);
        feedbackContainer.add(feedbackPoolLink);

        notesArea.setEditable(false);
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);
        // T-M28.1：三按钮事件绑定（此前缺失——用户实测点击检查更新无反应；测试盲区：只测渲染未测交互）
        checkButton.addActionListener(e -> runCheck(Channel.STABLE));
        checkPreviewButton.addActionListener(e -> runCheck(Channel.PREVIEW));
        checkPreviewButton.setVisible(false);
        installButton.addActionListener(e -> runInstall());
        restartButton.addActionListener(e -> restartIde());
        // T-ErrReport：报告问题（手动形态，无异常预填）
        reportProblemButton.addActionListener(e -> FeedbackReporting.openManualDialog());
        // T-LocalUpdateDir：本地目录行控件编排 + 交互绑定（浏览/打开/检测 + 输入防抖校验）
        localDirRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        localDirRow.setOpaque(false);
        localDirRow.add(localDirLabel);
        localDirRow.add(localDirField);
        localDirRow.add(browseLocalDirButton);
        localDirRow.add(openLocalDirButton);
        localDirRow.add(localCheckButton);
        localDirDebounceTimer = new javax.swing.Timer(LOCAL_DIR_DEBOUNCE_MS, e -> validateLocalDirAsync());
        localDirDebounceTimer.setRepeats(false);
        browseLocalDirButton.addActionListener(e -> browseLocalDir());
        openLocalDirButton.addActionListener(e -> openLocalDir());
        localCheckButton.addActionListener(e -> runCheck(Channel.LOCAL));
        localDirField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { scheduleLocalDirValidation(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { scheduleLocalDirValidation(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { scheduleLocalDirValidation(); }
        });
        buildLayout();
        resetState();
        updateTexts();
    }

    // ------------------------------------------------------------------
    // 布局（GridBag 行：版本/开关/按钮+状态/进度/日志/安装完成）
    // ------------------------------------------------------------------

    private void buildLayout() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(2, 0, 2, 0);

        // 版本内容行（当前版本 + 检查/安装按钮 + 状态反馈）；「版本信息」标签由设置面板
        // addRow 提供（统一标签列——与插件缓存/内部构建号左对齐，用户反馈对齐问题）
        JPanel versionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        versionRow.setOpaque(false);
        versionRow.add(versionLabel);
        versionRow.add(checkButton);
        versionRow.add(checkPreviewButton);
        versionRow.add(installButton);
        versionRow.add(statusLabel);
        addRow(versionRow, gbc);
        addRow(autoCheckCheck, gbc);
        addRow(progressBar, gbc);
        addRow(notesContainer, gbc);
        addRow(doneContainer, gbc);
        // T-LocalUpdateDir：本地安装包目录行（版本信息正下方，计划 §3.5）
        addRow(localDirRow, gbc);
        addRow(localDirStatusLabel, gbc);
        // T-ErrReport：需求池报告入口（报告问题按钮 + 需求池网页链接）
        addRow(feedbackContainer, gbc);
        // 底部占位（与设置页整体一致：内容顶置）
        gbc.gridy++;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        add(new JPanel(), gbc);
    }

    private void addRow(javax.swing.JComponent component, GridBagConstraints gbc) {
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        add(component, gbc);
    }

    // ------------------------------------------------------------------
    // 状态机渲染
    // ------------------------------------------------------------------

    /** 检查结果渲染（EDT；测试直接调用）。 */
    public void renderCheck(PluginUpdateRunner.CheckResult result) {
        notPublished = false;
        // T-LocalUpdateDir：远端通道独占「变更日志 + Gitee 链接」表头；本地通道渲染时另设
        notesTitleLabel.setText(MpSqlVisualizerBundle.message("settings.update.changelog"));
        giteeLink.setVisible(true);
        if (result instanceof PluginUpdateRunner.CheckResult.Available a) {
            pendingManifest = a.manifest();
            notesArea.setText(renderNotes(a.manifest()));
            state = State.Available;
        } else if (result instanceof PluginUpdateRunner.CheckResult.UpToDate) {
            pendingManifest = null;
            failureKind = null;
            state = State.UpToDate;
        } else if (result instanceof PluginUpdateRunner.CheckResult.Failure f) {
            pendingManifest = null;
            state = State.Failed;
            failureReason = f.message();
            failureKind = f.kind();
        } else {
            failureKind = null;
        }
        renderState();
    }

    /** 日志区内容：changeNotes + 兼容性警告（sinceBuild 高于当前 IDE 构建时提示不阻塞）。 */
    private String renderNotes(UpdateManifest manifest) {
        StringBuilder sb = new StringBuilder();
        sb.append(manifest.changeNotes() != null ? manifest.changeNotes() : "—");
        Integer current = currentBuildMajor();
        if (manifest.warnsIncompatible(current)) {
            sb.append("\n").append(
                MpSqlVisualizerBundle.message("settings.update.incompatible", manifest.sinceBuild(), current));
        }
        return sb.toString();
    }

    /** 当前 IDE 构建主号（如 `243`）；读取失败返回 null。 */
    private Integer currentBuildMajor() {
        String build = ApplicationInfo.getInstance().getBuild().asStringWithoutProductCode();
        if (build == null) return null;
        Matcher m = Pattern.compile("^\\d+").matcher(build);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 安装结果渲染（EDT；测试直接调用）。四分支：热生效 / 回退重启 / 未发布（404 友好态）/ 失败
     * （保留 pendingManifest 区分文案）。
     */
    public void renderInstall(PluginUpdateRunner.InstallResult result) {
        failureReason = result instanceof PluginUpdateRunner.InstallResult.Failed f ? f.message() : null;
        notPublished = result instanceof PluginUpdateRunner.InstallResult.NotPublished;
        if (result instanceof PluginUpdateRunner.InstallResult.InstalledDynamic) {
            state = State.InstalledLive;
        } else if (result instanceof PluginUpdateRunner.InstallResult.InstalledRestartRequired) {
            state = State.Installed;
        } else if (result instanceof PluginUpdateRunner.InstallResult.NotPublished) {
            state = State.Failed;
        } else {
            state = State.Failed;
        }
        renderState();
    }

    private void renderState() {
        State s = state;
        boolean done = s == State.Installed || s == State.InstalledLive;
        checkButton.setEnabled(!done && s != State.Checking && s != State.Downloading);
        checkPreviewButton.setEnabled(checkButton.isEnabled());
        installButton.setVisible(s == State.Available || s == State.Downloading);
        installButton.setEnabled(s == State.Available);
        restartButton.setVisible(s == State.Installed);
        installedLabel.setVisible(s == State.Installed);
        progressBar.setVisible(s == State.Downloading);
        progressBar.setIndeterminate(true);
        notesContainer.setVisible(s == State.Available);
        doneContainer.setVisible(s == State.Installed);
        // R62：兜底链接行在「检查失败且通道缺失」或「包未发布」态可见（网络态/其他安装失败/其他态隐藏）
        fallbackLinksContainer.setVisible(s == State.Failed && (
            (pendingManifest == null && failureKind == PluginUpdateRunner.FailureKind.CHANNEL_MISSING) || notPublished
        ));
        switch (s) {
            case Checking -> statusLabel.setText(MpSqlVisualizerBundle.message("settings.update.checking"));
            case UpToDate -> statusLabel.setText(MpSqlVisualizerBundle.message("settings.update.upToDate"));
            case Available -> statusLabel.setText(
                MpSqlVisualizerBundle.message("settings.update.available", pendingVersion() != null ? pendingVersion() : "?"));
            case Downloading -> statusLabel.setText(MpSqlVisualizerBundle.message("settings.update.downloading"));
            case Installed -> statusLabel.setText(MpSqlVisualizerBundle.message("settings.update.installed"));
            case InstalledLive -> statusLabel.setText(MpSqlVisualizerBundle.message("settings.update.installedLive"));
            case Failed -> {
                // R62：检查失败按 FailureKind 分流文案（三态可诊断）；安装失败（pendingManifest 非空）另走下面两支
                if (pendingManifest == null && failureKind != null) {
                    statusLabel.setText(MpSqlVisualizerBundle.message(
                        failureKind == PluginUpdateRunner.FailureKind.CHANNEL_MISSING
                            ? "settings.update.fail.channel404"
                            : "settings.update.fail.network",
                        failureReason != null ? failureReason : "?"));
                } else if (notPublished) {
                    // T-DevUpdateChannel：包没发布（HTTP 404/410）不渲染异常原文，给友好文案 + 发布页链接
                    statusLabel.setText(MpSqlVisualizerBundle.message("settings.update.notPublished"));
                } else {
                    statusLabel.setText(MpSqlVisualizerBundle.message(
                        "settings.update." + (pendingManifest == null ? "checkFailed" : "installFailed"),
                        failureReason != null ? failureReason : "?"));
                }
            }
            case Idle -> statusLabel.setText(" ");
        }
        // T-DevUpdateChannel / T-LocalUpdateDir：三条通道共用同一状态机，只在文案尾巴标出通道避免误读
        if (channel == Channel.PREVIEW && s != State.Idle) {
            statusLabel.setText(statusLabel.getText() + MpSqlVisualizerBundle.message("settings.update.channel.preview"));
        }
        if (channel == Channel.LOCAL && s != State.Idle) {
            statusLabel.setText(statusLabel.getText() + MpSqlVisualizerBundle.message("settings.update.channel.local"));
        }
    }

    /** 待装版本号（远端 = 清单；本地 = 扫描出的包；状态机渲染共用）。 */
    private String pendingVersion() {
        if (pendingManifest != null) return pendingManifest.version();
        return pendingLocalPackage != null ? pendingLocalPackage.version() : null;
    }

    /** 版本行/开关/日志与按钮文本刷新（语言切换 rebuild 时调用，仿 ShortcutEditor.updateTexts）。 */
    public void updateTexts() {
        String cv = PluginVersion.current();
        versionLabel.setText(MpSqlVisualizerBundle.message("settings.update.currentVersion")
            + ": " + (cv != null ? cv : "—"));
        autoCheckCheck.setText(MpSqlVisualizerBundle.message("settings.update.autoCheck"));
        autoCheckCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.update.autoCheck.tip"));
        checkButton.setText(MpSqlVisualizerBundle.message("settings.update.check"));
        checkButton.setToolTipText(UpdateSource.INSTANCE.manifestUrl());
        checkPreviewButton.setText(MpSqlVisualizerBundle.message("settings.update.checkPreview"));
        // tooltip 写实际读取的 URL：开发版对账「到底读了哪个文件」（预览清单常指向未发布版本）
        checkPreviewButton.setToolTipText(UpdateSource.INSTANCE.previewManifestUrl());
        installButton.setText(MpSqlVisualizerBundle.message("settings.update.downloadInstall"));
        installedLabel.setText(MpSqlVisualizerBundle.message("settings.update.installed"));
        restartButton.setText(MpSqlVisualizerBundle.message("settings.update.restartNow"));
        notesTitleLabel.setText(MpSqlVisualizerBundle.message("settings.update.changelog"));
        giteeLink.setText(MpSqlVisualizerBundle.message("settings.update.viewOnGitee"));
        reportProblemButton.setText(MpSqlVisualizerBundle.message("settings.update.reportProblem"));
        feedbackPoolLink.setText(MpSqlVisualizerBundle.message("settings.update.feedbackPool"));
        // T-LocalUpdateDir：本地目录行文案（按钮 + 状态行按最近读数重渲染）
        localDirLabel.setText(MpSqlVisualizerBundle.message("settings.update.localDir"));
        localDirLabel.setToolTipText(MpSqlVisualizerBundle.message("settings.update.localDir.tip"));
        browseLocalDirButton.setText(MpSqlVisualizerBundle.message("settings.update.localDir.browse"));
        browseLocalDirButton.setToolTipText(MpSqlVisualizerBundle.message("settings.update.localDir.browse.tip"));
        openLocalDirButton.setText(MpSqlVisualizerBundle.message("settings.update.localDir.open"));
        localCheckButton.setText(MpSqlVisualizerBundle.message("settings.update.localDir.check"));
        localCheckButton.setToolTipText(MpSqlVisualizerBundle.message("settings.update.localDir.check.tip"));
        localCheckButton.setEnabled(checkButton.isEnabled());
        if (lastLocalScan == null) {
            localDirStatusLabel.setText(" ");
            localDirStatusLabel.setToolTipText(null);
        } else {
            renderLocalDirStatus(lastLocalScan);
        }
        // P-3 的钉子：rebuild 只重建文本，可见性必须按**当前解锁态**重算，否则切语言按钮消失
        applyDevVisibility();
        renderState();
    }

    /** 本地目录输入防抖间隔（计划 §3.5：400ms 内连续输入只触发最后一次）。 */
    private static final int LOCAL_DIR_DEBOUNCE_MS = 400;

    /** 输入防抖入口：重置单发定时器（400ms 内连续输入只触发最后一次，计划 §3.5）。 */
    private void scheduleLocalDirValidation() {
        localDirDebounceTimer.stop();
        localDirDebounceTimer.start();
    }

    /**
     * 输入防抖到期：BGT 跑一遍扫描（合法性 + 包发现），EDT 回渲染状态行 + <b>写穿持久化</b>
     * （T-LocalDirPersist：合法目录与删空即存，不依赖 OK/Apply）。只动 localDirStatusLabel
     * 与 localUpdateDir 配置，<b>不翻状态机</b>。
     */
    private void validateLocalDirAsync() {
        final String dirRaw = localDirField.getText();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.ScanResult result;
            try {
                result = localScanRunner.apply(dirRaw);
            } catch (Throwable t) {
                result = cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.ScanResult.failed(
                        t.getMessage() != null ? t.getMessage() : t.toString());
            }
            final cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.ScanResult resultFinal = result;
            // 写穿值在 BGT 决议（需要 FS 判定，禁上 EDT）；null = 错误态不写穿
            String persistValue;
            if (result.isDirInvalid() || result.isFailed()) {
                persistValue = null;
            } else if (result.isDisabled()) {
                persistValue = "";
            } else {
                // Available/UpToDate/Empty/NoValidPackage ⇒ 目录层已判 Ok；重取归一路径
                cn.song.mybatisplus.visualizer.update.LocalUpdateDirKt.DirCheck check =
                        cn.song.mybatisplus.visualizer.update.LocalUpdateDirKt.parse(dirRaw, platformPluginsDir());
                persistValue = check.isOk() ? check.getDir().toString() : dirRaw;
            }
            final String persistValueFinal = persistValue;
            ApplicationManager.getApplication().invokeLater(() -> {
                // 不按 isDisplayable 丢弃：懒加载配置页可能尚未挂载，丢弃会让状态行永久停在旧读数
                renderLocalDirStatus(resultFinal);
                if (persistValueFinal != null) {
                    writeThrough(persistValueFinal);
                }
            });
        });
    }

    /**
     * 写穿持久化（T-LocalDirPersist）：换引用强制落盘（照 apply 的实证纪律——原地改可能
     * 不被 XmlSerializer 检测到），幂等（同值不触发持久化周期），并把归一值回写输入框
     * 使 {@code isModified} 归零（路径已落盘，无「待应用」的东西）。
     */
    private void writeThrough(String normalized) {
        if (normalized.equals(settings.getState().getLocalUpdateDir())) return;
        PluginSettings.State fresh = settings.getState().copy();
        fresh.setLocalUpdateDir(normalized);
        settings.loadState(fresh);
        if (!localDirField.getText().equals(normalized)) {
            localDirField.setText(normalized);
        }
    }

    /** 浏览目录（照抄 browseAutoSavePath 形态：只选目录，起始 user.home）；选完立即校验，不等防抖。 */
    private void browseLocalDir() {
        com.intellij.openapi.fileChooser.FileChooserDescriptor descriptor =
                new com.intellij.openapi.fileChooser.FileChooserDescriptor(
                    false, true, false, false, false, false)
                    .withTitle(MpSqlVisualizerBundle.message("settings.update.localDir.browse.tip"));
        com.intellij.openapi.vfs.VirtualFile baseDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByIoFile(new java.io.File(System.getProperty("user.home")));
        com.intellij.openapi.vfs.VirtualFile dir =
                com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, null, baseDir);
        if (dir != null) {
            localDirDebounceTimer.stop();
            localDirField.setText(dir.getPath());
            validateLocalDirAsync();
        }
    }

    /**
     * 打开目录（配置合法才开）。失败不再静默：异常与「平台没开成」都写回状态行，
     * 用户能区分「路径不对」与「系统没响应」。
     */
    private void openLocalDir() {
        cn.song.mybatisplus.visualizer.update.LocalUpdateDirKt.DirCheck check =
                cn.song.mybatisplus.visualizer.update.LocalUpdateDirKt.parseWithPlatformPluginsDir(
                        localDirField.getText());
        if (!check.isOk()) {
            renderLocalDirStatus(
                    cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.ScanResult.dirInvalid(check));
            return;
        }
        try {
            localDirectoryOpener.accept(check.getDir());
        } catch (Throwable t) {
            localDirStatusLabel.setText(t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    /** DirCheck 各态 → 面向用户的一句原因（i18n）。 */
    private String describeLocalDirCheck(cn.song.mybatisplus.visualizer.update.LocalUpdateDirKt.DirCheck check) {
        String kind = check.getKind();
        switch (kind) {
            case "Disabled": return MpSqlVisualizerBundle.message("settings.update.local.disabled");
            case "InvalidPath": return MpSqlVisualizerBundle.message("settings.update.local.invalidPath",
                    check.getReason() != null ? check.getReason() : "");
            case "EnvVarUnsupported": return MpSqlVisualizerBundle.message("settings.update.local.envVar");
            case "Relative": return MpSqlVisualizerBundle.message("settings.update.local.relative");
            case "NotFound": return MpSqlVisualizerBundle.message("settings.update.local.notFound");
            case "Inaccessible": return MpSqlVisualizerBundle.message("settings.update.local.inaccessible");
            case "NotDirectory": return MpSqlVisualizerBundle.message("settings.update.local.notDirectory");
            case "NotReadable": return MpSqlVisualizerBundle.message("settings.update.local.notReadable");
            case "InsidePluginsDir": return MpSqlVisualizerBundle.message("settings.update.local.insidePluginsDir");
            default: return "";
        }
    }

    /** 文件大小展示（KB/MB 一位小数；与通道无关的纯展示）。 */
    private static String renderFileSize(long bytes) {
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        if (bytes >= 1024) return String.format("%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    /**
     * 本地扫描结果 → 状态机渲染（T-LocalUpdateDir，EDT；测试直接调用）。
     * DirInvalid/Empty/NoValidPackage/Failed 都归 State.Failed；Available 时置 pendingLocalPackage，
     * 日志区显示本地包详情行（无 changeNotes，不放 Gitee 链接）。
     */
    public void renderLocalCheck(cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.ScanResult result) {
        notPublished = false;
        failureKind = null;
        pendingManifest = null;
        pendingLocalPackage = null;
        if (result.isDisabled()) {
            state = State.Idle;
            renderState();
            return;
        }
        if (result.isAvailable()) {
            pendingLocalPackage = result.getLatest();
            notesArea.setText(MpSqlVisualizerBundle.message(
                "settings.update.local.package",
                result.getLatest().fileName(),
                renderFileSize(result.getLatest().sizeBytes())));
            notesTitleLabel.setText(MpSqlVisualizerBundle.message("settings.update.local.package.title"));
            giteeLink.setVisible(false);
            state = State.Available;
        } else if (result.isUpToDate()) {
            state = State.UpToDate;
        } else if (result.isDirInvalid()) {
            state = State.Failed;
            failureReason = describeLocalDirCheck(result.getCheck());
        } else if (result.isEmpty()) {
            state = State.Failed;
            failureReason = MpSqlVisualizerBundle.message("settings.update.local.empty");
        } else if (result.isNoValidPackage()) {
            state = State.Failed;
            failureReason = MpSqlVisualizerBundle.message("settings.update.local.noValid", result.getSkipped());
        } else if (result.isFailed()) {
            state = State.Failed;
            failureReason = result.getReason();
        }
        renderState();
    }

    /** 目录合法性/扫描状态行渲染（EDT 单点；只动 localDirStatusLabel，不翻状态机）。 */
    public void renderLocalDirStatus(cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.ScanResult result) {
        lastLocalScan = result;
        String status;
        if (result.isDisabled()) {
            localDirStatusLabel.setText(MpSqlVisualizerBundle.message("settings.update.local.disabled"));
            localDirStatusLabel.setToolTipText(null);
            return;
        }
        if (result.isDirInvalid()) {
            status = describeLocalDirCheck(result.getCheck());
        } else if (result.isEmpty()) {
            status = MpSqlVisualizerBundle.message("settings.update.local.empty");
        } else if (result.isNoValidPackage()) {
            status = MpSqlVisualizerBundle.message("settings.update.local.noValid", result.getSkipped());
        } else if (result.isUpToDate()) {
            status = MpSqlVisualizerBundle.message(
                "settings.update.local.ready.upToDate", result.getTotal(), result.getLatest().version());
        } else if (result.isAvailable()) {
            status = MpSqlVisualizerBundle.message(
                "settings.update.local.ready", result.getTotal(), result.getLatest().version());
        } else {
            status = result.getReason();
        }
        localDirStatusLabel.setText(status);
        localDirStatusLabel.setToolTipText(buildLocalStatusTooltip(result));
    }

    /** 状态行 tooltip：跳过明细 + 截断提示（计划 §3.3 规则 2「不静默吞」）。 */
    private String buildLocalStatusTooltip(cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.ScanResult result) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (result.isNoValidPackage() && !result.getSkippedSamples().isEmpty()) {
            parts.add(MpSqlVisualizerBundle.message("settings.update.local.skippedDetail",
                    String.join("；", result.getSkippedSamples())));
        }
        if (result.isNoValidPackage() || result.isUpToDate() || result.isAvailable()) {
            if (result.isTruncated()) {
                parts.add(MpSqlVisualizerBundle.message("settings.update.local.truncated",
                        cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.MAX_CANDIDATES));
            }
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    /** 持久化值：可解析则存归一路径，错误态存 trim 原文（重开页面后状态行仍能按原文报原因）。 */
    public String normalizedDirForPersist() {
        String raw = localDirField.getText().trim();
        cn.song.mybatisplus.visualizer.update.LocalUpdateDirKt.DirCheck check =
                cn.song.mybatisplus.visualizer.update.LocalUpdateDirKt.parseWithPlatformPluginsDir(raw);
        return check.isOk() ? check.getDir().toString() : raw;
    }

    /** 面板销毁钩子（PluginSettingsPanel.disposeResources 调用）：停防抖定时器防销毁后回写。 */
    public void disposeUiResources() {
        localDirDebounceTimer.stop();
    }

    /**
     * 按 {@link DevUpdateMode} 重新决定开发版按钮可见性（唯一的赋值点）。
     * 解锁回调（面板连点）与 {@link #updateTexts()} 都走这里——两处任一失守都会表现为「开发模式失效」。
     */
    public void applyDevVisibility() {
        checkPreviewButton.setVisible(DevUpdateMode.isEnabled());
    }

    /** 解锁当场刷新（面板连点达到阈值后调用）：补文本 + 补可见性 + 让布局重算。 */
    public void refreshDevModeVisibility() {
        updateTexts();
        revalidate();
        repaint();
    }

    /** 从配置加载（Settings 打开/Reset 时调用）。 */
    public void resetState() {
        autoCheckCheck.setSelected(settings.getState().getAutoCheckUpdate());
        pendingManifest = null;
        failureReason = null;
        failureKind = null;
        notPublished = false;
        channel = Channel.STABLE;
        state = State.Idle;
        applyDevVisibility();
        renderState();
        // T-LocalUpdateDir：本地目录回填 + 状态行复位 + 立即校验一次（防抖 400ms 后出结果）
        localDirField.setText(settings.getState().getLocalUpdateDir() != null
                ? settings.getState().getLocalUpdateDir() : "");
        lastLocalScan = null;
        pendingLocalPackage = null;
        localDirStatusLabel.setText(" ");
        localDirStatusLabel.setToolTipText(null);
        scheduleLocalDirValidation();
    }

    // ------------------------------------------------------------------
    // 异步流程（Task.Backgroundable；回调 EDT + 判活，面板销毁则静默）
    // ------------------------------------------------------------------

    /** 检查更新（正式通道形态；Kotlin 默认实参的等价重载）。 */
    public void runCheck() {
        runCheck(Channel.STABLE);
    }

    /**
     * 检查更新（防重入：Checking/Downloading 中忽略）。两个按钮共用本状态机，只有 channel 不同：
     * 正式通道读 {@code update/update.json}，开发版通道读 {@code update/preview.update.json}。
     */
    public void runCheck(Channel channel) {
        if (state == State.Checking || state == State.Downloading) return;
        this.channel = channel;
        // T-LocalUpdateDir：本地通道扫 localDirField 当前文本（用输入框现值，不要求先点应用）
        if (channel == Channel.LOCAL) {
            final String dirRaw = localDirField.getText();
            state = State.Checking;
            renderState();
            ProgressManager.getInstance().run(new Task.Backgroundable(
                null,
                MpSqlVisualizerBundle.message("update.task.check"),
                true) {
                @Override
                public void run(ProgressIndicator indicator) {
                    cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.ScanResult result;
                    try {
                        result = localScanRunner.apply(dirRaw);
                    } catch (Throwable t) {
                        result = cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.ScanResult.failed(
                                t.getMessage() != null ? t.getMessage() : t.toString());
                    }
                    final cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.ScanResult resultFinal = result;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        // 不按 isDisplayable 丢弃：懒加载配置页可能尚未挂载，丢弃会让状态行停在旧读数
                        renderLocalDirStatus(resultFinal);
                        renderLocalCheck(resultFinal);
                    });
                }
            });
            return;
        }
        String url = channel == Channel.PREVIEW
                ? UpdateSource.INSTANCE.previewManifestUrl()
                : UpdateSource.INSTANCE.manifestUrl();
        state = State.Checking;
        renderState();
        ProgressManager.getInstance().run(new Task.Backgroundable(
            null,
            MpSqlVisualizerBundle.message("update.task.check"),
            true) {
            @Override
            public void run(ProgressIndicator indicator) {
                PluginUpdateRunner.CheckResult result = checkRunner.invoke(url);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (isDisplayable()) renderCheck(result);
                });
            }
        });
    }

    /**
     * 下载/安装（进度条 fraction 经 {@link SectionProgress} 驱动；防重入：Downloading 中忽略）。
     * T-LocalUpdateDir：LOCAL 通道装 pendingLocalPackage（走 localInstallRunner），
     * 其余两通道走 installRunner 下载链，不改原语义。
     */
    public void runInstall() {
        if (channel == Channel.LOCAL) {
            cn.song.mybatisplus.visualizer.update.LocalPackageScannerKt.LocalPackage pkg = pendingLocalPackage;
            if (pkg == null) return;
            state = State.Downloading;
            renderState();
            SectionProgress progress = new SectionProgress(progressBar);
            ProgressManager.getInstance().run(new Task.Backgroundable(
                null,
                MpSqlVisualizerBundle.message("update.task.download"),
                true) {
                @Override
                public void run(ProgressIndicator indicator) {
                    PluginUpdateRunner.InstallResult result = localInstallRunner.apply(pkg, progress);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (isDisplayable()) renderInstall(result);
                    });
                }
            });
            return;
        }
        UpdateManifest manifest = pendingManifest;
        if (manifest == null) return;
        if (state == State.Downloading) return;
        state = State.Downloading;
        renderState();
        SectionProgress progress = new SectionProgress(progressBar);
        ProgressManager.getInstance().run(new Task.Backgroundable(
            null,
            MpSqlVisualizerBundle.message("update.task.download"),
            true) {
            @Override
            public void run(ProgressIndicator indicator) {
                PluginUpdateRunner.InstallResult result = installRunner.invoke(manifest, progress);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (isDisplayable()) renderInstall(result);
                });
            }
        });
    }

    /** 重启 IDE（IDE 原生重启确认框，可选「稍后」——用户拍板，不自动重启）。 */
    public void restartIde() {
        restartAction.invoke();
    }

    // ------------------------------------------------------------------
    // 访问器（供测试与外层设置页访问组件）
    // ------------------------------------------------------------------

    public State getState() {
        return state;
    }

    /** 最近一次检查所用通道（T-DevUpdateChannel；测试断言分流用）。 */
    public Channel getChannel() {
        return channel;
    }

    /** 「检测更新（开发版）」按钮（T-DevUpdateChannel；测试断言可见性/使能用）。 */
    public JButton getCheckPreviewButton() {
        return checkPreviewButton;
    }

    public JBLabel getVersionLabel() {
        return versionLabel;
    }

    public JCheckBox getAutoCheckCheck() {
        return autoCheckCheck;
    }

    public JButton getCheckButton() {
        return checkButton;
    }

    public JBLabel getStatusLabel() {
        return statusLabel;
    }

    public JProgressBar getProgressBar() {
        return progressBar;
    }

    public JBTextArea getNotesArea() {
        return notesArea;
    }

    public JButton getInstallButton() {
        return installButton;
    }

    public JBLabel getInstalledLabel() {
        return installedLabel;
    }

    public JButton getRestartButton() {
        return restartButton;
    }

    public LinkLabel<Object> getGiteeLink() {
        return giteeLink;
    }

    public JButton getReportProblemButton() {
        return reportProblemButton;
    }

    public LinkLabel<Object> getFeedbackPoolLink() {
        return feedbackPoolLink;
    }

    public void setCheckRunner(Function1<String, PluginUpdateRunner.CheckResult> checkRunner) {
        this.checkRunner = checkRunner;
    }

    public void setInstallRunner(Function2<UpdateManifest, ProgressIndicator, PluginUpdateRunner.InstallResult> installRunner) {
        this.installRunner = installRunner;
    }

    public void setRestartAction(Function0<Unit> restartAction) {
        this.restartAction = restartAction;
    }

    /** 进度条适配：下载 fraction → 确定态进度（EDT 更新）。 */
    private static final class SectionProgress extends ProgressIndicatorBase {
        private final JProgressBar bar;

        SectionProgress(JProgressBar bar) {
            this.bar = bar;
        }

        @Override
        public void setFraction(double fraction) {
            super.setFraction(fraction);
            int value = (int) (fraction * 100);
            if (value < 0) value = 0;
            if (value > 100) value = 100;
            final int finalValue = value;
            ApplicationManager.getApplication().invokeLater(() -> {
                bar.setIndeterminate(fraction <= 0);
                bar.setValue(finalValue);
            });
        }
    }
}
