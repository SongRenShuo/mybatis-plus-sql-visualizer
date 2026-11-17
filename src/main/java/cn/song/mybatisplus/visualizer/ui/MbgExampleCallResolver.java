package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.xml.MbgCriterionMock;
import cn.song.mybatisplus.visualizer.xml.MockObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * MBG Example 调用点解析（T-MbgExamplePreview M2）。
 *
 * <p>MBG 生成器的 Example 用法固定为 {@code example.createCriteria().andCustIdEqualTo(v)} +
 * {@code mapper.selectByExample(example)}，而 andXxxYyy 的方法体固定是一句
 * {@code addCriterion("cust_id =", value, "custId")}。所以反推 Criterion 不需要猜列名：
 * 从 mapper 方法的调用点拿到 example 变量，在同一方法体里找 {@code and*} / {@code or*} 调用，
 * 再读对应方法的 addCriterion 第一个实参常量即可。</p>
 *
 * <p>产出直接喂给 {@code XmlPreviewModel.from} 的 collectionElements：
 * oredCriteria → 一个 Criteria 元素（valid=true + criteria 列表）；
 * criteria.criteria → 上述 Criterion 元素。</p>
 *
 * <p>全部失败路径返回 null（无 project / 接口不可解析 / 找不到调用点 / 方法体没有 addCriterion
 * 常量），上游据此退回升级前的「无属性元素」行为——不报错、不改变既有预览。</p>
 *
 * <p>T-StartupReadLockFreeze（S2/S3）预算语义、§14.8 就绪回调、§14.11 warm 自激防护、
 * D7 按条 Criteria 分组——口径详见各成员 KDoc，与 master 逐条对应。</p>
 */
public final class MbgExampleCallResolver {

    private static final Logger LOG = Logger.getInstance(MbgExampleCallResolver.class);

    /** MBG Criteria 的方法名：{@code and} / {@code or} + 大写驼峰首字母。 */
    private static final Pattern AND_OR_METHOD = Pattern.compile("^(and|or)[A-Z]\\w+$");

    /** 单次反推的 Criterion 上限（防御性：方法体里可能有大量链式调用）。 */
    private static final int MAX_CRITERIA = 20;

    // ------------------------------------------------------------------
    // T-StartupReadLockFreeze（S2/S3）：预取与预览解析的预算
    // ------------------------------------------------------------------

    /** Registry 总开关（plugin.xml registryKey）；关闭＝整段预取早退，预览不受影响。 */
    public static final String REGISTRY_KEY = "sqlvis.mbg.prefetch";

    /** 项目首次被本插件看到后的冷却窗口：避开索引构建与数据源加载窗口（S2 第 3 条）。 */
    public static final long PREFETCH_COOLDOWN_MS = 10_000L;

    /** 每项目每会话的预取检索次数上限（S2 第 2 条）。 */
    public static final int PREFETCH_BUDGET_PER_PROJECT = 32;

    /** 单次预取检索的硬预算：到点取消并记入失败记忆（S2 第 2 条）。 */
    public static final long PREFETCH_SEARCH_BUDGET_MS = 1_500L;

    /** 规模熔断阈值（S2 第 1 条）：检索耗时超过该值即本会话对该 namespace 熔断。 */
    public static final long SCALE_BREAKER_MS = 800L;

    /** 预览侧 resolve() 的单次预算（S3）：超预算返回 null，由调用方降级为占位骨架。 */
    public static final long RESOLVE_BUDGET_MS = 800L;

    /**
     * 预览首开触发的后台补齐预算（§14.8）。比预取的 1500ms 宽得多：它是在用户<b>已经看到占位骨架</b>
     * 之后跑的，不需要抢时间；真机 2500 表工程上一次检索 2.5s 级，1500ms 只会让它反复超时、
     * 缓存永远暖不起来。
     */
    public static final long WARM_BUDGET_MS = 10_000L;

    /** 同一语句最多挂几个「解析完成即重刷」监听（防反复打开堆积）。 */
    public static final int MAX_READY_LISTENERS = 8;

    /**
     * 缓存条目（T-PreviewOpenPerfM2 M1）：ReferencesSearch 是<b>全项目索引检索</b>，实测在最小规模
     * demo 上就占装配耗时的 78%（{@code [open-perf] m2=207 / total=266ms}），大工程按命中规模放大；
     * 而原实现每次 preview() 都重跑。key＝{@code namespace#statementId}，失效判据＝解析时实际读过的
     * 文件的 modificationStamp（mapper 接口 / Example 方法 / 调用点所在文件，任一改动即失效）。
     */
    public record CachedResolution(
            @NotNull Map<String, List<MockObject>> elements,
            @NotNull Map<VirtualFile, Long> stamps) {

        public CachedResolution {
            elements = Map.copyOf(elements);
            stamps = Map.copyOf(stamps);
        }
    }

    /** 一次检索的产物＋耗时（规模门与日志都要用真实检索耗时，不含等索引的时间）。 */
    private record SearchOutcome(@Nullable CachedResolution resolved, long elapsedMs) {
    }

    /** 预取决策结果；reason 同时进日志与测试断言。 */
    public record PrefetchDecision(boolean allowed, @Nullable String reason) {
    }

    /** 三态可替换函数形态（测试缝用）。 */
    @FunctionalInterface
    public interface LookupFunction {
        @Nullable
        Map<String, List<MockObject>> apply(@NotNull String key);
    }

    private static final Map<String, CachedResolution> CACHE = new ConcurrentHashMap<>();

    /** 预取失败记忆（本会话不再重试；预览路径不受影响）。 */
    private static final Set<String> PREFETCH_FAILED = ConcurrentHashMap.newKeySet();

    /** 每个项目第一次被本插件看到的时间：冷却窗口（PREFETCH_COOLDOWN_MS）的起点。 */
    private static final Map<String, Long> FIRST_SEEN_AT = new ConcurrentHashMap<>();

    /** 每项目每会话已消耗的预取预算（PREFETCH_BUDGET_PER_PROJECT）。 */
    private static final Map<String, Integer> BUDGET_USED = new ConcurrentHashMap<>();

