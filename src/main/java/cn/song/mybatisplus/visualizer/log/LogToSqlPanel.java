package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Splitter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * 「日志转 SQL」tab 面板（T-M38）：粘贴 MyBatis 日志（`Preparing:` / `Parameters:` 行）→
 * 批量还原为可执行 SQL。复用 {@link MyBatisLogParser} 配对与 {@link LogSqlRestorer} 还原（与自动捕获同链路，
 * 前缀/混合/截断配置同源读取设置）；一段日志含多组语句时全部转换，组间 `-- SQL #N` 注释分隔。
 */
public class LogToSqlPanel extends JPanel implements Disposable {

    private static final String TOOLBAR_ID_INPUT = "cn.song.mybatisplus.visualizer.logToSql.toolbar.input";
    private static final String TOOLBAR_ID_RESULT = "cn.song.mybatisplus.visualizer.logToSql.toolbar.result";

    /** T-Perf-14：大日志后台转换阈值——字符数（约 1MB）。 */
    private static final int HEAVY_INPUT_CHARS = 1_000_000;
    /** T-Perf-14：大日志后台转换阈值——行数（约 1000 行）。 */
    private static final int HEAVY_INPUT_LINES = 1_000;

    /** T-BatchInMerge：合并参考行里的 SQL 展示截断（防浮层单行撑爆）。 */
    private static final int MERGE_HINT_SQL_DISPLAY_LIMIT = 160;

    /** T-BatchInMerge：「…等 N 条」后缀的展示预算预留（截值不截计数）。 */
    private static final int MERGE_MORE_RESERVE = 24;

    /** T-PS5-11：合并参考分析的语句数上限（超限跳过逐语句 AST 预检，按计数披露）。 */
    private static final int MERGE_ANALYZE_STATEMENT_LIMIT = 100;

    private final Project project;
    private final Editor inputEditor;
    private final Editor resultEditor;

    /** O-92：「从剪贴板读取」动作（输入工具栏成员引用，测试驱动/断言用）。 */
    private final ReadClipboardAction readClipboardAction;

    private final JBLabel statusLabel;

    /** 执行错误富承载（富错误+复制，无定位动作——日志还原链无 mapper 来源锚点）。 */
    private final cn.song.mybatisplus.visualizer.ui.SqlResultPanel executionErrorPanel =
            new cn.song.mybatisplus.visualizer.ui.SqlResultPanel();

    // T-PreviewUx-Pack #2：诚实降级横幅（面板内嵌非模态提示，不算弹窗）——还原失败/参数缺失
    // 不再仅靠单行状态红字，横幅承载三要素（哪段没还原/为什么/用户能做什么）
    private final cn.song.mybatisplus.visualizer.ui.DegradationBanner degradationBanner =
            new cn.song.mybatisplus.visualizer.ui.DegradationBanner();

    /**
     * T-UiNative T-UN-14：空态提示接编辑器原生 placeholder（两区锚定物均为 EditorFactory
     * 直建编辑器，实际类型 EditorEx——243 javap 核实有 {@code setPlaceholder(CharSequence)}），
     * 显隐由编辑器随空文档/焦点原生管理；文案键（"log.panel.hint.input" / "log.panel.hint.result"）沿用不变。
     */
    private final String inputPlaceholder = MpSqlVisualizerBundle.message("log.panel.hint.input");
    private final String resultPlaceholder = MpSqlVisualizerBundle.message("log.panel.hint.result");

    // T-LogToSqlHint（20261010 用户优化点）：两区下方恒显灰字提示（对齐 SqlToJavaPanel 形态——
    // 编辑器原生 placeholder 仅聚焦时可见，未聚焦空态观感为「无提示」）
    private final JBLabel inputHintLabel;
    private final JBLabel resultHintLabel;

    /** 输出格式切换态：true=多行格式化（默认，用户确认）；false=日志原样压缩。 */
    private boolean formatToggled = true;

    /** T-Perf-14：后台转换进行中标志（防重入）。 */
    private final java.util.concurrent.atomic.AtomicBoolean converting = new java.util.concurrent.atomic.AtomicBoolean(false);

    private boolean disposed = false;

    public LogToSqlPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.statusLabel = new JBLabel(" ");
        this.statusLabel.setBorder(JBUI.Borders.empty(2, 6, 2, 6));
        this.inputEditor = createInputEditor();
        this.resultEditor = createResultEditor();
        this.readClipboardAction = new ReadClipboardAction();
        this.inputHintLabel = new JBLabel(MpSqlVisualizerBundle.message("log.panel.hint.input"));
        this.inputHintLabel.setForeground(com.intellij.util.ui.UIUtil.getInactiveTextColor());
        this.inputHintLabel.setBorder(JBUI.Borders.empty(2, 6, 2, 6));
        this.resultHintLabel = new JBLabel(MpSqlVisualizerBundle.message("log.panel.hint.result"));
        this.resultHintLabel.setForeground(com.intellij.util.ui.UIUtil.getInactiveTextColor());
        this.resultHintLabel.setBorder(JBUI.Borders.empty(2, 6, 2, 6));

