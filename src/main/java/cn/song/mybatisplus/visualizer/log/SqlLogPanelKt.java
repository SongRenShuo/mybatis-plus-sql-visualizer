package cn.song.mybatisplus.visualizer.log;

import javax.swing.JPanel;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.MpvsUiColors;
import com.intellij.ui.SimpleTextAttributes;

import java.awt.Color;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code SqlLogPanel.kt} 顶层函数（{@code withTerminator} / {@code filterByType} / {@code filterBySearch} /
 * T-Perf-03 差分 {@code diffVisible} 家族 / 渲染辅助 {@code formatTimestampFragment} /
 * {@code formatDurationFragment} / {@code formatSourceFragment}）的 Java 承载类。
 */
public final class SqlLogPanelKt {

    private SqlLogPanelKt() {
    }

    /** T-PerfSweep26-07（F105）：报错片段空白折叠正则提文件级常量——原 inline 构造在渲染热路径每次重建（Java 侧 {@code String.replaceAll} 每调用重编 Pattern，同病），提常量后一次复用。 */
    private static final java.util.regex.Pattern ERROR_FRAGMENT_WHITESPACE_REGEX =
            java.util.regex.Pattern.compile("\\s+");

    /** SQL 末尾分号结束符（显示层统一追加；已以 `;` 结尾或空串不重复；T-M42 提为顶层，openPreview/右键复制/列表复制共用）。 */
    public static String withTerminator(String sql) {
        return (sql.isEmpty() || sql.endsWith(";")) ? sql : sql + ";";
    }

    /**
     * 空态文案写入（T-AgentDiagWrap T-04，独立成顶层函数以便单测）。
     *
     * <p>为什么不能 {@code status.text = plain}：{@code JBList.emptyText} 是 {@code com.intellij.util.ui.StatusText}
     * （<b>不是</b> JLabel），内容落在 {@code SimpleColoredComponent} 上——它不解析 HTML，
     * 也不把 {@code \n} 当换行（{@code \n} 被当普通字符画成零宽，两段文字被无声拼接；超宽只裁不折）。
     * 平台侧唯一的多行手段是 {@link com.intellij.util.ui.StatusText#appendLine(String)}：每行一个 Fragment，
     * {@code paintColumnInBounds} 逐 Fragment 单独绘制（<b>仍不折行</b>，所以宽度折行由
     * {@link CaptureDiagnostics#wrapEmptyTextLines} 先算好）。
     *
     * @param widthPx 列表当前可用像素宽度
     * @param measure 单串像素宽度测量
     */
    public static void applyEmptyTextLines(com.intellij.util.ui.StatusText status, String text, int widthPx,
            kotlin.jvm.functions.Function1<String, Integer> measure) {
        status.clear();
        for (String line : CaptureDiagnostics.wrapEmptyTextLines(text, widthPx, measure)) {
            status.appendLine(line);
        }
    }

    /**
     * 右键菜单选中收敛（T-ListPreviewCopy）：命中行已在多选集合内 → 保留原多选（复制项=批量复制）；
     * 否则收敛为命中行（右键=切换操作目标，与改造前无条件收敛单行等价）。纯函数可独立单测，
     * 日志 tab 与历史 tab 右键菜单共用。
     */
    public static int[] resolveContextMenuSelection(int[] selectedIndices, int hitIndex) {
        for (int sel : selectedIndices) {
            if (sel == hitIndex) {
                return selectedIndices;
            }
        }
        return new int[]{hitIndex};
    }

