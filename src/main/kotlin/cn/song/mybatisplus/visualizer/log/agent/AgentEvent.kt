package cn.song.mybatisplus.visualizer.log.agent

import com.google.gson.JsonParser

/**
 * agent 事件模型 + JSON 解析（IDE 侧；与 agent 侧 util/Jsons.writeEvent 键位契约一一对应）。
 * 全字段可选容错：缺失/形态异常 → null/默认（协议演进向前兼容）。
 */
data class AgentEvent(
    /** SQL 文本（PreparedStatement 为 prepare 时 SQL，Statement 为 execute 参数，批为拼接）。 */
    val sql: String,
    /** 采集时间戳（毫秒）。 */
    val timestamp: Long,
    /** 精确耗时（纳秒）。 */
    val durationNanos: Long,
    /** 影响行数（查询 null）。 */
    val updateCount: Long?,
    /** 执行报错（类名: 消息；成功 null）。 */
    val errorText: String?,
    /** 调用栈过滤后的源文件（相对路径）。 */
    val sourceFile: String?,
    /** 行号（0 未知）。 */
    val sourceLine: Int,
    /** T-AgentSourceJump：源码位置候选（栈序 [file, line]；旧版 agent 无此键 → 空列表，跳转回退主位置）。 */
    val sourceCandidates: List<Pair<String, Int>> = emptyList(),
    /** T-SourceJumpFunnel：候选帧类名（与 sourceCandidates 栈序对齐，可能少于候选数——旧 agent 无类名列）。 */
    val candidateClasses: List<String> = emptyList(),
    /** 方法聚焦键 {SimpleClass}#{method}。 */
    val methodKey: String?,
    /** 请求聚合标签。 */
    val requestTag: String?,
    /** T-SourceJumpFunnel P2-2：调用栈快照「类#方法:行 ← …」（栈顶在左；旧版 agent 无此键 → null）。 */
    val stackDigest: String? = null,
    /** 参数快照 [index, valueText|null, type|null]（agent 侧已过脱敏层）。 */
    val params: List<Triple<Int, String?, String?>>,
    /** 执行线程名。 */
    val threadName: String?,
    /** connect 时连接 URL（T-AgentDialectLoop；旧版 agent 无此键 → null 容错）。 */
    val url: String? = null,
    /** v2 facts are optional. Legacy absence is UNKNOWN, never inferred success/identity. */
    val runId: String? = null,
    val executionId: String? = null,
    val sequence: Long? = null,
    val captureEpoch: Long? = null,
    val threadId: Long? = null,
    val startNanos: Long? = null,
    val resultStatus: String = "UNKNOWN",
    val updateCountStatus: String = "UNKNOWN",
    val captureSource: String? = null,
    val timestampSource: String? = null,
    val errorClass: String? = null,
    val sqlState: String? = null,
    val requestId: String? = null,
    val parentRequestId: String? = null,
    val connectionId: String? = null,
    val leaseId: String? = null,
    val transactionId: String? = null,
    /**
     * T-SqlListCatchup 券 F2b：聚合计数——agent 侧事件队列满时同模板（同 sql）事件聚合为一条，
     * 本字段是该条代表的执行次数（agent 心跳之外的事件 JSON `c` 键，仅 >1 时写出）。
     * 旧版 agent 无此键 → 1（按单次执行计），协议双向兼容。
     * 口径说明：「已收事件」计数（[AgentEventAdapter.markReceived]）按帧计 1，不受本字段影响。
     */
    val aggregatedCount: Int = 1,
) {
    companion object {
        /** 解析 EVENT 帧 body；结构不符返回 null（丢弃并计数，不断流）。 */
        fun parse(body: String): AgentEvent? = runCatching {
            val o = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val sql = o.get("sql")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                ?: return null
            val params = mutableListOf<Triple<Int, String?, String?>>()
            o.get("p")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
                val arr = el.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEach
                if (arr.size() >= 1 && arr.get(0).isJsonPrimitive) {
                    params.add(
                        Triple(
                            arr.get(0).asInt,
                            arr.getOrNull(1)?.takeIf { it.isJsonPrimitive }?.asString,
                            arr.getOrNull(2)?.takeIf { it.isJsonPrimitive }?.asString,
                        ),
                    )
                }
            }
            // T-PS4-14①：同一 JSON 数组只解析一遍（原 parseCandidates + 两处 parseCandidateRows 共三遍）
            val cands = parseCandidateRows(o.get("cands"))
            AgentEvent(
                sql = sql,
                timestamp = o.get("ts")?.asLong ?: System.currentTimeMillis(),
                durationNanos = o.get("dn")?.asLong ?: 0L,
                updateCount = o.get("u")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong,
                errorText = o.get("err")?.takeIf { it.isJsonPrimitive }?.asString,
                sourceFile = o.get("sf")?.takeIf { it.isJsonPrimitive }?.asString,
                sourceLine = o.get("sl")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                sourceCandidates = cands.map { it.file to it.line },
                candidateClasses = cands.mapNotNull { it.className },
                methodKey = o.get("mk")?.takeIf { it.isJsonPrimitive }?.asString,
                requestTag = o.get("rt")?.takeIf { it.isJsonPrimitive }?.asString,
                stackDigest = o.get("sd")?.takeIf { it.isJsonPrimitive }?.asString,
                params = params,
                threadName = o.get("th")?.takeIf { it.isJsonPrimitive }?.asString,
                url = o.get("url")?.takeIf { it.isJsonPrimitive }?.asString,
                runId = o.optionalText("runId"),
                executionId = o.optionalText("executionId"),
                sequence = o.optionalLong("sequence")?.takeIf { it > 0 },
                captureEpoch = o.optionalLong("captureEpoch")?.takeIf { it >= 0 },
                threadId = o.optionalLong("threadId")?.takeIf { it > 0 },
                startNanos = o.optionalLong("startNanos"),
                resultStatus = o.optionalText("resultStatus")
                    ?.takeIf { it in setOf("SUCCESS", "ERROR", "UNKNOWN") } ?: "UNKNOWN",
                updateCountStatus = o.optionalText("updateCountStatus")
                    ?.takeIf { it in setOf("KNOWN", "PARTIAL", "UNKNOWN") } ?: "UNKNOWN",
                captureSource = o.optionalText("captureSource"),
                timestampSource = o.optionalText("timestampSource"),
                errorClass = o.optionalText("errorClass"),
                sqlState = o.optionalText("sqlState"),
                requestId = o.optionalText("requestId"),
                parentRequestId = o.optionalText("parentRequestId"),
                connectionId = o.optionalText("connectionId"),
                leaseId = o.optionalText("leaseId"),
                transactionId = o.optionalText("transactionId"),
                // T-SqlListCatchup 券 F2b：聚合计数（旧版 agent 无此键按 1；畸形值回落 1）
                aggregatedCount = o.get("c")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asInt?.takeIf { it > 1 } ?: 1,
            )
        }.getOrNull()

        /**
         * T-SourceJumpFunnel：`cands` 数组解析（[file, line, className] 三列；畸形项跳过——协议演进容错）。
         * T-PS4-14①：sourceCandidates/candidateClasses 共用本次解析派生，不再各调一遍。
         * T-PS4-14②：第四列 method 协议历史遗留零消费方（candidateMethods 已删），不再解析。
         */
        private fun parseCandidateRows(el: com.google.gson.JsonElement?): List<CandidateRow> {
            val arr = el?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            val out = mutableListOf<CandidateRow>()
            arr.forEach { item ->
                val pair = item.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEach
                val file = pair.getOrNull(0)?.takeIf { it.isJsonPrimitive }?.asString
                if (file.isNullOrBlank()) return@forEach
                val line = pair.getOrNull(1)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                val cn = pair.getOrNull(2)?.takeIf { it.isJsonPrimitive }?.asString
                out.add(CandidateRow(file, maxOf(line, 0), cn))
            }
            return out
        }

        /** 候选行（T-SourceJumpFunnel）：file/line 必有，className 旧 agent 缺省（四列协议第四列已废弃）。 */
        internal data class CandidateRow(val file: String, val line: Int, val className: String?)

        private fun com.google.gson.JsonObject.optionalText(key: String): String? = runCatching {
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }.getOrNull()

        private fun com.google.gson.JsonObject.optionalLong(key: String): Long? = runCatching {
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asBigDecimal?.longValueExact()
        }.getOrNull()

        private fun com.google.gson.JsonArray.getOrNull(i: Int) =
            if (size() > i) get(i) else null
    }
}
