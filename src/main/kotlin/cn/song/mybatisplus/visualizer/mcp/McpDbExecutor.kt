package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.util.FatalErrors
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.sql.SQLWarning

/**
 * warnings 取尽内核（T-NoticeChannel 纯逻辑层）：[surfaces] 每项 = (getAllWarnings 调用,
 * clearWarnings 调用) 二元组，反射接线在 [McpDbExecutor.warningSurfaces]——本核不碰反射
 * 与平台类型，裸 JUnit 可直测。顶层函数（非 object 成员）：测试加载本文件门面类时不必
 * 触发 [McpDbExecutor] 的 Logger 初始化。
 *
 * 全程 runCatching：个别驱动对 warnings 抛 UnsupportedOperationException 时降级为不产出
 * 该面消息，绝不连坐查询结果；clearWarnings 失败同样不丢已取到的消息（下一条语句执行前的
 * pre-drain 兜底，DBX postgres.rs 同款两件套）。
 */
internal fun drainWarnings(vararg surfaces: Pair<() -> List<*>?, () -> Unit>): List<String> =
    surfaces.flatMap { (getAllWarnings, clearWarnings) ->
        val messages = runCatching {
            getAllWarnings()?.mapNotNull { w ->
                runCatching {
                    when (w) {
                        null -> null
                        is SQLWarning -> w.message
                        else -> w.toString()
                    }
                }.getOrNull()
            }
        }.getOrNull().orEmpty()
        runCatching { clearWarnings() }
        messages
    }.filter { it.isNotBlank() }

/**
 * 通道层行预算兜底（T-McpRowBudget / T-MRB-04，O-36）：工具层已按
 * [McpExplainFormatter.clampMaxRows] 钳制一次，本通道再钳一次——[McpDbExecutor.executeQuery]
 * 是「未来任何执行类工具共用」的通道（[McpSelectGuard] KDoc 同款宣示），新增执行工具若漏钳制
 * 也不会突破 [McpExplainFormatter.HARD_MAX_ROWS]（对齐 DBX「静态断言恒小于驱动上限」的护栏语义）。
 * 定案=**钳制不抛错**（越界静默收进合法区间，与 clampMaxTables 既有语义一致）。
 *
 * 顶层函数（非 object 成员）：测试加载本文件门面类时不必触发 [McpDbExecutor] 的 Logger 初始化。
 */
internal fun channelRowBudget(requested: Int): Int = McpExplainFormatter.clampMaxRows(requested)

/**
 * T-PreviewChainNoise W2：Database 反射链的**阻塞上下文兜底**（顶层函数，非 object 成员
 * ——与 [drainWarnings] 同款理由：测试加载本文件门面不必触发 [McpDbExecutor] 的 Logger 初始化）。
 *
 * 平台事实（243 sources `progress/coroutines.kt:138-143`）：`DatabaseConnectionManager$Builder
 * .createBlocking` 内部走 `runBlockingCancellable`，当前线程既无全局 ProgressIndicator 又无
 * Job（`executeOnPooledThread` 起的裸池线程即此形态）时打
 * `IllegalStateException: There is no ProgressIndicator or Job in this thread`。同一坑
 * `PreviewSideQuery` 已修（T-PreviewSideQuery），2026-10-23 用户真机日志证明
 * `ExplainPanelChannel.executeStructured` 是漏网第二处——故兜底下沉到唯一入口 [McpDbExecutor]，
 * 未来任何新调用点不再依赖各自记得包一层。
 *
 * **已有指示器时不得替换**：`runProcess(EmptyProgressIndicator)` 会顶掉线程上真实指示器，
 * 外层取消（MCP 工具跑在协程里带 Job）随之失效——所以只在缺上下文时补空指示器
 * （查询本身不读取消状态：只读短查询，超时由驱动级 `setQueryTimeout` 护栏负责）。
 */
internal fun <T> withDbBlockingContext(block: () -> T): T {
    val pm = com.intellij.openapi.progress.ProgressManager.getInstance()
    return if (pm.progressIndicator != null) {
        block()
    } else {
        pm.runProcess(
            com.intellij.openapi.util.Computable { block() },
            com.intellij.openapi.progress.EmptyProgressIndicator(),
        )
    }
}

