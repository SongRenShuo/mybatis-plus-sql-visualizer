package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;

/**
 * 极简 JSON 写出（agent 内禁外部依赖）：只需对象/字符串/数字/数组/布尔，
 * 写侧自己构造故无需解析器。转义覆盖引号/反斜杠/控制字符/Unicode 控制区。
 */
public final class Jsons {

    public static String writeEvent(SqlEvent e) {
        final StringBuilder sb = new StringBuilder(256);
        // 首字段标志：第一个成员前不得有逗号（否则 IDE 侧 JSON 解析全量失败——
        // 实证 {,"sql":...} 被丢弃，事件零到达）
        final boolean[] first = {true};
        sb.append('{');
        str(sb, first, "sql", e.sql);
        num(sb, first, "ts", e.timestamp);
        num(sb, first, "dn", e.durationNanos);
        str(sb, first, "runId", e.runId);
        str(sb, first, "executionId", e.executionId);
        if (e.sequence != null) num(sb, first, "sequence", e.sequence);
        if (e.captureEpoch != null) num(sb, first, "captureEpoch", e.captureEpoch);
        if (e.threadId != null) num(sb, first, "threadId", e.threadId);
        if (e.startNanos != null) num(sb, first, "startNanos", e.startNanos);
        str(sb, first, "resultStatus", e.resultStatus);
        str(sb, first, "updateCountStatus", e.updateCountStatus);
        str(sb, first, "captureSource", e.captureSource);
        str(sb, first, "timestampSource", e.timestampSource);
        str(sb, first, "errorClass", e.errorClass);
        str(sb, first, "sqlState", e.sqlState);
        str(sb, first, "requestId", e.requestId);
        str(sb, first, "parentRequestId", e.parentRequestId);
        str(sb, first, "connectionId", e.connectionId);
        str(sb, first, "leaseId", e.leaseId);
        str(sb, first, "transactionId", e.transactionId);
        if (e.updateCount != null) {
            num(sb, first, "u", e.updateCount);
        }
        str(sb, first, "err", e.errorText);
        str(sb, first, "sf", e.sourceFile);
        if (e.sourceLine > 0) {
            num(sb, first, "sl", e.sourceLine);
        }
        // T-AgentSourceJump：源码候选（IDE 侧解析回退；T-SourceJumpFunnel 增厚为
        // [file, line, className, method] 四列——列尾追加，旧 IDE 按下标只读前两列，双向兼容）
        if (e.sourceCandidates != null && !e.sourceCandidates.isEmpty()) {
            sep(sb, first);
            sb.append("\"cands\":[");
            for (int i = 0; i < e.sourceCandidates.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                String[] c = e.sourceCandidates.get(i);
                sb.append('[');
                val(sb, c.length > 0 ? c[0] : null);
                sb.append(',');
                sb.append(c.length > 1 ? c[1] : "0");
                if (c.length > 2) {
                    sb.append(',');
                    val(sb, c[2]);
                    sb.append(',');
                    val(sb, c.length > 3 ? c[3] : null);
                }
                sb.append(']');
            }
            sb.append(']');
        }
        str(sb, first, "mk", e.methodKey);
        str(sb, first, "rt", e.requestTag);
        // T-SourceJumpFunnel P2-2：调用栈快照（「类#方法:行」×≤16 帧，人工往上追踪用）
        str(sb, first, "sd", e.stackDigest);
        str(sb, first, "th", e.threadName);
        str(sb, first, "url", e.url);
        // T-SqlListCatchup 券 F2b：聚合计数（EventBus 队列满时同模板聚合的代表条数）。
        // 仅 >1 时写出——常态事件字节面零变化；旧 IDE（Gson 宽容解析）忽略未知键、
        // 新 IDE 读旧事件（无此键）按 1 计，双向兼容。
        if (e.aggregatedCount > 1) {
            num(sb, first, "c", e.aggregatedCount);
        }
        if (e.params != null && !e.params.isEmpty()) {
            sep(sb, first);
            sb.append("\"p\":[");
            for (int i = 0; i < e.params.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                String[] p = e.params.get(i);
                sb.append('[').append(p[0]).append(',');
                val(sb, p.length > 1 ? p[1] : null);
                sb.append(',');
                val(sb, p.length > 2 ? p[2] : null);
                sb.append(']');
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    /** Flat metadata-only control object; values are primitive facts, never arbitrary toString. */
    public static String writeObject(java.util.Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean[] first = {true};
        for (java.util.Map.Entry<String, Object> field : fields.entrySet()) {
            Object value = field.getValue();
            if (value == null) continue;
            sep(sb, first); val(sb, field.getKey()); sb.append(':');
            if (value instanceof Number || value instanceof Boolean) sb.append(value);
            else if (value instanceof String) val(sb, (String) value);
            else throw new IllegalArgumentException("unsupported control field");
        }
        return sb.append('}').toString();
    }

    /** 成员分隔：首成员无逗号，后续成员补逗号。 */
    private static void sep(StringBuilder sb, boolean[] first) {
        if (first[0]) {
            first[0] = false;
        } else {
            sb.append(',');
        }
    }

    private static void str(StringBuilder sb, boolean[] first, String key, String value) {
        if (value != null) {
            sep(sb, first);
            sb.append('"').append(key).append("\":");
            val(sb, value);
        }
    }

    private static void num(StringBuilder sb, boolean[] first, String key, long value) {
        sep(sb, first);
        sb.append('"').append(key).append("\":").append(value);
    }

    private static void val(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private Jsons() {
    }
}
