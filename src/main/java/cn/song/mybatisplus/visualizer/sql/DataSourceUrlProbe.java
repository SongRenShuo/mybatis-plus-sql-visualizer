// T-SyncK2JMaster309 自 master sql/DataSourceUrlProbe.kt 移植；T-SyncK2JMaster92 补 T-PS5-03 单槽 TTL 缓存
package cn.song.mybatisplus.visualizer.sql;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * T-DialectAutoInfer：IDE 数据源 URL 探测（DatabaseTools API，Ultimate-only）。
 *
 * <p>链路（262.3 javap 实锤，plugins/DatabaseTools/lib/modules/intellij.database*.jar）：
 * <ul>
 *  <li>{@code DataSourceManager.getManagers(project)} → 各 manager {@code getDataSources()} → RawDataSource</li>
 *  <li>{@code RawDataSource} 经 {@code DasDataSource.getConnectionConfig()} 得 {@code RawConnectionConfig.getUrl()}</li>
 * </ul>
 * 全程反射（IPGP 裁剪/Community 无 DatabaseTools 时类缺失 → runCatching 返回 null，静默降级）。
 * 只读探测，无 EDT 约束（与 {@code cn.song.mybatisplus.visualizer.execute.DialectPreMapping} 的
 * 读探测同类）；调用方通常已在后台线程。
 *
 * <p>T-PS5-03：结果加<b>单槽 TTL 缓存</b>——方言智能推断开启后单次预览会多次走
 * {@code DialectRegistry.effective}（报告实测约 7 次），每次探测都是一串反射调用；URL 本身是
 * 低频变更数据，TTL 窗口内复用即可。失效双保险：
 * <ol>
 *  <li>{@link #invalidate}——agent 会话建立/断开事件点清（{@code log.agent.AgentManager}）；</li>
 *  <li>TTL 上界（默认 5s）——数据源编辑后<b>至多一个 TTL 窗口内方言滞后</b>（可接受：预览是只读展示）。</li>
 * </ol>
 * 槽位持项目<b>弱引用</b>（不因缓存滞留已关闭项目），项目身份不同即重探。
 */
public final class DataSourceUrlProbe {

    private static final Logger LOG = Logger.getInstance(DataSourceUrlProbe.class);

    /** 单槽缓存条目（项目弱引用 + 结果 + 时间戳）。 */
    private static final class UrlSlot {
        final WeakReference<Project> project;
        final String url;
        final long atMs;

        UrlSlot(WeakReference<Project> project, String url, long atMs) {
            this.project = project;
            this.url = url;
            this.atMs = atMs;
        }
    }

    /** T-DialectQuoteWarn §十二：<b>按名</b>探测的单槽缓存（键＝项目身份 + 数据源名）。 */
    private static final class NamedUrlSlot {
        final WeakReference<Project> project;
        final String name;
        final String url;
        final long atMs;

        NamedUrlSlot(WeakReference<Project> project, String name, String url, long atMs) {
            this.project = project;
            this.name = name;
            this.url = url;
            this.atMs = atMs;
        }
    }

    /** 候选方言单槽缓存（O-136 项目候选共识链路；与 UrlSlot 同构）。 */
    private static final class CandidateSlot {
        final WeakReference<Project> project;
        final List<DialectDecisionEngine.Candidate> candidates;
        final long atMs;

        CandidateSlot(WeakReference<Project> project, List<DialectDecisionEngine.Candidate> candidates, long atMs) {
            this.project = project;
            this.candidates = candidates;
            this.atMs = atMs;
        }
    }

    /** T-PS5-03：TTL 上界（毫秒）。测试注入短窗驱动过期语义。 */
    public static volatile long ttlMs = 5_000L;

    /** T-PS5-03 测试缝：时间源（默认系统时钟）。 */
    public static volatile LongSupplier clockMs = System::currentTimeMillis;

    /** T-PS5-03 测试缝：探测替身（默认 null = 走真实反射链）。 */
    public static volatile Function<Project, String> probeForTest = null;

    /** T-DialectQuoteWarn §十二测试缝：按名探测替身（默认 null = 走真实反射链）。 */
    public static volatile BiFunction<Project, String, String> probeByNameForTest = null;

    /** 候选方言探测替身（测试注入；默认 null = 走真实反射链）。 */
    public static volatile Function<Project, List<DialectDecisionEngine.Candidate>> probeCandidatesForTest = null;

    /** T-PS5-03 测试缝：真实探测调用计数（断言 TTL 窗口内只探测一次）。 */
    public static final AtomicInteger probeCount = new AtomicInteger(0);

    private static volatile UrlSlot slot = null;

    private static volatile NamedUrlSlot namedSlot = null;

    private static volatile CandidateSlot candidateSlot = null;

    private DataSourceUrlProbe() {}

    /**
     * 取项目数据源列表中首个非空 URL（多数据源取列表序首个——DataSourceManager 列表序即
     * 用户面板展示序；尚无「最近使用」元数据可依，一期不深究）。
     * 探测失败（类缺失/反射异常/无数据源）→ null。TTL 窗口内命中缓存不再探测。
     */
    @Nullable
    public static String firstUrl(@Nullable Project project) {
        if (project == null) return null;
        long now = clockMs.getAsLong();
        UrlSlot cached = slot;
        if (cached != null && cached.project.get() == project && now - cached.atMs <= ttlMs) {
            return cached.url;
        }
        String url = probeFirstUrl(project);
        slot = new UrlSlot(new WeakReference<>(project), url, now);
        return url;
    }

    /** 多数据源只返回候选方言与会话内身份，不把连接 URL 留在决策模型中。 */
    @NotNull
    public static List<DialectDecisionEngine.Candidate> candidateDialects(@Nullable Project project) {
        if (project == null) {
            return List.of();
        }
        long now = clockMs.getAsLong();
        CandidateSlot cached = candidateSlot;
        if (cached != null && cached.project.get() == project && now - cached.atMs <= ttlMs) {
            return cached.candidates;
        }
        List<DialectDecisionEngine.Candidate> candidates = probeCandidateDialects(project);
        candidateSlot = new CandidateSlot(new WeakReference<>(project), candidates, now);
        return candidates;
    }

    /**
     * T-DialectQuoteWarn §十二：取<b>指定名字</b>的数据源 URL（同名取首个非空）。
     *
     * <p>与 {@link #firstUrl} 同一反射链（{@code DataSourceManager.getManagers} → {@code getDataSources()}
     * → {@code getName()} 匹配 → {@code getConnectionConfig().getUrl()}），差别只在<b>按名匹配</b>而非取首个。
     * 为什么必须按名：预览绑定的是「上次使用 / 已绑定」那一个数据源（面板 {@code bindstate.associated}），
     * 与项目内<b>首个</b>数据源可以不同且连接选项相反（本项目 demo：首个 jpa_demo、绑定 mpj_demo，
     * 两者 {@code ANSI_QUOTES} 状态相反）——拿 {@link #firstUrl} 当绑定源会给出错误结论。</p>
     *
     * <p>{@code project == null}、{@code name} 空、探测失败（类缺失/反射异常/无同名数据源）→ null（调用方按
     * 「未知」处理，不假装知道连接选项）。TTL 窗口内同 (项目, 名) 命中缓存不再探测。</p>
     */
    @Nullable
    public static String urlByName(@Nullable Project project, @Nullable String name) {
        if (project == null || name == null || name.isBlank()) return null;
        long now = clockMs.getAsLong();
        NamedUrlSlot cached = namedSlot;
        if (cached != null && cached.project.get() == project && cached.name.equals(name)
                && now - cached.atMs <= ttlMs) {
            return cached.url;
        }
        String url = probeUrlByName(project, name);
        namedSlot = new NamedUrlSlot(new WeakReference<>(project), name, url, now);
        return url;
    }

    /**
     * T-PS5-03：缓存失效（agent 会话建立/断开事件点调用）。
     * {@code project == null} 或与本槽项目同身份时清槽（单槽语义：异项目槽本就不可命中）。
     */
    public static void invalidate(@Nullable Project project) {
        UrlSlot cached = slot;
        if (cached != null && (project == null || cached.project.get() == project)) slot = null;
        NamedUrlSlot named = namedSlot;
        if (named != null && (project == null || named.project.get() == project)) namedSlot = null;
        CandidateSlot candidates = candidateSlot;
        if (candidates != null && (project == null || candidates.project.get() == project)) candidateSlot = null;
    }

    /** T-PS5-03 测试缝：无条件清槽（用例隔离）。 */
    public static void invalidateAllForTest() {
        slot = null;
        namedSlot = null;
        candidateSlot = null;
    }

    /** 候选方言真实探测（反射链，失败空清单）；{@link #probeCandidatesForTest} 注入时走替身。 */
    @NotNull
    private static List<DialectDecisionEngine.Candidate> probeCandidateDialects(@NotNull Project project) {
        Function<Project, List<DialectDecisionEngine.Candidate>> candidatesProbe = probeCandidatesForTest;
        if (candidatesProbe != null) {
            probeCount.incrementAndGet();
            return candidatesProbe.apply(project);
        }
        Function<Project, String> firstUrlProbe = probeForTest;
        if (firstUrlProbe != null) {
            String name = UrlSubprotocolDialects.dialectName(firstUrl(project));
            if (name == null) {
                return List.of();
            }
            return List.of(new DialectDecisionEngine.Candidate("test-first-url", name));
        }
        probeCount.incrementAndGet();
        try {
            if (project.isDisposed()) {
                return List.of();
            }
            List<DialectDecisionEngine.Candidate> candidates = new java.util.ArrayList<>();
            List<Object> managers = dataSourceManagers(project);
            if (managers != null) {
                for (int managerIndex = 0; managerIndex < managers.size(); managerIndex++) {
                    Object manager = managers.get(managerIndex);
                    Object raw = invoke(manager, "getDataSources");
                    if (!(raw instanceof List)) {
                        continue;
                    }
                    List<?> dataSources = (List<?>) raw;
                    for (int sourceIndex = 0; sourceIndex < dataSources.size(); sourceIndex++) {
                        Object source = dataSources.get(sourceIndex);
                        if (source == null) {
                            continue;
                        }
                        Object config = invoke(source, "getConnectionConfig");
                        String url = config != null && invoke(config, "getUrl") instanceof String s ? s : null;
                        DataSourceDialectClassification.Result classified = classifyDataSource(manager, source, url);
                        // T-DialectInferFix：非 SQL 数据源（Redis/Mongo/…）不是 SQL 执行目标，投进候选
                        // 只会让决策落 AMBIGUOUS（见 DataSourceDialectClassification KDoc），直接不参与投票。
                        if (!classified.isSqlTarget()) {
                            String displayName = dbmsDisplayName(source);
                            LOG.debug("DataSourceUrlProbe: skip non-SQL data source #" + managerIndex + "-"
                                    + sourceIndex + " (" + (displayName != null ? displayName : "?") + ")");
                            continue;
                        }
                        candidates.add(new DialectDecisionEngine.Candidate(
                                "session-" + managerIndex + "-" + sourceIndex,
                                classified.dialectName()));
                    }
                }
            }
            return candidates;
        } catch (Throwable t) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            return List.of();
        }
    }

    /** T-DialectInferFix：单数据源分类（平台 query language → DBMS 显示名 → URL 子协议）。 */
    private static DataSourceDialectClassification.Result classifyDataSource(Object manager, Object source,
                                                                            @Nullable String url) {
        return DataSourceDialectClassification.classify(
                queryLanguageId(manager, source),
                dbmsDisplayName(source),
                url);
    }

    /**
     * 平台给该数据源认的 query language id（{@code DataSourceManager.getQueryLanguage(T)}）。
     *
     * <p>反射口径（T-DialectInferFix P-2）：{@code getQueryLanguage(T)} 擦除后参数类型是
     * {@code com.intellij.database.model.RawDataSource}，传 {@code Object.class} 找不到方法；
     * Method 一律从<b>声明接口</b>取（实现类 {@code LocalDataSource} 在包内，跨 classloader 直取会
     * IllegalAccessException，见 DatabaseSchemaResolver 同款纪律）。取不到 → null（调用方降级）。</p>
     */
    @Nullable
    private static String queryLanguageId(Object manager, Object source) {
        try {
            Class<?> managerClass = Class.forName(DATA_SOURCE_MANAGER_CLASS);
            Class<?> rawType = Class.forName(RAW_DATA_SOURCE_CLASS);
            java.lang.reflect.Method method = managerClass.getMethod("getQueryLanguage", rawType);
            Object language = method.invoke(manager, source);
            return language instanceof com.intellij.lang.Language ? ((com.intellij.lang.Language) language).getID() : null;
        } catch (Throwable t) {
            LOG.debug("DataSourceUrlProbe.getQueryLanguage 反射失败（" + manager.getClass().getName() + "）", t);
            return null;
        }
    }

    /** 数据源 DBMS 显示名（{@code DasDataSource.getDbms().getDisplayName()}，取不到再取 {@code getName()}）。 */
    @Nullable
    private static String dbmsDisplayName(Object source) {
        try {
            Class<?> dasType = Class.forName(DAS_DATA_SOURCE_CLASS);
            Object dbms = dasType.getMethod("getDbms").invoke(source);
            if (dbms == null) return null;
            Class<?> dbmsClass = Class.forName("com.intellij.database.Dbms");
            Object display = dbmsClass.getMethod("getDisplayName").invoke(dbms);
            if (display instanceof String) return (String) display;
            Object name = dbmsClass.getMethod("getName").invoke(dbms);
            return name instanceof String ? (String) name : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 真实探测（反射链，失败静默 null）；{@link #probeForTest} 注入时走替身。 */
    @Nullable
    private static String probeFirstUrl(@NotNull Project project) {
        probeCount.incrementAndGet();
        Function<Project, String> probe = probeForTest;
        if (probe != null) return probe.apply(project);
        try {
            if (project.isDisposed()) return null;
            List<Object> managers = dataSourceManagers(project);
            if (managers == null) return null;
            for (Object manager : managers) {
                // manager 为反射拿到的任意类型（Object），统一走反射取 getDataSources()
                Object raw = invoke(manager, "getDataSources");
                if (!(raw instanceof List)) continue;
                List<?> dataSources = (List<?>) raw;
                for (Object ds : dataSources) {
                    if (ds == null) continue;
                    Object config = invoke(ds, "getConnectionConfig");
                    if (config == null) continue;
                    Object urlRaw = invoke(config, "getUrl");
                    String url = urlRaw instanceof String ? (String) urlRaw : null;
                    // T-DialectInferFix：首个非空 URL 也必须跳过非 SQL 数据源——Redis 排在前时
                    // 会取到 jdbc:redis://…，下游 dialectName 为 null，等于「首个 URL 不可用」。
                    if (url == null || url.isBlank()) continue;
                    if (!classifyDataSource(manager, ds, url).isSqlTarget()) continue;
                    return url;
                }
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 按名真实探测（反射链，失败静默 null）；{@link #probeByNameForTest} 注入时走替身。 */
    @Nullable
    private static String probeUrlByName(@NotNull Project project, @NotNull String name) {
        probeCount.incrementAndGet();
        BiFunction<Project, String, String> probe = probeByNameForTest;
        if (probe != null) return probe.apply(project, name);
        try {
            if (project.isDisposed()) return null;
            List<Object> managers = dataSourceManagers(project);
            if (managers == null) return null;
            for (Object manager : managers) {
                Object raw = invoke(manager, "getDataSources");
                if (!(raw instanceof List)) continue;
                List<?> dataSources = (List<?>) raw;
                for (Object ds : dataSources) {
                    if (ds == null) continue;
                    // 名字面量取法与 DataSourceSelector.boundConsoleDataSourceName 同源（LocalDataSource.getName）
                    Object nameRaw = invoke(ds, "getName");
                    if (!(nameRaw instanceof String)) continue;
                    String dsName = (String) nameRaw;
                    if (!dsName.equals(name)) continue;
                    Object config = invoke(ds, "getConnectionConfig");
                    if (config == null) continue;
                    Object urlRaw = invoke(config, "getUrl");
                    String url = urlRaw instanceof String ? (String) urlRaw : null;
                    if (url != null && !url.isBlank()) return url;
                }
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // T-DialectQuoteWarn §十二：URL → ANSI_QUOTES 三态
    // ------------------------------------------------------------------

    /**
     * 从 JDBC URL 判定是否<b>已声明</b> {@code sql_mode} 含 {@code ANSI_QUOTES}（T-DialectQuoteWarn §十二）。
     *
     * <p>返回值三态：
     * <ul>
     *   <li>{@code true} = URL 里 {@code sql_mode=} 的取值含 {@code ANSI_QUOTES} 项 → 可证明该连接双引号是标识符引号；</li>
     *   <li>{@code false} = URL 非空但未声明（含完全没有 {@code sql_mode}）→ 已知未声明；</li>
     *   <li>{@code null} = URL 为空/取不到 → 未知。</li>
     * </ul></p>
     *
     * <p><b>只做「声明了」的正向证明</b>：URL 未声明<b>不等于</b>连接未开——生效 {@code sql_mode} =
     * 服务端全局 + 会话覆盖，URL 里的 {@code sessionVariables} 只是会话覆盖，服务端全局仍可能开着。故
     * {@code false} 只表达「URL 未声明」，调用方（规则）据此只能降级 WARN，不得据此报 ERROR。</p>
     *
     * <p>解析口径：先 URL-decode（连接串里 {@code ,}/{@code '} 常被编码为 {@code %2C}/{@code %27}），
     * 再定位每个 {@code sql_mode=} 赋值，取其值（带引号则读到配对引号，否则读到 {@code &}/{@code ;}/串尾），
     * 按 {@code ,}/空白分词后不区分大小写比对 {@code ANSI_QUOTES}。匹配从严（只认 {@code sql_mode}
     * 赋值内的项），宁可漏判也不误判为「已声明」。</p>
     */
    @Nullable
    public static Boolean ansiQuotesFromUrl(@Nullable String url) {
        if (url == null || url.isBlank()) return null;
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(url, "UTF-8");
        } catch (Exception e) {
            decoded = url;
        }
        int from = 0;
        while (true) {
            java.util.regex.Matcher m = SQL_MODE_ASSIGN.matcher(decoded);
            if (!m.find(from)) return false;
            int afterAssign = m.end();
            String rest = decoded.substring(afterAssign);
            char first = rest.isEmpty() ? '\0' : rest.charAt(0);
            char quote = (first == '\'' || first == '"') ? first : '\0';
            String value;
            if (quote != '\0') {
                int end = rest.indexOf(quote, 1);
                if (end < 0) end = rest.length();
                value = rest.substring(1, end);
            } else {
                int end = -1;
                for (int i = 0; i < rest.length(); i++) {
                    char c = rest.charAt(i);
                    if (c == '&' || c == ';') {
                        end = i;
                        break;
                    }
                }
                value = end < 0 ? rest : rest.substring(0, end);
            }
            for (String token : value.split("[,\\s\\t]+")) {
                if (token.trim().equalsIgnoreCase("ANSI_QUOTES")) return true;
            }
            from = afterAssign;
        }
    }

    /** {@code sql_mode=} 赋值定位（不区分大小写）。 */
    private static final java.util.regex.Pattern SQL_MODE_ASSIGN =
            java.util.regex.Pattern.compile("(?i)sql_mode\\s*=\\s*");

    /** `DataSourceManager.getManagers(project)` 反射调用；类缺失/异常 → null。 */
    @Nullable
    @SuppressWarnings("unchecked")
    private static List<Object> dataSourceManagers(@NotNull Project project) {
        Class<?> managerClass;
        try {
            managerClass = Class.forName(
                    DATA_SOURCE_MANAGER_CLASS, true, DataSourceUrlProbe.class.getClassLoader());
        } catch (Throwable e) {
            return null;
        }
        Object managers;
        try {
            managers = managerClass.getMethod("getManagers", Project.class).invoke(null, project);
        } catch (Throwable e) {
            return null;
        }
        if (!(managers instanceof List)) return null;
        return (List<Object>) managers;
    }

    @Nullable
    private static Object invoke(@NotNull Object target, @NotNull String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable e) {
            LOG.debug("DataSourceUrlProbe." + method + " 反射失败（" + target.getClass().getName() + "）", e);
            return null;
        }
    }

    /** DatabaseTools 反射面类名（T-DialectInferFix：候选分类与候选投票共用同一份字面量）。 */
    private static final String DATA_SOURCE_MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager";
    private static final String RAW_DATA_SOURCE_CLASS = "com.intellij.database.model.RawDataSource";
    private static final String DAS_DATA_SOURCE_CLASS = "com.intellij.database.model.DasDataSource";
}
