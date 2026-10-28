package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.speedSearch.SpeedSearchUtil;

import javax.swing.BorderFactory;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 列表条目单元格渲染器基类（T-LogListHeader）。
 *
 * <p>版面（自上而下）：</p>
 * <pre>
 * [色条] #序号 时间 耗时 [库] 类名:行号 (行数) 同结构 N 条   ← 头部行（全部元信息）
 *        SELECT ...                                        ← SQL 第 1 行
 *        FROM ...
 *        WHERE ...
 *        ⛔ 报错（仅 errorText 存在时）
 * </pre>
 *
 * <p>演变：{@code bed58b64} 的 88px 左侧信息轨 + 进度条 + 竖线已全部撤除（用户实测否决：占宽度、
 * 与 SQL 不同轴、扫视要左右来回）。元信息改为头部一行，SQL 区拿整行宽度。</p>
 *
 * <p>条目分割：靠<b>上下各 12px 留白 + 底部 1px 略强色线</b>（见 {@link #ROW_VERTICAL_PADDING}）。
 * 不画斑马纹——深色主题下极淡底色辨识度不足，用户明确否决。</p>
 *
 * <p>头部行超宽时走 {@code clipSqlFragments} 的<b>顺序截断</b>（从左到右、超出即停、末尾补 {@code …}）——
 * 与 {@code 2026.10.23} 版本行为一致。曾一度实现「按优先级丢片段」的退化梯度，用户实测否决
 * （「类名为什么要省略，还不如 2026.10.23 版本的」），已整段删除。</p>
 */
public abstract class RailCellRendererBase extends JPanel implements ListCellRenderer<Object> {

    /** 头部行（序号/时间/耗时/库/MCP/来源/影响行数/徽章）。 */
    public final SimpleColoredComponent headerLine = new SimpleColoredComponent();

    /**
     * 首行兼容别名（存量测试断言元信息用）。<b>只读</b>——写入请走 {@link #headerLine}
     * （两字段恒为同一实例，final 防再赋值即 Kotlin {@code get() = headerLine} 别名的 Java 等价）。
     */
    public final SimpleColoredComponent metaLine = headerLine;

    /** SQL 行组件池（T-ListMode：单行模式恒 1 行；多行模式按变换后行数伸缩）。 */
    private final List<SimpleColoredComponent> sqlLinePool = new ArrayList<>();

    /** SQL 首行（SINGLE_LINE 恒为唯一 SQL 行；测试断言兼容单行语义）。 */
    public SimpleColoredComponent getSqlLine() {
        return sqlLinePool.get(0);
    }

    /** 全部 SQL 行（供 UI 测试按行断言多行模式渲染）。 */
    public List<SimpleColoredComponent> getSqlLines() {
        return sqlLinePool;
    }

    private final JPanel linesPanel = new JPanel(new java.awt.GridLayout(0, 1));

    /**
     * T-CrudColor：左侧类型色条（{@link SqlListTypeColor#STYLE_BAR} 下着色可见；
     * 其他样式 isOpaque=false 但仍<b>占位</b>——切换样式时文本左缘不位移）。
     * 类型只靠本色条表达，不加「查/增/改/删」字样。
     */
    public final TypeColorBar colorBar = createColorBar();

    /**
     * 未选中态 Border：上下留白 + 底部 1px 分隔线。
     *
     * <p>留白是分条主力——改造前上下各 5px，而条目内 SQL 行距也是 1.45 倍行高，
     * 导致「条目内间距 ≈ 条目间距」，眼睛找不到切分点（用户原话「容易混在一起」）。
     * 分隔线用比 {@code JBColor.border()} 略强的色，深色主题下才看得见。</p>
     *
     * <p><b>留白按模式分流</b>：单行模式本身是「一屏塞更多条」的密度模式，若也吃 12px 上下留白，
     * 单条从 ~45px 涨到 68px（一屏少看 4 条），与它的用途冲突——故单行用
     * {@link #ROW_VERTICAL_PADDING_DENSE}。两个 Border 实例<b>预建</b>，渲染热路径不新建 Border
     * （T-LogListFreeze 同族守卫）。</p>
     */
    public final Border normalBorder = borderFor(ROW_VERTICAL_PADDING);

    /** 单行模式用的紧凑 Border（上下 {@link #ROW_VERTICAL_PADDING_DENSE}）。 */
    private final Border compactBorder = borderFor(ROW_VERTICAL_PADDING_DENSE);

    /** 内容区：头部行（NORTH）+ SQL 行（CENTER）+ 错误行（SOUTH，按需挂载）。 */
    private final JPanel content = new JPanel(new BorderLayout());

    /** 头部行与 SQL 行之间的细分隔留白（纯视觉呼吸，不加线）。 */
    private final JPanel headerGap = new JPanel();

    /** SQL 区容器：SQL 行（CENTER）+ 错误行（SOUTH）。 */
    private final JPanel sqlHost = new JPanel(new BorderLayout());

    /**
     * 错误行（全宽红字；有 errorText 才挂载）。报错文本长度不可控，仍需通栏而不是塞进头部行。
     */
    private final SimpleColoredComponent errorLine = new SimpleColoredComponent();

    private boolean errorMounted = false;

    /** 同模板计数快照（子类提供取值来源）。 */
    protected abstract Map<String, Integer> templateCountsSnapshot();

    /** 可见条目最大耗时快照（子类提供取值来源）。 */
    protected abstract Long maxDurationMsSnapshot();

    /** 是否显示耗时（日志页/历史页各读设置）。 */
    protected abstract boolean showDuration();

    /** 元信息渲染文本（测试断言用）。 */
    public String getMetaTextForTest() {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = headerLine.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
        }
        return sb.toString().trim();
    }

    /** 错误行渲染文本（测试断言用）。 */
    public String getErrorTextForTest() {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = errorLine.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
        }
        return sb.toString().trim();
    }

    public RailCellRendererBase() {
        super(new BorderLayout());
        sqlLinePool.add(newLineComponent());
        setOpaque(true);
        headerLine.setOpaque(false);
        sqlLinePool.get(0).setOpaque(false);
        errorLine.setOpaque(false);
        linesPanel.setOpaque(false);
        content.setOpaque(false);
        sqlHost.setOpaque(false);
        headerGap.setOpaque(false);
        headerGap.setPreferredSize(new Dimension(1, HEADER_GAP));

        sqlHost.add(linesPanel, BorderLayout.CENTER);
        linesPanel.add(sqlLinePool.get(0));

        content.add(headerLine, BorderLayout.NORTH);
        content.add(headerGap, BorderLayout.CENTER);
        content.add(sqlHost, BorderLayout.SOUTH);

        // 色条挂 WEST；内容区占满其余（SQL 拿整行宽度，不再有固定宽侧栏）
        add(colorBar, BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        setBorder(normalBorder);
    }

    /** 新建 SQL 行组件（池扩充用；不透明性与既有行一致）。 */
    private static SimpleColoredComponent newLineComponent() {
        SimpleColoredComponent comp = new SimpleColoredComponent();
        comp.setOpaque(false);
        return comp;
    }

    /** 色条构建（preferredSize 恒 1px 高——高度由 EAST/WEST 布局拉满，宽度占位）。 */
    private static TypeColorBar createColorBar() {
        TypeColorBar bar = new TypeColorBar();
        bar.setPreferredSize(new Dimension(SqlListTypeColor.BAR_WIDTH, 1));
        bar.setOpaque(false);
        return bar;
    }

    @Override
    public Component getListCellRendererComponent(
            JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
    ) {
        LogSqlEntry entry = value instanceof LogSqlEntry ? (LogSqlEntry) value : null;

        // T-CrudColor：标记形态分派（读设置 + 纯函数；热路径零分配）
        Color tint = entry != null ? SqlListTypeColor.colorOf(entry.statementType()) : null;
        boolean useSelectionFill = isSelected;
        // 留白按模式分流：单行模式密度优先（见 normalBorder javadoc）
        SqlListDisplayMode mode = PluginSettings.getInstance().sqlListDisplayMode();
        Border activeBorder = mode == SqlListDisplayMode.SINGLE_LINE ? compactBorder : normalBorder;
        SqlLogPanelKt.applyCrudColorMark(
                this,
                colorBar,
                activeBorder,
                tint,
                isSelected,
                list,
                entry != null && SqlListTypeColor.isDdl(entry.statementType()));

        Color fg = useSelectionFill
                ? (list != null && list.getSelectionForeground() != null ? list.getSelectionForeground() : JBColor.foreground())
                : (list != null ? list.getForeground() : JBColor.foreground());
        List<List<SqlTextFragment>> lineFragments = null;
        if (entry != null) {
            bindHeaderLine(entry, isSelected, fg, useSelectionFill, list);
            bindErrorLine(entry, isSelected, fg, useSelectionFill, list);
            lineFragments = sqlLineFragmentsFor(entry, mode);
        }

        // SQL 区宽度 = 列表可视宽 - 单元格内边距 - 色条（不再有侧栏要扣）
        int sqlWidth = sqlAreaWidth(list, this);
        ensureSqlLineCount(lineFragments != null ? lineFragments.size() : 1);
        for (int i = 0; i < sqlLinePool.size(); i++) {
            SimpleColoredComponent comp = sqlLinePool.get(i);
            comp.setForeground(fg);
            comp.clear();
            if (lineFragments != null && i < lineFragments.size()) {
                for (SqlTextFragment f : clipSqlFragmentsToWidth(lineFragments.get(i), list, sqlWidth)) {
                    comp.append(f.text(), isSelected
                            ? SqlEntryPresentation.selectedAttributes(f.attributes(), fg) : f.attributes());
                }
            }
            if (list != null) {
                SpeedSearchUtil.applySpeedSearchHighlighting(list, comp, false, useSelectionFill);
            }
        }
        // T-ClickPreview：不设 toolTipText（悬停逐行弹气泡 = 条目多时卡顿根因），预览改单击触发
        return this;
    }

    /** 去重计数快照（仅日志页有去重；历史页恒空）。 */
    protected Map<String, Integer> dedupeCountsSnapshot() {
        return Map.of();
    }

    /**
     * 头部行绑定：全部元信息片段，按用户定案的「库前置」顺序。
     *
     * <p>顺序：{@code #序号 · 时间 · 耗时 · [库] · [MCP] · 类名:行号 · (影响行数) · ⨉去重 · 同结构 N 条}</p>
     *
     * <p>为什么库在类名之前（用户 2026-10-26 定案方案④）：</p>
     * <ul>
     *  <li>库角标是<b>定长短串</b>（{@code [MySQL]} / {@code [PostgreSQL]}），位置固定，扫视时形成稳定锚点；
     *      类名长度随内容浮动，放它之后，宽度不足时先裁它不破坏前几列的稳定性。</li>
     *  <li>多数据源场景下「这条打的是哪个库」是判定 SQL 是否走对源的第一判据。</li>
     * </ul>
     */
    private void bindHeaderLine(
            LogSqlEntry entry,
            boolean isSelected,
            Color fg,
            boolean useSelectionFill,
            JList<?> list
    ) {
        // 券01（T-PerfSweep27-01）：静态段/来源段按条目缓存，键含全部相关设置维度（键不等即重算，
        // 显示格式严格跟随设置）；⨉去重与×N 同结构徽章是会话级可变输入，恒每次算。
        HeaderStaticCacheKey cacheKey = SqlEntryPresentation.headerStaticCacheKey(showDuration());
        if (!java.util.Objects.equals(entry.cachedHeaderKey(), cacheKey) || entry.cachedHeaderCore() == null) {
            entry.setCachedHeaderCore(SqlEntryPresentation.headerStaticFragments(entry, showDuration()));
            entry.setCachedHeaderSource(SqlEntryPresentation.headerSourceFragments(entry));
            entry.setCachedHeaderKey(cacheKey);
        }
        List<SqlTextFragment> fragments = new ArrayList<>(
                entry.cachedHeaderCore() != null ? entry.cachedHeaderCore() : List.of());
        fragments.addAll(SqlEntryPresentation.headerBadgeFragments(
                entry,
                templateCountsSnapshot(),
                dedupeCountsSnapshot()));
        fragments.addAll(entry.cachedHeaderSource() != null ? entry.cachedHeaderSource() : List.of());
        int width = sqlAreaWidth(list, this);
        headerLine.setForeground(fg);
        headerLine.clear();
        for (SqlTextFragment fragment : clipSqlFragmentsToWidth(fragments, list, width)) {
            headerLine.append(fragment.text(), isSelected
                    ? SqlEntryPresentation.selectedAttributes(fragment.attributes(), fg) : fragment.attributes());
        }
        if (list != null) {
            SpeedSearchUtil.applySpeedSearchHighlighting(list, headerLine, false, useSelectionFill);
        }
    }

    /**
     * 错误行绑定（M56 T-M56-05 语义保留）。
     *
     * <p>报错文本远比头部行其他片段长（{@code ⛔ ERROR: relation "user" does not exist} 量级），
     * 混进头部行会把其余元信息全挤掉——故独立成行，只有 errorText 存在时才占高度。</p>
     */
    private void bindErrorLine(
            LogSqlEntry entry,
            boolean isSelected,
            Color fg,
            boolean useSelectionFill,
            JList<?> list
    ) {
        kotlin.Pair<String, SimpleTextAttributes> fragment = SqlLogPanelKt.formatErrorFragment(entry);
        errorLine.setForeground(fg);
        errorLine.clear();
        boolean shouldMount = fragment != null;
        if (fragment != null) {
            errorLine.append(
                    fragment.getFirst().stripTrailing(),
                    isSelected ? SqlEntryPresentation.selectedAttributes(fragment.getSecond(), fg) : fragment.getSecond());
        }
        if (errorMounted != shouldMount) {
            errorMounted = shouldMount;
            if (shouldMount) {
                sqlHost.add(errorLine, BorderLayout.SOUTH);
            } else {
                sqlHost.remove(errorLine);
            }
            sqlHost.revalidate();
        }
        if (shouldMount && list != null) {
            SpeedSearchUtil.applySpeedSearchHighlighting(list, errorLine, false, useSelectionFill);
        }
    }

    /** SQL 行组件池伸缩到 n 行（首位恒保留——{@link #getSqlLine()} 测试缝；收缩移除末尾）。 */
    private void ensureSqlLineCount(int n) {
        while (sqlLinePool.size() < n) {
            SimpleColoredComponent comp = newLineComponent();
            sqlLinePool.add(comp);
            linesPanel.add(comp);
        }
        while (sqlLinePool.size() > Math.max(1, n)) {
            linesPanel.remove(sqlLinePool.remove(sqlLinePool.size() - 1));
        }
    }

    /**
     * SQL 行片段（T-ListMode 模式分流）：SINGLE_LINE 直渲染不缓存（固定行高 + 主题切换实时生效）；
     * 多行模式走条目级缓存（键含模式+脱敏态，任一变化即重算）。
     */
    private List<List<SqlTextFragment>> sqlLineFragmentsFor(LogSqlEntry entry, SqlListDisplayMode mode) {
        // R60：展示文本打码、原始条目不动；缓存以模式和脱敏态为键，防切换后复用旧语义片段。
        boolean maskOn = PluginSettings.getInstance().getState().getDisplayMaskEnabled();
        String sql = SqlLogPanelKt.withTerminator(
                SqlListFragmentRenderer.displaySqlForList(entry, mode, maskOn));
        if (mode == SqlListDisplayMode.SINGLE_LINE) {
            List<List<SqlTextFragment>> single = new ArrayList<>();
            single.add(SqlListFragmentRenderer.renderFragments(sql));
            return single;
        }
        List<List<SqlSemanticFragment>> semantics = entry.cachedListLineFragments();
        Boolean cachedMasked = entry.cachedListLineFragmentsMasked();
        if (semantics == null || entry.cachedListLineFragmentsMode() != mode
                || !Boolean.valueOf(maskOn).equals(cachedMasked)) {
            semantics = SqlListFragmentRenderer.semanticLineFragments(sql, mode);
            entry.setCachedListLineFragments(semantics);
            entry.setCachedListLineFragmentsMode(mode);
            entry.setCachedListLineFragmentsMasked(maskOn);
        }
        return SqlEntryPresentation.visibleSqlLines(SqlListFragmentRenderer.projectLineFragments(semantics), mode);
    }

    /** 条目上下留白（px）——分条主力手段。改造前是 5，实测条目内行距≈条目间距导致混在一起。 */
    public static final int ROW_VERTICAL_PADDING = 12;

    /** 单行模式的紧凑留白（px）——密度优先，不能吃满 12px。 */
    public static final int ROW_VERTICAL_PADDING_DENSE = 8;

    /** 头部行与 SQL 首行之间的额外呼吸（px）。 */
    public static final int HEADER_GAP = 3;

    /** 条目分隔线——比 {@code JBColor.border()} 略强，深色主题下才可见。 */
    public static final Color ROW_DIVIDER =
            JBColor.namedColor("mpvs.log.rowDivider", new JBColor(0xC8C8C8, 0x4A4D52));

    /** 按留白高度构造条目 Border（预建实例，不在渲染热路径调用）。 */
    public static Border borderFor(int verticalPadding) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, ROW_DIVIDER),
                BorderFactory.createEmptyBorder(verticalPadding, 5, verticalPadding, 9));
    }

    /**
     * SQL 区可用像素宽度。
     *
     * <p>改造后不再有固定宽侧栏，只扣单元格内边距与色条。</p>
     */
    public static int sqlAreaWidth(JList<?> list, JPanel cell) {
        if (list == null || list.getWidth() <= 0) return 0;
        return list.getWidth() - cell.getInsets().left - cell.getInsets().right
                - SqlListTypeColor.BAR_WIDTH - list.getInsets().left - list.getInsets().right;
    }

    /** 按给定像素宽度裁切片段（宽度已由调用方算好时的入口）。 */
    public static List<SqlTextFragment> clipSqlFragmentsToWidth(
            List<SqlTextFragment> fragments,
            JList<?> list,
            int maxWidth
    ) {
        if (list == null || list.getFont() == null) return fragments;
        Font baseFont = list.getFont();
        Map<Integer, FontMetrics> metrics = new HashMap<>(3);
        return SqlEntryPresentation.clipSqlFragments(fragments, maxWidth, (text, attributes) -> {
            int style = ((attributes.getStyle() & SimpleTextAttributes.STYLE_BOLD) != 0 ? Font.BOLD : 0)
                    | ((attributes.getStyle() & SimpleTextAttributes.STYLE_ITALIC) != 0 ? Font.ITALIC : 0);
            return metrics.computeIfAbsent(style, key -> list.getFontMetrics(baseFont.deriveFont(key))).stringWidth(text);
        });
    }
}
