package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.UrlSubprotocolDialects
import com.intellij.openapi.project.Project

/**
 * O-136 T-PreviewExecDsDialect：预览执行链「数据源方言 → 草稿文件 SQL 方言 → Runner 集合」路由核心。
 *
 * 根因（计划 §二，243 平台 jar 字节码实证）：平台
 * `DatabaseRunners.getAttachDataSourceRunners → DbImplUtil.getAppropriateDataSourcesForExec(psiFile)`
 * 按 **psiFile 的 SQL 方言**（`SqlPsiFacade.getConfiguredSqlLanguageDialect`，即 SqlDialectMappings
 * 的文件级映射）取 `DatabaseDialect`，再以 `ds.getDatabaseDialect().similarTo(文件方言)` 过滤数据源——
 * 插件此前把 exec 草稿恒映射 MySQL，DM 等非 MySQL 数据源的 Runner 全被排除。
 *
 * 修复方向（S1+D1，按计划推荐定案）：
 *  - 目标数据源解析：last-used 记忆（UUID→显示名）→ 插件默认数据源 → null；
 *  - 方言来源：平台 `DbSqlUtilCore.findSqlDialect(LocalDataSource)`（数据源自身方言设置 →
 *    平台按连接配置推断，即 DAS 口径）优先；平台不可得时按插件 URL 推断
 *    （[cn.song.mybatisplus.visualizer.sql.UrlSubprotocolDialects]）映射平台 SQL 方言 language id
 *    兜底；仍不可得 → null = 保持 MySQL 映射（零回归，不猜近似方言）；
 *  - 组 Runner 展开：平台在「方言匹配集 ≠ 全部数据源」时返回一个 "All" 组 Runner
 *    （`getSubRunners()` 携带全部数据源的 Runner；组项自身 runnable 为 EmptyRunnable 空操作），
 *    插件弹窗不展开子级 → 方言不匹配的数据源（DM 等）藏在死项里不可选。展开后按显示名去重浮出。
 *
 * 反射隔离约定：平台类一律 `Class.forName` 反射 + runCatching，Community/无 Database 插件下降级
 * null（调用方保持 MySQL 现状），与 [DataSourceSettings] 同款口径。
 */
