package cn.song.mybatisplus.visualizer.log

/**
 * 控制台日志「线程分桶」路由与日志行形态判定（T-LogParseConc）。
 *
 * 背景：单槽状态机假设「SQL 行 → 参数行 → 下一条」严格串行，而真实控制台里多个请求线程的日志
 * 按到达序混排——另一线程的语句行会把本线程尚未收到参数的语句提前闭合，本线程随后到达的参数行
 * 因无上下文被静默丢弃（参数丢失、`?` 未还原）。`JdbcTemplateLogParser` 在 T-JdbcThreadBucket
 * （2026-09-15）已按线程 token 分桶修复，本文件把该机制下沉为三解析器共享实现（MyBatis/Hibernate
 * 此前从未跟进，T-LogParseConc 实证为「并发下条目严重不正确」主因）。
 *
 * 路由策略（fail-open，与既有 JdbcTemplate 实现同语义）：
 * - 行内提取到线程 token → 该线程的独立桶（不存在则新建，超上限先淘汰空闲桶）；
 * - 提取不到 token（多行 SQL 的裸续行等）→ 归属当前处于「收集态」的桶（通常唯一），
 *   否则回退共享默认桶——与旧单槽行为一致，不因提取失败而丢行；
 * - [routeNoTokenToRecent]=true 时（T-HibConc，Hibernate 专用）追加一条**次选**：没有收集态桶时
 *   归属**最近一次出现线程 token 的桶**（MRU）。理由：Hibernate 的多行 SQL 正文与 `Hibernate: `
 *   stdout 标志行都不带日志前缀，却紧跟在同线程的带 token 行之后；回退共享桶会让「正文落回退桶、
 *   绑定行落线程桶」分家，双副本合并与参数归属双双失效。MyBatis/JdbcTemplate 不需要该策略（其
 *   裸行必在等待态桶内），故默认关闭以保持已验证链路零变化。
 *
 * 桶状态由各解析器自定（[B] 泛型），本类只管「哪一行归哪个桶」与容量。
 *
 * T-ConsolePendingFlush 追加：本类同时记录**每个桶的最近活动时间**并提供 [settledBuckets]——分桶把
 * 「条目闭合」的前提从「下一条语句到来」改成「**同线程**的下一行到来」，而同线程下一行可能永不到来
 * （JdbcTemplate 参数行是 TRACE 级默认不开 + Tomcat 按请求新建线程），静默判定是唯一可行的补救信号。
 */
