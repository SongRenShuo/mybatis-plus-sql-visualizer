package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * T-ConsoleBind（2026-10-28）：执行控制台与预览绑定数据源的一致性闸门。
 *
 * **缺陷**（2026-10-28 录制现场实测）：预览面板头部显示「已绑定：mp_demo@localhost」，点
 * 执行 SQL / 执行计划 / 回滚 SQL-快照查询却在 mpj_demo 上跑（`Unknown column 'deleted'`
 * ——该列只在 mp_demo.user 存在）；对数据源节点 Close All Sessions 也不能改绑。
 *
 * **根因（262 平台 jar 反编译实证，两层叠加）**：
 *
 *  1. 平台 forced 关联短路——插件的 exec 文件路径稳定（T-PreviewFlash：
 *     `<projectBase>/.idea/sql-visualizer/exec/mpsql-exec.sql`），而平台 `JdbcConsoleProvider
 *     .attachConsole` 每次挂控制台都会把「文件→数据源」per-file 关联写死（字节码内
 *     `DataSourcePerFileMappings.setMappingAsync`）并挂上控制台；
 *     `DatabaseRunners.getAttachDataSourceRunners` 入口先查
 *     `DbImplUtil.getForcedDataSourceForExec`（→ `VirtualFileDataSourceProvider` EP：
 *     per-file 关联在前、挂载控制台在后），命中即 `singletonList(该源)` 返回——
 *     插件侧的 last-used 静默命中、preferredRunnerName、选择器弹窗全部被短路成
 *     「唯一 runner 直接跑」。旧经验（20261024-1914 第十二轮）所谓「无公开清除 API」
 *     在 262 已不成立：`JdbcConsoleProvider.detachConsole(Project, VirtualFile)` 与
 *     `DataSourcePerFileMappings.setMapping`（null 值走 `PerFileMappingsBase` 的
 *     `Map.remove`）均为 public，本对齐器即基于这两个面。
 *  2. 插件复用按名不按身份——[DataSourceSelector.reuseSharedConsole] 的复用判定是
 *     「last-used 记忆名 == 建共享缓存时的记录名」，从不探测共享控制台**当前实际绑定**
 *     的数据源身份。控制台被任何路径重绑（native 切换、被钉源全链跑一次）后，复用链
 *     照旧放行；头部绑定行又是事件驱动刷新，滞后于翻转——用户看到「已绑定 mp_demo」
 *     而执行落在 mpj_demo。Close All Sessions 无效同理：per-file 关联持久化，不随会话
 *     关闭清除，且 provider 顺序在挂载控制台之前。
 *
 * **修法**（对齐，不重写）：
 *  - 复用判定升级为身份制：预览有明确绑定目标（UUID）时，共享控制台必须「当前实际绑定」
 *    同一数据源才可复用（[resolveSharedConsoleReuse]）；无目标身份时保留旧名字判定逐字不变。
 *  - 全链枚举前把 exec 文件的 forced 关联对齐到绑定目标（[realignExecBind]：detach 陈旧
 *    控制台 + 重写 per-file 关联），用户显式要求换源时清关联让弹窗恢复全源列表
 *    （[resolveBindAction]）。同一数据源下重复预览 pin 已一致 → NOOP，复用语义不变。
 *
 * **身份键**：`LocalDataSource.getUniqueId()`（与 T-SI-02 last-used 身份键、
 * T-ConsoleDbContext 探测、ModuleDataSourceBindingStore 学习绑定同一空间）。
 * 隔离约定同 [ExecConsoleContext]：仅 Database 插件链内调用，Community 下
 * `Class.forName` 失败 → runCatching 全部退化为「不动作」，绝不影响主链。
 */
