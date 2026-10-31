package cn.song.mybatisplus.visualizer.sql

import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger

/**
 * T-DialectInferFix：数据源「能不能当 SQL 方言候选」的分类（纯函数，表驱动可单测）。
 *
 * 背景（2026-10-29 真机报障）：`DataSourceManager.getDataSources()` 返回的是**全部**数据源，
 * 其中包含 Redis/Mongo 这类根本不是 SQL 执行目标的数据源。旧实现把这类数据源的方言记成 null
 * 投进候选，而 [DialectDecisionEngine] 的「未知候选不得宣称共识」是**有意语义**
 * （`IdeaSqlDialectDecisionTest`「known and unknown candidates cannot be promoted to false consensus」
 * 专门锁定），于是「PostgreSQL + Redis」被判 AMBIGUOUS → 整个项目回落 MySQL 兼容兜底，
 * 连 `signal` 这种 PG 合法列名都被 MySQL 解析器标红。
 *
 * 判据（强→弱，只用平台事实，不猜）：
 *  1. 平台 query language id 能映射到注册方言 → [Kind.SQL_KNOWN]
 *     （platform 对该数据源认的方言，比驱动 URL 子协议更权威）；
 *  2. URL 子协议能映射到注册方言 → [Kind.SQL_KNOWN]；
 *  3. language id / DBMS 显示名命中已知非 SQL 家族 → [Kind.NON_SQL]（**不参与投票**）；
 *  4. 其余 → [Kind.SQL_UNKNOWN]（仍是候选：可能是插件未收录的 SQL 库，保持 fail-closed）。
 */
internal object DataSourceDialectClassification {

    enum class Kind { SQL_KNOWN, SQL_UNKNOWN, NON_SQL }

    data class Result(val kind: Kind, val dialectName: String?) {
        /** 非 SQL 家族的展示名（排障日志用；SQL 家族为空）。 */
        fun isSqlTarget(): Boolean = kind != Kind.NON_SQL
    }

    /**
     * 非 SQL 家族键（小写、去空格后**精确**比对，不做前缀匹配——前缀会把 Redshift 之外的
     * 名字误伤，例如任何以 "mongo" 开头的新 SQL 方言都会被误判）。
     */
    private val NON_SQL_KEYS: Set<String> = setOf(
        "redis", "mongo", "mongodb", "mongojs", "mongo js",
        "cassandra", "apachecassandra",
        "couchbase", "dynamo", "dynamodb", "amazondynamodb", "awsdynamodb",
        "elastic", "elasticsearch", "hbase", "neo4j", "influxdb",
    )

    /** DBMS 显示名 → 注册方言名（平台显示名与注册名不同的少数形态）。 */
    private val DBMS_ALIASES: Map<String, String> = mapOf(
        "microsoftsqlserver" to "SQL Server",
        "mssqlserver" to "SQL Server",
        "sqlserver" to "SQL Server",
        "postgresql" to "PostgreSQL",
        "mysql" to "MySQL",
        "mariadb" to "MariaDB",
        "oracle" to "Oracle",
    )

    fun classify(queryLanguageId: String?, dbmsDisplayName: String?, url: String?): Result {
        IdeaSqlContextBridge.pluginDialectName(queryLanguageId)?.let { return Result(Kind.SQL_KNOWN, it) }
        UrlSubprotocolDialects.dialectName(url)?.let { return Result(Kind.SQL_KNOWN, it) }
        if (isNonSqlKey(queryLanguageId) || isNonSqlKey(dbmsDisplayName) || isNonSqlUrl(url)) {
            return Result(Kind.NON_SQL, null)
        }
        val dbms = dbmsDisplayName?.trim().orEmpty()
        if (dbms.isNotEmpty()) {
            DBMS_ALIASES[dbms.lowercase().replace(" ", "")]?.let { return Result(Kind.SQL_KNOWN, it) }
            DialectRegistry.dialects.firstOrNull { it.name.equals(dbms, ignoreCase = true) }
                ?.let { return Result(Kind.SQL_KNOWN, it.name) }
        }
        return Result(Kind.SQL_UNKNOWN, null)
    }

    private fun isNonSqlKey(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase()?.replace(" ", "") ?: return false
        return normalized.isNotEmpty() && normalized in NON_SQL_KEYS
    }

