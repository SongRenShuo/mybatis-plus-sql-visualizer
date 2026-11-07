package cn.song.mybatisplus.visualizer.log

import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 日志条目缓冲（T-M31-03，纯逻辑，不依赖平台）：条目列表（最早在前、上限裁剪）、
 * 暂停态、去重（对齐 MybatisCodeHelper-Pro「移除重复的sql」——相同 SQL 在窗口内重复丢弃）、
 * 监听器登记——线程安全（任意线程可 [addEntry]，监听器回调不在此处调度）。
 *
 * T-PerfBatch 监听器分层：原先单一 `notifyChanged` 通道同时承载三种及时性语义——
 * 首条自动开窗（须即时）、徽章计数（须即时）、列表内容（可批量）——任何「取消逐条通知」
 * 的批量节流都会同时切断前两者（初版全量回归 11 败的根因）。分层后拆为三个独立出口：
 * [onEntryAccepted]（每次成功入队，自动展开等「有新 SQL」语义，即时）、
 * [onDedupeCountsChanged]（徽章计数变更，即时）、内容监听器（[addListener]，仅列表内容
 * 变更，入队路径走「数量 20 + 时间 200ms」节流，未达阈值条目由 [onContentNotifySuppressed]
 * 兜底给管理器的幂等 flush）。
 *
 * 与平台壳 [SqlLogManager] 分离：本类可纯 JUnit 测试（无 Application 依赖）。
 */