    /**
     * 批量复制文本（T-ListPreviewCopy）：选中条目升序逐条 {@link #withTerminator} 后 {@code "\n"} 拼接——
     * 与 JBList 内置 Ctrl+C 多选拼接链路（itemToText 逐条 + join("\n")）逐字节同构，
     * 右键批量复制与键盘复制两条入口产物一致。
     */
    public static String batchCopySql(List<LogSqlEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (LogSqlEntry it : entries) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(withTerminator(it.restoredSql()));
        }
        return sb.toString();
    }

    /** 复制菜单项文案（T-ListPreviewCopy）：多选带条数走 {@code selectedKey}（{0}=条数），单选沿用 {@code baseKey}。 */
    public static String copyMenuText(String baseKey, String selectedKey, int count) {
        return count > 1
                ? MpSqlVisualizerBundle.message(selectedKey, String.valueOf(count))
                : MpSqlVisualizerBundle.message(baseKey);
    }

    // ---------- T-LogListFreeze：列表刷新分批常量 ----------

    /** 首屏立即写入条数（保证快速首屏，EDT 冻结可控）。 */
    public static final int REFRESH_FIRST_BATCH_SIZE = 30;

    /** 每批异步追加条数（invokeLater 每批一次 EDT 脉冲）。 */
    public static final int REFRESH_CHUNK_SIZE = 30;

    /** 同步增量追加条数上限：超过则回退全量重建（全量重建带分批 EDT 写入，大批量更稳）。 */
    public static final int MAX_SYNC_APPEND = 300;

    /** 排序方式下拉项（文案 + 模式）。 */
    public record SortOption(String label, SortMode mode) {
    }

    /** 排序方式选项（顺序即下拉序；默认项在首位 = 时间升序）。 */
    public static List<SortOption> sortOptions() {
        List<SortOption> options = new ArrayList<>();
        options.add(new SortOption(MpSqlVisualizerBundle.message("log.sort.time.asc"), SortMode.TIME_ASC));
        options.add(new SortOption(MpSqlVisualizerBundle.message("log.sort.time.desc"), SortMode.TIME_DESC));
        options.add(new SortOption(MpSqlVisualizerBundle.message("log.sort.duration.asc"), SortMode.DURATION_ASC));
        options.add(new SortOption(MpSqlVisualizerBundle.message("log.sort.duration.desc"), SortMode.DURATION_DESC));
        options.add(new SortOption(MpSqlVisualizerBundle.message("log.sort.sequence"), SortMode.SEQUENCE));
        options.add(new SortOption(MpSqlVisualizerBundle.message("log.sort.structure"), SortMode.STRUCTURE_CLUSTER));
        options.add(new SortOption(MpSqlVisualizerBundle.message("log.sort.database"), SortMode.DATABASE_GROUP));
        return options;
    }

    /**
     * T-SqlLogToolbarEdt W3（2026-10-24）：{@code SortMode → 文案键} 静态查表。
     *
     * <p>为什么不再用 {@link #sortOptions()}：{@code sortModeAction.update()} 是<b>工具栏每次展开
     * 都要走的路径</b>（改 BGT 之前还在 EDT 上），而它每次 {@code firstOrNull{}} 都要新建 7 个
     * {@link SortOption} 并调 7 次 {@code MpSqlVisualizerBundle.message}（含 ResourceBundle 取值）。
     * 查表后 update 是常数时间、零分配。{@link #sortOptions()} 本身保留给「构建下拉项」这类
     * 一次性场景（需要顺序与 label）。</p>
     */
    public static String sortModeLabelKey(SortMode mode) {
        switch (mode) {
            case TIME_ASC: return "log.sort.time.asc";
            case TIME_DESC: return "log.sort.time.desc";
            case DURATION_ASC: return "log.sort.duration.asc";
            case DURATION_DESC: return "log.sort.duration.desc";
            case SEQUENCE: return "log.sort.sequence";
            case STRUCTURE_CLUSTER: return "log.sort.structure";
            case DATABASE_GROUP: return "log.sort.database";
        }
        throw new IllegalStateException("unexpected sort mode: " + mode);
    }

    public static final String DATABASE_UNKNOWN_SORT_KEY = "\uFFFF";

    /** 慢查询过滤使用调用方统一的耗时回退口径；关闭时直接透传。 */
    public static List<LogSqlEntry> filterBySlowOnly(List<LogSqlEntry> entries, boolean enabled,
            long thresholdMs, Function<LogSqlEntry, Long> durationOf) {
        if (!enabled) return entries;
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry entry : entries) {
            Long duration = durationOf.apply(entry);
            if ((duration != null ? duration : Long.MIN_VALUE) >= thresholdMs) result.add(entry);
        }
        return result;
    }

    /** 类型多选弹窗可勾选的类型（OTHER 不单列；全选时 OTHER 一并透传）。 */
    public static final List<StatementType> FILTERABLE_TYPES = List.of(
            StatementType.SELECT,
            StatementType.INSERT,
            StatementType.UPDATE,
            StatementType.DELETE,
            // T-DdlTypeColor：DDL 独立成档（不复用 INSERT/DELETE），故筛选弹窗单列，
            // 允许「只看结构变更」而不把普通增删一起筛出来
            StatementType.CREATE,
            StatementType.DROP
    );

    /**
     * 类型过滤（纯逻辑，可独立单测）：所选类型覆盖全部可筛选类型时视为「全部」，
     * 透传所有条目（含 OTHER）；否则仅保留分类命中的条目。
     */
    public static List<LogSqlEntry> filterByType(List<LogSqlEntry> entries, Set<StatementType> activeTypes) {
        if (activeTypes.containsAll(FILTERABLE_TYPES)) {
            return entries;
        }
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry it : entries) {
            // T-CrudColor：改用条目级缓存属性（行为等价）——避免每次刷新都重跑分类
            // （classify 内 Regex 每次编译 Pattern），与渲染器共用同一份结果。
            if (activeTypes.contains(it.statementType())) {
                result.add(it);
            }
        }
        return result;
    }

    /**
     * 屏蔽规则存量过滤（T-SqlSuppress O-69，纯逻辑，可独立单测）：命中屏蔽指纹集的条目
     * 从列表隐藏（缓冲里仍在——删除规则后复现；闸门已丢弃的历史条目不可恢复，UI 文案明示）。
     * {@code suppressKeys} 与闸门同一数据源（SqlSuppressRuleManager.getSuppressedKeys 经
     * {@link SqlLogManager} 同步进缓冲），空集透传（无规则常态零成本）。
     */
    public static List<LogSqlEntry> filterBySuppress(List<LogSqlEntry> entries, Set<String> suppressKeys) {
        if (suppressKeys.isEmpty()) {
            return entries;
        }
        // T-SqlSuppressUx 二段：精确键或结构键（手动开启结构匹配的规则）任一命中即滤除
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry it : entries) {
            if (!suppressKeys.contains(it.suppressKey()) && !suppressKeys.contains(it.structureKey())) {
                result.add(it);
            }
        }
        return result;
    }

    /**
     * 搜索过滤（纯逻辑，可独立单测）：查询词忽略大小写 contains，匹配还原 SQL 与摘要
     * （摘要含参数值的还原 SQL 前缀；参数值是常见检索目标）；空白查询透传全部。
     *
     * <p>T-LogSearchRowTime 扩展：
     * <ul>
     * <li>行号——{@code #5} / {@code 5} 精确 equals {@link LogSqlEntry#sequenceNumber()}（渲染形态 {@code #123}，所见即可搜；
     *   精确而非 contains：搜 {@code 1} 不应命中全部 1xx 行；0 = 无行号条目天然不命中任何行号查询）</li>
     * <li>时间——对显示片段（{@link #timestampDisplayText}，含方括号与设置格式，所见即所搜）contains；
     *   显示开关关闭时片段为 null，时间分支天然不参与</li>
     * </ul>
     * {@code timestampTextOf} 可注入（测试绕开设置读取），默认取当前设置下的真实显示片段。
     */
    public static List<LogSqlEntry> filterBySearch(List<LogSqlEntry> entries, String query,
            Function<LogSqlEntry, String> timestampTextOf) {
        String q = query.trim();
        if (q.isEmpty()) {
            return entries;
        }
        Long rowNumber = null;
        String rowRaw = q.startsWith("#") ? q.substring(1) : q;
        try {
            long parsed = Long.parseLong(rowRaw);
            if (parsed > 0) {
                rowNumber = parsed;
            }
        } catch (NumberFormatException ignored) {
            // 非纯数字查询 → 行号维度不参与
        }
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry it : entries) {
            boolean match = containsIgnoreCase(it.restoredSql(), q)
                    || containsIgnoreCase(it.summary(), q)
                    || (rowNumber != null && it.sequenceNumber() == rowNumber)
                    || (timestampTextOf != null
                        && timestampDisplayTextSafe(timestampTextOf, it, q));
            if (match) {
                result.add(it);
            }
        }
        return result;
    }

    /** 免注入重载：默认取当前设置下的真实显示片段（{@link #timestampDisplayText}）。 */
    public static List<LogSqlEntry> filterBySearch(List<LogSqlEntry> entries, String query) {
        return filterBySearch(entries, query, SqlLogPanelKt::timestampDisplayText);
    }

    /**
     * T-HistorySearchToggles / T-SearchToggleNativeLook：带 MatchCase/Regex 开关与正则编译 memo 的搜索过滤
     * （形态照日志页/历史页同链，master LogEntryFiltering.filterBySearch 的 Java 承载）。
     *
     * <ul>
     *   <li><b>正则</b>：生效条件 = 开启正则开关<b>或</b> {@code regex:} 前缀（前缀在场先剥、不参与匹配）；
     *       命中 restoredSql/summary；编译旗随 matchCase；非法正则 fail-closed 回退普通子串匹配；</li>
     *   <li><b>子串</b>：匹配 restoredSql/summary/时间显示片段，大小写统一由 matchCase 控制；
     *       行号 {@code #N}/{@code N} 精确 equals sequenceNumber，不受开关影响；</li>
     *   <li>空查询透传全部。</li>
     * </ul>
     */
    public static List<LogSqlEntry> filterBySearch(List<LogSqlEntry> entries, String query,
            boolean matchCase, boolean regexEnabled, HistoryRegexMemo memo) {
        String q = query.trim();
        if (q.isEmpty()) {
            return entries;
        }
        if (regexEnabled || q.startsWith(HistorySqlLogPanelKt.HISTORY_REGEX_PREFIX)) {
            kotlin.text.Regex pattern = HistorySqlLogPanelKt.resolveHistoryRegex(
                    q.substring(HistorySqlLogPanelKt.HISTORY_REGEX_PREFIX.length()), matchCase, memo);
            if (pattern != null) {
                List<LogSqlEntry> result = new ArrayList<>();
                for (LogSqlEntry entry : entries) {
                    if (pattern.containsMatchIn(entry.restoredSql()) || pattern.containsMatchIn(entry.summary())) {
                        result.add(entry);
                    }
                }
                return result;
            }
            // 非法正则：回退普通子串匹配（继续走下方通用路径）
        }
        Long rowNumber = null;
        String rowRaw = q.startsWith("#") ? q.substring(1) : q;
        try {
            long parsed = Long.parseLong(rowRaw);
            if (parsed > 0) {
                rowNumber = parsed;
            }
        } catch (NumberFormatException ignored) {
            // 非纯数字查询 → 行号维度不参与
        }
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry it : entries) {
            String ts = timestampDisplayText(it);
            boolean match = contains(it.restoredSql(), q, !matchCase)
                    || contains(it.summary(), q, !matchCase)
                    || (rowNumber != null && it.sequenceNumber() == rowNumber)
                    || (ts != null && contains(ts, q, !matchCase));
            if (match) {
                result.add(it);
            }
        }
        return result;
    }

    /** 子串包含（ignoreCase 控制大小写；master `String.contains(q, ignoreCase = ...)` 的 Java 承载）。 */
    private static boolean contains(String text, String q, boolean ignoreCase) {
        if (text == null) {
            return false;
        }
        if (ignoreCase) {
            return text.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));
        }
        return text.contains(q);
    }

    private static boolean containsIgnoreCase(String text, String q) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));
    }

    private static boolean timestampDisplayTextSafe(Function<LogSqlEntry, String> timestampTextOf,
            LogSqlEntry entry, String q) {
        String text = timestampTextOf.apply(entry);
        return text != null && containsIgnoreCase(text, q);
    }

    /**
     * 时间显示片段文本（T-LogSearchRowTime）：{@code [yyyy-MM-dd HH:mm:ss]} 形态（去段间距尾随空格），
     * 与列表渲染逐字节一致（所见即所搜/所复制的单一真源——格式/来源/开关语义全在
     * {@link #formatTimestampFragment}）；开关关闭返回 null（页面上没有时间片段可搜可复制）。
     */
    public static String timestampDisplayText(LogSqlEntry entry) {
        kotlin.Pair<String, SimpleTextAttributes> fragment = formatTimestampFragment(entry);
        return fragment == null ? null : fragment.getFirst().trim();
    }

    /**
     * 批量复制时间戳（T-LogSearchRowTime）：显示片段去方括号（用户定调：复制出纯时间文本
     * {@code 2024-01-15 10:30:45}，不带显示装饰括号）逐行拼接，与 {@link #batchCopySql} 同构；
     * 显示开关关闭时片段为 null 被跳过——全部关闭时返回空串（菜单项据此置灰）。
     */
    public static String batchCopyTimestamps(List<LogSqlEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (LogSqlEntry it : entries) {
            String text = timestampDisplayText(it);
            if (text == null) {
                continue;
            }
            if (text.startsWith("[") && text.endsWith("]") && text.length() >= 2) {
                text = text.substring(1, text.length() - 1);
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    // ---------- M56 T-M56-00：动态聚焦页签过滤 ----------

    /**
     * M56 T-M56-00：动态聚焦页签过滤层（纯逻辑，插在 {@link #filterByType} 与 {@link #filterBySearch} 之间）。
     * {@code method} 匹配 {@link LogSqlEntry#mapperStatement()}（agent 通道直取 / 桥通道标记第 4 段，
     * 忽略大小写）；{@code requestTag} 精确匹配 {@link LogSqlEntry#requestTag()}。
     * 双条件为「且」；null 维度透传；全 null 等价不过滤。T-M56-06 起 method 扩展真实方法边界
     * {@code {Class}#{method}} 形态（届时在 {@link #focusMethodKey} 统一登记来源）。
     */
    public record FocusFilter(String method, String requestTag) {

        /** 是否为空过滤（全维度 null = 透传）。 */
        public boolean isPassthrough() {
            return method == null && requestTag == null;
        }
    }

    /** 条目的聚焦 method 键（当前 = mapper 语句 id；T-M56-06 扩展真实方法边界）。 */
    public static String focusMethodKey(LogSqlEntry entry) {
        return entry.mapperStatement();
    }

    public static List<LogSqlEntry> filterByFocus(List<LogSqlEntry> entries, FocusFilter focus) {
        if (focus == null || focus.isPassthrough()) {
            return entries;
        }
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry e : entries) {
            boolean methodOk = focus.method() == null
                    || (focusMethodKey(e) != null && focusMethodKey(e).equalsIgnoreCase(focus.method()));
            boolean tagOk = focus.requestTag() == null
                    || focus.requestTag().equals(e.requestTag());
            if (methodOk && tagOk) {
                result.add(e);
            }
        }
        return result;
    }

    // ---------- T-Perf-03（自 dev 移植）：可见列表差分 ----------

    /**
     * 新旧可见列表差分结果（增量刷新决策；元素按同一性 == 引用比较——条目为不可变 record，
     * 缓冲内同一条目恒为同一实例）。
     */
    public interface VisibleDiff {

        /** 头部淘汰 evicted 条 + 尾部追加 appended（缓冲满后的流式 steady state；appended 可为空）。 */
        record HeadEvictAppend(int evicted, List<LogSqlEntry> appended) implements VisibleDiff {
        }

        /** 从 index 起插入 entries（尾部纯追加时 index == old.size）。 */
        record Insert(int index, List<LogSqlEntry> entries) implements VisibleDiff {
        }

        /** 尾部纯移除：移除 [from, old.size)。 */
        record TailRemove(int from) implements VisibleDiff {
        }

        /** 无变化。 */
        final class Same implements VisibleDiff {
            public static final Same INSTANCE = new Same();

            private Same() {
            }
        }

        /** 无法增量（离散增删/超大追加）——全量重建。 */
        final class FullRebuild implements VisibleDiff {
            public static final FullRebuild INSTANCE = new FullRebuild();

            private FullRebuild() {
            }
        }
    }

    /** 同一性公共前缀长度。 */
    public static int commonPrefixLength(List<LogSqlEntry> old, List<LogSqlEntry> new_) {
        int n = Math.min(old.size(), new_.size());
        int i = 0;
        while (i < n && old.get(i) == new_.get(i)) i++;
        return i;
    }

    /** 同一性公共后缀长度（不与 prefix 重叠）。 */
    public static int commonSuffixLength(List<LogSqlEntry> old, List<LogSqlEntry> new_, int prefix) {
        int n = Math.min(old.size(), new_.size()) - prefix;
        int i = 0;
        while (i < n && old.get(old.size() - 1 - i) == new_.get(new_.size() - 1 - i)) i++;
        return i;
    }

    /** old 的 [aStart..] 与 new 的 [bStart..] 前 length 个元素是否同一。 */
    public static boolean identityPrefix(List<LogSqlEntry> old, int aStart, List<LogSqlEntry> new_, int bStart, int length) {
        if (old.size() - aStart < length || new_.size() - bStart < length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (old.get(aStart + i) != new_.get(bStart + i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 可见列表差分：流式灌入时把「clear+全量重建」降阶为「尾部追加 / 头部淘汰+追加」。
     *
     * 判定顺序：
     *  1. 头部淘汰对齐（evicted>0 且 old 去头后与 new 前缀同一）→ HeadEvictAppend；
     *  2. 前缀/后缀差分 → 纯插入（含尾部追加）/ 尾部纯移除 / 无变化；
     *  3. 其余（过滤、搜索、去重裁剪、排序导致的离散增删）→ FullRebuild。
     */
    public static VisibleDiff diffVisible(List<LogSqlEntry> old, List<LogSqlEntry> new_, int evicted) {
        if (evicted > 0 && old.size() >= evicted && new_.size() >= old.size() - evicted
                && identityPrefix(old, evicted, new_, 0, old.size() - evicted)) {
            List<LogSqlEntry> appended = new_.subList(old.size() - evicted, new_.size());
            return new VisibleDiff.HeadEvictAppend(evicted, appended);
        }
        int prefix = commonPrefixLength(old, new_);
        int suffix = commonSuffixLength(old, new_, prefix);
        int removed = old.size() - prefix - suffix;
        int added = new_.size() - prefix - suffix;
        if (removed == 0 && added == 0) {
            return VisibleDiff.Same.INSTANCE;
        }
        if (removed == 0 && added >= 1 && added <= MAX_SYNC_APPEND) {
            return new VisibleDiff.Insert(prefix, new_.subList(prefix, prefix + added));
        }
        if (added == 0 && suffix == 0) {
            return new VisibleDiff.TailRemove(prefix);
        }
        return VisibleDiff.FullRebuild.INSTANCE;
    }

    // ---------- 列表渲染辅助（T-LogListFreeze-04 片段通道；渲染器与 SqlLogPanel 共用） ----------

    /** 元信息灰（时间戳/来源/徽章；与原 HTML 通道 #9E9E9E 一致）。T-UN-13（T-UiNative）：改 namedColor，亮值不变。 */
    public static final SimpleTextAttributes LIST_META_GRAY =
            new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY);

    /**
     * T-CrudColor：按标记形态应用「色条 / 整行淡背景」——<b>两个列表渲染器共用</b>（§1.2.1）。
     *
     * 三分支：
     *  - 关闭 / OTHER（tint = null）→ 与改造前逐字节一致；
     *  - {@link SqlListTypeColor#STYLE_BAR} → 色条着色，单元格背景走平台选中填充（色条不受影响，选中不丢色）；
     *  - {@link SqlListTypeColor#STYLE_BACKGROUND} → <b>未选中</b>行底色与列表背景淡混色（类型标记）；
     *    选中行交给平台选中填充（T-UN-09 描边退役——自研选中视觉整体下线，不再干预 Border）。
     */
    public static void applyCrudColorMark(
            JPanel cell,
            JPanel colorBar,
            javax.swing.border.Border normalBorder,
            Color tint,
            boolean isSelected,
            javax.swing.JList<?> list) {
        applyCrudColorMark(cell, colorBar, normalBorder, tint, isSelected, list, false);
    }

    public static void applyCrudColorMark(
            JPanel cell,
            JPanel colorBar,
            javax.swing.border.Border normalBorder,
            Color tint,
            boolean isSelected,
            javax.swing.JList<?> list,
            boolean dashed) {
        Color listBg = list != null ? list.getBackground() : cell.getBackground();
        Color selectionBg = list != null ? list.getSelectionBackground() : listBg;
        switch (SqlListTypeColor.styleOf()) {
            case SqlListTypeColor.STYLE_BAR: {
                // T-DdlTypeColor：DDL 走虚线绘制。把形态告知色条面板（自绘时读它），
                // 而不是在这里直接画——绘制逻辑归组件自己，本函数只管「该显示什么」。
                if (colorBar instanceof TypeColorBar) ((TypeColorBar) colorBar).setDashed(dashed);
                if (tint == null) {
                    setOpaqueIfChanged(colorBar, false);
                } else {
                    setBackgroundIfChanged(colorBar, tint);
                    setOpaqueIfChanged(colorBar, true);
                }
                setBorderIfChanged(cell, normalBorder);
                setBackgroundIfChanged(cell, isSelected ? selectionBg : listBg);
                break;
            }
            case SqlListTypeColor.STYLE_BACKGROUND: {
                setOpaqueIfChanged(colorBar, false);
                setBorderIfChanged(cell, normalBorder);
                setBackgroundIfChanged(cell, isSelected ? selectionBg
                        : (tint == null ? listBg
                        : SqlListTypeColor.blend(listBg, tint, SqlListTypeColor.MIX_NORMAL)));
                break;
            }
            default: { // STYLE_OFF
                setOpaqueIfChanged(colorBar, false);
                setBorderIfChanged(cell, normalBorder);
                setBackgroundIfChanged(cell, isSelected ? selectionBg : listBg);
                break;
            }
        }
    }

    /**
     * 仅在变化时设置边框（T-CrudColor 性能守卫）。
     *
     * 实测（JBR 21）：{@code JComponent.setBorder(新实例)} <b>即使值与旧值相同也会触发 repaint() +1</b>，
     * 而传入<b>同一实例</b>则 revalidate/repaint 均为 0。渲染器逐格调用，若每格无脑赋值就会把
     * 渲染热路径变成「每格一次重绘调度」——正是 T-LogListFreeze 那类副作用累积问题的成因。
     * 用<b>同一性</b>（{@code !=}）比较：本函数只会在预建的两个 Border 实例间切换，身份比较即可，
     * 且避免 CompoundBorder.equals 的深度比较开销。
     */
    private static void setBorderIfChanged(JPanel cell, javax.swing.border.Border border) {
        if (cell.getBorder() != border) {
            cell.setBorder(border);
        }
    }

    /**
     * 仅在变化时设置背景（T-CrudColor 性能守卫）。
     *
     * 实测（JBR 21）：{@code setBackground(新 Color 实例)} 即使 RGB 相同也会触发 repaint() +1；
     * 传入同一实例则为 0。配合 {@link SqlListTypeColor#colorOf} 的实例缓存，同色格不再产生任何 repaint。
     */
    private static void setBackgroundIfChanged(JPanel target, Color color) {
        if (!java.util.Objects.equals(color, target.getBackground())) {
            target.setBackground(color);
        }
    }

    /** 仅在变化时设置不透明（setOpaque 同值本身零副作用，此处为语义显式化）。 */
    private static void setOpaqueIfChanged(JPanel target, boolean opaque) {
        if (target.isOpaque() != opaque) {
            target.setOpaque(opaque);
        }
    }

    // ----------
    // T-PerfSweep3 #3：SimpleDateFormat 复用——formatTimestampFragment 是「每可见单元格渲染 +
    // 每条目搜索匹配/批量复制」的双热路径（SqlLogPanel 与 HistorySqlLogPanel 两面板共用本单点），
    // 此前每次调用新建 SimpleDateFormat。SimpleDateFormat 非线程安全，照 SqlLogAutoSaver 的
    // T-Perf-12 先例做 ThreadLocal + 格式串键缓存：键=格式串天然跟随设置变更（换格式=新条目，
    // 无需失效钩子）；消费形态是 EDT 渲染/搜索与右键菜单，ThreadLocal 在覆盖多线程的同时免同步。
    // format(Date) 每次完整重置内部 calendar，复用同一实例输出与逐次新建逐字节一致；
    // 缓存规模上界=用户可配置的格式串种类数（个位数量级），非法格式串构造即抛不进缓存、
    // 每次重试构造（与逐次新建同语义，仍由调用处 catch 回落默认格式）。
    // ----------
    private static final ThreadLocal<Map<String, SimpleDateFormat>> TIMESTAMP_FORMATTER_CACHE =
            ThreadLocal.withInitial(HashMap::new);

    private static SimpleDateFormat timestampFormatter(String formatStr) {
        return TIMESTAMP_FORMATTER_CACHE.get().computeIfAbsent(formatStr, SimpleDateFormat::new);
    }

    /**
     * 时间戳片段（T-LogSearchRowTime 收口为条目级）：未开启时间戳返回 null。
     * 片段文本带尾随空格做段间距（原 HTML span 尾空格语义）；时间来源跟随设置
     * （「日志时间」源且条目有原始日志时间时用之，否则捕获时间）——排序口径 displayTimeOf 同规则。
     */
    public static kotlin.Pair<String, SimpleTextAttributes> formatTimestampFragment(LogSqlEntry entry) {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        if (!state.getLogTimestampEnabled()) {
            return null;
        }
        long ts;
        if (PluginSettings.LOG_TIMESTAMP_SOURCE_LOG.equals(state.getLogTimestampSource())
                && entry.logTimestamp() != null) {
            ts = entry.logTimestamp();
        } else {
            ts = entry.timestamp();
        }
        String formatted;
        try {
            formatted = timestampFormatter(state.getLogTimestampFormat()).format(new Date(ts));
        } catch (IllegalArgumentException e) {
            formatted = timestampFormatter(SqlLogPanel.DEFAULT_TIMESTAMP_FORMAT).format(new Date(ts));
        }
        return new kotlin.Pair<>("[" + formatted + "] ", LIST_META_GRAY);
    }

    /** 耗时片段：颜色分档与原 HTML 通道一致（选中白/无耗时灰/慢红/偏慢橙/正常绿）；无耗时显示 "—" 占位。 */
    public static kotlin.Pair<String, SimpleTextAttributes> formatDurationFragment(Long ms, boolean isSelected) {
        long threshold = PluginSettings.getInstance().getState().getSlowQueryThresholdMs();
        Color color;
        if (isSelected) {
            // T-UN-13（T-UiNative）：五档写死色 → namedColor（亮值不变）
            color = MpvsUiColors.DURATION_SELECTED;
        } else if (ms == null) {
            color = MpvsUiColors.DURATION_NONE;             // 灰：无耗时
        } else if (ms >= threshold) {
            color = MpvsUiColors.DURATION_SLOW;             // 红：慢查询
        } else if (ms >= threshold / 2) {
            color = MpvsUiColors.DURATION_WARN;             // 橙：偏慢
        } else {
            color = MpvsUiColors.DURATION_OK;               // 绿：正常
        }
        String text = (ms != null) ? (ms + "ms") : "—";
        return new kotlin.Pair<>(text + " ", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color));
    }

    /** M56 T-M56-02：增强捕获角标已按用户要求移除（增强为默认通道，逐行标来源属噪音）。 */

    /**
     * O-90：同模板计数徽章（N+1 捕获后聚类标记）——counts[structureKey] 达
     * {@link LogSqlModelsKt#TEMPLATE_BADGE_MIN_COUNT} 才显示「×N 同模板」，与去重 ⨉N 徽章相邻同落点（元信息行尾）。
     * 计数由调用方传面板全量条目的 {@link LogSqlModelsKt#structureKeyCounts} 聚合快照（徽章计数不随当前过滤波动）；
     * 无计数/未达阈值返回 null 不显示。文案走双语 bundle（log.panel.templateBadge）；着色
     * {@link MpvsUiColors#TEMPLATE_N1}（选中白同规）。
     */
    public static kotlin.Pair<String, SimpleTextAttributes> formatTemplateCountFragment(
            LogSqlEntry entry,
            Map<String, Integer> counts,
            boolean isSelected) {
        Integer n = counts.get(entry.structureKey());
        if (n == null) {
            return null;
        }
        if (n < LogSqlModelsKt.TEMPLATE_BADGE_MIN_COUNT) {
            return null;
        }
        Color color = isSelected ? new Color(0xFFFFFF) : MpvsUiColors.TEMPLATE_N1;
        return new kotlin.Pair<>(
                MpSqlVisualizerBundle.message("log.panel.templateBadge", n) + " ",
                new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color));
    }

    /**
     * T-AgentDialectLoop：目标库角标（agent 通道由连接 URL 推断）；未知返回 null 不显示。
     *
     * <p>T-DataSourceBadgeMode（O-133，D135 显示侧门控）：默认仅显示数据库类型——{@code ·} 分隔符唯一产自
     * {@link cn.song.mybatisplus.visualizer.log.agent.JdbcUrlDialects#badgeName}，OFF 态切其前段即纯方言段
     * （落盘/历史读回/MCP 检索链仍消费全量串，后开开关不丢信息，切换即时生效）；
     * ON 态原样全量（{@code [MySQL · jpa_demo@localhost:3306]}）。无 {@code ·} 分隔符的串（方言解析失败仅存
     * 连接事实）OFF 态原样保留——辨不出方言时保留最后上下文优于显示空白。null 恒 null 不显示。
     * 热路径直读设置沿 {@link #formatDurationFragment} 先例（每 cell 一次服务查找，V38 基准同量级）。
     */
    public static kotlin.Pair<String, SimpleTextAttributes> formatDatabaseFragment(LogSqlEntry entry, boolean isSelected) {
        String full = entry.databaseName();
        if (full == null) {
            return null;
        }
        String db = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().getState().getDataSourceBadgeDetail()
                ? full
                : full.contains(" · ") ? full.substring(0, full.indexOf(" · ")) : full;
        Color color = isSelected ? new Color(0xFFFFFF) : new Color(0x00695C);
        return new kotlin.Pair<>("[" + db + "] ", new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color));
    }

    /**
     * T-McpAuditTrail T-MAT-05：MCP 通道徽标——{@code source == MCP}（MCP 执行类工具跑过的语句）才显示，
     * 其余来源返回 null 不显示（与目标库角标同规：未知即不占位，零噪音）。
     *
     * <p>落点与既有角标一致（时间/耗时/目标库之后、来源之前），协议同为
     * {@code Pair<String, SimpleTextAttributes>?}——两页渲染器（{@link LogEntryListCellRenderer} 与历史页
     * {@link HistoryCellRenderer}）共用本函数，避免「同条目两页显示不一致」。
     * 读回侧来源由 {@link SqlSource#MCP} 的 pd 载荷回填（见 SqlLogParamCodec）。
     */
    public static kotlin.Pair<String, SimpleTextAttributes> formatMcpFragment(LogSqlEntry entry, boolean isSelected) {
        if (entry.source() != SqlSource.MCP) return null;
        Color color = isSelected ? new Color(0xFFFFFF) : new Color(0x7B1FA2);
        return new kotlin.Pair<>("[MCP] ", new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color));
    }

    /** M56 T-M56-05：影响行数片段（DML 语义；null 未知不显示）。 */
    public static kotlin.Pair<String, SimpleTextAttributes> formatUpdateCountFragment(LogSqlEntry entry, boolean isSelected) {
        Long u = entry.updateCount();
        if (u == null) {
            return null;
        }
        // 负值 = 无有效计数（JDBC getUpdateCount 的 -1 语义；旧版 agent 会误报 -1）→ 不显示
        if (u < 0) {
            return null;
        }
        Color color = isSelected ? new Color(0xFFFFFF) : new Color(0x607D8B);
        return new kotlin.Pair<>("(" + u + " rows) ", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color));
    }

    /** M56 T-M56-05：报错片段（errorText 同列展示；红字截断首段，悬停完整文本在预览窗）。 */
    public static kotlin.Pair<String, SimpleTextAttributes> formatErrorFragment(LogSqlEntry entry) {
        String err = entry.errorText();
        if (err == null) {
            return null;
        }
        String oneLine = ERROR_FRAGMENT_WHITESPACE_REGEX.matcher(err).replaceAll(" ");
        String text = oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
        return new kotlin.Pair<>("⛔ " + text + " ",
                new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new Color(0xD32F2F)));
    }

    /** 来源片段：未知来源返回 null；选中态走组件前景（selectionForeground），非选中灰。 */
    public static kotlin.Pair<String, SimpleTextAttributes> formatSourceFragment(LogSqlEntry entry, boolean isSelected) {
        String source;
        if (SourceMarkers.isMpPaginationCount(entry.sourceFile())) {
            source = "MP 分页计数";
        } else if (entry.sourceFile() != null) {
            // F-44-7：标记路径始终使用正斜杠（与 CallerSourceLocator 一致），不能用 File.separatorChar
            String file = entry.sourceFile();
            String name = file.substring(file.lastIndexOf('/') + 1);
            source = entry.sourceLine() > 0 ? name + ":" + entry.sourceLine() : name;
        } else {
            return null;
        }
        return new kotlin.Pair<>("[" + source + "] ",
                isSelected ? SimpleTextAttributes.REGULAR_ATTRIBUTES : LIST_META_GRAY);
    }
}
