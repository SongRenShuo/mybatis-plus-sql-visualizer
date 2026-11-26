package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.ui.standalone.MissingFragmentFillFlow;
import cn.song.mybatisplus.visualizer.ui.standalone.MissingSqlFragmentSupport;
import cn.song.mybatisplus.visualizer.ui.standalone.StandaloneSqlSourceResolver;
import cn.song.mybatisplus.visualizer.ui.standalone.StandaloneWarningText;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQL 预览弹窗（M3-02，F-07 弹窗展示方式）。
 *
 * 复用 SqlPreviewPanel 展示 SQL 与参数对照，底部提供"复制 / 关闭"。
 */
public class SqlPreviewDialog extends DialogWrapper {

    private final SqlPreviewPanel panel;

    /**
     * T-DmlImpactProminence：底部蓝色主行动——复制 SQL 并关闭（复制成功提示随之关闭面板）。
     *
     * <p>字段初始化先于构造器体（init() 会在构造期同步调用 createActions()，本字段若在
     * init() 之后才赋值，动作数组里出现空位、按钮不渲染——UI-042：用户侧表现为
     * 「复制 SQL 并关闭」按钮整个不见）。</p>
     */
    private final Action copyAndCloseAction = new DialogWrapperAction(
            MpSqlVisualizerBundle.message("panel.copySqlAndClose")) {
        @Override
        protected void doAction(java.awt.event.ActionEvent actionEvent) {
            // UI-044：弹窗随即关闭，「关闭 SQL 面板」动作已无目标，通知里不再挂它
            SqlPreviewPanelSqlViewKt.copySql(panel, false);
            doDispose();
        }
    };

    /**
     * 构造期首次 createActions 的产出（UI-042 回归缝）。
     *
     * <p>init() 同步触发 createActions()；此捕获只记首次（后续测试再调 createActions 不覆盖）。</p>
     */
    public Action[] actionsAtConstructionForTest = null;

    /** T-PreviewActionRow D-7：产出本次预览的原始文本（null = 本入口无原文，不挂补齐入口）。 */
    @Nullable
    private final String sourceText;

    /** master 主构造参数（Kotlin 属性初始化器里可用；Java 侧落字段供注入缝的默认实现读取）。 */
    @Nullable
    private final Project dialogProject;

    /**
     * T-PreviewActionRow D-6/D-7：本弹窗内补齐的片段（refid → 内容）。
     *
     * <p>存弹窗自己不存工作台：补完只影响<b>这个弹窗</b>的展示，不回写工作台输入区——用户手里那段
     * 原文保持原样（与工作台同一语义：补齐不污染原文）。</p>
     */
    private final Map<String, String> fragmentOverrides = new LinkedHashMap<>();

    /**
     * 补齐内容来源（默认弹 {@code MissingFragmentDialog}；测试注入，避免 headless 弹模态框）。
     *
     * <p>K2J 注：<b>不得</b>写成字段初始化器 {@code = refid -> …(dialogProject, refid)}——lambda 引用
     * 构造期才赋值的 blank final 会撞 javac 明确赋值分析（本仓在册坑，见 {@code draftTextProvider}
     * 的方法引用注释）；改在构造器体内赋值。</p>
     */
    @NotNull
    public java.util.function.Function<String, String> missingFragmentPrompt;

    /**
     * 最近一次「贴入即识别」用的原文（T-PreviewActionRow D-7）。
     *
     * <p>为什么单独存：识别成功后面板里是<b>渲染后的 SQL</b>，缺片段补齐要的却是贴进来那段原文；
     * {@code draftTextProvider} 那时已经给出渲染结果，不能再用。贴入路径没有 {@code sourceText} 参数，
     * 只能在这里记一手。</p>
     */
    @Nullable
    private String lastSourceText = null;

    /** 解析代次守卫：只应用最后一次请求的产物（贴入停手与手动操作竞态时保最后一次）。 */
    private int resolveGeneration = 0;