    /**
     * URL 子协议是否属非 SQL 家族（T-Fr45DmGuard S1 起改为 internal：MCP `sqlv_explain` 的前置
     * 守卫 [cn.song.mybatisplus.visualizer.mcp.ExplainTargetGuard] 复用同一判定，**不复制键表**）。
     */
    internal fun isNonSqlUrl(url: String?): Boolean {
        val trimmed = url?.trim() ?: return false
        if (!trimmed.startsWith("jdbc:", ignoreCase = true)) return false
        return isNonSqlKey(trimmed.substring(5).substringBefore(':'))
    }
}

/**
 * T-DialectAutoInfer：IDE 数据源 URL 探测（DatabaseTools API，Ultimate-only）。
 *
 * 链路（262.3 javap 实锤，plugins/DatabaseTools/lib/modules/intellij.database*.jar）：
 *  - `DataSourceManager.getManagers(project)` → 各 manager `getDataSources()` → RawDataSource
 *  - `RawDataSource` 经 `DasDataSource.getConnectionConfig()` 得 `RawConnectionConfig.getUrl()`
 *  全程反射（IPGP 裁剪/Community 无 DatabaseTools 时类缺失 → runCatching 返回 null，静默降级）。
 *  只读探测，无 EDT 约束（与 [cn.song.mybatisplus.visualizer.execute.DialectPreMapping] 的
 *  读探测同类）；调用方通常已在后台线程。
 *
 * T-PS5-03：结果加**单槽 TTL 缓存**——方言智能推断开启后单次预览会多次走
 * [DialectRegistry.effective]（报告实测约 7 次），每次探测都是一串反射调用；URL 本身是
 * 低频变更数据，TTL 窗口内复用即可。失效双保险：
 *  1. [invalidate]——agent 会话建立/断开事件点清（[cn.song.mybatisplus.visualizer.log.agent.AgentManager]）；
 *  2. TTL 上界（默认 5s）——数据源编辑后**至多一个 TTL 窗口内方言滞后**（可接受：预览是只读展示）。
 * 槽位持项目**弱引用**（不因缓存滞留已关闭项目），项目身份不同即重探。
 */
internal object DataSourceUrlProbe {

    private val LOG = Logger.getInstance(DataSourceUrlProbe::class.java)

    /** 单槽缓存条目（项目弱引用 + 结果 + 时间戳）。 */
    private class UrlSlot(
        val project: java.lang.ref.WeakReference<Project>,
        val url: String?,
        val atMs: Long,
    )

    private class CandidateSlot(
        val project: java.lang.ref.WeakReference<Project>,
        val candidates: List<DialectDecisionEngine.Candidate>,
        val atMs: Long,
    )

    /** T-DialectQuoteWarn §十二：**按名**探测的单槽缓存（键＝项目身份 + 数据源名）。 */
    private class NamedUrlSlot(
        val project: java.lang.ref.WeakReference<Project>,
        val name: String,
        val url: String?,
        val atMs: Long,
    )

    /** T-PS5-03：TTL 上界（毫秒）。测试注入短窗驱动过期语义。 */
    internal var ttlMs: Long = 5_000L

    /** T-PS5-03 测试缝：时间源（默认系统时钟）。 */
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    /** T-PS5-03 测试缝：探测替身（默认 null = 走真实反射链）。 */
    internal var probeForTest: ((Project) -> String?)? = null

    /** T-DialectQuoteWarn §十二测试缝：按名探测替身（默认 null = 走真实反射链）。 */
    internal var probeByNameForTest: ((Project, String) -> String?)? = null

    internal var probeCandidatesForTest: ((Project) -> List<DialectDecisionEngine.Candidate>)? = null

    /** T-PS5-03 测试缝：真实探测调用计数（断言 TTL 窗口内只探测一次）。 */
    internal val probeCount = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var slot: UrlSlot? = null

    @Volatile
    private var namedSlot: NamedUrlSlot? = null

    @Volatile
    private var candidateSlot: CandidateSlot? = null

    /**
     * 取项目数据源列表中首个非空 URL（多数据源取列表序首个——DataSourceManager 列表序即
     * 用户面板展示序；尚无「最近使用」元数据可依，一期不深究）。
     * 探测失败（类缺失/反射异常/无数据源）→ null。TTL 窗口内命中缓存不再探测。
     */
    fun firstUrl(project: Project?): String? {
        if (project == null) return null
        val now = clockMs()
        slot?.let { cached ->
            if (cached.project.get() === project && now - cached.atMs <= ttlMs) return cached.url
        }
        val url = probeFirstUrl(project)
        slot = UrlSlot(java.lang.ref.WeakReference(project), url, now)
        return url
    }

