package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.log.MapperNamespaceIndex
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicInteger

/** 逐文件代戳合成用的质数乘子（把 path 哈希与 modificationStamp 混进同一项）。 */
private const val PART_FILE = 1_000_003L

/**
 * T-IndexScanSweep2 P1（2026-11-16）：补全候选「项目级名称清单」的**一次构建**服务。
 *
 * ## 为什么
 *
 * 改造前两处清单**每次补全**都重算，且成本主因是「对最多 500 个 XML 建 PSI」：
 *  - `XmlCompletionSymbols.crossFileSqlIds`（跨文件 `<sql id>` → `refid` 候选）走
 *    `FileTypeIndex(XML)` → 最多 500 文件逐个 `PsiManager.findFile` + `rootTag` + 遍历 `<sql>`；
 *  - `XmlCompletionSymbols.configPropertyKeys`（mybatis-config `<properties>` key）同形态。
 * 实测（P-1/P-2 探针，Light 沙箱，N=100/200/400）：同一份清单连续两次调用各 2.6~4.1ms，
 * **第二次与第一次同量级（无缓存）、耗时随 N 增长**——即每次补全全付一次。
 *
 * ## 做法
 *
 * 本服务维护两份**不可变快照**：`crossFileSqlIds`（`ns.sqlId`，无 namespace 时裸 id）与
 * `configPropertyKeys`（`<property name>` 的 name 属性值）。构建来源**只查自定义索引**
 * [MapperNamespaceIndex] 的两类文件级标记键（`#mapper` / `#config`），不再 `FileTypeIndex` 全量；
 * 建好前返回空（与既有 dumb 闸同语义），**不在 EDT 上建**。
 *
 * ## 失效口径（关键）
 *
 * 按**索引状态**失效，不是 `PsiModificationTracker`（后者击键即失效＝等于没有）：
 * 代戳 = `MapperNamespaceIndex.getIndexStamp(project)`（索引内容代）**⊕** 来源文件集合的
 * `VirtualFile.modificationStamp`（路径 + 逐文件代戳）。
 *
 * 两条代戳**缺一不可**（2026-11-16 探针实测）：
 *  - 索引代戳只随「索引**产出**变化」递增——新增/删除 XML 文件 = 变化；
 *  - 但「改已存在 mapper 的 `<sql id>`」（namespace/根标签没变 ⇒ 索引键不变）索引代戳**恒不变**
 *    （实测 `before=401 after=401`）→ 只能靠该文件的 `VirtualFile.modificationStamp`（实测
 *    `14 → 2112`）捕捉。故两者合用，才能满足「改 `<sql id>`/properties 后立即生效」。
 *
 * 边界（如实登记）：`VirtualFile.modificationStamp` 只随**落盘**变化，故**别的文件**里未保存的
 * 编辑要等保存才进清单（当前文件自身的 `<sql id>` 不走本清单——`refid` 同文件片段由
 * `localIds` 走活 PSI 补齐，不受影响）。
 *
 * ## 回退通道
 *
 * Registry `sqlvis.completion.nameIndexCache`（默认 true，plugin.xml `<registryKey>` 登记）→ false 时
 * `XmlCompletionSymbols` 走**现扫实现**（`legacyCrossFileSqlIds`/`legacyConfigPropertyKeys`），
 * 供真机 A/B；下一版删除（承 T-IbatisGutterPerf P5 口径：不承诺长期保留）。
 *
 * ## 线程与红线
 *
 * 懒建（首次调用触发）；生产走 `ReadAction.nonBlocking{}.inSmartMode(project).expireWith(project)`，
 * 等索引不持读锁、可撤销、随工程回收；单测/unit-test 模式同步构建以保证确定性。
 * **不引** `PsiTreeChangeListener`/`EditorFactoryListener`（仓内红线族维持 0）。
 */
@Service(Service.Level.PROJECT)
internal class XmlSymbolNameIndexCache(private val project: Project) {

    private val LOG = Logger.getInstance(XmlSymbolNameIndexCache::class.java)

    @Volatile
    private var sqlIdsSnapshot: List<String> = emptyList()

    @Volatile
    private var propsSnapshot: List<String> = emptyList()

    /** 已构建快照对应的代戳；`Long.MIN_VALUE` = 从未构建。 */
    @Volatile
    private var builtSignature: Long = Long.MIN_VALUE

    /** 已提交异步构建的代戳（同代戳只提交一次）。 */
    @Volatile
    private var pendingSignature: Long = Long.MIN_VALUE

    private val buildLock = Any()

    /** 真实构建次数（守卫读数；不代表用户可见行为）。 */
    internal val buildCount = AtomicInteger(0)

    /** 测试缝：强制同步构建（生产＝false，走后台线程池）。 */
    internal var syncForTest: Boolean = false

    /** 测试缝：Registry 开关（生产＝`Registry.is(REGISTRY_KEY, true)`）。 */
    internal var enabledProvider: () -> Boolean = {
        runCatching { Registry.`is`(REGISTRY_KEY, true) }.getOrDefault(true)
    }

    /**
     * 测试缝：清空全部状态（快照/代戳/构建计数）并复位两个开关。
     *
     * 需要它是因为单测的 project 在**同一测试类内可能是共享的**（`BasePlatformTestCase` 的 project
     * 复用），而本服务挂在 project 上 → 计数与开关会跨用例污染（2026-11-16 实测：某用例把
     * `enabledProvider` 置 false 后，后续用例静默走回退链、`buildCount` 恒为历史累计值）。
     */
    internal fun resetForTest() {
        synchronized(buildLock) {
            sqlIdsSnapshot = emptyList()
            propsSnapshot = emptyList()
            builtSignature = Long.MIN_VALUE
            pendingSignature = Long.MIN_VALUE
            buildCount.set(0)
        }
        syncForTest = false
        enabledProvider = { runCatching { Registry.`is`(REGISTRY_KEY, true) }.getOrDefault(true) }
    }

