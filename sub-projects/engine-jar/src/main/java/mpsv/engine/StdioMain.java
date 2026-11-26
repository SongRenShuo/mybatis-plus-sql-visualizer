// T-SyncK2JMaster181 自 master sub-projects/engine-jar/src/main/kotlin/mpsv/engine/StdioMain.kt 移植
// （master 冻结 tip f06828c2d，291 行 Kotlin → Java）。
//
// master 该文件含三个顶层声明：`object StdioMain`、顶层函数 `jsonStr`、`internal object MiniJson`。
// Java 一个文件只能有一个 public 顶层类型 → `StdioMain` 为 public，另两个落成本文件内的**包级私有**
// 顶层类型（`StdioMainKt` / `MiniJson`，各成独立 .class）。它们只被本包 StdioMain 使用
// （Kotlin `internal` = 模块内可见，Java 侧包级私有是等价收窄面；全仓 grep 无外部引用），
// 命名形态与「顶层函数 → XxxKt 门面类」的项目惯例一致，调用点写 `StdioMainKt.jsonStr(...)`。
package mpsv.engine;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P5 最小 stdio 分发入口（{@code java -jar mpsv-engine-<ver>.jar --stdio}）。
 *
 * <p>每行 stdin 解析为一个 JSON 请求，按 {@code kind} 转发到 {@link EngineApi}，每行 stdout 写一个 JSON 响应。
 * 约定（与 {@code openidea-mybatis-plus-sql-visualizer} 的 {@code src/sidecar/proto.rs} 对齐）：
 * <ul>
 *  <li>请求：{@code {"protocol":1,"kind":"ping|logs|check|perf|wrapper|…|explain|schema","id":…,
 *      "source":{"path":…,"text":…},"dialect":…,"sql":…,"table":…}}。</li>
 *  <li>响应：既有门面的 JSON（{@code {"ok":…}}），若请求带 {@code id} 则原样回显为 {@code {"id":…,…}}。</li>
 *  <li>{@code ping} / {@code health} → {@code {"ok":true,"version":"0.1.0"}}（握手）。</li>
 *  <li>坏 JSON / 未知 kind 一律返回结构化错误，<b>不退出</b>；stderr 只放日志。</li>
 * </ul>
 *
 * <p>为什么手写 JSON：引擎产物只有 jsqlparser + Kotlin stdlib（零 Gson/Jackson，见 build.gradle.kts
 * 的依赖约束），为 stdin 一行文本再加一个 JSON 库不划算；这里只需要对象/数组/字符串/数字/布尔/空。
 *
 * <p><b>移植注记（k2j）</b>：{@code object StdioMain} → {@code public final class} + 私有构造器 + 全静态；
 * {@code const val} → {@code public static final}；{@code when (kind)} 的 {@code null} 分支单独前置
 * （Java 17 的 {@code case null} 属模式匹配 preview，本模块未开 {@code --enable-preview}），其余落
 * switch 表达式。
 */
public final class StdioMain {

    private StdioMain() {
    }

    /** 与 jar 的 {@code Implementation-Version} / 插件钉定版本同源（手改时三处同步）。 */
    public static final String VERSION = "0.1.0";

    /** {@code explain.execute} 缺省超时（与 Rust 侧 {@code ConnSpec::default} 同值）。 */
    public static final long DEFAULT_EXPLAIN_TIMEOUT_MS = 5_000L;

    /**
     * master 侧是 Kotlin（无受检异常）：{@code reader.readLine()} 的 {@code IOException} 会直接穿出 main
     * 由 JVM 打印栈并以非 0 退出。Java 侧声明 {@code throws IOException} 保持同一行为——
     * 不吞掉 IO 失败（吞掉会把「协议流读失败」静默变成「正常结束，退出码 0」）。
     */
    public static void main(String[] args) throws IOException {
        // Kotlin `args.contains("--stdio")`（String[] 的 contains 由 Kotlin 标准库提供 → Java 手写等值判定）。
        boolean stdio = false;
        for (String arg : args) {
            if ("--stdio".equals(arg)) {
                stdio = true;
                break;
            }
        }
        if (!stdio) {
            System.err.println("mpsv-engine: run with --stdio to serve JSON lines on stdin/stdout");
            return;
        }
        // 协议流**必须**显式 UTF-8：中文 Windows 上 JVM 的 stdout.encoding 仍是 GBK
        // （JEP 400 只改 file.encoding），带中文的数据库错误消息会输出 GBK 字节，
        // Rust 侧按 UTF-8 解析 JSON 直接失败。stdin 同理（请求里的中文 SQL/名称）。
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            if (line.isBlank()) {
                continue;
            }
            String response;
            try {
                response = handleLine(line);
            } catch (Throwable t) {
                // 逐行容错是协议的一等语义（一行坏输入不得打死 sidecar 会话）；master 同口径。
                response = errorJson("invalid", "handler failure: "
                        + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            }
            out.println(response);
            out.flush();
        }
    }

