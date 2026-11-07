package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
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
 *
 * **T-ConsoleBind263（2026-11-07）**：IDE 2026.3（IU-263.7307.200）移除了
 * `DataSourcePerFileMappingsImpl.setMapping(VirtualFile, LocalDataSource)`——上面第 1 层修法里
 * 的写关联那一步因此恒抛 `NoSuchMethodException`，「清 pin」只剩 detach、per-file 关联没被清，
 * 平台 forced 关联把 runner 枚举继续短路成单源（换源弹窗只剩当前源；工具栏点选 `mpj_demo`
 * 静默落到被钉住的 Oracle 源）。写入口改为**多版本策略链** S1→S2→S3→S4（[selectMappingStrategies]）：
 * 263 走 S1 `setMappingWithWriteAction`，而它**是异步协程**（见 [MappingStrategy] KDoc），
 * 所以要写后回读（[probePerFileMappingId]）并以 S3 `setMapping(VirtualFile, String)` 同步兜底。
 */
internal object ExecFileBinding {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ExecFileBinding::class.java)

    /** per-file 关联实现类（262 平台 jar 实证：`com.intellij.database.dataSource.DataSourcePerFileMappingsImpl`）。 */
    private const val PER_FILE_MAPPINGS_CLASS = "com.intellij.database.dataSource.DataSourcePerFileMappingsImpl"

    /** per-file 关联接口类（263 起 `setMappingWithWriteAction` 声明在接口上；两边都探，防形态差异）。 */
    private const val PER_FILE_MAPPINGS_API_CLASS = "com.intellij.database.console.DataSourcePerFileMappings"

    /** 数据源类（反射参数类型必须来自同一 classloader，不能用编译期引用）。 */
    private const val LOCAL_DS_CLASS = "com.intellij.database.dataSource.LocalDataSource"

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
        val mappingId = probePerFileMappingId(project, vf)
        return ExecBindProbe(consoleId, mappingId)
    }

    // ------------------------------------------------------------------
    // T-ConsoleBind263（2026-11-07）：per-file 关联写入的多版本策略链
    // ------------------------------------------------------------------
    //
    // **缺陷**：IDE 2026.3（IU-263.7307.200）移除了
    // `DataSourcePerFileMappingsImpl.setMapping(VirtualFile, LocalDataSource)`，旧反射恒抛
    // `NoSuchMethodException` → pin 清不掉 → 平台 forced 关联继续短路 runner 枚举，换源弹窗
    // 只剩当前源、工具栏点选静默落到被钉住的源（现场日志 idea.1.log:27899-28040）。
    //
    // **263 字节码实证（本单取证，`intellij.database.core.impl.ex.jar`）**：
    //  - `setMapping(VirtualFile, String)`：`DbVFSUtils.isConsoleFile(vf)` 时恒清（`setMapping(vf, null)`），
    //    否则写 id 并在 `value != null` 时广播 `OnMappingsChangedListener`；
    //  - `setMappingWithWriteAction(vf, ds, callback)`：**异步**——`BuildersKt.launch(coroutineScope,
    //    Dispatchers.getEDT(), ...)` → 协程体 `edtWriteAction { setMapping(vf, ds?.uniqueId) }` → callback。
    //    故「调用后立刻回读」在 EDT 上必然读到旧值（§八 V1 前提被推翻）→ 必须由同步策略兜底；
    //  - 静态 `setDataSourcePerFileMapping(project, vf, ds)`：`getInstance(project).setMapping(vf,
    //    ds.getUniqueId())`，**ds 非空无判空** → 只能写、不能清（CLEAR 严禁走它）。
    //  - 静态 `getDataSourcePerFileMapping(project, vf)`：`getMapping(vf)` →
    //    `DataSourceStorage.getDataSourceById(id)`，是本单「写后回读」的判据来源。

    /**
     * per-file 关联写入策略（按「新 → 旧 → 只写」顺序探测，见 [selectMappingStrategies]）。
     *
     * @param writeOnly 只能写、不能清（`ds=null` 会 NPE 或静默失败）——CLEAR 必须排除
     */
    internal enum class MappingStrategy(val id: String, val writeOnly: Boolean) {
        /** 263+：`setMappingWithWriteAction(VirtualFile, LocalDataSource?, Runnable?)`（平台自派写动作，异步）。 */
        WRITE_ACTION("S1_setMappingWithWriteAction", false),

        /** ≤262：`setMapping(VirtualFile, LocalDataSource)`（263 已移除）。 */
        LEGACY_DS("S2_setMapping(VirtualFile,LocalDataSource)", false),

        /** 兜底同步写：`setMapping(VirtualFile, String)`（null = 清除；263 字节码语义已实证）。 */
        STRING_ID("S3_setMapping(VirtualFile,String)", false),

        /** 最后兜底：静态 `setDataSourcePerFileMapping(project, vf, ds)`，只能写。 */
        STATIC_WRITE_ONLY("S4_setDataSourcePerFileMapping", true),
    }

    /**
     * 纯函数：给定平台可用策略集合与本次语义（清/写），返回**实际尝试顺序** S1→S2→S3→S4。
     *
     * CLEAR（[clearing]=true，`intendedDs == null`）必须剔除只能写的 S4——那是 263 的
     * `setDataSourcePerFileMapping`：字节码里直接 `ds.getUniqueId()` 无判空，传 null 必 NPE。
     * 可用集合按探测结果传入，顺序**不随传入顺序变化**（表驱动单测钉死）。
     */
    internal fun selectMappingStrategies(
        available: Collection<MappingStrategy>,
        clearing: Boolean,
    ): List<MappingStrategy> = MappingStrategy.values().filter { candidate ->
        candidate in available && !(clearing && candidate.writeOnly)
    }

    /**
     * 一次对齐的结果（策略名 + 是否落地 + 回读到的身份）——日志与上层判定共用，
     * 取代旧实现的单一布尔（旧日志分不清「清了」与「写了」，本次事故正是因此只剩一条 WARN）。
     *
     * @param detached `JdbcConsoleProvider.detachConsole` 是否成功
     * @param mapped per-file 关联是否写到位（[verified]=false 时为「调用未抛异常」）
     * @param strategy 实际生效的策略（未生效为 null）
     * @param expectedId 期望的关联身份（null = 清除）
     * @param observedId 回读到的关联身份（探测不可用为 null）
     * @param verified 回读是否可用并与期望一致（无回读能力时为 false，不代表失败）
     * @param failure 最后一次失败的归因串（`<异常类型>: <首行消息>`，或 `READBACK_MISMATCH ...`；成功为 null）
     */
    internal data class RealignReport(
        val detached: Boolean,
        val mapped: Boolean,
        val strategy: MappingStrategy?,
        val expectedId: String?,
        val observedId: String?,
        val verified: Boolean,
        val failure: String?,
    ) {
        /** 两步都到位（调用方据此决定是否作废共享/已备控制台缓存）。 */
        val applied: Boolean get() = detached && mapped
    }

    /** 平台可用的写入策略（按固定顺序；探测结果进程内一次性缓存）。 */
    internal fun availableMappingStrategies(): List<MappingStrategy> {
        probedStrategies?.let { return it }
        synchronized(this) {
            probedStrategies?.let { return it }
            val impl = cachedClass(PER_FILE_MAPPINGS_CLASS)
            val localDs = cachedClass(LOCAL_DS_CLASS)
            val api = cachedClass(PER_FILE_MAPPINGS_API_CLASS)
            val available = ArrayList<MappingStrategy>(MappingStrategy.values().size)
            // S1：263 在实现类覆写、接口声明——任一侧可解析即视为可用（两边都查，避免代理/接口形态差异）
            if (localDs != null) {
                if (cachedMethod(impl, "setMappingWithWriteAction", VirtualFile::class.java, localDs, Runnable::class.java) != null ||
                    cachedMethod(api, "setMappingWithWriteAction", VirtualFile::class.java, localDs, Runnable::class.java) != null
                ) {
                    available += MappingStrategy.WRITE_ACTION
                }
                if (cachedMethod(impl, "setMapping", VirtualFile::class.java, localDs) != null) {
                    available += MappingStrategy.LEGACY_DS
                }
            }
            if (cachedMethod(impl, "setMapping", VirtualFile::class.java, String::class.java) != null) {
                available += MappingStrategy.STRING_ID
            }
            if (localDs != null &&
                cachedMethod(
                    impl,
                    "setDataSourcePerFileMapping",
                    Project::class.java,
                    VirtualFile::class.java,
                    localDs,
                ) != null
            ) {
                available += MappingStrategy.STATIC_WRITE_ONLY
            }
            probedStrategies = available
            // T-ConsoleBind263 §4.3：契约探针**首次换源时**打一行（D2 定案：不放启动——社区版/无
            // Database 插件场景不该多付一次类加载）。IDE 大版本再次移除入口时，这一行就是读数。
            LOG.info("[T-ConsoleBind] platform mapping contract: preferred=${available.firstOrNull()?.id ?: "UNAVAILABLE"}, available=$available")
            return available
        }
    }

    /** T-ConsoleBind263 §4.3：契约探针——首选策略名；平台无任何入口时 null（调用方只记日志）。 */
    internal fun availableMappingStrategy(): String? = availableMappingStrategies().firstOrNull()?.id

    /**
     * 对齐 exec 文件的 forced 关联：detach 挂载控制台（若在）+ 按 [selectMappingStrategies] 的
     * 顺序重写 per-file 关联，每一步都用 [probePerFileMappingId] 回读验证。
     *
     * [intendedDs] 为 null = 清除关联（CLEAR：让选择器恢复全源列表；detach 仍必须做——
     * 挂载控制台 provider 同样会短路枚举）。
     *
     * 为什么必须回读：263 的 S1 是异步协程（字节码实证），调用返回时写还没落地；只信「没抛异常」
     * 会把「看着成功、其实没写」再次放行——本次事故的用户体感正是「什么都没发生」。
     */
    fun realignExecBind(project: Project, vf: VirtualFile, intendedDs: Any?): RealignReport {
        val detached = runCatching {
            val provider = Class.forName(DataSourceSelector.CONSOLE_PROVIDER_CLASS)
            provider.getMethod("detachConsole", Project::class.java, VirtualFile::class.java)
                .invoke(null, project, vf)
            true
        }.onFailure {
            LOG.warn("[T-ConsoleBind] detachConsole FAILED: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)

        val expectedId = intendedDs?.let { runCatching { DataSourceSettings.getUniqueId(it) }.getOrNull() }
        val clearing = intendedDs == null
        val available = availableMappingStrategies()
        val candidates = selectMappingStrategies(available, clearing)
        val readbackAvailable = cachedMethod(
            cachedClass(PER_FILE_MAPPINGS_CLASS),
            "getDataSourcePerFileMapping",
            Project::class.java,
            VirtualFile::class.java,
        ) != null
        var observedId = probePerFileMappingId(project, vf)
        var strategy: MappingStrategy? = null
        var verified = false
        var failure: String? = null

        if (candidates.isEmpty()) {
            // P0-3：失败必须可 grep——写明「无可用策略 + 本次语义」，不带异常（本机就是没有入口）。
            failure = "NO_STRATEGY_AVAILABLE(clearing=$clearing)"
            LOG.warn(
                "[T-ConsoleBind] setMapping UNAVAILABLE strategy=none reason=$failure " +
                    "available=$available expected=$expectedId",
            )
        } else {
            for (candidate in candidates) {
                val invoked = runCatching { invokeMappingStrategy(project, vf, intendedDs, candidate) }
                if (invoked.isFailure) {
                    failure = describeFailure(invoked.exceptionOrNull())
                    LOG.warn("[T-ConsoleBind] setMapping FAILED strategy=${candidate.id} reason=$failure")
                    continue
                }
                if (!readbackAvailable) {
                    // 平台连回读 API 都没有（社区版/极旧平台）：只能认「调用未抛」。
                    strategy = candidate
                    verified = false
                    failure = null
                    LOG.info("[T-ConsoleBind] setMapping applied strategy=${candidate.id} (readback unavailable)")
                    break
                }
                observedId = probePerFileMappingId(project, vf)
                if (observedId == expectedId) {
                    strategy = candidate
                    verified = true
                    failure = null
                    LOG.info("[T-ConsoleBind] setMapping applied strategy=${candidate.id} observed=$observedId")
                    break
                }
                // S1 在 263 上必然走到这里（异步）：下一候选就是同步兜底，属预期路径，记 INFO 不刷 WARN。
                failure = "READBACK_MISMATCH expected=$expectedId observed=$observedId"
                LOG.info("[T-ConsoleBind] setMapping not applied yet strategy=${candidate.id} reason=$failure")
            }
        }

        val mapped = strategy != null
        if (!mapped && candidates.isNotEmpty()) {
            LOG.warn(
                "[T-ConsoleBind] setMapping EXHAUSTED strategy=none reason=${failure ?: "unknown"} " +
                    "available=$available expected=$expectedId observed=$observedId",
            )
        }
        LOG.info(
            "[T-ConsoleBind] exec file pin realigned: detach=$detached, mapped=$mapped, " +
                "strategy=${strategy?.id ?: "none"}, expected=$expectedId, observed=$observedId, verified=$verified",
        )
        return RealignReport(
            detached = detached,
            mapped = mapped,
            strategy = strategy,
            expectedId = expectedId,
            observedId = observedId,
            verified = verified,
            failure = failure,
        )
    }

    /**
     * 按策略实际写一次（不验证）。S3/S4 走 `PerFileMappingsBase.setMapping`，与平台自己的
     * `edtWriteAction` 同形——EDT 上必须包写命令，否则 `handleMappingChange → reparseFiles`
     * 会以 `Write-unsafe context` 收场（DialectPreMapping 同类实证）。
     */
    private fun invokeMappingStrategy(
        project: Project,
        vf: VirtualFile,
        intendedDs: Any?,
        strategy: MappingStrategy,
    ) {
        when (strategy) {
            MappingStrategy.WRITE_ACTION -> {
                val instance = mappingInstance(project)
                    ?: throw IllegalStateException("DataSourcePerFileMappings.getInstance(project) = null")
                val method = cachedMethod(
                    instance.javaClass, "setMappingWithWriteAction",
                    VirtualFile::class.java, cachedClass(LOCAL_DS_CLASS), Runnable::class.java,
                ) ?: throw NoSuchMethodException("${instance.javaClass.name}.setMappingWithWriteAction")
                method.invoke(instance, vf, intendedDs, null)
            }
            MappingStrategy.LEGACY_DS -> {
                val instance = mappingInstance(project)
                    ?: throw IllegalStateException("DataSourcePerFileMappings.getInstance(project) = null")
                val method = cachedMethod(
                    instance.javaClass, "setMapping",
                    VirtualFile::class.java, cachedClass(LOCAL_DS_CLASS),
                ) ?: throw NoSuchMethodException("${instance.javaClass.name}.setMapping(VirtualFile,LocalDataSource)")
                method.invoke(instance, vf, intendedDs)
            }
            MappingStrategy.STRING_ID -> {
                val instance = mappingInstance(project)
                    ?: throw IllegalStateException("DataSourcePerFileMappings.getInstance(project) = null")
                val uniqueId = intendedDs?.let { runCatching { DataSourceSettings.getUniqueId(it) }.getOrNull() }
                val method = cachedMethod(
                    instance.javaClass, "setMapping", VirtualFile::class.java, String::class.java,
                ) ?: throw NoSuchMethodException("${instance.javaClass.name}.setMapping(VirtualFile,String)")
                inWriteCommand(project) { method.invoke(instance, vf, uniqueId) }
            }
            MappingStrategy.STATIC_WRITE_ONLY -> {
                // 选择规则已排除 CLEAR；这里再守一道（配置错误不得变成 NPE）。
                requireNotNull(intendedDs) { "STATIC_WRITE_ONLY 不能用于 CLEAR（平台内部直接取 ds.uniqueId）" }
                val impl = cachedClass(PER_FILE_MAPPINGS_CLASS)
                    ?: throw ClassNotFoundException(PER_FILE_MAPPINGS_CLASS)
                val method = cachedMethod(
                    impl, "setDataSourcePerFileMapping",
                    Project::class.java, VirtualFile::class.java, cachedClass(LOCAL_DS_CLASS),
                ) ?: throw NoSuchMethodException("$PER_FILE_MAPPINGS_CLASS.setDataSourcePerFileMapping")
                inWriteCommand(project) { method.invoke(null, project, vf, intendedDs) }
            }
        }
    }

    /** 模型写：EDT 上包写命令；已是写上下文或不在 EDT（平台会自己拒绝）则原样调用。 */
    private fun inWriteCommand(project: Project, block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app == null || app.isWriteAccessAllowed || !app.isDispatchThread) {
            block()
            return
        }
        // 显式构造 Runnable：`runWriteCommandAction` 另有 (Project, Computable) 重载，
        // 直接传 lambda 会撞 SAM 歧义（编译期实测）。
        WriteCommandAction.runWriteCommandAction(project, Runnable { block() })
    }

    private fun mappingInstance(project: Project): Any? = runCatching {
        val impl = cachedClass(PER_FILE_MAPPINGS_CLASS) ?: return null
        cachedMethod(impl, "getInstance", Project::class.java)?.invoke(null, project)
    }.getOrNull()

    /**
     * 回读 per-file 关联的身份（`DataSourcePerFileMappingsImpl.getDataSourcePerFileMapping`）。
     * 探测不可用/异常 → null（调用方用 [RealignReport.verified] 区分「没写」与「读不到」）。
     */
    private fun probePerFileMappingId(project: Project, vf: VirtualFile): String? = runCatching {
        val impl = cachedClass(PER_FILE_MAPPINGS_CLASS) ?: return null
        val ds = cachedMethod(impl, "getDataSourcePerFileMapping", Project::class.java, VirtualFile::class.java)
            ?.invoke(null, project, vf) ?: return null
        DataSourceSettings.getUniqueId(ds)
    }.onFailure {
        LOG.info("[T-ConsoleBind] probe per-file mapping FAILED (treat as unmapped): ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /** P0-3：失败归因串（异常类型 + 首行消息），保证 `grep strategy=S` 能一次捞到。 */
    private fun describeFailure(error: Throwable?): String {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        val name = cause?.javaClass?.simpleName ?: "UnknownError"
        val message = cause?.message?.lineSequence()?.firstOrNull()?.take(200) ?: ""
        return "$name: $message".trim()
    }

    // ---------- 反射 Class/Method 记忆化（沿用 DataSourceSettings 的 F68a 范式） ----------
    //
    // 换源是用户动作、频次低，但每次都会走 settle 链；负缓存用 Optional（CHM 禁 null），
    // 键含类名 + 方法签名防跨类/跨重载撞键。

    @Volatile
    private var probedStrategies: List<MappingStrategy>? = null

    private val classCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Class<*>>>()

    private val methodCache =
        java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<java.lang.reflect.Method>>()

    private fun cachedClass(fqcn: String): Class<*>? =
        classCache.getOrPut(fqcn) {
            java.util.Optional.ofNullable(runCatching { Class.forName(fqcn) }.getOrNull())
        }.orElse(null)

    private fun cachedMethod(clazz: Class<*>?, name: String, vararg paramTypes: Class<*>?): java.lang.reflect.Method? {
        if (clazz == null || paramTypes.any { it == null }) return null
        val types = paramTypes.filterNotNull().toTypedArray()
        val key = "${clazz.name}#$name(${types.joinToString(",") { it.name }})"
        return methodCache.getOrPut(key) {
            java.util.Optional.ofNullable(runCatching { clazz.getMethod(name, *types) }.getOrNull())
        }.orElse(null)
    }
}
