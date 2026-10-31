package cn.song.mybatisplus.visualizer.log.agent;

import cn.song.mybatisplus.visualizer.log.CaptureProvenance;
import cn.song.mybatisplus.visualizer.log.LogSqlEntry;
import cn.song.mybatisplus.visualizer.log.LogSqlParam;
import cn.song.mybatisplus.visualizer.log.SqlSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import kotlin.Triple;

/**
 * AgentEvent → LogSqlEntry 纯函数适配（§4.4，M56 T-M56-02）：
 * 字段映射 + 参数还原 + dedupeKey 一致性——与桥通道同 SQL 产出同指纹，
 * 增强接管/回落切换期两通道并存时去重口径统一（SqlLogEntryBuffer.dedupeEnabled 默认开）。
 * 参数还原：按 {@code ?} 序朴素替换（引号包裹字符串值），数量不匹配时保留原 SQL 不替换（防错位）。
 */
public final class AgentEventAdapter {

    private AgentEventAdapter() {
    }

    /** 摘要截断长度（Kotlin data class summary 默认参数口径，对齐 LogSqlEntry）。 */
    private static final int MAX_SUMMARY_LENGTH = 160;

    /**
     * T-DevAcceptFix2 ⑤（20261006 第二轮验收）：<b>已收事件计数</b>——agent 事件到达并进入适配链的累计。
     *
     * <p>口径：<b>收到即计数</b>（含后续被去重/屏蔽/展示链丢弃的）——它回答的是「事件到没到」，
     * 不是「展示了多少」。面板零产出时：N=0 → 查 agent 侧（织入/门禁/传输）；N&gt;0 → 查消费侧。
     * 此前两条通道在停采态不打点、收事件也不打点，导致现场无法分流（沙盒日志实证）。
     */
    private static final java.util.concurrent.atomic.AtomicLong receivedCount =
        new java.util.concurrent.atomic.AtomicLong();

    /** 事件到达打点（{@link AgentManager} 适配前调用）。 */
    public static void markReceived() {
        receivedCount.incrementAndGet();
        // T-CaptureCatchAll 券02：事件恢复 → 控制台桥活性回落立即解除（重新让位）。
        // 放在打点同一处：任何「事件真的到了」的路径都必然经过这里，漏挂即滞回失效。
        cn.song.mybatisplus.visualizer.log.CaptureActivityFallback.onAgentEvent();
    }

    /** 已收事件数（诊断行取数；测试可读）。 */
    public static long receivedEvents() {
        return receivedCount.get();
    }

    public static LogSqlEntry toEntry(AgentEvent event) {
        List<Triple<Integer, String, String>> sorted = new ArrayList<>(event.params());
        sorted.sort(Comparator.comparingInt(Triple::getFirst));
        List<LogSqlParam> params = new ArrayList<>();
        for (Triple<Integer, String, String> p : sorted) {
            params.add(new LogSqlParam(p.getSecond(), p.getThird()));
        }
        String restored = restore(event.sql(), params);
        return new LogSqlEntry(
            event.sql(),
            restored,
            params,
            event.timestamp(),
            null,
            computeSummary(restored),
            SqlSource.AGENT,
            event.durationNanos() / 1_000_000L,
            event.sourceFile(),
            event.sourceLine(),
            // T-AgentSourceJump：候选帧透传（IDE 侧跳转在主位置解析失败时按序回退）
            event.sourceCandidates(),
            // T-SourceJumpFunnel：候选类名与栈快照透传（选帧弹层/NotFound 提示/详情面板往上追踪）
            event.candidateClasses(),
            event.stackDigest(),
            // T-StackBizFrame S3：线程名透传——调用栈弹层标题据此标注「这条 SQL 在哪个线程上执行」
            event.threadName(),
            0L,
            // T-M56-06：方法聚焦键（agent 栈帧 {Class}#{method}）——与 mapper 语句 id 同入 mapperStatement
            // 字段语义扩展由 focusMethodKey 统一承载（agent JDBC 层无 mapper id，非冲突）
            event.methodKey(),
            event.errorText(),
            event.requestTag(),
            event.updateCount(),
            // R32 弱形态：连接 URL → 目标库标注（方言名 · 库名@host:port url 解析真值；
            // 凭据在 JdbcUrlDialects.targetOf 解析期剔除；未知/畸形 URL 为 null，面板不显示角标）
            JdbcUrlDialects.badgeName(event.url()),
            // T-DialectInferFix：同一条 URL 另存**注册方言名**（渲染命名空间）——日志/历史双击预览
            // 用它当 EXACT 方言证据；与角标显示名分开，避免 HSQL/GBASE 这类无注册方言的名字被
            // byName 静默回落 MySQL。
            cn.song.mybatisplus.visualizer.sql.UrlSubprotocolDialects.dialectName(event.url()),
            // R10 T-ShardingSphereCompat：agent 通道无 Actual SQL 附注，null
            null,
            // 通道归因打标——agent 事件适配产物恒为 AGENT
            CaptureProvenance.AGENT);
    }

