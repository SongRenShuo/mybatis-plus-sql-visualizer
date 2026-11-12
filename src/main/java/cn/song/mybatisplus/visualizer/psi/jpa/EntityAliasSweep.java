package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiClass;
import kotlin.jvm.functions.Function1;
import kotlin.sequences.Sequence;

import java.util.Iterator;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import cn.song.mybatisplus.visualizer.psi.EntityClassResolver;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiModificationTracker;

/**
 * 实体别名反查的<b>有界扫描</b>（T-GutterDupFreeze S3，20261008 用户真机冻结事故）。
 *
 * <p>事故形态：{@code userMapper.selectList(<wrapper 链>)} 被误认成自定义 DAO HQL 后，预览链拿链源码里的
 * {@code from t_order} 去反查实体类——不存在的名字必然扫完整个项目（每个类短名一次 StubIndex 查询，
 * 索引未就绪期每次都是随机磁盘读），实测 70 秒；该扫描跑在 {@code ReadAction.compute}（不可取消）里，
 * 读锁全程不放 → EDT 等读许可被冻结、平台 {@code SuvorovProgress} 覆盖层接管事件（空白窗关不掉）。</p>
 *
 * <p>本对象把该扫描收口为「有上限 + 可取消 + 命中即止」，并把判定逻辑与 PSI 取数解耦，便于用纯用例
 * 钉住「超限即停」这条判别行为（守卫类改动须可反向验证）。</p>
 *
 * <p>两条入口（券 0 T-Ibatis2SlowFix 起）：{@link #resolve} ＝完整反查（含扫描，供非同步关键路径的调用方）；
 * {@link #resolveCachedOnly} ＝<b>零扫描</b>版本（直查 + 只读缓存），同步预览热路径专用——XML 内嵌 SQL 的
 * 根标识符多为物理表名，扫描必然打空却要先付整段预算（20261008 冻结事故同形态）。</p>
 *
 * <p>上限口径：正常工程的类短名数远小于上限，只有病态/超大工程或误判输入才会触顶；触顶按<b>未命中</b>
 * 降级并回调记账。<b>触顶不是「确认不存在」</b>——调用方据此<b>不写负缓存</b>（见
 * {@link cn.song.mybatisplus.visualizer.ui.SqlPreviewService} 的调用点），避免大工程里的合法别名被
 * 一次性截断后永久记成未命中。</p>
 */
public final class EntityAliasSweep {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final EntityAliasSweep INSTANCE = new EntityAliasSweep();

    private EntityAliasSweep() {
    }

    /**
     * 单次反查允许遍历的类短名数上限。
     *
     * <p>取 5000：常规工程（含多模块 demo）的全量短名在千级，功能上等于不设限；真正起作用的是
     * 病态/超大工程下把「冷索引逐名随机读」的最坏代价钉住（配合取消检查，平台需要写锁时可中断）。</p>
     */
    public static final int SCAN_LIMIT = 5000;

    /** 取消检查间隔（每 N 个短名查一次）：{@code checkCanceled} 自身有开销，不必逐个查。 */
    private static final int CANCEL_CHECK_INTERVAL = 64;

    /** 查询宿主作用域内解析；普通名有候选时短路，仅旧慢路径做有界完整扫描。 */
    public static EntityClassResolver.Resolution resolve(Project project, String name) {
        return resolve(project, name, null);
    }

    public static EntityClassResolver.Resolution resolve(Project project, String name, PsiElement context) {
        if (name.isBlank() || DumbService.isDumb(project)) return unresolved();
        try {
            var direct = EntityClassResolver.resolveByName(project, name, context);
            if (direct.psiClass() != null || !direct.candidates().isEmpty()) return direct;
            GlobalSearchScope scope = EntityClassResolver.searchScope(project, context);
            long stamp = PsiModificationTracker.getInstance(project).getModificationCount();
            long roots = ProjectRootModificationTracker.getInstance(project).getModificationCount();
            LookupCache cache = project.getUserData(CACHE_KEY);
            if (cache == null || cache.psiStamp != stamp || cache.rootStamp != roots) {
                cache = new LookupCache(stamp, roots);
                project.putUserData(CACHE_KEY, cache);
            }
            LookupKey key = new LookupKey(name, scope);
            var cached = cache.entries.get(key);
            if (cached != null) {
                List<PsiClass> classes = new ArrayList<>();
                for (var pointer : cached) {
                    PsiClass clazz = pointer.getElement();
                    if (clazz != null) classes.add(clazz);
                }
                if (classes.size() == cached.size()) return EntityClassResolver.resolveWithAliasCandidates(project, name, context, classes);
                cache.entries.remove(key);
            }
            PsiShortNamesCache namesCache = PsiShortNamesCache.getInstance(project);
            scanCount.incrementAndGet();
            AliasCandidates scan = findAliasCandidates(name, () -> Arrays.asList(namesCache.getAllClassNames()).iterator(),
                    scanLimitForTest != null ? scanLimitForTest : SCAN_LIMIT, shortName -> namesCache.getClassesByName(shortName, scope));
            // 触顶也缓存空候选，避免同名每次重复占用完整预算；PSI/根变化即失效。
            // 直接命中在缓存查询之前返回，空缓存不遮蔽真实实体。未完成扫描不得证明唯一性。
            if (cache.entries.size() >= CACHE_LIMIT) cache.entries.clear();
            List<SmartPsiElementPointer<PsiClass>> pointers = new ArrayList<>();
            for (PsiClass clazz : scan.candidates()) pointers.add(SmartPointerManager.getInstance(project).createSmartPsiElementPointer(clazz));
            cache.entries.put(key, pointers);
            if (!scan.complete()) return unresolved();
            return EntityClassResolver.resolveWithAliasCandidates(project, name, context, scan.candidates());
        } catch (IndexNotReadyException ignored) {
            return unresolved();
        }
    }

