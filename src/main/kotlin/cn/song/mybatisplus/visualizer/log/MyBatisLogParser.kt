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
 *
 * T-IbatisPanwei 券 A/B（iBatis 2.x 控制台形态，2026-11-06）：iBatis 的 JDBC 日志代理按
 * `{pstm-N}` / `{conn-N}` 实例计数打三行一组——`Executing Statement: <sql>` →
 * `Parameters: [v1, null, v2, …]` → `Types: [java.lang.String, null, …]`。参数行是 Java 集合
 * `toString`，与 MyBatis 的「值(类型)」语法**不兼容**（旧行为：单值把整段方括号当字面量渲染成
 * `'[x]'`；含 null 的多值只匹配到 null 项、其余值丢弃并错位填充）。
 *
 * 本分支的边界（钉死，不做猜测式增强）：
 * - **行级识别**：只认带 `{pstm-N}` / `{conn-N}` 标记的行，命中即由本分支消费并返回，绝不落到
 *   用户配置前缀分支（用户把 SQL 前缀配成 `Executing Statement:` 也不会产生双条目）；
 * - **列表切分**：外层 `[ ]` 剥一层、按顶层逗号切分、裸 `null` 记为 null 值（切分口径复用
 *   [ListParamRenderer.splitListElements]）。**元素内含逗号/嵌套方括号/空串元素**（`List.toString`
 *   不可逆）是已知边界：按逗号切分、不猜转义；`splitListElements` 会滤掉空串元素，这一形态下
 *   参数与 `Types:` 的位置对齐可能偏移（与 `ListParamRenderer` 同口径，不另造实现）；
 * - **类型接入**：`Parameters:` 行先挂起（键 = pstm id），同 id 的 `Types:` 行按位置并入
 *   [LogSqlParam.type] 后闭合。**必须并入类型**：`00340025002200200006` 这类纯数字串在无类型
 *   启发式下会裸出成数值字面量（Oracle 模式语义错误）。兜底闭合点：同 id 的新 `Executing
 *   Statement`、`{conn-N} Preparing Statement:`、MyBatis `Preparing:` 语句行、[closePending]；
 *   `Types:` 缺行时按无类型启发式渲染（现状口径），不伪造类型；
 * - **[takeEntries] 不冲刷挂起态**：生产桥每行喂入后都会调 [takeEntries]，若在此闭合，`Parameters:`
 *   行一落地就被取出、随后的 `Types:` 行永远无处并入；
 * - **`{conn-N} Preparing Statement:` 只消费不产条目**（决策 D-2：iBatis 每条语句都 prepare 一次、
 *   紧接 execute，两条都收会翻倍）；
 * - **批处理一期不支持**（iBatis batch 一次性 prepare、多次 `Parameters:`）——同 id 的第二条
 *   `Parameters:` 会先把上一批按无类型闭合，登记为缺口。
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

    /**
     * T-SAF-22：老化判定时钟（毫秒）。与 [LogThreadRouter] 共用同一时钟源（JdbcTemplate/
     * ShardingSphere 先例）：驱动挂起桶与 iBatis 挂起态的老化淘汰判定；测试注入可控件以获得确定性。
     */
    private val clock: () -> Long = System::currentTimeMillis,
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

        // ---------- T-SAF-12：兜底桶多来源疑似态（仅回退桶会置位，线程桶不触达） ----------

        /** 无 token 参数行已在本桶延迟挂起（归属不明，等闭合点按现状闭合；第二条参数行到达即实证丢弃）。 */
        var needsThreadContext = false

        /**
         * 自学校信号：本桶曾出现「参数行无上下文 + 刚闭合出带参数条目」——单一流不可能的形态
         * （Prep,Prep,Params,Params），多来源交错实证；此后本桶无 token 配对一律延迟处置。
         */
        var noTokenMultiOrigin = false

        /** 最近一次本桶闭合产出的条目是否带参数（自学校信号的判据）。 */
        var lastCloseHadParams = false
    }

    private val router = LogThreadRouter(
        factory = { Bucket() },
        // 无线程 token 的裸行（续行 / 「前缀独占一行」的下一行）要落到**正在等待它**的桶——
        // 否则带前缀的 `Preparing:`（token=main）与其下一行裸 SQL 会分属两个桶，等待态永远完不成
        isCollecting = { it.continuing || it.prefixMatched || it.paramPrefixMatched },
        isIdle = { it.pendingSql == null && !it.prefixMatched && !it.paramPrefixMatched },
        // T-SAF-22：挂起桶老化——静默超阈值的未闭合语句在容量压力下强制闭合产出后淘汰
        // （isIdle 判据让挂起桶永无淘汰资格，64 上限被废掉、滞留随控制台无界增长）
        forceClose = { closePending(it) },
        clock = clock,
    )

    /**
     * iBatis 2.x 未闭合语句（券 A/B）：键 = `{pstm-N}` 的 N（配对优先按 id，线程桶只在
     * 「语句行关闭 MyBatis 侧未闭合条目」时用到）。
     *
     * @param params null = `Parameters:` 行未到（仅 SQL 已知）；非 null = 参数已到、等 `Types:` 并入
     */
    private class IbatisPending(
        val sql: String,
        val logLine: String,
        var params: List<LogSqlParam>? = null,
        /** T-SAF-22：挂起态创建时刻（毫秒）——老化淘汰判据（静默超 TTL 兜底闭合）。 */
        val createdAtMs: Long,
    )

    /**
     * pstm id → 挂起态（保持插入序，[closePending] 按序冲刷）。
     * 存在独立于线程桶的第二个理由：`Parameters:` 与 `Types:` 在并发控制台里可能被别的线程的
     * 语句行夹开，按 id 归位比按线程桶归位更接近 iBatis 的打印事实。
     */
    private val ibatisPending = LinkedHashMap<String, IbatisPending>()

    /**
     * T-SAF-12：本控制台近期出现过的线程 token（有界 FIFO，最多 [MAX_TRACKED_TOKENS] 个）。
     * 「近期出现过 ≥2 个不同线程来源」即多来源控制台——无 token 行在兜底桶里的配对自此视为
     * 归属不明（延迟处置判据之一）。纯裸流（全程无 token）无此信号，由桶级自学校信号兜底
     * （见 [Bucket.noTokenMultiOrigin]）。
     */
    private val recentTokens = ArrayDeque<String>()

    /** T-SAF-12：延迟配对判据的 token 追踪窗口（有界，防长会话线程名漂移膨胀）。 */
    private fun noteTokenOrigin(token: String?) {
        if (token == null) return
        recentTokens.remove(token)
        recentTokens.addLast(token)
        while (recentTokens.size > MAX_TRACKED_TOKENS) recentTokens.removeFirst()
    }

    /** T-SAF-12：无 token 行归属是否不明（多来源疑似）——兜底桶延迟配对判据。 */
    private fun noTokenAmbiguous(bucket: Bucket): Boolean =
        bucket.noTokenMultiOrigin || recentTokens.size >= 2

    /** 喂入一行控制台输出（任意线程；按线程 token 路由到独立桶——解析器实例按 console 隔离）。 */
    fun feed(line: String) {
        val token = LogThreadRouter.threadTokenOf(line)
        feedInto(router.bucketForToken(token), line, token)
    }

    /**
     * F58（T-PerfSweep18 压轴）：喂入一行＋**已提取**的线程 token——同一行喂多解析器的热路径
     * （控制台桥）每行只提一次 token 下传，免重复 [LogThreadRouter.threadTokenOf]。
     * token=null 即「提取不到」的 fail-open 回退语义，与 [feed] 内部自提逐字等价。
     */
    fun feed(line: String, threadToken: String?) {
        feedInto(router.bucketForToken(threadToken), line, threadToken)
    }

    private fun feedInto(bucket: Bucket, line: String, token: String?) {
        noteTokenOrigin(token)
        // 分支 0（券 A/B）：iBatis 2.x 标记行——命中即消费并返回，绝不落到下方用户配置前缀分支
        if (feedIbatis(bucket, line)) return

        // 分支 1：Preparing 行（有 SQL 同行）
        val sql = extractAfterKeyword(line, sqlPrefix)
        if (sql != null) {
            // 其它语句行 = iBatis 挂起参数组的兜底闭合点（Types 缺行/日志截断时不丢条目）
            flushIbatis()
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
            if (bucket.pendingSql == null) {
                // 无 SQL 上下文，忽略（T-SAF-12：顺带登记多来源自学校信号）
                noteOrphanParams(bucket)
                return
            }
            // T-LPC-03：等到的行若本身是日志行 → 该语句**无参数**（MyBatis 对无参语句打印空前缀行）。
            // 闭合且不吞该行（按正常流程重新喂入——含它自己的线程桶路由；本等待态已复位，不会再进本分支）。
            if (looksLikeLogLine(line, sqlPrefix, paramPrefix)) {
                LogParseDiag.swallowedLineBlocked.incrementAndGet()
                closePending(bucket)
                feed(line)
                return
            }
            pairParams(bucket, parseParams(line.trim().take(maxParseLength)), token)
            return
        }

        // 分支 4：Parameters 行（有参数同行）——正常形态
        val paramsText = extractAfterKeyword(line, paramPrefix)
        if (paramsText != null) {
            bucket.prefixMatched = false // 清除等待态（罕见：Preparing 独占一行后紧跟正常 Parameters）
            if (bucket.pendingSql == null) {
                LogParseDiag.paramWithoutContext.incrementAndGet() // T-LPC-05：静默丢弃留痕
                noteOrphanParams(bucket) // T-SAF-12：多来源自学校信号
                return
            }
            // T-SAF-20：参数文本与 SQL 正文同受 maxParseLength 截断——此前 Parameters 余下文本
            // 无界直入 parseParams，其 PARAM_REGEX 对逗号密集无括号文本是二次复杂度（每个逗号
            // 起点惰性重扫到行尾），单条数百 KB 的 Parameters 行可冻结控制台解析线程。
            // T-SAF-12：配对统一走 [pairParams]（兜底桶多来源疑似时延迟处置）。
            // 立即闭合——MyBatis 单条语句 Parameters 行后参数完整，立即生成条目避免去重窗口时序问题；
            // 多 Parameters 行（极端）合并逻辑：若下一行仍是 Parameters，pendingParams 已被清空后重开。
            pairParams(bucket, parseParams(paramsText.take(maxParseLength)), token)
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

    // ---------- iBatis 2.x 标记行（券 A/B） ----------

    /**
     * 处理带 `{pstm-N}` / `{conn-N}` 标记的行；返回 true 表示**本行已被消费**（调用方须立即返回）。
     *
     * 廉价前置一次 `contains`：MyBatis/Hibernate 日志行在热路径上（每行每解析器调用）零正则开销。
     */
    private fun feedIbatis(bucket: Bucket, line: String): Boolean {
        if (!line.contains(PSTM_MARKER_PREFIX) && !line.contains(CONN_MARKER_PREFIX)) return false
        evictExpiredIbatis()
        val marker = IBATIS_MARKER.find(line) ?: return false
        val id = marker.groupValues[2]
        val rest = line.substring(marker.range.last + 1).trim()
        if (marker.groupValues[1] == CONN_KIND) {
            // `{conn-N} Preparing Statement: <sql>`（及 conn 的其它行）只消费不产条目（D-2）
            if (rest.startsWith(IBATIS_PREPARING_STATEMENT)) flushIbatis()
            return true
        }
        return when {
            rest.startsWith(IBATIS_EXECUTING_STATEMENT) -> {
                onIbatisExecuting(bucket, id, rest.removePrefix(IBATIS_EXECUTING_STATEMENT).trim(), line)
                true
            }
            rest.startsWith(IBATIS_PARAMETERS) -> {
                onIbatisParameters(id, rest.removePrefix(IBATIS_PARAMETERS).trim())
                true
            }
            rest.startsWith(IBATIS_TYPES) -> {
                onIbatisTypes(id, rest.removePrefix(IBATIS_TYPES).trim())
                true
            }
            // 其余带 pstm 标记的行（Closing statement 等）只消费不产条目
            else -> true
        }
    }

    /** `{pstm-N} Executing Statement:`：开启一条语句；同 id 的旧挂起态先按无类型兜底闭合。 */
    private fun onIbatisExecuting(bucket: Bucket, id: String, sql: String, line: String) {
        ibatisPending.remove(id)?.let { emitIbatis(it) }
        // 语句行同时终止 MyBatis 侧的未闭合语句/续行（与分支 1 同语义）
        closePending(bucket)
        if (sql.isEmpty()) return
        ibatisPending[id] = IbatisPending(sql.take(maxParseLength), line, createdAtMs = clock())
    }

    /**
     * T-SAF-22：iBatis 挂起态老化——静默超 [IBATIS_PENDING_TTL_MS] 的挂起组按无类型兜底闭合
     * 产出后淘汰（与线程桶老化同一判据与量级）。挂起表按插入序持有，表首最老，首个未超龄条目
     * 处即可停扫；每条 iBatis 标记行喂入时检查一次（摊还 O(1)，无后台线程）。
     */
    private fun evictExpiredIbatis() {
        if (ibatisPending.isEmpty()) return
        val now = clock()
        val iterator = ibatisPending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.createdAtMs < IBATIS_PENDING_TTL_MS) break
            iterator.remove()
            emitIbatis(entry.value)
        }
    }

    /** `{pstm-N} Parameters:`：挂起等 Types；无对应语句（上下文已丢）按既有诊断计数丢弃。 */
    private fun onIbatisParameters(id: String, text: String) {
        val pending = ibatisPending[id]
        if (pending == null) {
            LogParseDiag.paramWithoutContext.incrementAndGet()
            return
        }
        // T-SAF-20：超长列表截断——截断会破坏 `[...]` 结构，parseIbatisParams 按非列表形态
        // 返回空表（超长即放弃解析，不产出错位的半截参数），与 maxParseLength 的兜底语义一致。
        val params = parseIbatisParams(text.take(maxParseLength))
        if (pending.params != null) {
            // 同 id 的第二条 Parameters（批处理 / 同语句多次执行）：上一批先按无类型闭合，本批另起一条
            ibatisPending.remove(id)
            emitIbatis(pending)
            ibatisPending[id] = IbatisPending(pending.sql, pending.logLine, params, createdAtMs = clock())
        } else {
            pending.params = params
        }
    }

    /** `{pstm-N} Types:`：按位置并入类型后闭合；无对应挂起态（或 Parameters 尚未到）则只消费。 */
    private fun onIbatisTypes(id: String, text: String) {
        val pending = ibatisPending[id] ?: return
        val params = pending.params ?: return // 形态异常（Types 先于 Parameters）：保留 SQL 等参数行
        ibatisPending.remove(id)
        // T-SAF-20：Types 行与参数行同受截断（超长截断的错位边界与参数行同口径登记）
        emitEntry(pending.sql, pending.logLine, applyIbatisTypes(params, parseIbatisTypes(text.take(maxParseLength))))
    }

    /** 按「无类型兜底」闭合单条 iBatis 挂起态（Types 缺行 / 被新语句顶掉）。 */
    private fun emitIbatis(pending: IbatisPending): LogSqlEntry =
        emitEntry(pending.sql, pending.logLine, pending.params ?: emptyList())

    /** 冲刷全部 iBatis 挂起态（按插入序），返回首个产出条目（保持 closePending 返回语义）。 */
    private fun flushIbatis(): LogSqlEntry? {
        if (ibatisPending.isEmpty()) return null
        val items = ibatisPending.values.toList()
        ibatisPending.clear()
        var first: LogSqlEntry? = null
        for (item in items) {
            val produced = emitIbatis(item)
            if (first == null) first = produced
        }
        return first
    }

    /** 条目产出唯一收口（MyBatis 桶闭合与 iBatis 挂起闭合共用，避免两处各构造一份）。 */
    private fun emitEntry(sql: String, logLine: String?, params: List<LogSqlParam>): LogSqlEntry {
        val entry = LogSqlEntry(
            rawSql = sql,
            restoredSql = restorer.restore(sql, params),
            params = params,
            source = SqlSource.MYBATIS,
            logTimestamp = logLine?.let { LogTimestampParser.parse(it) },
        )
        entries += entry
        return entry
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
    private fun closePending(bucket: Bucket): LogSqlEntry? = closePending(bucket, dropParams = false)

    /**
     * 闭合指定桶的未完成条目；[dropParams]=true（T-SAF-12 跨线程错配实证路径）时按**无参数**
     * 闭合（`?` 保留、不产出错配还原 SQL），挂起参数一并丢弃。
     */
    private fun closePending(bucket: Bucket, dropParams: Boolean): LogSqlEntry? {
        val sql = bucket.pendingSql ?: return null
        bucket.pendingSql = null
        bucket.continuing = false
        val logLine = bucket.pendingLogLine
        bucket.pendingLogLine = null
        val params = if (dropParams) {
            bucket.pendingParams.clear()
            emptyList()
        } else {
            bucket.pendingParams.toList().also { bucket.pendingParams.clear() }
        }
        bucket.needsThreadContext = false
        bucket.lastCloseHadParams = params.isNotEmpty()
        return emitEntry(sql, logLine, params)
    }

    /**
     * T-SAF-12（D5 温和版）：参数配对唯一入口（分支 3/4 共用）。
     *
     * 兜底桶（无 token 行 fail-open 的归属地，[LogThreadRouter.isFallback]）内，若「无 token 行
     * 归属不明」（[noTokenAmbiguous]：控制台近期出现过 ≥2 个线程来源，或本桶已自学校多来源实证），
     * 则**延迟配对**：参数挂起、标记 [Bucket.needsThreadContext] 并计数
     * [LogParseDiag.fallbackPairingDelayed]，由闭合点（下一条语句行 / [closePending()] / 老化淘汰）
     * 按现状闭合——单一线程流语义不变（挂起参数在闭合点照常还原，只是产出时点后移）。
     *
     * 延迟挂起期间同一挂起窗口内再到达一条无 token 参数行：单一线程不可能产生（第一条参数行
     * 已闭合挂起语句）——跨线程交错实证，挂起语句按**无参数**闭合（[LogParseDiag.crossThreadMixBlocked]
     * 计数），两组疑似参数一并丢弃：宁可参数不还原，也不产出「B 的 SQL 配 A 的参数」的错配
     * 还原 SQL（该形态直达执行链）。带 token 的参数行走各自线程桶，本判据不触达。
     */
    private fun pairParams(bucket: Bucket, params: List<LogSqlParam>, token: String?) {
        val delayed = token == null && router.isFallback(bucket) && noTokenAmbiguous(bucket)
        if (delayed && bucket.pendingParams.isNotEmpty()) {
            LogParseDiag.crossThreadMixBlocked.incrementAndGet()
            closePending(bucket, dropParams = true)
            return
        }
        bucket.pendingParams += params
        if (delayed) {
            bucket.needsThreadContext = true
            LogParseDiag.fallbackPairingDelayed.incrementAndGet()
            return // 延迟闭合：由闭合点按现状闭合
        }
        closePending(bucket)
    }

    /**
     * T-SAF-12：无 token 参数行无上下文被丢弃时的自学校信号——若本桶刚闭合出**带参数**的条目，
     * 即「Prep,Prep,Params,Params」交错形态（单一流不可能产生），登记多来源实证（粘滞）。
     */
    private fun noteOrphanParams(bucket: Bucket) {
        if (router.isFallback(bucket) && bucket.lastCloseHadParams) {
            bucket.noTokenMultiOrigin = true
        }
    }

    /**
     * 闭合全部未完成条目（面板刷新 / 粘贴末尾时调用）：iBatis 挂起态先冲刷（按 pstm 插入序），
     * 再按线程桶序闭合 MyBatis 侧。
     */
    fun closePending(): LogSqlEntry? {
        var first: LogSqlEntry? = flushIbatis()
        for (bucket in router.allBuckets()) {
            val produced = closePending(bucket)
            if (first == null) first = produced
        }
        return first
    }

    /**
     * 取出全部已闭合条目并清空缓冲区（面板刷新用）。
     *
     * **不冲刷 iBatis 挂起态**：控制台桥每行喂入后都会调用本方法，若在此闭合，`Parameters:` 行
     * 落地即被取走、紧随的 `Types:` 行再也无处并入（类型丢失 → 纯数字串裸出成语义错误的字面量）。
     * 挂起态的闭合见类注释「兜底闭合点」。
     */
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
         * T-SAF-22：iBatis 挂起态老化 TTL（毫秒）——与 [LogThreadRouter.DEFAULT_BUCKET_IDLE_TIMEOUT_MS]
         * 同量级（60s）：`Types:` 与参数行是相邻输出，正常间隔毫秒级，静默 60s 即认定
         * 「Types 行不会再来了」，按无类型兜底闭合优于永久滞留（挂起表无条目上限的 #22 同类问题）。
         */
        private const val IBATIS_PENDING_TTL_MS = 60_000L

        /** T-SAF-12：多来源判据的 token 追踪窗口（最近 N 个不同线程 token；有界防膨胀）。 */
        private const val MAX_TRACKED_TOKENS = 8

        // ---------- iBatis 2.x 标记（券 A/B） ----------

        /** 廉价前置判据（两次 indexOf 即可挡掉 MyBatis/Hibernate 行，不跑正则）。 */
        private const val PSTM_MARKER_PREFIX = "{pstm-"
        private const val CONN_MARKER_PREFIX = "{conn-"

        /** 标记种类分组值：`{conn-N}` 只消费不产条目，其余（pstm）走语句状态机。 */
        private const val CONN_KIND = "conn"

        private const val IBATIS_EXECUTING_STATEMENT = "Executing Statement:"
        private const val IBATIS_PREPARING_STATEMENT = "Preparing Statement:"
        private const val IBATIS_PARAMETERS = "Parameters:"
        private const val IBATIS_TYPES = "Types:"

        /** iBatis JDBC 日志代理的实例计数器标记：`{pstm-100021}` / `{conn-100022}`。 */
        private val IBATIS_MARKER = Regex("""\{(pstm|conn)-(\d+)\}""")

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
 * iBatis 2.x `Parameters:` 行（`[v1, null, v2]`，Java 集合 `toString` 形态）→ 参数表。
 *
 * 外层 `[ ]` 是**结构**不是值的一部分，剥一层后按顶层逗号切分；裸 `null` 记为 null 值
 * （其余元素原样作为值文本，类型留空等 `Types:` 行并入）。
 *
 * 切分口径复用 [ListParamRenderer.splitListElements]（与 IN 子句集合展开同一份实现）。
 * 已知边界（`toString` 不可逆，与 `ListParamRenderer` 同口径登记）：元素内含逗号/嵌套方括号
 * 按逗号切分；空串元素会被 `splitListElements` 滤掉，此形态下与 `Types:` 的位置对齐可能偏移。
 *
 * 非列表形态（首尾不是 `[` `]`）返回空表——iBatis 恒打列表形态，不猜其它形状。
 */
internal fun parseIbatisParams(text: String): List<LogSqlParam> {
    val elements = ListParamRenderer.splitListElements(text) ?: return emptyList()
    return elements.map { element ->
        if (element == IBATIS_NULL_LITERAL) LogSqlParam(null, null) else LogSqlParam(element, null)
    }
}

/**
 * iBatis 2.x `Types:` 行（`[java.lang.String, null, java.lang.Long]`）→ 类型表（按位置对应
 * `Parameters:` 行）。裸 `null` 表示该位置参数为 null（iBatis 对 null 参数打 `null` 类型），
 * 记为 null 表示「无类型」——不伪造类型。
 */
internal fun parseIbatisTypes(text: String): List<String?> {
    val elements = ListParamRenderer.splitListElements(text) ?: return emptyList()
    return elements.map { element -> if (element == IBATIS_NULL_LITERAL) null else element }
}

/**
 * 按位置把 `Types:` 并入参数（越界位置保持无类型；[types] 为空表时原样返回）。
 * 类型来自 iBatis 的**全限定名**（`java.lang.String`），[LogSqlRestorer] 的 PLAIN_TYPES 与
 * 日期族匹配都已覆盖这类形态（`java.lang.Long` 归一后原样直出、`java.lang.String` 走引号）。
 */
internal fun applyIbatisTypes(params: List<LogSqlParam>, types: List<String?>): List<LogSqlParam> {
    if (types.isEmpty()) return params
    return params.mapIndexed { index, param ->
        val type = types.getOrNull(index) ?: return@mapIndexed param
        param.copy(type = type)
    }
}

/** iBatis 列表里的 null 字面量（Java `List.toString()` 对 null 元素打印 `null`）。 */
private const val IBATIS_NULL_LITERAL = "null"

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
