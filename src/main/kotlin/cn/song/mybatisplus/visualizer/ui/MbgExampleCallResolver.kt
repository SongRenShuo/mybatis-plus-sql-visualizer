package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.FatalErrors
import cn.song.mybatisplus.visualizer.xml.MbgCriterionMock
import cn.song.mybatisplus.visualizer.xml.MockObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MBG Example 调用点解析（T-MbgExamplePreview M2）。
 *
 * MBG 生成器的 Example 用法固定为
 * `example.createCriteria().andCustIdEqualTo(v)` + `mapper.selectByExample(example)`，
 * 而 `andXxxYyy` 的方法体固定是一句 `addCriterion("cust_id =", value, "custId")`。
 * 所以反推 Criterion 不需要猜列名：从 mapper 方法的调用点拿到 example 变量，
 * 在同一方法体里找 `and*` / `or*` 调用，再读对应方法的 `addCriterion` 第一个实参常量即可。
 *
 * 产出直接喂给 [cn.song.mybatisplus.visualizer.xml.XmlPreviewModel.from] 的
 * `collectionElements`：
 *  - `oredCriteria` → 一个 Criteria 元素（`valid=true` + `criteria` 列表）
 *  - `criteria.criteria` → 上述 Criterion 元素
 *
 * 全部失败路径返回 null（无 project / 接口不可解析 / 找不到调用点 / 方法体没有 addCriterion 常量），
 * 上游据此退回升级前的「无属性元素」行为——不报错、不改变既有预览。
 */
object MbgExampleCallResolver {

    private val LOG = Logger.getInstance(MbgExampleCallResolver::class.java)

    /** MBG Criteria 的方法名：`and` / `or` + 大写驼峰首字母。 */
    private val AND_OR_METHOD = Regex("""^(and|or)[A-Z]\w+$""")

    /** 单次反推的 Criterion 上限（防御性：方法体里可能有大量链式调用）。 */
    private const val MAX_CRITERIA = 20

    // ------------------------------------------------------------------
    // T-StartupReadLockFreeze（S2/S3）：预取与预览解析的预算
    // 现场：后台预取在**阻塞读锁**里跑 `ReferencesSearch`，与 EDT 的「写意图 → 写锁升级」互等
    // （计划 §二/§三）。以下预算只削减投机性加速，任何一项命中都不影响预览功能本身。
    // ------------------------------------------------------------------

    /** Registry 总开关（plugin.xml `<registryKey>`）；关闭＝整段预取早退，预览不受影响。 */
    internal const val REGISTRY_KEY = "sqlvis.mbg.prefetch"

    /** 项目首次被本插件看到后的冷却窗口：避开索引构建与数据源加载窗口（S2 第 3 条）。 */
    internal const val PREFETCH_COOLDOWN_MS = 10_000L

    /** 每项目每会话的预取检索次数上限（S2 第 2 条）。 */
    internal const val PREFETCH_BUDGET_PER_PROJECT = 32

    /** 单次预取检索的硬预算：到点取消并记入失败记忆（S2 第 2 条）。 */
    internal const val PREFETCH_SEARCH_BUDGET_MS = 1_500L

    /**
     * 规模熔断阈值（S2 第 1 条）：取计划 §九 D-2 的「首次检索耗时自适应」口径，不用模块文件数计数。
     *
     * 理由：文件计数要额外查一次 `FileIndex`/`FileTypeIndex`，那本身也是索引操作（索引期同样会等）；
     * 而耗时是检索**已经付过的成本**，零额外查询，且天然适配「同一 namespace 在不同工程规模下」
     * 的差异（静态阈值只能按最大工程保守取值）。超过该阈值即本会话对该 namespace 熔断。
     */
    internal const val SCALE_BREAKER_MS = 800L

    /** 预览侧 `resolve()` 的单次预算（S3）：超预算返回 null，由调用方降级为占位骨架。 */
    internal const val RESOLVE_BUDGET_MS = 800L

    /**
     * 缓存条目（T-PreviewOpenPerfM2 M1）：`ReferencesSearch` 是**全项目索引检索**，实测在最小规模
     * demo 上就占装配耗时的 78%（`[open-perf] m2=207` / `total=266ms`），大工程按命中规模放大；
     * 而原实现每次 `preview()` 都重跑。key＝`namespace#statementId`，失效判据＝解析时实际读过的
     * 文件的 `modificationStamp`（mapper 接口 / Example 方法 / 调用点所在文件，任一改动即失效）。
     */
    internal data class CachedResolution(
        val elements: Map<String, List<MockObject>>,
        val stamps: Map<VirtualFile, Long>,
    )

