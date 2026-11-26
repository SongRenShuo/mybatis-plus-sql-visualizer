// T-SyncK2JMaster180 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/standalone/SqlStandalonePreviewPanel.kt 移植
package cn.song.mybatisplus.visualizer.ui.standalone;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.ui.SqlInputEditor;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelExecutionKt;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelResultViewKt;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelSqlViewKt;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

    /**
     * 补齐内容来源（T-PreviewActionRow D-6）：默认弹 {@link MissingFragmentDialog}，测试可注入。
     *
     * <p>与 {@link MissingFragmentFillFlow#promptViaDialog} 同一实现——注入点只在「怎么问用户」，
     * 「记进 overrides + 重解析」仍走真实链路。</p>
     *
     * <p>K2J 注：<b>不得</b>写成字段初始化器 {@code = refid -> …(project, refid)}——lambda 引用构造期
     * 才赋值的 blank final {@code project} 会撞 javac 明确赋值分析；改在构造器体内赋值。</p>
     */
    @NotNull
    public Function<String, String> missingFragmentPrompt;

    /** 内嵌预览面板（懒创建；Disposer 挂本面板，防双 Editor 常驻）。volatile：ExecuteAction 的 update 在 BGT 读（面板内执行入口同口径先例）。 */
    private volatile SqlPreviewPanel previewPanel;

    /** Disposer 防重注册守卫（对齐 SqlLogPanel.historyPanelRegistered 先例）。 */
    private boolean previewPanelRegistered = false;

    /** 代次守卫：晚到的后台解析结果触碰已过期/已清空的 UI（对齐 SqlToJavaPanel invalidateRequests）。 */
    private long resolveGeneration = 0L;

    /**
     * 当前输入来自哪个文件（T-LoadFilePlacement D-2）：非 null 时状态行带「已从 X 加载」，
     * 用户再编辑输入 / 清空 / 换文件即失效。
     *
     * <p>为什么记状态而不是只弹一次提示：解析是异步的，加载后状态行会立刻被识别结果覆盖——
     * 不挂住来源的话，用户看不到「我现在看的是文件里的内容」。</p>
     */
    @Nullable
    private String loadedFileName = null;

    /**
     * 测试缝：替代真实文件选择框的「选文件」通道（headless 下不能弹 {@code FileChooser}）。
     *
     * <p>生产路径为 null → 走 {@link MapperFileLoader#chooseFile}（EDT 选文件）；用例注入一个真实
     * {@code VirtualFile} 后驱动 {@link LoadFileAction} 的完整动作路径——选文件与读文件由此分离：
     * 本缝只决定「选了哪个文件」，读文本仍走 {@link #loadTextInvoker}。</p>
     */
    @Nullable
    public java.util.function.Supplier<VirtualFile> fileChooserForTest = null;

    /**
     * 测试缝：把「读文件文本」这一原语换成可观察的替身（校验读发生在后台线程 / 不该被读时抛错）。
     *
     * <p>生产默认 {@link MapperFileLoader#loadText}——读权限 IO，由 {@link LoadFileAction} 派到后台线程执行。</p>
     */
    @NotNull
    public Function<VirtualFile, String> loadTextInvoker = MapperFileLoader::loadText;

    /** 载入文件的代次守卫：晚到的后台读取结果不得触碰已清空/已释放/已被新一次加载取代的 UI。 */
    private long loadFileGeneration = 0L;

    private boolean disposed = false;

    /** 解析预览动作实例（字段化：构造期就要注册组件级快捷键，不能每次建工具栏都新建一个）。 */
    private final ResolveAction resolveAction = new ResolveAction();

    /** 执行 SQL 动作实例（字段化同 {@code resolveAction} 先例；enable 随内嵌面板存亡翻转）。 */
    private final ExecuteAction executeAction = new ExecuteAction();

    /** 复制 SQL（T-PreviewActionRow D-1）：页签里唯一的「取走产物」入口。 */
    private final CopySqlAction copySqlAction = new CopySqlAction();

    private final ClearAction clearAction = new ClearAction();

    private final OpenInDialogAction openInDialogAction = new OpenInDialogAction();

    /**
     * 顶部工具栏（图标）：解析 / 格式化 / 从文件加载 / 清空 / 在弹框中打开。
     *
     * <p>T-PreviewActionRow 二轮（用户 2026-11-20 反馈「清空与在弹框中打开这两个按钮需要保留」）：
     * 这两项回到顶部——它们是用户按位置记忆的「随时可用」操作；底部只留两个真正新增的产物出口
     * （执行 SQL / 复制 SQL），同一动作不再两处重复。</p>
     */
    private final List<AnAction> inputActions = createInputActions();

    /** 底部动作行（顺序即按钮顺序；enable 由 {@link #refreshBottomActions} 统一刷新）。 */
    private final List<AnAction> bottomActions = List.of(executeAction, copySqlAction);

    /** 底部按钮（与 {@link #bottomActions} 下标一一对应）。 */
    private final List<JButton> bottomButtons = new ArrayList<>();

    /** 底部主行动下标（顺序见 {@link #bottomActions}）：复制 SQL——与弹窗「复制 SQL 并关闭」同一取走口径。 */
    private static final int PRIMARY_BOTTOM_INDEX = 1;

    private List<AnAction> createInputActions() {
        List<AnAction> actions = new ArrayList<>();
        actions.add(resolveAction);
        actions.add(new FormatAction());
        // 读文件是**输入源**能力，只挂在有输入区的工作台上；通用预览弹窗
        //（22 个文件 / 53 处调用点共用、只展示已渲染 SQL）不再拥有它。
        actions.add(new LoadFileAction());
        actions.add(clearAction);
        actions.add(openInDialogAction);
        return List.copyOf(actions);
    }

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

    public SqlStandalonePreviewPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.inputEditor = new SqlInputEditor(project, "");
        this.missingFragmentPrompt = refid -> MissingFragmentFillFlow.promptViaDialog(project, refid);
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
        // T-PreviewActionRow：底部两行——产物动作行 + 识别状态行
        JPanel southHost = new JPanel(new BorderLayout());
        southHost.add(buildBottomActionRow(), BorderLayout.NORTH);
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
                // 输入一变，「来自某个文件」的溯源就不再成立（载入后本字段会立刻重新赋值）
                loadedFileName = null;
            }
        }, this);
    }

    // ------------------------------------------------------------------
    // 工具栏
    // ------------------------------------------------------------------

    private JPanel buildInputToolbar() {
        DefaultActionGroup group = new DefaultActionGroup(inputActions.toArray(new AnAction[0]));
        ActionToolbar toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID, group, true);
        toolbar.setTargetComponent(this);
        JPanel host = new JPanel(new BorderLayout());
        host.add(toolbar.getComponent(), BorderLayout.WEST);
        return host;
    }

    /**
     * 底部产物动作行（T-PreviewActionRow D-1）：文字按钮，右对齐。
     *
     * <p>为什么用手工 {@code JButton} 而不是 {@code ActionToolbar}：工具窗工具栏在图标模式下只画图标，
     * 要文字得逐按钮改 presentation（平台未提供稳定的「强制文字」入口）；而且这一排要和
     * 预览弹窗底部那排手感一致（弹窗侧也是 {@code JButton}）。</p>
     *
     * <p>文案与 enable 都来自 {@link #bottomActions} 的动作本体：先 {@code update(e)} 再读 presentation，
     * enable 判定只有一处（{@code ExecuteAction}/{@code CopySqlAction} 各看 {@code previewPanel != null}）。</p>
     */
    private JPanel buildBottomActionRow() {
        List<String> labels = List.of(
            "standalone.action.execute",
            "standalone.action.copySql");
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        row.setBorder(JBUI.Borders.empty(2, 6, 2, 6));
        for (int index = 0; index < bottomActions.size(); index++) {
            AnAction action = bottomActions.get(index);
            JButton button = new JButton(MpSqlVisualizerBundle.message(labels.get(index)));
            button.addActionListener(e -> action.actionPerformed(anActionEventOf(action)));
            // 主行动＝复制 SQL（与弹窗「复制 SQL 并关闭」同一取走口径；执行 SQL 次之）
            if (index == PRIMARY_BOTTOM_INDEX) {
                SqlPreviewPanelExecutionKt.applyPrimaryButtonStyle(button);
            }
            bottomButtons.add(button);
            row.add(button);
        }
        refreshBottomActions();
        return row;
    }

    /** 按动作自身的 {@code update} 结果刷新底部按钮的 enable（面板未建立时执行/复制禁用）。 */
    private void refreshBottomActions() {
        for (int index = 0; index < bottomButtons.size(); index++) {
            JButton button = bottomButtons.get(index);
            AnAction action = bottomActions.get(index);
            AnActionEvent event = anActionEventOf(action);
            action.update(event);
            button.setEnabled(event.getPresentation().isEnabled());
        }
    }

    private static AnActionEvent anActionEventOf(AnAction action) {
        return AnActionEvent.createFromAnAction(action, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT);
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

    /**
     * 复制 SQL（T-PreviewActionRow D-1）：页签里最重要的「取走」动作——此前<b>完全没有入口</b>，
     * 用户要么进弹窗点一次，要么在编辑器里全选 Ctrl+C（复制到的是带注释原文）。
     *
     * <p>走 {@code SqlPreviewPanelSqlViewKt.copySql}（与弹窗「复制 SQL」同一条链，含去注释），但<b>必须</b>
     * {@code offerClosePanelAction = false}：那条通知默认会挂「关闭 SQL 面板」动作，而
     * {@code onClosePanelRequested} 只有弹窗注入——页签里会挂一条点了没反应的死链接。</p>
     *
     * <p><b>{@code public}</b>：回归用例走真实动作路径（同 {@link ExecuteAction} 口径）。</p>
     */
    public class CopySqlAction extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("standalone.action.copySql"));
            e.getPresentation().setDescription(
                MpSqlVisualizerBundle.message("standalone.action.copySql.description"));
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
                setStatus(MpSqlVisualizerBundle.message("standalone.status.copy.noPreview"));
                return;
            }
            SqlPreviewPanelSqlViewKt.copySql(panel, false);
        }
    }

    /**
     * 从文件加载（T-LoadFilePlacement D-2）：选一个 mapper XML → 替换输入区 → 直接解析预览。
     *
     * <p>为什么在工作台而不在弹窗：弹窗是「看结果」的通用展示层（18 个 gutter 图标、日志、历史
     * 共 21 处入口），在里面换输入会静默改掉「这条 SQL 从哪来」的语义；工作台才有「取文本」这一层。</p>
     *
     * <p>线程（T-Perf33MergeAlign）：选文件在 EDT、读文本在后台、回填回 EDT；读前先按 {@code file.length}
     * 粗筛超大文件（UTF-8 4 字节/字符），避免整份读进内存。</p>
     *
     * <p><b>{@code public}</b>：回归用例必须走真实动作路径（同 {@link OpenInDialogAction} 口径）。</p>
     */
    public class LoadFileAction extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.MapperXml);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("standalone.action.loadFile"));
            e.getPresentation().setDescription(MpSqlVisualizerBundle.message("standalone.action.loadFile.description"));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            // 一、选文件（EDT）：FileChooser 是 UI 操作，测试缝可替换
            VirtualFile file;
            java.util.function.Supplier<VirtualFile> seam = fileChooserForTest;
            if (seam != null) {
                file = seam.get();
            } else {
                // parent 传本面板：选择器归属工具窗，不落到主窗口后面（T-PreviewInfoLayout 问题一）
                file = MapperFileLoader.chooseFile(
                    project,
                    "standalone.action.loadFile.title",
                    SqlStandalonePreviewPanel.this);
            }
            if (file == null) {
                return;
            }
            // 二、读前粗筛（EDT，零成本）：UTF-8 单字符最多 4 字节，字节数 > 4×字符上限
            //     ⇒ 字符数必超上限，直接拒绝——避免把极端大文件整份读进内存再被精确判据丢弃。
            //     （精确判据仍是 applyLoadedFile 里的字符数检查，这里只是保守前置拦截。）
            if (file.getLength() > StandaloneSqlSourceResolver.MAX_TEXT_LENGTH * 4L) {
                setStatus(MpSqlVisualizerBundle.message("standalone.status.loadedFile.tooLong"));
                return;
            }
            // 三、读文本（后台）：读权限 IO 不能压 EDT；代次守卫丢弃在途/过期的结果
            long generation = ++loadFileGeneration;
            Application app = ApplicationManager.getApplication();
            app.executeOnPooledThread(() -> {
                String text = loadTextInvoker.apply(file);
                String fileName = file.getName();
                app.invokeLater(() -> {
                    if (disposed || generation != loadFileGeneration || project.isDisposed()) {
                        return;
                    }
                    if (text != null && !text.isBlank()) {
                        applyLoadedFile(new MapperFileLoader.LoadedFile(fileName, text));
                    }
                }, ModalityState.nonModal());
            });
        }
    }

    /**
     * 把选中的文件内容换进输入区并解析（P-1/P-3 口径：替换不弹确认，但状态行写明来源；
     * 超长文件<b>不写入</b>，避免把编辑器撑爆）。
     */
    private void applyLoadedFile(MapperFileLoader.LoadedFile loaded) {
        if (loaded.text().length() > StandaloneSqlSourceResolver.MAX_TEXT_LENGTH) {
            setStatus(MpSqlVisualizerBundle.message("standalone.status.loadedFile.tooLong"));
            return;
        }
        boolean replacedExisting = !inputEditor.getText().isBlank();
        // 程序性写入走 setSql（抑制补全 autopopup）；文档监听会先把 loadedFileName 清掉，故在其后赋值
        inputEditor.setSql(loaded.text());
        inputHintLabel.setVisible(false);
        loadedFileName = loaded.fileName();
        setStatus(MpSqlVisualizerBundle.message(
            replacedExisting ? "standalone.status.loadedFile.replaced" : "standalone.status.loadedFile",
            loaded.fileName()));
        resolveFromInput();
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
        long generation = ++resolveGeneration;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            StandaloneSqlSourceResolver.Resolved resolved = resolveQuietly(text);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (disposed || generation != resolveGeneration || project.isDisposed()) {
                    return;
                }
                applyResolved(resolved, openDialog, rawInput);
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

    /**
     * 装配一次解析结果。
     *
     * <p>T-PreviewActionRow D-5：缺失片段事件<b>不再由这里算</b>——解析器已按原文把
     * {@code missingFragmentIds} 挂进载荷，面板据此在结果区渲染补齐入口（工作台只负责注入「怎么补」）。
     * 这样弹窗（同一面板）也能展示同一份事实，不必各自扫文本。</p>
     */
    private void applyResolved(StandaloneSqlSourceResolver.Resolved resolved,
                               boolean openDialog,
                               String rawText) {
        if (resolved == null) {
            setStatus(withOrigin(MpSqlVisualizerBundle.message("standalone.status.unrecognized")));
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
        setStatus(withOrigin(statusTextOf(resolved)));
        if (openDialog) {
            openDialogWith(result);
        }
    }

    /**
     * 状态行挂上「输入来源」（T-LoadFilePlacement）：文件载入后识别结果会覆盖状态行，
     * 不带上来源用户就分不清「这是文件里的内容还是我贴的」。
     */
    private String withOrigin(String text) {
        String name = loadedFileName;
        if (name == null) {
            return text;
        }
        return text + " ｜ " + MpSqlVisualizerBundle.message("standalone.status.loadedFile", name);
    }

    /**
     * T-PreviewActionRow D-6：补齐一个缺失片段后重解析。
     *
     * <p>为什么逐个补：一个片段可能引用另一个缺失片段，一次只问一个才不会让用户面对一个
     * 不知道从哪粘起的空框；补完重解析会重新算缺失集合，链路自然收敛。</p>
     *
     * <p>内容来源走 {@link #missingFragmentPrompt}（默认弹出框，测试可注入）——「记 overrides + 重解析」
     * 仍是真实链路，与弹窗侧共用 {@link MissingFragmentFillFlow}。</p>
     */
    private void fillMissingFragment(String refid) {
        String content = missingFragmentPrompt.apply(refid);
        if (!MissingFragmentFillFlow.record(refid, content, fragmentOverrides)) {
            return;
        }
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
        // T-PreviewActionRow D-7：把原文随窗带上——弹窗内看到 TODO 占位时才有东西可重解析补齐
        //（其余入口无原文，不传 → 弹窗不挂补齐入口）
        SqlPreviewService.showResult(project, result, null, null, autoReparse, inputEditor.getText());
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
        // T-PreviewActionRow D-5：把「补齐」注入面板（面板只有渲染后 SQL，重解析要原文）
        panel.setMissingFragmentFillHandler(this::fillMissingFragment);
        if (!previewPanelRegistered) {
            Disposer.register(this, panel);
            previewPanelRegistered = true;
        }
        previewHolder.removeAll();
        previewHolder.add(panel, BorderLayout.CENTER);
        previewHolder.revalidate();
        previewHolder.repaint();
        previewPanel = panel;
        refreshBottomActions();
        return panel;
    }

    private void clear() {
        resolveGeneration++;
        loadFileGeneration++; // 丢弃在途的文件读取（清空后晚到的文本不得写回）
        fragmentOverrides.clear();
        loadedFileName = null;
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
        refreshBottomActions();
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

    /** 测试缝：输入区当前文本（T-LoadFilePlacement：断言「载入替换了输入」）。 */
    public String inputTextForTest() {
        return inputEditor.getText();
    }

    /** 测试缝：输入工具栏动作序列（简单类名）——钉住「从文件加载」真的挂在工具栏上。 */
    public List<String> inputToolbarActionNamesForTest() {
        List<String> names = new ArrayList<>();
        for (AnAction action : inputActions) {
            names.add(action.getClass().getSimpleName());
        }
        return names;
    }

    /** 测试缝：底部动作行动作序列（简单类名）——钉住「复制 SQL」等确实在底部那一排。 */
    public List<String> bottomActionNamesForTest() {
        List<String> names = new ArrayList<>();
        for (AnAction action : bottomActions) {
            names.add(action.getClass().getSimpleName());
        }
        return names;
    }

    /** 测试缝：底部按钮 enable（按动作简单类名；不存在返回 null）。 */
    @Nullable
    public Boolean bottomButtonEnabledForTest(String actionSimpleName) {
        int index = -1;
        for (int i = 0; i < bottomActions.size(); i++) {
            if (bottomActions.get(i).getClass().getSimpleName().equals(actionSimpleName)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return null;
        }
        return index < bottomButtons.size() ? bottomButtons.get(index).isEnabled() : null;
    }

    /** 测试缝：内嵌面板的缺失片段提示行组件（派发真实点击，覆盖「面板 → 工作台」回调链）。 */
    @Nullable
    public javax.swing.JLabel embeddedMissingFragmentLabelForTest() {
        SqlPreviewPanel panel = previewPanel;
        return panel != null ? panel.missingFragmentLabelForTest() : null;
    }

    /** 测试缝：内嵌面板实例（断言复制通知动作集合等面板态）。 */
    @Nullable
    public SqlPreviewPanel embeddedPanelForTest() {
        return previewPanel;
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
        loadFileGeneration++; // 丢弃在途的文件读取（释放后晚到的文本不得写回）
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
