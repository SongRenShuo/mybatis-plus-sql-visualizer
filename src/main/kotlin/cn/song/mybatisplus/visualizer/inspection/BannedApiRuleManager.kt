package cn.song.mybatisplus.visualizer.inspection

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 禁用 API 规则管理器（FR-0045 第 2 项 T-BannedApiCheck）：规则增删改查 + 上限拒绝 + 变更通知，
 * 跨会话持久化。
 *
 * 模板照抄 [cn.song.mybatisplus.visualizer.log.SqlSuppressRuleManager]（仓内既有同类实现的唯一
 * 权威形态）：
 *  - application-level 轻服务（`@Service(Service.Level.APP)`）——团队规范规则跨项目通用，
 *    与屏蔽规则/历史记录同作用域；
 *  - 经 XmlSerializer 持久化到**独立文件** `mp-sql-visualizer-banned-api.xml`
 *    （不与 [cn.song.mybatisplus.visualizer.config.PluginSettings] 的 `mp-sql-visualizer.xml`
 *    互踩写盘，也不与屏蔽规则的 `mp-sql-visualizer-suppress.xml` 混装）；
 *  - 应用级必须 `RoamingType.DISABLED`（Settings Sync 回滚坑，见 PluginSettings KDoc）。
 *
 * 运行时判据 [compiledRules]：规则变更时**整体重建并替换**（@Volatile 发布）——inspection 与
 * 存量扫描各自取快照读，规则变更不打断在途扫描，也不会读到半更新状态。
 *
 * 上限 [MAX_RULES]：inspection 在**每个方法调用点**都要过一遍规则表，规则数直接进 daemon 热路径；
 * 200 条对团队规范清单足够，超限拒新增（先到先得，不做 LRU 淘汰——静默淘汰会让「规则突然失效」
 * 无从排查）。
 *
 * headless 测试约束：本类除 [getInstance] 外无平台依赖（Logger 不建、无 UI），可直接
 * `BannedApiRuleManager()` 构造纯 JUnit。
 */
@State(
    name = "MpSqlVisualizerBannedApiRules",
    storages = [Storage("mp-sql-visualizer-banned-api.xml", roamingType = RoamingType.DISABLED)],
)
@Service(Service.Level.APP)
class BannedApiRuleManager : PersistentStateComponent<BannedApiRuleManager.State> {

    /** 可持久化状态（XmlSerializer 序列化）。规则按创建序排列（index 0 = 最早）。 */
    data class State(
        var rules: MutableList<BannedApiRule> = mutableListOf(),
    )

    private var state = State()

    /** 规则变更互斥锁（EDT 单写为主，与后台 getState（IDE 保存）隔离，防并发遍历 CME）。 */
    private val rulesLock = Any()

    /** 预编译匹配表（不可变快照，@Volatile 发布；空表时 inspection 零行为）。 */
    @Volatile
    var compiledRules: CompiledRules = CompiledRules(emptyList())
        private set

    /** 变更监听器（管理页订阅后刷新列表；CopyOnWriteArrayList——注册/注销低频、回调遍历高频）。 */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    // ---------- 查询 ----------

    /** 全部规则快照（创建序）。 */
    val rules: List<BannedApiRule>
        get() = synchronized(rulesLock) { state.rules.toList() }

    /** 指定 id 的规则快照；不存在返回 null。 */
    fun ruleById(id: String): BannedApiRule? =
        synchronized(rulesLock) { state.rules.firstOrNull { it.id == id } }

    // ---------- 增 / 改 / 删 / 清 ----------

    /**
     * 新增规则。
     *
     * @return [AddResult.ADDED]（新建并发布通知）/ [AddResult.DUPLICATE]（同目标已存在，幂等跳过；
     * 判重只看「类匹配串 + 模式 + 方法名」，说明文字不同不算新规则）/ [AddResult.INVALID]
     * （[BannedApiMatcher.validate] 不过）/ [AddResult.LIMIT_REACHED]（达 [MAX_RULES]）
     */
    fun addRule(rule: BannedApiRule): AddResult {
        if (BannedApiMatcher.validate(rule) != null) return AddResult.INVALID
        synchronized(rulesLock) {
            if (state.rules.any { sameTarget(it, rule) }) return AddResult.DUPLICATE
            if (state.rules.size >= MAX_RULES) return AddResult.LIMIT_REACHED
            state.rules += BannedApiRule(
                className = rule.className.trim(),
                classRegex = rule.classRegex,
                methodName = rule.methodName.trim(),
                description = rule.description.trim(),
                level = rule.level,
                createdAt = System.currentTimeMillis(),
            ).apply { ensureId() }
        }
        republish()
        return AddResult.ADDED
    }