    private val cache = ConcurrentHashMap<String, CachedResolution>()

    /** 预取失败记忆（本会话不再重试；预览路径不受影响）。 */
    private val prefetchFailed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 每个项目第一次被本插件看到的时间：冷却窗口（[PREFETCH_COOLDOWN_MS]）的起点。 */
    private val firstSeenAt: MutableMap<String, Long> = ConcurrentHashMap()

    /** 每项目每会话已消耗的预取预算（[PREFETCH_BUDGET_PER_PROJECT]）。 */
    private val budgetUsed: MutableMap<String, Int> = ConcurrentHashMap()

    /** 规模熔断：检索耗时超过 [SCALE_BREAKER_MS] 的 namespace，本会话不再预取（S2 第 1 条）。 */
    private val circuitOpenNamespaces: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 诊断/测试缝：缓存命中次数与真正执行检索的次数（单线程测试用，生产仅观测）。 */
    internal var cacheHits: Int = 0
        private set

    internal var searchRuns: Int = 0
        private set

    /** 测试缝：dumb 判定（生产＝`DumbService.isDumb`）。 */
    internal var isDumbProvider: (Project) -> Boolean = { project ->
        runCatching { DumbService.isDumb(project) }.getOrDefault(false)
    }

    /** 测试缝：项目是否初始化完成（平台 API 在项目打开早期为 false）。 */
    internal var isProjectInitializedProvider: (Project) -> Boolean = { project ->
        runCatching { project.isInitialized }.getOrDefault(false)
    }

    /** 测试缝：Registry 总开关读取（生产＝`Registry.is(REGISTRY_KEY, true)`）。 */
    internal var prefetchEnabledProvider: () -> Boolean = {
        // `is` 是 Kotlin 关键字，调用 Java 侧 `Registry.is(String, boolean)` 必须加反引号；
        // 该重载在 key 未注册时直接返回默认值（不抛 MissingResourceException），正好当兜底。
        runCatching { Registry.`is`(REGISTRY_KEY, true) }.getOrDefault(true)
    }

    /** 测试缝：清空缓存、计数与全部预取闸门状态。 */
    internal fun resetCacheForTest() {
        cache.clear()
        cacheHits = 0
        searchRuns = 0
        prefetchFailed.clear()
        firstSeenAt.clear()
        budgetUsed.clear()
        circuitOpenNamespaces.clear()
    }

    /** 一次检索的产物＋耗时（规模门与日志都要用真实检索耗时，不含等索引的时间）。 */
    private data class SearchOutcome(val resolved: CachedResolution?, val elapsedMs: Long)

    /**
     * 预览侧同步入口（S3）。
     *
     * 原实现是 `ReadAction.compute`——**阻塞读锁**，在几千文件的工程里一次检索可秒级持锁，
     * 撞上 EDT 的「写意图 → 写锁升级」就是计划 §三 的互等。现在改走
     * `ReadAction.nonBlocking { … }.inSmartMode(project)`：
     *  - 等索引交给平台在**不持读锁**的状态下等待（`inSmartMode`）；
     *  - 检索在应用线程池里跑，调用方只按 [RESOLVE_BUDGET_MS] 有界等待，超预算取消并返回 null
     *    （调用方 `XmlPreviewService` 已按「解析不到」降级为占位骨架）；
     *  - 调用方**已经**持读锁时（如 MCP 渲染链 `ReadAction.compute` 内）不退化成后台等锁，
     *    就地嵌套读动作——否则多一次读锁往返，还可能撞上写优先的等待。
     *
     * 返回值语义不变：解析不到 / 超预算 / 异常都返回 null。
     */
    fun resolve(
        project: Project?,
        namespace: String?,
        statementId: String,
    ): Map<String, List<MockObject>>? {
        if (project == null || namespace.isNullOrBlank()) return null
        // key 带 project 身份：本 object 是进程级，多项目窗口打开时同 `namespace#statementId`
        // 会撞车（不同项目可以有同名 mapper）
        val key = keyOf(project, namespace, statementId)
        cachedIfFresh(key)?.let { return it }
        val startedMs = System.currentTimeMillis()
        // T-StartupReadLockFreeze §14（2026-11-13 真机 `m2=3219ms` 实证）：预算必须**落在检索本身**，
        // 不能只落在「等多久」上——预览主链 `SqlPreviewService.previewAsync` 把整段装配包在
        // `ReadAction.compute` 里，`isReadAccessAllowed` 恒为真，于是原来的「已持读锁→就地嵌套」分支
        // 绕开了 `blockingGet(RESOLVE_BUDGET_MS)`，冷态一次 ReferencesSearch 把 800ms 上限吃成 3.2s
        // （用户 2500 表工程上是 30s 量级）。现在两条分支共用同一个截止时刻，检索内部逐条引用自查。
        val deadlineAt = startedMs + RESOLVE_BUDGET_MS
        val outcome = try {
            if (ApplicationManager.getApplication().isReadAccessAllowed) {
                ReadAction.compute<SearchOutcome, RuntimeException> {
                    searchOnce(key) { resolveInReadAction(project, namespace, statementId, deadlineAt) }
                }
            } else {
                resolveBounded(project, namespace, statementId, key, startedMs, deadlineAt)
            }
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t
            logSkip("resolve", "error:${t.javaClass.simpleName}", startedMs, key)
            return null
        }
        val resolved = outcome?.resolved
        if (resolved == null) {
            // 到点被截断要留痕（与「解析不到」区分），真机按 reason=deadline 检索
            if (System.currentTimeMillis() >= deadlineAt) logSkip("resolve", "deadline", startedMs, key)
            return null
        }
        return storeResolved(key, resolved).elements
    }

