package cn.song.mybatisplus.visualizer.log;
import cn.song.mybatisplus.visualizer.log.agent.AgentManager;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener;
import cn.song.mybatisplus.visualizer.config.SqlLogConfigurable;
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
// T-UiCoord F2：下拉按钮文本截尾省略复用 meta 行的同款纯函数（避免第二份省略实现）
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelKt;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.MpvsUiColors;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.ListSpeedSearch;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.tabs.JBTabs;
import com.intellij.ui.tabs.JBTabsFactory;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.TabsListener;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.actionSystem.Toggleable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具窗口面板（标签页 + 工具栏；headless 测试不布局，交互断言走条目模型层）。
 *
 * 筛选项精简改造（2026-08-27）：
 * - 类型筛选：工具栏行「SQL类型」多选按钮（弹窗勾选，替代原两行按钮组）
 * - 高频折叠：整体移除（识别重复 SQL 的价值由去重 ⨉N 徽章承接）
 * - 去重徽章：开启「移除重复的sql」后被丢弃的 SQL 在条目旁累加显示 ⨉N
 * - Ctrl+F/⌘F 搜索：默认隐藏的搜索条，快捷键唤起实时过滤（Esc 清空后再按收起）
 */
public class SqlLogPanel extends JPanel implements Disposable {

    public final Project project;
    public final SqlLogManager manager;

    private final DefaultListModel<LogSqlEntry> listModel = new DefaultListModel<>();
    public final SqlEntryList list = new ViewportWidthSqlEntryList(listModel);
    /** 列表条目单元格渲染器（internal 供 UI 测试直接渲染、断言 HTML 输出）。 */
    private LogEntryListCellRenderer entryCellRenderer;
    /** T-ClickPreview：单击出轻量预览弹窗（点击时按行捕获 SQL，锚点在行下缘不遮行），双击走 openPreview 完整弹窗；悬停 tooltip 已移除（卡顿根因）。
     *  T-ListPreviewCopy：预览文本经 clickPreviewSql——设置「预览时格式化还原 SQL」开启（默认）时多行格式化显示。 */
    private SqlListPreviewHost previewHost;
    private final SqlDockedPreview dockedPreview;
    /** 面板级测试缝（master internal val previewHostForTest/dockedPreviewForTest 等价）。 */
    public SqlListPreviewHost getPreviewHostForTest() { return previewHost; }
    public SqlDockedPreview getDockedPreviewForTest() { return dockedPreview; }
    private final JBTabs tabs;
    private TabInfo logsTab;
    private TabInfo logToSqlTab;
    private TabInfo sqlToJavaTab;
    private TabInfo historyTab;
    /** T-M38：日志转 SQL 转换 tab（粘贴日志 → 批量还原可执行 SQL；原「SQL 预览」被动展示改造）。 */
    private final LogToSqlPanel logToSqlPanel;
    /** T-M36.4：SQL → Java 转换 tab（粘贴 SQL → 格式化/补全 → 转换 → 可编辑 Java 高亮结果区）。 */
    private final SqlToJavaPanel sqlToJavaPanel;
    /** F-44：历史 SQL tab（读取 .idea 目录中的自动保存文件，支持源码定位）。仅 logAutoSaveEnabled 时创建。 */
    private HistorySqlLogPanel historyPanel = null;
    private boolean slowOnly;
    private JCheckBox slowOnlyCheckBox;

    private final JCheckBox dedupeCheckBox;

    /**
     * SQL类型多选按钮（点击弹可勾选弹窗；文案只显示选中状态：全部 / 无 / 「查询, 更新」组合）。
     *
     * T-UiCoord F2（2026-09-21 用户定案「工具栏不要两行」）：原「SQL类型：」标签控件退役，
     * 标签键转作 tooltip 前缀（tooltip = `SQL类型：` + 当前状态**全文**）；按钮显示文本按
     * {@link #DROPDOWN_TEXT_BUDGET_PX} 截尾省略——文本定宽封顶是多选后行宽不漂移、单行恒成立的前提。
     */
    private final JButton typeFilterButton;