/**
 * T-McpToolset headless 执行通道（sqlv_explain 专用，反射隔离 DatabaseTools）。
 *
 * 为什么不复用 [cn.song.mybatisplus.visualizer.execute.SqlExecutor]：既有执行链是 UI 控制台
 * 形态（弹数据源选择框 + 异步回调 + 结果进原生控制台），违反 MCP 零弹窗红线且拿不到文本结果。
 * 本通道走 DatabaseConnectionManager 同步建连（243/262 javap 同形实证）：
 * ```
 * DatabaseConnectionManager.getInstance()                    // static
 *   .build(project, localDataSource)                         // LocalDataSource 即 DatabaseConnectionPoint
 *   .setAskPassword(false)                                   // 零弹窗：密码未保存直接失败（fail closed）
 *   .createBlocking()  -> GuardedRef<DatabaseConnection>     // 同步无 UI
 * ref.get().getRemoteConnection()                            // DatabaseConnectionCore 接口方法
 *   .createStatement().executeQuery(sql)  -> RemoteResultSet // DBN remote JDBC 镜像
 * ```
 * 全反射 + Method 一律从声明接口/类获取（T-Embed-10 跨 classloader 教训）。签名由
 * McpDbSignatureCrossVersionTest 对本机 243/253/262 发行版核验。
 *
 * T-NoticeChannel：执行前后经 [warningSurfaces] 取尽语句级与连接级 warnings
 * （getAllWarnings + clearWarnings，DBN 镜像接口），产出挂 RawResult.notices 供工具层渲染。
 *
 * 数据源发现复用 [cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver] 同链：
 * DataSourceManager.getManagers(project) → getDataSources()，按 DasDataSource.getName() 匹配别名。
 */
object McpDbExecutor {

    private val LOG = Logger.getInstance(McpDbExecutor::class.java)

    /** database 插件标记类（探测用，与 DatabaseSchemaResolver 同款）。 */
    private const val MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager"
    private const val DAS_DATA_SOURCE = "com.intellij.database.model.DasDataSource"

    private const val DCM = "com.intellij.database.dataSource.DatabaseConnectionManager"
    private const val CONNECTION_POINT = "com.intellij.database.dataSource.DatabaseConnectionPoint"
    private const val BUILDER = "com.intellij.database.dataSource.DatabaseConnectionManager\$Builder"
    private const val GUARDED_REF = "com.intellij.database.util.GuardedRef"
    private const val CONNECTION_CORE = "com.intellij.database.dataSource.DatabaseConnectionCore"
    private const val CONNECTION = "com.intellij.database.dataSource.DatabaseConnection"
    private const val REMOTE_CONNECTION = "com.intellij.database.remote.jdbc.RemoteConnection"
    private const val REMOTE_STATEMENT = "com.intellij.database.remote.jdbc.RemoteStatement"
    private const val REMOTE_RESULT_SET = "com.intellij.database.remote.jdbc.RemoteResultSet"
    private const val REMOTE_META = "com.intellij.database.remote.jdbc.RemoteResultSetMetaData"

    /** T-McpAuditTrail T-MAT-01：失败文本上限（历史文件单条元数据行，不落整段堆栈）。 */
    private const val ERROR_TEXT_LIMIT = 200

    /** database 插件是否在场（Ultimate/DataGrip）。 */
    fun isDatabaseAvailable(): Boolean = runCatching {
        Class.forName(MANAGER_CLASS)
        true
    }.getOrDefault(false)

    /** 列出全部数据源显示名（alias 未匹配时给 agent 的可选清单）。 */
    fun listDataSourceNames(project: Project): List<String> {
        val dataSources = dataSources(project) ?: return emptyList()
        return dataSources.mapNotNull { ds ->
            runCatching {
                Class.forName(DAS_DATA_SOURCE).getMethod("getName").invoke(ds) as? String
            }.getOrNull()
        }
    }

