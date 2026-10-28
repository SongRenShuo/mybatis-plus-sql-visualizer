package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.MpvsUiColors;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPopupMenu;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ListSpeedSearch;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import kotlin.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.swing.DefaultListModel;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * 历史 SQL 面板（F-44-3 两级导航）：读取自动保存目录中的历史文件展示
 * （F-44-4：目录随插件更名，旧名 mybatis-plus-sql-visualizer 不再读取）。
 *
 * <p>目录解析与写端同源（{@code SqlLogAutoSaver.resolveHistoryDir}）——设置页「保存目录」填了自定义路径时，
 * 历史页读的就是同一个目录（T-HistoryTabAlways 前读端只认默认 .idea 路径，自定义目录下写的文件看不到）。
 *
 * <p>展示层级（用户要求）：
 * <ol>
 *   <li><b>一级（默认）</b>：文件列表——每行 = 文件名 | 文件大小 | SQL 条数；双击进入二级；</li>
 *   <li><b>二级</b>：单个文件的 SQL 列表——每行 = 耗时 + 执行时间 + 源码位置 + SQL 摘要（高亮）；
 *       双击 SQL → 预览弹窗；右键 → 复制 SQL / 跳转到源码；顶部「返回」回一级。</li>
 * </ol>
 *
 * <p>搜索框一级按文件名过滤、二级按 SQL 内容/源文件过滤（emptyText 动态切换）。
 */
public class HistorySqlLogPanel extends JPanel implements Disposable {

    // ---------- 一级：文件列表 ----------
    private final DefaultListModel<HistoryFileEntry> fileListModel = new DefaultListModel<>();
    private final JBList<HistoryFileEntry> fileList = new JBList<>(fileListModel);
    private List<HistoryFileEntry> allFiles = List.of();

    // ---------- 二级：SQL 列表（复用 SqlEntryList——Ctrl+C 复制纯 SQL 链路） ----------
    private final DefaultListModel<LogSqlEntry> sqlListModel = new DefaultListModel<>();
    // 视口宽度跟随（master HistorySqlLogPanel.kt:85 同款）——宽度交给视口、由渲染器裁剪长 SQL
    private final SqlEntryList sqlList = new ViewportWidthSqlEntryList(sqlListModel);

    /**
     * 二级列表渲染器（面板直持引用）。<b>勿经 {@code sqlList.cellRenderer} 取回</b>：平台
     * ExpandedItemListCellRendererWrapper 会包装列表渲染器（speed search 展开态），getter 拿到的是
     * wrapper——20261008 验收缺陷 #5 即「as? HistoryCellRenderer」静默失败致同模板徽章计数永不更新
     * （面板级计数正确、右键聚焦可用，唯独徽章不渲染）。回填一律走本字段。
     */
    private final HistoryCellRenderer sqlCellRenderer = new HistoryCellRenderer();

    /** 二级速度搜索是否已安装。构造于 CardLayout 隐藏卡时安装不生效（真机 key 流不达、焦点不在），
     *  故延后到 showFileContent/loadFileContentSync 切卡可见后安装（T-UiNative T-UN-05）。 */
    private boolean sqlSpeedSearchInstalled = false;

    /** T-ClickPreview：单击出轻量预览弹窗（点击时按行捕获 SQL，锚点在行下缘不遮行），双击走 openPreview 完整弹窗；悬停 tooltip 已移除（卡顿根因）。
     *  T-ListPreviewCopy：预览文本经 clickPreviewSql——设置「预览时格式化还原 SQL」开启（默认）时多行格式化显示。 */
    private SqlListPreviewHost previewHost;
    private final SqlDockedPreview dockedPreview;
    private HistoryFileEntry currentFile = null;
    private List<LogSqlEntry> currentEntries = List.of();

    /** T-SqlLogPerf：二级列表滚动宿主（构造期装配 adjustment 监听驱动滚动追加）。 */
    private JBScrollPane sqlScrollPane = null;

    // ---------- O-90：同结构（结构相同，疑似 N+1）计数徽章与聚焦 ----------
    // 注：内部标识沿用 template* 命名；**面向用户**的文案自 T-DevAcceptFix2 ⑪ 起为「同结构 N 条 /
    // 只看同结构」（原「×N 同模板」与插件既有功能「SQL 快捷模板」撞名且 ×N 易被读成倍数）。

    /** O-90：当前文件全量条目的同模板计数（徽章渲染与右键聚焦动作共用；applySqlSearch 重建）。 */
    private Map<String, Integer> templateCounts = Map.of();

    /** F88 测试缝：非空时替代 {@code LogSqlModelsKt#structureKeyCounts} 聚合（测试注入计数 lambda 断言聚合次数）。 */
    @Nullable
    public java.util.function.Function<List<LogSqlEntry>, Map<String, Integer>> structureKeyCountsHookForTest = null;

    /** F88：计数已对齐的条目快照（身份判据）——聚合只随快照换代重算，不随 apply 次数重算。 */
    private List<LogSqlEntry> templateCountsSnapshotFor = null;

    /** O-90：同模板聚焦键（非 null 时二级列表只显该模板条目；右键动作与 chip 点击经 {@link #applyTemplateFocus}）。 */
    private String templateFocusKey = null;

    /** O-90 同模板聚焦提示 chip（聚焦时显示该模板计数，点击清除；构建于 {@link #buildUI} 工具栏）。 */
    private final javax.swing.JLabel templateFilterChip;

    // ---------- T-Perf-05：后台任务代际 + 搜索防抖 ----------

    /** 后台刷新/加载代际：新任务启动即作废旧任务的 EDT apply。 */
    private int bgGeneration = 0;

    /** T-Perf-05：搜索防抖（每击键 O(N) clear+add → 停顿 200ms 应用一次）。 */
    private final javax.swing.Timer searchDebounceTimer =
            new javax.swing.Timer(SEARCH_DEBOUNCE_MS, e -> applySearch());

    // ---------- T-SearchToggleNativeLook（O-137）：搜索框+开关迁移到平台原生形态 ----------
    //
    // SearchTextArea（Find in Files/Ctrl+F 同款）内嵌原生 ToggleMatchCase/ToggleRegex；
    // 两旗改从 NativeSearchTextField.findModel 读取（FindModel.isRegularExpressions/isCaseSensitive），
    // 过滤语义/memo 键/regex: 前缀完全不变。原生 Toggle 无独立 click 监听——测试缝直接置 FindModel 旗，
    // 生产路径由原生钮自己改旗。

    private final cn.song.mybatisplus.visualizer.ui.search.NativeSearchTextField nativeSearch;

    private final cn.song.mybatisplus.visualizer.ui.search.NativeSearchTextField searchField;

    /** 正则编译 memo（键 = 源串 + matchCase 旗，见 {@link HistoryRegexMemo}）。 */
    private final HistoryRegexMemo regexMemo;

    /** 搜索框宿主：原生 SearchTextArea 自带内嵌开关+右侧读数，直接占满一行。 */
    private final javax.swing.JComponent searchBar;

    // ---------- T-UiNative T-UN-10：顶部导航动作（原手摆 28×28「仿平台按钮」JButton 平移） ----------

