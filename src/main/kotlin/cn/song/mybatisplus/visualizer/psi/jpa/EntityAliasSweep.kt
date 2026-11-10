package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.psi.EntityClassResolver
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.ConcurrentHashMap

/**
 * 实体别名反查的**有界扫描**（T-GutterDupFreeze S3，20261008 用户真机冻结事故）。
 *
 * 事故形态：`userMapper.selectList(<wrapper 链>)` 被误认成自定义 DAO HQL 后，预览链拿链源码里的
 * `from t_order` 去反查实体类——不存在的名字必然扫完整个项目（每个类短名一次 StubIndex 查询，
 * 索引未就绪期每次都是随机磁盘读），实测 70 秒；该扫描跑在 `ReadAction.compute`（不可取消）里，
 * 读锁全程不放 → EDT 等读许可被冻结、平台 `SuvorovProgress` 覆盖层接管事件（空白窗关不掉）。
 *
 * 本对象把该扫描收口为「有上限 + 可取消 + 命中即止」，并把判定逻辑与 PSI 取数解耦，便于用纯用例
 * 钉住「超限即停」这条判别行为（守卫类改动须可反向验证）。
 *
 * 两条入口（券 0 T-Ibatis2SlowFix 起）：[resolve] ＝完整反查（含扫描，供非同步关键路径的调用方）；
 * [resolveCachedOnly] ＝**零扫描**版本（直查 + 只读缓存），同步预览热路径专用——XML 内嵌 SQL 的
 * 根标识符多为物理表名，扫描必然打空却要先付整段预算（20261008 冻结事故同形态）。
 *
 * 上限口径：正常工程的类短名数远小于上限，只有病态/超大工程或误判输入才会触顶；触顶按**未命中**
 * 降级并回调记账。**触顶不是「确认不存在」**——调用方据此**不写负缓存**（见
 * [cn.song.mybatisplus.visualizer.ui.SqlPreviewService] 的调用点），避免大工程里的合法别名被
 * 一次性截断后永久记成未命中。
 */
internal object EntityAliasSweep {

    /**
     * 预览与转换器共用的实体名入口。普通类名已有候选（包括歧义）就不扫描别名；
     * 仅旧慢路径执行有界完整扫描，不能在首个别名命中处停止并伪装成唯一命中。
     * 索引未就绪不写负缓存；**扫描触顶也落缓存**（见下），取消异常自然向上传播。
     */
    fun resolve(project: Project, name: String, context: PsiElement? = null): EntityClassResolver.Resolution {
        if (name.isBlank() || DumbService.isDumb(project)) return unresolved()
        return try {
            val direct = EntityClassResolver.resolveByName(project, name, context)
            if (direct.psiClass != null || direct.candidates.isNotEmpty()) return direct
            val scope = EntityClassResolver.searchScope(project, context)
            val stamp = PsiModificationTracker.getInstance(project).modificationCount
            val roots = ProjectRootModificationTracker.getInstance(project).modificationCount
            val existing = project.getUserData(CACHE_KEY)
            val cache = if (existing != null && existing.psiStamp == stamp && existing.rootStamp == roots) existing
            else LookupCache(stamp, roots).also { project.putUserData(CACHE_KEY, it) }
            val key = LookupKey(name, scope)
            val cached = cache.entries[key]
            if (cached != null) {
                val classes = cached.mapNotNull { it.element }
                if (classes.size == cached.size) {
                    return EntityClassResolver.resolveWithAliasCandidates(project, name, context, classes)
                }
                cache.entries.remove(key)
            }
            val namesCache = PsiShortNamesCache.getInstance(project)
            scanCount.incrementAndGet()
            val scan = findAliasCandidates(name, namesCache.allClassNames.asSequence(), scanLimitForTest ?: SCAN_LIMIT) {
                namesCache.getClassesByName(it, scope)
            }
            // 触顶（未扫完）**同样落缓存**（空候选）：
            // 20261016 实测——真实工程的短名空间（含 JDK）恒大于 [SCAN_LIMIT]，故触顶是常态而非例外；
            // 触顶后按未解析返回且不缓存，等于**同一个名字每次预览都重扫整整一段预算**（单次约 1s，而该扫描
            // 跑在预览的阻塞读动作里，即持读锁 1s）。缓存空候选**不改变结果**（触顶本来就恒为「未解析」），
            // 只去掉重复代价；缓存随 PSI/根版本失效，工程变小后仍会重扫。直接命中在缓存查询之前就已返回，
            // 故空缓存不会遮蔽真实实体（见本函数开头两条 return）。
            if (cache.entries.size >= CACHE_LIMIT) cache.entries.clear()
            cache.entries[key] = scan.candidates.map { SmartPointerManager.getInstance(project).createSmartPsiElementPointer(it) }
            if (!scan.complete) return unresolved()
            EntityClassResolver.resolveWithAliasCandidates(project, name, context, scan.candidates)
        } catch (_: IndexNotReadyException) {
            unresolved()
        }
    }

