package cn.song.mybatisplus.visualizer.execute;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.util.List;
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
 */
final class ExecFileBinding {

    private static final Logger LOG = Logger.getInstance(ExecFileBinding.class);

    /** per-file 关联实现类（262 平台 jar 实证：{@code com.intellij.database.dataSource.DataSourcePerFileMappingsImpl}）。 */
    private static final String PER_FILE_MAPPINGS_CLASS = "com.intellij.database.dataSource.DataSourcePerFileMappingsImpl";

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
     * @param forceChooserPopup 用户显式要求弹出选择器（forceChoose ∥ forceChoosePopup）——
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
        String mappingId;
        try {
            Class<?> impl = Class.forName(PER_FILE_MAPPINGS_CLASS);
            Object ds = impl.getMethod("getDataSourcePerFileMapping", Project.class, VirtualFile.class)
                    .invoke(null, project, vf);
            mappingId = ds != null ? DataSourceSettings.getUniqueId(ds) : null;
        } catch (Throwable t) {
            LOG.info("[T-ConsoleBind] probe per-file mapping FAILED (treat as unmapped): "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            mappingId = null;
        }
        return new ExecBindProbe(consoleId, mappingId);
    }

    /**
     * 对齐 exec 文件的 forced 关联：detach 挂载控制台（若在）+ 重写 per-file 关联。
     * {@code intendedDs} 为 null = 清除关联（CLEAR：让选择器恢复全源列表；detach 仍必须做——
     * 挂载控制台 provider 同样会短路枚举）。返回是否两步都成功（调用方据此决定是否作废
     * 共享/已备控制台缓存——被 detach 的可能正是内嵌在预览面板里的那个控制台）。
     *
     * <p>平台 API 出处（262 javap 实证，均 public）：</p>
     * <ul>
     *  <li>{@code JdbcConsoleProvider.detachConsole(Project, VirtualFile)}：getConsole → Disposer.dispose
     *      → removeConsoleToolbar → updatePresentation；</li>
     *  <li>{@code DataSourcePerFileMappingsImpl.setMapping(VirtualFile, LocalDataSource?)}：委托
     *      {@code PerFileMappingsBase.setMapping}（null 值走 {@code Map.remove} 清除，字节码实证），
     *      并广播 {@code OnMappingsChangedListener}。</li>
     * </ul>
     */
    static boolean realignExecBind(Project project, VirtualFile vf, @Nullable Object intendedDs) {
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
        boolean remapped;
        try {
            Class<?> impl = Class.forName(PER_FILE_MAPPINGS_CLASS);
            Object instance = impl.getMethod("getInstance", Project.class).invoke(null, project);
            impl.getMethod("setMapping", VirtualFile.class,
                            Class.forName("com.intellij.database.dataSource.LocalDataSource"))
                    .invoke(instance, vf, intendedDs);
            remapped = true;
        } catch (Throwable t) {
            LOG.warn("[T-ConsoleBind] setMapping FAILED: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            remapped = false;
        }
        LOG.info("[T-ConsoleBind] exec file pin realigned (detach=" + detached + ", remap=" + remapped
                + ", target=" + (intendedDs != null ? intendedDs.getClass().getSimpleName() : "null") + ")");
        return detached && remapped;
    }
}
