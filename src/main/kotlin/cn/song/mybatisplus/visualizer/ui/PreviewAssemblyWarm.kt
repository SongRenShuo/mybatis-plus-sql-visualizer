package cn.song.mybatisplus.visualizer.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.xml.XmlTag
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicInteger

/**
 * T-PreviewAssemblyWarm：文件打开时**后台预热一次预览装配**，只算不建 UI，结果丢弃。
 *
 * ## 为什么需要（读数，非推测）
 *
 * 沙盒 `P8` 埋点（2026-11-13 21:44，cust-big-demo）：本会话**第一次**预览 `request→dialog 351ms +
 * construct 256ms + show 88ms ≈ 695ms`，而之后每一次只有 `55~75ms`。其中 `construct` 那 256ms（热态
 * 15~20ms）是编辑器/控制台/Laf 的首次初始化——属面板面，本类**不碰**；`request→dialog` 那 351ms 里
 * 除装配（热态 47~62ms）之外主要是**首次类加载与 JIT**，这部分可以提前在用户点击之前消化掉。
 *
 * ## 边界（与计划 W1-b 一致，越线即错）
 *
 * - **不建 UI**：不创建 Editor、不建面板、不碰 Disposable 树——只调 [XmlPreviewService.preview] 这种
 *   纯装配入口，返回的 `PreviewResult` 直接丢弃（它只写既有缓存：实体缓存 / 候选缓存 / 解析器缓存）。
 * - **不写执行文件**：`mpsql-exec.sql` 的写盘属于 `SqlPreviewService.previewAsync` 的预热分支，本类不调用。
 * - **不触发同步检索**：`preview` 内部对 M2 一律 deferred（首开骨架语义不变）。
 * - **dumb 态跳过**：索引期预热只会跟着索引一起等，跳过等价于现状。
 * - **每文件一次 + 每项目 【WARM_FILES_PER_PROJECT】 个文件**：`coalesceBy` 单飞 + 文件级去重；
 *   失败/取消会把名额与去重标记放回去，等下次渲染重试。
 *
 * ## 开关与回退
 *
 * Registry `sqlvis.preview.assemblyWarm`（默认开，plugin.xml `<registryKey>` 登记）；关掉即完全回到
 * 「点击时才装配」的旧行为，无任何残留。
 *
 * 线程口径照 S1 预取链 / `warmCandidates`：`ReadAction.nonBlocking{}.inSmartMode(project).expireWith(project)
 * .coalesceBy(...)`——等索引不持读锁、可撤销、随工程关闭撤掉。
 */
internal object PreviewAssemblyWarm {

    /** Registry 总开关（plugin.xml `<registryKey>`）；关闭＝整段预热早退。 */
    internal const val REGISTRY_KEY = "sqlvis.preview.assemblyWarm"

    private val LOG = Logger.getInstance(PreviewAssemblyWarm::class.java)

    /**
     * 每个项目**每会话**允许预热的文件数上限。
     *
     * 真机教训（2026-11-13 22:20 用户沙盒日志）：一个 mapper 文件有 **11 条语句**，原实现按
     * `文件#语句` 去重 ⇒ 一次文件渲染预热了 11 次（日志 `assembly warm done in 9.8~10.9s` × 11，
     * 那 10 秒几乎全在等 smart mode，但落地时 11 份装配挤在同一刻跑）。预热只为「首次点击」服务，
     * 一个文件一次就够，且整会话不该无限制地铺开。
     */
    internal const val WARM_FILES_PER_PROJECT = 2

    /** 已预热过的文件（按 VirtualFile.url，每文件一次；取消/失败会移除以便下次重试）。 */
    private val warmedFiles: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 每项目已用掉的预热额度（会话级）。 */
    private val usedPerProject: MutableMap<String, AtomicInteger> = java.util.concurrent.ConcurrentHashMap()

    /** 实际跑完的预热次数（守卫读数；不代表用户可见行为）。 */
    internal val runCount = AtomicInteger()

    /** 测试缝：Registry 开关（生产＝`Registry.is(REGISTRY_KEY, true)`）。 */
    internal var enabledProvider: () -> Boolean = {
        runCatching { Registry.`is`(REGISTRY_KEY, true) }.getOrDefault(true)
    }