    /**
     * 按别名找数据源。[alias] 空时：恰好一个数据源 → 用之；0 个/多个 → null（调用方报错并列名）。
     * 别名匹配大小写不敏感；找不到返回 null。
     */
    fun findDataSource(project: Project, alias: String?): Any? {
        val dataSources = dataSources(project) ?: return null
        val named = alias?.trim()?.takeIf { it.isNotEmpty() }
        if (named == null) {
            return dataSources.singleOrNull()
        }
        return dataSources.firstOrNull { ds ->
            val name = runCatching {
                Class.forName(DAS_DATA_SOURCE).getMethod("getName").invoke(ds) as? String
            }.getOrNull()
            name != null && name.equals(named, ignoreCase = true)
        }
    }

    /**
     * 一次执行的结果载体（T-McpAuditTrail / T-MAT-01）：只含 JDK 类型，不引平台/IDE 服务类型——
     * 回调注入形态正是为了不把 [McpDbExecutor] 的 headless 边界与 [cn.song.mybatisplus.visualizer.log.SqlLogManager]
     * 之类的项目级服务绑在一起（依赖留在工具层）。
     *
     * @param executedSql 实际交给驱动的语句文本（explain 通道=EXPLAIN 包装句）。**注意**：历史入库
     *   必须用调用方自己持有的**原始** selectSql，不得落本字段（T-MAT-02：包装句首 token 为
     *   `EXPLAIN`，[cn.song.mybatisplus.visualizer.log.LogStatementClassifier] 会判 `OTHER` → 无类型染色）。
     * @param elapsedMs 本次执行耗时（含建连与取数，毫秒）
     * @param errorText 失败时的「异常类名: 首行消息」（截断）；成功为 null
     * @param dataSourceName 数据源显示名（反射 DasDataSource.getName；不可达/headless 为 null）
     */
    data class ExecutedOutcome(
        val executedSql: String,
        val elapsedMs: Long,
        val errorText: String?,
        val dataSourceName: String?,
    )