class SqlLogEntryBuffer(
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    /** 最大条目数（可变——运行时动态调整上限，超出自动淘汰最早）。 */
    @Volatile
    var maxEntries: Int = maxEntries

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val entries = ArrayDeque<LogSqlEntry>()

    /** T-LogRowNumber：会话内捕获序号计数器（成功入队时分配；启动项目/手动清空归零。可从任意线程调用故原子）。 */
    private val sequenceCounter = AtomicLong(0)

    /** T-Perf-03（自 dev 移植）：自上次 drain 以来的头部淘汰条数（面板增量刷新对齐用；drain 约定单消费方——面板 EDT 刷新）。 */
    private val evictedSinceDrain = java.util.concurrent.atomic.AtomicInteger()

    /** T-PerfBatch：内容通知节流原子量——时间窗内累计的未通知条数与上次通知时刻（无 Timer）。 */
    private val pendingContentNotify = AtomicInteger(0)
    private val lastContentNotifyNanos = AtomicLong(0)

    // ---------- T-SqlListCatchup 券B：计数变更键增量出口 ----------

    /**
     * T-SqlListCatchup 券B：内容通知节流窗内累积的「计数变更键」集合（键 = dedupeKey）。
     * 内容侧每发生一次会改变展示层计数的队列变更（新条目入队 / ⨉N 重复丢弃 / 头部淘汰 /
     * removeEntry），把受影响的 dedupeKey 记进来；面板 [drainChangedKeys] 消费后按键增量更新
     * 徽章累积 map，不再每拍全量重算。
     *
     * 有界性（照 [dedupeKeyPresence] KDoc 口径）：风暴期同键反复变化**只记键不记次数**（Set 语义），
     * 集合上限以现存键域封顶——键随淘汰/清空同步出域（[clear] 一并清空），不引入新增长面。
     * 自身锁独立（`synchronized(changedCountKeys)`），登记发生在 `synchronized(entries)` 内
     * （与 presence 维护同锁登记），锁序恒为 entries → changedCountKeys，无反向获取。
     */
    private val changedCountKeys = HashSet<String>()

    /**
     * T-SqlListCatchup 券B：计数全量重建标记——增量口径的**失效事件点**置位，面板 drain 到后
     * 回退全量重算一次（对齐 P6 既有「已知事件点全量重建」口径）。置位点及各自为什么必须全量：
     * - [dedupeEnabled] 翻转：false 期间 presence 冻结不维护，true 时索引全量重建——键域与
     *   presence 都可能整体跳变，增量键集表达不了；
     * - [rebuildDedupeIndexAfterHandoff]：接管沿索引按 grace 截断只留新鲜键而条目仍留队，
     *   索引键域与 presence 口径分叉，按键取 structureKey 的出口可能失配；
     * - [clear]：全清事件，累积 map 必须与 buffer 双双清零；
     * - 淘汰把键**扫出键域**（presence 计数归零摘键）：扫键是 F83 短路与 P6 索引重指的历史缺陷
     *   高发点（dedupeIndex 重建 bug 家族），键域收缩时回退全量一次，把增量基线重新锚定，
     *   成本 O(N)（N≤200，且该拍本就有 snapshot 全量），正确性换极低代价；
     * - 去重关闭期间的入队/淘汰与不可去重条目（ShardingSphere）变更：presence 口径外，
     *   增量键集无法表达；
     * - [dedupeCounts] 键上限溢出（静默淘汰最旧键）：键集无法表达「哪个键被逐出」。
     *
     * 原子量：置位发生在任意生产线程（含 entries 锁内），消费在面板 EDT——getAndSet 保证
     * 「置位落在消费窗口内不丢标记」（标记落两次 drain 之间时本拍即走全量，不产生陈旧窗口）。
     */
    private val countFullRebuildPending = AtomicBoolean(false)

    @Volatile
    var paused: Boolean = false
        internal set

    /** 去重开关（默认开——用户实测后要求默认勾选，T-M31.13）。
     *  T-SqlLogPerf P6：从 false 翻 true 时全量重建 [dedupeIndex]——关闭期间入队不走索引维护，
     *  重开必须补账，否则新窗口内的重复漏判（等价性红线，见 isDuplicate 注释）。 */
    @Volatile
    var dedupeEnabled: Boolean = true
        set(value) {
            val changed = value != field
            field = value
            if (changed) {
                // 券B：开关翻转是计数增量口径的失效点——false 期间 presence 冻结不维护、true 时索引
                // 全量重建，面板累积 map 都须回退全量重算一次（P6 重建口径对齐，见 countFullRebuildPending KDoc）。
                countFullRebuildPending.set(true)
                if (value) {
                    synchronized(entries) { rebuildDedupeIndexLocked() }
                }
            }
        }

    /**
     * T-SqlLogPerf P6（20261006-1020 计划）：dedupeKey → 队内**保留实例**索引——isDuplicate
     * 从锁内 O(n) 全表扫描（65 条/秒压测下每条线性扫 200 条 + 三通道写线程与 EDT snapshot 抢锁）
     * 降为 O(1) 查表。窗口判定取每键**最大时间戳实例**与旧 firstOrNull 全扫逐字等价：
     * 「存在同键条目使 now-timestamp < 窗口」⟺ 最大时间戳落窗口内。
     * T-AgentCaptureProvenance O-147 W1：值由时间戳升级为**保留实例**本身——同桶信息融合需要
     * O(1) 定位被保留的条目（时间戳随实例可读，两者恒一致）。
     *
     * 维护面（全部须在 `synchronized(entries)` 内）：入队 upsert / 头部淘汰回扫 / 融合原位替换 /
     * dedupeExisting 与 dedupeEnabled 翻 true 全量重建 / clear 清空 / removeEntry 单键重算。
     * dedupeEnabled=false 期间不维护（不触 dedupeKey lazy——T-PerfSweep9 同款口径），重开时补账。
     */
    private val dedupeIndex = HashMap<String, LogSqlEntry>()

    /**
     * T-PerfSweep26-01（F83）：dedupeKey → **队内现存条数**计数——淘汰回扫的短路判据。被淘汰者恰为
     * 索引最新时，仅当同键仍有余条（计数 >0）才付 O(maxEntries) 全队反扫重指次新；唯一键稳态计数
     * 归零，直接摘索引跳过必空的反扫（原状：唯一键下每接受一条新 SQL 都在 `synchronized(entries)`
     * 内付一次 O(maxEntries) 反扫且结果必空）。
     *
     * 有界性：键域 = [dedupeIndex] 键域（canDedupe 条目的 dedupeKey），随索引同步增删、[clear] 一并
     * 清空，不引入新增长面。维护面（全部在 `synchronized(entries)` 内、与索引同条件）：接受入队 +1 /
     * 头部淘汰 -1（先减后判）/ removeEntry -1 / [rebuildDedupeIndexLocked] 与
     * [rebuildDedupeIndexAfterHandoff] 全量重建 / [clear] 清空。dedupeEnabled=false 期间不维护也不读
     * （stale 值零消费，重开时随索引补账）；接管沿重建按「队内现存」口径补账（索引按 grace 截断只留
     * 新鲜键，条目仍留队的陈旧键计数不缺账）。融合（mergeDuplicateEntry）原位替换不改键
     * （restoredSql 保留），计数不动。
     */
    private val dedupeKeyPresence = HashMap<String, Int>()

    /**
     * T-CrossChannelDedupe（20261105）：**跨通道等价索引**——crossKey → 队内**保留实例**。
     *
     * 为什么需要它：[dedupeIndex] 的键是「参数还原后的 SQL 指纹」（[LogSqlEntry.dedupeKey]），而两个
     * 通道各有一套还原器（控制台 `LogSqlRestorer` / agent `AgentEventAdapter.restore`），布尔参数、
     * 日期族、注释内 `?`、参数个数守卫、全限定类型名任一处口径分叉就产出不同指纹——同一次执行于是
     * 各占一行（用户报障：同一 INSERT 一条带耗时/位置、一条全是 `—`）。本索引改用**两侧同源、不经
     * 参数还原**的原材料：[logTimeKey]（占位符版 rawSql 指纹）+ 参数值签名，即 T-AgentLogTime 已验证
     * 能跨通道命中的口径。
     *
     * 只在 [dedupeIndex] 未命中时才查（命中即走既有同键融合，零额外成本）；命中后还须满足
     * [canFoldAcrossChannels]（两侧通道不同、时间差 ≤ [CROSS_CHANNEL_WINDOW_MS]）才折叠。
     *
     * 维护面与 [dedupeIndex] **完全同锁同生命周期**（全部在 `synchronized(entries)` 内）：入队 upsert /
     * 头部淘汰摘键 / 融合原位替换重指 / [removeEntry] 单键重算 / [clear] 清空 /
     * [rebuildDedupeIndexLocked] 与 [rebuildDedupeIndexAfterHandoff] 全量重建。
     *
     * 有界性：键域随条目淘汰同步摘除（索引存该键**最后入队**实例、淘汰取**最旧**，索引命中被淘汰者
     * 即说明队内已无同键余条，无需反扫）+ [purgeExpiredCrossChannelLocked] 时间窗惰性清理（防长会话里
     * 「条目仍留队但早已超窗」的键驻留）。
     *
     * 误配面（计划 §五-4 已裁决可接受）：同一语句 5s 内以相同参数执行多次时，跨通道配对按 FIFO 可能
     * 错位——融合只补空不覆盖，错位只影响毫秒级耗时与同一条语句同一栈帧的位置，不产生错误数据。
     */
    private val crossChannelIndex = HashMap<String, LogSqlEntry>()

    /** T-CrossChannelDedupe：跨通道等价键（两侧同源原材料，见 [crossChannelIndex]）。 */
    private fun crossChannelKey(entry: LogSqlEntry): String =
        logTimeKey(entry) + '\u0002' + logTimeParamSignature(entry.params)

    /** T-CrossChannelDedupe：两侧时间差是否落在跨通道折叠窗内（毫秒级同一次执行）。 */
    private fun withinCrossChannelWindow(a: LogSqlEntry, b: LogSqlEntry): Boolean {
        val delta = b.timestamp - a.timestamp
        return (if (delta < 0) -delta else delta) <= CROSS_CHANNEL_WINDOW_MS
    }

    /**
     * T-CrossChannelDedupe：跨通道折叠判据——两侧 `captureProvenance` 不同（[CaptureProvenance.UNKNOWN]
     * 视为可折叠：旧条目/历史读回未打标，不该阻断兜底）且时间差在窗内。
     *
     * 同通道已知标记一律不折叠：同通道同 crossKey 而异 dedupeKey 不是「两份观测」，是同一通道内的两条
     * 不同语句（或同一渲染器两次不同输出），折叠会吞掉真实条目。
     */
    private fun canFoldAcrossChannels(existing: LogSqlEntry, incoming: LogSqlEntry): Boolean {
        val sameKnownChannel = existing.captureProvenance != CaptureProvenance.UNKNOWN &&
            incoming.captureProvenance != CaptureProvenance.UNKNOWN &&
            existing.captureProvenance == incoming.captureProvenance
        if (sameKnownChannel) return false
        return withinCrossChannelWindow(existing, incoming)
    }

    /**
     * T-CrossChannelDedupe：跨通道索引的时间窗惰性清理（每次入队顺带扫一遍，键域 ≤ 队内条目数）。
     * 超窗条目即使仍留队，也永远不可能是下一次入队的配对候选（[withinCrossChannelWindow] 必然拒绝），
     * 提前摘键只为把长会话的驻留面压到「窗内键」。
     */
    private fun purgeExpiredCrossChannelLocked(nowMs: Long) {
        if (crossChannelIndex.isEmpty()) return
        val it = crossChannelIndex.values.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().timestamp > CROSS_CHANNEL_WINDOW_MS) it.remove()
        }
    }

    /** java.util.ArrayDeque 无按下标替换：按同一性重建（融合低频，O(n) 锁内可接受）。 */
    private fun replaceEntryLocked(target: LogSqlEntry, replacement: LogSqlEntry) {
        val rebuilt = ArrayDeque<LogSqlEntry>(entries.size)
        for (e in entries) rebuilt.add(if (e === target) replacement else e)
        entries.clear()
        entries.addAll(rebuilt)
    }

    /** T-CrossChannelDedupe：融合换实例后把跨通道索引重指到队内新实例（键不变——rawSql/params 不参与融合）。 */
    private fun repointCrossChannelLocked(previous: LogSqlEntry, merged: LogSqlEntry) {
        val ck = crossChannelKey(previous)
        if (crossChannelIndex[ck] === previous) crossChannelIndex[ck] = merged
    }

    /** T-PerfSweep26-01（F83）测试缝：淘汰路径全队反扫（`entries.lastOrNull` 扫描）执行计数——
     *  唯一键稳态下应恒 0，仅测试断言用（V38 计数缝同款形态），生产零读。 */
    private val evictionRescanCount = AtomicInteger()
    internal val evictionRescanCountForTest: Int get() = evictionRescanCount.get()

    // UI013：Logic 未绑定，文本相同不能证明同一次执行；Actual 仅属该次配对，禁止裁剪或融合。
    // 反向验证锚点：临时改为 true，UI013 自动/手动保真用例必须变红。
    private fun canDedupe(entry: LogSqlEntry): Boolean = entry.source != SqlSource.SHARDINGSPHERE

    private fun rebuildDedupeIndexLocked() {
        // 券B：索引随队全量重建（dedupeExisting 裁剪 / 开关翻 true）——presence 同拍全量重算，
        // 面板累积 map 必须同拍回退全量锚定（增量键集表达不了重建前后的键域跳变）。
        countFullRebuildPending.set(true)
        dedupeIndex.clear()
        rebuildDedupeKeyPresenceLocked() // F83：计数随索引一并全量重建
        crossChannelIndex.clear() // T-CrossChannelDedupe：跨通道索引同锁同生命周期全量重建
        for (e in entries) {
            if (!canDedupe(e)) continue
            val latest = dedupeIndex[e.dedupeKey]
            if (latest == null || e.timestamp > latest.timestamp) dedupeIndex[e.dedupeKey] = e
            crossChannelIndex[crossChannelKey(e)] = e
        }
    }

    /**
     * F83：键级在队计数全量重建——按「队内现存」口径统计（不看索引成员资格：接管沿重建只把陈旧键
     * 摘出索引、条目仍留队，计数必须把这类在队不在索引的实例也补上）。调用方须已持 `synchronized(entries)`。
     */
    private fun rebuildDedupeKeyPresenceLocked() {
        dedupeKeyPresence.clear()
        for (e in entries) {
            if (!canDedupe(e)) continue
            dedupeKeyPresence[e.dedupeKey] = (dedupeKeyPresence[e.dedupeKey] ?: 0) + 1
        }
    }

    /**
     * T-AttachZeroEvent P3（2026-10-09 现场复盘）+ T-AgentCaptureProvenance O-147 W2-补（2026-10-14 真机复验）：
     * **通道纪元重建**——增强捕获接管沿（桥让位、agent 接管，`SqlCaptureGate.paused` false→true）由
     * [AgentManager.updateOnline] 调用。
     *
     * 为什么重建：dedupeKey 跨通道统一（agent 与桥同 SQL 产出同指纹，AgentEventAdapter 契约），目的是
     * 折叠接管/回落**重叠期**的双通道双报；桥一旦封停，重叠窗语义即结束——接管前桥纪元的索引不得再吞
     * agent 纪元的首批同键事件（现场：接管前 60s 内桥刚捕过同端点，接管后同 SQL 的 agent 事件全部落窗口
     * 内被当重复丢弃，面板零新条目、「attach 即生效」看似失效）。
     *
     * **O-147 W2-补：由「全清」改为「只清陈旧键、保留新鲜键」**。全清会把**同一次执行的双通道副本**也一起
     * 切断：控制台条目在接管沿前几毫秒入库（2026-10-14 真机复验实测 6ms 窗口——`捕获归因: 通道=CONSOLE_EP`
     * 入库 → 接管沿清索引 → 随后同一条 SQL 的 agent 事件不再判重、新开一行）⇒ 用户看到「一行带源码、
     * 一行 `—` 无源码」的原始报障形态。改为：`timestamp >= now - graceMs` 的键保留（同一次执行的控制台
     * 副本，agent 副本到达时按 [mergeDuplicateEntry] 融合升级为可跳转行），更旧的键清掉（P3 原意保留——
     * 陈旧桥纪元条目不得吞 agent 首批，agent 新观测照常成为新行）。
     *
     * 只动索引，**不清条目、不清 ⨉N 徽章计数**（历史留痕）；此后 agent↔agent 短窗重复照常折叠。
     * 回落沿（true→false）**不**调用本方法——理由见 [AgentManager.updateOnline] 尾注。
     *
     * @param graceMs 保留窗（默认 [HANDOFF_MERGE_GRACE_MS]）：同一次执行的双通道副本间隔为毫秒级，
     *   而 60s 去重窗内的陈旧条目不得入窗（否则 P3 症状回归）。
     * @param nowMs 判定基准（默认系统时钟；测试注入合成时间戳，保持与既有合成时间戳用例的确定性）。
     */
    fun rebuildDedupeIndexAfterHandoff(
        graceMs: Long = HANDOFF_MERGE_GRACE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(entries) {
            // 券B：接管沿重建是计数增量口径的失效点（grace 截断让索引键域与 presence 分叉——
            // 条目仍留队的陈旧键被摘出索引，按键取 structureKey 的出口会失配），面板回退全量一次。
            countFullRebuildPending.set(true)
            dedupeIndex.clear()
            // T-CrossChannelDedupe：跨通道索引与 dedupeIndex 同口径——接管沿同样只留「新鲜键」。
            // 陈旧键若留在跨通道索引里，接管后 agent 首批事件会被折进桥纪元条目（P3 症状的跨通道形态）。
            crossChannelIndex.clear()
            // T-DedupeDecouple：跨通道索引与去重开关解耦——去重关闭期间它照样要重建，
            // 否则「关掉 SQL 去重」会让兜底整段失效（catch 只在 dedupeEnabled 时建索引）。
            if (dedupeEnabled) rebuildDedupeKeyPresenceLocked() // F83：计数按队内现存全量补账（与索引的 grace 截断口径不同，条目仍留队的陈旧键不缺账）
            val cutoff = nowMs - graceMs
            for (e in entries) {
                if (!canDedupe(e) || e.timestamp < cutoff) continue
                if (dedupeEnabled) {
                    val latest = dedupeIndex[e.dedupeKey]
                    if (latest == null || e.timestamp > latest.timestamp) dedupeIndex[e.dedupeKey] = e
                }
                crossChannelIndex[crossChannelKey(e)] = e
            }
        }
    }

    /**
     * 去重丢弃计数（T-Perf-19：键 = restoredSql 指纹 [sqlFingerprint]，非 SQL 原文——
     * 原文键在条目淘汰后文本永久驻留，长会话可达数十 MB）：去重开启期间每丢弃
     * 一条相同 SQL 计数 +1，供列表「⨉N」徽章展示该 SQL 被去掉过几次。
     * 仅在 [dedupeEnabled] 为 true 时累计；[clear] 与 [resetDedupeCounts] 归零。
     *
     * T-PerfSweep F3 有界化：插入序 LinkedHashMap + 上限 [MAX_DEDUPE_COUNT_KEYS] 淘汰最旧
     * （原 ConcurrentHashMap 无界，条目淘汰后计数不随移除，十万级去重会话可累积百万级键）。
     * 徽章「本次去重开启以来的总丢弃量」语义在上限窗口内不变；超限淘汰最旧属极端会话的
     * 软降级（>4096 种不同 SQL 且都发生过重复），只丢最旧键的计数，列表内容不受影响。
     */
    private val dedupeCounts = LinkedHashMap<String, Int>()

    /** 计数登记（去重丢弃/裁剪共用）；超限淘汰最旧键。 */
    private fun recordDedupeDrop(key: String) {
        synchronized(dedupeCounts) {
            dedupeCounts[key] = (dedupeCounts[key] ?: 0) + 1
            if (dedupeCounts.size > MAX_DEDUPE_COUNT_KEYS) {
                val it = dedupeCounts.keys.iterator()
                it.next()
                it.remove()
                // 券B：键上限溢出静默淘汰最旧键——变更键集表达不了「哪个键被逐出」，面板回退全量对齐。
                countFullRebuildPending.set(true)
            }
        }
    }

    /** 去重窗口（毫秒）：相同还原 SQL 在该窗口内重复出现时丢弃后到者。 */
    @Volatile
    var dedupeWindowMs: Long = DEFAULT_DEDUPE_WINDOW_MS

    /** 过滤关键字（T-M31.14，`;` 分隔后传入）：还原 SQL 包含任一关键字则丢弃。 */
    @Volatile
    var filterKeywords: List<String> = emptyList()

    /**
     * T-SqlSuppress（O-69）：屏蔽规则指纹集合（各规则规范化文本的 [sqlFingerprint]，由
     * [SqlSuppressRuleManager] 的项目订阅者 [SqlLogManager] 整体替换写入）。入队闸门命中即丢弃——
     * 语义与关键字过滤一致（「从未捕获过」：不进去重计数、不占捕获序号、不触发任何内容/徽章/
     * 入队回调）。空集时短路零成本（不触 `entry.dedupeKey` 的 lazy 计算——无规则的常态）。
     */
    @Volatile
    var suppressKeys: Set<String> = emptySet()

    /** F20（T-PerfSweep9）：键集中是否存在结构口径键——与 [suppressKeys] 同点写入（SqlLogManager
     *  订阅者同步刷写），false 时结构键第二查找整体跳过（structureKey lazy 不触达）。 */
    @Volatile
    var hasStructureKeys: Boolean = false

    /**
     * T-SqlSuppress：屏蔽闸门命中回调（参数 = 命中指纹键）——[SqlSuppressRuleManager.recordHit]
     * 命中计数的唯一出口（管理对话框「已拦截 N 次」，D4）。专用通道，不与既有回调混用：
     * 被拦条目不触发 onEntryAccepted/onEntryStored/onDedupeCountsChanged/内容监听（从未捕获过）。
     */
    var onSuppressedHit: ((String) -> Unit)? = null

    /** 条目被淘汰时的回调（最早条目超出上限时被移除）；用于自动保存历史 SQL（F-43）。 */
    var onEvicted: ((LogSqlEntry) -> Unit)? = null

    /**
     * T-SqlHistoryFull：成功入队回调（非暂停/过滤/去重丢弃）——历史「全量」写入出口，带条目标。
     * 与 [onEntryAccepted] 的分工：onEntryAccepted 是「有新 SQL」即时语义（无参数）；
     * 本回调把成功入队条目交给写盘方（自动保存），每条成功入队触发一次。
     */
    var onEntryStored: ((LogSqlEntry) -> Unit)? = null

    /** O-147：已存条目的元数据升级，不是新捕获；锁外回调，不追加第二条历史。 */
    var onEntryMerged: ((LogSqlEntry, LogSqlEntry) -> Unit)? = null

    /** T-PerfBatch：每次成功入队（非暂停/过滤/去重丢弃）即回调——「有新 SQL」语义（自动展开）
     * 的即时性出口；去重丢弃分支不回调（重复 SQL 不是新 SQL，且空缓冲不可能产生重复）。 */
    var onEntryAccepted: (() -> Unit)? = null

    /** T-PerfBatch：徽章计数变更出口（去重丢弃/裁剪计数/关闭清零/清空列表归零）——
     * ⨉N 徽章必须即时刷新（用户实测「不及时」是缺陷），不走内容节流。 */
    var onDedupeCountsChanged: (() -> Unit)? = null

    /** T-PerfBatch：内容通知被节流抑制时的兜底出口——管理器把它接到幂等 flush 上，
     * 保证未达阈值条目最迟在下一次 EDT flush 可见（flush 读当下快照，不丢条目）。 */
    var onContentNotifySuppressed: (() -> Unit)? = null

    /** 新条目入队（最早在前，追加到队尾；暂停态丢弃；过滤关键字命中丢弃；去重窗口内相同 SQL 丢弃并计数；超过上限裁掉最旧）。
     *
     * T-PerfBatch 分层通知：去重丢弃分支只走 [onDedupeCountsChanged]（徽章即时，列表内容未变）；
     * 成功入队走 [onEntryAccepted]（即时）+ 内容通知节流（[notifyContentChangedThrottled]）。
     *
     * T-Perf-06（自 dev 移植）：去重判定与入队合并进同一 `synchronized(entries)` 临界区——此前 [isDuplicate]
     * 在锁外无锁遍历 ArrayDeque，与 EDT 清空/删除（clear/removeEntry）并发修改会触发
     * ConcurrentModificationException，经 LogFilterProxyHandler（无 catch）上浮为 IDE 红色报错弹窗。 */
    /** 普通入队：受「SQL 日志捕获」开关门控（`paused` 期间丢弃）。 */
    fun addEntry(entry: LogSqlEntry): Boolean = addEntryInternal(entry, respectPaused = true)

    /**
     * 审计入队：**不受「SQL 日志捕获」开关约束**（D1 定案，2026-09-22 用户）。
     *
     * MCP 执行类工具（当前唯一产出点 `sqlv_explain`）在真实数据源上跑过的语句属**审计留痕**，
     * 其可用性只由 MCP 工具自身的开关决定（设置页「启用 SQL Visualizer MCP 工具」＋工具级禁用清单，
     * 见 `McpToolGate`）——与「SQL 日志」页签的捕获按钮无关。故开关关着（含用户显式停止后的
     * `paused=true`）时审计条目**照样落列表**。
     *
     * 其余门与 [addEntry] 完全一致：关键字过滤 / 去重窗口 / 上限淘汰——它们是列表展示偏好，不是开关。
     */
    fun addAuditEntry(entry: LogSqlEntry): Boolean = addEntryInternal(entry, respectPaused = false)

    private fun addEntryInternal(entry: LogSqlEntry, respectPaused: Boolean): Boolean {
        if (respectPaused && paused) return false
        if (isFiltered(entry.restoredSql)) return false
        // T-SqlSuppress（O-69）：屏蔽规则命中 → 入队前丢弃（isFiltered 之后、去重/上限之前——
        // 「从未捕获过」：不进去重计数、不占捕获序号、不触发任何内容回调；命中计数走
        // [onSuppressedHit] 专用出口）。空集短路：无规则时零成本，不触 dedupeKey lazy 计算。
        // 【反向验证探针锚点】注释掉下面 if 块 = 摘除屏蔽闸门，SqlLogEntryBufferTest 屏蔽用例必红。
        // T-SqlSuppressUx 二段：精确键（默认口径规则）或结构键（手动开启结构匹配的规则）任一命中即拦；
        // 命中键回传 recordHit 归账到对应规则。空集短路下 structureKey 的 lazy 亦不被触达。
        // F20（T-PerfSweep9）：结构键独立门控——键集里无任何结构口径键（SqlSuppressRuleManager 的
        // 「(结构)」后缀约定）时跳过第二查找，避免「全部精确口径规则」形态每条捕获白付一次
        // structureKey lazy（O(n) 字面量参数化扫描 + StringBuilder 分配）。
        if (suppressKeys.isNotEmpty()) {
            val hitKey = when {
                entry.suppressKey in suppressKeys -> entry.suppressKey
                hasStructureKeys && entry.structureKey in suppressKeys -> entry.structureKey
                else -> null
            }
            if (hitKey != null) {
                onSuppressedHit?.invoke(hitKey)
                return false
            }
        }
        var duplicate = false
        var mergeChanged = false
        var mergeDiagExisting: LogSqlEntry? = null
        var mergeDiagMerged: LogSqlEntry? = null
        // T-CrossChannelDedupe：跨通道兜底折叠的锁外诊断载荷——只放指纹前缀与通道，绝不含 SQL 原文/参数值
        var crossMerged = false
        var crossDiagKey: String? = null
        var crossDiagExisting: LogSqlEntry? = null
        var crossDiagMerged: LogSqlEntry? = null
        var crossUnfolded = false
        val evictedEntries = mutableListOf<LogSqlEntry>()
        var storedEntry: LogSqlEntry? = null
        synchronized(entries) {
            if (dedupeEnabled && isDuplicate(entry)) {
                duplicate = true
                // 券B：重复丢弃走 recordDedupeDrop（⨉N 计数 +1），登记变更键供面板按键重读徽章计数；
                // 同桶融合（mergeChanged）不改 restoredSql → dedupeKey/structureKey 都不变，键登记足够。
                markCountKeyLocked(entry.dedupeKey)
                // 【反向验证探针锚点】T-AgentCaptureProvenance O-147 W1：注释掉下方 if 块 =
                // 摘除「同桶信息融合」——SqlLogEntryBufferTest 的「控制台先到_agent后到_融合升级」
                // 用例必红（保留条目缺源码/耗时/库标，跳转仍 MissingSource），恢复后转绿。
                val storedExisting = dedupeIndex[entry.dedupeKey]
                if (storedExisting != null) {
                    val merged = mergeDuplicateEntry(storedExisting, entry)
                    if (merged != storedExisting) {
                        replaceEntryLocked(storedExisting, merged)
                        dedupeIndex[entry.dedupeKey] = merged
                        // T-CrossChannelDedupe：跨通道索引持有的是**队内实例**——融合换实例后同步重指，
                        // 否则后续跨通道兜底会融合进一个已不在队内的陈旧副本（升级丢失）。
                        repointCrossChannelLocked(storedExisting, merged)
                        mergeChanged = true
                        mergeDiagExisting = storedExisting
                        mergeDiagMerged = merged
                    }
                }
            } else {
                // T-CrossChannelDedupe（20261105）：dedupeKey 未命中时的**跨通道等价键兜底**。
                // 两侧还原器口径分叉（布尔/日期族/注释内 ?/参数个数守卫/全限定类型名）会让同一次执行产出
                // 两个不同 dedupeKey，同键分支永远折不上——本分支用「占位符版 rawSql + 参数值签名」再判一次。
                // 只在未命中时计算（命中即返回，热路径零额外成本）。
                // T-CrossChannelDedupe：跨通道兜底**不依赖** [dedupeEnabled]——它折叠的是「同一次执行
                // 的两份观测」，不是「重复执行」。绑在用户去重开关上会让「关掉 SQL 去重」直接退化成
                // 「同一次执行出现两条」（一条带耗时位置、一条全空），即 T-DedupeDecouple 的真机报障：
                // 状态栏「控制台已捕获 N / 增强已收 M」并存 + 列表里出现无字段副本。
                val crossKey = if (canDedupe(entry)) crossChannelKey(entry) else null
                val crossHit = if (crossKey != null) crossChannelIndex[crossKey] else null
                val crossDivergent = crossHit != null && crossHit.dedupeKey != entry.dedupeKey
                // 【反向验证探针锚点】T-CrossChannelDedupe：注释掉下面这个 if 分支 = 摘除跨通道兜底折叠，
                // CrossChannelDedupeTest 的布尔/日期族/注释内 ?/全限定类型名/先后到 用例必红（同一次执行留两行），
                // 恢复后转绿。
                //
                // T-DedupeDecouple：折叠条件**不再要求 crossDivergent**（dedupeKey 不同）。原判据假定
                // 「同键副本由上面的同键去重分支处理」，但那条分支受 dedupeEnabled 门控——用户关掉
                // 「SQL 去重」后，键相同的跨通道副本两边都不管，同一次执行照样两条。去重开启时同键分支
                // 先命中、根本走不到这里，故去掉该条件不改变既有路径。
                if (crossKey != null && crossHit != null && canFoldAcrossChannels(crossHit, entry)) {
                    // 兜底折叠：复用同键融合（补空不覆盖、通道归并 MERGED），原位替换不新增行。
                    // 不计 recordDedupeDrop——这不是「重复 SQL 被丢弃」，是同一次执行两份观测的合并。
                    crossMerged = true
                    crossDiagKey = crossKey
                    crossDiagExisting = crossHit
                    val merged = mergeDuplicateEntry(crossHit, entry)
                    if (merged !== crossHit) {
                        replaceEntryLocked(crossHit, merged)
                        crossChannelIndex[crossKey] = merged
                        // 同键索引若指向被替换实例，同步重指（键不变：融合不动 restoredSql）
                        if (dedupeIndex[merged.dedupeKey] === crossHit) dedupeIndex[merged.dedupeKey] = merged
                        crossDiagMerged = merged
                    }
                } else {
                    // §4.3 哨兵：同 crossKey、异 dedupeKey、同在窗内，但折叠条件不满足（同通道已知标记）
                    // ——「还原器又分叉了且兜底也接不住」的长期证据行。只含指纹前缀与通道。
                    if (crossKey != null && crossHit != null && crossDivergent &&
                        withinCrossChannelWindow(crossHit, entry)
                    ) {
                        crossUnfolded = true
                        crossDiagKey = crossKey
                        crossDiagExisting = crossHit
                    }
                    // T-LogRowNumber：入队时分配捕获序号（去重丢弃/暂停/过滤不占号，列表不跳号）
                    val added = addNumbered(entry)
                    storedEntry = added
                    entries.addLast(added)
                    if (dedupeEnabled && canDedupe(added)) {
                        // T-SqlLogPerf P6：索引 upsert（dedupeKey lazy 首触与旧版 isDuplicate 同价，
                        // 仅去重开启时支付）
                        dedupeIndex[added.dedupeKey] = added
                        // F83：键级在队计数 +1（与索引同条件同锁维护）
                        dedupeKeyPresence[added.dedupeKey] = (dedupeKeyPresence[added.dedupeKey] ?: 0) + 1
                        // 券B：在队计数 +1，登记变更键供面板按 presence 净差增量（同键入队与后续淘汰的
                        // 增减在 drain 间折叠为一次净变化，无悬挂余量）。
                        markCountKeyLocked(added.dedupeKey)
                    } else {
                        // 券B：去重关闭期间 presence 不维护 / 不可去重条目（ShardingSphere）不在
                        // presence 口径内——增量键集无法表达，面板回退全量重算（与全量重算的现状同价）。
                        countFullRebuildPending.set(true)
                    }
                    // T-DedupeDecouple：跨通道索引与去重开关**解耦**——去重关闭时它照样维护，
                    // 否则兜底查不到候选（真机：关掉「SQL 去重」后同一次执行又出现两条）。
                    if (canDedupe(added)) {
                        crossChannelIndex[crossChannelKey(added)] = added
                        purgeExpiredCrossChannelLocked(System.currentTimeMillis())
                    }
                    while (entries.size > maxEntries) {
                        val evicted = entries.removeFirst()
                        if (dedupeEnabled) {
                            val k = evicted.dedupeKey
                            // F83：键级在队计数随淘汰同步 -1（canDedupe=false 条目从未入计）。先减后判：
                            // 淘汰者自身出队后剩余 >0 才算「队内仍有同键余条」。
                            if (canDedupe(evicted)) {
                                val remaining = (dedupeKeyPresence[k] ?: 0) - 1
                                if (remaining > 0) dedupeKeyPresence[k] = remaining else dedupeKeyPresence.remove(k)
                                // 券B：在队计数 -1，登记被淘汰键（面板等价性用例断言 drain 集合包含被淘汰键）。
                                markCountKeyLocked(k)
                                if (remaining <= 0) {
                                    // 券B：键被扫出键域（presence 摘键）——扫键是 F83 短路与 P6 索引重指的
                                    // 历史缺陷高发点，回退全量重算一次把增量基线重新锚定（成本 O(N)，
                                    // N≤200，且该拍本就有 snapshot 全量；同键反复入队出队不扫键，走增量）。
                                    countFullRebuildPending.set(true)
                                }
                            } else {
                                // 券B：不可去重条目淘汰不在 presence 口径内——面板回退全量。
                                countFullRebuildPending.set(true)
                            }
                            // T-SqlLogPerf P6：索引存的是「最近」实例，仅当被淘汰者恰为该键最新时才需要重算；
                            // F83：队内仍有同键余条（计数 >0）才付 O(maxEntries) 反扫重指次新（超窗重复入队
                            // 形态，低频）——唯一键稳态计数归零，直接摘索引跳过必空的反扫（每接受一条省一次锁内全队扫）。
                            if (dedupeIndex[k] === evicted) {
                                if ((dedupeKeyPresence[k] ?: 0) > 0) {
                                    evictionRescanCount.incrementAndGet() // F83 测试缝：全队反扫执行计数
                                    val stillThere = entries.lastOrNull { canDedupe(it) && it.dedupeKey == k }
                                    if (stillThere != null) dedupeIndex[k] = stillThere else dedupeIndex.remove(k)
                                } else {
                                    dedupeIndex.remove(k)
                                }
                            }
                        } else {
                            // 券B：去重关闭期间 presence 不维护——面板回退全量。
                            countFullRebuildPending.set(true)
                        }
                        // T-DedupeDecouple：跨通道索引回收与去重开关解耦（去重关闭时索引仍在维护）。
                        // 索引存该键**最后入队**实例、淘汰取**最旧**——命中被淘汰者即说明队内已无同键余条。
                        if (canDedupe(evicted)) {
                            val ck = crossChannelKey(evicted)
                            if (crossChannelIndex[ck] === evicted) crossChannelIndex.remove(ck)
                        }
                        evictedEntries.add(evicted)
                        evictedSinceDrain.incrementAndGet() // T-Perf-03：头部淘汰计数（面板增量刷新）
                    }
                }
            }
        }
        if (duplicate) {
            recordDedupeDrop(entry.dedupeKey)
            onDedupeCountsChanged?.invoke() // 徽章计数变了 → 即时刷新（不走内容节流）
            // O-147 W1：融合改变了保留条目的可见元数据 → 走内容通道（列表须重绘）；仍非「新 SQL」
            // （不触发 onEntryAccepted——accepted 语义 = 新捕获），返回值保持 false 不变。
            if (mergeChanged) {
                notifyContentChangedThrottled()
                val diagExisting = mergeDiagExisting
                val diagMerged = mergeDiagMerged
                if (diagExisting != null && diagMerged != null) {
                    onEntryMerged?.invoke(diagExisting, diagMerged)
                    logCrossChannelThrottled {
                        "重复观测跨通道融合: 指纹=${entry.dedupeKey.take(8)} " +
                            "通道=${diagExisting.captureProvenance}+${entry.captureProvenance}" +
                            "→${diagMerged.captureProvenance} 升级=${mergeUpgradedAspects(diagExisting, diagMerged)}"
                    }
                }
            }
            return false
        }
        // T-CrossChannelDedupe：兜底折叠——同一次执行的两份观测合并，**不是新捕获**：返回 false
        // （不触发 onEntryAccepted）、不计 ⨉N 徽章（不调 recordDedupeDrop）；但元数据升级要落历史
        // （onEntryMerged）并重绘列表（内容通道）。
        if (crossMerged) {
            val diagExisting = crossDiagExisting
            val diagMerged = crossDiagMerged
            val diagKey = crossDiagKey
            if (diagExisting != null && diagMerged != null && diagKey != null) {
                notifyContentChangedThrottled()
                onEntryMerged?.invoke(diagExisting, diagMerged)
                logCrossChannelThrottled { crossChannelMergeDiagText(diagKey, diagExisting, entry, diagMerged) }
            }
            return false
        }
        // T-CrossChannelDedupe §4.3 哨兵：同 crossKey + 异 dedupeKey + 同窗内却未折叠（同通道已知标记）
        // ——「还原器又分叉了，且兜底也接不住」的长期证据行；条目照常入队。
        if (crossUnfolded) {
            val diagExisting = crossDiagExisting
            val diagKey = crossDiagKey
            if (diagExisting != null && diagKey != null) {
                logCrossChannelThrottled { crossChannelUnfoldedDiagText(diagKey, diagExisting, entry) }
            }
        }
        // O-147 W0：缺源码条目的通道归因诊断（限频）——「增强已开启却无法跳转」现场的直接证据行
        // （V180-1：通道=AGENT 缺源码 → 查 agent 侧栈定位；通道=CONSOLE_* → 查桥闸门为何回落）。
        // T-CaptureCatchAll 券02：取「有效封停态」而非裸 paused——活性回落解封期间桥确实在产出，
        // 打「桥封停=true」会把排障引向错误方向。
        if (entry.sourceFile == null && entry.sourceCandidates.isEmpty()) {
            logAttributionThrottled { provenanceDiagText(entry, SqlCaptureGate.bridgeSuppressed()) }
        }
        // T-Perf-12（自 dev 移植）：onEvicted 回调移出 synchronized(entries)——回调链（自动保存磁盘写）
        // 在锁内同步执行会阻塞 EDT snapshot() 抢同一把锁（控制台输出热路径互相卡）
        // T-SqlHistoryFull：被淘汰条目先经 onEntryStored 写过（入队成功即写），此处 onEvicted 不再重复消费
        evictedEntries.forEach { onEvicted?.invoke(it) }
        // T-SqlHistoryFull：成功入队即写历史——「全量」出口（含随后被淘汰的条目，它们也先进了列表）
        storedEntry?.let { onEntryStored?.invoke(it) }
        onEntryAccepted?.invoke() // 「有新 SQL」语义即时出口（自动展开等），先于内容节流
        notifyContentChangedThrottled()
        return true
    }

    /**
     * 手动触发去重裁剪（用户勾选「移除重复的sql」时）：扫描现有 entries，
     * 相同 SQL（按 [LogSqlEntry.dedupeKey] 空白规范化指纹）保留最早一条、丢弃后续；丢弃数累加到 [dedupeCounts]
     * （与运行时去重共享计数——徽章反映「本次去重开启以来的总丢弃量」，含开启前已存在的重复）。
     *
     * T-LogRowNumber 追加（2026-09-10）：手动全量裁剪**不看时间窗口**——去重窗口
     * （[dedupeWindowMs]，默认 60s）是运行时的「相同 SQL 短时间内重复打印」语义，而「点击移除重复」
     * 是用户显式的一次性全量去重，跨窗口的重复（用户不勾选去重运行很久后累积的同文本 SQL）也必须裁掉，
     * 否则点击后重复项仍在列表（用户反馈「显示不正确」）。仅按键判重，不比较时间戳。
     *
     * @return 本次裁剪移除的条目数（面板日志/测试断言）。
     */
    fun dedupeExisting(): Int {
        if (!dedupeEnabled) return 0
        var removed = 0
        synchronized(entries) {
            if (entries.size < 2) return@synchronized
            val keep = mutableListOf<LogSqlEntry>()
            for (entry in entries) {
                // T-LogDedupeMpMark：与 isDuplicate 同键（空白规范化指纹），勾选时对既有跨格式副本同样生效；
                // T-LogRowNumber 追加：手动全量裁剪不比较时间窗口（见 KDoc），纯按指纹判重。
                val duplicate = if (canDedupe(entry)) {
                    keep.firstOrNull { canDedupe(it) && it.dedupeKey == entry.dedupeKey }
                } else null
                if (duplicate != null) {
                    recordDedupeDrop(entry.dedupeKey)
                    removed++
                } else {
                    keep += entry
                }
            }
            if (removed > 0) {
                entries.clear()
                entries.addAll(keep)
                if (dedupeEnabled) rebuildDedupeIndexLocked() // T-SqlLogPerf P6：裁剪后索引随队重建
            }
        }
        if (removed > 0) {
            notifyChanged() // 用户操作：内容即时刷新（不节流）
            onDedupeCountsChanged?.invoke()
        }
        return removed
    }

    /**
     * 关闭去重：清空累计计数（语义：「关闭」= 清零；再次开启时从零起算）。
     * 若原本未开启或计数本就为空，跳过通知（避免无意义 EDT 调度）。
     * T-PerfBatch：只动徽章数据（列表内容不变）——走 [onDedupeCountsChanged]，不走内容通道。
     */
    fun disableDedupe() {
        if (!dedupeEnabled) return
        dedupeEnabled = false
        val hadCounts = synchronized(dedupeCounts) { dedupeCounts.isNotEmpty() }
        resetDedupeCounts()
        if (hadCounts) onDedupeCountsChanged?.invoke()
    }

    private fun isFiltered(sql: String): Boolean =
        filterKeywords.isNotEmpty() && filterKeywords.any { sql.contains(it, ignoreCase = true) }

    /** 去重判定（T-Perf-06：**必须在 `synchronized(entries)` 内调用**——无锁遍历 ArrayDeque 会与清空/删除并发 CME）。
     * T-LogDedupeMpMark：按 [LogSqlEntry.dedupeKey]（空白规范化指纹）比较——JdbcTemplate 双路日志副本仅差
     * 首尾空白，原文精确相等永不命中；预计算指纹串比较也快于全文 ==。
     * T-SqlLogPerf P6：O(n) 全表扫改 [dedupeIndex] O(1) 查表——「存在同键条目使 now-timestamp < 窗口」
     * ⟺ 该键最近时间戳落窗口内（最大时间戳是窗口判定的唯一有效候选，逐字等价）。 */
    private fun isDuplicate(entry: LogSqlEntry): Boolean {
        if (!canDedupe(entry)) return false
        val latest = dedupeIndex[entry.dedupeKey] ?: return false
        return entry.timestamp - latest.timestamp < dedupeWindowMs
    }

    /** 最早在前的快照（正序）。 */
    fun snapshot(): List<LogSqlEntry> = synchronized(entries) { entries.toList() }

    /**
     * 当前条目数（T-UxOnboard T-UX-03 增设）：入队热路径（onEntryAccepted 回调）需要 O(1) 判
     * 「本条是否会话首条」——snapshot() 全量拷贝在流式灌入下是 O(n²) 累积成本，不得用于热路径。
     */
    fun size(): Int = synchronized(entries) { entries.size }

    /**
     * T-Perf-03（自 dev 移植）：取走自上次调用以来的头部淘汰条数并归零。
     * 单消费方约定（面板 EDT 刷新）：与 [snapshot] 搭配，用于增量刷新的头部对齐；
     * 其他调用方调用会偷走面板的对齐信息（回退全量重建，正确性不受影响）。
     */
    fun drainEvictedCount(): Int = evictedSinceDrain.getAndSet(0)

    // ---------- T-SqlListCatchup 券B：计数快照增量出口 ----------

    /**
     * T-SqlListCatchup 券B：未消费的内容通知计数——内容通知节流窗（数量 20 + 时间 200ms 双阈值）内
     * 已入队但尚未触发内容监听器的条目数（读 [pendingContentNotify]，**只读不消费**）。
     * 追赶期「落后 N 条」量化读数的缓冲段数据源；**券 D 追赶读数消费方**
     * （`CaptureStatusBar` / `LogPerfProbe` 据此与桥通道未解析行数取大者）。
     */
    fun pendingNotifyCount(): Int = pendingContentNotify.get()

    /**
     * T-SqlListCatchup 券B：取走自上次调用以来累积的计数变更键（dedupeKey）并清空（消费即清）。
     * 节流窗内同键反复变化只记键不记次数（Set 语义），有界性见 [changedCountKeys] KDoc。
     * 单消费方约定（面板 EDT 刷新）：与 [drainEvictedCount] 同款——其他调用方调用会偷走面板的
     * 增量信息（面板回退全量重算，正确性不受影响）。
     */
    fun drainChangedKeys(): Set<String> = synchronized(changedCountKeys) {
        if (changedCountKeys.isEmpty()) emptySet()
        else {
            val drained = HashSet(changedCountKeys)
            changedCountKeys.clear()
            drained
        }
    }

    /**
     * T-SqlListCatchup 券B：取走计数全量重建标记并复位。置位点与各点为什么必须全量逐条见
     * [countFullRebuildPending] KDoc。单消费方约定同 [drainChangedKeys]。
     */
    fun drainCountRebuildFlag(): Boolean = countFullRebuildPending.getAndSet(false)

    /** T-SqlListCatchup 券B：按键读 ⨉N 去重丢弃计数（[dedupeCount] 的按键版，供面板按变更键重读；无记录返回 0）。 */
    fun dedupeCountForKey(key: String): Int = synchronized(dedupeCounts) { dedupeCounts[key] } ?: 0

    /**
     * T-SqlListCatchup 券B：按键读在队计数（[dedupeKeyPresence] 的消费出口）。
     * 注意：仅「去重开启 + 可去重条目」有准确读数——去重关闭期间 presence 冻结不维护，读数无效
     * （该状态下增量口径整体失效，buffer 置全量重建标记，本出口不会被面板在增量路径消费）。
     */
    internal fun dedupePresenceForKey(key: String): Int = synchronized(entries) { dedupeKeyPresence[key] } ?: 0

    /**
     * T-SqlListCatchup 券B：按键取该键保留实例的 structureKey（走 [dedupeIndex] O(1) 查表；
     * structureKey 惰性缓存首算一次后直达，仅增量路径的新键支付一次指纹计算）。键不在索引返回 null。
     */
    internal fun structureKeyForKey(key: String): String? = synchronized(entries) { dedupeIndex[key]?.structureKey }

    /**
     * T-SqlListCatchup 券B：登记计数变更键。调用方须已持 `synchronized(entries)`——与 presence
     * 维护同锁登记，保证 drain 读到的键在「去重开启 + 可去重」增量口径内有效。
     */
    private fun markCountKeyLocked(key: String) {
        synchronized(changedCountKeys) { changedCountKeys.add(key) }
    }

    /**
     * 清空全部（去重计数一并归零——徽章与列表内容同生命周期；
     * T-LogRowNumber：捕获序号一并归零——清空后下一条从 #1 起计数）。
     */
    fun clear() {
        synchronized(entries) {
            entries.clear()
            dedupeIndex.clear() // T-SqlLogPerf P6：索引随队清空
            dedupeKeyPresence.clear() // F83：键级在队计数随队清空
            crossChannelIndex.clear() // T-CrossChannelDedupe：跨通道索引同生命周期清空
            // 券B：清空后悬挂的变更键作废（面板全量重建不消费键集），集合一并清空保持有界口径。
            synchronized(changedCountKeys) { changedCountKeys.clear() }
        }
        // 券B：clear 是兜底全量重建点——累积 map 必须与 buffer 双双清零对齐。
        countFullRebuildPending.set(true)
        resetDedupeCounts()
        sequenceCounter.set(0)
        pendingContentNotify.set(0) // 面板即将全量重建，悬挂的节流余量作废
        notifyChanged() // 用户操作：内容即时刷新（不节流）
        onDedupeCountsChanged?.invoke() // 计数一并归零（T-PerfBatch 徽章通道）
    }

    /** 去重丢弃计数归零（清空列表、面板重新开启去重时调用）。 */
    fun resetDedupeCounts() {
        synchronized(dedupeCounts) { dedupeCounts.clear() }
        // 券B：丢弃计数整体清零——变更键集表达不了「全部清零」，面板 ⨉N 累积 map 回退全量对齐。
        countFullRebuildPending.set(true)
    }

    /**
     * T-LogRowNumber：捕获序号归零（项目启动 processStarted 回调调用）。
     * 不清列表——旧条目保留原行号，仅新一轮启动后的 SQL 从 #1 重新计数。
     */
    fun resetEntrySequence() {
        sequenceCounter.set(0)
    }

    /** 指定 SQL 的去重丢弃次数（参数为还原 SQL 原文，内部按规范化+指纹查，与 [LogSqlEntry.dedupeKey] 同键；无丢弃记录返回 0）。 */
    fun dedupeCount(sql: String): Int = synchronized(dedupeCounts) {
        dedupeCounts[sqlFingerprint(normalizeSqlWhitespace(sql))]
    } ?: 0

    /** 去重丢弃计数快照（键 = [sqlFingerprint] 指纹，值 = 丢弃次数；供面板渲染徽章）。 */
    fun dedupeCountsSnapshot(): Map<String, Int> = synchronized(dedupeCounts) { dedupeCounts.toMap() }

    /**
     * T-LogRowNumber：入队时以 [entry] 为原型分配捕获序号（copy 出带号实例入队）。
     */
    private fun addNumbered(entry: LogSqlEntry): LogSqlEntry =
        entry.copy(sequenceNumber = sequenceCounter.incrementAndGet())

    /**
     * 删除单条（面板右键菜单）。
     *
     * T-LogRowNumber：buffer 入库的是带行号 copy（[addNumbered]），而面板持有的引用来自
     * 列表模型（即入库实例本身）——两者同一，按 **同一性** 删除即可；不能用 data class equals：
     * 序号字段参与 equals，外部构造/改造过的等值条目不应误删列表中的真身。
     */
    fun removeEntry(entry: LogSqlEntry) {
        synchronized(entries) {
            val removed = entries.removeAll { it === entry }
            // T-SqlLogPerf P6：单条删除仅重算该键（用户右键低频，O(n) 一次可接受）
            if (removed && dedupeEnabled) {
                val k = entry.dedupeKey
                // F83：键级在队计数 -1（回扫本身用户右键低频保留 O(n) 一次，不属本券热路径）
                if (canDedupe(entry)) {
                    val remaining = (dedupeKeyPresence[k] ?: 0) - 1
                    if (remaining > 0) dedupeKeyPresence[k] = remaining else dedupeKeyPresence.remove(k)
                    // 券B：登记变更键供面板按 presence 净差增量；键被扫出键域（计数归零摘键）时
                    // 回退全量重算一次（扫键=键域收缩，与淘汰路径同一兜底口径）。
                    markCountKeyLocked(k)
                    if (remaining <= 0) countFullRebuildPending.set(true)
                } else {
                    // 券B：不可去重条目不在 presence 口径内——面板回退全量。
                    countFullRebuildPending.set(true)
                }
                // T-SqlLogPerf P6：单条删除仅重算该键——反扫队内余条重指或摘键
                val stillThere = entries.lastOrNull { canDedupe(it) && it.dedupeKey == k }
                if (stillThere != null) dedupeIndex[k] = stillThere else dedupeIndex.remove(k)
            } else if (removed) {
                // 券B：去重关闭期间 presence 不维护——面板回退全量。
                countFullRebuildPending.set(true)
            }
            // T-DedupeDecouple：跨通道索引重算与去重开关解耦（去重关闭时索引仍在维护）。
            // 删除可能拿走该键**最新入队**实例（与淘汰不同），故必须反扫队内余条而非直接摘键。
            if (removed && canDedupe(entry)) {
                val ck = crossChannelKey(entry)
                if (crossChannelIndex[ck] === entry) {
                    val crossStillThere = entries.lastOrNull { canDedupe(it) && crossChannelKey(it) == ck }
                    if (crossStillThere != null) crossChannelIndex[ck] = crossStillThere
                    else crossChannelIndex.remove(ck)
                }
            }
        }
        notifyChanged()
    }

    // ---------- T-M55-02：执行报错反向绑定（原地更新最近条目） ----------

    /** 错误反向绑定结果（丢弃原因显式化——桥层据此 LOG.debug，不阻塞主链）。 */
    enum class ErrorBindResult {
        /** 已绑定（含同文本幂等重绑）。 */
        BOUND,
        /** 缓冲为空（条目已被清空/尚未捕获）→ 错误丢弃。 */
        DROPPED_EMPTY,
        /** 最近条目超出绑定时间窗（[errorBindWindowMs]）→ 错误丢弃。 */
        DROPPED_WINDOW_EXPIRED,
        /** 错误文本为空 → 无事可绑。 */
        DROPPED_BLANK,
    }

    /**
     * 错误绑定时间窗（毫秒）：异常打印在 Preparing/Parameters 之后，仅反向绑定该窗口内
     * 入列的最近条目——超窗视为「与缓冲内条目无关的错误」（启动期残留/应用级报错），显式丢弃。
     */
    @Volatile
    var errorBindWindowMs: Long = DEFAULT_ERROR_BIND_WINDOW_MS

    /**
     * T-M55-02：错误文本反向绑定**时间窗内同控制台最近条目**（原地更新 errorText）。
     *
     * - 原地更新 = 队尾条目 copy(errorText=...) 替换——[LogSqlEntry.dedupeKey] 不含 errorText
     *   （SQL 指纹稳定），去重计数与 ⨉N 徽章语义不受影响；
     * - 绑定成功走 [notifyChanged]（面板差分刷新；尾位替换在 VisibleDiff 下为全量重建，
     *   由管理器置脏+幂等 flush 合并频率，错误块逐行多次绑定最终只刷一次 EDT）；
     * - 条目可能已被去重丢弃/淘汰/清空——此处表现为「最近条目缺位或超窗」，显式返回丢弃原因，
     *   绝不阻塞主链（错误块解析失败不影响 SQL 捕获）。
     */
    fun bindErrorToRecent(errorText: String): ErrorBindResult {
        if (errorText.isBlank()) return ErrorBindResult.DROPPED_BLANK
        var replaced = false
        var result: ErrorBindResult
        synchronized(entries) {
            val recent = entries.lastOrNull()
            result = when {
                recent == null -> ErrorBindResult.DROPPED_EMPTY
                System.currentTimeMillis() - recent.timestamp > errorBindWindowMs ->
                    ErrorBindResult.DROPPED_WINDOW_EXPIRED
                // 幂等：同一错误块逐行追加会重复绑定，文本相同视为已绑（不替换不刷新）
                recent.errorText == errorText -> ErrorBindResult.BOUND
                else -> {
                    entries.removeLast()
                    val updated = recent.copy(errorText = errorText)
                    entries.addLast(updated)
                    // T-CrossChannelDedupe：两个索引持有的是**队内实例**——原位替换后必须同步重指，
                    // 否则后续同键去重/跨通道兜底会融合进已不在队内的旧副本（升级在列表上不可见）。
                    // 键不变（errorText 不入 dedupeKey / crossKey），只换值。
                    // T-DedupeDecouple：去重索引受 dedupeEnabled 约束，跨通道索引不受。
                    if (dedupeEnabled && dedupeIndex[updated.dedupeKey] === recent) {
                        dedupeIndex[updated.dedupeKey] = updated
                    }
                    if (canDedupe(updated)) {
                        val ck = crossChannelKey(updated)
                        if (crossChannelIndex[ck] === recent) crossChannelIndex[ck] = updated
                    }
                    replaced = true
                    ErrorBindResult.BOUND
                }
            }
        }
        if (replaced) notifyChanged()
        return result
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    // ---------- T-AgentCaptureProvenance（O-147）W0：归因诊断限频出口 ----------

    /**
     * 归因诊断限频：`[ATTRIB_DIAG_INTERVAL_NANOS]` 窗内首条即时输出、其余累计抑制数随下次输出带上。
     * message 为惰性——被抑制时不构造字符串。
     * 平台 Logger 用 `by lazy` 延迟到首条诊断才初始化（纯 JUnit 不触发诊断的用例零平台触达）。
     *
     * **T-CaptureGateStability P3（2026-11-05 真机实测）**：跨通道折叠类诊断改用**独立通道**
     * （[crossChannelLogNanos]）。此前三类共用本通道，而控制台条目入队必然先打「捕获归因」（缺源码），
     * 紧随其后的折叠日志被同一 10s 窗吃掉——真机复验四次折叠、日志一条都没有，现场无法确认折叠发生。
     */
    private val attributionLogNanos = AtomicLong(Long.MIN_VALUE / 2)
    private val attributionSuppressed = AtomicLong()

    /** P3：跨通道折叠/未折叠诊断的独立限频通道（不与「捕获归因」互相压窗口）。 */
    private val crossChannelLogNanos = AtomicLong(Long.MIN_VALUE / 2)
    private val crossChannelSuppressed = AtomicLong()

    private fun logAttributionThrottled(message: () -> String) =
        logThrottled(attributionLogNanos, attributionSuppressed, message)

    private fun logCrossChannelThrottled(message: () -> String) =
        logThrottled(crossChannelLogNanos, crossChannelSuppressed, message)

    private fun logThrottled(nanos: AtomicLong, suppressed: AtomicLong, message: () -> String) {
        val now = System.nanoTime()
        val last = nanos.get()
        if (now - last >= ATTRIB_DIAG_INTERVAL_NANOS && nanos.compareAndSet(last, now)) {
            val dropped = suppressed.getAndSet(0)
            val text = message()
            LOG.info(if (dropped > 0L) "$text（期间另有 $dropped 条同形态未逐条记）" else text)
        } else {
            suppressed.incrementAndGet()
        }
    }

    /**
     * T-CaptureGateStability P3 测试缝：两条诊断通道各自的「最近一次真正输出」时刻。
     * 初值 `Long.MIN_VALUE / 2`（与字段初值同源）表示从未输出。仅供 `SqlLogEntryBufferTest`
     * 断言两者**互不吞窗口**（改回共用通道时第二个断言必红），生产零读。
     */
    internal fun attributionDiagStampForTest(): Long = attributionLogNanos.get()

    internal fun crossChannelDiagStampForTest(): Long = crossChannelLogNanos.get()

    /** O-147 W0：诊断用（懒加载，见 [logAttributionThrottled]）。 */
    private val LOG: com.intellij.openapi.diagnostic.Logger by lazy {
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlLogEntryBuffer::class.java)
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    /**
     * T-PerfBatch：列表内容通知节流——「数量 [CONTENT_NOTIFY_BATCH] 条 + 时间
     * [CONTENT_NOTIFY_INTERVAL_NANOS]」双阈值（原子量，无 Timer）：时间窗内快速灌入时
     * 首条即时通知、后续累计到阈值才再通知；未达阈值条目由 [onContentNotifySuppressed]
     * 兜底给管理器的幂等 flush（flush 读当下快照，不丢条目）。低流量（两条间隔超窗）下
     * 每条即时，行为与节流前等价。
     */
    private fun notifyContentChangedThrottled() {
        val pending = pendingContentNotify.incrementAndGet()
        val now = System.nanoTime()
        if (pending >= CONTENT_NOTIFY_BATCH ||
            now - lastContentNotifyNanos.get() >= CONTENT_NOTIFY_INTERVAL_NANOS
        ) {
            pendingContentNotify.set(0)
            lastContentNotifyNanos.set(now)
            notifyChanged()
        } else {
            onContentNotifySuppressed?.invoke()
        }
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 200
        private const val DEFAULT_DEDUPE_WINDOW_MS = 60000L

        /** T-M55-02：错误反向绑定时间窗默认值（慢查询执行期异常打印仍落在窗口内）。 */
        private const val DEFAULT_ERROR_BIND_WINDOW_MS = 5 * 60_000L

        /** T-PerfBatch：内容通知数量阈值——时间窗内累计 N 条才再通知（首条不受限，即时）。 */
        private const val CONTENT_NOTIFY_BATCH = 20

        /** T-PerfBatch：内容通知时间阈值（200ms）——距上次通知超窗即即时通知，保证低流量等价即时。 */
        private const val CONTENT_NOTIFY_INTERVAL_NANOS = 200_000_000L

        /** T-PerfSweep F3：去重丢弃计数键上限——超限淘汰最旧（见 [dedupeCounts] KDoc）。 */
        private const val MAX_DEDUPE_COUNT_KEYS = 4096

        /** O-147 W0：归因诊断限频窗（10s）。 */
        private const val ATTRIB_DIAG_INTERVAL_NANOS = 10_000_000_000L

        /**
         * O-147 W2-补：接管沿重建索引时保留的「同一次执行双通道副本」窗（5s）。
         * 取 5s 的依据：同一次执行被两通道各捕一次，间隔是毫秒级（真机实测 6ms）；而 P3 现场要挡的是
         * 「接管前 60s 内」的陈旧桥纪元条目——5s 窗两者兼顾（陈旧键照清，新鲜键保留供融合）。
         */
        private const val HANDOFF_MERGE_GRACE_MS = 5_000L

        /**
         * T-CrossChannelDedupe：跨通道等价键兜底折叠的时间窗（5s）。
         *
         * 取 5s 的依据与 [HANDOFF_MERGE_GRACE_MS] 同源：同一次执行被两通道各捕一次的间隔是毫秒级
         * （真机实测 6ms）；而同一模板的两次独立执行在正常业务里不会落在 5s 内还带完全相同的参数值。
         * 远小于 60s 去重窗——兜底只救「同一次执行的两份观测」，不扩大既有的去重语义。
         */
        private const val CROSS_CHANNEL_WINDOW_MS = 5_000L
    }
}