    /** 测试缝：单测走同步执行（真实环境走应用线程池）；null＝按环境判定。 */
    internal var syncForTest: Boolean? = null

    internal fun resetForTest() {
        warmedFiles.clear()
        usedPerProject.clear()
        runCount.set(0)
        enabledProvider = { runCatching { Registry.`is`(REGISTRY_KEY, true) }.getOrDefault(true) }
        syncForTest = null
    }

    /**
     * 预热一次（幂等）。由 gutter 的文件打开预热信号调用——与 ctx 预热同一个信号，便于统一预算与去重。
     *
     * @param tag 光标附近（或首个）语句标签；`id` 取不到就不预热（无 id 无法定位装配对象）
     */
    internal fun warmOnce(tag: XmlTag) {
        if (!enabledProvider()) return
        val project = tag.project
        if (project.isDisposed) return
        val file = tag.containingFile ?: return
        val statementId = runCatching { tag.getAttributeValue("id") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val fileKey = file.virtualFile?.url ?: file.name
        val key = "$fileKey#$statementId"
        // ① 每文件一次（不是每语句——一个 mapper 常有十几条语句，见 WARM_FILES_PER_PROJECT 的实测教训）
        if (!warmedFiles.add(fileKey)) return
        // ② 每项目每会话的额度（预热只服务「首次点击」，不该无限铺开）
        val used = usedPerProject.computeIfAbsent(project.locationHash) { AtomicInteger() }
        if (used.incrementAndGet() > WARM_FILES_PER_PROJECT) {
            warmedFiles.remove(fileKey)
            return
        }
        if (runCatching { DumbService.isDumb(project) }.getOrDefault(false)) {
            warmedFiles.remove(fileKey) // 索引期不预热，等下一次渲染再试
            used.decrementAndGet()
            return
        }
        val started = System.currentTimeMillis()
        val task = { runWarm(project, tag, statementId, started, key) }
        if (syncForTest ?: ApplicationManager.getApplication().isUnitTestMode) {
            task()
            return
        }
        runCatching {
            ReadAction.nonBlocking<Unit> { task() }
                .inSmartMode(project)
                .expireWith(project)
                .coalesceBy(this, "assemblyWarm:$key")
                .submit(AppExecutorUtil.getAppExecutorService())
                .onError { t ->
                    warmedFiles.remove(fileKey)
                    used.decrementAndGet()
                    LOG.info("[open-perf] assembly warm 未完成：${t.javaClass.simpleName}（下次渲染重试）")
                }
        }.onFailure {
            warmedFiles.remove(fileKey)
            used.decrementAndGet()
        }
    }

    /** 装配本体：**只算不建 UI**，产物丢弃（只留缓存）。异常不外抛（预热失败等价于没预热）。 */
    private fun runWarm(project: Project, tag: XmlTag, statementId: String, startedMs: Long, key: String) {
        try {
            val file = tag.containingFile as? com.intellij.psi.xml.XmlFile ?: return
            // 实体/表上下文：与点击路径同一来源（includeCandidates=false，不在预热里解析联表候选）
            val src = XmlSqlCompletionContextResolver.resolveXml(project, tag, includeCandidates = false)
            val entity = src.main?.let { m ->
                cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext(
                    m.tableName, m.columns, m.tableAlias, m.columnComments, m.tableComment, src.candidates,
                )
            }
            // 装配（纯数据）：结果直接丢弃——目的是把类加载/解析/JIT 提前做掉，缓存随解析器自身留在项目里
            XmlPreviewService.preview(
                file.text,
                statementId,
                project,
                entity,
                sourceContext = null,
            )
            runCount.incrementAndGet()
            LOG.info("[open-perf] assembly warm done in ${System.currentTimeMillis() - startedMs}ms key=$key")
        } catch (t: Throwable) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) throw t
            warmedFiles.remove(key.substringBefore('#'))
            LOG.info("[open-perf] assembly warm fail ${t.javaClass.simpleName}: ${t.message}（等价于没预热）")
        }
    }
}