    /** 导航工具栏引用（层级切换后即时刷新 Back 可见性；T-UN-10）。 */
    private ActionToolbar _navToolbarRef = null;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);

    private final Project project;

    public HistorySqlLogPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        // T-UiNative P0：一级文件列表打字即搜（平台 ListSpeedSearch，按文件名匹配）
        ListSpeedSearch.installOn(fileList, HistoryFileEntry::getDisplayName);
        this.dockedPreview = new SqlDockedPreview(project, sqlList, () -> {
            if (previewHost != null) previewHost.toggle();
        });
        this.templateFilterChip = new javax.swing.JLabel();
        templateFilterChip.setVisible(false);
        templateFilterChip.setFont(templateFilterChip.getFont().deriveFont(java.awt.Font.BOLD));
        templateFilterChip.setForeground(MpvsUiColors.TEMPLATE_N1);
        templateFilterChip.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        templateFilterChip.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                applyTemplateFocus(null);
            }
        });
        // 原生搜索框：placeholderText=null、showRegex=true（历史页需要正则钮）
        this.nativeSearch = new cn.song.mybatisplus.visualizer.ui.search.NativeSearchTextField(null, true, project);
        this.searchField = this.nativeSearch;
        this.regexMemo = new HistoryRegexMemo();
        this.searchBar = nativeSearch.getRow();
        this.searchDebounceTimer.setRepeats(false);
        buildUI();
        // F87（T-PerfSweep25）懒首扫：原「构造即 refresh」把后台扫盘成本从「用时才有」提前到
        // 「工具窗开窗即付」（R19 历史 tab 常驻使本面板随 SqlLogPanel init 构造）。现首扫延迟到
        // 首次切到历史 tab——SqlLogPanel 的 tab ChangeListener 已有「切到历史 tab 即 refresh」
        // 接线（F-44 形态保留，见 SqlLogPanel），即首扫唯一入口；设置变更路径经
        // historyDirChangedSinceLastScan 照旧。R19 引导闭环不受影响：空态引导文案由
        // buildUI 渲染（updateFilesEmptyText），与扫描无关，开窗即见。
    }

    // ---------- 测试访问器（F-44-3 两级导航真实场景测试） ----------

    /** 一级文件列表模型条目（测试断言：文件名/大小/条数）。 */
    public List<HistoryFileEntry> getFileListEntries() {
        List<HistoryFileEntry> result = new ArrayList<>();
        for (int i = 0; i < fileListModel.size(); i++) {
            result.add(fileListModel.getElementAt(i));
        }
        return result;
    }

    /** 二级 SQL 列表模型条目（测试断言）。 */
    public List<LogSqlEntry> getSqlListEntries() {
        List<LogSqlEntry> result = new ArrayList<>();
        for (int i = 0; i < sqlListModel.size(); i++) {
            result.add(sqlListModel.getElementAt(i));
        }
        return result;
    }

    /** 当前是否处于一级文件列表视图（测试断言）。 */
    public boolean isShowingFiles() {
        return currentFile == null;
    }

    /** O-90 测试断言：当前同模板计数与聚焦键。 */
    public Map<String, Integer> getTemplateCountsForTest() {
        return templateCounts;
    }

    /** O-90 测试断言：当前聚焦键（null = 未聚焦）。 */
    public String getTemplateFocusKeyForTest() {
        return templateFocusKey;
    }

    /** 打开指定文件进入二级（测试驱动；等价双击文件）。 */
    public void openFileForTest(HistoryFileEntry entry) {
        loadFileContentSync(entry);
    }

    /** 返回一级（测试驱动；等价点击返回按钮）。 */
    public void backToFilesForTest() {
        showFileList();
    }

    /** 设置搜索词（测试驱动；等价在搜索框输入，立即应用过滤——生产路径 DocumentListener 走防抖）。 */
    public void setSearchForTest(String query) {
        searchField.setText(query);
        applySearch();
    }

    /** 读取搜索框当前文本（测试断言：进条目卡应被清空）。 */
    public String searchTextForTest() {
        return searchField.getText();
    }

    /**
     * 进条目卡前搜索框复位（生产异步路径与测试同步路径共用单源，防两处漂移）：
     * 用户定（20261010）文件级搜索词不带入条目级——清空触发防抖重过滤（空查询=全量条目）；
     * 历史记录在文件级应用时已入账，不受清空影响。
     */
    private void clearSearchForEntryLevel() {
        searchField.setText("");
    }

    /** 大小写敏感开关（原生钮状态落点；默认关=忽略大小写，三链语义统一）。 */
    private boolean matchCaseEnabled() {
        return nativeSearch.isMatchCase();
    }

    /** 正则开关（原生钮状态落点；默认关=子串语义；regex: 前缀独立生效）。 */
    private boolean regexEnabled() {
        return nativeSearch.isRegex();
    }

    /** 测试断言/驱动：正则旗（等价用户点击 = 置 FindModel.isRegularExpressions 后应用过滤）。 */
    public boolean getRegexToggleForTest() {
        return regexEnabled();
    }

    public void setRegexToggleForTest(boolean value) {
        nativeSearch.getFindModel().setRegularExpressions(value);
        applySearch();
    }

    /** 测试断言/驱动：大小写旗。 */
    public boolean getMatchCaseToggleForTest() {
        return matchCaseEnabled();
    }

    public void setMatchCaseToggleForTest(boolean value) {
        nativeSearch.getFindModel().setCaseSensitive(value);
        applySearch();
    }

    /** 二级列表当前行高（测试断言行高随模式重设用）。 */
    public int getSqlListFixedCellHeightForTest() {
        return sqlList.getFixedCellHeight();
    }

    /** 二级列表实例（测试断言 getCellBounds 行高与渲染 preferred 一致——布局缓存失效防回归用）。 */
    public javax.swing.JList<?> getSqlListForTest() {
        return sqlList;
    }

    /** 测试缝：面板直持的二级渲染器（勿走 sqlList.cellRenderer——平台 wrapper 包装，见字段 KDoc）。 */
    public HistoryCellRenderer getSqlCellRendererForTest() {
        return sqlCellRenderer;
    }

    /** 二级 SQL 列表停靠预览宿主（T-ClickPreview 退役后测试缝换轨）。 */
    public SqlListPreviewHost getPreviewHostForTest() {
        return previewHost;
    }

    public SqlDockedPreview getDockedPreviewForTest() {
        return dockedPreview;
    }

    /** 显示模式变更处理（topic 监听与测试共用）：行高随模式重设 + 二级可见时全量重建。
     *  多行片段缓存按条目内模式键自动失效（无需显式清）。日志面板同款处理经 topic 对齐。
     *  T-MergeFollowup-03（复查二轮）：BasicListUI 的 cellHeights 布局缓存不归 Swing layout 管
     *  （revalidate 无效），多行模式间切换 -1→-1 也不触发 setFixedCellHeight 的 propertyChange——
     *  updateUI() 重装列表 UI 强制布局状态失效（与日志页同款），applySqlSearch 全量重建作为双保险。
     *  T-DisplayModeToSettings：入口迁设置页「列表显示」，此处仅响应设置广播。 */
    public void onDisplayModeChanged() {
        applySqlListCellHeight();
        sqlList.updateUI();
        if (currentFile != null) {
            applySqlSearch();
        }
    }

    /**
     * 设置变更处理（topic 监听入口，T-HistoryTabAlways）：解析出的历史目录与上次扫描所用不同时
     * 直接重扫（用户在设置页改了「保存目录」，不重扫就还得手动点刷新），未变时只重算显示模式并重绘
     * （原 {@link #onDisplayModeChanged} 行为）。
     */
    public void onSettingsChanged() {
        if (historyDirChangedSinceLastScan()) {
            refresh();
        } else {
            onDisplayModeChanged();
        }
    }

    /** T-HistoryTabAlways：解析出的历史目录是否与上次扫描所用不同（设置页改了「保存目录」）。
     *  测试直接断言本判据——{@link #refresh} 是后台任务，headless 下到点不可控。 */
    public boolean historyDirChangedSinceLastScan() {
        return !Objects.equals(getHistoryDir(), lastScannedDir);
    }

    /**
     * 行高随显示模式分流（T-ListMode × T-LogListFreeze-04 权衡，与日志 tab {@code SqlLogPanel.applyListCellHeight} 同款）：
     * SINGLE_LINE 固定行高走 O(1) 布局快路径；KEYWORD_WRAP/FORMATTED 可变行高（条目行数不等），
     * 多行片段有条目级缓存，逐条测量只剩轻量组件布局。
     */
    public void applySqlListCellHeight() {
        int height = -1;
        if (PluginSettings.getInstance().sqlListDisplayMode() == SqlListDisplayMode.SINGLE_LINE) {
            Component c = sqlList.getCellRenderer().getListCellRendererComponent(
                    sqlList, new LogSqlEntry("SELECT 1", "SELECT 1", List.of()), 0, false, false);
            height = c.getPreferredSize().height;
        }
        sqlList.setFixedCellHeight(height);
    }

    /** 返回一级（原 backButton）：图标 Back、tooltip 沿用 log.history.back.tip；可见性 = 二级列表（update 输出）。 */
    private class BackAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.Back);
            e.getPresentation().setDescription(MpSqlVisualizerBundle.message("log.history.back.tip"));
            e.getPresentation().setVisible(!isShowingFiles());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            showFileList();
        }
    }

    /** 刷新（原 refreshButton）：图标 Refresh、tooltip 沿用 log.history.refresh.tip。 */
    private class RefreshAction extends AnAction {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.Refresh);
            e.getPresentation().setDescription(MpSqlVisualizerBundle.message("log.history.refresh.tip"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            refresh();
        }
    }

    /** 与日志页同款直接动作；历史只跳转，不引入实时尾部跟随。 */
    final AnAction scrollToEndAction = new AnAction() {
        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.ScrollToEnd);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.scrollBottom"));
            // 日志页的 tip 描述实时跟随，历史使用同一中性动作文案，避免误导。
            e.getPresentation().setDescription(MpSqlVisualizerBundle.message("log.panel.scrollBottom"));
            e.getPresentation().setVisible(!isShowingFiles());
            e.getPresentation().setEnabled(!isShowingFiles() && !sqlListModel.isEmpty() && !project.isDisposed());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            if (isShowingFiles() || project.isDisposed() || sqlListModel.isEmpty()) return;
            int generation = bgGeneration;
            if (pendingAppendGen == generation && pendingAppendIndex < pendingEntries.size()) {
                List<LogSqlEntry> remaining = pendingEntries.subList(pendingAppendIndex, pendingEntries.size());
                // 一次批量模型事件；先清待写状态，防滚动/布局监听重入重复追加。
                pendingEntries = List.of();
                pendingAppendIndex = 0;
                sqlListModel.addAll(remaining);
            }
            // 同步完成，不留下会滚动到新文件/新过滤结果的 invokeLater 请求。
            if (generation != bgGeneration || isShowingFiles() || sqlListModel.isEmpty()) return;
            sqlList.ensureIndexIsVisible(sqlListModel.size() - 1);
            javax.swing.JScrollBar bar = sqlScrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        }
    };

    /**
     * 测试缝：导航工具栏动作组。
     *
     * <p>{@code ActionGroup.getChildren} 是 override-only API，不能在插件字节码里出现（Marketplace
     * 校验报 override-only usage）——生产侧只暴露组对象，取子项由测试代码自行调用。</p>
     */
    public ActionGroup navigationToolbarGroupForTest() {
        ActionToolbar toolbar = _navToolbarRef;
        return toolbar != null ? toolbar.getActionGroup() : null;
    }

    /** 层级切换后即时刷新导航工具栏（Back 可见性走 update；组件不可见时平台跳过，无副作用）。 */
    private void refreshNavToolbar() {
        ActionToolbar toolbar = _navToolbarRef;
        if (toolbar != null) {
            toolbar.updateActionsImmediately();
        }
    }

    private void buildUI() {
        // 顶部工具栏：返回（仅二级可见）+ 刷新 + 搜索
        // T-UiNative T-UN-10：手摆按钮 → 平台 ActionToolbar（Back 可见性由 BackAction.update 按当前层级输出）
        ActionToolbar navToolbar = ActionManager.getInstance()
                .createActionToolbar("mpvs.history.nav", new DefaultActionGroup(new BackAction(), new RefreshAction(), scrollToEndAction), true);
        navToolbar.setTargetComponent(this);
        _navToolbarRef = navToolbar;
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        toolbar.add(navToolbar.getComponent());
        // T-DisplayModeToSettings：显示模式入口迁设置页「列表显示」（工具栏按钮移除）
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.files"));
        Dimension minSize = searchField.getTextEditor().getMinimumSize();
        searchField.getTextEditor().setMinimumSize(new Dimension(200, minSize.height));
        searchField.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleSearch();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleSearch();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleSearch();
            }
        });
        // 原生按钮只修改 FindModel，不触发 DocumentListener；切旗必须同步重验并清除过期错误。
        nativeSearch.getFindModel().addObserver(model -> applySearch());
        // T-HistorySearchToggles：搜索框外包宿主容器（CENTER 搜索框 + EAST 正则/大小写两枚开关）
        toolbar.add(searchBar);
        // O-90：同模板聚焦提示 chip（聚焦时显示；点击清除）
        toolbar.add(templateFilterChip);
        add(toolbar, BorderLayout.NORTH);

        // T-ListMode：设置变更（含模式切换广播）→ 清二级列表渲染缓存并重绘；
        // connect(this) 随面板 Disposer 树自动注销。invokeLater 显式 ModalityState.any()
        // （设置页红线：模态弹窗存续期默认 modality 会挂起回调）
        project.getMessageBus().connect(this).subscribe(
                PluginSettings.SETTINGS_CHANGE_TOPIC,
                (cn.song.mybatisplus.visualizer.config.SettingsChangeListener) () ->
                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                        ApplicationManager.getApplication().invokeLater(
                                () -> onSettingsChanged(),
                                com.intellij.openapi.application.ModalityState.any()));

        // 一级：文件列表
        updateFilesEmptyText();
        fileList.setCellRenderer(new HistoryFileCellRenderer());
        // T-LogListFreeze-04：固定行高（单行恒定高）——JList 布局走 O(1) 快路径，不再逐条调渲染器测量
        fileList.setFixedCellHeight(fileList.getCellRenderer()
                .getListCellRendererComponent(fileList, new HistoryFileEntry(new File("sample.sql"), 1), 0, false, false)
                .getPreferredSize().height);
        fileList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    HistoryFileEntry selected = fileList.getSelectedValue();
                    if (selected != null) {
                        showFileContent(selected);
                    }
                }
            }
        });
        cards.add(new JBScrollPane(fileList), CARD_FILES);

        // 二级：SQL 列表
        sqlList.getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.entries.empty"));
        sqlList.setCellRenderer(sqlCellRenderer);
        applySqlListCellHeight();
        sqlList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // T-ClickPreview：先转发（仅左键；单击命中行才排程预览/双击停表并关旧预览），双击再开完整弹窗
                // T-ClickPreviewCopyRestore：修饰键一并转发——Ctrl/Shift 单击（多选第一击）不排程预览
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    previewHost.onRowClick(e);
                }
                if (e.getClickCount() >= 2) {
                    LogSqlEntry selected = sqlList.getSelectedValue();
                    if (selected != null) {
                        openPreview(selected);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showSqlContextMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showSqlContextMenu(e);
                }
            }
        });
        // T-SqlLogPerf：二级列表滚动追加——滚动条逼近底部时续写下一批（fillSqlList 只写首屏）
        // T-LogListFreeze：宽度跟随视口由 ViewportWidthSqlEntryList 承担，横向滚动条永不出现（长 SQL 由渲染器裁剪）
        sqlScrollPane = new JBScrollPane(sqlList);
        sqlScrollPane.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sqlScrollPane.getVerticalScrollBar().addAdjustmentListener(e -> maybeAppendOnScroll());
        previewHost = new SqlListPreviewHost(sqlList, sqlScrollPane, dockedPreview,
                "mpvs.logs.preview.split", true,
                open -> { });
        cards.add(previewHost.getComponent(), CARD_SQL);
        add(cards, BorderLayout.CENTER);
    }

    // ---------- 视图切换 ----------

    private void showFileList() {
        currentFile = null;
        currentFileStamp = null;
        currentEntries = List.of();
        pendingEntries = List.of(); // F85（T-PerfSweep24）：返回一级清挂起批次（有 showing 守卫不误消费，清掉免滞留）
        if (previewHost != null) previewHost.hideHint(); // 返回一级时收起迁移提示（停靠预览随卡片隐藏）
        refreshNavToolbar(); // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = false）
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.files"));
        cardLayout.show(cards, CARD_FILES);
        applyFileSearch();
    }

    private void showFileContent(HistoryFileEntry fileEntry) {
        currentFile = fileEntry;
        templateFocusKey = null; // O-90：换文件即清除同模板聚焦（旧文件的模板键对新文件无意义）
        // T-UiNative T-UN-05：二级 SQL 列表切卡可见后重挂速度搜索（构造于隐藏卡时 installOn
        // 仍不生效的兜底；标记防重复装——平台 installOn 重复挂载同一列表不幂等）
        if (!sqlSpeedSearchInstalled) {
            sqlSpeedSearchInstalled = true;
            ListSpeedSearch.installOn(sqlList, entry -> SqlLogPanelKt.withTerminator(entry.restoredSql()));
        }
        refreshNavToolbar(); // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = true）
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.sql"));
        cardLayout.show(cards, CARD_SQL);
        // T-Perf-05：立即清空旧文件内容（大文件后台读取期间不残留旧文件条目，防误操作）
        currentEntries = List.of();
        clearSearchForEntryLevel();
        applySqlSearch();
        final int gen = ++bgGeneration;
        new Task.Backgroundable(project, MpSqlVisualizerBundle.message("log.history.load.progress"), true) {
            @Override
            public void run(ProgressIndicator indicator) {
                final List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readFromFile(fileEntry.file());
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (gen != bgGeneration || project.isDisposed()) {
                        return;
                    }
                    currentEntries = entries;
                    // T-SqlLogPerf：记录加载完成时的文件快照——refresh() 据此免掉未变文件的全量重读
                    currentFileStamp = new Pair<>(fileEntry.file().lastModified(), fileEntry.file().length());
                    applySqlSearch();
                    LOG.info("HistorySqlLogPanel.showFileContent: " + fileEntry.file().getName()
                            + " 共 " + entries.size() + " 条 SQL");
                });
            }
        }.queue();
    }

    /** 测试缝：同步加载文件内容（生产路径 showFileContent 已后台化，headless 测试需要确定性）。 */
    private void loadFileContentSync(HistoryFileEntry fileEntry) {
        currentFile = fileEntry;
        templateFocusKey = null; // O-90：换文件即清除同模板聚焦（与生产路径同语义）
        // T-UiNative T-UN-05：二级 SQL 列表可见后确保速度搜索安装（生产 showFileContent 已装，
        // 此同步路径 headless 下 CardLayout 不见真机 showing，marker 判定可能不触发，故统一补装）
        if (!sqlSpeedSearchInstalled) {
            sqlSpeedSearchInstalled = true;
            ListSpeedSearch.installOn(sqlList, entry -> SqlLogPanelKt.withTerminator(entry.restoredSql()));
        }
        refreshNavToolbar(); // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = true）
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.sql"));
        cardLayout.show(cards, CARD_SQL);
        clearSearchForEntryLevel();
        currentEntries = SqlLogFileReader.INSTANCE.readFromFile(fileEntry.file());
        currentFileStamp = new Pair<>(fileEntry.file().lastModified(), fileEntry.file().length());
        applySqlSearch();
    }

    // ---------- 刷新 ----------

    /**
     * T-CrudColor：仅重绘二级 SQL 列表（不重扫文件、不重载列表）。
     *
     * 设置页改了配色/标记样式后由 {@code SqlLogPanel} 的设置变更监听调用——渲染器直读设置，
     * 重绘即生效；走 repaint 而非 {@link #refresh} 是为了不打断用户当前浏览位置、不触发磁盘扫描。
     */
    public void repaintSqlList() {
        sqlList.repaint();
    }

    /**
     * 扫描 .idea 目录：生成文件摘要列表；若正在浏览某文件且文件仍存在则保持二级并重载，否则回一级。
     *
     * T-Perf-05：目录扫描 + countInFile / readFromFile 全部移入 {@link Task.Backgroundable}
     * （100MB 自动保存文件的逐行扫描在 EDT 直跑必然长冻结）；EDT 只做结果应用。
     * 代际守卫：新一轮刷新/加载启动后，旧后台任务的 apply 自动作废。
     *
     * <p>T-SqlLogPerf（20261006-1020 计划 P1）：浏览态刷新不再<b>无条件</b>全量重读当前文件——
     * 仅当 (lastModified, length) 与加载完成时快照 [currentFileStamp] 不一致（自动保存确有追加）
     * 才重读；未变则后台只做目录扫描 + countInFile（有缓存）。日常 IDE 实测 19MB/8708 条文件
     * 一次浏览窗口内被 refresh + showFileContent 全量解析约 3 遍（15~16.6s），此处消除 refresh 侧的重复成本。
     */
    /** F88 测试缝：refresh 实际调用计数（判别「构造即扫」与「懒首扫」——构造路径须为 0；Kotlin private set 形态）。 */
    private int refreshInvocationCountForTest = 0;

    /** F88 测试缝读数（internal 读 → public getter）。 */
    public int getRefreshInvocationCountForTest() {
        return refreshInvocationCountForTest;
    }

    public void refresh() {
        refreshInvocationCountForTest++;
        // T-HistoryTabAlways：记录本次扫描所用目录（EDT 侧纯字段写），供设置变更时判定要不要重扫
        lastScannedDir = getHistoryDir();
        final int gen = ++bgGeneration;
        final File keepFile = currentFile != null ? currentFile.file() : null;
        new Task.Backgroundable(project, MpSqlVisualizerBundle.message("log.history.scan.progress"), true) {
            @Override
            public void run(ProgressIndicator indicator) {
                final List<HistoryFileEntry> scanned = scanHistoryFiles(getHistoryDir());
                final SqlReload keepReload;
                if (keepFile != null && keepFile.exists()) {
                    Pair<Long, Long> stamp = new Pair<>(keepFile.lastModified(), keepFile.length());
                    // currentFileStamp 为 EDT 写的 Swing 字段，此处读只做「是否变化」启发判定：
                    // 竞态最坏结果是多读一次文件，无正确性风险（读到 null 视为变化 → 重读，行为同旧版）
                    keepReload = stamp.equals(currentFileStamp)
                            ? null
                            : new SqlReload(keepFile, SqlLogFileReader.INSTANCE.readFromFile(keepFile), stamp);
                } else {
                    keepReload = null;
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (gen != bgGeneration || project.isDisposed()) {
                        return;
                    }
                    applyRefresh(scanned, keepFile, keepReload);
                });
            }
        }.queue();
    }

    /** 测试缝：同步刷新（生产路径 refresh() 已后台化；headless 测试需要确定性）。 */
    public void refreshForTest() {
        bgGeneration++; // 作废在途后台 apply（构造期 refresh() 的后台扫描可能尚未完成）
        String dir = getHistoryDir();
        lastScannedDir = dir;
        File keepFile = currentFile != null ? currentFile.file() : null;
        SqlReload keepReload = null;
        if (keepFile != null && keepFile.exists()) {
            keepReload = new SqlReload(keepFile, SqlLogFileReader.INSTANCE.readFromFile(keepFile),
                    new Pair<>(keepFile.lastModified(), keepFile.length()));
        }
        applyRefresh(scanHistoryFiles(dir), keepFile, keepReload);
    }

    /** 目录扫描 + 条目计数（纯 IO，后台执行）。 */
    private List<HistoryFileEntry> scanHistoryFiles(String dir) {
        File directory = new File(dir);
        List<File> files;
        if (directory.isDirectory()) {
            File[] raw = directory.listFiles(f -> f.getName().endsWith(".sql"));
            files = raw == null ? List.of() :
                    Arrays.stream(raw).sorted(Comparator.comparing(File::getName)).collect(Collectors.toList());
        } else {
            files = List.of();
        }
        List<HistoryFileEntry> result = new ArrayList<>();
        for (File file : files) {
            result.add(new HistoryFileEntry(file, SqlLogFileReader.INSTANCE.countInFile(file)));
        }
        return result;
    }

    /**
     * 一级列表空态渲染（T-SettingsFocusFromLink）：文案按行 appendLine（StatusText 不解析换行转义），
     * 「未开启自动保存 且 无历史文件」时追加「自动保存历史 SQL」蓝字链接（点击打开设置并定位高亮
     * 「自动保存历史 SQL」设置行）；其余态仅纯文案。刷新点=init 与 {@link #applyRefresh}（扫描落地）。
     */
    private void updateFilesEmptyText() {
        com.intellij.util.ui.StatusText status = fileList.getEmptyText();
        status.clear();
        if (!allFiles.isEmpty()) {
            status.setText(MpSqlVisualizerBundle.message("log.history.search.noMatches"));
            return;
        }
        for (String line : MpSqlVisualizerBundle.message("log.history.files.empty").split("\n", -1)) {
            status.appendLine(line);
        }
        boolean autoSaveEnabled = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
                .getState().getLogAutoSaveEnabled();
        if (HistorySqlLogPanelKt.shouldShowAutoSaveHistoryLink(autoSaveEnabled, allFiles.size())) {
            CaptureDiagnostics.appendLinkRow(
                    status,
                    List.of(new Pair<>(
                            MpSqlVisualizerBundle.message("log.history.empty.link.autoSave"),
                            (java.awt.event.ActionListener) e ->
                                    cn.song.mybatisplus.visualizer.config.PluginSettingsFolderConfigurable
                                            .openSqlLogSettingsFocused(
                                                    project,
                                                    MpSqlVisualizerBundle.message("settings.log.autoSave")))));
        }
    }

    /** T-SqlLogPerf：浏览文件重载结果包（条目 + 读取完成时的 (lastModified, length) 快照）。 */
    private static final class SqlReload {
        final File file;
        final List<LogSqlEntry> entries;
        final Pair<Long, Long> stamp;

        SqlReload(File file, List<LogSqlEntry> entries, Pair<Long, Long> stamp) {
            this.file = file;
            this.entries = entries;
            this.stamp = stamp;
        }
    }

    /** 应用扫描结果（EDT；仅刷新/加载代际最新者执行）。 */
    private void applyRefresh(List<HistoryFileEntry> scanned, File keepFile, SqlReload keepReload) {
        allFiles = scanned;
        LOG.info("HistorySqlLogPanel.refresh: 扫描到 " + allFiles.size() + " 个历史文件");
        // T-SettingsFocusFromLink：空态条件链接随扫描结果刷新（未开自动保存且无文件时露出）
        updateFilesEmptyText();

        // 保持二级视图：当前文件仍存在则保留二级
        if (keepFile != null && keepFile.exists()) {
            // 仅文件真有变化（快照不一致）才替换解析结果；未变时 currentEntries/滚动位置原样保留
            if (keepReload != null) {
                currentEntries = keepReload.entries;
                currentFileStamp = keepReload.stamp;
                applySqlSearch();
            }
            return;
        }
        // 否则回一级（默认展示文件列表）
        currentFile = null;
        currentFileStamp = null;
        currentEntries = List.of();
        templateFocusKey = null; // O-90：回一级清除同模板聚焦（chip 同步隐）
        refreshNavToolbar(); // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = false）
        searchField.getTextEditor().getEmptyText().setText(MpSqlVisualizerBundle.message("log.history.search.files"));
        cardLayout.show(cards, CARD_FILES);
        applyFileSearch();
    }

    // ---------- 搜索过滤 ----------

    /** T-Perf-05：搜索输入防抖调度（DocumentListener 入口）。 */
    private void scheduleSearch() {
        searchDebounceTimer.restart();
    }

    private void applySearch() {
        // T-LogToSqlHint 批：过滤真正应用（防抖后单点）喂本组件搜索历史（平台历史钮已接管为自有实现）
        nativeSearch.recordSearchQuery(searchField.getText());
        if (currentFile != null) {
            applySqlSearch();
        } else {
            applyFileSearch();
        }
    }

    /**
     * 一级：按文件名过滤。T-HistorySearchToggles：matchCase 开关生效（默认关=忽略大小写）；
     * regex 开关<b>不参与</b>（文件名正则价值低，tooltip 已写明两级语义差）。
     */
    private void applyFileSearch() {
        String query = searchField.getText().trim();
        fileListModel.clear();
        List<HistoryFileEntry> filtered;
        if (query.isEmpty()) {
            filtered = allFiles;
        } else {
            filtered = new ArrayList<>();
            String lowerQuery = query.toLowerCase(Locale.ENGLISH);
            for (HistoryFileEntry it : allFiles) {
                boolean hit = matchCaseEnabled()
                        ? it.getDisplayName().contains(query)
                        : it.getDisplayName().toLowerCase(Locale.ENGLISH).contains(lowerQuery);
                if (hit) {
                    filtered.add(it);
                }
            }
        }
        for (HistoryFileEntry it : filtered) {
            fileListModel.addElement(it);
        }
        // 一级仍为文件名子串搜索；从二级返回时清掉与此层无关的正则错误。
        nativeSearch.setSearchError(null);
        updateFilesEmptyText();
        // T-SearchToggleNativeLook：原生状态区读数（对齐 IDEA 搜索条「N results」位）
        nativeSearch.setStatusText(query.isEmpty()
                ? ""
                : MpSqlVisualizerBundle.message("log.history.search.results", filtered.size()));
    }

    private void applySqlSearch() {
        // O-90：同模板计数随文件内容重建（**全量条目**聚合——徽章计数不随搜索/聚焦波动）；
        // 渲染器与右键聚焦动作共用同一快照。
        // F88（T-PerfSweep25）：聚合与查询无关、只随条目快照换代——同快照反复 apply（空查询
        // 防抖反复触发、查询进出）不再重复 O(N) 聚合（报告「空查询聚合不短路」的收敛形态）；
        // 快照换代（换文件/重载/清空）仍首算，徽章口径与首屏计数不丢（朴素「空查询即跳过」
        // 会使首次空态展示丢徽章——applySqlSearch 是 templateCounts 唯一赋值点，已否）。
        if (templateCountsSnapshotFor != currentEntries) {
            templateCounts = structureKeyCountsHookForTest != null
                    ? structureKeyCountsHookForTest.apply(currentEntries)
                    : LogSqlModelsKt.structureKeyCounts(currentEntries);
            templateCountsSnapshotFor = currentEntries;
        }
        sqlCellRenderer.templateCounts = templateCounts;
        // T-HistorySearchToggles：查询解析与过滤抽为顶层纯函数 [filterHistoryEntries]（可独立单测，
        // 形态照日志页 filterBySearch 先例）；memo 实例面板持有，防抖 200ms 反复 apply 复用编译结果。
        // 正则生效条件 = 开关或 `regex:` 前缀（前缀是已发行能力，验收 3.11 不回归）；编译旗随
        // matchCase；非法正则 fail-closed 回退子串保持；行号 `#N` 精确匹配不受开关影响。
        List<LogSqlEntry> filteredEntries = HistorySqlLogPanelKt.filterHistoryEntries(
                currentEntries,
                searchField.getText().trim(),
                regexEnabled(),
                matchCaseEnabled(),
                regexMemo);
        // T-LogListRail：进度条归一基准取**当前可见条目**的最大耗时（与日志页同口径；单遍无分配）
        sqlCellRenderer.maxDurationMs = LogEntryRailKt.maxDurationOf(filteredEntries);
        String query = searchField.getText().trim();
        boolean invalidRegex = !query.isEmpty()
                && (regexEnabled() || query.startsWith(HistorySqlLogPanelKt.HISTORY_REGEX_PREFIX))
                && regexMemo.compiled == null;
        String error = invalidRegex
                ? MpSqlVisualizerBundle.message("log.history.search.invalidRegex") : null;
        // 过滤仍保留既有非法正则子串回退，但不能让「0 条」掩盖输入错误。
        nativeSearch.setSearchError(error);
        nativeSearch.setStatusText(error != null ? error
                : (query.isEmpty()
                        ? ""
                        : MpSqlVisualizerBundle.message("log.history.search.results", filteredEntries.size())));
        sqlList.getEmptyText().setText(error != null ? error : MpSqlVisualizerBundle.message(
                currentEntries.isEmpty() ? "log.history.entries.empty" : "log.history.search.noMatches"));
        showSqlEntries(filteredEntries);
    }

    /**
     * O-90：二级列表统一出口——搜索过滤结果再过同模板聚焦层后填充，并同步聚焦 chip。
     * （聚焦层插在搜索层之后：模板聚焦是对当前可见行集的再收敛，与日志页
     * filterByFocusTabs 插在 filterByFocus 之后同序。）
     */
    private void showSqlEntries(List<LogSqlEntry> entries) {
        String key = templateFocusKey;
        if (key == null) {
            fillSqlList(entries);
        } else {
            List<LogSqlEntry> focused = new ArrayList<>();
            for (LogSqlEntry e : entries) {
                if (Objects.equals(e.structureKey(), key)) {
                    focused.add(e);
                }
            }
            fillSqlList(focused);
        }
        syncTemplateChip();
    }

    /**
     * O-90：同模板聚焦统一入口（右键动作 / chip 点击 / 测试驱动同缝）：key 非空 → 二级列表
     * 只显该模板条目；null → 清除。非二级视图只记状态不刷列表（切入文件时按现状重算）。
     */
    public void applyTemplateFocus(String key) {
        templateFocusKey = key;
        if (currentFile != null) {
            applySqlSearch();
        } else {
            syncTemplateChip();
        }
    }

    /** O-90：同步聚焦提示 chip（聚焦时显示该模板计数 + ✕，点击清除；未聚焦隐藏）。 */
    private void syncTemplateChip() {
        String key = templateFocusKey;
        if (key == null) {
            templateFilterChip.setVisible(false);
            templateFilterChip.setToolTipText(null);
            return;
        }
        Integer count = templateCounts.get(key);
        templateFilterChip.setText(MpSqlVisualizerBundle.message(
                "log.panel.templateBadge", count != null ? count : 0) + " ✕");
        templateFilterChip.setToolTipText(MpSqlVisualizerBundle.message("log.history.templateFilter.clearTip"));
        templateFilterChip.setVisible(true);
    }

    /**
     * T-SqlHistoryFull → T-SqlLogPerf 演进（20261006-1020 计划 P3/P4）：二级列表<b>滚动追加</b>填充。
     *
     * <p>旧版：首屏 30 条立即写入 + 剩余每 30 条 invokeLater 链式追加——8708 条文件排 290 个 EDT 脉冲，
     * 无论用户看多少都全量进模型。改为：首屏 30 条立即写入，其余挂 [pendingEntries]，用户滚动
     * 逼近列表底部（[maybeAppendOnScroll]）时才续写下一批（每批 [REFRESH_CHUNK_SIZE] 条，
     * 滚动条 max 变更会再触发 adjustment 事件自然节流）——大文件只付出「用户真正浏览到」的成本。
     * gen 代际守卫保留：新一轮 fillSqlList（重新加载/搜索/刷新）启动即作废旧待写队列。
     */
    private void fillSqlList(List<LogSqlEntry> entries) {
        final int gen = ++bgGeneration;
        pendingEntries = List.of();
        pendingAppendIndex = 0;
        pendingAppendGen = gen;
        LogPerfProbe.measure("History.fillSqlList", () -> entries.size() + " 条", () -> {
            sqlList.setIgnoreRepaint(true);
            sqlListModel.clear();
            if (!entries.isEmpty()) {
                // 批量写入（master `sqlListModel.addAll(entries.take(N))`）——**必须整批一次 addAll**：
                // 逐条 addElement 会为每行各发一次模型变更事件 + 布局失效，正是 T-LogListFreeze 要消除的
                // 历史打开卡顿主源（T-SyncK2JMaster115 由 HistorySqlLogPanelNavigationTest 的分块轮数断言抓出）。
                sqlListModel.addAll(entries.subList(0, Math.min(REFRESH_FIRST_BATCH_SIZE, entries.size())));
            }
            sqlList.setIgnoreRepaint(false);
            sqlList.revalidate();
            sqlList.repaint();
            return null;
        });

        if (entries.size() > REFRESH_FIRST_BATCH_SIZE) {
            pendingEntries = entries;
            pendingAppendIndex = REFRESH_FIRST_BATCH_SIZE;
        }
        maybeAppendOnScroll();
    }

    /** 滚动追加触发窗口：视口底沿距列表底沿小于该像素即续写下一批（约半屏，提前装载避免跟随感断裂）。 */
    private static final int APPEND_TRIGGER_PIXELS = 480;

    /** 滚动逼近底部时续写待写条目（adjustment 事件 + fillSqlList 尾部各调一次；一次最多一批，靠滚动条 max 变更再触发）。 */
    private void maybeAppendOnScroll() {
        if (!sqlList.isShowing()) {
            return; // 未显示（headless/隐藏卡）不自动续写——测试经 [appendPendingForTest] 驱动
        }
        appendPendingIfNearBottom();
    }

    /** 测试缝（master 为 internal 可见性）：headless 下无真实滚动事件，直接驱动滚动追加（与 adjustment 监听同一实现路径）。 */
    public void appendPendingForTest() {
        appendPendingIfNearBottom();
    }

    private void appendPendingIfNearBottom() {
        if (pendingAppendIndex >= pendingEntries.size()) {
            return;
        }
        javax.swing.JScrollBar bar = sqlScrollPane != null ? sqlScrollPane.getVerticalScrollBar() : null;
        if (bar != null) {
            int distance = bar.getMaximum() - (bar.getValue() + bar.getVisibleAmount());
            if (distance > APPEND_TRIGGER_PIXELS) {
                return;
            }
        }
        if (pendingAppendGen != bgGeneration || project.isDisposed()) {
            pendingEntries = List.of();
            return;
        }
        int end = Math.min(pendingEntries.size(), pendingAppendIndex + REFRESH_CHUNK_SIZE);
        sqlListModel.addAll(pendingEntries.subList(pendingAppendIndex, end));
        pendingAppendIndex = end;
        if (pendingAppendIndex >= pendingEntries.size()) {
            pendingEntries = List.of();
        }
    }

    /** 滚动追加待写队列（fillSqlList 写完首屏后的剩余条目；追加完毕置空）。 */
    private List<LogSqlEntry> pendingEntries = List.of();

    /** 待写队列下一位游标（避免每批 drop() 拷贝余量）。 */
    private int pendingAppendIndex = 0;

    /** 待写队列所属代际（滚动回调校验，旧代际队列作废）。 */
    private int pendingAppendGen = 0;

    /** T-SqlLogPerf：当前浏览文件加载完成时的 (lastModified, length) 快照——refresh() 据此判定文件是否变化。 */
    private Pair<Long, Long> currentFileStamp = null;

    /** T-HistoryTabAlways：上次扫描所用目录——设置变更时据此判定是否需要重扫（保存目录被改过）。 */
    private String lastScannedDir = "";

    /** 历史 SQL 目录：与写端同源解析（自定义保存目录优先；无项目根回落 user.home）。 */
    private String getHistoryDir() {
        return SqlLogAutoSaver.resolveHistoryDir(
                PluginSettings.getInstance().getState().getLogAutoSavePath(),
                project.getBasePath());
    }

    private void openPreview(LogSqlEntry entry) {
        SqlPreviewService.showResult(project, buildPreviewResult(entry));
    }

    /**
     * 双击预览的 PreviewResult 构建（T-HistParam F3，internal 供 UI 测试不弹窗断言识别结果）：
     * 与日志 tab 共用 toPreviewResult 统一识别通道——v2 历史条目（rawSql/params 齐全）参数行
     * 与日志页一致，字面量条目走 F4 恢复；T-ListPreviewCopy：formatSql 改跟随设置
     * 「预览时格式化还原 SQL」（logFormatSql，默认开=默认格式化），消除与日志 tab 双击预览的不一致。
     */
    public PreviewResult buildPreviewResult(LogSqlEntry entry) {
        return LogEntryPreviewRecognizerKt.toPreviewResult(entry,
                PluginSettings.getInstance().getState().getLogFormatSql(),
                PluginSettings.getInstance().getState().getLiteralParamRecovery());
    }

    // ---------- 二级右键菜单 ----------

    private void showSqlContextMenu(MouseEvent e) {
        int idx = sqlList.locationToIndex(e.getPoint());
        if (idx < 0) {
            return;
        }
        // T-ListPreviewCopy：与日志 tab 同款选中收敛——命中行已在多选内保留（复制项=批量复制多条），
        // 否则收敛为该行；跳转等其余菜单项恒作用于被右键的行
        sqlList.setSelectedIndices(
                SqlLogPanelKt.resolveContextMenuSelection(sqlList.getSelectedIndices(), idx));
        LogSqlEntry entry = sqlListModel.getElementAt(idx);
        List<LogSqlEntry> selectedEntries = new ArrayList<>();
        for (int i : sqlList.getSelectedIndices()) {
            selectedEntries.add(sqlListModel.getElementAt(i));
        }

        // T-UiNative P1a：JPopupMenu+JMenuItem → 平台 DefaultActionGroup+AnAction（ActionPopupMenu 承载）
        ActionPopupMenu popup = ActionManager.getInstance()
                .createActionPopupMenu("mpvs.history.sql", buildSqlContextMenu(entry, selectedEntries));
        // 243 的 ActionPopupMenu 只有 setter 无 getter，Kotlin 不合成 targetComponent 属性——显式调 setter
        popup.setTargetComponent(sqlList);
        popup.getComponent().show(sqlList, e.getX(), e.getY());
    }

    /**
     * 二级右键菜单动作组（R31 抽取 internal：测试断言「对比选中两条」接线与灰置规则）。
     * 动作集与抽取前逐项一致，末位追加 R31 对比动作。
     */
    public DefaultActionGroup buildSqlContextMenu(LogSqlEntry entry, List<LogSqlEntry> selectedEntries) {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new CopySqlAction(selectedEntries));
        // T-LogSearchRowTime：复制时间戳（与日志页同款——纯时间文本无方括号；显示关闭时置灰，
        // 置灰逻辑在 CopyTimestampAction.update）
        String timestampClip = SqlLogPanelKt.batchCopyTimestamps(selectedEntries);
        group.add(new CopyTimestampAction(selectedEntries, timestampClip));
        group.add(new JumpToSourceAction(entry));
        // T-M55-01：拆「跳转到 XML」双目标（与日志页同套）
        group.add(new JumpToMapperXmlAction(entry));
        // O-90：按模板聚焦（「×N 同模板」徽章达阈值才可用；过滤出该模板全部条目，chip 点击清除）
        group.add(new FocusTemplateAction(entry));
        // R4（W3 历史工作流包）：导出选中/全部条目（CSV/TSV/TXT，后台写盘）
        List<LogSqlEntry> allEntries = new ArrayList<>();
        for (int i = 0; i < sqlList.getModel().getSize(); i++) {
            allEntries.add(sqlList.getModel().getElementAt(i));
        }
        group.add(new ExportSqlAction(selectedEntries.isEmpty() ? allEntries : selectedEntries));
        // R31：历史/版本 SQL 对比（恰两条选中可用）
        group.add(new CompareSelectedAction(selectedEntries));
        return group;
    }

    /**
     * R31：历史/版本 SQL 对比——多选恰两条时可用；对比 = {@code SqlDiffer} 逐行 diff + 结构指纹
     * （{@code LogSqlEntry.structureKey}，同指纹提示「结构等价，仅参数不同」）。
     * 呈现复用预览面板 showSqlDiff 的 IDEA 原生 Diff 形态（非模态、SQL 语法高亮、同步滚动）。
     */
    public class CompareSelectedAction extends AnAction {
        private final List<LogSqlEntry> entries;

        CompareSelectedAction(List<LogSqlEntry> entries) {
            this.entries = entries;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.Diff);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.history.compare"));
            e.getPresentation().setDescription(MpSqlVisualizerBundle.message("log.history.compare.tip"));
            e.getPresentation().setEnabled(entries.size() == 2);
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            if (entries.size() != 2) {
                return;
            }
            showHistoryDiff(entries.get(0), entries.get(1));
        }
    }

    /**
     * R31：打开对比窗口——IDEA 原生 Diff（形态照抄 SqlPreviewPanel.showSqlDiff：
     * DiffContentFactory.createEditable + SimpleDiffRequest → DiffManager.showDiff），
     * 标题带结构指纹结论（等价提示「仅参数不同」，否则带差异行数）。
     */
    private void showHistoryDiff(LogSqlEntry first, LogSqlEntry second) {
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            return;
        }
        HistoryDiffVerdict verdict = HistorySqlLogPanelKt.historyDiffVerdict(first, second);
        com.intellij.diff.DiffContentFactory diffContentFactory = com.intellij.diff.DiffContentFactory.getInstance();
        com.intellij.openapi.fileTypes.FileType sqlFileType =
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("SQL");
        com.intellij.diff.contents.DocumentContent leftContent =
                diffContentFactory.createEditable(project, verdict.oldSql(), sqlFileType);
        com.intellij.diff.contents.DocumentContent rightContent =
                diffContentFactory.createEditable(project, verdict.newSql(), sqlFileType);
        com.intellij.diff.requests.SimpleDiffRequest request = new com.intellij.diff.requests.SimpleDiffRequest(
                HistorySqlLogPanelKt.historyDiffDialogTitle(verdict),
                leftContent,
                rightContent,
                MpSqlVisualizerBundle.message("log.history.compare.old"),
                MpSqlVisualizerBundle.message("log.history.compare.new"));
        com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
    }

    // ---------- R4（W3 历史工作流包）：二级 SQL 导出（CSV/TSV/TXT/SQL，后台写盘） ----------

    private class ExportSqlAction extends AnAction {
        private final List<LogSqlEntry> entries;

        ExportSqlAction(List<LogSqlEntry> entries) {
            this.entries = entries;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.Export);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.history.export"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            exportEntries(entries);
        }
    }

    /** R4：导出选中/全部条目——格式四选（CSV/TSV/TXT/SQL，默认 SQL）→ 文件保存对话框 → 后台写盘 → 通知。
     *  照 ShareDialog 的 FileSaverDialog 惯例（仓内既有形态）。 */
    public void exportEntries(List<LogSqlEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        SqlExportFormat format = chooseExportFormat();
        if (format == null) {
            return;
        }
        List<SqlExportRow> rows = new ArrayList<>();
        for (LogSqlEntry it : entries) {
            StringBuilder source = new StringBuilder();
            if (it.sourceFile() != null && !it.sourceFile().isBlank()) {
                source.append(it.sourceFile());
                if (it.sourceLine() > 0) {
                    source.append(":").append(it.sourceLine());
                }
            }
            rows.add(new SqlExportRow(
                    SqlExportService.formatTime(it.timestamp()),
                    it.restoredSql(),
                    source.toString(),
                    it.executionTimeMs()));
        }
        com.intellij.openapi.fileChooser.FileSaverDescriptor descriptor =
                new com.intellij.openapi.fileChooser.FileSaverDescriptor(
                        MpSqlVisualizerBundle.message("log.history.export"),
                        "SQL (" + format.getExt().toUpperCase(Locale.ROOT) + ")",
                        format.getExt());
        com.intellij.openapi.fileChooser.FileSaverDialog dialog =
                com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
                        .createSaveFileDialog(descriptor, project);
        // T-ExportSqlFormat（用户拍板 20261009）：SQL 格式默认名 sql-export-<yyyyMMdd-HHmmss>.sql
        // （时间戳防同日覆盖、不占 sql-history 名族）；CSV/TSV/TXT 保持原名随 ext。
        String defaultName = format == SqlExportFormat.SQL
                ? SqlExportService.defaultSqlExportFileName()
                : "sql-history." + format.getExt();
        com.intellij.openapi.vfs.VirtualFileWrapper saved =
                dialog.save((com.intellij.openapi.vfs.VirtualFile) null, defaultName);
        if (saved == null) {
            return;
        }
        File target = saved.getFile();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                // R60：导出走展示脱敏（displayMaskEnabled；投影级，数据层条目不动）
                boolean exportMask = PluginSettings.getInstance().getState().getDisplayMaskEnabled();
                SqlExportService.exportToFile(target, SqlExportService.format(rows, format, exportMask));
                com.intellij.notification.NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP_ID)
                        .createNotification(
                                MpSqlVisualizerBundle.message("log.history.export.done", rows.size(), target.getAbsolutePath()),
                                com.intellij.notification.NotificationType.INFORMATION)
                        .notify(project);
            } catch (Throwable t) {
                com.intellij.notification.NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP_ID)
                        .createNotification(
                                MpSqlVisualizerBundle.message("log.history.export.failed",
                                        t.getMessage() != null ? t.getMessage() : ""),
                                com.intellij.notification.NotificationType.ERROR)
                        .notify(project);
            }
        });
    }

    /** R4：导出格式四选（取消返回 null）。选项文本用格式名（CSV/TSV/TXT/SQL 无需翻译）；
     *  默认选中 SQL（T-ExportSqlFormat 用户拍板 20261009；P3：showChooseDialog 返回所选下标、取消 -1）。 */
    private SqlExportFormat chooseExportFormat() {
        String[] names = {"CSV", "TSV", "TXT", "SQL"};
        int idx = com.intellij.openapi.ui.Messages.showChooseDialog(
                project,
                MpSqlVisualizerBundle.message("log.history.export.choose"),
                MpSqlVisualizerBundle.message("log.history.export"),
                MpvsIcons.Export,
                names,
                names[3]);
        if (idx < 0) {
            return null;
        }
        switch (idx) {
            case 0:
                return SqlExportFormat.CSV;
            case 1:
                return SqlExportFormat.TSV;
            case 2:
                return SqlExportFormat.TXT;
            case 3:
                return SqlExportFormat.SQL;
            default:
                return null;
        }
    }

    // ---------- T-UiNative P1a：二级右键菜单动作（原 JMenuItem 语义平移，update 设图标/文案/灰置） ----------

    private class CopySqlAction extends AnAction {
        private final List<LogSqlEntry> entries;

        CopySqlAction(List<LogSqlEntry> entries) {
            this.entries = entries;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.Copy);
            e.getPresentation().setText(SqlLogPanelKt.copyMenuText(
                    "log.history.copy", "log.history.copySelected", entries.size()));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            UiTextClipboard.copy(SqlLogPanelKt.batchCopySql(entries));
        }
    }

    private class CopyTimestampAction extends AnAction {
        private final List<LogSqlEntry> entries;
        private final String clip;

        CopyTimestampAction(List<LogSqlEntry> entries, String clip) {
            this.entries = entries;
            this.clip = clip;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.Copy);
            e.getPresentation().setText(SqlLogPanelKt.copyMenuText(
                    "log.history.copyTimestamp", "log.history.copyTimestampSelected", entries.size()));
            e.getPresentation().setEnabled(!clip.isEmpty());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            if (!clip.isEmpty()) {
                UiTextClipboard.copy(clip);
            }
        }
    }

    /** 跳转到 Java 调用方（T-M55-01：与「跳转到 XML」拆分为双目标，与日志页同套）。 */
    private class JumpToSourceAction extends AnAction {
        private final LogSqlEntry entry;

        JumpToSourceAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons.Export);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.history.jumpToJava"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            jumpToSource(entry);
        }
    }

    /**
     * O-90：按模板聚焦——右键带「×N 同模板」徽章的条目聚焦该模板（structureKey 精确匹配，
     * 与日志页 FocusTemplateAction 同形态：条目驱动的聚焦过滤器）；历史页无页签栏，
     * 聚焦态由工具栏 chip 承载（计数 + ✕ 点击清除，{@link #applyTemplateFocus}）。
     * 准入 = 计数达 {@code LogSqlModelsKt.TEMPLATE_BADGE_MIN_COUNT}（徽章在场才可点）。
     */
    private class FocusTemplateAction extends AnAction {
        private final LogSqlEntry entry;

        FocusTemplateAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.focusTemplate"));
            // T-DevAcceptFix2 ⑪（20261006 第三轮走查）：菜单项带判定口径说明（与日志页同源文案）
            e.getPresentation().setDescription(MpSqlVisualizerBundle.message("log.panel.focusTemplate.desc"));
            Integer n = templateCounts.get(entry.structureKey());
            e.getPresentation().setEnabled(n != null && n >= LogSqlModelsKt.TEMPLATE_BADGE_MIN_COUNT);
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            Integer n = templateCounts.get(entry.structureKey());
            if (n == null) {
                return;
            }
            if (n >= LogSqlModelsKt.TEMPLATE_BADGE_MIN_COUNT) {
                applyTemplateFocus(entry.structureKey());
            }
        }
    }

    /**
     * T-M55-01：跳转到 mapper XML 语句节点。mapperStatement 非空且定位成功才显示
     * （update 内解析一次并存引用）；定位失败不显示，「跳转到 Java」承担回退，不劣化。
     */
    class JumpToMapperXmlAction extends AnAction {
        private final LogSqlEntry entry;
        private HjtMapperXml resolved = null;

        JumpToMapperXmlAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setIcon(MpvsIcons.MapperXml);
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.history.jumpToXml"));
            resolved = resolveXmlJumpTarget(entry);
            e.getPresentation().setEnabledAndVisible(resolved != null);
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            HjtMapperXml target = resolved != null ? resolved : resolveXmlJumpTarget(entry);
            if (target == null) {
                return;
            }
            new OpenFileDescriptor(project, target.getVirtualFile(), target.getLine(), 0).navigate(true);
        }
    }

    /**
     * 解析跳转目标（与 {@link #jumpToSource(LogSqlEntry)} 共用；测试驱动直接断言分支，不触发弹窗）。
     * T-SourceJumpFunnel P0-5：判定收敛到 {@link EntryJumpResolver}（与实时面板同一实现）——
     * 主位置不在工程内（自研框架汇聚门面帧）时按候选栈序回退，本面板此前无此能力。
     * T-RecentExecMenu O-119：转接原语收口 {@link LogEntryJumpOps}（三处共用）；<b>本页策略</b>=
     * {@code MissingSource} 并入 {@code NoSource}（走引导弹窗，与实时页的轻通知不同体，各自保留）。
     */
    public HistoryJumpTarget resolveJumpTarget(LogSqlEntry entry) {
        LogEntryJumpOps.SourceTarget target = LogEntryJumpOps.resolveSource(project, entry);
        if (target instanceof LogEntryJumpOps.SourceTarget.Precise precise) {
            return new HjtNavigate(precise.virtualFile(), precise.line());
        }
        if (target instanceof LogEntryJumpOps.SourceTarget.NoSource) {
            return HjtNoSource.INSTANCE;
        }
        if (target instanceof LogEntryJumpOps.SourceTarget.MissingSource) {
            return HjtNoSource.INSTANCE;
        }
        if (target instanceof LogEntryJumpOps.SourceTarget.NotFound notFound) {
            return new HjtNotFound(notFound.sourceFile());
        }
        // sealed 穷尽（Kotlin when 无 else）——防御分支，正常不可达
        throw new IllegalStateException("HistorySqlLogPanel.resolveJumpTarget: unexpected " + target);
    }

    /**
     * T-M55-01：XML 目标解析——与日志页 {@code SqlLogPanel.resolveEntryXmlJump} 同语义。
     * mapperStatement 非空且 {@link MapperXmlLocator} 定位成功才返回；否则 null（回退 Java，不劣化）。
     * T-RecentExecMenu O-119：解析原语收口 {@link LogEntryJumpOps#resolveXml}。
     */
    public HjtMapperXml resolveXmlJumpTarget(LogSqlEntry entry) {
        LogEntryJumpOps.XmlTarget target = LogEntryJumpOps.resolveXml(project, entry);
        return target != null ? new HjtMapperXml(target.virtualFile(), target.line()) : null;
    }

    /**
     * 跳转到产生该 SQL 的源码位置（测试驱动；等价右键「跳转到源码」）。
     * 通过 sourceFile + sourceLine 直接定位（F-44 新格式）。
     */
    public void jumpToSourceForTest(LogSqlEntry entry) {
        jumpToSource(entry);
    }

    /**
     * 跳转到产生该 SQL 的源码位置。
     * 通过 sourceFile + sourceLine 直接定位（F-44 新格式）；
     * T-JumpCapture：无捕获位置时弹框引导开启源码捕获拦截器。
     * T-RecentExecMenu O-119：解析/导航/通知原语走 {@link LogEntryJumpOps}（本页策略保持原样：
     * NoSource/MissingSource 引导弹窗、NotFound 轻通知，标题键为本页口径 {@code log.history.*}）。
     */
    private void jumpToSource(LogSqlEntry entry) {
        LogEntryJumpOps.SourceTarget target = LogEntryJumpOps.resolveSource(project, entry);
        // 缺源码位置（旧版 F-43 条目 / 未启用拦截器捕获）：引导开启源码捕获（T-JumpCapture）
        if (target instanceof LogEntryJumpOps.SourceTarget.NoSource
                || target instanceof LogEntryJumpOps.SourceTarget.MissingSource) {
            LogEntryJumpOps.showMissingSourceGuide(project);
        } else if (target instanceof LogEntryJumpOps.SourceTarget.NotFound notFound) {
            // 文件不存在时提示（项目结构可能已变化）；T-UiNative T-UN-02：改轻通知（原模态弹窗）
            LogEntryJumpOps.showToast(
                    project,
                    "log.history.jumpToSource",
                    MpSqlVisualizerBundle.message("log.history.jumpToSource.notFound", notFound.sourceFile()));
        } else if (target instanceof LogEntryJumpOps.SourceTarget.Precise precise) {
            LogEntryJumpOps.navigate(project, precise.virtualFile(), precise.line());
        }
    }

    @Override
    public void dispose() {
        // T-Perf-05：面板销毁停止防抖 Timer（防晚到 apply 触碰已销毁面板）
        searchDebounceTimer.stop();
        if (previewHost != null) previewHost.hideHint();
        dockedPreview.dispose();
    }

    private static final Logger LOG = Logger.getInstance(HistorySqlLogPanel.class);
    private static final String CARD_FILES = "files";
    private static final String CARD_SQL = "sql";

    // T-RecentExecMenu O-119：跳转通知改经 [LogEntryJumpOps] 共享组；本常量保留给
    // R4 历史/日志导出 done/failed 两条通知（dev 批次 9 交付，master 未见故曾随退役删除）。
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /** T-Perf-05：搜索输入防抖窗口（毫秒）。 */
    private static final int SEARCH_DEBOUNCE_MS = 200;

    /** T-SqlHistoryFull：二级列表分批填充——首屏立即写入条数（与实时面板同值，快速首屏）。 */
    private static final int REFRESH_FIRST_BATCH_SIZE = 30;

    /** T-SqlHistoryFull：二级列表分批填充——每块 invokeLater 追加条数（防 EDT 长冻结）。 */
    private static final int REFRESH_CHUNK_SIZE = 30;
}