internal class LogThreadRouter<B : Any>(
    private val factory: () -> B,
    /** 该桶是否处于「多行 SQL 收集中」——无线程 token 的续行按此归属。 */
    private val isCollecting: (B) -> Boolean,
    /** 该桶是否空闲（无待闭合状态）——超上限淘汰用，淘汰不作为即丢状态，故只淘汰空闲桶。 */
    private val isIdle: (B) -> Boolean,
    private val maxBuckets: Int = DEFAULT_MAX_BUCKETS,
    /** 无 token 且无收集态桶时，是否归属最近使用的桶（T-HibConc；默认关闭=回退共享桶）。 */
    private val routeNoTokenToRecent: Boolean = false,
    /**
     * T-SAF-22：非空闲桶的老化阈值（毫秒）——静默超过它的非空闲桶在容量压力下强制闭合后淘汰。
     * [isIdle] 判据让「有 pendingSql 的桶」永不具备淘汰资格（#22 病灶：64 上限被废掉，滞留随
     * 控制台无界增长），本阈值是第二条淘汰规则：挂起态静默超过它即认定「后续行不会再来了」，
     * 闭合产出优于永久滞留。
     */
    private val bucketIdleTimeoutMs: Long = DEFAULT_BUCKET_IDLE_TIMEOUT_MS,
    /**
     * T-SAF-22：老化淘汰前的强制闭合回调（解析器传入各自的 closePending(bucket)）——
     * 被淘汰的挂起语句先按无参数兜底闭合产出（进 entries 由调用方取走），而不是静默丢弃；
     * null 时老化桶直接淘汰（路由层兜底有界性，条目丢弃由解析器层面自行权衡）。
     */
    private val forceClose: ((B) -> Unit)? = null,
    /** settle 判定的时钟（毫秒）；测试注入可控件以获得确定性（T-ConsolePendingFlush）。 */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 线程 token → 独立桶（保持插入序，便于 closePending 按序冲刷）。 */
    private val buckets = LinkedHashMap<String, B>()

    /** token 提取失败时的回退桶（与旧单槽状态机同语义）。 */
    private val fallback: B = factory()

    /** 最近一次出现线程 token 的桶（[routeNoTokenToRecent] 的次选归属）。 */
    private var recent: B? = null

    /**
     * T-ConsolePendingFlush：桶 → 最近一次收到行的时钟毫秒（[settledBuckets] 的判据）。
     * 用 IdentityHashMap（键是桶实例本身，无 equals 语义）；桶被淘汰/清空时同步清理，不滞留已淘汰桶。
     */
    private val lastActivity = java.util.IdentityHashMap<B, Long>()

    /** 记录活动时间并返回该桶（路由的三个出口共用）。 */
    private fun touch(bucket: B): B {
        lastActivity[bucket] = clock()
        return bucket
    }

    /**
     * T-ConsolePendingFlush：已静默 ≥ [settleMs] 的桶（分桶在前、回退桶在后，与 [allBuckets] 同序）。
     *
     * 用途：按线程分桶后，一条语句的条目只在「**同线程桶**里再来一行」时才闭合产出；而该行可能永不
     * 到来——JdbcTemplate 的参数行是 TRACE 级（默认不开），Tomcat 又按请求新建线程，于是每条语句都
     * 停在自己的桶里等一个不存在的后继行，`takeEntries()` 永远取不到（面板「已扫描 N 行 / 产出 0 条」）。
     * 静默超过阈值即可认定「后续行不会再来了」（参数行与语句行是同线程相邻输出，正常间隔 1ms 量级），
     * 此时闭合是安全的。
     */
    fun settledBuckets(settleMs: Long, nowMs: Long = clock()): List<B> {
        val out = ArrayList<B>()
        for (bucket in buckets.values) {
            val last = lastActivity[bucket] ?: continue
            if (nowMs - last >= settleMs) out += bucket
        }
        lastActivity[fallback]?.let { if (nowMs - it >= settleMs) out += fallback }
        return out
    }

    /** 按行路由到对应桶。 */
    fun bucketFor(line: String): B = bucketForToken(threadTokenOf(line))

    /**
     * 按已提取的线程 token 路由到对应桶。
     *
     * F58（T-PerfSweep18 压轴）：控制台桥的逐行热路径把同一行喂最多四个解析器，原先每个解析器
     * 各自 `bucketFor(line)` → [threadTokenOf] 重提同一 token（同一行最多算 4 次：双 substring
     * 窗口 + 级别锚正则 + 方括号段扫描）。现喂行方（processConsoleLine / 嗅探路径 / 批量转换面板 /
     * MCP 渲染工具）**每行提一次**下传全部解析器，经本口路由——[bucketFor] 保留为单解析器
     * 调用方（既有测试等）的兼容薄壳；token=null 的 fail-open 回退语义逐字不变。
     */
    fun bucketForToken(token: String?): B {
        if (token != null) {
            buckets[token]?.let { recent = it; return touch(it) }
            if (buckets.size >= maxBuckets) evictIdle()
            return touch(factory().also { buckets[token] = it; recent = it })
        }
        // 无线程 token 行（Hibernate 的多行 SQL 正文 / `Hibernate: ` stdout 标志行等）：
        // 1) MRU 桶优先——Hibernate 一条日志消息只有一个行首前缀，正文行紧跟在**同线程**那条
        //    带 token 行之后（stdout 标志行同理），MRU 是最强信号；且这是「下一条消息的正文」
        //    与「当前块续行」唯一都成立过的解释。
        //    （两者在观测上不可区分：MRU=B 且 A 在收集时，既可能是 B 的正文行、也可能是 A 的续行；
        //     按真实控制台的书写节奏，整条消息一次写出、跨消息交错远比跨行交错常见 → 取 MRU。）
        // 2) 无 MRU 时退「收集态桶」（唯一收集态=确定归属）→ 3) 回退共享桶。
        if (routeNoTokenToRecent) {
            recent?.let { return touch(it) }
            val collecting = buckets.values.filter { isCollecting(it) }
            if (collecting.isNotEmpty()) return touch(collecting.first())
            return touch(fallback)
        }
        // MyBatis/JdbcTemplate：收集态桶优先（其裸行必在等待态桶内），否则回退共享桶
        val collecting = buckets.values.filter { isCollecting(it) }
        if (collecting.isNotEmpty()) {
            return touch(collecting.firstOrNull { it === recent } ?: collecting.first())
        }
        return touch(fallback)
    }

    /** 全部桶（分桶在前、回退桶在后）——closePending 全量冲刷用。 */
    fun allBuckets(): List<B> = buckets.values.toList() + fallback

    /**
     * 该桶是否为**共享回退桶**（T-SAF-12）：无 token 行 fail-open 的归属地。回退桶携带全部
     * 无 token 行的配对状态，是多来源交错错配的唯一风险面——解析器的配对防护按此判据收窄，
     * 带 token 的线程桶不受影响。
     */
    internal fun isFallback(bucket: B): Boolean = bucket === fallback

    /** 当前活跃桶数（诊断/测试用）。 */
    fun bucketCount(): Int = buckets.size

    /** 清空（面板销毁/重挂载时）。 */
    fun clear() {
        buckets.clear()
        lastActivity.clear()
        recent = null
    }

    /**
     * 淘汰直到低于上限（新建桶前调用）。两条规则：
     * ① 空闲桶（isIdle）——既有规则，淘汰不丢待闭合状态；
     * ② 非空闲但静默超 [bucketIdleTimeoutMs] 的桶（T-SAF-22）——先 [forceClose] 兜底闭合
     *    （产出挂起条目）再淘汰。isIdle 判据让挂起桶永无淘汰资格（#22 病灶：64 上限被废掉，
     *    滞留随控制台无界增长），本规则把「挂起但已静默超时」的桶纳入同一上限治理：
     *    静默超阈值即认定后续行不会再来了，闭合产出优于永久滞留。
     */
    private fun evictIdle() {
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext() && buckets.size >= maxBuckets) {
            val entry = iterator.next()
            if (isIdle(entry.value)) {
                if (recent === entry.value) recent = null
                lastActivity.remove(entry.value) // 活动时间随桶一并清理（防已淘汰桶滞留）
                iterator.remove()
            }
        }
        if (buckets.size < maxBuckets) return
        val now = clock()
        val aged = buckets.entries.iterator()
        while (aged.hasNext() && buckets.size >= maxBuckets) {
            val entry = aged.next()
            val last = lastActivity[entry.value] ?: continue
            if (now - last >= bucketIdleTimeoutMs) {
                forceClose?.invoke(entry.value) // 挂起语句兜底闭合（产出进 entries，不静默丢弃）
                if (recent === entry.value) recent = null
                lastActivity.remove(entry.value)
                aged.remove()
            }
        }
    }

    companion object {
        /** 单实例最大线程桶数（超限时空闲桶冲刷淘汰，防异常形态下内存膨胀）。 */
        const val DEFAULT_MAX_BUCKETS = 64

        /**
         * T-SAF-22：非空闲桶老化阈值（静默 60s 即视为「后继行不会再来了」）——与 settledBuckets
         * 静默判据同一量级（参数行与语句行同线程相邻输出，正常间隔 1ms 量级），取 60s 容忍
         * 低频慢查询的语句间隔，同时封住「每条未闭合语句一个永久桶」的无界滞留。
         */
        const val DEFAULT_BUCKET_IDLE_TIMEOUT_MS = 60_000L

        /** 线程 token 提取时，日志级别关键词前后窗口大小（字符）。 */
        private const val THREAD_WINDOW = 35

        /** 匹配行内方括号段（线程名 / TID 段 / 纯数字下标）。 */
        private val THREAD_BRACKET = Regex("""\[([^\]]*)]""")

        /** 纯数字判定（column index / 数组下标等非线程名）。 */
        private val PURE_DIGIT = Regex("""\d+""")

        /** 日志级别关键词锚点（线程 token 定位；T-PerfSweep 收口为常量，逐行热路径复用）。 */
        private val LEVEL_KEYWORD_PATTERN = Regex("""\b(DEBUG|TRACE|INFO|WARN|ERROR)\b""")

        /**
         * 从日志行提取线程 token（并发分桶键）。兼容三种形态：
         * - SkyWalking 注入：`2026-09-15 13:47:17.843 [TID: AE51C…] [http-nio-…-exec-4] DEBUG …`
         * - logback 默认：`2026-08-21 10:00:00.124 TRACE [main] o.h… : …`
         * - Spring Boot 默认：`2026-08-24 15:46:42.444 [TID: null] [cache-preheat-1] TRACE …`
         *
         * 以日志级别关键词为锚，取前后窗口内最近的非 TID 非纯数字方括号段。
         * 返回 null 表示无法可靠提取（→ 回退共享默认桶，fail-open）。
         */
        fun threadTokenOf(line: String): String? {
            val levelMatch = LEVEL_KEYWORD_PATTERN.find(line) ?: return null
            val levelPos = levelMatch.range.first
            val beforeLevel = line.substring((levelPos - THREAD_WINDOW).coerceAtLeast(0), levelPos)
            val afterLevel = line.substring(levelPos, (levelPos + THREAD_WINDOW).coerceAtMost(line.length))

            fun findThread(s: String): String? =
                THREAD_BRACKET.findAll(s)
                    .map { it.groupValues[1] }
                    .filter { token ->
                        !token.startsWith("TID:", ignoreCase = true) &&
                            !token.matches(PURE_DIGIT) &&
                            token.isNotBlank()
                    }
                    .lastOrNull()

            return findThread(beforeLevel) ?: findThread(afterLevel)
        }
    }
}