    /** 处理一行请求，返回一行响应（测试可直接调用，无需进程）。 */
    public static String handleLine(String line) {
        MiniJson.J root;
        try {
            root = MiniJson.parse(line);
        } catch (Exception e) {
            return errorJson("invalid", "bad json: " + (e.getMessage() != null ? e.getMessage() : "parse error"));
        }
        if (!(root instanceof MiniJson.J.Obj)) {
            return errorJson("invalid", "request must be a JSON object");
        }
        MiniJson.J.Obj obj = (MiniJson.J.Obj) root;
        MiniJson.J id = obj.entries().get("id");
        MiniJson.J kindNode = obj.entries().get("kind");
        String kind = kindNode instanceof MiniJson.J.Str ? ((MiniJson.J.Str) kindNode).value() : null;

        String body;
        // Kotlin `when (kind)` 的 `null -> errorJson("invalid", "missing kind")` 分支前置（见类注）。
        if (kind == null) {
            body = errorJson("invalid", "missing kind");
        } else {
            body = switch (kind) {
                case "ping", "health" -> "{\"ok\":true,\"version\":" + StdioMainKt.jsonStr(VERSION) + "}";
                case "logs", "check", "perf", "wrapper", "xml", "annotation", "jpa", "jdbcTemplate" ->
                        EngineApi.render(kind, sourceText(obj), fieldString(obj, "dialect"));
                case "explain" -> {
                    String explainSql = fieldString(obj, "sql");
                    String explainDialect = fieldString(obj, "dialect");
                    yield EngineApi.explain(
                            explainSql != null ? explainSql : sourceText(obj),
                            explainDialect != null ? explainDialect : "MySQL");
                }
                case "schema" -> {
                    String table = fieldString(obj, "table");
                    String schemaDialect = fieldString(obj, "dialect");
                    yield EngineApi.schema(
                            table != null ? table : "",
                            schemaDialect != null ? schemaDialect : "MySQL");
                }
                // T5-5：真连库执行 EXPLAIN。凭据只出现在请求行里，不进日志（stderr 只有进程级日志）。
                case "explain.execute" -> {
                    String url = fieldString(obj, "url");
                    String userText = fieldString(obj, "user");
                    String password = fieldString(obj, "password");
                    String execSql = fieldString(obj, "sql");
                    Long timeoutMs = fieldNumber(obj, "timeoutMs");
                    yield EngineApi.explainExecute(
                            url != null ? url : "",
                            userText != null ? userText : "",
                            password != null ? password : "",
                            execSql != null ? execSql : "",
                            timeoutMs != null ? timeoutMs : DEFAULT_EXPLAIN_TIMEOUT_MS);
                }
                default -> errorJson("unsupported", "unknown kind '" + kind + "'");
            };
        }
        // Kotlin `if (id != null && body.startsWith("{")) "{\"id\":" + id.toJson() + "," + body.substring(1) else body`
        if (id != null && body.startsWith("{")) {
            return "{\"id\":" + id.toJson() + "," + body.substring(1);
        }
        return body;
    }

    private static String fieldString(MiniJson.J.Obj obj, String key) {
        MiniJson.J node = obj.entries().get(key);
        return node instanceof MiniJson.J.Str ? ((MiniJson.J.Str) node).value() : null;
    }

