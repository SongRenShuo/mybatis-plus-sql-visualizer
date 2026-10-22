// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/RollbackSqlDialog.kt 移植
// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/RollbackSqlDialog.kt 移植（O-44a 空白符可视化接线）
// T-SyncK2JMaster184 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/RollbackSqlDialog.kt 移植（O-52/O-59：粘贴回填带 dialect + identityColumns，消费点解析自增列）
// T-SyncK2JMaster496 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/RollbackSqlDialog.kt 移植
//（T-RollbackSnapshotView/CsvView 快照双态表格+CSV 编辑器、T-PreviewSideQuery 只读旁路查询、
//  T-DialectGateExit 方言冲突出口行、执行回执 onSubmission（F6 留痕改回执驱动）、快照输入失效联动）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector;
import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate;
import cn.song.mybatisplus.visualizer.execute.ExecutionSubmission;
import cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance;
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore;
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator;
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver;
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 回滚 SQL 视图（T-RollbackSql P1-F3 / P2-F5，dbx 精华融合）。
 *
 * <p>MODELESS 独立弹窗（仿 SqlPreviewDialog 惯例）——刻意不写进预览面板 native 文档、
 * 不切显示模式：`showSql` 三分支管线（T-RefreshDbSteal 治理后）零侵入。
 *
 * <p>形态按评级分层（dbx「评级不足阻断执行」的对应物）：
 * <ul>
 *  <li>Complete：绿色横幅 + 只读回滚 SQL 编辑器 + 底部「执行回滚 / 复制 / 关闭」；</li>
 *  <li>Partial：橙色横幅（风险警示）+ COUNT 自查模板展示，其余同上；</li>
 *  <li>Unable（可解析的 UPDATE/DELETE）：红色横幅 + 人工恢复指引 + **快照工作流**——
 *      快照查询模板一键执行（T-PreviewSideQuery 只读旁路：不替换预览编辑器/不换入控制台，
 *      结果自动填入下方并生成回滚；失败/空结果在对话框内提示，仍可手工复制粘贴）+
 *      粘贴结果生成完整回滚（dbx「有 before-image 才回滚」的半自动落地，P2-F5 降级方案；
 *      262 连接协议不可程序化快照的定夺见 {@link RollbackSnapshotStore} KDoc）——粘贴生成的回填 INSERT
 *      在快照条目持有 identity 信息（O-52：{@link RollbackSnapshotStore.SnapshotEntry} 的
 *      {@code dialect} / {@code identityColumns}）时按方言包装，否则不包装；</li>
 *  <li>Unable（完全不可解析/MERGE/DDL）：不进本视图，面板入口直接消息框（计划 §2.2）。</li>
 * </ul>
 *
 * <p>「执行回滚」不走
 * {@code cn.song.mybatisplus.visualizer.execute.SqlExecutor.execute} 全链弹数据源
 * 选择——持预览面板引用走 {@link SqlPreviewPanelExecutionKt#executeAdhocSql}，T-RefreshDbSteal 所有权卫队天然生效；
 * 执行提交后快照条目置「已执行」（F6 留痕，由 {@link #rollbackSubmissionReceipt()} 回执驱动）+ 横幅更新。
 */
public class RollbackSqlDialog extends DialogWrapper {

    private final Project project;
    /** 预览面板引用——「执行回滚/执行快照查询」复用 executeAdhocSql 管线（计划 §2.4）。 */
    private final SqlPreviewPanel panel;
    /** 生成结果（面板入口已按「Unable 且无快照工作流」过滤）。 */
    private final RollbackSqlGenerator.RollbackResult result;
    /** 原语句关键字（INSERT/UPDATE/DELETE）——快照回填模式分派。 */
    private final String statementKind;
    /** 经执行按钮执行过的 UPDATE/DELETE 登记条目（F6 留痕；非执行路径入口为 null）。 */
    private final RollbackSnapshotStore.SnapshotEntry snapshotEntry;

    private final JBLabel bannerLabel = new JBLabel();
    private final JBLabel warningLabel = new JBLabel();
    private final JBLabel countCheckLabel = new JBLabel();
    private final JBLabel snapshotQueryLabel = new JBLabel();

    private final JBTextArea pasteArea = createPasteArea();
    private final JBTextField pkField = new JBTextField(20);
    @Nullable
    private Editor editor = null;

    // T-RollbackSnapshotView：快照双态展示——表格视图（只读，列值一一对应）与文本视图
    //（保留手工粘贴/编辑）。自动查询结果默认表格；文本始终是生成回滚的权威来源。
    // T-RollbackSnapshotCsvView：表格增强——可排序/可选中/复制/右键菜单/行号/列宽自适应。
    private final DefaultTableModel snapshotTableModel = new DefaultTableModel() {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final TableRowSorter<DefaultTableModel> snapshotSorter = new TableRowSorter<>(snapshotTableModel);
    private final JBList<String> snapshotRowHeader = createSnapshotRowHeader();
    private final JBTable snapshotTable = createSnapshotTable();
    private final JPanel snapshotCards = new JBPanel<>(new CardLayout());
    private final JBLabel snapshotSummary = new JBLabel();
    private final JButton snapshotToggle = createSnapshotToggle();
    private final JButton snapshotOpenCsv = createSnapshotOpenCsv();
    private final JButton snapshotAutofit = createSnapshotAutofit();
    private boolean snapshotTableShown = false;

    private static JBList<String> createSnapshotRowHeader() {
        JBList<String> list = new JBList<>();
        list.setBorder(JBUI.Borders.empty());
        return list;
    }

    private JButton createSnapshotToggle() {
        JButton button = new JButton(MpSqlVisualizerBundle.message("rollback.snapshot.view.text"));
        button.setToolTipText(MpSqlVisualizerBundle.message("rollback.snapshot.view.tooltip"));
        button.addActionListener(e -> toggleSnapshotView());
        return button;
    }

    private JButton createSnapshotOpenCsv() {
        JButton button = new JButton(MpSqlVisualizerBundle.message("rollback.snapshot.openCsv"));
        button.setToolTipText(MpSqlVisualizerBundle.message("rollback.snapshot.openCsv.tooltip"));
        button.addActionListener(e -> openSnapshotInCsvEditor());
        return button;
    }

    private JButton createSnapshotAutofit() {
        JButton button = new JButton(MpSqlVisualizerBundle.message("rollback.snapshot.autofit"));
        button.setToolTipText(MpSqlVisualizerBundle.message("rollback.snapshot.autofit.tooltip"));
        button.addActionListener(e -> autoFitSnapshotColumns());
        return button;
    }

    /** T-RollbackSnapshotCsvView：「在 CSV 编辑器中打开」生成的临时文件（dispose 时清理未打开的）。 */
    private final List<java.io.File> snapshotTempFiles = new ArrayList<>();

    /** 测试缝：打开临时文件（生产走 FileEditorManager.openFile）。 */
    public volatile BiConsumer<VirtualFile, java.io.File> snapshotOpenInEditorForTest = null;

    /** 测试缝：headless 无真库/绑定数据源；生产为 null 走 DataSourceSelector.boundConsoleDataSource。 */
    public volatile Supplier<Object> snapshotQueryTargetForTest = null;

    /** 快照数据源选择器弹出测试缝（生产走 DataSourceSelector.prepareConsole）。 */
    public interface SnapshotPromptForTest {
        void accept(Project project, String sql, Consumer<String> onError);
    }

    /** 测试缝：数据源选择器弹出（生产走 DataSourceSelector.prepareConsole）。 */
    public volatile SnapshotPromptForTest snapshotPromptForTest = null;

    /** 绑定等待测试缝（生产走 SqlPreviewPanelExecutionKt.awaitDataSourceBoundThen）。 */
    public interface SnapshotAwaitBindForTest {
        void accept(Project project, int generation, Runnable onBound, Runnable onGiveUp);
    }

    /** 测试缝：绑定等待（生产走 SqlPreviewPanelExecutionKt.awaitDataSourceBoundThen）。 */
    public volatile SnapshotAwaitBindForTest snapshotAwaitBindForTest = null;

    /** 测试缝：方言闸请求（生产走 SqlPreviewPanelExecutionKt.executionDialectGateRequest，由模型能力决定可重渲染位）。 */
    public volatile Supplier<ExecutionDialectGate.Request> snapshotGateRequestForTest = null;

    /** 测试缝：按目标方言重渲染（生产走 SqlPreviewPanelExecutionKt.rerenderForExecutionTarget）。 */
    public volatile Function<String, Boolean> snapshotRerenderForTest = null;

    /** T-DialectGateExit：方言闸阻断态的出口行（默认隐藏，仅方言冲突时出现）。 */
    private final JPanel gateExitRow = createGateExitRow();

    /** 最近一次方言阻断的目标方言（「按目标方言重渲染」按钮的动作输入）。 */
    @Nullable
    private String blockedTargetDialect = null;

    private final JButton rerenderToTargetButton = createRerenderToTargetButton();

    private final JButton switchDataSourceButton = createSwitchDataSourceButton();

    private JButton createRerenderToTargetButton() {
        JButton button = new JButton(MpSqlVisualizerBundle.message("rollback.snapshot.rerender"));
        button.setToolTipText(MpSqlVisualizerBundle.message("rollback.snapshot.rerender.tooltip"));
        button.addActionListener(e -> rerenderToTargetAndRetry());
        return button;
    }

    private JButton createSwitchDataSourceButton() {
        JButton button = new JButton(MpSqlVisualizerBundle.message("rollback.snapshot.switchSource"));
        button.setToolTipText(MpSqlVisualizerBundle.message("rollback.snapshot.switchSource.tooltip"));
        button.addActionListener(e -> {
            String sql = currentResult.snapshotSelectSql();
            if (sql == null) return;
            Project proj = panel != null ? panel.project : null;
            if (proj == null) return;
            promptDataSourceThenRetrySnapshot(proj, sql, true);
        });
        return button;
    }

    // 底部动作以字段持有——DialogWrapper 无公开 getActions()（javap 实证），粘贴生成后
    // 启用态只能经自身引用刷新；字段初始化先于 init()（createActions 在 init() 内调用）
    private final Action executeAction = new DialogWrapperAction(MpSqlVisualizerBundle.message("rollback.action.execute")) {
        @Override
        protected void doAction(java.awt.event.ActionEvent actionEvent) {
            executeRollback();
        }
    };
    private final Action copyAction = new DialogWrapperAction(MpSqlVisualizerBundle.message("rollback.action.copy")) {
        @Override
        protected void doAction(java.awt.event.ActionEvent actionEvent) {
            copyRollback();
        }
    };

    /** 粘贴生成后的最新回滚结果（执行回滚/复制按钮取此值）。 */
    private RollbackSqlGenerator.RollbackResult currentResult;

    /** 仅快照派生产物受输入编辑影响；原始 INSERT 等非快照回滚保持原有执行路径。 */
    private boolean snapshotGenerationStarted = false;

    /** 测试访问用：DialogWrapper.init() 创建的同一个中心面板（不重复建编辑器）。 */
    @Nullable
    private JComponent snapshotCenterPanel = null;

    private static JBTextArea createPasteArea() {
        JBTextArea area = new JBTextArea(4, 60);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private JBTable createSnapshotTable() {
        JBTable table = new JBTable(snapshotTableModel) {
            @Override
            public String getToolTipText(java.awt.event.MouseEvent event) {
                int viewColumn = columnAtPoint(event.getPoint());
                if (viewColumn >= 0) {
                    int modelColumn = convertColumnIndexToModel(viewColumn);
                    if (modelColumn >= 0 && modelColumn < getColumnCount()) {
                        return snapshotTableModel.getColumnName(modelColumn);
                    }
                }
                return super.getToolTipText(event);
            }
        };
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowSorter(snapshotSorter);
        table.setCellSelectionEnabled(true);
        table.setRowSelectionAllowed(true);
        table.setFillsViewportHeight(true);
        table.setPreferredScrollableViewportSize(new Dimension(DIALOG_WIDTH - 48, 92));
        installSnapshotCopyBindings(table);
        table.addMouseListener(snapshotPopupListener());
        return table;
    }

    private static JPanel createGateExitRow() {
        JPanel row = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 4, 2));
        row.setOpaque(false);
        row.setVisible(false);
        return row;
    }

    public RollbackSqlDialog(@Nullable Project project,
                             @Nullable SqlPreviewPanel panel,
                             RollbackSqlGenerator.RollbackResult result,
                             String statementKind) {
        this(project, panel, result, statementKind, null);
    }

    public RollbackSqlDialog(@Nullable Project project,
                             @Nullable SqlPreviewPanel panel,
                             RollbackSqlGenerator.RollbackResult result,
                             String statementKind,
                             @Nullable RollbackSnapshotStore.SnapshotEntry snapshotEntry) {
        super(project, false, DialogWrapper.IdeModalityType.MODELESS);
        this.project = project;
        this.panel = panel;
        this.result = result;
        this.statementKind = statementKind;
        this.snapshotEntry = snapshotEntry;
        this.currentResult = result;
        // 排序后行号需按视图顺序重排；数据装载后同样刷新（showSnapshotTable 内调用）。
        snapshotSorter.addRowSorterListener(e -> updateSnapshotRowHeader());
        snapshotTable.getTableHeader().addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int viewColumn = snapshotTable.columnAtPoint(e.getPoint());
                    if (viewColumn >= 0) autoFitSnapshotColumn(viewColumn);
                }
            }
        });
        setTitle(MpSqlVisualizerBundle.message("rollback.dialog.title"));
        setCancelButtonText(MpSqlVisualizerBundle.message("rollback.action.close"));
        init();
        assembleBanner();
        assembleSnapshotSection();
        DocumentListener inputListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                snapshotInputChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                snapshotInputChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                snapshotInputChanged();
            }
        };
        pasteArea.getDocument().addDocumentListener(inputListener);
        pkField.getDocument().addDocumentListener(inputListener);
    }

    /** 生成结果（面板入口已按「Unable 且无快照工作流」过滤）。 */
    public RollbackSqlGenerator.RollbackResult getResult() {
        return result;
    }

    /** 经执行按钮执行过的 UPDATE/DELETE 登记条目（F6 留痕；非执行路径入口为 null）。 */
    @Nullable
    public RollbackSnapshotStore.SnapshotEntry getSnapshotEntry() {
        return snapshotEntry;
    }

    /** 粘贴生成后的最新回滚结果（执行回滚/复制按钮取此值）。 */
    public RollbackSqlGenerator.RollbackResult getCurrentResult() {
        return currentResult;
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel body = new JBPanel<>(new BorderLayout());
        body.setOpaque(false);
        body.add(buildTopBlock(), BorderLayout.NORTH);
        body.add(buildEditorBlock(), BorderLayout.CENTER);
        if (hasSnapshotWorkflow()) body.add(buildSnapshotBlock(), BorderLayout.SOUTH);
        body.setPreferredSize(new Dimension(DIALOG_WIDTH, DIALOG_HEIGHT));
        snapshotCenterPanel = body;
        return body;
    }

    // ------------------------------------------------------------------
    // 组装
    // ------------------------------------------------------------------

    private JComponent buildTopBlock() {
        JPanel block = new JBPanel<>(new BorderLayout());
        block.setOpaque(false);
        block.setBorder(JBUI.Borders.empty(6, 10, 2, 10));
        block.add(bannerLabel, BorderLayout.NORTH);
        block.add(warningLabel, BorderLayout.CENTER);
        if (currentResult.countCheckSql() != null) {
            countCheckLabel.setText(countCheckHtml());
            countCheckLabel.setToolTipText(currentResult.countCheckSql());
            block.add(countCheckLabel, BorderLayout.SOUTH);
        }
        return block;
    }

    private JComponent buildEditorBlock() {
        String sqlText = String.join("\n", currentResult.statements());
        com.intellij.openapi.editor.Document doc = EditorFactory.getInstance().createDocument(sqlText);
        Editor created;
        if (project != null) {
            created = EditorFactory.getInstance().createEditor(doc, project, sqlFileType(), true);
        } else {
            created = EditorFactory.getInstance().createViewer(doc);
        }
        // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）。
        // 本编辑器此前零外观设置（只读回滚 SQL），故是唯一不落在 EditorEx.apply 块内的接线点。
        EditorWhitespaceSettings.applyTo(created);
        // T-SqlCopyShortcuts O-122：无选中 Ctrl+C 复制全文、有选中只复制片段
        //（层位与理由见 EditorCopyShortcuts 的 KDoc；本处此前无任何键盘复制绑定）
        EditorCopyShortcuts.install(created, UiTextClipboard::copy);
        editor = created;
        // 编辑器随弹窗 disposable 树级联释放（Disposer 树铁律）
        Disposer.register(getDisposable(), this::releaseEditor);
        JBScrollPane pane = new JBScrollPane(created.getComponent());
        pane.setPreferredSize(new Dimension(DIALOG_WIDTH, EDITOR_HEIGHT));
        return pane;
    }

    private boolean hasSnapshotWorkflow() {
        return result.snapshotSelectSql() != null && panel != null;
    }

    private JComponent buildSnapshotBlock() {
        JPanel block = new JBPanel<>(new BorderLayout());
        block.setOpaque(false);
        block.setBorder(JBUI.Borders.empty(4, 10, 6, 10));

        JPanel north = new JBPanel<>(new BorderLayout());
        north.setOpaque(false);
        // T-RollbackSnapshotCsvView C：第一行 SQL 标签（CENTER 可压缩）+ 摘要/视图切换
        //（EAST 固定）——旧实现把切换按钮放在 FlowLayout 行尾，窄宽换行后被裁掉。
        JPanel labelRow = new JBPanel<>(new BorderLayout());
        labelRow.setOpaque(false);
        labelRow.add(snapshotQueryLabel, BorderLayout.CENTER);
        snapshotSummary.setVisible(false);
        JPanel eastRow = new JBPanel<>(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        eastRow.setOpaque(false);
        eastRow.add(snapshotSummary);
        eastRow.add(snapshotToggle);
        labelRow.add(eastRow, BorderLayout.EAST);
        north.add(labelRow, BorderLayout.NORTH);

        // 第二行：动作按钮（执行快照查询 / 复制快照 SQL / 在 CSV 编辑器中打开 / 自适应列宽）
        JPanel actionRow = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 4, 2));
        actionRow.setOpaque(false);
        JButton runButton = new JButton(MpSqlVisualizerBundle.message("rollback.snapshot.run"));
        runButton.setToolTipText(MpSqlVisualizerBundle.message("rollback.snapshot.run.tooltip"));
        runButton.addActionListener(e -> runSnapshotQuery());
        actionRow.add(runButton);
        JButton copyButton = new JButton(MpSqlVisualizerBundle.message("rollback.snapshot.copy"));
        copyButton.addActionListener(e -> {
            String sql = currentResult.snapshotSelectSql();
            if (sql == null) return;
            copyToClipboard(sql);
        });
        actionRow.add(copyButton);
        actionRow.add(snapshotOpenCsv);
        actionRow.add(snapshotAutofit);
        north.add(actionRow, BorderLayout.CENTER);

        // T-DialectGateExit：方言冲突时的两个出口（默认隐藏）
        gateExitRow.add(rerenderToTargetButton);
        gateExitRow.add(switchDataSourceButton);
        north.add(gateExitRow, BorderLayout.SOUTH);
        block.add(north, BorderLayout.NORTH);

        snapshotCards.setOpaque(false);
        JBScrollPane tablePane = new JBScrollPane(snapshotTable);
        tablePane.setRowHeaderView(snapshotRowHeader);
        snapshotCards.add(tablePane, SNAPSHOT_CARD_TABLE);
        snapshotCards.add(new JBScrollPane(pasteArea), SNAPSHOT_CARD_TEXT);
        ((CardLayout) snapshotCards.getLayout()).show(snapshotCards, SNAPSHOT_CARD_TEXT);

        JPanel center = new JBPanel<>(new BorderLayout(0, 4));
        center.setOpaque(false);
        center.add(snapshotCards, BorderLayout.CENTER);
        JPanel bottomRow = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bottomRow.setOpaque(false);
        bottomRow.add(new JBLabel(MpSqlVisualizerBundle.message("rollback.snapshot.pk")));
        bottomRow.add(pkField);
        JButton generateButton = new JButton(MpSqlVisualizerBundle.message("rollback.snapshot.generate"));
        generateButton.addActionListener(e -> generateFromPaste());
        bottomRow.add(generateButton);
        center.add(bottomRow, BorderLayout.SOUTH);
        block.add(center, BorderLayout.CENTER);
        return block;
    }

    /** T-RollbackSnapshotView：把快照数据（列名 + 行）装进只读表格并切到表格视图。 */
    private void showSnapshotTable(List<String> columns, List<List<String>> rows) {
        Object[][] data = new Object[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            Object[] rowData = new Object[row.size()];
            for (int j = 0; j < row.size(); j++) {
                String cell = row.get(j);
                rowData[j] = cell != null ? cell : "";
            }
            data[i] = rowData;
        }
        snapshotTableModel.setDataVector(data, columns.toArray());
        // 新数据回到原始顺序；行号与列宽随内容刷新。
        snapshotSorter.setSortKeys(null);
        updateSnapshotRowHeader();
        autoFitSnapshotColumns();
        snapshotSummary.setText(MpSqlVisualizerBundle.message("rollback.snapshot.summary", rows.size(), columns.size()));
        snapshotSummary.setVisible(true);
        snapshotTableShown = true;
        ((CardLayout) snapshotCards.getLayout()).show(snapshotCards, SNAPSHOT_CARD_TABLE);
        snapshotToggle.setText(MpSqlVisualizerBundle.message("rollback.snapshot.view.text"));
    }

    /** T-RollbackSnapshotCsvView A：行号列按视图顺序重排（排序后 1..N 连续）。 */
    private void updateSnapshotRowHeader() {
        javax.swing.DefaultListModel<String> model = new javax.swing.DefaultListModel<>();
        for (int i = 1; i <= snapshotTable.getRowCount(); i++) model.addElement(String.valueOf(i));
        snapshotRowHeader.setModel(model);
        snapshotRowHeader.setFixedCellHeight(snapshotTable.getRowHeight());
    }

    /** T-RollbackSnapshotCsvView A：Ctrl+C / Ctrl+Insert 复制当前选择（TSV）。 */
    private void installSnapshotCopyBindings(JTable table) {
        Action action = new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                copySnapshotSelection();
            }
        };
        table.getInputMap().put(KeyStroke.getKeyStroke("control C"), "snapshotCopy");
        table.getInputMap().put(KeyStroke.getKeyStroke("control INSERT"), "snapshotCopy");
        table.getActionMap().put("snapshotCopy", action);
    }

    /** T-RollbackSnapshotCsvView A：右键菜单（复制单元格/行/列、全选、自适应列宽）。 */
    private java.awt.event.MouseAdapter snapshotPopupListener() {
        return new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                maybeShow(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                maybeShow(e);
            }

            private void maybeShow(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                JPopupMenu menu = new JPopupMenu();
                menu.add(snapshotMenuItem("rollback.snapshot.copyCell", RollbackSqlDialog.this::copySnapshotSelection));
                menu.add(snapshotMenuItem("rollback.snapshot.copyRow", RollbackSqlDialog.this::copySnapshotRows));
                menu.add(snapshotMenuItem("rollback.snapshot.copyColumn", RollbackSqlDialog.this::copySnapshotColumn));
                menu.add(snapshotMenuItem("rollback.snapshot.selectAll", () -> snapshotTable.selectAll()));
                menu.addSeparator();
                menu.add(snapshotMenuItem("rollback.snapshot.autofit", RollbackSqlDialog.this::autoFitSnapshotColumns));
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        };
    }

    private JMenuItem snapshotMenuItem(String key, Runnable action) {
        JMenuItem item = new JMenuItem(MpSqlVisualizerBundle.message(key));
        item.addActionListener(e -> action.run());
        return item;
    }

    /** 复制当前选择（无选择时复制全表）；TSV 与结果网格复制口径一致。 */
    public boolean copySnapshotSelection() {
        List<Integer> rows = new ArrayList<>();
        int[] selectedRows = snapshotTable.getSelectedRows();
        if (selectedRows.length == 0) {
            for (int i = 0; i < snapshotTable.getRowCount(); i++) rows.add(i);
        } else {
            for (int r : selectedRows) rows.add(r);
        }
        List<Integer> columns = new ArrayList<>();
        int[] selectedColumns = snapshotTable.getSelectedColumns();
        if (selectedColumns.length == 0) {
            for (int i = 0; i < snapshotTable.getColumnCount(); i++) columns.add(i);
        } else {
            for (int c : selectedColumns) columns.add(c);
        }
        return copySnapshotCells(rows, columns);
    }

    private boolean copySnapshotRows() {
        List<Integer> rows = new ArrayList<>();
        int[] selectedRows = snapshotTable.getSelectedRows();
        if (selectedRows.length == 0) {
            for (int i = 0; i < snapshotTable.getRowCount(); i++) rows.add(i);
        } else {
            for (int r : selectedRows) rows.add(r);
        }
        List<Integer> columns = new ArrayList<>();
        for (int i = 0; i < snapshotTable.getColumnCount(); i++) columns.add(i);
        return copySnapshotCells(rows, columns);
    }

    private boolean copySnapshotColumn() {
        int viewColumn = snapshotTable.getSelectedColumn();
        if (viewColumn < 0) viewColumn = 0;
        int modelColumn = snapshotTable.convertColumnIndexToModel(viewColumn);
        String header = snapshotTableModel.getColumnName(modelColumn);
        StringBuilder body = new StringBuilder();
        for (int viewRow = 0; viewRow < snapshotTable.getRowCount(); viewRow++) {
            if (viewRow > 0) body.append('\n');
            Object value = snapshotTableModel.getValueAt(
                    snapshotTable.convertRowIndexToModel(viewRow), modelColumn);
            if (value != null) body.append(value.toString());
        }
        String text = body.length() == 0 ? header : header + "\n" + body;
        UiTextClipboard.copy(text);
        return true;
    }

    private boolean copySnapshotCells(List<Integer> viewRows, List<Integer> viewColumns) {
        if (viewRows.isEmpty() || viewColumns.isEmpty()) return false;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < viewRows.size(); i++) {
            if (i > 0) text.append('\n');
            for (int j = 0; j < viewColumns.size(); j++) {
                if (j > 0) text.append('\t');
                Object value = snapshotTableModel.getValueAt(
                        snapshotTable.convertRowIndexToModel(viewRows.get(i)),
                        snapshotTable.convertColumnIndexToModel(viewColumns.get(j)));
                if (value != null) text.append(value.toString());
            }
        }
        if (text.length() == 0) return false;
        UiTextClipboard.copy(text.toString());
        return true;
    }

    /** 按内容自适应列宽（双击列头 = 单列；按钮 = 全部），上限 360px。 */
    public void autoFitSnapshotColumns() {
        for (int viewColumn = 0; viewColumn < snapshotTable.getColumnCount(); viewColumn++) {
            autoFitSnapshotColumn(viewColumn);
        }
    }

    private void autoFitSnapshotColumn(int viewColumn) {
        if (viewColumn < 0 || viewColumn >= snapshotTable.getColumnCount()) return;
        int modelColumn = snapshotTable.convertColumnIndexToModel(viewColumn);
        java.awt.FontMetrics headerMetrics =
                snapshotTable.getTableHeader().getFontMetrics(snapshotTable.getTableHeader().getFont());
        java.awt.FontMetrics cellMetrics = snapshotTable.getFontMetrics(snapshotTable.getFont());
        int width = headerMetrics.stringWidth(snapshotTableModel.getColumnName(modelColumn)) + 24;
        int rows = Math.min(snapshotTable.getRowCount(), 500);
        for (int viewRow = 0; viewRow < rows; viewRow++) {
            Object value = snapshotTableModel.getValueAt(
                    snapshotTable.convertRowIndexToModel(viewRow), modelColumn);
            String s = value != null ? value.toString() : "";
            width = Math.max(width, cellMetrics.stringWidth(s) + 16);
        }
        int clamped = Math.max(48, Math.min(width, 360));
        snapshotTable.getColumnModel().getColumn(viewColumn).setPreferredWidth(clamped);
    }

    /**
     * T-RollbackSnapshotCsvView B：「在 CSV 编辑器中打开」——快照写临时 .tsv 后用
     * {@link FileEditorManager} 打开。Ultimate 由 grid 插件的 {@code csv-data-editor} 自动进入原生表格
     *（可切文本）；Community 降级为文本编辑器。写盘+refresh 在池线程（VFS refresh 属慢操作），
     * 打开在 EDT。
     */
    public void openSnapshotInCsvEditor() {
        Project proj = project;
        if (proj == null) return;
        String text = pasteArea.getText();
        if (text == null || text.isBlank()) {
            warningLabel.setText("<html>" + escapeHtml(
                    MpSqlVisualizerBundle.message("rollback.snapshot.emptyInput")) + "</html>");
            return;
        }
        // 落在 JVM 系统临时目录（java.io.tmpdir）：不在 IDE 的 VFS 允许根内时也能被
        // 测试沙盒放行（沙盒只允许 C:/Users/USER/AppData/Local/Temp 等少数根），
        // 且与 IDE 配置目录解耦——临时文件由本对话框显式回收。
        String tmpDir = System.getProperty("java.io.tmpdir");
        java.io.File file = new java.io.File(
                tmpDir != null ? tmpDir : PathManager.getTempPath(),
                "mpsql-rollback-snapshot-" + System.currentTimeMillis() + ".tsv");
        // T-RollbackSnapshotCsvView：写盘+VFS refresh 属写操作——生产走池线程
        //（WriteIntentReadAction，对齐 DataSourceSelectorResults 的 refreshVfs 段）；
        // 单测模式同步执行（池线程在 headless 用例里不派发 EDT 回调，测试无法确定性地观察）。
        Application app = ApplicationManager.getApplication();
        if (app.isUnitTestMode()) {
            VirtualFile vf = writeSnapshotTempFile(file, text);
            if (vf == null) {
                warningLabel.setText("<html>" + escapeHtml(
                        MpSqlVisualizerBundle.message("rollback.snapshot.openCsv.failed")) + "</html>");
                return;
            }
            snapshotTempFiles.add(file);
            BiConsumer<VirtualFile, java.io.File> sink = snapshotOpenInEditorForTest;
            if (sink != null) sink.accept(vf, file);
            else FileEditorManager.getInstance(proj).openFile(vf, true);
            return;
        }
        app.executeOnPooledThread(() -> {
            VirtualFile vf = writeSnapshotTempFile(file, text);
            app.invokeLater(() -> {
                if (vf == null) {
                    warningLabel.setText("<html>" + escapeHtml(
                            MpSqlVisualizerBundle.message("rollback.snapshot.openCsv.failed")) + "</html>");
                    return;
                }
                snapshotTempFiles.add(file);
                BiConsumer<VirtualFile, java.io.File> sink = snapshotOpenInEditorForTest;
                if (sink != null) {
                    sink.accept(vf, file);
                } else {
                    FileEditorManager.getInstance(proj).openFile(vf, true);
                }
            }, ModalityState.any());
        });
    }

    /**
     * 写临时 TSV 并让 VFS 可见。
     *
     * <p>写盘在池线程（{@code WriteIntentReadAction}），随后用
     * {@code com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh} 触发**异步**刷新：
     * 同步 {@code refreshAndFindFileByIoFile} 会在测试沙盒触发 VFS 根访问限制
     *（VfsRootAccessNotAllowedError），异步标记不触碰根校验。文件内容用文档 API 兜底写入，
     * 保证 CSV 编辑器打开时是完整内容。</p>
     */
    @Nullable
    private VirtualFile writeSnapshotTempFile(java.io.File file, String text) {
        try {
            return WriteIntentReadAction.compute(
                    (com.intellij.openapi.util.Computable<VirtualFile>) () -> {
                        java.io.File parent = file.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try {
                            java.nio.file.Files.write(file.toPath(),
                                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        } catch (java.io.IOException io) {
                            return null;
                        }
                        com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, false, file);
                        return LocalFileSystem.getInstance().findFileByIoFile(file);
                    });
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            return null;
        }
    }

    /** dispose 清理：仍被编辑器打开的临时文件保留（避免删正在编辑的文件），其余删除。 */
    private void cleanupSnapshotTempFiles() {
        Project proj = project;
        if (proj == null) return;
        FileEditorManager manager;
        try {
            manager = FileEditorManager.getInstance(proj);
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            manager = null;
        }
        for (java.io.File file : snapshotTempFiles) {
            boolean open;
            try {
                VirtualFile vf = LocalFileSystem.getInstance().findFileByIoFile(file);
                open = vf != null && manager != null && manager.isFileOpen(vf);
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) throw (Error) t;
                open = false;
            }
            if (!open) {
                try {
                    file.delete();
                } catch (Throwable t) {
                    if (FatalErrors.isFatal(t)) throw (Error) t;
                }
            }
        }
        snapshotTempFiles.clear();
    }

    /** 表格 ↔ 文本视图切换；从文本切表格时重新解析，解析失败留在文本视图并提示。 */
    private void toggleSnapshotView() {
        if (snapshotTableShown) {
            snapshotTableShown = false;
            ((CardLayout) snapshotCards.getLayout()).show(snapshotCards, SNAPSHOT_CARD_TEXT);
            snapshotToggle.setText(MpSqlVisualizerBundle.message("rollback.snapshot.view.table"));
            return;
        }
        String pasteText = pasteArea.getText();
        kotlin.Pair<List<String>, List<List<String>>> parsed =
                RollbackSqlGenerator.parseSnapshotTsv(pasteText != null ? pasteText : "");
        if (parsed == null) {
            warningLabel.setText("<html>" + escapeHtml(
                    MpSqlVisualizerBundle.message("rollback.snapshot.parseFailed")) + "</html>");
            return;
        }
        showSnapshotTable(parsed.getFirst(), parsed.getSecond());
    }

    private void assembleBanner() {
        String textKey;
        JBColor color;
        if (snapshotEntry != null && snapshotEntry.status() == RollbackSnapshotStore.Status.ROLLBACK_EXECUTED) {
            textKey = "rollback.banner.executed";
            color = SUCCESS_COLOR;
        } else if (currentResult.rating() == RollbackSqlGenerator.Rating.COMPLETE) {
            textKey = "rollback.banner.complete";
            color = SUCCESS_COLOR;
        } else if (currentResult.rating() == RollbackSqlGenerator.Rating.PARTIAL) {
            textKey = "rollback.banner.partial";
            color = WARNING_COLOR;
        } else {
            textKey = "rollback.banner.unable";
            color = ERROR_COLOR;
        }
        bannerLabel.setText(MpSqlVisualizerBundle.message(textKey));
        bannerLabel.setForeground(color);
        warningLabel.setText(warningsHtml(currentResult));
        warningLabel.setForeground(JBColor.foreground());
    }

    private void assembleSnapshotSection() {
        String sql = currentResult.snapshotSelectSql();
        if (sql == null) return;
        String shown = sql.length() > SNAPSHOT_DISPLAY_LIMIT ? sql.substring(0, SNAPSHOT_DISPLAY_LIMIT) + "…" : sql;
        snapshotQueryLabel.setText("<html><b>" + MpSqlVisualizerBundle.message("rollback.snapshot.section") + "</b> <code>" + shown + "</code></html>");
        snapshotQueryLabel.setToolTipText(sql);
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    @Override
    protected Action[] createActions() {
        boolean hasStatements = !currentResult.statements().isEmpty();
        executeAction.setEnabled(hasStatements);
        copyAction.setEnabled(hasStatements);
        return new Action[]{executeAction, copyAction, getCancelAction()};
    }

    /** 「执行回滚」：经预览面板 executeAdhocSql 复用执行管线（DML 二次确认内置）。 */
    public void executeRollback() {
        String sql = String.join("\n", currentResult.statements());
        if (sql.isBlank()) return;
        if (panel != null) {
            SqlPreviewPanelExecutionKt.executeAdhocSql(panel, sql, false, snapshotEntry, rollbackSubmissionReceipt());
        }
    }

    /** 每次请求仅结算一次；真实提交后保留业务留痕，关闭弹窗仅抑制迟到的 UI 更新。 */
    public Consumer<ExecutionSubmission> rollbackSubmissionReceipt() {
        AtomicBoolean completed = new AtomicBoolean(false);
        final RollbackSqlGenerator.RollbackResult submittedResult = currentResult;
        final RollbackSnapshotStore store = project != null
                ? project.getService(RollbackSnapshotStore.class) : null;
        return outcome -> {
            if (!completed.compareAndSet(false, true) || outcome != ExecutionSubmission.SUBMITTED) return;
            // SUBMITTED 来自底层 afterSubmitted，不代表数据库执行成功或事务已提交。
            if (snapshotEntry != null && store != null) store.markRollbackExecuted(snapshotEntry);
            Runnable updateBanner = () -> {
                if (!isDisposed() && currentResult == submittedResult) {
                    bannerLabel.setText(MpSqlVisualizerBundle.message("rollback.banner.executed"));
                    bannerLabel.setForeground(SUCCESS_COLOR);
                }
            };
            Application app = ApplicationManager.getApplication();
            if (app.isDispatchThread()) updateBanner.run();
            else app.invokeLater(updateBanner, ModalityState.any());
        };
    }

    /** 「复制」：回滚 SQL 入剪贴板。 */
    public boolean copyRollback() {
        String sql = String.join("\n", currentResult.statements());
        if (sql.isBlank()) return false;
        copyToClipboard(sql);
        return true;
    }

    /**
     * 「执行快照查询」：只读旁路查询（T-PreviewSideQuery 通道，McpDbExecutor 只读链）——
     * **不调用** {@code executeAdhocSql}，因此不替换预览编辑器、不换入控制台（旧实现会把预览窗口
     * 切成执行视图且结果只在控制台网格，用户须手工复制粘贴，快照框看起来「没填充」）。
     * 结果自动填入快照框并按粘贴结果生成回滚；阻断/失败/空结果在对话框内提示，
     * 「复制快照 SQL」+ 手工粘贴路径保留为降级通道。
     */
    public void runSnapshotQuery() {
        runSnapshotQuery(true, null);
    }

    /**
     * @param allowRerenderRetry 方言冲突时是否允许「按目标方言重渲染后重试」——重试只做一次，
     *                           防止「渲染→仍不匹配→再渲染」循环。
     * @param sqlOverride        重渲染后按新预览文本重算出的快照 SELECT（提交用它；显示区回滚文本不动）
     */
    private void runSnapshotQuery(boolean allowRerenderRetry, @Nullable String sqlOverride) {
        String sql = sqlOverride != null ? sqlOverride : currentResult.snapshotSelectSql();
        if (sql == null) return;
        SqlPreviewPanel p = panel;
        if (p == null) return;
        Project proj = p.project;
        if (proj == null) return;
        Object target = null;
        if (snapshotQueryTargetForTest != null) {
            target = snapshotQueryTargetForTest.get();
        } else {
            try {
                target = DataSourceSelector.boundConsoleDataSource();
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) throw (Error) t;
                target = null;
            }
        }
        if (target == null) {
            // 用户真机反馈：无数据源时不该只报「请先绑定」，应直接弹数据源选择器，绑定后自动重试
            //（与 DML 影响面预演/EXPLAIN 的 T-UI030 链路同款；只准备/绑定，不提交任何语句）。
            promptDataSourceThenRetrySnapshot(proj, sql, false);
            return;
        }
        warningLabel.setText("");
        hideGateExitRow();
        // T-DialectGateExit：改用面板自己的闸请求——`canLosslesslyRerender` 由模型能力决定
        //（原实现写死 false，把本来能无损重渲染的路径直接判成阻断）。
        // 面板无当前预览时（headless 夹具/异常态）沿用旧口径的 MySQL 兜底，不因拿不到方言而误判冲突。
        ExecutionDialectGate.Request request;
        if (snapshotGateRequestForTest != null) {
            request = snapshotGateRequestForTest.get();
        } else if (p.current != null) {
            request = SqlPreviewPanelExecutionKt.executionDialectGateRequest(p, ExecutionDialectGate.Intent.QUERY);
        } else {
            request = new ExecutionDialectGate.Request(
                    p.executionDialectRenderMode,
                    cn.song.mybatisplus.visualizer.sql.MySqlDialect.INSTANCE.getName(),
                    ExecutionDialectGate.Intent.QUERY,
                    false,
                    true,
                    null);
        }
        PreviewSideQuery.run(proj, target, sql, request, false, true, result -> {
            if (result instanceof PreviewSideQuery.Result.Table table) {
                if (table.raw().rows().isEmpty()) {
                    warningLabel.setText("<html>" + escapeHtml(
                            MpSqlVisualizerBundle.message("rollback.snapshot.emptyResult")) + "</html>");
                    return;
                }
                pasteArea.setText(toSnapshotTsv(table.raw()));
                showSnapshotTable(table.raw().headers(), table.raw().rows());
                generateFromPaste();
            } else if (result instanceof PreviewSideQuery.Result.Blocked blocked) {
                warningLabel.setText("<html>" + escapeHtml(blocked.message()) + "</html>");
                showGateExitRowIfDialectConflict(blocked.verdict(), allowRerenderRetry);
            } else if (result instanceof PreviewSideQuery.Result.Failed failed) {
                warningLabel.setText("<html>" + escapeHtml(failed.message()) + "</html>");
            } else if (result instanceof PreviewSideQuery.Result.Scalar) {
                warningLabel.setText("<html>" + escapeHtml(
                        MpSqlVisualizerBundle.message("rollback.snapshot.parseFailed")) + "</html>");
            }
        });
    }

    /**
     * T-DialectGateExit：方言冲突时露出两个出口。
     *
     * <ul>
     *  <li>「按目标方言重新渲染并重试」：手动选方言（MANUAL_CONFLICT）时不提供——闸门语义是插件不覆盖手动选择；
     *      其余冲突都提供，能否成功由 rerenderForExecutionTarget 的模型能力决定。</li>
     *  <li>「切换数据源」：任何方言冲突都提供（强制弹选择器，见 P0-3）。</li>
     * </ul>
     *
     * <p>只读护栏/目标未知等阻断不显示（那是另一类原因，重渲染与换目标都解决不了）。</p>
     */
    private void showGateExitRowIfDialectConflict(
            @Nullable ExecutionDialectGate.Verdict verdict,
            boolean allowRerenderRetry) {
        if (verdict == null) return;
        boolean conflict;
        switch (verdict.status()) {
            case AUTO_RERENDER_REQUIRED:
            case RERENDER_UNAVAILABLE:
            case MANUAL_CONFLICT:
                conflict = true;
                break;
            default:
                conflict = false;
                break;
        }
        if (!conflict) return;
        String targetDialect = verdict.targetDialectName();
        blockedTargetDialect = targetDialect;
        boolean canRerender = verdict.status() != ExecutionDialectGate.Status.MANUAL_CONFLICT
                && targetDialect != null && allowRerenderRetry;
        rerenderToTargetButton.setVisible(canRerender);
        rerenderToTargetButton.setEnabled(canRerender);
        switchDataSourceButton.setVisible(true);
        switchDataSourceButton.setEnabled(true);
        gateExitRow.setVisible(true);
        gateExitRow.revalidate();
        gateExitRow.repaint();
    }

    private void hideGateExitRow() {
        blockedTargetDialect = null;
        gateExitRow.setVisible(false);
    }

    /** 「按目标方言重新渲染并重试」：成功则重建请求重跑一次；失败就地说明原因（不冒充成功）。 */
    public void rerenderToTargetAndRetry() {
        String targetDialect = blockedTargetDialect;
        if (targetDialect == null) return;
        SqlPreviewPanel p = panel;
        if (p == null) return;
        Function<String, Boolean> rerender = snapshotRerenderForTest != null
                ? snapshotRerenderForTest
                : name -> SqlPreviewPanelExecutionKt.rerenderForExecutionTarget(p, name);
        boolean ok;
        try {
            ok = Boolean.TRUE.equals(rerender.apply(targetDialect));
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            ok = false;
        }
        if (!ok) {
            warningLabel.setText("<html>" + escapeHtml(
                    MpSqlVisualizerBundle.message("rollback.snapshot.rerender.failed")) + "</html>");
            return;
        }
        hideGateExitRow();
        // 重渲染会改写预览文本，快照 SELECT 可能带旧方言的标识符引用 → 按新文本重算后再提交。
        runSnapshotQuery(false, refreshedSnapshotSql());
    }

    /**
     * 按重渲染后的预览文本重算快照 SELECT（主键列沿用对话框当前输入）。
     *
     * <p>快照 SELECT由 {@link RollbackSqlGenerator} 从语句 AST 拼出，标识符引用跟随输入文本的方言——
     * 不重算就可能把旧方言的引用样式提交给新目标。重算失败（无法解析）时回退原文本，不阻断重试。</p>
     */
    @Nullable
    private String refreshedSnapshotSql() {
        SqlPreviewPanel p = panel;
        if (p == null) return currentResult.snapshotSelectSql();
        String newSql = SqlPreviewPanelExecutionKt.currentEditorSql(p);
        if (newSql == null) return currentResult.snapshotSelectSql();
        List<String> pkColumns = new ArrayList<>();
        String pkText = pkField.getText();
        if (pkText != null) {
            for (String part : pkText.split("[,，]")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) pkColumns.add(trimmed);
            }
        }
        RollbackSqlGenerator.RollbackResult regenerated;
        try {
            regenerated = RollbackSqlGenerator.generate(
                    newSql, pkColumns.isEmpty() ? null : pkColumns);
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            regenerated = null;
        }
        if (regenerated != null && regenerated.snapshotSelectSql() != null) {
            return regenerated.snapshotSelectSql();
        }
        return currentResult.snapshotSelectSql();
    }

    /** 测试访问器：出口行当前是否可见（方言冲突时应为 true）。 */
    public boolean gateExitRowVisibleForTest() {
        return gateExitRow.isVisible();
    }

    /** 测试访问器：两个出口按钮（可见性/启用态断言用）。 */
    public JButton rerenderButtonForTest() {
        return rerenderToTargetButton;
    }

    public JButton switchSourceButtonForTest() {
        return switchDataSourceButton;
    }

    /** 结果网格 → TSV（首行列名；单元格内制表/换行折叠为空格，与手工复制口径一致）。 */
    private String toSnapshotTsv(cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult raw) {
        StringBuilder out = new StringBuilder();
        out.append(String.join("\t", raw.headers()));
        for (List<String> row : raw.rows()) {
            out.append('\n');
            List<String> cells = new ArrayList<>();
            for (String cell : row) {
                String c = cell != null ? cell : "";
                cells.add(c.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
            }
            out.append(String.join("\t", cells));
        }
        return out.toString();
    }

    /**
     * 无绑定数据源：弹数据源选择器（只准备/绑定，不提交任何语句），绑定成功后自动重试快照查询；
     * 取消/超时收口为原有说明文案。链路与 DML 影响面预演 / EXPLAIN 的 T-UI030 同款，
     * 代际沿用面板 {@code explainStructuredGeneration}（预览切换即停表，避免晚到重试打到新预览）。
     *
     * @param forceChoose T-DialectGateExit：已绑定但方言冲突时，强制弹选择器（跳过 last-used 静默命中
     *                    与共享控制台复用）——否则「切换数据源」点了等于没点。
     */
    private void promptDataSourceThenRetrySnapshot(Project proj, String sql, boolean forceChoose) {
        SqlPreviewPanel p = panel;
        if (p == null) return;
        warningLabel.setText("");
        AtomicBoolean errorShown = new AtomicBoolean(false);
        if (ExplainFallbackGuidance.shouldPromptForDataSource(DataSourceSelector.isPrepareInFlight())) {
            try {
                if (snapshotPromptForTest != null) {
                    snapshotPromptForTest.accept(proj, sql, message -> {
                        errorShown.set(true);
                        warningLabel.setText("<html>" + escapeHtml(message) + "</html>");
                    });
                } else {
                    DataSourceSelector.prepareConsole(proj, sql, null, null, message -> {
                        errorShown.set(true);
                        warningLabel.setText("<html>" + escapeHtml(message) + "</html>");
                    }, null, null, null, forceChoose);
                }
            } catch (Throwable e) {
                if (FatalErrors.isFatal(e)) throw (Error) e;
                errorShown.set(true);
                warningLabel.setText("<html>" + escapeHtml(
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + "</html>");
            }
        }
        Runnable onGiveUp = () -> {
            if (!errorShown.get()) {
                warningLabel.setText("<html>" + escapeHtml(
                        MpSqlVisualizerBundle.message("rollback.snapshot.noDataSource")) + "</html>");
            }
        };
        if (snapshotAwaitBindForTest != null) {
            snapshotAwaitBindForTest.accept(proj, p.explainStructuredGeneration, this::runSnapshotQuery, onGiveUp);
        } else {
            SqlPreviewPanelExecutionKt.awaitDataSourceBoundThen(
                    p, proj, p.explainStructuredGeneration, this::runSnapshotQuery, onGiveUp);
        }
    }

    private void snapshotInputChanged() {
        if (snapshotGenerationStarted) invalidateSnapshotResult();
    }

    /** 清空执行/复制共用的模型和编辑器，不能仅禁用按钮或退回可能可执行的原始结果。 */
    private void invalidateSnapshotResult() {
        currentResult = new RollbackSqlGenerator.RollbackResult(
                RollbackSqlGenerator.Rating.UNABLE,
                List.of(),
                result.warnings(),
                null,
                result.targetTable(),
                result.whereText(),
                result.setColumns(),
                result.setValues(),
                result.snapshotSelectSql());
        refreshAfterPaste();
    }

    /** 「生成回滚」：粘贴 TSV → 快照回填（按原语句类型分派）→ 刷新编辑器与横幅。 */
    public void generateFromPaste() {
        if (!hasSnapshotWorkflow()) return;
        snapshotGenerationStarted = true;
        // 每次尝试先撤销上次产物；解析失败、生成失败乃至异常都不得保留旧 SQL。
        invalidateSnapshotResult();
        String table = result.targetTable();
        if (table == null) return;
        String pasteText = pasteArea.getText();
        kotlin.Pair<List<String>, List<List<String>>> parsed =
                RollbackSqlGenerator.parseSnapshotTsv(pasteText != null ? pasteText : "");
        if (parsed == null) {
            warningLabel.setText("<html>" + escapeHtml(MpSqlVisualizerBundle.message("rollback.snapshot.parseFailed")) + "</html>");
            return;
        }
        List<String> columns = parsed.getFirst();
        List<List<String>> rows = parsed.getSecond();
        // T-RollbackSnapshotView：手动粘贴成功后也切到表格视图（列值对应一目了然）。
        showSnapshotTable(columns, rows);
        List<String> pk = null;
        String pkText = pkField.getText();
        if (pkText != null) {
            List<String> trimmed = new ArrayList<>();
            for (String part : pkText.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) trimmed.add(t);
            }
            if (!trimmed.isEmpty()) pk = trimmed;
        }
        RollbackSqlGenerator.SnapshotMode mode;
        switch (statementKind.toUpperCase()) {
            case "UPDATE":
                mode = RollbackSqlGenerator.SnapshotMode.REVERT_UPDATE;
                break;
            case "DELETE":
                mode = RollbackSqlGenerator.SnapshotMode.BACKFILL_INSERT;
                break;
            default:
                mode = RollbackSqlGenerator.SnapshotMode.REVERT_INSERT;
                break;
        }
        // O-59 T-IIR-02：identity 列**在消费点解析**（而非登记点）。登记点位于执行前调用链上，
        // 在那里同步走 DAS 模型遍历有 EDT 慢操作断言风险（O-59 计划 §六 风险表首条即点名）；
        // 本对话框由用户点击动作打开，且 DatabaseSchemaResolver 本身是缓存优先 + 有界扫描的
        // UI 面，在此解析既避开执行链、又复用既有缓存。条目已带非空清单时优先用条目。
        List<String> identityColumns;
        if (snapshotEntry != null && snapshotEntry.identityColumns() != null
                && !snapshotEntry.identityColumns().isEmpty()) {
            identityColumns = snapshotEntry.identityColumns();
        } else {
            identityColumns = autoGeneratedColumnsOf(table);
        }
        RollbackSqlGenerator.RollbackResult generated = RollbackSqlGenerator.generateFromSnapshotRows(
                table,
                columns,
                rows,
                mode,
                pk,
                result.setColumns(),
                result.setValues(),
                // O-52（D1=A）：identity 知识随快照条目留存，此处取回传给生成器——条目未持有
                // （旧条目/未传参的登记点）时保持 null/空 → 不包装，与改前逐字节相同
                snapshotEntry != null ? snapshotEntry.dialect() : null,
                identityColumns);
        if (generated.rating() == RollbackSqlGenerator.Rating.UNABLE) {
            warningLabel.setText(warningsHtml(generated));
            return;
        }
        // 生成结果不携带原语句的快照模板/SET 元信息；保留它们以支持再次生成与快照查询。
        currentResult = new RollbackSqlGenerator.RollbackResult(
                generated.rating(),
                generated.statements(),
                generated.warnings(),
                generated.countCheckSql(),
                generated.targetTable(),
                result.whereText(),
                result.setColumns(),
                result.setValues(),
                result.snapshotSelectSql());
        refreshAfterPaste();
    }

    /**
     * O-59 T-IIR-02：按表名解析该表的<b>自增列</b>（{@code DasColumn.Attribute.AUTO_GENERATED}）。
     *
     * <p>取不到（无数据源 / 模型未内省 / 表不在模型里 / 反射链异常）一律返回<b>空清单 = 不包装</b>——
     * 与 O-52 D1=A 的「未知不臆造」契约一致（宁可少包装，也不误包装）。
     * 解析走 {@link DatabaseSchemaResolver#columnMeta}（缓存优先；扫描预算与负缓存口径见该类 KDoc）。</p>
     */
    private List<String> autoGeneratedColumnsOf(@Nullable String table) {
        if (table == null || table.isBlank()) return List.of();
        if (project == null) return List.of();
        try {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, DatabaseSchemaResolver.ColumnMeta> entry
                    : DatabaseSchemaResolver.columnMeta(project, table).entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue().autoGenerated())) out.add(entry.getKey());
            }
            return out;
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            return List.of();
        }
    }

    /** 粘贴生成成功后的视图刷新（编辑器内容 + 横幅 + 底部动作启用态）。 */
    private void refreshAfterPaste() {
        String text = String.join("\n", currentResult.statements());
        Editor ed = editor;
        if (ed != null) {
            com.intellij.openapi.editor.Document doc = ed.getDocument();
            if (doc != null && !doc.getText().equals(text)) {
                // 文档写入须写操作语境（测试实证：裸 setText 在平台断言下硬抛）
                if (project != null) {
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> doc.setText(text));
                } else {
                    doc.setText(text);
                }
            }
        }
        assembleBanner();
        boolean hasStatements = !currentResult.statements().isEmpty();
        executeAction.setEnabled(hasStatements);
        copyAction.setEnabled(hasStatements);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private String countCheckHtml() {
        String countCheckSql = currentResult.countCheckSql();
        return "<html><span style='color:gray'>" + escapeHtml(MpSqlVisualizerBundle.message("rollback.countcheck.hint")) + "</span> "
                + "<code>" + escapeHtml(countCheckSql != null ? countCheckSql : "") + "</code></html>";
    }

    private String warningsHtml(RollbackSqlGenerator.RollbackResult r) {
        if (r.warnings().isEmpty()) return "";
        StringBuilder items = new StringBuilder();
        for (RollbackSqlGenerator.RollbackWarning warning : r.warnings()) {
            String text;
            if (warning.messageArgs().isEmpty()) {
                text = MpSqlVisualizerBundle.message(warning.messageKey());
            } else {
                text = MpSqlVisualizerBundle.message(warning.messageKey(), warning.messageArgs().toArray());
            }
            items.append("<li>").append(escapeHtml(text)).append("</li>");
        }
        return "<html><ul style='margin-left:12px'>" + items + "</ul></html>";
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private void releaseEditor() {
        Editor ed = editor;
        if (ed != null) {
            EditorFactory.getInstance().releaseEditor(ed);
            editor = null;
        }
    }

    private com.intellij.openapi.fileTypes.FileType sqlFileType() {
        return com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("SQL");
    }

    @Override
    public String getDimensionServiceKey() {
        return DIMENSION_KEY;
    }

    /** T-RollbackSql：程序化关闭（同 SqlPreviewDialog.doDispose 惯例；测试 tearDown 用）。 */
    public void doDispose() {
        dispose();
    }

    /** 释放自建编辑器（DialogWrapper 不感知 EditorFactory 产物，须自行兜底）。 */
    @Override
    public void dispose() {
        releaseEditor();
        // T-RollbackSnapshotCsvView B：临时 TSV 随弹窗回收（仍被编辑器打开的保留）。
        // 放在 dispose() 而非 Disposer 回调——dispose 是确定的收口点，便于测试断言。
        cleanupSnapshotTempFiles();
        super.dispose();
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用，仿 SqlPreviewPanel 访问器惯例）
    // ------------------------------------------------------------------

    public String bannerTextForTest() {
        return bannerLabel.getText();
    }

    public String warningsTextForTest() {
        return warningLabel.getText();
    }

    public String sqlTextForTest() {
        Editor ed = editor;
        return ed != null && ed.getDocument() != null ? ed.getDocument().getText() : "";
    }

    /** 测试访问器：编辑器实例（T-SqlCopyShortcuts：键盘复制接线断言用）。 */
    public Editor editorForTest() {
        return editor;
    }

    public JBTextArea pasteAreaForTest() {
        return pasteArea;
    }

    public JBTable snapshotTableForTest() {
        return snapshotTable;
    }

    public String snapshotSummaryTextForTest() {
        return snapshotSummary.getText();
    }

    public boolean snapshotTableShownForTest() {
        return snapshotTableShown;
    }

    public JButton snapshotToggleForTest() {
        return snapshotToggle;
    }

    public JButton snapshotOpenCsvForTest() {
        return snapshotOpenCsv;
    }

    public JButton snapshotAutofitForTest() {
        return snapshotAutofit;
    }

    public boolean copySnapshotSelectionForTest() {
        return copySnapshotSelection();
    }

    public void autoFitSnapshotColumnsForTest() {
        autoFitSnapshotColumns();
    }

    public void openSnapshotInCsvEditorForTest() {
        openSnapshotInCsvEditor();
    }

    public List<java.io.File> snapshotTempFilesForTest() {
        return List.copyOf(snapshotTempFiles);
    }

    public JBList<String> snapshotRowHeaderForTest() {
        return snapshotRowHeader;
    }

    public JComponent centerPanelForTest() {
        return snapshotCenterPanel != null ? snapshotCenterPanel : createCenterPanel();
    }

    public JBTextField pkFieldForTest() {
        return pkField;
    }

    public Action executeActionForTest() {
        return executeAction;
    }

    public Action copyActionForTest() {
        return copyAction;
    }

    public JBLabel snapshotQueryLabelForTest() {
        return snapshotQueryLabel;
    }

    /** 尺寸记忆 key。 */
    public static final String DIMENSION_KEY = "cn.song.mybatisplus.visualizer.RollbackSqlDialog";

    private static final String SNAPSHOT_CARD_TABLE = "snapshot-table";
    private static final String SNAPSHOT_CARD_TEXT = "snapshot-text";

    private static final int DIALOG_WIDTH = 720;
    private static final int DIALOG_HEIGHT = 460;
    private static final int EDITOR_HEIGHT = 180;

    /** 快照 SQL 行内展示截断长度（全文在 tooltip）。 */
    private static final int SNAPSHOT_DISPLAY_LIMIT = 80;

    private static final JBColor SUCCESS_COLOR = JBColor.namedColor("RollbackSql.complete", new java.awt.Color(0x2E7D32));
    private static final JBColor WARNING_COLOR = JBColor.namedColor("RollbackSql.partial", new java.awt.Color(0xB26A00));
    private static final JBColor ERROR_COLOR = JBColor.namedColor("ErrorForeground", JBColor.RED);
}
