package cn.song.mybatisplus.visualizer.history

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * SQL 历史记录管理器（F-7）：增删改查 + 上限淘汰 + 收藏 + 标签 + 搜索，跨会话持久化。
 *
 * application-level 轻服务（@Service(Service.Level.APP)，跨项目共享），经 XmlSerializer
 * 持久化到 `mp-sql-visualizer.xml`（与 [PluginSettings] 同文件）。
 *
 * 性能约束：
 * - [add] 均摊 O(1)（队尾追加 + 队首淘汰）
 * - 上限默认 200（可经 [maxSize] 配置），超出时淘汰最旧非收藏条目
 * - [search] 子串匹配，200 条 < 10ms
 */
@State(
    name = "MpSqlVisualizerHistory",
    storages = [Storage("mp-sql-visualizer.xml", roamingType = RoamingType.DISABLED)],
)
@Service(Service.Level.APP)
class SqlHistoryManager : PersistentStateComponent<SqlHistoryManager.State> {

    /**
     * 可持久化状态（XmlSerializer 序列化）。
     * 条目按时间倒序排列（index 0 = 最新）；[favoriteEntries] 独立存放，不参与上限淘汰。
     */
    data class State(
        var entries: MutableList<SqlHistoryEntry> = mutableListOf(),
        var favoriteEntries: MutableList<SqlHistoryEntry> = mutableListOf(),
    )

    private var state = State()
    private var planGeneration = 0L

    @Synchronized
    fun explainPlanGeneration(): Long = planGeneration

    /** 提交前固定原条目；异步完成时禁止再按“最新 SQL”回查归属。 */
    @Synchronized
    fun beginExplainCapture(projectIdentity: String, previewSql: String, key: ExplainPlanKey, entryId: String? = null): ExplainPlanCapture? {
        if (key.projectIdentity != projectIdentity) return null
        val entry = if (entryId != null) findById(entryId)?.takeIf {
            it.projectIdentity == projectIdentity
        } else entries.firstOrNull { it.projectIdentity == projectIdentity && it.sql == previewSql }
        if (entry == null) return null
        return ExplainPlanCapture(entry.id, planGeneration, key)
    }

    /** R91 可只读消费此 API：严格同项目/UUID/方言/格式/模板/exact，返回副本。 */
    @Synchronized
    fun matchingExplainPlans(key: ExplainPlanKey): List<ExplainPlanSnapshot> {
        if (!key.persistable) return emptyList()
        return entries.flatMap { it.explainPlans }.filter { it.key() == key }
            .sortedByDescending { it.capturedAt }.map { it.copy() }
    }

    @Synchronized
    fun saveExplainPlan(capture: ExplainPlanCapture, raw: cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult): ExplainPlanSaveResult {
        if (capture.generation != planGeneration) return ExplainPlanSaveResult.CLEARED_OR_REMOVED
        val entry = findById(capture.entryId) ?: return ExplainPlanSaveResult.CLEARED_OR_REMOVED
        if (entry.projectIdentity != capture.key.projectIdentity) return ExplainPlanSaveResult.CLEARED_OR_REMOVED
        if (!capture.key.persistable) return ExplainPlanSaveResult.SESSION_ONLY
        val snapshot = ExplainPlanSnapshot.create(capture.key, raw)
        val newestTime = entries.asSequence().flatMap { it.explainPlans.asSequence() }.maxOfOrNull { it.capturedAt } ?: 0L
        // 同一毫秒完成的多个回调仍按保存顺序淘汰，不因稳定排序把最新一份丢掉。
        if (newestTime >= snapshot.capturedAt && newestTime < Long.MAX_VALUE) snapshot.capturedAt = newestTime + 1
        if (snapshot.serializedBytes() > MAX_PLAN_BYTES) return ExplainPlanSaveResult.TOO_LARGE
        entry.explainPlans.add(0, snapshot)
        enforcePlanBounds()
        return ExplainPlanSaveResult.SAVED
    }

    /** 计划预算只淘汰计划，绝不删除 SQL（特别是收藏）。加载旧/手改 XML 同样受限。 */
    private fun enforcePlanBounds() {
        val all = state.entries + state.favoriteEntries
        all.forEach { entry ->
            entry.explainPlans.removeAll {
                !it.key().persistable || it.projectIdentity != entry.projectIdentity ||
                    it.raw() == null || it.serializedBytes() > MAX_PLAN_BYTES
            }
            entry.explainPlans.sortByDescending { it.capturedAt }
            while (entry.explainPlans.size > MAX_PLANS_PER_ENTRY) entry.explainPlans.removeAt(entry.explainPlans.lastIndex)
        }
        val oldest = all.flatMap { entry -> entry.explainPlans.map { Triple(entry, it, it.serializedBytes()) } }
            .sortedBy { it.second.capturedAt }
        var bytes = oldest.sumOf { it.third.toLong() }
        for ((entry, snapshot, size) in oldest) {
            if (bytes <= MAX_TOTAL_PLAN_BYTES) break
            entry.explainPlans.remove(snapshot)
            bytes -= size
        }
    }