    /** S3 的有界路径：提交非阻塞读动作，只等 [RESOLVE_BUDGET_MS]（检索内部同时受 [deadlineAt] 约束）。 */
    private fun resolveBounded(
        project: Project,
        namespace: String,
        statementId: String,
        key: String,
        startedMs: Long,
        deadlineAt: Long,
    ): SearchOutcome? {
        val promise = ReadAction.nonBlocking<SearchOutcome> {
            searchOnce(key) { resolveInReadAction(project, namespace, statementId, deadlineAt) }
        }
            .inSmartMode(project)
            .expireWith(project)
            .submit(AppExecutorUtil.getAppExecutorService())
        return try {
            promise.blockingGet(RESOLVE_BUDGET_MS.toInt(), TimeUnit.MILLISECONDS)
        } catch (t: TimeoutException) {
            promise.cancel()
            logSkip("resolve", "timeout", startedMs, key)
            null
        } catch (t: Throwable) {
            promise.cancel()
            if (FatalErrors.isFatal(t)) throw t
            logSkip("resolve", "error:${t.javaClass.simpleName}", startedMs, key)
            null
        }
    }

    /**
     * 缓存外壳（**纯逻辑**，可单测）：命中且依赖文件未变则直接返回；否则执行 [compute] 并落缓存。
     *
     * `compute` 返回 null 表示「解析不到」——**不写缓存**：把「当时索引没就绪 / 找不到调用点」
     * 永久固化下来会让下次打开永远拿不到（宁可比预期多跑一次）。
     *
     * T-StartupReadLockFreeze S1：拆成「新鲜判定 / 检索 / 落缓存」三段（[cachedIfFresh] /
     * [searchOnce] / [storeResolved]），预取的非阻塞回调只需复用后两段——本函数的行为保持不变。
     */
    internal fun resolveCached(
        key: String,
        compute: () -> CachedResolution?,
    ): Map<String, List<MockObject>>? {
        cachedIfFresh(key)?.let { return it }
        val outcome = searchOnce(key, compute)
        val resolved = outcome.resolved
        if (resolved == null) {
            // 不写缓存：把「当时索引没就绪 / 找不到调用点」永久固化会让下次打开永远拿不到
            LOG.info("[m2] unresolved（不写缓存） $key")
            return null
        }
        return storeResolved(key, resolved).elements
    }

    /** 新鲜判定 + 命中计数（缓存命中才自增 [cacheHits]）。 */
    private fun cachedIfFresh(key: String): Map<String, List<MockObject>>? {
        val cached = cache[key] ?: return null
        if (!isFresh(cached)) {
            LOG.info("[m2] cache stale (依赖文件已改) $key")
            return null
        }
        cacheHits++
        LOG.info("[m2] cache hit ${describe(cached)} $key")
        return cached.elements
    }

    /** 唯一自增 [searchRuns] 的地方：真正执行一次 PSI 检索并计时。 */
    private fun searchOnce(key: String, compute: () -> CachedResolution?): SearchOutcome {
        searchRuns++
        val startedMs = System.currentTimeMillis()
        val resolved = compute()
        return SearchOutcome(resolved, System.currentTimeMillis() - startedMs)
    }

