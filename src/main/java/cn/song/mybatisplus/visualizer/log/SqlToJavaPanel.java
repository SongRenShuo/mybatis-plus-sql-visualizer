package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings;
import cn.song.mybatisplus.visualizer.execute.DocWriteContext;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator;
import cn.song.mybatisplus.visualizer.reverse.JavaCommentStripper;
import cn.song.mybatisplus.visualizer.reverse.ReverseJoin;
import cn.song.mybatisplus.visualizer.reverse.ReverseWrapperFormat;
import cn.song.mybatisplus.visualizer.reverse.SqlReverseException;
import cn.song.mybatisplus.visualizer.reverse.SqlReverseResult;
import cn.song.mybatisplus.visualizer.reverse.SqlToJavaConverter;
import cn.song.mybatisplus.visualizer.ui.EntitySelectionDialog;
import cn.song.mybatisplus.visualizer.ui.JavaCodeFormatter;
import cn.song.mybatisplus.visualizer.ui.SqlInputEditor;
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog;
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.util.IconScaler;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.gotoByName.ChooseByNameModel;
import com.intellij.ide.util.gotoByName.ChooseByNamePopup;
import com.intellij.ide.util.gotoByName.ChooseByNamePopupComponent;
import com.intellij.ide.util.gotoByName.GotoClassModel2;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.lang.java.JavaSyntaxHighlighterFactory;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.ide.ui.laf.darcula.DarculaUIUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import kotlin.Pair;
import kotlin.Triple;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import java.util.ArrayList;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「SQL → Java」tab 面板（T-M36.4）：粘贴 SQL → 格式化/自动补全 → 转换 →
 * 可编辑 Java 高亮结果区。
 *
 * 布局（Splitter 上下分栏 + IDEA 原生 ActionToolbar）：
 *  - 上：SQL 输入区（{@link SqlInputEditor}：SQL 高亮 + 打字自动弹补全）；工具栏：转换 / 格式化 / 清空；
 *  - 下：Java 结果区（可编辑 + Java 词法高亮）；工具栏：复制代码 / 清空；
 *  - 底部：状态行（错误红字 / 提示灰字）。
 *
 * 转换流程复用 {@link SqlToJavaConverter} 共享编排（与编辑器 Action 同一链路）；实体查找
 * 后台 ReadAction（2025.3+ 慢操作守卫）；多候选弹 {@link SqlSelectionDialog}。
 */
public class SqlToJavaPanel extends JPanel implements Disposable {

    private static final String TOOLBAR_ID_INPUT = "cn.song.mybatisplus.visualizer.reverse.input.toolbar";
    private static final String TOOLBAR_ID_RESULT = "cn.song.mybatisplus.visualizer.reverse.result.toolbar";

    /** T-M42：调用类选择器固定特殊候选（service 内 this / ServiceImpl 的 baseMapper 字段）。 */
    private static final String CALLER_THIS = "this";
    private static final String CALLER_BASE_MAPPER = "baseMapper";

    /** 特殊项用途说明（选中后字段级 tooltip；非类名，无全限定名可展示）。 */
    private static final Map<String, String> SPECIAL_TOOLTIPS = Map.of(
            CALLER_THIS, "this：当前 service 类内使用（生成 this.list(...) / this.save(...) 等）",
            CALLER_BASE_MAPPER, "baseMapper：ServiceImpl 的 baseMapper 字段（生成 baseMapper.selectList(...) / baseMapper.insert(...) 等）"
    );

    /** 类选择字段圆角直径（保留给旧调用点；实际绘制走 [paintPickerFieldSurface] 的平台口径）。 */
    private static final int PICKER_CORNER = 10;

    /** T-MapResultVo v4：返回类型选择器的 Map/Objs 预置声明（生成器按含 Map/含 Object 派生族）。 */
    private static final String MAP_LIST_DECL = "List<Map<String, Object>>";
    /** T-MapResultVo v6：Maps 分页声明——SQL 含 LIMIT 时前缀=Page&lt;Map&lt;String, Object&gt;&gt;、
     *  方法 selectMapsPage/listMapsPage/selectJoinMapsPage（MP core 3.5.17 + MPJ 1.5.8 javap 实证；
     *  v5 去自由输入后此形态从 UI 不可达，本项补回）。 */
    private static final String PAGE_MAP_LIST_DECL = "Page<Map<String, Object>>";
    private static final String OBJECT_LIST_DECL = "List<Object>";

    /** 类选择字段按钮图标（12px 缩放——AllIcons 原生 16px 偏大，用户反馈）。 */
    private static final Icon PICKER_ELLIPSIS_ICON = IconScaler.scale(AllIcons.General.Ellipsis, 12f / 16f);
    private static final Icon PICKER_CANCEL_ICON = IconScaler.scale(AllIcons.Actions.Cancel, 12f / 16f);

    private final Project project;

    private final SqlInputEditor inputEditor;
    private final Editor resultEditor;
    private final JBLabel statusLabel = new JBLabel(" ");
    private final JBLabel inputHintLabel;
    private final JBLabel resultHintLabel;

    /** T-M36.6：生成形态下拉（与设置页 reverseWrapperFormat 双向同步：写穿持久化 + tab 重选刷新）。 */
    private final JComboBox<FormatOption> formatCombo;

    /** T-M36.7：调用类选择（Service/Mapper 类名，非必选——留空生成纯表达式）。 */
    private final ClassPickerField callerField;

    /** T-MapResultVo v2：返回类型字段——可编辑组合框（自由键入任意返回声明）+ 选类按钮（Goto Class）。
     *  预置常用声明（List&lt;Map&lt;String, Object&gt;&gt; / List&lt;Object&gt;）亦可键入 HashMap/自定义类等形态；
     *  生成器按声明形态派生调用族（含 Map→Maps 族、含 Object→Objs 族、其余按类名走 VO 语义）。
     *  空值 = 自动（现状实体/VO 推断）。 */
    private final ReturnTypeField voField;

    /** 设置 → 下拉同步期间的抑制标志（refreshFormatFromSettings 不触发重生成）。 */
    private boolean suppressFormatSync = false;

    /** T-M36.6：上次成功解析缓存（输入文本未变时切换形态只重生成，不重新 parse/查实体）。 */
    private String cacheText = null;
    private SqlReverseResult cacheResult = null;
    private List<SqlToJavaConverter.EntityCandidate> cacheCandidates = null;
    private Map<String, JavaCodeGenerator.EntityTarget> cacheSubEntities = Collections.emptyMap();

    /**
     * UI010：一次转换请求的不可变快照。
     *
     * <p>代次只在 EDT 读写；后台任务只持有快照，不读取输入组件——后台回来时输入/形态/调用类/
     * 返回类型任一项已变，结果一律丢弃（旧请求的渲染不得覆盖新请求）。</p>
     */
    private static final class ConversionRequest {
        final long generation;
        final String text;
        final ReverseWrapperFormat format;
        final String caller;
        final String returnType;

        ConversionRequest(long generation, String text, ReverseWrapperFormat format,
                          String caller, String returnType) {
            this.generation = generation;
            this.text = text;
            this.format = format;
            this.caller = caller;
            this.returnType = returnType;
        }
    }

    private long requestGeneration = 0L;

    /** 后台调度宿主（测试替换；生产走 pooled thread）。 */
    public interface RunnableDispatcher {
        void dispatch(Runnable block);
    }

    /** 实体选择弹窗宿主（测试替换；生产走 {@link EntitySelectionDialog}）。 */
    public interface EntityDialogHost {
        void show(List<EntitySelectionDialog.TableRow> rows, EntitySelectionDialog.ConfirmCallback onConfirm);
    }

    // 测试仅替换调度/弹窗宿主；解析、实体查询、缓存回填和渲染仍走同一生产闭包。
    public RunnableDispatcher backgroundDispatcherForTest = null;
    public RunnableDispatcher uiDispatcherForTest = null;
    public EntityDialogHost entityDialogForTest = null;

    private void invalidateRequests(boolean clearCache) {
        requestGeneration++;
        if (clearCache) {
            cacheText = null;
            cacheResult = null;
            cacheCandidates = null;
            cacheSubEntities = Collections.emptyMap();
        }
    }

    private void invalidateRequests() {
        invalidateRequests(false);
    }

    private ConversionRequest requestSnapshot() {
        return new ConversionRequest(
                requestGeneration, inputEditor.getText(), reverseFormat(),
                callerField.getText(), voField.getText());
    }

    private boolean isCurrent(ConversionRequest request) {
        return !disposed && !project.isDisposed()
                && request.generation == requestGeneration
                && Objects.equals(request.text, inputEditor.getText())
                && request.format == reverseFormat()
                && Objects.equals(request.caller, callerField.getText())
                && Objects.equals(request.returnType, voField.getText());
    }

    private void dispatchBackground(Runnable block) {
        RunnableDispatcher dispatcher = backgroundDispatcherForTest;
        if (dispatcher != null) {
            dispatcher.dispatch(block);
        } else {
            ApplicationManager.getApplication().executeOnPooledThread(block);
        }
    }

    private void dispatchUi(ConversionRequest request, Runnable block) {
        Runnable guarded = () -> {
            if (isCurrent(request)) block.run();
        };
        RunnableDispatcher dispatcher = uiDispatcherForTest;
        if (dispatcher != null) {
            dispatcher.dispatch(guarded);
        } else {
            ApplicationManager.getApplication().invokeLater(guarded, ModalityState.nonModal());
        }
    }

    /** 调用类 MPJ 能力（后台 ReadAction 继承链实证；QUERY 形态/未解析默认 false）。 */
    private boolean callerMpj = false;

    /** T-M44：调用类继承链方法名集合（后台 ReadAction 反查；生成器按真实 API 选择调用方法）。 */
    private Set<String> callerMethods = Collections.emptySet();

    private boolean disposed = false;

    public SqlToJavaPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.inputEditor = new SqlInputEditor(project, "");
        this.resultEditor = createJavaEditor();
        this.inputHintLabel = new JBLabel(MpSqlVisualizerBundle.message("reverse.panel.hint.input"));
        this.inputHintLabel.setForeground(UIUtil.getInactiveTextColor());
        this.inputHintLabel.setBorder(JBUI.Borders.empty(2, 6, 2, 6));
        this.resultHintLabel = new JBLabel(MpSqlVisualizerBundle.message("reverse.panel.hint.result"));
        this.resultHintLabel.setForeground(UIUtil.getInactiveTextColor());
        this.resultHintLabel.setBorder(JBUI.Borders.empty(2, 6, 2, 6));
        this.formatCombo = new JComboBox<>();
        initFormatCombo();
        this.callerField = new ClassPickerField(
                project,
                MpSqlVisualizerBundle.message("reverse.panel.callerHint"),
                () -> {
                    // T-FixMpjStale：调用类变更 → 先后台反查 MPJ 能力/方法集，回填后再重生成——
                    // 此前 callerMpj 仅在 SQL 解析链（convert）反查，「先贴 SQL 后选调用类」路径拿
                    // stale callerMpj=false 恒报「无 MPJ 能力」（真机实证：DeviceInfoMapper extends
                    // MPJBaseMapper 仍误报）
                    refreshCallerCapabilityThenRegenerate();
                    return Unit.INSTANCE;
                });
        this.voField = new ReturnTypeField(
                project,
                MpSqlVisualizerBundle.message("reverse.panel.voHint"),
                () -> {
                    invalidateRequests();
                    if (cacheResult != null) regenerateWithNewFormat();
                    return Unit.INSTANCE;
                });

        // 注册到 Disposer 树：面板 dispose 时级联释放 SqlInputEditor 的 DocumentListener
        Disposer.register(this, inputEditor);

        // 上：输入区（工具栏 + 灰字提示 + 编辑器——空文档显示引导文案）
        JPanel inputPanel = new JPanel(new BorderLayout());
        JPanel inputNorth = new JPanel(new BorderLayout());
        inputNorth.add(buildInputToolbar(), BorderLayout.NORTH);
        inputNorth.add(inputHintLabel, BorderLayout.SOUTH);
        inputPanel.add(inputNorth, BorderLayout.NORTH);
        inputPanel.add(inputEditor.getComponent(), BorderLayout.CENTER);