    /** 历史记录上限（非收藏条目）。默认 200，跟随 [PluginSettings.historyMaxSize] 配置。 */
    var maxSize: Int = DEFAULT_MAX_SIZE
        set(value) { field = value.coerceAtLeast(1) }

    // ---------- 添加 ----------

    /**
     * 添加一条历史记录（均摊 O(1)）。
     *
     * 自动分配 id；按时间戳插入正确位置（倒序）。收藏条目进 [State.favoriteEntries]，
     * 非收藏条目进 [State.entries] 并在超出 [maxSize] 时淘汰最旧者。
     *
     * @return 添加后的条目（含分配的 id）
     */
    @Synchronized
    fun add(entry: SqlHistoryEntry): SqlHistoryEntry {
        entry.ensureId()
        if (entry.favorite) {
            insertSorted(state.favoriteEntries, entry)
            evictFavoritesIfNeeded()
        } else {
            insertSorted(state.entries, entry)
            evictIfNeeded()
        }
        if (entry.explainPlans.isNotEmpty()) enforcePlanBounds()
        return entry
    }

    /**
     * T-Perf-19：收藏条目上限（[MAX_FAVORITES]）——此前 favoriteEntries 永不淘汰，
     * 长期使用无限增长（持久化文件与内存双膨胀）。超出时淘汰最旧收藏（队尾）。
     */
    private fun evictFavoritesIfNeeded() {
        while (state.favoriteEntries.size > MAX_FAVORITES) {
            state.favoriteEntries.removeAt(state.favoriteEntries.lastIndex)
        }
    }

    /**
     * 按时间戳倒序插入（index 0 = 最新）。遍历找到第一个 timestamp <= 新条目的位置插入。
     * 列表最多 200 条，线性遍历 O(n) 可接受（均摊仍 O(1)——追加到头部最常见）。
     */
    private fun insertSorted(list: MutableList<SqlHistoryEntry>, entry: SqlHistoryEntry) {
        val idx = list.indexOfFirst { it.timestamp <= entry.timestamp }
        if (idx < 0) list.add(entry) else list.add(idx, entry)
    }

    /**
     * 超出上限时淘汰最旧的非收藏条目（队尾）。R15：钉住条目跳过（防翻找丢失）——
     * 从队尾向前找首个非钉住条目移除；全为钉住则不淘汰（钉住总量由 MAX_PINNED 把关）。
     */
    private fun evictIfNeeded() {
        while (state.entries.size > maxSize) {
            val idx = state.entries.indexOfLast { !it.pinned }
            if (idx < 0) return // 全为钉住：不再淘汰（钉住上限由 togglePin 把关）
            state.entries.removeAt(idx)
        }
    }

    /** R15：钉住上限（防钉住分区无限膨胀挤占淘汰机制）。 */
    private val MAX_PINNED = 50

    /** R15：切换钉住态；钉住数达 [MAX_PINNED] 上限时拒绝并返回 false。 */
    fun togglePin(id: String): Boolean {
        val entry = findById(id) ?: return false
        if (!entry.pinned) {
            val pinnedCount = state.entries.count { it.pinned } + state.favoriteEntries.count { it.pinned }
            if (pinnedCount >= MAX_PINNED) return false
        }
        entry.pinned = !entry.pinned
        return true
    }

    // ---------- 查询（最新在前） ----------

    /** 全部条目（含收藏与非收藏，按时间倒序合并）。 */
    val entries: List<SqlHistoryEntry>
        get() = mergeByTimestamp(state.favoriteEntries, state.entries)

    /** 按 id 查找条目（含收藏分区）。 */
    fun findById(id: String): SqlHistoryEntry? =
        state.entries.firstOrNull { it.id == id } ?: state.favoriteEntries.firstOrNull { it.id == id }

    /** 收藏条目（时间倒序）。 */
    fun filterFavorites(): List<SqlHistoryEntry> = state.favoriteEntries.toList()

    // ---------- 收藏 ----------

    /**
     * 切换收藏状态。
     * 收藏 → 移入 [State.favoriteEntries]；取消收藏 → 移回 [State.entries]。
     */
    fun toggleFavorite(id: String) {
        val inNormal = state.entries.indexOfFirst { it.id == id }
        if (inNormal >= 0) {
            val e = state.entries.removeAt(inNormal)
            e.favorite = true
            insertSorted(state.favoriteEntries, e)
            evictFavoritesIfNeeded()
            return
        }
        val inFav = state.favoriteEntries.indexOfFirst { it.id == id }
        if (inFav >= 0) {
            val e = state.favoriteEntries.removeAt(inFav)
            e.favorite = false
            insertSorted(state.entries, e)
            evictIfNeeded()
        }
    }