    /**
     * 按 {@code ?} 序替换参数为字面量（字符串值加单引号 + 内部引号转义；null → NULL）。
     * 占位符数 != 参数数 → 原样返回（防错位误替换）；跳过引号字面量内的 {@code ?}。
     */
    public static String restore(String sql, List<LogSqlParam> params) {
        if (params.isEmpty()) {
            return sql;
        }
        // 数量守卫：占位符数 != 参数数 → 原样返回（防错位误替换）
        if (countPlaceholders(sql) != params.size()) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length() + 32);
        int pi = 0;
        boolean inSingle = false;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (inSingle) {
                out.append(c);
                if (c == '\'') {
                    // 单引号转义（'' 成对）不终结
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        out.append('\'');
                        i++;
                    } else {
                        inSingle = false;
                    }
                }
                i++;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '?') {
                if (pi < params.size()) {
                    out.append(renderParam(params.get(pi)));
                    pi++;
                } else {
                    out.append(c);
                }
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 占位符计数（跳过引号字面量内的 {@code ?}；与 restore 扫描规则一致）。 */
    private static int countPlaceholders(String sql) {
        int n = 0;
        boolean inSingle = false;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    // 单引号转义（'' 成对）不终结
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inSingle = false;
                    }
                }
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '?') {
                n++;
            }
            i++;
        }
        return n;
    }

    private static String renderParam(LogSqlParam p) {
        if (p.value() == null) {
            return "NULL";
        }
        if (isNumericLike(p.value()) && isNumericType(p.type())) {
            return p.value();
        }
        return "'" + p.value().replace("'", "''") + "'";
    }

    /** T-PS4-04：正则提常量（每参数两处现场编译 → 类级单例；T-PerfSweep F2 惯例）。 */
    private static final java.util.regex.Pattern NUMERIC_TYPE_REGEX =
        java.util.regex.Pattern.compile("(Int|Integer|Long|Short|Byte|Double|Float|BigDecimal|BigInteger|Numeric|Decimal)");

    private static final java.util.regex.Pattern NUMERIC_LIKE_REGEX =
        java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?");

    private static boolean isNumericType(String type) {
        return type != null && NUMERIC_TYPE_REGEX.matcher(type).matches();
    }

    private static boolean isNumericLike(String v) {
        return NUMERIC_LIKE_REGEX.matcher(v).matches();
    }

    /** 摘要默认值（Kotlin data class 默认参数；与 LogSqlEntry 私有 computeSummary 同一口径）。 */
    private static String computeSummary(String restoredSql) {
        int newlineIdx = restoredSql.indexOf('\n');
        int endIdx = newlineIdx >= 0 && newlineIdx < MAX_SUMMARY_LENGTH
            ? newlineIdx
            : Math.min(restoredSql.length(), MAX_SUMMARY_LENGTH);
        if (endIdx < restoredSql.length()) {
            return restoredSql.substring(0, endIdx) + "\u2026";
        }
        return restoredSql;
    }
}