    /** @param candidates complete matching owners (empty when truncated)
     *  @param complete whether the full input was scanned */
    public record AliasCandidates(List<PsiClass> candidates, boolean complete) {}

    /**
     * 券 0（T-Ibatis2SlowFix）：<b>零扫描</b>的别名反查——判据与 {@link #resolve} 相同，但不启动扫描。
     *
     * <p>同步预览热路径（XML 链的源语言实体探针）专用：直查命中即返回；直查未命中时<b>只读</b>
     * {@link LookupCache}（由 {@link #resolve} 或后台预热写入），缓存缺失 / 版本失效 / 指针失效一律按未解析返回。</p>
     *
     * <p>为什么必须把扫描移出这条路径：XML 内嵌 SQL 的根标识符多是<b>物理表名</b>（{@code t_order}），
     * 别名反查打不到任何 {@code @Entity(name=...)}，必然扫完整段预算——这正是 20261008 真机冻结事故的
     * 形态（同族输入 {@code from t_order} 实测 70 秒；现行上限下单次约 1s，且弹窗要等它回来才出）。
     * <b>结论不变</b>：{@code resolve} 触顶（未扫完）本来就按未解析返回，本方法与之逐值等价，只是不付代价。</p>
     *
     * <p>完整反查仍在 {@link #resolve}（转换链 / 实体作用域等非同步关键路径的调用方使用）；缓存被预热后
     * （例如后续接上延迟反查）本方法即能命中，故这里保留缓存直读而不写死「永不反查」。</p>
     */
    public static EntityClassResolver.Resolution resolveCachedOnly(Project project, String name) {
        return resolveCachedOnly(project, name, null);
    }

    /** Kotlin 默认参 {@code context: PsiElement? = null} 的等价重载。 */
    public static EntityClassResolver.Resolution resolveCachedOnly(Project project, String name, PsiElement context) {
        if (name.isBlank() || DumbService.isDumb(project)) return unresolved();
        try {
            var direct = EntityClassResolver.resolveByName(project, name, context);
            if (direct.psiClass() != null || !direct.candidates().isEmpty()) return direct;
            long stamp = PsiModificationTracker.getInstance(project).getModificationCount();
            long roots = ProjectRootModificationTracker.getInstance(project).getModificationCount();
            LookupCache existing = project.getUserData(CACHE_KEY);
            if (existing == null) return unresolved();
            if (existing.psiStamp != stamp || existing.rootStamp != roots) return unresolved();
            List<SmartPsiElementPointer<PsiClass>> cached =
                    existing.entries.get(new LookupKey(name, EntityClassResolver.searchScope(project, context)));
            if (cached == null) return unresolved();
            // 空候选＝上一趟触顶的结论（触顶本就按未解析），等价于直查未命中后的默认结果
            if (cached.isEmpty()) return unresolved();
            List<PsiClass> classes = new ArrayList<>();
            for (var pointer : cached) {
                PsiClass clazz = pointer.getElement();
                if (clazz != null) classes.add(clazz);
            }
            // 指针失效（PSI 改过）＝缓存不可信：按未解析返回；清理留给 resolve，本方法保持只读
            if (classes.size() != cached.size()) return unresolved();
            return EntityClassResolver.resolveWithAliasCandidates(project, name, context, classes);
        } catch (IndexNotReadyException ignored) {
            return unresolved();
        }
    }

    public static AliasCandidates findAliasCandidates(String aliasName, Sequence<String> names,
                                                       Function1<String, PsiClass[]> lookup) {
        return findAliasCandidates(aliasName, names, SCAN_LIMIT, lookup);
    }

