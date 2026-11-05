package cn.song.mybatisplus.visualizer.log

/**
 * MyBatis 控制台日志解析器（T-M31-01）：逐行喂入控制台输出，配对 `Preparing:` / `Parameters:` 行。
 *
 * 支持的日志形态（T-M31.14 起前缀可配置）：
 * - MP 前缀：`==>  Preparing: SELECT ...` / `==> Parameters: 1(Long), abc(String)`
 * - 原生：`Preparing: SELECT ...` / `Parameters: 1, abc(String)`
 * - 带日志框架前缀（时间戳/线程/类名）——[mixedPrefix]=true 时按关键字定位（默认，宽容匹配）；
 *   false 时仅行首精确前缀匹配（用户自定义前缀后开启）
 * - 无参语句：仅 `Preparing:` 行（无 Parameters），还原时不做替换
 *
 * 噪声行（`<== Columns:` / `<== Total:` / 业务日志等）自然忽略；
 * 一条语句可跟多条 Parameters 行（极端形态），参数顺序追加。
 * [maxParseLength] 超长截断（防控制台大输出卡顿）。
 *
 * T-LogParseConc（并发配对修正，用户实测驱动）：
 * - **按线程分桶**（[LogThreadRouter]）：并发应用里多线程语句按到达序混排，单槽状态机下另一线程的
 *   `Preparing:` 行会把本线程尚未收到参数的语句提前闭合、本线程随后的 `Parameters:` 行因无上下文被丢弃
 *   （参数全丢、`?` 未还原）。故全部状态（`pendingSql`/参数/等待态/续行态）按线程 token 隔离。
 * - **空 `Parameters:` 行不再吞下一行**：下一行若带日志行特征（[looksLikeLogLine]），说明该语句本身
 *   无参数（MyBatis 对无参语句打印空前缀行）——闭合且**不吞该行**（按正常流程重新喂入）。旧行为会把
 *   整行日志当作参数值塞进 `?`（面板出现 `LIMIT 2026-09-16 14:35:00.413 DEBUG …` 这类文字）。
 * - **多行 SQL 续行**（T-LPC-07）：`Preparing:` 之后的裸文本行（不含日志特征、非空）并入当前 SQL——
 *   mapper 里格式化的 SQL 不再只留首行，增强通道两侧 rawSql 同形（日志时间相关器得以命中）。
 */
