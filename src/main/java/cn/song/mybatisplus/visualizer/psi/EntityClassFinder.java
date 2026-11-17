package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 实体类查找器（T-M36.2：SQL 反向转换的 LambdaQueryWrapper 上下文解析）。
 *
 * 按 SQL 表名在项目中查找对应实体类（供 {@link cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator}
 * 生成 {@code LambdaQueryWrapper<Entity>} 代码），查找策略：
 * 1. <b>类名推断快路径</b>：表名 → 驼峰类名候选（含 {@code Entity} 后缀变体 + 去首段变体——
 *    {@code sys_account_role_rel} → {@code AccountRoleRel[Entity]}，前缀不一致常见命名直接命中）→
 *    {@link PsiShortNamesCache} 按类名检索 → {@link EntityResolver} 解析校验表名精确匹配，防同名词误配；
 * 2. <b>@TableName 反查慢路径</b>（快路径未命中时兜底，结果项目级缓存）：
 *    全量扫描项目类，匹配「@TableName 注解值」或「推断表名」等于 SQL 表名的类——
 *    扫描结果（类名/全限定名）缓存于 Project userData，以 {@code PsiModificationTracker.modificationCount}
 *    失效（PSI 任何修改自动重扫一次；无实体匹配场景不再每次转换全量扫描——性能修复）。
 *
 * <p>多模块同名实体可能返回多个候选（调用方走候选选择交互）；无候选返回空列表（调用方回落
 * 字符串列名 QueryWrapper 形态）。</p>
 */
public class EntityClassFinder {

    private final Project project;

    public EntityClassFinder(Project project) {
        this.project = project;
    }

    /**
     * 按表名查找实体类（须在 ReadAction 内调用——PSI 索引访问）。
     *
     * @param tableName SQL 表名（解析器已剥前缀/反引号）
     * @return 表名精确匹配的实体类列表（按候选类名顺序：纯驼峰名优先、Entity 后缀次之）
     */
    public List<PsiClass> find(String tableName) {
        if (tableName == null || tableName.isBlank()) return List.of();
        EntityResolver resolver = new EntityResolver();
        // 源码优先；库兜底只做候选名索引查询，不在反查热路径全扫 classpath。
        List<PsiClass> result = findByCandidateNames(tableName, resolver, GlobalSearchScope.projectScope(project));
        if (!result.isEmpty()) return result;
        result = findByCandidateNames(tableName, resolver, libraryScope());
        if (!result.isEmpty()) return result;
        result = findByTableNameAnnotationCached(tableName);
        if (!result.isEmpty()) return result;
        return findByLibrarySweepCached(tableName);
    }

    /** 按候选类名在给定 scope 内找表名精确匹配的实体（快路径）。 */
    private List<PsiClass> findByCandidateNames(String tableName, EntityResolver resolver, GlobalSearchScope scope) {
        List<PsiClass> result = new ArrayList<>();
        for (String candidateName : candidateClassNames(tableName)) {
            PsiClass[] classes = PsiShortNamesCache.getInstance(project)
                .getClassesByName(candidateName, scope);
            for (PsiClass clazz : classes) {
                // T-M36.3：VO/DTO 类排除——候选类名以 VO/DTO 结尾的（如 UserVO）不参与转换
                // （表名校验已覆盖绝大多数；此为命名惯例的显式防御）
                if (isVoDto(clazz)) {
                    continue;
                }
                // 解析表名精确匹配（大小写不敏感——表名大小写因方言/平台而异）；
                // resolve 异常情况返回空表名 → 自然排除
                String resolvedTable = resolver.resolve(clazz).getTableName();
                if (resolvedTable != null && resolvedTable.equalsIgnoreCase(tableName)) {
                    result.add(clazz);
                }
            }
        }
        return result;
    }

    /**
     * 库兜底用 allScope：notScope(projectScope) 会把模块库根一并排除。
     * 项目类此前已查过且 0 命中，重复的几次查询由 EntityResolver 缓存摊销。
     */
    private GlobalSearchScope libraryScope() { return GlobalSearchScope.allScope(project); }