    /**
     * 在指定数据源上执行只读查询并采集行列文本（EXPLAIN 用）。
     * 密码未保存/不可达等建连失败以 SQLException 冒泡，适配层按
     * [McpToolErrors.DATASOURCE_UNREACHABLE] 包装为 agent 可读错误（零弹窗 fail closed）。
     *
     * T-ExecTimeoutGuard（O-25）：[timeoutSeconds] > 0 时执行前 setQueryTimeout（驱动级
     * 建议值，RemoteStatement 镜像 243/262 javap 同形）；超时形态异常（[cn.song.mybatisplus.
     * visualizer.execute.ExecutionTimeoutGuard.isTimeoutLike]）先 Statement.cancel() 收尾
     * 再冒泡，消息尾附 `timed out after Ns`（agent 可读，不引 JSON）。
     *
     * T-McpRowBudget（O-36）：[maxRows] 先过 [channelRowBudget] 兜底钳制再进采集——调用方
     * （含未来新增的执行类工具）传什么都不会突破 [McpExplainFormatter.HARD_MAX_ROWS]。
     *
     * T-McpAuditTrail（T-MAT-01）：[onExecuted] 为执行链唯一出口钩子——成功与失败**两条路径**
     * 都回调一次（失败回调后异常原样冒泡）。钩子是旁路：自身抛异常只记日志，绝不连坐执行结果
     * 或吞掉失败（审计不得成为门控）。null（默认）时零行为差异——既有调用点逐字节不变。
     *
     * R23（Oracle/SQL Server EXPLAIN 接入）：[channelNote] 非 null 时走 **execute() 多结果宽容
     * 通道**——`executeQuery(String)` 只接受「首结果即 ResultSet」的语句，而 Oracle 的
     * `EXPLAIN PLAN FOR`（首结果为 update count、无行返回）与 SQL Server 的 `SET STATISTICS XML
     * ON; …` 批（前导 SET 产生 update count）都会让它直接抛错。宽容通道按 JDBC 结果游走协议
     * （execute → updateCount → getMoreResults，有界）取**首个** ResultSet；一个 ResultSet 都
     * 拿不到时返回空行列（诚实降级）。[channelNote] 追加在驱动 notices 之后的 notices 节——
     * 二段式/附加结果集方言的计划「为什么不在上面的行里」的说明由
     * [cn.song.mybatisplus.visualizer.sql.ExplainSupport] 表单源持有。null（默认）走既有
     * executeQuery 通道，**行为逐字节不变**（其余 9 家方言与其他调用点的防回归面）。
     *
     * T-ExplainPwdPrompt（2026-10-24）：[askPassword] **必填**——编译期强迫每个调用点按通道
     * 表态，不设默认值（默认 false 会让未来新增的 UI 调用点「静默不弹」重演缺陷；默认 true 会
     * 击穿 MCP 零弹窗红线）。UI 上下文（面板执行计划/预估行数）传 true：数据源未存密码时交
     * 平台原生密码框（Builder 默认即 true，javap 构造器字节码实证；只在「未存」时问，已存密码
     * 零变化）；MCP 工具（`SqlVisualizerExplainTool`）传 false：程序性调用弹模态框即挂死，
     * 维持零弹窗 fail closed。
     */
    fun executeQuery(
        project: Project,
        dataSource: Any,
        sql: String,
        maxRows: Int,
        timeoutSeconds: Int = 0,
        askPassword: Boolean,
        onExecuted: ((ExecutedOutcome) -> Unit)? = null,
        channelNote: String? = null,
    ): McpExplainFormatter.RawResult {
        val started = System.currentTimeMillis()
        return try {
            executeQueryInternal(project, dataSource, sql, maxRows, timeoutSeconds, askPassword, channelNote).also {
                notifyExecuted(onExecuted, sql, System.currentTimeMillis() - started, null, dataSource)
            }
        } catch (e: Throwable) {
            // T-FatalPassthrough（合流收口，T-FP-03 守卫要求）：JVM 级致命错误不投审计钩子——
            // 钩子会构造 ExecutedOutcome（反射取数据源名 + 文本构造）并落历史，内存/链接已损坏时
            // 继续这些副作用只会把现场搅乱；fatal 原样透传给平台/进程级通道。反射链须先解包：
            // 目标方法抛出的 Error 被 Method.invoke 包进 InvocationTargetException（普通异常），
            // 只判外层会漏判（与 errorTextOf 同款解包口径，见 util/FatalErrors.kt 用法说明）。
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            if (FatalErrors.isFatal(cause)) throw cause
            notifyExecuted(onExecuted, sql, System.currentTimeMillis() - started, e, dataSource)
            throw e
        }
    }

    /**
     * 钩子投递（T-MAT-01）：hook 为 null 时**立即返回**——耗时以外的任何采集（反射取数据源名、
     * 异常文本构造）都不发生，未接线的调用点零开销。
     */
    private fun notifyExecuted(
        hook: ((ExecutedOutcome) -> Unit)?,
        sql: String,
        elapsedMs: Long,
        failure: Throwable?,
        dataSource: Any,
    ) {
        if (hook == null) return
        val outcome = ExecutedOutcome(
            executedSql = sql,
            elapsedMs = elapsedMs,
            errorText = failure?.let { errorTextOf(it) },
            dataSourceName = dataSourceName(dataSource),
        )
        runCatching { hook(outcome) }.onFailure {
            LOG.warn("McpDbExecutor: onExecuted hook failed (execution result unaffected)", it)
        }
    }

    /**
     * 失败文本（T-MAT-01）：异常类名 + 首行消息，[ERROR_TEXT_LIMIT] 截断。
     *
     * 反射调用链里异常被包在 `InvocationTargetException` 中（其 message 恒 null），故先解包取
     * 目标异常——否则历史里只剩一个类名，排障无信息。多行堆栈只取首行（历史文件单条正文，
     * 不落整段堆栈）。
     */
    private fun errorTextOf(e: Throwable): String {
        val target = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        val firstLine = target.message?.lineSequence()?.firstOrNull { it.isNotBlank() }
        val text = "${target.javaClass.simpleName}: ${firstLine ?: "(no message)"}"
        return if (text.length <= ERROR_TEXT_LIMIT) text else text.take(ERROR_TEXT_LIMIT) + "…"
    }