/**
 * 行是否**带日志行特征**（T-LogParseConc）：供「空 `Parameters:` 等待态」与「SQL 续行」判定共用。
 *
 * 用途有二，缺它两处都会误判：
 * - 空 `Parameters:` 行后的下一行若是日志行，说明该语句**本就是无参数语句**（MyBatis 对无参语句
 *   打印空前缀行）——须闭合且**不得吞掉该行**；否则整行日志会被当作参数值塞进 `?`。
 * - 多行 SQL 续行判定：日志行即续行终止符。
 *
 * 判定特征（任一命中即为日志行）：`==>` / `<==` 前缀、含给定语句关键字（`Preparing:`/`Parameters:` 等）、
 * 行首时间戳、或「日志级别关键词 + 方括号段」组合（裸参数文本形态 `10(Long), abc(String)` 三者皆不命中）。
 */
internal fun looksLikeLogLine(line: String, vararg keywords: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.startsWith("==>") || trimmed.startsWith("<==")) return true
    for (keyword in keywords) {
        if (keyword.isNotEmpty() && trimmed.contains(keyword)) return true
    }
    if (regexLineStartTimestamp.containsMatchIn(line)) return true
    return regexLevelKeyword.containsMatchIn(trimmed) && trimmed.contains('[')
}

private val regexLineStartTimestamp = Regex("""^\s*\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}""")
private val regexLevelKeyword = Regex("""\b(DEBUG|TRACE|INFO|WARN|ERROR)\b""")