    /**
     * 券 0（T-Ibatis2SlowFix）：**零扫描**的别名反查——判据与 [resolve] 相同，但不启动扫描。
     *
     * 同步预览热路径（XML 链的源语言实体探针）专用：直查命中即返回；直查未命中时**只读**
     * [LookupCache]（由 [resolve] 或后台预热写入），缓存缺失 / 版本失效 / 指针失效一律按未解析返回。
     *
     * 为什么必须把扫描移出这条路径：XML 内嵌 SQL 的根标识符多是**物理表名**（`t_order`），
     * 别名反查打不到任何 `@Entity(name=...)`，必然扫完整段预算——这正是 20261008 真机冻结事故的
     * 形态（同族输入 `from t_order` 实测 70 秒；现行上限下单次约 1s，且弹窗要等它回来才出）。
     * **结论不变**：`resolve` 触顶（未扫完）本来就按未解析返回，本方法与之逐值等价，只是不付代价。
     *
     * 完整反查仍在 [resolve]（转换链 / 实体作用域等非同步关键路径的调用方使用）；缓存被预热后
     * （例如后续接上延迟反查）本方法即能命中，故这里保留缓存直读而不写死「永不反查」。
     */
    fun resolveCachedOnly(project: Project, name: String, context: PsiElement? = null): EntityClassResolver.Resolution {
        if (name.isBlank() || DumbService.isDumb(project)) return unresolved()
        return try {
            val direct = EntityClassResolver.resolveByName(project, name, context)
            if (direct.psiClass != null || direct.candidates.isNotEmpty()) return direct
            val stamp = PsiModificationTracker.getInstance(project).modificationCount
            val roots = ProjectRootModificationTracker.getInstance(project).modificationCount
            val existing = project.getUserData(CACHE_KEY) ?: return unresolved()
            if (existing.psiStamp != stamp || existing.rootStamp != roots) return unresolved()
            val cached = existing.entries[LookupKey(name, EntityClassResolver.searchScope(project, context))]
                ?: return unresolved()
            // 空候选＝上一趟触顶的结论（触顶本就按未解析），等价于直查未命中后的默认结果
            if (cached.isEmpty()) return unresolved()
            val classes = cached.mapNotNull { it.element }
            // 指针失效（PSI 改过）＝缓存不可信：按未解析返回；清理留给 [resolve]，本方法保持只读
            if (classes.size != cached.size) return unresolved()
            EntityClassResolver.resolveWithAliasCandidates(project, name, context, classes)
        } catch (_: IndexNotReadyException) {
            unresolved()
        }
    }

    internal data class AliasCandidates(val candidates: List<PsiClass>, val complete: Boolean)