    /** 数据源显示名（反射 DasDataSource.getName；不可达返回 null——展示性字段，取不到不影响主链）。 */
    private fun dataSourceName(dataSource: Any): String? = runCatching {
        Class.forName(DAS_DATA_SOURCE).getMethod("getName").invoke(dataSource) as? String
    }.getOrNull()

    /** T-MAT-01 之前的执行本体（钩子包装前逐行原样；行为零变化）。 */
    private fun executeQueryInternal(
        project: Project,
        dataSource: Any,
        sql: String,
        maxRows: Int,
        timeoutSeconds: Int = 0,
        askPassword: Boolean,
        channelNote: String? = null,
    ): McpExplainFormatter.RawResult {
        val rowBudget = channelRowBudget(maxRows)
        val manager = Class.forName(DCM).getMethod("getInstance").invoke(null)
        val builder = Class.forName(DCM).getMethod("build", Project::class.java, Class.forName(CONNECTION_POINT))
            .invoke(manager, project, dataSource)
        // T-ExplainPwdPrompt（2026-10-24）：askPassword 按调用点通道表态（见 executeQuery KDoc）——
        // 此前硬编码 false（2026-09-15 T-McpToolset 为 MCP 立的零弹窗红线），面板 EXPLAIN 与预估
        // 行数旁路切到本通道时被连坐：数据源未存密码不弹原生密码框，直接 Access denied 红字
        //（沙盒实测截图 2026-10-23）。平台 Builder 默认即 true（javap 构造器字节码 iconst_1），
        // 询问链在 DatabaseConnectionEstablisher（suspend，含凭据失败重试）。
        Class.forName(BUILDER).getMethod("setAskPassword", Boolean::class.javaPrimitiveType)
            .invoke(builder, askPassword)
        // T-PreviewChainNoise W2：建连前补齐阻塞上下文（见 withDbBlockingContext KDoc）——
        // 裸池线程（ExplainPanelChannel.executeStructured 的 executeOnPooledThread）上
        // createBlocking 内部 runBlockingCancellable 缺 Job 即打
        // "There is no ProgressIndicator or Job in this thread" SEVERE（真机 2026-10-23 实证）。
        val ref = withDbBlockingContext {
            Class.forName(BUILDER).getMethod("createBlocking").invoke(builder)
        }
        try {
            val connection = Class.forName(GUARDED_REF).getMethod("get").invoke(ref)
            val remoteConnection = findInterfaceMethod(connection, CONNECTION_CORE, CONNECTION, "getRemoteConnection")
                .invoke(connection)
            val statement = Class.forName(REMOTE_CONNECTION).getMethod("createStatement").invoke(remoteConnection)
            try {
                // 超时护栏：驱动级建议值，设置 0=不限；设置失败不拦执行（护栏尽力而为语义）
                if (timeoutSeconds > 0) {
                    runCatching {
                        Class.forName(REMOTE_STATEMENT).getMethod("setQueryTimeout", Int::class.javaPrimitiveType)
                            .invoke(statement, timeoutSeconds)
                    }.onFailure { LOG.info("McpDbExecutor: setQueryTimeout unsupported by driver, guard off", it) }
                }
                val surfaces = warningSurfaces(remoteConnection, statement)
                // 执行前先 drain 一次（DBX 口径）：DBN 连接可复用，上一条语句/会话遗留的陈旧
                // notices 不允许串扰进本次结果；返回值丢弃，只做清空。
                drainWarnings(*surfaces)
                try {
                    // R23：channelNote 非 null（二段式/多结果集方言）走 execute() 宽容通道；
                    // null（默认）保持 executeQuery 通道——既有 9 家方言与其他调用点逐字节不变
                    val raw = if (channelNote == null) {
                        val resultSet = Class.forName(REMOTE_STATEMENT).getMethod("executeQuery", String::class.java)
                            .invoke(statement, sql)
                        collect(resultSet, rowBudget)
                    } else {
                        executeStatementCollectFirst(statement, sql, rowBudget)
                    }
                    // 执行成功才采集 notices；失败路径直接抛出（notices 无从产生=DBX「出错丢弃」口径）
                    val notices = drainWarnings(*surfaces)
                    if (notices.isNotEmpty()) LOG.info("McpDbExecutor: drained ${notices.size} driver notice(s)")
                    // R23：通道说明固定排在驱动 notices 之后——「行里为什么没有计划」的说明总是收尾
                    return raw.copy(notices = if (channelNote == null) notices else notices + channelNote)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    // T-ExecTimeoutGuard：超时先收尾再报错——cancel 请求（RMI 镜像尽力而为）
                    // 后再冒泡带 timed out 标记的异常；cancel 失败不吞超时事实（如实措辞）
                    val target = e.targetException
                    if (cn.song.mybatisplus.visualizer.execute.ExecutionTimeoutGuard.isTimeoutLike(target)) {
                        runCatching {
                            Class.forName(REMOTE_STATEMENT).getMethod("cancel").invoke(statement)
                        }.onFailure { LOG.warn("McpDbExecutor: cancel after timeout failed", it) }
                        throw java.sql.SQLException(
                            "query timed out after ${timeoutSeconds}s (cancel requested); " +
                                "original: ${target?.message ?: target?.javaClass?.simpleName}",
                            target,
                        )
                    }
                    throw e
                }
            } finally {
                runCatching { Class.forName(REMOTE_STATEMENT).getMethod("close").invoke(statement) }
                    .onFailure { LOG.warn("McpDbExecutor: close statement failed", it) }
            }
        } finally {
            runCatching { Class.forName(GUARDED_REF).getMethod("close").invoke(ref) }
                .onFailure { LOG.warn("McpDbExecutor: close connection ref failed", it) }
        }
    }