/**
 * 解析期静默失效计数（T-LogParseConc S5）：这些失效过去**完全无痕**——用户只能靠肉眼发现
 * 「参数没了」「SQL 里混进日志行」。计数暴露给面板/日志侧周期上报，排障时一眼看到是哪一类。
 *
 * 纯静态计数（无 IDE 依赖，可在纯逻辑用例中断言）；上报由调用方（控制台桥，已持有 Logger）完成。
 */
internal object LogParseDiag {

    /** 参数行到达时无待闭合语句（上下文已丢）→ 该参数被丢弃。 */
    val paramWithoutContext = java.util.concurrent.atomic.AtomicLong()

    /** 空 `Parameters:` 等待态被判别式拦下（原本会把下一行整行吞成参数值）。 */
    val swallowedLineBlocked = java.util.concurrent.atomic.AtomicLong()

    /** 多行残句强制闭合（未等到闭合括号）。 */
    val partialMultilineClosed = java.util.concurrent.atomic.AtomicLong()

    /** 参数文本命中日志行特征被拒（最后一道防污染）。 */
    val paramWithLogMarker = java.util.concurrent.atomic.AtomicLong()

    /** T-SAF-12：兜底桶无 token 参数行因多来源疑似而延迟配对（needsThreadContext 挂起计数）。 */
    val fallbackPairingDelayed = java.util.concurrent.atomic.AtomicLong()