    /** 库内非常规命名实体只读缓存；未就绪则投递后台扫描，本次仍返回空，不等待。 */
    private List<PsiClass> findByLibrarySweepCached(String tableName) {
        LibrarySweepCache entry = librarySweepCache();
        List<MatchedClass> cached = entry != null ? entry.tables.get(tableName.toLowerCase(Locale.ROOT)) : null;
        if (cached != null) {
            List<PsiClass> restored = new ArrayList<>();
            for (MatchedClass matched : cached) {
                PsiClass clazz = restoreMatched(matched);
                if (clazz != null) restored.add(clazz);
            }
            return restored;
        }
        requestLibrarySweep();
        return List.of();
    }

    private LibrarySweepCache librarySweepCache() {
        long current = projectStructureVersion();
        LibrarySweepCache cached = project.getUserData(LIBRARY_SWEEP_CACHE_KEY);
        return cached != null && cached.count == current ? cached : null;
    }

    /** 项目结构版本：文件增删/依赖与模块根变化失效，改已有文件正文不使库缓存失效。 */
    private long projectStructureVersion() {
        return ProjectRootModificationTracker.getInstance(project).getModificationCount();
    }

    /** 四道闸：开关、本结构版本一次、冷却窗口、全局在途上限。 */
    private void requestLibrarySweep() {
        if (!isLibrarySweepEnabled()) return;
        if (!markSweepDispatched()) return;
        if (inFlightSweep.get() >= MAX_IN_FLIGHT_SWEEP) return;
        if (sweepDispatchForTest != null) {
            sweepDispatchForTest.invoke(project);
            return;
        }
        var app = ApplicationManager.getApplication();
        if (app == null) return;
        inFlightSweep.incrementAndGet();
        java.util.concurrent.Future<?> future = null;
        try {
            future = app.executeOnPooledThread(() -> {
                try {
                    // 不包外层 ReadAction：内核自带分片读动作，外层包裹会重新整趟持锁。
                    int entries = runLibrarySweepNow();
                    if (entries < 0) clearLibrarySweepDispatched();
                } catch (Throwable t) {
                    // 取消、关工程、取数异常均允许下次重投；JVM 致命错误保留原对象透传。
                    clearLibrarySweepDispatched();
                    if (FatalErrors.isFatal(t)) throw t;
                } finally {
                    inFlightSweep.decrementAndGet();
                }
            });
        } catch (Throwable ignored) {
            // 对应上游投递层 runCatching().getOrNull()，保持与任务体的 fatal 策略边界。
        }
        if (future == null) inFlightSweep.decrementAndGet();
    }

    /** 每结构版本一次，再叠时间冷却，避免依赖/模块频繁变化引发投递风暴。 */
    private boolean markSweepDispatched() {
        if (!cooldownElapsed()) return false;
        long current = projectStructureVersion();
        LibrarySweepCache existing = project.getUserData(LIBRARY_SWEEP_CACHE_KEY);
        if (existing != null && existing.count == current) {
            if (existing.dispatched) return false;
            existing.dispatched = true;
        } else {
            project.putUserData(LIBRARY_SWEEP_CACHE_KEY, new LibrarySweepCache(current, new LinkedHashMap<>(), true));
        }
        project.putUserData(LIBRARY_SWEEP_DISPATCH_AT_KEY, sweepNow());
        return true;
    }

    /** 窗口下界为上趟实际耗时，保证两趟之间至少歇一趟，直接扫描也参与冷却。 */
    private boolean cooldownElapsed() {
        Long last = project.getUserData(LIBRARY_SWEEP_DISPATCH_AT_KEY);
        if (last == null) return true;
        Long lastCost = project.getUserData(LIBRARY_SWEEP_LAST_COST_KEY);
        return sweepNow() - last >= Math.max(sweepCooldownMs, lastCost != null ? lastCost : 0L);
    }

    private long sweepNow() { return sweepClockForTest != null ? sweepClockForTest.invoke() : System.currentTimeMillis(); }

    private void clearLibrarySweepDispatched() {
        LibrarySweepCache cached = project.getUserData(LIBRARY_SWEEP_CACHE_KEY);
        if (cached != null) cached.dispatched = false;
    }

