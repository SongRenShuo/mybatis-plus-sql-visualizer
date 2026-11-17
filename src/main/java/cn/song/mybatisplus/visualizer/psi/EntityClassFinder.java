package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProcessCanceledException;
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
import com.intellij.psi.impl.java.stubs.index.JavaAnnotationIndex;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实体类查找器（T-M36.2：SQL 反向转换的 LambdaQueryWrapper 上下文解析）。
 *
 * <p>按 SQL 表名在项目中查找对应实体类（供 {@link cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator}
 * 生成 {@code LambdaQueryWrapper<Entity>} 代码），查找策略：
 * <ol>
 *   <li><b>类名推断快路径</b>：表名 → 驼峰类名候选（含 {@code Entity} 后缀变体 + 去首段变体）→
 *       {@link PsiShortNamesCache} 按类名检索 → {@link EntityResolver} 解析校验表名精确匹配；</li>
 *   <li><b>@TableName 反查慢路径</b>（快路径未命中时兜底）：一次全量扫描建「表名 → 类」全表索引，
 *       缓存于 Project userData，任意表名查询 O(1)；失效基准＝项目结构版本 + TTL（挂
 *       PsiModificationTracker 会被预览链路自己写 exec 文件/临时 PSI 顶掉，导致每次打开预览都重扫）。
 *       索引未就绪时投递后台构建、本次原样返回空（不阻塞、不等待）；</li>
 *   <li><b>跨 ORM 回补</b>：注解索引未命中时，按查询做一次「全项目推断」扫描（认 JPA {@code @Table} /
 *       MBG 命名推断等），合并进同一索引，每个缓存期至多一次；</li>
 *   <li><b>库兜底</b>：实体类来自依赖 JAR 时按候选类名快路径 + 一次性全库扫描。</li>
 * </ol>
 * 多模块同名实体可能返回多个候选；无候选返回空列表（调用方回落字符串列名 QueryWrapper 形态）。</p>
 */
public class EntityClassFinder {

    private final Project project;

    public EntityClassFinder(Project project) {
        this.project = project;
    }

