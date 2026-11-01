package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.ModuleDataSourceBindingStore
import com.intellij.openapi.project.Project

/**
 * S2-12：执行链「绕开绑定链取源」回落。
 *
 * 平台 `DatabaseRunners.getAttachDataSourceRunners` 按「文件 → 数据源」绑定筛 Runner，未绑定
 * 必空（2026-11-01 真机实证：单源已连接已内省、未绑定时点执行 runners=0）⇒ 新工程第一次点执行
 * 必然失败。243 基线判不出平台绑定态（`DataSourcePerFileMappingsImpl` 是 262 API），故目标源
 * 只能用插件自己的事实确定：**学习绑定 > last-used 记忆 > 单源**，歧义不猜。
 *
 * 执行通道（不新建）：照平台 per-源 runner 的语义（`DatabaseRunners.runInDataSource` 字节码
 * 实证 = `DatabaseSessionManager.getSession(project, localDs, sql)` + `consumer.consume(session)`，
 * 无同名会话时 getSession 新建）直取 session 交给既有 `sessionConsumer`，控制台/结果表/审计
 * 整链照旧复用；runner 适配器交给既有 [DataSourceSelector.runRunner]（它反射 `run()` /
 * `getDisplayName()`，普通对象即可）。**不 attachConsole、不写 per-file 映射、不动绑定存储**。
 *
 * 反射隔离约定：平台类一律 `Class.forName` + runCatching，Community / 无 Database 插件下降级
 * null（回落不触发，走既有归因文案），与 [DataSourceSettings] 同款口径。
 */
internal object ExecBindFallback {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ExecBindFallback::class.java)

    /**
     * 回落目标源挑选（纯函数，S2-12 定案口径）。
     *
     * 确定性优先级：
     *  1. 学习绑定 UUID 精确命中 → 用它；
     *  2. last-used 记忆 UUID 精确命中 → 用它；
     *  3. last-used 记忆名字**唯一**命中 → 用它（多命中 = 歧义，不猜）；
     *  4. 在册源恰好 1 个 → 用它（单源无歧义）；
     *  5. 其余（零候选 / 多候选 / 记忆指向已删除源后仍多源）→ null，调用方保持既有归因文案。
     */
    internal fun pickFallbackTargetDataSource(
        learnedBindingId: String?,
        lastUsedMemory: ExecDialectRouting.MemoryRef?,
        registeredDataSources: List<Any>,
        dataSourceById: (String) -> Any?,
        dataSourceName: (Any) -> String,
    ): Any? {
        learnedBindingId?.takeIf { it.isNotBlank() }?.let { id ->
            dataSourceById(id)?.let { return it }
        }
        lastUsedMemory?.let { memory ->
            memory.dataSourceId?.takeIf { it.isNotBlank() }?.let { id ->
                dataSourceById(id)?.let { return it }
            }
            memory.displayName?.takeIf { it.isNotBlank() }?.let { name ->
                val matches = registeredDataSources.filter { dataSourceName(it) == name }
                if (matches.size == 1) return matches.first()
            }
        }
        if (registeredDataSources.size == 1) return registeredDataSources.first()
        return null
    }

    /**
     * 回落目标源解析（生产链）：学习绑定（模块层）→ last-used 记忆（模块层）→ 由
     * [pickFallbackTargetDataSource] 收口。registered 由调用方传入（空 Runner 分支已枚举一次）。
     */
    internal fun pickTarget(project: Project, registeredDataSources: List<Any>, moduleKey: String?): Any? {
        val learnedId = moduleKey?.let { key ->
            runCatching { ModuleDataSourceBindingStore.getInstance(project).dataSourceId(key) }.getOrNull()
        }
        val memory = runCatching { DataSourceSelector.lastUsedRunner(project, moduleKey) }.getOrNull()
            ?.let { ExecDialectRouting.MemoryRef(it.dataSourceId, it.displayName) }
        return pickFallbackTargetDataSource(
            learnedBindingId = learnedId,
            lastUsedMemory = memory,
            registeredDataSources = registeredDataSources,
            dataSourceById = { id -> runCatching { DataSourceSettings.getDataSourceById(project, id) }.getOrNull() },
            dataSourceName = { ds -> DataSourceSettings.getName(ds) },
        )
    }

    /**
     * 直取 session 的执行适配器：`run()` = `DatabaseSessionManager.getSession(project, ds, sql)`
     * + 把 session 交给既有 `sessionConsumer`（`runInDataSource` 的逐行等价，见类 KDoc）。
     *
     * [run] 内平台不可得时显式 error——异常经 [DataSourceSelector.runRunner] 的
     * InvocationTargetException 处理归因到 onError / 通知，不静默吞掉。
     *
     * @param sessionConsumer 既有准备链的 session 回调（`chooseAndPrepare` 现成构造）
     */
    internal fun directRunner(
        project: Project,
        ds: Any,
        sql: String,
        sessionConsumer: com.intellij.util.Consumer<Any>,
    ): Runnable = DirectSessionRunner(project, ds, sql, sessionConsumer)

    /** 平台 `DatabaseSessionManager.getSession(Project, DatabaseConnectionPoint, String)` 反射。 */
    private fun openSession(project: Project, ds: Any, sql: String): Any? {
        val managerClass = Class.forName("com.intellij.database.console.session.DatabaseSessionManager")
        val pointClass = Class.forName("com.intellij.database.dataSource.DatabaseConnectionPoint")
        val method = managerClass.getMethod("getSession", Project::class.java, pointClass, String::class.java)
        return method.invoke(null, project, ds, sql)
    }

    /** 直取 session 的执行适配器（`getDisplayName` 供 runRunner 的名字反射与记忆复用）。 */
    private class DirectSessionRunner(
        private val project: Project,
        private val ds: Any,
        private val sql: String,
        private val sessionConsumer: com.intellij.util.Consumer<Any>,
    ) : Runnable {
        @Suppress("unused") // 经 runRunner 反射调用
        fun getDisplayName(): String = DataSourceSettings.getName(ds)

        override fun run() {
            val session = runCatching { openSession(project, ds, sql) }.getOrElse { e ->
                LOG.warn("[S2-12] direct getSession FAILED: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            } ?: error("DatabaseSessionManager.getSession unavailable (Database plugin missing?)")
            LOG.info("[S2-12] direct session created: ${session.javaClass.name}")
            sessionConsumer.consume(session)
        }
    }
}
