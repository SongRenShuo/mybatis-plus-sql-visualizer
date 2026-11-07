package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.PluginSettings.Companion.LOG_TIMESTAMP_SOURCE_LOG
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem

// T-M53-II（2026-10-10）同法，D202 第六次登记：SqlLogPanel 的「刷新管线 / 全量重建链 / 排序」
// 内聚成员，以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal。

    // ---------- 刷新管线（类型过滤 → 搜索过滤 → 排序 → 渲染） ----------

    /**
     * 刷新列表（过滤 → 搜索 → 排序 → 差分渲染）。
     *
     * 执行顺序：
     * 1. [filterBySuppress]：按屏蔽规则指纹集滤存量（T-SqlSuppress O-69；管线最前——空集零成本）
     * 2. [filterByType]：按 [activeFilter] 筛选（全选 = 全部，含 OTHER）
     * 3. [filterByFocus]：按 [activeFocus] 聚焦（M56 T-M56-00 基础设施；null = 透传）
     * 4. [filterBySearch]：按 [searchText] 搜索（默认忽略大小写；T-HistorySearchToggles 起随 MatchCase 开关）
     * 5. [applySort]：按 [sortMode] 排序（降序耗时）
     * 6. 差分渲染：T-Perf-03 增量刷新——流式追加免全量重建；无法增量时回退分批全量重建
     *
     * T-LogListFreeze-04：HTML 缓存随片段通道退役——徽章计数在渲染时直读 [dedupeCounts]，
     * 原「清缓存/选择性失效」机制一并移除。
     */
    internal fun SqlLogPanel.refreshList() {
        val gen = ++refreshGeneration
        // T-SqlLogPerf P5（20261006-1020 计划）：上一轮全量重建链未走完就来了新刷新时，增量差分会被
        // 「model.size != lastRendered.size」拒绝 → 每次刷新都重启重建链，风暴期列表永远停在首屏 30 条
        // （用户体感「监听加载有延迟」的实态）。进差分前同步补完剩余批次，让差分基线对齐。
        // F80（T-PerfSweep24）：超大余量时补完让路（false＝本拍刷新挂起，链尾补跑）——单拍同步写
        // 量不再随 logMaxCount 线性放大。
        if (!flushRebuildChainSync()) return
        // T-SqlListCatchup 券D：全拍耗时打点（补链之后 → diff 收尾，即「真正做事的一拍」完整耗时）。
        // 挂在补链之外：补完让路的空拍（return false）不算刷新拍，不污染分布。分位读数走
        // LogPerfProbe.sampleStats("LogPanel.refreshList")，≥100ms 的拍另有阈值 INFO（既有呈现）。
        LogPerfProbe.measure("LogPanel.refreshList", { "visible=${listModel.size()}" }) {
            refreshListBeat(gen)
        }
    }

    /**
     * 券B（T-SqlListCatchup）：徽章计数更新（⨉N 去重丢弃 + ×N 同模板）——增量对账路径，
     * 状态见 [countAccumulator]。消费顺序：先 drain 变更键、后 drain 全量标记——标记若落在
     * 两次 drain 之间则本拍即走全量，不产生一拍陈旧窗口。
     */
    internal fun SqlLogPanel.refreshCountMaps(snapshot: List<LogSqlEntry>) {
        val changedKeys = manager.entries.drainChangedKeys()
        val fullRebuild = manager.entries.drainCountRebuildFlag()
        countAccumulator.apply(snapshot, fullRebuild, changedKeys)
        dedupeCounts = countAccumulator.currentDedupeCounts
        entryCellRenderer.dedupeCounts = dedupeCounts
        templateCounts = countAccumulator.currentTemplateCounts
        entryCellRenderer.templateCounts = templateCounts
    }

    /**
     * 券D 拆出：refreshList 的实际刷新拍体（[refreshList] 负责补链门 + 全拍打点，本函数做事）。
     *
     * T-SqlListCatchup 券E（2026-11-07）：拍体**拆拍探针**——按现有结构把一拍拆成
     * `.snapshot`（快照物化，券D 既有 key）/ `.diagnostics`（诊断行与状态栏同步）/ `.counts`
     * （券B 增量计数段）/ `.filters`（过滤链 + 排序 + 进度条基准）/ `.diffModel`（差分应用与
     * 模型写）五段，各包 measure 入券D 分布采样；全拍 `LogPanel.refreshList` 与旧
     * `.snapshot` key 不动。拆拍目的是拿**分项账单**定位 102~232ms 单拍的重段（真机压测
     * 2026-11-07），只对账单显示真正重的段动手。拍尾的聚焦隐藏提示与慢查计数（条数级常数
     * 成本，与模型写无关）不打点——全拍与各段之差即这段余量。
     */
    internal fun SqlLogPanel.refreshListBeat(gen: Long) {
        val snapshot = LogPerfProbe.measure("LogPanel.refreshList.snapshot", { "size=${manager.entries.size()}" }) {
            manager.entries.snapshot()
        }
        // T-CaptureTruth T-CT-03：条目变化同步诊断行（有条目即还原默认空态文案并停表）
        // 券E：诊断同步含 AgentManager/屏蔽规则取数（每个刷新拍都跑），单独打点以便与计数段划清。
        LogPerfProbe.measure("LogPanel.refreshList.diagnostics") {
            syncDiagnostics()
        }

        // 券B（T-SqlListCatchup）：徽章计数增量更新（变更键对账，兜底事件回退全量）——代替旧的
        // 每拍 dedupeCountsSnapshot() + structureKeyCounts(snapshot) 两份全量 O(N) 重算；
        // 徽章聚合口径不变（**全量条目**，不随当前过滤/搜索波动）。
        // 券E：增量计数段单独打点（全量回退是否每拍发生、值多少钱，账单说了算）。
        LogPerfProbe.measure("LogPanel.refreshList.counts") {
            refreshCountMaps(snapshot)
        }

        var focusHiddenDelta = 0
        val newVisible = LogPerfProbe.measure("LogPanel.refreshList.filters", { "in=${snapshot.size}" }) {
            // T-SqlSuppress：屏蔽存量过滤插管线最前（与类型过滤同级；键集经 SqlLogManager 订阅同步）
            val filtered = filterByFocus(
                filterByType(
                    applySlowOnlyFilter(filterBySuppress(snapshot, manager.entries.suppressKeys)),                activeFilter,
                ),
                activeFocus,
            )
            // T-M55-03/04：动态聚焦页签层（页签=过滤器，多开并集；离散增删走 FullRebuild，差分零改动）
            val focused = filterByFocusTabs(filtered, focusTabs)
            focusHiddenDelta = filtered.size - focused.size
            // T-HistorySearchToggles：搜索旗随 MatchCase 开关（默认关=忽略大小写，与现状一致）
            // T-SearchToggleNativeLook：正则旗随 Regex 钮（20261010 用户令日志页补正则，语义与历史页同链）
            val searched = filterBySearch(focused, searchText, matchCase = logMatchCase, regexEnabled = logRegex, memo = regexMemo)
            val sorted = applySort(searched)

            // T-LogListRail：进度条归一基准取**当前可见条目**的最大耗时（不随全量缓冲波动，
            // 与过滤/排序结果一致；无耗时条目返回 null → 所有条画空槽）。
            // 用单遍遍历而非 mapNotNull{}.maxOrNull()：后者会为几千~几万条分配一个临时列表，
            // 而本行每次刷新都跑（流式灌入下每秒多次），是纯 GC 压力。
            entryCellRenderer.maxDurationMs = maxDurationOf(sorted)
            sorted
        }

        LogPerfProbe.measure("LogPanel.refreshList.diffModel", { "model=${listModel.size()}" }) {
            val evicted = manager.entries.drainEvictedCount()
            val modelSizeBefore = listModel.size()
            val visibleTailSequenceBefore = lastRendered.lastOrNull()?.sequenceNumber ?: 0L
            // 模型变更（含紧随其后的锚点复原）整体处于程序性滚动窗口内：期间的调整事件不代表用户手势。
            withProgrammaticScroll {
                if (!applyIncremental(newVisible, evicted)) {
                    // T-LogListAnchor 券02：全量重建会 clear 模型、视口回顶。暂停态先记住顶部条目身份，
                    // 等它被写进模型（可能落在后续批次）再复原阅读位置；跟随态不需要（重建后本就贴底）。
                    pendingScrollAnchor = if (tailFollow.shouldFollow) null else captureTopAnchor()
                    applyFullRebuild(newVisible, gen)
                    // 首屏批次是同步写完的：锚点条目若在其中立即复原；落在后续批次则交给
                    // appendRebuildChunk 逐拍重试（链已排空仍没找到 = 条目已被淘汰/过滤掉，放弃）。
                    pendingScrollAnchor?.let { anchor ->
                        if (restoreAnchor(anchor) || rebuildRemaining == null) pendingScrollAnchor = null
                    }
                }
            }
            val tailAdvancedAtCapacity = evicted > 0 &&
                (newVisible.lastOrNull()?.sequenceNumber ?: 0L) > visibleTailSequenceBefore
            // 缓冲区满时头部淘汰与尾部追加相抵，模型长度不变；只随确有新可见条目时贴底。
            // 券E 账单注：本拍滚动（ensureIndexIsVisible → BasicListUI 变高布局 O(N) 全扫）曾是
            // 单拍 102~232ms 的最大单一成分——渲染器条目级 preferred size 缓存（RailCellRendererBase
            // 券E 段）把它从 ~30ms 压到 <10ms（headless 账单，2026-11-07）。
            if (tailFollow.shouldFollow && (listModel.size() > modelSizeBefore || tailAdvancedAtCapacity)) {
                scrollToBottom(resume = false)
            }
        }

        // 聚焦隐藏计数提示（聚焦层单独度量：输入 filtered → 输出 focused；券E 拆拍后由
        // filters 段透出 focusHiddenDelta，本处只做 Swing 标签写）
        if (focusTabs.isEmpty()) {
            focusHiddenLabel.isVisible = false
        } else {
            focusHiddenLabel.text = MpSqlVisualizerBundle.message("log.focus.hidden", focusHiddenDelta.toString())
            focusHiddenLabel.isVisible = focusHiddenDelta > 0
        }

        // O-1：同步慢查询阈值 + 更新计数（Phase C）
        slowQueryCounter.thresholdMs = PluginSettings.getInstance().state.slowQueryThresholdMs
        slowQueryCounter.reset()
        snapshot.forEach { slowQueryCounter.report(it.executionTimeMs) }
        updateSlowQueryLabel()
        // S1-4④：非法正则不再静默——过滤已把本次编译结果写进 regexMemo，这里补可见反馈。
        updateSearchErrorFeedback()
    }

    /**
     * T-Perf-03（自 dev 移植）：增量应用差分（同步完成，无异步追加链）。模型与 [lastRendered] 尺寸不一致
     * （旧异步追加链被新刷新中断/外部改动）时拒绝增量——全量重建兜底。
     */
    internal fun SqlLogPanel.applyIncremental(newVisible: List<LogSqlEntry>, evicted: Int): Boolean {
        if (listModel.size() != lastRendered.size) return false
        return when (val diff = diffVisible(lastRendered, newVisible, evicted)) {
            is VisibleDiff.Same -> { lastRendered = newVisible; true }
            is VisibleDiff.HeadEvictAppend -> {
                // T-LogListAnchor 券02：暂停态下头部淘汰会把整屏内容上顶一行高（JList 无滚动锚定）。
                // 先量出被删行的总高（只涉及 0..evicted-1，成本 O(evicted)），模型变更后把视口
                // 按同一高度上移——用户正在读的那条原地不动，新条目只在尾部出现。
                val evictedHeight = if (tailFollow.shouldFollow) {
                    0
                } else {
                    list.getCellBounds(0, diff.evicted - 1)?.height ?: 0
                }
                repeat(diff.evicted) { listModel.remove(0) }
                diff.appended.forEach { listModel.addElement(it) }
                lastRendered = newVisible
                shiftViewportUpBy(evictedHeight)
                true
            }
            is VisibleDiff.Insert -> {
                // 尾部追加不动视口上方内容，无需补偿；中段插入（过滤条件变化产生）走 FullRebuild，
                // 由 pendingScrollAnchor 按条目身份复原。
                diff.entries.forEachIndexed { i, e -> listModel.add(diff.index + i, e) }
                lastRendered = newVisible
                true
            }
            is VisibleDiff.TailRemove -> {
                for (i in lastRendered.size - 1 downTo diff.from) listModel.remove(i)
                lastRendered = newVisible
                true
            }
            is VisibleDiff.FullRebuild -> false
        }
    }

    /**
     * 全量重建（分批 EDT 写入 + generation 守卫）。
     *
     * T-Perf-03 追加链 generation 守卫（自 dev 移植）：新 refreshList 启动后旧链在新 chunk 写入前检测代际
     * 并终止——杜绝旧快照剩余条目继续追加造成的幽灵/重复条目。
     * T-SqlLogPerf P5：剩余批次游标提为字段 [rebuildRemaining]——refreshList 进差分前经
     * [flushRebuildChainSync] 同步补完在途链（旧实现链被新刷新抢占后只能整体重启，见 flushRebuildChainSync 注释）。
     */
    internal fun SqlLogPanel.applyFullRebuild(sorted: List<LogSqlEntry>, gen: Long) {
        lastRendered = sorted
        rebuildRemaining = null
        // T-LogListFreeze-01：批量抑制布局 + T-LogListFreeze-03：分批 EDT 写入
        list.ignoreRepaint = true
        listModel.clear()
        if (sorted.isNotEmpty()) {
            // 首屏批次（立即写入，保证快速首屏）
            val firstBatch = sorted.take(REFRESH_FIRST_BATCH_SIZE)
            listModel.addAll(firstBatch)
        }
        list.ignoreRepaint = false
        list.revalidate()
        list.repaint()

        // T-LogListFreeze-03：剩余批次通过 invokeLater 异步追加（每批一次 EDT 脉冲，避免长冻结）
        rebuildRemaining = if (sorted.size > REFRESH_FIRST_BATCH_SIZE) {
            sorted.drop(REFRESH_FIRST_BATCH_SIZE).iterator()
        } else {
            null
        }
        if (rebuildRemaining != null) {
            ApplicationManager.getApplication().invokeLater { appendRebuildChunk() }
        }
    }
    /** 追加一批（REFRESH_CHUNK_SIZE 条）；写完仍有余量则排下一拍。 */
    internal fun SqlLogPanel.appendRebuildChunk() {
        val remaining = rebuildRemaining ?: return
        val chunk = mutableListOf<LogSqlEntry>()
        repeat(REFRESH_CHUNK_SIZE) { if (remaining.hasNext()) chunk.add(remaining.next()) }
        withProgrammaticScroll {
            listModel.addAll(chunk)
            // T-LogListAnchor 券02：重建在途时锚点条目可能落在本批才出现——找到就复原并清空；
            // 跟随态优先（用户中途点了「滚动到底部」时不得再被锚点拉回）。
            if (chunk.isNotEmpty() && tailFollow.shouldFollow) {
                pendingScrollAnchor = null
                scrollToBottom(resume = false)
            } else {
                pendingScrollAnchor?.let { anchor -> if (restoreAnchor(anchor)) pendingScrollAnchor = null }
            }
        }
        rebuildRemaining = if (remaining.hasNext()) remaining else null
        if (rebuildRemaining == null) pendingScrollAnchor = null
        if (rebuildRemaining != null) {
            ApplicationManager.getApplication().invokeLater { appendRebuildChunk() }
        } else if (pendingRefreshAfterChain) {
            // F80：链排空——补跑挂起的刷新（风暴期多次挂起合并为一次补跑）
            pendingRefreshAfterChain = false
            ApplicationManager.getApplication().invokeLater { refreshList() }
        }
    }

    /**
     * T-SqlLogPerf P5（20261006-1020 计划）：同步补完在途全量重建链。
     *
     * 旧形态：重建链在途时 `listModel.size != lastRendered.size`，增量差分必被拒绝 → 风暴期
     * 每 200ms 的新刷新都重启重建链，链永远走不完，列表停在首屏 30 条且反复 clear/addAll 白耗 EDT。
     * 补完后 model 与 lastRendered 对齐，后续刷新走增量差分。
     *
     * F80（T-PerfSweep24）补完保留分批：余量 ≤ [FLUSH_SYNC_MAX]（默认设置 ≤170）照旧单拍同步
     * 补完——默认行为逐字不变；余量超大（用户 logMaxCount 数千）不再单拍同步写数千条
     * （EDT 冻结随 logMaxCount 线性放大），本拍刷新让路（返回 false 挂起），在途链沿用
     * appendRebuildChunk 逐拍语义推进（每拍 30 条 EDT 脉冲），链排空补跑挂起刷新——
     * 风暴期新刷新不再反复 clear+重启链，列表从首屏 30 条起逐拍长满。
     */
    internal fun SqlLogPanel.flushRebuildChainSync(): Boolean {
        val remaining = rebuildRemaining ?: return true
        val rest = remaining.asSequence().toList()
        if (rest.size > FLUSH_SYNC_MAX) {
            // 超大余量：让路——链保持推进（rebuildRemaining 不动），本拍刷新挂起
            pendingRefreshAfterChain = true
            return false
        }
        rebuildRemaining = null
        list.ignoreRepaint = true
        if (rest.isNotEmpty()) listModel.addAll(rest)
        list.ignoreRepaint = false
        // T-LogListAnchor 券02：链被同步补完——锚点条目此时可能才出现，补一次复原尝试。
        pendingScrollAnchor?.let { anchor -> if (restoreAnchor(anchor)) pendingScrollAnchor = null }
        list.revalidate()
        list.repaint()
        return true
    }

    /** 按当前排序模式排序条目。 */
    // ---------- 排序方式（T-LogSortModes） ----------

    /** 弹出排序方式单选菜单（与 SQL类型弹窗同款交互；当前项打勾）。锚点＝触发动作的按钮。 */
    internal fun SqlLogPanel.showSortPopup(anchor: java.awt.Component?) {
        val popup = JPopupMenu()
        buildSortMenuItems().forEach { popup.add(it) }
        val target = anchor ?: toolbarComponent
        if (target != null) popup.show(target, 0, target.height + 2) else popup.show(toolbarComponent, 0, 0)
    }

    /**
     * 构建排序菜单项（拆出供测试断言选项齐备——headless 下 JPopupMenu.show 不可靠）。
     * 单选语义：点选即生效并刷新列表与按钮文案。
     */
    internal fun SqlLogPanel.buildSortMenuItems(): List<JRadioButtonMenuItem> =
        sortOptions().map { option ->
            JRadioButtonMenuItem(option.label, option.mode == sortMode).apply {
                isFocusable = false
                addActionListener { applySortMode(option.mode) }
            }
        }

    /** 应用排序方式：更新模式 → 刷新列表 → 刷新动作 presentation（当前方式进 tooltip 与菜单选中态）。 */
    internal fun SqlLogPanel.applySortMode(mode: SortMode) {
        if (sortMode == mode) {
            refreshSortAction()
            return
        }
        sortMode = mode
        refreshList()
        refreshSortAction()
    }

    /** 刷新排序动作的 presentation（工具窗工具栏只渲染图标，当前方式由 tooltip 承载）。 */
    internal fun SqlLogPanel.refreshSortAction() {
        _toolbarRef?.updateActionsImmediately()
    }

    /**
     * 排序动作文案 = 当前方式标签（见 [sortModeAction] 的 update：进 presentation.text 与 tooltip）。
     * 工具窗工具栏按惯例只渲染图标，故当前方式实际由 tooltip「排序：<方式>」与菜单选中态承载。
     */
    internal fun SqlLogPanel.updateSortButtonText() {
        refreshSortAction()
    }

    internal fun SqlLogPanel.applySort(entries: List<LogSqlEntry>): List<LogSqlEntry> = when (sortMode) {
        // 时间升序（默认）：与捕获顺序一致（sortedBy 稳定，等值保持入列序）
        SortMode.TIME_ASC -> entries.sortedBy { displayTimeOf(it) }
        SortMode.TIME_DESC -> entries.sortedByDescending { displayTimeOf(it) }
        // 耗时：结构化字段优先（agent 通道直采），缺失时回退日志文本解析；无耗时沉底
        SortMode.DURATION_ASC -> entries.sortedBy { durationOf(it) ?: Long.MAX_VALUE }
        SortMode.DURATION_DESC -> entries.sortedByDescending { durationOf(it) ?: Long.MIN_VALUE }
        // 行号：无行号（历史读回/round-trip）沉底
        SortMode.SEQUENCE -> entries.sortedBy { if (it.sequenceNumber > 0) it.sequenceNumber else Long.MAX_VALUE }
        // 同结构聚类（用户 2026-10-14 补）：按结构指纹分组、组内保持时间升序——排查 N+1 时同形状条目相邻。
        // structureKey 为条目级 lazy（每条只算一次指纹），故比较器是纯字段读取。
        SortMode.STRUCTURE_CLUSTER -> entries.sortedWith(
            compareBy({ it.structureKey }, { displayTimeOf(it) }),
        )
        // 按目标库分组（用户 2026-10-14 补）：库名升序、未知（null）沉底，组内保持时间升序。
        SortMode.DATABASE_GROUP -> entries.sortedWith(
            compareBy({ it.databaseName ?: DATABASE_UNKNOWN_SORT_KEY }, { displayTimeOf(it) }),
        )
    }

    /**
     * 「只看慢查询」过滤接线（阈值取当前设置；判定走 [durationOf]，与慢查询徽章同口径）。
     * 纯逻辑在 [cn.song.mybatisplus.visualizer.log.filterBySlowOnly]，便于独立单测。
     */
    internal fun SqlLogPanel.applySlowOnlyFilter(entries: List<LogSqlEntry>): List<LogSqlEntry> =
        cn.song.mybatisplus.visualizer.log.filterBySlowOnly(
            entries,
            slowOnly,
            PluginSettings.getInstance().state.slowQueryThresholdMs,
            ::durationOf,
        )

    /** 条目耗时（毫秒）：结构化 executionTimeMs 优先，缺失时回退日志文本解析。 */    internal fun SqlLogPanel.durationOf(entry: LogSqlEntry): Long? =
        entry.executionTimeMs ?: LogDurationParser.parse(entry)

    /**
     * 条目显示时间（排序口径）：与列表现行时间列一致——「日志时间」源开启且条目有原始日志时间时
     * 用之，否则用捕获时间（[formatTimestampFragment] 同规则），避免「看到的时间与排序依据不一致」。
     */
    internal fun SqlLogPanel.displayTimeOf(entry: LogSqlEntry): Long {
        val state = PluginSettings.getInstance().state
        return if (state.logTimestampSource == LOG_TIMESTAMP_SOURCE_LOG) {
            entry.logTimestamp ?: entry.timestamp
        } else {
            entry.timestamp
        }
    }
