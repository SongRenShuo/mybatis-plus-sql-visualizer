// T-SyncK2JMaster180 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/standalone/SqlStandalonePreviewPanel.kt 移植
package cn.song.mybatisplus.visualizer.ui.standalone;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.ui.SqlInputEditor;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelExecutionKt;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelResultViewKt;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立 SQL 预览工作台（T-SqlPreviewStandalone D-2）：不依赖语句入口，用户粘贴一段
 * SQL 或 mapper XML 片段，走 {@link StandaloneSqlSourceResolver} 识别后喂内嵌 {@link SqlPreviewPanel}
 * 展示——格式化、参数填值、复制可执行 SQL、动态条件勾选（XML 路径）与执行全部复用
 * 既有面板能力，本类只做装配。
 *
 * <p>布局（Splitter 上下分栏，对齐 SqlToJavaPanel 先例）：</p>
 * <ul>
 *  <li>上：SQL 输入区（{@link SqlInputEditor}：SQL 高亮 + 打字自动弹补全）；工具栏：解析预览 / 格式化 / 清空 / 在弹框中打开；</li>
 *  <li>下：预览区（内嵌 {@link SqlPreviewPanel}，懒创建——首次解析成功才建实例，避免常驻双 Editor 内存代价）；</li>
 *  <li>底部：状态行（识别结果如实呈现：XML 语句块 / 纯 SQL / 未识别 / 过长）。</li>
 * </ul>
 *
 * <p>线程纪律：解析是纯文本函数（project=null 链零 PSI 读），派后台线程；结果经代次守卫
 * 回 EDT 喂面板（对齐 SqlToJavaPanel 的 ConversionRequest 代次模式）。</p>
 */
public class SqlStandalonePreviewPanel extends JPanel implements Disposable {

    private final Project project;

    // 构造器内赋值：字段初始化器先于构造体执行，彼时 project 尚未绑定（master 主构造参数在
    // Kotlin 属性初始化器里可用，Java 侧必须落构造器体）。
    private final SqlInputEditor inputEditor;

    private final JBLabel statusLabel = paddedLabel(" ");

    /** 输入区空文档灰字提示（用户交互引导；有内容自动隐藏）。 */
    private final JBLabel inputHintLabel = hintLabel(MpSqlVisualizerBundle.message("standalone.hint.input"));

    /** 预览区空态灰字提示（首次解析成功前显示）。 */
    private final JBLabel previewHintLabel = hintLabel(MpSqlVisualizerBundle.message("standalone.hint.preview"));

    /** 预览区宿主：懒创建前显示灰字提示，解析成功后替换为 {@link SqlPreviewPanel}。 */
    private final JPanel previewHolder = new JPanel(new BorderLayout());

    /**
     * T-IncludePlaceholder A 券：用户补齐的 {@code <sql id>} 片段（id → 片段内容）。
     *
     * <p>与输入区分开存：用户输入永远保持原样（贴什么显示什么），补齐内容只在解析前拼到前面——
     * 这样「补齐」不污染用户手里那段文本，也不会因为再次编辑输入而丢失。</p>
     */
    private final Map<String, String> fragmentOverrides = new LinkedHashMap<>();

    /** 缺失片段提示行（有缺失才可见；点击进入补齐输入）。 */
    private final JBLabel missingFragmentLabel = createMissingFragmentLabel();

    /** 内嵌预览面板（懒创建；Disposer 挂本面板，防双 Editor 常驻）。volatile：ExecuteAction 的 update 在 BGT 读（面板内执行入口同口径先例）。 */
    private volatile SqlPreviewPanel previewPanel;

    /** Disposer 防重注册守卫（对齐 SqlLogPanel.historyPanelRegistered 先例）。 */
    private boolean previewPanelRegistered = false;

    /** 代次守卫：晚到的后台解析结果触碰已过期/已清空的 UI（对齐 SqlToJavaPanel invalidateRequests）。 */
    private long resolveGeneration = 0L;

    private boolean disposed = false;

    /** 解析预览动作实例（字段化：构造期就要注册组件级快捷键，不能每次建工具栏都新建一个）。 */
    private final ResolveAction resolveAction = new ResolveAction();

    /** 执行 SQL 动作实例（字段化同 {@code resolveAction} 先例；enable 随内嵌面板存亡翻转）。 */
    private final ExecuteAction executeAction = new ExecuteAction();

    private static JBLabel paddedLabel(String text) {
        JBLabel label = new JBLabel(text);
        label.setBorder(JBUI.Borders.empty(2, 6, 2, 6));
        return label;
    }

