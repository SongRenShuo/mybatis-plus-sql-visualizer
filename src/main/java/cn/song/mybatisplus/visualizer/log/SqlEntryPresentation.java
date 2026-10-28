package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpvsUiColors;
import com.intellij.ui.SimpleTextAttributes;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntBiFunction;
import javax.swing.JList;
import javax.swing.JPanel;

/** 两个日志列表共用的元信息、裁剪与选中样式。 */
public final class SqlEntryPresentation {
    private static final SimpleTextAttributes SECONDARY = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY);
    private SqlEntryPresentation() {}

    /**
     * 券01（T-PerfSweep27-01）测试缝：静态段实际计算次数。
     * 先红判据（修复前每次渲染 +1）与反向验证（摘缓存后回红）共用；生产链路无读取方，零行为影响。
     */
    public static int headerCoreComputeCount = 0;

    /** 缓存键组装（券01）：showDuration + 时间戳/角标/慢查阈值全部设置维度。 */
    public static HeaderStaticCacheKey headerStaticCacheKey(boolean showDuration) {
        cn.song.mybatisplus.visualizer.config.PluginSettings.State state =
                cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().getState();
        return new HeaderStaticCacheKey(
                showDuration,
                state.getLogTimestampEnabled(),
                state.getLogTimestampFormat(),
                state.getLogTimestampSource(),
                state.getDataSourceBadgeDetail(),
                state.getSlowQueryThresholdMs());
    }

    /**
     * 头部行片段（T-LogListHeader）：SQL 上方那一行的全部元信息。
     *
     * <p><b>顺序与形态完全对齐 {@code 2026.10.23} 版本</b>（用户 2026-10-26 定案回退）：</p>
     * <pre>#序号 · 时间戳 · 耗时 · [目标库] · [MCP] · (影响行数) · ⨉去重 · 同结构 N 条 · [文件.java:行号]</pre>
     *
     * <p>三个关键点（都是对 {@code 4ba423d3}/{@code f559049e} 期间偏离的纠正）：</p>
     * <ul>
     *  <li><b>来源放最后</b>，不是紧跟库角标；</li>
     *  <li><b>来源保留完整文件名</b>（{@code [UserMapper.java:41]}，带方括号、带 {@code .java}），
     *      不做骨架化压缩——用户明确「类名为什么要省略，还不如 2026.10.23 版本的」；</li>
     *  <li><b>超宽走顺序截断</b>（{@code clipSqlFragments} 从左到右、超出即停、末尾补 {@code …}），
     *      不再按优先级丢片段（退化梯度机制已删除）。</li>
     * </ul>
     *
     * <p>{@code (影响行数)} 只在 DML 上有值；SELECT 不显示（不补空占位——那会让每行多出一段空白）。</p>
     *
     * <p>券01（T-PerfSweep27-01）拆段：本函数保持原签名与输出序列（存量测试零改动），
     * 内部改为 {@link #headerStaticFragments} + {@link #headerBadgeFragments} +
     * {@link #headerSourceFragments} 三段组合——渲染层按此分段做条目级缓存（静态/来源段可缓存、
     * 徽章段每次算）。<b>{@code selected} 参数自此被忽略</b>：片段层恒产出非选中态属性。选中态着色由
     * {@code bindHeaderLine} 的 {@code selectedAttributes(f.attributes, fg)} 无条件覆盖（选中色最终
     * 全部是列表 selectionForeground，片段层选中分支本就不可见），绑定输出逐字节一致。</p>
     */
    public static List<SqlTextFragment> headerFragments(LogSqlEntry entry, boolean selected, boolean showDuration,
            Map<String, Integer> templateCounts) {
        return headerFragments(entry, selected, showDuration, templateCounts, Map.of());
    }

    public static List<SqlTextFragment> headerFragments(LogSqlEntry entry, boolean selected, boolean showDuration,
            Map<String, Integer> templateCounts, Map<String, Integer> dedupeCounts) {
        List<SqlTextFragment> result = new ArrayList<>(headerStaticFragments(entry, showDuration));
        result.addAll(headerBadgeFragments(entry, templateCounts, dedupeCounts));
        result.addAll(headerSourceFragments(entry));
        return result;
    }

    /**
     * 静态段（券01 可缓存部分）：序号/时间/耗时/库/MCP/影响行数。
     * 只依赖条目不可变字段与 {@link HeaderStaticCacheKey} 覆盖的设置维度；恒非选中态。
     */
    public static List<SqlTextFragment> headerStaticFragments(LogSqlEntry entry, boolean showDuration) {
        headerCoreComputeCount++;
        List<SqlTextFragment> result = new ArrayList<>();
        if (entry.sequenceNumber() > 0) result.add(new SqlTextFragment("#" + entry.sequenceNumber() + " ", SECONDARY));
        add(result, SqlLogPanelKt.formatTimestampFragment(entry));
        if (showDuration) add(result, SqlLogPanelKt.formatDurationFragment(entry.executionTimeMs(), false));
        add(result, SqlLogPanelKt.formatDatabaseFragment(entry, false));
        add(result, SqlLogPanelKt.formatMcpFragment(entry, false));
        add(result, SqlLogPanelKt.formatUpdateCountFragment(entry, false));
        return result;
    }

    /**
     * 动态徽章段（券01 每次算，不可缓存）：⨉去重 + ×N 同结构。
     * 两个徽章的输入是面板级快照 map，随流式灌入每拍变化——进缓存会吞更新。
     */
    public static List<SqlTextFragment> headerBadgeFragments(LogSqlEntry entry, Map<String, Integer> templateCounts) {
        return headerBadgeFragments(entry, templateCounts, Map.of());
    }

    public static List<SqlTextFragment> headerBadgeFragments(LogSqlEntry entry, Map<String, Integer> templateCounts,
            Map<String, Integer> dedupeCounts) {
        List<SqlTextFragment> result = new ArrayList<>();
        // UI013：同键的其他来源可能有丢弃计数，不能把徽章借给未参与去重的 Sharding 执行。
        Integer count = entry.source() == SqlSource.SHARDINGSPHERE
                ? null : dedupeCounts.get(entry.dedupeKey());
        if (count != null && count > 0) result.add(new SqlTextFragment("⨉" + count + " ", SECONDARY));
        kotlin.Pair<String, SimpleTextAttributes> template =
                SqlLogPanelKt.formatTemplateCountFragment(entry, templateCounts, false);
        if (template != null) result.add(new SqlTextFragment(template.getFirst(), template.getSecond()));
        return result;
    }

    /** 来源段（券01 可缓存部分）：`[文件.java:行号]`，无源码位置为空列表。恒非选中态。 */
    public static List<SqlTextFragment> headerSourceFragments(LogSqlEntry entry) {
        kotlin.Pair<String, SimpleTextAttributes> source = SqlLogPanelKt.formatSourceFragment(entry, false);
        return source != null ? List.of(new SqlTextFragment(source.getFirst(), source.getSecond())) : List.of();
    }

    /**
     * 通栏注脚片段（历史遗留：{@code bed58b64} 把角标/徽章放在 SQL 下方通栏）。
     *
     * <p>T-LogListHeader 起<b>渲染器不再使用本方法</b>——注脚行已合并进 {@link #headerFragments}。
     * 保留是因为存量测试仍直接断言它（{@code SqlEntryPresentationTest}），且它是
     * 「哪些片段属于诊断类」的口径文档。新代码请用 {@link #headerFragments}。</p>
     */
    public static List<SqlTextFragment> details(LogSqlEntry entry, boolean selected,
            Map<String, Integer> templateCounts) {
        return details(entry, selected, templateCounts, Map.of(), false);
    }

    public static List<SqlTextFragment> details(LogSqlEntry entry, boolean selected,
            Map<String, Integer> templateCounts, Map<String, Integer> dedupeCounts) {
        return details(entry, selected, templateCounts, dedupeCounts, false);
    }

    public static List<SqlTextFragment> details(LogSqlEntry entry, boolean selected,
            Map<String, Integer> templateCounts, Map<String, Integer> dedupeCounts, boolean includeError) {
        List<SqlTextFragment> result = new ArrayList<>();
        add(result, SqlLogPanelKt.formatDatabaseFragment(entry, selected));
        add(result, SqlLogPanelKt.formatMcpFragment(entry, selected));
        add(result, SqlLogPanelKt.formatUpdateCountFragment(entry, selected));
        if (includeError) add(result, SqlLogPanelKt.formatErrorFragment(entry));
        Integer count = entry.source() == SqlSource.SHARDINGSPHERE
                ? null : dedupeCounts.get(entry.dedupeKey());
        if (count != null && count > 0) result.add(new SqlTextFragment("⨉" + count + " ", SECONDARY));
        add(result, SqlLogPanelKt.formatTemplateCountFragment(entry, templateCounts, selected));
        return result;
    }

    public static List<SqlTextFragment> metadata(LogSqlEntry entry, boolean selected, boolean showDuration,
            Map<String, Integer> templateCounts) {
        return metadata(entry, selected, showDuration, templateCounts, Map.of(), false);
    }

    public static List<SqlTextFragment> metadata(LogSqlEntry entry, boolean selected, boolean showDuration,
            Map<String, Integer> templateCounts, Map<String, Integer> dedupeCounts, boolean showLogDetails) {
        List<SqlTextFragment> result = new ArrayList<>();
        if (entry.sequenceNumber() > 0) result.add(new SqlTextFragment("#" + entry.sequenceNumber() + " ", SECONDARY));
        add(result, SqlLogPanelKt.formatTimestampFragment(entry));
        if (showDuration) add(result, SqlLogPanelKt.formatDurationFragment(entry.executionTimeMs(), selected));
        add(result, SqlLogPanelKt.formatDatabaseFragment(entry, selected));
        add(result, SqlLogPanelKt.formatMcpFragment(entry, selected));
        if (showLogDetails) {
            add(result, SqlLogPanelKt.formatUpdateCountFragment(entry, selected));
            add(result, SqlLogPanelKt.formatErrorFragment(entry));
            // UI013：同键的其他来源可能有丢弃计数，不能把徽章借给未参与去重的 Sharding 执行。
            Integer count = entry.source() == SqlSource.SHARDINGSPHERE
                    ? null : dedupeCounts.get(entry.dedupeKey());
            if (count != null && count > 0) result.add(new SqlTextFragment("⨉" + count + " ", SECONDARY));
        }
        add(result, SqlLogPanelKt.formatTemplateCountFragment(entry, templateCounts, selected));
        add(result, SqlLogPanelKt.formatSourceFragment(entry, selected));
        return result;
    }

    private static void add(List<SqlTextFragment> target, kotlin.Pair<String, SimpleTextAttributes> fragment) {
        if (fragment != null) target.add(new SqlTextFragment(fragment.getFirst(), fragment.getSecond()));
    }

    /**
     * 可见行裁剪（T-LogListRail 改判：关键字换行从「前 2 行 + 整行占位符」改为「3 行实内容」）。
     *
     * <p>原实现超 3 行时取前 2 行、第三行整行放 {@code … (+N)}，等于<b>三行只读到两个子句</b>。用户实测反馈
     * 这种形态下 {@code WHERE} 之后的子句全部不可见，而 {@code WHERE} 恰是排查时最需要看的部分。</p>
     *
     * <p>现形态：三个子句全部渲染，剩余条数作为<b>末行行尾</b>的小尾巴（{@code … (+N)}），
     * 信息量从「2 个子句」提到「3 个子句」，且不增加行高。</p>
     *
     * <p>FORMATTED 不裁剪（格式化模式按用户要求不封顶——SQL 区能高多少就高多少）。</p>
     */
    public static List<List<SqlTextFragment>> visibleSqlLines(List<List<SqlTextFragment>> lines, SqlListDisplayMode mode) {
        if (mode != SqlListDisplayMode.KEYWORD_WRAP || lines.size() <= MAX_KEYWORD_WRAP_LINES) return lines;
        int hidden = lines.size() - MAX_KEYWORD_WRAP_LINES;
        SqlTextFragment tail = new SqlTextFragment(" … (+" + hidden + ")", KEYWORD_WRAP_TAIL_ATTRIBUTES);
        List<SqlTextFragment> lastVisible = new ArrayList<>(lines.get(MAX_KEYWORD_WRAP_LINES - 1));
        lastVisible.add(tail);
        List<List<SqlTextFragment>> visible = new ArrayList<>(lines.subList(0, MAX_KEYWORD_WRAP_LINES - 1));
        visible.add(lastVisible);
        return visible;
    }

    /** 关键字换行的可见行数上限（三个子句：SELECT / FROM / WHERE 量级）。 */
    public static final int MAX_KEYWORD_WRAP_LINES = 3;

    /** 末行小尾巴的着色（灰、不抢 SQL 语法高亮）。 */
    private static final SimpleTextAttributes KEYWORD_WRAP_TAIL_ATTRIBUTES =
            new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY);

    public static SimpleTextAttributes selectedAttributes(SimpleTextAttributes attributes, Color foreground) {
        return new SimpleTextAttributes(attributes.getStyle(), foreground);
    }

    public static List<SqlTextFragment> clipForList(List<SqlTextFragment> fragments, JList<?> list,
            JPanel cell, int colorBarWidth) {
        if (list == null || list.getWidth() <= 0 || list.getFont() == null) return fragments;
        int width = list.getWidth() - cell.getInsets().left - cell.getInsets().right - colorBarWidth
                - list.getInsets().left - list.getInsets().right;
        Font base = list.getFont();
        Map<Integer, FontMetrics> metrics = new HashMap<>(3);
        return clipSqlFragments(fragments, width, (text, attributes) -> {
            int style = ((attributes.getStyle() & SimpleTextAttributes.STYLE_BOLD) != 0 ? Font.BOLD : 0)
                    | ((attributes.getStyle() & SimpleTextAttributes.STYLE_ITALIC) != 0 ? Font.ITALIC : 0);
            return metrics.computeIfAbsent(style, key -> list.getFontMetrics(base.deriveFont(key))).stringWidth(text);
        });
    }

    public static List<SqlTextFragment> clipSqlFragments(List<SqlTextFragment> fragments, int maxWidth,
            ToIntBiFunction<String, SimpleTextAttributes> measure) {
        if (maxWidth <= 0 || fragments.isEmpty()) return fragments;
        int total = 0;
        for (SqlTextFragment fragment : fragments) total += measure.applyAsInt(fragment.text(), fragment.attributes());
        if (total <= maxWidth) return fragments;
        String ellipsis = "…";
        List<SqlTextFragment> clipped = new ArrayList<>(fragments.size());
        int used = 0;
        for (SqlTextFragment fragment : fragments) {
            int available = maxWidth - used - measure.applyAsInt(ellipsis, fragment.attributes());
            if (available <= 0) break;
            String text = fragment.text();
            int measured = measure.applyAsInt(text, fragment.attributes());
            if (measured <= available) {
                clipped.add(fragment);
                used += measured;
                continue;
            }
            int low = 0;
            int high = text.codePointCount(0, text.length());
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                String prefix = text.substring(0, text.offsetByCodePoints(0, mid));
                if (measure.applyAsInt(prefix, fragment.attributes()) <= available) low = mid;
                else high = mid - 1;
            }
            if (low > 0) {
                String prefix = text.substring(0, text.offsetByCodePoints(0, low));
                clipped.add(new SqlTextFragment(prefix, fragment.attributes()));
                used += measure.applyAsInt(prefix, fragment.attributes());
            }
            break;
        }
        SimpleTextAttributes attrs = clipped.isEmpty() ? fragments.get(0).attributes() : clipped.get(clipped.size() - 1).attributes();
        if (used + measure.applyAsInt(ellipsis, attrs) <= maxWidth) clipped.add(new SqlTextFragment(ellipsis, attrs));
        return clipped;
    }
}
