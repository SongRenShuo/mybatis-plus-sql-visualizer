// T-SyncK2JMaster482 波D1 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanel.kt 增量移植
// （T-M53-II D2-5 第 4 轮：SqlPreviewPanel 2598→1323——类本体仅留字段/构造器/生命周期/测试缝；
//   参数面板、SQL 视图、结果区视图、文本工具与编辑器生命周期/执行链已拆为同包扩展函数文件）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.execute.SqlExecutor;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel;
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramPanel;
import cn.song.mybatisplus.visualizer.ui.inlay.PreviewInlayScope;
import cn.song.mybatisplus.visualizer.util.EditorCommentShortcuts;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.XmlForEachView;
import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.ui.HideableTitledPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBLoadingPanel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import kotlin.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * SQL 预览面板（M9-01/02/03 可编辑版，F-07/F-08）。
 *
 * <p>弹窗 {@link SqlPreviewDialog} 与工具窗口共用本面板，展示：</p>
 * <ul>
 *  <li>顶部单行：元信息（Wrapper 类型 / 表名 / 风险警示，T-PreviewTopOverlap F3 按宽度预算截断）
 *    + 工具栏（压缩切换 / 格式化 / 关键字换行 / 竖向排列 / 执行计划 / 刷新 + 「更多」折叠低频动作；
 *    复制错误仅警示时可见）</li>
 *  <li>中部左右分栏：左侧<b>参数值输入框</b>（`#param` → 值，输入实时回填 SQL），右侧<b>可编辑 SQL 编辑器</b></li>
 *  <li>SQL 编辑器：`EditorFactory.createEditor`（可写）+ 原生 SQL 语法高亮；文档由原生 SQL FileType PSI
 *    文件支撑，挂 {@link cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContributor}
 *    （language="SQL" + IS_PREVIEW_EDITOR 守卫）提供补全（Ctrl+Space）</li>
 *  <li>补全上下文（表名/列名）双载体传递：<b>Editor userData 优先</b>（M16.2，嵌入式编辑器编辑后 PSI
 *    可能被 commit 重建丢失 userData）+ <b>PSI userData 兜底</b></li>
 * </ul>
 *
 * <p>说明：编辑器生命周期在 {@link #removeNotify} 时释放，避免泄漏（EditorFactory.releaseEditor）。</p>
 *
 * <p>T-M53-II：构造期属性放宽为 internal——同包扩展函数（拆出的协作文件）需要读取它；
 * 可见性只在模块内变化，调用点语义不变。Java 侧 internal → public（KOTLIN_TO_JAVA_RULES §0.1）。</p>
 */
public class SqlPreviewPanel extends JBPanel<SqlPreviewPanel> implements Disposable {

    // ------------------------------------------------------------------
    // 伴生对象（Kotlin companion object → 静态成员）
    // ------------------------------------------------------------------

    /** O-4：通知分组 id（plugin.xml 注册，diff/copy 共用）。 */
    public static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /**
     * O-88：预设分区是否处于折叠态（缺省折叠=true——20261006 T-DevAcceptFix 验收反馈定案，
     * 预设是低频操作不应默认展开占竖向空间；重建时由当前折叠件实例懒惰回写，见 buildSchemeRow）。
     * 静态而非实例：gutter 换语句=关旧弹窗、开新面板实例，实例字段在新实例归零丢折叠态（真机走查实证）；
     * 跨面板实例保留、不持久化（无 PropertiesComponent/磁盘写入）。volatile：读写入口可能非 EDT。
     */
    public static volatile boolean presetSectionCollapsed = true;

    /** O-88 测试缝：折叠态静态字段读（跨实例保留用例断言用）。 */
    public static boolean presetCollapsedForTest() {
        return presetSectionCollapsed;
    }

    /** O-88 测试缝：折叠态静态字段复位（setUp 调用，防静态态跨用例/跨测试类串扰）。 */
    public static void resetPresetCollapsedForTest() {
        presetSectionCollapsed = true;
    }

    /** R22 T-04：方案行 panel.name 锚点——方案行也是「含 JBLabel 的 JBPanel」且插在 paramPanel
     *  头部，paramRowLabelForTest 按契约只取普通参数行，须按此排除（否则布局断言序号错位）。 */
    public static final String SCHEME_ROW_NAME = "mpsv.schemeRow";

    /** O-95 S3：绑定数据源指示行/标签 panel.name 锚点（测试定位 + paramRowLabelForTest 排除同口径）。 */
    public static final String BIND_STATE_ROW_NAME = "mpsv.bindStateRow";
    public static final String BIND_STATE_LABEL_NAME = "mpsv.bindStateLabel";

    /** R22 T-02/T-03：参数历史快照特殊键前缀（勾选态/foreach null/foreach 权威值；普通参数键
     *  不以 `__` 开头——键空间约定，ParamHistoryCache 对值透明）。 */
    public static final String SNAPSHOT_CHK = "__chk__:";
    public static final String SNAPSHOT_FNULL = "__fnull__:";
    public static final String SNAPSHOT_FEV = "__fev__:";

    /** R22 T-02：勾选框 client property 锚点（快照枚举 + 召回视觉同步）。 */
    public static final String PROP_TOGGLE_ID = "mpsv.toggleId";

    /**
     * T-DevAcceptFix ⑤（20261006 验收）：参数输入框的 source 锚点——召回回写按锚点显式映射
     * （syncParamFieldText），禁以 paramValues 插入序对位 paramFields 行序（方案切换后
     * 快照键序与面板构建序可漂移 → 回写错位）。
     */
    public static final String PROP_FIELD_SOURCE = "mpsv.paramSource";
    public static final String PROP_FOREACH_NULL = "mpsv.foreachNull";

    /** F-45：列信息标签页标题。 */
    public static final String COLUMN_INFO_TAB_TITLE = "列信息";

    /** T-PreviewTopOverlap F3：元信息宽度预算下限（px）——极窄时保底可读，超出仍由 ellipsize 收。 */
    public static final int MIN_META_BUDGET_PX = 80;

    /** M36.5.44：re-host 最小重挂间隔（ms）——DBN 结果刷新期高频 reparent，低于此间隔视为"还在被 DBN 处理"，跳过不抢，防死循环刷新闪烁。 */
    public static final long REHOST_MIN_INTERVAL_MS = 800L;

    /** T-RefreshDbSteal：去抖窗口内被跳过的释放 → 延迟复查间隔（ms）。取与去抖门槛同值：
     *  复查时刻距最近一次重挂必然已越过门槛，`since` 复查不会误拦。 */
    public static final long REHOST_RETRY_DELAY_MS = 800L;

    public static final Pattern NUMBER_REGEX = Pattern.compile("-?\\d+(\\.\\d+)?");
    /** T-M24-04：XML 压缩视图的空白归一化（多行文本节点 / 段间空格）。 */
    public static final Pattern WHITESPACE_REGEX = Pattern.compile("\\s+");

    /**
     * T-PT-02：TEMPORAL 参数的可解析日期形态——`yyyy` / `yyyy-M-d` / 追加 ` HH:mm[:ss[.SSS]]`
     * （`T` 分隔兼容 ISO 形态；只做提示，不阻塞渲染）。
     */
    public static final Pattern DATE_REGEX = Pattern.compile(
            "\\d{4}(-\\d{1,2}(-\\d{1,2}([ T]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?)?)?)?");

    /** T-PT-02：行尾校验警示标（淡色，深浅主题各一档）。 */
    public static final String PARAM_WARN_MARK = "⚠";
    public static final JBColor PARAM_WARN_FOREGROUND = new JBColor(0xB8860B, 0xD9A343);

    /**
     * T-PT-02：集合命名启发式的单数词排除表——以 `s` 结尾但语义为单数（状态/地址等），
     * 不做数组提示（宁缺勿错：提示只是占位文案）。
     */
    public static final Set<String> COLLECTION_NAME_EXCEPTIONS = Set.of(
            "status", "address", "class", "business", "progress", "process", "access", "alias",
            "analysis", "radius", "series", "species", "news", "pass", "glass", "mass", "press",
            "stress", "success", "express", "compass", "canvas", "atlas", "bias", "lens",
            "physics", "politics");

    /** T-Perf-04：参数击键 renderSql 防抖窗口（毫秒）。 */
    private static final int RENDER_DEBOUNCE_MS = 150;

    /**
     * T-DollarInjectMark：`${}` 直替提示的会话级关闭记忆（✕ 后本 IDE 会话所有预览面板
     * 不再弹——常用 `${orderBy}` 场景的横幅疲劳缓解；面板关闭/切换文件不重置）。
     */
    public static boolean dollarMarkDismissedSession = false;

    /** T-DollarInjectMark：横幅文案里的 `${}` token（技术记号非语言文案，双语言共用，作 {0} 参数传入）。 */
    public static final String DOLLAR_TOKEN = "${}";

    /** 测试复位：清除 `${}` 提示的会话级关闭记忆。 */
    public static void resetDollarMarkForTest() {
        dollarMarkDismissedSession = false;
    }

    /** T-PreviewFlash：EDT 等待预热产物的轮询间隔与超时（毫秒）。 */
    public static final long PREWARM_POLL_MS = 20L;
    /**
     * T-PrewarmBudget（2026-09-20 据实测重定）：预热产物的等待预算——消费侧 PrewarmAwait
     * `completeOnTimeout` 用它决定「等多久放弃、改走降级装配」。取值 500ms 覆盖冷启最大 360ms
     * 与全部复开（详见 master KDoc 与 docs/验收文档/20260920-预热耗时采样与预算建议.md）。
     */
    public static final long PREWARM_WAIT_MS = 500L;

    /**
     * T-PerfSweep6 T-PS6-04（性能报告发现 4）：预热目录残留清扫的**年龄**上限（7 天）。
     */
    public static final long STALE_PREVIEW_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * T-PerfSweep6 T-PS6-04：预热目录内本插件命名的文件**条数**上限（超出按 mtime 删最旧）。
     * 200 = 常规使用下永远撞不到（一次预览最多留 1~2 个文件），只兜「异常路径反复泄漏」的底。
     */
    public static final int PREVIEW_DIR_MAX_FILES = 200;

    /** T-FixN2：本 IDE 进程号（文件名的会话段；取不到退化为 `0`，仍是合法文件名）。 */
    private static final String PREVIEW_TEMP_FILE_OWNER =
            resolvePreviewTempFileOwner();

    private static String resolvePreviewTempFileOwner() {
        try {
            return Long.toString(ProcessHandle.current().pid());
        } catch (Throwable t) {
            return "0";
        }
    }

    /** T-FixN2：本 JVM 内预览序号（跨面板共享，保证同进程内每个预热文件名唯一）。 */
    private static final AtomicInteger PREVIEW_TEMP_FILE_SEQ = new AtomicInteger(0);

    /** T-FixN2：生成下一次预热的唯一临时文件名（形态与判据同源，见 PreviewInlayScope）。 */
    public static String nextPreviewTempFileName() {
        return PreviewInlayScope.previewTempFileName(
                PREVIEW_TEMP_FILE_OWNER,
                PREVIEW_TEMP_FILE_SEQ.incrementAndGet());
    }

    /**
     * T-FixN2：预览文件的方言映射对象——<b>父目录</b>（取不到父目录时退回文件本身，语义同改动前）。
     * 详见 master KDoc（目录映射被其下所有文件继承，一次映射覆盖全部预览文件）。
     */
    public static VirtualFile previewDialectMappingTarget(VirtualFile vf) {
        return vf.getParent() != null ? vf.getParent() : vf;
    }

    // ------------------------------------------------------------------
    // 实例字段
    // ------------------------------------------------------------------

    public final Project project;

    public final JBLabel metaLabel = new JBLabel();

    // T-PreviewTopOverlap F1/F3：顶部行与工具栏提升为字段——布局断言（topRowForTest/toolbarForTest）
    // 与宽度预算计算（applyMetaTruncation 需工具栏理想宽）使用
    public JBPanel<JBPanel<?>> topRow = null;
    public JBPanel<JBPanel<?>> toolbarPanel = null;

    // T-PreviewTopOverlap F3：元信息原文缓存——metaLineText 第一行（meta+警示，纯文本）、
    // metaPerfSegments 第二行分段（可见文本+颜色）；tooltip 恒全文，label 文本按预算重拼
    public String metaLineText = "";

    /**
     * F3 分段缓存（P5 复核修正）：meta 行<b>主体</b>（meta + 警示行）——宽度预算下恒优先保留。
     * 与 {@link #metaLineDigest} 分段缓存的原因见 {@link SqlPreviewPanelTextKt#metaLineWithinBudget}
     * （摘要不得挤掉主体）。
     */
    public String metaLineHead = "";

    /** F3 分段缓存（P5 复核修正）：meta 行尾部的调用栈内联摘要——预算不足时先截它。 */
    public String metaLineDigest = "";
    public List<MetaPerfSegment> metaPerfSegments = List.of();
    public int lastMetaBudget = -1;

    /**
     * T-SourceJumpFunnel P6 修复：meta 行 tooltip 的<b>基线文本</b>（updateMeta 每次预览重建）。
     *
     * <p>行内提示态（参数导入空剪贴板）需要临时改写可见行与 tooltip，但 tooltip 是「悬停见完整调用链」
     * 的唯一载体（§3.3 后半句），不能被提示覆写丢掉——故提示态按「提示 + 换行 + 基线」拼接，
     * 本字段是拼接的基线来源（也保证提示重复触发时幂等，不会叠加自身）。</p>
     *
     * <p>T-StackHoverUi 起栈文本移出 tooltip（见 {@link #stackHover}）：本基线只含 meta/警示/perf，
     * 「完整调用链」改由悬停弹层承接。</p>
     */
    public String metaTooltipBase = "";

    /**
     * T-StackHoverUi：调用栈悬停弹层控制器（调试器 Frames 风格，D84 拍板 A 案）。
     *
     * <p>栈文本在悬停面的唯一载体——旧实现把截断摘要+全文重复拼进 tooltip、超长单行折成文本墙；
     * 声明序在 init 块之前（构造期 NPE 固定检查项：init 内 attach/register 均读本字段）。</p>
     */
    public final StackFrameHover stackHover;

    /** F3：perf 建议分段（可见文本 + HTML 颜色值），截断时按预算丢尾段。 */
    public record MetaPerfSegment(String text, String color) {
    }

    // T-M26.6：格式化快捷键动作（跟随 IDE keymap 的 Reformat Code，兜底 Ctrl+Alt+L）——
    // 注册到面板整体，焦点在 SQL 编辑器或参数输入框时均可触发；每次触发取当前 editor 格式化
    public final AnAction reformatAction = new AnAction() {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            SqlPreviewPanelSqlViewKt.formatSql(SqlPreviewPanel.this);
        }
    };

    // 左：动态条件复选框 + 参数值输入区
    public final JBPanel<JBPanel<?>> paramPanel = new JBPanel<JBPanel<?>>() {{
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }};
    // 顶部对齐容器：BoxLayout Y_AXIS 在视口高于内容时默认垂直居中；
    // 用 BorderLayout.NOWRAP 包裹后固定参数行在顶部（用户要求"向上靠"）
    private final JBPanel<JBPanel<?>> paramTopAlign = new JBPanel<JBPanel<?>>(new BorderLayout()) {{
        add(paramPanel, BorderLayout.NORTH);
        setOpaque(false);
    }};
    // T-M26.3：滚动区提升为字段——截断上限（标签列/复选框文本）按视口宽度计算，
    // 保证行宽不超出视口（标题行右侧按钮不被推出）
    public final JBScrollPane paramScroll = new JBScrollPane(paramTopAlign) {{
        setPreferredSize(new Dimension(240, 200));
        // T-PreviewBgFit（问题一）：本面板自身给出面板底色——左侧参数区整条链是透明的
        //（paramTopAlign/viewport 均非不透明，M36.5 起用于与父容器融合），底色一直靠祖先容器提供；
        // T-UiNative P0a 把自绘 ThemedSplitPane（当时显式设了 UIUtil.getPanelBackground()）换成平台
        // OnePixelSplitter 时丢了那层背景，深色主题下参数行下方直接透出 splitter 的近黑背景。
        // 恢复祖先背景之外再让本滚动区成不透明面板灰，今后再换容器也不会复发。
        setOpaque(true);
        setBackground(UIUtil.getPanelBackground());
        getViewport().setOpaque(false);
        // M36.5：去 LAF 默认 viewport 描边（左侧白色内边框根因）
        setBorder(JBUI.Borders.empty());
    }};
    public final List<JBTextField> paramFields = new ArrayList<>();
    // T-PT-02：普通参数行行尾校验警示标（与 paramFields 同序，仅 buildParamRow 产出）
    public final List<JBLabel> paramWarnLabels = new ArrayList<>();
    public final List<JCheckBox> conditionCheckBoxes = new ArrayList<>();
    // R30 条件求值探镜：XML 条件行尾的三态结果标注（条件 id → 标签），renderSql 每轮刷新（零监听器）
    public final Map<String, JBLabel> xmlConditionOutcomeLabels = new LinkedHashMap<>();
    public Disposable autoPopupDisposable = null;
    public final List<JBTextField> conditionValueFields = new ArrayList<>();
    // T-M23-04：批量列级勾选复选框（INSERT/UPDATE/UPSERT 参数列，与动态条件复选框互斥——批量预览无 conditionViews）
    public final List<JCheckBox> batchCheckBoxes = new ArrayList<>();
    // T-M26.3：全选/全不选按钮回挂分区标题行右侧（IDEA 组操作惯例，T-M23.2 原生形态；
    // T-M26.2 曾固定面板右上角，长文本截断后按钮不再被推出视口）。
    // T-SelToggle：全选/全不选合并为单个状态切换按钮——全勾时文案「全不选」点击清空，
    // 其余态文案「全选」点击填满；文案随勾选态翻转（renderSql 末尾单点同步）。
    public JButton toggleSelectButton = null;
    public JButton clearButton = null;        // 动态条件分区清除按钮
    public JButton batchClearButton = null;   // 批量参数列分区清除按钮
    // T-M23.2：批量「参数列」区全选/全不选切换按钮（T-SelToggle 合并单按钮）
    public JButton batchToggleSelectButton = null;

    // 右：可编辑 SQL 编辑器（PSI 支撑，补全用）
    public Editor editor = null;

    /**
     * T-M27.2：自建 autopopup 的调度防重入标志（平台 autopopup 对非文件编辑器不生效）。
     * T-RA-02：监听器本体不再用字段记账——T-M52 起移除职责全在 {@link #autoPopupDisposable}
     * （`addDocumentListener(listener, disposable)`，dispose 即摘），原 `autoPopupListener`
     * 字段自此只写不读，已删；细节见 bindAutoPopup。
     */
    public boolean autoPopupScheduled = false;

    /** T-M27.4：渲染 setText 期间抑制自建 autopopup（格式化替换整文档不触发补全）。 */
    public boolean suppressAutoPopup = false;
    public PsiFile psiFile = null;
    /** M34 回归修复：预览 PSI 的临时 VirtualFile（PsiDocumentManager 追踪需真实 VFS 文件），releaseEditor 时删除。 */
    public volatile VirtualFile previewTempFile = null;
    /**
     * T-FixN2：本面板<b>已创建</b>的预览临时文件登记表（projectPreviewFile 建文件即登记，
     * releaseEditor 统一回收）。与 {@link #previewTempFile} 的分工：后者是「当前在用/最近一次预热」
     * 的那一个（唯一名字后仍可能被竞态里晚到的预热覆盖），本表是「本面板创建过的全部」——
     * 覆盖「预热已发布但从未被装配消费」与「两次预热竞态中晚到的那一个」两类无主文件。
     *
     * <p>只登记本面板自己创建的文件（含跨会话同名残留的兜底复用分支），故 releaseEditor 的删除
     * <b>不会</b>误删其它面板/其它 IDE 会话正在使用的预览文件（同项目多开进程各有自己的 pid 段）。</p>
     */
    public final Set<java.io.File> previewTempFilesCreated = ConcurrentHashMap.newKeySet();

    /**
     * T-PerfSweep6 T-PS6-04：目录级兜底清扫的「本面板已扫过」闸门（首次解析预热目录时扫一次）。
     * 原子置位：`projectPreviewFile` 可能被预热线程与 EDT 同时触达。
     */
    public final AtomicBoolean previewDirSwept = new AtomicBoolean(false);
    /** T-M36.6-03：后台预热产物（vf+PSI 就绪对），createEditor 优先消费——EDT 零慢操作。 */
    public volatile Pair<VirtualFile, PsiFile> prewarmedTempFile = null;

    /**
     * T-PerfTail #2：预热产物异步载体（消除 EDT sleep-poll）——prewarmPreviewTempFile 每次
     * 预热新建（clear 重预热随之更新），createEditor 热态同步消费、冷态注册回调回 EDT 装配。
     */
    public volatile PrewarmAwait<Pair<VirtualFile, PsiFile>> prewarmAwait = null;
    /** T-GifRerecord：预热失败标志——await 快速降级用，避免 EDT 空等 2s 后再走降级路径。 */
    public volatile boolean prewarmFailed = false;

    /**
     * T-PrewarmLateRecover F1/F2：预热轮次令牌——每次发起预热（构造期 / clear 重预热 / 横幅重试）
     * 自增，预热线程发布产物时比对。不等即「本轮已被后来的一轮取代」：产物不入字段（否则旧轮的
     * 迟到产物会覆盖新轮刚交付的那份），文件仍由 releaseEditor 按登记表回收。
     */
    public volatile int prewarmRound = 0;

    /**
     * T-PrewarmLateRecover F1：当前编辑器文档是<b>降级产物</b>时的身份标记（主路径装配成功置 null）。
     * 迟到产物换装前用它确认「此刻显示的确实是那份降级文档」——不比对身份就可能把用户已经
     * 在用的主路径文档误换掉。
     */
    public Document degradedPreviewDocument = null;

    /**
     * T-PrewarmLateRecover F1：插件最后一次写进编辑器文档的 SQL 文本——换装判据里的「用户没动过」。
     * 三条写入支链（原生控制台 / 既有编辑器 / 新建编辑器）都要更新，漏一条即把插件的重渲染
     * 误判成用户编辑（换装被永久挡掉）。
     */
    public String lastRenderedSql = null;

    /** R90：同目标会话基线与持久化历史协调；未知 UUID 仅按目标对象身份比较。 */
    public final cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession explainPlanHistorySession =
            new cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession();

    /** R91：本次预览已确认的 EXPLAIN 目标；绝不从项目最近使用来源推断。 */
    public AiContextTarget aiContextTarget = null;

    /** R91：本面板最近一次获取的计划，历史清空/加载换代即失效。 */
    public kotlin.Pair<Long, cn.song.mybatisplus.visualizer.history.ExplainPlanSnapshot> aiContextPlan = null;

    /** R91：AI 上下文计划的确认目标（预览 + 计划键 + 仍有效判定）。 */
    public record AiContextTarget(
            PreviewResult preview,
            cn.song.mybatisplus.visualizer.history.ExplainPlanKey key,
            java.util.function.Supplier<Boolean> stillCurrent) {
    }

    /**
     * T-PrewarmLateRecover F1：预热相关回调的 EDT 派发通道（写安全模态）。
     *
     * <p>与 prewarmPreviewTempFile 里 PrewarmAwait 的 dispatcher 是同一份实现：迟到产物换装也要
     * 写文档（`WriteCommandAction { d.setText }`），故必须同款 `defaultModalityState()`——
     * `any()` 恒 write-unsafe（T-FixN1 的 KDoc 有平台侧逐帧依据）。</p>
     */
    public final Consumer<Runnable> prewarmDispatcher = runnable ->
            ApplicationManager.getApplication().invokeLater(
                    runnable,
                    ModalityState.defaultModalityState());
    /** T-M36.6-03：面板释放标志——预热线程晚于 dispose 完成时丢弃产物并删除文件。
     *  T-StabilityTail 起语义扩为「放弃预热」：面板 dispose 与**项目开始销毁**（见
     *  {@link #prewarmLifecycleGate}）都置它，预热线程在下一个检查点放弃（不再取服务）。 */
    public volatile boolean previewDisposed = false;

    /**
     * T-StabilityTail（窗口内异步泄漏治理）：预热任务的「项目开始销毁」取消门。
     *
     * <p>注册进 `ProjectEx.getEarlyDisposable()` 的 Disposable 树（见 bindPrewarmToProjectLifecycle），
     * 项目开始销毁时由平台回收并置 {@link #previewDisposed}，使构造期/`clear()` 派发的池线程在窗口内
     * 不再取服务。面板 dispose 时解除登记（不留指向本面板的项目树引用）；null = 未挂或已解除。</p>
     */
    public Disposable prewarmLifecycleGate = null;
    /** T-PreviewExecDs-01c：已 setMapping 方言的预览 vf（同对象幂等短路，避免每次预览重设方言触发 push all dumb）。
     *  T-FixN2 起该 vf 是**预览目录**（不再是单个文件）——见 mapPreviewDialectBeforePsi。 */
    public volatile VirtualFile previewDialectMappedVf = null;
    /** F-46：当前代码位置（文件名:行号），来源列默认值；null 表示未知（日志/历史等无锚点场景）。 */
    private String sourceLocation = null;
    public final JBPanel<JBPanel<?>> editorHolder = new JBPanel<JBPanel<?>>(new GridLayout(1, 1)) {{
        // M36.5.25e：GridLayout(1,1) 强制唯一子组件（内嵌控制台）fill——BorderLayout CENTER
        // 受子组件 preferredSize 影响导致 editor 不 fill（实际高度≈preferred ~375，15 行 SQL
        // 被截断）；GridLayout 按可用空间均分强制填满，editor 高度自适应窗口大小，无需固定
        // minimumSize 兜底。首次塌缩兜底由 editorComponentRaw 的 minimumSize 承担。
        setOpaque(false);
    }};

    // M36.5：SQL 执行状态面板（主面板 SOUTH——仅在有状态/错误内容时可见，避免空白占位）
    public final SqlResultPanel resultPanel = new SqlResultPanel() {{
        setVisible(false);
    }};

    /**
     * T-UxOnboard T-UX-02：预览编辑器空态引导（原生 placeholder，显隐由编辑器随空文档管理）。
     * 常态下预览内容即编辑器全文、placeholder 不可见；用户清空文档后原生提示接管。
     */
    public final String previewEmptyHint = MpSqlVisualizerBundle.message("panel.preview.emptyHint");

    /** M36：已内嵌的原生控制台 UI 组件（清空/释放时级联 dispose）。 */
    public JComponent embeddedConsole = null;

    /**
     * R33：结构化 EXPLAIN 回调代际计数——每次发起新的 EXPLAIN 结构化提交/新预览/清空都递增，
     * 在途 pooled 回调（JDBC 可能慢于用户操作）按提交时捕获的代际判过期，过期即丢弃：
     * 慢计划绝不覆盖用户已经看到的新预览/新提交（与 DataSourceSelector 的 swapGeneration 同思路）。
     */
    public int explainStructuredGeneration = 0;

    /** O-145 W7：结果到达时冻结的渲染模式；提交时不得重读设置冒充当初的渲染事实。 */
    public cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.RenderMode executionDialectRenderMode =
            cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.RenderMode.UNKNOWN;

    /** 预览来源模块规范键（方言决策/闸门的模块作用域信号；null = 无来源模块）。 */
    public String sourceModuleKey = null;

    /** R48 自动填充预演载体（FieldFill 面板与预览结果回填链路）。 */
    public cn.song.mybatisplus.visualizer.model.PreviewResult fieldFillPreview = null;

    /** T-PreviewSideQuery P1：预估影响行数的内联结果行（不进入控制台/结果区）。 */
    public JBLabel impactResultLabel = null;

    /**
     * UI-044 测试缝：复制通知「动作文案」采集器（null/缺席 = 不采集）。
     * 单测模式不弹真通知，若只断言点击行为就看不到「通知挂了哪些动作」——置非 null 后
     * {@code SqlPreviewPanelSqlViewKt.copySql} 会把将要挂上的动作文案记进来，供用例断言
     * 「复制并关闭」不带关闭链接。（Kotlin 侧经 WeakHashMap 扩展属性承载——Java 直接字段即可。）
     */
    public java.util.List<String> copyNotificationActionsForTest = null;

    /** 当前方言决策（meta 行方言段重组的事实源）。 */
    public cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision currentDialectDecision = null;

    /** 结果实际生成方言名（预热/预览目录方言映射同步用）。 */
    public String previewDesiredDialectName = null;

    /**
     * M36.5.39-A2：持久包装容器——console 组件统一嵌入此容器、容器整体挂入 editorHolder。
     * DBN results refreshed 期间会临时拿走 console 组件，旧的直挂方式（editorHolder.add(console)）
     * 会把 editorHolder 的显示链一并波及（holderShowing=false → 右侧空白）；隔离后 DBN 只动
     * consoleHost 子层，editorHolder/ThemedSplitPane 显示链稳定。
     */
    public final JBPanel<JBPanel<?>> consoleHost = new JBPanel<JBPanel<?>>(new BorderLayout()) {{
        setOpaque(false);
    }};

    /** M36.5.39-A1：最近一次嵌入 console 的身份 id（identityHashCode）——sameAsPrev 探针判定用。 */
    public Integer lastConsoleEmbeddedId = null;

    /** M36.5.44：上次 re-host 时刻（去抖用）——DBN 结果刷新期高频 reparent，需最小间隔抑制死循环。 */
    public long lastRehostAt = 0L;

    /** T-RefreshDbSteal：当前挂接的 rehost HierarchyListener 引用——detach/dispose 时显式移除。
     *  旧实现「监听随组件废弃，无需显式解绑」的假设在共享控制台场景不成立：组件被新面板复用后
     *  旧面板的监听仍存活，parent==null 时会把共享控制台抢回已废弃面板（真机日志 6 连抢实锤）。 */
    public HierarchyListener rehostListener = null;

    /** T-RefreshDbSteal：去抖窗口内被跳过的释放事件 → 已调度延迟复查（单次标志，防重复调度）。 */
    public boolean rehostRetryScheduled = false;

    // M36.4：原生编辑器文档引用（参数回填/格式化时使用）
    /** 原生编辑器的文档引用（内嵌后参数变更同步用）。 */
    public Document nativeEditorDoc = null;

    /**
     * T-AutoFold F5：原生控制台编辑器引用（onNativeEditorReady 时登记）——
     * showSql nativeDoc 支链的程序性折叠应用目标；detachConsole/clear/dispose 时清空，
     * 防止 EditorFactory 泄漏告警。recoverConsole 失联路径经 embedConsole 换新组件后
     * 由下一次 onNativeEditorReady 重新登记。
     */
    public Editor nativeConsoleEditor = null;

    public PreviewResult current = null;
    public boolean compactMode = false;

    /**
     * T-PreviewDisplayMode：编辑器显示模式。
     *
     * <p>编辑器文本是「模型派生视图」——参数输入/条件勾选/foreach 次数/压缩切换等任何变更都会
     * renderSql 全量重建并整篇覆盖文档，因此<b>一次性文档变换必被下一次重建抹掉</b>。模式改存状态、
     * 由 applyDisplayMode 在渲染管线中统一套用，重建后形态保持。</p>
     */
    public PreviewDisplayMode displayMode = PreviewDisplayMode.NORMAL;

    /** T-PreviewDisplayMode：预览编辑器显示模式（见 {@link #displayMode} 的 KDoc）。 */
    public enum PreviewDisplayMode { NORMAL, FORMAT, KEYWORD_WRAP, VERTICAL }

    // O-4：上一次预览的 SQL（会话缓存，供 Compare 并排对比用）
    public String previousSql = null;

    // F-1：JOIN 关系图（仅 SELECT 含 JOIN 时显示标签页）
    public final DiagramPanel diagramPanel;
    // T-Dgm-04：标签页宿主——原生图框架组件（joinDiagramNative=true 且 Diagrams 插件可用）与
    // 自绘 DiagramPanel 共用此容器按需切换；切换时旧原生编辑器即时 dispose 防 FileEditor 泄漏
    public final javax.swing.JPanel diagramTabHost = new javax.swing.JPanel(new java.awt.BorderLayout());
    /**
     * T-F8：JOIN 图标签页的加载面——包住整个 {@link #diagramTabHost}，转圈盖在标签页内容之上。
     *
     * <p>宿主层对原生/自绘两条渲染路径都可见，是唯一能覆盖真机默认路径的加载面。转圈层由平台
     * {@link JBLoadingPanel} 提供（独立 loadingLayer 盖在内容层之上，不替换内容，2 参构造
     * startDelayMs=-1 使 startLoading/stopLoading 同步改可见性）。Disposable 挂本面板。</p>
     */
    public final JBLoadingPanel diagramLoadingHost = new JBLoadingPanel(new BorderLayout(), this);
    public JComponent currentNativeDiagram = null;
    /**
     * T-DbSchemaCompletion：待补刷富化的图（首次富化一点数据源信息都没拿到时记下）。
     *
     * <p>仅存"上一次富化空手而归"的那个 {@link PreviewResult}——数据源建连（原生控制台就绪）后据此补刷一次，
     * 刷完置空；新预览替换 current 时旧值自然失配，无需额外清理。</p>
     */
    public PreviewResult diagramEnrichRetryTarget = null;
    // F-45：maintainPage 列信息（仅 <pages> 预览非 null 时显示标签页）
    private JComponent columnInfoPanel = null;
    /** 内容区卡片：null=无内容 / "sql"=SQL编辑 / "sql+diagram"=SQL编辑 + JOIN图标签页。 */
    private JBPanel<JBPanel<?>> contentPane = null;
    public JTabbedPane contentTabPane = null;

    // M12：动态条件启用集合（勾选状态，元素为 ConditionToggle.id）+ 参数值（按 source 绑定，取代位置绑定）
    public final Set<String> enabledIds = new LinkedHashSet<>();
    public final Map<String, String> paramValues = new LinkedHashMap<>();
    // T-M24-04：XML 预览渲染状态（条件开关复用 enabledIds，id = test 表达式原文；
    // T-M26.4：bind 覆盖状态已移除——bind 值由「参数」区输入统一承担）
    public final Map<String, Integer> xmlForEachCounts = new LinkedHashMap<>();
    // O-78 T-ForeachItemRender：foreach 集合值权威状态（collectionExpr → 逗号分隔解析后的值序）——
    // 播种收进 renderSql 帧内按新鲜映射清单进行，值不再依赖「渲染后同步读键清单」
    public final Map<String, List<String>> xmlForEachValues = new LinkedHashMap<>();
    public final Set<String> xmlForEachNulls = new LinkedHashSet<>();
    // R22 T-02/T-03：勾选态与 foreach 行的组件登记（快照枚举 + 召回视觉同步；随 rebuild 清场）
    public final List<JCheckBox> xmlForEachNullBoxes = new ArrayList<>();
    public final List<Pair<String, JBTextField>> xmlForEachValueFields = new ArrayList<>();
    public List<String> xmlErrors = List.of();
    /** T-M25.4：最近一次 XML 渲染的参数映射（集合值输入按当前渲染键回填）。 */
    public List<cn.song.mybatisplus.visualizer.xml.ParameterMapping> xmlMappings = List.of();
    // T-M25.3：错误信息复制按钮（warning 存在时可见，一键复制完整错误文本）
    public JButton errorCopyButton = null;
    public String currentWarning = null;

    /**
     * T-PSG-04（N-33）：最近一次参数回填的「`?` 位点数 vs 参数条数」对账标注——不等时非 null。
     *
     * <p>只在 backfillParamsBySource 里按串感知位点数与 sources 条数计算（每次回填都重算，不留
     * 陈旧值）；执行前由 warnOnPlaceholderMismatch 读走并如实登记日志。<b>不阻断</b>任何流程。</p>
     */
    public String placeholderReconciliation = null;

    // T-PreviewUx-Pack #2：诚实降级横幅——渲染警示（RENDER_WARNING）与 T-PreviewExecDs
    // 降级链层级提示（DEGRADED）统一收口；降级提示优先级高于渲染警示（见 DegradationBanner.Kind）
    public final DegradationBanner degradationBanner = createDegradationBanner();

    /**
     * T-DmlImpactProminence（用户真机反馈：左下角灰字不够明显）：影响行数试算结果独立横幅行——
     * 与降级/渲染警示**并存**（各自占一行，不抢同一显示位），蓝色加粗；失败红色。
     */
    public final JBPanel<JBPanel<?>> dmlImpactBanner = createDmlImpactBanner();
    public final JBLabel dmlImpactBannerLabel = createDmlImpactBannerLabel();

    private static JBPanel<JBPanel<?>> createDmlImpactBanner() {
        JBPanel<JBPanel<?>> banner = new JBPanel<>(new BorderLayout());
        banner.setOpaque(false);
        banner.setVisible(false);
        return banner;
    }

    private static JBLabel createDmlImpactBannerLabel() {
        JBLabel label = new JBLabel();
        label.putClientProperty("html.disable", true);
        label.setBorder(JBUI.Borders.empty(4, 8, 4, 8));
        label.setForeground(JBColor.namedColor("Link.activeForeground", JBColor.BLUE));
        return label;
    }

    /** 组装横幅（T-DollarInjectMark ✕ 回调：关闭 → 会话级记忆 + 清除本类别）。 */
    private static DegradationBanner createDegradationBanner() {
        DegradationBanner banner = new DegradationBanner();
        // T-DollarInjectMark：✕ 关闭 → 会话级记忆（本 IDE 会话不再弹；测试经 resetDollarMarkForTest 复位）
        banner.onDismiss = () -> {
            dollarMarkDismissedSession = true;
            banner.clear(DegradationBanner.Kind.DOLLAR_INJECT);
        };
        return banner;
    }

    /** T-DollarInjectMark：最近一次 XML 渲染的 `${}` 直替计数（非 XML 路径恒 0）。 */
    public int xmlDollarSubstitutions = 0;

    /**
     * T-InjectionGate T-IG-02：本面板实例内已确认过 `${}` raw 段执行（会话级记忆）。
     *
     * <p>同一面板确认一次后不再重复弹（常用 `${orderBy}` 场景的确认疲劳缓解）；记忆随面板实例
     * 存活——面板 dispose 即失效。volatile：执行入口可能在非 EDT 触发（弹窗按钮回调），读写可见性保守处理。</p>
     */
    public volatile boolean dollarRawConfirmedInSession = false;

    /**
     * T-PC2-04（D1=B 定案）：本面板实例内已确认过含 UNKNOWN 参数执行（会话级记忆）。
     *
     * <p>语义与 {@link #dollarRawConfirmedInSession} 同款；执行与 EXPLAIN 共用同一记忆，两条入口是同一道门。</p>
     */
    public volatile boolean unknownParamsConfirmedInSession = false;

    // P-05：参数历史缓存 + debounce 写回
    public final ParamHistoryCache paramHistoryCache = new ParamHistoryCache();
    /** P-05：debounce 写回调度器（Swing Timer，500ms 延迟，restart 实现防抖）。 */
    public final Timer historyAlarm =
            new Timer(500, e -> SqlPreviewPanelParamPanelKt.flushHistoryToCache(this));

    // R22 T-04：命名方案下拉（rebuildLeftPanel 随参数区重建；syncSchemeCombo 填充/刷新）
    public JComboBox<String> schemeCombo = null;
    // O-95 S3：绑定数据源指示行标签（rebuildLeftPanel 重建；renderSql 尾部单点刷新）
    public JBLabel bindStateLabel = null;
    /** 程序化填 combo 模型时置位（抑制 addActionListener 的用户切换语义）。 */
    public boolean schemeSyncingCombo = false;
    /** 上次已同步到下拉的激活方案名——对话框回调时比对判定「激活方案被对话框切走」。 */
    public String lastShownActiveScheme = ParamHistoryCache.DEFAULT_SCHEME;
    /** 管理方案对话框（MODELESS，单实例防堆叠；关闭后由可见性判断重建）。 */
    public ParamSchemeManagerDialog schemeManagerDialog = null;

    // O-88：预设行折叠件（平台标准 HideableTitledPanel，Run Config「Before launch」区同款）。
    // 折叠态=companion 静态字段（见 {@link #presetSectionCollapsed}）：跨预览重建且跨面板实例保留。
    /**
     * 当前预设折叠件实例——{@link HideableTitledPanel} 公共面无状态变更监听器（装饰器私有），
     * 用户点标题折叠的变化以「重建时读 {@link HideableTitledPanel#isExpanded} 回写」的懒惰捕获跟进；
     * 折叠件随 paramPanel.removeAll 摘除后实例仍持有最后视觉态，跨 clear/rebuild 均可恢复。
     */
    public HideableTitledPanel presetFold = null;

    // T-Perf-04：参数击键路径 renderSql 防抖（Swing Timer，150ms，模式同 historyAlarm）
    /** 待执行的渲染任务（最后一次调度者生效——防抖语义）。 */
    private Runnable pendingRender = null;
    private final Timer renderDebounceTimer =
            new Timer(RENDER_DEBOUNCE_MS, e -> flushDebouncedRender());

    {
        historyAlarm.setRepeats(false);
        renderDebounceTimer.setRepeats(false);
    }

    /**
     * 调度防抖渲染：keystroke 路径用——150ms 停顿后只渲染一次，最终结果与逐键全量渲染一致。
     *
     * <p>单元测试模式（isUnitTestMode，headless 无 EDT 泵）同步执行：既有测试依赖「改参数值 →
     * 文档即时更新」的同步语义，且挂起的 Timer 在后续测试泵 EDT 时迟到触发会触碰已销毁面板
     * 并触发 TransactionGuard "Write-unsafe context" 日志断言（交叉污染）。生产行为不变。</p>
     */
    public void scheduleRender(Runnable task) {
        pendingRender = task;
        com.intellij.openapi.application.Application app =
                com.intellij.openapi.application.ApplicationManager.getApplication();
        if (app != null && app.isUnitTestMode()) {
            flushDebouncedRender();
            return;
        }
        renderDebounceTimer.restart();
    }

    /** Timer 动作：执行最后调度的渲染任务（EDT）。 */
    private void flushDebouncedRender() {
        Runnable task = pendingRender;
        pendingRender = null;
        if (task != null) task.run();
    }

    /** T-M36.6-03 / T-PreviewFlash：后台预热预览临时文件（占位空内容，createEditor 就绪后按实际 SQL 同步）。 */
    public JButton modeButton = null;

    public SqlPreviewPanel() {
        this(null);
    }

    public SqlPreviewPanel(@Nullable Project project) {
        super(new BorderLayout());
        this.project = project;
        this.diagramPanel = new DiagramPanel(project);
        this.stackHover = new StackFrameHover(project);

        // 顶部元信息行 + 工具栏
        // T-PreviewTopOverlap F1：metaLabel 从 WEST 迁 CENTER——BorderLayout 的 WEST/EAST 各按
        // 理想宽整块贴边绝不收缩，两偏好宽之和超容器宽时直接互相重叠（用户真机截图实锤：元信息
        // 文字压进按钮行）；CENTER 只拿剩余宽度，与 EAST 结构性零重叠。宽度不足由 F3 截断兜底。
        // （合并曾取 dev T-GifHeader 两行布局为基底，用户定案「更多」折叠菜单模式——单行方案转正。）
        metaLabel.setBorder(JBUI.Borders.empty(0, 8, 4, 8));
        topRow = new JBPanel<JBPanel<?>>(new BorderLayout());
        topRow.add(metaLabel, BorderLayout.CENTER);
        toolbarPanel = SqlPreviewPanelResultViewKt.toolbar(this);
        topRow.add(toolbarPanel, BorderLayout.EAST);
        topRow.setBorder(JBUI.Borders.empty(4, 8, 2, 8));
        // T-PreviewUx-Pack #2：诚实降级横幅（面板内嵌非模态提示，不算弹窗）——渲染警示 /
        // T-PreviewExecDs 降级链层级提示统一收口（触发即显示、恢复即隐藏，不占高度）
        // T-DevAcceptFix ①（20261006 验收）：绑定指示行迁出参数区——挂「更多」按钮（工具栏 EAST
        // 末位）正下方右对齐常驻薄行，与右侧内嵌控制台头部数据源显示相邻；init 一次构建常驻，
        // 不随参数区重建（常驻语义不再依赖参数区是否建行），refreshBindStateRow 单点刷新链不变。
        JBPanel<JBPanel<?>> northHost = new JBPanel<JBPanel<?>>(new BorderLayout());
        northHost.setOpaque(false);
        northHost.add(topRow, BorderLayout.NORTH);
        northHost.add(SqlPreviewPanelParamPanelKt.buildBindStateRow(this), BorderLayout.CENTER);
        // T-DmlImpactProminence：降级横幅与影响行数结果横幅上下各占一行（并存不抢位）
        dmlImpactBanner.add(dmlImpactBannerLabel, BorderLayout.CENTER);
        JBPanel<JBPanel<?>> bannerStack = new JBPanel<JBPanel<?>>(new BorderLayout());
        bannerStack.setOpaque(false);
        bannerStack.add(degradationBanner, BorderLayout.NORTH);
        bannerStack.add(dmlImpactBanner, BorderLayout.CENTER);
        northHost.add(bannerStack, BorderLayout.SOUTH);
        add(northHost, BorderLayout.NORTH);
        // T-PreviewTopOverlap F3：宽度预算截断跟随尺寸变化（首显 + 拉伸/缩窄；宽度未变短路）
        topRow.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                SqlPreviewPanelResultViewKt.applyMetaTruncation(SqlPreviewPanel.this);
            }

            @Override
            public void componentShown(ComponentEvent e) {
                SqlPreviewPanelResultViewKt.applyMetaTruncation(SqlPreviewPanel.this);
            }
        });

        // T-StackHoverUi：调用栈悬停弹层（调试器 Frames 风格）——meta 行悬停承接完整调用链，
        // 弹层生命周期随面板 Disposer 树级联释放（弹窗关闭即清）
        Disposer.register(this, stackHover);
        stackHover.attach(metaLabel);

        // 中部左右分栏：参数输入 | SQL 编辑器/原生控制台
        // T-M26.3：按钮回挂分区标题行右侧（conditionSectionHeader/batchSectionHeader），
        // 左面板即参数滚动区（T-M26.2 的固定操作行已拆除——长文本按视口截断后按钮不再被推出视口）
        OnePixelSplitter split = new OnePixelSplitter(false, "mpvs.preview.splitter", 0.3f);
        split.setFirstComponent(paramScroll);
        split.setSecondComponent(editorHolder);
        // T-PreviewBgFit（问题一）：恢复 T-UiNative P0a 换组件时丢掉的容器背景——
        // 原 ThemedSplitPane 显式设过 `background = UIUtil.getPanelBackground()`，OnePixelSplitter
        // 版本漏了它，左侧透明参数区于是透出分栏自身背景（深色主题近黑）。
        // T-DarkTheme：命名色按主题解析，浅/深主题各自正确（主题切换由 LafManagerListener 触发重绘）。
        split.setBackground(UIUtil.getPanelBackground());
        // M36.5：删除 verticalSplit 下半区——控制台已内嵌右侧，底部仅保留状态行
        //（resultPanel SOUTH，无内容时隐藏不占高度，错误/执行状态正常显示）
        add(split, BorderLayout.CENTER);
        add(resultPanel, BorderLayout.SOUTH);

        // T-M26.6：注册格式化快捷键——组件级 custom shortcut 命中即消费按键，
        // 不与 IDE 全局 Reformat Code 双触发（预览语言无 formatter，全局动作本不可用）
        reformatAction.registerCustomShortcutSet(
                new CustomShortcutSet(SqlPreviewPanelSqlViewKt.reformatShortcuts(this)),
                this);

        // T-DarkTheme：主题切换时重绘（工具窗口模式下实时响应；命名色自动更新，仅需触发重绘）
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(LafManagerListener.TOPIC, e -> SqlPreviewPanelEditorLifecycleKt.lookAndFeelChanged(this));

        // F-1：注册图集面板到 Disposer 树（级联释放后台渲染任务）
        Disposer.register(this, diagramPanel);
        // T-Dgm-04：标签页实际展示 diagramTabHost（内嵌原生组件或 diagramPanel）
        diagramTabHost.setOpaque(false);
        diagramTabHost.add(diagramPanel, java.awt.BorderLayout.CENTER);
        // T-F8：加载面在宿主层（标签页内容 = 加载面板 → diagramTabHost），并把它交给图面板出图
        //（面板自身的 contentLayer 在原生路径下不可见，见 diagramLoadingHost KDoc）
        diagramLoadingHost.add(diagramTabHost, BorderLayout.CENTER);
        diagramPanel.bindHostLoadingPanel(diagramLoadingHost);

        // T-M36.6-03：预热线程提前创建临时 SQL 文件 + VFS refresh + PSI 解析（后台线程慢操作合法），
        // createEditor（EDT）优先消费预热产物——243 平台 EDT/EDT-ReadAction 均禁止慢操作。
        if (project != null) {
            SqlPreviewPanelEditorLifecycleKt.prewarmPreviewTempFile(this);
        }
    }

    /**
     * T-M53-II：{@code modeButton} 的就绪态读取口。
     *
     * <p>组合式重构把显示模式链搬进同包扩展文件后，{@code ::modeButton.isInitialized} 这类<b>后备字段</b>引用
     * 在声明类外不可用（编译器红：backing field not accessible）。把判定留在类内，扩展侧按
     * {@code modeButtonOrNull()?.text = …} 调用——语义与原先逐字相同（同一 isInitialized 判定 + 同一赋值）。</p>
     */
    public JButton modeButtonOrNull() {
        return modeButton != null ? modeButton : null;
    }

    /**
     * T-Dgm-04：JOIN 图渲染测试缝——非 null 时 showJoinDiagram 的换图动作改走它（注入会抛的 render）。
     * 只在测试里赋值；生产恒 null。
     */
    public Consumer<DiagramModel> diagramRenderForTest = null;

    // T-PreviewFast P6：显示模式变换单槽缓存键/值（见 applyDisplayMode）。
    public String formatCacheKey = null;
    public String formatCacheValue = null;

    /** T-M23.6：提示「关闭 SQL 面板」按钮回调（由预览弹窗 SqlPreviewDialog 注入 dispose()）。 */
    public Runnable onClosePanelRequested = null;

    /** O-95 S3：绑定数据源名提供者（测试缝优先；生产走 DataSourceSelector）。 */
    public Supplier<String> bindStateNameProvider = null;

    /** T-M26.3：复选框字体（与渲染一致，截断度量用——UIUtil.getLabelFont 与复选框字体在 headless 下可能不一致）。 */
    public final Font checkBoxFont = new JCheckBox().getFont();

    @Override
    public void removeNotify() {
        super.removeNotify();
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] panel removeNotify: embedded=" + System.identityHashCode(embeddedConsole));
        SqlPreviewPanelEditorLifecycleKt.releaseEditor(this);
    }

    /**
     * T-DarkTheme：释放主题监听（messageBus.connect(this) 随 Disposable 销毁自动取消订阅）。
     * 工具窗口模式下面板可能长期存活，由 SqlPreviewDialog 或工具窗口工厂调用释放。
     */
    @Override
    public void dispose() {
        // O-88 复验修复：dispose 链捕获折叠态——懒惰回写（buildSchemeRow 开头）只在同实例触发参数区
        // 重建时执行，真机「收起→取消→换语句」走 DialogWrapper.dispose→Disposer 级联释放面板，
        // 全程无 buildSchemeRow 调用，静态字段从未写 true。面板自身是 Disposable 且经
        // Disposer.register(宿主 disposable, this) 挂树（弹窗见 SqlPreviewDialog，工具窗同惯例），
        // 宿主任何关闭路径（取消/Alt+F4/父级释放）级联触发本方法——在此读当前折叠件视觉态回写
        // 静态字段（真值语义=取反，与懒惰捕获行一致）。懒惰捕获行保留，覆盖刷新等重建路径。
        if (presetFold != null) presetSectionCollapsed = !presetFold.isExpanded();
        // T-RefreshDbSteal：面板废弃——移除 rehost 监听（共享控制台可能已被新面板接管，
        // 本面板的监听不得残活抢组件）；embedConsole 对 null 安全
        try {
            SqlPreviewPanelExecutionKt.detachConsole(this);
        } catch (Throwable ignored) {
        }
        // T-AutoFold F5：面板废弃——控制台编辑器引用一并清空（防 EditorFactory 泄漏告警）
        // T-PreviewLineCommentDash O-134：面板销毁减计数（detachConsole 已覆盖常规路径，此处兜底直清分支）
        if (nativeConsoleEditor != null) EditorCommentShortcuts.uninstall(nativeConsoleEditor);
        nativeConsoleEditor = null;
        // T-M36.6-03：面板销毁——晚到的预热线程产物作废（预热线程自查后删除临时文件）
        previewDisposed = true;
        // T-PerfSweep6 T-PS6-04③（性能报告发现 4）：面板 dispose 补一次同款临时文件回收。
        // 此前回收实际只由 removeNotify → releaseEditor 触发，而三种宿主（弹窗 / 工具窗 / 嵌入控制台）
        // 下 dispose 与 removeNotify 的先后未穷尽——dispose 先到时残留文件无人回收。本方法幂等，
        // 两条路径都走到也无副作用（集合已清空即零动作）。
        SqlPreviewPanelEditorLifecycleKt.recoverPreviewTempFiles(this);
        // T-StabilityTail：解除预热取消门在项目 earlyDisposable 树上的登记——面板先于项目销毁时
        // 不留指向本面板的引用（项目先销毁时该 gate 已被平台回收，Disposer 对已销毁件幂等）
        if (prewarmLifecycleGate != null) {
            try {
                Disposer.dispose(prewarmLifecycleGate);
            } catch (Throwable ignored) {
            }
        }
        prewarmLifecycleGate = null;
        // T-Perf-04：防抖/历史 Timer 随面板停止（防晚到 flush 触碰已销毁面板状态）
        renderDebounceTimer.stop();
        pendingRender = null;
        historyAlarm.stop();
        // T-Perf-16：释放 DataSourceSelector 单例滞留引用（弹窗关闭此前不走 clear 按钮路径——
        // prepared/shared/pendingOnConsole 强引用已关闭面板与原生控制台，堆转储滞留整棵面板树）
        SqlExecutor.releaseForPanel();
        // T-PS4-14④：面板 dispose 是 JOIN 虚拟外键注册表的最后语义回收点（T-EmbedFkOrder
        // 「显式语义点回收」三点口径：登记前 registerJoinForeignKeys 自带 clear + 无 JOIN 路径
        // unloadJoinDiagram + 面板 dispose）——最后一张 JOIN 图建图的登记此前驻留到下次建图，
        // 残留期 JoinVirtualFkProvider EP 会给用户自开的 Database 图补出旧连线。runCatching
        // 照邻居清理件风格（释放失败不阻断 dispose 链）
        try {
            cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport.clearRegisteredFk();
        } catch (Throwable ignored) {
        }
        // messageBus 连接经 connect(this) 绑定本 Disposable，Disposer.dispose 时自动取消订阅
        Disposer.dispose(this);
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用；master 侧 internal → public，KOTLIN_TO_JAVA_RULES §0.1）
    // ------------------------------------------------------------------

    public Editor editorForTest() {
        return editor;
    }

    /** T-PSG-04 测试缝：最近一次回填的「位点数 vs 参数条数」对账标注（相等为 null）。 */
    public String placeholderReconciliationForTest() {
        return placeholderReconciliation;
    }

    /** T-PreviewConsoleFmt 测试缝：模拟控制台文档已就绪（nativeDoc 指向该文档）。 */
    public void markNativeDocForTest(Document doc) {
        nativeEditorDoc = doc;
    }

    /** T-PreviewConsoleFmt 测试缝：挂载后回填渲染的等价入口。 */
    public void renderSqlForTest() {
        SqlPreviewPanelSqlViewKt.renderSql(this);
    }

    /** T-AutoFold F5：登记的原生控制台编辑器引用（支链①接入断言用）。 */
    public Editor nativeConsoleEditorForTest() {
        return nativeConsoleEditor;
    }

    public PsiFile psiFileForTest() {
        return psiFile;
    }

    /**
     * T-PreviewDocLock2 F3 回归守护：预热载体（异步完成态）——测试据此有界等待预热出结果
     * （headless 无 EDT 泵，预热跑在池线程），再驱动真实消费链断言分支走向。
     */
    public PrewarmAwait<Pair<VirtualFile, PsiFile>> prewarmAwaitForTest() {
        return prewarmAwait;
    }

    /**
     * T-PreviewDocLock2 F3 回归守护：预热产物字段（publishPrewarmProduct 写入、消费即置 null）。
     * 断言点：①预热完成后字段与 await 交付值<b>同实例</b>（发布侧不变式）；②消费后字段被清空（一次性语义）。
     */
    public Pair<VirtualFile, PsiFile> prewarmedTempFileForTest() {
        return prewarmedTempFile;
    }

    /** T-PreviewDocLock2 F3 回归守护：释放编辑器（<b>不</b>触发 clear 的重预热）——「产物已被消费」降级分支断言用。 */
    public void releaseEditorForTest() {
        SqlPreviewPanelEditorLifecycleKt.releaseEditor(this);
    }

    /**
     * T-PerfSweep6 T-PS6-04 测试缝：对给定目录执行一次目录级兜底清扫（生产入口在
     * projectPreviewFile 首次解析预热目录时）。测试用临时目录即可直测「按年龄/条数回收」
     * 与「非本插件命名零删除」两条判据。
     */
    public void sweepStalePreviewFilesForTest(java.io.File dir) {
        SqlPreviewPanelEditorLifecycleKt.sweepStalePreviewFiles(this, dir);
    }

    /** T-PrewarmLateRecover F1：当前是否处于「降级文档」态（换装判据里的身份字段）。 */
    @Nullable
    public Document degradedDocumentForTest() {
        return degradedPreviewDocument;
    }

    /**
     * T-ErrorLocateUsability T-ELU-01（O-49）：错误定位动作的<b>显隐判据</b>缝——
     * 锚点可解析 → 非 null（按钮出现）；不可解析 → null（按钮不出现）。
     * 这是本任务修复点的直接可断言面（改前恒非 null）。
     */
    @Nullable
    public Runnable errorLocateActionForTest() {
        return SqlPreviewPanelExecutionKt.errorLocateAction(this);
    }

    /** T-ELU-01：富错误回调缝（端到端断言「锚点不可解析 → 定位按钮不出现」）。 */
    public void showErrorRichWithLocateForTest(cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo info) {
        SqlPreviewPanelExecutionKt.showErrorRichWithLocate(this, info);
    }

    /** T-PrewarmLateRecover F1：插件最后一次写入编辑器文档的文本（换装判据里的「用户没动过」）。 */
    @Nullable
    public String lastRenderedSqlForTest() {
        return lastRenderedSql;
    }

    /**
     * T-PrewarmLateRecover F1：驱动迟到产物通道——与「预热线程发布时发现消费侧已放弃
     * （`PrewarmAwait.complete` 返回 false）」走同一条代码路径（tryUpgradeToLateProduct）。
     */
    public void onLatePrewarmProductForTest(Pair<VirtualFile, PsiFile> product) {
        SqlPreviewPanelEditorLifecycleKt.tryUpgradeToLateProduct(this, product);
    }

    /** T-PrewarmLateRecover F2：横幅「重试」动作入口（等价于点击横幅上的动作）。 */
    public void retryPrewarmForTest() {
        SqlPreviewPanelEditorLifecycleKt.retryPrewarm(this);
    }

    public PreviewResult currentForTest() {
        return current;
    }

    public boolean compactForTest() {
        return compactMode;
    }

    /** T-PreviewDisplayMode：当前显示模式（参数变更后保持性断言用）。 */
    public PreviewDisplayMode displayModeForTest() {
        return displayMode;
    }

    /** M36.5.2：内嵌控制台组件（同实例防抖/替换断言用）。 */
    public void embedConsoleForTest(JComponent component) {
        SqlPreviewPanelExecutionKt.embedConsole(this, component);
    }

    public JComponent embeddedConsoleForTest() {
        return embeddedConsole;
    }

    /** M36.5.39-A2：持久包装容器 + 显示链宿主（ConsoleHostTest 断言用）。 */
    public JComponent consoleHostForTest() {
        return consoleHost;
    }

    public JComponent editorHolderForTest() {
        return editorHolder;
    }

    /** T-UxOnboard T-UX-02：预览编辑器空态提示文案（placeholder 接线断言用）。 */
    public String previewEmptyHintForTest() {
        return previewEmptyHint;
    }

    /** M36.5.39-A1：最近一次嵌入 console 的身份 id（sameAsPrev 探针断言用）。 */
    public Integer lastConsoleEmbeddedIdForTest() {
        return lastConsoleEmbeddedId;
    }

    /** T-RefreshDbSteal F3：摘除控制台 + 移除 rehost 监听（生命周期断言用）。 */
    public void detachConsoleForTest() {
        SqlPreviewPanelExecutionKt.detachConsole(this);
    }

    /** T-RefreshDbSteal F4：所有权判定（卫队断言用，见 RefreshDbStealTest）。 */
    public boolean consoleAttachedToMeForTest() {
        return SqlPreviewPanelExecutionKt.consoleAttachedToMe(this);
    }

    /** T-RollbackSql：执行状态面板（executeAdhocSql 结果反馈断言用）。 */
    public SqlResultPanel resultPanelForTest() {
        return resultPanel;
    }

    /**
     * T-F8：JOIN 图加载态接线探针（测试用）——转发到 DiagramPanel 的 loadingProbeForTest。
     *
     * <p>接线用例据此断「进入加载态 → 渲染 → 退出加载态」的时序（时序为何必须如此见
     * updateDiagramTab 与 showJoinDiagram 的 KDoc）。</p>
     */
    public void diagramLoadingProbeForTest(Consumer<Boolean> probe) {
        diagramPanel.loadingProbeForTest = probe;
    }

    /** T-F8：JOIN 图面板（「停转圈时图已渲染」的断言入口）。 */
    public DiagramPanel diagramPanelForTest() {
        return diagramPanel;
    }

    /** T-F8：当前是否走原生图路径（「停转圈时图已换入」的另一分支——原生路径不经过 diagramPanel）。 */
    public boolean hasNativeDiagramForTest() {
        return currentNativeDiagram != null;
    }

    /**
     * T-TS-05：JOIN 图标签页的加载面（测试断言用）——标签页内容必须<b>是它</b>。
     *
     * <p>与 diagramPanelForTest 的 isLoadingForTest 互补：后者只断「加载态为真」，把标签页内容改回
     * diagramTabHost（加载面不在层级里）也照样为真；真机默认原生图路径下 diagramPanel 被
     * removeAll() 摘出层级，转圈根本画不出来。故需按对象身份断结构。</p>
     */
    public JBLoadingPanel diagramLoadingHostForTest() {
        return diagramLoadingHost;
    }

    /** T-TS-05：内容区标签页容器（测试断言用；未切到标签页模式时为 null）。 */
    public JTabbedPane contentTabPaneForTest() {
        return contentTabPane;
    }

    /** T-RefreshDbSteal F3：当前挂接的 rehost 监听是否仍在组件上（解绑断言用）。 */
    public boolean rehostListenerAttachedForTest() {
        JComponent console = embeddedConsole;
        if (console == null) return false;
        HierarchyListener listener = rehostListener;
        if (listener == null) return false;
        for (HierarchyListener l : console.getHierarchyListeners()) {
            if (l == listener) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // F-46：来源位置（来源列默认值）
    // ------------------------------------------------------------------

    /** 设置当前代码位置（文件名:行号），来源列默认显示此值。 */
    public void setSourceLocation(String loc) {
        sourceLocation = loc;
    }

    /** 当前代码位置（文件名:行号），null 表示未知。 */
    public String currentSourceLocation() {
        return sourceLocation;
    }

    // ------------------------------------------------------------------
    // O-4：预览弹窗 UX 增强（外部组件访问面板状态）
    // ------------------------------------------------------------------

    /** O-4：当前编辑器 SQL 文本（含手动编辑与参数回填）；编辑器未创建时返回 null。 */
    public String editorDocumentText() {
        return editor != null ? editor.getDocument().getText() : null;
    }

    /** O-4：当前预览的未解析参数来源列表（与 SQL 中 `?` 按出现顺序对应）。 */
    public List<String> unresolvedParams() {
        return current != null ? current.getUnresolvedParams() : Collections.emptyList();
    }

    /**
     * T-JpaParamOrder F3：复制可执行 SQL 的回填 sources——JPA 占位符名序非空时优先
     * （按名回填口径），否则回落 {@link #unresolvedParams()}，与面板渲染（renderSql else 分支）同口径。
     */
    public List<String> backfillSources() {
        PreviewResult cur = current;
        if (cur == null) return Collections.emptyList();
        return !cur.getPlaceholderParamNames().isEmpty()
                ? cur.getPlaceholderParamNames() : cur.getUnresolvedParams();
    }

    /** O-4：当前参数值快照（source → 用户输入值）。 */
    public Map<String, String> paramValuesSnapshot() {
        return new LinkedHashMap<>(paramValues);
    }

    /**
     * T-InjectionGate T-IG-01：当前预览语句是否含 `${}` 原文拼接段（PreviewResult.hasDollarRawSegments
     * 面板侧读口——复制可执行 SQL 的产物标记注释据此追加）。
     */
    public boolean hasDollarRawSegments() {
        return current != null && current.isHasDollarRawSegments();
    }

    /** F-8：当前预览的表名（可 null）。 */
    public String currentTableName() {
        return current != null ? current.getTableName() : null;
    }

    /** F-8：当前预览的方言名称（可 null）。 */
    public String currentDialect() {
        return current != null && current.getPreviewModel() != null
                ? current.getPreviewModel().getDialect().getName() : null;
    }

    public JBTextField paramFieldForTest(int index) {
        return index >= 0 && index < paramFields.size() ? paramFields.get(index) : null;
    }

    /** T-M24-04：元信息标签文本（警告断言用）。 */
    public String metaLabelForTest() {
        return metaLabel.getText();
    }

    /**
     * T-SourceJumpFunnel §3.3：元信息标签 tooltip（「悬停见完整链」断言用）。
     *
     * <p>headless 下无法产生真实鼠标悬停，但 Swing 悬停展示的正是本属性文本，故断言它即锁定
     * 悬停内容契约（真实悬停动作本身不在自动化范围）。</p>
     */
    public String metaTooltipForTest() {
        return metaLabel.getToolTipText();
    }

    /** T-StackHoverUi：当前喂给悬停弹层的栈快照（接线断言用——tooltip 不再承载栈文本）。 */
    public String stackDigestForTest() {
        return stackHover.currentDigestForTest();
    }

    /** T-M25.3：错误复制按钮（可见性断言用）。 */
    public JButton errorCopyButtonForTest() {
        return errorCopyButton;
    }

    // ------------------------------------------------------------------
    // T-PreviewTopOverlap F4：顶部行布局 / 折叠菜单测试访问
    // ------------------------------------------------------------------

    /** 顶部行（metaLabel + 工具栏宿主，布局断言用）。 */
    public JBPanel<JBPanel<?>> topRowForTest() {
        return topRow;
    }

    /** 工具栏（直显按钮集合断言用）。 */
    public JBPanel<JBPanel<?>> toolbarForTest() {
        return toolbarPanel;
    }

    /** 「更多」折叠菜单项文案（顺序与弹层一致，断言用；show 依赖真实屏幕故只暴露文案与 step）。 */
    public List<String> moreActionsForTest() {
        List<String> titles = new ArrayList<>();
        for (Pair<String, Runnable> action : SqlPreviewPanelResultViewKt.moreActions(this)) {
            titles.add(action.getFirst());
        }
        return titles;
    }

    /** 「更多」折叠菜单 step（onChosen 委托断言用，无需真实弹层）。 */
    public BaseListPopupStep<String> moreActionsStepForTest() {
        return SqlPreviewPanelResultViewKt.moreActionsStep(this, SqlPreviewPanelResultViewKt.moreActions(this));
    }

    /**
     * N4：构造 step 的注入口——真实三项动作都会弹模态（单测禁弹），故用无副作用的替身项断言
     * 「命中真实项时<b>不同步</b>执行动作」这条新契约。
     */
    public BaseListPopupStep<String> moreActionsStepWithForTest(List<Pair<String, Runnable>> items) {
        return SqlPreviewPanelResultViewKt.moreActionsStep(this, items);
    }

    /** F3：宽度预算截断手动触发（headless 无 componentResized，测试直调）。 */
    public void applyMetaTruncationForTest() {
        SqlPreviewPanelResultViewKt.applyMetaTruncation(this);
    }

    /**
     * T-M19.4-01：第 index 个普通参数行的标签（JBLabel）——布局断言用
     * （宽度自适应/tooltip 全名）。按 paramPanel 中「含 JBLabel 的 JBPanel 行」定位，
     * 与 paramFields 的顺序一致（普通参数行按 source 顺序追加）。
     * R22 T-04：方案行同为「含 JBLabel 的 JBPanel」且插在头部，按 {@link #SCHEME_ROW_NAME}
     * 排除——访问器契约是「普通参数行」，不含方案行（否则序号错位、断言取到方案行标签）。
     * O-95 S3：绑定指示行同形状（含 JBLabel 的 JBPanel），按 BIND_STATE_ROW_NAME 同口径排除。
     */
    public JBLabel paramRowLabelForTest(int index) {
        List<JBPanel<?>> labeled = new ArrayList<>();
        for (Component c : paramPanel.getComponents()) {
            if (c instanceof JBPanel) {
                JBPanel<?> row = (JBPanel<?>) c;
                if (SCHEME_ROW_NAME.equals(row.getName()) || BIND_STATE_ROW_NAME.equals(row.getName())) continue;
                for (Component cc : row.getComponents()) {
                    if (cc instanceof JBLabel) {
                        labeled.add(row);
                        break;
                    }
                }
            }
        }
        if (index < 0 || index >= labeled.size()) return null;
        JBPanel<?> row = labeled.get(index);
        for (Component cc : row.getComponents()) {
            if (cc instanceof JBLabel) return (JBLabel) cc;
        }
        return null;
    }

    /** T-HibDualCapture：第 index 个普通参数框的 tooltip（`#source = 值`），供长值兜底断言。 */
    public String paramFieldTooltipForTest(int index) {
        return index >= 0 && index < paramFields.size() ? paramFields.get(index).getToolTipText() : null;
    }

    /** T-HibDualCapture：第 index 个普通参数框的光标位置（应钉 0，视口停在值头部）。 */
    public int paramFieldCaretForTest(int index) {
        return index >= 0 && index < paramFields.size() ? paramFields.get(index).getCaretPosition() : -1;
    }

    /** T-PT-02：第 index 个普通参数框的占位提示（emptyText，按类型类别）。 */
    public String paramEmptyTextForTest(int index) {
        return index >= 0 && index < paramFields.size() ? paramFields.get(index).getEmptyText().getText() : null;
    }

    /** T-PT-02：第 index 个普通参数行的校验警示文案（无警示返回 null；不阻塞渲染）。 */
    public String paramWarningForTest(int index) {
        JBLabel label = index >= 0 && index < paramWarnLabels.size() ? paramWarnLabels.get(index) : null;
        return label != null && label.isVisible() ? label.getToolTipText() : null;
    }

    public JCheckBox conditionCheckboxForTest(int index) {
        return index >= 0 && index < conditionCheckBoxes.size() ? conditionCheckBoxes.get(index) : null;
    }

    public JBTextField conditionValueFieldForTest(int index) {
        return index >= 0 && index < conditionValueFields.size() ? conditionValueFields.get(index) : null;
    }

    /** T-M23-04：第 index 个批量列级勾选复选框（toggle 列按 BatchModel 顺序）。 */
    public JCheckBox batchCheckboxForTest(int index) {
        return index >= 0 && index < batchCheckBoxes.size() ? batchCheckBoxes.get(index) : null;
    }

    /** T-M23.2 / T-SelToggle：批量「参数列」区全选/全不选切换按钮。 */
    public JButton batchToggleSelectButtonForTest() {
        return batchToggleSelectButton;
    }

    public Set<String> enabledIdsForTest() {
        return new LinkedHashSet<>(enabledIds);
    }

    public JButton toggleSelectButtonForTest() {
        return toggleSelectButton;
    }

    public JButton clearButtonForTest() {
        return clearButton;
    }

    public JButton batchClearButtonForTest() {
        return batchClearButton;
    }

    /** T-PreviewBgFit 测试缝：左侧参数滚动区（背景/不透明断言用）。 */
    public JBScrollPane paramScrollForTest() {
        return paramScroll;
    }

    /** T-M26.1→T-UiNative：左右分栏（平台 OnePixelSplitter，原生分隔条主题融合 + 比例记忆）。 */
    public OnePixelSplitter splitPaneForTest() {
        return findSplitPane(this);
    }

    private static OnePixelSplitter findSplitPane(Container c) {
        for (Component child : c.getComponents()) {
            // OnePixelSplitter 的 orientation 为 boolean（false = 水平）
            if (child instanceof OnePixelSplitter && !((OnePixelSplitter) child).getOrientation()) {
                return (OnePixelSplitter) child;
            }
            if (child instanceof Container) {
                OnePixelSplitter found = findSplitPane((Container) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** T-M26.6：格式化快捷键 action（注册断言 + 触发格式化测试用）。 */
    public AnAction reformatActionForTest() {
        return reformatAction;
    }

    /** P-05：参数导入入口（测试调用用，绕过剪贴板）。 */
    public void importParamsForTest(Map<String, String> params) {
        SqlPreviewPanelParamPanelKt.importParams(this, params);
    }

    /** P-05：参数历史缓存（大小断言用）。 */
    public ParamHistoryCache paramHistoryCacheForTest() {
        return paramHistoryCache;
    }

    /** T-PreviewUx-Pack #2：诚实降级横幅（显隐/文案/类别断言用）。 */
    public DegradationBanner degradationBannerForTest() {
        return degradationBanner;
    }

    /** T-DmlImpactProminence：影响行数结果横幅（显隐断言用）。 */
    public JBPanel<JBPanel<?>> dmlImpactBannerForTest() {
        return dmlImpactBanner;
    }

    /** T-DmlImpactProminence：影响行数结果横幅文案（断言用）。 */
    public String dmlImpactBannerTextForTest() {
        return dmlImpactBannerLabel.getText();
    }

    /** P-05：当前 paramValues 快照（断言用）。 */
    public Map<String, String> paramValuesForTest() {
        return new LinkedHashMap<>(paramValues);
    }

    /** P-05：强制同步刷写历史缓存（测试用，绕过 debounce Timer）。 */
    public void flushHistoryForTest() {
        SqlPreviewPanelParamPanelKt.flushHistoryToCache(this);
    }

    /** P-05.1：当前 paramValues 序列化为 JSON（测试调用用）。 */
    public String serializeParamsToJsonForTest() {
        return SqlPreviewPanelParamPanelKt.serializeParamsToJson(this);
    }

    /** R22 T-02/T-03 测试缝：历史快照构建（含勾选态/foreach 权威态特殊键）。 */
    public Map<String, String> buildHistorySnapshotForTest() {
        return SqlPreviewPanelParamPanelKt.buildHistorySnapshot(this);
    }

    /** R22 T-02/T-03 测试缝：召回（含勾选态/foreach 权威态还原）。 */
    public void recallFromHistoryForTest() {
        SqlPreviewPanelParamPanelKt.recallFromHistory(this);
    }

    /** R22 T-02 测试缝：可见勾选框选中态（按 client property id/expr 查）。 */
    @Nullable
    public Boolean toggleCheckSelectedForTest(String id) {
        for (JCheckBox cb : toggleBoxes()) {
            if (id.equals(cb.getClientProperty(PROP_TOGGLE_ID))) return cb.isSelected();
        }
        return null;
    }

    /** R22 T-02 测试缝：foreach null 勾选框选中态。 */
    @Nullable
    public Boolean forEachNullSelectedForTest(String expr) {
        for (JCheckBox cb : xmlForEachNullBoxes) {
            if (expr.equals(cb.getClientProperty(PROP_FOREACH_NULL))) return cb.isSelected();
        }
        return null;
    }

    /** R22 T-03 测试缝：foreach 值字段文本。 */
    @Nullable
    public String forEachValueFieldTextForTest(String expr) {
        for (Pair<String, JBTextField> pair : xmlForEachValueFields) {
            if (pair.getFirst().equals(expr)) return pair.getSecond().getText();
        }
        return null;
    }

    /** R22 T-02/T-03 测试缝：权威状态集（召回断言用）。 */
    public Set<String> forEachNullsForTest() {
        return new LinkedHashSet<>(xmlForEachNulls);
    }

    public Map<String, List<String>> forEachValuesForTest() {
        return new LinkedHashMap<>(xmlForEachValues);
    }

    /** R22 T-02 测试缝：程序化切换条件勾选（同步可见勾选框，等价用户点击的状态面）。 */
    public void toggleCheckForTest(String id, boolean selected) {
        if (selected) enabledIds.add(id); else enabledIds.remove(id);
        for (JCheckBox cb : toggleBoxes()) {
            if (id.equals(cb.getClientProperty(PROP_TOGGLE_ID))) cb.setSelected(selected);
        }
    }

    /** R22 T-02 测试缝：程序化切换 foreach null 勾选。 */
    public void toggleForEachNullForTest(String expr, boolean selected) {
        if (selected) xmlForEachNulls.add(expr); else xmlForEachNulls.remove(expr);
        for (JCheckBox cb : xmlForEachNullBoxes) {
            if (expr.equals(cb.getClientProperty(PROP_FOREACH_NULL))) cb.setSelected(selected);
        }
    }

    /** R22 T-03 测试缝：程序化设置 foreach 权威值（同步可见字段文本）。 */
    public void setForEachValuesForTest(String expr, List<String> values) {
        xmlForEachValues.put(expr, values);
        for (Pair<String, JBTextField> pair : xmlForEachValueFields) {
            if (pair.getFirst().equals(expr)) pair.getSecond().setText(String.join(",", values));
        }
    }

    /** R22 T-03 测试缝：直驱 applyXmlForEachValues（等价字段 listener 链路的状态面）。 */
    public void applyXmlForEachValuesForTest(String expr, String text) {
        SqlPreviewPanelParamPanelKt.applyXmlForEachValues(this, new XmlForEachView(expr, null), text);
    }

    /** R22 T-02 测试缝：清空权威状态（模拟新会话；组件在场便于断言视觉同步）。 */
    public void clearStateForTest() {
        enabledIds.clear();
        paramValues.clear();
        xmlForEachValues.clear();
        xmlForEachNulls.clear();
    }

    /** R22 T-02 测试缝：快照灌入当前键的历史缓存（模拟既有方案）。 */
    public void loadSnapshotForTest(Map<String, String> snapshot) {
        String key = SqlPreviewPanelParamPanelKt.historyCacheKey(this);
        if (key == null) return;
        paramHistoryCache.save(key, snapshot);
    }

    /** R22 测试缝：当前预览 SQL 缓存键。 */
    @Nullable
    public String historyCacheKeyForTest() {
        return SqlPreviewPanelParamPanelKt.historyCacheKey(this);
    }

    /** R22 T-04 测试缝：方案下拉（在场/选中态断言）。 */
    public JComboBox<String> schemeComboForTest() {
        return schemeCombo;
    }

    /** O-88：当前预设折叠件实例（null=本轮参数区未建预设行——无参数早退/清空后）。 */
    public HideableTitledPanel presetFoldForTest() {
        return presetFold;
    }

    /** O-88：按 SCHEME_ROW_NAME 锚点取折叠内容里的方案行（折叠态下 visible=false 仍可取到）。 */
    @Nullable
    public JComponent schemeRowForTest() {
        return presetFold != null ? findSchemeRowDeep(presetFold) : null;
    }

    /** O-95 S3 测试缝：绑定指示行标签（null=本轮参数区未建指示行）。 */
    public JBLabel bindStateLabelForTest() {
        return bindStateLabel;
    }

    /** O-95 S3 测试缝：参数区组件序列（指示行在预设行之下的次序断言用，BoxLayout Y_AXIS 视觉序=组件序）。 */
    public List<Component> paramPanelComponentsForTest() {
        return List.of(paramPanel.getComponents());
    }

    private JComponent findSchemeRowDeep(Container root) {
        for (Component child : root.getComponents()) {
            if (SCHEME_ROW_NAME.equals(child.getName())) {
                return child instanceof JComponent ? (JComponent) child : null;
            }
            if (child instanceof Container) {
                JComponent found = findSchemeRowDeep((Container) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * 按 source 锚点取可见输入框（T-DevAcceptFix ⑤：召回回写用，禁插入序对位——见 syncParamFieldText）。
     */
    @Nullable
    public JBTextField paramFieldForSourceForTest(String source) {
        for (JBTextField field : paramFields) {
            if (source.equals(field.getClientProperty(PROP_FIELD_SOURCE))) return field;
        }
        return null;
    }

    /** R22 T-04 测试缝：程序化切换方案（等价下拉用户切换：清态→switchScheme→召回→渲染）。 */
    public void switchSchemeForTest(String name) {
        SqlPreviewPanelParamPanelKt.switchToScheme(this, name);
    }

    /** R22 T-04 测试缝：对话框回调链（同步下拉 + 激活变更整面重召回）。 */
    public void onSchemesChangedForTest() {
        SqlPreviewPanelParamPanelKt.onSchemesChangedFromDialog(this);
    }

    /** {@code (conditionCheckBoxes + batchCheckBoxes)} 等价（测试缝内部用）。 */
    private List<JCheckBox> toggleBoxes() {
        List<JCheckBox> all = new ArrayList<>(conditionCheckBoxes);
        all.addAll(batchCheckBoxes);
        return all;
    }
}