    private static JBLabel hintLabel(String text) {
        JBLabel label = paddedLabel(text);
        label.setForeground(UIUtil.getInactiveTextColor());
        return label;
    }

    private JBLabel createMissingFragmentLabel() {
        JBLabel label = paddedLabel(" ");
        label.setForeground(JBUI.CurrentTheme.Link.Foreground.ENABLED);
        label.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        label.setVisible(false);
        label.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                promptMissingFragment();
            }
        });
        return label;
    }

    public SqlStandalonePreviewPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.inputEditor = new SqlInputEditor(project, "");
        Disposer.register(this, inputEditor);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.add(buildInputToolbar(), BorderLayout.NORTH);
        inputPanel.add(inputHintLabel, BorderLayout.SOUTH);
        inputPanel.add(inputEditor.getComponent(), BorderLayout.CENTER);

        previewHolder.add(previewHintLabel, BorderLayout.NORTH);
        Splitter splitter = new Splitter(true, 0.4f);
        splitter.setFirstComponent(inputPanel);
        splitter.setSecondComponent(previewHolder);
        splitter.setShowDividerControls(false);
        add(splitter, BorderLayout.CENTER);
        // T-IncludePlaceholder：底部两行——缺失片段提示（有缺失才显示）+ 识别状态行
        JPanel southHost = new JPanel(new BorderLayout());
        southHost.add(missingFragmentLabel, BorderLayout.NORTH);
        southHost.add(statusLabel, BorderLayout.SOUTH);
        add(southHost, BorderLayout.SOUTH);

        // T-StandaloneHintAndWarning：Alt+Shift+Q 在工作台里接「解析预览」。
        // 为什么必须接管：该键全局绑 ConvertToSqlAction，而它只在 Java/XML/kt 文件上 enabled
        //（actions/ConvertToSqlAction 的 update）——工作台输入是 input.sql，动作恒 disabled，
        // 按键被丢弃、界面零反馈，而预览区空态提示恰恰承诺了「按 Alt+Shift+Q」。组件级 custom
        // shortcut 命中即消费按键（同 SqlPreviewPanel 格式化键 T-M26.6 的既有手法）。
        resolveAction.registerCustomShortcutSet(new CustomShortcutSet(reparseShortcuts()), this);

        // 空态提示显隐跟随文档内容
        inputEditor.getEditor().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                inputHintLabel.setVisible(inputEditor.getText().isBlank());
            }
        }, this);
    }

    // ------------------------------------------------------------------
    // 工具栏
    // ------------------------------------------------------------------

    private JPanel buildInputToolbar() {
        DefaultActionGroup group = new DefaultActionGroup(
            resolveAction,
            executeAction,
            new FormatAction(),
            new ClearAction(),
            new OpenInDialogAction());
        ActionToolbar toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID, group, true);
        toolbar.setTargetComponent(this);
        JPanel host = new JPanel(new BorderLayout());
        host.add(toolbar.getComponent(), BorderLayout.WEST);
        return host;
    }

    /** 解析预览：后台跑双源解析器，代次守卫回 EDT 喂内嵌面板。 */
    private class ResolveAction extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.Convert);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("standalone.action.resolve"));
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            resolveFromInput();
        }
    }

    /**
     * 执行 SQL（D233/T-StandaloneExecPhase2）：把内嵌预览面板的产物送既有执行链——
     * Schema 校验 / DML 确认 / {@code ${}} raw 确认门 / 未知参数确认门 / 方言闸 / 控制台内嵌
     * 全部复用 {@code executeSql}（SqlPreviewPanelExecution 的 internal 扩展），本动作只做
     * 入口与 enable 判定，不另设闸门（弹窗克制红线：模态确认只留给执行链既有形态）。
     *
     * <p>enable 判定＝内嵌面板已创建（懒创建只在解析成功路径发生——未识别文本+开弹窗不建面板）
     * 且未被清空；面板无 SQL 文本时 {@code executeSql} 静默 return（M36 既有口径），点不出事故。</p>
     *
     * <p><b>{@code public} 而非 {@code private}</b>（master 为 {@code internal}）：回归用例必须走真实动作路径
     * （对齐 {@link OpenInDialogAction} 的既有口径——只测面板方法会漏掉接线缺陷）。</p>
     */
    public class ExecuteAction extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.Execute);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("standalone.action.execute"));
            e.getPresentation().setDescription(MpSqlVisualizerBundle.message("standalone.action.execute.description"));
            e.getPresentation().setEnabled(previewPanel != null);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            SqlPreviewPanel panel = previewPanel;
            if (panel == null) {
                // 竞态兜底（update 拦可见性后仍到不了这里）：给引导不裸跑
                setStatus(MpSqlVisualizerBundle.message("standalone.status.execute.noPreview"));
                return;
            }
            SqlPreviewPanelExecutionKt.executeSql(panel);
        }
    }

    /** 格式化输入区 SQL（SqlFormatter 幂等；程序性写入不触发补全，对齐 SqlToJavaPanel.FormatAction）。 */
    private class FormatAction extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.Format);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("standalone.action.format"));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            String text = inputEditor.getText();
            if (text.isBlank()) {
                return;
            }
            inputEditor.setSql(SqlFormatter.format(text));
        }
    }

    /** 清空：作废在途解析、清输入、预览区回到空态提示（释放内嵌面板，下轮解析重建）。<b>{@code public}</b>（master {@code internal}）：回归用例走真实动作路径（同 {@link OpenInDialogAction} 口径）。 */
    public class ClearAction extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.Clear);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("standalone.action.clear"));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            clear();
        }
    }

    /**
     * 在弹框中打开：同一解析链产出 → 既有 modeless 弹窗（与三条语句入口同构）。
     *
     * <p><b>{@code public} 而非 {@code private}</b>（master {@code internal}）：回归用例必须走这条真实动作路径——
     * 2026-11-18 缺陷就出在「动作先置标志位、再调无参方法被默认值抹掉」，只测面板方法会漏掉。</p>
     */
    public class OpenInDialogAction extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.DockedPreview);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("standalone.action.openDialog"));
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            resolveFromInput(true);
        }
    }

    // ------------------------------------------------------------------
    // 解析与展示
    // ------------------------------------------------------------------

    /**
     * 解析当前输入并展示（内部入口）。流程：EDT 取文本快照 → 后台纯文本解析 →
     * 代次守卫回 EDT 装配。
     *
     * <p><b>{@code openDialog} 只存在于本条调用链</b>（捕获进后台闭包、随代次守卫回传），不再用字段传递——
     * 2026-11-18 缺陷：原实现用 {@code openDialogPending} 字段，动作先置真再调无参 {@code resolveFromInput()}，
     * 被形参默认值 {@code false} 就地抹掉，导致「在弹框中打开」永不弹窗。字段形态跨调用、语义靠调用
     * 顺序维持，禁止回归。</p>
     *
     * <p>空输入与未识别文本同样可以开弹窗（用户诉求：这个按钮永远能开）——空输入给空编辑器，
     * 未识别给原文 + 信息级说明，用户可直接在弹窗内粘贴/编辑。</p>
     */
    public void resolveFromInput() {
        resolveFromInput(false);
    }

    public void resolveFromInput(boolean openDialog) {
        String rawInput = inputEditor.getText();
        if (rawInput.isBlank()) {
            if (openDialog) {
                setStatus(MpSqlVisualizerBundle.message("standalone.status.emptyOpenedDialog"));
                openDialogWith(StandaloneSqlSourceResolver.emptyPreviewResult(), true);
            } else {
                setStatus(MpSqlVisualizerBundle.message("standalone.status.empty"));
            }
            return;
        }
        if (rawInput.length() > StandaloneSqlSourceResolver.MAX_TEXT_LENGTH) {
            setStatus(MpSqlVisualizerBundle.message("standalone.status.tooLong"));
            return;
        }
        // T-IncludePlaceholder A 券：补齐的片段只在解析前拼入，用户输入保持原样
        String text = MissingSqlFragmentSupport.withFragments(rawInput, fragmentOverrides);
        List<String> missing = MissingSqlFragmentSupport.missingRefids(rawInput);
        long generation = ++resolveGeneration;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            StandaloneSqlSourceResolver.Resolved resolved = resolveQuietly(text);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (disposed || generation != resolveGeneration || project.isDisposed()) {
                    return;
                }
                applyResolved(resolved, openDialog, rawInput, missing);
            }, ModalityState.nonModal());
        });
    }

    /** runCatching 降级 → try/catch（fatal 透传，其余吞为 null，master 同点口径）。 */
    private static StandaloneSqlSourceResolver.Resolved resolveQuietly(String text) {
        try {
            return StandaloneSqlSourceResolver.resolve(text);
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            return null;
        }
    }

    private void applyResolved(StandaloneSqlSourceResolver.Resolved resolved,
                               boolean openDialog,
                               String rawText) {
        applyResolved(resolved, openDialog, rawText, List.of());
    }

    private void applyResolved(StandaloneSqlSourceResolver.Resolved resolved,
                               boolean openDialog,
                               String rawText,
                               List<String> missingRefids) {
        updateMissingFragmentHint(missingRefids);
        if (resolved == null) {
            setStatus(MpSqlVisualizerBundle.message("standalone.status.unrecognized"));
            if (openDialog) {
                openDialogWith(rawTextResult(rawText), true);
            }
            return;
        }
        // 两条解析链都没还原：warning 换成结构化四段（标题｜来源｜语句｜原因｜建议），
        // 面板横幅与「复制错误信息」都读这一份——用户粘贴出去的才是可定位的完整信息。
        PreviewResult result = resolved.failure() != null
            ? resolved.result().withWarning(buildFailureWarning(resolved.failure()), false)
            : resolved.result();
        SqlPreviewPanelResultViewKt.showResult(ensurePreviewPanel(), result);
        previewHintLabel.setVisible(false);
        setStatus(statusTextOf(resolved));
        if (openDialog) {
            openDialogWith(result);
        }
    }

    /** T-IncludePlaceholder A 券：更新「缺失片段」提示行（无缺失即隐藏）。 */
    private void updateMissingFragmentHint(List<String> missing) {
        if (missing.isEmpty()) {
            missingFragmentLabel.setVisible(false);
            return;
        }
        missingFragmentLabel.setText(MpSqlVisualizerBundle.message(
            "standalone.missingFragment.label",
            String.join("、", missing)));
        missingFragmentLabel.setVisible(true);
    }

    /**
     * T-IncludePlaceholder A 券：点提示行 → 让用户补齐首个缺失片段，补齐后立即重解析。
     *
     * <p>为什么逐个补：一个片段可能引用另一个缺失片段，一次只问一个才不会让用户面对一个
     * 不知道从哪粘起的空框；补完重解析会重新算缺失集合，链路自然收敛。</p>
     */
    private void promptMissingFragment() {
        List<String> missing = MissingSqlFragmentSupport.missingRefids(inputEditor.getText());
        if (missing.isEmpty()) {
            return;
        }
        String refid = missing.get(0);
        MissingFragmentDialog dialog = new MissingFragmentDialog(project, refid);
        if (!dialog.showAndGet()) {
            return;
        }
        String content = dialog.content();
        if (content == null || content.isBlank()) {
            return;
        }
        fragmentOverrides.put(refid, content);
        resolveFromInput();
    }

    /**
     * 走既有 modeless 弹窗（{@code SqlPreviewService} 单例关旧开新）。
     *
     * @param autoReparse 「先开弹窗、后贴内容」场景置 true：弹窗挂防抖监听，用户往空编辑器里
     *   贴入 SQL / mapper 片段后自动重新识别一次（命中即交还手动，不干扰后续编辑）。
     */
    private void openDialogWith(PreviewResult result) {
        openDialogWith(result, false);
    }

    private void openDialogWith(PreviewResult result, boolean autoReparse) {
        if (project.isDisposed()) {
            return;
        }
        SqlPreviewService.showResult(project, result, autoReparse);
    }

    /**
     * 未识别文本的弹窗载荷：原文照搬 + 信息级说明（UI-001 的 {@code informationalWarning}，
     * 面板用 ℹ 前缀，不冒充渲染失败）。
     */
    private PreviewResult rawTextResult(String text) {
        return new PreviewResult(text, text, List.of(), null, null, null, List.of())
            .withWarning(StandaloneWarningText.rawTextNotice(), true);
    }

    /** 结构化失败文案（单行、{@code ｜} 分段）——与弹窗同源，见 {@link StandaloneWarningText}。 */
    private String buildFailureWarning(StandaloneSqlSourceResolver.Failure failure) {
        return StandaloneWarningText.failure(failure);
    }

    /** 识别结果如实呈现（状态条教会用户「插件认成了什么」，含 MyBatis / iBatis 哪条链）。 */
    private String statusTextOf(StandaloneSqlSourceResolver.Resolved resolved) {
        if (resolved.kind() == StandaloneSqlSourceResolver.Kind.XML_STATEMENT) {
            String id = resolved.statementId() != null ? resolved.statementId() : "?";
            boolean iBatis = resolved.flavor() == XmlFlavorDetector.Flavor.IBATIS;
            if (resolved.statementCount() > 1) {
                return MpSqlVisualizerBundle.message(
                    iBatis ? "standalone.status.ibatis.multi" : "standalone.status.xml.multi",
                    id, resolved.statementCount());
            }
            return iBatis
                ? MpSqlVisualizerBundle.message("standalone.status.ibatis", id)
                : MpSqlVisualizerBundle.message("standalone.status.xml", id);
        }
        return MpSqlVisualizerBundle.message("standalone.status.plain", resolved.paramCount());
    }

    /** 懒创建内嵌面板（防重注册守卫 + 预览区替换空态提示）。 */
    private SqlPreviewPanel ensurePreviewPanel() {
        SqlPreviewPanel existing = previewPanel;
        if (existing != null) {
            previewHolder.add(existing, BorderLayout.CENTER);
            previewHolder.revalidate();
            previewHolder.repaint();
            return existing;
        }
        // T-StandaloneHintAndWarning：工作台专用空态提示——原文案的「选中代码」与 Alt+Shift+Q
        // 是编辑器流的语义，搬过来是假承诺（本类已接管该键，但「选中代码」在工作台不成立）。
        SqlPreviewPanel panel = new SqlPreviewPanel(
            project,
            MpSqlVisualizerBundle.message("standalone.preview.emptyHint"));
        if (!previewPanelRegistered) {
            Disposer.register(this, panel);
            previewPanelRegistered = true;
        }
        previewHolder.removeAll();
        previewHolder.add(panel, BorderLayout.CENTER);
        previewHolder.revalidate();
        previewHolder.repaint();
        previewPanel = panel;
        return panel;
    }

    private void clear() {
        resolveGeneration++;
        fragmentOverrides.clear();
        missingFragmentLabel.setVisible(false);
        inputEditor.setSql("");
        SqlPreviewPanel panel = previewPanel;
        if (panel != null) {
            previewHolder.removeAll();
            previewHolder.add(previewHintLabel, BorderLayout.NORTH);
            previewHintLabel.setVisible(true);
            previewHolder.revalidate();
            previewHolder.repaint();
            Disposer.dispose(panel);
        }
        previewPanel = null;
        previewPanelRegistered = false;
        setStatus(" ");
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    // ------------------------------------------------------------------
    // 面板外通道（SqlLogPanel / action 桥接）
    // ------------------------------------------------------------------

    /** 工具窗页签选中后聚焦输入区；prefillText 非 null 时先程序性预填再解析（D-4 剪贴板候选）。 */
    public void focusAndMaybeResolve(@Nullable String prefillText) {
        if (prefillText != null) {
            inputEditor.setSql(prefillText);
        }
        inputEditor.getEditor().getContentComponent().requestFocusInWindow();
        if (prefillText != null && !prefillText.isBlank()) {
            resolveFromInput();
        }
    }

    /** 测试缝：程序性写入输入区（Light 用例驱动识别/弹窗链，不依赖真实键入）。 */
    public void setInputTextForTest(String text) {
        inputEditor.setSql(text);
    }

    /** 测试缝：内嵌预览面板当前生效的空态提示（证工作台专用文案真的接上了）。 */
    public String embeddedPreviewHintForTest() {
        return ensurePreviewPanel().previewEmptyHintForTest();
    }

    /** 测试缝：ExecuteAction 的 enable 判定现值（<b>不触发懒创建</b>——与 embeddedPreviewHintForTest 不同，读的是裸字段）。 */
    public boolean executeActionEnabledForTest() {
        return previewPanel != null;
    }

    /** 测试缝：状态行现值（无面板时点执行的引导文案断言）。 */
    public String statusTextForTest() {
        return statusLabel.getText();
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        resolveGeneration++;
        // 显式释放子件（对齐 SqlToJavaPanel.dispose 的既有口径；guard 幂等防双释放）
        inputEditor.dispose();
        SqlPreviewPanel panel = previewPanel;
        if (panel != null) {
            Disposer.dispose(panel);
        }
        previewPanel = null;
        previewPanelRegistered = false;
    }

    private static final String TOOLBAR_ID = "SqlStandalonePreviewPanelToolbar";

    /**
     * 重新解析快捷键：与 plugin.xml 里 ConvertToSql 的双 keymap 登记同源（Win/Linux {@code alt shift Q}，
     * macOS {@code meta shift Q}）——用户是在编辑器里学会这个键的，工作台沿用同一手感。
     */
    public static Shortcut[] reparseShortcuts() {
        if (SystemInfo.isMac) {
            return new Shortcut[]{new KeyboardShortcut(KeyStroke.getKeyStroke("meta shift Q"), null)};
        }
        return new Shortcut[]{new KeyboardShortcut(KeyStroke.getKeyStroke("alt shift Q"), null)};
    }
}
