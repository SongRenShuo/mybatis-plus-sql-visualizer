// T-SyncK2JMaster482 波D2 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ParamHistoryCache.kt
// （R22 T-04 命名方案 + T-DevAcceptFix2 ①按键激活指针 + F49/F50 增量同步）
// 自 master 同文件 T-M19-StorageClear + F54 增量移植（桶外粗锁 + 存储代际 + 应用级清理）
// T-SyncK2JMaster496 自 master 同文件 T-PresetMethodScope/T-PresetMethodBinding 增量移植
//（computeKey 第三维 scope + 旧键回落 + 方案×方法最近保存键指针表及其持久化）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.log.LogSqlModelsKt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * P-05：参数历史缓存——LRU 淘汰 + 持久化。
 *
 * <p>同一实体类的查询在不同预览间复用上次填的参数值（按 {@code 表名 + SQL 模板指纹} 缓存）。</p>
 * <ul>
 *   <li>内存缓存：{@link LinkedHashMap} accessOrder=true（LRU），运行时快速读写。</li>
 *   <li>持久化：{@link PluginSettings.State#getParamHistoryJson()}（JSON 字符串），IDE 重启后恢复。</li>
 *   <li>缓存上限 {@link #MAX_HISTORY_SIZE} = 50 条（LRU 淘汰），单条无 TTL。</li>
 * </ul>
 *
 * <p>序列化/反序列化用自研最小 JSON（避免引入新依赖）；格式：
 * <pre>[{"key":"tbl:abcdef...",{"source1":"val1","source2":"val2"}}]</pre>
 *
 * <p>R22 T-04（命名方案，D2 定案）：存储结构从「单方案」升级「多方案」——</p>
 * <ul>
 *   <li><b>默认方案</b>（{@link #DEFAULT_SCHEME}）：既有自动召回链路的数据落点，仍持久化到
 *    {@code PluginSettings.State.paramHistoryJson}（旧字段语义不变——降级兼容 + 存量零迁移）；</li>
 *   <li><b>命名方案</b>：方案名 → (缓存键 → 快照)，持久化到 {@code paramSchemesJson}；激活方案落
 *    {@code paramActiveScheme}。</li>
 * </ul>
 * <p>旧 {@code paramHistoryJson} 数据在恢复时自动成为默认方案内容（**D2：旧数据自动成为「默认方案」零迁移损失**），
 * 老用户无感。{@code save}/{@code recall} 按激活方案路由——未触碰方案的调用方（含既有自动保存/召回链）行为逐字节不变。</p>
 */
public class ParamHistoryCache {

    /** 缓存上限（LRU 淘汰）。 */
    public static final int MAX_HISTORY_SIZE = 50;

    /** F49：命名方案个数上限（防方案集无界膨胀；达上限后新建另存被拒，存量操作不受影响）。 */
    public static final int MAX_SCHEME_COUNT = 20;

    /**
     * F49 计数缝（测试用）：非空时 {@link #persistToStorage}（全量序列化）每次回调一次。
     * 「switch 只写指针」守卫=switchScheme 前后计数不变；摘掉修复（switch 回走全量）→ 计数+1 → 红。
     */
    public static volatile Runnable persistProbe = null;

    /** R22 T-04：默认方案内部名（空串——用户方案名恒非空不冲突；UI 显示名经 bundle「默认方案」本地化）。 */
    public static final String DEFAULT_SCHEME = "";

    /** R22 T-04：命名方案持久化 key（IDE：PluginSettings.State.paramSchemesJson；测试：静态存储 key）。 */
    private static final String SCHEMES_STORAGE_KEY = "paramSchemes";

    /** R22 T-04：激活方案持久化 key（IDE：PluginSettings.State.paramActiveScheme；测试：静态存储 key）。 */
    private static final String ACTIVE_SCHEME_STORAGE_KEY = "paramActiveScheme";

    /** T-DevAcceptFix2 ①：按键激活指针的测试环境静态存储键（IDE 环境走 PluginSettings 同名字段）。 */
    private static final String ACTIVE_BY_KEY_STORAGE_KEY = "paramActiveSchemeByKey";

    /** T-PresetMethodBinding：方案×方法最近保存键的测试环境静态存储键（IDE 环境走同名字段）。 */
    private static final String RECENT_KEY_STORAGE_KEY = "paramSchemeRecentKey";

    /**
     * T-PresetMethodScope：声明作用域在缓存键里的分隔符。
     *
     * <p>选 {@code |@} 而不是单字符：旧键形如 {@code tableName:<hex>} / {@code sql:<hex>}，
     * 表名与指纹都不会含本串；回落用 {@link #legacyKeyOf} 一次切分即可拿回旧键，不需要解析结构。</p>
     */
    public static final String SCOPE_SEPARATOR = "|@";

    /** 持久化 key（IDE 环境：PluginSettings.State.paramHistoryJson 字段；测试环境：静态存储 key）。 */
    private static final String STORAGE_KEY = "paramHistory";

    /**
     * 测试环境回落静态存储（无 IDE 服务时保证持久化往返可测）。
     * IDE 环境不使用本存储（走 PluginSettings）。
     */
    private static final Map<String, String> fallbackStorage = new HashMap<>();

    /** T-M19-StorageClear：参数存储写与应用级清理共用互斥，保证「清理返回后旧写不复活」。 */
    private static final Object STORAGE_MUTATION_LOCK = new Object();

    /** 应用级存储代际；每次确认清理递增。 */
    private static final java.util.concurrent.atomic.AtomicLong STORAGE_GENERATION =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 存活缓存实例弱集合：设置页清理时立即清掉已打开 SqlPreviewPanel 的内存桶；弱引用不延长
     * 面板生命周期。所有访问均在 {@link #STORAGE_MUTATION_LOCK} 内。
     */
    private static final Set<ParamHistoryCache> LIVE_INSTANCES =
            Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * 设置页「清除参数历史与命名方案」的唯一入口。
     *
     * <p>仅清 {@link PluginSettings.State} 中四个参数字段与测试回落存储；共享 XML 中语言、日志、外观等
     * 其他组件状态原样保留，绝不删除 mp-sql-visualizer.xml 文件。</p>
     */
    public static void clearPersistedHistory() {
        clearPersistedHistory(null);
    }

    /** 实例 clear 用于测试隔离/显式复位：发起实例可立即按新代际继续使用；其他已打开实例保持失效。 */
    private static void clearPersistedHistory(ParamHistoryCache origin) {
        synchronized (STORAGE_MUTATION_LOCK) {
            long nextGeneration = STORAGE_GENERATION.incrementAndGet();
            for (ParamHistoryCache instance : new ArrayList<>(LIVE_INSTANCES)) {
                instance.invalidateForClear();
            }
            if (origin != null) origin.observedStorageGeneration = nextGeneration;

            Throwable stateFailure = null;
            try {
                PluginSettings.State state = PluginSettings.getInstance().getState();
                state.setParamHistoryJson("");
                state.setParamSchemesJson("");
                state.setParamActiveScheme(DEFAULT_SCHEME);
                state.setParamActiveSchemeByKey("");
                state.setParamSchemeRecentKey("");
            } catch (Throwable t) {
                stateFailure = t;
            }
            if (stateFailure != null) {
                com.intellij.openapi.application.Application app =
                        com.intellij.openapi.application.ApplicationManager.getApplication();
                if (app == null || !app.isUnitTestMode()) {
                    if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(stateFailure)) {
                        throw (Error) stateFailure;
                    }
                    // master 的 stateWrite.getOrThrow() 直译：runCatching 捕获的异常原样重抛
                    throw new RuntimeException(stateFailure);
                }
            }
            synchronized (fallbackStorage) {
                fallbackStorage.put(STORAGE_KEY, "");
                fallbackStorage.put(SCHEMES_STORAGE_KEY, "");
                fallbackStorage.put(ACTIVE_SCHEME_STORAGE_KEY, DEFAULT_SCHEME);
                fallbackStorage.put(ACTIVE_BY_KEY_STORAGE_KEY, "");
                fallbackStorage.put(RECENT_KEY_STORAGE_KEY, "");
            }
        }
    }

    private final int maxSize;

    /** LRU 内存缓存（accessOrder=true：访问顺序，最近访问在尾部）。默认方案（R22 T-04）的数据落点。 */
    private final LinkedHashMap<String, Map<String, String>> cache;

    /**
     * F54（D31 道 B，性能十八轮）：<b>桶外粗锁</b>——保全部 accessOrder LinkedHashMap 桶
     * （默认桶 {@link #cache} 与命名桶）跨线程结构一致。
     *
     * <p>为什么不是 CHM/读写锁（性能十九轮对上轮建议的收敛定案）：accessOrder=true 的
     * <b>读即回写</b>（get 触发 afterNodeAccess 改链）——CHM 只保桶级原子但丢 LRU 语义、
     * 读写锁的读锁内含结构回写（「读锁」名不副实）。粗锁＝单把互斥锁包全部桶操作
     * （含持久化序列化的桶遍历快照），自动召回链争用面＝每操作一次无竞争 synchronized
     * （~15-20ns 量级），换结构一致 + LRU 序不漂移。顶层 {@link #schemes} 的 CHM/COWAL（F50）
     * 不在锁内——桶引用的搬运（建桶/换名/删除）自身线程安全，桶<b>内容</b>操作才需要锁。</p>
     */
    private final Object bucketLock = new Object();

    /**
     * R22 T-04：命名方案存储——方案名 → (缓存键 → 参数快照)（逐方案 LRU）。
     *
     * <p>F50（T-PerfQuick5）：顶层结构 **ConcurrentHashMap 化**——后台 pooled 写回
     * （{@link #saveForScheme}，SqlPreviewPanel 写回线程）与 EDT 读（{@link #schemeNames}/对话框渲染器）
     * 此前共用裸 LinkedHashMap 无任何同步保护，结构写与遍历并发即 CME/丢条目面。内层逐方案 LRU（accessOrder）
     * 维持原样——与默认方案 {@link #cache} 同族既知形态，本轮在案不动。</p>
     */
    private final ConcurrentHashMap<String, LinkedHashMap<String, Map<String, String>>> schemes = new ConcurrentHashMap<>();

    /**
     * F50：命名方案**创建序**索引——CHM 无序，而方案清单的「创建序」是 UI 既有语义
     * （对话框列表/下拉顺序，ParamHistoryCacheTest 创建序断言在案）。序列化顺序也按本索引
     * 走（{@link #serializeSchemesToJson}），持久化往返创建序不漂移。复制写清单（读多写少、
     * 写仅限方案操作与惰性建桶）。
     */
    private final CopyOnWriteArrayList<String> schemeOrder = new CopyOnWriteArrayList<>();

    /**
     * T-DevAcceptFix2 ①（20261006 第二轮验收）：激活方案**按缓存键（=方法/SQL）记录**。
     *
     * <p>原实现为全局单值——预设名清单与激活指针全局共享，用户实测「所有的方法都是一套预设」。
     * 现：本表按缓存键记激活名（键=方法/SQL，与快照同分桶）；{@link #legacyActiveScheme} 承接旧持久化
     * 字段 {@code paramActiveScheme}（全局单值）作为**未记录键的回落值**，且仅在「该名字在当前键下确有条目」
     * 时才生效（否则回落默认方案）——迁移安全（V92）。</p>
     */
    private final ConcurrentHashMap<String, String> activeSchemeByKey = new ConcurrentHashMap<>();

    /**
     * T-PresetMethodBinding：方案 × 声明作用域 → 该预设在该方法下**最近一次保存**的完整键。
     *
     * <p>用途与「改 SQL 不丢」：同一方法里改了 SQL（键的 SQL 指纹段变化），精确键未命中时按本表
     * 找回上次那份快照。与 {@link #activeSchemeByKey} 同层（都是指针，不存内容），随命名方案一起持久化。
     * 顶层 CHM + 内层 CHM：写只在保存路径（持 {@link #STORAGE_MUTATION_LOCK}），读在召回路径
     * （{@link #bucketLock} 内探测）。</p>
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> recentKeyBySchemeScope =
            new ConcurrentHashMap<>();

    /**
     * 旧全局单值激活方案（持久化兼容 + 未记录键的回落；语义见 {@link #activeSchemeFor}）。
     * F54 微（性能十八轮）：volatile——EDT 写 / pooled 读窗口窄但真实（写未发布时
     * 读侧可能长眼看旧值），引用写原子性由 volatile 发布补齐。
     */
    private volatile String legacyActiveScheme = DEFAULT_SCHEME;

    /**
     * T-M19-StorageClear：本实例已同步到的应用级存储代际。
     *
     * <p>设置页清理会递增全局代际并在同一互斥区清空所有存活实例；异步写回必须携带取快照时
     * 的代际，代际不一致即拒绝，防止「用户确认清理 → 旧面板 500ms/pooled 写回迟到」把明文
     * 参数重新写进 mp-sql-visualizer.xml。</p>
     */
    private volatile long observedStorageGeneration = 0L;

    public ParamHistoryCache() {
        this(MAX_HISTORY_SIZE);
    }

    public ParamHistoryCache(int maxSize) {
        this.maxSize = maxSize;
        this.cache = new LinkedHashMap<>(maxSize + 1, 0.75f, true);
        // 注册、取代际与持久化恢复必须和应用级清理串行：否则可能在清理刚写空后又恢复旧字段。
        synchronized (STORAGE_MUTATION_LOCK) {
            this.observedStorageGeneration = STORAGE_GENERATION.get();
            LIVE_INSTANCES.add(this);
            restoreFromPersistence();
        }
    }

    /**
     * 计算缓存键（R22 T-01，D1=A 定案）：有表名 = {@code tableName:fingerprint}（存量兼容）；
     * **无表名 = {@code sql:} + SQL 文本规范化指纹**（dedupeKey 同源口径——{@code normalizeSqlWhitespace}
     * + {@code sqlFingerprint}），纯文本/自定义 DAO 预览的调试参数不再丢失。
     *
     * <p>T-PresetMethodScope：第三维 {@code scope}（声明作用域 = 项目身份 + 相对路径 + 方法签名）非空时
     * 追加 {@code |@<scope>}——同一段 SQL 写在两个方法里时，键不再相同，预设与参数历史按方法隔离。
     * scope 为空返回旧格式，既有调用点与存量持久化数据逐字节不变。</p>
     *
     * @param tableName 表名（null 表示纯文本预览——R22 起以规范化指纹为键持久化）
     * @param rawSql    原始 SQL（用于计算指纹）
     * @return 缓存键（恒非 null——rawSql 空串也有指纹；调用方仍可按需判空）
     */
    public String computeKey(String tableName, String rawSql) {
        return computeKey(tableName, rawSql, null);
    }

    /**
     * @param scope 声明作用域（{@code cn.song.mybatisplus.visualizer.psi.PreviewOriginScope} 产出；null = 无方法上下文）
     */
    public String computeKey(String tableName, String rawSql, String scope) {
        String legacy;
        if (tableName != null && !tableName.isEmpty()) {
            legacy = tableName + ":" + fingerprint(rawSql);
        } else {
            String normalized = LogSqlModelsKt.normalizeSqlWhitespace(rawSql);
            legacy = "sql:" + LogSqlModelsKt.sqlFingerprint(normalized);
        }
        if (scope == null || scope.isBlank()) return legacy;
        return legacy + SCOPE_SEPARATOR + scope.replace(SCOPE_SEPARATOR, "|_");
    }

    /**
     * T-PresetMethodScope：带 scope 键 → 旧键（无 scope）。存量数据（升级前写入）全部挂在旧键上，
     * 读路径据此回落，避免升级后预设「消失」。
     */
    public String legacyKeyOf(String key) {
        int idx = key.indexOf(SCOPE_SEPARATOR);
        return idx < 0 ? key : key.substring(0, idx);
    }

    /** T-PresetMethodBinding：键里的声明作用域（无 scope 键返回 null）。 */
    public String scopeOf(String key) {
        int idx = key.indexOf(SCOPE_SEPARATOR);
        return idx >= 0 ? key.substring(idx + SCOPE_SEPARATOR.length()) : null;
    }

    /**
     * T-PresetMethodBinding：记录「该方案在该方法下最近一次保存的键」。无 scope 键（日志/历史
     * 双击链路）不记——它们没有方法归属，回落目标只对方法键有意义。调用方须持 {@link #bucketLock}。
     */
    private void recordRecentKeyLocked(String scheme, String key) {
        String scope = scopeOf(key);
        if (scope == null) return;
        recentKeyBySchemeScope
                .computeIfAbsent(scheme, k -> new ConcurrentHashMap<>())
                .put(scope, key);
    }

    /**
     * T-PresetMethodBinding：**改 SQL 不丢**的召回入口——按当前键的作用域找该预设（当前激活方案）
     * 最近一次保存的快照。
     *
     * <p>与 {@link #recall} 的分工：{@code recall} 只做精确键 + 旧键回落（语义是「同一条 SQL 的快照」）；
     * 本方法才跨 SQL 形态，返回的是「这个方法上次用的参数」。面板据此只恢复参数值与 FieldFill，
     * 结构态（勾选/foreach）不参与——Wrapper 条件 id 是按解析序递增的，跨 SQL 套用会错位。</p>
     *
     * @return 回落快照；无指针 / 指针键就是精确键或旧键（已由 recall 覆盖）/ 桶内已淘汰 → null
     */
    public Map<String, String> recallRecent(String key) {
        String scope = scopeOf(key);
        if (scope == null) return null;
        String legacy = legacyKeyOf(key);
        synchronized (bucketLock) {
            String active = activeSchemeFor(key);
            ConcurrentHashMap<String, String> scopeKeys = recentKeyBySchemeScope.get(active);
            String recentKey = scopeKeys != null ? scopeKeys.get(scope) : null;
            if (recentKey == null) return null;
            if (recentKey.equals(key) || recentKey.equals(legacy)) return null;
            LinkedHashMap<String, Map<String, String>> entries =
                    DEFAULT_SCHEME.equals(active) ? cache : schemes.get(active);
            if (entries == null) return null;
            Map<String, String> hit = entries.get(recentKey);
            if (hit != null) enforceMaxSize(entries);
            return hit;
        }
    }

    /**
     * 保存参数值到缓存（LRU 淘汰 + 持久化）。
     *
     * <p>R22 T-04：按**激活方案**路由（未触碰方案的调用方 = 默认方案，行为不变）。
     * 线程池延迟落盘场景（快照与写入可能跨方案切换）请用 {@link #saveForScheme} 显式带方案名。</p>
     *
     * @param key    缓存键（由 {@link #computeKey} 生成）
     * @param params 参数映射（source → value）
     */
    public void save(String key, Map<String, String> params) {
        long generation = generationToken();
        saveForScheme(activeSchemeFor(key), key, params, generation);
    }

    /**
     * R22 T-04：把快照写入**指定方案**——调用方取快照时的方案名随行落库，
     * 防止「EDT 取快照 → 线程池写回」窗口内用户切换方案导致的串桶。
     *
     * <p>T-M19-StorageClear：异步调用方必须传取快照时的 {@link #generationToken()}。设置页清理与写入
     * 共用 {@link #STORAGE_MUTATION_LOCK}；清理后到达的旧代际任务直接拒绝，不得复活已清参数。</p>
     *
     * @param scheme 目标方案名（空串 = 默认方案）
     * @param key    缓存键
     * @param params 参数映射（source → value）
     * @return 是否实际写入；空快照或代际已失效返回 false
     */
    public boolean saveForScheme(String scheme, String key, Map<String, String> params) {
        return saveForScheme(scheme, key, params, generationToken());
    }

    public boolean saveForScheme(String scheme, String key, Map<String, String> params, long expectedGeneration) {
        if (params == null || params.isEmpty()) return false;
        synchronized (STORAGE_MUTATION_LOCK) {
            if (expectedGeneration != STORAGE_GENERATION.get() || observedStorageGeneration != expectedGeneration) {
                return false;
            }
            // F54：桶内容写 + 淘汰同一临界区（entries 捕获一次——旧实现两次 entriesForScheme 查表顺带收敛）
            synchronized (bucketLock) {
                LinkedHashMap<String, Map<String, String>> entries = entriesForScheme(scheme);
                entries.put(key, params);
                enforceMaxSize(entries);
                // T-PresetMethodBinding：记下「该方案在该方法下最近保存的键」，供改 SQL 后回落
                recordRecentKeyLocked(scheme, key);
            }
            persistToStorage();
            return true;
        }
    }

    /**
     * 召回历史参数值（R22 T-04 起按激活方案路由）。
     *
     * @param key 缓存键
     * @return 历史参数映射，未命中返回 null
     */
    public Map<String, String> recall(String key) {
        // F54：accessOrder 读即回写 LRU 序——读也在桶外粗锁临界区内（这是读写锁被否的根因）
        synchronized (bucketLock) {
            String active = activeSchemeFor(key);
            LinkedHashMap<String, Map<String, String>> entries =
                    DEFAULT_SCHEME.equals(active) ? cache : schemes.get(active);
            if (entries == null) return null;
            // T-PresetMethodScope：精确键优先，未命中回落旧键（升级前的存量快照）
            Map<String, String> value = entries.get(key);
            if (value == null) value = entries.get(legacyKeyOf(key));
            if (value != null) {
                // 访问后 LinkedHashMap 自动更新顺序（accessOrder=true），无需手动处理
                enforceMaxSize(entries);
            }
            return value;
        }
    }

    /**
     * 清空参数历史（全部存活实例 + 持久化字段）。R22 T-04：含全部命名方案与激活方案复位。
     * T-M19-StorageClear：这是应用级失效动作，不再只清调用实例；仅把 PluginSettings 中四个参数
     * 历史字段写空，绝不删除共享的 mp-sql-visualizer.xml，也不触碰其中其他设置组件。
     */
    public void clear() {
        clearPersistedHistory(this);
    }

    /** 当前实例的存储代际令牌；异步写回须在 EDT 取快照时一并捕获。 */
    public long generationToken() {
        return observedStorageGeneration;
    }

    /** R92: old open panels must not export cleared simulation values into fresh snapshots. */
    public boolean isCurrentGeneration() {
        return observedStorageGeneration == STORAGE_GENERATION.get();
    }

    /**
     * 新预览开始时接纳最新代际。showResult 已先清空并重建面板参数状态，因此此时不再携带清理前
     * 的 UI 快照；接纳后允许保存新输入。清理发生后仍停留在原预览的面板保持旧代际，任何写回均拒绝。
     */
    public void adoptCurrentGenerationForFreshPreview() {
        synchronized (STORAGE_MUTATION_LOCK) {
            observedStorageGeneration = STORAGE_GENERATION.get();
        }
    }

    /** 清空本实例的全部桶；调用方须持 {@link #STORAGE_MUTATION_LOCK}。代际不在此更新，以封住旧面板后续写回。 */
    private void invalidateForClear() {
        synchronized (bucketLock) {
            cache.clear();
            schemes.clear();
            schemeOrder.clear();
            activeSchemeByKey.clear();
            recentKeyBySchemeScope.clear();
            legacyActiveScheme = DEFAULT_SCHEME;
        }
    }

    // ---------------- R22 T-04：命名方案 API（D2 定案——旧数据自动成为默认方案） ----------------

    /**
     * 命名方案清单（默认方案除外，创建序）——**全量**名单（序列化遍历用）。
     * F50：创建序由 {@link #schemeOrder} 承载；CHM 直查键拼接在尾部（极窄竞态窗内「已建桶未入序」
     * 的条目不丢），distinct 去重。
     * F54 微（性能十八轮 中间分配）：原「序表 + 键集拼接再 distinct」先拼中间 List 再去重二次分配——
     * 改 {@link #orderedSchemeNames()} 一次成型。
     */
    public List<String> schemeNames() {
        return orderedSchemeNames();
    }

    /**
     * 创建序方案名清单（一次成型，无中间 List）：序表先入、CHM 直查键补尾，HashSet 去重。
     * 语义与旧「schemeOrder + schemes.keys distinct」逐值等价。
     */
    private List<String> orderedSchemeNames() {
        if (schemeOrder.isEmpty()) return new ArrayList<>(schemes.keySet());
        Set<String> seen = new java.util.HashSet<>(schemeOrder.size() + schemes.size());
        List<String> out = new ArrayList<>(schemeOrder.size() + schemes.size());
        for (String name : schemeOrder) {
            if (seen.add(name)) out.add(name);
        }
        for (String name : schemes.keySet()) {
            if (seen.add(name)) out.add(name);
        }
        return out;
    }

    /**
     * T-DevAcceptFix2 ①：**指定缓存键（=方法/SQL）可见**的命名方案清单（创建序）——
     * UI（面板下拉/管理对话框）只列「在本方法下确有条目」的预设，达成「每个方法一套预设、互不干扰」。
     * key 为 null/空（无预览上下文）→ 空表（只有默认方案）。
     * F54：桶级 containsKey 探测入临界区（读侧与桶扩容/结构写互斥）。
     *
     * <p>T-PresetMethodScope：带 scope 键没有直接条目时回落旧键（升级前的存量预设），并把命中的
     * 快照**惰性复制**到新键——首次打开该方法后两侧各自独立，旧预设不再持续共享。</p>
     */
    public List<String> schemeNamesFor(String key) {
        if (key == null || key.isEmpty()) return List.of();
        String legacy = legacyKeyOf(key);
        List<String> names = orderedSchemeNames();
        List<String> direct = new ArrayList<>();
        synchronized (bucketLock) {
            for (String name : names) {
                LinkedHashMap<String, Map<String, String>> entries = schemes.get(name);
                if (entries != null && entries.containsKey(key)) direct.add(name);
            }
        }
        if (!direct.isEmpty() || legacy.equals(key)) return direct;
        List<String> fallback = new ArrayList<>();
        synchronized (bucketLock) {
            for (String name : names) {
                LinkedHashMap<String, Map<String, String>> entries = schemes.get(name);
                if (entries != null && entries.containsKey(legacy)) fallback.add(name);
            }
        }
        if (!fallback.isEmpty()) migrateLegacyFor(key, legacy, fallback);
        return fallback;
    }

    /**
     * T-PresetMethodScope：把 {@code names} 各方案在旧键 {@code legacy} 下的快照复制到带 scope 键 {@code key}。
     * 只在「新键无条目、旧键有条目」时发生（{@link #schemeNamesFor} 回落分支），每个方法至多一次；
     * 复制后两侧独立编辑，不再互相覆盖。激活指针随迁（仅当新键尚无指针）。
     */
    private void migrateLegacyFor(String key, String legacy, List<String> names) {
        synchronized (STORAGE_MUTATION_LOCK) {
            if (observedStorageGeneration != STORAGE_GENERATION.get()) return;
            boolean migrated = false;
            synchronized (bucketLock) {
                for (String name : names) {
                    LinkedHashMap<String, Map<String, String>> entries = schemes.get(name);
                    if (entries == null) continue;
                    if (entries.containsKey(key)) continue;
                    Map<String, String> snapshot = entries.get(legacy);
                    if (snapshot == null) continue;
                    entries.put(key, snapshot);
                    enforceMaxSize(entries);
                    recordRecentKeyLocked(name, key);
                    migrated = true;
                }
                if (migrated && !activeSchemeByKey.containsKey(key)) {
                    String legacyPointer = activeSchemeByKey.get(legacy);
                    if (legacyPointer != null) activeSchemeByKey.put(key, legacyPointer);
                }
            }
            if (migrated) persistToStorage();
        }
    }

    /**
     * T-DevAcceptFix2 ①：指定缓存键的激活方案名。取值链：按键记录 → 旧全局值回落 → 默认方案；
     * 回落值仅在「该名字在该键下确有条目」时生效（否则默认方案）——迁移安全（V92）。
     * key 为 null/空 → 默认方案。F54：桶级探测入临界区。
     *
     * <p>T-PresetMethodScope：带 scope 键未记录指针时，先回落同一条 SQL 的旧键指针，再回落全局值。</p>
     */
    public String activeSchemeFor(String key) {
        if (key == null || key.isEmpty()) return DEFAULT_SCHEME;
        String legacy = legacyKeyOf(key);
        String scope = scopeOf(key);
        String stored = activeSchemeByKey.get(key);
        if (stored == null) stored = activeSchemeByKey.get(legacy);
        if (stored == null) stored = legacyActiveScheme;
        if (DEFAULT_SCHEME.equals(stored)) return DEFAULT_SCHEME;
        boolean hasEntry;
        synchronized (bucketLock) {
            LinkedHashMap<String, Map<String, String>> entries = schemes.get(stored);
            if (entries == null) {
                hasEntry = false;
            } else if (entries.containsKey(key) || entries.containsKey(legacy)) {
                hasEntry = true;
            } else {
                // T-PresetMethodBinding：改了 SQL 的键在桶里没有条目，但指针指向该方案在本方法下最近
                // 保存的键——仍视为「该方案在此键下有效」，否则回落链会在 activeSchemeFor 处断掉。
                String recentKey = null;
                if (scope != null) {
                    ConcurrentHashMap<String, String> scopeKeys = recentKeyBySchemeScope.get(stored);
                    recentKey = scopeKeys != null ? scopeKeys.get(scope) : null;
                }
                hasEntry = recentKey != null && entries.containsKey(recentKey);
            }
        }
        return hasEntry ? stored : DEFAULT_SCHEME;
    }

    /** 是否存在指定命名方案。 */
    public boolean hasScheme(String name) {
        return schemes.containsKey(name);
    }

    /**
     * 旧全局激活方案名（**回落值视图**，数据模型层测试用）——T-DevAcceptFix2 ① 起 UI 一律走
     * {@link #activeSchemeFor}（按键）。本视图返回 {@link #legacyActiveScheme}（每次切换/另存都会同步更新，
     * 语义＝「最近一次激活的方案名」）。生产侧调用点已全部迁移，禁在新代码中使用。
     */
    public String activeSchemeName() {
        return legacyActiveScheme;
    }

    /**
     * 单参切换（**无键上下文**，数据模型层测试用）——只更新回落值，不建按键记录。
     * 生产侧（面板/对话框）一律走两参形态。
     */
    public void switchScheme(String name) {
        switchScheme(name, null);
    }

    /**
     * 切换指定缓存键的激活方案（面板下拉驱动）。未知方案名忽略（防脏态）；同名幂等不重复落盘。
     *
     * <p>F49（T-PerfQuick5）：切换**只写激活指针**（{@link #persistActiveSchemePointer}），不再走
     * {@link #persistToStorage} 全量序列化——方案内容未变，重序列化全部命名方案 + 无条件重写
     * legacy 字段属纯写放大，且发生在 EDT 同步路径。
     * T-DevAcceptFix2 ①：指针按键记录（key 空 = 无预览上下文，仅更新回落值不建记录）。</p>
     */
    public void switchScheme(String name, String key) {
        synchronized (STORAGE_MUTATION_LOCK) {
            if (observedStorageGeneration != STORAGE_GENERATION.get()) return;
            if (!DEFAULT_SCHEME.equals(name) && !schemes.containsKey(name)) return;
            if (key == null || key.isEmpty()) {
                legacyActiveScheme = name;
                persistActiveSchemePointer();
                return;
            }
            String current = activeSchemeByKey.get(key);
            if (current != null && current.equals(name) && legacyActiveScheme.equals(name)) return;
            activeSchemeByKey.put(key, name);
            legacyActiveScheme = name;
            persistActiveSchemePointer();
        }
    }

    /**
     * 另存为命名方案：把 {@code params} 作为 {@code key} 的快照写入 {@code name}（该键下已存在同名条目则覆盖），
     * 并把该键激活切到 {@code name}。默认方案名/空名/空快照返回 {@link SchemeOpResult#INVALID}（调用方
     * 此前静默守卫的显式化——对话框在更早的输入环节已拦，行为不变）；方案名个数达
     * {@link #MAX_SCHEME_COUNT} 上限返回 {@link SchemeOpResult#LIMIT_REACHED}（F49）。
     *
     * <p>T-DevAcceptFix2 ①：查重口径从「名字全局唯一」改为「**该键下**是否已有同名条目」——
     * 同名预设可在不同方法下各自存在、各存各值（D71 推荐案；重命名/删除维持名字级）。</p>
     */
    public SchemeOpResult saveAsScheme(String name, String key, Map<String, String> params) {
        synchronized (STORAGE_MUTATION_LOCK) {
            if (observedStorageGeneration != STORAGE_GENERATION.get()) return SchemeOpResult.INVALID;
            if (name == null || name.isBlank() || DEFAULT_SCHEME.equals(name) || params == null || params.isEmpty()) {
                return SchemeOpResult.INVALID;
            }
            if (!schemes.containsKey(name) && schemes.size() >= MAX_SCHEME_COUNT) {
                return SchemeOpResult.LIMIT_REACHED;
            }
            // F54：桶内容写 + 淘汰入临界区（CHM 原子建桶后桶内 put/淘汰仍是裸结构操作）
            synchronized (bucketLock) {
                LinkedHashMap<String, Map<String, String>> entries = entriesForScheme(name);
                entries.put(key, params);
                enforceMaxSize(entries);
                // T-PresetMethodBinding：另存为同时刷新「最近保存键」指针
                recordRecentKeyLocked(name, key);
            }
            activeSchemeByKey.put(key, name);
            legacyActiveScheme = name;
            persistToStorage();
            return SchemeOpResult.OK;
        }
    }

    /**
     * 召回指定方案的 {@code key} 快照（方案不存在/键未命中返回 null）。F54：get 读即回写 → 临界区。
     * T-PresetMethodScope：带 scope 键未命中回落旧键（存量快照）。
     */
    public Map<String, String> recallFromScheme(String name, String key) {
        synchronized (bucketLock) {
            LinkedHashMap<String, Map<String, String>> entries = schemes.get(name);
            if (entries == null) return null;
            Map<String, String> hit = entries.get(key);
            return hit != null ? hit : entries.get(legacyKeyOf(key));
        }
    }

    /**
     * T-PresetMethodScope 测试缝：指定方案在指定键下**直接**有条目（不含旧键回落）。
     * 惰性迁移的判据——迁移前带 scope 键无直接条目，迁移后才有。
     */
    public boolean schemeHasDirectEntry(String name, String key) {
        synchronized (bucketLock) {
            LinkedHashMap<String, Map<String, String>> entries = schemes.get(name);
            return entries != null && entries.containsKey(key);
        }
    }

    /** T-PresetMethodScope 测试缝：默认方案桶在指定键下是否直接有条目（不含旧键回落）。 */
    public boolean hasDirectEntry(String key) {
        synchronized (bucketLock) {
            return cache.containsKey(key);
        }
    }

    /** 指定方案当前条目数（缓存键数，测试/对话框摘要用）。 */
    public int schemeEntryCount(String name) {
        synchronized (bucketLock) {
            LinkedHashMap<String, Map<String, String>> entries = schemes.get(name);
            return entries != null ? entries.size() : 0;
        }
    }

    /** R22 T-04：方案操作结果（对话框状态行分流）。F49 增 LIMIT_REACHED（个数上限）与 INVALID（入参不合规）。 */
    public enum SchemeOpResult { OK, DUPLICATE, NOT_FOUND, DEFAULT_PROTECTED, LIMIT_REACHED, INVALID }

    /**
     * 重命名方案（激活指针随迁）。
     * F50：CHM 化后不再整表重建——remove+put 保序（创建序由 {@link #schemeOrder} 原位换名承载），
     * 语义与旧 LinkedHashMap 重建逐值等价（NOT_FOUND/DUPLICATE 判定次序不变）。
     */
    public SchemeOpResult renameScheme(String oldName, String newName) {
        synchronized (STORAGE_MUTATION_LOCK) {
            if (observedStorageGeneration != STORAGE_GENERATION.get()) return SchemeOpResult.INVALID;
            if (DEFAULT_SCHEME.equals(oldName)) return SchemeOpResult.DEFAULT_PROTECTED;
            LinkedHashMap<String, Map<String, String>> entries = schemes.get(oldName);
            if (entries == null) return SchemeOpResult.NOT_FOUND;
            if (newName == null || newName.isBlank() || DEFAULT_SCHEME.equals(newName)
                    || schemes.containsKey(newName)) {
                return SchemeOpResult.DUPLICATE;
            }
            schemes.remove(oldName);
            schemes.put(newName, entries);
            int idx = schemeOrder.indexOf(oldName);
            if (idx >= 0) {
                schemeOrder.remove(idx);
                schemeOrder.add(idx, newName);
            }
            // T-DevAcceptFix2 ①：重命名维持**名字级**（D71 定案）——按键指针与回落值随迁
            activeSchemeByKey.entrySet().removeIf(e -> oldName.equals(e.getValue()));
            synchronized (bucketLock) {
                for (String k : new ArrayList<>(activeSchemeByKey.keySet())) {
                    if (schemes.get(newName).containsKey(k)) activeSchemeByKey.put(k, newName);
                }
            }
            if (legacyActiveScheme.equals(oldName)) legacyActiveScheme = newName;
            persistToStorage();
            return SchemeOpResult.OK;
        }
    }

    /** 删除方案（激活方案被删时归位默认方案）。T-DevAcceptFix2 ①：按键指针与回落值同步摘除。 */
    public SchemeOpResult deleteScheme(String name) {
        synchronized (STORAGE_MUTATION_LOCK) {
            if (observedStorageGeneration != STORAGE_GENERATION.get()) return SchemeOpResult.INVALID;
            if (DEFAULT_SCHEME.equals(name)) return SchemeOpResult.DEFAULT_PROTECTED;
            if (schemes.remove(name) == null) return SchemeOpResult.NOT_FOUND;
            schemeOrder.remove(name);
            activeSchemeByKey.entrySet().removeIf(e -> name.equals(e.getValue()));
            if (legacyActiveScheme.equals(name)) legacyActiveScheme = DEFAULT_SCHEME;
            persistToStorage();
            return SchemeOpResult.OK;
        }
    }

    /** 当前缓存条目数（默认方案；测试断言用）。 */
    public int size() {
        synchronized (bucketLock) {
            return cache.size();
        }
    }

    /**
     * 是否包含指定键（该键的激活方案；测试断言用，触发 LRU 顺序更新）。
     * T-PresetMethodScope：带 scope 键未命中回落旧键。
     */
    public boolean containsKey(String key) {
        synchronized (bucketLock) {
            LinkedHashMap<String, Map<String, String>> entries = entriesForScheme(activeSchemeFor(key));
            return entries.containsKey(key) || entries.containsKey(legacyKeyOf(key));
        }
    }

    /**
     * R22 T-04：指定方案的条目视图（默认方案 = {@link #cache}；命名方案惰性建，同 LRU 语义）。
     * F50：CHM 原子建桶（computeIfAbsent，原 getOrPut 非原子），建桶同时补创建序（幂等）。
     */
    private LinkedHashMap<String, Map<String, String>> entriesForScheme(String scheme) {
        if (DEFAULT_SCHEME.equals(scheme)) return cache;
        LinkedHashMap<String, Map<String, String>> entries =
                schemes.computeIfAbsent(scheme, k -> newSchemeEntries());
        schemeOrder.addIfAbsent(scheme);
        return entries;
    }

    /** R22 T-04：新建方案条目映射（与默认方案同 LRU 形态）。 */
    private LinkedHashMap<String, Map<String, String>> newSchemeEntries() {
        return new LinkedHashMap<>(maxSize + 1, 0.75f, true);
    }

    /** LRU 淘汰：{@code entries} 超过 maxSize 时移除最久未访问的条目（头部）。 */
    private void enforceMaxSize(LinkedHashMap<String, Map<String, String>> entries) {
        while (entries.size() > maxSize) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
        }
    }

    /**
     * 全量持久化到 PluginSettings（IDE 环境）：命名方案 + 激活方案入新字段，默认方案继续写旧字段（降级兼容）；
     * 测试环境回落静态存储。F49 计数缝：{@link #persistProbe} 非空时回调一次（基准/守卫测试数「全量序列化次数」）。
     * F54：两段桶遍历序列化（{@link #serializeSchemesToJson}/{@link #serializeToJson}）在 {@link #bucketLock}
     * 临界区内取<b>一致性快照</b>（pooled 写回与持久化并发时不再有 CME 面）；存储写入
     * （PluginSettings/fallbackStorage）留在锁外——锁只保桶结构，不背 IO。
     */
    private void persistToStorage() {
        Runnable probe = persistProbe;
        if (probe != null) probe.run();
        String schemesJson;
        String legacyJson;
        synchronized (bucketLock) {
            schemesJson = serializeSchemesToJson();
            legacyJson = serializeToJson(cache);
        }
        String active = legacyActiveScheme;
        String activeByKey = serializeActiveByKey();
        // T-PresetMethodBinding：指针表随方案集合一起落盘（内容未变时也重序列化，量小可忽略）
        String recentKeys = serializeRecentKeys();
        boolean written;
        try {
            PluginSettings.State state = PluginSettings.getInstance().getState();
            state.setParamSchemesJson(schemesJson);
            state.setParamActiveScheme(active);
            state.setParamActiveSchemeByKey(activeByKey);
            state.setParamSchemeRecentKey(recentKeys);
            written = true;
        } catch (Throwable t) {
            written = false;
        }
        if (!written) {
            // 测试环境（无 IDE 服务）：回落静态存储，保证持久化往返可测
            synchronized (fallbackStorage) {
                fallbackStorage.put(SCHEMES_STORAGE_KEY, schemesJson);
                fallbackStorage.put(ACTIVE_SCHEME_STORAGE_KEY, active);
                fallbackStorage.put(ACTIVE_BY_KEY_STORAGE_KEY, activeByKey);
                fallbackStorage.put(RECENT_KEY_STORAGE_KEY, recentKeys);
            }
        }
        // 旧字段保持既有语义：默认方案快照继续写 paramHistoryJson（旧版构建可读，存量零迁移的另一面）
        boolean legacyWritten;
        try {
            PluginSettings.getInstance().getState().setParamHistoryJson(legacyJson);
            legacyWritten = true;
        } catch (Throwable t) {
            legacyWritten = false;
        }
        if (!legacyWritten) {
            synchronized (fallbackStorage) {
                fallbackStorage.put(STORAGE_KEY, legacyJson);
            }
        }
    }

    /**
     * F49：只写激活方案指针——{@code switchScheme} 专用轻量落盘路径，不做方案集/legacy 的
     * 全量重序列化（内容未变，重写属 EDT 同步路径上的纯写放大）。
     */
    private void persistActiveSchemePointer() {
        String active = legacyActiveScheme;
        String activeByKey = serializeActiveByKey();
        boolean written;
        try {
            PluginSettings.State state = PluginSettings.getInstance().getState();
            state.setParamActiveScheme(active);
            state.setParamActiveSchemeByKey(activeByKey);
            written = true;
        } catch (Throwable t) {
            written = false;
        }
        if (!written) {
            synchronized (fallbackStorage) {
                fallbackStorage.put(ACTIVE_SCHEME_STORAGE_KEY, active);
                fallbackStorage.put(ACTIVE_BY_KEY_STORAGE_KEY, activeByKey);
            }
        }
    }

    /**
     * T-DevAcceptFix2 ①：按键激活指针序列化（{@code {"缓存键":"方案名", ...}}，最小 JSON 形态；
     * 复用 {@link #escapeJson}/{@link #unescapeJson} 与 {@link #splitTopLevelEntries} 切分工具）。
     */
    private String serializeActiveByKey() {
        if (activeSchemeByKey.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        // 键排序后遍历（对齐 F50 口径）：CHM 遍历序为哈希序，不排序会使持久化 JSON 序漂移
        List<String> keys = new ArrayList<>(activeSchemeByKey.keySet());
        Collections.sort(keys);
        for (String k : keys) {
            String v = activeSchemeByKey.get(k);
            if (v == null) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(k)).append("\":\"").append(escapeJson(v)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /** T-DevAcceptFix2 ①：按键激活指针反序列化（损坏/空 → 空表，不崩溃）。 */
    private Map<String, String> deserializeActiveByKey(String json) {
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : splitTopLevelEntries(trimmed.substring(1, trimmed.length() - 1))) {
            String key = unescapeJson(e.getKey());
            String name = unescapeJson(removeSurrounding(e.getValue(), "\""));
            if (!key.isEmpty() && !name.isEmpty()) result.put(key, name);
        }
        return result;
    }

    /**
     * T-PresetMethodBinding：{@code {"方案名":{"<scope>":"<键>"}}} 序列化。
     * 空表返回空串（对齐其余参数字段「无数据 = 空串」）；方案与 scope 均排序，持久化不漂移。
     */
    private String serializeRecentKeys() {
        if (recentKeyBySchemeScope.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("{");
        boolean firstScheme = true;
        List<String> schemeNamesSorted = new ArrayList<>(recentKeyBySchemeScope.keySet());
        Collections.sort(schemeNamesSorted);
        for (String scheme : schemeNamesSorted) {
            ConcurrentHashMap<String, String> scopeKeys = recentKeyBySchemeScope.get(scheme);
            if (scopeKeys == null || scopeKeys.isEmpty()) continue;
            if (!firstScheme) sb.append(",");
            firstScheme = false;
            sb.append("\"").append(escapeJson(scheme)).append("\":{");
            boolean firstScope = true;
            List<String> scopesSorted = new ArrayList<>(scopeKeys.keySet());
            Collections.sort(scopesSorted);
            for (String scope : scopesSorted) {
                String key = scopeKeys.get(scope);
                if (key == null) continue;
                if (!firstScope) sb.append(",");
                firstScope = false;
                sb.append("\"").append(escapeJson(scope)).append("\":\"").append(escapeJson(key)).append("\"");
            }
            sb.append("}");
        }
        sb.append("}");
        return firstScheme ? "" : sb.toString();
    }

    /** T-PresetMethodBinding：最近键指针反序列化（损坏/空 → 空表，不崩溃）。 */
    private Map<String, Map<String, String>> deserializeRecentKeys(String json) {
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return Map.of();
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : splitTopLevelEntries(trimmed.substring(1, trimmed.length() - 1))) {
            String valueText = e.getValue();
            if (!valueText.startsWith("{") || !valueText.endsWith("}")) continue;
            Map<String, String> inner = new LinkedHashMap<>();
            for (Map.Entry<String, String> innerEntry
                    : splitTopLevelEntries(valueText.substring(1, valueText.length() - 1))) {
                String key = unescapeJson(removeSurrounding(innerEntry.getValue(), "\""));
                String scopeName = unescapeJson(innerEntry.getKey());
                if (!scopeName.isEmpty() && !key.isEmpty()) inner.put(scopeName, key);
            }
            if (!inner.isEmpty()) result.put(unescapeJson(e.getKey()), inner);
        }
        return result;
    }

    /**
     * 从持久化恢复内存缓存（IDE 环境）；测试环境回落静态存储。
     * R22 T-04（D2 定案）：旧 {@code paramHistoryJson} 数据自动成为「默认方案」——零迁移损失，老用户无感。
     */
    private void restoreFromPersistence() {
        // 旧字段 → 默认方案（D2 迁移落点：旧字段只读兼容，数据原样进默认方案）
        String legacyJson = readPersisted(STORAGE_KEY,
                () -> PluginSettings.getInstance().getState().getParamHistoryJson());
        if (legacyJson != null && !legacyJson.isBlank()) {
            try {
                for (Map.Entry<String, Map<String, String>> e : deserializeFromJson(legacyJson).entrySet()) {
                    cache.put(e.getKey(), e.getValue());
                }
            } catch (Throwable ignored) {
            }
        }
        // 新字段 → 命名方案 + 激活方案（字段为空/损坏时保持默认方案激活态）
        String schemesJson = readPersisted(SCHEMES_STORAGE_KEY,
                () -> PluginSettings.getInstance().getState().getParamSchemesJson());
        if (schemesJson != null && !schemesJson.isBlank()) {
            try {
                for (Map.Entry<String, Map<String, Map<String, String>>> e
                        : deserializeSchemesFromJson(schemesJson).entrySet()) {
                    schemes.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
                    schemeOrder.addIfAbsent(e.getKey()); // F50：JSON 序 = 创建序，恢复时同步入序
                }
            } catch (Throwable ignored) {
            }
        }
        String active = readPersisted(ACTIVE_SCHEME_STORAGE_KEY,
                () -> PluginSettings.getInstance().getState().getParamActiveScheme());
        if (active == null) active = DEFAULT_SCHEME;
        if (!DEFAULT_SCHEME.equals(active) && !schemes.containsKey(active)) active = DEFAULT_SCHEME;
        legacyActiveScheme = active;
        // T-DevAcceptFix2 ①：按键激活指针恢复（旧字段已作为回落值装载，未记录键自动回落它）
        String byKeyJson = readPersisted(ACTIVE_BY_KEY_STORAGE_KEY,
                () -> PluginSettings.getInstance().getState().getParamActiveSchemeByKey());
        if (byKeyJson != null && !byKeyJson.isBlank()) {
            try {
                for (Map.Entry<String, String> e : deserializeActiveByKey(byKeyJson).entrySet()) {
                    activeSchemeByKey.put(e.getKey(), e.getValue());
                }
            } catch (Throwable ignored) {
            }
        }
        // T-PresetMethodBinding：最近保存键指针恢复（缺字段/损坏 → 空表，回落链退化为「精确 + 旧键」）
        String recentJson = readPersisted(RECENT_KEY_STORAGE_KEY,
                () -> PluginSettings.getInstance().getState().getParamSchemeRecentKey());
        if (recentJson != null && !recentJson.isBlank()) {
            try {
                for (Map.Entry<String, Map<String, String>> e : deserializeRecentKeys(recentJson).entrySet()) {
                    recentKeyBySchemeScope.put(e.getKey(), new ConcurrentHashMap<>(e.getValue()));
                }
            } catch (Throwable t) {
                if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) throw (Error) t;
            }
        }
        // 全方案统一执行上限（含 D2 迁入的默认方案）；F54：构造期 this 未逃逸、并发未起，
        // 但按 enforceMaxSize 的持锁契约统一走临界区（防后续演进把恢复挪出构造器时踩裸调）
        synchronized (bucketLock) {
            enforceMaxSize(cache);
            for (LinkedHashMap<String, Map<String, String>> v : schemes.values()) enforceMaxSize(v);
        }
    }

    /** 读持久化值（IDE 环境：PluginSettings.State；为空回落测试环境静态存储）。 */
    private String readPersisted(String storageKey, Supplier<String> fromState) {
        String fromSettings;
        try {
            fromSettings = fromState.get();
        } catch (Throwable t) {
            fromSettings = "";
        }
        if (fromSettings == null) fromSettings = "";
        if (!fromSettings.isBlank()) return fromSettings;
        synchronized (fallbackStorage) {
            String v = fallbackStorage.get(storageKey);
            return v != null ? v : "";
        }
    }

    /**
     * 自研最小 JSON 序列化（格式：{@code [{"key":{...params}}]}）。
     * 仅处理 String→String 映射，key/value 含特殊字符时转义。
     * R22 T-04：参数化条目源——默认方案与命名方案共用同一数组格式。
     */
    private String serializeToJson(Map<String, Map<String, String>> entries) {
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[");
        int index = 0;
        for (Map.Entry<String, Map<String, String>> entry : entries.entrySet()) {
            if (index > 0) sb.append(",");
            sb.append("{\"").append(escapeJson(entry.getKey())).append("\":{");
            int pIdx = 0;
            for (Map.Entry<String, String> p : entry.getValue().entrySet()) {
                if (pIdx > 0) sb.append(",");
                sb.append("\"").append(escapeJson(p.getKey())).append("\":\"")
                        .append(escapeJson(p.getValue())).append("\"");
                pIdx++;
            }
            sb.append("}}");
            index++;
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * R22 T-04：命名方案序列化——{@code {"方案名":[{key:{...params}}], ...}}。
     * 每个方案的条目复用旧数组格式（{@link #serializeToJson}）；空方案跳过，全空返回空串（对齐旧字段「无数据=空串」）。
     * F50：按 {@link #schemeOrder} 创建序遍历（CHM 直查键序为哈希序，直接遍历会使持久化 JSON 序漂移）。
     */
    private String serializeSchemesToJson() {
        List<String> orderedNames = schemeNames();
        if (orderedNames.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String name : orderedNames) {
            LinkedHashMap<String, Map<String, String>> entries = schemes.get(name);
            if (entries == null) continue;
            if (entries.isEmpty()) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(name)).append("\":").append(serializeToJson(entries));
        }
        sb.append("}");
        return first ? "" : sb.toString();
    }

    /**
     * R22 T-04：命名方案反序列化（解析 {@code {"方案名":[...]}}；失败返回空 map——持久化数据损坏不崩溃）。
     */
    private Map<String, Map<String, Map<String, String>>> deserializeSchemesFromJson(String json) {
        Map<String, Map<String, Map<String, String>>> result = new LinkedHashMap<>();
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return Map.of();
        String content = trimmed.substring(1, trimmed.length() - 1);
        for (Map.Entry<String, String> e : splitTopLevelEntries(content)) {
            String valueText = e.getValue();
            if (!valueText.startsWith("[") || !valueText.endsWith("]")) continue;
            result.put(unescapeJson(e.getKey()), deserializeFromJson(valueText));
        }
        return result;
    }

    /** R22 T-04：拆顶层 {@code "name":value} 对（考虑嵌套括号与字符串转义；value 可为对象或数组）。 */
    private List<Map.Entry<String, String>> splitTopLevelEntries(String text) {
        List<Map.Entry<String, String>> result = new ArrayList<>();
        int depth = 0;
        int colonIdx = -1;
        int entryStart = 0;
        boolean inString = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') i++; // 跳过转义对（如 \" \\）
                else if (c == '"') inString = false;
            } else {
                switch (c) {
                    case '"':
                        inString = true;
                        break;
                    case '{':
                    case '[':
                        depth++;
                        break;
                    case '}':
                    case ']':
                        depth--;
                        break;
                    case ':':
                        if (depth == 0 && colonIdx < 0) colonIdx = i;
                        break;
                    case ',':
                        if (depth == 0) {
                            if (entryStart < colonIdx) {
                                String name = parseJsonString(text, entryStart).str;
                                result.add(new AbstractMap.SimpleEntry<>(name, text.substring(colonIdx + 1, i).trim()));
                            }
                            entryStart = i + 1;
                            colonIdx = -1;
                        }
                        break;
                    default:
                        break;
                }
            }
            i++;
        }
        if (entryStart < colonIdx) {
            String name = parseJsonString(text, entryStart).str;
            result.add(new AbstractMap.SimpleEntry<>(name, text.substring(colonIdx + 1).trim()));
        }
        return result;
    }

    /**
     * 自研最小 JSON 反序列化（解析 {@code [{"key":{...params}}]} 格式）。
     * 解析失败返回空 map（容错：持久化数据损坏不崩溃）。
     */
    private Map<String, Map<String, String>> deserializeFromJson(String json) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return result;
        // 简化解析：按顶层对象拆分
        List<String> objects = splitTopLevelObjects(trimmed.substring(1, trimmed.length() - 1));
        for (String obj : objects) {
            String objTrimmed = obj.trim();
            if (!objTrimmed.startsWith("{") || !objTrimmed.endsWith("}")) continue;
            String content = objTrimmed.substring(1, objTrimmed.length() - 1);
            // 第一个 key 是缓存键，value 是 params 对象
            int colonIdx = content.indexOf(":");
            if (colonIdx <= 0) continue;
            StrPos keyParsed = parseJsonString(content, 0);
            String key = unescapeJson(keyParsed.str);
            int paramsObjStart = content.indexOf("{", colonIdx);
            if (paramsObjStart < 0) continue;
            Map<String, String> paramsObj = parseJsonObject(content.substring(paramsObjStart));
            result.put(key, paramsObj);
        }
        return result;
    }

    /** 按顶层对象拆分（考虑嵌套括号）。 */
    private List<String> splitTopLevelObjects(String text) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    result.add(text.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return result;
    }

    /** 解析 JSON 对象（{@code {...}}）为 String→String 映射。 */
    private Map<String, String> parseJsonObject(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return result;
        String content = trimmed.substring(1, trimmed.length() - 1);
        int i = 0;
        while (i < content.length()) {
            // 跳过空白与逗号
            while (i < content.length() && (content.charAt(i) == ' ' || content.charAt(i) == '\t' || content.charAt(i) == ',')) i++;
            if (i >= content.length()) break;
            StrPos keyParsed = parseJsonString(content, i);
            i = keyParsed.pos;
            while (i < content.length() && content.charAt(i) == ' ') i++;
            if (i < content.length() && content.charAt(i) == ':') i++;
            while (i < content.length() && content.charAt(i) == ' ') i++;
            StrPos valueParsed = parseJsonString(content, i);
            i = valueParsed.pos;
            result.put(unescapeJson(keyParsed.str), unescapeJson(valueParsed.str));
        }
        return result;
    }

    /** 解析 JSON 双引号字符串，返回 (值, 下一个位置)。 */
    private StrPos parseJsonString(String text, int start) {
        int i = start;
        int len = text.length();
        while (i < len && text.charAt(i) != '"') i++;
        if (i >= len) return new StrPos("", len);
        i++; // 跳过起始引号
        StringBuilder sb = new StringBuilder();
        while (i < len && text.charAt(i) != '"') {
            if (text.charAt(i) == '\\' && i + 1 < len) {
                i++;
                sb.append(unescapeJsonChar(text.charAt(i)));
            } else {
                sb.append(text.charAt(i));
            }
            i++;
        }
        if (i < len) i++; // 跳过闭合引号
        return new StrPos(sb.toString(), i);
    }

    private static char unescapeJsonChar(char c) {
        switch (c) {
            case '"': return '"';
            case '\\': return '\\';
            case '/': return '/';
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            default: return c;
        }
    }

    /** JSON 字符串转义（最小集：引号、反斜杠、换行、回车、制表符）。 */
    private static String escapeJson(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 字符串整体反转义（处理 \\ \" \\n 等）。 */
    private static String unescapeJson(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\\' && i + 1 < text.length()) {
                sb.append(unescapeJsonChar(text.charAt(i + 1)));
                i += 2;
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    /** Kotlin {@code String.removeSurrounding(delimiter)} 等价（首尾均为该串时剥除，否则原样）。 */
    private static String removeSurrounding(String text, String delimiter) {
        if (text.length() >= delimiter.length() * 2
                && text.startsWith(delimiter) && text.endsWith(delimiter)) {
            return text.substring(delimiter.length(), text.length() - delimiter.length());
        }
        return text;
    }

    /** (值, 下一个位置) 解析结果载体。 */
    private static final class StrPos {
        final String str;
        final int pos;

        StrPos(String str, int pos) {
            this.str = str;
            this.pos = pos;
        }
    }

    /**
     * SQL 模板指纹：SHA-256 前 16 hex 字符。
     * 相同 SQL → 相同指纹；不同 SQL → 不同指纹（碰撞概率可忽略）。
     */
    public static String fingerprint(String sql) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < bytes.length; i++) {
                sb.append(String.format("%02x", bytes[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            // 理论上不可达；兜底用长度+hash
            return String.format("%08x", sql.hashCode());
        }
    }
}
