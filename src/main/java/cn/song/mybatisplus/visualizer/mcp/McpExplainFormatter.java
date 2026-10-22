// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpExplainFormatter.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.execute.ErrorPositionExtractor;
import cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

/**
 * T-McpToolset EXPLAIN 计划文本格式化（纯逻辑层，无 IDE 类型）。
 *
 * 输入为已读取的行列数据（适配层把 DBN RemoteResultSet 读成纯数据后再进本层），
 * 行数/单元格字符预算参照 DBX MCP 的上下文防撑爆口径（调研报告 §8：MAX_ALLOWED_ROWS /
 * 单元格 200 字符上限 4000）。
 */
public final class McpExplainFormatter {

    private McpExplainFormatter() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final McpExplainFormatter INSTANCE = new McpExplainFormatter();

    /** 返回行数默认预算（EXPLAIN 计划行通常 &lt;100；超预算按截断处理）。 */
    public static final int DEFAULT_MAX_ROWS = 200;

    /** 返回行数硬上限（T-McpRowBudget / O-36）：调用方给的 maxRows 再大也钳到此值，
     *  对齐 DBX {@code MAX_EXECUTE_QUERY_ROWS = 1000}（竞品第八轮报告 §8 #37）。 */
    public static final int HARD_MAX_ROWS = 1000;

    /** 单元格字符预算。 */
    public static final int MAX_CELL_CHARS = 2000;

    /**
     * 已读取的查询结果（适配层采集；{@code truncated}=采集时已到行数预算上限）。
     * {@code notices}=执行后取尽的驱动 NOTICE/WARNING（T-NoticeChannel；默认空清单，
     * 空 notices 的输出与无此字段时逐字节一致——防回归硬门禁）。
     */
    public record RawResult(
        /** 结果列头（列标签清单）。 */
        List<String> headers,
        /** 行数据（每行为与 headers 对齐的单元格文本）。 */
        List<List<String>> rows,
        /** 采集时是否已到行数预算上限（true = 实际结果更多）。 */
        boolean truncated,
        /** 执行后取尽的驱动 NOTICE/WARNING 文本（T-NoticeChannel；无则空清单）。 */
        List<String> notices
    ) {
        /** Kotlin 默认参数等价重载：notices 缺省空清单。 */
        public RawResult(List<String> headers, List<List<String>> rows, boolean truncated) {
            this(headers, rows, truncated, List.of());
        }
    }

    /**
     * notices 节渲染（T-NoticeChannel / O-16）：驱动 NOTICE/WARNING 文本每条一行。
     * 空清单返回空串——拼接进工具输出时零追加，保证「空 notices 输出逐字节与现状一致」。
     */
    public static String formatNotices(List<String> notices) {
        if (notices.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("\n\nnotices:");
        for (String notice : notices) {
            out.append("\n- ").append(flatNotice(notice));
        }
        return out.toString();
    }

    /** 单条 notice 压成一行（节内每条一行的契约；换行不丢词只降形态）。 */
    private static String flatNotice(String value) {
        return value.replace("\r\n", "\n").replace('\n', ' ').replace('\r', ' ').trim();
    }

    /**
     * maxRows 限幅（T-McpRowBudget / T-MRB-02）：null/非正 → 默认 200；超硬上限 → 1000；其余原样。
     * 形态与语义照 {@link McpSchemaFormatter#clampMaxTables(Integer)}（越界<b>钳制不抛错</b>——工具面报错
     * 会打断 agent 流程，钳制只是把预算收进合法区间）。
     */
    public static int clampMaxRows(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_MAX_ROWS;
        }
        if (requested > HARD_MAX_ROWS) {
            return HARD_MAX_ROWS;
        }
        return requested;
    }

