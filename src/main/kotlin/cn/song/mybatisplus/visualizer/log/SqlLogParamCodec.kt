package cn.song.mybatisplus.visualizer.log

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.util.Base64

/**
 * 历史文件参数载荷编解码（T-HistParam F1）：格式 v2 在头部注释行之下新增独立注释行
 * `-- pd: <base64> --`，payload 为 UTF-8 JSON `{"r": rawSql, "p": [[value, type], ...]}`
 * （value/type 可为 null）的 Base64 编码，正文保持可执行 restoredSql（F-43 初心：文件
 * 可直接在数据库工具打开执行）。
 *
 * M56 T-M56-00：payload 扩展可选键 `m`（mapperStatement）/ `e`（errorText）/ `q`（requestTag）/
 * `u`（updateCount）——写入端仅在非默认时落键，旧 payload（无扩展键）解码出 null，旧历史文件可读。
 *
 * 选 Base64 加独立注释行的理由：
 * - 参数值可能含 `|`、逗号、`--`、括号、换行等任意文本，明文分隔符方案必然引入转义歧义；
 *   Base64 字符集（A-Za-z0-9+/=）与头部解析正则、字段分隔符零冲突，单行形态不破坏
 *   [SqlLogFileReader.countInFile] 的头部行计数判据；
 * - 旧版本插件的 Reader 正则锚定行尾，读到 pd 行会把它并进 SQL 正文当注释行——展示冗余
 *   但可执行性不破坏（回滚场景安全）；若把参数塞进头部行本体，旧正则整条解析失败丢条目。
 */
object SqlLogParamCodec {

    private const val RAW_FIELD = "r"
    private const val PARAMS_FIELD = "p"
    private const val MAPPER_FIELD = "m"
    private const val ERROR_FIELD = "e"
    private const val TAG_FIELD = "q"
    private const val UPDATE_COUNT_FIELD = "u"
    /**
     * T-McpAuditTrail T-MAT-03：来源标记（[SqlSource] 常量名）。
     *
     * 为什么不复用头部 `source:` 段：该段语义已被 [SqlLogFileReader.parseSource] 占用为
     * 「源码文件:行号」（回填 [LogSqlEntry.sourceFile]/[LogSqlEntry.sourceLine]），
     * 改其语义会打断 `SqlLogHistoryRoundTripTest` 的头部逐字符断言；且头部是本插件与旧版
     * 读端共享的格式面。pd 载荷天然满足两条：旧读端把整行当正文注释降级（正文无损）、
     * 旧文件无此键 → 解码为 null（来源仍 UNKNOWN，与现状一致）。
     */
    private const val SOURCE_FIELD = "s"
    // T-SourceJumpFunnel：候选 [file, line] 对数组 + 候选类名 + 栈快照（历史读回后跳转回退仍可用）
    private const val CANDS_FIELD = "cd"
    private const val CAND_CLASSES_FIELD = "cc"
    private const val STACK_DIGEST_FIELD = "sd"
    /** T-StackBizFrame S3：线程名（仅非空落键；旧 payload 无此键 → null，标题退回纯「调用栈」）。 */
    private const val THREAD_NAME_FIELD = "tn"
    /** R10 T-ShardingSphereCompat：Actual SQL 折叠附注（仅非默认落键；旧 payload 无此键 → null）。 */
    private const val ACTUAL_SQL_FIELD = "a"
    /**
     * T-AgentCaptureProvenance（O-147）：捕获路径（[CaptureProvenance] 常量名，仅非 UNKNOWN 落键）。
     * 旧 payload 无此键 → 解码 null → 读回 UNKNOWN（向前兼容不迁移）；不认识的值同样降级 UNKNOWN。
     */
    private const val PROVENANCE_FIELD = "pv"

    private val gson = Gson()

