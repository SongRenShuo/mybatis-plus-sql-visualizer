package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.feedback.FeedbackApi;
import cn.song.mybatisplus.visualizer.feedback.FeedbackReporting;
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
import kotlin.jvm.functions.Function0;
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

    private UpdateManifest pendingManifest = null;

    private String failureReason = null;

    /**
     * 检查编排 seam（T-M28.1）：测试注入 fake 不触网；默认走 {@link PluginUpdateRunner#check} 真实实现。
     * 与按钮绑定配套——交互测试经 doClick 驱动全链路。
     */
    private Function0<PluginUpdateRunner.CheckResult> checkRunner =
        () -> PluginUpdateRunner.check(PluginVersion.current());

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
        checkButton.addActionListener(e -> runCheck());
        installButton.addActionListener(e -> runInstall());
        restartButton.addActionListener(e -> restartIde());
        // T-ErrReport：报告问题（手动形态，无异常预填）
        reportProblemButton.addActionListener(e -> FeedbackReporting.openManualDialog());
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
        versionRow.add(installButton);
        versionRow.add(statusLabel);
        addRow(versionRow, gbc);
        addRow(autoCheckCheck, gbc);
        addRow(progressBar, gbc);
        addRow(notesContainer, gbc);
        addRow(doneContainer, gbc);
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

    /** 安装结果渲染（EDT；测试直接调用）。三分支：热生效 / 回退重启 / 失败（保留 pendingManifest 区分文案）。 */
    public void renderInstall(PluginUpdateRunner.InstallResult result) {
        failureReason = result instanceof PluginUpdateRunner.InstallResult.Failed f ? f.message() : null;
        if (result instanceof PluginUpdateRunner.InstallResult.InstalledDynamic) {
            state = State.InstalledLive;
        } else if (result instanceof PluginUpdateRunner.InstallResult.InstalledRestartRequired) {
            state = State.Installed;
        } else {
            state = State.Failed;
        }
        renderState();
    }

    private void renderState() {
        State s = state;
        boolean done = s == State.Installed || s == State.InstalledLive;
        checkButton.setEnabled(!done && s != State.Checking && s != State.Downloading);
        installButton.setVisible(s == State.Available || s == State.Downloading);
        installButton.setEnabled(s == State.Available);
        restartButton.setVisible(s == State.Installed);
        installedLabel.setVisible(s == State.Installed);
        progressBar.setVisible(s == State.Downloading);
        progressBar.setIndeterminate(true);
        notesContainer.setVisible(s == State.Available);
        doneContainer.setVisible(s == State.Installed);
        // R62：兜底链接行只在「检查失败且通道缺失」态可见（网络态/安装失败/其他态一律隐藏）
        fallbackLinksContainer.setVisible(
            s == State.Failed && pendingManifest == null
                && failureKind == PluginUpdateRunner.FailureKind.CHANNEL_MISSING);
        switch (s) {
            case Checking -> statusLabel.setText(MpSqlVisualizerBundle.message("settings.update.checking"));
            case UpToDate -> statusLabel.setText(MpSqlVisualizerBundle.message("settings.update.upToDate"));
            case Available -> statusLabel.setText(
                MpSqlVisualizerBundle.message("settings.update.available", pendingManifest != null ? pendingManifest.version() : "?"));
            case Downloading -> statusLabel.setText(MpSqlVisualizerBundle.message("settings.update.downloading"));
            case Installed -> statusLabel.setText(MpSqlVisualizerBundle.message("settings.update.installed"));
            case InstalledLive -> statusLabel.setText(MpSqlVisualizerBundle.message("settings.update.installedLive"));
            case Failed -> {
                // R62：检查失败按 FailureKind 分流文案（三态可诊断）；安装失败维持原文案
                if (pendingManifest == null && failureKind != null) {
                    statusLabel.setText(MpSqlVisualizerBundle.message(
                        failureKind == PluginUpdateRunner.FailureKind.CHANNEL_MISSING
                            ? "settings.update.fail.channel404"
                            : "settings.update.fail.network",
                        failureReason != null ? failureReason : "?"));
                } else {
                    statusLabel.setText(MpSqlVisualizerBundle.message(
                        "settings.update." + (pendingManifest == null ? "checkFailed" : "installFailed"),
                        failureReason != null ? failureReason : "?"));
                }
            }
            case Idle -> statusLabel.setText(" ");
        }
    }

    /** 版本行/开关/日志与按钮文本刷新（语言切换 rebuild 时调用，仿 ShortcutEditor.updateTexts）。 */
    public void updateTexts() {
        String cv = PluginVersion.current();
        versionLabel.setText(MpSqlVisualizerBundle.message("settings.update.currentVersion")
            + ": " + (cv != null ? cv : "—"));
        autoCheckCheck.setText(MpSqlVisualizerBundle.message("settings.update.autoCheck"));
        autoCheckCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.update.autoCheck.tip"));
        checkButton.setText(MpSqlVisualizerBundle.message("settings.update.check"));
        installButton.setText(MpSqlVisualizerBundle.message("settings.update.downloadInstall"));
        installedLabel.setText(MpSqlVisualizerBundle.message("settings.update.installed"));
        restartButton.setText(MpSqlVisualizerBundle.message("settings.update.restartNow"));
        notesTitleLabel.setText(MpSqlVisualizerBundle.message("settings.update.changelog"));
        giteeLink.setText(MpSqlVisualizerBundle.message("settings.update.viewOnGitee"));
        reportProblemButton.setText(MpSqlVisualizerBundle.message("settings.update.reportProblem"));
        feedbackPoolLink.setText(MpSqlVisualizerBundle.message("settings.update.feedbackPool"));
        renderState();
    }

    /** 从配置加载（Settings 打开/Reset 时调用）。 */
    public void resetState() {
        autoCheckCheck.setSelected(settings.getState().getAutoCheckUpdate());
        pendingManifest = null;
        failureReason = null;
        failureKind = null;
        state = State.Idle;
        renderState();
    }

    // ------------------------------------------------------------------
    // 异步流程（Task.Backgroundable；回调 EDT + 判活，面板销毁则静默）
    // ------------------------------------------------------------------

    /** 检查更新（防重入：Checking/Downloading 中忽略）。 */
    public void runCheck() {
        if (state == State.Checking || state == State.Downloading) return;
        state = State.Checking;
        renderState();
        ProgressManager.getInstance().run(new Task.Backgroundable(
            null,
            MpSqlVisualizerBundle.message("update.task.check"),
            true) {
            @Override
            public void run(ProgressIndicator indicator) {
                PluginUpdateRunner.CheckResult result = checkRunner.invoke();
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (isDisplayable()) renderCheck(result);
                });
            }
        });
    }

    /** 下载并安装（进度条 fraction 经 {@link SectionProgress} 驱动；防重入：Downloading 中忽略）。 */
    public void runInstall() {
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

    public void setCheckRunner(Function0<PluginUpdateRunner.CheckResult> checkRunner) {
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