    /** 落缓存（唯一的写入口）。 */
    private fun storeResolved(key: String, resolved: CachedResolution): CachedResolution {
        cache[key] = resolved
        LOG.info("[m2] resolved ${describe(resolved)} $key")
        return resolved
    }

    private fun describe(entry: CachedResolution): String =
        "criteria=${entry.elements["criteria.criteria"]?.size ?: 0} stamps=${entry.stamps.size}"

    private fun keyOf(project: Project, namespace: String, statementId: String): String =
        "${project.locationHash}#$namespace#$statementId"

    /** S6：跳过/放弃路径统一一条 info（`[m2] <stage> skip reason=… elapsedMs=…`），供真机取证。 */
    private fun logSkip(stage: String, reason: String, startedMs: Long, key: String) {
        LOG.info("[m2] $stage skip reason=$reason elapsedMs=${System.currentTimeMillis() - startedMs} key=$key")
    }

    /**
     * 检索内部的截止判定（T-StartupReadLockFreeze §14）。
     *
     * 必须放在**检索本身**里，而不是调用方「等多久」上：预览主链整体包在 `ReadAction.compute` 内，
     * `isReadAccessAllowed` 恒为真，等待式预算（`blockingGet`）在那种上下文里根本走不到。
     * `Long.MAX_VALUE`（默认值）语义＝不设限，供纯逻辑单测与预取链复用。
     */
    private fun isDeadlineHit(deadlineAt: Long): Boolean =
        deadlineAt != Long.MAX_VALUE && System.currentTimeMillis() >= deadlineAt

    /** 测试缝：截止判定的纯逻辑入口（见 `MbgExampleResolveDeadlineTest`）。 */
    internal fun isDeadlineHitForTest(deadlineAt: Long): Boolean = isDeadlineHit(deadlineAt)

    /**
     * 预取（T-PreviewOpenPerfM2 M11）：把 M2 检索挪到「用户还在看文件」的时候。
     *
     * 真机首开 `[open-perf] … m2=1628/2042ms`、`request→dialog 2714/4580ms`，而第二次起 m2≈0、
     * 弹窗 200ms 级——首开慢的全部就是这一次冷检索。gutter 渲染本来就要遍历 mapper 语句，
     * 顺手在后台跑一次即可让「点开」命中缓存；不改变任何交互，失败静默。
     *
     * **T-StartupReadLockFreeze（S1/S2）**：原实现用 `executeOnPooledThread` + `resolve()` 的
     * **阻塞读锁**跑索引检索，并且在途集合是「全局单飞」（一条卡住拖死所有 key）。现在：
     *  - 检索走 `ReadAction.nonBlocking { … }.inSmartMode(project).expireWith(project)
     *    .coalesceBy(this, key)`——等索引不持读锁、可被取消，同 key 单飞而不同 key 互不阻塞；
     *  - 三道闸（Registry 开关 / 项目就绪 / dumb / 冷却）与预算、规模熔断见 [decidePrefetch]；
     *  - 单次硬预算 [PREFETCH_SEARCH_BUDGET_MS] 到点取消该任务并记失败记忆；
     *  - **失败记忆**：解析不到（`countByExample` / `deleteByExample` 这类在本项目里根本没有调用点的
     *    语句就是常态）不写缓存，而 gutter 会反复重绘 —— 不记失败就会变成每次重绘都重跑一次全项目
     *    检索（实测 6 秒内同一语句跑了 4 轮、日志刷屏）。记下 key 后本会话不再预取。
     *  - 单元测试模式不触发（避免后台线程干扰断言）。
     */
    fun prefetch(project: Project?, namespace: String?, statementId: String) {
        if (project == null || namespace.isNullOrBlank()) return
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val key = keyOf(project, namespace, statementId)
        val startedMs = System.currentTimeMillis()
        val decision = decidePrefetch(
            projectKey = project.locationHash,
            namespace = namespace,
            key = key,
            initialized = isProjectInitializedProvider(project),
            dumb = isDumbProvider(project),
            enabled = prefetchEnabledProvider(),
            nowMs = startedMs,
        )
        if (!decision.allowed) {
            logSkip("prefetch", decision.reason ?: "gate", startedMs, key)
            return
        }
        submitPrefetch(project, namespace, statementId, key, startedMs)
    }