    fun isCacheEnabled(): Boolean = enabledProvider()

    /** 跨文件 `<sql id>` 候选（`ns.sqlId` / 无 ns 时裸 id）。建好前/ dumb 态 → 空。 */
    fun crossFileSqlIds(): List<String> {
        ensureBuilt()
        return sqlIdsSnapshot
    }

    /** mybatis-config `<properties>` key 清单。建好前/ dumb 态 → 空。 */
    fun configPropertyKeys(): List<String> {
        ensureBuilt()
        return propsSnapshot
    }

    // ------------------------------------------------------------------
    // 失效与构建
    // ------------------------------------------------------------------

    private fun ensureBuilt() {
        if (!enabledProvider()) return
        if (runCatching { DumbService.isDumb(project) }.getOrDefault(false)) return
        val sig = signature() ?: return
        if (sig == builtSignature) return
        if (syncForTest || ApplicationManager.getApplication().isUnitTestMode) {
            synchronized(buildLock) {
                if (sig == builtSignature) return
                doBuild(sig)
            }
            return
        }
        if (pendingSignature == sig) return
        scheduleBuild(sig)
    }

    /** 来源文件集合 + 索引代的合成代戳（任一失败 → null，本次不重建）。 */
    private fun signature(): Long? = runCatching {
        // 逐文件代戳按 XOR 合成：**与文件枚举顺序无关**（`getContainingFiles` 的迭代顺序不承诺稳定，
        // 用顺序相关的滚动哈希会在顺序变化时误判「变了」→ 每次补全重建；XOR 消除这类假失效）。
        var h = 0L
        for (vf in MapperNamespaceIndex.getMapperFiles(project)) {
            h = h xor (vf.path.hashCode() * PART_FILE + vf.modificationStamp)
        }
        for (vf in MapperNamespaceIndex.getConfigFiles(project)) {
            h = h xor (vf.path.hashCode() * PART_FILE + vf.modificationStamp)
        }
        h * 31 + MapperNamespaceIndex.getIndexStamp(project)
    }.getOrNull()

    private fun scheduleBuild(sig: Long) {
        pendingSignature = sig
        runCatching {
            ReadAction.nonBlocking<Unit> { doBuild(sig) }
                .inSmartMode(project)
                .expireWith(project)
                .coalesceBy(this, "nameIndex:$sig")
                .submit(AppExecutorUtil.getAppExecutorService())
                .onError { t ->
                    if (pendingSignature == sig) pendingSignature = Long.MIN_VALUE
                    LOG.info("[completion-perf] name index build 未完成：${t.javaClass.simpleName}（下次补全重试）")
                }
        }.onFailure {
            if (pendingSignature == sig) pendingSignature = Long.MIN_VALUE
        }
    }

    /** 构建本体（须在读动作内）：只查索引取文件，再按文件建 PSI 抽名称。异常不外抛（保留旧快照）。 */
    private fun doBuild(sig: Long) {
        val started = System.currentTimeMillis()
        val built = runCatching { ReadAction.compute<Built, RuntimeException> { buildSnapshot() } }.getOrNull()
            ?: return
        sqlIdsSnapshot = built.sqlIds
        propsSnapshot = built.props
        builtSignature = sig
        buildCount.incrementAndGet()
        if (pendingSignature == sig) pendingSignature = Long.MIN_VALUE
        LOG.info(
            "[completion-perf] name index built: sqlIds=${built.sqlIds.size} props=${built.props.size} " +
                "files=${built.files} ms=${System.currentTimeMillis() - started}",
        )
    }

    private fun buildSnapshot(): Built {
        val psiManager = PsiManager.getInstance(project)
        val mapperFiles = MapperNamespaceIndex.getMapperFiles(project)
        val configFiles = MapperNamespaceIndex.getConfigFiles(project)

        val sqlIds = LinkedHashSet<String>()
        for (vf in mapperFiles) {
            val file = psiManager.findFile(vf) as? XmlFile ?: continue
            val root = file.rootTag ?: continue
            if (root.name != "mapper") continue
            val ns = root.getAttributeValue("namespace")?.takeIf { it.isNotBlank() }
            for (sql in root.findSubTags("sql")) {
                val id = sql.getAttributeValue("id")?.takeIf { it.isNotBlank() } ?: continue
                sqlIds.add(if (ns != null) "$ns.$id" else id)
            }
        }

        val props = LinkedHashSet<String>()
        for (vf in configFiles) {
            val file = psiManager.findFile(vf) as? XmlFile ?: continue
            props.addAll(XmlCompletionSymbols.propertyKeysOf(file))
        }

        return Built(sqlIds.toList(), props.toList(), mapperFiles.size + configFiles.size)
    }

    private data class Built(val sqlIds: List<String>, val props: List<String>, val files: Int)

    companion object {
        /** Registry 回退开关（plugin.xml `<registryKey>`）；false → `XmlCompletionSymbols` 走现扫实现。 */
        internal const val REGISTRY_KEY = "sqlvis.completion.nameIndexCache"

        fun getInstance(project: Project): XmlSymbolNameIndexCache =
            project.getService(XmlSymbolNameIndexCache::class.java)
    }
}