        // 下：结果区（工具栏 + 灰字提示 + Java 编辑器）
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
        add(statusLabel, BorderLayout.SOUTH);
        // 空态提示显隐跟随文档内容
        inputEditor.getEditor().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent e) {
                invalidateRequests(true);
                inputHintLabel.setVisible(inputEditor.getText().isBlank());
            }
        }, this);
        resultEditor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent e) {
                resultHintLabel.setVisible(resultEditor.getDocument().getText().isBlank());
            }
        }, this);
        refreshFormatFromSettings();
    }

    private void initFormatCombo() {
        formatCombo.setModel(new DefaultComboBoxModel<>(formatOptions()));
        formatCombo.setRenderer(new ListCellRenderer<FormatOption>() {
            private final DefaultListCellRenderer delegate = new DefaultListCellRenderer();

            @Override
            public Component getListCellRendererComponent(
                    JList<? extends FormatOption> list,
                    FormatOption value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus) {
                Component c = delegate.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (c instanceof JComponent) {
                    ((JComponent) c).setToolTipText(value != null ? value.label() : null);
                }
                return c;
            }
        });
        // T-M39 五次增量：长选项截断时完整文案可见——弹出列表项（JList cell renderer tooltip）
        // 与选中态（combo 自身 tooltip）都带完整 label；选中变化时同步 combo tooltip
        formatCombo.addActionListener(e -> {
            // 选中项截断时 hover 显示完整文案（先于写穿/重生成，suppress 态同样生效）
            FormatOption sel = (FormatOption) formatCombo.getSelectedItem();
            formatCombo.setToolTipText(sel != null ? sel.label() : null);
            if (suppressFormatSync) return;
            String value = sel != null ? sel.persisted() : null;
            if (value != null && !value.equals(settingsState().getReverseWrapperFormat())) {
                // 写穿式持久化（对齐语言下拉 M19.1 实证模式）——面板是设置值的快捷入口
                settingsState().setReverseWrapperFormat(value);
                regenerateWithNewFormat();
            }
        });
        // 初始选中（model 首项，无 selection 事件）也带完整文案 tooltip
        FormatOption sel0 = (FormatOption) formatCombo.getSelectedItem();
        formatCombo.setToolTipText(sel0 != null ? sel0.label() : null);
    }

    // ------------------------------------------------------------------
    // 工具栏
    // ------------------------------------------------------------------

    private JPanel buildInputToolbar() {
        DefaultActionGroup group = new DefaultActionGroup(
                new ConvertAction(),
                new FormatAction(),
                new ClearInputAction()
        );
        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar(TOOLBAR_ID_INPUT, group, true);
        toolbar.setTargetComponent(this);
        // T-M39 增量：选项（形态下拉/调用类/VO）独立成行——原 BorderLayout.EAST + FlowLayout
        // 三控件挤一行，窄侧边栏下形态下拉被挤压截断（用户截图只见「→ 字符串列名）」）
        JPanel bar = new JPanel(new BorderLayout());
        bar.add(toolbar.getComponent(), BorderLayout.NORTH);
        bar.add(buildOptionsRow(), BorderLayout.SOUTH);
        return bar;
    }

    /**
     * 选项行：形态下拉独占整行（高频、选项文本长须完整显示）+ 调用类/VO 各半行
     * （低频可选，占位提示截断无妨）——GridBag 拉伸，窄窗口各自收缩互不挤压。
     */
    private JPanel buildOptionsRow() {
        JPanel row = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = JBUI.insets(0, 4, 4, 4);
        c.gridx = 0;
        c.gridwidth = 2;
        row.add(formatCombo, c);
        c.gridy = 1;
        c.gridwidth = 1;
        c.weightx = 1.0;
        row.add(callerField, c);
        c.gridx = 1;
        row.add(voField, c);
        return row;
    }

    private JPanel buildResultToolbar() {
        DefaultActionGroup group = new DefaultActionGroup(
                new CopyAction(),
                new FormatCodeAction(),
                new ClearResultAction()
        );
        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar(TOOLBAR_ID_RESULT, group, true);
        toolbar.setTargetComponent(this);
        JPanel bar = new JPanel(new BorderLayout());
        bar.add(toolbar.getComponent(), BorderLayout.WEST);
        return bar;
    }

    /** 转换：parse（EDT 纯函数）→ 实体查找（后台 ReadAction）→ 分发（设置形态）。 */
    private class ConvertAction extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.Convert);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("reverse.panel.convert"));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            convert();
        }
    }

    /** 格式化输入区 SQL（SqlFormatter 幂等；程序性写入不触发补全）。 */
    private class FormatAction extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.Format);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("reverse.panel.format"));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            String text = inputEditor.getText();
            if (text.isBlank()) return;
            inputEditor.setSql(cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter.format(text));
        }
    }

    private class ClearInputAction extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.Clear);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("reverse.panel.clearInput"));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            inputEditor.setSql("");
        }
    }

    private class CopyAction extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.Copy);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("reverse.panel.copy"));
            e.getPresentation().setEnabled(!resultEditor.getDocument().getText().isBlank());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            // T-M45：只复制 Java 代码——剥离说明注释（忽略「日志」），粘贴即用
            UiTextClipboard.copy(copyText());
            status(MpSqlVisualizerBundle.message("reverse.copy.done"), false);
        }
    }

    /** T-M45：直接格式化结果区 Java 代码（对齐输入区「格式化 SQL」交互——格式化当前框内内容）。 */
    private class FormatCodeAction extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.Format);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("reverse.panel.formatCode"));
            e.getPresentation().setEnabled(!resultEditor.getDocument().getText().isBlank());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            formatResult();
        }
    }

    private class ClearResultAction extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.Clear);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("reverse.panel.clearResult"));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            invalidateRequests(true);
            setResultText("");
            status(" ", false);
        }
    }

    // ------------------------------------------------------------------
    // 类选择字段（T-M39 增量：用户要求直接选择项目 Java 类而非手动输入）
    // ------------------------------------------------------------------

    /**
     * 类选择字段：点击弹项目类选择器（Goto Class 弹窗，{@link GotoClassModel2}）。
     *
     * T-M39 三次增量：**「类图标 + 类名」的 IDEA 原生观感**（用户反馈纯文本样式不原生）——
     * 选中后左侧显示 {@link PsiClass#getIcon(int)}（类/接口/枚举各有原生图标，如 AllIcons.Nodes.Class），
     * 右侧 ✕ 清除；空值显示占位提示（灰字）+ … 浏览；自绘输入框样式
     * （圆角底色 + 主题边框色，对齐 IDEA TextField——只读无焦点态）。
     */
    public class ClassPickerField extends JPanel {
        private String currentText = "";
        private Icon pickedIcon = null;
        private final String placeholder;

        private final JBLabel iconLabel = new JBLabel();
        private final JBLabel textLabel = new JBLabel(" ");
        // T-M39 增量修复：关闭/浏览按钮改原生紧凑图标按钮——IDEA 图标按钮类
        // （JBIconButton/SmallIconButton/InlineIconButton）在 IPGP 发行版全部被裁剪（编译实证），
        // 用 JButton + IDEA 原生图标 + 12×12 固定尺寸（IconUtil.scale 缩放——16px 偏大实证反馈）
        // + FlowLayout 宿主防 BorderLayout 拉伸
        private final JButton actionButton = new JButton(PICKER_ELLIPSIS_ICON);
        // T-M39 增量修复：关闭/浏览按钮改原生紧凑图标按钮（见上）
        private final JPanel actionButtonHost = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 4));

        /** T-M44 增量：模型实例提为字段（弹窗与测试缝共用；元素级方法的 PSI 读取在委托内包 ReadAction）。 */
        public final ChooseByNameModel callerModel = new CallerClassByNameModel();

        /** T-Mxx：模型实例的 concrete 引用（供 setCurrentEditorClass / currentEditorClass 访问）。 */
        public CallerClassByNameModel getCallerModelConcrete() {
            return (CallerClassByNameModel) callerModel;
        }

        private final Function0<Unit> onChanged;

        public ClassPickerField(@NotNull Project projectRef, String placeholder, Function0<Unit> onChanged) {
            super(new BorderLayout());
            this.placeholder = placeholder;
            this.onChanged = onChanged;
            actionButton.setBorderPainted(false);
            actionButton.setContentAreaFilled(false);
            actionButton.setFocusPainted(false);
            actionButton.setMargin(JBUI.emptyInsets());
            actionButton.setBorder(JBUI.Borders.empty());
            actionButton.setIconTextGap(0);
            actionButton.setPreferredSize(new Dimension(12, 12));
            actionButton.setMinimumSize(new Dimension(12, 12));
            actionButton.setMaximumSize(new Dimension(12, 12));
            actionButton.setToolTipText(MpSqlVisualizerBundle.message("reverse.panel.pickClass"));
            actionButton.addActionListener(e -> {
                if (currentText.isEmpty()) pickClass();
                else clearPicked();
            });

            // 按钮宿主：FlowLayout 垂直居中（BorderLayout.EAST 会拉伸子组件高度，需宿主隔离）；右侧 4px 边距
            // 防 ✕/… 按钮贴边（对齐 IDEA SearchTextField 的 JTextField.Search.Gap 间距）
            actionButtonHost.setOpaque(false);
            actionButtonHost.setBorder(JBUI.Borders.empty(0, 0, 0, JBUI.scale(4)));
            actionButtonHost.add(actionButton);

            setOpaque(false);
            setPreferredSize(new Dimension(1, JBUI.scale(24)));
            setMinimumSize(new Dimension(1, JBUI.scale(24)));
            iconLabel.setBorder(JBUI.Borders.empty(0, 5, 0, 0));
            textLabel.setBorder(JBUI.Borders.empty(0, 4, 0, 0));
            add(iconLabel, BorderLayout.WEST);
            add(textLabel, BorderLayout.CENTER);
            add(actionButtonHost, BorderLayout.EAST);
            // 点击字段任意位置弹选择器：Swing 鼠标事件不冒泡（子组件 JLabel 铺满后
            // 父面板监听收不到——实证反馈「只能点三点」）——监听挂到每个子组件 + 手型光标
            attachClick(iconLabel);
            attachClick(textLabel);
            attachClick(actionButtonHost);
            refresh();
        }

        /**
         * 输入框观感：圆角底色 + 主题边框（对齐 IDEA TextField；只读无焦点态）。
         * 实现在共享的 [paintPickerFieldSurface]（两处选择框共用，UI-045）。
         */
        @Override
        public void paintComponent(Graphics g) {
            paintPickerFieldSurface(this, g);
        }

        /** 对外文本（生成器读取类名；测试 setText 兼容——直接赋值不触发变更回调）。 */
        public String getText() {
            return currentText;
        }

        public void setText(String value) {
            currentText = value;
            refresh();
        }

        /**
         * 弹 Goto Class 选择器（IDE 标准类搜索弹窗，候选 = 项目内全部 Java 类）。
         *
         * T-M42：模型用 {@link CallerClassByNameModel} 委托包装 {@link GotoClassModel2}——候选前插
         * `this`（service 内调用）与 `baseMapper`（ServiceImpl 的 baseMapper 字段）两个固定项。
         */
        private void pickClass() {
            // T-CallerPicker：PSI 读取须在 ReadAction 内（EDT 同步，语义不变）——262 断言硬抛实证
            getCallerModelConcrete().setCurrentEditorClass(
                    ReadAction.compute(() -> detectCurrentEditorClass()));
            // T-M44 增量：模型提为字段（测试缝 callerModelForTest 复用同一实例）
            ChooseByNamePopup popup = ChooseByNamePopup.createPopup(project, callerModel, (PsiElement) null);
            popup.invoke(new ChooseByNamePopupComponent.Callback() {
                @Override
                public void elementChosen(Object element) {
                    if (element instanceof String) {
                        // T-M42：特殊调用值（字符串元素）——this=当前 service 类内、baseMapper=ServiceImpl
                        // 字段；tooltip = 用途说明 + T-Mxx 当前编辑器类上下文
                        setPicked((String) element, buildSpecialTooltip((String) element), AllIcons.Nodes.Field);
                    } else if (element instanceof PsiClass) {
                        pickPsiClassForTest((PsiClass) element);
                    }
                }

                @Override
                public void onClose() {
                }
            }, ModalityState.current(), false);
        }

        /**
         * T-Mxx：获取当前编辑器打开的 Java/Kotlin 类的 PsiClass（如有）。
         * 多编辑器时取活动编辑器；非 Java/Kotlin 文件返回 null。
         * 须在 ReadAction 内调用（PSI 访问）。
         */
        private PsiClass detectCurrentEditorClass() {
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor == null) return null;
            com.intellij.psi.PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
            if (psiFile == null) return null;
            if (!(psiFile instanceof PsiClassOwner)) return null;
            PsiClassOwner owner = (PsiClassOwner) psiFile;
            PsiClass[] classes = owner.getClasses();
            for (PsiClass cls : classes) {
                if (cls.getName() != null && psiFile.getName().startsWith(cls.getName())) {
                    return cls;
                }
            }
            return classes.length > 0 ? classes[0] : null;
        }

        /**
         * T-CallerPicker：构造 this/baseMapper 的 tooltip 文本，含当前编辑器类信息。
         * 兼顾 PM（Plain MyBatis-Plus）与 MPJ（MyBatis-Plus-Join）两种依赖。
         *
         * 当前编辑器类上下文读取（name/superTypes/泛型参数）包 ReadAction——elementChosen
         * 回调在 EDT 裸读 PSI 会触发 262 断言硬抛；无当前类或未知 special 时同步回落。
         */
        private String buildSpecialTooltip(String special) {
            String base = SPECIAL_TOOLTIPS.get(special);
            if (base == null) return special;
            PsiClass editorCls = getCallerModelConcrete().getCurrentEditorClass();
            if (editorCls == null) return base;
            String context = ReadAction.compute(() -> {
                String clsName = editorCls.getName();
                if (clsName == null) return null;
                if (CALLER_THIS.equals(special)) {
                    return "（当前：" + clsName + "）";
                } else if (CALLER_BASE_MAPPER.equals(special)) {
                    return buildBaseMapperContext(editorCls, clsName);
                }
                return "";
            });
            if (context == null) return base;
            return base + "\n" + context;
        }

        /**
         * T-CallerPicker：baseMapper 选项的上下文提取——兼顾 PM 与 MPJ 两种依赖。
         * 优先匹配 ServiceImpl<A, B>（取 B=实体），回落 BaseMapper<A>（取 A=实体）。
         * 调用方 {@link #buildSpecialTooltip} 保证在 ReadAction 内执行（superTypes 为 PSI 访问），勿裸调。
         */
        private String buildBaseMapperContext(PsiClass currentCls, String clsName) {
            PsiClassType[] superTypes = currentCls.getSuperTypes();
            // 优先：ServiceImpl<X, Entity> / MPJBaseService<X, Entity> → 取第 2 个泛型参数（实体）
            for (PsiClassType type : superTypes) {
                String className = type.getClassName();
                if (className != null && (className.contains("ServiceImpl")
                        || className.contains("MPJBaseService") || className.contains("JoinService"))) {
                    PsiType[] params = type.getParameters();
                    if (params.length >= 2) {
                        return "（当前：" + clsName + "，表：" + params[1].getPresentableText() + "）";
                    }
                }
            }
            // 回落：BaseMapper<Entity> / MPJBaseMapper<Entity> → 取第 1 个泛型参数（实体）
            for (PsiClassType type : superTypes) {
                String className = type.getClassName();
                if (className != null && (className.contains("BaseMapper") || className.contains("MPJBaseMapper"))) {
                    PsiType[] params = type.getParameters();
                    if (params.length >= 1) {
                        return "（当前：" + clsName + "，表：" + params[0].getPresentableText() + "）";
                    }
                }
            }
            // 无泛型信息 → 仅展示类名
            return "（当前：" + clsName + "）";
        }

        /**
         * T-M44 审计兜底（用户实测堆栈：elementChosen → getQualifiedName → stub 树遍历断言硬抛）：
         * 选择器回调的 PSI 读取（name/qualifiedName/getIcon）须在 ReadAction 内（EDT 上同步执行，
         * 语义不变）。internal 供测试缝直接调用（Light 可建 PsiClass 验证选取行为）。
         *
         * T-CallerPicker：入口的调用类校验（VO/DTO/Utils/Controller 等不合法类拒绝 + 友好提示）
         * 原在校验先行、ReadAction 之前直读 PSI——与 name/qualifiedName/getIcon 合并进单次
         * ReadAction（262 断言硬抛：isInterface/hasModifierProperty 同属 PSI 访问）。
         */
        public void pickPsiClassForTest(PsiClass cls) {
            Pair<String, Triple<String, String, Icon>> outcome = ReadAction.compute(() -> {
                String name = cls.getName();
                if (name == null) return null;
                String rejectReason = validateCallerClass(cls);
                if (rejectReason != null) {
                    return new Pair<>(rejectReason, null);
                }
                return new Pair<>(null, new Triple<>(name, cls.getQualifiedName(), cls.getIcon(0)));
            });
            if (outcome == null) return;
            if (outcome.getFirst() != null) {
                showCallerRejectMessage(outcome.getFirst(),
                        outcome.getSecond() != null ? outcome.getSecond().getFirst() : "");
                return;
            }
            Triple<String, String, Icon> picked = outcome.getSecond();
            if (picked == null) return;
            setPicked(picked.getFirst(), picked.getSecond(), picked.getThird());
        }

        /**
         * T-Mxx：校验选中的类是否为合法的 SQL→Java 调用类。
         * 合法 = Mapper/Service/ServiceImpl/Impl（白名单后缀）或继承 MPJ 接口的具体类。
         * 不合法 = VO/DTO/BO/PO/Utils/Controller/Config 等无 CRUD 能力的类。
         *
         * @return null = 合法；非 null = 拒绝原因 bundle key
         */
        private String validateCallerClass(PsiClass cls) {
            String name = cls.getName();
            if (name == null) return "reverse.panel.callerReject.unknown";
            // 1. 快速白名单：类名以 Mapper/Service/ServiceImpl/Impl 结尾 → 放行
            if (name.endsWith("Mapper") || name.endsWith("Service")
                    || name.endsWith("ServiceImpl") || name.endsWith("Impl")) {
                return null;
            }
            // 2. 黑名单：VO/DTO/BO/PO/Utils/Util/Controller/Config/Interceptor 等 → 拒绝
            if (name.endsWith("VO") || name.endsWith("DTO") || name.endsWith("BO")
                    || name.endsWith("PO") || name.endsWith("Utils") || name.endsWith("Util")
                    || name.endsWith("Controller") || name.endsWith("Config")
                    || name.endsWith("Interceptor") || name.endsWith("Filter")
                    || name.endsWith("Handler") || name.endsWith("Aspect")) {
                return "reverse.panel.callerReject.voDto";
            }
            // 3. 接口/抽象类 → 拒绝
            if (cls.isInterface() || cls.hasModifierProperty(PsiModifier.ABSTRACT)) {
                return "reverse.panel.callerReject.interface";
            }
            // 4. 其他具体类 → 放行（避免误杀自定义命名）
            return null;
        }

        /** T-Mxx：调用类选择被拒绝的友好提示（状态行显示）。 */
        private void showCallerRejectMessage(String reasonKey, String className) {
            SqlToJavaPanel.this.status(MpSqlVisualizerBundle.message(reasonKey, className), true);
        }

        /**
         * Goto Class 模型委托包装（T-M42）：`getNames` 前插 `this`/`baseMapper` 两个固定名，
         * `getElementsByName` 对二者直出字符串元素（元素即字符串本身），其余方法一行委托
         * {@link GotoClassModel2}。接口签名按 243 平台 {@link ChooseByNameModel} 实证（javap 2024.3.7.1）。
         *
         * 必须显式覆写的三个方法（委托实现按 NavigationItem/PsiClass 强转，对字符串元素会抛
         * ClassCastException/NPE，逐行实证）：
         *  - {@link #getElementName(Object)}/{@link #getFullName(Object)}：字符串元素直出字符串本身（过滤/排序/提示用）；
         *  - {@link #getListCellRenderer()}：字符串元素走纯文本 + 字段图标渲染（委托渲染器对非
         *    NavigationItem 值在 showIcon=true 时 ModuleRendererFactory 返回 null → NPE）。
         */
        public class CallerClassByNameModel implements ChooseByNameModel {
            private final GotoClassModel2 delegate = new GotoClassModel2(project);
            private final ListCellRenderer<Object> delegateRenderer =
                    (ListCellRenderer<Object>) delegate.getListCellRenderer();

            /** T-Mxx：当前编辑器打开的类（选择器打开前检测一次，供 this/baseMapper tooltip 使用）。 */
            private PsiClass currentEditorClass = null;

            /** 特殊项渲染：纯文本 + 字段图标 + 用途说明 tooltip。 */
            private final DefaultListCellRenderer specialRenderer = new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(
                        JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (c instanceof JLabel) {
                        ((JLabel) c).setIcon(AllIcons.Nodes.Field);
                    }
                    if (value instanceof String) {
                        ((JComponent) c).setToolTipText(SPECIAL_TOOLTIPS.get(value));
                    }
                    return c;
                }
            };

            public PsiClass getCurrentEditorClass() {
                return currentEditorClass;
            }

            public void setCurrentEditorClass(PsiClass value) {
                currentEditorClass = value;
            }

            @Override
            public String[] getNames(boolean checkBoxState) {
                String[] base = delegate.getNames(checkBoxState);
                String[] result = new String[2 + base.length];
                result[0] = CALLER_THIS;
                result[1] = CALLER_BASE_MAPPER;
                System.arraycopy(base, 0, result, 2, base.length);
                return result;
            }

            @Override
            public Object[] getElementsByName(String name, boolean checkBoxState, String pattern) {
                if (CALLER_THIS.equals(name) || CALLER_BASE_MAPPER.equals(name)) {
                    return new Object[]{name};
                }
                return delegate.getElementsByName(name, checkBoxState, pattern);
            }

            @Override
            public String getElementName(Object element) {
                if (element instanceof String) return (String) element;
                String computed = ReadAction.compute(() -> delegate.getElementName(element));
                return computed != null ? computed : element.toString();
            }

            @Override
            public String getFullName(Object element) {
                if (element instanceof String) return (String) element;
                return ReadAction.compute(() -> delegate.getFullName(element));
            }

            /** 列表渲染：特殊项走纯文本 + 字段图标；类元素委托 GotoClassModel2 渲染器。 */
            @Override
            public ListCellRenderer<?> getListCellRenderer() {
                return new ListCellRenderer<Object>() {
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                        if (value instanceof String) {
                            return specialRenderer.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                        }
                        return delegateRenderer.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    }
                };
            }

            @Override
            public String getPromptText() {
                String p = delegate.getPromptText();
                return p != null ? p : "";
            }

            @Override
            public String getCheckBoxName() {
                return delegate.getCheckBoxName();
            }

            // getCheckBoxMnemonic() 已废弃（Goto Class 弹窗移除 checkbox UI），删除覆写使用接口默认实现
            @Override
            public String getNotInMessage() {
                String p = delegate.getNotInMessage();
                return p != null ? p : "";
            }

            @Override
            public String getNotFoundMessage() {
                String p = delegate.getNotFoundMessage();
                return p != null ? p : "";
            }

            @Override
            public boolean loadInitialCheckBoxState() {
                return delegate.loadInitialCheckBoxState();
            }

            @Override
            public void saveInitialCheckBoxState(boolean state) {
                delegate.saveInitialCheckBoxState(state);
            }

            @Override
            public String[] getSeparators() {
                return delegate.getSeparators();
            }

            @Override
            public String getHelpId() {
                String p = delegate.getHelpId();
                return p != null ? p : "";
            }

            @Override
            public boolean willOpenEditor() {
                return delegate.willOpenEditor();
            }

            @Override
            public boolean useMiddleMatching() {
                return delegate.useMiddleMatching();
            }
        }

        /** 选中结果写入（类图标 + 简单类名，tooltip = 全限定名）+ 变更回调。 */
        private void setPicked(String simpleName, String qualifiedName, Icon icon) {
            currentText = simpleName;
            setToolTipText(qualifiedName);
            pickedIcon = icon;
            refresh();
            onChanged.invoke();
        }

        /** 子组件点击统一入口：单次点击弹选择器 + 手型光标（按钮自身消费事件，不重复触发）。 */
        private void attachClick(JComponent component) {
            component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            component.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) {
                        pickClass();
                    }
                }
            });
        }

        /** 清除选中值（✕ 按钮触发）。 */
        private void clearPicked() {
            currentText = "";
            setToolTipText(null);
            pickedIcon = null;
            refresh();
            onChanged.invoke();
        }

        /** 按状态刷新显示：空值 → 占位灰字 + … 浏览；有值 → 类图标 + 类名 + ✕ 清除。 */
        private void refresh() {
            if (currentText.isEmpty()) {
                iconLabel.setIcon(null);
                textLabel.setText(placeholder);
                textLabel.setForeground(UIUtil.getInactiveTextColor());
                // 占位文案被截断时 hover 显示完整提示（选中后字段级 tooltip 为全限定名，不遮蔽）
                textLabel.setToolTipText(placeholder);
                actionButton.setIcon(PICKER_ELLIPSIS_ICON);
                actionButton.setToolTipText(MpSqlVisualizerBundle.message("reverse.panel.pickClass"));
            } else {
                iconLabel.setIcon(pickedIcon);
                textLabel.setText(currentText);
                textLabel.setForeground(UIUtil.getLabelForeground());
                textLabel.setToolTipText(null);
                actionButton.setIcon(PICKER_CANCEL_ICON);
                actionButton.setToolTipText(MpSqlVisualizerBundle.message("reverse.panel.clearClass"));
            }
        }

        /** 测试访问器：可点击子组件清单（iconLabel/textLabel/按钮宿主）。 */
        public List<JComponent> clickTargetsForTest() {
            return List.of(iconLabel, textLabel, actionButtonHost);
        }

        /** 测试访问器：清除/浏览按钮（断言紧凑尺寸）。 */
        public JButton actionButtonForTest() {
            return actionButton;
        }

        /** 测试访问器：模拟选择器选中（类图标 + 简单类名 + 全限定名 + 变更回调）。 */
        /** 对齐 Kotlin 默认参数 icon = AllIcons.Nodes.Class。 */
        public void pickForTest(String simpleName, String qualifiedName) {
            pickForTest(simpleName, qualifiedName, com.intellij.icons.AllIcons.Nodes.Class);
        }

        public void pickForTest(String simpleName, String qualifiedName, Icon icon) {
            setPicked(simpleName, qualifiedName, icon);
        }

        /** T-Mxx 测试缝：模拟选中 this/baseMapper（走 buildSpecialTooltip 完整路径，含当前编辑器类上下文）。 */
        public void pickSpecialForTest(String special) {
            setPicked(special, buildSpecialTooltip(special), AllIcons.Nodes.Field);
        }

        /** 测试访问器：模拟 ✕ 清除。 */
        public void clearForTest() {
            clearPicked();
        }

        /** 测试访问器：当前显示的类图标（null = 空值占位态）。 */
        public Icon iconForTest() {
            return iconLabel.getIcon();
        }

        /** 测试访问器：文本标签 tooltip（占位态 = 完整占位文案；选中态 = null 不遮蔽字段级全限定名）。 */
        public String textTooltipForTest() {
            return textLabel.getToolTipText();
        }
    }

    /**
     * T-MapResultVo v4：返回类型字段——<b>照抄 {@link ClassPickerField}（「调用类」行）的交互与观感</b>。
     *
     * 用户真机四轮反馈定稿：①v2 可编辑组合框带下拉箭头＋固定预置项（枚举追不上真实形态换壳复现）
     * → v3 去下拉改纯输入框；②v3 的占位走 {@code JTextField.placeholderText} 客户属性，<b>本仓该做法
     * 不渲染</b>（全仓唯一一处，真机实测空框无灰字）→ v4 改为与「调用类」完全同款：
     * 手绘圆角输入框底 + 灰字 {@link JBLabel} 占位（{@link UIUtil#getInactiveTextColor}，空值态）＋
     * 右侧「…」按钮（空值=弹选择、有值=✕ 清除）＋<b>点击字段任意位置弹选择器</b>。
     *
     * 选择器（用户四轮反馈后定稿的形态）：平台弹出列表——「自动（留空；实体/VO 推断）」
     * /「List&lt;Map&lt;String, Object&gt;&gt;」/「List&lt;Object&gt;」/「选择类…（Goto Class）」。
     * 生成器按声明形态派生调用族（含 Map→Maps 族、含 Object→Objs 族、其余按类名走 VO 语义）。
     */
    public class ReturnTypeField extends JPanel {
        private String currentText = "";
        private Icon pickedIcon = null;
        private final Project projectRef;
        private final String placeholder;
        private final Function0<Unit> onChanged;

        private final JBLabel iconLabel = new JBLabel();
        private final JBLabel textLabel = new JBLabel(" ");

        private final JButton pickButton = new JButton(PICKER_ELLIPSIS_ICON);

        /** 按钮宿主：FlowLayout 垂直居中（BorderLayout.EAST 会拉伸子组件高度，需宿主隔离）——
         *  与 {@link ClassPickerField} 同款形态（观感一致，用户实测⋯须可见可点）。 */
        private final JPanel actionButtonHost = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 4));

        /** 返回类型「选择类」弹框模型（T-MapResultVo v5）：固定候选前插，与调用类 this/baseMapper 同款。 */
        public final ChooseByNameModel typeModel;

        public ReturnTypeField(Project projectRef, String placeholder) {
            this(projectRef, placeholder, () -> Unit.INSTANCE);
        }

        public ReturnTypeField(Project projectRef, String placeholder, Function0<Unit> onChanged) {
            super(new BorderLayout());
            this.projectRef = projectRef;
            this.placeholder = placeholder;
            this.onChanged = onChanged;

            pickButton.setBorderPainted(false);
            pickButton.setContentAreaFilled(false);
            pickButton.setFocusPainted(false);
            pickButton.setMargin(JBUI.emptyInsets());
            pickButton.setBorder(JBUI.Borders.empty());
            pickButton.setIconTextGap(0);
            pickButton.setPreferredSize(new Dimension(12, 12));
            pickButton.setMinimumSize(new Dimension(12, 12));
            pickButton.setMaximumSize(new Dimension(12, 12));
            pickButton.setToolTipText(MpSqlVisualizerBundle.message("reverse.panel.pickClass"));
            // 空值=弹选择器（与「调用类」同款入口）；有值=清除
            pickButton.addActionListener(e -> {
                if (currentText.isEmpty()) openPicker();
                else clearValue();
            });

            actionButtonHost.setOpaque(false);
            actionButtonHost.setBorder(JBUI.Borders.empty(0, JBUI.scale(2), 0, JBUI.scale(4)));
            actionButtonHost.add(pickButton);

            setOpaque(false);
            setPreferredSize(new Dimension(1, JBUI.scale(24)));
            setMinimumSize(new Dimension(1, JBUI.scale(24)));
            iconLabel.setBorder(JBUI.Borders.empty(0, 5, 0, 0));
            textLabel.setBorder(JBUI.Borders.empty(0, 4, 0, 0));
            add(iconLabel, BorderLayout.WEST);
            add(textLabel, BorderLayout.CENTER);
            add(actionButtonHost, BorderLayout.EAST);
            // 点击字段任意位置弹选择器（子组件铺满后父面板收不到冒泡——监听挂到每个子组件 + 手型光标）；
            // 子组件点击在 EDT 惰性绑定（子组件在下方才创建，此处 init 已全部就位）
            attachClick(iconLabel);
            attachClick(textLabel);
            attachClick(this);

            this.typeModel = new ReturnTypeByNameModel();
            refresh();
        }

        /**
         * 输入框观感：圆角底色 + 主题边框色（对齐 IDEA TextField；只读无焦点态）——
         * 与 {@link ClassPickerField} 共用 [paintPickerFieldSurface]，保证两行观感一致（UI-045 抽出）。
         */
        @Override
        public void paintComponent(Graphics g) {
            paintPickerFieldSurface(this, g);
        }

        /** 对外文本（生成器读取返回声明；测试直接赋值不触发变更回调——对齐 ClassPickerField 语义）。 */
        public String getText() {
            return currentText;
        }

        public void setText(String value) {
            currentText = value;
            refresh();
        }

        /** 测试缝：显示层文案（空值态=占位灰字，有值态=声明文本）。 */
        public Object placeholderTextForTest() {
            return textLabel.getText();
        }

        /** 测试缝：选类按钮（可见性/可点性断言）。 */
        public JButton pickButtonForTest() {
            return pickButton;
        }

        /** 测试访问器：显示层标签（灰字占位/取值文案断言；v4 不再有输入框组件）。 */
        public Component editorComponentForTest() {
            return textLabel;
        }

        /** 测试缝：取值态左侧图标（声明形态/类的图标断言——用户真机反馈「选中后没有类图标」）。 */
        public Icon valueIconForTest() {
            return pickedIcon;
        }

        /** 子组件点击统一入口：单次点击弹选择器 + 手型光标（按钮自身消费事件，不重复触发）。 */
        private void attachClick(JComponent component) {
            component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            component.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) openPicker();
                }
            });
        }

        /** 清除选中值（✕ 按钮触发）。 */
        private void clearValue() {
            currentText = "";
            pickedIcon = null;
            setToolTipText(null);
            refresh();
            onChanged.invoke();
        }

        /**
         * 选择器：<b>直接进「选择类」弹框</b>（与「调用类」点击即弹逐字同款交互）——Map/Objs 声明与
         * 「自动（留空）」作为<b>固定候选前插</b>在候选列表最前（{@link ReturnTypeByNameModel}，
         * 与调用类的 this/baseMapper 同款机制），不再是「先弹四项小列表再进类搜索」两层交互。
         */
        private void openPicker() {
            ChooseByNamePopup popup = ChooseByNamePopup.createPopup(projectRef, typeModel, (PsiElement) null);
            popup.invoke(new ChooseByNamePopupComponent.Callback() {
                @Override
                public void elementChosen(Object element) {
                    applyChosen(element);
                }

                @Override
                public void onClose() {
                }
            }, ModalityState.current(), false);
        }

        /**
         * 选中结果映射（弹框回调与测试共用）：固定项（自动/两条 Map 声明，字符串元素）直写；
         * 类元素取简单名 + 图标（PSI 读取包 ReadAction——262 断言硬抛，与 ClassPickerField 同款）。
         */
        public void applyChosen(Object element) {
            if (element == null) return;
            if (element instanceof String) {
                String s = (String) element;
                if (s.equals(autoLabelOf())) {
                    setValue("", null);
                } else {
                    // 声明形态（Map/Objs 列表）带类图标——用户真机反馈「选中后没有类图标」
                    setValue(s, AllIcons.Nodes.Class);
                }
            } else if (element instanceof PsiClass) {
                PsiClass psiClass = (PsiClass) element;
                Pair<String, Icon> picked = ReadAction.compute(() -> {
                    String name = psiClass.getName();
                    if (name == null) return null;
                    return new Pair<>(name, psiClass.getIcon(0));
                });
                if (picked == null) return;
                setValue(picked.getFirst(), picked.getSecond());
            }
        }

        private String autoLabelOf() {
            return MpSqlVisualizerBundle.message("reverse.panel.resultType.auto");
        }

        /** 固定候选（四项，均以字符串元素承载——与调用类 this/baseMapper 的元素形态一致；
         *  Map 两形态（列表/分页）并列，Objs 无分页重载故不提供 Page&lt;Object&gt;——MP core 3.5.17 javap 实证）。 */
        private List<String> specialNames() {
            return List.of(autoLabelOf(), MAP_LIST_DECL, PAGE_MAP_LIST_DECL, OBJECT_LIST_DECL);
        }

        /**
         * 「选择类」弹框模型：委托 {@link GotoClassModel2}，在候选名列表最前<b>前插</b>返回类型固定项
         * （自动（留空）/List&lt;Map&lt;String, Object&gt;&gt;/List&lt;Object&gt;）——与 {@link CallerClassByNameModel}
         * 的 this/baseMapper 完全同款机制（字符串元素、元素名/全限定名直出、独立渲染器 + 字段图标），
         * 用户真机五轮反馈「为什么这两个不能出现在选择类的弹框里，类似当时的 this」。
         */
        public class ReturnTypeByNameModel implements ChooseByNameModel {
            private final GotoClassModel2 delegate = new GotoClassModel2(projectRef);
            private final ListCellRenderer<Object> delegateRenderer =
                    (ListCellRenderer<Object>) delegate.getListCellRenderer();

            private final DefaultListCellRenderer specialRenderer = new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(
                        JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    // 声明形态（Map/Objs 列表）用类图标（与选中后字段侧图标一致）；「自动」用模式项图标
                    JLabel label = c instanceof JLabel ? (JLabel) c : null;
                    if (label != null) {
                        label.setIcon(autoLabelOf().equals(value) ? AllIcons.Nodes.Field : AllIcons.Nodes.Class);
                        // 「自动（留空）」项 tooltip 复用返回类型占位说明（Map/Objs 两项文本自明，不附 tooltip）
                        if (autoLabelOf().equals(value)) {
                            label.setToolTipText(placeholder);
                        }
                    }
                    return c;
                }
            };

            @Override
            public String[] getNames(boolean checkBoxState) {
                List<String> specials = specialNames();
                String[] base = delegate.getNames(checkBoxState);
                String[] result = new String[specials.size() + base.length];
                for (int i = 0; i < specials.size(); i++) {
                    result[i] = specials.get(i);
                }
                System.arraycopy(base, 0, result, specials.size(), base.length);
                return result;
            }

            @Override
            public Object[] getElementsByName(String name, boolean checkBoxState, String pattern) {
                if (specialNames().contains(name)) {
                    return new Object[]{name};
                }
                return delegate.getElementsByName(name, checkBoxState, pattern);
            }

            /** 固定项元素名 = 字符串本身（委托实现按 NavigationItem 强转）；类元素委托（PSI 包 ReadAction）。 */
            @Override
            public String getElementName(Object element) {
                if (element instanceof String) return (String) element;
                String computed = ReadAction.compute(() -> delegate.getElementName(element));
                return computed != null ? computed : element.toString();
            }

            /** 固定项全限定名 = 字符串本身；类元素委托（同上 ReadAction，262 stub 断言实证）。 */
            @Override
            public String getFullName(Object element) {
                if (element instanceof String) return (String) element;
                return ReadAction.compute(() -> delegate.getFullName(element));
            }

            @Override
            public ListCellRenderer<?> getListCellRenderer() {
                return new ListCellRenderer<Object>() {
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                        if (value instanceof String) {
                            return specialRenderer.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                        }
                        return delegateRenderer.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    }
                };
            }

            @Override
            public String getPromptText() {
                String p = delegate.getPromptText();
                return p != null ? p : "";
            }

            @Override
            public String getCheckBoxName() {
                return delegate.getCheckBoxName();
            }

            @Override
            public String getNotInMessage() {
                String p = delegate.getNotInMessage();
                return p != null ? p : "";
            }

            @Override
            public String getNotFoundMessage() {
                String p = delegate.getNotFoundMessage();
                return p != null ? p : "";
            }

            @Override
            public boolean loadInitialCheckBoxState() {
                return delegate.loadInitialCheckBoxState();
            }

            @Override
            public void saveInitialCheckBoxState(boolean state) {
                delegate.saveInitialCheckBoxState(state);
            }

            @Override
            public String[] getSeparators() {
                return delegate.getSeparators();
            }

            @Override
            public String getHelpId() {
                String p = delegate.getHelpId();
                return p != null ? p : "";
            }

            @Override
            public boolean willOpenEditor() {
                return delegate.willOpenEditor();
            }

            @Override
            public boolean useMiddleMatching() {
                return delegate.useMiddleMatching();
            }
        }

        /** 选中写入（裸声明文本）或清除（空串）。 */
        private void setValue(String value, Icon icon) {
            currentText = value;
            pickedIcon = icon;
            refresh();
            onChanged.invoke();
        }

        /** 按状态刷新显示：空值 → 占位灰字 + ⋯ 浏览；有值 → 图标 + 声明文本 + ✕ 清除。 */
        private void refresh() {
            if (currentText.isEmpty()) {
                iconLabel.setIcon(null);
                textLabel.setText(placeholder);
                textLabel.setForeground(UIUtil.getInactiveTextColor());
                textLabel.setToolTipText(placeholder);
                pickButton.setIcon(PICKER_ELLIPSIS_ICON);
                pickButton.setToolTipText(MpSqlVisualizerBundle.message("reverse.panel.pickClass"));
            } else {
                iconLabel.setIcon(pickedIcon);
                textLabel.setText(currentText);
                textLabel.setForeground(UIUtil.getLabelForeground());
                textLabel.setToolTipText(null);
                pickButton.setIcon(PICKER_CANCEL_ICON);
                pickButton.setToolTipText(MpSqlVisualizerBundle.message("reverse.panel.clearClass"));
            }
        }
    }

    // ------------------------------------------------------------------
    // 转换流程
    // ------------------------------------------------------------------

    private void convert() {
        if (disposed || project.isDisposed()) return;
        invalidateRequests(true);
        final ConversionRequest request = requestSnapshot();
        String text = request.text;
        if (text.isBlank()) {
            status(MpSqlVisualizerBundle.message("reverse.panel.empty"), true);
            return;
        }
        // T-BatchSql：顶层分号拆分 → 多语句走批量路径
        List<String> statements = SqlToJavaConverter.INSTANCE.splitStatements(text);
        if (statements.size() > 1) {
            convertBatch(statements, request);
            return;
        }
        // 单条 SQL：尝试快速 parse 提供精确错误提示（保持既有单条错误行号定位体验）
        SqlReverseResult result;
        try {
            result = SqlToJavaConverter.INSTANCE.parse(text);
        } catch (SqlReverseException ex) {
            status(
                    MpSqlVisualizerBundle.message(ex.getKey(), ex.getDetail())
                            + "（第 " + SqlToJavaConverter.INSTANCE.errorLine(text, ex.getOffset()) + " 行）",
                    true);
            return;
        }
        // T-M36.6：缓存成功解析（输入文本未变时切换形态复用）
        cacheText = text;
        cacheResult = result;
        ReverseWrapperFormat format = request.format;
        if (format == ReverseWrapperFormat.QUERY) {
            cacheCandidates = null;
            render(result, Collections.emptyList(), format, Collections.emptyMap(), request);
            return;
        }
        dispatchBackground(() -> {
            // Kotlin val payload = try {...} catch {...} 的 Java 等价——单次赋值须 genuinely final
            // 才能被下方 EDT lambda 捕获，故把 try/catch 折进辅助方法一次求值（语义不变：fatal 透传、
            // 失败降级「无候选」）。
            final Triple<List<SqlToJavaConverter.EntityCandidate>,
                    Map<String, List<SqlToJavaConverter.EntityCandidate>>,
                    Pair<Boolean, Set<String>>> payload = resolveReversePayload(project, result, request.caller);
            dispatchUi(request, () -> {
                cacheCandidates = payload.getFirst();
                callerMpj = payload.getThird().getFirst();
                callerMethods = payload.getThird().getSecond();
                boolean manualEnabled = manualEntitySelectionEnabled();
                // 手动选择实体弹窗分支（开关开启时）：
                //  - JOIN 多表 → 主表 + 副表一次多选（替代 T-M37 逐表弹窗）；
                //  - 主表 0 候选（无 JOIN）→ 单行主表手动挑选（自动推断兜底）。
                if (manualEnabled && !result.joins().isEmpty()) {
                    // T-M49：唯一可推断（主表 1 候选 + 每个副表各 1 候选）→ 跳过弹窗直接渲染，
                    // 减少规范命名项目每次转换的确认打扰；有歧义（0/多候选）仍弹手动选择。
                    if (canAutoInferEntitySelection(payload.getFirst(), payload.getSecond())) {
                        Map<String, JavaCodeGenerator.EntityTarget> subEntities = new HashMap<>();
                        for (Map.Entry<String, List<SqlToJavaConverter.EntityCandidate>> entry : payload.getSecond().entrySet()) {
                            List<SqlToJavaConverter.EntityCandidate> cands = entry.getValue();
                            if (!cands.isEmpty()) {
                                JavaCodeGenerator.EntityTarget sub = toSubEntity(cands.get(0));
                                if (sub != null) subEntities.put(entry.getKey(), sub);
                            }
                        }
                        cacheCandidates = payload.getFirst();
                        cacheSubEntities = subEntities;
                        // T-TS-03：写体显式过闸——render 内的 setResultText 写结果区文档；本拍由 BGT 派发
                        DocWriteContext.runWriteSafe(project, "reverse render dispatch (auto infer)", () ->
                                render(result, payload.getFirst(), format, subEntities, request));
                    } else {
                        showEntitySelection(result, payload.getFirst(), payload.getSecond(), format, request);
                    }
                    return;
                }
                if (manualEnabled && payload.getFirst().isEmpty()) {
                    showEntitySelection(result, payload.getFirst(), payload.getSecond(), format, request);
                    return;
                }
                // 现状：副表候选逐表处理（开关关闭时多候选不弹窗，取第一个）
                resolveSubEntities(payload.getSecond(), result.joins(), request, subEntities -> {
                    if (!isCurrent(request)) return Unit.INSTANCE;
                    cacheSubEntities = subEntities;
                    // T-TS-03：同上一处——本回调可能由模态选择弹窗关闭后触发，写体统一过闸
                    DocWriteContext.runWriteSafe(project, "reverse render dispatch (sub entities)", () ->
                            render(result, payload.getFirst(), format, subEntities, request));
                    return Unit.INSTANCE;
                });
            });
        });
    }

    /**
     * Kotlin 侧 {@code val payload = try {...} catch {...}} 的 Java 承载（原内联 try/catch 折出）：
     * BGT 单次 ReadAction 收集主/副表候选与调用类能力（MPJ/方法集），PSI 读失败降级「无候选」，
     * JVM 级 fatal 原样透传（T-FP-03 收口语义不变）。
     */
    private Triple<List<SqlToJavaConverter.EntityCandidate>,
            Map<String, List<SqlToJavaConverter.EntityCandidate>>,
            Pair<Boolean, Set<String>>> resolveReversePayload(
            Project project, SqlReverseResult result, String caller) {
        try {
            return ReadAction.compute(() -> {
                List<SqlToJavaConverter.EntityCandidate> candidates =
                        SqlToJavaConverter.INSTANCE.findCandidates(project, result.tableName());
                // T-M37 P1：副表**全部**候选（alias → 列表；零/无别名 → 空），EDT 层按 joins 顺序分发
                Map<String, List<SqlToJavaConverter.EntityCandidate>> subCandidates =
                        SqlToJavaConverter.INSTANCE.findSubEntityCandidates(project, result.joins());
                // 调用类 MPJ 能力（继承链实证）——VO 查询/多表生成据此选合法方法
                boolean callerMpjLocal = SqlToJavaConverter.INSTANCE.invocationHasMpj(project, caller);
                // T-M44：调用类真实方法集（继承链）——生成器按真实 API 选择调用方法（selectPage 缺失回落 selectJoinPage 族）
                Set<String> callerMethodsLocal = SqlToJavaConverter.INSTANCE.invocationMethods(project, caller);
                return new Triple<>(candidates, subCandidates, new Pair<>(callerMpjLocal, callerMethodsLocal));
            });
        } catch (Throwable t) {
            // T-FP-03 收口：JVM 级 fatal 透传（PSI 读失败才降级为「无候选」）
            if (FatalErrors.isFatal(t)) throw t;
            return new Triple<>(
                    Collections.<SqlToJavaConverter.EntityCandidate>emptyList(),
                    Collections.<String, List<SqlToJavaConverter.EntityCandidate>>emptyMap(),
                    new Pair<>(false, Collections.<String>emptySet()));
        }
    }

    /**
     * T-BatchSql：批量 SQL → Java 转换（多语句路径）。
     *
     * 流程：
     *  1. 批量 parse（EDT 纯函数，错误隔离）；
     *  2. 后台单次 ReadAction 查找全部语句的实体候选；
     *  3. EDT 批量生成 + 拼接为代码块写入结果区。
     *
     * 边界（P1）：
     *  - 不弹实体选择弹窗（交互复杂度过高）——统一走自动推断 + 字符串列名回落；
     *  - 不缓存（cacheResult/cacheText 仅单条路径使用）；
     *  - 超过 20 条时状态行提示「语句较多，实体查找可能耗时较长」。
     */
    private void convertBatch(List<String> statements, ConversionRequest request) {
        // 1. 批量解析（EDT 纯函数；错误隔离——失败项 error 非 null）
        List<SqlToJavaConverter.BatchItemResult> parseResults = SqlToJavaConverter.INSTANCE.parseBatch(statements);
        // 2. 后台单次 ReadAction 实体查找（所有语句共享）
        dispatchBackground(() -> {
            Pair<Map<Integer, List<SqlToJavaConverter.EntityCandidate>>,
                    Map<Integer, Map<String, List<SqlToJavaConverter.EntityCandidate>>>> pair =
                    ReadAction.compute(() -> SqlToJavaConverter.INSTANCE.findCandidatesBatch(project, parseResults));
            // 3. EDT 生成 + 拼接
            dispatchUi(request, () -> {
                ReverseWrapperFormat format = request.format;
                String code = SqlToJavaConverter.INSTANCE.generateBatchCode(
                        parseResults,
                        pair.getFirst(),
                        pair.getSecond(),
                        format,
                        buildInvocation(),
                        reverseLogicDeleteValue());
                // T-TS-03：写体显式过闸（setResultText 内同样过闸，这里是「哪次活动在写」的语义
                // 声明点：本拍由 BGT 派发，模态进度下 defaultModalityState 可能取到写不安全模态）
                setResultText(code, request);
                long finalSuccess = parseResults.stream().filter(it -> it.result() != null).count();
                int failed = statements.size() - (int) finalSuccess;
                String msg;
                if (failed > 0) {
                    msg = MpSqlVisualizerBundle.message("reverse.batch.partial", finalSuccess, statements.size(), failed);
                } else {
                    msg = MpSqlVisualizerBundle.message("reverse.batch.success", finalSuccess, statements.size());
                }
                status(msg, failed > 0);
            });
        });
    }

    /** 手动选择实体类开关（测试 environment settings 未注册时默认开）。 */
    private boolean manualEntitySelectionEnabled() {
        try {
            return settingsState().getReverseManualEntitySelection();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * T-M49：JOIN 多表实体是否唯一可推断（主表 1 候选 + 每个副表各 1 候选）——是则可跳过手动选择弹窗，
     * 直接自动渲染。internal 供单元测试直接验证判定逻辑（不依赖面板实例/EDT）。
     */
    public boolean canAutoInferEntitySelection(
            List<SqlToJavaConverter.EntityCandidate> mainCandidates,
            Map<String, List<SqlToJavaConverter.EntityCandidate>> subCandidates) {
        return mainCandidates.size() == 1
                && subCandidates.values().stream().allMatch(it -> it.size() == 1);
    }

    /**
     * 实体选择弹窗（自动推断兜底/多表一次多选）：
     * 弹窗前先写回落结果（取消后不空白），确认后按选中实体重新生成。
     */
    private void showEntitySelection(
            SqlReverseResult result,
            List<SqlToJavaConverter.EntityCandidate> mainCandidates,
            Map<String, List<SqlToJavaConverter.EntityCandidate>> subCandidates,
            ReverseWrapperFormat format,
            ConversionRequest request) {
        if (!isCurrent(request)) return;
        // 先回落显示（弹窗取消 → 保持字符串列名结果）
        setResultText(generateCode(result, null, format, Collections.emptyMap()), request);
        status(joinStatus(result, MpSqlVisualizerBundle.message("reverse.panel.status.noEntity")), false);
        List<EntitySelectionDialog.TableRow> rows = new ArrayList<>();
        rows.add(new EntitySelectionDialog.TableRow(
                EntitySelectionDialog.MAIN_KEY,
                MpSqlVisualizerBundle.message("dialog.selectEntity.mainTable") + " " + result.tableName(),
                mainCandidates.stream()
                        .map(SqlToJavaConverter.EntityCandidate::psiClass)
                        .collect(Collectors.toList()),
                result.tableName()
        ));
        for (ReverseJoin join : result.joins()) {
            String alias = join.alias();
            if (alias != null) {
                List<SqlToJavaConverter.EntityCandidate> subs = subCandidates.getOrDefault(alias, Collections.emptyList());
                List<PsiClass> subClasses = subs.stream()
                        .map(SqlToJavaConverter.EntityCandidate::psiClass)
                        .collect(Collectors.toList());
                rows.add(new EntitySelectionDialog.TableRow(
                        alias,
                        MpSqlVisualizerBundle.message("dialog.selectEntity.joinTable") + " " + alias + " " + join.tableName(),
                        subClasses,
                        join.tableName()
                ));
            }
        }
        EntitySelectionDialog.ConfirmCallback onConfirm = selections -> {
            if (!isCurrent(request)) return;
            // T-Perf-08：实体确认回调解析移后台——candidateOf 的 EntityResolver.resolve 遍历继承链
            // 全部字段 + 每字段注解 resolve，大实体（50+ 字段）耗时线性，EDT 直解析构成确认停顿；
            // 后台 ReadAction 解析后回 EDT 回填缓存并渲染（对齐 convert 的既有异步模式）。
            dispatchBackground(() -> {
                // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
                Object[] resolved = ReadAction.compute(() -> {
                    PsiClass mainPsi = selections.get(EntitySelectionDialog.MAIN_KEY);
                    SqlToJavaConverter.EntityCandidate main = mainPsi != null
                            ? SqlToJavaConverter.INSTANCE.candidateOf(mainPsi) : null;
                    Map<String, JavaCodeGenerator.EntityTarget> subEntities = new HashMap<>();
                    for (ReverseJoin join : result.joins()) {
                        String alias = join.alias();
                        if (alias == null) continue;
                        PsiClass psi = selections.get(alias);
                        if (psi != null) {
                            JavaCodeGenerator.EntityTarget sub = toSubEntity(SqlToJavaConverter.INSTANCE.candidateOf(psi));
                            if (sub != null) subEntities.put(alias, sub);
                        }
                    }
                    return new Object[]{
                            main,
                            subEntities,
                            main != null ? Collections.singletonList(main)
                                    : Collections.<SqlToJavaConverter.EntityCandidate>emptyList()};
                });
                @SuppressWarnings("unchecked")
                java.util.List<SqlToJavaConverter.EntityCandidate> candidates =
                        (java.util.List<SqlToJavaConverter.EntityCandidate>) resolved[2];
                @SuppressWarnings("unchecked")
                Map<String, JavaCodeGenerator.EntityTarget> subEnts =
                        (Map<String, JavaCodeGenerator.EntityTarget>) resolved[1];
                // T-WSC-02：render → setResultText → WriteCommandAction 写结果区文档，属文档写入回调，
                // 不能用 any()（恒 write-unsafe，见 DocWriteContext KDoc）——改 nonModal。
                dispatchUi(request, () -> {
                    cacheCandidates = candidates;
                    cacheSubEntities = subEnts;
                    render(result, candidates, format, subEnts, request);
                });
            });
        };
        EntityDialogHost dialogHost = entityDialogForTest;
        if (dialogHost != null) {
            dialogHost.show(rows, onConfirm);
        } else {
            new EntitySelectionDialog(project, rows, onConfirm).show();
        }
    }

    /**
     * T-M37 P1：副表实体按 joins 顺序逐表解析（EDT 线程）——
     *  - 候选数 > 1：弹 {@link SqlSelectionDialog}（displayTexts 仿主表候选格式「类名（表 表名）」+ 文件名），
     *    选中后写入 subEntities 并在 onSelect 里继续处理后续副表（异步回调链）；
     *  - 候选数 == 1：直接并入；== 0 或该 join 无别名：跳过（该表列回落字符串列名，现状）；
     *  - 全部处理完 → onComplete（最后统一 render；副表选择结果同时进 cacheSubEntities，
     *    切换形态重生成时复用不再弹）。
     */
    private void resolveSubEntities(
            Map<String, List<SqlToJavaConverter.EntityCandidate>> subCandidates,
            List<ReverseJoin> joins,
            ConversionRequest request,
            Function1<Map<String, JavaCodeGenerator.EntityTarget>, Unit> onComplete) {
        // 按 joins 顺序的别名列表（无别名的 join 无候选，直接跳过）
        List<String> aliases = joins.stream()
                .map(ReverseJoin::alias)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Map<String, JavaCodeGenerator.EntityTarget> subEntities = new HashMap<>();
        resolveSubNext(subCandidates, aliases, subEntities, 0, request, onComplete);
    }

    private void resolveSubNext(
            Map<String, List<SqlToJavaConverter.EntityCandidate>> subCandidates,
            List<String> aliases,
            Map<String, JavaCodeGenerator.EntityTarget> subEntities,
            int index,
            ConversionRequest request,
            Function1<Map<String, JavaCodeGenerator.EntityTarget>, Unit> onComplete) {
        if (!isCurrent(request)) return;
        for (int i = index; i < aliases.size(); i++) {
            String alias = aliases.get(i);
            List<SqlToJavaConverter.EntityCandidate> candidates =
                    subCandidates.getOrDefault(alias, Collections.emptyList());
            if (candidates.isEmpty()) {
                continue; // 未找到实体 → 该表列回落字符串列名
            }
            if (candidates.size() == 1) {
                JavaCodeGenerator.EntityTarget sub = toSubEntity(candidates.get(0));
                if (sub != null) subEntities.put(alias, sub);
                continue;
            }
            // 多候选弹窗（EDT；displayTexts 仿主表候选用法）——选完继续处理后续副表
            if (!manualEntitySelectionEnabled()) {
                JavaCodeGenerator.EntityTarget sub = toSubEntity(candidates.get(0));
                if (sub != null) subEntities.put(alias, sub);
                continue;
            }
            // T-M44 审计兜底：候选 PSI 读取（name/containingFile）须在 ReadAction 内（EDT 同步）
            final int nextIndex = i + 1;
            List<SqlSelectionDialog.Row> displayTexts = ReadAction.compute(() -> candidates.stream().map(c ->
                    SqlSelectionDialog.Row.of(
                            c.psiClass().getName() + "（表 " + c.tableInfo().getTableName() + "）",
                            cn.song.mybatisplus.visualizer.ui.SqlSelectionDialogKt.entityCandidateSubtitle(c.psiClass())
                    )).collect(Collectors.toList()));
            new SqlSelectionDialog<>(project, candidates, displayTexts, (SqlToJavaConverter.EntityCandidate selected) -> {
                if (!isCurrent(request)) return;
                JavaCodeGenerator.EntityTarget sub = toSubEntity(selected);
                if (sub != null) subEntities.put(alias, sub);
                resolveSubNext(subCandidates, aliases, subEntities, nextIndex, request, onComplete);
            }).show();
            return;
        }
        onComplete.invoke(subEntities);
    }

    /** 副表候选 → 生成用实体目标（类名缺失则跳过该表——回落字符串列名）。 */
    private JavaCodeGenerator.EntityTarget toSubEntity(SqlToJavaConverter.EntityCandidate candidate) {
        String className = candidate.psiClass().getName();
        if (className == null) return null;
        Map<String, String> columnToField = new HashMap<>();
        for (ColumnInfo col : candidate.tableInfo().getColumns()) {
            columnToField.put(col.getColumnName(), col.getFieldName());
        }
        return new JavaCodeGenerator.EntityTarget(
                className, columnToField, Map.of(),
                candidate.tableInfo().getLogicDeleteColumn(), reverseLogicDeleteValue());
    }

    /**
     * T-ReverseGroupLogicDelete：逻辑删除「未删除」值（项目级优先 → 全局 → 兜底 "0"）——
     * 生成器据此识别 MP 依据 {@code @TableLogic} 自动注入的 {@code AND <列> = <值>} 并跳过。
     * 与正向注入 {@code effectiveLogicDeleteValue().ifBlank { "0" }} 同口径。
     */
    private String reverseLogicDeleteValue() {
        try {
            String value = ProjectPluginSettings.getInstance(project).effectiveLogicDeleteValue();
            if (value != null && !value.isBlank()) return value;
        } catch (Throwable ignored) {
            // 项目级服务未注册（测试环境）等——回落全局兜底
        }
        return SqlToJavaConverter.DEFAULT_LOGIC_DELETE_VALUE;
    }

    /**
     * 供 SQL 日志列表联动（T-M37 P1）：写入输入区，并清空结果区与状态行提示
     * （不自动触发转换——用户点「转换」或切换形态后生成）。
     */
    public void setInputSql(String sql) {
        invalidateRequests(true);
        inputEditor.setSql(sql);
        setResultText("");
        status(" ", false);
    }

    /**
     * T-M36.6：切换形态 → 用当前输入与缓存立即重生成（输入文本未变不重新 parse/查实体）；
     * 无缓存（QUERY 路径未查过实体）或输入已变化 → 走完整转换流程。
     */
    private void regenerateWithNewFormat() {
        if (disposed || project.isDisposed()) return;
        invalidateRequests();
        final ConversionRequest request = requestSnapshot();
        String text = request.text;
        if (text.isBlank()) return;
        if (cacheText == null || !cacheText.equals(text) || cacheResult == null) {
            convert();
            return;
        }
        ReverseWrapperFormat format = request.format;
        List<SqlToJavaConverter.EntityCandidate> candidates = cacheCandidates;
        if (format == ReverseWrapperFormat.QUERY || candidates == null) {
            // QUERY 形态或从未查过实体（QUERY 切换来）→ 重新走完整流程（同步/后台自适应）
            if (format == ReverseWrapperFormat.QUERY) {
                render(cacheResult, Collections.emptyList(), format, Collections.emptyMap(), request);
            } else {
                convert();
            }
            return;
        }
        render(cacheResult, candidates, format, cacheSubEntities, request);
    }

    /** T-M37：多表 JOIN 状态提示（生成 MPJ 形态说明）。 */
    private String joinStatus(SqlReverseResult result, String base) {
        if (!result.joins().isEmpty()) {
            return base + "（多表 JOIN 已生成 MPJ 形态）";
        }
        return base;
    }

    /**
     * T-FixMpjStale：调用类变更 → 后台 ReadAction 反查 MPJ 能力与方法集（与 convert 解析链
     * 的 ：813 反查同源 {@link SqlToJavaConverter#invocationHasMpj}/{@link SqlToJavaConverter#invocationMethods}），回填
     * callerMpj/callerMethods 后再重生成。反查是 PSI 索引访问（须 BGT ReadAction——EDT 慢操作
     * 断言），完成后 EDT 派发重生成；特殊值 this/baseMapper 恒 MPJ 能力（与反查同语义，同步分支）。
     */
    private void refreshCallerCapabilityThenRegenerate() {
        if (disposed || project.isDisposed()) return;
        invalidateRequests();
        final ConversionRequest request = requestSnapshot();
        String caller = request.caller.trim();
        if (caller.isEmpty() || "this".equals(caller) || "baseMapper".equals(caller)) {
            callerMpj = !caller.isEmpty();
            callerMethods = Collections.emptySet();
            if (cacheResult != null) regenerateWithNewFormat();
            return;
        }
        dispatchBackground(() -> {
            Pair<Boolean, Set<String>> pair;
            try {
                pair = ReadAction.compute(() -> new Pair<>(
                        SqlToJavaConverter.INSTANCE.invocationHasMpj(project, caller),
                        SqlToJavaConverter.INSTANCE.invocationMethods(project, caller)));
            } catch (Throwable t) {
                // T-FP-03 收口：JVM 级 fatal 透传（PSI 读失败才降级为「无 MPJ 能力/无方法集」）
                if (FatalErrors.isFatal(t)) throw t;
                pair = new Pair<>(false, Collections.<String>emptySet());
            }
            Pair<Boolean, Set<String>> resolved = pair;
            dispatchUi(request, () -> {
                callerMpj = resolved.getFirst();
                callerMethods = resolved.getSecond();
                if (cacheResult != null) regenerateWithNewFormat();
            });
        });
    }

    /**
     * 结果区写文本（WriteCommandAction——平台线程检查强制 write action）。
     *
     * <p>T-TS-03：写体统一过 {@link DocWriteContext} 闸门。{@code WriteCommandAction} 只声明「这是一次命令式写」，
     * <b>不赋予 write-safe 资格</b>——合法性取决于这次 EDT 活动本身有没有资格（posted runnable 一律不算
     * 用户活动，见 DocWriteContext KDoc）。本方法的调用方里有 BGT 派发的 {@code invokeLater} 拍（批量转换
     * convertBatch）与模态选择弹窗的回调，这些活动不保证 write-safe；闸门判为不可写时转 nonModal
     * 投递一拍。写体幂等（重投递写入的是同一份文本，文档内容不变），重跑无害。
     */
    private void setResultText(String text) {
        setResultText(text, requestSnapshot());
    }

    private void setResultText(String text, ConversionRequest request) {
        DocWriteContext.runWriteSafe(project, "reverse panel result write", () -> {
            // 写安全闸门也可能再派发一拍，必须在真正写文档时复查，不能只守上游回调。
            if (!isCurrent(request)) return;
            WriteCommandAction.runWriteCommandAction(project, () -> resultEditor.getDocument().setText(text));
        });
    }

    /** T-M45：复制文本 = 剥离注释后的结果区内容（复制按钮经此取文本）。 */
    private String copyText() {
        return JavaCommentStripper.strip(resultEditor.getDocument().getText());
    }

    /** T-M45：格式化结果区当前内容（IDE Java 格式化器，结果写回编辑器——复制基于当前内容）。 */
    private void formatResult() {
        // T-Perf-11：格式化（PSI 创建 + 全量 reformat）移 Task.Backgroundable 后台执行，
        // 结果回 EDT 写回——批量转换 20+ 条语句拼大代码块时 EDT 不再随体积线性冻结。
        final ConversionRequest request = requestSnapshot();
        String code = resultEditor.getDocument().getText();
        JavaCodeFormatter.INSTANCE.formatAsync(project, code, formatted -> {
            if (isCurrent(request) && code.equals(resultEditor.getDocument().getText())
                    && !formatted.equals(code)) {
                setResultText(formatted, request);
            }
        });
    }

    /** T-M36.7：调用上下文（调用类 + VO 均非必选；留空返回 null → 纯表达式）。 */
    private JavaCodeGenerator.InvocationTarget buildInvocation() {
        String caller = callerField.getText().trim();
        String vo = voField.getText().trim();
        if (vo.isEmpty()) vo = null;
        if (caller.isEmpty() && vo == null) return null;
        if (caller.isEmpty()) {
            // T-M50：仅选 VO（无调用类）→ VO 用于 selectAs 字段引用（Vo::getXxx），不生成调用行。
            // mpj = true 放开 JOIN/VO 判定（selectAs VO 引用不依赖调用类 MPJ 能力）
            return new JavaCodeGenerator.InvocationTarget("", null, true, Set.of(), false, vo);
        }
        return new JavaCodeGenerator.InvocationTarget(caller, null, callerMpj, callerMethods, false, vo);
    }

    private void render(
            SqlReverseResult result,
            List<SqlToJavaConverter.EntityCandidate> candidates,
            ReverseWrapperFormat format,
            Map<String, JavaCodeGenerator.EntityTarget> subEntities) {
        render(result, candidates, format, subEntities, requestSnapshot());
    }

    /** 结果分发：0 候选回落 QueryWrapper；1 候选直接生成；多候选选择后生成。 */
    private void render(
            SqlReverseResult result,
            List<SqlToJavaConverter.EntityCandidate> candidates,
            ReverseWrapperFormat format,
            Map<String, JavaCodeGenerator.EntityTarget> subEntities,
            ConversionRequest request) {
        if (!isCurrent(request)) return;
        if (candidates.isEmpty()) {
            String code0 = generateCodeOrReport(result, null, format, subEntities);
            if (code0 == null) return;
            setResultText(code0, request);
            status(joinStatus(result, MpSqlVisualizerBundle.message("reverse.panel.status.noEntity")), false);
        } else if (candidates.size() == 1) {
String code1 = generateCodeOrReport(result, candidates.get(0), format, subEntities);
            if (code1 == null) return;
            setResultText(code1, request);
            status(joinStatus(result, MpSqlVisualizerBundle.message(
                    "reverse.panel.status.entity",
                    candidates.get(0).psiClass().getName() != null ? candidates.get(0).psiClass().getName() : "")), false);
        } else {
            // 开关关闭 → 不弹任何选择框，取第一个（纯自动推断）
            if (!manualEntitySelectionEnabled()) {
                String code2 = generateCodeOrReport(result, candidates.get(0), format, subEntities);
                if (code2 == null) return;
                setResultText(code2, request);
                // T-FixEntityAmbiguity：多候选静默取首须明示——真机实证同表多实体（User/UserDO）
                // 静默取到字段子集实体，用户只见「回落字符串列名」大列表误判为映射缺陷
                String msg;
                if (candidates.size() > 1) {
                    String names = candidates.stream()
                            .map(c -> c.psiClass().getName())
                            .filter(Objects::nonNull)
                            .collect(Collectors.joining("、"));
                    msg = MpSqlVisualizerBundle.message(
                            "reverse.panel.status.entityAmbiguous",
                            result.tableName(), names,
                            candidates.get(0).psiClass().getName() != null ? candidates.get(0).psiClass().getName() : "");
                } else {
                    msg = MpSqlVisualizerBundle.message(
                            "reverse.panel.status.entity",
                            candidates.get(0).psiClass().getName() != null ? candidates.get(0).psiClass().getName() : "");
                }
                status(joinStatus(result, msg), false);
                return;
            }
            // 多候选弹窗（EDT；displayTexts 仿主表候选用法）——选完继续处理后续副表
            // T-M44 审计兜底：候选 PSI 读取（name/containingFile）须在 ReadAction 内（EDT 同步）
            List<SqlSelectionDialog.Row> displayTexts = ReadAction.compute(() -> candidates.stream().map(c ->
                    SqlSelectionDialog.Row.of(
                            c.psiClass().getName() + "（表 " + c.tableInfo().getTableName() + "）",
                            cn.song.mybatisplus.visualizer.ui.SqlSelectionDialogKt.entityCandidateSubtitle(c.psiClass())
                    )).collect(Collectors.toList()));
            new SqlSelectionDialog<>(project, candidates, displayTexts, (SqlToJavaConverter.EntityCandidate selected) -> {
                if (!isCurrent(request)) return;
                String codeSel = generateCodeOrReport(result, selected, format, subEntities);
                if (codeSel == null) return;
                setResultText(codeSel, request);
                status(MpSqlVisualizerBundle.message(
                        "reverse.panel.status.entity",
                        selected.psiClass().getName() != null ? selected.psiClass().getName() : ""), false);
            }).show();
        }
    }

    /** 面板级生成（调用上下文 + 副实体归属）。 */
    /**
     * T-PickMpjErr：生成期业务拒绝（SqlReverseException，如多表 JOIN 需 MPJ 依赖而调用类无 MPJ 能力）
     * 转状态栏错误提示，结果区保留上次内容；返回 null = 已报错。generateCode 在 EDT 鼠标回调
     * （选类 elementChosen / 形态切换 / 弹窗回调）内同步执行，不接住会逃逸成平台 UnhandledException
     * 红叹号（真机实证）。口径对齐 convert() 的解析期 catch（不带行号——errorLine 需原始输入文本，
     * 生成期错误均为结构性拒绝）。报错时机与 joinStatus 成功提示互斥，调用点据 null 短路。
     */
    private String generateCodeOrReport(
            @NotNull cn.song.mybatisplus.visualizer.reverse.SqlReverseResult result,
            SqlToJavaConverter.EntityCandidate candidate,
            @NotNull ReverseWrapperFormat format,
            @NotNull Map<String, JavaCodeGenerator.EntityTarget> subEntities) {
        try {
            return generateCode(result, candidate, format, subEntities);
        } catch (SqlReverseException ex) {
            status(MpSqlVisualizerBundle.message(ex.getKey(), ex.getDetail()), true);
            return null;
        }
    }

    private String generateCode(
            SqlReverseResult result,
            SqlToJavaConverter.EntityCandidate candidate,
            ReverseWrapperFormat format,
            Map<String, JavaCodeGenerator.EntityTarget> subEntities) {
        return SqlToJavaConverter.INSTANCE.generateCode(result, candidate, format, buildInvocation(), subEntities,
                reverseLogicDeleteValue());
    }

    /** 反向转换配置形态（测试环境 settings 未注册时默认 AUTO）。 */
    private ReverseWrapperFormat reverseFormat() {
        try {
            return ReverseWrapperFormat.fromPersisted(settingsState().getReverseWrapperFormat());
        } catch (Throwable ignored) {
            return ReverseWrapperFormat.AUTO;
        }
    }

    /**
     * 两个类选择字段（「调用类」/「返回类型」）共用的输入框外观（UI-045）。
     *
     * <p>按平台口径复刻 {@code DarculaUIUtil.doPaint}（2024.3.7.1 字节码实证，k2j 侧 javap 复核：
     * {@code DarculaUIUtil.BW} 为 {@code JBValue}，其 {@code get()} 返回 int），两个关键量：
     * <ul>
     * <li><b>描边宽度</b> {@code BW} = {@code Component.focusWidth}，默认 <b>2px</b>（不是 1px）；</li>
     * <li><b>圆角半径</b> 平台硬编码 {@code JBUIScale.scale(7.0f)}（<b>不是</b> {@code Component.arc = 5}），
     * 且用 {@code Path2D.quadTo} 画<b>二次贝塞尔</b>而非 {@code drawRoundRect} 的圆弧——同一半径下
     * 贝塞尔的过渡更平缓，圆弧在拐点处更「急」，肉眼能看出区别。</li>
     * </ul>
     * 早期这里用 {@code drawRoundRect(…, PICKER_CORNER, PICKER_CORNER)} 画 1px 圆弧：描边偏淡
     * （浅色主题实测平台框边框 215,217,222 vs 自绘框 227,228,231）、圆角观感偏硬。
     *
     * <p>不直接调 {@code paintOutlineBorder}：它要求传 {@code Outline} 枚举，而该枚举只有
     * error/warning/defaultButton/focus 四档（javap 实证），没有「普通态」成员。</p>
     */
    static void paintPickerFieldSurface(javax.swing.JComponent target, Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int bw = DarculaUIUtil.BW.get();
            float r = JBUIScale.scale(7f);
            float w = (float) target.getWidth();
            float h = (float) target.getHeight();
            // 底色：圆角矩形（填充用同一贝塞尔轮廓，保证内外一致）
            g2.setColor(UIUtil.getTextFieldBackground());
            g2.fill(roundedOutline(0f, 0f, w - 1f, h - 1f, r));
            // 描边：平台同款 2px 宽 + Component.borderColor
            g2.setColor(JBColor.namedColor("Component.borderColor", JBColor.border()));
            java.awt.Stroke saved = g2.getStroke();
            g2.setStroke(new java.awt.BasicStroke((float) bw));
            float inset = bw / 2f;
            g2.draw(roundedOutline(inset, inset, w - 1f - inset * 2, h - 1f - inset * 2, r));
            g2.setStroke(saved);
        } finally {
            g2.dispose();
        }
    }

    /**
     * 圆角矩形轮廓（二次贝塞尔四角，对齐平台 {@code DarculaUIUtil.doPaint} 的 {@code Path2D} 构造）。
     *
     * <p>平台在角上各放一个 {@code quadTo}（控制点=角点），等价于「圆角半径 r 的四分之一圆弧」，
     * 但曲率过渡比 {@code RoundRectangle2D} 的椭圆弧更平缓——这是两个框观感差异的根因。</p>
     */
    private static java.awt.Shape roundedOutline(float x, float y, float w, float h, float r) {
        java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
        float x2 = x + w;
        float y2 = y + h;
        p.moveTo(x + r, y);
        p.lineTo(x2 - r, y);
        p.quadTo(x2, y, x2, y + r);
        p.lineTo(x2, y2 - r);
        p.quadTo(x2, y2, x2 - r, y2);
        p.lineTo(x + r, y2);
        p.quadTo(x, y2, x, y2 - r);
        p.lineTo(x, y + r);
        p.quadTo(x, y, x + r, y);
        p.closePath();
        return p;
    }

    private PluginSettings.State settingsState() {
        return PluginSettings.getInstance().getState();
    }

    /**
     * 形态下拉选项（值与设置页同源；label 复用 settings.reverseWrapperFormat.* keys）。
     * 必须为 data class（JDK17 JComboBox 按 equals 匹配，M19 实证）。
     */
    private record FormatOption(String persisted, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private FormatOption[] formatOptions() {
        return new FormatOption[]{
                new FormatOption(ReverseWrapperFormat.AUTO.getPersistedValue(),
                        MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.auto")),
                new FormatOption(ReverseWrapperFormat.QUERY.getPersistedValue(),
                        MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.query")),
                new FormatOption(ReverseWrapperFormat.LAMBDA.getPersistedValue(),
                        MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.lambda")),
                new FormatOption(ReverseWrapperFormat.WRAPPERS.getPersistedValue(),
                        MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.wrappers")),
                new FormatOption(ReverseWrapperFormat.MPJ_LAMBDA.getPersistedValue(),
                        MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.mpjLambda")),
        };
    }

    /** 从设置刷新下拉选中（面板创建 / tab 重选时调用；不触发重生成）。 */
    public void refreshFormatFromSettings() {
        String current = settingsState().getReverseWrapperFormat();
        FormatOption option = null;
        for (FormatOption o : formatOptions()) {
            if (o.persisted().equals(current)) {
                option = o;
                break;
            }
        }
        if (option == null) option = formatOptions()[0];
        FormatOption sel = (FormatOption) formatCombo.getSelectedItem();
        if (sel == null || !sel.persisted().equals(option.persisted())) {
            invalidateRequests();
            suppressFormatSync = true;
            try {
                formatCombo.setSelectedItem(option);
            } finally {
                suppressFormatSync = false;
            }
        }
    }

    /** 测试访问器：当前形态下拉选中值。 */
    public String selectedFormatForTest() {
        FormatOption sel = (FormatOption) formatCombo.getSelectedItem();
        return sel != null ? sel.persisted() : "";
    }

    /** 测试访问器：形态下拉组件（布局断言——选项行 GridBag 拉伸）。 */
    public JComboBox<?> formatComboForTest() {
        return formatCombo;
    }

    /** 测试访问器：模拟用户下拉选择（触发写穿 + 重生成完整链路）。 */
    public void selectFormatForTest(String persisted) {
        FormatOption target = null;
        for (FormatOption o : formatOptions()) {
            if (o.persisted().equals(persisted)) {
                target = o;
                break;
            }
        }
        formatCombo.setSelectedItem(target);
    }

    /** 测试访问器：调用类选择字段。 */
    public ClassPickerField callerFieldForTest() {
        return callerField;
    }

    /** T-M44 增量测试缝：选择器模型（ChooseByNameModel 接口——getFullName/getElementName 的 ReadAction 委托行为测试）。 */
    public ChooseByNameModel callerModelForTest() {
        return callerField.callerModel;
    }

    /** T-MapResultVo v5 测试缝：返回类型「选择类」弹框模型（固定候选前插断言）。 */
    public ChooseByNameModel returnTypeModelForTest() {
        return voField.typeModel;
    }

    /** T-MapResultVo v5 测试缝：选中映射（字符串固定项/类元素 → 返回声明文本）。 */
    public void applyReturnTypeChosenForTest(Object element) {
        voField.applyChosen(element);
    }

    /** T-Mxx 测试缝：设置当前编辑器打开的类（供 this/baseMapper tooltip 上下文测试）。 */
    public void setCurrentEditorClassForTest(PsiClass cls) {
        callerField.getCallerModelConcrete().setCurrentEditorClass(cls);
    }

    /** T-M44 增量测试缝：委托调用类选择器的 ReadAction 选取（elementChosen 的 PsiClass 分支同一路径）。 */
    public void pickPsiClassForTest(PsiClass cls) {
        callerField.pickPsiClassForTest(cls);
    }

    /** 测试访问器：注入调用类 MPJ 能力（真实解析依赖索引，Light 环境不可测——见 InvocationMpjHeavyTest）。 */
    public void setCallerMpjForTest(boolean value) {
        callerMpj = value;
    }

    /** 测试访问器：输入区空态提示行（断言灰字文案与空态显隐）。 */
    public JBLabel inputHintLabelForTest() {
        return inputHintLabel;
    }

    /** 测试访问器：结果区空态提示行。 */
    public JBLabel resultHintLabelForTest() {
        return resultHintLabel;
    }

    /** 测试访问器：类选择字段可点击子组件（断言整块可点——监听挂到每个子组件）。 */
    public List<JComponent> pickerClickTargetsForTest() {
        return callerField.clickTargetsForTest();
    }

    /** 测试访问器：类选择字段清除按钮（断言 12×12 紧凑尺寸）。 */
    public JButton pickerActionButtonForTest() {
        return callerField.actionButtonForTest();
    }

    /** 测试访问器：返回 VO 选择字段。 */
    public ReturnTypeField voFieldForTest() {
        return voField;
    }

    /** 状态行（错误红字 / 提示主题色）。 */
    private void status(String message, boolean error) {
        if (error) {
            // IDE 标准错误前景命名色（亮暗主题自适应；243 HighlighterColors 无 ERROR 常量）
            statusLabel.setForeground(JBColor.namedColor("ErrorForeground", JBColor.RED));
        } else {
            statusLabel.setForeground(EditorColorsManager.getInstance().getGlobalScheme()
                    .getAttributes(HighlighterColors.TEXT).getForegroundColor());
        }
        statusLabel.setText(message);
    }

    // ------------------------------------------------------------------
    // Java 结果编辑器（可编辑 + 词法高亮）
    // ------------------------------------------------------------------

    private Editor createJavaEditor() {
        Editor editor = EditorFactory.getInstance()
                .createEditor(EditorFactory.getInstance().createDocument(""), project);
        EditorEx editorEx = (EditorEx) editor;
        editorEx.setHighlighter(new LexerEditorHighlighter(
                JavaSyntaxHighlighterFactory.getSyntaxHighlighter(JavaLanguage.INSTANCE, project, null),
                EditorColorsManager.getInstance().getGlobalScheme()));
        editorEx.getSettings().setLineNumbersShown(true);
        editorEx.getSettings().setFoldingOutlineShown(true);
        editorEx.getSettings().setIndentGuidesShown(false);
        editorEx.getSettings().setAdditionalPageAtBottom(false);
        // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
        cn.song.mybatisplus.visualizer.ui.EditorWhitespaceSettings.applyTo(editorEx);
        // 可编辑（用户需求：改实体类名/参数后复制）；不回写 SQL（单向编辑边界）
        return editor;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        invalidateRequests(true);
        inputEditor.dispose();
        EditorFactory.getInstance().releaseEditor(resultEditor);
    }

    // ------------------------------------------------------------------
    // internal 测试访问器（仿 SqlPreviewPanel.panelForTest 约定）
    // ------------------------------------------------------------------

    /** 测试访问器：触发转换（走完整流程，含异步实体查找路径）。 */
    public void convertForTest() {
        convert();
    }

    /**
     * T-FixEntityAmbiguity 测试缝：直调多候选分发 {@link #render}（private，且多候选分支仅存在于
     * BGT 查找后的 EDT 回调——Light 索引不可用无法真实实体夹具，见 M27 实证登记）。
     * 只做转发，不改变生产语义——供「开关关闭静默取首 + 状态栏明示候选」分支的面板级断言使用。
     */
    public void renderForTest(
            SqlReverseResult result,
            List<SqlToJavaConverter.EntityCandidate> candidates,
            ReverseWrapperFormat format) {
        render(result, candidates, format, Collections.emptyMap());
    }

    /** 测试访问器：结果区文本。 */
    public String resultTextForTest() {
        return resultEditor.getDocument().getText();
    }

    /**
     * T-FS-05 测试缝：直调结果区写入口 setResultText（private，无法从测试触达）。
     * 只做转发，不改变生产语义——供「写不安全转投递 / 写安全就地写」两条行为断言使用。
     */
    public void setResultTextForTest(String text) {
        setResultText(text);
    }

    /** 测试访问器：将复制到剪贴板的文本（T-M45：剥离注释后的纯 Java 代码）。 */
    public String copyTextForTest() {
        return copyText();
    }

    /** 测试访问器：触发结果区格式化（对齐用户点击「格式化代码」按钮）。 */
    public void formatCodeForTest() {
        formatResult();
    }

    /** 测试访问器：状态行文本。 */
    public String statusForTest() {
        return statusLabel.getText();
    }

    /** 测试访问器：输入区组件（setSql/text 为公开 API）。 */
    public SqlInputEditor inputEditorForTest() {
        return inputEditor;
    }
}
