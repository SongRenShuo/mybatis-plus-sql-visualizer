package mpsv.engine

/**
 * P5 最小 stdio 分发入口（`java -jar mpsv-engine-<ver>.jar --stdio`）。
 *
 * 每行 stdin 解析为一个 JSON 请求，按 `kind` 转发到 [EngineApi]，每行 stdout 写一个 JSON 响应。
 * 约定（与 `openidea-mybatis-plus-sql-visualizer` 的 `src/sidecar/proto.rs` 对齐）：
 *  - 请求：`{"protocol":1,"kind":"ping|logs|check|perf|wrapper|…|explain|schema","id":…,
 *    "source":{"path":…,"text":…},"dialect":…,"sql":…,"table":…}`。
 *  - 响应：既有门面的 JSON（`{"ok":…}`），若请求带 `id` 则原样回显为 `{"id":…,…}`。
 *  - `ping` / `health` → `{"ok":true,"version":"0.1.0"}`（握手）。
 *  - 坏 JSON / 未知 kind 一律返回结构化错误，**不退出**；stderr 只放日志。
 *
 * 为什么手写 JSON：引擎产物只有 jsqlparser + Kotlin stdlib（零 Gson/Jackson，见 build.gradle.kts
 * 的依赖约束），为 stdin 一行文本再加一个 JSON 库不划算；这里只需要对象/数组/字符串/数字/布尔/空。
 */
object StdioMain {

    /** 与 jar 的 `Implementation-Version` / 插件钉定版本同源（手改时三处同步）。 */
    const val VERSION = "0.1.0"

    @JvmStatic
    fun main(args: Array<String>) {
        if (!args.contains("--stdio")) {
            System.err.println("mpsv-engine: run with --stdio to serve JSON lines on stdin/stdout")
            return
        }
        val reader = System.`in`.bufferedReader()
        val out = System.out
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val response = try {
                handleLine(line)
            } catch (t: Throwable) {
                errorJson("invalid", "handler failure: " + (t.message ?: t.javaClass.simpleName))
            }
            out.println(response)
            out.flush()
        }
    }

    /** 处理一行请求，返回一行响应（测试可直接调用，无需进程）。 */
    internal fun handleLine(line: String): String {
        val root = try {
            MiniJson.parse(line)
        } catch (e: Exception) {
            return errorJson("invalid", "bad json: " + (e.message ?: "parse error"))
        }
        val obj = root as? MiniJson.J.Obj
            ?: return errorJson("invalid", "request must be a JSON object")
        val id = obj.entries["id"]
        val kind = (obj.entries["kind"] as? MiniJson.J.Str)?.value

        val body = when (kind) {
            "ping", "health" -> "{\"ok\":true,\"version\":" + jsonStr(VERSION) + "}"
            "logs", "check", "perf", "wrapper", "xml", "annotation", "jpa", "jdbcTemplate" ->
                EngineApi.render(kind, sourceText(obj), fieldString(obj, "dialect"))
            "explain" -> EngineApi.explain(
                fieldString(obj, "sql") ?: sourceText(obj),
                fieldString(obj, "dialect") ?: "MySQL",
            )
            "schema" -> EngineApi.schema(
                fieldString(obj, "table") ?: "",
                fieldString(obj, "dialect") ?: "MySQL",
            )
            null -> errorJson("invalid", "missing kind")
            else -> errorJson("unsupported", "unknown kind '" + kind + "'")
        }
        return if (id != null && body.startsWith("{")) {
            "{\"id\":" + id.toJson() + "," + body.substring(1)
        } else {
            body
        }
    }

    private fun fieldString(obj: MiniJson.J.Obj, key: String): String? =
        (obj.entries[key] as? MiniJson.J.Str)?.value

    private fun sourceText(obj: MiniJson.J.Obj): String =
        ((obj.entries["source"] as? MiniJson.J.Obj)?.entries?.get("text") as? MiniJson.J.Str)?.value
            ?: ""

    private fun errorJson(kind: String, message: String): String =
        "{\"ok\":false,\"kind\":" + jsonStr(kind) + ",\"error\":" + jsonStr(message) + "}"
}

/** RFC 8259 最小转义集（与 [EngineApi] 的门面口径一致）。 */
internal fun jsonStr(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (ch < ' ') sb.append("\\u").append(String.format("%04x", ch.code)) else sb.append(ch)
        }
    }
    return sb.append('"').toString()
}

/** 极简 JSON 解析（对象/数组/字符串/数字/布尔/null），够 proto.rs 的请求形状即可。 */
internal object MiniJson {

    sealed class J {
        data class Obj(val entries: LinkedHashMap<String, J>) : J()
        data class Arr(val items: List<J>) : J()
        data class Str(val value: String) : J()
        data class Num(val raw: String) : J()
        data class Bool(val value: Boolean) : J()
        object Null : J()

        /** 重新序列化（用于原样回显 `id`）。 */
        fun toJson(): String = when (this) {
            is Obj -> "{" + entries.entries.joinToString(",") {
                jsonStr(it.key) + ":" + it.value.toJson()
            } + "}"
            is Arr -> "[" + items.joinToString(",") { it.toJson() } + "]"
            is Str -> jsonStr(value)
            is Num -> raw
            is Bool -> if (value) "true" else "false"
            Null -> "null"
        }
    }

    fun parse(text: String): J {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipWhitespace()
        require(parser.pos == text.length) { "trailing characters at " + parser.pos }
        return value
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun peek(): Char {
            require(pos < s.length) { "unexpected end of input" }
            return s[pos]
        }

        fun value(): J {
            skipWhitespace()
            return when (peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> J.Str(string())
                't' -> literal("true", J.Bool(true))
                'f' -> literal("false", J.Bool(false))
                'n' -> literal("null", J.Null)
                else -> number()
            }
        }

        private fun obj(): J.Obj {
            pos++ // {
            val entries = LinkedHashMap<String, J>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return J.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                expect(':')
                entries[key] = value()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return J.Obj(entries) }
                    else -> throw IllegalArgumentException("expected , or } at " + pos)
                }
            }
        }

        private fun arr(): J.Arr {
            pos++ // [
            val items = ArrayList<J>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return J.Arr(items)
            }
            while (true) {
                items.add(value())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return J.Arr(items) }
                    else -> throw IllegalArgumentException("expected , or ] at " + pos)
                }
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < s.length) { "unterminated escape" }
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= s.length) { "short \\u escape" }
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("bad escape \\" + e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): J.Num {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            if (pos < s.length && s[pos] == '.') {
                pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            require(pos > start) { "expected a value at " + start }
            return J.Num(s.substring(start, pos))
        }

        private fun literal(word: String, value: J): J {
            require(s.startsWith(word, pos)) { "expected " + word + " at " + pos }
            pos += word.length
            return value
        }

        private fun expect(c: Char) {
            require(pos < s.length && s[pos] == c) { "expected '" + c + "' at " + pos }
            pos++
        }
    }
}