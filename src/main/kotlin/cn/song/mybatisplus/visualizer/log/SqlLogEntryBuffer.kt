package cn.song.mybatisplus.visualizer.log

import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
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
            if (changed && value) {
                synchronized(entries) { rebuildDedupeIndexLocked() }
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

    /** T-PerfSweep26-01（F83）测试缝：淘汰路径全队反扫（`entries.lastOrNull` 扫描）执行计数——
     *  唯一键稳态下应恒 0，仅测试断言用（V38 计数缝同款形态），生产零读。 */
    private val evictionRescanCount = AtomicInteger()
    internal val evictionRescanCountForTest: Int get() = evictionRescanCount.get()

    // UI013：Logic 未绑定，文本相同不能证明同一次执行；Actual 仅属该次配对，禁止裁剪或融合。
    // 反向验证锚点：临时改为 true，UI013 自动/手动保真用例必须变红。
    private fun canDedupe(entry: LogSqlEntry): Boolean = entry.source != SqlSource.SHARDINGSPHERE

    private fun rebuildDedupeIndexLocked() {
        dedupeIndex.clear()
        rebuildDedupeKeyPresenceLocked() // F83：计数随索引一并全量重建
        for (e in entries) {
            if (!canDedupe(e)) continue
            val latest = dedupeIndex[e.dedupeKey]
            if (latest == null || e.timestamp > latest.timestamp) dedupeIndex[e.dedupeKey] = e
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
            dedupeIndex.clear()
            if (!dedupeEnabled) return
            rebuildDedupeKeyPresenceLocked() // F83：计数按队内现存全量补账（与索引的 grace 截断口径不同，条目仍留队的陈旧键不缺账）
            val cutoff = nowMs - graceMs
            for (e in entries) {
                if (!canDedupe(e) || e.timestamp < cutoff) continue
                val latest = dedupeIndex[e.dedupeKey]
                if (latest == null || e.timestamp > latest.timestamp) dedupeIndex[e.dedupeKey] = e
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
        val evictedEntries = mutableListOf<LogSqlEntry>()
        var storedEntry: LogSqlEntry? = null
        synchronized(entries) {
            if (dedupeEnabled && isDuplicate(entry)) {
                duplicate = true
                // 【反向验证探针锚点】T-AgentCaptureProvenance O-147 W1：注释掉下方 if 块 =
                // 摘除「同桶信息融合」——SqlLogEntryBufferTest 的「控制台先到_agent后到_融合升级」
                // 用例必红（保留条目缺源码/耗时/库标，跳转仍 MissingSource），恢复后转绿。
                val storedExisting = dedupeIndex[entry.dedupeKey]
                if (storedExisting != null) {
                    val merged = mergeDuplicateEntry(storedExisting, entry)
                    if (merged != storedExisting) {
                        // java.util.ArrayDeque 无按下标替换：按同一性重建（融合低频，O(n) 锁内可接受）
                        val rebuilt = ArrayDeque<LogSqlEntry>(entries.size)
                        for (e in entries) rebuilt.add(if (e === storedExisting) merged else e)
                        entries.clear()
                        entries.addAll(rebuilt)
                        dedupeIndex[entry.dedupeKey] = merged
                        mergeChanged = true
                        mergeDiagExisting = storedExisting
                        mergeDiagMerged = merged
                    }
                }
            } else {
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
                    }
                    evictedEntries.add(evicted)
                    evictedSinceDrain.incrementAndGet() // T-Perf-03：头部淘汰计数（面板增量刷新）
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
                    logAttributionThrottled {
                        "重复观测跨通道融合: 指纹=${entry.dedupeKey.take(8)} " +
                            "通道=${diagExisting.captureProvenance}+${entry.captureProvenance}" +
                            "→${diagMerged.captureProvenance} 升级=${mergeUpgradedAspects(diagExisting, diagMerged)}"
                    }
                }
            }
            return false
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

    /**
     * 清空全部（去重计数一并归零——徽章与列表内容同生命周期；
     * T-LogRowNumber：捕获序号一并归零——清空后下一条从 #1 起计数）。
     */
    fun clear() {
        synchronized(entries) {
            entries.clear()
            dedupeIndex.clear() // T-SqlLogPerf P6：索引随队清空
            dedupeKeyPresence.clear() // F83：键级在队计数随队清空
        }
        resetDedupeCounts()
        sequenceCounter.set(0)
        pendingContentNotify.set(0) // 面板即将全量重建，悬挂的节流余量作废
        notifyChanged() // 用户操作：内容即时刷新（不节流）
        onDedupeCountsChanged?.invoke() // 计数一并归零（T-PerfBatch 徽章通道）
    }

    /** 去重丢弃计数归零（清空列表、面板重新开启去重时调用）。 */
    fun resetDedupeCounts() {
        synchronized(dedupeCounts) { dedupeCounts.clear() }
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
                }
                val stillThere = entries.lastOrNull { canDedupe(it) && it.dedupeKey == k }
                if (stillThere != null) dedupeIndex[k] = stillThere else dedupeIndex.remove(k)
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
                    entries.addLast(recent.copy(errorText = errorText))
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
     * 归因诊断限频（缺源码入队与跨通道融合两条共用）：[ATTRIB_DIAG_INTERVAL_NANOS] 窗内首条
     * 即时输出、其余累计抑制数随下次输出带上。message 为惰性——被抑制时不构造字符串。
     * 平台 Logger 用 `by lazy` 延迟到首条诊断才初始化（纯 JUnit 不触发诊断的用例零平台触达）。
     */
    private val attributionLogNanos = AtomicLong(Long.MIN_VALUE / 2)
    private val attributionSuppressed = AtomicLong()

    private fun logAttributionThrottled(message: () -> String) {
        val now = System.nanoTime()
        val last = attributionLogNanos.get()
        if (now - last >= ATTRIB_DIAG_INTERVAL_NANOS && attributionLogNanos.compareAndSet(last, now)) {
            val suppressed = attributionSuppressed.getAndSet(0)
            val text = message()
            LOG.info(if (suppressed > 0L) "$text（期间另有 $suppressed 条同形态未逐条记）" else text)
        } else {
            attributionSuppressed.incrementAndGet()
        }
    }

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
    }
}