    /**
     * 测试缝：替换「池线程计算 + invokeLater 回 EDT」的派发（默认 null = 生产形态）。
     * 契约：计算完回调 {@code onDone} 恰一次（空文本/识别失败传 null，不回调则无回填）。用例注入
     * 同步形态即可确定性驱动同一条链——先例＝{@code cn.song.mybatisplus.visualizer.util.EdtDispatch.invokeLaterOverride}。
     */
    public interface ResolveOffEdtDispatcher {
        void dispatch(@NotNull java.util.function.Supplier<String> textSource,
                      @NotNull java.util.function.Consumer<StandaloneSqlSourceResolver.Resolved> onDone);
    }

    @Nullable
    public ResolveOffEdtDispatcher resolveOffEdtDispatcher = null;

    public SqlPreviewDialog(@Nullable Project project, @NotNull PreviewResult result) {
        this(project, result, null);
    }

    public SqlPreviewDialog(@Nullable Project project, @NotNull PreviewResult result, @Nullable String sourceLocation) {
        this(project, result, sourceLocation, null, false);
    }

    /** 4 参形态（master 默认参展开：带源上下文、不进入贴入即识别待命）。 */
    public SqlPreviewDialog(@Nullable Project project, @NotNull PreviewResult result, @Nullable String sourceLocation,
                            @Nullable cn.song.mybatisplus.visualizer.context.PreviewSourceContext sourceContext) {
        this(project, result, sourceLocation, sourceContext, false);
    }

    /**
     * 全参形态（master 默认参展开）。
     *
     * @param autoParseOnPaste T-DialogPasteReparse：「先开弹窗、后贴内容」——挂防抖监听，用户往编辑器里
     *                         贴入内容后自动重新识别一次（独立工作台的<b>空/原文态</b>开窗时置 true）。
     */
    public SqlPreviewDialog(@Nullable Project project, @NotNull PreviewResult result, @Nullable String sourceLocation,
                            @Nullable cn.song.mybatisplus.visualizer.context.PreviewSourceContext sourceContext,
                            boolean autoParseOnPaste) {
        this(project, result, sourceLocation, sourceContext, autoParseOnPaste, null);
    }