    /**
     * 排序方式按钮（T-LogSortModes）：点击弹单选菜单，文案显示当前方式；与「SQL类型」按钮同款式
     * （原生 LaF 扁平按钮 + 右侧下箭头），默认时间升序。取代原「耗时排序」双态切换按钮——
     * 排序恒有确定方式，不再依赖「是否检测到耗时」决定显隐。
     *
     * T-UiCoord F2：原「排序：」标签控件同样退役（标签键转 tooltip 前缀），文本同样截尾封顶。
     */
    public final AnAction sortModeAction = new AnAction() {
        { getTemplatePresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.Sort); }
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT（见 toolbarGroup 注释的字节码依据）
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
        @Override public void update(AnActionEvent e) {
            String label = currentSortLabel();
            e.getPresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.Sort);
            e.getPresentation().setText(label);
            e.getPresentation().setDescription(MpSqlVisualizerBundle.message("log.sort.label") + label);
        }
        @Override public void actionPerformed(AnActionEvent e) {
            showSortPopup(e.getInputEvent() != null ? e.getInputEvent().getComponent() : null);
        }
    };

    public final ToggleAction previewToggleAction = new ToggleAction(
            MpSqlVisualizerBundle.message("log.dockedPreview.toggle"),
            MpSqlVisualizerBundle.message("log.dockedPreview.toggle.tip"),
                cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.DockedPreview) {
        // T-SqlLogToolbarEdt W1：ToggleAction 的 update 由平台模板实现，但 isSelected 被覆写后
        // 平台仍按 AnAction 默认的更新线程判定 → 显式声明 BGT（isSelected 只读 dockedPreview 状态）。
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
        @Override public boolean isSelected(AnActionEvent e) { return dockedPreview.isOpen(); }
        @Override public void setSelected(AnActionEvent e, boolean state) {
            if (dockedPreview.isOpen() != state) previewHost.toggle();
        }
    };

    // ---------- T-SqlLogPerf：「滚动到底部」动作 + 尾部跟随 ----------

    /**
     * T-SqlLogPerf（20261006-1020 计划 + 用户点名）：「滚动到底部」动作 + 尾部跟随。
     *
     * <p>背景：log 包原没有任何滚动控制——并发灌入时新条目只进模型、视口原地不动，用户体感
     * 「监听加载有延迟」。本动作一键跳列表底并恢复跟随；跟随态下新条目到达自动贴底，
     * 用户向上滚动阅读即自动停跟随（滚回底部/点动作恢复），与 IDE 控制台「滚动到底」习惯一致。
     * 形态走左侧动作图标组（AnAction）而非右侧控制组 JButton——控制组最坏宽有预算锁
     * （SqlLogPanelLayoutTest 防「又往控制组加控件」），图标组窄了由平台溢出承接。
     */
    final AnAction scrollToEndAction = new ScrollToEndAction();

    /** Kotlin {@code val scrollToEndAction} 的 JVM 访问器等价入口（属性名读取用）。 */
    public AnAction getScrollToEndAction() {
        return scrollToEndAction;
    }

    private final TailFollowState tailFollow = new TailFollowState();
    private JBScrollPane listScrollPane;

    private void scrollToBottom(boolean resume) {
        if (resume) tailFollow.onScrollToEnd();
        if (listModel.isEmpty()) return;
        list.ensureIndexIsVisible(listModel.size() - 1);
        if (listScrollPane != null) {
            javax.swing.JScrollBar bar = listScrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        }
    }

    public TailFollowMode getTailFollowModeForTest() { return tailFollow.getMode(); }
    public JBScrollPane getListScrollPaneForTest() { return listScrollPane; }
    public void resumeTailFollowForTest() { scrollToBottom(true); }

    // ---------- T-SearchToggleNativeLook（O-137）：搜索条迁平台原生 SearchTextArea ----------
    //
    // MatchCase/Regex 双开关走原生钮（内嵌搜索框右侧，与 Ctrl+F 同形态）。20261010 用户令日志页
    // 补正则钮（D133「日志页无正则」拍板翻案）——正则语义与历史页同链：开关或 `regex:` 前缀、
    // 非法正则 fail-closed 回退子串。旗改从 NativeSearchTextField.findModel 读，过滤语义零变化。
    // 显隐契约保持在宿主 searchBar 上（isVisible 由 showSearch/hideSearch 控制）。

    private final cn.song.mybatisplus.visualizer.ui.search.NativeSearchTextField nativeSearch;

    /** 搜索条（默认隐藏，Ctrl+F/⌘F 唤起；实时过滤 restoredSql 与摘要）。 */
    private final cn.song.mybatisplus.visualizer.ui.search.NativeSearchTextField searchField;

    /** 正则编译 memo（防抖 200ms 反复 apply 复用编译结果，键=源串+matchCase 旗）。 */
    private final HistoryRegexMemo regexMemo;

    /** 搜索条宿主（原生 SearchTextArea 自带内嵌开关；显隐由 showSearch/hideSearch 控制）。 */
    private final JPanel searchBar;

    /** 当前大小写敏感态（原生钮状态落点）。 */
    private boolean logMatchCase() {
        return nativeSearch.isMatchCase();
    }

    /** 当前正则态（原生钮状态落点；`regex:` 前缀独立生效）。 */
    private boolean logRegex() {
        return nativeSearch.isRegex();
    }

    /** 测试断言/驱动：MatchCase 旗（等价用户点击 = 置 FindModel.isCaseSensitive 后自动刷新列表）。 */
    public boolean getMatchCaseToggleForTest() {
        return logMatchCase();
    }

    public void setMatchCaseToggleForTest(boolean value) {
        nativeSearch.getFindModel().setCaseSensitive(value);
        refreshList();
    }

    /** 测试断言/驱动：Regex 旗（等价用户点击 = 置 FindModel.isRegularExpressions 后自动刷新列表）。 */
    public boolean getRegexToggleForTest() {
        return logRegex();
    }

    public void setRegexToggleForTest(boolean value) {
        nativeSearch.getFindModel().setRegularExpressions(value);
        refreshList();
    }

    // ---------- 过滤/排序状态 ----------

    /** 当前激活的类型过滤集合（默认全选 = 「全部」，含 OTHER 透传）。 */
    private final Set<StatementType> activeFilter = new LinkedHashSet<>(SqlLogPanelKt.FILTERABLE_TYPES);

    /** 排序模式（T-LogSortModes：默认时间升序 = 捕获顺序）。 */
    private SortMode sortMode = SortMode.TIME_ASC;

    /** 当前搜索词（搜索条实时同步；空串 = 不过滤）。 */
    private String searchText = "";

    /**
     * M56 T-M56-00：动态聚焦过滤状态（基础设施；null = 不过滤）。
     * T-M56-02/06/07 起 agent 通道接管后由页签/请求聚合驱动写入；桥通道恒 null 与现状逐字一致。
     */
    private volatile SqlLogPanelKt.FocusFilter activeFocus = null;

    /** 去重丢弃计数快照（refreshList 后更新；cellRenderer 渲染 ⨉N 徽章用）。 */
    private Map<String, Integer> dedupeCounts = Map.of();

    /** O-90：同模板计数快照（refreshList 后更新；cellRenderer 徽章与右键聚焦动作共用）。 */
    private Map<String, Integer> templateCounts = Map.of();

    // ---------- T-Perf-03（自 dev 移植）：增量刷新状态 ----------

    /** 刷新代际：新 refreshList 启动时 +1，旧分批追加链检测到代际过期即终止。 */
    private long refreshGeneration = 0L;

    /** 上一轮渲染的可见列表（同一性差分基线；由 applyIncremental/applyFullRebuild 与 listModel 保持同步）。 */
    public List<LogSqlEntry> lastRendered = List.of();

    // T-Perf-15：manager 监听器命名引用（uiRefreshListener/capturingUiListener）声明于 init 前统一区块。
    //
    // T-RecentExecMenu O-119：送页签通道回调的**稳定引用**（init 注册 / dispose 同一性注销用）——
    // 必须声明在 init 之前：init 块内会读它完成注册，声明在后会读到未初始化值（本仓构造期序坑第三次）。
    private final java.util.function.BiConsumer<LogSqlEntry, LogEntryTab> openEntryInTabCallback =
            (entry, tab) -> openEntryInTab(entry, tab);

    // ---------- T-Perf-15（自 dev 移植）：manager 监听器命名引用（init 注册 / dispose 注销） ----------

    /** 缓冲变更 → 列表刷新（manager.uiNotifyListener 回调链；EDT 执行）。 */
    private final Runnable uiRefreshListener = this::refreshList;

    /** capturing 状态变化 → 刷新捕捉动作 presentation（T-CaptureBack：捕捉开关回迁面板工具栏，
     *  图标/文案由 CaptureAction.update 按 capturing 态输出）。 */
    private final Runnable capturingUiListener = new Runnable() {
        @Override
        public void run() {
            LOG.info("SqlLogPanel.capturingListener: 触发");
            ApplicationManager.getApplication().invokeLater(() -> {
                // T-UiNative T-UN-11：反射 hack（updateCaptureButtonIcon 遍历组件树强改 ActionButton 图标）
                // 退役——回归平台更新机制。真实 IDE 中工具窗可见，isShowing 满足，updateActionsImmediately
                // 正常执行；沙盒/单测中组件不可见时平台会跳过更新——测试断言直接读
                // captureToggleAction.presentation，不依赖按钮刷新。
                ActionToolbar toolbar = _toolbarRef;
                if (toolbar != null) {
                    toolbar.updateActionsImmediately();
                }
                // T-CaptureTruth T-CT-03：捕捉态变化 → 诊断行立即跟上（起停捕捉是用户最先看的时刻）
                syncDiagnostics();
            });
        }
    };

    // ---------- T-Perf-24（自 dev 移植）：搜索输入防抖 ----------

    /** 搜索输入防抖 Timer——每击键一次 O(N) 刷新 → 停顿 200ms 应用一次。 */
    private final javax.swing.Timer searchDebounceTimer;

    /** T-Perf-24（自 dev 移植）：搜索输入防抖窗口（毫秒）。 */
    private static final int SEARCH_DEBOUNCE_MS = 200;

    /**
     * T-UiCoord F2：两个下拉按钮（SQL类型 / 排序）显示文本的像素上限（@100% 缩放，用 JBUI.scale 取用）。
     *
     * 定宽封顶是「工具栏恒为单行」的前提之一：`SQL类型` 多选后文案会到「查询, 更新, 删除, 插入」，
     * 不封顶时行宽随选择漂移（旧流式行正是这么折到第二行的）。封顶后按钮宽度只由预算决定，
     * 超限截尾省略、tooltip 恒全文（见 {@link #clipToDropdownBudget}）。
     *
     * **取值依据（2026-09-21 headless 实测，非估算）**：每个下拉按钮的固定开销 = 图标 16 + LaF 内边距 38
     * ≈ 54px（与文本无关），勾选固定 102px。用户侧边栏 460px、图标组真机量测约 133px 下：
     * 默认态（「全部」）控制组 ≈300 → 合计 433（余 27px）；多选态封顶后控制组 ≈333 → 合计 466。
     * 预算取 64（不是初版计划的 78）：78 时多选态控制组 346，超 `460 − 图标组保守下限 120 = 340`
     * 的预算锁。多选态余下约 6px 的超出量（相对图标组量测中值）由平台图标溢出兜底，
     * 属登记边界而非缺陷——见计划 §七 待验证前提 2。
     */
    private static final int DROPDOWN_TEXT_BUDGET_PX = 64;
    /** T-CaptureTruth T-CT-03：零产出诊断刷新间隔（与桥轮询同频 2s）。 */
    private static final int DIAGNOSTICS_TICK_MS = 2000;
    /** R53：双通道状态栏两段文案之间的语义分隔符。 */
    private static final String SEP_LABEL = "｜";
    /**
     * T-ListMetaRevert：判定「滚动条已在底部」的容差（px）。
     *
     * <p>取 2px 而非 0：不同 LAF 下 {@code value} 与 {@code maximum - visibleAmount} 可能差几个像素
     * （舍入），严格相等会导致「拖到底却不恢复跟随」。</p>
     */
    private static final int SCROLL_BOTTOM_TOLERANCE_PX = 2;

    // ---------- O-1 日志窗口差异化：慢查询计数器 + 耗时着色 ----------

    /** 慢查询计数器（O-1 Phase C）。 */
    private final SlowQueryCounter slowQueryCounter =
            new SlowQueryCounter(PluginSettings.getInstance().getState().getSlowQueryThresholdMs());
    /** 工具栏慢查询计数标签（动态更新）。 */
    private JLabel slowQueryLabel = null;

    // ---------- UI 组件（工具栏单行：动作图标组靠左 + 列表控制组靠右） ----------

    /** 工具栏引用（capturing 变化时经平台更新机制刷新动作 presentation，T-UN-11）。 */
    private ActionToolbar _toolbarRef = null;
    /** 工具栏动作组（T-M39：internal 供测试断言帮助入口存在性）。
     *  T-CaptureBack：捕捉开关回迁首位（捕捉/清空/设置/屏蔽管理/搜索/帮助；搜索在帮助左侧为用户指定排序）。
     *  T-SqlSuppress：屏蔽管理入口插设置旁（D1 主入口；D7 图标 AllIcons.General.Filter 漏斗）。 */
    /**
     * R53：增强捕获暂停/恢复动作（工具窗入口；图标/文案按 userPaused 态输出）。
     * 语义 = 暂停只停 agent 通道（事件在入队点丢弃）、控制台通道照常；暂停≠关闭——
     * 连接/心跳/CONFIG 链不动。增强未开启时隐藏（update 置 isVisible=false，平台不渲染）。
     */
    public final AnAction agentPauseAction = new AgentPauseAction();

    public final DefaultActionGroup toolbarGroup;

    // ---------- T-M55-03/04：动态聚焦过滤页签栏字段（须在 init 块之前——Kotlin 按文本序初始化，
    // init 里 buildTopPanel 会读这些字段） ----------

    /** 当前开启的聚焦页签（EDT 读写——右键菜单/页签关闭按钮均在 EDT）。 */
    private final List<FocusTab> focusTabs = new ArrayList<>();

    /** 聚焦页签栏（工具栏行与搜索条之间；无页签时隐藏不占高度）。 */
    private final JPanel focusTabBar = new JPanel(new WrapLayout());

    /** 聚焦隐藏计数提示（聚焦视图下不匹配任何页签的条目数；无页签时隐藏）。 */
    private final JLabel focusHiddenLabel = new JLabel();

    // ---------- T-UiCoord F2：工具栏单行宿主字段（同样须在 init 之前——init 里 buildToolbar 会赋值；
    // 声明晚于 init 会让赋值被随后的 `= null` 初始化器覆盖回 null） ----------

    /** 工具栏单行宿主（`buildToolbar()` 产物）。 */
    private JPanel toolbarRow = null;

    /** 工具栏右侧控制组（EAST 宿主内那层 FlowLayout 面板）。 */
    private JPanel toolbarControls = null;

    /** 工具栏动作图标组组件（CENTER）——结构断言用（曾因还原反向验证探针时误删 CENTER 挂载而漏）。 */
    private JComponent toolbarComponent = null;

    /**
     * 诊断刷新定时器（仅「捕捉中且零条目」时运行）。
     *
     * <p>为什么需要定时器：零产出意味着缓冲不发任何通知，而控制台行数/增强挂载态都在变——不主动拉取
     * 的话诊断行会永远停在「捕捉开始那一刻」的快照上，恰好在用户真正想知道「为什么还是没有」时失语。
     * 2s 与桥轮询同频（SqlLogConsoleBridge POLL_INTERVAL_MS）；命中条目或停止捕捉即停表，常态零开销。
     */
    private javax.swing.Timer diagnosticsTimer = null;

    // ---------- R53：双通道捕获状态栏（字段须在 init 之前——init 里 buildStatusBar 会赋值） ----------

    /** 状态栏：控制台通道段（已挂载 N 个 · 已扫描 M 行）。 */
    private javax.swing.JLabel statusConsoleLabel = null;

    /** 状态栏：增强 agent 通道段（未开启 / 暂停中 / 已挂载 pid · 已收事件 N / 等待…）。 */
    private javax.swing.JLabel statusAgentLabel = null;

    /**
     * 状态栏宿主（非捕捉态整体隐藏——只清两段文案会留下分隔符与一条空白高度；
     * BorderLayout 跳过 isVisible=false 的子件，故置位后需 revalidate）。
     */
    private javax.swing.JPanel statusBarPanel = null;

    /** 当前打开的类型弹窗内勾选框（全选/全不选即时同步用；弹窗每次打开重建）。 */
    private List<kotlin.Pair<StatementType, JCheckBox>> typePopupCheckBoxes = List.of();

    /** T-SelToggle：弹窗底部全选/全不选切换按钮（T-SelToggle 双按钮合一；弹窗每次打开重建，与 typePopupCheckBoxes 同生命周期约定）。 */
    private JButton typePopupToggleButton = null;

    /**
     * T-SelToggle：弹窗切换按钮文案——activeFilter 全勾 → 「全不选」（点击清空），
     * 其余态（含空集）→ 「全选」（点击填满）。判定与外层「全部」文案（updateTypeFilterButton）同口径。
     */
    private String typePopupToggleLabel() {
        return MpSqlVisualizerBundle.message(
                activeFilter.containsAll(SqlLogPanelKt.FILTERABLE_TYPES)
                        ? "log.filter.selectNone"
                        : "log.filter.selectAll");
    }

    /**
     * T-SelToggle：弹窗切换按钮文案随勾选态刷新。弹窗无公共渲染下游可收口
     * （updateTypeFilterButton/refreshList 还被搜索等非弹窗链路调用），三个勾选态变更入口
     * （弹窗构建末尾、applyAllTypes 末尾、单类型复选框 listener）显式调用本函数。
     */
    private void syncTypePopupToggleLabel() {
        JButton button = typePopupToggleButton;
        if (button != null) {
            String label = typePopupToggleLabel();
            if (!label.equals(button.getText())) {
                button.setText(label);
            }
        }
    }

    /**
     * 弹出类型多选弹窗：勾选即时生效且弹窗不关闭（JCheckBox 非 menu item，
     * 点击不触发 popup 收起，支持连续勾选组合）；底部单个全选/全不选切换按钮（T-SelToggle）
     * 一键设置——同步刷新弹窗内勾选框选中态 + 按钮文案 + 列表（弹窗保持打开，立即可见）。
     */
    private void showTypeFilterPopup() {
        // T-UiNative P1a：JPopupMenu 外壳 → 平台 JBPopup（createComponentPopupBuilder 承载 JCheckBox 面板；
        // 触发源是工具栏按钮 ActionListener 无 MouseEvent，锚点沿用原 show 坐标——按钮正下方 2px）
        JPanel contentPanel = buildTypeFilterContent();
        JBPopupFactory.getInstance()
                .createComponentPopupBuilder(contentPanel, contentPanel)
                .setRequestFocus(false)
                .setCancelOnClickOutside(true)
                .createPopup()
                .show(new RelativePoint(typeFilterButton, new Point(0, typeFilterButton.getHeight() + 2)));
    }

    /**
     * T-SelToggle：构建弹窗内容（勾选框区 + 分隔线 + 底部切换按钮），赋值 typePopupCheckBoxes
     * 与 typePopupToggleButton。与 showTypeFilterPopup 拆分是纯提取（行为零变更），
     * 使测试可脱离 popup.show 直接驱动勾选框/切换按钮（headless 下弹层 show 不可靠）。
     */
    private JPanel buildTypeFilterContent() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(8, 12, 8, 12));
        List<kotlin.Pair<StatementType, JCheckBox>> checkBoxes = new ArrayList<>();
        for (StatementType type : SqlLogPanelKt.FILTERABLE_TYPES) {
            JCheckBox cb = new JCheckBox(labelForType(type), activeFilter.contains(type));
            cb.setOpaque(false);
            cb.setAlignmentX(Component.LEFT_ALIGNMENT);
            cb.setFocusable(false);
            cb.addActionListener(e -> {
                if (cb.isSelected()) {
                    activeFilter.add(type);
                } else {
                    activeFilter.remove(type);
                }
                updateTypeFilterButton();
                refreshList();
                // T-SelToggle：单类型勾选即时同步切换按钮文案
                syncTypePopupToggleLabel();
            });
            checkBoxes.add(new kotlin.Pair<>(type, cb));
            // T-CrudColor：复选框前置色块图例——否则用户得去设置页才能把颜色和类型对上号。
            // 样式为 off（或该类型无标记色）时不加色块，避免出现「有图例却无标记」的错位。
            // 包一层行面板保持 checkBoxes 结构与 applyAllTypes 语义不变。
            Color tint = SqlListTypeColor.colorOf(type);
            JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            rowPanel.setOpaque(false);
            rowPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            rowPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, cb.getPreferredSize().height + 2));
            if (tint != null) {
                rowPanel.add(new JLabel(colorSwatchIcon(tint)));
            }
            rowPanel.add(cb);
            content.add(rowPanel);
        }
        typePopupCheckBoxes = checkBoxes;
        content.add(Box.createVerticalStrut(6));
        JSeparator sep = new JSeparator();
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(sep);
        content.add(Box.createVerticalStrut(6));
        JButton toggleButton = new JButton(typePopupToggleLabel());
        toggleButton.setFocusable(false);
        toggleButton.addActionListener(e -> applyAllTypes(!activeFilter.containsAll(SqlLogPanelKt.FILTERABLE_TYPES)));
        typePopupToggleButton = toggleButton;
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(toggleButton);
        content.add(row);
        return content;
    }

    /**
     * T-CrudColor：类型色块图例图标（12×12 实心方块）。
     * 用于类型过滤弹窗——纯色块、无边框，与列表中的标记同色，起对照作用。
     */
    private Icon colorSwatchIcon(Color color) {
        return new Icon() {
            @Override
            public int getIconWidth() {
                return 12;
            }

            @Override
            public int getIconHeight() {
                return 12;
            }

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(color);
                g.fillRect(x, y, getIconWidth(), getIconHeight());
            }
        };
    }

    /** 全选/全不选（全选 = 「全部」语义，列表透传含 OTHER）：同步弹窗内勾选框 + 按钮文案 + 列表。 */
    private void applyAllTypes(boolean selected) {
        activeFilter.clear();
        if (selected) {
            activeFilter.addAll(SqlLogPanelKt.FILTERABLE_TYPES);
        }
        for (kotlin.Pair<StatementType, JCheckBox> p : typePopupCheckBoxes) {
            p.getSecond().setSelected(activeFilter.contains(p.getFirst()));
        }
        updateTypeFilterButton();
        refreshList();
        // T-SelToggle：切换按钮点击后文案翻转（此时 activeFilter 已是新态）
        syncTypePopupToggleLabel();
    }

    public SqlLogPanel(@NotNull Project project, @NotNull SqlLogManager manager) {
        super(new BorderLayout());
        this.project = project;
        this.manager = manager;

        this.dedupeCheckBox = new JCheckBox(MpSqlVisualizerBundle.message("log.panel.dedupe"));
        dedupeCheckBox.setSelected(manager.getEntries().isDedupeEnabled());
        dedupeCheckBox.addActionListener(e -> {
            boolean nowChecked = dedupeCheckBox.isSelected();
            boolean wasEnabled = manager.getEntries().isDedupeEnabled();
            if (nowChecked == wasEnabled) {
                return;
            }
            if (nowChecked) {
                manager.getEntries().setDedupeEnabled(true);
                manager.getEntries().resetDedupeCounts();
                // F-44-2 用户实测缺：勾选「移除重复的sql」后立即对已有条目去重
                // —— 此前仅清计数、不裁剪缓冲，重复 SQL 继续展示
                long removed = manager.getEntries().dedupeExisting();
                if (removed > 0) {
                    LOG.info("dedupeCheckBox: 开启去重后裁掉了 " + removed + " 条已存在重复 SQL");
                }
            } else {
                // 关闭去重：清空累计计数（语义：关闭 = 本次累计清零）
                manager.getEntries().disableDedupe();
            }
            refreshList();
        });

        this.typeFilterButton = new JButton();
        typeFilterButton.setFocusable(false);
        typeFilterButton.setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.TypeFilter);
        typeFilterButton.setHorizontalTextPosition(SwingConstants.LEFT); // 图标在文字右侧（下拉按钮惯例）
        typeFilterButton.addActionListener(e -> showTypeFilterPopup());

        this.dockedPreview = new SqlDockedPreview(project, list, () -> previewHost.toggle());
        this.tabs = JBTabsFactory.createTabs(project, this);
        tabs.getPresentation().setSingleRow(true);
        tabs.getPresentation().setSupportsCompression(true);
        tabs.getPresentation().setTabDraggingEnabled(false);

        this.logToSqlPanel = new LogToSqlPanel(project);
        this.sqlToJavaPanel = new SqlToJavaPanel(project);
        this.nativeSearch = new cn.song.mybatisplus.visualizer.ui.search.NativeSearchTextField(
                MpSqlVisualizerBundle.message("log.search.emptyText"),
                true,
                project);
        this.searchField = this.nativeSearch;
        this.regexMemo = new HistoryRegexMemo();
        this.searchBar = new JPanel(new BorderLayout());
        this.searchBar.setOpaque(false);
        this.searchBar.setVisible(false);
        this.searchBar.add(nativeSearch.getRow(), BorderLayout.CENTER);
        // 搜索框文本变更 → 防抖应用。T-SearchToggleNativeLook 迁平台原生 SearchTextArea 时漏接线：
        // 本仓 dev 侧接线原在 buildSearchField 的 DocumentListener 上，master 原生结构只剩
        // onSearchTextChanged / searchDebounceTimer 两个孤儿与 hideSearch 里「非空时触发
        // DocumentListener 清过滤并刷新」的注释（测试钩子 applySearchTextForTest 直调内部入口，
        // 覆盖不到真实键入分派，故未暴露）。本处补回接线，恢复「键入即过滤」「隐藏搜索条即清过滤」。
        nativeSearch.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onSearchTextChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onSearchTextChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onSearchTextChanged();
            }
        });
        this.searchDebounceTimer = new javax.swing.Timer(SEARCH_DEBOUNCE_MS, e -> applySearchText());
        this.searchDebounceTimer.setRepeats(false);
        this.captureToggleAction = new CaptureAction();
        // T-SqlLogToolbarEdt W1（2026-10-24，用户令「可修的性能问题现在立项」）：组与组内动作显式
        // 声明 ActionUpdateThread.BGT。
        //
        // 为什么必须显式声明（243 字节码实证，非猜测）：AnAction.getActionUpdateThread() 的默认
        // 实现是「实现 UpdateInBackground 且返回 true → BGT；ActionClassMetaData.isDefaultUpdate
        // 为 true → BGT；否则 EDT」，而 isDefaultUpdate 判定的是「声明 update() 的类是否就是
        // AnAction 本身」——组内这批动作全都覆写了 update()，于是默认全部落 EDT。
        //
        // 实测代价（沙盒 idea.log 20:34:28 / 20:35:23）：ActionUpdater - 3981 ms / 3174 ms total
        // to grab EDT 10 times to expand DefaultActionGroup#expandGroup@...log.toolbar ...，
        // 累计抢 EDT 3.1~4.0 秒。
        //
        // 约束（W2 已逐个核过）：update() 内只读状态 + 写 e.presentation，不触碰 Swing 组件/
        // 文档/EDT-only API，故 BGT 合规。例外=AgentPauseAction：master 同样未声明 BGT
        // （方案 B 无条件隐藏，EDT 更新无害），保持一致不擅自加。
        DefaultActionGroup toolbarGroupImpl = new DefaultActionGroup() {
            @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
        };
        // 操作在前、工具在后、帮助收尾（? 是纯信息性动作，平台惯例放末尾；溢出从右端折叠，
        // 帮助最先让位）。不设自建「更多」弹层——窄宽折叠交给平台 ActionToolbar 自带的溢出（»）。
        toolbarGroupImpl.addAll(java.util.Arrays.asList(new AnAction[] {
                captureToggleAction, // 捕捉开关（回迁面板工具栏首位，T-CaptureBack）
                this.agentPauseAction, // R53：暂停/恢复（方案 B 临时全隐藏——动作对象保留在组内）
                new ClearAction(),
                new SettingsAction(),
                new ManageSuppressAction(), // T-SqlSuppress：屏蔽管理（设置旁，D1 主入口 + D7 漏斗图标）
                new ShowSearchAction(), // 搜索在帮助左侧（用户指定排序）
                previewToggleAction,
                scrollToEndAction,
                sortModeAction,
                new CommunityAction(),
                new HelpAction(), // 数组初始化器尾逗号合法——守卫逐行解析成员列表的统一行形态
        }));
        this.toolbarGroup = toolbarGroupImpl;
        // T-M55-03/04：聚焦页签栏与隐藏计数提示初始态（无页签隐藏不占高度）
        this.focusTabBar.setOpaque(false);
        this.focusTabBar.setVisible(false);
        this.focusHiddenLabel.setForeground(MpvsUiColors.META_GRAY);
        this.focusHiddenLabel.setVisible(false);

        // 注册子面板到 Disposer 树：父面板 dispose 时级联释放子面板（及其 DocumentListener），
        // 避免 IDE 退出时 ObjectTree 报 Memory leak detected
        Disposer.register(this, logToSqlPanel);
        Disposer.register(this, sqlToJavaPanel);
        // 停靠预览持有独立编辑器实例：随面板级联释放（防测试/真机泄漏「Editor hasn't been released」）
        Disposer.register(this, dockedPreview);

        // T-RecentExecMenu O-119：面板外「送条目到页签」通道注册（弹框右键用；dispose 注销）
        manager.onOpenEntryInTab = openEntryInTabCallback;

        // 标签 1：SQL 日志（工具栏行 + 搜索条 + 条目列表）
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.add(buildTopPanel(), BorderLayout.NORTH);
        previewHost = new SqlListPreviewHost(list, buildListPane(), dockedPreview, "mpvs.logs.preview.split", true,
                open -> Toggleable.setSelected(previewToggleAction.getTemplatePresentation(), open));
        logPanel.add(previewHost.getComponent(), BorderLayout.CENTER);
        // R53：双通道捕获状态栏（捕捉期间常驻；非捕捉态清空隐藏——见 syncCaptureStatusBar）
        logPanel.add(buildStatusBar(), BorderLayout.SOUTH);

        logsTab = tabs.addTab(new TabInfo(logPanel).setText(MpSqlVisualizerBundle.message("log.tab.logs")));
        logToSqlTab = tabs.addTab(new TabInfo(logToSqlPanel).setText(MpSqlVisualizerBundle.message("log.tab.logToSql")));
        sqlToJavaTab = tabs.addTab(new TabInfo(sqlToJavaPanel).setText(MpSqlVisualizerBundle.message("log.tab.sqlToJava")));
        // R19（T-PerfSweep 批次 2）+ T-HistoryTabAlways：历史 tab 常驻——原 F-44 条件挂载
        // （「开启自动保存 或 .idea 中已有历史文件」）让未开启自动保存的干净项目没有入口，
        // 关掉设置页开关还会把已挂载的 tab 摘掉；文件列表空态已带「开启自动保存」引导文案，
        // tab 常驻即引导闭环，F-44 的移除分支随之废除、设置变更不再参与显隐。
        addHistoryTab();
        add(tabs.getComponent(), BorderLayout.CENTER);
        tabs.addListener(new TabsListener() {
            // UI-004：平台参数顺序为（旧页签, 新页签）——此前首参误命名为 current，
            // 使历史页刷新挂在「离开历史页」一拍；更名后进入历史页才刷新。
            @Override public void selectionChanged(TabInfo previous, TabInfo current) {
                if (current == sqlToJavaTab) sqlToJavaPanel.refreshFormatFromSettings();
                if (current == historyTab) getHistoryPanel().refresh();
            }
        }, this);

        // 设置变更后列表重排重绘（T-HistoryTabAlways 起历史 tab 无显隐逻辑，恒在）
        SettingsChangeListener settingsListener = () -> ApplicationManager.getApplication().invokeLater(() -> {
            // T-LogListFreeze-04：片段渲染直读设置态（时间戳/耗时显示/慢查询阈值）——设置变更后
            // 走 refreshList 重排重绘即生效（SINGLE_LINE 直渲染；多行模式片段缓存按模式键自动失效）
            // T-ListMode：显示模式入口已迁设置页（T-DisplayModeToSettings）——设置页 apply 广播后
            // 行高随模式重设。T-MergeFollowup-03（复查二轮）：BasicListUI 的 cellHeights 布局缓存
            // <b>不归 Swing layout 管</b>——revalidate 无效；且多行模式间切换是 -1→-1（setFixedCellHeight
            // 值不变不触发 propertyChange 失效），refreshList 增量路径在内容无变化时也不产生 model
            // 结构事件——updateUI() 重装列表 UI 强制布局状态失效，是最直接可靠的失效手段。
            applyListCellHeight();
            list.updateUI();
            refreshList();
            // T-CrudColor：历史二级列表同样直读设置，重绘即生效（不重扫文件、不打断浏览位置）。
            // 仅当历史 tab 已挂载时才取面板——避免仅为重绘而强制实例化（historyPanel 惰性创建）
            if (historyTab != null) {
                getHistoryPanel().repaintSqlList();
            }
        });
        project.getMessageBus().connect(this).subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, settingsListener);

        // T-Perf-15（自 dev 移植）：监听器改命名引用——dispose 时精确注销（此前 addUiListener/addCapturingListener
        // 只加不减，面板随 tool window content 销毁重建逐次累积）
        manager.addUiListener(uiRefreshListener);
        refreshList();

        // capturing 变化 → 刷新捕捉动作（T-UiNative T-UN-11：动作挂工具窗标题栏，presentation
        // 由 CaptureAction.update 按 capturing 态输出；监听经平台更新机制刷新，沙盒测试断言直读
        // captureToggleAction.presentation）
        manager.addCapturingListener(capturingUiListener);
    }

    private HistorySqlLogPanel getHistoryPanel() {
        if (historyPanel == null) {
            historyPanel = new HistorySqlLogPanel(project);
        }
        return historyPanel;
    }

    // ---------- T-M55-03/04：动态聚焦过滤页签栏（页签=过滤器，多开并集，可关闭；不碰差分） ----------
    // 字段（focusTabs/focusTabBar/focusHiddenLabel）声明在类头部 init 块之前（文本序初始化）

    /** 当前开启的聚焦页签（测试断言）。 */
    java.util.List<FocusTab> focusTabsForTest() {
        return List.copyOf(focusTabs);
    }

    /** 聚焦页签栏子组件数（测试断言：页签数 = chips + 前缀标签 + 计数标签）。 */
    int focusTabBarComponentCountForTest() {
        return focusTabBar.getComponentCount();
    }

    /** Kotlin {@code val focusTabBarComponentCountForTest} 的 JVM 访问器等价入口（属性名读取用）。 */
    public int getFocusTabBarComponentCountForTest() {
        return focusTabBar.getComponentCount();
    }

    /** 聚焦隐藏计数提示（测试断言显隐与文案）。 */
    JLabel focusHiddenLabelForTest() {
        return focusHiddenLabel;
    }

    /** Kotlin {@code val focusHiddenLabelForTest} 的 JVM 访问器等价入口（属性名读取用）。 */
    public JLabel getFocusHiddenLabelForTest() {
        return focusHiddenLabel;
    }

    /** 测试驱动：等价右键「聚焦调用方方法/按请求聚焦」添加页签。 */
    void addFocusTabForTest(FocusTab tab) {
        addFocusTab(tab);
    }

    /** 测试驱动：等价页签 ✕ 关闭。 */
    void removeFocusTabForTest(FocusTab tab) {
        removeFocusTab(tab);
    }

    private void addFocusTab(FocusTab tab) {
        List<FocusTab> next;
        if (tab instanceof FocusTab.Request request) {
            next = FocusTab.addRequestTab(focusTabs, request.requestTag());
        } else if (tab instanceof FocusTab.Method) {
            // Kotlin：focusTabs.contains(tab) ? focusTabs.toList() : focusTabs + tab
            next = new ArrayList<>(focusTabs);
            if (!next.contains(tab)) {
                next.add(tab);
            }
        } else if (tab instanceof FocusTab.Template template) {
            next = FocusTab.addTemplateTab(focusTabs, template.structureKey(), template.count());
        } else {
            next = new ArrayList<>(focusTabs);
        }
        focusTabs.clear();
        focusTabs.addAll(next);
        rebuildFocusTabBar();
        refreshList();
    }

    private void removeFocusTab(FocusTab tab) {
        if (focusTabs.remove(tab)) {
            rebuildFocusTabBar();
            refreshList();
        }
    }

    /** 重建页签栏 chips（页签数 = 前缀标签 + N×chip + 计数标签）。 */
    private void rebuildFocusTabBar() {
        focusTabBar.removeAll();
        focusTabBar.add(new JLabel(MpSqlVisualizerBundle.message("log.focus.label")));
        for (FocusTab focusTab : focusTabs) {
            focusTabBar.add(buildFocusChip(focusTab));
        }
        focusTabBar.add(focusHiddenLabel);
        focusTabBar.setVisible(!focusTabs.isEmpty());
        if (focusTabBar.getParent() != null) {
            focusTabBar.getParent().revalidate();
            focusTabBar.getParent().repaint();
        }
    }

    /** 单个聚焦页签 chip：名称 + ✕ 关闭（关闭即摘除过滤器并刷新）。 */
    private JComponent buildFocusChip(FocusTab tab) {
        JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        chip.setOpaque(false);
        JLabel nameLabel = new JLabel(tab.label());
        nameLabel.setToolTipText(tab.label());
        chip.add(nameLabel);
        JButton closeButton = new JButton(AllIcons.Actions.Close);
        closeButton.setFocusable(false);
        closeButton.setToolTipText(MpSqlVisualizerBundle.message("log.focus.close"));
        closeButton.addActionListener(e -> removeFocusTab(tab));
        chip.add(closeButton);
        return chip;
    }

    /** 右键入口的聚焦页签解析（sourceFile+sourceLine → 方法行号区间页签）；解析失败返回 null。 */
    FocusTab.Method resolveCallerMethodFocus(LogSqlEntry entry) {
        CallerMethodRangeResolver.CallerMethodRange range =
                CallerMethodRangeResolver.resolve(project, entry.sourceFile(), entry.sourceLine());
        if (range == null) {
            return null;
        }
        return new FocusTab.Method(range.sourceFile(), range.startLine(), range.endLine(),
                range.className(), range.methodName());
    }

    // ---------- 顶部区域（工具栏行 + 聚焦页签栏 + 搜索条） ----------

    /** 顶部竖向容器：工具栏行（NORTH）+ 聚焦页签栏（CENTER，无页签隐藏不占高度）+ 搜索条（SOUTH）。 */
    private JPanel buildTopPanel() {
        JPanel top = new JPanel(new BorderLayout());
        top.add(buildToolbar(), BorderLayout.NORTH);
        top.add(focusTabBar, BorderLayout.CENTER);
        // T-HistorySearchToggles：挂搜索条宿主（搜索框 + MatchCase 开关），显隐契约不变
        top.add(searchBar, BorderLayout.SOUTH);
        return top;
    }

    private JPanel buildToolbar() {
        // T-M31.16：暂停按钮移除（捕捉开关已覆盖——用户方案 A）
        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar(TOOLBAR_ID, toolbarGroup, true);
        toolbar.setTargetComponent(this);
        _toolbarRef = toolbar; // 保存引用供 capturing 监听刷新

        updateTypeFilterButton();
        // T-UiCoord F2（2026-09-21 用户定案「不要两行」）：工具栏**恒为单行**——
        // 左侧动作图标组（CENTER，拿剩余宽度；窄了交给平台图标溢出）+ 右侧列表控制组
        // （EAST：慢查询徽章 / SQL类型 / 排序 / 去重勾选，勾选仍在最右）。
        // BorderLayout 结构上不存在折行可能，故任意宽度下都只有一行；原 WrapLayout 流式行退役
        // ——它按「谁最后谁折」处理放不下，2026-09-16 加排序单元、09-18 加第 5 枚图标后，
        // 460px 侧边栏下「排序」单元孤行到第二行（右留 300px 空档、两行左缘差 10px）。
        JPanel row = new JPanel(new BorderLayout());
        row.add(toolbar.getComponent(), BorderLayout.CENTER);
        toolbarComponent = toolbar.getComponent();

        // O-1：慢查询计数徽章。T-UiCoord F2：由「慢查询: N」全文改短徽章（复用存量键
        // log.panel.slowQueryBadge）让出一行余量，全文进 tooltip（见 updateSlowQueryLabel）。
        slowQueryLabel = new JLabel();
        // T-UN-13（T-UiNative）：写死红 → namedColor（亮值不变，主题可覆写）
        slowQueryLabel.setForeground(MpvsUiColors.SLOW_RED);
        slowQueryLabel.setFont(slowQueryLabel.getFont().deriveFont(Font.BOLD));
        slowQueryLabel.setVisible(false); // 默认隐藏，有慢查询时显示
        // 控制组：Flow(RIGHT) 单行右对齐；外包 Y 轴 BoxLayout + 上下 glue 使整组垂直居中
        // （FlowLayout 自身按顶部摆放，直接挂 EAST 会与图标组差几像素不共轴）
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        controls.setOpaque(false);
        controls.add(slowQueryLabel);
        controls.add(typeFilterButton);
        controls.add(dedupeCheckBox);
        toolbarControls = controls;
        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setOpaque(false);
        right.add(Box.createVerticalGlue());
        right.add(controls);
        right.add(Box.createVerticalGlue());
        row.add(right, BorderLayout.EAST);
        toolbarRow = row;
        return row;
    }

    /** Ctrl+F/⌘F 唤起搜索条（快捷键经 CommonShortcuts.getFind() 平台 keymap 自适应 mac/win）。 */
    public class ShowSearchAction extends AnAction {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

        ShowSearchAction() {
            registerCustomShortcutSet(CommonShortcuts.getFind(), list);
        }

        @Override
        public void update(AnActionEvent e) {
            // 图标必须在 update 里写（工具栏 ActionButton 按 presentation 渲染；
            // 不设则渲染为占位圆圈——用户实测缺陷）
            e.getPresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.Search);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.search"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            showSearch();
        }
    }

    private void showSearch() {
        if (!searchBar.isVisible()) {
            searchBar.setVisible(true);
            if (searchBar.getParent() != null) {
                searchBar.getParent().revalidate();
                searchBar.getParent().repaint();
            }
        }
        nativeSearch.getComponent().requestFocusInWindow();
    }

    private void hideSearch() {
        searchField.setText(""); // 非空时触发 DocumentListener 清过滤并刷新
        searchBar.setVisible(false);
        if (searchBar.getParent() != null) {
            searchBar.getParent().revalidate();
            searchBar.getParent().repaint();
        }
        list.requestFocusInWindow();
    }

    private void onSearchTextChanged() {
        // T-Perf-24（自 dev 移植）：防抖——停顿 200ms 才应用一次过滤
        searchDebounceTimer.restart();
    }

    private void applySearchText() {
        searchText = searchField.getText();
        // T-LogToSqlHint 批：喂本组件搜索历史（平台历史钮已接管为自有实现）
        nativeSearch.recordSearchQuery(searchText);
        refreshList();
    }

    /** 测试驱动：立即应用搜索框文本（headless 下防抖 Swing Timer 到点不可控），等价生产防抖回调。 */
    public void applySearchTextForTest() {
        applySearchText();
    }

    // ---------- SQL类型多选弹窗 ----------

    /**
     * 更新 SQL类型按钮文案（全部 / 无 / 类型组合）。
     *
     * T-UiCoord F2：标签控件退役后语义由 tooltip 承载——显示文本按预算截尾省略（多选组合可能很长），
     * tooltip 恒为「SQL类型：」+ 完整状态。
     */
    private void updateTypeFilterButton() {
        String state;
        if (activeFilter.containsAll(SqlLogPanelKt.FILTERABLE_TYPES)) {
            state = MpSqlVisualizerBundle.message("log.filter.all");
        } else if (activeFilter.isEmpty()) {
            state = MpSqlVisualizerBundle.message("log.filter.type.none");
        } else {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (StatementType type : SqlLogPanelKt.FILTERABLE_TYPES) {
                if (activeFilter.contains(type)) {
                    if (!first) sb.append(", ");
                    sb.append(labelForType(type));
                    first = false;
                }
            }
            state = sb.toString();
        }
        typeFilterButton.setText(clipToDropdownBudget(state, typeFilterButton));
        typeFilterButton.setToolTipText(MpSqlVisualizerBundle.message("log.filter.type.label") + state);
    }

    /**
     * T-UiCoord F2：下拉按钮显示文本按预算截尾省略（复用 meta 行的 {@link SqlPreviewPanelTextKt#clipTailToWidth} 纯函数）。
     * 预算用 JBUI 缩放表达——文本宽度随缩放变化，写死像素会在高 DPI 下失效。
     * tooltip 恒全文，省略只发生在按钮显示层。
     */
    private String clipToDropdownBudget(String text, JComponent target) {
        FontMetrics metrics = target.getFontMetrics(target.getFont());
        return cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelTextKt.clipTailToWidth(text, metrics::stringWidth, JBUI.scale(DROPDOWN_TEXT_BUDGET_PX));
    }

    // ---------- 工具栏动作 ----------

    /** T-Perf-17：historyPanel 当前是否已注册到 Disposer 树（防重复注册守卫标志）。 */
    private boolean historyPanelRegistered = false;

    /**
     * 添加历史 tab（T-HistoryTabAlways 后为构造期唯一调用点）。
     *
     * <p>T-Perf-17：Disposer 注册与防重入守卫保留——历史面板必须登记进本面板的 Disposer 树
     * （否则工具窗关闭时面板连同渲染器/防抖 Timer 不释放）；守卫在将来出现重复挂载路径时仍生效
     * （重复注册在 2026.x 平台报 "Object must not be registered twice" 错误弹窗）。
     */
    private void addHistoryTab() {
        if (!historyPanelRegistered) {
            Disposer.register(this, getHistoryPanel());
            historyPanelRegistered = true;
        }
        historyTab = tabs.addTab(new TabInfo(getHistoryPanel()).setText(MpSqlVisualizerBundle.message("log.tab.history")));
    }

    /** 测试访问器：主 tab 容器（历史 tab 常驻断言用；T-HistoryTabAlways 起 tab 恒在，无显隐路径）。 */
    public com.intellij.ui.tabs.JBTabs tabsForTest() {
        return tabs;
    }

    /**
     * 捕捉开关动作（T-CaptureBack）：回迁面板工具栏首位（T-UN-11 曾迁工具窗标题栏，
     * 新 UI 下标题栏动作悬停才可见致入口不可发现，回归面板）。图标/文案在 CaptureAction.update
     * 按 manager.capturing 态输出；测试断言直接读 presentation，不依赖按钮刷新。
     */
    private final AnAction captureToggleAction;

    /** 对外 API（T-UiNative T-UN-11）：捕捉开关动作——SqlLogToolWindowFactory 经此挂工具窗标题栏。 */
    public AnAction getCaptureToggleAction() {
        return captureToggleAction;
    }

    /**
     * 捕捉开关（T-CaptureBack：回迁面板工具栏首位；仍经 captureToggleAction 暴露供标题栏兼容）。
     * 图标/文案在 update 按 manager.capturing 态输出（平台更新机制驱动，不再反射强改按钮）。
     */
    public class CaptureAction extends AnAction {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

        @Override
        public void update(AnActionEvent e) {
            if (manager.isCapturing()) {
                e.getPresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.CaptureStop);
                e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.stop"));
                e.getPresentation().setDescription(manager.isWaitingForConsole()
                        ? MpSqlVisualizerBundle.message("log.panel.capture.waiting.desc")
                        : null);
            } else {
                e.getPresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.CaptureStart);
                e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.capture"));
                e.getPresentation().setDescription((String) null);
            }
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            if (manager.isCapturing()) {
                manager.stopCapture();
            } else {
                manager.startCapture(true);  // 手动点击：设置 userArmed 持久标志
            }
        }
    }

    /**
     * R53：增强捕获暂停/恢复（工具窗入口）。
     *
     * <p>T-AgentPauseVisibility（2026-10-19，用户拍板方案 B）：<b>临时全隐藏</b>。
     * 增强捕获已默认开启且无提示，该按钮在应用未运行时没有可暂停对象，并与捕捉按钮形成
     * 「播放/暂停」误读；先整体隐藏，动作对象仍保留在工具栏组内（不删能力代码）。
     * 代价：暂停 agent 通道的 UI 入口暂时不可达（关闭总开关仍会回落控制台）。
     * 恢复条件：方案 A（agent 已挂载或暂停态才显示）落地，或新增状态栏/右键菜单入口。</p>
     *
     * <p>历史语义（保留在 actionPerformed）：暂停只停 agent 通道（事件在入队点丢弃）、
     * 控制台通道照常；暂停≠关闭——连接/心跳/CONFIG 链不动。</p>
     */
    private class AgentPauseAction extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            // 方案 B：无条件隐藏。不要改回 isEnhancedEnabled()——恢复时必须走方案 A 的条件可见。
            e.getPresentation().setVisible(false);
            e.getPresentation().setEnabled(false);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            AgentManager am = AgentManager.getInstance(project);
            am.setUserPaused(!am.isUserPaused());
        }
    }

    class ClearAction extends AnAction {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

        ClearAction() {
            super(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.Clear);
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.Clear);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.clear"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            // 清空也是捕获事件——尾随状态机须感知（master SqlLogPanel.kt onClear 同款），
            // 否则清除后尾随错停在 FOLLOWING/PAUSED 的旧态上
            tailFollow.onClear();
            manager.getEntries().clear();
        }
    }

    /**
     * T-SqlLogPerf：「滚动到底部」动作（跳列表底 + 恢复尾部跟随）。
     * 图标复用 {@link AllIcons.General.ArrowDown}（本文件 SQL类型/排序按钮同款下箭头，向下语义一致）。
     */
    public class ScrollToEndAction extends AnAction {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.ScrollToEnd);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.scrollBottom"));
            e.getPresentation().setDescription(MpSqlVisualizerBundle.message("log.panel.scrollBottom.tip"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            scrollToBottom(true);
        }
    }

    public class SettingsAction extends AnAction {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.Settings);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.settings"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            // T-SettingsFocusFromLink：统一走主设置页入口（hint=null 只切到 SQL 日志页不聚焦行）
            cn.song.mybatisplus.visualizer.config.PluginSettingsFolderConfigurable
                    .openSqlLogSettingsFocused(project, null);
        }
    }

    /**
     * T-SqlSuppress（O-69）：屏蔽管理入口（D1 定案主入口，插设置旁；D7 图标漏斗
     * {@code AllIcons.General.Filter}——262 jar 实渲染比对后用户选定，全栏无漏斗形不撞车）。
     * 打开与右键「管理屏蔽规则…」同一非模态对话框。
     */
    public class ManageSuppressAction extends AnAction {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.Suppress);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.suppress.manage"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            new SqlSuppressManagerDialog(project).show();
        }
    }

    /** T-Community：交流群弹框入口（工具栏动作组；二维码异步加载，见 CommunityDialog）。 */
    public class CommunityAction extends AnAction {
        // T-SqlLogToolbarEdt W1：master 对本动作同样显式声明 BGT（虽未覆写 update，声明无害且与组内一致）
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

        CommunityAction() {
            super(MpSqlVisualizerBundle.message("log.community.action"),
                    MpSqlVisualizerBundle.message("log.community.action.tip"),
                    MpvsIcons.Community);
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            CommunityDialog dialog = new CommunityDialog(project);
            dialog.startLoading();
            dialog.show();
        }
    }

    /**
     * T-M39：帮助入口（? 按钮，位于设置按钮旁）→ 内嵌浏览器打开本地 HTML 帮助文档。
     */
    public class HelpAction extends AnAction {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.Help);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.help"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            if (!JBCefApp.isSupported()) {
                LOG.info("[HelpAction] JCEF not supported, falling back to system browser");
                browseOnlineDoc();
                return;
            }
            openLocalHtml();
        }

        /** 降级：系统浏览器打开在线文档。
         *  单元测试模式必须短路——BrowserUtil.browse 不走沙盒隔离，直接 ShellExecute
         *  拉起宿主机默认浏览器（HelpUiTest 真实触发 actionPerformed，定时回归任务期间
         *  周期性弹出用户 Edge，2026-09-11 真机实证）。 */
        private void browseOnlineDoc() {
            if (ApplicationManager.getApplication().isUnitTestMode()) {
                LOG.info("[HelpAction] Unit test mode, skipping system browser fallback");
                return;
            }
            BrowserUtil.browse(HELP_DOCUMENTATION_URL);
        }

        /** 内嵌浏览器打开本地 HTML 帮助页。 */
        private void openLocalHtml() {
            String htmlContent = loadText("html", "marketplace-custom-page.html");
            if (htmlContent.isBlank()) {
                LOG.warn("[HelpAction] Help HTML resource is empty, falling back to online doc");
                browseOnlineDoc();
                return;
            }
            try {
                // 暗色检测：IDE 主题为暗色时注入 class（JCEF 不读取 IDE 的 prefers-color-scheme）
                if (isDarkTheme()) {
                    htmlContent = htmlContent.replaceAll("(?i)<body>", "<body class=\"dark\">");
                    LOG.info("[HelpAction] Dark theme detected, injected .dark class");
                }
                LOG.info("[HelpAction] Opening local help HTML via loadHTML");
                HTMLEditorProvider.openEditor(project, HELP_TAB_TITLE, htmlContent);
            } catch (Throwable ex) {
                LOG.warn("[HelpAction] Failed to open local help HTML, falling back to online doc", ex);
                browseOnlineDoc();
            }
        }

        /** 检测当前 IDE 是否为暗色主题（JBColor.isBright 反映用户设置，不依赖 OS）。 */
        private boolean isDarkTheme() {
            return !JBColor.isBright();
        }

        /** 从 resources 加载文本文件。 */
        private String loadText(String basePath, String fileName) {
            try {
                InputStream stream = HelpAction.class.getClassLoader().getResourceAsStream(basePath + "/" + fileName);
                if (stream == null) {
                    return "";
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    return sb.toString();
                }
            } catch (Throwable t) {
                return "";
            }
        }
    }

    // ---------- T-AgentDiagWrap T-04：空态文案的「文本 → 显示」 ----------

    /**
     * 最近一次写入空态的纯文本（{@code \n} 分隔语义行）。宽度变化时按新宽度重排同一份文本，
     * 所以必须留住它——否则 resize 只能重排「上一次的视觉行」，再窄一档就还原不了。
     */
    private String lastEmptyText = null;

    /** 当前空态是否带「增强捕获」链接（诊断行不带——常规空态专属，见 {@link #applyEmptyTextWithEnhancedLink}）。 */
    private boolean emptyTextLinkEnabled = false;

    /** 写入空态文案（纯文本入口，渲染层在此做折行）。 */
    private void applyEmptyText(String plain) {
        lastEmptyText = plain;
        emptyTextLinkEnabled = false;
        renderEmptyText();
    }

    /**
     * 写入空态文案 + 尾部可点「增强捕获」链接（20261008 验收缺陷 #3：第三径原为纯文本不可点，
     * 与验收文档 3.22「点该引导走到增强捕获的开启流程」不符）。仅常规空态用；诊断行不加链接。
     */
    private void applyEmptyTextWithEnhancedLink() {
        lastEmptyText = MpSqlVisualizerBundle.message("log.panel.empty");
        emptyTextLinkEnabled = true;
        renderEmptyText();
    }

    /** 按当前列表可用宽度重排并逐视觉行写入（EDT）。 */
    private void renderEmptyText() {
        String plain = lastEmptyText;
        if (plain == null) {
            return;
        }
        com.intellij.util.ui.StatusText status = list.getEmptyText();
        status.clear();
        SqlLogPanelKt.applyEmptyTextLines(status, plain, emptyTextWidthPx(),
                s -> list.getFontMetrics(list.getFont()).stringWidth(s));
        if (emptyTextLinkEnabled) {
            // T-SettingsFocusFromLink：引导链接（**单一整体入口**——用户定「一个整体，点击都跳到目标位置」：
            // 不再拆页签/设置页/增强捕获三个去向的分链），点击打开主设置页并定位高亮「增强捕获」设置行
            // （空态文案里「参数实参与精确耗时…在设置页开启 →」所指）。经 appendLinkRow 单点——
            // 平台 appendSecondaryText 固定写第二行的坑见该函数 KDoc。
            CaptureDiagnostics.appendLinkRow(
                    status,
                    List.of(new kotlin.Pair<>(
                            MpSqlVisualizerBundle.message("log.panel.empty.link"),
                            (java.awt.event.ActionListener) e ->
                                    cn.song.mybatisplus.visualizer.config.PluginSettingsFolderConfigurable.openSqlLogSettingsFocused(
                                            project,
                                            MpSqlVisualizerBundle.message("settings.enhancedCapture")))));
        }
    }

    /** 空态文案可用宽度：列表宽度扣掉自身内边距与左右余量；尚未布局（首帧 / 隐藏卡）时给兜底值。 */
    private int emptyTextWidthPx() {
        java.awt.Insets insets = list.getInsets();
        int w = list.getWidth() - insets.left - insets.right - CaptureDiagnostics.EMPTY_TEXT_EDGE_PADDING_PX * 2;
        return w <= 0 ? CaptureDiagnostics.EMPTY_TEXT_FALLBACK_WIDTH_PX : w;
    }

    // ---------- T-CaptureTruth T-CT-03：零产出诊断 ----------

    /**
     * 诊断行同步（EDT）：更新列表空态文案 + 管理定时器生命周期。
     * 取数全部为只读内存态（buffer 快照 / 桥计数 / AgentManager 状态），不触发平台调用。
     */
    /**
     * R53：双通道捕获状态栏（SQL 日志页签底部一行，捕捉期间常驻；非捕捉态整条隐藏）。
     * 两个 JLabel（控制台通道 / 增强通道）+ 语义分隔符；文案由 {@link CaptureStatusBar#captureStatusLineText}
     * 纯函数产出，{@link #syncCaptureStatusBar} 在 2s 诊断定时器上无锁轮询刷新（V80①：取数全为只读内存态）。
     * 淡色（UIUtil.getContextHelpForeground）与空态诊断行标注同视觉惯例，不抢正文。
     */
    private javax.swing.JPanel buildStatusBar() {
        statusConsoleLabel = new javax.swing.JLabel();
        statusConsoleLabel.setForeground(com.intellij.util.ui.UIUtil.getContextHelpForeground());
        statusAgentLabel = new javax.swing.JLabel();
        statusAgentLabel.setForeground(com.intellij.util.ui.UIUtil.getContextHelpForeground());
        javax.swing.JPanel bar = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        bar.setOpaque(false);
        bar.add(statusConsoleLabel);
        javax.swing.JLabel sep = new javax.swing.JLabel(SEP_LABEL);
        sep.setForeground(com.intellij.util.ui.UIUtil.getContextHelpForeground());
        bar.add(sep);
        bar.add(statusAgentLabel);
        bar.setBorder(com.intellij.util.ui.JBUI.Borders.empty(0, 6, 3, 6));
        // 初始隐藏：面板构建时捕捉多半未起，先显示会留一条无信息的空带
        bar.setVisible(false);
        statusBarPanel = bar;
        return bar;
    }

    /** R53 测试缝：双通道状态栏两段标签（第一段=控制台通道，第二段=增强通道）。 */
    java.util.List<javax.swing.JLabel> statusBarLabelsForTest() {
        java.util.List<javax.swing.JLabel> labels = new java.util.ArrayList<>();
        labels.add(statusConsoleLabel);
        labels.add(statusAgentLabel);
        return labels;
    }

    /** R53 测试缝：同步驱动状态栏刷新（等价 2s 诊断定时器的一拍）。 */
    void syncStatusBarForTest() {
        syncCaptureStatusBar();
    }

    /** R53 测试缝：状态栏宿主面板（非捕捉态应整条隐藏——不留分隔符/空白高度）。 */
    javax.swing.JPanel statusBarForTest() {
        return statusBarPanel;
    }

    /**
     * R53：双通道捕获状态栏刷新（EDT，挂在诊断定时器上 2s 无锁轮询）。
     * 文案由纯函数 {@link CaptureStatusBar#captureStatusLineText} 产出（V80①：取数全为只读内存态）；
     * 非捕捉态整条面板隐藏（只清文案会留下分隔符与空白高度）。
     */
    private void syncCaptureStatusBar() {
        javax.swing.JLabel label = statusConsoleLabel;
        if (label == null) {
            return;
        }
        CaptureStatusBar.Pair texts = null;
        if (manager.isCapturing()) {
            try {
                cn.song.mybatisplus.visualizer.log.agent.AgentManager am =
                        cn.song.mybatisplus.visualizer.log.agent.AgentManager.getInstance(project);
                texts = CaptureStatusBar.captureStatusLineText(new CaptureStatusBar.CaptureStatusLine(
                        true,
                        manager.getConsoleBridge().attachedConsoles.size(),
                        manager.getConsoleBridge().consoleLinesSeen.get(),
                        am.statusSummary(),
                        am.healthyPids(),
                        am.isUserPaused(),
                        cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter.receivedEvents()));
            } catch (Throwable ignored) {
                texts = null; // runCatching().getOrNull() 等价
            }
        }
        if (texts == null) {
            label.setText("");
            if (statusAgentLabel != null) {
                statusAgentLabel.setText("");
            }
            setStatusBarVisible(false);
        } else {
            label.setText(texts.first());
            if (statusAgentLabel != null) {
                statusAgentLabel.setText(texts.second());
            }
            setStatusBarVisible(true);
        }
    }

    /** R53：状态栏显隐（BorderLayout 跳过不可见子件，须 revalidate 才重排高度）。 */
    private void setStatusBarVisible(boolean visible) {
        javax.swing.JPanel bar = statusBarPanel;
        if (bar == null || bar.isVisible() == visible) {
            return;
        }
        bar.setVisible(visible);
        java.awt.Container parent = bar.getParent();
        if (parent != null) {
            parent.revalidate();
        }
    }

    private void syncDiagnostics() {
        // R53：定时器捕捉期间常跑（状态栏持续刷新）；空态判定改 O(1) size() 而非 snapshot() 全量拷贝
        syncCaptureStatusBar();
        boolean active = manager.isCapturing() && manager.getEntries().size() == 0;
        String text;
        if (active) {
            String enhancedStatus;
            try {
                enhancedStatus = cn.song.mybatisplus.visualizer.log.agent.AgentManager.getInstance(project).statusSummary();
            } catch (Throwable ignored) {
                enhancedStatus = null; // runCatching 等价
            }
            long suppressed;
            try {
                SqlSuppressRuleManager mgr = SqlSuppressRuleManager.getInstance();
                long sum = 0L;
                for (SqlSuppressRule rule : mgr.getRules()) {
                    sum += mgr.hitCount(rule.getId());
                }
                suppressed = sum;
            } catch (Throwable ignored) {
                suppressed = 0L;
            }
            text = CaptureDiagnostics.captureDiagnosticsText(new CaptureDiagnostics(
                    true,
                    manager.isWaitingForConsole(),
                    0,
                    manager.getConsoleBridge().attachedConsoles.size(),
                    manager.getConsoleBridge().consoleLinesSeen.get(),
                    enhancedStatus,
                    // T-RA-03：两个「行去哪了」计数——守卫误丢行 / EP 短路后条目未落到本面板时，
                    // 它们是面板唯一可见的证据（此前生产侧零读取，故障被伪装成「控制台没输出」）。
                    manager.getConsoleBridge().getReplayDropCount(),
                    manager.getConsoleBridge().epActiveShortCircuitCount.get(),
                    // T-DevAcceptFix2 ⑤：增强通道已收事件数（恒显示）——「事件到没到」的唯一判别量
                    cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter.receivedEvents(),
                    // T-DevAcceptFix2 ⑩：屏蔽规则命中次数（应用级累计，与规则面板同源计数）——
                    // 「事件到了却 0 条」时最容易被忽略的一条吃行通道（用户自建规则，设计内但必须可见）
                    suppressed));
        } else {
            text = null;
        }
        if (text != null) {
            applyEmptyText(text);
        } else {
            applyEmptyTextWithEnhancedLink();
        }
        // R53：定时器捕捉期间常跑（状态栏持续刷新）；非捕捉态停表清栏
        if (manager.isCapturing()) {
            if (diagnosticsTimer == null) {
                diagnosticsTimer = new javax.swing.Timer(DIAGNOSTICS_TICK_MS, e -> syncDiagnostics());
                diagnosticsTimer.setRepeats(true);
                diagnosticsTimer.start();
            }
        } else {
            if (diagnosticsTimer != null) {
                diagnosticsTimer.stop();
            }
            diagnosticsTimer = null;
        }
    }

    // ---------- 条目列表 ----------

    private JBScrollPane buildListPane() {
        applyEmptyTextWithEnhancedLink();
        // T-AgentDiagWrap T-04：列表宽度变化即重排空态文案——否则用户拉窄/拉宽工具窗后，
        // 文案还按旧宽度折行（窄窗下会重新长出被裁掉的尾巴，等于修复只在首帧生效）。
        list.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                renderEmptyText();
            }
        });
        LogEntryListCellRenderer renderer = new LogEntryListCellRenderer();
        entryCellRenderer = renderer;
        list.setCellRenderer(renderer);
        applyListCellHeight();
        // T-UiNative T-UN-05（本次修复）：主日志列表构造即可见，速度搜索在此挂载（不移进
        // SqlEntryList.init——历史二级列表复用它却构造于 CardLayout 隐藏卡，构造期挂载不生效）
        ListSpeedSearch.installOn(list, element -> SqlLogPanelKt.withTerminator(element.restoredSql()));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // T-ClickPreview：先转发（仅左键；单击命中行才排程预览/双击停表并关旧预览），双击再开完整弹窗
                // T-ClickPreviewCopyRestore：修饰键一并转发——Ctrl/Shift 单击（多选第一击）不排程预览
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    // T-ListPreviewDock：单击转发停靠预览宿主（单击只选中+一次性就地迁移提示）
                    previewHost.onRowClick(e);
                }
                if (e.getClickCount() >= 2) {
                    LogSqlEntry selected = list.getSelectedValue();
                    if (selected != null) {
                        openPreview(selected);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    SqlLogPanelActionsKt.showContextMenu(SqlLogPanel.this, e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    SqlLogPanelActionsKt.showContextMenu(SqlLogPanel.this, e);
                }
            }
        });
        // T-SqlLogPerf + O-148：尾部跟随的用户意图归因——真实输入事件（滚轮/滚动条按下/导航键）
        // 才暂停跟随；程序性追加（新条目进模型）不抢视口，跟随态下由刷新链贴底。
        JBScrollPane scrollPane = new JBScrollPane(list);
        scrollPane.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        listScrollPane = scrollPane;
        scrollPane.addMouseWheelListener(event -> {
            if (event.getWheelRotation() != 0) tailFollow.onUserScroll();
        });
        scrollPane.getVerticalScrollBar().addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                tailFollow.onUserScroll();
            }
        });
        // T-ListMetaRevert：用户手动滚回底部时恢复自动跟随。
        // 改造前只有「滚动到底部」按钮会调 onScrollToEnd()，用户滚回去后必须再点一下按钮
        // 才能恢复——违反「滚到底就继续跟随」的直觉。
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (e.getValueIsAdjusting()) return;
            if (!(e.getAdjustable() instanceof javax.swing.JScrollBar)) return;
            javax.swing.JScrollBar bar = (javax.swing.JScrollBar) e.getAdjustable();
            // 真正能滚到的最大值是 maximum - visibleAmount（Swing 的 maximum 是内容总高语义，
            // 直接比 maximum 永远不成立——这类 bug 的典型写法错误）。
            int maxScrollable = bar.getMaximum() - bar.getVisibleAmount();
            if (maxScrollable > 0 && bar.getValue() >= maxScrollable - SCROLL_BOTTOM_TOLERANCE_PX) {
                tailFollow.onScrollToEnd();
            }
        });
        java.util.Set<Integer> navigationKeys = java.util.Set.of(
                java.awt.event.KeyEvent.VK_UP, java.awt.event.KeyEvent.VK_DOWN,
                java.awt.event.KeyEvent.VK_PAGE_UP, java.awt.event.KeyEvent.VK_PAGE_DOWN,
                java.awt.event.KeyEvent.VK_HOME, java.awt.event.KeyEvent.VK_END);
        java.awt.event.KeyAdapter navigationListener = new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (navigationKeys.contains(e.getKeyCode())) tailFollow.onUserScroll();
            }
        };
        list.addKeyListener(navigationListener);
        scrollPane.getVerticalScrollBar().addKeyListener(navigationListener);
        return scrollPane;
    }

    /**
     * 行高随显示模式分流（T-ListMode × T-LogListFreeze-04 权衡）：
     * SINGLE_LINE 固定行高——WideSelectionListUI 短路逐条测量走 O(1) 快路径
     * （原可变行高 × HTML 排版 = 16s EDT Freeze；片段通道后固定行高仍是流式灌入的最优路径）；
     * KEYWORD_WRAP/FORMATTED 可变行高——条目行数不等，固定高会裁行；多行片段有条目级缓存，
     * 逐条测量只剩轻量组件布局（无 HTML 解析，冻结量级不复现；真机大日志表现见计划文档）。
     * 全文查看由单击预览/双击弹窗承接。
     */
    void applyListCellHeight() {
        list.setFixedCellHeight(PluginSettings.getInstance().sqlListDisplayMode() == SqlListDisplayMode.SINGLE_LINE
                ? measureFixedCellHeight()
                : -1);
    }

    /** T-LogListFreeze-04：固定行高实测——样例条目渲染一次取首选高（两行文本 + 5,8 空边框 + 1px 分隔线）。 */
    private int measureFixedCellHeight() {
        LogSqlEntry sample = new LogSqlEntry("SELECT 1", "SELECT 1", List.of());
        Component c = entryCellRenderer.getListCellRendererComponent(list, sample, 0, false, false);
        int measured = c.getPreferredSize().height;
        if (measured > 0) {
            return measured;
        }
        return list.getFontMetrics(list.getFont()).getHeight() * 2 + 13;
    }

    // ---------- 刷新管线（类型过滤 → 聚焦过滤 → 搜索过滤 → 排序 → 渲染） ----------

    /**
     * 刷新列表（过滤 → 搜索 → 排序 → 差分渲染）。
     *
     * 执行顺序：
     * 1. filterBySuppress：按屏蔽规则指纹集滤存量（T-SqlSuppress O-69；管线最前——空集零成本）
     * 2. filterByType：按 activeFilter 筛选（全选 = 全部，含 OTHER）
     * 3. filterByFocus：按 activeFocus 聚焦（M56 T-M56-00 基础设施；null = 透传）
     * 4. filterBySearch：按 searchText 搜索（大小写不敏感）
     * 5. applySort：按 sortMode 排序（降序耗时）
     * 6. 差分渲染：T-Perf-03 增量刷新——流式追加免全量重建；无法增量时回退分批全量重建
     *
     * T-LogListFreeze-04：HTML 缓存随片段通道退役——徽章计数在渲染时直读 dedupeCounts，
     * 原「清缓存/选择性失效」机制一并移除。
     */
    private void refreshList() {
        long gen = ++refreshGeneration;
        // T-SqlLogPerf P5（20261006-1020 计划）：上一轮全量重建链未走完就来了新刷新时，增量差分会被
        // 「model.size != lastRendered.size」拒绝 → 每次刷新都重启重建链，风暴期列表永远停在首屏 30 条
        // （用户体感「监听加载有延迟」的实态）。进差分前同步补完剩余批次，让差分基线对齐。
        // F80：超大余量时补完让路（false＝本拍刷新挂起，链尾补跑）——单拍同步写
        // 量不再随 logMaxCount 线性放大。
        if (!flushRebuildChainSync()) return;
        List<LogSqlEntry> snapshot = LogPerfProbe.measure("LogPanel.refreshList.snapshot",
                () -> "size=" + manager.getEntries().size(),
                () -> manager.getEntries().snapshot());
        // T-CaptureTruth T-CT-03：条目变化同步诊断行（有条目即还原默认空态文案并停表）
        syncDiagnostics();

        // 去重丢弃计数快照（徽章渲染数据源）：徽章在元信息行渲染时直读，无缓存失效链
        dedupeCounts = manager.getEntries().dedupeCountsSnapshot();
        entryCellRenderer.dedupeCounts = dedupeCounts;

        // O-90：同模板计数快照（**全量条目**聚合——徽章计数不随当前过滤/搜索波动；
        // snapshot 本就为过滤链物化，structureKey 惰性缓存后单遍 O(N)）
        templateCounts = LogSqlModelsKt.structureKeyCounts(snapshot);
        entryCellRenderer.templateCounts = templateCounts;

        // T-SqlSuppress：屏蔽存量过滤插管线最前（与类型过滤同级；键集经 SqlLogManager 订阅同步）
        List<LogSqlEntry> filtered = SqlLogPanelKt.filterByFocus(
                SqlLogPanelKt.filterByType(
                        SqlLogPanelKt.filterBySuppress(snapshot, manager.getEntries().getSuppressKeys()),
                        activeFilter),
                activeFocus);
        // T-M55-03/04：动态聚焦页签层（页签=过滤器，多开并集；离散增删走 FullRebuild，差分零改动）
        List<LogSqlEntry> focused = FocusTab.filterByFocusTabs(filtered, focusTabs);
        // T-HistorySearchToggles：搜索旗随 MatchCase 开关（默认关=忽略大小写，与现状一致）
        // T-SearchToggleNativeLook：正则旗随 Regex 钮（20261010 用户令日志页补正则，语义与历史页同链）
        List<LogSqlEntry> searched = SqlLogPanelKt.filterBySearch(
                focused, searchText, logMatchCase(), logRegex(), regexMemo);
        List<LogSqlEntry> newVisible = applySort(searched);
        int evicted = manager.getEntries().drainEvictedCount();

        // T-LogListRail：进度条归一基准取**当前可见条目**的最大耗时（不随全量缓冲波动，
        // 与过滤/排序结果一致；无耗时条目返回 null → 所有条画空槽）。
        // 用单遍遍历而非 mapNotNull{}.maxOrNull()：后者会为几千~几万条分配一个临时列表，
        // 而本行每次刷新都跑（流式灌入下每秒多次），是纯 GC 压力。
        entryCellRenderer.maxDurationMs = LogEntryRailKt.maxDurationOf(newVisible);

        int modelSizeBefore = listModel.size();
        long visibleTailSequenceBefore = lastRendered.isEmpty() ? 0L : lastRendered.get(lastRendered.size() - 1).sequenceNumber();
        if (!applyIncremental(newVisible, evicted)) {
            applyFullRebuild(newVisible, gen);
        }
        boolean tailAdvancedAtCapacity = evicted > 0
                && (!newVisible.isEmpty()
                    && newVisible.get(newVisible.size() - 1).sequenceNumber() > visibleTailSequenceBefore);
        // T-SqlLogPerf + O-148：尾部跟随——仅当列表真的长高（新条目追加）才贴底；
        // 搜索/过滤收窄不打扰浏览位置。缓冲区满时头部淘汰与尾部追加相抵，模型长度不变；
        // 只随确有新可见条目时贴底。
        if (tailFollow.getShouldFollow() && (listModel.size() > modelSizeBefore || tailAdvancedAtCapacity)) {
            list.ensureIndexIsVisible(listModel.size() - 1);
        }

        // 聚焦隐藏计数提示（聚焦层单独度量：输入 filtered → 输出 focused）
        if (focusTabs.isEmpty()) {
            focusHiddenLabel.setVisible(false);
        } else {
            int hidden = filtered.size() - focused.size();
            focusHiddenLabel.setText(MpSqlVisualizerBundle.message("log.focus.hidden", String.valueOf(hidden)));
            focusHiddenLabel.setVisible(hidden > 0);
        }

        // O-1：同步慢查询阈值 + 更新计数（Phase C）
        slowQueryCounter.setThresholdMs(PluginSettings.getInstance().getState().getSlowQueryThresholdMs());
        slowQueryCounter.reset();
        for (LogSqlEntry it : snapshot) {
            slowQueryCounter.report(it.executionTimeMs());
        }
        updateSlowQueryLabel();
    }

    /**
     * M56 T-M56-00：设置动态聚焦过滤（页签/请求聚合驱动；null 或全空维度 = 取消聚焦）。
     * 桥通道不写此状态，行为与现状逐字一致。过滤值变化经 refreshList 全量差分呈现
     * （离散增删走 FullRebuild，无需特殊增量分支）。
     */
    public void setFocusFilter(SqlLogPanelKt.FocusFilter focus) {
        SqlLogPanelKt.FocusFilter next = focus != null && !focus.isPassthrough() ? focus : null;
        if (java.util.Objects.equals(next, activeFocus)) {
            return;
        }
        activeFocus = next;
        refreshList();
    }

    /**
     * T-Perf-03（自 dev 移植）：增量应用差分（同步完成，无异步追加链）。模型与 lastRendered 尺寸不一致
     * （旧异步追加链被新刷新中断/外部改动）时拒绝增量——全量重建兜底。
     */
    private boolean applyIncremental(List<LogSqlEntry> newVisible, int evicted) {
        if (listModel.size() != lastRendered.size()) {
            return false;
        }
        SqlLogPanelKt.VisibleDiff diff = SqlLogPanelKt.diffVisible(lastRendered, newVisible, evicted);
        if (diff instanceof SqlLogPanelKt.VisibleDiff.Same) {
            lastRendered = newVisible;
            return true;
        } else if (diff instanceof SqlLogPanelKt.VisibleDiff.HeadEvictAppend hea) {
            for (int i = 0; i < hea.evicted(); i++) {
                listModel.remove(0);
            }
            for (LogSqlEntry e : hea.appended()) {
                listModel.addElement(e);
            }
            lastRendered = newVisible;
            return true;
        } else if (diff instanceof SqlLogPanelKt.VisibleDiff.Insert ins) {
            for (int i = 0; i < ins.entries().size(); i++) {
                listModel.add(ins.index() + i, ins.entries().get(i));
            }
            lastRendered = newVisible;
            return true;
        } else if (diff instanceof SqlLogPanelKt.VisibleDiff.TailRemove tr) {
            for (int i = lastRendered.size() - 1; i >= tr.from(); i--) {
                listModel.remove(i);
            }
            lastRendered = newVisible;
            return true;
        }
        return false; // FullRebuild
    }

    /**
     * 全量重建（分批 EDT 写入 + generation 守卫）。
     *
     * <p>T-Perf-03 追加链 generation 守卫（自 dev 移植）：新 refreshList 启动后旧链在新 chunk 写入前检测代际
     * 并终止——杜绝旧快照剩余条目继续追加造成的幽灵/重复条目。
     * T-SqlLogPerf P5：剩余批次游标提为字段 [rebuildRemaining]——refreshList 进差分前经
     * [flushRebuildChainSync] 同步补完在途链（旧实现链被新刷新抢占后只能整体重启，见 flushRebuildChainSync 注释）。
     */
    private void applyFullRebuild(List<LogSqlEntry> sorted, long gen) {
        lastRendered = sorted;
        rebuildRemaining = null;
        // T-LogListFreeze-01：批量抑制布局 + T-LogListFreeze-03：分批 EDT 写入
        list.setIgnoreRepaint(true);
        listModel.clear();
        if (!sorted.isEmpty()) {
            // 首屏批次（立即写入，保证快速首屏）
            List<LogSqlEntry> firstBatch = sorted.subList(0,
                    Math.min(SqlLogPanelKt.REFRESH_FIRST_BATCH_SIZE, sorted.size()));
            listModel.addAll(firstBatch);
        }
        list.setIgnoreRepaint(false);
        list.revalidate();
        list.repaint();

        // T-LogListFreeze-03：剩余批次通过 invokeLater 异步追加（每批一次 EDT 脉冲，避免长冻结）
        rebuildRemaining = sorted.size() > SqlLogPanelKt.REFRESH_FIRST_BATCH_SIZE
                ? sorted.subList(SqlLogPanelKt.REFRESH_FIRST_BATCH_SIZE, sorted.size()).iterator()
                : null;
        if (rebuildRemaining != null) {
            ApplicationManager.getApplication().invokeLater(this::appendRebuildChunk);
        }
    }

    /** T-SqlLogPerf P5：在途全量重建链的剩余游标（null = 无在途链）。 */
    private Iterator<LogSqlEntry> rebuildRemaining = null;

    /** F80（T-PerfSweep24）：flushRebuildChainSync 单拍同步补完封顶——默认设置（logMaxCount=200）
     * 在途余量 ≤170 全量补完（行为不变）；用户调大 logMaxCount 至数千时，超出部分不再单拍
     * 同步写数千条模型（EDT 冻结随 logMaxCount 线性放大），改由 appendRebuildChunk 逐拍链推进、
     * 本拍刷新挂起链尾补跑。 */
    private static final int FLUSH_SYNC_MAX = 512;

    /** F80：链后补跑挂起标志（超大余量让路本拍刷新，链排空即补跑一次）。 */
    private boolean pendingRefreshAfterChain = false;

    /** 追加一批（REFRESH_CHUNK_SIZE 条）；写完仍有余量则排下一拍。 */
    private void appendRebuildChunk() {
        Iterator<LogSqlEntry> remaining = rebuildRemaining;
        if (remaining == null) {
            return;
        }
        List<LogSqlEntry> chunk = new ArrayList<>();
        for (int i = 0; i < SqlLogPanelKt.REFRESH_CHUNK_SIZE && remaining.hasNext(); i++) {
            chunk.add(remaining.next());
        }
        listModel.addAll(chunk);
        rebuildRemaining = remaining.hasNext() ? remaining : null;
        if (rebuildRemaining != null) {
            ApplicationManager.getApplication().invokeLater(this::appendRebuildChunk);
        } else if (pendingRefreshAfterChain) {
            // F80：链排空——补跑挂起的刷新（风暴期多次挂起合并为一次补跑）
            pendingRefreshAfterChain = false;
            ApplicationManager.getApplication().invokeLater(this::refreshList);
        }
    }

    /**
     * T-SqlLogPerf P5（20261006-1020 计划）：同步补完在途全量重建链。
     *
     * <p>旧形态：重建链在途时 {@code listModel.size != lastRendered.size}，增量差分必被拒绝 → 风暴期
     * 每 200ms 的新刷新都重启重建链，链永远走不完，列表停在首屏 30 条且反复 clear/addAll 白耗 EDT。
     * 补完后 model 与 lastRendered 对齐，后续刷新走增量差分。
     */
    /**
     * F80（T-PerfSweep24）补完保留分批：余量 ≤ {@link #FLUSH_SYNC_MAX}（默认设置 ≤170）照旧单拍同步
     * 补完——默认行为逐字不变；余量超大（用户 logMaxCount 数千）不再单拍同步写数千条
     * （EDT 冻结随 logMaxCount 线性放大），本拍刷新让路（返回 false 挂起），在途链沿用
     * appendRebuildChunk 逐拍语义推进（每拍 30 条 EDT 脉冲），链排空补跑挂起刷新。
     *
     * @return true = 已补完（刷新可继续差分）；false = 超大余量让路（本拍刷新挂起）
     */
    private boolean flushRebuildChainSync() {
        Iterator<LogSqlEntry> remaining = rebuildRemaining;
        if (remaining == null) {
            return true;
        }
        List<LogSqlEntry> rest = new ArrayList<>();
        remaining.forEachRemaining(rest::add);
        if (rest.size() > FLUSH_SYNC_MAX) {
            // 超大余量：让路——链保持推进（rebuildRemaining 不动），本拍刷新挂起
            pendingRefreshAfterChain = true;
            return false;
        }
        rebuildRemaining = null;
        list.setIgnoreRepaint(true);
        if (!rest.isEmpty()) {
            listModel.addAll(rest);
        }
        list.setIgnoreRepaint(false);
        list.revalidate();
        list.repaint();
        return true;
    }

    // ---------- 排序方式（T-LogSortModes；T-UiCoord 后按钮改走动作图标组，文案由 presentation 承载） ----------

    /**
     * 当前排序方式显示名（静态查表，零分配——T-SqlLogToolbarEdt W3）。
     *
     * <p>旧实现每次都要新建 7 个 {@link SqlLogPanelKt.SortOption} 并调 7 次 bundle 取值，而这里
     * 处在工具栏每次展开的 update 路径上。</p>
     */
    private String currentSortLabel() {
        return MpSqlVisualizerBundle.message(SqlLogPanelKt.sortModeLabelKey(sortMode));
    }

    /** 弹出排序方式单选菜单（锚点 = 触发事件的组件，缺省落工具栏本体）。 */
    private void showSortPopup(java.awt.Component anchor) {
        JPopupMenu popup = new JPopupMenu();
        for (JRadioButtonMenuItem item : buildSortMenuItems()) {
            popup.add(item);
        }
        java.awt.Component target = anchor != null ? anchor : toolbarComponent;
        if (target != null) {
            popup.show(target, 0, target.getHeight() + 2);
        } else if (toolbarComponent != null) {
            popup.show(toolbarComponent, 0, 0);
        }
    }

    /**
     * 构建排序菜单项（拆出供测试断言选项齐备——headless 下 JPopupMenu.show 不可靠）。
     * 单选语义：点选即生效并刷新列表与按钮文案。
     */
    private List<JRadioButtonMenuItem> buildSortMenuItems() {
        List<JRadioButtonMenuItem> items = new ArrayList<>();
        for (SqlLogPanelKt.SortOption option : SqlLogPanelKt.sortOptions()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(option.label(), option.mode() == sortMode);
            item.setFocusable(false);
            item.addActionListener(e -> applySortMode(option.mode()));
            items.add(item);
        }
        return items;
    }

    /** 应用排序方式：更新模式 → 刷新列表 → 同步按钮文案。 */
    private void applySortMode(SortMode mode) {
        if (sortMode == mode) {
            refreshSortAction();
            return;
        }
        sortMode = mode;
        refreshList();
        refreshSortAction();
    }

    /** 刷新排序动作的 presentation（工具窗工具栏只渲染图标，当前方式由 tooltip 承载）。 */
    private void refreshSortAction() {
        if (_toolbarRef != null) {
            _toolbarRef.updateActionsImmediately();
        }
    }


    /** 按当前排序模式排序条目。 */
    private List<LogSqlEntry> applySort(List<LogSqlEntry> entries) {
        switch (sortMode) {
            case TIME_DESC:
                // 时间降序
                return entries.stream()
                        .sorted(Comparator.comparingLong(this::displayTimeOf).reversed())
                        .collect(Collectors.toList());
            case DURATION_ASC:
                // 耗时：结构化字段优先（agent 通道直采），缺失时回退日志文本解析；无耗时沉底
                return entries.stream()
                        .sorted(Comparator.comparingLong((LogSqlEntry e) -> {
                            Long d = durationOf(e);
                            return d != null ? d : Long.MAX_VALUE;
                        }))
                        .collect(Collectors.toList());
            case DURATION_DESC:
                return entries.stream()
                        .sorted(Comparator.comparingLong((LogSqlEntry e) -> {
                            Long d = durationOf(e);
                            return d != null ? d : Long.MIN_VALUE;
                        }).reversed())
                        .collect(Collectors.toList());
            case SEQUENCE:
                // 行号：无行号（历史读回/round-trip）沉底
                return entries.stream()
                        .sorted(Comparator.comparingLong(e ->
                                e.sequenceNumber() > 0 ? e.sequenceNumber() : Long.MAX_VALUE))
                        .collect(Collectors.toList());
            case TIME_ASC:
            default:
                // 时间升序（默认）：与捕获顺序一致（sortedBy 稳定，等值保持入列序）
                return entries.stream()
                        .sorted(Comparator.comparingLong(this::displayTimeOf))
                        .collect(Collectors.toList());
        }
    }

    /** 条目耗时（毫秒）：结构化 executionTimeMs 优先，缺失时回退日志文本解析。 */
    private Long durationOf(LogSqlEntry entry) {
        return entry.executionTimeMs() != null ? entry.executionTimeMs() : LogDurationParser.parse(entry);
    }

    /**
     * 条目显示时间（排序口径）：与列表现行时间列一致——「日志时间」源开启且条目有原始日志时间时
     * 用之，否则用捕获时间（formatTimestampFragment 同规则），避免「看到的时间与排序依据不一致」。
     */
    private long displayTimeOf(LogSqlEntry entry) {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        if (PluginSettings.LOG_TIMESTAMP_SOURCE_LOG.equals(state.getLogTimestampSource())) {
            return entry.logTimestamp() != null ? entry.logTimestamp() : entry.timestamp();
        }
        return entry.timestamp();
    }

    // ---------- 渲染辅助（F-44-2：片段通道恢复 SQL 高亮 + 耗时） ----------
    // 注：formatTimestampFragment / formatDurationFragment / formatSourceFragment 为文件级 static 函数
    // （见 SqlLogPanelKt），供 LogEntryListCellRenderer 与 HistoryCellRenderer 共用。

    /**
     * F-44-6 / T-JumpCapture：统一解析跳转目标：仅依赖捕获位置，不做任何启发式定位。
     * 有位置 → 精确 file:line（LjtPrecise）或文件已失效（LjtNotFound）；
     * MP 分页 COUNT 标记 → LjtNoSource（无用户调用点，纯提示）；
     * 无位置 → LjtMissingSource（引导开启源码捕获，SourceCaptureGuide）。
     * 原「按表名搜索 Wrapper」回退已整体移除：同表名 ≠ 同调用点，命中即误导。
     * T-SourceJumpFunnel P0-5：判定逻辑收敛到 {@link EntryJumpResolver}（实时/历史两面板共用同一实现，
     * 历史面板由此获得候选回退能力）；T-RecentExecMenu O-119：转接原语收口到 {@link LogEntryJumpOps}
     * （实时/历史/弹框三方共用），本函数只做类型转接（类型保持不动——既有测试按其断言分支）。
     */
    public LogJumpTarget resolveEntryJump(LogSqlEntry entry) {
        LogEntryJumpOps.SourceTarget target = LogEntryJumpOps.resolveSource(project, entry);
        if (target instanceof LogEntryJumpOps.SourceTarget.Precise precise) {
            return new LjtPrecise(precise.virtualFile(), precise.line());
        }
        if (target == LogEntryJumpOps.SourceTarget.NoSource.INSTANCE) {
            return LjtNoSource.INSTANCE;
        }
        if (target instanceof LogEntryJumpOps.SourceTarget.NotFound notFound) {
            return new LjtNotFound(notFound.sourceFile());
        }
        return LjtMissingSource.INSTANCE;
    }

    /**
     * T-M55-01：XML 目标解析——mapperStatement 非空且 {@link MapperXmlLocator} 定位成功时返回
     * LjtMapperXml；statementId 缺失 / MP 内置语句 XML 不存在 / 多模块定位失败
     * 一律返回 null（调用方回退 Java 跳转，不劣化）。定位自带 ReadAction，EDT 直调安全。
     * T-RecentExecMenu O-119：解析原语收口 {@link LogEntryJumpOps#resolveXml}。
     */
    LjtMapperXml resolveEntryXmlJump(LogSqlEntry entry) {
        LogEntryJumpOps.XmlTarget target = LogEntryJumpOps.resolveXml(project, entry);
        if (target == null) {
            return null;
        }
        return new LjtMapperXml(target.virtualFile(), target.line());
    }

    /** 等价右键「跳转到源码」（供 UI 测试断言精确跳转分支，避 headless 弹窗）。 */
    public void jumpToSourceForTest(LogSqlEntry entry) {
        jumpToSource(entry);
    }

    /**
     * F-44-6：日志 ↔ 源码关联跳转。
     * 仅依赖捕获位置精确跳转；无位置时弹框引导开启源码捕获（T-JumpCapture）。
     * T-RecentExecMenu O-119：<b>整体策略</b>收口 {@link LogEntryJumpOps#jumpToSourceRealtime}
     * （与弹框右键共用同一实现与文案，避免第四份策略复制）。
     */
    private void jumpToSource(LogSqlEntry entry) {
        LogEntryJumpOps.jumpToSourceRealtime(project, entry);
    }

    /**
     * T-UiNative T-UN-02：跳转无源码提示改轻通知（原模态弹窗——纯告知无需打断）。
     * T-RecentExecMenu O-119：实现收口 {@link LogEntryJumpOps#showToast}（标题键留本页口径）；
     * 见 {@link FocusCallerMethodAction} 同用本薄封装。
     */
    private void showJumpToast(String content) {
        LogEntryJumpOps.showToast(project, "log.panel.jumpToSource", content);
    }

    /** 更新工具栏慢查询计数徽章（O-1 Phase C）。 */
    private void updateSlowQueryLabel() {
        int count = slowQueryCounter.getSlowCount();
        if (slowQueryLabel != null) {
            if (count > 0) {
                // T-UiCoord F2：全文「慢查询: N」（约 65px）在一行定构下吃掉余量 → 改短徽章
                // 「慢 N」（复用存量键 log.panel.slowQueryBadge），全文进 tooltip 不丢信息
                slowQueryLabel.setText(MpSqlVisualizerBundle.message("log.panel.slowQueryBadge") + " " + count);
                slowQueryLabel.setToolTipText(MpSqlVisualizerBundle.message("log.toolbar.slowQueryCount", Integer.toString(count)));
                slowQueryLabel.setVisible(true);
            } else {
                slowQueryLabel.setVisible(false);
            }
        }
    }

    private void openPreview(LogSqlEntry entry) {
        // T-M31.14：设置开启时预览展示格式化版（多行缩进），否则原样
        PluginSettings.State state = PluginSettings.getInstance().getState();
        // T-HistParam F3：识别通道统一——与历史 tab 共用 toPreviewResult
        // （占位符识别 / 字面量参数恢复 F4 / 回退警告分流，行为与原 openPreview 双分支逐字段一致）
        SqlPreviewService.showResult(project, LogEntryPreviewRecognizerKt.toPreviewResult(
                entry, state.getLogFormatSql(), state.getLiteralParamRecovery()), null);
    }

    /** R4（W3 历史工作流包）：导出当前可见行集（lastRendered=面板过滤/搜索/排序后的实际可见集）。 */
    public class ExportVisibleAction extends AnAction {
        ExportVisibleAction() {
            super(MpSqlVisualizerBundle.message("log.export.visible"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            SqlLogPanelActionsKt.exportVisibleEntries(SqlLogPanel.this);
        }
    }

    // ---------- T-UiNative P1a：列表右键菜单动作（原 JMenuItem 语义平移，update 设文案/灰置，actionPerformed 平移原监听体） ----------

    /** 复制 SQL：多选批量逐行拼接（batchCopySql），单选复制该行。 */
    public class CopySqlAction extends AnAction {
        private final List<LogSqlEntry> entries;

        CopySqlAction(List<LogSqlEntry> entries) {
            this.entries = entries;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(SqlLogPanelKt.copyMenuText(
                    "log.panel.copy", "log.panel.copySelected", entries.size()));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            UiTextClipboard.copy(SqlLogPanelKt.batchCopySql(entries));
        }
    }

    /** 复制时间戳：显示开关关闭时无片段可复制 → update 置灰。 */
    public class CopyTimestampAction extends AnAction {
        private final List<LogSqlEntry> entries;
        private final String clip;

        CopyTimestampAction(List<LogSqlEntry> entries, String clip) {
            this.entries = entries;
            this.clip = clip;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(SqlLogPanelKt.copyMenuText(
                    "log.panel.copyTimestamp", "log.panel.copyTimestampSelected", entries.size()));
            e.getPresentation().setEnabled(!clip.isEmpty());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            if (!clip.isEmpty()) {
                UiTextClipboard.copy(clip);
            }
        }
    }

    /** 删除被右键的条目。 */
    public class DeleteSqlAction extends AnAction {
        private final LogSqlEntry entry;

        DeleteSqlAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.delete"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            manager.getEntries().removeEntry(entry);
        }
    }

    /**
     * T-SqlSuppress（O-69）：屏蔽选中的 SQL。多选支持——去重后的<b>不同指纹</b>各成一条规则
     * （空白变体同指纹只建一条），已存在跳过（幂等）；达上限拒新增并提示（D6）。
     * 动作链：规则入库（监听器同步更新缓冲键集）→ refreshList（存量心跳行立即消失，D4 最强反馈）
     * → balloon 反馈附「管理屏蔽规则…」直达对话框（D4 中层）。
     */
    public class SuppressSqlAction extends AnAction {
        private final List<LogSqlEntry> entries;

        SuppressSqlAction(List<LogSqlEntry> entries) {
            this.entries = entries;
        }

        @Override
        public void update(AnActionEvent e) {
            // Kotlin entries.distinctBy { it.dedupeKey }（保持首现序）的等价内联
            Map<String, LogSqlEntry> distinct = new LinkedHashMap<>();
            for (LogSqlEntry it : entries) {
                distinct.putIfAbsent(it.dedupeKey(), it);
            }
            int count = distinct.size();
            e.getPresentation().setText(count > 1
                    ? MpSqlVisualizerBundle.message("log.suppress.actionSelected", Integer.toString(count))
                    : MpSqlVisualizerBundle.message("log.suppress.action"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            SqlSuppressRuleManager ruleManager = SqlSuppressRuleManager.getInstance();
            // Kotlin entries.distinctBy { it.dedupeKey }.map { it.restoredSql }
            Map<String, LogSqlEntry> distinct = new LinkedHashMap<>();
            for (LogSqlEntry it : entries) {
                distinct.putIfAbsent(it.dedupeKey(), it);
            }
            List<String> sqls = new ArrayList<>();
            for (LogSqlEntry it : distinct.values()) {
                sqls.add(it.restoredSql());
            }
            kotlin.Pair<Integer, Boolean> result = ruleManager.addRules(sqls); // F21：单次 republish
            refreshList(); // addRule 的监听器已同步更新缓冲键集——存量行立即消失
            if (result.getFirst() > 0) {
                SqlLogPanelActionsKt.showSuppressBalloon(SqlLogPanel.this, result.getFirst());
            }
            if (result.getSecond()) {
                SqlLogPanelActionsKt.showSuppressLimitBalloon(SqlLogPanel.this);
            }
        }
    }

    /** 发送到 SQL → Java 转换（第三 tab）。 */
    public class SendToSqlToJavaAction extends AnAction {
        private final LogSqlEntry entry;

        SendToSqlToJavaAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.sendToReverse"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            openEntryInTab(entry, LogEntryTab.SQL_TO_JAVA);
        }
    }

    /** 发送到日志转 SQL（第二 tab）。T-RecentExecMenu O-119：落点收口 openEntryInTab（弹框右键同用）。 */
    public class SendToLogToSqlAction extends AnAction {
        private final LogSqlEntry entry;

        SendToLogToSqlAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.sendTo"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            openEntryInTab(entry, LogEntryTab.LOG_TO_SQL);
        }
    }

    /** 跳转到 Java 调用方（T-M55-01：与「跳转到 XML」拆分为双目标；无位置→引导捕获弹窗，无提示/文件丢失→toast）。 */
    public class JumpToSourceAction extends AnAction {
        private final LogSqlEntry entry;

        JumpToSourceAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.jumpToJava"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            jumpToSource(entry);
        }
    }

    /**
     * T-M55-01：跳转到 mapper XML 语句节点。mapperStatement 非空且定位成功才显示
     * （update 内解析一次并存引用，actionPerformed 复用，单次右键只做一次索引查询）；
     * 定位失败（XML 不存在/多模块未命中）不显示，原有「跳转到 Java」承担回退，不劣化。
     */
    public class JumpToMapperXmlAction extends AnAction {
        private final LogSqlEntry entry;
        private LjtMapperXml resolved;

        JumpToMapperXmlAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.jumpToXml"));
            resolved = resolveEntryXmlJump(entry);
            e.getPresentation().setEnabledAndVisible(resolved != null);
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            // update 已解析成功才可见可点；防御再解析一次（menu 会话内基本命中缓存外的同结果）
            LjtMapperXml target = resolved != null ? resolved : resolveEntryXmlJump(entry);
            if (target == null) {
                return;
            }
            new OpenFileDescriptor(project, target.getVirtualFile(), target.getLine(), 0).navigate(true);
        }
    }

    /**
     * T-M55-03：聚焦调用方方法（静态行号区间近似版）——sourceFile 非空时可用；
     * 点击时解析方法行号区间并加入聚焦页签栏（解析失败 toast，不静默）。
     * Ultra 的编辑器行槽 ⚡ 入口首批不做（记录为可选增强）。
     */
    public class FocusCallerMethodAction extends AnAction {
        private final LogSqlEntry entry;

        FocusCallerMethodAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.focusCallerMethod"));
            // MP 分页 COUNT 标记无用户调用点；其余 sourceFile 非空即可用（解析成败在 action 时点判定）
            e.getPresentation().setEnabled(entry.sourceFile() != null
                    && !SourceMarkers.isMpPaginationCount(entry.sourceFile()));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            FocusTab.Method tab = resolveCallerMethodFocus(entry);
            if (tab == null) {
                showJumpToast(MpSqlVisualizerBundle.message("log.panel.focusCallerMethod.failed"));
                return;
            }
            addFocusTab(tab);
        }
    }

    /**
     * T-M55-04：按请求聚焦——requestTag 非空的条目右键即产生请求页签（同一次请求的多条 SQL
     * 共享同值，页签内呈现扁平时间序）。页签 K 有界（addRequestTab 超限挤掉最早请求页签，
     * 防长压测会话 requestTag 高基数爆页签栏）；无请求标签条目在请求聚焦下隐藏 + 计数提示
     * （与 03 方法聚焦同策略）。「最近 K 个请求标签下拉」的独立入口不另做——右键任意
     * 同标签条目等价可达，计划内「或」二选一取轻。
     */
    public class FocusRequestAction extends AnAction {
        private final LogSqlEntry entry;

        FocusRequestAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.focusRequest"));
            e.getPresentation().setEnabled(entry.requestTag() != null && !entry.requestTag().isBlank());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            String requestTag = entry.requestTag();
            if (requestTag != null && !requestTag.isBlank()) {
                addFocusTab(new FocusTab.Request(requestTag));
            }
        }
    }

    /**
     * O-90：按结构聚焦——右键带「同结构 N 条」徽章的条目产生结构页签（structureKey 精确匹配，
     * 过滤出该结构全部条目；与 04 请求聚焦同一交互形态：条目驱动的聚焦过滤器，页签可关闭恢复）。
     * 准入 = 计数达 {@link LogSqlModelsKt#TEMPLATE_BADGE_MIN_COUNT}（徽章在场才可点）；同结构重复创建幂等
     * （{@link FocusTab#addTemplateTab} 按 structureKey 判重）。徽章本体为展示性（与去重徽章先例一致——
     * 列表片段非交互组件），聚焦入口落在本动作；T-DevAcceptFix2 ⑪ 起菜单项带 description
     * 说明判定口径（「字面量不同也算」——用户实测里 {@code IN ('a')} 与 {@code IN ('b')} 计同一结构）。
     */
    public class FocusTemplateAction extends AnAction {
        private final LogSqlEntry entry;

        FocusTemplateAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.focusTemplate"));
            e.getPresentation().setDescription(MpSqlVisualizerBundle.message("log.panel.focusTemplate.desc"));
            Integer n = templateCounts.get(entry.structureKey());
            e.getPresentation().setEnabled(n != null && n >= LogSqlModelsKt.TEMPLATE_BADGE_MIN_COUNT);
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            Integer n = templateCounts.get(entry.structureKey());
            if (n == null) return;
            if (n >= LogSqlModelsKt.TEMPLATE_BADGE_MIN_COUNT) {
                addFocusTab(new FocusTab.Template(entry.structureKey(), n));
            }
        }
    }

    /** 面板销毁：释放单击预览弹窗与日志转 SQL / SQL→Java 面板编辑器（manager 生命周期由项目 @Service 管理）。 */
    @Override
    public void dispose() {
        // T-Perf-24：停止搜索防抖 Timer（防晚到 apply 触碰已销毁面板）
        searchDebounceTimer.stop();
        // F80：撤链后补跑挂起标志（防 dispose 后晚到 invokeLater 触碰已销毁面板）
        pendingRefreshAfterChain = false;
        // T-CaptureTruth T-CT-03：停止诊断定时器（同上，防晚到 syncDiagnostics 触碰已销毁列表）
        if (diagnosticsTimer != null) {
            diagnosticsTimer.stop();
        }
        diagnosticsTimer = null;
        // T-Perf-15：注销 manager 监听器（防面板销毁后仍被回调——每条新 SQL invokeLater 调已销毁面板）
        manager.removeUiListener(uiRefreshListener);
        manager.removeCapturingListener(capturingUiListener);
        // T-RecentExecMenu O-119：注销送页签通道（同一性判定——防误清他人注册）
        if (manager.onOpenEntryInTab == openEntryInTabCallback) {
            manager.onOpenEntryInTab = null;
        }
        if (previewHost != null) previewHost.hideHint();
        dockedPreview.dispose();
        logToSqlPanel.dispose();
        sqlToJavaPanel.dispose();
    }

    /**
     * T-RecentExecMenu O-119：面板外请求（弹框右键「发送到…」）把条目送进对应页签并选中——
     * 与右键 {@link SendToSqlToJavaAction} / {@link SendToLogToSqlAction} 同体（三处共用本方法，单一实现）。
     */
    public void openEntryInTab(LogSqlEntry entry, LogEntryTab tab) {
        if (tab == LogEntryTab.LOG_TO_SQL) {
            logToSqlPanel.loadFromEntry(entry);
            tabs.select(logToSqlTab, false);
        } else if (tab == LogEntryTab.SQL_TO_JAVA) {
            sqlToJavaPanel.setInputSql(entry.restoredSql());
            tabs.select(sqlToJavaTab, false);
        }
    }

    // ---------- 测试访问器（headless 测试断言用） ----------

    /** 当前列表模型条目数（测试断言）。 */
    public int getVisibleEntryCount() {
        return listModel.size();
    }

    /** 当前列表模型条目 restoredSql 列表（测试断言）。 */
    public List<String> getVisibleEntries() {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) {
            result.add(listModel.getElementAt(i).restoredSql());
        }
        return result;
    }

    /** 当前激活的类型过滤集合（测试断言）。 */
    public Set<StatementType> getCurrentFilter() {
        return new LinkedHashSet<>(activeFilter);
    }

    /** 当前排序模式（测试断言）。 */
    public SortMode getCurrentSortMode() {
        return sortMode;
    }

    /** 排序方式选择（测试驱动：等价用户在菜单里点选某项）。 */
    public void selectSortModeForTest(SortMode mode) {
        applySortMode(mode);
    }

    /** 排序动作（T-UiCoord 后按钮退役为动作图标组；测试断言 presentation）。 */
    public AnAction getSortActionForTest() {
        return sortModeAction;
    }

    /** 排序菜单项文案列表（测试断言选项齐备；构建菜单但不 show）。 */
    public List<String> getSortMenuLabelsForTest() {
        List<String> labels = new ArrayList<>();
        for (JRadioButtonMenuItem item : buildSortMenuItems()) {
            labels.add(item.getText());
        }
        return labels;
    }

    /** 当前排序方式文案（测试断言；按钮退役后由动作 presentation 承载）。 */
    public String getSortButtonTextForTest() {
        return currentSortLabel();
    }

    /** 去重勾选框（F-44-2 测试驱动：模拟用户勾选「移除重复的sql」验证既有条目立即去重）。 */
    public JCheckBox getDedupeCheckBoxForTest() {
        return dedupeCheckBox;
    }

    /** SQL类型多选按钮（测试断言文案）。 */
    public JButton getTypeFilterButtonForTest() {
        return typeFilterButton;
    }

    /** T-SelToggle：类型弹窗底部全选/全不选切换按钮（未打开弹窗时为 null）。 */
    public JButton getTypePopupToggleButtonForTest() {
        return typePopupToggleButton;
    }

    /**
     * T-SelToggle：headless 测试直接构建弹窗内容（不经 popup.show——无显示设备时
     * lightweight popup 不可靠），返回后 {@link #getTypePopupToggleButtonForTest()} 即非 null 可驱动。
     */
    public JPanel buildTypeFilterContentForTest() {
        return buildTypeFilterContent();
    }

    /**
     * T-UiCoord F2：工具栏单行宿主（布局契约断言用——单行 / 不越界 / 控件共轴）。
     * 返回的是生产实例（{@code buildToolbar()} 的产物），不是测试另搭的仿制品。
     */
    JPanel toolbarRowForTest() {
        return toolbarRow;
    }

    /** Kotlin {@code val toolbarRowForTest} 的 JVM 访问器等价入口（属性名读取用）。 */
    public JPanel getToolbarRowForTest() {
        return toolbarRow;
    }

    /** T-UiCoord F2：工具栏右侧控制组（类型 / 排序 / 去重勾选 / 慢查询徽章）——单行契约的主断言面。 */
    JPanel toolbarControlsForTest() {
        return toolbarControls;
    }

    /** Kotlin {@code val toolbarControlsForTest} 的 JVM 访问器等价入口（属性名读取用）。 */
    public JPanel getToolbarControlsForTest() {
        return toolbarControls;
    }

    /** T-UiCoord F2：工具栏动作图标组组件（结构断言：它必须挂在行宿主 CENTER）。 */
    JComponent toolbarComponentForTest() {
        return toolbarComponent;
    }

    /** Kotlin {@code val toolbarComponentForTest} 的 JVM 访问器等价入口（属性名读取用）。 */
    public JComponent getToolbarComponentForTest() {
        return toolbarComponent;
    }

    /** T-UiCoord F2：下拉按钮显示文本的像素预算（测试按同一预算算期望的省略文本）。 */
    int dropdownTextBudgetForTest() {
        return JBUI.scale(DROPDOWN_TEXT_BUDGET_PX);
    }

    /** Kotlin {@code val dropdownTextBudgetForTest} 的 JVM 访问器等价入口（属性名读取用）。 */
    public int getDropdownTextBudgetForTest() {
        return JBUI.scale(DROPDOWN_TEXT_BUDGET_PX);
    }

    /** 应用类型过滤组合（测试驱动：设置选中集 + 刷新按钮文案与列表）。 */
    public void applyTypeFilterForTest(Set<StatementType> types) {
        activeFilter.clear();
        activeFilter.addAll(types);
        updateTypeFilterButton();
        refreshList();
    }

    /** 搜索条（测试断言显隐/文本）。 */
    public cn.song.mybatisplus.visualizer.ui.search.NativeSearchTextField getSearchFieldForTest() {
        return searchField;
    }

    /** 布局测试契约：搜索条可见性（旧断言落在 searchField.isVisible 上，迁到宿主 searchBar）。 */
    public boolean getSearchFieldVisibleForTest() {
        return searchBar.isVisible();
    }

    public void setSearchFieldVisibleForTest(boolean value) {
        searchBar.setVisible(value);
    }

    /** 停靠预览宿主（T-ClickPreview 退役后测试缝换轨；排程/取消/弹窗残留断言走 previewHost）。 */
    public SqlListPreviewHost getClickPreviewForTest() {
        return previewHost;
    }

    /** 工具栏慢查询计数标签（测试断言显隐与文案）。 */
    public JLabel getSlowQueryLabelForTest() {
        return slowQueryLabel;
    }

    private static final Logger LOG = Logger.getInstance(SqlLogPanel.class);
    private static final String TOOLBAR_ID = "cn.song.mybatisplus.visualizer.log.toolbar";
    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    public static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";
    /** T-SqlSuppress（O-69/D5）：屏蔽规则 balloon 通知组（plugin.xml 独立注册）。 */
    public static final String SUPPRESS_NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.logsuppress";
    public static final String DEFAULT_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss";
    /** T-M39：帮助文档 URL（降级系统浏览器打开）。
     *  2026-08-31 改为自建站点 jvtools.cn（可自主接入国内 CDN；
     *  当前实测：直连不通，需走系统/IDE 代理才可达，与 plugins.jetbrains.com 同）。
     *  2026-10-12：改用 canonical 域名 + 无后缀路径。旧写法
     *  `mybatis-plus-sql-visualizer.jvtools.cn/features/wrapper-preview.html` 实测要走
     *  两次跳转（旧域名 301 → 新域名，`.html` 308 → 无后缀），canonical 地址直接 200。 */
    private static final String HELP_DOCUMENTATION_URL =
            "https://sql-visualizer.jvtools.cn/features/wrapper-preview";
    /** T-M39：帮助标签页标题。 */
    private static final String HELP_TAB_TITLE = "SQL Visualizer - Help";

    /** 类型 → 文案。 */
    private static String labelForType(StatementType type) {
        switch (type) {
            case SELECT:
                return MpSqlVisualizerBundle.message("log.filter.select");
            case INSERT:
                return MpSqlVisualizerBundle.message("log.filter.insert");
            case UPDATE:
                return MpSqlVisualizerBundle.message("log.filter.update");
            case DELETE:
                return MpSqlVisualizerBundle.message("log.filter.delete");
            case CREATE:
                return MpSqlVisualizerBundle.message("log.filter.create");
            case DROP:
                return MpSqlVisualizerBundle.message("log.filter.drop");
            default:
                return "OTHER";
        }
    }
}