    /**
     * warnings 采集面（语句级先、连接级后，执行前 drain 与执行后采集共用同一组 Method 查找）。
     * getAllWarnings/clearWarnings 在 RemoteStatement 与 RemoteConnection 上均有声明
     * （243/262 发行版 javap 实证，McpDbSignatureChecker 登记）——DBN remote JDBC 镜像自带
     * 取尽方法，无需手写 getNextWarning 链式遍历（每次 next 都是一次 RMI 往返）。
     */
    private fun warningSurfaces(
        remoteConnection: Any,
        statement: Any,
    ): Array<Pair<() -> List<*>?, () -> Unit>> = arrayOf(
        {
            Class.forName(REMOTE_STATEMENT).getMethod("getAllWarnings").invoke(statement) as? List<*>
        } to {
            Class.forName(REMOTE_STATEMENT).getMethod("clearWarnings").invoke(statement)
        },
        {
            Class.forName(REMOTE_CONNECTION).getMethod("getAllWarnings").invoke(remoteConnection) as? List<*>
        } to {
            Class.forName(REMOTE_CONNECTION).getMethod("clearWarnings").invoke(remoteConnection)
        },
    )

    /**
     * R23：execute() 多结果宽容通道——按 JDBC 结果游走协议取**首个** ResultSet。
     *
     * 协议（java.sql.Statement 契约，RemoteStatement 镜像 262 javap 同形实证）：
     * `execute(sql)` 返回首结果是否为 ResultSet；false 时 `getUpdateCount()` 给出当前结果的
     * update count（**-1 = 再无结果**）；`getMoreResults()` 前进到下一结果。前导 SET 语句
     * （Oracle `EXPLAIN PLAN FOR` / SQL Server `SET STATISTICS XML ON`）产生 update count=0
     * 的首结果——本函数跳过它们取到真正的查询行；一个 ResultSet 都没有（Oracle 计划只登记）
     * 返回空行列。游走**有界**（[MAX_RESULT_HOPS]）——驱动 getMoreResults 失灵时不挂死。
     */
    private fun executeStatementCollectFirst(statement: Any, sql: String, maxRows: Int): McpExplainFormatter.RawResult {
        val stmtClass = Class.forName(REMOTE_STATEMENT)
        val execute = stmtClass.getMethod("execute", String::class.java)
        val getResultSet = stmtClass.getMethod("getResultSet")
        val getMoreResults = stmtClass.getMethod("getMoreResults")
        val getUpdateCount = stmtClass.getMethod("getUpdateCount")
        var rs: Any? = if (execute.invoke(statement, sql) as Boolean) getResultSet.invoke(statement) else null
        var hops = 0
        while (rs == null && hops < MAX_RESULT_HOPS) {
            hops++
            if (getUpdateCount.invoke(statement) as Int == -1) break
            if (getMoreResults.invoke(statement) as Boolean) {
                rs = getResultSet.invoke(statement)
            }
        }
        LOG.info("McpDbExecutor: execute-channel first-result walk done (hops=$hops, resultSet=${rs != null})")
        return if (rs != null) collect(rs, maxRows) else McpExplainFormatter.RawResult(emptyList(), emptyList())
    }