internal object ExecDialectRouting {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ExecDialectRouting::class.java)

    /** 组 Runner 展开最大轮数：平台当前最多两级（`DatabaseRunners$4 → $4$1`）。 */
    private const val MAX_GROUP_DEPTH = 3

    /** 平台 MySQL SQL 方言 language id（[DialectPreMapping.findMySql] 同款判定口径）。 */
    internal const val MYSQL_LANGUAGE_ID = "MySQL"
    internal const val GENERIC_LANGUAGE_ID = "GenericSQL"

    /**
     * 插件方言名（[cn.song.mybatisplus.visualizer.sql.DialectRegistry] 命名空间，键须与
     * [cn.song.mybatisplus.visualizer.sql.Dialect.name] 逐字一致）→ 平台 SQL 方言 language id
     * 候选清单（按序尝试，命中平台可用清单为止）。
     *
     * id 依据：243 平台 com/intellij/sql/dialects 目录下各方言 xml 的 `lang.parserDefinition language`
     * 逐文件核对——MySQL / PostgreSQL / Oracle / MariaDB / TSQL(mssql) / H2 为 243 在册实锤；
     * DM / KingBase / OceanBase / openGauss / SQLite / HighGo / Vastbase 在 243 方言集缺位
     * （无对应 dialect xml），候选 id 取产品名常见注册形态，真平台缺位时 [urlFallbackId]
     * 自然落 null（不猜近似方言）。家族别名仅作平台缺位时的尽力兜底（如 OceanBase MySQL
     * 模式 → MySQL），别名错误最多令 Runner 枚举落空（与现状同态），不产生错误执行。
     */
    internal val PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS: Map<String, List<String>> = mapOf(
        "MySQL" to listOf("MySQL"),
        "MariaDB" to listOf("MariaDB", "MySQL"),
        "PostgreSQL" to listOf("PostgreSQL"),
        "Oracle" to listOf("Oracle"),
        "SQL Server" to listOf("TSQL"),
        "H2" to listOf("H2"),
        "SQLite" to listOf("SQLite"),
        "DM（达梦）" to listOf("DM", "Dameng"),
        "KingBase（人大金仓）" to listOf("KingBase", "KingbaseES", "PostgreSQL"),
        "OceanBase" to listOf("OceanBase", "MySQL"),
        "openGauss" to listOf("openGauss", "PostgreSQL"),
        // T-GaussDbDialect（券 04）：独立方言。262 平台无 GaussDB / openGauss 方言模块
        // （.intellijPlatform 制品目录 grep gauss 零命中），故候选按「同族 id → PG 兜底」排；
        // 漏登记此键会让执行目标 Runner 枚举落空并回退 MySQL（功能不生效）。
        "GaussDB" to listOf("GaussDB", "openGauss", "PostgreSQL"),
        // T-XcDialects3（券 03）：瀚高/海量同 PG 族。262 平台无 HighGo / Vastbase 方言模块
        // （本机 IDEA 262.10968.63 的 intellij.database.dialects.*.jar 逐包列举实证，P-11），
        // 候选链恒落 PG 兜底——照 GaussDB 同款「本名 → PG 兜底」写法。
        "HighGo" to listOf("HighGo", "PostgreSQL"),
        "Vastbase" to listOf("Vastbase", "PostgreSQL"),
    )

    /** 目标数据源解析测试缝（headless 无 DataSourceStorage；生产恒 null 走真链）。 */
    @Volatile
    internal var targetDataSourceForTest: ((Project) -> Any?)? = null

    /** last-used 记忆的轻量投影（与 DataSourceSelector.LastUsedMemory 同构，测试面免平台类型）。 */
    internal data class MemoryRef(val dataSourceId: String?, val displayName: String?)

    // ------------------------------------------------------------------
    // 纯逻辑（表驱动单测）
    // ------------------------------------------------------------------

    /**
     * 目标方言 id 决策：平台 DAS 方言优先，URL 推断兜底；皆无（或空白）→ null = 保持 MySQL 映射。
     */
    internal fun targetLanguageId(platformDialectId: String?, urlFallbackId: String?): String? =
        platformDialectId?.takeIf { it.isNotBlank() } ?: urlFallbackId?.takeIf { it.isNotBlank() }

    /**
     * 目标数据源解析（纯函数）：记忆 UUID 精确命中 → 显示名唯一命中 → 插件默认数据源 → null。
     * 与 T-SI-02 的记忆命中口径同源（UUID 优先、名字回退、皆不唯一不猜）。
     */
    internal fun resolveTargetDataSourceFromInputs(
        memory: MemoryRef?,
        dataSourceById: (String) -> Any?,
        registeredDataSources: List<Any>,
        dataSourceName: (Any) -> String,
        defaultDataSource: () -> Any?,
    ): Any? {
        if (memory != null) {
            memory.dataSourceId?.takeIf { it.isNotBlank() }?.let { id ->
                dataSourceById(id)?.let { return it }
            }
            memory.displayName?.takeIf { it.isNotBlank() }?.let { name ->
                registeredDataSources.firstOrNull { dataSourceName(it) == name }?.let { return it }
            }
        }
        return defaultDataSource()
    }

    /**
     * URL 兜底：数据源 URL → 插件子协议推断 → 平台 SQL 方言 language id 候选 → 平台可用清单命中。
     * 任何一步不可得 → null（调用方保持 MySQL，不猜）。
     */
    internal fun urlFallbackId(ds: Any?, availableIds: List<String>): String? {
        if (ds == null) return null
        val url = dataSourceUrl(ds) ?: return null
        val pluginDialect = cn.song.mybatisplus.visualizer.sql.UrlSubprotocolDialects.dialectName(url) ?: return null
        for (candidate in PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS[pluginDialect].orEmpty()) {
            if (availableIds.any { it.equals(candidate, ignoreCase = true) }) return candidate
        }
        return null
    }

    /**
     * 组 Runner 展开判定（纯函数）：该 runner 是否携带非空子 Runner 列表。
     *
     * 空子项组**保留原项**（`subs.isNullOrEmpty()` 与「非组项」同支）：平台「All」组在数据源
     * introspect 完成前子 Runner 为空，此时组项仍是唯一执行句柄——按空展开剔除会把 Runner
     * 列表清空、命中空 Runner 归因文案并终结执行链（20261014 testDbConsole 集成回归实证）。
     */
    internal fun <T> expandGroupRunners(
        runners: List<T>,
        nameOf: (T) -> String,
        subRunnersOf: (T) -> List<T>?,
    ): List<T> {
        val out = ArrayList<T>(runners.size)
        val seen = HashSet<String>()
        for (r in runners) {
            val subs = subRunnersOf(r)
            if (subs.isNullOrEmpty()) {
                if (seen.add(nameOf(r))) out.add(r)
                continue
            }
            // 组项本体剔除（有子项可浮出时组项不再承载可选项）；子 Runner 浮出顶层、按显示名去重
            for (sub in subs) {
                if (seen.add(nameOf(sub))) out.add(sub)
            }
        }
        return out
    }

    /**
     * 空 Runner 文案选择（D3）：项目存在已配置数据源 → 方言不匹配可行动文案；
     * 真的没有任何数据源 → 既有空数据源引导文案。
     */
    internal fun emptyRunnersHintKey(hasRegisteredDataSources: Boolean): String =
        if (hasRegisteredDataSources) "execute.datasource.dialectMismatch" else "execute.datasource.empty"

    // ------------------------------------------------------------------
    // 反射段（平台不可用一律降级 null / 空清单）
    // ------------------------------------------------------------------

    /** 数据源 JDBC URL（LocalDataSource.getUrl() 反射；取不到 null）。 */
    internal fun dataSourceUrl(ds: Any?): String? = runCatching {
        ds?.javaClass?.getMethod("getUrl")?.invoke(ds) as? String
    }.getOrNull()

    /**
     * 平台方言探测：`DbSqlUtilCore.findSqlDialect(LocalDataSource)` 的 language id——
     * 数据源自身方言设置（getDefaultDialect）→ 平台按连接配置推断，null = 平台不可用/数据源无方言。
     * 刻意用可空的 findSqlDialect 而非 getSqlDialect（后者兜底 Generic 方言，会把枚举引向空集）。
     */
    internal fun platformDialectId(ds: Any?): String? = runCatching {
        if (ds == null) return null
        val core = Class.forName("com.intellij.database.util.DbSqlUtilCore")
        val method = core.getMethod("findSqlDialect", Class.forName("com.intellij.database.dataSource.LocalDataSource"))
        val dialect = method.invoke(null, ds) ?: return null
        (dialect as? com.intellij.lang.Language)?.id
    }.getOrNull()

    /** 平台可用 SQL 方言 language id 清单（SqlDialectMappings.getAvailableValues 投影；失败空清单）。 */
    internal fun availableLanguageIds(project: Project): List<String> = runCatching {
        val mappings = Class.forName("com.intellij.sql.dialects.SqlDialectMappings")
            .getMethod("getInstance", Project::class.java).invoke(null, project)
        @Suppress("UNCHECKED_CAST")
        val values = mappings.javaClass.getMethod("getAvailableValues").invoke(mappings) as? List<Any?>
        values?.mapNotNull { (it as? com.intellij.lang.Language)?.id } ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * 目标 SQL Language 实例（[DialectPreMapping.setMapping] 消费形态）：
     * 平台方言 id → URL 兜底 id → 平台可用清单内取 Language；null = 保持 MySQL 映射（零回归）。
     */
    internal fun resolveTargetLanguage(project: Project, ds: Any?): Any? {
        if (ds == null) return null
        val platform = runCatching { platformDialectId(ds) }.getOrNull()
        val url = runCatching { urlFallbackId(ds, availableLanguageIds(project)) }.getOrNull()
        val targetId = targetLanguageId(platform, url) ?: return null
        return runCatching { DialectPreMapping.languageById(project, targetId) }.getOrNull()
    }

    /** 插件渲染方言 → 平台 SQL Language。按候选顺序命中；平台无对应方言时诚实返回 null。 */
    internal fun resolvePluginDialectLanguage(project: Project, dialectName: String?): Any? {
        if (dialectName.isNullOrBlank()) return null
        for (id in PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS[dialectName].orEmpty()) {
            runCatching { DialectPreMapping.languageById(project, id) }.getOrNull()?.let { return it }
        }
        return null
    }

    /**
     * 目标数据源解析（生产链）：last-used 记忆（UUID→显示名）→ 插件默认数据源 → null。
     */
    internal fun resolveTargetDataSource(project: Project): Any? {
        targetDataSourceForTest?.let { return it(project) }
        val memory = runCatching {
            DataSourceSelector.lastUsedRunner(project)
        }.getOrNull()?.let { MemoryRef(it.dataSourceId, it.displayName) }
        return resolveTargetDataSourceFromInputs(
            memory = memory,
            dataSourceById = { id -> runCatching { DataSourceSettings.getDataSourceById(project, id) }.getOrNull() },
            registeredDataSources = runCatching { DataSourceSettings.getAllDataSources(project) }.getOrDefault(emptyList()),
            dataSourceName = { ds -> DataSourceSettings.getName(ds) },
            defaultDataSource = { runCatching { DataSourceSettings.getDefaultDataSource(project) }.getOrNull() },
        )
    }

    /** 方言名归一（大小写与首尾空白不参与比对；空值返回 null）。 */
    private fun knownDialectName(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()

    /**
     * 把 exec 文件重映射到指定平台 SQL 方言（runner 枚举为空时的兜底）。
     *
     * 用途：平台 `getAttachDataSourceRunners` 按 psiFile 已映射方言筛 runner，数据源无平台
     * Dialects 映射时该方言筛不出 runner。重映射到**目标数据源方言**后再枚举即可命中，
     * 后续控制台/结果表/审计全部复用既有链路（不新建执行通道）。
     *
     * @return true = 映射已就位（可重试枚举）；false = 平台无该方言或写映射失败
     */
    internal fun remapExecFileDialect(project: Project, vf: com.intellij.openapi.vfs.VirtualFile, language: Any?): Boolean =
        runCatching { DialectPreMapping.ensureMapped(project, vf, language, inProcessMapped = false) }
            .getOrDefault(false)

    /**
     * 组 Runner 展开生产链：反射 `getSubRunners()`（平台组 Runner 标志方法，
     * DatabaseRunners$3 字节码实证）。
     *
     * 可访问性（2026-10-19 真机回归）：`DatabaseRunners$3` / `$4$1` 是包私有类，
     * `Method.invoke` 前必须 `isAccessible = true`。缺这一行时 `IllegalAccessException`
     * 被 runCatching 吞掉，组项被当成普通项保留；其 `run()` 绑定 EmptyRunnable，
     * 于是「runRunner invoked OK」后永远等不到 session，直到准备门 60s 超时。
     *
     * 展开最多 [MAX_GROUP_DEPTH] 轮（平台存在两级组）；超过深度仍是组项（含自引用）时
     * 剔除并记录——保留空操作组只会把 60s 悬挂留回给用户，剔除后上层走空 Runner 归因文案。
     */
    internal fun expandGroupRunners(runners: List<Any>): List<Any> {
        var current = runners
        repeat(MAX_GROUP_DEPTH) {
            current = expandGroupRunners(
                current,
                nameOf = { ds -> DataSourceSelector.getRunnerName(ds) },
                subRunnersOf = { runner -> subRunnersOf(runner) },
            )
            if (current.none { isGroupRunner(it) }) return current
        }
        current.filter { isGroupRunner(it) }.forEach { runner ->
            LOG.warn(
                "[M36] unexpanded group runner dropped after $MAX_GROUP_DEPTH passes: " +
                    "${runner.javaClass.name} (subs=${subRunnersOf(runner)?.size})",
            )
        }
        return current.filterNot { isGroupRunner(it) }
    }

    /** 组 Runner 判定：`getSubRunners()` 返回非空列表；反射失败按非组项处理（保持旧行为）。 */
    private fun isGroupRunner(runner: Any): Boolean = !subRunnersOf(runner).isNullOrEmpty()

    private fun subRunnersOf(runner: Any): List<Any>? {
        // 桩对象/非平台 Runner 没有该方法：属正常形态，不记日志（生产 Runner 继承基类方法）。
        val method = runCatching { runner.javaClass.getMethod("getSubRunners") }.getOrNull() ?: return null
        return runCatching {
            // 平台组 Runner（DatabaseRunners$3/$4$1）是包私有类：不设 accessible 会抛
            // IllegalAccessException，展开整链静默降级成「保留组项 → 执行空操作 → 60s 超时」。
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            method.invoke(runner) as? List<Any>
        }.onFailure { e ->
            LOG.warn(
                "[M36] getSubRunners reflection failed: ${runner.javaClass.name} " +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
        }.getOrNull()
    }

    /**
     * 空操作 Runner 识别：平台组项（"All"）的 runnable 恒为 `EmptyRunnable`。
     * 选中它执行等于什么都不做，必须立即报错，而不是等准备门 60s 超时。
     * 字段声明在 `DatabaseRunners$MyRunner`（父类），按继承链查找。
     */
    internal fun isNoOpRunner(runner: Any): Boolean = runCatching {
        var type: Class<*>? = runner.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField("runnable") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return@runCatching field.get(runner) is com.intellij.openapi.util.EmptyRunnable
            }
            type = type.superclass
        }
        false
    }.getOrDefault(false)
}
