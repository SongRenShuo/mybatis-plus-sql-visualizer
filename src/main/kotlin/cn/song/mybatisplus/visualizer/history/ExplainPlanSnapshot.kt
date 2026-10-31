package cn.song.mybatisplus.visualizer.history

import cn.song.mybatisplus.visualizer.log.structureFingerprint
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.util.xmlb.XmlSerializer
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID

/**
 * R90：仅值类型，不持有 PSI/Swing/JDBC；没有专门的连接串、数据源显示名或凭据字段。
 * payload 是未脱敏的原始计划，谓词/业务字面量中仍可能包含秘密或凭据，必须独立授权保留。
 * 旧 XML 缺字段即空。
 */
data class ExplainPlanSnapshot(
    var id: String = "",
    var capturedAt: Long = 0,
    var projectIdentity: String = "",
    var dataSourceUuid: String = "",
    var dialect: String = "",
    var format: String = "",
    var templateFingerprint: String = "",
    var exactFingerprint: String = "",
    var payload: String = "",
) {
    fun key(): ExplainPlanKey = ExplainPlanKey(
        projectIdentity, dataSourceUuid, dialect, format, templateFingerprint, exactFingerprint,
    )

    /**
     * JSON 仅编码列头、行及采集截断标记；不持久化驱动 notices（可能含连接诊断）。
     *
     * T-TraceDocExport S3：兼容两种 payload 形态——
     *  - 旧/结构化形态 `{"headers":[…],"rows":[[…]],"truncated":bool}`（TABLE/JSON 及多列回退）；
     *  - 原文文本形态 `{"text":"…","headers":[…],"truncated":bool}`（TEXT 档单列计划，见 [create]）。
     * 缺 `headers` 的文本 payload 按 [TEXT_HEADER] 还原为单列；缺 `truncated` 视为 false（旧文件容错）。
     */
    fun raw(): McpExplainFormatter.RawResult? = runCatching {
        val json = JsonParser.parseString(payload).asJsonObject
        if (json.has("text") && !json.get("text").isJsonNull) {
            val text = json.get("text").asString
            McpExplainFormatter.RawResult(
                json.getAsJsonArray("headers")?.map { it.asString } ?: listOf(TEXT_HEADER),
                if (text.isEmpty()) emptyList() else text.split("\n").map { listOf(it) },
                json.get("truncated")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            )
        } else {
            McpExplainFormatter.RawResult(
                json.getAsJsonArray("headers").map { it.asString },
                json.getAsJsonArray("rows").map { row -> row.asJsonArray.map { it.asString } },
                json.get("truncated")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            )
        }
    }.getOrNull()

    /** 按真实 XML 序列化 UTF-8 字节计费，包括标签与 XML 转义；不是仅算 SQL/JSON 字符数。 */
    fun serializedBytes(): Int = org.jdom.output.XMLOutputter()
        .outputString(XmlSerializer.serialize(this)).toByteArray(Charsets.UTF_8).size

    companion object {
        /**
         * T-TraceDocExport S3：原文文本快照的稳定列头。
         *
         * TEXT 档（DM/Oracle/OceanBase/H2/SQLite）计划是单列文本行；payload 走
         * `{"text": "...", "headers": [...], "truncated": bool}` 形态时，缺 headers 的
         * 旧/手写快照按本常量还原为单列 [McpExplainFormatter.RawResult]。
         */
        const val TEXT_HEADER = "PLAN"

        fun create(key: ExplainPlanKey, raw: McpExplainFormatter.RawResult): ExplainPlanSnapshot =
            ExplainPlanSnapshot(
                UUID.randomUUID().toString(), System.currentTimeMillis(), key.projectIdentity,
                key.dataSourceUuid, key.dialect, key.format, key.templateFingerprint, key.exactFingerprint,
                textPayloadOf(key, raw)
                    ?: Gson().toJson(mapOf("headers" to raw.headers, "rows" to raw.rows, "truncated" to raw.truncated)),
            )

        /**
         * T-TraceDocExport S3：TEXT 档单列计划落**原文文本** payload（沿用 truncated 预算）。
         *
         * 仅当「格式=TEXT ∧ 单列 ∧ 单元格不含换行」时启用——文本形态按行拼接，多列或单元格自带
         * 换行会丢失行列边界，此时回退结构化 JSON（仍然可持久化，只是不是文本形态）。
         * 空行集产出空 text，读回为空 rows（不臆造占位行）。
         */
        private fun textPayloadOf(key: ExplainPlanKey, raw: McpExplainFormatter.RawResult): String? {
            if (!key.format.equals(ExplainOutputFormat.TEXT.name, ignoreCase = true)) return null
            if (raw.headers.size > 1) return null
            if (raw.rows.any { row -> row.size > 1 || row.any { it.contains('\n') || it.contains('\r') } }) return null
            return Gson().toJson(
                linkedMapOf(
                    "text" to raw.rows.joinToString("\n") { it.firstOrNull().orEmpty() },
                    "headers" to raw.headers,
                    "truncated" to raw.truncated,
                ),
            )
        }
    }
}

/** 模板键供后续消费者检索；比较必须同时命中 exact，不能把不同参数冒充同一 SQL。 */
data class ExplainPlanKey(
    val projectIdentity: String,
    val dataSourceUuid: String,
    val dialect: String,
    val format: String,
    val templateFingerprint: String,
    val exactFingerprint: String,
) {
    /**
     * 可持久化档位：TABLE/JSON（结构化）与 TEXT（原文文本快照，T-TraceDocExport S3 扩员）。
     * XML（SQL Server 计划 XML）仍不落历史——无消费方，保持原红线。
     */
    val persistable: Boolean get() = projectIdentity.isNotBlank() &&
        UUID_PATTERN.matches(dataSourceUuid) && dialect.isNotBlank() &&
        format in setOf(ExplainOutputFormat.TABLE.name, ExplainOutputFormat.JSON.name, ExplainOutputFormat.TEXT.name) &&
        templateFingerprint.isNotBlank() && exactFingerprint.isNotBlank()

    companion object {
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        fun projectIdentity(basePath: String?): String = basePath?.takeIf { it.isNotBlank() }?.let {
            hash(Paths.get(it).toAbsolutePath().normalize().toString().replace('\\', '/'))
        }.orEmpty()

        fun create(projectIdentity: String, uuid: String?, dialect: String, format: ExplainOutputFormat, sql: String) =
            ExplainPlanKey(projectIdentity, uuid.orEmpty().lowercase(java.util.Locale.ROOT), dialect, format.name,
                hash(structureFingerprint(sql)), hash(sql))

        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** 仅本次提交持有；清空/重新加载会使 generation 失效，条目删除后也绝不复活。 */
data class ExplainPlanCapture(val entryId: String, val generation: Long, val key: ExplainPlanKey)

enum class ExplainPlanSaveResult { SAVED, SESSION_ONLY, TOO_LARGE, CLEARED_OR_REMOVED, RETENTION_DISABLED }
