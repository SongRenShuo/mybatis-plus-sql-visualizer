package cn.song.mybatisplus.visualizer.execute;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * T-ConsoleBind（2026-10-28）：执行控制台与预览绑定数据源的一致性闸门。
 *
 * <p><b>缺陷</b>（2026-10-28 录制现场实测）：预览面板头部显示「已绑定：mp_demo@localhost」，点
 * 执行 SQL / 执行计划 / 回滚 SQL-快照查询却在 mpj_demo 上跑（{@code Unknown column 'deleted'}
 * ——该列只在 mp_demo.user 存在）；对数据源节点 Close All Sessions 也不能改绑。</p>
 *
 * <p><b>根因（262 平台 jar 反编译实证，两层叠加）</b>：</p>
 * <ol>
 *  <li>平台 forced 关联短路——插件的 exec 文件路径稳定（T-PreviewFlash：
 *      {@code <projectBase>/.idea/sql-visualizer/exec/mpsql-exec.sql}），而平台 {@code JdbcConsoleProvider
 *      .attachConsole} 每次挂控制台都会把「文件→数据源」per-file 关联写死（字节码内
 *      {@code DataSourcePerFileMappings.setMappingAsync}）并挂上控制台；
 *      {@code DatabaseRunners.getAttachDataSourceRunners} 入口先查
 *      {@code DbImplUtil.getForcedDataSourceForExec}（→ {@code VirtualFileDataSourceProvider} EP：
 *      per-file 关联在前、挂载控制台在后），命中即 {@code singletonList(该源)} 返回——
 *      插件侧的 last-used 静默命中、preferredRunnerName、选择器弹窗全部被短路成
 *      「唯一 runner 直接跑」。旧经验（20261024-1914 第十二轮）所谓「无公开清除 API」
 *      在 262 已不成立：{@code JdbcConsoleProvider.detachConsole(Project, VirtualFile)} 与
 *      {@code DataSourcePerFileMappings.setMapping}（null 值走 {@code PerFileMappingsBase} 的
 *      {@code Map.remove}）均为 public，本对齐器即基于这两个面。</li>
 *  <li>插件复用按名不按身份——{@code DataSourceSelector.reuseSharedConsole} 的复用判定是
 *      「last-used 记忆名 == 建共享缓存时的记录名」，从不探测共享控制台<b>当前实际绑定</b>
 *      的数据源身份。控制台被任何路径重绑（native 切换、被钉源全链跑一次）后，复用链
 *      照旧放行；头部绑定行又是事件驱动刷新，滞后于翻转——用户看到「已绑定 mp_demo」
 *      而执行落在 mpj_demo。Close All Sessions 无效同理：per-file 关联持久化，不随会话
 *      关闭清除，且 provider 顺序在挂载控制台之前。</li>
 * </ol>
 *
 * <p><b>修法</b>（对齐，不重写）：</p>
 * <ul>
 *  <li>复用判定升级为身份制：预览有明确绑定目标（UUID）时，共享控制台必须「当前实际绑定」
 *      同一数据源才可复用（{@link #resolveSharedConsoleReuse}）；无目标身份时保留旧名字判定逐字不变。</li>
 *  <li>全链枚举前把 exec 文件的 forced 关联对齐到绑定目标（{@link #realignExecBind}：detach 陈旧
 *      控制台 + 重写 per-file 关联），用户显式要求换源时清关联让弹窗恢复全源列表
 *      （{@link #resolveBindAction}）。同一数据源下重复预览 pin 已一致 → NOOP，复用语义不变。</li>
 * </ul>
 *
 * <p><b>身份键</b>：{@code LocalDataSource.getUniqueId()}（与 T-SI-02 last-used 身份键、
 * T-ConsoleDbContext 探测、ModuleDataSourceBindingStore 学习绑定同一空间）。
 * 隔离约定同 {@link ExecConsoleContext}：仅 Database 插件链内调用，Community 下
 * {@code Class.forName} 失败 → runCatching 全部退化为「不动作」，绝不影响主链。</p>
 *
 * <p><b>T-ConsoleBind263（2026-11-07）</b>：IDE 2026.3（IU-263.7307.200）移除了
 * {@code DataSourcePerFileMappingsImpl.setMapping(VirtualFile, LocalDataSource)}——上面第 1 层修法里
 * 的写关联那一步因此恒抛 {@code NoSuchMethodException}，「清 pin」只剩 detach、per-file 关联没被清，
 * 平台 forced 关联把 runner 枚举继续短路成单源（换源弹窗只剩当前源；工具栏点选 {@code mpj_demo}
 * 静默落到被钉住的 Oracle 源）。写入口改为<b>多版本策略链</b> S1→S2→S3→S4（{@link #selectMappingStrategies}）：
 * 263 走 S1 {@code setMappingWithWriteAction}，而它<b>是异步协程</b>（见 {@link MappingStrategy} KDoc），
 * 所以要写后回读（{@link #probePerFileMappingId}）并以 S3 {@code setMapping(VirtualFile, String)} 同步兜底。</p>
 */
final class ExecFileBinding {

    private static final Logger LOG = Logger.getInstance(ExecFileBinding.class);

    /** per-file 关联实现类（262 平台 jar 实证：{@code com.intellij.database.dataSource.DataSourcePerFileMappingsImpl}）。 */
    private static final String PER_FILE_MAPPINGS_CLASS = "com.intellij.database.dataSource.DataSourcePerFileMappingsImpl";

    /** per-file 关联接口类（263 起 {@code setMappingWithWriteAction} 声明在接口上；两边都探，防形态差异）。 */
    private static final String PER_FILE_MAPPINGS_API_CLASS = "com.intellij.database.console.DataSourcePerFileMappings";

    /** 数据源类（反射参数类型必须来自同一 classloader，不能用编译期引用）。 */
    private static final String LOCAL_DS_CLASS = "com.intellij.database.dataSource.LocalDataSource";

    private ExecFileBinding() {
    }

    // ------------------------------------------------------------------
    // 纯判定（headless 表驱动直测；不触平台类）
    // ------------------------------------------------------------------

    /**
     * exec 文件 forced 关联探针结果（两项独立可空；全 null = 无钉源/探测不可用）。
     *
     * <ul>
     *  <li>{@code attachedConsoleDsId}：挂载控制台绑定的数据源 UUID（{@code JdbcConsoleProvider.getValidConsole}
     *      → {@code getDataSource}）。</li>
     *  <li>{@code perFileMappingDsId}：per-file 关联的数据源 UUID（{@code DataSourcePerFileMappingsImpl
     *      .getDataSourcePerFileMapping}）。</li>
     * </ul>
     */
    record ExecBindProbe(@Nullable String attachedConsoleDsId, @Nullable String perFileMappingDsId) {

        /**
         * 平台实际的钉源身份——provider 顺序决定优先级（plugin.xml 实证：
         * {@code DbConsoleRootDataSourceProvider}（per-file 关联）在前，{@code JdbcConsoleDataSourceProvider}
         * （挂载控制台）在后；forced 枚举短路按同一顺序取第一个命中）。
         */
        @Nullable
        String pinnedDsId() {
            return perFileMappingDsId != null ? perFileMappingDsId : attachedConsoleDsId;
        }
    }

    enum Action {
        /** pin 不存在 / 已与目标一致 / 无目标身份（含冲突不猜）——不动平台状态。 */
        NOOP,

        /** pin 与目标不一致——detach 陈旧控制台并把 per-file 关联重写到目标数据源。 */
        REALIGN,

        /** 用户显式要求换源（forceChoose 族）——清 pin（detach + 清关联），让选择器恢复全源列表。 */
        CLEAR,
    }

    record BindAction(Action action, @Nullable String intendedDsId) {
    }

    /**
     * exec 文件 pin 的处置判定（纯函数）。
     *
     * @param forceChooserPopup 用户显式要求弹出选择器（forceChooser ∥ forceChoosePopup）——
     *        此时 pin 存在会把枚举短路成单源，必须清除才能恢复「列全部源」的弹窗语义
     * @param intendedDsId      本次预览的绑定目标身份（null = 无可信目标：无记忆/学习与记忆冲突/同名歧义——不猜）
     */
    static BindAction resolveBindAction(boolean forceChooserPopup, @Nullable String intendedDsId,
                                        ExecBindProbe probe) {
        String pinned = probe.pinnedDsId();
        if (pinned == null) return new BindAction(Action.NOOP, null);
        if (forceChooserPopup) return new BindAction(Action.CLEAR, null);
        if (intendedDsId == null || intendedDsId.isBlank()) return new BindAction(Action.NOOP, null);
        return pinned.equals(intendedDsId)
                ? new BindAction(Action.NOOP, null)
                : new BindAction(Action.REALIGN, intendedDsId);
    }

    /**
     * 共享控制台复用身份判定（纯函数；{@code DataSourceSelector.reuseSharedConsole} 的核心判据）。
     *
     * <p>有预览绑定目标（{@code intendedDsId} 非空）时<b>身份制</b>：共享控制台「当前实际绑定」
     * （{@code liveConsoleDsId} 现场探测）必须等于目标——这正是本缺陷的主判据：预览绑定
     * mp_demo（uuid-mp）时不得复用实际绑在 mpj_demo（uuid-mpj）上的控制台，哪怕记忆名
     * 与建缓存记录名都是 mp_demo（旧判据在此形态下放行）。探不到身份（{@code liveConsoleDsId}
     * null）= 身份不可信，宁冷启不复用（跑错库的代价远大于重建一次控制台）。</p>
     *
     * <p>无目标身份时保留旧名字判定<b>逐字不变</b>（{@code lastRunnerName(project) == sharedDataSourceName}，
     * 含记忆缺失即不复用的原语义）。</p>
     */
    static boolean resolveSharedConsoleReuse(
            @Nullable String intendedDsId,
            @Nullable String liveConsoleDsId,
            @Nullable String legacyMemoryName,
            @Nullable String legacySharedRecordedName) {
        if (intendedDsId != null && !intendedDsId.isBlank()) {
            return liveConsoleDsId != null && liveConsoleDsId.equals(intendedDsId);
        }
        return legacyMemoryName != null && !legacyMemoryName.isBlank()
                && legacyMemoryName.equals(legacySharedRecordedName);
    }

    /**
     * 预览绑定目标解析核心（纯函数，假数据源模型直测）。
     *
     * <p>优先级：显式指定（在册唯一命中才认，歧义不猜）&gt; 模块学习 &gt; last-used（UUID 失效退
     * 名字唯一命中）。学习与 last-used 并存且不同 → null（冲突不猜，与 EXPLAIN 回落同规）。
     * 返回数据源<b>对象</b>（realign 重写 per-file 关联需要），身份判定全部走 {@code idOf}。</p>
     *
     * @param registered 在册数据源对象（生产 = {@code DataSourceSettings.getAllDataSources}）
     * @param byId       UUID → 数据源对象（生产 = {@code DataSourceSettings.getDataSourceById}）
     */
    @Nullable
    static Object resolveIntendedDataSourceCore(
            List<Object> registered,
            Function<Object, String> nameOf,
            Function<Object, String> idOf,
            Function<String, Object> byId,
            @Nullable String learnedId,
            DataSourceSelector.LastUsedMemory memory,
            @Nullable String preferredRunnerName) {
        // ① 显式指定：唯一命中即目标（压过学习/记忆冲突——用户此刻的显式选择就是意图本身）
        if (preferredRunnerName != null && !preferredRunnerName.isBlank()) {
            Object preferred = uniqueByName(registered, nameOf, preferredRunnerName);
            if (preferred != null) return preferred;
        }
        // ② last-used → 数据源对象：UUID 精确（数据源被改名也认），失效退名字唯一命中
        Object lastDs = null;
        if (memory.dataSourceId() != null && !memory.dataSourceId().isBlank()) {
            lastDs = byId.apply(memory.dataSourceId());
        }
        if (lastDs == null && memory.displayName() != null && !memory.displayName().isBlank()) {
            lastDs = uniqueByName(registered, nameOf, memory.displayName());
        }
        // ③ 学习 vs last-used 冲突不猜（与 explainFallbackDataSource 同规，交选择器自愈）
        String learned = learnedId != null && !learnedId.isBlank() ? learnedId : null;
        String lastId = lastDs != null ? idOf.apply(lastDs) : null;
        String resolvedId = DataSourceSelector.resolveExplainFallbackChoice(learned, lastId);
        if (resolvedId == null) return null;
        return resolvedId.equals(learned) ? (learned != null ? byId.apply(learned) : null) : lastDs;
    }

    /** 在册数据源里按显示名唯一命中（同名多条/未命中 → null，不猜首条）。 */
    @Nullable
    private static Object uniqueByName(List<Object> registered, Function<Object, String> nameOf, String name) {
        Object matched = null;
        for (Object ds : registered) {
            if (name.equals(nameOf.apply(ds))) {
                if (matched != null) return null;
                matched = ds;
            }
        }
        return matched;
    }

    /**
     * 生产包装：解析本次预览的绑定目标数据源对象。
     * 任何平台反射失败都退化为 null（NOOP，既有行为），绝不阻断准备链。
     */
    @Nullable
    static Object resolveIntendedDataSource(
            @Nullable Project project,
            @Nullable String moduleKey,
            @Nullable String preferredRunnerName) {
        List<Object> registered;
        try {
            registered = DataSourceSettings.getAllDataSources(project);
        } catch (Throwable t) {
            registered = List.of();
        }
        if (registered.isEmpty()) return null;
        String learnedId = null;
        if (moduleKey != null && !moduleKey.isBlank()) {
            final String key = moduleKey;
            try {
                cn.song.mybatisplus.visualizer.sql.ModuleDataSourceBindingStore store =
                        cn.song.mybatisplus.visualizer.sql.ModuleDataSourceBindingStore.getInstance(project);
                Object candidate = store != null ? store.learnedCandidate(project, key) : null;
                learnedId = candidate != null
                        ? ((cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Candidate) candidate).id()
                        : null;
            } catch (Throwable t) {
                learnedId = null;
            }
        }
        return resolveIntendedDataSourceCore(
                registered,
                ds -> {
                    try {
                        return DataSourceSettings.getName(ds);
                    } catch (Throwable t) {
                        return null;
                    }
                },
                ds -> {
                    try {
                        return DataSourceSettings.getUniqueId(ds);
                    } catch (Throwable t) {
                        return null;
                    }
                },
                id -> {
                    try {
                        return DataSourceSettings.getDataSourceById(project, id);
                    } catch (Throwable t) {
                        return null;
                    }
                },
                learnedId,
                DataSourceSelector.lastUsedRunner(project, moduleKey),
                preferredRunnerName);
    }

    // ------------------------------------------------------------------
    // 反射探测与对齐（仅 Database 插件链内调用；失败一律 fail-closed 不动作）
    // ------------------------------------------------------------------

    /**
     * 探测 exec 文件当前被平台钉住的数据源身份（{@link ExecBindProbe}）。
     * Community / 无 Database 插件 / 反射任何一环失败 → 全 null（NOOP）。
     */
    static ExecBindProbe probeExecBind(Project project, VirtualFile vf) {
        String consoleId;
        try {
            Class<?> provider = Class.forName(DataSourceSelector.CONSOLE_PROVIDER_CLASS);
            Object console = provider.getMethod("getValidConsole", Project.class, VirtualFile.class)
                    .invoke(null, project, vf);
            consoleId = console != null ? DataSourceSelector.consoleDataSourceId(console) : null;
        } catch (Throwable t) {
            LOG.info("[T-ConsoleBind] probe attached console FAILED (treat as unpinned): "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            consoleId = null;
        }
        String mappingId = probePerFileMappingId(project, vf);
        return new ExecBindProbe(consoleId, mappingId);
    }

    // ------------------------------------------------------------------
    // T-ConsoleBind263（2026-11-07）：per-file 关联写入的多版本策略链
    // ------------------------------------------------------------------
    //
    // **缺陷**：IDE 2026.3（IU-263.7307.200）移除了
    // DataSourcePerFileMappingsImpl.setMapping(VirtualFile, LocalDataSource)，旧反射恒抛
    // NoSuchMethodException → pin 清不掉 → 平台 forced 关联继续短路 runner 枚举，换源弹窗
    // 只剩当前源、工具栏点选静默落到被钉住的源（现场日志 idea.1.log:27899-28040）。
    //
    // **263 字节码实证（本单取证，intellij.database.core.impl.ex.jar）**：
    //  - setMapping(VirtualFile, String)：DbVFSUtils.isConsoleFile(vf) 时恒清（setMapping(vf, null)），
    //    否则写 id 并在 value != null 时广播 OnMappingsChangedListener；
    //  - setMappingWithWriteAction(vf, ds, callback)：**异步**——BuildersKt.launch(coroutineScope,
    //    Dispatchers.getEDT(), ...) → 协程体 edtWriteAction { setMapping(vf, ds?.uniqueId) } → callback。
    //    故「调用后立刻回读」在 EDT 上必然读到旧值（§八 V1 前提被推翻）→ 必须由同步策略兜底；
    //  - 静态 setDataSourcePerFileMapping(project, vf, ds)：getInstance(project).setMapping(vf,
    //    ds.getUniqueId())，**ds 非空无判空** → 只能写、不能清（CLEAR 严禁走它）。
    //  - 静态 getDataSourcePerFileMapping(project, vf)：getMapping(vf) →
    //    DataSourceStorage.getDataSourceById(id)，是本单「写后回读」的判据来源。

    /**
     * per-file 关联写入策略（按「新 → 旧 → 只写」顺序探测，见 {@link #selectMappingStrategies}）。
     */
    enum MappingStrategy {
        /** 263+：{@code setMappingWithWriteAction(VirtualFile, LocalDataSource?, Runnable?)}（平台自派写动作，异步）。 */
        WRITE_ACTION("S1_setMappingWithWriteAction", false),

        /** ≤262：{@code setMapping(VirtualFile, LocalDataSource)}（263 已移除）。 */
        LEGACY_DS("S2_setMapping(VirtualFile,LocalDataSource)", false),

        /** 兜底同步写：{@code setMapping(VirtualFile, String)}（null = 清除；263 字节码语义已实证）。 */
        STRING_ID("S3_setMapping(VirtualFile,String)", false),

        /** 最后兜底：静态 {@code setDataSourcePerFileMapping(project, vf, ds)}，只能写。 */
        STATIC_WRITE_ONLY("S4_setDataSourcePerFileMapping", true);

        /** 策略标识（日志/契约测试锚点）。 */
        final String id;

        /** 只能写、不能清（{@code ds=null} 会 NPE 或静默失败）——CLEAR 必须排除。 */
        final boolean writeOnly;

        MappingStrategy(String id, boolean writeOnly) {
            this.id = id;
            this.writeOnly = writeOnly;
        }

        public String id() {
            return id;
        }

        public boolean writeOnly() {
            return writeOnly;
        }
    }

    /**
     * 纯函数：给定平台可用策略集合与本次语义（清/写），返回<b>实际尝试顺序</b> S1→S2→S3→S4。
     *
     * <p>CLEAR（{@code clearing}=true，{@code intendedDs == null}）必须剔除只能写的 S4——那是 263 的
     * {@code setDataSourcePerFileMapping}：字节码里直接 {@code ds.getUniqueId()} 无判空，传 null 必 NPE。
     * 可用集合按探测结果传入，顺序<b>不随传入顺序变化</b>（表驱动单测钉死）。</p>
     */
    static List<MappingStrategy> selectMappingStrategies(
            Collection<MappingStrategy> available,
            boolean clearing) {
        List<MappingStrategy> ordered = new ArrayList<>();
        for (MappingStrategy candidate : MappingStrategy.values()) {
            if (available.contains(candidate) && !(clearing && candidate.writeOnly)) {
                ordered.add(candidate);
            }
        }
        return ordered;
    }

    /**
     * 一次对齐的结果（策略名 + 是否落地 + 回读到的身份）——日志与上层判定共用，
     * 取代旧实现的单一布尔（旧日志分不清「清了」与「写了」，本次事故正是因此只剩一条 WARN）。
     *
     * @param detached  {@code JdbcConsoleProvider.detachConsole} 是否成功
     * @param mapped    per-file 关联是否写到位（{@code verified}=false 时为「调用未抛异常」）
     * @param strategy  实际生效的策略（未生效为 null）
     * @param expectedId 期望的关联身份（null = 清除）
     * @param observedId 回读到的关联身份（探测不可用为 null）
     * @param verified  回读是否可用并与期望一致（无回读能力时为 false，不代表失败）
     * @param failure   最后一次失败的归因串（{@code <异常类型>: <首行消息>}，或 {@code READBACK_MISMATCH ...}；成功为 null）
     */
    record RealignReport(
            boolean detached,
            boolean mapped,
            @Nullable MappingStrategy strategy,
            @Nullable String expectedId,
            @Nullable String observedId,
            boolean verified,
            @Nullable String failure
    ) {
        /** 两步都到位（调用方据此决定是否作废共享/已备控制台缓存）。 */
        boolean applied() {
            return detached && mapped;
        }
    }

    /**
     * 平台可用的写入策略（按固定顺序；探测结果进程内一次性缓存）。
     *
     * <p>缓存只服务于生产路径（每次换源都探一遍纯属浪费）；{@link #detectMappingStrategies} 是无缓存的
     * 纯探测，供契约测试注入自己的类来源——测试要的正是「换一版平台形态，探测会不会跟着变」。</p>
     */
    static List<MappingStrategy> availableMappingStrategies() {
        List<MappingStrategy> cached = probedStrategies;
        if (cached != null) return cached;
        synchronized (ExecFileBinding.class) {
            cached = probedStrategies;
            if (cached != null) return cached;
            List<MappingStrategy> available = detectMappingStrategies(ExecFileBinding::cachedClass);
            probedStrategies = available;
            // T-ConsoleBind263 §4.3：契约探针**首次换源时**打一行（D2 定案：不放启动——社区版/无
            // Database 插件场景不该多付一次类加载）。IDE 大版本再次移除入口时，这一行就是读数。
            LOG.info("[T-ConsoleBind] platform mapping contract: preferred="
                    + (available.isEmpty() ? "UNAVAILABLE" : available.get(0).id()) + ", available=" + available);
            return available;
        }
    }

    /**
     * 一条策略的<b>签名契约</b>：探测时会去哪些类上找哪个方法、参数类型是什么。
     *
     * <p>抽成数据是为了让契约测试能拿它去核对<b>真实平台 jar 的 class 文件</b>——263 的 class 是
     * Java 25 字节码（major 69），而测试 JVM 是 17，{@code Class.forName} 必然 {@code UnsupportedClassVersionError}，
     * 所以「真实 jar 校验」只能读字节不改类加载。两边共用这一份 spec，才不会出现「测试验的签名
     * 与生产查的签名不是同一组」。</p>
     *
     * @param owners 允许声明该方法的类（S1 在 263 实现类覆写、接口也声明——任一侧命中即可）
     * @param paramTypes 参数类型的<b>全限定名</b>（{@code VirtualFile} 等平台核心类型照常走类解析，
     *   {@code LocalDataSource} 来自 Database 插件、缺失时整条策略缺席）
     */
    record MappingSpec(
            MappingStrategy strategy,
            List<String> owners,
            String methodName,
            List<String> paramTypes
    ) {
    }

    /** 平台核心类型（恒在）；Database 侧类型见 {@link #LOCAL_DS_CLASS}。 */
    private static final String VIRTUAL_FILE_CLASS = "com.intellij.openapi.vfs.VirtualFile";
    private static final String PROJECT_CLASS = "com.intellij.openapi.project.Project";
    private static final String RUNNABLE_CLASS = "java.lang.Runnable";
    private static final String STRING_CLASS = "java.lang.String";

    /** 探测契约（顺序即策略优先级 S1→S4，见 {@link #selectMappingStrategies}）。 */
    static final List<MappingSpec> MAPPING_SPECS = List.of(
            new MappingSpec(
                    MappingStrategy.WRITE_ACTION,
                    List.of(PER_FILE_MAPPINGS_CLASS, PER_FILE_MAPPINGS_API_CLASS),
                    "setMappingWithWriteAction",
                    List.of(VIRTUAL_FILE_CLASS, LOCAL_DS_CLASS, RUNNABLE_CLASS)),
            new MappingSpec(
                    MappingStrategy.LEGACY_DS,
                    List.of(PER_FILE_MAPPINGS_CLASS),
                    "setMapping",
                    List.of(VIRTUAL_FILE_CLASS, LOCAL_DS_CLASS)),
            new MappingSpec(
                    MappingStrategy.STRING_ID,
                    List.of(PER_FILE_MAPPINGS_CLASS),
                    "setMapping",
                    List.of(VIRTUAL_FILE_CLASS, STRING_CLASS)),
            new MappingSpec(
                    MappingStrategy.STATIC_WRITE_ONLY,
                    List.of(PER_FILE_MAPPINGS_CLASS),
                    "setDataSourcePerFileMapping",
                    List.of(PROJECT_CLASS, VIRTUAL_FILE_CLASS, LOCAL_DS_CLASS))
    );

    /**
     * 无缓存探测：给定「类名 → Class」的来源，返回该平台上可用的写入策略集合（顺序恒 S1→S4）。
     *
     * <p><b>为什么要注入</b>：契约测试得能喂两种形态——合成夹具（钉住反射签名，防我们自己写错方法名）
     * 与真实平台 jar（见 {@link #MAPPING_SPECS} 的说明：263 只能读字节）。生产调用一律走
     * {@link #availableMappingStrategies} 的缓存版本。</p>
     *
     * <p>解析不到某个类（社区版 / 无 Database 插件）时对应策略直接缺席——不猜测、不兜底成「都有」。</p>
     */
    static List<MappingStrategy> detectMappingStrategies(Function<String, Class<?>> resolve) {
        HashMap<String, Class<?>> resolved = new HashMap<>(8);
        java.util.function.Function<String, Class<?>> classOf = fqcn -> {
            // Kotlin getOrPut(null) 语义：null 不落缓存，重复解析缺类（可接受——只发生在测试注入）
            if (!resolved.containsKey(fqcn)) resolved.put(fqcn, resolve.apply(fqcn));
            return resolved.get(fqcn);
        };

        List<MappingStrategy> available = new ArrayList<>(MappingStrategy.values().length);
        for (MappingSpec spec : MAPPING_SPECS) {
            // 任一参数类型解析不到 → 这条策略无法被反射命中（宁可缺席，也不猜「大概有」）。
            Class<?>[] paramClasses = new Class[spec.paramTypes().size()];
            boolean allResolved = true;
            for (int i = 0; i < spec.paramTypes().size(); i++) {
                Class<?> pc = classOf.apply(spec.paramTypes().get(i));
                if (pc == null) {
                    allResolved = false;
                    break;
                }
                paramClasses[i] = pc;
            }
            if (!allResolved) continue;
            boolean hit = false;
            for (String owner : spec.owners()) {
                Class<?> clazz = classOf.apply(owner);
                if (clazz == null) continue;
                try {
                    clazz.getMethod(spec.methodName(), paramClasses);
                    hit = true;
                    break;
                } catch (Throwable ignored) {
                    // 该 owner 上无此签名，试下一个
                }
            }
            if (hit) available.add(spec.strategy());
        }
        return available;
    }

    /** T-ConsoleBind263 §4.3：契约探针——首选策略名；平台无任何入口时 null（调用方只记日志）。 */
    @Nullable
    static String availableMappingStrategy() {
        List<MappingStrategy> available = availableMappingStrategies();
        return available.isEmpty() ? null : available.get(0).id();
    }

    /**
     * 对齐 exec 文件的 forced 关联：detach 挂载控制台（若在）+ 按 {@link #selectMappingStrategies} 的
     * 顺序重写 per-file 关联，每一步都用 {@link #probePerFileMappingId} 回读验证。
     *
     * <p>{@code intendedDs} 为 null = 清除关联（CLEAR：让选择器恢复全源列表；detach 仍必须做——
     * 挂载控制台 provider 同样会短路枚举）。</p>
     *
     * <p>为什么必须回读：263 的 S1 是异步协程（字节码实证），调用返回时写还没落地；只信「没抛异常」
     * 会把「看着成功、其实没写」再次放行——本次事故的用户体感正是「什么都没发生」。</p>
     */
    static RealignReport realignExecBind(Project project, VirtualFile vf, @Nullable Object intendedDs) {
        boolean detached;
        try {
            Class<?> provider = Class.forName(DataSourceSelector.CONSOLE_PROVIDER_CLASS);
            provider.getMethod("detachConsole", Project.class, VirtualFile.class)
                    .invoke(null, project, vf);
            detached = true;
        } catch (Throwable t) {
            LOG.warn("[T-ConsoleBind] detachConsole FAILED: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            detached = false;
        }

        String expectedId = null;
        if (intendedDs != null) {
            try {
                expectedId = DataSourceSettings.getUniqueId(intendedDs);
            } catch (Throwable t) {
                expectedId = null;
            }
        }
        boolean clearing = intendedDs == null;
        List<MappingStrategy> available = availableMappingStrategies();
        List<MappingStrategy> candidates = selectMappingStrategies(available, clearing);
        boolean readbackAvailable = cachedMethod(
                cachedClass(PER_FILE_MAPPINGS_CLASS),
                "getDataSourcePerFileMapping",
                Project.class, VirtualFile.class) != null;
        String observedId = probePerFileMappingId(project, vf);
        MappingStrategy strategy = null;
        boolean verified = false;
        String failure = null;

        if (candidates.isEmpty()) {
            // P0-3：失败必须可 grep——写明「无可用策略 + 本次语义」，不带异常（本机就是没有入口）。
            failure = "NO_STRATEGY_AVAILABLE(clearing=" + clearing + ")";
            LOG.warn("[T-ConsoleBind] setMapping UNAVAILABLE strategy=none reason=" + failure
                    + " available=" + available + " expected=" + expectedId);
        } else {
            for (MappingStrategy candidate : candidates) {
                boolean invokedOk;
                try {
                    invokeMappingStrategy(project, vf, intendedDs, candidate);
                    invokedOk = true;
                } catch (Throwable t) {
                    invokedOk = false;
                    failure = describeFailure(t);
                    LOG.warn("[T-ConsoleBind] setMapping FAILED strategy=" + candidate.id() + " reason=" + failure);
                    continue;
                }
                if (!readbackAvailable) {
                    // 平台连回读 API 都没有（社区版/极旧平台）：只能认「调用未抛」。
                    strategy = candidate;
                    verified = false;
                    failure = null;
                    LOG.info("[T-ConsoleBind] setMapping applied strategy=" + candidate.id() + " (readback unavailable)");
                    break;
                }
                observedId = probePerFileMappingId(project, vf);
                if (observedId != null && observedId.equals(expectedId)) {
                    strategy = candidate;
                    verified = true;
                    failure = null;
                    LOG.info("[T-ConsoleBind] setMapping applied strategy=" + candidate.id() + " observed=" + observedId);
                    break;
                }
                // S1 在 263 上必然走到这里（异步）：下一候选就是同步兜底，属预期路径，记 INFO 不刷 WARN。
                failure = "READBACK_MISMATCH expected=" + expectedId + " observed=" + observedId;
                LOG.info("[T-ConsoleBind] setMapping not applied yet strategy=" + candidate.id() + " reason=" + failure);
            }
        }

        boolean mapped = strategy != null;
        if (!mapped && !candidates.isEmpty()) {
            LOG.warn("[T-ConsoleBind] setMapping EXHAUSTED strategy=none reason=" + (failure != null ? failure : "unknown")
                    + " available=" + available + " expected=" + expectedId + " observed=" + observedId);
        }
        LOG.info("[T-ConsoleBind] exec file pin realigned: detach=" + detached + ", mapped=" + mapped
                + ", strategy=" + (strategy != null ? strategy.id() : "none")
                + ", expected=" + expectedId + ", observed=" + observedId + ", verified=" + verified);
        return new RealignReport(detached, mapped, strategy, expectedId, observedId, verified, failure);
    }

    /**
     * 按策略实际写一次（不验证）。S3/S4 走 {@code PerFileMappingsBase.setMapping}，与平台自己的
     * {@code edtWriteAction} 同形——EDT 上必须包写命令，否则 {@code handleMappingChange → reparseFiles}
     * 会以 {@code Write-unsafe context} 收场（DialectPreMapping 同类实证）。
     */
    private static void invokeMappingStrategy(
            Project project,
            VirtualFile vf,
            @Nullable Object intendedDs,
            MappingStrategy strategy) throws Exception {
        switch (strategy) {
            case WRITE_ACTION -> {
                Object instance = mappingInstance(project);
                if (instance == null) {
                    throw new IllegalStateException("DataSourcePerFileMappings.getInstance(project) = null");
                }
                Method method = cachedMethod(
                        instance.getClass(), "setMappingWithWriteAction",
                        VirtualFile.class, cachedClass(LOCAL_DS_CLASS), Runnable.class);
                if (method == null) {
                    throw new NoSuchMethodException(instance.getClass().getName() + ".setMappingWithWriteAction");
                }
                method.invoke(instance, vf, intendedDs, null);
            }
            case LEGACY_DS -> {
                Object instance = mappingInstance(project);
                if (instance == null) {
                    throw new IllegalStateException("DataSourcePerFileMappings.getInstance(project) = null");
                }
                Method method = cachedMethod(
                        instance.getClass(), "setMapping",
                        VirtualFile.class, cachedClass(LOCAL_DS_CLASS));
                if (method == null) {
                    throw new NoSuchMethodException(instance.getClass().getName() + ".setMapping(VirtualFile,LocalDataSource)");
                }
                method.invoke(instance, vf, intendedDs);
            }
            case STRING_ID -> {
                Object instance = mappingInstance(project);
                if (instance == null) {
                    throw new IllegalStateException("DataSourcePerFileMappings.getInstance(project) = null");
                }
                String uniqueId = intendedDs != null
                        ? tryGetUniqueId(intendedDs) : null;
                Method method = cachedMethod(
                        instance.getClass(), "setMapping", VirtualFile.class, String.class);
                if (method == null) {
                    throw new NoSuchMethodException(instance.getClass().getName() + ".setMapping(VirtualFile,String)");
                }
                Method m = method;
                inWriteCommand(project, () -> {
                    try {
                        m.invoke(instance, vf, uniqueId);
                    } catch (Exception e) {
                        throw sneakyThrow(e);
                    }
                });
            }
            case STATIC_WRITE_ONLY -> {
                // 选择规则已排除 CLEAR；这里再守一道（配置错误不得变成 NPE）。
                if (intendedDs == null) {
                    throw new IllegalArgumentException("STATIC_WRITE_ONLY 不能用于 CLEAR（平台内部直接取 ds.uniqueId）");
                }
                Class<?> impl = cachedClass(PER_FILE_MAPPINGS_CLASS);
                if (impl == null) {
                    throw new ClassNotFoundException(PER_FILE_MAPPINGS_CLASS);
                }
                Method method = cachedMethod(
                        impl, "setDataSourcePerFileMapping",
                        Project.class, VirtualFile.class, cachedClass(LOCAL_DS_CLASS));
                if (method == null) {
                    throw new NoSuchMethodException(PER_FILE_MAPPINGS_CLASS + ".setDataSourcePerFileMapping");
                }
                Method m = method;
                Object ds = intendedDs;
                inWriteCommand(project, () -> {
                    try {
                        m.invoke(null, project, vf, ds);
                    } catch (Exception e) {
                        throw sneakyThrow(e);
                    }
                });
            }
        }
    }

    @Nullable
    private static String tryGetUniqueId(Object ds) {
        try {
            return DataSourceSettings.getUniqueId(ds);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 模型写：EDT 上包写命令；已是写上下文或不在 EDT（平台会自己拒绝）则原样调用。 */
    private static void inWriteCommand(Project project, Runnable block) {
        Application app = ApplicationManager.getApplication();
        if (app == null || app.isWriteAccessAllowed() || !app.isDispatchThread()) {
            block.run();
            return;
        }
        // 显式构造 Runnable：runWriteCommandAction 另有 (Project, Computable) 重载，
        // Kotlin 侧直接传 lambda 会撞 SAM 歧义（编译期实测）——Java 无此歧义，保持显式形态。
        WriteCommandAction.runWriteCommandAction(project, block);
    }

    @Nullable
    private static Object mappingInstance(Project project) {
        try {
            Class<?> impl = cachedClass(PER_FILE_MAPPINGS_CLASS);
            if (impl == null) return null;
            Method getInstance = cachedMethod(impl, "getInstance", Project.class);
            return getInstance != null ? getInstance.invoke(null, project) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 回读 per-file 关联的身份（{@code DataSourcePerFileMappingsImpl.getDataSourcePerFileMapping}）。
     * 探测不可用/异常 → null（调用方用 {@link RealignReport#verified} 区分「没写」与「读不到」）。
     */
    @Nullable
    private static String probePerFileMappingId(Project project, VirtualFile vf) {
        try {
            Class<?> impl = cachedClass(PER_FILE_MAPPINGS_CLASS);
            if (impl == null) return null;
            Method method = cachedMethod(impl, "getDataSourcePerFileMapping", Project.class, VirtualFile.class);
            if (method == null) return null;
            Object ds = method.invoke(null, project, vf);
            return ds != null ? DataSourceSettings.getUniqueId(ds) : null;
        } catch (Throwable t) {
            LOG.info("[T-ConsoleBind] probe per-file mapping FAILED (treat as unmapped): "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    /** P0-3：失败归因串（异常类型 + 首行消息），保证 {@code grep strategy=S} 能一次捞到。 */
    private static String describeFailure(@Nullable Throwable error) {
        Throwable cause = error instanceof java.lang.reflect.InvocationTargetException && ((java.lang.reflect.InvocationTargetException) error).getTargetException() != null
                ? ((java.lang.reflect.InvocationTargetException) error).getTargetException()
                : error;
        String name = cause != null ? cause.getClass().getSimpleName() : "UnknownError";
        String message = "";
        if (cause != null && cause.getMessage() != null) {
            String firstLine = cause.getMessage().split("\n", 2)[0];
            message = firstLine.length() > 200 ? firstLine.substring(0, 200) : firstLine;
        }
        return (name + ": " + message).trim();
    }

    /** Runnable 不能抛受检异常——sneaky 透传保持 describeFailure 看到原始反射异常（与 Kotlin 直抛同语义）。 */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    // ---------- 反射 Class/Method 记忆化（沿用 DataSourceSettings 的 F68a 范式） ----------
    //
    // 换源是用户动作、频次低，但每次都会走 settle 链；负缓存用 Optional（CHM 禁 null），
    // 键含类名 + 方法签名防跨类/跨重载撞键。

    @Nullable
    private static volatile List<MappingStrategy> probedStrategies = null;

    private static final ConcurrentHashMap<String, Optional<Class<?>>> CLASS_CACHE = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    @Nullable
    private static Class<?> cachedClass(String fqcn) {
        return CLASS_CACHE.computeIfAbsent(fqcn, k -> {
            try {
                return Optional.of(Class.forName(k));
            } catch (Throwable t) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    @Nullable
    private static Method cachedMethod(@Nullable Class<?> clazz, String name, Class<?>... paramTypes) {
        if (clazz == null) return null;
        for (Class<?> t : paramTypes) {
            if (t == null) return null;
        }
        StringBuilder key = new StringBuilder(clazz.getName()).append('#').append(name).append('(');
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) key.append(',');
            key.append(paramTypes[i].getName());
        }
        key.append(')');
        return METHOD_CACHE.computeIfAbsent(key.toString(), k -> {
            try {
                return Optional.of(clazz.getMethod(name, paramTypes));
            } catch (Throwable t) {
                return Optional.empty();
            }
        }).orElse(null);
    }
}