    /** 规模熔断：检索耗时超过 SCALE_BREAKER_MS 的 namespace，本会话不再预取（S2 第 1 条）。 */
    private static final Set<String> CIRCUIT_OPEN_NAMESPACES = ConcurrentHashMap.newKeySet();

    /** T-StartupReadLockFreeze §14.8：M2 就绪回调（预览首开转异步后的自动重刷入口）。 */
    private static final Map<String, List<Runnable>> READY_LISTENERS = new ConcurrentHashMap<>();

    /** §14.11：已经启动过补齐的 key（同一缓存代只补一次）。 */
    private static final Set<String> WARM_ATTEMPTED = ConcurrentHashMap.newKeySet();

    /** 诊断/测试缝：缓存命中次数与真正执行检索的次数（单线程测试用，生产仅观测）。 */
    private static final AtomicInteger_Like CACHE_HITS = new AtomicInteger_Like();

    private static final AtomicInteger_Like SEARCH_RUNS = new AtomicInteger_Like();

    /** 测试缝：dumb 判定（生产＝DumbService.isDumb）。 */
    public static volatile Function<Project, Boolean> isDumbProvider = project -> {
        try {
            return DumbService.isDumb(project);
        } catch (Throwable t) {
            return false;
        }
    };

    /** 测试缝：项目是否初始化完成（平台 API 在项目打开早期为 false）。 */
    public static volatile Function<Project, Boolean> isProjectInitializedProvider = project -> {
        try {
            return project.isInitialized();
        } catch (Throwable t) {
            return false;
        }
    };

    /** 测试缝：Registry 总开关读取（生产＝Registry.is(REGISTRY_KEY, true)；key 未注册时直接返回默认值）。 */
    public static volatile BooleanSupplier prefetchEnabledProvider = () -> {
        try {
            return Registry.is(REGISTRY_KEY, true);
        } catch (Throwable t) {
            return true;
        }
    };

    /** 测试缝：冷缓存判定（平台测试里没有真实索引规模，用它模拟「一定未命中」）。生产恒为 null。 */
    @Nullable
    public static volatile LookupFunction cachedOnlyOverrideForTest = null;

    /** 测试缝：warm 调度的等待 key（模拟「已转后台」）；生产恒为 null。 */
    @Nullable
    public static volatile Function<String, String> warmOverrideForTest = null;

    /** Kotlin object 语义兼容实例。 */
    public static final MbgExampleCallResolver INSTANCE = new MbgExampleCallResolver();

    private MbgExampleCallResolver() {
    }

    /** 极简可变计数（本类缓存读数专用；生产仅观测）。 */
    private static final class AtomicInteger_Like {
        volatile int value;

        int get() {
            return value;
        }

        void set(int v) {
            value = v;
        }
    }

    /** 测试缝：清空缓存、计数与全部预取闸门状态。 */
    public static void resetCacheForTest() {
        CACHE.clear();
        CACHE_HITS.set(0);
        SEARCH_RUNS.set(0);
        PREFETCH_FAILED.clear();
        FIRST_SEEN_AT.clear();
        BUDGET_USED.clear();
        CIRCUIT_OPEN_NAMESPACES.clear();
        WARM_ATTEMPTED.clear();
        READY_LISTENERS.clear();
    }

    /** 测试缝读数。 */
    public static int getCacheHits() {
        return CACHE_HITS.get();
    }

    /** 测试缝读数。 */
    public static int getSearchRuns() {
        return SEARCH_RUNS.get();
    }

    /** 纯判定（单测入口）：缓存已热 → 不必补；本代已补过 → 不再补。 */
    public static boolean shouldStartWarm(boolean cacheFresh, boolean alreadyAttempted) {
        return !cacheFresh && !alreadyAttempted;
    }

