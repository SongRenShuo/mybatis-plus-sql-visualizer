// T-SyncK2JMaster482 波D1 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelText.kt
// （T-M53-II 2026-10-10：由 SqlPreviewPanel.kt 原样搬出的文本排版/截断工具函数，同包同可见性，零行为改动）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.sql.interceptor.PaginationSimulator;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import org.jetbrains.annotations.Nullable;

import java.util.function.ToIntFunction;

/**
 * {@code SqlPreviewPanelText.kt} 顶层声明（文本排版/截断工具函数）的 Java 承载类。
 *
 * <p>这些函数在 master 侧位于 {@code SqlPreviewPanelText.kt} 顶层（{@code internal}），无面板组件依赖，
 * 抽出即为了可脱 {@code FontMetrics} 单测。Kotlin 顶层声明编译落点为 {@code <文件名>Kt}，故类名为
 * {@code SqlPreviewPanelTextKt}。</p>
 */
public final class SqlPreviewPanelTextKt {

    private SqlPreviewPanelTextKt() {
    }

    /**
     * T-SourceJumpFunnel P2-2：把调用栈快照 {@code digest} 组装成 meta 行内联摘要（截断展示）；
     * {@code digest} 为 null 时空串。
     *
     * <p>T-StackHoverUi 改造：原「两段文案」（内联摘要 + tooltip 全文节点）收窄为单段——完整链不再进
     * tooltip（旧实现截断摘要+全文重复拼接、超长单行折成文本墙），改由 {@link StackFrameHover} 悬停弹层
     * 承接。抽出为顶层 internal 纯函数仅为让 §3.3 验收项（截断长度/截断标记）可直接单测断言。</p>
     */
    public static String buildStackDigestInlineLine(@Nullable String digest) {
        if (digest == null) return "";
        String label = MpSqlVisualizerBundle.message("panel.meta.stackDigest");
        // T-StackBizFrame S3：先取景（头部全是 agent/驱动/框架帧时跳到首个项目帧），再按原契约截断。
        // 首个非内部帧本来就在开头时取景原样返回，故既有「前 120 字符 + …」行为逐字不变。
        String excerpt = StackDigestFrames.projectExcerpt(digest);
        String summary =
                excerpt.length() > SqlPreviewPanelKt.STACK_DIGEST_MAX_CHARS
                        ? excerpt.substring(0, SqlPreviewPanelKt.STACK_DIGEST_MAX_CHARS)
                                + SqlPreviewPanelKt.STACK_DIGEST_ELLIPSIS
                        : excerpt;
        return "   " + label + " " + summary;
    }

    // ------------------------------------------------------------------
    // T-SourceJumpFunnel P5 复核修正：meta 行宽度预算组装（纯逻辑，脱 FontMetrics 可直测）
    // ------------------------------------------------------------------

    /**
     * 调用栈摘要在 meta 行里至少要拿到的宽度（px）：约等于标签「   调用栈:」自身宽度 @13pt，
     * 低于此值就整段不放摘要——宁可让主体独占预算，也不留一个看不出是什么的孤零零省略号。
     */
    public static final int META_DIGEST_MIN_PX = 56;

    /**
     * T-M26.3 中段省略的纯函数形态（{@link SqlPreviewPanel#ellipsize} 逐字同源）：保留尾部 30%，
     * 超宽时显示 `头…尾`。{@code widthOf} 是文本像素宽度度量——注入后本函数与
     * {@link #metaLineWithinBudget} 都能脱 FontMetrics 单测（测试用「1 字符 = 1px」替身即可）。
     *
     * <p>Kotlin {@code (String) -> Int} → Java {@link ToIntFunction}（Kotlin 侧传 lambda 即自动 SAM 转换）。</p>
     */
    public static String ellipsizeMiddle(String text, ToIntFunction<String> widthOf, int maxWidth) {
        String mark = "\u2026";
        if (widthOf.applyAsInt(text) <= maxWidth) return text;
        int tailLen = (int) (text.length() * 0.3);
        tailLen = Math.max(tailLen, 4);
        tailLen = Math.min(tailLen, text.length() - 1);
        String tail = text.substring(text.length() - tailLen);
        int head = text.length() - tailLen;
        while (head > 0 && widthOf.applyAsInt(text.substring(0, head) + mark + tail) > maxWidth) head--;
        return text.substring(0, head) + mark + tail;
    }