    /** 数字字段（{@code timeoutMs}）按原始 token 转 Long；缺省/超范围返回 null 由调用方给默认值。 */
    private static Long fieldNumber(MiniJson.J.Obj obj, String key) {
        MiniJson.J node = obj.entries().get(key);
        if (!(node instanceof MiniJson.J.Num)) {
            return null;
        }
        // Kotlin `raw.toLongOrNull()`（非法数字返回 null，不抛）。
        try {
            return Long.parseLong(((MiniJson.J.Num) node).raw());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String sourceText(MiniJson.J.Obj obj) {
        MiniJson.J source = obj.entries().get("source");
        if (!(source instanceof MiniJson.J.Obj)) {
            return "";
        }
        MiniJson.J text = ((MiniJson.J.Obj) source).entries().get("text");
        // Kotlin `… as? MiniJson.J.Str)?.value ?: ""` —— 内层 as? 的 null 合并不可省。
        return text instanceof MiniJson.J.Str ? ((MiniJson.J.Str) text).value() : "";
    }

    private static String errorJson(String kind, String message) {
        return "{\"ok\":false,\"kind\":" + StdioMainKt.jsonStr(kind) + ",\"error\":" + StdioMainKt.jsonStr(message) + "}";
    }
}

/** RFC 8259 最小转义集（与 {@link EngineApi} 的门面口径一致）。Kotlin 顶层函数 → 门面类（项目惯例）。 */
final class StdioMainKt {

    private StdioMainKt() {
    }

    static String jsonStr(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\u000C' -> sb.append("\\f");
                default -> {
                    if (ch < ' ') {
                        sb.append("\\u").append(String.format("%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}

/** 极简 JSON 解析（对象/数组/字符串/数字/布尔/null），够 proto.rs 的请求形状即可。 */
final class MiniJson {

    private MiniJson() {
    }

    /**
     * JSON 值代数。Kotlin 侧为 {@code sealed class J} + 六个子类型；Java 17 落 sealed interface + record
     * （{@code Null} 是单例，故为带 {@code INSTANCE} 的私有构造类而非 record）。
     *
     * <p>{@code toJson()} 在 Kotlin 里是 {@code when (this) { is Obj -> …; is Arr -> …; … }} 的类型分派；
     * Java 17 的模式 switch 仍是 preview（本模块未开 {@code --enable-preview}），故改为**接口方法 + 各子类型覆写**
     * ——行为逐分支等价（每种值的序列化形态一一对应），且不依赖预览特性。
     */
    sealed interface J permits J.Obj, J.Arr, J.Str, J.Num, J.Bool, J.Null {

        /** 重新序列化（用于原样回显 {@code id}）。 */
        String toJson();

        record Obj(LinkedHashMap<String, J> entries) implements J {
            @Override
            public String toJson() {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, J> e : entries.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append(StdioMainKt.jsonStr(e.getKey())).append(':').append(e.getValue().toJson());
                }
                return sb.append('}').toString();
            }
        }

        record Arr(List<J> items) implements J {
            @Override
            public String toJson() {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(items.get(i).toJson());
                }
                return sb.append(']').toString();
            }
        }

        record Str(String value) implements J {
            @Override
            public String toJson() {
                return StdioMainKt.jsonStr(value);
            }
        }

        record Num(String raw) implements J {
            @Override
            public String toJson() {
                return raw;
            }
        }

        record Bool(boolean value) implements J {
            @Override
            public String toJson() {
                return value ? "true" : "false";
            }
        }

        /** JSON {@code null}（Kotlin `object Null : J()`）。 */
        final class Null implements J {

            static final Null INSTANCE = new Null();

            private Null() {
            }

            @Override
            public String toJson() {
                return "null";
            }
        }
    }

    static J parse(String text) {
        Parser parser = new Parser(text);
        J value = parser.value();
        parser.skipWhitespace();
        // Kotlin `require(parser.pos == text.length) { … }` → IllegalArgumentException（同异常类型）。
        if (parser.pos != text.length()) {
            throw new IllegalArgumentException("trailing characters at " + parser.pos);
        }
        return value;
    }

    private static final class Parser {

        private final String s;
        int pos = 0;

        Parser(String s) {
            this.s = s;
        }

        void skipWhitespace() {
            while (pos < s.length() && isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        /** Kotlin {@code Char.isWhitespace()} 的等价判定（含 SPACE_SEPARATOR，如 NBSP）。 */
        private static boolean isWhitespace(char c) {
            return Character.isWhitespace(c) || Character.isSpaceChar(c);
        }

        private char peek() {
            if (pos >= s.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            return s.charAt(pos);
        }

        J value() {
            skipWhitespace();
            // Kotlin `when (peek()) { '{' -> obj() … else -> number() }` → switch 表达式
            // （char 分支穷尽由 default 覆盖；各分支都产出 J，无需 yield 外的额外出口）。
            return switch (peek()) {
                case '{' -> obj();
                case '[' -> arr();
                case '"' -> new J.Str(string());
                case 't' -> literal("true", new J.Bool(true));
                case 'f' -> literal("false", new J.Bool(false));
                case 'n' -> literal("null", J.Null.INSTANCE);
                default -> number();
            };
        }

        private J.Obj obj() {
            pos++; // {
            LinkedHashMap<String, J> entries = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return new J.Obj(entries);
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                entries.put(key, value());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return new J.Obj(entries);
                } else {
                    throw new IllegalArgumentException("expected , or } at " + pos);
                }
            }
        }

        private J.Arr arr() {
            pos++; // [
            List<J> items = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return new J.Arr(items);
            }
            while (true) {
                items.add(value());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return new J.Arr(items);
                } else {
                    throw new IllegalArgumentException("expected , or ] at " + pos);
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\u000C');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new IllegalArgumentException("short \\u escape");
                            }
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private J.Num number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (!(pos > start)) {
                throw new IllegalArgumentException("expected a value at " + start);
            }
            return new J.Num(s.substring(start, pos));
        }

        private J literal(String word, J value) {
            if (!s.startsWith(word, pos)) {
                throw new IllegalArgumentException("expected " + word + " at " + pos);
            }
            pos += word.length();
            return value;
        }

        private void expect(char c) {
            if (!(pos < s.length() && s.charAt(pos) == c)) {
                throw new IllegalArgumentException("expected '" + c + "' at " + pos);
            }
            pos++;
        }
    }
}