    /** execute() 宽容通道的结果游走上限（防驱动失灵挂死；正常批 ≤3~4 个结果，余量充足）。 */
    private const val MAX_RESULT_HOPS = 32

    /** 采集 ResultSet 行列（读满 [maxRows] 即停，truncated 标记供格式化层提示）。 */
    private fun collect(resultSet: Any, maxRows: Int): McpExplainFormatter.RawResult {        val rsClass = Class.forName(REMOTE_RESULT_SET)
        val meta = rsClass.getMethod("getMetaData").invoke(resultSet)
        val columnCount = Class.forName(REMOTE_META).getMethod("getColumnCount").invoke(meta) as Int
        val headers = (1..columnCount).map { col ->
            Class.forName(REMOTE_META).getMethod("getColumnLabel", Int::class.javaPrimitiveType)
                .invoke(meta, col) as? String ?: "col$col"
        }
        val nextMethod = rsClass.getMethod("next")
        val getString = rsClass.getMethod("getString", Int::class.javaPrimitiveType)
        val wasNull = rsClass.getMethod("wasNull")
        val rows = mutableListOf<List<String>>()
        var truncated = false
        while ((nextMethod.invoke(resultSet) as Boolean)) {
            if (rows.size >= maxRows) {
                truncated = true
                break
            }
            val row = (1..columnCount).map { col ->
                val value = getString.invoke(resultSet, col) as? String
                if (value == null && (wasNull.invoke(resultSet) as Boolean)) "" else value ?: ""
            }
            rows.add(row)
        }
        runCatching { rsClass.getMethod("close").invoke(resultSet) }
        LOG.info("McpDbExecutor: collected ${rows.size} rows (truncated=$truncated), columns=${headers.size}")
        return McpExplainFormatter.RawResult(headers, rows, truncated)
    }

    /**
     * 方法弹性查找：优先声明接口 [primaryInterface]（T-Embed-10：Method 一律从声明接口拿，
     * 跨插件 classloader 用实现类反射会抛 IllegalAccessException），243/262 声明层差异时
     * 回落 [fallbackInterface]。
     */
    private fun findInterfaceMethod(target: Any, primaryInterface: String, fallbackInterface: String, name: String) =
        runCatching {
            Class.forName(primaryInterface).getMethod(name)
        }.recoverCatching {
            Class.forName(fallbackInterface).getMethod(name)
        }.getOrElse {
            // 两个声明层都没有 → 从实例类拿（照 DatabaseSchemaResolver 的失败即空语义，此处应不可达）
            target.javaClass.getMethod(name)
        }

    /** 数据源清单（反射 DataSourceManager.getManagers(project) → getDataSources()）。 */
    private fun dataSources(project: Project): List<Any>? = runCatching {
        val managerClass = Class.forName(MANAGER_CLASS)
        val managers = managerClass.getMethod("getManagers", Project::class.java)
            .invoke(null, project) as? List<*> ?: return@runCatching emptyList<Any>()
        val out = mutableListOf<Any>()
        for (manager in managers) {
            if (manager == null) continue
            val list = managerClass.getMethod("getDataSources").invoke(manager) as? List<*> ?: continue
            out.addAll(list.filterNotNull())
        }
        out
    }.getOrNull()
}