internal object ExecFileBinding {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ExecFileBinding::class.java)

    /** per-file 关联实现类（262 平台 jar 实证：`com.intellij.database.dataSource.DataSourcePerFileMappingsImpl`）。 */
    private const val PER_FILE_MAPPINGS_CLASS = "com.intellij.database.dataSource.DataSourcePerFileMappingsImpl"

    // ------------------------------------------------------------------
    // 纯判定（headless 表驱动直测；不触平台类）
    // ------------------------------------------------------------------

    /** exec 文件 forced 关联探针结果（两项独立可空；全 null = 无钉源/探测不可用）。 */
    internal data class ExecBindProbe(
        /** 挂载控制台绑定的数据源 UUID（`JdbcConsoleProvider.getValidConsole` → `getDataSource`）。 */
        val attachedConsoleDsId: String?,
        /** per-file 关联的数据源 UUID（`DataSourcePerFileMappingsImpl.getDataSourcePerFileMapping`）。 */
        val perFileMappingDsId: String?,
    ) {
        /**
         * 平台实际的钉源身份——provider 顺序决定优先级（plugin.xml 实证：
         * `DbConsoleRootDataSourceProvider`（per-file 关联）在前，`JdbcConsoleDataSourceProvider`
         * （挂载控制台）在后；forced 枚举短路按同一顺序取第一个命中）。
         */
        val pinnedDsId: String? get() = perFileMappingDsId ?: attachedConsoleDsId
    }

    enum class Action {
        /** pin 不存在 / 已与目标一致 / 无目标身份（含冲突不猜）——不动平台状态。 */
        NOOP,

        /** pin 与目标不一致——detach 陈旧控制台并把 per-file 关联重写到目标数据源。 */
        REALIGN,

        /** 用户显式要求换源（forceChoose 族）——清 pin（detach + 清关联），让选择器恢复全源列表。 */
        CLEAR,
    }

    data class BindAction(val action: Action, val intendedDsId: String? = null)

    /**
     * exec 文件 pin 的处置判定（纯函数）。
     *
     * @param forceChooserPopup 用户显式要求弹出选择器（forceChoose ∥ forceChoosePopup）——
     *        此时 pin 存在会把枚举短路成单源，必须清除才能恢复「列全部源」的弹窗语义
     * @param intendedDsId 本次预览的绑定目标身份（null = 无可信目标：无记忆/学习与记忆冲突/同名歧义——不猜）
     */
    fun resolveBindAction(forceChooserPopup: Boolean, intendedDsId: String?, probe: ExecBindProbe): BindAction {
        val pinned = probe.pinnedDsId ?: return BindAction(Action.NOOP)
        if (forceChooserPopup) return BindAction(Action.CLEAR)
        if (intendedDsId.isNullOrBlank()) return BindAction(Action.NOOP)
        return if (pinned == intendedDsId) BindAction(Action.NOOP) else BindAction(Action.REALIGN, intendedDsId)
    }

    /**
     * 共享控制台复用身份判定（纯函数；[DataSourceSelector.reuseSharedConsole] 的核心判据）。
     *
     * 有预览绑定目标（[intendedDsId] 非空）时**身份制**：共享控制台「当前实际绑定」
     * （[liveConsoleDsId] 现场探测）必须等于目标——这正是本缺陷的主判据：预览绑定
     * mp_demo（uuid-mp）时不得复用实际绑在 mpj_demo（uuid-mpj）上的控制台，哪怕记忆名
     * 与建缓存记录名都是 mp_demo（旧判据在此形态下放行）。探不到身份（[liveConsoleDsId]
     * null）= 身份不可信，宁冷启不复用（跑错库的代价远大于重建一次控制台）。
     *
     * 无目标身份时保留旧名字判定**逐字不变**（`lastRunnerName(project) == sharedDataSourceName`，
     * 含记忆缺失即不复用的原语义）。
     */
    fun resolveSharedConsoleReuse(
        intendedDsId: String?,
        liveConsoleDsId: String?,
        legacyMemoryName: String?,
        legacySharedRecordedName: String?,
    ): Boolean =
        if (!intendedDsId.isNullOrBlank()) {
            liveConsoleDsId != null && liveConsoleDsId == intendedDsId
        } else {
            !legacyMemoryName.isNullOrBlank() && legacyMemoryName == legacySharedRecordedName
        }

    /**
     * 预览绑定目标解析核心（纯函数，假数据源模型直测）。
     *
     * 优先级：显式指定（在册唯一命中才认，歧义不猜）> 模块学习 > last-used（UUID 失效退
     * 名字唯一命中）。学习与 last-used 并存且不同 → null（冲突不猜，与 EXPLAIN 回落同规）。
     * 返回数据源**对象**（realign 重写 per-file 关联需要），身份判定全部走 [idOf]。
     *
     * @param registered 在册数据源对象（生产 = `DataSourceSettings.getAllDataSources`）
     * @param byId UUID → 数据源对象（生产 = `DataSourceSettings.getDataSourceById`）
     */
    internal fun resolveIntendedDataSourceCore(
        registered: List<Any>,
        nameOf: (Any) -> String?,
        idOf: (Any) -> String?,
        byId: (String) -> Any?,
        learnedId: String?,
        memory: DataSourceSelector.LastUsedMemory,
        preferredRunnerName: String?,
    ): Any? {
        val byUniqueName: (String) -> Any? = { name ->
            registered.filter { nameOf(it) == name }.singleOrNull()
        }
        // ① 显式指定：唯一命中即目标（压过学习/记忆冲突——用户此刻的显式选择就是意图本身）
        preferredRunnerName?.takeIf { it.isNotBlank() }?.let(byUniqueName)?.let { return it }
        // ② last-used → 数据源对象：UUID 精确（数据源被改名也认），失效退名字唯一命中
        val lastDs = memory.dataSourceId?.takeIf { it.isNotBlank() }?.let(byId)
            ?: memory.displayName?.takeIf { it.isNotBlank() }?.let(byUniqueName)
        // ③ 学习 vs last-used 冲突不猜（与 explainFallbackDataSource 同规，交选择器自愈）
        val learned = learnedId?.takeIf { it.isNotBlank() }
        val resolvedId = DataSourceSelector.resolveExplainFallbackChoice(learned, lastDs?.let(idOf)) ?: return null
        return if (resolvedId == learned) learned?.let(byId) else lastDs
    }

    /**
     * 生产包装：解析本次预览的绑定目标数据源对象。
     * 任何平台反射失败都退化为 null（NOOP，既有行为），绝不阻断准备链。
     */
    internal fun resolveIntendedDataSource(
        project: Project,
        moduleKey: String?,
        preferredRunnerName: String?,
    ): Any? {
        val registered = runCatching { DataSourceSettings.getAllDataSources(project) }.getOrDefault(emptyList())
        if (registered.isEmpty()) return null
        val learnedId = moduleKey?.takeIf { it.isNotBlank() }?.let { key ->
            runCatching {
                cn.song.mybatisplus.visualizer.sql.ModuleDataSourceBindingStore.getInstance(project)
                    .learnedCandidate(project, key)?.id
            }.getOrNull()
        }
        return resolveIntendedDataSourceCore(
            registered,
            { ds -> runCatching { DataSourceSettings.getName(ds) }.getOrNull() },
            { ds -> runCatching { DataSourceSettings.getUniqueId(ds) }.getOrNull() },
            { id -> runCatching { DataSourceSettings.getDataSourceById(project, id) }.getOrNull() },
            learnedId,
            DataSourceSelector.lastUsedRunner(project, moduleKey),
            preferredRunnerName,
        )
    }

    // ------------------------------------------------------------------
    // 反射探测与对齐（仅 Database 插件链内调用；失败一律 fail-closed 不动作）
    // ------------------------------------------------------------------

    /**
     * 探测 exec 文件当前被平台钉住的数据源身份（[ExecBindProbe]）。
     * Community / 无 Database 插件 / 反射任何一环失败 → 全 null（NOOP）。
     */
    fun probeExecBind(project: Project, vf: VirtualFile): ExecBindProbe {
        val consoleId = runCatching {
            val provider = Class.forName(DataSourceSelector.CONSOLE_PROVIDER_CLASS)
            val console = provider.getMethod("getValidConsole", Project::class.java, VirtualFile::class.java)
                .invoke(null, project, vf) ?: return@runCatching null
            DataSourceSelector.consoleDataSourceId(console)
        }.onFailure {
            LOG.info("[T-ConsoleBind] probe attached console FAILED (treat as unpinned): ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
        val mappingId = runCatching {
            val impl = Class.forName(PER_FILE_MAPPINGS_CLASS)
            val ds = impl.getMethod("getDataSourcePerFileMapping", Project::class.java, VirtualFile::class.java)
                .invoke(null, project, vf) ?: return@runCatching null
            DataSourceSettings.getUniqueId(ds)
        }.onFailure {
            LOG.info("[T-ConsoleBind] probe per-file mapping FAILED (treat as unmapped): ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
        return ExecBindProbe(consoleId, mappingId)
    }

    /**
     * 对齐 exec 文件的 forced 关联：detach 挂载控制台（若在）+ 重写 per-file 关联。
     * [intendedDs] 为 null = 清除关联（CLEAR：让选择器恢复全源列表；detach 仍必须做——
     * 挂载控制台 provider 同样会短路枚举）。返回是否两步都成功（调用方据此决定是否作废
     * 共享/已备控制台缓存——被 detach 的可能正是内嵌在预览面板里的那个控制台）。
     *
     * 平台 API 出处（262 javap 实证，均 public）：
     *  - `JdbcConsoleProvider.detachConsole(Project, VirtualFile)`：getConsole → Disposer.dispose
     *    → removeConsoleToolbar → updatePresentation；
     *  - `DataSourcePerFileMappingsImpl.setMapping(VirtualFile, LocalDataSource?)`：委托
     *    `PerFileMappingsBase.setMapping`（null 值走 `Map.remove` 清除，字节码实证），
     *    并广播 `OnMappingsChangedListener`。
     */
    fun realignExecBind(project: Project, vf: VirtualFile, intendedDs: Any?): Boolean {
        val detached = runCatching {
            val provider = Class.forName(DataSourceSelector.CONSOLE_PROVIDER_CLASS)
            provider.getMethod("detachConsole", Project::class.java, VirtualFile::class.java)
                .invoke(null, project, vf)
            true
        }.onFailure {
            LOG.warn("[T-ConsoleBind] detachConsole FAILED: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
        val remapped = runCatching {
            val impl = Class.forName(PER_FILE_MAPPINGS_CLASS)
            val instance = impl.getMethod("getInstance", Project::class.java).invoke(null, project)
            impl.getMethod(
                "setMapping",
                VirtualFile::class.java,
                Class.forName("com.intellij.database.dataSource.LocalDataSource"),
            ).invoke(instance, vf, intendedDs)
            true
        }.onFailure {
            LOG.warn("[T-ConsoleBind] setMapping FAILED: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
        LOG.info("[T-ConsoleBind] exec file pin realigned (detach=$detached, remap=$remapped, target=${intendedDs?.javaClass?.simpleName})")
        return detached && remapped
    }
}