    /**
     * 预览侧同步入口（S3）。
     *
     * <p>原实现是 ReadAction.compute——<b>阻塞读锁</b>，在几千文件的工程里一次检索可秒级持锁，
     * 撞上 EDT 的「写意图 → 写锁升级」就是计划 §三 的互等。现在改走
     * {@code ReadAction.nonBlocking { … }.inSmartMode(project)}：等索引交给平台在不持读锁的状态下
     * 等待（inSmartMode）；检索在应用线程池里跑，调用方只按 {@link #RESOLVE_BUDGET_MS} 有界等待，
     * 超预算取消并返回 null；调用方<b>已经</b>持读锁时（如 MCP 渲染链）不退化成后台等锁，
     * 就地嵌套读动作。</p>
     *
     * <p>返回值语义不变：解析不到 / 超预算 / 异常都返回 null。</p>
     */
    @Nullable
    public static Map<String, List<MockObject>> resolve(@Nullable Project project,
                                                        @Nullable String namespace,
                                                        @Nullable String statementId) {
        if (project == null || namespace == null || namespace.isBlank() || statementId == null) {
            return null;
        }
        // key 带 project 身份：本 object 是进程级，多项目窗口打开时同 namespace#statementId 会撞车
        String key = keyOf(project, namespace, statementId);
        Map<String, List<MockObject>> cached = cachedIfFresh(key);
        if (cached != null) {
            return cached;
        }
        long startedMs = System.currentTimeMillis();
        // T-StartupReadLockFreeze §14（2026-11-13 真机 m2=3219ms 实证）：预算必须**落在检索本身**，
        // 不能只落在「等多久」上——预览主链把整段装配包在 ReadAction.compute 里，
        // isReadAccessAllowed 恒为真，于是原来的「已持读锁→就地嵌套」分支绕开了
        // blockingGet(RESOLVE_BUDGET_MS)，冷态一次 ReferencesSearch 把 800ms 上限吃成 3.2s。
        // 现在两条分支共用同一个截止时刻，检索内部逐条引用自查。
        long deadlineAt = startedMs + RESOLVE_BUDGET_MS;
        SearchOutcome outcome;
        try {
            boolean readAllowed;
            try {
                readAllowed = ApplicationManager.getApplication() != null
                        && ApplicationManager.getApplication().isReadAccessAllowed();
            } catch (Throwable t) {
                readAllowed = false;
            }
            if (readAllowed) {
                outcome = ReadAction.compute(() ->
                        searchOnce(key, () -> resolveInReadAction(project, namespace, statementId, deadlineAt)));
            } else {
                outcome = resolveBounded(project, namespace, statementId, key, startedMs, deadlineAt);
            }
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) {
                throw t;
            }
            logSkip("resolve", "error:" + t.getClass().getSimpleName(), startedMs, key);
            return null;
        }
        CachedResolution resolved = outcome != null ? outcome.resolved() : null;
        if (resolved == null) {
            // 到点被截断要留痕（与「解析不到」区分），真机按 reason=deadline 检索
            if (System.currentTimeMillis() >= deadlineAt) {
                logSkip("resolve", "deadline", startedMs, key);
            }
            return null;
        }
        return storeResolved(key, resolved).elements();
    }

    /** S3 的有界路径：提交非阻塞读动作，只等 RESOLVE_BUDGET_MS（检索内部同时受 deadlineAt 约束）。 */
    @Nullable
    private static SearchOutcome resolveBounded(@NotNull Project project,
                                                @NotNull String namespace,
                                                @NotNull String statementId,
                                                @NotNull String key,
                                                long startedMs,
                                                long deadlineAt) {
        org.jetbrains.concurrency.CancellablePromise<SearchOutcome> promise =
                ReadAction.<SearchOutcome>nonBlocking(() ->
                        searchOnce(key, () -> resolveInReadAction(project, namespace, statementId, deadlineAt)))
                .inSmartMode(project)
                .expireWith(project)
                .submit(AppExecutorUtil.getAppExecutorService());
        try {
            return promise.blockingGet((int) RESOLVE_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException t) {
            promise.cancel();
            logSkip("resolve", "timeout", startedMs, key);
            return null;
        } catch (java.util.concurrent.ExecutionException t) {
            promise.cancel();
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            if (FatalErrors.isFatal(cause)) {
                if (cause instanceof RuntimeException r) throw r;
                if (cause instanceof Error e) throw e;
                throw new RuntimeException(cause);
            }
            logSkip("resolve", "error:" + cause.getClass().getSimpleName(), startedMs, key);
            return null;
        } catch (Throwable t) {
            promise.cancel();
            if (FatalErrors.isFatal(t)) {
                throw t;
            }
            logSkip("resolve", "error:" + t.getClass().getSimpleName(), startedMs, key);
            return null;
        }
    }

    /**
     * T-StartupReadLockFreeze §14.8：<b>只读缓存</b>，绝不触发检索。
     *
     * <p>预览首开用它决定「立即出占位骨架 + 转后台」，这是本单最后的形态：真机日志证明
     * ReferencesSearch 在超大工程上的开销藏在引擎内部（refsSeen 仅 13 条却 2.4s），逐条自查的
     * 截止判定只能在两次 next() 之间生效，救不了单次内部耗时。同步等它＝必然慢。</p>
     */
    @Nullable
    public static Map<String, List<MockObject>> resolveCachedOnly(@Nullable Project project,
                                                                  @Nullable String namespace,
                                                                  @Nullable String statementId) {
        if (project == null || namespace == null || namespace.isBlank() || statementId == null) {
            return null;
        }
        String key = keyOf(project, namespace, statementId);
        LookupFunction override = cachedOnlyOverrideForTest;
        if (override != null) {
            return override.apply(key);
        }
        return cachedIfFresh(key);
    }

    /**
     * 预览首开触发的后台补齐（§14.8）：<b>绕过</b>冷却 / 失败记忆 / 预算三道预取闸——
     * 这不是投机性预取，而是「用户已经看着占位骨架在等结果」的显式请求。
     *
     * @return 就绪监听的 key；null＝无需等待（已有新鲜缓存 / 无法调度）
     */
    @Nullable
    public static String warmForPreview(@Nullable Project project,
                                        @Nullable String namespace,
                                        @Nullable String statementId) {
        if (project == null || namespace == null || namespace.isBlank() || statementId == null) {
            return null;
        }
        String key = keyOf(project, namespace, statementId);
        // §14.11（2026-11-13 14:31 真机）：同一 key 同一缓存代只允许启动一次补齐。
        // 不在「补不到结果」这条路上设闸会自激：warm 无果 → fireReady → 面板重算 → 仍冷 → 再 warm，
        // 实测 3 秒内 10 轮、每轮都走完整 showResult（控制台准备 + exec 写 + 重排），
        // 用户看到的就是「一直不秒开」。缓存失效（文件改动）时才移除重试资格。
        // 判在测试缝之前：否则「第二次打开该不该再补」在单测里不可观测。
        if (!shouldStartWarm(cachedIfFresh(key) != null, WARM_ATTEMPTED.contains(key))) {
            LOG.info("[m2] warm skip reason=already-attempted key=" + key);
            return null;
        }
        WARM_ATTEMPTED.add(key);
        Function<String, String> override = warmOverrideForTest;
        if (override != null) {
            return override.apply(key);
        }
        boolean unitTest;
        try {
            unitTest = ApplicationManager.getApplication() != null
                    && ApplicationManager.getApplication().isUnitTestMode();
        } catch (Throwable t) {
            unitTest = false;
        }
        if (unitTest) {
            return null;
        }
        long deadlineAt = System.currentTimeMillis() + WARM_BUDGET_MS;
        LOG.info("[m2] warm start key=" + key);
        try {
            ReadAction.<CachedResolution>nonBlocking(() ->
                            resolveInReadAction(project, namespace, statementId, deadlineAt))
                    .inSmartMode(project)
                    .expireWith(project)
                    .coalesceBy(MbgExampleCallResolver.class, "warm:" + key)
                    .submit(AppExecutorUtil.getAppExecutorService())
                    .onSuccess(resolved -> {
                        if (resolved != null) {
                            storeResolved(key, resolved);
                        } else {
                            LOG.info("[m2] warm unresolved（占位保持） " + key);
                            fireReady(key);
                        }
                    })
                    .onError(t -> {
                        LOG.info("[m2] warm fail " + t.getClass().getSimpleName() + " key=" + key);
                        fireReady(key);
                    });
        } catch (RuntimeException e) {
            LOG.info("[m2] warm dispatch fail " + e.getClass().getSimpleName() + " key=" + key);
            // 调度就没成功：退回补齐资格，下次打开还能重试
            WARM_ATTEMPTED.remove(key);
            fireReady(key);
            return null;
        }
        return key;
    }

    /**
     * 注册「该 key 解析完成」的一次性回调；已有新鲜缓存则<b>立即</b>回调。
     *
     * <p>键来自 {@link #warmForPreview} 的返回值（预览结果里以 m2AwaitKey 透传），面板据此等落地再重算。</p>
     *
     * @return true＝已受理（缓存命中当场回调 / 已登记等待）；false＝超出 MAX_READY_LISTENERS
     */
    public static boolean whenResolved(@NotNull String key, @NotNull Runnable callback) {
        if (cachedIfFresh(key) != null) {
            callback.run();
            return true;
        }
        List<Runnable> list = READY_LISTENERS.computeIfAbsent(key,
                k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()));
        if (list.size() >= MAX_READY_LISTENERS) {
            LOG.info("[m2] whenResolved 超上限，丢弃 key=" + key);
            return false;
        }
        list.add(callback);
        return true;
    }

    /** 触发并清空该 key 的就绪回调（解析落地 / 确认无结果 / 失败三处都调，避免面板一直等）。 */
    private static void fireReady(@NotNull String key) {
        List<Runnable> list = READY_LISTENERS.remove(key);
        if (list == null) {
            return;
        }
        List<Runnable> snapshot;
        synchronized (list) {
            snapshot = List.copyOf(list);
        }
        for (Runnable callback : snapshot) {
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // 回调失败不打断其余监听
            }
        }
    }

    /**
     * 缓存外壳（<b>纯逻辑</b>，可单测）：命中且依赖文件未变则直接返回；否则执行 compute 并落缓存。
     *
     * <p>compute 返回 null 表示「解析不到」——<b>不写缓存</b>：把「当时索引没就绪 / 找不到调用点」
     * 永久固化下来会让下次打开永远拿不到（宁可比预期多跑一次）。</p>
     *
     * <p>T-StartupReadLockFreeze S1：拆成「新鲜判定 / 检索 / 落缓存」三段（cachedIfFresh /
     * searchOnce / storeResolved），预取的非阻塞回调只需复用后两段——本函数的行为保持不变。</p>
     */
    @Nullable
    static Map<String, List<MockObject>> resolveCached(@NotNull String key,
                                                       @NotNull Supplier<CachedResolution> compute) {
        Map<String, List<MockObject>> cached = cachedIfFresh(key);
        if (cached != null) {
            return cached;
        }
        SearchOutcome outcome = searchOnce(key, compute);
        CachedResolution resolved = outcome.resolved();
        if (resolved == null) {
            // 不写缓存：把「当时索引没就绪 / 找不到调用点」永久固化会让下次打开永远拿不到
            LOG.info("[m2] unresolved（不写缓存） " + key);
            return null;
        }
        return storeResolved(key, resolved).elements();
    }

    /** 新鲜判定 + 命中计数（缓存命中才自增 cacheHits）。 */
    @Nullable
    private static Map<String, List<MockObject>> cachedIfFresh(@NotNull String key) {
        CachedResolution cached = CACHE.get(key);
        if (cached == null) {
            return null;
        }
        if (!isFresh(cached)) {
            LOG.info("[m2] cache stale (依赖文件已改) " + key);
            // §14.11：依赖文件改了＝新的缓存代，恢复补齐资格，否则这次改动永远补不上
            WARM_ATTEMPTED.remove(key);
            return null;
        }
        CACHE_HITS.value++;
        LOG.info("[m2] cache hit " + describe(cached) + " " + key);
        return cached.elements();
    }

    /** 唯一自增 searchRuns 的地方：真正执行一次 PSI 检索并计时。 */
    private static SearchOutcome searchOnce(@NotNull String key, @NotNull Supplier<CachedResolution> compute) {
        SEARCH_RUNS.value++;
        long startedMs = System.currentTimeMillis();
        CachedResolution resolved = compute.get();
        return new SearchOutcome(resolved, System.currentTimeMillis() - startedMs);
    }

    /** 落缓存（唯一的写入口）。 */
    private static CachedResolution storeResolved(@NotNull String key, @NotNull CachedResolution resolved) {
        CACHE.put(key, resolved);
        LOG.info("[m2] resolved " + describe(resolved) + " " + key);
        // §14.8：预览首开在等这个信号（面板拿到后重算并刷新），预取成功也走这里
        fireReady(key);
        return resolved;
    }

    private static String describe(@NotNull CachedResolution entry) {
        List<MockObject> criteria = entry.elements().get("criteria.criteria");
        return "criteria=" + (criteria != null ? criteria.size() : 0)
                + " stamps=" + entry.stamps().size();
    }

    private static String keyOf(@NotNull Project project, @NotNull String namespace, @NotNull String statementId) {
        return project.getLocationHash() + "#" + namespace + "#" + statementId;
    }

    /** S6：跳过/放弃路径统一一条 info（{@code [m2] <stage> skip reason=… elapsedMs=…}），供真机取证。 */
    private static void logSkip(@NotNull String stage, @NotNull String reason, long startedMs, @NotNull String key) {
        LOG.info("[m2] " + stage + " skip reason=" + reason
                + " elapsedMs=" + (System.currentTimeMillis() - startedMs) + " key=" + key);
    }

    /**
     * 检索内部的截止判定（T-StartupReadLockFreeze §14）。
     *
     * <p>必须放在<b>检索本身</b>里，而不是调用方「等多久」上：预览主链整体包在 ReadAction.compute 内，
     * isReadAccessAllowed 恒为真，等待式预算（blockingGet）在那种上下文里根本走不到。
     * {@code Long.MAX_VALUE}（默认值）语义＝不设限，供纯逻辑单测与预取链复用。</p>
     */
    private static boolean isDeadlineHit(long deadlineAt) {
        return deadlineAt != Long.MAX_VALUE && System.currentTimeMillis() >= deadlineAt;
    }

    /** 测试缝：截止判定的纯逻辑入口（见 MbgExampleResolveDeadlineTest）。 */
    public static boolean isDeadlineHitForTest(long deadlineAt) {
        return isDeadlineHit(deadlineAt);
    }

    /**
     * 预取（T-PreviewOpenPerfM2 M11）：把 M2 检索挪到「用户还在看文件」的时候。
     *
     * <p>真机首开 {@code [open-perf] … m2=1628/2042ms}、{@code request→dialog 2714/4580ms}，
     * 而第二次起 m2≈0、弹窗 200ms 级——首开慢的全部就是这一次冷检索。gutter 渲染本来就要遍历
     * mapper 语句，顺手在后台跑一次即可让「点开」命中缓存；不改变任何交互，失败静默。</p>
     *
     * <p><b>T-StartupReadLockFreeze（S1/S2）</b>：原实现用 executeOnPooledThread + resolve() 的
     * <b>阻塞读锁</b>跑索引检索，并且在途集合是「全局单飞」（一条卡住拖死所有 key）。现在：
     * 检索走 nonBlocking + inSmartMode + expireWith + coalesceBy——等索引不持读锁、可被取消，
     * 同 key 单飞而不同 key 互不阻塞；三道闸（Registry 开关 / 项目就绪 / dumb / 冷却）与预算、
     * 规模熔断见 {@link #decidePrefetch}；单次硬预算到点取消该任务并记失败记忆；
     * <b>失败记忆</b>：解析不到（countByExample / deleteByExample 这类在本项目里根本没有调用点的
     * 语句就是常态）不写缓存，而 gutter 会反复重绘——不记失败就会变成每次重绘都重跑一次全项目
     * 检索（实测 6 秒内同一语句跑了 4 轮、日志刷屏）。记下 key 后本会话不再预取。
     * 单元测试模式不触发（避免后台线程干扰断言）。</p>
     */
    public static void prefetch(@Nullable Project project, @Nullable String namespace, @Nullable String statementId) {
        if (project == null || namespace == null || namespace.isBlank() || statementId == null) {
            return;
        }
        boolean unitTest;
        try {
            unitTest = ApplicationManager.getApplication() != null
                    && ApplicationManager.getApplication().isUnitTestMode();
        } catch (Throwable t) {
            unitTest = false;
        }
        if (unitTest) {
            return;
        }
        String key = keyOf(project, namespace, statementId);
        long startedMs = System.currentTimeMillis();
        PrefetchDecision decision = decidePrefetch(
                project.getLocationHash(), namespace, key,
                isProjectInitializedProvider.apply(project),
                isDumbProvider.apply(project),
                prefetchEnabledProvider.getAsBoolean(),
                startedMs);
        if (!decision.allowed()) {
            logSkip("prefetch", decision.reason() != null ? decision.reason() : "gate", startedMs, key);
            return;
        }
        submitPrefetch(project, namespace, statementId, key, startedMs);
    }

    /** 预取决策（放行 / 跳过原因）。纯函数（状态只在本类内），可在无平台环境单测。 */
    @NotNull
    static PrefetchDecision decidePrefetch(@NotNull String projectKey,
                                           @NotNull String namespace,
                                           @NotNull String key,
                                           boolean initialized,
                                           boolean dumb,
                                           boolean enabled,
                                           long nowMs) {
        // 冷却起点＝本插件第一次看到该项目的时刻（243 的 Project 没有「打开时间」API；
        // gutter 首次渲染与项目打开基本同期，用首次到达时间当代理）
        FIRST_SEEN_AT.putIfAbsent(projectKey, nowMs);
        if (!enabled) {
            return new PrefetchDecision(false, "registry-off");
        }
        if (!initialized) {
            return new PrefetchDecision(false, "project-not-initialized");
        }
        if (dumb) {
            return new PrefetchDecision(false, "dumb-mode");
        }
        Long firstSeen = FIRST_SEEN_AT.get(projectKey);
        if (firstSeen == null || nowMs - firstSeen < PREFETCH_COOLDOWN_MS) {
            return new PrefetchDecision(false, "cooldown");
        }
        if (CIRCUIT_OPEN_NAMESPACES.contains(namespace)) {
            return new PrefetchDecision(false, "scale-circuit-open");
        }
        if (PREFETCH_FAILED.contains(key)) {
            return new PrefetchDecision(false, "failed-before");
        }
        CachedResolution cached = CACHE.get(key);
        if (cached != null && isFresh(cached)) {
            return new PrefetchDecision(false, "cache-fresh");
        }
        int used = BUDGET_USED.getOrDefault(projectKey, 0);
        if (used >= PREFETCH_BUDGET_PER_PROJECT) {
            return new PrefetchDecision(false, "budget-exhausted");
        }
        BUDGET_USED.put(projectKey, used + 1);
        return new PrefetchDecision(true, null);
    }

    /**
     * 生产调度：非阻塞读动作 + 单次硬预算。
     *
     * <p>三条关键属性（对应现场两根栈）：检索不再由调用线程持阻塞读锁执行——inSmartMode 把
     * 「等索引」交给平台；超时 cancel() 由 nonBlocking 的进度检查传播进 ReferencesSearch，
     * 读锁随之释放；coalesceBy(this, key) 只合并同一个 key，卡住一条不再阻塞别的 key。</p>
     */
    private static void submitPrefetch(@NotNull Project project,
                                       @NotNull String namespace,
                                       @NotNull String statementId,
                                       @NotNull String key,
                                       long startedMs) {
        org.jetbrains.concurrency.CancellablePromise<SearchOutcome> promise =
                ReadAction.<SearchOutcome>nonBlocking(() ->
                        searchOnce(key, () -> resolveInReadAction(project, namespace, statementId, Long.MAX_VALUE)))
                .inSmartMode(project)
                .expireWith(project)
                .coalesceBy(MbgExampleCallResolver.class, key)
                .submit(AppExecutorUtil.getAppExecutorService());
        // 只有第一条结算路径生效（超时 / 成功 / 失败可能并发）
        AtomicBoolean settled = new AtomicBoolean(false);
        Runnable settleTimeout = () -> {
            if (settled.compareAndSet(false, true)) {
                if (!promise.isDone()) {
                    promise.cancel();
                }
                PREFETCH_FAILED.add(key);
                logSkip("prefetch", "timeout", startedMs, key);
            }
        };
        var timer = AppExecutorUtil.getAppScheduledExecutorService()
                .schedule(settleTimeout, PREFETCH_SEARCH_BUDGET_MS, TimeUnit.MILLISECONDS);
        promise.onSuccess(outcome -> {
            timer.cancel(false);
            if (settled.compareAndSet(false, true)) {
                onPrefetchDone(key, namespace, outcome, startedMs);
            }
        }).onError(error -> {
            timer.cancel(false);
            if (settled.compareAndSet(false, true)) {
                if (error instanceof java.util.concurrent.CancellationException
                        || error instanceof com.intellij.openapi.progress.ProcessCanceledException) {
                    // 项目关闭 / 被同 key 的新任务合并掉：不记失败，只留一条日志
                    logSkip("prefetch", "cancelled", startedMs, key);
                    return;
                }
                PREFETCH_FAILED.add(key);
                logSkip("prefetch", "error:" + error.getClass().getSimpleName(), startedMs, key);
            }
        });
    }

    /** 预取成功/解析不到的统一收口：落缓存或记失败，并按真实检索耗时决定是否熔断该 namespace。 */
    private static void onPrefetchDone(@NotNull String key,
                                       @NotNull String namespace,
                                       @NotNull SearchOutcome outcome,
                                       long startedMs) {
        CachedResolution resolved = outcome.resolved();
        if (resolved == null) {
            PREFETCH_FAILED.add(key);
            logSkip("prefetch", "unresolved", startedMs, key);
            return;
        }
        if (outcome.elapsedMs() >= SCALE_BREAKER_MS) {
            CIRCUIT_OPEN_NAMESPACES.add(namespace);
            LOG.info("[m2] prefetch circuit-open namespace=" + namespace + " searchMs=" + outcome.elapsedMs());
        }
        storeResolved(key, resolved);
    }

    /**
     * 测试缝：跑一遍与 {@link #prefetch} <b>同一套</b>决策链，放行时同步执行「检索 + 落缓存」。
     *
     * @return null＝已放行（并已执行一次检索）；非 null＝跳过原因
     */
    @Nullable
    public static String prefetchForTest(@NotNull String projectKey,
                                         @NotNull String namespace,
                                         @NotNull String statementId,
                                         boolean initialized,
                                         boolean dumb,
                                         boolean enabled,
                                         long nowMs,
                                         @NotNull Supplier<CachedResolution> search) {
        String key = projectKey + "#" + namespace + "#" + statementId;
        long startedMs = System.currentTimeMillis();
        PrefetchDecision decision = decidePrefetch(projectKey, namespace, key, initialized, dumb, enabled, nowMs);
        if (!decision.allowed()) {
            logSkip("prefetch", decision.reason() != null ? decision.reason() : "gate", startedMs, key);
            return decision.reason();
        }
        onPrefetchDone(key, namespace, searchOnce(key, search), startedMs);
        return null;
    }

    /** 依赖文件全部仍有效且 stamp 未变才算新鲜（不引文件监听，按需比 stamp）。 */
    private static boolean isFresh(@NotNull CachedResolution entry) {
        for (Map.Entry<VirtualFile, Long> e : entry.stamps().entrySet()) {
            VirtualFile file = e.getKey();
            if (!file.isValid() || file.getModificationStamp() != e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static CachedResolution resolveInReadAction(@NotNull Project project,
                                                        @NotNull String namespace,
                                                        @NotNull String statementId,
                                                        long deadlineAt) {
        StringBuilder trace = new StringBuilder();
        PsiClass mapperClass = JavaPsiFacade.getInstance(project)
                .findClass(namespace, GlobalSearchScope.allScope(project));
        if (mapperClass == null) {
            LOG.info("[m2] trace " + namespace + "#" + statementId + " mapperClass=NOT_FOUND");
            return null;
        }
        Map<VirtualFile, Long> stamps = new java.util.HashMap<>();
        PsiClass finalMapperClass = mapperClass;
        noteFile(stamps, finalMapperClass.getContainingFile());
        // T-PreviewOpenPerfM2 M10：检索范围收到「mapper 所在模块及其依赖它的模块」。
        // 引用方**必须**能看见该接口，所以这个范围在语义上不丢命中，却比全项目小得多——
        // 真机首次打开 m2=1628ms（253 处引用、冷索引），全项目作用域是大头。
        com.intellij.openapi.module.Module module = null;
        try {
            module = ModuleUtilCore.findModuleForPsiElement(mapperClass);
        } catch (RuntimeException ignored) {
            // 模块定位失败 → 全项目 scope
        }
        GlobalSearchScope scope = module != null
                ? GlobalSearchScope.moduleWithDependentsScope(module)
                : GlobalSearchScope.projectScope(project);
        trace.append("scope=").append(module != null ? module.getName() : "PROJECT").append(' ');
        // D7（真机 2026-11-12 15:00）：oredCriteria 必须按**每条 Criteria**分组，不能拍平成一条。
        // `example.createCriteria().andA()` 与 `example.or().andB()` 在 MBG 里是两条 Criteria，
        // Example_Where_Clause 用 separator="or" 连接——拍平就把 `( a ) or ( b )` 错拼成 `a and b`。
        Map<Object, List<MbgCriterionMock>> groups = new LinkedHashMap<>();
        List<PsiMethod> methods = List.of(mapperClass.findMethodsByName(statementId, true));
        trace.append("methods=").append(methods.size()).append(' ');
        for (PsiMethod method : methods) {
            if (isDeadlineHit(deadlineAt)) {
                trace.append("deadline ");
                continue;
            }
            noteFile(stamps, method.getContainingFile());
            PsiMethodCallExpression call = findCallSite(scope, method, deadlineAt);
            if (call == null) {
                trace.append("noCallSite ");
                continue;
            }
            noteFile(stamps, call.getContainingFile());
            String exampleName = exampleVariableName(call);
            if (exampleName == null) {
                String argText = call.getArgumentList().getExpressions().length > 0
                        ? call.getArgumentList().getExpressions()[0].getText() : "";
                trace.append("noExampleArg(arg=")
                        .append(argText, 0, Math.min(40, argText.length()))
                        .append(") ");
                continue;
            }
            PsiCodeBlock body = PsiTreeUtil.getParentOfType(call, PsiCodeBlock.class);
            if (body == null) {
                trace.append("noBody ");
                continue;
            }
            Map<Object, List<MbgCriterionMock>> found = collectCriteria(project, body, exampleName, trace);
            int criteriaCount = 0;
            for (List<MbgCriterionMock> list : found.values()) {
                criteriaCount += list.size();
            }
            trace.append('[').append(exampleName).append(':').append(criteriaCount)
                    .append('/').append(found.size()).append("] ");
            for (Map.Entry<Object, List<MbgCriterionMock>> entry : found.entrySet()) {
                groups.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
            }
        }
        List<MbgCriterionMock> flat = new ArrayList<>();
        for (List<MbgCriterionMock> list : groups.values()) {
            flat.addAll(list);
        }
        LOG.info("[m2] trace " + namespace + "#" + statementId + " " + trace
                + " -> criteria=" + flat.size() + " groups=" + groups.size());
        if (flat.isEmpty()) {
            return null;
        }
        List<MockObject> criteriaObjects = new ArrayList<>();
        for (List<MbgCriterionMock> list : groups.values()) {
            criteriaObjects.add(MbgCriterionMock.criteriaOf(list));
        }
        List<MockObject> flatObjects = new ArrayList<>();
        for (MbgCriterionMock mock : flat) {
            flatObjects.add(mock.toMockObject());
        }
        return new CachedResolution(
                Map.of("oredCriteria", criteriaObjects, "criteria.criteria", flatObjects),
                stamps);
    }

    private static void noteFile(Map<VirtualFile, Long> stamps, @Nullable PsiFile file) {
        if (file != null && file.getVirtualFile() != null && file.getVirtualFile().isValid()) {
            stamps.put(file.getVirtualFile(), file.getVirtualFile().getModificationStamp());
        }
    }

    /**
     * mapper 方法在本项目里的<b>首个可用调用点</b>。
     *
     * <p>T-PreviewOpenPerfM2 M5 的 findFirst() 在真机大工程上返回 null（同一方法 Find Usages 有
     * 253 处引用、索引健康）——Query.findFirst() 在这条检索链上不可靠。改为 forEach 取首个命中
     * （语义等价、仍是提前终止，不把全项目引用收成 List）。</p>
     *
     * <p>D6（真机 2026-11-12 14:46，mybatis-plus-demo 的 CtCustmanagerRelMapper#selectByExample）：
     * <b>命中不等于能当调用点用</b>。ReferencesSearch 对接口方法会把「实现的声明处 / 方法引用」
     * 一起返回，这类 ref 的 element 上溯没有 PsiMethodCallExpression——取第一个 ref 就直接放弃，
     * trace 里就是 noCallSite，而项目里明明有两个真实调用点。现在改为跳过不可用的 ref 继续找。</p>
     */
    @Nullable
    private static PsiMethodCallExpression findCallSite(@NotNull GlobalSearchScope scope,
                                                        @NotNull PsiMethod method,
                                                        long deadlineAt) {
        PsiMethodCallExpression[] site = {null};
        int[] refsSeen = {0};
        boolean[] deadlineHit = {false};
        ReferencesSearch.search(method, scope).forEach(candidate -> {
            refsSeen[0]++;
            PsiMethodCallExpression call =
                    PsiTreeUtil.getParentOfType(candidate.getElement(), PsiMethodCallExpression.class);
            if (isDeadlineHit(deadlineAt)) {
                // 逐条引用自查截止时刻：这是整条 M2 里最贵的一段（全项目/模块索引检索），
                // 大工程上没有这个检查就只能等它自己跑完（真机 30s 档）。到点即停，按「解析不到」降级。
                deadlineHit[0] = true;
                return false;
            }
            if (call == null) {
                return true; // 声明处 / 方法引用：继续找下一个
            }
            site[0] = call;
            return false;
        });
        if (site[0] == null) {
            // 原实现此处再跑一次 ReferencesSearch.search(...).findAll() 只为数 totalRefs——
            // 恰是「没有调用点」的高频失败路径，等于把最贵的一次检索翻倍；refsSeen 已是扫描条数
            //（提前终止时 site 非空，不走本分支），故直接用它，不再二次检索。
            LOG.info("[m2] findCallSite empty method=" + method.getName()
                    + " refsSeen=" + refsSeen[0] + " deadlineHit=" + deadlineHit[0]);
        }
        return site[0];
    }

    /** 调用实参的变量名（{@code selectByExample(example)} → example）。 */
    @Nullable
    private static String exampleVariableName(@NotNull PsiMethodCallExpression call) {
        var expressions = call.getArgumentList().getExpressions();
        if (expressions.length == 0 || !(expressions[0] instanceof PsiReferenceExpression ref)) {
            return null;
        }
        return ref.getReferenceName();
    }

    /** 方法体内所有以 example 为根的 {@code and*} / {@code or*} 调用。 */
    @NotNull
    private static Map<Object, List<MbgCriterionMock>> collectCriteria(@NotNull Project project,
                                                                       @NotNull PsiCodeBlock body,
                                                                       @NotNull String exampleName,
                                                                       @Nullable StringBuilder trace) {
        // D7：键＝这条条件所属的 Criteria 节点（createCriteria() / or() 调用，或别名变量的初始化表达式）；
        // 同一 Criteria 的多条条件落在同一组，不同 Criteria 之间由 Example_Where_Clause 的 or 连接。
        Map<Object, List<MbgCriterionMock>> groups = new LinkedHashMap<>();
        int matched = 0;
        int noResolve = 0;
        int notExample = 0;
        int notRooted = 0;
        int noCondition = 0;
        int total = 0;
        for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class)) {
            if (total >= MAX_CRITERIA) {
                break;
            }
            String name = call.getMethodExpression().getReferenceName();
            if (name == null || !AND_OR_METHOD.matcher(name).matches()) {
                continue;
            }
            matched++;
            PsiMethod method = call.resolveMethod();
            if (method == null) {
                noResolve++;
                continue;
            }
            if (!isExampleCriteria(method)) {
                notExample++;
                continue;
            }
            if (!rootedAtExample(call.getMethodExpression().getQualifierExpression(), exampleName)) {
                notRooted++;
                continue;
            }
            String condition = addCriterionCondition(project, method);
            if (condition == null) {
                noCondition++;
                continue;
            }
            Object groupKey = criteriaGroupKey(call, exampleName) != null
                    ? criteriaGroupKey(call, exampleName) : exampleName;
            List<String> values = new ArrayList<>();
            for (PsiExpression argument : call.getArgumentList().getExpressions()) {
                values.add(argument.getText());
            }
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>())
                    .add(new MbgCriterionMock(condition, values, MbgCriterionMock.kindOf(name)));
            total++;
        }
        if (trace != null) {
            trace.append("(and/or=").append(matched).append(" noResolve=").append(noResolve)
                    .append(" notExample=").append(notExample).append(" notRooted=").append(notRooted)
                    .append(" noCond=").append(noCondition).append(") ");
        }
        return groups;
    }

    /**
     * D7：一条 {@code and*} / {@code or*} 条件归属哪条 Criteria。
     *
     * <p>链上最靠近根的 createCriteria() / or() 调用就是分界线——
     * {@code example.createCriteria().andA().andB()} 的两条都归同一节点，
     * {@code example.or().andC()} 归另一个节点。分步别名
     * （{@code Criteria c = example.createCriteria(); c.andA()}）用变量的初始化表达式当键。
     * 认不出时返回 null，调用方按「同一个 example」兜底成一组。</p>
     */
    @Nullable
    private static Object criteriaGroupKey(@NotNull PsiMethodCallExpression call, @NotNull String exampleName) {
        PsiExpression cur = call.getMethodExpression().getQualifierExpression();
        while (cur instanceof PsiMethodCallExpression methodCall) {
            String name = methodCall.getMethodExpression().getReferenceName();
            if ("createCriteria".equals(name) || "or".equals(name)) {
                return methodCall;
            }
            cur = methodCall.getMethodExpression().getQualifierExpression();
        }
        if (!(cur instanceof PsiReferenceExpression ref)) {
            return null;
        }
        if (exampleName.equals(ref.getReferenceName())) {
            return null;
        }
        PsiVariable variable;
        try {
            variable = ref.resolve() instanceof PsiVariable resolved ? resolved : null;
        } catch (RuntimeException e) {
            variable = null;
        }
        if (variable == null) {
            return null;
        }
        try {
            return variable.getInitializer();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 方法是否属于 MBG 的 {@code XxxExample}（或其内部类 Criteria）。 */
    private static boolean isExampleCriteria(@NotNull PsiMethod method) {
        PsiClass owner = method.getContainingClass();
        if (owner == null) {
            return false;
        }
        String ownerName = owner.getName();
        if (ownerName != null && ownerName.endsWith("Example")) {
            return true;
        }
        PsiClass outer = owner.getContainingClass();
        return outer != null && outer.getName() != null && outer.getName().endsWith("Example");
    }

    /**
     * 调用链的根是否指向<b>本次</b> selectByExample 实参那个 example 变量。
     *
     * <p>覆盖两种写法：链式 {@code example.createCriteria().andXxx(v)}（qualifier 最左端是 example 引用）
     * 与分步 {@code Criteria c = example.createCriteria(); c.andXxx(v)}（根的<b>初始化表达式</b>追溯到
     * example）。</p>
     *
     * <p>为什么不能按类型名收（E2E 验收缺陷 D4）：同一方法体里常有多个 Example 实例
     * （MBG 生成的服务实现就是 exampleNA / exampleNB 各一条链），按「变量类型以 Example 结尾」收会把
     * <b>另一次查询</b>的条件也并进来——真机产物变成
     * {@code WHERE (status = ? AND cust_name LIKE ? AND tenant_id = ? AND create_time BETWEEN ? AND tenant_id = ?)}，
     * 两个 Example 的 AND 条件被拼成一条永不成立的谓词。所以只认<b>同一对象</b>：变量名相同，或该变量的
     * 初始化表达式追溯后仍落在同一个 example 变量上。</p>
     */
    public static boolean rootedAtExample(@Nullable PsiExpression expr, @NotNull String exampleName) {
        PsiReferenceExpression ref = rootReference(expr);
        if (ref == null) {
            return false;
        }
        if (exampleName.equals(ref.getReferenceName())) {
            return true;
        }
        PsiVariable variable;
        try {
            variable = ref.resolve() instanceof PsiVariable resolved ? resolved : null;
        } catch (RuntimeException e) {
            variable = null;
        }
        if (variable == null) {
            return false;
        }
        PsiExpression initializer;
        try {
            initializer = variable.getInitializer();
        } catch (RuntimeException e) {
            initializer = null;
        }
        if (initializer == null) {
            return false;
        }
        PsiReferenceExpression initRef = rootReference(initializer);
        return initRef != null && exampleName.equals(initRef.getReferenceName());
    }

    /** 把 {@code a.b().c()} 这类链剥到最左端的引用表达式（a）。 */
    @Nullable
    private static PsiReferenceExpression rootReference(@Nullable PsiExpression expr) {
        PsiExpression cur = expr;
        while (cur instanceof PsiMethodCallExpression methodCall) {
            cur = methodCall.getMethodExpression().getQualifierExpression();
        }
        return cur instanceof PsiReferenceExpression ref ? ref : null;
    }

    /** {@code andXxxYyy} 方法体里 {@code addCriterion("<condition>", ...)} 的第一个实参常量。 */
    @Nullable
    private static String addCriterionCondition(@NotNull Project project, @NotNull PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return null;
        }
        PsiMethodCallExpression call = PsiTreeUtil.findChildOfType(body, PsiMethodCallExpression.class);
        if (call == null) {
            return null;
        }
        if (!"addCriterion".equals(call.getMethodExpression().getReferenceName())) {
            return null;
        }
        var expressions = call.getArgumentList().getExpressions();
        if (expressions.length == 0) {
            return null;
        }
        Object constant;
        try {
            constant = JavaPsiFacade.getInstance(project).getConstantEvaluationHelper()
                    .computeConstantExpression(expressions[0]);
        } catch (RuntimeException e) {
            return null;
        }
        return constant instanceof String s ? s : null;
    }
}
