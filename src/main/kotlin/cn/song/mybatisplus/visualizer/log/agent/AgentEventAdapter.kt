package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.log.CaptureProvenance
import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.LogSqlParam
import cn.song.mybatisplus.visualizer.log.SqlSource

/**
 * AgentEvent → LogSqlEntry 纯函数适配（§4.4，M56 T-M56-02）：
 * 字段映射 + 参数还原 + dedupeKey 一致性——与桥通道同 SQL 产出同指纹，
 * 增强接管/回落切换期两通道并存时去重口径统一（SqlLogEntryBuffer.dedupeEnabled 默认开）。
 * 参数还原：按 `?` 序朴素替换（引号包裹字符串值），数量不匹配时保留原 SQL 不替换（防错位）。
 */
object AgentEventAdapter {

    /**
     * T-DevAcceptFix2 ⑤（20261006 第二轮验收）：**已收事件计数**——agent 事件到达并进入适配链的累计。
     *
     * 口径：**收到即计数**（含后续被去重/屏蔽/展示链丢弃的）——它回答的是「事件到没到」，
     * 不是「展示了多少」。面板零产出时：N=0 → 查 agent 侧（织入/门禁/传输）；N>0 → 查消费侧。
     * 此前两条通道在停采态不打点、收事件也不打点，导致现场无法分流（沙盒日志实证）。
     */
    private val receivedCount = java.util.concurrent.atomic.AtomicLong()

    /** 事件到达打点（[AgentManager] 适配前调用）。 */
    internal fun markReceived() {
        receivedCount.incrementAndGet()
    }

    /** 已收事件数（诊断行取数；测试可读）。 */
    fun receivedEvents(): Long = receivedCount.get()

    fun toEntry(event: AgentEvent): LogSqlEntry {
        val params = event.params
            .sortedBy { it.first }
            .map { LogSqlParam(it.second, it.third) }
        val restored = restore(event.sql, params)
        return LogSqlEntry(
            rawSql = event.sql,
            restoredSql = restored,
            params = params,
            timestamp = event.timestamp,
            source = SqlSource.AGENT,
            executionTimeMs = event.durationNanos / 1_000_000L,
            sourceFile = event.sourceFile,
            sourceLine = event.sourceLine,
            // T-AgentSourceJump：候选帧透传（IDE 侧跳转在主位置解析失败时按序回退）
            sourceCandidates = event.sourceCandidates,
            // T-SourceJumpFunnel：候选类名与栈快照透传（选帧弹层/NotFound 提示/详情面板往上追踪）
            candidateClasses = event.candidateClasses,
            stackDigest = event.stackDigest,
            // T-StackBizFrame S3：线程名透传——调用栈弹层标题据此标注「这条 SQL 在哪个线程上执行」
            threadName = event.threadName,
            // T-M56-06：方法聚焦键（agent 栈帧 {Class}#{method}）——与 mapper 语句 id 同入 mapperStatement
            // 字段语义扩展由 focusMethodKey 统一承载（agent JDBC 层无 mapper id，非冲突）
            mapperStatement = event.methodKey,
            errorText = event.errorText,
            requestTag = event.requestTag,
            updateCount = event.updateCount,
            // R32 弱形态：连接 URL → 目标库标注（方言名 · 库名@host:port url 解析真值；
            // 凭据在 JdbcUrlDialects.targetOf 解析期剔除；未知/畸形 URL 为 null，面板不显示角标）
            databaseName = JdbcUrlDialects.badgeName(event.url),
            // T-DialectInferFix：同一条 URL 另存**注册方言名**（渲染命名空间）——日志/历史双击预览
            // 用它当 EXACT 方言证据；与角标显示名分开，避免 HSQL/GBASE 这类无注册方言的名字被
            // byName 静默回落 MySQL。
            dialectName = cn.song.mybatisplus.visualizer.sql.UrlSubprotocolDialects.dialectName(event.url),
            // O-147 W0：通道归因打标——agent 事件适配产物恒为 AGENT
            captureProvenance = CaptureProvenance.AGENT,
        )
    }

    /**
     * 按 `?` 序替换参数为字面量（字符串值加单引号 + 内部引号转义；null → NULL）。
     * 占位符数 != 参数数 → 原样返回（防错位误替换）；跳过引号字面量内的 `?`。
     */
    internal fun restore(sql: String, params: List<LogSqlParam>): String {
        if (params.isEmpty()) return sql
        // 数量守卫：占位符数 != 参数数 → 原样返回（防错位误替换）
        if (countPlaceholders(sql) != params.size) return sql
        val out = StringBuilder(sql.length + 32)
        var pi = 0
        var inSingle = false
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (inSingle) {
                out.append(c)
                if (c == '\'') {
                    // 单引号转义（'' 成对）不终结
                    if (i + 1 < sql.length && sql[i + 1] == '\'') {
                        out.append('\''); i++
                    } else {
                        inSingle = false
                    }
                }
                i++
                continue
            }
            if (c == '\'') {
                inSingle = true
                out.append(c)
                i++
                continue
            }
            if (c == '?') {
                if (pi < params.size) {
                    out.append(renderParam(params[pi]))
                    pi++
                } else {
                    out.append(c)
                }
                i++
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** 占位符计数（跳过引号字面量内的 `?`；与 restore 扫描规则一致）。 */
    private fun countPlaceholders(sql: String): Int {
        var n = 0
        var inSingle = false
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (inSingle) {
                if (c == '\'') {
                    // 单引号转义（'' 成对）不终结
                    if (i + 1 < sql.length && sql[i + 1] == '\'') {
                        i++
                    } else {
                        inSingle = false
                    }
                }
            } else if (c == '\'') {
                inSingle = true
            } else if (c == '?') {
                n++
            }
            i++
        }
        return n
    }

    private fun renderParam(p: LogSqlParam): String = when {
        p.value == null -> "NULL"
        isNumericLike(p.value) && isNumericType(p.type) -> p.value
        else -> "'" + p.value.replace("'", "''") + "'"
    }

    private fun isNumericType(type: String?): Boolean = type != null && type.matches(NUMERIC_TYPE_REGEX)

    private fun isNumericLike(v: String): Boolean = v.matches(NUMERIC_LIKE_REGEX)

    /** T-PS4-04：正则提常量（每参数两处现场编译 → object 级单例；T-PerfSweep F2 惯例）。 */
    private val NUMERIC_TYPE_REGEX =
        Regex("(Int|Integer|Long|Short|Byte|Double|Float|BigDecimal|BigInteger|Numeric|Decimal)")

    private val NUMERIC_LIKE_REGEX = Regex("-?\\d+(\\.\\d+)?")
}