    /**
     * 尾部截断（保留头部）——用于调用栈摘要：摘要<b>栈顶在左</b>（{@link #buildStackDigestInlineLine} 的摘要
     * 自栈顶起截），尾部是信息量最低的栈底帧，故超宽时截尾，而非 {@link #ellipsizeMiddle} 的中段省略。
     */
    public static String clipTailToWidth(String text, ToIntFunction<String> widthOf, int maxWidth) {
        if (widthOf.applyAsInt(text) <= maxWidth) return text;
        String mark = "\u2026";
        int end = text.length();
        while (end > 0 && widthOf.applyAsInt(text.substring(0, end) + mark) > maxWidth) end--;
        return end <= 0 ? "" : text.substring(0, end) + mark;
    }

    /**
     * T-SourceJumpFunnel P5 复核修正：meta 行在宽度预算内的组装——<b>主体优先，摘要吃剩余宽度</b>。
     *
     * <p>P5 把调用栈摘要并入可见行后，若仍把「主体 + 摘要」当<b>单段文本</b>交给 {@link #ellipsizeMiddle}
     * （保留尾部 30%），窄窗下主体会被整段挤掉、可见行只剩「…」+ 栈底帧——主体（表名 / wrapper 类型）
     * 是这一行的主信息，栈底恰是调用链里信息量最低的一端，优先级倒挂。复核（20260920）实测默认宽度
     * 预算仅够 15~25 字符，而摘要上限 120 字符，故必然触发。</p>
     *
     * <p>组装规则：主体自身超预算 → 主体中段省略（F3 原行为）；主体放得下 → 摘要按剩余宽度截尾；
     * 剩余不足 {@link #META_DIGEST_MIN_PX} → 整段丢弃摘要。{@code digest} 为空（无调用栈快照）时逐字
     * 退回 F3 原行为，渲染不变。</p>
     *
     * @param full   完整行（主体 + 摘要）——放得下即整段返回（与改前逐字节一致）
     * @param head   主体（meta + 警示行）
     * @param digest 调用栈内联摘要（`   调用栈: &lt;摘要&gt;`；无快照时为空串）
     */
    public static String metaLineWithinBudget(
            String full,
            String head,
            String digest,
            ToIntFunction<String> widthOf,
            int budget) {
        if (widthOf.applyAsInt(full) <= budget) return full;
        if (digest.isEmpty()) return ellipsizeMiddle(head, widthOf, budget);
        int remain = budget - widthOf.applyAsInt(head);
        if (remain < META_DIGEST_MIN_PX) return ellipsizeMiddle(head, widthOf, budget);
        return head + clipTailToWidth(digest, widthOf, remain);
    }

    /**
     * R12 T-PaginationSim：模型级重渲染后的分页 LIMIT 行回带（20261008 dev-bak 验收缺陷 #1）。
     *
     * <p>面板勾选/参数变更走 XmlPreviewModel.renderSql / SqlGenerator 重建——模型层 SQL 是分页后处理
     * <b>之前</b>的形态（服务端 applyPaginationShared 只作用于整条 PreviewResult），LIMIT 行会在此丢失
     * （真机实测：分页参数行与 COUNT 段都在、主 SQL 尾部无 LIMIT）。服务端把 LIMIT 行存进
     * {@code PreviewResult.paginationLimitLine}，面板重渲染时用本函数原样带回。baseSql 已含 LIMIT
     * （手写语句）或 limitLine 为 null 时原样返回。</p>
     */
    public static String appendPaginationLimitLine(String baseSql, @Nullable String limitLine) {
        if (limitLine == null) return baseSql;
        if (PaginationSimulator.hasExistingLimit(baseSql)) return baseSql;
        return baseSql.stripTrailing() + "\n" + limitLine;
    }
}