    /** 多数据源只返回候选方言与会话内身份，不把连接 URL 留在决策模型中。 */
    fun candidateDialects(project: Project?): List<DialectDecisionEngine.Candidate> {
        if (project == null) return emptyList()
        val now = clockMs()
        candidateSlot?.let { cached ->
            if (cached.project.get() === project && now - cached.atMs <= ttlMs) return cached.candidates
        }
        val candidates = probeCandidateDialects(project)
        candidateSlot = CandidateSlot(java.lang.ref.WeakReference(project), candidates, now)
        return candidates
    }

    /**
     * T-DialectQuoteWarn §十二：取**指定名字**的数据源 URL（同名取首个非空）。
     *
     * 与 [firstUrl] 同一反射链（`DataSourceManager.getManagers` → `getDataSources()` →
     * `getName()` 匹配 → `getConnectionConfig().getUrl()`），差别只在**按名匹配**而非取首个。
     * 为什么必须按名：预览绑定的是「上次使用 / 已绑定」那一个数据源（面板 `bindstate.associated`），
     * 与项目内**首个**数据源可以不同且连接选项相反（本项目 demo：首个 jpa_demo、绑定 mpj_demo，
     * 两者 `ANSI_QUOTES` 状态相反）——拿 [firstUrl] 当绑定源会给出错误结论。
     *
     * `project == null`、`name` 空、探测失败（类缺失/反射异常/无同名数据源）→ null（调用方按
     * 「未知」处理，不假装知道连接选项）。TTL 窗口内同 (项目, 名) 命中缓存不再探测。
     */
    fun urlByName(project: Project?, name: String?): String? {
        if (project == null || name.isNullOrBlank()) return null
        val now = clockMs()
        namedSlot?.let { cached ->
            if (cached.project.get() === project && cached.name == name && now - cached.atMs <= ttlMs) {
                return cached.url
            }
        }
        val url = probeUrlByName(project, name)
        namedSlot = NamedUrlSlot(java.lang.ref.WeakReference(project), name, url, now)
        return url
    }

    /**
     * T-PS5-03：缓存失效（agent 会话建立/断开事件点调用）。
     * `project == null` 或与本槽项目同身份时清槽（单槽语义：异项目槽本就不可命中）。
     */
    fun invalidate(project: Project?) {
        val cached = slot
        if (cached != null && (project == null || cached.project.get() === project)) slot = null
        val named = namedSlot
        if (named != null && (project == null || named.project.get() === project)) namedSlot = null
        val candidates = candidateSlot
        if (candidates != null && (project == null || candidates.project.get() === project)) candidateSlot = null
    }

    /** T-PS5-03 测试缝：无条件清槽（用例隔离）。 */
    internal fun invalidateAllForTest() {
        slot = null
        namedSlot = null
        candidateSlot = null
    }

    private fun probeCandidateDialects(project: Project): List<DialectDecisionEngine.Candidate> {
        probeCandidatesForTest?.let {
            probeCount.incrementAndGet()
            return it(project)
        }
        probeForTest?.let {
            val name = UrlSubprotocolDialects.dialectName(firstUrl(project)) ?: return emptyList()
            return listOf(DialectDecisionEngine.Candidate("test-first-url", name))
        }
        probeCount.incrementAndGet()
        return runCatching {
            if (project.isDisposed) return emptyList()
            val candidates = mutableListOf<DialectDecisionEngine.Candidate>()
            dataSourceManagers(project).orEmpty().forEachIndexed managerLoop@ { managerIndex, manager ->
                val dataSources = invoke(manager, "getDataSources") as? List<*> ?: return@managerLoop
                dataSources.forEachIndexed sourceLoop@ { sourceIndex, source ->
                    if (source == null) return@sourceLoop
                    val config = invoke(source, "getConnectionConfig")
                    val url = config?.let { invoke(it, "getUrl") as? String }
                    val classified = classifyDataSource(manager, source, url)
                    // T-DialectInferFix：非 SQL 数据源（Redis/Mongo/…）不是 SQL 执行目标，投进候选
                    // 只会让决策落 AMBIGUOUS（见 DataSourceDialectClassification KDoc），直接不参与投票。
                    if (!classified.isSqlTarget()) {
                        LOG.debug(
                            "DataSourceUrlProbe: skip non-SQL data source #$managerIndex-$sourceIndex " +
                                "(${dbmsDisplayName(source) ?: "?"})",
                        )
                        return@sourceLoop
                    }
                    candidates += DialectDecisionEngine.Candidate(
                        "session-$managerIndex-$sourceIndex", classified.dialectName,
                    )
                }
            }
            candidates
        }.getOrDefault(emptyList())
    }