class MyBatisLogParser(
    private val restorer: LogSqlRestorer = LogSqlRestorer(),

    /**
     * 配置项为 **var 可变**（T-xx 设置生效性审计）：此前为构造时固化（filter 挂载时快照）——
     * 捕捉中修改前缀/混合/最大长度对已挂载 console 不生效（读的是缓存旧值）；
     * 现由 [SqlLogConsoleBridge] 每次喂行前按 [ParserConfig] 重新同步，即时生效。
     * 默认值与构造参数保持兼容（既有测试按命名参数构造不受影响）。
     */
    var sqlPrefix: String = DEFAULT_SQL_PREFIX,
    var paramPrefix: String = DEFAULT_PARAM_PREFIX,
    var mixedPrefix: Boolean = true,
    var maxParseLength: Int = DEFAULT_MAX_PARSE_LENGTH,
) {

    private val entries = mutableListOf<LogSqlEntry>()

    /** 单线程桶：一条语句从 `Preparing:` 到闭合之间的全部状态（T-LogParseConc 按线程隔离）。 */
    private class Bucket {
        var pendingSql: String? = null
        var pendingLogLine: String? = null
        val pendingParams = mutableListOf<LogSqlParam>()

        /** 前缀独占一行兼容（T-M31.xx）：`Preparing:` 行 remainder 为空 → 等下一行作 SQL。 */
        var prefixMatched = false

        /** `Parameters:` 行 remainder 为空 → 等下一行作参数文本（T-LogParseConc 加了日志行判别）。 */
        var paramPrefixMatched = false

        /** 多行 SQL 续行态（T-LPC-07）：下一行是裸文本则并入 `pendingSql`。 */
        var continuing = false
    }

    private val router = LogThreadRouter(
        factory = { Bucket() },
        // 无线程 token 的裸行（续行 / 「前缀独占一行」的下一行）要落到**正在等待它**的桶——
        // 否则带前缀的 `Preparing:`（token=main）与其下一行裸 SQL 会分属两个桶，等待态永远完不成
        isCollecting = { it.continuing || it.prefixMatched || it.paramPrefixMatched },
        isIdle = { it.pendingSql == null && !it.prefixMatched && !it.paramPrefixMatched },
    )

    /** 喂入一行控制台输出（任意线程；按线程 token 路由到独立桶——解析器实例按 console 隔离）。 */
    fun feed(line: String) {
        feedInto(router.bucketFor(line), line)
    }

    /**
     * F58（T-PerfSweep18 压轴）：喂入一行＋**已提取**的线程 token——同一行喂多解析器的热路径
     * （控制台桥）每行只提一次 token 下传，免重复 [LogThreadRouter.threadTokenOf]。
     * token=null 即「提取不到」的 fail-open 回退语义，与 [feed] 内部自提逐字等价。
     */
    fun feed(line: String, threadToken: String?) {
        feedInto(router.bucketForToken(threadToken), line)
    }

    private fun feedInto(bucket: Bucket, line: String) {
        // 分支 1：Preparing 行（有 SQL 同行）
        val sql = extractAfterKeyword(line, sqlPrefix)
        if (sql != null) {
            closePending(bucket) // 上一条未闭合先闭合（无 Parameters 或异常日志）
            bucket.pendingSql = sql.take(maxParseLength)
            bucket.pendingLogLine = line
            bucket.pendingParams.clear()
            bucket.prefixMatched = false
            bucket.paramPrefixMatched = false // T-LogParseConc：等待态随语句切换复位（旧实现残留→吞掉后续整行）
            bucket.continuing = true
            return
        }

        // 分支 2：Preparing 独占一行（remainder 为空）→ 进入等待 SQL 态
        if (!bucket.prefixMatched && isPrefixOnly(line, sqlPrefix)) {
            bucket.prefixMatched = true
            bucket.pendingParams.clear()
            return
        }

        // 分支 3：Parameters 独占一行（极端形态）→ 等待下一行参数文本
        if (!bucket.paramPrefixMatched && isPrefixOnly(line, paramPrefix)) {
            if (bucket.prefixMatched) {
                // 等待 SQL 中看到 Parameters（中间无 SQL 行）→ 放弃等待
                bucket.prefixMatched = false
                return
            }
            bucket.paramPrefixMatched = true
            return
        }
        if (bucket.paramPrefixMatched) {
            bucket.paramPrefixMatched = false
            if (line.isBlank()) return // 跳过空行
            if (bucket.pendingSql == null) return // 无 SQL 上下文，忽略
            // T-LPC-03：等到的行若本身是日志行 → 该语句**无参数**（MyBatis 对无参语句打印空前缀行）。
            // 闭合且不吞该行（按正常流程重新喂入——含它自己的线程桶路由；本等待态已复位，不会再进本分支）。
            if (looksLikeLogLine(line, sqlPrefix, paramPrefix)) {
                LogParseDiag.swallowedLineBlocked.incrementAndGet()
                closePending(bucket)
                feed(line)
                return
            }
            bucket.pendingParams += parseParams(line.trim())
            closePending(bucket)
            return
        }

        // 分支 4：Parameters 行（有参数同行）——正常形态
        val paramsText = extractAfterKeyword(line, paramPrefix)
        if (paramsText != null) {
            bucket.prefixMatched = false // 清除等待态（罕见：Preparing 独占一行后紧跟正常 Parameters）
            if (bucket.pendingSql == null) {
                LogParseDiag.paramWithoutContext.incrementAndGet() // T-LPC-05：静默丢弃留痕
                return
            }
            bucket.pendingParams += parseParams(paramsText)
            // 立即闭合——MyBatis 单条语句 Parameters 行后参数完整，立即生成条目避免去重窗口时序问题；
            // 多 Parameters 行（极端）合并逻辑：若下一行仍是 Parameters，pendingParams 已被清空后重开。
            closePending(bucket)
            return
        }

        // 分支 5：等待 SQL 态 → 此行即 SQL
        if (bucket.prefixMatched) {
            if (line.isBlank()) return // 跳过空行
            // 多模块并发时，Hibernate 行可能出现在 Preparing: 独占一行后的等待态 → 跳过并继续等待
            if (looksLikeHibernateLine(line)) {
                return // 不重置 prefixMatched，继续等 MyBatis SQL
            }
            bucket.pendingSql = line.trim().take(maxParseLength)
            bucket.pendingLogLine = line
            bucket.prefixMatched = false
            bucket.continuing = true
            return
        }

        // 分支 6（T-LPC-07）：SQL 续行——仅当本行未被任何正常分支消费、且不像日志行/异构语句行时并入当前 SQL。
        // 排在等待态之后是必须的：「等下一行参数文本」态下的裸参数行（`42(Long)`）不能被当续行吞掉。
        if (bucket.continuing) {
            if (line.isBlank() || looksLikeLogLine(line, sqlPrefix, paramPrefix) || looksLikeForeignStatement(line)) {
                bucket.continuing = false // 日志行/异构语句行/空行终止续行（本行交回噪声分支）
            } else {
                appendContinuation(bucket, line)
            }
            return
        }

        // 分支 7：噪声行（与旧行为一致）
    }

    /**
     * 是否像**异构语句行**（其他 ORM/框架的 SQL 行，如 `Hibernate: select …`）——续行判定须拒收：
     * 这类行本身代表另一条语句，并入会污染 MyBatis 条目的 SQL 正文（`DualParserIntegrationTest` 覆盖）。
     */
    private fun looksLikeForeignStatement(line: String): Boolean {
        val trimmed = line.trim()
        return looksLikeHibernateLine(line) || HEADER_LIKE_PATTERN.containsMatchIn(trimmed)
    }

    /** 续行并入（受 [maxParseLength] 约束，超限即终止续行不吞后续）。 */
    private fun appendContinuation(bucket: Bucket, line: String) {
        val current = bucket.pendingSql ?: run { bucket.continuing = false; return }
        val merged = current + "\n" + line.trimEnd()
        if (merged.length >= maxParseLength) {
            bucket.continuing = false
            return
        }
        bucket.pendingSql = merged
    }

    /** 闭合指定桶的未完成条目（新语句到来 / 面板刷新时调用）。 */
    private fun closePending(bucket: Bucket): LogSqlEntry? {
        val sql = bucket.pendingSql ?: return null
        bucket.pendingSql = null
        bucket.continuing = false
        val logTs = bucket.pendingLogLine?.let { LogTimestampParser.parse(it) }
        bucket.pendingLogLine = null
        val params = bucket.pendingParams.toList()
        val entry = LogSqlEntry(
            rawSql = sql,
            restoredSql = restorer.restore(sql, params),
            params = params,
            source = SqlSource.MYBATIS,
            logTimestamp = logTs,
        )
        entries += entry
        bucket.pendingParams.clear()
        return entry
    }

    /** 闭合全部桶的未完成条目（面板刷新 / 粘贴末尾时调用）。 */
    fun closePending(): LogSqlEntry? {
        var first: LogSqlEntry? = null
        for (bucket in router.allBuckets()) {
            val produced = closePending(bucket)
            if (first == null) first = produced
        }
        return first
    }

    /** 取出全部已闭合条目并清空缓冲区（面板刷新用）。 */
    fun takeEntries(): List<LogSqlEntry> {
        val result = entries.toList()
        entries.clear()
        return result
    }

    private fun extractAfterKeyword(line: String, keyword: String): String? {
        if (keyword.isEmpty()) return null
        val idx = if (mixedPrefix) line.indexOf(keyword) else {
            // 严格模式：行首精确前缀（允许日志框架前缀在关键字前？——严格模式按用户定义的前缀整段匹配，
            // 即行以「前缀 + 空格/冒号」开头；此处实现为行首匹配关键字）
            if (line.startsWith(keyword)) 0 else -1
        }
        if (idx < 0) return null
        val text = line.substring(idx + keyword.length).trim()
        return text.ifEmpty { null }
    }

    /**
     * 行仅含前缀关键字（remainder 为空/纯空白）——分行形态信号。
     * 与 [extractAfterKeyword] 使用相同的 mixedPrefix 判断路径，保证两种模式下行为一致。
     */
    private fun isPrefixOnly(line: String, keyword: String): Boolean {
        if (keyword.isEmpty()) return false
        val idx = if (mixedPrefix) line.indexOf(keyword) else {
            if (line.startsWith(keyword)) 0 else -1
        }
        if (idx < 0) return false
        return line.substring(idx + keyword.length).trim().isEmpty()
    }

    /**
     * 是否为 Hibernate 日志行（多模块并发时可能混入 MyBatis 控制台）。
     * 在 prefixMatched 等待态中跳过此类行，避免将 Hibernate 行误认为 MyBatis SQL。
     */
    private fun looksLikeHibernateLine(line: String): Boolean {
        val trimmed = line.trim()
        // Hibernate: 前缀 / 块注释开头 / 参数绑定行
        return trimmed.startsWith("Hibernate:", ignoreCase = true) ||
            trimmed.startsWith("/*") ||
            HIBERNATE_BINDING_PATTERN.containsMatchIn(trimmed)
    }

    companion object {
        private const val DEFAULT_SQL_PREFIX = "Preparing:"
        private const val DEFAULT_PARAM_PREFIX = "Parameters:"
        private const val DEFAULT_MAX_PARSE_LENGTH = 20000

        /**
         * Hibernate 参数绑定行模式：`binding parameter [N] as [TYPE] - [VALUE]`
         */
        private val HIBERNATE_BINDING_PATTERN = Regex(
            """binding parameter\s+\[(\d+)\]\s+as\s+\[(\w+)\]\s+-\s+\[(.*)\]""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * T-LPC-07：`名称: 内容` 形态的行首标识符前缀（如 `Hibernate: select …`）——续行判定拒收，
         * 避免异构语句行/框架头行被并入 MyBatis 条目的 SQL 正文。
         */
        private val HEADER_LIKE_PATTERN = Regex("""^[A-Za-z][A-Za-z0-9_.]{2,}\s*:""")
    }
}

/**
 * 解析 `Parameters:` 行参数文本（T-M31-02 依赖）：`1(Long), abc(String), null` →
 * [(1, Long), (abc, String), (null, null)]。
 *
 * 非贪婪正则 + 右边界断言：`值(类型)` 值可含逗号/括号（`a, b(String)`、`a(x)(String)`）；
 * `null` 字面量无类型标注（须先于一般分支匹配，防 `null, 1(Integer)` 被吞成 `null, 1`）。
 * 全无 `(类型)` 标注时（旧版/第三方日志）回落逗号粗分，类型 null（还原器启发式判定引号）。
 */
internal fun parseParams(text: String): List<LogSqlParam> {
    val result = mutableListOf<LogSqlParam>()
    for (match in PARAM_REGEX.findAll(text)) {
        val nullLiteral = match.groups[1]?.value
        if (nullLiteral != null) {
            result += LogSqlParam(null, null)
            continue
        }
        result += LogSqlParam(match.groups[2]?.value?.trim(), match.groups[3]?.value)
    }
    if (result.isEmpty() && text.isNotBlank()) {
        result += text.split(',').map { LogSqlParam(it.trim().ifEmpty { null }, null) }
    }
    return result
}

/**
 * `null` 分支在前（见 [parseParams] KDoc）；`\A`/`\z` 锚定避免跨参数误配。
 *
 * T-CrossChannelResidual（20261105）：类型分组由 `[A-Za-z_$][\w$]*` 放宽为 `[A-Za-z_$][\w$.]*`，
 * 允许全限定类型名（`42(java.lang.Long)`）。原口径匹配不上 → 回落逗号粗分 → `value` 里残留
 * `(java.lang.Long)`、`type=null`，与控制台还原后的字面量形态分叉（`LogSqlRestorer.PLAIN_TYPES`
 * 本就有 `java.lang.*` 条目，但解析器从不产出这种形态，那段映射等于死代码）。
 * 真实 MyBatis 日志打简单类名，故现场不可达；放宽只为关掉这个边界并让既有映射真正生效。
 */
private val PARAM_REGEX = Regex(
    """(?:\A|,\s*)(null)(?=,\s*|\z)|(?:\A|,\s*)(.+?)\(([A-Za-z_$][\w$.]*(?:\[\])*)\)(?=,\s*|\z)""",
)
