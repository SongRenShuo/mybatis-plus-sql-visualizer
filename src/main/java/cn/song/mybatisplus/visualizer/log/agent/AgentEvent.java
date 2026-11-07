package cn.song.mybatisplus.visualizer.log.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

import kotlin.Pair;
import kotlin.Triple;

/**
 * agent 事件模型 + JSON 解析（IDE 侧；与 agent 侧 util/Jsons.writeEvent 键位契约一一对应）。
 * 全字段可选容错：缺失/形态异常 → null/默认（协议演进向前兼容）。
 */
public record AgentEvent(
    /** SQL 文本（PreparedStatement 为 prepare 时 SQL，Statement 为 execute 参数，批为拼接）。 */
    String sql,
    /** 采集时间戳（毫秒）。 */
    long timestamp,
    /** 精确耗时（纳秒）。 */
    long durationNanos,
    /** 影响行数（查询 null）。 */
    Long updateCount,
    /** 执行报错（类名: 消息；成功 null）。 */
    String errorText,
    /** 调用栈过滤后的源文件（相对路径）。 */
    String sourceFile,
    /** 行号（0 未知）。 */
    int sourceLine,
    /** T-AgentSourceJump：源码位置候选（栈序 [file, line]；旧版 agent 无此键 → 空列表，跳转回退主位置）。 */
    List<Pair<String, Integer>> sourceCandidates,
    /** T-SourceJumpFunnel：候选帧类名（与 sourceCandidates 栈序对齐，可能少于候选数——旧 agent 无类名列）。 */
    List<String> candidateClasses,
    /** 方法聚焦键 {@code {SimpleClass}#{method}}。 */
    String methodKey,
    /** 请求聚合标签。 */
    String requestTag,
    /** T-SourceJumpFunnel P2-2：调用栈快照「类#方法:行 ← …」（栈顶在左；旧版 agent 无此键 → null）。 */
    String stackDigest,
    /** 参数快照 [index, valueText|null, type|null]（agent 侧已过脱敏层）。 */
    List<Triple<Integer, String, String>> params,
    /** 执行线程名。 */
    String threadName,
    /** connect 时连接 URL（T-AgentDialectLoop；旧版 agent 无此键 → null 容错）。 */
    String url,
    /**
     * T-SqlListCatchup 券 F2b：聚合计数——agent 侧事件队列满时同模板（同 sql）事件聚合为一条，
     * 本字段是该条代表的执行次数（agent 心跳之外的事件 JSON {@code c} 键，仅 &gt;1 时写出）。
     * 旧版 agent 无此键 → 1（按单次执行计），协议双向兼容。
     * 口径说明：「已收事件」计数（AgentEventAdapter.markReceived）按帧计 1，不受本字段影响。
     */
    int aggregatedCount
) {

    /** T-AgentDialectLoop：copy(url=…) 的 Java 等价（测试/回填用）。 */
    public AgentEvent withUrl(String newUrl) {
        return new AgentEvent(sql, timestamp, durationNanos, updateCount, errorText, sourceFile, sourceLine,
                sourceCandidates, candidateClasses, methodKey, requestTag, stackDigest, params, threadName,
                newUrl, aggregatedCount);
    }

    /** 便捷构造器：aggregatedCount 缺省 = 1（旧全参形态兼容，Kotlin 默认参数）。 */
    public AgentEvent(String sql, long timestamp, long durationNanos, Long updateCount, String errorText,
                      String sourceFile, int sourceLine, List<Pair<String, Integer>> sourceCandidates,
                      List<String> candidateClasses, String methodKey, String requestTag, String stackDigest,
                      List<Triple<Integer, String, String>> params, String threadName, String url) {
        this(sql, timestamp, durationNanos, updateCount, errorText, sourceFile, sourceLine, sourceCandidates,
                candidateClasses, methodKey, requestTag, stackDigest, params, threadName, url, 1);
    }

    /** 便捷构造器：sourceCandidates/candidateClasses/stackDigest 缺省（Kotlin 默认参数）。 */
    public AgentEvent(String sql, long timestamp, long durationNanos, Long updateCount, String errorText,
                      String sourceFile, int sourceLine, String methodKey, String requestTag,
                      List<Triple<Integer, String, String>> params, String threadName, String url) {
        this(sql, timestamp, durationNanos, updateCount, errorText, sourceFile, sourceLine,
            List.of(), List.of(), methodKey, requestTag, null, params, threadName, url, 1);
    }

    /** 便捷构造器：sourceCandidates/candidateClasses/stackDigest/url 均缺省（Kotlin 默认参数）。 */
    public AgentEvent(String sql, long timestamp, long durationNanos, Long updateCount, String errorText,
                      String sourceFile, int sourceLine, String methodKey, String requestTag,
                      List<Triple<Integer, String, String>> params, String threadName) {
        this(sql, timestamp, durationNanos, updateCount, errorText, sourceFile, sourceLine,
            List.of(), List.of(), methodKey, requestTag, null, params, threadName, null, 1);
    }

    /**
     * 解析 EVENT 帧 body；结构不符返回 null（丢弃并计数，不断流）。
     */
    public static AgentEvent parse(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject o = root.getAsJsonObject();
            JsonElement sqlEl = o.get("sql");
            String sql = null;
            if (sqlEl != null && sqlEl.isJsonPrimitive()) {
                String text = sqlEl.getAsString();
                if (!text.isBlank()) {
                    sql = text;
                }
            }
            if (sql == null) {
                return null;
            }
            List<Triple<Integer, String, String>> params = new ArrayList<>();
            JsonElement pEl = o.get("p");
            if (pEl != null && pEl.isJsonArray()) {
                for (JsonElement el : pEl.getAsJsonArray()) {
                    if (!el.isJsonArray()) {
                        continue;
                    }
                    JsonArray arr = el.getAsJsonArray();
                    if (arr.size() >= 1 && arr.get(0).isJsonPrimitive()) {
                        params.add(new Triple<>(
                            arr.get(0).getAsInt(),
                            primitiveStringOrNull(arr, 1),
                            primitiveStringOrNull(arr, 2)));
                    }
                }
            }
            // T-PS4-14①：同一 JSON 数组只解析一遍（原 parseCandidates + 两处 parseCandidateRows 共三遍）
            List<CandidateRow> cands = parseCandidateRows(o.get("cands"));
            return new AgentEvent(
                sql,
                o.get("ts") != null ? o.get("ts").getAsLong() : System.currentTimeMillis(),
                o.get("dn") != null ? o.get("dn").getAsLong() : 0L,
                numberLongOrNull(o.get("u")),
                primitiveStringOrNull(o.get("err")),
                primitiveStringOrNull(o.get("sf")),
                o.get("sl") != null && o.get("sl").isJsonPrimitive() ? o.get("sl").getAsInt() : 0,
                cands.stream().map(r -> new Pair<>(r.file(), r.line())).collect(java.util.stream.Collectors.toList()),
                cands.stream().map(CandidateRow::className).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toList()),
                primitiveStringOrNull(o.get("mk")),
                primitiveStringOrNull(o.get("rt")),
                primitiveStringOrNull(o.get("sd")),
                params,
                primitiveStringOrNull(o.get("th")),
                primitiveStringOrNull(o.get("url")),
                // T-SqlListCatchup 券 F2b：聚合计数（旧版 agent 无此键按 1；畸形值回落 1）
                aggregatedCountOrNull(o.get("c")));
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * T-SourceJumpFunnel：{@code cands} 数组解析（[file, line, className] 三列；畸形项跳过——协议演进容错）。
     * T-PS4-14①：sourceCandidates/candidateClasses 共用本次解析派生，不再各调一遍。
     * T-PS4-14②：第四列 method 协议历史遗留零消费方（candidateMethods 已删），不再解析。
     */
    private static List<CandidateRow> parseCandidateRows(JsonElement el) {
        if (el == null || !el.isJsonArray()) {
            return List.of();
        }
        JsonArray arr = el.getAsJsonArray();
        List<CandidateRow> out = new ArrayList<>();
        for (JsonElement item : arr) {
            if (!item.isJsonArray()) {
                continue;
            }
            JsonArray pair = item.getAsJsonArray();
            JsonElement fileEl = getOrNull(pair, 0);
            String file = fileEl != null && fileEl.isJsonPrimitive() ? fileEl.getAsString() : null;
            if (file == null || file.isBlank()) {
                continue;
            }
            JsonElement lineEl = getOrNull(pair, 1);
            int line = lineEl != null && lineEl.isJsonPrimitive() ? lineEl.getAsInt() : 0;
            String cn = primitiveStringOrNull(getOrNull(pair, 2));
            out.add(new CandidateRow(file, Math.max(line, 0), cn));
        }
        return out;
    }

    /** 候选行（T-SourceJumpFunnel）：file/line 必有，className 旧 agent 缺省（四列协议第四列已废弃）。 */
    public record CandidateRow(
        /** 候选文件（相对路径）。 */
        String file,
        /** 行号（0 未知，负数归零）。 */
        int line,
        /** 候选帧类名（旧 agent 缺省 → null）。 */
        String className
    ) {
    }

    private static JsonElement getOrNull(JsonArray arr, int i) {
        return arr.size() > i ? arr.get(i) : null;
    }

    /** primitive 且可转字符串则取值，否则 null（对应 Kotlin takeIf { isJsonPrimitive }?.asString）。 */
    private static String primitiveStringOrNull(JsonElement el) {
        if (el != null && el.isJsonPrimitive()) {
            return el.getAsString();
        }
        return null;
    }

    private static String primitiveStringOrNull(JsonArray arr, int index) {
        return primitiveStringOrNull(getOrNull(arr, index));
    }

    /** 仅数字 primitive 取 long，否则 null（对应 Kotlin takeIf { isJsonPrimitive && isNumber }?.asLong）。 */
    private static Long numberLongOrNull(JsonElement el) {
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsLong();
        }
        return null;
    }

    /** T-SqlListCatchup 券 F2b：仅数字 primitive 且 &gt;1 取值，否则 1（对应 Kotlin asInt.takeIf { it > 1 } ?: 1）。 */
    private static int aggregatedCountOrNull(JsonElement el) {
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            int v = el.getAsInt();
            if (v > 1) {
                return v;
            }
        }
        return 1;
    }
}