    /** T-DialectInferFix：单数据源分类（平台 query language → DBMS 显示名 → URL 子协议）。 */
    private fun classifyDataSource(
        manager: Any,
        source: Any,
        url: String?,
    ): DataSourceDialectClassification.Result = DataSourceDialectClassification.classify(
        queryLanguageId = queryLanguageId(manager, source),
        dbmsDisplayName = dbmsDisplayName(source),
        url = url,
    )

    /**
     * 平台给该数据源认的 query language id（`DataSourceManager.getQueryLanguage(T)`）。
     *
     * 反射口径（T-DialectInferFix P-2）：`getQueryLanguage(T)` 擦除后参数类型是
     * `com.intellij.database.model.RawDataSource`，传 `Object::class.java` 找不到方法；
     * Method 一律从**声明接口**取（实现类 `LocalDataSource` 在包内，跨 classloader 直取会
     * IllegalAccessException，见 DatabaseSchemaResolver 同款纪律）。取不到 → null（调用方降级）。
     */
    private fun queryLanguageId(manager: Any, source: Any): String? = runCatching {
        val managerClass = Class.forName(DATA_SOURCE_MANAGER_CLASS) ?: return null
        val rawType = Class.forName(RAW_DATA_SOURCE_CLASS) ?: return null
        val method = managerClass.getMethod("getQueryLanguage", rawType)
        (method.invoke(manager, source) as? com.intellij.lang.Language)?.id
    }.onFailure {
        LOG.debug("DataSourceUrlProbe.getQueryLanguage 反射失败（${manager.javaClass.name}）", it)
    }.getOrNull()

    /** 数据源 DBMS 显示名（`DasDataSource.getDbms().getDisplayName()`，取不到再取 `getName()`）。 */
    private fun dbmsDisplayName(source: Any): String? = runCatching {
        val dasType = Class.forName(DAS_DATA_SOURCE_CLASS) ?: return null
        val dbms = dasType.getMethod("getDbms").invoke(source) ?: return null
        val dbmsClass = Class.forName("com.intellij.database.Dbms") ?: return null
        (dbmsClass.getMethod("getDisplayName").invoke(dbms) as? String)
            ?: (dbmsClass.getMethod("getName").invoke(dbms) as? String)
    }.getOrNull()

    /** 真实探测（反射链，失败静默 null）；[probeForTest] 注入时走替身。 */
    private fun probeFirstUrl(project: Project): String? {
        probeCount.incrementAndGet()
        probeForTest?.let { return it(project) }
        return runCatching {
            if (project.isDisposed) return null
            val managers = dataSourceManagers(project) ?: return null
            for (manager in managers) {
                // manager 为反射拿到的任意类型（Any），统一走反射取 getDataSources()
                val dataSources = invoke(manager, "getDataSources") as? List<*> ?: continue
                for (ds in dataSources.filterNotNull()) {
                    val config = invoke(ds, "getConnectionConfig") ?: continue
                    val url = invoke(config, "getUrl") as? String
                    // T-DialectInferFix：首个非空 URL 也必须跳过非 SQL 数据源——Redis 排在前时
                    // 会取到 jdbc:redis://…，下游 dialectName 为 null，等于「首个 URL 不可用」。
                    if (url.isNullOrBlank()) continue
                    if (!classifyDataSource(manager, ds, url).isSqlTarget()) continue
                    return url
                }
            }
            null
        }.getOrNull()
    }

