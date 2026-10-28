package cn.song.mybatisplus.visualizer.log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import kotlin.Pair;

/**
 * 历史文件参数载荷编解码（T-HistParam F1）：格式 v2 在头部注释行之下新增独立注释行
 * `-- pd: <base64> --`，payload 为 UTF-8 JSON `{"r": rawSql, "p": [[value, type], ...]}`
 * （value/type 可为 null）的 Base64 编码，正文保持可执行 restoredSql（F-43 初心：文件
 * 可直接在数据库工具打开执行）。
 *
 * <p>M56 T-M56-00：payload 扩展可选键 {@code m}（mapperStatement）/ {@code e}（errorText）/
 * {@code q}（requestTag）/ {@code u}（updateCount）——写入端仅在非默认时落键，
 * 旧 payload（无扩展键）解码出 null，旧历史文件可读。
 *
 * <p>选 Base64 加独立注释行的理由：
 * - 参数值可能含 {@code |}、逗号、{@code --}、括号、换行等任意文本，明文分隔符方案必然引入转义歧义；
 *   Base64 字符集（A-Za-z0-9+/=）与头部解析正则、字段分隔符零冲突，单行形态不破坏
 * [SqlLogFileReader.countInFile] 的头部行计数判据；
 * - 旧版本插件的 Reader 正则锚定行尾，读到 pd 行会把它并进 SQL 正文当注释行——展示冗余
 *   但可执行性不破坏（回滚场景安全）；若把参数塞进头部行本体，旧正则整条解析失败丢条目。
 */
public final class SqlLogParamCodec {

    public static final SqlLogParamCodec INSTANCE = new SqlLogParamCodec();
    private SqlLogParamCodec() {}

    private static final String RAW_FIELD = "r";
    private static final String PARAMS_FIELD = "p";
    private static final String MAPPER_FIELD = "m";
    private static final String ERROR_FIELD = "e";
    private static final String TAG_FIELD = "q";
    private static final String UPDATE_COUNT_FIELD = "u";
    /**
     * T-McpAuditTrail T-MAT-03：来源标记（{@link SqlSource} 常量名）。
     *
     * <p>为什么不复用头部 {@code source:} 段：该段语义已被 {@link SqlLogFileReader#parseSource} 占用为
     * 「源码文件:行号」（回填 {@link LogSqlEntry#sourceFile()}/{@link LogSqlEntry#sourceLine()}），
     * 改其语义会打断 {@code SqlLogHistoryRoundTripTest} 的头部逐字符断言；且头部是本插件与旧版
     * 读端共享的格式面。pd 载荷天然满足两条：旧读端把整行当正文注释降级（正文无损）、
     * 旧文件无此键 → 解码为 null（来源仍 UNKNOWN，与现状一致）。
     */
    private static final String SOURCE_FIELD = "s";
    // T-SourceJumpFunnel：候选 [file, line] 对数组 + 候选类名 + 栈快照（历史读回后跳转回退仍可用）
    private static final String CANDS_FIELD = "cd";
    private static final String CAND_CLASSES_FIELD = "cc";
    private static final String STACK_DIGEST_FIELD = "sd";
    /** R10 T-ShardingSphereCompat：Actual SQL 折叠附注（仅非默认落键；旧 payload 无此键 → null）。 */
    private static final String ACTUAL_SQL_FIELD = "a";
    /** 捕获路径（CaptureProvenance 常量名，仅非 UNKNOWN 落键；旧 payload 无此键 → 读端回退 UNKNOWN）。 */
    private static final String PROVENANCE_FIELD = "pv";

    private static final Gson GSON = new Gson();

    /**
     * pd 载荷解码结果。mapperStatement / errorText / requestTag / updateCount 为 M56 扩展字段，
     * 旧 payload 为 null。
     */
    public record DecodedPayload(
            /** rawSql 原文。 */
            String rawSql,
            /** 参数列表（[value, type] 对还原）。 */
            List<LogSqlParam> params,
            /** mapper 语句 id（旧 payload 为 null）。 */
            String mapperStatement,
            /** 执行报错（旧 payload 为 null）。 */
            String errorText,
            /** 请求标签（旧 payload 为 null）。 */
            String requestTag,
            /** 影响行数（旧 payload 为 null）。 */
            Long updateCount,
            /** T-SourceJumpFunnel：候选 [file, line]（栈序）；旧 payload 为空。 */
            List<Pair<String, Integer>> sourceCandidates,
            /** T-SourceJumpFunnel：候选类名（栈序对齐）；旧 payload 为空。 */
            List<String> candidateClasses,
            /** T-SourceJumpFunnel：调用栈快照；旧 payload 为 null。 */
            String stackDigest,
            /**
             * T-McpAuditTrail T-MAT-03：来源（当前仅 {@link SqlSource#MCP} 落键）；旧 payload / 非 MCP 条目为 null。
             * 读端把它回填进 {@link LogSqlEntry#source()}，不填则沿用默认 {@link SqlSource#UNKNOWN}。
             */
            SqlSource source,
            /** R10 T-ShardingSphereCompat：Actual SQL 折叠附注；旧 payload 为 null。 */
            String actualSql,
            /** 捕获路径；旧 payload / 未打标条目为 null（读端回退 UNKNOWN）。 */
            CaptureProvenance captureProvenance) {

        /** 无扩展字段形态（Kotlin data class 默认参数等价）。 */
        public DecodedPayload(String rawSql, List<LogSqlParam> params) {
            this(rawSql, params, null, null, null, null, List.of(), List.of(), null, null, null, null);
        }
    }