    /** 设置不可用默认开，测试缝优先。 */
    private boolean isLibrarySweepEnabled() {
        if (sweepEnabledForTest != null) return sweepEnabledForTest;
        try {
            return PluginSettings.getInstance().getState().getLibraryEntitySweepEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private int sweepScanLimit() { return sweepScanLimitForTest != null ? sweepScanLimitForTest : LIBRARY_SWEEP_SCAN_LIMIT; }

    /**
     * 全 classpath 的唯一全量扫描，必须在 BGT 调用，不可包外层 ReadAction。
     * 短名清单用独立短读动作，每 500 个短名一个读动作，片间真正释放读锁并检查取消。
     * 上限按剩余预算切片，触顶返回 -1 且不落残缺缓存；先取结构版本，结构在扫描期间变化则结果自然过期。
     * 只认 @TableName：按类名推断会把 JDK 等任意类全建成表，常规命名已由快路径覆盖。
     * 显式按钮扫描也记录完成时刻和耗时（至少 1ms），避免随后未命中立即再扫。
     */
    public int runLibrarySweepNow() {
        long startedMs = sweepNow();
        long startedAt = projectStructureVersion();
        GlobalSearchScope scope = libraryScope();
        int limit = sweepScanLimit();
        Map<String, List<MatchedClass>> tables = new LinkedHashMap<>();
        String[] names = ReadAction.compute(() -> PsiShortNamesCache.getInstance(project).getAllClassNames());
        int scanned = 0;
        boolean truncated = false;
        int from = 0;
        while (from < names.length) {
            ProgressManager.checkCanceled();
            int remaining = limit - scanned;
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            int to = Math.min(Math.min(from + LIBRARY_SWEEP_SLICE, from + remaining), names.length);
            final int sliceFrom = from;
            ReadAction.compute(() -> {
                PsiShortNamesCache namesCache = PsiShortNamesCache.getInstance(project);
                for (int j = sliceFrom; j < to; j++) {
                    String shortName = names[j];
                    if (endsWithIgnoreCase(shortName, "VO") || endsWithIgnoreCase(shortName, "DTO")) continue;
                    for (PsiClass clazz : namesCache.getClassesByName(shortName, scope)) {
                        if (clazz.isInterface() || clazz.isEnum() || clazz.isAnnotationType()) continue;
                        if (isVoDto(clazz)) continue;
                        String table = tableNameAnnotation(clazz);
                        if (table == null || table.isBlank()) continue;
                        tables.computeIfAbsent(table.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                            .add(new MatchedClass(shortName, clazz.getQualifiedName()));
                    }
                }
                return null;
            });
            // 等价于上游片内逐短名 scanned++：只在整片成功返回后才使用这个计数。
            scanned += to - from;
            from = to;
        }
        project.putUserData(LIBRARY_SWEEP_DISPATCH_AT_KEY, sweepNow());
        project.putUserData(LIBRARY_SWEEP_LAST_COST_KEY, Math.max(1L, sweepNow() - startedMs));
        if (truncated) return -1;
        project.putUserData(LIBRARY_SWEEP_CACHE_KEY, new LibrarySweepCache(startedAt, tables, true));
        return tables.size();
    }

    /**
     * @TableName 反查（缓存化兜底）：实体命名与表名驼峰不一致时（如 {@code AccountRoleRelEntity}
     * ↔ {@code sys_account_role_rel}）按类名推断无法命中——全量扫描项目全部类短名，匹配
     * 「@TableName 注解值」或「推断表名（{@link EntityResolver#inferDecoratedTableName}）」等于 SQL 表名
     * （大小写不敏感）的类。
     *
     * <p><b>性能（用户反馈：无实体场景转换卡顿）</b>：全量扫描为 O(项目类数) PSI 索引访问，此前每次
     * 转换都执行（快路径 0 命中时必触发，JOIN 每张副表各一次）——现把扫描结果（类全限定名）
     * 缓存于 Project userData：同表名后续 find 直接按类名重取（几次索引查询）；缓存以
     * {@code PsiModificationTracker.modificationCount} 失效（PSI 修改后自动重扫一次）。
     * 缓存存类名字符串而非 PsiClass 引用（PSI 对象随文件修改失效，引用长期持有不安全）。
     * 须在 ReadAction 内调用。</p>
     */
    private List<PsiClass> findByTableNameAnnotationCached(String tableName) {
        ReverseLookupCache entry = reverseLookupCache();
        String key = tableName.toLowerCase(Locale.ROOT);
        List<MatchedClass> cached = entry.names.get(key);
        if (cached != null) {
            List<PsiClass> restored = new ArrayList<>(cached.size());
            for (MatchedClass matched : cached) {
                PsiClass clazz = restoreMatched(matched);
                if (clazz != null) restored.add(clazz);
            }
            return restored;
        }
        entry.scans++;
        List<PsiClass> matched = scanAllClassesByTableName(tableName);
        List<MatchedClass> entries = new ArrayList<>(matched.size());
        for (PsiClass clazz : matched) {
            String name = clazz.getName();
            if (name == null) continue;
            entries.add(new MatchedClass(name, clazz.getQualifiedName()));
        }
        entry.names.put(key, entries);
        return matched;
    }

    /** 缓存条目按类名重取 PsiClass（shortName 索引查询 + qualifiedName 精确比对防同名误配）。 */
    private PsiClass restoreMatched(MatchedClass matched) {
        // 两种缓存共用恢复路径，依赖库类必须从 allScope 重取；全限定名防同名误配。
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        PsiClass[] classes = PsiShortNamesCache.getInstance(project)
            .getClassesByName(matched.shortName, scope);
        for (PsiClass clazz : classes) {
            if (java.util.Objects.equals(clazz.getQualifiedName(), matched.qualifiedName) && !isVoDto(clazz)) {
                return clazz;
            }
        }
        return null;
    }

    /** 全量扫描匹配表名的类（P2：跳过接口/枚举/注解类——MP 实体恒为 class）。 */
    private List<PsiClass> scanAllClassesByTableName(String tableName) {
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        List<PsiClass> result = new ArrayList<>();
        int scanned = 0;
        for (String shortName : cache.getAllClassNames()) {
            // 项目慢路径同样须可取消，避免全量短名遍历长时间持读锁。
            if (scanned % LIBRARY_SWEEP_CANCEL_INTERVAL == 0) ProgressManager.checkCanceled();
            scanned++;
            // 短名快筛：VO/DTO 结尾的直接跳过（避免逐类检查）
            if (endsWithIgnoreCase(shortName, "VO") || endsWithIgnoreCase(shortName, "DTO")) continue;
            for (PsiClass clazz : cache.getClassesByName(shortName, scope)) {
                // P2：接口/枚举/注解类不可能是 MP 实体，跳过（减少 PSI 访问）
                if (clazz.isInterface() || clazz.isEnum() || clazz.isAnnotationType()) continue;
                if (isVoDto(clazz)) continue;
                String className = clazz.getName();
                if (className == null) continue;
                String annotated = tableNameAnnotation(clazz);
                // T-TS-07：显式传候选类所属项目（= 本 finder 的项目，scope 亦为 projectScope）——
                // 反查表名的兜底推断不再受「首个打开项目」影响；clazz 是 PSI，project 必可得
                String resolvedTable = annotated != null
                        ? annotated
                        : EntityResolver.inferDecoratedTableName(className, clazz.getProject());
                if (resolvedTable.equalsIgnoreCase(tableName)) {
                    result.add(clazz);
                }
            }
        }
        result.sort(java.util.Comparator.comparing(PsiClass::getName));
        return result;
    }

    /** 类级 @TableName 注解值；无注解或值非字符串返回 null（短名兜底——注解类不可解析环境仍可识别）。 */
    private static String tableNameAnnotation(PsiClass clazz) {
        PsiModifierList modifierList = clazz.getModifierList();
        if (modifierList == null) return null;
        PsiAnnotation[] annotations = modifierList.getAnnotations();
        if (annotations.length == 0) return null;
        PsiAnnotation annotation = null;
        for (PsiAnnotation a : annotations) {
            if (TABLE_NAME.equals(a.getQualifiedName())) {
                annotation = a;
                break;
            }
        }
        if (annotation == null) {
            for (PsiAnnotation a : annotations) {
                if (a.getNameReferenceElement() != null && "TableName".equals(a.getNameReferenceElement().getReferenceName())) {
                    annotation = a;
                    break;
                }
            }
        }
        if (annotation == null) return null;
        if (!(annotation.findAttributeValue("value") instanceof PsiLiteralExpression)) return null;
        Object value = ((PsiLiteralExpression) annotation.findAttributeValue("value")).getValue();
        return value instanceof String ? (String) value : null;
    }

    private static boolean isVoDto(PsiClass clazz) {
        String name = clazz.getName();
        return name != null && (endsWithIgnoreCase(name, "VO") || endsWithIgnoreCase(name, "DTO"));
    }

    private static boolean endsWithIgnoreCase(String s, String suffix) {
        int offset = s.length() - suffix.length();
        if (offset < 0) return false;
        return s.substring(offset).equalsIgnoreCase(suffix);
    }

    /**
     * 候选类名：表名驼峰（{@code car_device_info} → {@code CarDeviceInfo}，复用
     * {@link cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator#entityClassName}）、{@code Entity}
     * 后缀变体（{@code CarDeviceInfoEntity}——MP 项目实体命名惯例），以及<b>去首段变体</b>
     * （{@code sys_account_role_rel} → {@code AccountRoleRel[Entity]}——{@code sys_}/{@code t_}/{@code biz_} 等前缀不一致的
     * 常见命名直接快路径命中，避免全量反查）；去重。
     */
    public List<String> candidateClassNames(String tableName) {
        String pascal = cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator.entityClassName(tableName);
        int idx = tableName.indexOf('_');
        String noFirstSeg = idx >= 0 ? tableName.substring(idx + 1) : tableName;
        noFirstSeg = cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator.entityClassName(noFirstSeg);
        Set<String> distinct = new LinkedHashSet<>(List.of(pascal, pascal + "Entity", noFirstSeg, noFirstSeg + "Entity"));
        return new ArrayList<>(distinct);
    }

    /** 项目级反查缓存（Project userData；PsiModificationTracker 失效）。 */
    private static final class ReverseLookupCache {
        /** 扫描时的 modificationCount——PSI 修改后失效重建。 */
        final long count;
        /** 建成时刻（TTL 兜底：结构版本看不见的正文修改靠 TTL 失效）。 */
        final long createdAt = System.currentTimeMillis();
        /** 表名（小写）→ 匹配类（短名 + 全限定名）。 */
        final Map<String, List<MatchedClass>> names = new LinkedHashMap<>();
        /** 全量扫描次数（测试访问器断言缓存命中不再扫描）。 */
        int scans = 0;
        /** 跨 ORM 回补（全项目推断扫描）执行次数，每缓存期至多 +1（测试访问器用）。 */
        int inferredScanCount = 0;

        ReverseLookupCache(long count) {
            this.count = count;
        }
    }

    /** 缓存条目：短名（索引查询键）+ 全限定名（精确比对防同名误配）。 */
    private static final class MatchedClass {
        final String shortName;
        final String qualifiedName;

        MatchedClass(String shortName, String qualifiedName) {
            this.shortName = shortName;
            this.qualifiedName = qualifiedName;
        }
    }

    /** 库扫描缓存以项目结构版本失效；正文编辑不能退化为每次打字重扫 classpath。 */
    private static final class LibrarySweepCache {
        /** 扫描开始时的结构版本。 */
        final long count;
        /** 小写表名到类短名/全限定名，不长期持有 PSI。 */
        final Map<String, List<MatchedClass>> tables;
        /** 本版本是否投递过；取消/触顶时清除。 */
        boolean dispatched;

        LibrarySweepCache(long count, Map<String, List<MatchedClass>> tables, boolean dispatched) {
            this.count = count;
            this.tables = tables;
            this.dispatched = dispatched;
        }
    }

    private static final Key<LibrarySweepCache> LIBRARY_SWEEP_CACHE_KEY = Key.create("mp-visualizer.librarySweepCache");
    private static final Key<Long> LIBRARY_SWEEP_DISPATCH_AT_KEY = Key.create("mp-visualizer.librarySweepDispatchAt");
    private static final Key<Long> LIBRARY_SWEEP_LAST_COST_KEY = Key.create("mp-visualizer.librarySweepLastCostMs");
    /** 项目慢路径每 64 个短名检查取消。 */
    public static final int LIBRARY_SWEEP_CANCEL_INTERVAL = 64;
    /** 每片一个独立读动作，片间释放读锁（不能只靠无进度上下文的 checkCanceled 让锁）。 */
    public static final int LIBRARY_SWEEP_SLICE = 500;
    /** 病态兜底而非常规截断线；真实 classpath 含 JDK 常有 2~3 万个短名。 */
    public static final int LIBRARY_SWEEP_SCAN_LIMIT = 200_000;
    public static final long LIBRARY_SWEEP_COOLDOWN_MS = 5_000L;
    public static Integer sweepScanLimitForTest = null;
    public static long sweepCooldownMs = LIBRARY_SWEEP_COOLDOWN_MS;
    /** Kotlin function 字段保留 Function0/Function1，而不是改成不可赋 Kotlin lambda 的 Java SAM 字段。 */
    public static Function0<Long> sweepClockForTest = null;
    public static Boolean sweepEnabledForTest = null;
    public static Function1<Project, Unit> sweepDispatchForTest = null;
    private static final int MAX_IN_FLIGHT_SWEEP = 1;
    private static final java.util.concurrent.atomic.AtomicInteger inFlightSweep = new java.util.concurrent.atomic.AtomicInteger();

    public static void seedLibrarySweepForTest(Project project, String table, PsiClass clazz) {
        long current = ProjectRootModificationTracker.getInstance(project).getModificationCount();
        LibrarySweepCache existing = project.getUserData(LIBRARY_SWEEP_CACHE_KEY);
        Map<String, List<MatchedClass>> tables = existing != null && existing.count == current
            ? new LinkedHashMap<>(existing.tables) : new LinkedHashMap<>();
        String shortName = clazz.getName();
        if (shortName == null) return;
        tables.put(table.toLowerCase(Locale.ROOT), List.of(new MatchedClass(shortName, clazz.getQualifiedName())));
        project.putUserData(LIBRARY_SWEEP_CACHE_KEY, new LibrarySweepCache(current, tables, true));
    }

    public static boolean sweepDispatchedForTest(Project project) {
        LibrarySweepCache cached = project.getUserData(LIBRARY_SWEEP_CACHE_KEY);
        return cached != null && cached.dispatched;
    }

    public static int sweepTableCountForTest(Project project) {
        LibrarySweepCache cached = project.getUserData(LIBRARY_SWEEP_CACHE_KEY);
        return cached != null ? cached.tables.size() : 0;
    }

    public static Set<String> sweepTablesForTest(Project project) {
        LibrarySweepCache cached = project.getUserData(LIBRARY_SWEEP_CACHE_KEY);
        return cached != null ? cached.tables.keySet() : Set.of();
    }

    public static void clearLibrarySweepDispatchedForTest(Project project) {
        LibrarySweepCache cached = project.getUserData(LIBRARY_SWEEP_CACHE_KEY);
        if (cached != null) cached.dispatched = false;
    }

    public static void clearLibrarySweepCache(Project project) {
        project.putUserData(LIBRARY_SWEEP_CACHE_KEY, null);
        project.putUserData(LIBRARY_SWEEP_DISPATCH_AT_KEY, null);
        project.putUserData(LIBRARY_SWEEP_LAST_COST_KEY, null);
    }

    private ReverseLookupCache reverseLookupCache() {
        // 失效计数：PsiModificationTracker（IPGP 中位于 com.intellij.psi.util 包，编译实证；
        // PSI 任何修改递增，缓存自动失效重扫）
        long current = PsiModificationTracker.getInstance(project).getModificationCount();
        ReverseLookupCache existing = project.getUserData(REVERSE_LOOKUP_CACHE_KEY);
        if (existing != null && existing.count == current) {
            // TTL 兜底：正文修改（结构版本看不见）超 TTL 视为失效，允许重建一次；测试置 0 强制重建。
            long ttl = reverseLookupTtlMs;
            long age = System.currentTimeMillis() - existing.createdAt;
            if (ttl > 0 && age >= 0 && age < ttl) return existing;
        }
        Long delay = indexScanDelayMsForTest;
        if (delay != null && delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        indexBuildCount(project).incrementAndGet();
        ReverseLookupCache fresh = new ReverseLookupCache(current);
        project.putUserData(REVERSE_LOOKUP_CACHE_KEY, fresh);
        return fresh;
    }

    /** Kotlin companion object 兼容层（{@code EntityClassFinder.Companion.xxx(...)} 调用形态）。 */
    public static final class Companion {
        private Companion() {}

        /** 测试访问器：项目级全量反查扫描次数（缓存命中后不再增长）。 */
        public int scanCountForTest(Project project) {
            ReverseLookupCache cache = project.getUserData(REVERSE_LOOKUP_CACHE_KEY);
            return cache != null ? cache.scans : 0;
        }

        /** 反查缓存信息（设置页展示）：返回（缓存表名条目数, 缓存类名总数）；无缓存 → (0, 0)。 */
        public kotlin.Pair<Integer, Integer> reverseLookupCacheInfo(Project project) {
            ReverseLookupCache entry = project.getUserData(REVERSE_LOOKUP_CACHE_KEY);
            if (entry == null) return new kotlin.Pair<>(0, 0);
            int total = 0;
            for (List<MatchedClass> classes : entry.names.values()) {
                total += classes.size();
            }
            return new kotlin.Pair<>(entry.names.size(), total);
        }

        /** 清除项目级反查缓存（设置页「清除缓存」；下次 find 重新全量扫描）。 */
        public void clearReverseLookupCache(Project project) {
            project.putUserData(REVERSE_LOOKUP_CACHE_KEY, null);
            clearLibrarySweepCache(project);
        }

        /**
         * 反查缓存大小估算（设置页展示 KB/MB）：缓存内容为字符串（表名/类短名/全限定名），
         * 按 UTF-16 字符 2 字节 + 每条目/每值固定开销近似——展示用，非精确 JVM 内存。
         */
        public long reverseLookupCacheSizeBytes(Project project) {
            ReverseLookupCache entry = project.getUserData(REVERSE_LOOKUP_CACHE_KEY);
            if (entry == null) return 0L;
            long bytes = 0L;
            for (Map.Entry<String, List<MatchedClass>> e : entry.names.entrySet()) {
                bytes += e.getKey().length() * 2L + 16;
                for (MatchedClass matched : e.getValue()) {
                    int nameLen = matched.shortName.length() + (matched.qualifiedName != null ? matched.qualifiedName.length() : 0);
                    bytes += nameLen * 2L + 24;
                }
            }
            return bytes;
        }
    }

    public static final Companion Companion = new Companion();

    // ---- 外层 static 转发器（兼容 Kotlin 侧 {@code EntityClassFinder.method(...)} 调用形态） ----

    public static int scanCountForTest(Project project) {
        return Companion.scanCountForTest(project);
    }

    public static kotlin.Pair<Integer, Integer> reverseLookupCacheInfo(Project project) {
        return Companion.reverseLookupCacheInfo(project);
    }

    public static void clearReverseLookupCache(Project project) {
        Companion.clearReverseLookupCache(project);
    }

    public static long reverseLookupCacheSizeBytes(Project project) {
        return Companion.reverseLookupCacheSizeBytes(project);
    }

    // ==================================================================
    // T-SyncK2JMaster179 生产补漏：master EntityClassFinder.kt companion 的 TTL / 测试缝 / 访问器。
    // ==================================================================

    /** 调用方等待索引建成的预算上限（毫秒）。 */
    public static final long INDEX_WAIT_BUDGET_MS = 800L;

    /** 反查索引 TTL（毫秒），默认 5 分钟；测试置 0 强制下次查询重建。 */
    public static volatile long reverseLookupTtlMs = 300_000L;

    /** 测试缝：强制建索引走同步分支（null = 看 ApplicationManager.isUnitTestMode）。 */
    public static volatile Boolean syncIndexBuildForTest = null;

    /** 测试缝：给全量扫描注入人为延迟（毫秒）。 */
    public static volatile Long indexScanDelayMsForTest = null;

    private static final Key<java.util.concurrent.atomic.AtomicInteger> REVERSE_LOOKUP_SCAN_TOTAL_KEY =
        Key.create("mp-visualizer.reverseLookupScanTotal");

    /** 跨缓存实例的累计建索引次数（守卫用：缓存重建后 scans 归零仍能看出有没有重扫）。 */
    public static java.util.concurrent.atomic.AtomicInteger indexBuildCount(Project project) {
        java.util.concurrent.atomic.AtomicInteger counter = project.getUserData(REVERSE_LOOKUP_SCAN_TOTAL_KEY);
        if (counter == null) {
            counter = new java.util.concurrent.atomic.AtomicInteger();
            project.putUserData(REVERSE_LOOKUP_SCAN_TOTAL_KEY, counter);
        }
        return counter;
    }

    /** 跨 ORM 回补（全项目推断扫描）执行次数。 */
    public static int inferredScanCountForTest(Project project) {
        ReverseLookupCache cache = project.getUserData(REVERSE_LOOKUP_CACHE_KEY);
        return cache != null ? cache.inferredScanCount : 0;
    }

    private static final String TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName";

    private static final Key<ReverseLookupCache> REVERSE_LOOKUP_CACHE_KEY =
        Key.create("mp-visualizer.reverseLookupCache");
}