    /** 预取决策（放行 / 跳过原因）。纯函数（状态只在本 object 内），可在无平台环境单测。 */
    internal fun decidePrefetch(
        projectKey: String,
        namespace: String,
        key: String,
        initialized: Boolean,
        dumb: Boolean,
        enabled: Boolean,
        nowMs: Long,
    ): PrefetchDecision {
        // 冷却起点＝本插件第一次看到该项目的时刻（243 的 Project 没有「打开时间」API；
        // gutter 首次渲染与项目打开基本同期，用首次到达时间当代理）
        val firstSeen = firstSeenAt.computeIfAbsent(projectKey) { nowMs }
        if (!enabled) return PrefetchDecision(false, "registry-off")
        if (!initialized) return PrefetchDecision(false, "project-not-initialized")
        if (dumb) return PrefetchDecision(false, "dumb-mode")
        if (nowMs - firstSeen < PREFETCH_COOLDOWN_MS) return PrefetchDecision(false, "cooldown")
        if (namespace in circuitOpenNamespaces) return PrefetchDecision(false, "scale-circuit-open")
        if (key in prefetchFailed) return PrefetchDecision(false, "failed-before")
        cache[key]?.let { if (isFresh(it)) return PrefetchDecision(false, "cache-fresh") }
        val used = budgetUsed[projectKey] ?: 0
        if (used >= PREFETCH_BUDGET_PER_PROJECT) return PrefetchDecision(false, "budget-exhausted")
        budgetUsed[projectKey] = used + 1
        return PrefetchDecision(true, null)
    }

    /** 预取决策结果；[reason] 同时进日志与测试断言。 */
    internal data class PrefetchDecision(val allowed: Boolean, val reason: String?)