    // ---------- 标签 ----------

    /** 为条目添加标签（去重）。 */
    fun addTag(id: String, tag: String) {
        if (tag.isBlank()) return
        findById(id)?.let { e ->
            if (e.tags.none { it == tag }) e.tags.add(tag)
        }
    }

    /** 移除条目的指定标签。 */
    fun removeTag(id: String, tag: String) {
        findById(id)?.tags?.remove(tag)
    }

    /** 全部标签集合（所有条目的标签并集）。 */
    fun allTags(): Set<String> =
        (state.entries + state.favoriteEntries).flatMap { it.tags }.toSet()

    /** 按标签筛选条目（精确匹配，时间倒序）。 */
    fun filterByTag(tag: String): List<SqlHistoryEntry> =
        entries.filter { it.tags.contains(tag) }

    // ---------- 搜索 ----------

    /**
     * 子串搜索（SQL 文本，忽略大小写）。空查询返回全量。
     * 简单 substring 匹配，200 条 < 10ms。
     */
    fun search(query: String): List<SqlHistoryEntry> {
        if (query.isBlank()) return entries
        val q = query.trim().lowercase()
        return entries.filter { it.sql.lowercase().contains(q) || it.compactSql.lowercase().contains(q) }
    }

    // ---------- F-08：项目维度 ----------

    /**
     * F-08：按项目过滤条目（时间倒序）。
     *
     * @param projectIdentity 项目身份（[SqlHistoryEntry.projectIdentity]，即 projectId）；
     *   空白 → 返回「未归属」档（旧数据 / 非文件型项目写入的条目，读回兼容口径见
     *   [SqlHistoryEntry.projectIdentity] KDoc）。
     */
    fun filterByProject(projectIdentity: String): List<SqlHistoryEntry> =
        if (projectIdentity.isBlank()) entries.filter { it.projectIdentity.isBlank() }
        else entries.filter { it.projectIdentity == projectIdentity }

    /**
     * F-08：按项目清空（过滤/清空按项目隔离）——只删该项目名下的条目，两个分区
     * （收藏 + 普通）一并清；其他项目与「未归属」档不受影响。返回删除条数。
     */
    @Synchronized
    fun clearProject(projectIdentity: String): Int {
        if (projectIdentity.isBlank()) {
            // 未归属档：只清 identity 空白的条目，不动任何已归属项目
            val before = state.entries.size + state.favoriteEntries.size
            state.entries.removeAll { it.projectIdentity.isBlank() }
            state.favoriteEntries.removeAll { it.projectIdentity.isBlank() }
            return before - state.entries.size - state.favoriteEntries.size
        }
        val before = state.entries.size + state.favoriteEntries.size
        state.entries.removeAll { it.projectIdentity == projectIdentity }
        state.favoriteEntries.removeAll { it.projectIdentity == projectIdentity }
        return before - state.entries.size - state.favoriteEntries.size
    }

    /**
     * F-08：项目清单（identity → 显示名），供 UI 的项目过滤下拉取选项。
     * 同 identity 多条目时取**最新一条**的 [SqlHistoryEntry.projectName]（项目改名后跟随新名）；
     * 「未归属」条目以空串键参与（UI 侧自行渲染「未归属」文案）。
     */
    fun projectNames(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        // entries 已时间倒序：先到先写即「最新者优先」，后到的旧条目不覆盖
        entries.forEach { entry -> map.putIfAbsent(entry.projectIdentity, entry.projectName) }
        return map
    }

    // ---------- R51：参数快照回写（O-105 余半·主体） ----------

    /**
     * R51：把参数快照回写到**最新一条**满足 [match] 的条目（[entries] 已按时间倒序——
     * firstOrNull 即新者权威，同一 SQL 多次预览只更新最近一条，旧条目保留各自时点的快照）。
     *
     * 调用方＝面板参数编辑防抖落库链（flushHistoryToCache）：recordHistory 早于参数输入
     * （预览产物先落历史、参数后填），快照只能在编辑发生时补进。匹配谓词由调用方给定
     * （缓存键 = tableName/SQL 指纹，见 ParamHistoryCache.computeKey——manager 不依赖 ui 包）。
     *
     * @return 是否命中并更新（未命中不抛——面板链路静默容错）
     */
    fun updateNewestParamSnapshot(match: (SqlHistoryEntry) -> Boolean, snapshot: Map<String, String>): Boolean {
        if (snapshot.isEmpty()) return false
        val entry = entries.firstOrNull(match) ?: return false
        entry.paramSnapshot.clear()
        entry.paramSnapshot.putAll(snapshot)
        return true
    }