    /** T-SAF-12：兜底桶跨线程错配实证被拦下（同一挂起窗口第二条无 token 参数行 → 挂起语句按无参数闭合）。 */
    val crossThreadMixBlocked = java.util.concurrent.atomic.AtomicLong()

    /**
     * T-PanweiInsertCapture F7：**SQL 形态行未被任何分支消费**的累计。
     *
     * 判据：行内含 `Executing:` / `{pstm-` / `{conn-` / `{stmt-` 之一，但走完 [MyBatisLogParser]
     * 全部分支后落到噪声档。典型来源：`mixedPrefix=false`（只认用户配置的行首前缀）时的
     * MyBatis `==>  Executing:` 行。它为「某类语句整类不见」提供最小证据——「有 N 行 SQL 日志
     * 没被识别」，而不是零信息。
     */
    val unrecognizedSqlLine = java.util.concurrent.atomic.AtomicLong()

    /**
     * T-PanweiInsertCapture F6：本会话是否出现过 **iBatis 2.x 日志形态**（`{pstm-`/`{conn-`/`{stmt-}`
     * 标记行被消费过）。
     *
     * 用途：iBatis 的批处理（`executeBatch`）在框架层**不打印任何 SQL 行**（`BaseLogProxy.EXECUTE_METHODS`
     * 不含该名），控制台通道结构性不可见。面板据此在「iBatis 工程 + 增强捕获未开启」时给出具名提示，
     * 而不是让用户以为插件坏了。
     */
    @Volatile
    var sawIbatisForm: Boolean = false

    /** 上次已上报的计数快照（仅上报增量，避免刷屏）。 */
    private var reported = longArrayOf(0, 0, 0, 0, 0, 0, 0)

    /**
     * 当前各计数（[paramWithoutContext], [swallowedLineBlocked], [partialMultilineClosed],
     * [paramWithLogMarker], [fallbackPairingDelayed], [crossThreadMixBlocked], [unrecognizedSqlLine]）。
     */
    fun counts(): LongArray = longArrayOf(
        paramWithoutContext.get(),
        swallowedLineBlocked.get(),
        partialMultilineClosed.get(),
        paramWithLogMarker.get(),
        fallbackPairingDelayed.get(),
        crossThreadMixBlocked.get(),
        unrecognizedSqlLine.get(),
    )

    /**
     * 返回「自上次上报以来的增量」描述；无增量返回 null（调用方按周期调用，零增量零输出）。
     */
    @Synchronized
    fun takeDeltaReport(): String? {
        val now = counts()
        if (now.contentEquals(reported)) return null
        val delta = LongArray(now.size) { now[it] - reported[it] }
        reported = now
        return "控制台解析诊断（增量）：参数行无上下文丢弃=${delta[0]} 空参数行拦下=${delta[1]} " +
            "多行残句闭合=${delta[2]} 参数含日志特征被拒=${delta[3]} " +
            "兜底桶配对延迟=${delta[4]} 跨线程错配拦下=${delta[5]} 未识别的 SQL 形态行=${delta[6]}（累计 ${now.joinToString("/")}）"
    }

    /** 全量归零（测试/会话切换）。 */
    @Synchronized
    fun reset() {
        paramWithoutContext.set(0)
        swallowedLineBlocked.set(0)
        partialMultilineClosed.set(0)
        paramWithLogMarker.set(0)
        fallbackPairingDelayed.set(0)
        crossThreadMixBlocked.set(0)
        unrecognizedSqlLine.set(0)
        sawIbatisForm = false
        reported = longArrayOf(0, 0, 0, 0, 0, 0, 0)
    }
}