    /**
     * 生产调度：非阻塞读动作 + 单次硬预算。
     *
     * 三条关键属性（对应现场两根栈）：
     *  1. 检索不再由调用线程持阻塞读锁执行——`inSmartMode` 把「等索引」交给平台；
     *  2. 超时 `cancel()` 由 nonBlocking 的进度检查传播进 `ReferencesSearch`，读锁随之释放；
     *  3. `coalesceBy(this, key)` 只合并同一个 key，卡住一条不再阻塞别的 key。
     */
    private fun submitPrefetch(
        project: Project,
        namespace: String,
        statementId: String,
        key: String,
        startedMs: Long,
    ) {
        val promise = ReadAction.nonBlocking<SearchOutcome> {
            searchOnce(key) { resolveInReadAction(project, namespace, statementId) }
        }
            .inSmartMode(project)
            .expireWith(project)
            .coalesceBy(this, key)
            .submit(AppExecutorUtil.getAppExecutorService())
        // 只有第一条结算路径生效（超时 / 成功 / 失败可能并发）
        val settled = AtomicBoolean(false)
        fun settle(action: () -> Unit) {
            if (settled.compareAndSet(false, true)) action()
        }
        val timer = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            settle {
                if (!promise.isDone) promise.cancel()
                prefetchFailed.add(key)
                logSkip("prefetch", "timeout", startedMs, key)
            }
        }, PREFETCH_SEARCH_BUDGET_MS, TimeUnit.MILLISECONDS)
        promise.onSuccess { outcome ->
            timer.cancel(false)
            settle { onPrefetchDone(key, namespace, outcome, startedMs) }
        }.onError { error ->
            timer.cancel(false)
            settle {
                if (error is CancellationException || error is com.intellij.openapi.progress.ProcessCanceledException) {
                    // 项目关闭 / 被同 key 的新任务合并掉：不记失败，只留一条日志
                    logSkip("prefetch", "cancelled", startedMs, key)
                    return@settle
                }
                prefetchFailed.add(key)
                logSkip("prefetch", "error:${error.javaClass.simpleName}", startedMs, key)
            }
        }
    }

    /** 预取成功/解析不到的统一收口：落缓存或记失败，并按真实检索耗时决定是否熔断该 namespace。 */
    private fun onPrefetchDone(key: String, namespace: String, outcome: SearchOutcome, startedMs: Long) {
        val resolved = outcome.resolved
        if (resolved == null) {
            prefetchFailed.add(key)
            logSkip("prefetch", "unresolved", startedMs, key)
            return
        }
        if (outcome.elapsedMs >= SCALE_BREAKER_MS) {
            circuitOpenNamespaces.add(namespace)
            LOG.info("[m2] prefetch circuit-open namespace=$namespace searchMs=${outcome.elapsedMs}")
        }
        storeResolved(key, resolved)
    }

    /**
     * 测试缝：跑一遍与 [prefetch] **同一套**决策链，放行时同步执行「检索 + 落缓存」。
     *
     * 生产与这里的差异只有「调度」那一段（`ReadAction.nonBlocking` + 预算计时器）——决策、
     * 计数与落盘走同一条代码。纯 JUnit 里没有 Project，故项目身份与三道闸读数由参数注入。
     *
     * @return null＝已放行（并已执行一次检索）；非 null＝跳过原因
     */
    internal fun prefetchForTest(
        projectKey: String,
        namespace: String,
        statementId: String,
        initialized: Boolean = true,
        dumb: Boolean = false,
        enabled: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
        search: () -> CachedResolution? = { null },
    ): String? {
        val key = "$projectKey#$namespace#$statementId"
        val startedMs = System.currentTimeMillis()
        val decision = decidePrefetch(projectKey, namespace, key, initialized, dumb, enabled, nowMs)
        if (!decision.allowed) {
            logSkip("prefetch", decision.reason ?: "gate", startedMs, key)
            return decision.reason
        }
        onPrefetchDone(key, namespace, searchOnce(key, search), startedMs)
        return null
    }

    /** 依赖文件全部仍有效且 stamp 未变才算新鲜（不引文件监听，按需比 stamp）。 */
    private fun isFresh(entry: CachedResolution): Boolean =
        entry.stamps.all { (file, stamp) -> file.isValid && file.modificationStamp == stamp }

    private fun resolveInReadAction(
        project: Project,
        namespace: String,
        statementId: String,
        deadlineAt: Long = Long.MAX_VALUE,
    ): CachedResolution? {
        val trace = StringBuilder()
        val mapperClass = JavaPsiFacade.getInstance(project)
            .findClass(namespace, GlobalSearchScope.allScope(project))
        if (mapperClass == null) {
            LOG.info("[m2] trace $namespace#$statementId mapperClass=NOT_FOUND")
            return null
        }
        val stamps = mutableMapOf<VirtualFile, Long>()
        fun note(file: PsiFile?) {
            file?.virtualFile?.takeIf { it.isValid }?.let { stamps[it] = it.modificationStamp }
        }
        note(mapperClass.containingFile)
        // T-PreviewOpenPerfM2 M10：检索范围收到「mapper 所在模块及其依赖它的模块」。
        // 引用方**必须**能看见该接口，所以这个范围在语义上不丢命中，却比全项目小得多——
        // 真机首次打开 m2=1628ms（253 处引用、冷索引），全项目作用域是大头。
        val module = runCatching { ModuleUtilCore.findModuleForPsiElement(mapperClass) }.getOrNull()
        val scope = if (module != null) {
            GlobalSearchScope.moduleWithDependentsScope(module)
        } else {
            GlobalSearchScope.projectScope(project)
        }
        trace.append("scope=${if (module != null) module.name else "PROJECT"} ")
        // D7（真机 2026-11-12 15:00）：`oredCriteria` 必须按**每条 Criteria**分组，不能拍平成一条。
        // `example.createCriteria().andA()` 与 `example.or().andB()` 在 MBG 里是两条 Criteria，
        // `Example_Where_Clause` 用 `separator="or"` 连接——拍平就把 `( a ) or ( b )` 错拼成 `a and b`。
        val groups = LinkedHashMap<Any, MutableList<MbgCriterionMock>>()
        val methods = mapperClass.findMethodsByName(statementId, true)
        trace.append("methods=${methods.size} ")
        methods.forEach { method ->
            if (isDeadlineHit(deadlineAt)) {
                trace.append("deadline ")
                return@forEach
            }
            note(method.containingFile)
            val call = findCallSite(scope, method, deadlineAt)
            if (call == null) {
                trace.append("noCallSite ")
                return@forEach
            }
            note(call.containingFile)
            val exampleName = exampleVariableName(call)
            if (exampleName == null) {
                trace.append("noExampleArg(arg=${call.argumentList.expressions.firstOrNull()?.text?.take(40)}) ")
                return@forEach
            }
            val body = PsiTreeUtil.getParentOfType(call, PsiCodeBlock::class.java)
            if (body == null) {
                trace.append("noBody ")
                return@forEach
            }
            val found = collectCriteria(project, body, exampleName, trace)
            trace.append("[${exampleName}:${found.values.sumOf { it.size }}/${found.size}] ")
            for ((groupKey, list) in found) {
                groups.getOrPut(groupKey) { mutableListOf() }.addAll(list)
            }
        }
        val flat = groups.values.flatten()
        LOG.info("[m2] trace $namespace#$statementId $trace -> criteria=${flat.size} groups=${groups.size}")
        if (flat.isEmpty()) return null
        return CachedResolution(
            elements = mapOf(
                "oredCriteria" to groups.values.map { MbgCriterionMock.criteriaOf(it) },
                "criteria.criteria" to flat.map { it.toMockObject() },
            ),
            stamps = stamps,
        )
    }

    /**
     * mapper 方法在本项目里的**首个可用调用点**。
     *
     * T-PreviewOpenPerfM2 M5 的 `findFirst()` 在真机大工程上返回 null（同一方法 `Find Usages`
     * 有 253 处引用、索引健康）——`Query.findFirst()` 在这条检索链上不可靠。改为 `forEach`
     * 取首个命中（语义等价、仍是提前终止，不把全项目引用收成 List）。
     *
     * D6（真机 2026-11-12 14:46，`mybatis-plus-demo` 的 `CtCustmanagerRelMapper#selectByExample`）：
     * **命中不等于能当调用点用**。`ReferencesSearch` 对接口方法会把「实现的声明处 / 方法引用」
     * 一起返回，这类 ref 的 element 上溯没有 `PsiMethodCallExpression`——取第一个 ref 就直接放弃，
     * trace 里就是 `noCallSite`，而项目里明明有两个真实调用点。现在改为跳过不可用的 ref 继续找。
     */
    private fun findCallSite(
        scope: GlobalSearchScope,
        method: PsiMethod,
        deadlineAt: Long = Long.MAX_VALUE,
    ): PsiMethodCallExpression? {
        var site: PsiMethodCallExpression? = null
        var refsSeen = 0
        var deadlineHit = false
        ReferencesSearch.search(method, scope).forEach { candidate ->
            refsSeen++
            val call = PsiTreeUtil.getParentOfType(candidate.element, PsiMethodCallExpression::class.java)
            when {
                // 逐条引用自查截止时刻：这是整条 M2 里最贵的一段（全项目/模块索引检索），
                // 大工程上没有这个检查就只能等它自己跑完（真机 30s 档）。到点即停，按「解析不到」降级。
                isDeadlineHit(deadlineAt) -> { deadlineHit = true; false }
                call == null -> true // 声明处 / 方法引用：继续找下一个
                else -> { site = call; false }
            }
        }
        if (site == null) {
            // 原实现此处再跑一次 `ReferencesSearch.search(...).findAll()` 只为数 totalRefs——
            // 恰是「没有调用点」的高频失败路径，等于把最贵的一次检索翻倍；refsSeen 已是扫描条数
            //（提前终止时 site 非空，不走本分支），故直接用它，不再二次检索。
            LOG.info("[m2] findCallSite empty method=$method refsSeen=$refsSeen deadlineHit=$deadlineHit")
        }
        return site
    }

    /** 调用实参的变量名（`selectByExample(example)` → `example`）。 */
    private fun exampleVariableName(call: PsiMethodCallExpression): String? =
        (call.argumentList.expressions.firstOrNull() as? PsiReferenceExpression)?.referenceName

    /** 方法体内所有以 example 为根的 `and*` / `or*` 调用。 */
    private fun collectCriteria(
        project: Project,
        body: PsiCodeBlock,
        exampleName: String,
        trace: StringBuilder? = null,
    ): LinkedHashMap<Any, MutableList<MbgCriterionMock>> {
        // D7：键＝这条条件所属的 Criteria 节点（`createCriteria()` / `or()` 调用，或别名变量的初始化表达式）；
        // 同一 Criteria 的多条条件落在同一组，不同 Criteria 之间由 `Example_Where_Clause` 的 `or` 连接。
        val groups = LinkedHashMap<Any, MutableList<MbgCriterionMock>>()
        var matched = 0
        var noResolve = 0
        var notExample = 0
        var notRooted = 0
        var noCondition = 0
        var total = 0
        PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java).forEach { call ->
            if (total >= MAX_CRITERIA) return@forEach
            val name = call.methodExpression.referenceName ?: return@forEach
            if (!AND_OR_METHOD.matches(name)) return@forEach
            matched++
            val method = call.resolveMethod()
            if (method == null) {
                noResolve++
                return@forEach
            }
            if (!isExampleCriteria(method)) {
                notExample++
                return@forEach
            }
            if (!rootedAtExample(call.methodExpression.qualifierExpression, exampleName)) {
                notRooted++
                return@forEach
            }
            val condition = addCriterionCondition(project, method)
            if (condition == null) {
                noCondition++
                return@forEach
            }
            val groupKey = criteriaGroupKey(call, exampleName) ?: exampleName
            groups.getOrPut(groupKey) { mutableListOf() } += MbgCriterionMock(
                condition = condition,
                values = call.argumentList.expressions.map { it.text },
                kind = MbgCriterionMock.kindOf(name),
            )
            total++
        }
        trace?.append("(and/or=$matched noResolve=$noResolve notExample=$notExample notRooted=$notRooted noCond=$noCondition) ")
        return groups
    }

    /**
     * D7：一条 `and*` / `or*` 条件归属哪条 Criteria。
     *
     * 链上最靠近根的 `createCriteria()` / `or()` 调用就是分界线——`example.createCriteria().andA().andB()` 的
     * 两条都归同一节点，`example.or().andC()` 归另一个节点。分步别名（`Criteria c = example.createCriteria();
     * c.andA()`）用变量的初始化表达式当键。认不出时返回 null，调用方按「同一个 example」兜底成一组。
     */
    private fun criteriaGroupKey(call: PsiMethodCallExpression, exampleName: String): Any? {
        var cur: PsiExpression? = call.methodExpression.qualifierExpression
        while (cur is PsiMethodCallExpression) {
            when (cur.methodExpression.referenceName) {
                "createCriteria", "or" -> return cur
            }
            cur = cur.methodExpression.qualifierExpression
        }
        val ref = cur as? PsiReferenceExpression ?: return null
        if (ref.referenceName == exampleName) return null
        val variable = runCatching { ref.resolve() as? PsiVariable }.getOrNull() ?: return null
        return runCatching { variable.initializer }.getOrNull()
    }

    /** 方法是否属于 MBG 的 `XxxExample`（或其内部类 `Criteria`）。 */
    private fun isExampleCriteria(method: PsiMethod): Boolean {
        val owner = method.containingClass ?: return false
        return owner.name?.endsWith("Example") == true ||
            owner.containingClass?.name?.endsWith("Example") == true
    }

    /**
     * 调用链的根是否指向**本次** selectByExample 实参那个 example 变量。
     *
     * 覆盖两种写法：链式 `example.createCriteria().andXxx(v)`（qualifier 最左端是 example 引用）
     * 与分步 `Criteria c = example.createCriteria(); c.andXxx(v)`（根的**初始化表达式**追溯到 example）。
     *
     * 为什么不能按类型名收（E2E 验收缺陷 D4）：同一方法体里常有多个 Example 实例
     * （MBG 生成的服务实现就是 `exampleNA` / `exampleNB` 各一条链），按「变量类型以 Example 结尾」
     * 收会把**另一次查询**的条件也并进来——真机产物变成
     * `WHERE (status = ? AND cust_name LIKE ? AND tenant_id = ? AND create_time BETWEEN ? AND tenant_id = ?)`，
     * 两个 Example 的 AND 条件被拼成一条永不成立的谓词。所以只认**同一对象**：变量名相同，或该变量的
     * 初始化表达式追溯后仍落在同一个 example 变量上。
     */
    internal fun rootedAtExample(expr: PsiExpression?, exampleName: String): Boolean {
        val ref = rootReference(expr) ?: return false
        if (ref.referenceName == exampleName) return true
        val variable = runCatching { ref.resolve() as? PsiVariable }.getOrNull() ?: return false
        val initializer = runCatching { variable.initializer }.getOrNull() ?: return false
        return rootReference(initializer)?.referenceName == exampleName
    }

    /** 把 `a.b().c()` 这类链剥到最左端的引用表达式（`a`）。 */
    private fun rootReference(expr: PsiExpression?): PsiReferenceExpression? {
        var cur = expr
        while (cur is PsiMethodCallExpression) cur = cur.methodExpression.qualifierExpression
        return cur as? PsiReferenceExpression
    }

    /** `andXxxYyy` 方法体里 `addCriterion("<condition>", ...)` 的第一个实参常量。 */
    private fun addCriterionCondition(project: Project, method: PsiMethod): String? {
        val body = method.body ?: return null
        val call = PsiTreeUtil.findChildOfType(body, PsiMethodCallExpression::class.java) ?: return null
        if (call.methodExpression.referenceName != "addCriterion") return null
        val arg = call.argumentList.expressions.firstOrNull() ?: return null
        return JavaPsiFacade.getInstance(project)
            .constantEvaluationHelper.computeConstantExpression(arg) as? String
    }
}