    public static AliasCandidates findAliasCandidates(String aliasName, Sequence<String> names, int limit,
                                                       Function1<String, PsiClass[]> lookup) {
        Set<PsiClass> candidates = new LinkedHashSet<>();
        int scanned = 0;
        Iterator<String> iterator = names.iterator();
        while (iterator.hasNext()) {
            String shortName = iterator.next();
            if (scanned >= limit) return new AliasCandidates(List.of(), false);
            if (scanned % CANCEL_CHECK_INTERVAL == 0) ProgressManager.checkCanceled();
            scanned++;
            for (PsiClass clazz : lookup.invoke(shortName)) {
                if (clazz.isInterface() || clazz.isEnum() || clazz.isAnnotationType()) continue;
                if (aliasName.equals(JpaMetadataReader.entityNameOf(clazz))) candidates.add(clazz);
            }
        }
        return new AliasCandidates(new ArrayList<>(candidates), true);
    }

    private static EntityClassResolver.Resolution unresolved() { return new EntityClassResolver.Resolution(null, List.of()); }
    /** @param name exact entity name @param scope query scope including libraries */
    private record LookupKey(String name, GlobalSearchScope scope) {}
    private static final class LookupCache {
        final long psiStamp;
        final long rootStamp;
        final Map<LookupKey, List<SmartPsiElementPointer<PsiClass>>> entries = new ConcurrentHashMap<>();
        LookupCache(long psiStamp, long rootStamp) { this.psiStamp = psiStamp; this.rootStamp = rootStamp; }
    }
    private static final int CACHE_LIMIT = 4096;
    private static final Key<LookupCache> CACHE_KEY = Key.create("mp-visualizer.scopedEntityAliases");
    private static final AtomicInteger scanCount = new AtomicInteger();
    public static Integer scanLimitForTest;
    public static int scanCountForTest() { return scanCount.get(); }
    public static void resetScanCountForTest() { scanCount.set(0); }

    /** Kotlin 默认参数等价重载：{@code limit} 缺省 {@link #SCAN_LIMIT}，{@code onTruncated} 缺省空回调。 */
    public static PsiClass findAliasOwner(String aliasName, Sequence<String> names,
                                          Function1<String, PsiClass[]> lookup) {
        return findAliasOwner(aliasName, names, lookup, SCAN_LIMIT, scanned -> kotlin.Unit.INSTANCE);
    }

    /** Kotlin 默认参数等价重载：{@code onTruncated} 缺省空回调。 */
    public static PsiClass findAliasOwner(String aliasName, Sequence<String> names,
                                          Function1<String, PsiClass[]> lookup, int limit) {
        return findAliasOwner(aliasName, names, lookup, limit, scanned -> kotlin.Unit.INSTANCE);
    }

    /**
     * 扫描 {@code names}（类短名序列），返回首个实体别名等于 {@code aliasName}（忽略大小写）的实体类。
     *
     * @param aliasName   目标实体别名（{@code @Entity(name = ...)} 的 name）
     * @param names       待遍历的类短名（生产侧＝{@code PsiShortNamesCache.getAllClassNames()}）
     * @param lookup      按短名取候选类（生产侧＝{@code PsiShortNamesCache.getClassesByName(shortName, scope)}，
     *                    平台签名返回 {@code PsiClass[]}）
     * @param limit       遍历上限，默认 {@link #SCAN_LIMIT}
     * @param onTruncated 触顶回调（入参＝已遍历短名数），生产侧记账用
     * @return 命中的实体类；未命中或触顶（降级为未命中）时 null
     */
    public static PsiClass findAliasOwner(String aliasName, Sequence<String> names,
                                          Function1<String, PsiClass[]> lookup, int limit,
                                          Function1<Integer, kotlin.Unit> onTruncated) {
        int scanned = 0;
        Iterator<String> it = names.iterator();
        while (it.hasNext()) {
            String shortName = it.next();
            if (scanned >= limit) {
                onTruncated.invoke(scanned);
                return null;
            }
            if (scanned % CANCEL_CHECK_INTERVAL == 0) ProgressManager.checkCanceled();
            scanned++;
            for (PsiClass clazz : lookup.invoke(shortName)) {
                // 与旧实现同口径：接口/枚举/注解类型不可能是 @Entity 实体
                if (clazz.isInterface() || clazz.isEnum() || clazz.isAnnotationType()) continue;
                String alias = JpaMetadataReader.entityNameOf(clazz);
                if (alias == null) continue;
                if (alias.equalsIgnoreCase(aliasName)) return clazz;
            }
        }
        return null;
    }
}