        // 上：日志输入区（工具栏 + 灰字提示 + 编辑器——位置对齐 SqlToJavaPanel：
        // 提示在工具栏下方、编辑器上方，两面板统一）
        JPanel inputPanel = new JPanel(new BorderLayout());
        JPanel inputNorth = new JPanel(new BorderLayout());
        inputNorth.add(buildInputToolbar(), BorderLayout.NORTH);
        inputNorth.add(inputHintLabel, BorderLayout.SOUTH);
        inputPanel.add(inputNorth, BorderLayout.NORTH);
        inputPanel.add(inputEditor.getComponent(), BorderLayout.CENTER);

        // 下：SQL 结果区（工具栏 + 灰字提示 + 编辑器，同构）
        JPanel resultPanel = new JPanel(new BorderLayout());
        JPanel resultNorth = new JPanel(new BorderLayout());
        resultNorth.add(buildResultToolbar(), BorderLayout.NORTH);
        resultNorth.add(resultHintLabel, BorderLayout.SOUTH);
        resultPanel.add(resultNorth, BorderLayout.NORTH);
        resultPanel.add(resultEditor.getComponent(), BorderLayout.CENTER);

        Splitter splitter = new Splitter(true, 0.5f);
        splitter.setFirstComponent(inputPanel);
        splitter.setSecondComponent(resultPanel);
        splitter.setShowDividerControls(false);
        add(splitter, BorderLayout.CENTER);
        // T-PreviewUx-Pack #2：底部区 = 诚实降级横幅（上）+ 执行错误富承载（中）+ 状态行（下）——
        // 横幅触发即显示、恢复即隐藏（不占高度），状态行语义不变
        JPanel southHost = new JPanel(new BorderLayout());
        southHost.add(degradationBanner, BorderLayout.NORTH);
        southHost.add(executionErrorPanel, BorderLayout.CENTER);
        southHost.add(statusLabel, BorderLayout.SOUTH);
        add(southHost, BorderLayout.SOUTH);