    /**
     * O-147 历史修订身份：修订仅升级元数据——匹配首次 SQL/绑定值/捕获时间，不使用可重置的 UI 序号。
     * SHA-256（首次 rawSql/restoredSql 去尾分号 + timestamp + 参数值/类型对）的十六进制串。
     */
    public static String historyIdentity(LogSqlEntry entry) {
        JsonArray original = new JsonArray();
        original.add(entry.rawSql().trim().replaceAll(";$", "").trim());
        original.add(entry.restoredSql().trim().replaceAll(";$", "").trim());
        original.add(entry.timestamp());
        JsonArray params = new JsonArray();
        for (LogSqlParam p : entry.params()) {
            JsonArray pair = new JsonArray();
            pair.add(p.value() == null ? JsonNull.INSTANCE : new JsonPrimitive(p.value()));
            pair.add(p.type() == null ? JsonNull.INSTANCE : new JsonPrimitive(p.type()));
            params.add(pair);
        }
        original.add(params);
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(GSON.toJson(original).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 编码 rawSql + params 为 Base64 载荷（无 M56 扩展字段形态，等价 encodeEntry 全默认条目）。 */
    public static String encode(String rawSql, List<LogSqlParam> params) {
        return encodeEntry(new LogSqlEntry(rawSql, rawSql, params));
    }

    /**
     * 编码条目为 Base64 载荷：rawSql + params 恒写；M56 扩展字段（mapperStatement/errorText/
     * requestTag/updateCount）仅非默认时落键，旧版本插件解码端忽略未知键（gson 宽松解析）。
     */
    public static String encodeEntry(LogSqlEntry entry) {
        java.io.StringWriter out = new java.io.StringWriter();
        writeEncodedEntry(entry, out);
        return out.toString();
    }

    /** JSON → UTF-8 → Base64 均增量写入，修订调用方可以在任意字节预算处中止。 */
    public static void writeEncodedEntry(LogSqlEntry entry, java.io.Writer target) {
        java.io.OutputStream ascii = new java.io.OutputStream() {
            @Override
            public void write(int b) throws java.io.IOException {
                target.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws java.io.IOException {
                int at = off;
                while (at < off + len) {
                    int n = Math.min(1024, off + len - at);
                    target.write(new String(b, at, n, StandardCharsets.US_ASCII));
                    at += n;
                }
            }
        };
        java.io.OutputStream encoded = Base64.getEncoder().wrap(ascii);
        java.io.Writer utf8 = new java.io.OutputStreamWriter(encoded, StandardCharsets.UTF_8);
        try {
            com.google.gson.stream.JsonWriter json = GSON.newJsonWriter(new ChunkedWriter(utf8));
            json.beginObject();
            json.name(RAW_FIELD).value(entry.rawSql());
            json.name(PARAMS_FIELD).beginArray();
            for (LogSqlParam p : entry.params()) {
                json.beginArray().value(p.value()).value(p.type()).endArray();
            }
            json.endArray();
            if (entry.mapperStatement() != null) {
                json.name(MAPPER_FIELD).value(entry.mapperStatement());
            }
            if (entry.errorText() != null) {
                json.name(ERROR_FIELD).value(entry.errorText());
            }
            if (entry.requestTag() != null) {
                json.name(TAG_FIELD).value(entry.requestTag());
            }
            if (entry.updateCount() != null) {
                json.name(UPDATE_COUNT_FIELD).value(entry.updateCount());
            }
            // T-SourceJumpFunnel：候选/栈快照（非默认才落键，旧版本解码端忽略未知键）
            if (!entry.sourceCandidates().isEmpty()) {
                json.name(CANDS_FIELD).beginArray();
                for (Pair<String, Integer> candidate : entry.sourceCandidates()) {
                    json.beginArray().value(candidate.getFirst()).value(candidate.getSecond()).endArray();
                }
                json.endArray();
            }
            if (!entry.candidateClasses().isEmpty()) {
                json.name(CAND_CLASSES_FIELD).beginArray();
                for (String cls : entry.candidateClasses()) {
                    json.value(cls);
                }
                json.endArray();
            }
            if (entry.stackDigest() != null) {
                json.name(STACK_DIGEST_FIELD).value(entry.stackDigest());
            }
            if (entry.actualSql() != null) {
                json.name(ACTUAL_SQL_FIELD).value(entry.actualSql());
            }
            // 捕获路径仅非 UNKNOWN 落键（枚举名而非序号，新增常量不使旧文件失效）
            if (entry.captureProvenance() != null && entry.captureProvenance() != CaptureProvenance.UNKNOWN) {
                json.name(PROVENANCE_FIELD).value(entry.captureProvenance().name());
            }
            // T-McpAuditTrail T-MAT-03：来源标记——仅 MCP 落键（本任务范围：其余来源的读回口径
            // 维持 UNKNOWN 不变，不做未立项的行为扩张）。枚举名而非序号：新增常量不使旧文件失效。
            // R10：SHARDINGSPHERE 同样落键——控制台解析产出的来源若无键，历史读回退化为 UNKNOWN。
            if (entry.source() == SqlSource.MCP || entry.source() == SqlSource.SHARDINGSPHERE) {
                json.name(SOURCE_FIELD).value(entry.source().name());
            }
            json.endObject();
            json.close(); // 输出 Base64 尾块，不关闭外部 target。
        } catch (java.io.IOException e) {
            // master 无 catch（Kotlin 无受检异常概念）——IOException 原样透传（含修订链
            // RevisionOutput 抛的 RevisionLimitExceeded，测试按该类型捕获）。
            // 不得包装成 UncheckedIOException 改变异常形态；受检透传走既有 sneakyThrow 先例。
            throw sneakyThrow(e);
        }
    }

    /** 受检异常原样透传（AcpTransportEpRegistrar/McpToolsetSupport 同款先例）。 */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    /** 避免 OutputStreamWriter.write(String) 将巨型 String 一次转成 char[]。 */
    public static final class ChunkedWriter extends java.io.Writer {
        private final java.io.Writer target;

        public ChunkedWriter(java.io.Writer target) {
            this.target = target;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws java.io.IOException {
            int at = off;
            while (at < off + len) {
                int n = Math.min(1024, off + len - at);
                target.write(cbuf, at, n);
                at += n;
            }
        }

        @Override
        public void write(String str, int off, int len) throws java.io.IOException {
            int at = off;
            while (at < off + len) {
                int n = Math.min(1024, off + len - at);
                target.write(str, at, n);
                at += n;
            }
        }

        @Override
        public void write(int c) throws java.io.IOException {
            target.write(c);
        }

        @Override
        public void flush() throws java.io.IOException {
            target.flush();
        }

        @Override
        public void close() throws java.io.IOException {
            target.close();
        }
    }

    /**
     * 解码 Base64 载荷为 DecodedPayload；任何形态异常（非法 Base64 / JSON 缺字段 /
     * 结构不符 / rawSql 空白）返回 null，调用方把 pd 行降级为普通正文注释行。
     * M56 扩展键缺失/形态异常按缺省 null 容错（不因扩展键损坏丢弃整条）。
     */
    public static DecodedPayload decode(String payload) {
        try {
            String decoded = new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(decoded);
            if (!parsed.isJsonObject()) return null;
            JsonObject json = parsed.getAsJsonObject();
            JsonElement rawEl = json.get(RAW_FIELD);
            String rawSql = rawEl != null && rawEl.isJsonPrimitive() ? rawEl.getAsString() : null;
            if (rawSql == null || rawSql.isBlank()) return null;
            List<LogSqlParam> params = new ArrayList<>();
            JsonElement paramsEl = json.get(PARAMS_FIELD);
            if (paramsEl != null && paramsEl.isJsonArray()) {
                for (JsonElement el : paramsEl.getAsJsonArray()) {
                    if (!el.isJsonArray()) return null;
                    JsonArray arr = el.getAsJsonArray();
                    if (arr.size() < 1) return null;
                    JsonElement valueEl = arr.get(0);
                    String value = valueEl != null && valueEl.isJsonPrimitive() ? valueEl.getAsString() : null;
                    String type = null;
                    if (arr.size() > 1) {
                        JsonElement typeEl = arr.get(1);
                        type = typeEl != null && typeEl.isJsonPrimitive() ? typeEl.getAsString() : null;
                    }
                    params.add(new LogSqlParam(value, type));
                }
            }
            // T-SourceJumpFunnel：候选 [file, line] 对数组（栈序）——形态异常的元素逐个跳过（forEach return 等价）
            List<Pair<String, Integer>> sourceCandidates = new ArrayList<>();
            JsonElement candsEl = json.get(CANDS_FIELD);
            JsonArray candsArr = candsEl != null && candsEl.isJsonArray() ? candsEl.getAsJsonArray() : null;
            if (candsArr != null) {
                for (JsonElement el : candsArr) {
                    if (!el.isJsonArray()) continue;
                    JsonArray pair = el.getAsJsonArray();
                    JsonElement fileEl = getOrNull(pair, 0);
                    String file = fileEl != null && fileEl.isJsonPrimitive() ? fileEl.getAsString() : null;
                    if (file == null) continue;
                    JsonElement lineEl = getOrNull(pair, 1);
                    int line = lineEl != null && lineEl.isJsonPrimitive() ? lineEl.getAsInt() : 0;
                    sourceCandidates.add(new Pair<>(file, Math.max(line, 0)));
                }
            }
            // T-SourceJumpFunnel：候选类名（栈序对齐）——非原始值逐个跳过
            List<String> candidateClasses = new ArrayList<>();
            JsonElement classesEl = json.get(CAND_CLASSES_FIELD);
            JsonArray classesArr = classesEl != null && classesEl.isJsonArray() ? classesEl.getAsJsonArray() : null;
            if (classesArr != null) {
                for (JsonElement el : classesArr) {
                    if (el != null && el.isJsonPrimitive()) {
                        candidateClasses.add(el.getAsString());
                    }
                }
            }
            return new DecodedPayload(
                    rawSql,
                    params,
                    stringField(json, MAPPER_FIELD),
                    stringField(json, ERROR_FIELD),
                    stringField(json, TAG_FIELD),
                    longField(json, UPDATE_COUNT_FIELD),
                    sourceCandidates,
                    candidateClasses,
                    stringField(json, STACK_DIGEST_FIELD),
                    // T-McpAuditTrail T-MAT-03：来源键缺失/值非本版本枚举名 → null（旧文件与旧版写入端天然兼容，
                    // 不认识的值只丢来源标记，绝不丢弃整条——与其余扩展键同款容错口径）
                    sourceField(json, SOURCE_FIELD),
                    stringField(json, ACTUAL_SQL_FIELD),
                    // 键缺失 / 值非本版本枚举名 → null（读端回退 UNKNOWN，不因扩展键损坏丢弃整条）
                    provenanceField(json, PROVENANCE_FIELD));
        } catch (Exception e) {
            return null;
        }
    }

    /** Kotlin JsonArray.getOrNull 扩展等价：下标越界返回 null。 */
    private static JsonElement getOrNull(JsonArray arr, int i) {
        return arr.size() > i ? arr.get(i) : null;
    }

    /** M56 扩展字符串键读取：缺失/非原始值/空白按 null（master takeIf 链等价）。 */
    private static String stringField(JsonObject json, String field) {
        JsonElement el = json.get(field);
        if (el == null || !el.isJsonPrimitive()) return null;
        String s = el.getAsString();
        return s.isBlank() ? null : s;
    }

    /**
     * T-McpAuditTrail T-MAT-03：来源键读取——缺失/非原始值/非本版本枚举名按 null
     * （master 的 {@code SqlSource.values().firstOrNull { it.name == name }} 等价）。
     */
    private static SqlSource sourceField(JsonObject json, String field) {
        JsonElement el = json.get(field);
        if (el == null || !el.isJsonPrimitive()) return null;
        String name = el.getAsString();
        for (SqlSource value : SqlSource.values()) {
            if (value.name().equals(name)) return value;
        }
        return null;
    }

    /** M56 扩展数值键读取：缺失/非原始值/非数值按 null。 */
    private static Long longField(JsonObject json, String field) {
        JsonElement el = json.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return null;
        return el.getAsLong();
    }

    /** 捕获路径键读取：缺失/非原始值/非本版本枚举名按 null（与 sourceField 同款容错口径）。 */
    private static CaptureProvenance provenanceField(JsonObject json, String field) {
        JsonElement el = json.get(field);
        if (el == null || !el.isJsonPrimitive()) return null;
        String name = el.getAsString();
        for (CaptureProvenance value : CaptureProvenance.values()) {
            if (value.name().equals(name)) return value;
        }
        return null;
    }
}