    /**
     * 计划文本：列头 + 行 + 截断提示（截断标记来自 {@link RawResult#truncated()}，行数已由适配层按
     * 生效预算采集裁剪）。
     *
     * <p>{@code maxRows}=本次生效的行预算，只影响截断提示里的数字；默认 {@link #DEFAULT_MAX_ROWS}——
     * 不传参时输出与 T-McpRowBudget 之前逐字节一致（防回归硬门禁，用例钉住该字面量）。</p>
     */
    public static String format(RawResult raw, int maxRows) {
        StringBuilder out = new StringBuilder();
        if (!raw.headers().isEmpty()) {
            out.append(String.join(" | ", raw.headers().stream().map(String::trim).toList())).append('\n');
        }
        for (List<String> row : raw.rows()) {
            StringBuilder cells = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    cells.append(" | ");
                }
                cells.append(cell(row.get(i)));
            }
            out.append(cells).append('\n');
        }
        if (raw.truncated()) {
            out.append("\n[TRUNCATED] output limited to ").append(maxRows)
                .append(" rows; the actual plan may have more.");
        }
        return trimEnd(out.toString());
    }

    /** Kotlin 默认参数等价重载：maxRows 缺省 {@link #DEFAULT_MAX_ROWS}。 */
    public static String format(RawResult raw) {
        return format(raw, DEFAULT_MAX_ROWS);
    }

    private static String cell(String value) {
        String flat = value.replace("\r\n", "\n").trim();
        return flat.length() > MAX_CELL_CHARS ? flat.substring(0, MAX_CELL_CHARS) + "…" : flat;
    }

    /** Kotlin trimEnd() 等价：剥除尾部空白（含 Unicode 空白，同 Kotlin isWhitespace 口径）。 */
    static String trimEnd(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * T-Round10FU（独立复核 ⑨）：{@code sqlv_explain} 失败消息里的 {@code errorPosition} 文本行。
     *
     * <p>驱动错误文本携带位置信号（PG {@code Position: N} / MySQL {@code at line N} / Oracle 两种形态）时附一行
     * {@code errorPosition: char N}（字符位置）或 {@code errorPosition: line N}（行号），供外部 agent 定位再自修复；
     * <b>无信号（或 marker 不唯一而放弃归属）返回空串</b>——空串拼接即零追加，维持诚实降级。</p>
     *
     * <p>抽出为纯函数的理由（⑨ 的症结）：这段文案原先内联在 {@code SqlVisualizerExplainTool.executeExplain}
     * 的 {@code getOrElse} 里，而该分支要真实数据源 + 项目上下文才可达，于是全仓<b>没有任何用例断言过这段文案形态</b>
     * （{@code McpToolGateTest} 只实例化工具类）——验收里那句「mcp 相关既有用例全绿」因此是空绿。
     * 本函数落在纯逻辑层（同 {@link #formatNotices}：不 import 任何 mcpServer/com.intellij 类型），
     * 文案形态由 {@code McpErrorPositionTextTest} 逐字钉住；<b>接线</b>（工具确实走本函数）另由该用例的
     * 源码扫描断言钉住——只钉文案不钉接线的话，把内联文案改回来就又回到空绿。</p>
     *
     * @param position {@link cn.song.mybatisplus.visualizer.execute.ErrorPositionExtractor#extractPosition(String, Integer)}
     *                 的返回值：1-based 值 / 是否字符位置（false=行号）/ 来源标签；null = 无位置信号
     */
    public static String formatErrorPosition(kotlin.Triple<Integer, Boolean, String> position) {
        if (position == null) {
            return "";
        }
        return "\nerrorPosition: " + (position.getSecond() ? "char" : "line") + " " + position.getFirst();
    }

    /** T-M19-McpErrorMeta：先拆反射包装，再保留驱动状态码、厂商码与原有位置文案；缺失字段不追加。 */
    public static String formatFailure(Throwable error, String dialectName, String explainSql) {
        Throwable cause = error instanceof InvocationTargetException ite ? ite.getTargetException() : error;
        String causeMsg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        ExecutionErrorInfo info = ErrorPositionExtractor.buildInfo(cause, causeMsg, explainSql.length());
        kotlin.Triple<Integer, Boolean, String> pos = ErrorPositionExtractor.extractPosition(causeMsg, explainSql.length());
        String firstLine = firstNonBlankLine(causeMsg);
        StringBuilder sb = new StringBuilder("EXPLAIN failed on '").append(dialectName)
                .append("' data source: ").append(firstLine);
        if (info.sqlState() != null) {
            sb.append("\nsqlState: ").append(info.sqlState());
        }
        if (info.errorCode() != null) {
            sb.append("\nerrorCode: ").append(info.errorCode());
        }
        sb.append(formatErrorPosition(pos));
        return McpToolErrors.code(McpToolErrors.DATASOURCE_UNREACHABLE, sb.toString());
    }

    /** Kotlin {@code lineSequence().firstOrNull { it.isNotBlank() } ?: causeMsg} 等价。 */
    private static String firstNonBlankLine(String message) {
        for (String line : message.split("\\R")) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return message;
    }
}