    /** 按表名查找实体类（须在 ReadAction 内调用——PSI 索引访问）。 */
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
                if (isVoDto(clazz)) continue;
                String resolvedTable = resolver.resolve(clazz).getTableName();
                if (resolvedTable != null && resolvedTable.equalsIgnoreCase(tableName)) {
                    result.add(clazz);
                }
            }
        }
        return result;
    }

    /** 库兜底用 allScope：notScope(projectScope) 会把模块库根一并排除。 */
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
                    int entries = runLibrarySweepNow();
                    if (entries < 0) clearLibrarySweepDispatched();
                } catch (Throwable t) {
                    clearLibrarySweepDispatched();
                    if (FatalErrors.isFatal(t)) throw t;
                } finally {
                    inFlightSweep.decrementAndGet();
                }
            });
        } catch (Throwable ignored) {
            // 对应上游 runCatching().getOrNull()
        }
        if (future == null) inFlightSweep.decrementAndGet();
    }

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

    private boolean isLibrarySweepEnabled() {
        if (sweepEnabledForTest != null) return sweepEnabledForTest;
        try {
            return PluginSettings.getInstance().getState().getLibraryEntitySweepEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private int sweepScanLimit() { return sweepScanLimitForTest != null ? sweepScanLimitForTest : LIBRARY_SWEEP_SCAN_LIMIT; }

    /** 全 classpath 唯一全量扫描，必须 BGT 调用，不可包外层 ReadAction。 */
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
                        String table = TableNameAnnotations.tableNameOfClass(clazz);
                        if (table == null || table.isBlank()) continue;
                        tables.computeIfAbsent(table.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                            .add(new MatchedClass(shortName, clazz.getQualifiedName()));
                    }
                }
                return null;
            });
            scanned += to - from;
            from = to;
        }
        project.putUserData(LIBRARY_SWEEP_DISPATCH_AT_KEY, sweepNow());
        project.putUserData(LIBRARY_SWEEP_LAST_COST_KEY, Math.max(1L, sweepNow() - startedMs));
        if (truncated) return -1;
        project.putUserData(LIBRARY_SWEEP_CACHE_KEY, new LibrarySweepCache(startedAt, tables, true));
        return tables.size();
    }

    // ==================================================================
    // 反查慢路径：一次全表索引 + TTL + 异步构建 + 跨 ORM 回补（master EntityClassFinder.kt 语义）
    // ==================================================================

    /**
     * @TableName 反查（缓存化兜底）：一次全量扫描建「表名 → 类」全表索引，之后任意表名 O(1)。
     * 索引未就绪时投递后台构建、本次原样返回空（不阻塞）；注解索引未命中时触发一次跨 ORM 推断回补。
     */
    private List<PsiClass> findByTableNameAnnotationCached(String tableName) {
        ReverseLookupCache entry = reverseLookupCache();
        ensureIndexBuilt(entry);
        List<MatchedClass> hit = entry.index.get(tableName.toLowerCase(Locale.ROOT));
        // 跨 ORM 兜底：注解索引只覆盖带表名注解的实体；未命中时做一次「全项目推断」并合并进同一索引。
        if (hit == null && !entry.inferredScanDone) {
            ensureInferredScan(entry);
            hit = entry.index.get(tableName.toLowerCase(Locale.ROOT));
        }
        List<MatchedClass> found = hit != null ? hit : List.of();
        // 顺序契约：旧实现按类名排序返回（多候选时调用方按此顺序处理）。
        List<MatchedClass> sorted = new ArrayList<>(found);
        sorted.sort((a, b) -> {
            String sa = a.shortName != null ? a.shortName : "";
            String sb = b.shortName != null ? b.shortName : "";
            return sa.compareTo(sb);
        });
        List<PsiClass> result = new ArrayList<>(sorted.size());
        for (MatchedClass matched : sorted) {
            PsiClass clazz = restoreMatched(matched);
            if (clazz != null) result.add(clazz);
        }
        return result;
    }

    /** 缓存条目按类名重取 PsiClass（shortName 索引查询 + qualifiedName 精确比对防同名误配）。 */
    private PsiClass restoreMatched(MatchedClass matched) {
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

    /**
     * 建索引：一次全量扫描覆盖所有表名，派到应用线程池（非 EDT 至多等 INDEX_WAIT_BUDGET_MS；EDT 一律不等）。
     * 同步口径（测试/单测）走同步分支。
     */
    private void ensureIndexBuilt(ReverseLookupCache entry) {
        if (entry.built) return;
        var app = ApplicationManager.getApplication();
        boolean onEdt = app != null && app.isDispatchThread();
        if (entry.building.compareAndSet(false, true)) {
            boolean sync = syncIndexBuildForTest != null ? syncIndexBuildForTest : (app != null && app.isUnitTestMode());
            if (sync) {
                long started = System.currentTimeMillis();
                Map<String, List<MatchedClass>> built;
                try {
                    built = scanAllTablesIndex();
                } catch (Throwable t) {
                    if (FatalErrors.isFatal(t)) throw t;
                    built = null;
                }
                applyBuiltIndex(entry, built, started);
                return;
            }
            if (!retryCooldownElapsed(entry)) {
                releaseBuild(entry);
                return;
            }
            dispatchIndexBuild(entry);
        }
        if (onEdt) return;
        try {
            entry.latch.await(INDEX_WAIT_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 上次被撤销/失败后的冷却窗口——防连续输入把扫描打成「反复重投」。 */
    private static boolean retryCooldownElapsed(ReverseLookupCache entry) {
        long last = entry.lastAbortAt;
        return last == 0L || System.currentTimeMillis() - last >= INDEX_RETRY_COOLDOWN_MS;
    }

    /** 把全量扫描派到应用线程池（不持读锁等索引；扫描内部自带取消检查）。 */
    private void dispatchIndexBuild(ReverseLookupCache entry) {
        long started = System.currentTimeMillis();
        try {
            ReadAction.<Map<String, List<MatchedClass>>>nonBlocking(() -> {
                try {
                    return scanAllTablesIndex();
                } catch (ProcessCanceledException pce) {
                    throw pce; // 撤销要能被平台看见
                } catch (Throwable t) {
                    if (FatalErrors.isFatal(t)) throw t;
                    return null;
                }
            })
                .inSmartMode(project)
                .expireWith(project)
                .submit(AppExecutorUtil.getAppExecutorService())
                .onSuccess(built -> applyBuiltIndex(entry, built, started))
                .onError(t -> {
                    entry.lastAbortAt = System.currentTimeMillis();
                    releaseBuild(entry);
                });
        } catch (RuntimeException e) {
            entry.lastAbortAt = System.currentTimeMillis();
            releaseBuild(entry);
        }
    }

    /** 索引建成落地（同步/异步共用）：填索引 → 置 built → 放行等待者。 */
    private void applyBuiltIndex(ReverseLookupCache entry, Map<String, List<MatchedClass>> built, long startedMs) {
        try {
            if (built == null) return;
            ReverseLookupCache current = project.getUserData(REVERSE_LOOKUP_CACHE_KEY);
            if (current == null) return;
            ReverseLookupCache target = current;
            if (target.built) return;
            entry.index.putAll(built);
            target.index.putAll(built);
            // 已有项目级回补结果（同一结构版本）→ 直接并进来，新条目立刻具备 MBG/iBatis 那部分
            Map<String, List<MatchedClass>> cached = cachedInferredIndex();
            if (cached != null) {
                for (Map.Entry<String, List<MatchedClass>> e : cached.entrySet()) {
                    target.index.putIfAbsent(e.getKey(), e.getValue());
                }
                target.inferredScanDone = true;
            }
            target.scans++;
            target.built = true;
            indexBuildCount(project).incrementAndGet();
        } finally {
            releaseBuild(entry);
            ReverseLookupCache current = project.getUserData(REVERSE_LOOKUP_CACHE_KEY);
            if (current != null && current != entry) releaseBuild(current);
        }
    }

    /** 放行等待者并释放在途标记（失败路径同样要放行）。 */
    private static void releaseBuild(ReverseLookupCache entry) {
        entry.building.set(false);
        entry.latch.countDown();
    }

    /**
     * 「全项目推断」回补（跨 ORM 兜底）：只在注解索引未命中时触发，结果合并进同一索引并置 inferredScanDone
     * （每个结构版本/缓存期至多一次）。项目级缓存命中则直接并入，不再跑全项目推断。
     */
    private void ensureInferredScan(ReverseLookupCache entry) {
        Map<String, List<MatchedClass>> cached = cachedInferredIndex();
        if (cached != null) {
            if (!entry.inferredScanDone) {
                for (Map.Entry<String, List<MatchedClass>> e : cached.entrySet()) {
                    entry.index.putIfAbsent(e.getKey(), e.getValue());
                }
                entry.inferredScanDone = true;
            }
            return;
        }
        var app = ApplicationManager.getApplication();
        boolean onEdt = app != null && app.isDispatchThread();
        if (entry.inferredBuilding.compareAndSet(false, true)) {
            boolean sync = syncIndexBuildForTest != null ? syncIndexBuildForTest : (app != null && app.isUnitTestMode());
            if (sync) {
                runInferredScan(entry);
                return;
            }
            if (!retryCooldownElapsed(entry)) {
                entry.inferredBuilding.set(false);
                return;
            }
            long started = System.currentTimeMillis();
            try {
                ReadAction.<Map<String, List<MatchedClass>>>nonBlocking(() -> {
                    try {
                        return scanInferredTableIndex();
                    } catch (ProcessCanceledException pce) {
                        throw pce;
                    } catch (Throwable t) {
                        if (FatalErrors.isFatal(t)) throw t;
                        return null;
                    }
                })
                    .inSmartMode(project)
                    .expireWith(project)
                    .submit(AppExecutorUtil.getAppExecutorService())
                    .onSuccess(built -> mergeInferred(entry, built, started))
                    .onError(t -> {
                        entry.lastAbortAt = System.currentTimeMillis();
                        entry.inferredBuilding.set(false);
                    });
            } catch (RuntimeException e) {
                entry.lastAbortAt = System.currentTimeMillis();
                entry.inferredBuilding.set(false);
            }
        }
        if (onEdt) return;
        try {
            entry.inferredLatch.await(INDEX_WAIT_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 同步口径（测试/预热线程）跑一次全项目推断扫描并合并。 */
    private void runInferredScan(ReverseLookupCache entry) {
        long started = System.currentTimeMillis();
        Map<String, List<MatchedClass>> built;
        try {
            built = scanInferredTableIndex();
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw t;
            built = null;
        }
        mergeInferred(entry, built, started);
    }

    private void mergeInferred(ReverseLookupCache entry, Map<String, List<MatchedClass>> built, long startedMs) {
        try {
            if (built == null) return;
            if (project.getUserData(REVERSE_LOOKUP_CACHE_KEY) != entry) return;
            for (Map.Entry<String, List<MatchedClass>> e : built.entrySet()) {
                entry.index.putIfAbsent(e.getKey(), e.getValue());
            }
            entry.inferredScanDone = true;
            entry.inferredScanCount++;
            project.putUserData(INFERRED_INDEX_KEY, new structuresVersionPair(projectStructureVersion(), built));
        } finally {
            entry.inferredBuilding.set(false);
            entry.inferredLatch.countDown();
        }
    }

    /** 项目级「跨 ORM 回补结果」——同结构版本才有效。 */
    private Map<String, List<MatchedClass>> cachedInferredIndex() {
        structuresVersionPair cached = project.getUserData(INFERRED_INDEX_KEY);
        if (cached == null) return null;
        return cached.version == projectStructureVersion() ? cached.index : null;
    }

    /** 建全表索引：注解索引候选源 + 测试缝延迟。 */
    private Map<String, List<MatchedClass>> scanAllTablesIndex() {
        Long delay = indexScanDelayMsForTest;
        if (delay != null && delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return scanAnnotatedTableIndex();
    }

    /** 候选源＝平台注解索引（MP @TableName / JPA @Table），只取带注解的类。 */
    private Map<String, List<MatchedClass>> scanAnnotatedTableIndex() {
        Map<String, List<MatchedClass>> index = new LinkedHashMap<>();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        int scanned = 0;
        for (String simpleName : TableNameAnnotations.SIMPLE_NAMES) {
            Collection<PsiAnnotation> annotations;
            try {
                annotations = JavaAnnotationIndex.getInstance().getAnnotations(simpleName, project, scope);
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) throw t;
                annotations = List.of();
            }
            for (PsiAnnotation annotation : annotations) {
                if (scanned % LIBRARY_SWEEP_CANCEL_INTERVAL == 0) ProgressManager.checkCanceled();
                scanned++;
                String tableName = TableNameAnnotations.tableNameOf(annotation);
                if (tableName == null || tableName.isBlank()) continue;
                PsiClass clazz = PsiTreeUtil.getParentOfType(annotation, PsiClass.class);
                if (clazz == null) continue;
                if (clazz.isInterface() || clazz.isEnum() || clazz.isAnnotationType()) continue;
                if (isVoDto(clazz)) continue;
                String className = clazz.getName();
                if (className == null) continue;
                index.computeIfAbsent(tableName.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(new MatchedClass(className, clazz.getQualifiedName()));
            }
        }
        return index;
    }

    /** 退回口径：全项目短名遍历 + 逐类推断表名（跨 ORM：MP @TableName / JPA @Table 都认）。 */
    private Map<String, List<MatchedClass>> scanInferredTableIndex() {
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        Map<String, List<MatchedClass>> index = new LinkedHashMap<>();
        int scanned = 0;
        for (String shortName : cache.getAllClassNames()) {
            if (scanned % LIBRARY_SWEEP_CANCEL_INTERVAL == 0) ProgressManager.checkCanceled();
            scanned++;
            if (endsWithIgnoreCase(shortName, "VO") || endsWithIgnoreCase(shortName, "DTO")) continue;
            for (PsiClass clazz : cache.getClassesByName(shortName, scope)) {
                if (clazz.isInterface() || clazz.isEnum() || clazz.isAnnotationType()) continue;
                if (isVoDto(clazz)) continue;
                String className = clazz.getName();
                if (className == null) continue;
                String annotated = TableNameAnnotations.tableNameOfClass(clazz);
                String resolvedTable = annotated != null
                    ? annotated
                    : EntityResolver.inferDecoratedTableName(className, clazz.getProject());
                if (resolvedTable == null || resolvedTable.isBlank()) continue;
                index.computeIfAbsent(resolvedTable.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(new MatchedClass(className, clazz.getQualifiedName()));
            }
        }
        return index;
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

    /** 候选类名：纯驼峰 + Entity 后缀 + 去首段变体；去重。 */
    public List<String> candidateClassNames(String tableName) {
        String pascal = cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator.entityClassName(tableName);
        int idx = tableName.indexOf('_');
        String noFirstSeg = idx >= 0 ? tableName.substring(idx + 1) : tableName;
        noFirstSeg = cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator.entityClassName(noFirstSeg);
        Set<String> distinct = new LinkedHashSet<>(List.of(pascal, pascal + "Entity", noFirstSeg, noFirstSeg + "Entity"));
        return new ArrayList<>(distinct);
    }

    /** 项目级反查缓存（Project userData；失效基准＝项目结构版本 + TTL）。 */
    private static final class ReverseLookupCache {
        final long structureVersion;
        final long createdAt;
        final Map<String, List<MatchedClass>> index = new LinkedHashMap<>();
        volatile boolean built = false;
        final AtomicBoolean building = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);
        volatile long lastAbortAt = 0L;
        volatile boolean inferredScanDone = false;
        final AtomicBoolean inferredBuilding = new AtomicBoolean(false);
        final CountDownLatch inferredLatch = new CountDownLatch(1);
        int inferredScanCount = 0;
        int scans = 0;

        ReverseLookupCache(long structureVersion, long createdAt) {
            this.structureVersion = structureVersion;
            this.createdAt = createdAt;
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

    /** 项目级「跨 ORM 回补结果」缓存条目的版本包裹（结构版本 → 索引）。 */
    private static final class structuresVersionPair {
        final long version;
        final Map<String, List<MatchedClass>> index;

        structuresVersionPair(long version, Map<String, List<MatchedClass>> index) {
            this.version = version;
            this.index = index;
        }
    }

    /** 库扫描缓存以项目结构版本失效。 */
    private static final class LibrarySweepCache {
        final long count;
        final Map<String, List<MatchedClass>> tables;
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
    public static final int LIBRARY_SWEEP_CANCEL_INTERVAL = 64;
    public static final int LIBRARY_SWEEP_SLICE = 500;
    public static final int LIBRARY_SWEEP_SCAN_LIMIT = 200_000;
    public static final long LIBRARY_SWEEP_COOLDOWN_MS = 5_000L;
    public static Integer sweepScanLimitForTest = null;
    public static long sweepCooldownMs = LIBRARY_SWEEP_COOLDOWN_MS;
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

    /** 当前项目结构版本 + TTL 判定：命中则复用缓存，否则重建。 */
    private ReverseLookupCache reverseLookupCache() {
        long current = projectStructureVersion();
        long now = System.currentTimeMillis();
        ReverseLookupCache existing = project.getUserData(REVERSE_LOOKUP_CACHE_KEY);
        if (existing != null && existing.structureVersion == current
            && now - existing.createdAt >= 0 && now - existing.createdAt < reverseLookupTtlMs) {
            return existing;
        }
        ReverseLookupCache fresh = new ReverseLookupCache(current, now);
        project.putUserData(REVERSE_LOOKUP_CACHE_KEY, fresh);
        return fresh;
    }

    /** Kotlin companion object 兼容层。 */
    public static final class Companion {
        private Companion() {}

        public int scanCountForTest(Project project) {
            ReverseLookupCache cache = project.getUserData(REVERSE_LOOKUP_CACHE_KEY);
            return cache != null ? cache.scans : 0;
        }

        public kotlin.Pair<Integer, Integer> reverseLookupCacheInfo(Project project) {
            ReverseLookupCache entry = project.getUserData(REVERSE_LOOKUP_CACHE_KEY);
            if (entry == null) return new kotlin.Pair<>(0, 0);
            int total = 0;
            for (List<MatchedClass> classes : entry.index.values()) {
                total += classes.size();
            }
            return new kotlin.Pair<>(entry.index.size(), total);
        }

        public void clearReverseLookupCache(Project project) {
            project.putUserData(REVERSE_LOOKUP_CACHE_KEY, null);
            clearLibrarySweepCache(project);
        }

        public long reverseLookupCacheSizeBytes(Project project) {
            ReverseLookupCache entry = project.getUserData(REVERSE_LOOKUP_CACHE_KEY);
            if (entry == null) return 0L;
            long bytes = 0L;
            for (Map.Entry<String, List<MatchedClass>> e : entry.index.entrySet()) {
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

    // ---- 外层 static 转发器 ----

    public static int scanCountForTest(Project project) { return Companion.scanCountForTest(project); }

    public static kotlin.Pair<Integer, Integer> reverseLookupCacheInfo(Project project) {
        return Companion.reverseLookupCacheInfo(project);
    }

    public static void clearReverseLookupCache(Project project) { Companion.clearReverseLookupCache(project); }

    public static long reverseLookupCacheSizeBytes(Project project) { return Companion.reverseLookupCacheSizeBytes(project); }

    // ==================================================================
    // companion 常量 / 测试缝 / 访问器
    // ==================================================================

    /** 调用方等待索引建成的预算上限（毫秒）。 */
    public static final long INDEX_WAIT_BUDGET_MS = 800L;

    /** 扫描被撤销/失败后的冷却窗口（毫秒）。 */
    public static final long INDEX_RETRY_COOLDOWN_MS = 10_000L;

    /** 反查索引 TTL（毫秒），默认 5 分钟；测试置 0 强制下次查询重建。 */
    public static volatile long reverseLookupTtlMs = 300_000L;

    /** 测试缝：强制建索引走同步分支（null = 看 ApplicationManager.isUnitTestMode）。 */
    public static volatile Boolean syncIndexBuildForTest = null;

    /** 测试缝：给全量扫描注入人为延迟（毫秒）。 */
    public static volatile Long indexScanDelayMsForTest = null;

    private static final Key<java.util.concurrent.atomic.AtomicInteger> REVERSE_LOOKUP_SCAN_TOTAL_KEY =
        Key.create("mp-visualizer.reverseLookupScanTotal");

    private static final Key<ReverseLookupCache> REVERSE_LOOKUP_CACHE_KEY =
        Key.create("mp-visualizer.reverseLookupCache");

    private static final Key<structuresVersionPair> INFERRED_INDEX_KEY =
        Key.create("sqlvis.reverseLookup.inferredIndex");

    private static final String TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName";

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
}