    /**
     * 按 id 覆盖单条规则（管理页「保存修改」）。
     *
     * @return [UpdateResult.UPDATED] / [UpdateResult.NOT_FOUND]（规则已被别的窗口删掉）/
     * [UpdateResult.DUPLICATE]（改成与另一条同目标）/ [UpdateResult.INVALID]（校验不过）
     */
    fun updateRule(rule: BannedApiRule): UpdateResult {
        if (BannedApiMatcher.validate(rule) != null) return UpdateResult.INVALID
        val changed = synchronized(rulesLock) {
            val existing = state.rules.firstOrNull { it.id == rule.id } ?: return UpdateResult.NOT_FOUND
            if (state.rules.any { it.id != rule.id && sameTarget(it, rule) }) return UpdateResult.DUPLICATE
            val sanitized = BannedApiRule(
                id = existing.id,
                className = rule.className.trim(),
                classRegex = rule.classRegex,
                methodName = rule.methodName.trim(),
                description = rule.description.trim(),
                level = rule.level,
                createdAt = existing.createdAt,
            )
            val idx = state.rules.indexOf(existing)
            state.rules[idx] = sanitized
            true
        }
        if (changed) republish()
        return UpdateResult.UPDATED
    }

    /** 按 id 删除单条规则；确有删除时发布通知（返回是否删除）。 */
    fun removeRule(id: String): Boolean {
        val removed = synchronized(rulesLock) { state.rules.removeAll { it.id == id } }
        if (removed) republish()
        return removed
    }

    /** 清空全部规则（管理页「清空」；确认交互由调用方负责）。返回是否有变化。 */
    fun clearRules(): Boolean {
        val cleared = synchronized(rulesLock) {
            val wasEmpty = state.rules.isEmpty()
            state.rules.clear()
            !wasEmpty
        }
        if (cleared) republish()
        return cleared
    }

    // ---------- 变更通知 ----------

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /**
     * 整体重建并发布预编译匹配表 + 通知监听器。先原子替换 [compiledRules] 再遍历监听器——
     * 订阅者回调里读到的必是新规则。
     */
    private fun republish() {
        compiledRules = BannedApiMatcher.compile(rules)
        listeners.forEach { it() }
    }

    // ---------- 持久化 ----------

    override fun getState(): State = state

    override fun loadState(state: State) {
        // 防御 null/损坏：列表为 null 时置空（XmlSerializer 不产 null，外部覆写可能）
        synchronized(rulesLock) {
            this.state = State(rules = state.rules ?: mutableListOf())
            // 存量/手改 XML 可能缺 id 或缺时间戳——就地补齐，保证列表选中与删除可用
            this.state.rules.forEach { rule ->
                rule.ensureId()
                if (rule.level !in BannedApiRule.LEVELS) rule.level = BannedApiRule.LEVEL_WARNING
            }
        }
        republish() // 重建匹配表（启动恢复 / 测试 round-trip 同路）
    }

    /** 判重口径：目标（类匹配串 + 模式 + 方法名）相同即同一条规则；说明/级别不同不算新规则。 */
    private fun sameTarget(a: BannedApiRule, b: BannedApiRule): Boolean =
        a.className.trim() == b.className.trim() &&
            a.classRegex == b.classRegex &&
            a.methodName.trim() == b.methodName.trim()

    /** 新增结果。 */
    enum class AddResult {
        /** 新建成功。 */
        ADDED,

        /** 同目标规则已存在——幂等跳过。 */
        DUPLICATE,

        /** 规则校验不过（类名/说明为空、正则非法、级别未知）。 */
        INVALID,

        /** 达上限拒新增（[MAX_RULES]）。 */
        LIMIT_REACHED,
    }

    /** 修改结果。 */
    enum class UpdateResult {
        /** 已覆盖。 */
        UPDATED,

        /** 指定 id 的规则不存在（并发删除）。 */
        NOT_FOUND,

        /** 修改后与另一条规则同目标。 */
        DUPLICATE,

        /** 规则校验不过。 */
        INVALID,
    }

    companion object {
        /** 规则上限（inspection 热路径成本 = 方法调用点数 × 规则数）。 */
        const val MAX_RULES = 200

        /** 全局实例获取。 */
        @JvmStatic
        fun getInstance(): BannedApiRuleManager =
            ApplicationManager.getApplication().getService(BannedApiRuleManager::class.java)
    }
}