    /**
     * R51：按匹配谓词取**最新一条**条目的参数快照（recall 链在 ParamHistoryCache 未命中时的
     * 回落源；快照为空的条目不参与——无快照的历史回落无意义）。
     */
    fun newestParamSnapshot(match: (SqlHistoryEntry) -> Boolean): Map<String, String>? {
        val entry = entries.firstOrNull { match(it) && it.paramSnapshot.isNotEmpty() } ?: return null
        return entry.paramSnapshot
    }

    /**
     * F-08：把参数快照回写到**本项目**最新一条满足 [match] 的条目——项目收窄版
     * [updateNewestParamSnapshot]。双项目并发同表名/同 SQL 不串快照。
     *
     * 收窄判据（[matchesProject]）：identity **非空且不同**的条目出局；identity 空白的旧条目
     * 保持可命中（旧数据读回兼容，不因新维度收紧而失去既有回落能力）。
     * 独立命名而非加默认参数：既有调用点混用「括号内 lambda」与「尾 lambda」两种形态，
     * 动既有签名两种形态都救不了（K2 实证），项目维度单独成方法零波及。
     *
     * @return 是否命中并更新（未命中不抛——面板链路静默容错）
     */
    fun updateNewestParamSnapshotForProject(
        projectIdentity: String,
        match: (SqlHistoryEntry) -> Boolean,
        snapshot: Map<String, String>,
    ): Boolean {
        if (snapshot.isEmpty()) return false
        val entry = entries.firstOrNull { match(it) && matchesProject(it, projectIdentity) } ?: return false
        entry.paramSnapshot.clear()
        entry.paramSnapshot.putAll(snapshot)
        return true
    }

    /**
     * F-08：取**本项目**最新一条满足 [match] 的条目的参数快照——项目收窄版
     * [newestParamSnapshot]（收窄判据同 [updateNewestParamSnapshotForProject]）。
     */
    fun newestParamSnapshotForProject(
        projectIdentity: String,
        match: (SqlHistoryEntry) -> Boolean,
    ): Map<String, String>? {
        val entry = entries.firstOrNull {
            match(it) && it.paramSnapshot.isNotEmpty() && matchesProject(it, projectIdentity)
        } ?: return null
        return entry.paramSnapshot
    }

    /**
     * F-08 项目收窄判据：条目 identity 空白（旧数据/未归属）放行；否则须与请求方 identity 相等。
     */
    private fun matchesProject(entry: SqlHistoryEntry, projectIdentity: String): Boolean =
        entry.projectIdentity.isBlank() || entry.projectIdentity == projectIdentity

    // ---------- 删除 / 清空 ----------

    /** 按 id 删除条目。 */
    @Synchronized
    fun remove(id: String) {
        state.entries.removeAll { it.id == id }
        state.favoriteEntries.removeAll { it.id == id }
    }

    /** 清空全部历史（含收藏）。 */
    @Synchronized
    fun clear() {
        planGeneration++
        state.entries.clear()
        state.favoriteEntries.clear()
    }

    // ---------- 持久化 ----------

    override fun getState(): State = state

    @Synchronized
    override fun loadState(state: State) {
        planGeneration++
        // 防御 null/损坏：任一列表为 null 时置空（XmlSerializer 不会产 null，但外部覆写可能）
        this.state = State(
            entries = state.entries ?: mutableListOf(),
            favoriteEntries = state.favoriteEntries ?: mutableListOf(),
        )
        enforcePlanBounds()
    }

    // ---------- 内部工具 ----------

    /** 两个分区按时间戳倒序合并。 */
    private fun mergeByTimestamp(
        a: List<SqlHistoryEntry>,
        b: List<SqlHistoryEntry>,
    ): List<SqlHistoryEntry> {
        val result = ArrayList<SqlHistoryEntry>(a.size + b.size)
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            if (a[i].timestamp >= b[j].timestamp) result.add(a[i++]) else result.add(b[j++])
        }
        while (i < a.size) result.add(a[i++])
        while (j < b.size) result.add(b[j++])
        return result
    }

    companion object {
        const val MAX_PLANS_PER_ENTRY = 5
        const val MAX_PLAN_BYTES = 256 * 1024
        const val MAX_TOTAL_PLAN_BYTES = 8 * 1024 * 1024

        /** 默认历史记录上限（非收藏条目）。 */
        const val DEFAULT_MAX_SIZE = 200

        /** T-Perf-19：收藏条目上限（宽松封顶防无限增长）。 */
        const val MAX_FAVORITES = 500

        /** 全局实例获取。 */
        @JvmStatic
        fun getInstance(): SqlHistoryManager =
            ApplicationManager.getApplication().getService(SqlHistoryManager::class.java)
    }
}