    /**
     * 全参形态（master 默认参展开 + 本轮新增 {@code sourceText}）。
     *
     * @param sourceText T-PreviewActionRow D-7：产出本次预览的<b>原始文本</b>（只有工作台开窗这条链传）。
     *                   面板只拿得到渲染后的 SQL（include 已展开成 TODO 占位），缺片段要补齐就必须用原始
     *                   XML 重解析。其余入口（gutter / 日志 / 历史 / 语句预览）没有原文可重解析，
     *                   传 null ⇒ 弹窗不挂补齐入口，避免出现点了没反应的链接。
     */
    public SqlPreviewDialog(@Nullable Project project, @NotNull PreviewResult result, @Nullable String sourceLocation,
                            @Nullable cn.song.mybatisplus.visualizer.context.PreviewSourceContext sourceContext,
                            boolean autoParseOnPaste, @Nullable String sourceText) {
        super(project, false, DialogWrapper.IdeModalityType.MODELESS);
        this.panel = new SqlPreviewPanel(project);
        this.dialogProject = project;
        this.sourceText = sourceText;
        this.missingFragmentPrompt = refid -> MissingFragmentFillFlow.promptViaDialog(project, refid);
        setTitle(MpSqlVisualizerBundle.message("dialog.preview.title"));
        panel.setSourceLocation(sourceLocation);
        panel.setSourceContext(sourceContext);
        SqlPreviewPanelResultViewKt.showResult(panel, result);
        // T-PreviewActionRow D-7：注入补齐入口——有原文（工作台开窗 / 贴入即识别）才有得补；
        // 其余入口（gutter / 日志 / 历史 / 语句预览）拿不到原文，不注入 ⇒ 面板整行不显示。
        if (sourceText != null || autoParseOnPaste) {
            panel.setMissingFragmentFillHandler(this::fillMissingFragment);
        }
        Disposer.register(getDisposable(), panel);
        panel.onClosePanelRequested = this::dispose;
        panel.setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
        init();
        setCancelButtonText(MpSqlVisualizerBundle.message("dialog.preview.cancel"));
        // 弹窗默认尺寸（按屏幕比例自适应；环境异常兜底）
        // 字体/尺寸兜底后进入「贴入即识别」待命（必须在 showResult 之后：面板编辑器此时才就位）
        if (autoParseOnPaste) armPasteReparse();
        // T-K2JWalk1 KW-1：弹窗自挂 project Disposer 树——工程关闭（含 Open This Window 切换）
        // 时平台遍历树执行本回调收口存活弹窗，根除「预览弹窗跨工程残留」（首个修法把收口挂在
        // SqlPreviewService 构造器，但 gutter 的 XML 链在 getService 之前就 return，服务从未
        // 实例化、收口从未挂上——真机复测残留依旧后改挂此处，弹窗创建即注册必在树上）。
        // 幂等：先被关旧开新/用户关闭 dispose 过的弹窗此处 isDisposed 直接跳过。
        if (project != null) {
            Disposer.register(project, () -> {
                if (!isDisposed()) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewDialog.class)
                            .info("[KW-1] project closing: dispose leftover preview dialog");
                    doDispose();
                }
            });
        }
    }

    @NotNull
    @Override
    protected JComponent createCenterPanel() {
        return panel;
    }

    // ------------------------------------------------------------------
    // T-DialogPasteReparse：「先开弹窗、后贴内容」
    // ------------------------------------------------------------------

    /** 是否仍待命（命中一次即 false——之后交还手动，避免与用户后续编辑互相打架）。 */
    private volatile boolean pasteReparseArmed = false;

    /** 轮询 Timer（仅在待命期存在；命中 / dispose 即停）。 */
    @Nullable
    private javax.swing.Timer pasteReparseTimer = null;

    /** 上一次轮询看到的草稿文本（判「还在变 / 已停手」）。 */
    @Nullable
    private String lastDraftText = null;

    /** 文本连续未变化的轮询次数（达到 {@link #PASTE_REPARSE_STABLE_TICKS} 即视为停手）。 */
    private int stableTicks = 0;

    /**
     * 草稿文本来源（测试缝：headless 下弹窗编辑器可能还没建出来，
     * 用例注入固定文本即可驱动同一重识别链）。
     *
     * <p>方法引用而非 lambda：字段初始化器先于构造体执行，lambda 捕获构造期才赋值的
     * {@code panel} 会撞 javac 明确赋值分析；方法引用是延迟解析，无此问题。</p>
     */
    @NotNull
    public java.util.function.Supplier<String> draftTextProvider = this::defaultDraftText;

    /** 默认草稿来源：面板当前草稿文本（native console 文档优先，自建编辑器兜底；空返回 null）。 */
    @Nullable
    private String defaultDraftText() {
        return panel.currentDraftText();
    }

    /**
     * 进入待命：轮询草稿文本，停手后自动重新识别一次。
     *
     * <h2>为什么是轮询而不是挂 DocumentListener</h2>
     *
     * <p>面板的编辑器是<b>懒建</b>的（空结果构造期 {@code draftDocument()} 为 null，真机要等首次布局/挂载
     * 才有编辑器）——挂监听会有「此刻没文档就永远挂不上」的窗口。轮询只读
     * {@link SqlPreviewPanel#currentDraftText()}，文档何时出现都能接上；代价是待命期每
     * {@link #PASTE_REPARSE_POLL_MS} 一次取文本（命中即停，常态为零开销）。</p>
     *
     * <h2>为什么只在「空/原文态」开窗时进入待命</h2>
     *
     * <p>正常语句预览的编辑器内容已是渲染后的 SQL，用户在那儿编辑是<b>改产物</b>（回填参数/手调 SQL），
     * 自动解析会把他的编辑冲掉。</p>
     */
    private void armPasteReparse() {
        pasteReparseArmed = true;
        // 注意：本方法在构造期 init 里被调用，而下方字段声明在 init 之后——**不得在这里读取
        // draftTextProvider 等字段**（此刻仍是 JVM 默认 null，本仓在册坑）。首次轮询再取。
        lastDraftText = null;
        stableTicks = 0;
        if (pasteReparseTimer != null) {
            pasteReparseTimer.stop();
        }
        javax.swing.Timer timer = new javax.swing.Timer(PASTE_REPARSE_POLL_MS, e -> pollDraftForReparse());
        timer.setRepeats(true);
        timer.start();
        pasteReparseTimer = timer;
    }

    private void pollDraftForReparse() {
        if (!pasteReparseArmed) {
            stopPasteReparse();
            return;
        }
        String text = draftTextProvider.get();
        if (text == null || text.isBlank()) return;
        if (!text.equals(lastDraftText)) {
            lastDraftText = text;
            stableTicks = 0;
            return;
        }
        stableTicks++;
        if (stableTicks >= PASTE_REPARSE_STABLE_TICKS) {
            reparseDraftFromEditor();
        }
    }

    private void stopPasteReparse() {
        pasteReparseArmed = false;
        if (pasteReparseTimer != null) {
            pasteReparseTimer.stop();
            pasteReparseTimer = null;
        }
    }

    /**
     * 对当前草稿做一次独立预览识别（贴入停手后触发）。
     *
     * <p>识别走与工作台页签<b>同一条链</b>（{@link cn.song.mybatisplus.visualizer.ui.standalone.StandaloneSqlSourceResolver}：
     * 风味判定 + 双链择优），失败文案同源（{@link cn.song.mybatisplus.visualizer.ui.standalone.StandaloneWarningText}）。
     * 命中后立即交还手动，不再自动解析。</p>
     */
    public void reparseDraftFromEditor() {
        if (!pasteReparseArmed) return;
        String text = draftTextProvider.get();
        if (text == null || text.isBlank()) return;
        // T-PreviewActionRow D-7：记一手原文——识别成功后草稿文本变成渲染结果，
        // 缺片段补齐要的还是「贴进来那段」，只能在这里留底
        lastSourceText = text;
        resolveOffEdt(() -> text, this::applyResolvedAsFresh);
    }

    /** 测试访问器：是否仍待命。 */
    public boolean pasteReparseArmedForTest() {
        return pasteReparseArmed;
    }

    /**
     * T-PreviewActionRow D-6/D-7：补齐一个缺失片段 → 用原文重解析 → 就地回填面板。
     *
     * <p>不重开弹窗：用户是在结果区看到 TODO 占位后点进来补的，补完应该当场看到展开后的 SQL。
     * 先停待命：计时器若还在跑，会拿旧草稿再覆盖一次刚补好的结果。</p>
     *
     * <p>解析同样走 {@link #resolveOffEdt}（T-PerfSweep33 券01 的唯一出口）：补进来的是整段 mapper，
     * DOM 解析在 100k 上限下最坏数百毫秒，不能停在 EDT。</p>
     */
    private void fillMissingFragment(String refid) {
        String source = sourceText != null ? sourceText : lastSourceText;
        if (source == null) return;
        String content = missingFragmentPrompt.apply(refid);
        if (!MissingFragmentFillFlow.record(refid, content, fragmentOverrides)) return;
        stopPasteReparse();
        String finalSource = source;
        resolveOffEdt(() -> MissingSqlFragmentSupport.withFragments(finalSource, fragmentOverrides),
                this::applyResolvedAsFresh);
    }

    /** 测试缝：触发一次补齐（等价点击面板提示行；内容由 {@link #missingFragmentPrompt} 提供）。 */
    public void fillMissingFragmentForTest(String refid) {
        fillMissingFragment(refid);
    }

    /** 测试缝：提示行组件（派发真实点击覆盖监听接线）。 */
    @Nullable
    public javax.swing.JLabel missingFragmentLabelForTest() {
        return panel.missingFragmentLabelForTest();
    }

    // ------------------------------------------------------------------
    // T-PerfSweep33 券01：识别链挪池线程（性能分析第十期 §4.1）
    // ------------------------------------------------------------------

    /**
     * 识别链后台化。{@code StandaloneSqlSourceResolver.resolve} 是纯文本链（零 PSI/索引；工作台页签同链
     * 早已池线程化——SqlStandalonePreviewPanel），但最坏 8 次 DOM 解析、输入上限 100k 字符，
     * EDT 直跑可停数百毫秒。代次守卫只应用最后一次请求；弹窗已释放时丢弃（迟到产物面）。
     */
    private void resolveOffEdt(@NotNull java.util.function.Supplier<String> textSource,
                               @NotNull java.util.function.Consumer<StandaloneSqlSourceResolver.Resolved> applyOnEdt) {
        int generation = ++resolveGeneration;
        ResolveOffEdtDispatcher seam = resolveOffEdtDispatcher;
        if (seam != null) {
            seam.dispatch(textSource, resolved -> deliverResolved(generation, resolved, applyOnEdt));
            return;
        }
        Application app = ApplicationManager.getApplication();
        app.executeOnPooledThread(() -> {
            String text;
            try {
                text = textSource.get();
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) throw (Error) t;
                text = null;
            }
            StandaloneSqlSourceResolver.Resolved resolved = null;
            if (text != null) {
                try {
                    resolved = StandaloneSqlSourceResolver.resolve(text);
                } catch (Throwable t) {
                    if (FatalErrors.isFatal(t)) throw (Error) t;
                    resolved = null;
                }
            }
            StandaloneSqlSourceResolver.Resolved delivered = resolved;
            app.invokeLater(() -> deliverResolved(generation, delivered, applyOnEdt), ModalityState.nonModal());
        });
    }

    /** 迟到产物闸：只应用最后一次请求；弹窗已释放时丢弃。 */
    private void deliverResolved(int generation,
                                 @Nullable StandaloneSqlSourceResolver.Resolved resolved,
                                 @NotNull java.util.function.Consumer<StandaloneSqlSourceResolver.Resolved> applyOnEdt) {
        if (isDisposed() || generation != resolveGeneration) return;
        if (resolved != null) {
            applyOnEdt.accept(resolved);
        }
    }

    /**
     * 识别产物回填（失败文案同源 {@link StandaloneWarningText}）；命中后停轮询、交还手动——
     * 不再自动解析，与用户后续编辑互不打架。
     */
    private void applyResolvedAsFresh(StandaloneSqlSourceResolver.Resolved resolved) {
        PreviewResult fresh = resolved.failure() != null
                ? resolved.result().withWarning(StandaloneWarningText.failure(resolved.failure()), false)
                : resolved.result();
        stopPasteReparse();
        SqlPreviewPanelResultViewKt.showResult(panel, fresh);
    }

    @Override
    protected void dispose() {
        stopPasteReparse();
        super.dispose();
    }

    /**
     * T-RefreshDbSteal F2：程序化关闭（供 SqlPreviewService 弹窗单例化关旧开新）。
     * DialogWrapper.dispose 在平台侧 protected，同类内提升为 public 包装——语义与用户
     * 点关闭一致（级联 Disposer 释放面板，rehost 监听随之移除）。
     */
    public void doDispose() {
        dispose();
    }

    /** internal 测试访问器（面板「关闭 SQL 面板」回调链路断言）。 */
    public SqlPreviewPanel panelForTest() {
        return panel;
    }

    @NotNull
    @Override
    protected Action[] createActions() {
        Action[] actions = new Action[]{
                // 执行 SQL（M17：选择数据源 → 临时文件 → 可执行；提示由面板/支持层统一处理）
                new DialogWrapperAction(MpSqlVisualizerBundle.message("panel.executeSql")) {
                    @Override
                    protected void doAction(java.awt.event.ActionEvent actionEvent) {
                        SqlPreviewPanelExecutionKt.executeSql(panel);
                    }
                },
                new DialogWrapperAction(MpSqlVisualizerBundle.message("panel.copySql")) {
                    @Override
                    protected void doAction(java.awt.event.ActionEvent actionEvent) {
                        SqlPreviewPanelSqlViewKt.copySql(panel);
                    }
                },
                copyAndCloseAction,
                // 关闭
                getCancelAction()
        };
        if (actionsAtConstructionForTest == null) actionsAtConstructionForTest = actions;
        return actions;
    }

    /** 复制并关闭按钮按主行动样式渲染（蓝色实底）；其余按钮保持平台默认。 */
    @Override
    protected javax.swing.JButton createJButtonForAction(Action action) {
        javax.swing.JButton button = super.createJButtonForAction(action);
        if (action == copyAndCloseAction) SqlPreviewPanelExecutionKt.applyPrimaryButtonStyle(button);
        return button;
    }

    /** UI-043 测试访问器（createJButtonForAction 在 DialogWrapper 内为 protected）。 */
    public javax.swing.JButton createJButtonForActionForTest(Action action) {
        return createJButtonForAction(action);
    }

    /** T-DmlImpactProminence：测试访问器（createActions 在 DialogWrapper 内为 protected）。 */
    public Action[] actionsForTest() {
        return createActions();
    }

    @Override
    public String getDimensionServiceKey() {
        return DIMENSION_KEY;
    }

    public static final String DIMENSION_KEY = "cn.song.mybatisplus.visualizer.SqlPreviewDialog";
    public static final int MIN_WIDTH = 640;
    public static final int MIN_HEIGHT = 420;

    /** T-DialogPasteReparse：草稿轮询间隔（ms）——文档出现后才接得上（编辑器懒建）。 */
    public static final int PASTE_REPARSE_POLL_MS = 400;

    /** T-DialogPasteReparse：文本连续未变化的轮询次数阈值（400ms × 2 ≈ 800ms 静默即认为停手）。 */
    public static final int PASTE_REPARSE_STABLE_TICKS = 2;
    public static final double SCREEN_RATIO_WIDTH = 0.62;
    public static final double SCREEN_RATIO_HEIGHT = 0.65;
    public static final double SCREEN_RATIO_CAP = 0.85;
    public static final int DEFAULT_WIDTH = 900;
    public static final int DEFAULT_HEIGHT = 600;

    /**
     * 计算弹窗默认尺寸（M14）：按屏幕比例（宽 62% / 高 65%），
     * 夹在「下限 900×600」与「屏幕 85% 上限」之间；小屏时上限优先（不超屏）。
     */
    public static Dimension initialSize() {
        return initialSize(screenWidthOf(), screenHeightOf());
    }

    public static Dimension initialSize(int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) return new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        int w = fitWithin((int) (screenWidth * SCREEN_RATIO_WIDTH), screenWidth, DEFAULT_WIDTH);
        int h = fitWithin((int) (screenHeight * SCREEN_RATIO_HEIGHT), screenHeight, DEFAULT_HEIGHT);
        return new Dimension(w, h);
    }

    private static int fitWithin(int ratioValue, int screenSize, int minDefault) {
        int cap = (int) (screenSize * SCREEN_RATIO_CAP);
        return minDefault <= cap ? Math.min(Math.max(ratioValue, minDefault), cap) : cap;
    }

    private static int screenWidthOf() {
        try {
            return Toolkit.getDefaultToolkit().getScreenSize().width;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int screenHeightOf() {
        try {
            return Toolkit.getDefaultToolkit().getScreenSize().height;
        } catch (Throwable t) {
            return 0;
        }
    }
}