    internal fun findAliasCandidates(
        aliasName: String,
        names: Sequence<String>,
        limit: Int = SCAN_LIMIT,
        lookup: (String) -> Array<PsiClass>,
    ): AliasCandidates {
        val candidates = LinkedHashSet<PsiClass>()
        var scanned = 0
        for (shortName in names) {
            if (scanned >= limit) return AliasCandidates(emptyList(), false)
            if (scanned % CANCEL_CHECK_INTERVAL == 0) ProgressManager.checkCanceled()
            scanned++
            for (clazz in lookup(shortName)) {
                if (clazz.isInterface || clazz.isEnum || clazz.isAnnotationType) continue
                if (JpaMetadataReader.entityNameOf(clazz) == aliasName) candidates.add(clazz)
            }
        }
        return AliasCandidates(candidates.toList(), true)
    }

    private fun unresolved() = EntityClassResolver.Resolution(null, emptyList())
    private data class LookupKey(val name: String, val scope: GlobalSearchScope)
    private class LookupCache(val psiStamp: Long, val rootStamp: Long) {
        val entries = ConcurrentHashMap<LookupKey, List<SmartPsiElementPointer<PsiClass>>>()
    }
    private const val CACHE_LIMIT = 4096
    private val CACHE_KEY = Key.create<LookupCache>("mp-visualizer.scopedEntityAliases")

    /**
     * 单次反查允许遍历的类短名数上限。
     *
     * 取 5000：常规工程（含多模块 demo）的全量短名在千级，功能上等于不设限；真正起作用的是
     * 病态/超大工程下把「冷索引逐名随机读」的最坏代价钉住（配合取消检查，平台需要写锁时可中断）。
     */
    internal const val SCAN_LIMIT: Int = 5000

    /** 已执行的扫描次数（测试访问器：验「触顶结果落缓存、不重复扫」）。 */
    private val scanCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 测试缝：扫描上限替身（null = [SCAN_LIMIT]）——置 0 可确定性地制造「触顶」。 */
    internal var scanLimitForTest: Int? = null

    internal fun scanCountForTest(): Int = scanCount.get()

    internal fun resetScanCountForTest() {
        scanCount.set(0)
    }

    /** 取消检查间隔（每 N 个短名查一次）：`checkCanceled` 自身有开销，不必逐个查。 */
    private const val CANCEL_CHECK_INTERVAL: Int = 64

    /**
     * 扫描 [names]（类短名序列），返回首个实体别名等于 [aliasName]（忽略大小写）的实体类。
     *
     * @param aliasName   目标实体别名（`@Entity(name = ...)` 的 name）
     * @param names       待遍历的类短名（生产侧＝`PsiShortNamesCache.getAllClassNames()`）
     * @param lookup      按短名取候选类（生产侧＝`PsiShortNamesCache.getClassesByName(shortName, scope)`，
     *                    平台签名返回 `PsiClass[]`）
     * @param limit       遍历上限，默认 [SCAN_LIMIT]
     * @param onTruncated 触顶回调（入参＝已遍历短名数），生产侧记账用
     * @return 命中的实体类；未命中或触顶（降级为未命中）时 null
     */
    fun findAliasOwner(
        aliasName: String,
        names: Sequence<String>,
        lookup: (String) -> Array<PsiClass>,
        limit: Int = SCAN_LIMIT,
        onTruncated: (Int) -> Unit = {},
    ): PsiClass? {
        var scanned = 0
        for (shortName in names) {
            if (scanned >= limit) {
                onTruncated(scanned)
                return null
            }
            if (scanned % CANCEL_CHECK_INTERVAL == 0) ProgressManager.checkCanceled()
            scanned++
            for (clazz in lookup(shortName)) {
                // 与旧实现同口径：接口/枚举/注解类型不可能是 @Entity 实体
                if (clazz.isInterface || clazz.isEnum || clazz.isAnnotationType) continue
                val alias = JpaMetadataReader.entityNameOf(clazz) ?: continue
                if (alias.equals(aliasName, ignoreCase = true)) return clazz
            }
        }
        return null
    }
}