    /** 按名真实探测（反射链，失败静默 null）；[probeByNameForTest] 注入时走替身。 */
    private fun probeUrlByName(project: Project, name: String): String? {
        probeCount.incrementAndGet()
        probeByNameForTest?.let { return it(project, name) }
        return runCatching {
            if (project.isDisposed) return null
            val managers = dataSourceManagers(project) ?: return null
            for (manager in managers) {
                val dataSources = invoke(manager, "getDataSources") as? List<*> ?: continue
                for (ds in dataSources.filterNotNull()) {
                    // 名字面量取法与 DataSourceSelector.boundConsoleDataSourceName 同源（LocalDataSource.getName）
                    val dsName = invoke(ds, "getName") as? String ?: continue
                    if (dsName != name) continue
                    val config = invoke(ds, "getConnectionConfig") ?: continue
                    val url = invoke(config, "getUrl") as? String
                    if (!url.isNullOrBlank()) return url
                }
            }
            null
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // T-DialectQuoteWarn §十二：URL → ANSI_QUOTES 三态
    // ------------------------------------------------------------------

    /**
     * 从 JDBC URL 判定是否**已声明** `sql_mode` 含 `ANSI_QUOTES`（T-DialectQuoteWarn §十二）。
     *
     * 返回值三态：
     *  - `true`  = URL 里 `sql_mode=` 的取值含 `ANSI_QUOTES` 项 → 可证明该连接双引号是标识符引号；
     *  - `false` = URL 非空但未声明（含完全没有 `sql_mode`）→ 已知未声明；
     *  - `null`  = URL 为空/取不到 → 未知。
     *
     * **只做「声明了」的正向证明**：URL 未声明**不等于**连接未开——生效 `sql_mode` = 服务端全局 +
     * 会话覆盖，URL 里的 `sessionVariables` 只是会话覆盖，服务端全局仍可能开着。故 `false` 只表达
     * 「URL 未声明」，调用方（规则）据此只能降级 WARN，不得据此报 ERROR。
     *
     * 解析口径：先 URL-decode（连接串里 `,`/`'` 常被编码为 `%2C`/`%27`），再定位每个 `sql_mode=`
     * 赋值，取其值（带引号则读到配对引号，否则读到 `&`/`;`/串尾），按 `,`/空白分词后不区分大小写
     * 比对 `ANSI_QUOTES`。匹配从严（只认 `sql_mode` 赋值内的项），宁可漏判也不误判为「已声明」。
     */
    fun ansiQuotesFromUrl(url: String?): Boolean? {
        if (url.isNullOrBlank()) return null
        val decoded = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        var from = 0
        while (true) {
            val m = SQL_MODE_ASSIGN.find(decoded, from) ?: return false
            val afterAssign = m.range.last + 1
            val rest = decoded.substring(afterAssign)
            val quote = rest.firstOrNull()?.takeIf { it == '\'' || it == '"' }
            val value = if (quote != null) {
                val end = rest.indexOf(quote, startIndex = 1).let { if (it < 0) rest.length else it }
                rest.substring(1, end)
            } else {
                val end = rest.indexOfFirst { it == '&' || it == ';' }
                if (end < 0) rest else rest.substring(0, end)
            }
            if (value.split(',', ' ', '\t').any { it.trim().equals("ANSI_QUOTES", ignoreCase = true) }) {
                return true
            }
            from = afterAssign
        }
    }

    /** `sql_mode=` 赋值定位（不区分大小写）。 */
    private val SQL_MODE_ASSIGN = Regex("(?i)sql_mode\\s*=\\s*")

    /** `DataSourceManager.getManagers(project)` 反射调用；类缺失/异常 → null。 */
    private fun dataSourceManagers(project: Project): List<Any>? {
        val managerClass = runCatching {
            Class.forName(DATA_SOURCE_MANAGER_CLASS, true, DataSourceUrlProbe::class.java.classLoader)
        }.getOrNull() ?: return null
        val managers = runCatching {
            managerClass.getMethod("getManagers", Project::class.java).invoke(null, project)
        }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        return managers as? List<Any>
    }

    private fun invoke(target: Any, method: String): Any? = runCatching {
        target.javaClass.getMethod(method).invoke(target)
    }.onFailure {
        LOG.debug("DataSourceUrlProbe.$method 反射失败（${target.javaClass.name}）", it)
    }.getOrNull()

    /** DatabaseTools 反射面类名（T-DialectInferFix：候选分类与候选投票共用同一份字面量）。 */
    private const val DATA_SOURCE_MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager"
    private const val RAW_DATA_SOURCE_CLASS = "com.intellij.database.model.RawDataSource"
    private const val DAS_DATA_SOURCE_CLASS = "com.intellij.database.model.DasDataSource"
}