    /** 修订仅升级元数据：匹配首次 SQL/绑定值/捕获时间，不使用可重置的 UI 序号。 */
    internal fun historyIdentity(entry: LogSqlEntry): String {
        val original = JsonArray().apply {
            add(entry.rawSql.trim().removeSuffix(";").trim())
            add(entry.restoredSql.trim().removeSuffix(";").trim())
            add(entry.timestamp)
            add(JsonArray().apply { entry.params.forEach { p ->
                add(JsonArray().apply {
                    add(p.value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
                    add(p.type?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
                })
            } })
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(gson.toJson(original).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** pd 载荷解码结果。[mapperStatement] / [errorText] / [requestTag] / [updateCount] 为 M56 扩展字段，旧 payload 为 null。 */
    data class DecodedPayload(
        val rawSql: String,
        val params: List<LogSqlParam>,
        val mapperStatement: String? = null,
        val errorText: String? = null,
        val requestTag: String? = null,
        val updateCount: Long? = null,
        /** T-SourceJumpFunnel：候选 [file, line]（栈序）；旧 payload 为空。 */
        val sourceCandidates: List<Pair<String, Int>> = emptyList(),
        /** T-SourceJumpFunnel：候选类名（栈序对齐）；旧 payload 为空。 */
        val candidateClasses: List<String> = emptyList(),
        /** T-SourceJumpFunnel：调用栈快照；旧 payload 为 null。 */
        val stackDigest: String? = null,
        /** T-StackBizFrame S3：产生该 SQL 的线程名（弹层标题用）；旧 payload 为 null。 */
        val threadName: String? = null,
        /** T-McpAuditTrail T-MAT-03：来源（当前仅 [SqlSource.MCP] 落键）；旧 payload / 非 MCP 条目为 null。
         * 读端把它回填进 [LogSqlEntry.source]，不填则沿用默认 [SqlSource.UNKNOWN]。
         */
        val source: SqlSource? = null,
        /** R10 T-ShardingSphereCompat：Actual SQL 折叠附注；旧 payload 为 null。 */
        val actualSql: String? = null,
        /** O-147：捕获路径（旧 payload / 未打标条目为 null → 读端回退 UNKNOWN）。 */
        val captureProvenance: CaptureProvenance? = null,
    )

    /** 编码 rawSql + params 为 Base64 载荷（无 M56 扩展字段形态，等价 [encodeEntry] 全默认条目）。 */
    fun encode(rawSql: String, params: List<LogSqlParam>): String =
        encodeEntry(
            LogSqlEntry(
                rawSql = rawSql,
                restoredSql = rawSql,
                params = params,
            ),
        )

    /**
     * 编码条目为 Base64 载荷：rawSql + params 恒写；M56 扩展字段（mapperStatement/errorText/
     * requestTag/updateCount）仅非默认时落键，旧版本插件解码端忽略未知键（gson 宽松解析）。
     */
    fun encodeEntry(entry: LogSqlEntry): String = java.io.StringWriter().also { writeEncodedEntry(entry, it) }.toString()

    /** JSON → UTF-8 → Base64 均增量写入，修订调用方可以在任意字节预算处中止。 */
    internal fun writeEncodedEntry(entry: LogSqlEntry, target: java.io.Writer) {
        val ascii = object : java.io.OutputStream() {
            override fun write(b: Int) { target.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                var at = off
                while (at < off + len) {
                    val n = minOf(1024, off + len - at)
                    target.write(String(b, at, n, Charsets.US_ASCII)); at += n
                }
            }
        }
        val encoded = Base64.getEncoder().wrap(ascii)
        val utf8 = java.io.OutputStreamWriter(encoded, Charsets.UTF_8)
        val json = gson.newJsonWriter(ChunkedWriter(utf8))
        json.beginObject()
        json.name(RAW_FIELD).value(entry.rawSql)
        json.name(PARAMS_FIELD).beginArray()
        entry.params.forEach { p -> json.beginArray().value(p.value).value(p.type).endArray() }
        json.endArray()
        entry.mapperStatement?.let { json.name(MAPPER_FIELD).value(it) }
        entry.errorText?.let { json.name(ERROR_FIELD).value(it) }
        entry.requestTag?.let { json.name(TAG_FIELD).value(it) }
        entry.updateCount?.let { json.name(UPDATE_COUNT_FIELD).value(it) }
        if (entry.sourceCandidates.isNotEmpty()) {
            json.name(CANDS_FIELD).beginArray()
            entry.sourceCandidates.forEach { (file, line) -> json.beginArray().value(file).value(line).endArray() }
            json.endArray()
        }
        if (entry.candidateClasses.isNotEmpty()) {
            json.name(CAND_CLASSES_FIELD).beginArray()
            entry.candidateClasses.forEach { json.value(it) }
            json.endArray()
        }
        entry.stackDigest?.let { json.name(STACK_DIGEST_FIELD).value(it) }
        entry.threadName?.takeIf { it.isNotBlank() }?.let { json.name(THREAD_NAME_FIELD).value(it) }
        entry.actualSql?.let { json.name(ACTUAL_SQL_FIELD).value(it) }
        if (entry.captureProvenance != CaptureProvenance.UNKNOWN) json.name(PROVENANCE_FIELD).value(entry.captureProvenance.name)
        if (entry.source == SqlSource.MCP || entry.source == SqlSource.SHARDINGSPHERE) json.name(SOURCE_FIELD).value(entry.source.name)
        json.endObject()
        json.close() // 输出 Base64 尾块，不关闭外部 target。
    }

    /** 避免 OutputStreamWriter.write(String) 将巨型 String 一次转成 char[]。 */
    internal class ChunkedWriter(private val target: java.io.Writer) : java.io.Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            var at = off
            while (at < off + len) { val n = minOf(1024, off + len - at); target.write(cbuf, at, n); at += n }
        }
        override fun write(str: String, off: Int, len: Int) {
            var at = off
            while (at < off + len) { val n = minOf(1024, off + len - at); target.write(str, at, n); at += n }
        }
        override fun write(c: Int) = target.write(c)
        override fun flush() = target.flush()
        override fun close() = target.close()
    }

    /**
     * 解码 Base64 载荷为 [DecodedPayload]；任何形态异常（非法 Base64 / JSON 缺字段 /
     * 结构不符 / rawSql 空白）返回 null，调用方把 pd 行降级为普通正文注释行。
     * M56 扩展键缺失/形态异常按缺省 null 容错（不因扩展键损坏丢弃整条）。
     */
    fun decode(payload: String): DecodedPayload? = runCatching {
        val json = JsonParser.parseString(String(Base64.getDecoder().decode(payload), Charsets.UTF_8))
            .takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val rawSql = json.get(RAW_FIELD)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: return null
        val params = mutableListOf<LogSqlParam>()
        json.get(PARAMS_FIELD)?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
            val arr = el.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            if (arr.size() < 1) return null
            val value = arr.get(0)?.takeIf { it.isJsonPrimitive }?.asString
            val type = if (arr.size() > 1) arr.get(1)?.takeIf { it.isJsonPrimitive }?.asString else null
            params.add(LogSqlParam(value, type))
        }
        DecodedPayload(
            rawSql = rawSql,
            params = params,
            mapperStatement = json.get(MAPPER_FIELD)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() },
            errorText = json.get(ERROR_FIELD)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() },
            requestTag = json.get(TAG_FIELD)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() },
            updateCount = json.get(UPDATE_COUNT_FIELD)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong,
            sourceCandidates = run {
                val arr = json.get(CANDS_FIELD)?.takeIf { it.isJsonArray }?.asJsonArray
                val out = mutableListOf<Pair<String, Int>>()
                arr?.forEach { el ->
                    val pair = el.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEach
                    val file = pair.getOrNull(0)?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                    val line = pair.getOrNull(1)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    out.add(file to maxOf(line, 0))
                }
                out
            },            candidateClasses = run {
                val arr = json.get(CAND_CLASSES_FIELD)?.takeIf { it.isJsonArray }?.asJsonArray
                val out = mutableListOf<String>()
                arr?.forEach { el ->
                    el.takeIf { it.isJsonPrimitive }?.asString?.let { out.add(it) }
                }
                out
            },
            stackDigest = json.get(STACK_DIGEST_FIELD)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() },
            threadName = json.get(THREAD_NAME_FIELD)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() },
            // T-McpAuditTrail T-MAT-03：来源键缺失/值非本版本枚举名 → null（旧文件与旧版写入端天然兼容，
            // 不认识的值只丢来源标记，绝不丢弃整条——与其余扩展键同款容错口径）
            source = json.get(SOURCE_FIELD)?.takeIf { it.isJsonPrimitive }?.asString
                ?.let { name -> SqlSource.values().firstOrNull { it.name == name } },
            actualSql = json.get(ACTUAL_SQL_FIELD)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() },
            // O-147：键缺失 / 值非本版本枚举名 → null（读端回退 UNKNOWN，不因扩展键损坏丢弃整条）
            captureProvenance = json.get(PROVENANCE_FIELD)?.takeIf { it.isJsonPrimitive }?.asString
                ?.let { name -> CaptureProvenance.values().firstOrNull { it.name == name } },
        )
    }.getOrNull()

    private fun com.google.gson.JsonArray.getOrNull(i: Int) = if (size() > i) get(i) else null
}