        // 灰字提示随各自内容显隐（空文档显示、有内容隐藏；两区独立监听不互串；
        // 243 平台 DocumentListener 已 Kotlin 化——Kotlin 接口无 SAM，Java 侧用匿名类）
        com.intellij.openapi.editor.event.DocumentListener inputSync =
                new com.intellij.openapi.editor.event.DocumentListener() {
                    @Override
                    public void documentChanged(com.intellij.openapi.editor.event.DocumentEvent event) {
                        inputHintLabel.setVisible(event.getDocument().getText().isBlank());
                    }
                };
        com.intellij.openapi.editor.event.DocumentListener resultSync =
                new com.intellij.openapi.editor.event.DocumentListener() {
                    @Override
                    public void documentChanged(com.intellij.openapi.editor.event.DocumentEvent event) {
                        resultHintLabel.setVisible(event.getDocument().getText().isBlank());
                    }
                };
        inputEditor.getDocument().addDocumentListener(inputSync);
        resultEditor.getDocument().addDocumentListener(resultSync);
        inputHintLabel.setVisible(inputEditor.getDocument().getText().isBlank());
        resultHintLabel.setVisible(resultEditor.getDocument().getText().isBlank());
    }

    // ------------------------------------------------------------------
    // 工具栏
    // ------------------------------------------------------------------

    private JPanel buildInputToolbar() {
        DefaultActionGroup group = new DefaultActionGroup(
                new ConvertAction(),
                // O-92（批次9 波次2，用户定案「剪贴板按钮先行」）：粘贴还原入口低门槛化——
                // V32 实证唯一入口=本 tab 手动 Ctrl+V，DBX #167「贴文本得 SQL」默认期待下降为
                // 一次点击；编辑器右键/意图入口留后续批次评估（不在本件扩面）。
                readClipboardAction,
                new ClearInputAction());
        com.intellij.openapi.actionSystem.ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar(TOOLBAR_ID_INPUT, group, true);
        toolbar.setTargetComponent(this);
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        panel.add(toolbar.getComponent());
        return panel;
    }

    private JPanel buildResultToolbar() {
        DefaultActionGroup group = new DefaultActionGroup(new CopyAction(), new ToggleFormatAction(),
                // T-PreviewUx-Pack #4：执行还原产物（送原生 Database 控制台链路；多条语句先勾选确认）
                new ExecuteResultAction(),
                new ClearResultAction());
        com.intellij.openapi.actionSystem.ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar(TOOLBAR_ID_RESULT, group, true);
        toolbar.setTargetComponent(this);
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        panel.add(toolbar.getComponent());
        return panel;
    }

    private class ConvertAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.Execute);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.panel.convert"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            convert();
        }
    }

    /**
     * O-92：从剪贴板读取日志文本填入输入区——「新增 UI 交互须照抄既有同类实现」定例：
     * 同族工具栏动作形态（update 设图标/文案 + actionPerformed 直调面板方法，同
     * {@link ConvertAction} / {@link CopyAction}）；填入即转照抄 {@link #loadFromEntry}（列表「发送到日志转 SQL」
     * 的既有同语义链路）。「有内容才启用」在 update 内探测剪贴板（同平台粘贴动作惯例；
     * 工具窗工具栏刷新频度低，探测成本可忽略），剪贴板空点击给状态行轻提示（不弹窗，
     * 弹窗克制红线）。图标 MenuPaste：243 基线 AllIcons$Actions javap 204 字段核实
     * <b>无 Paste 字段</b>（MenuPaste/Menu_paste 在），自家动作同族取用防 NoSuchFieldError。
     */
    private class ReadClipboardAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.MenuPaste);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.panel.readClipboard"));
            String clip = UiTextClipboard.readText();
            e.getPresentation().setEnabled(clip != null && !clip.isBlank());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            readFromClipboard();
        }
    }

    private class ClearInputAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.GC);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.panel.clearInput"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            setDocumentText(inputEditor, "");
        }
    }

    private class CopyAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.Copy);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.panel.copy"));
            e.getPresentation().setEnabled(!resultEditor.getDocument().getText().isBlank());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            UiTextClipboard.copy(resultEditor.getDocument().getText());
            status(MpSqlVisualizerBundle.message("logToSql.copy.done"), false);
        }
    }

    /** 压缩/多行切换：立即用当前输入重转（空输入不动作）。 */
    private class ToggleFormatAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.ReformatCode);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.panel.toggleFormat"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            formatToggled = !formatToggled;
            if (!inputEditor.getDocument().getText().isBlank()) {
                convert();
            }
        }
    }

    private class ClearResultAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.GC);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.panel.clearResult"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            setDocumentText(resultEditor, "");
            // T-AutoFold F4-c：清空后同步清除折叠区域
            cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(resultEditor, "");
            status(" ", false);
        }
    }

    /**
     * T-PreviewUx-Pack #4：执行还原产物（吸收 DBX「多语句执行确认」口径）——
     * 产物拆分后<b>单条直通</b>（不增加低价值打断）；多条先弹候选勾选浮层
     * （T-CrudSql 原生形态：JBPopup 无按钮浮层，Enter/双击执行选中项、ESC 关闭、打字过滤），
     * 选中项送既有执行链路（execute/DataSourceSelector + SqlExecutor——原生 Database 控制台，
     * 凭证不出 IDE；Community 由 SqlExecutor 提示降级）。
     */
    private class ExecuteResultAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(AllIcons.Actions.Execute);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.panel.execute"));
            e.getPresentation().setEnabled(!resultEditor.getDocument().getText().isBlank());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            executeResult();
        }
    }

    /** 执行入口：拆语句 → 单条直通 / 多条勾选浮层。 */
    private void executeResult() {
        String text = resultEditor.getDocument().getText();
        if (text.isBlank()) return;
        List<String> statements = splitSqlStatements(text);
        if (statements.isEmpty()) {
            status(MpSqlVisualizerBundle.message("logToSql.panel.noEntry"), true);
            return;
        }
        if (statements.size() == 1) {
            executeStatement(statements.get(0));
            return;
        }
        // 多条：候选勾选浮层（CrudSqlPreviewFlow.showCandidates 同款范式——无按钮、
        // Enter/双击确认、ESC 关闭、速度搜索过滤；条目 = 摘要行 + 完整语句详情行）
        List<StatementCandidate> rows = new ArrayList<>();
        for (int index = 0; index < statements.size(); index++) {
            String sql = statements.get(index);
            String keyword = cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier.getFirstKeyword(sql);
            rows.add(new StatementCandidate(
                    index + 1,
                    keyword != null ? keyword : "SQL",
                    sql));
        }
        // T-BatchInMerge（O-14）：同表等值批删的合并参考行——浮层原生单行提示位
        // （adText），零新弹窗零新组件形态；执行文本与复制行为零改动
        String mergeAdText = buildMergeAdText(statements);
        com.intellij.openapi.ui.popup.IPopupChooserBuilder<StatementCandidate> builder =
                com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(rows)
                        .setTitle(MpSqlVisualizerBundle.message("logToSql.execute.pickTitle"))
                        .setRenderer(new StatementCandidateRenderer())
                        .setNamerForFiltering(StatementCandidate::summary)
                        .setItemChosenCallback(selected -> {
                            // 执行延后到选择浮层关闭之后（T-CrudSql 真机修复同款：不在选择 UI 的事件栈内
                            // 同步开 modeless 链路，262 实测会被吞且零异常零日志）
                            ApplicationManager.getApplication().invokeLater(() -> executeStatement(selected.sql()));
                        });
        if (mergeAdText != null) {
            builder.setAdText(mergeAdText);
        }
        builder.createPopup().showInFocusCenter();
    }

    /** 执行单条语句：非查询先 DML 二次确认（与预览面板执行同交互），随后送原生控制台链路。
     *  T-UnboundedWriteGuard：确认消息经 ExecuteConfirmMessages——命中无界写特征时同一对话框
     *  列出发现（不新增弹窗形态/步骤），无发现时文案逐字节不变。
     *  弹窗白名单依据（BannedApiSourceScanTest「模态弹窗只允许白名单内既有场景」）：非查询提交前
     *  的 YesNo 确认属「用户必须做选择」场景，与 SqlPreviewPanel 执行确认同类。 */
    private void executeStatement(String sql) {
        boolean isQuery = cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier.isQuery(sql);
        if (!isQuery) {
            String keyword = cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier.getFirstKeyword(sql);
            String keywordOrSql = keyword != null ? keyword : "SQL";
            int confirm = com.intellij.openapi.ui.Messages.showYesNoDialog(
                    project,
                    cn.song.mybatisplus.visualizer.execute.ExecuteConfirmMessages.build(
                            sql,
                            keywordOrSql,
                            // R52：日志还原链无预览会话方言，取当前生效方言（与渲染/执行链同源函数）
                            cn.song.mybatisplus.visualizer.sql.DialectRegistry.INSTANCE.effective(project)),
                    MpSqlVisualizerBundle.message("execute.confirm.dml"),
                    com.intellij.openapi.ui.Messages.getWarningIcon());
            if (confirm != com.intellij.openapi.ui.Messages.YES) return;
        }
        cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request dialectGateRequest =
                cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.rawSqlRequest(sql);
        status(MpSqlVisualizerBundle.message("logToSql.execute.pending"), false);
        cn.song.mybatisplus.visualizer.execute.SqlExecutor.execute(
                project, sql, null, isQuery,
                null, null,
                this::showExecutionError,
                this::showExecutionErrorRich,
                false,
                dialectGateRequest, null,
                this::showSubmission,
                false);
    }

    /** 提交回执驱动状态行：SUBMITTED 才报已提交，取消/被闸拦各按其文案（不虚报成功）。 */
    private void showSubmission(cn.song.mybatisplus.visualizer.execute.ExecutionSubmission outcome) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(
                    () -> showSubmission(outcome), com.intellij.openapi.application.ModalityState.any());
            return;
        }
        switch (outcome) {
            case SUBMITTED -> status(MpSqlVisualizerBundle.message("logToSql.execute.submitted"), false);
            case CANCELLED -> status(MpSqlVisualizerBundle.message("logToSql.execute.cancelled"), false);
            case BLOCKED -> showExecutionError(MpSqlVisualizerBundle.message("logToSql.execute.blocked"));
        }
    }

    /** 日志没有 mapper 来源锚点：保留富错误与复制，但不提供虚假的定位动作。 */
    private void showExecutionErrorRich(cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo info) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(
                    () -> showExecutionErrorRich(info), com.intellij.openapi.application.ModalityState.any());
            return;
        }
        if (disposed || project.isDisposed()) return;
        String text = cn.song.mybatisplus.visualizer.execute.ExecutionErrorFormatter.format(info);
        status(text, true);
        statusLabel.setVisible(false);
        executionErrorPanel.showErrorRich(info, null);
    }

    /** 执行链回调有 EDT 与后台两条路径；只在 EDT 更新面板，不弹窗。 */
    private void showExecutionError(String message) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(
                    () -> showExecutionError(message), com.intellij.openapi.application.ModalityState.any());
            return;
        }
        if (disposed || project.isDisposed()) return;
        status(message, true);
        statusLabel.setVisible(false);
        executionErrorPanel.showError(message);
    }

    // ------------------------------------------------------------------
    // 转换流程
    // ------------------------------------------------------------------

    /**
     * O-92：剪贴板 → 输入区（EDT）。有文本：填入即转（{@link #loadFromEntry} 同款链路，小输入同步
     * 转换、大输入走 {@link #convert} 既有的后台分频路径）；剪贴板空：状态行轻提示，零写入零弹窗。
     */
    private void readFromClipboard() {
        String text = UiTextClipboard.readText();
        if (text == null || text.isBlank()) {
            status(MpSqlVisualizerBundle.message("logToSql.clipboard.empty"), false);
            return;
        }
        setDocumentText(inputEditor, text);
        convert();
    }

    /**
     * 逐行解析输入日志 → 批量还原（每组 withTerminator + 格式化/压缩切换）→ 写结果区。
     *
     * 三解析器并列：MyBatis + Hibernate + JdbcTemplate 同时喂行，各自识别匹配的格式，结果合并输出。
     * 一段混合日志（同时含多种格式）也能正确转换。
     *
     * T-Perf-14：大日志（超过 {@link #HEAVY_INPUT_CHARS} / {@link #HEAVY_INPUT_LINES} 阈值，粘贴数 MB 日志场景）
     * 解析 + 逐条格式化移 {@link com.intellij.openapi.progress.Task.Backgroundable} 后台 + 进度，EDT 不再全量解析冻结；
     * 小输入保持同步（即时反馈）。防重入：后台转换进行中忽略再次触发。
     */
    private void convert() {
        String text = inputEditor.getDocument().getText();
        if (text.isBlank()) {
            status(MpSqlVisualizerBundle.message("logToSql.panel.empty"), true);
            return;
        }
        // 前缀/混合/截断与自动捕获同源（每次转换即时读取，改设置立即生效）
        LogParsers parsers = buildParsers();
        int lineCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineCount++;
            }
        }
        boolean heavy = text.length() > HEAVY_INPUT_CHARS || lineCount >= HEAVY_INPUT_LINES;
        if (!heavy) {
            applyConversion(computeConversion(text, parsers));
            return;
        }
        if (!converting.compareAndSet(false, true)) {
            return;
        }
        status(MpSqlVisualizerBundle.message("logToSql.status.converting"), false);
        new com.intellij.openapi.progress.Task.Backgroundable(
                project,
                MpSqlVisualizerBundle.message("logToSql.panel.convertingTask"),
                true) {
            @Override
            public void run(com.intellij.openapi.progress.ProgressIndicator indicator) {
                final ConversionOutput out = computeConversion(text, parsers);
                ApplicationManager.getApplication().invokeLater(() -> {
                    converting.set(false);
                    if (project.isDisposed()) {
                        return;
                    }
                    applyConversion(out);
                });
            }
        }.queue();
    }

    /** 四解析器装配（转换配置每次转换即时读取；R10 增第四格式 ShardingSphere 双段日志）。 */
    private LogParsers buildParsers() {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        return new LogParsers(
                new MyBatisLogParser(new LogSqlRestorer(),
                        state.getLogSqlPrefix(), state.getLogParamPrefix(), state.getLogMixedPrefix(), state.getLogMaxParseLength()),
                new HibernateLogParser(new LogSqlRestorer(),
                        state.getLogHibernateSqlPrefix(), state.getLogHibernateParamPrefix(), state.getLogMixedPrefix(),
                        state.getLogMaxParseLength(), true),
                new JdbcTemplateLogParser(new LogSqlRestorer(), state.getLogJdbcEnabled(), state.getLogMaxParseLength()),
                new ShardingSphereLogParser(new LogSqlRestorer(), state.getLogMaxParseLength()),
                state.getLogShardingSphereEnabled());
    }

    /** 纯计算：逐行喂四解析器 + 逐条还原/格式化（后台线程安全——不触碰 Swing）。 */
    private ConversionOutput computeConversion(String text, LogParsers parsers) {
        MyBatisLogParser myBatisParser = parsers.myBatis();
        HibernateLogParser hibernateParser = parsers.hibernate();
        JdbcTemplateLogParser jdbcParser = parsers.jdbc();
        for (String line : text.split("\n", -1)) {
            // F58：同款每行一次 token 下传
            String threadToken = LogThreadRouter.threadTokenOf(line);
            myBatisParser.feed(line, threadToken);
            hibernateParser.feed(line, threadToken);
            jdbcParser.feed(line, threadToken);
            if (parsers.shardingEnabled()) {
                parsers.sharding().feed(line);
            }
        }
        myBatisParser.closePending();
        hibernateParser.closePending();
        jdbcParser.closePending();
        parsers.sharding().closePending();
        List<LogSqlEntry> entries = new ArrayList<>();
        entries.addAll(myBatisParser.takeEntries());
        entries.addAll(hibernateParser.takeEntries());
        entries.addAll(jdbcParser.takeEntries());
        entries.addAll(parsers.sharding().takeEntries());
        if (entries.isEmpty()) {
            return new ConversionOutput(null, 0, 0);
        }
        boolean multi = entries.size() > 1;
        // T-PerfSweep6 T-PS6-08（性能报告发现 16）：未还原计数并入既有单遍遍历（master 侧为并入既有单遍 mapIndexed）。
        // 此前是第二次 `entries.count { countPlaceholders(it.restoredSql) > 0 }`——同一批条目再遍历
        // 一遍列表、再进一遍 lambda（字符串扫描次数不变，省的是第二次列表遍历与闭包开销）。
        // T-HibDualCapture：string-aware 计数，字符串/注释里的 `?` 不再误计。
        int unresolved = 0;
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < entries.size(); index++) {
            LogSqlEntry entry = entries.get(index);
            if (LogEntryPreviewRecognizerKt.countPlaceholders(entry.restoredSql()) > 0) {
                unresolved++;
            }
            String base = withTerminator(entry.restoredSql());
            String body = formatToggled ? SqlFormatter.INSTANCE.format(base) : base;
            String piece;
            if (multi) {
                // T-StatementNaming（O-19）：语句前导注释 `-- Name: x`（或最近行注释）→ 组头附「 [名称]」；
                // 无注释 nameOf=null 后缀空串——组头逐字节维持现状（硬门禁）
                String named = cn.song.mybatisplus.visualizer.util.SqlStatementNamer.nameOf(entry.restoredSql());
                piece = "-- " + MpSqlVisualizerBundle.message("logToSql.status.group", index + 1)
                        + (named != null ? " [" + named + "]" : "") + "\n" + body;
            } else {
                piece = body;
            }
            if (index > 0) {
                out.append("\n\n");
            }
            out.append(piece);
        }
        return new ConversionOutput(out.toString(), entries.size(), unresolved);
    }

    /** 应用转换结果（EDT）：写结果区 + 状态行 + 诚实降级横幅。 */
    private void applyConversion(ConversionOutput out) {
        if (out.text() == null) {
            // T-PreviewUx-Pack #2：未识别到条目 → 横幅三要素（哪段没还原=整段日志，为什么=前缀不匹配，能做什么）
            degradationBanner.show(
                    cn.song.mybatisplus.visualizer.ui.DegradationBanner.Kind.RENDER_WARNING,
                    MpSqlVisualizerBundle.message("logToSql.banner.noEntry"));
            status(MpSqlVisualizerBundle.message("logToSql.panel.noEntry"), true);
            return;
        }
        setDocumentText(resultEditor, out.text());
        // T-AutoFold F4-c：转换产出后按当前设置重建结果区折叠（关闭态零操作）。
        // 随 dev 的计算/应用拆分从 computeConversion（后台线程）移到此处（EDT），写后即折。
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(resultEditor, out.text());
        if (out.unresolved() > 0) {
            // T-PreviewUx-Pack #2：还原不完整横幅（为什么=Parameters 缺失/残缺，能做什么=补全参数行）
            degradationBanner.show(
                    cn.song.mybatisplus.visualizer.ui.DegradationBanner.Kind.RENDER_WARNING,
                    MpSqlVisualizerBundle.message("logToSql.banner.unresolved", out.unresolved()));
        } else {
            degradationBanner.clear(cn.song.mybatisplus.visualizer.ui.DegradationBanner.Kind.RENDER_WARNING);
        }
        if (out.unresolved() > 0) {
            status(MpSqlVisualizerBundle.message("logToSql.status.unresolved", out.unresolved()), true);
        } else {
            status(MpSqlVisualizerBundle.message("logToSql.status.converted", out.entriesCount()), false);
        }
    }

    /** 转换结果（null text = 无条目）。 */
    private record ConversionOutput(String text, int entriesCount, int unresolved) {
    }

    /** 四解析器装配载体（Kotlin 多值类 LogParsers 的 Java 承载）。 */
    private record LogParsers(
            /// MyBatis 解析器
            MyBatisLogParser myBatis,
            /// Hibernate 解析器
            HibernateLogParser hibernate,
            /// JdbcTemplate 解析器
            JdbcTemplateLogParser jdbc,
            /// ShardingSphere 双段日志解析器（R10 第四格式）
            ShardingSphereLogParser sharding,
            /// ShardingSphere 喂行开关（与自动捕获同源读取设置）
            boolean shardingEnabled) {
    }

    /** T-M38：列表「发送到日志转 SQL」落点——条目渲染回日志文本填入输入区并立即转换。 */
    void loadFromEntry(LogSqlEntry entry) {
        setDocumentText(inputEditor, LogSqlModelsKt.toLogText(entry));
        convert();
    }

    /** SQL 末尾分号结束符（显示层统一追加；已以 `;` 结尾或空串不重复）。 */
    private String withTerminator(String sql) {
        return (sql.isEmpty() || sql.endsWith(";")) ? sql : sql + ";";
    }

    private void setDocumentText(Editor editor, String text) {
        if (editor.getDocument().getText().equals(text)) {
            return;
        }
        WriteCommandAction.runWriteCommandAction(project, () -> editor.getDocument().setText(text));
    }

    /** 状态行（错误红字 / 提示主题色）；普通状态恢复时撤销上次执行错误动作。 */
    private void status(String message, boolean error) {
        executionErrorPanel.showEmpty();
        statusLabel.setVisible(true);
        statusLabel.setForeground(error
                ? JBColor.namedColor("ErrorForeground", JBColor.RED)
                : EditorColorsManager.getInstance().getGlobalScheme().getAttributes(HighlighterColors.TEXT).getForegroundColor());
        statusLabel.setText(message);
    }

    // ------------------------------------------------------------------
    // 编辑器
    // ------------------------------------------------------------------

    /** 日志输入编辑器：普通文本（日志含前缀/时间戳等噪声，SQL 高亮会误导）；行号开。 */
    private Editor createInputEditor() {
        Editor editor = EditorFactory.getInstance()
                .createEditor(EditorFactory.getInstance().createDocument(""), project);
        EditorEx ex = (EditorEx) editor;
        ex.getSettings().setLineNumbersShown(true);
        ex.getSettings().setFoldingOutlineShown(false);
        ex.getSettings().setIndentGuidesShown(false);
        ex.getSettings().setAdditionalPageAtBottom(false);
        // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
        cn.song.mybatisplus.visualizer.ui.EditorWhitespaceSettings.applyTo(ex);
        ex.setPlaceholder(inputPlaceholder); // T-UiNative T-UN-14：空态提示原生件
        return editor;
    }

    /**
     * SQL 结果编辑器：可编辑（微调参数后复制）+ 自研 SQL 词法高亮。
     *
     * <p>T-SqlDialectPrewarm：此前这里取平台 SQL FileType 的高亮器
     * （{@code SyntaxHighlighterFactory.getSyntaxHighlighter(sqlFileType, project, null)}），链路会落到
     * {@code SqlDialectMappings.getMapping} → {@code PerFileMappingsBase.ensureStateLoaded} 的<b>一次性延迟加载</b>
     * ——逐条 {@code VirtualFileManager.findFileByUrl} 解析持久化的方言映射，在 EDT 上撞平台慢操作断言
     * （真机栈：本方法 → {@code FSRecordsImpl.update} → {@code SlowOperations.assertSlowOperationsAreAllowed}）。
     * 改走自研 {@code cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter}：不查方言映射、
     * 不依赖 Database 插件（未装时此前该区根本没有高亮），配色与预览面板/日志预览同源。</p>
     */
    private Editor createResultEditor() {
        Editor editor = EditorFactory.getInstance()
                .createEditor(EditorFactory.getInstance().createDocument(""), project);
        EditorEx ex = (EditorEx) editor;
        ex.setHighlighter(new LexerEditorHighlighter(
                new cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter(),
                EditorColorsManager.getInstance().getGlobalScheme()));
        ex.getSettings().setLineNumbersShown(true);
        ex.getSettings().setFoldingOutlineShown(true);
        ex.getSettings().setIndentGuidesShown(false);
        ex.getSettings().setAdditionalPageAtBottom(false);
        // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
        cn.song.mybatisplus.visualizer.ui.EditorWhitespaceSettings.applyTo(ex);
        ex.setPlaceholder(resultPlaceholder); // T-UiNative T-UN-14：空态提示原生件
        return editor;
    }

    /** T-UiNative T-UN-14：输入区空态提示（原 hintLabel 测试缝，断言 placeholder 文案接线用）。 */
    String inputPlaceholderForTest() {
        return inputPlaceholder;
    }

    /** T-UiNative T-UN-14：结果区空态提示。 */
    String resultPlaceholderForTest() {
        return resultPlaceholder;
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        EditorFactory.getInstance().releaseEditor(inputEditor);
        EditorFactory.getInstance().releaseEditor(resultEditor);
    }

    // ------------------------------------------------------------------
    // internal 测试访问器
    // ------------------------------------------------------------------

    void setInputForTest(String text) {
        setDocumentText(inputEditor, text);
    }

    void convertForTest() {
        convert();
    }

    /** O-92：输入区当前文本（剪贴板填入断言用）。 */
    String inputTextForTest() {
        return inputEditor.getDocument().getText();
    }

    /** O-92：等价点击「从剪贴板读取」。 */
    void readClipboardForTest() {
        readFromClipboard();
    }

    void toggleFormatForTest() {
        formatToggled = !formatToggled;
    }

    String resultTextForTest() {
        return resultEditor.getDocument().getText();
    }

    /** T-SqlDialectPrewarm：结果区写入（高亮器 token 类型断言用）。 */
    void setResultForTest(String text) {
        setDocumentText(resultEditor, text);
    }

    /** T-AutoFold F4-c：结果区编辑器（折叠区域断言用）。 */
    Editor resultEditorForTest() {
        return resultEditor;
    }

    String statusForTest() {
        return statusLabel.getText();
    }

    /** T-PreviewUx-Pack #2：诚实降级横幅（显隐/文案断言用）。 */
    cn.song.mybatisplus.visualizer.ui.DegradationBanner degradationBannerForTest() {
        return degradationBanner;
    }

    // ------------------------------------------------------------------
    // T-PreviewUx-Pack #4：多语句执行确认（候选模型 / 渲染器 / 语句拆分）
    // ------------------------------------------------------------------

    /** 候选项（纯数据，可单测）：序号 + 首关键字摘要 + 完整语句。 */
    record StatementCandidate(int index, String keyword, String sql) {
        /** 摘要行：{@code SQL #N · 关键字}（CrudSqlPreviewFlow.summary 同款 `·` 分隔风格）。 */
        String summary() {
            return MpSqlVisualizerBundle.message("logToSql.status.group", index) + " · " + keyword;
        }
    }

    /**
     * 候选行渲染器：主题色双行（加粗摘要 + 次要色详情），CrudSqlPreviewFlow.CandidateCellRenderer
     * 同款范式；详情行禁 HTML 解析（语句可能以 {@code <} 开头），按 {@link SqlSelectionDialog#MAX_LINE_LENGTH} 截断。
     */
    private static class StatementCandidateRenderer extends JPanel implements javax.swing.ListCellRenderer<StatementCandidate> {
        private final javax.swing.JLabel summaryLabel = new javax.swing.JLabel();
        private final javax.swing.JLabel detailLabel = new javax.swing.JLabel();

        StatementCandidateRenderer() {
            setOpaque(true);
            setBorder(JBUI.Borders.empty(6, 12));
            summaryLabel.setFont(summaryLabel.getFont().deriveFont(java.awt.Font.BOLD));
            detailLabel.putClientProperty("html.disable", true);
            detailLabel.setForeground(com.intellij.util.ui.UIUtil.getContextHelpForeground());
            add(summaryLabel, BorderLayout.NORTH);
            add(detailLabel, BorderLayout.CENTER);
        }

        @Override
        public java.awt.Component getListCellRendererComponent(
                javax.swing.JList<? extends StatementCandidate> list,
                StatementCandidate value,
                int index,
                boolean selected,
                boolean focused) {
            summaryLabel.setText(value.summary());
            String sql = value.sql();
            int nl = sql.indexOf('\n');
            String firstLine = nl >= 0 ? sql.substring(0, nl) : sql;
            if (firstLine.length() > SqlSelectionDialog.MAX_LINE_LENGTH) {
                detailLabel.setText(firstLine.substring(0, SqlSelectionDialog.MAX_LINE_LENGTH) + "…");
            } else {
                detailLabel.setText(firstLine);
            }
            setBackground(selected
                    ? com.intellij.util.ui.UIUtil.getListSelectionBackground(true)
                    : com.intellij.util.ui.UIUtil.getListBackground());
            summaryLabel.setForeground(com.intellij.util.ui.UIUtil.getListForeground());
            detailLabel.setForeground(selected
                    ? com.intellij.util.ui.UIUtil.getListForeground()
                    : com.intellij.util.ui.UIUtil.getContextHelpForeground());
            return this;
        }
    }

    java.util.List<String> statementsForTest() {
        return splitSqlStatements(resultEditor.getDocument().getText());
    }

    void loadFromEntryForTest(LogSqlEntry entry) {
        loadFromEntry(entry);
    }

    /**
     * T-PreviewUx-Pack #4：结果文本 → 语句列表（纯函数）——按顶层 {@code ;} 切分，
     * 引号字符串内的 {@code ;} 不切分；{@code --} 行注释与块注释内的 {@code ;} 不切分（LogToSqlPanel 产物
     * 组间自带 {@code -- SQL #N} 注释头，注释随所属语句一并保留——原生控制台接受注释）。
     * 段间空白丢弃，空列表 = 无可执行语句。
     *
     * <p>T-SemicolonGuard（O-13）：实现收敛委托至 util/SqlStatementSplitter
     * （单实现三处历史切分合一）；新增括号深度与 BEGIN/END 块感知两项语义
     * （迁移前后既有样例输出逐段一致，守卫测试锁死）。
     */
    public static java.util.List<String> splitSqlStatements(String sql) {
        return cn.song.mybatisplus.visualizer.util.SqlStatementSplitter.split(sql);
    }

    /**
     * T-BatchInMerge（O-14）：批量等值谓词合并参考文案（纯函数可测；null = 无合并集）。
     * 异构语句在场时按「N/M 条」披露（不静默丢）；展示预算截值清单时<b>计数后缀恒可见</b>
     * （先裁值再拼「…等 N 条」，N = 行数/字节超限 + 展示裁掉的值数之和——绝不无计数截断）。
     */
    public static String buildMergeAdText(java.util.List<String> statements) {
        // T-PS5-11：语句数预检——超上限**跳过逐语句 AST 预检**（500 行上限最坏数百次 JSqlParser
        // 解析，全部发生在浮层打开前的 EDT），改按计数披露：明说未做同构分析 + 执行仍逐条，
        // 绝不静默丢（既有「异构不静默丢」口径的计数版）。上限内维持现状。
        if (statements.size() > MERGE_ANALYZE_STATEMENT_LIMIT) {
            return MpSqlVisualizerBundle.message(
                    "logToSql.execute.mergeSkipped",
                    statements.size(),
                    MERGE_ANALYZE_STATEMENT_LIMIT);
        }
        cn.song.mybatisplus.visualizer.util.BatchPredicateMerge.MergePreview preview =
                cn.song.mybatisplus.visualizer.util.BatchPredicateMerge.mergePreview(statements);
        if (preview == null) return null;
        String prefix = "DELETE FROM " + preview.table() + " WHERE " + preview.column() + " IN (";
        // 展示预算：前缀 + 值清单 + 收括号 ≤ 上限；后缀占位先扣（保证计数可见）
        int budget = MERGE_HINT_SQL_DISPLAY_LIMIT - prefix.length() - 1;
        List<String> joined = new ArrayList<>();
        int used = 0;
        int dropped = 0;
        List<String> values = preview.values();
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            int cost = value.length() + (index > 0 ? 2 : 0);
            if (used > 0 && used + cost > budget - MERGE_MORE_RESERVE) {
                dropped = values.size() - joined.size();
                break;
            }
            used += cost;
            joined.add(value);
        }
        int hidden = preview.hiddenValues() + dropped;
        String moreText = hidden > 0 ? MpSqlVisualizerBundle.message("logToSql.execute.mergeMore", hidden) : "";
        String inner = String.join(", ", joined) + (!moreText.isEmpty() ? ", " + moreText : "");
        String displaySql = prefix + inner + ")";
        if (preview.coveredCount() == preview.totalStatements()) {
            return MpSqlVisualizerBundle.message("logToSql.execute.mergeHint", preview.coveredCount(), displaySql);
        }
        return MpSqlVisualizerBundle.message(
                "logToSql.execute.mergeHintSubset",
                preview.totalStatements(), preview.coveredCount(), displaySql);
    }
}
