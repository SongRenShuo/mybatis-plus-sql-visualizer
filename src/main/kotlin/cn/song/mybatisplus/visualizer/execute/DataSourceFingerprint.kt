package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * T-HdAbsorb D218（吸收自 Hibernate Debugger 的 `HibernateDataSourceFingerprint` 五元组思路）：
 * last-used 记忆的**指纹兜底层**。
 *
 * 背景：last-used 记忆有两级身份（UUID 键 `mpsql.visualizer.last.runner.id` + 显示名键
 * `mpsql.visualizer.last.runner`，见 [DataSourceSelector.LastUsedKeys]）——**两个键都以
 * 「名字/ID 不变」为前提**。数据源被改名（或 IDE 重装后 UUID 重排）时，T-SI-02 链
 * （UUID 精确命中 → 名字唯一命中 → 同名歧义弃）两级全部落空，静默复用失效、每次预览都要
 * 重新弹选择器。指纹兜底的记忆对象是**连接事实本身**（url+user+dbms 归一化哈希），改名/重建
 * 不影响——只要连接事实还在项目里，就能恢复静默复用。
 *
 * 实现口径（吸收时按本仓可读面收敛，与对方五元组的差异如实登记）：
 *  - 对方五元组 = jdbcUrl+driver+username+catalog+schema；本仓反射链（[DataSourceUrlProbe]
 *    同款 DatabaseTools 面）稳定可读的是 **url / user / dbms 显示名** 三元——catalog/schema
 *    无公开反射通道，刻意不猜（读不到就不进指纹，而不是拿空串凑满五元让两个不同库同指纹）。
 *  - **只做兜底、不做主键**：指纹命中排在 UUID/名字两级全落空之后；命中仍走 runner 侧
 *    方言闸与既有执行链，不新增任何「绕过闸」的路径。
 *  - **歧义即弃**（T-SI-02 同纪律）：多个数据源同指纹不猜，交选择器。
 *  - 写入走 EDT（T-LastUsedEdt 同款纪律——PropertiesComponent 是项目级持久化状态）。
 */
internal object DataSourceFingerprint {

    private val LOG = Logger.getInstance(DataSourceFingerprint::class.java)

    /** 指纹记忆键（经 [DataSourceSelector.LastUsedKeys.key] 拼模块后缀，与 last-used 双键同族）。 */
    internal const val FINGERPRINT_KEY = "mpsql.visualizer.last.runner.fingerprint"

    /**
     * 指纹计算（纯函数，headless 直测）：url 必须非空（无 URL 不构成指纹——非 SQL 源
     * （Redis/Mongo）不该进记忆）；user/dbms 空按 null 归一参与哈希。稳定哈希 SHA-256 hex。
     */
    fun compute(url: String?, user: String?, dbms: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return null
        val normalized = "${u.replace(Regex("\\s+"), "")}\u0000${user?.trim()?.takeIf { it.isNotEmpty() } ?: "-"}" +
            "\u0000${dbms?.trim()?.takeIf { it.isNotEmpty() } ?: "-"}"
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            // T-FP 纪律：fatal 透传（指纹算不出只是降级为「无指纹兜底」，VirtualMachineError 不吞）
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) throw t
            LOG.debug("DataSourceFingerprint: digest unavailable", t)
            null
        }
    }

    /** 读记忆指纹（[moduleKey] 语义与 last-used 双键一致：模块级优先、项目级兜底）。 */
    fun remembered(project: Project?, moduleKey: String?): String? = runCatching {
        val pc = com.intellij.ide.util.PropertiesComponent.getInstance(project ?: return null)
        pc.getValue(DataSourceSelector.LastUsedKeys.key(FINGERPRINT_KEY, moduleKey))
    }.getOrNull()

    /**
     * 写/清记忆指纹（EDT 纪律同 [DataSourceSelector.rememberRunner]；null/blank = 清——
     * 「这次选的源算不出指纹」时留着旧指纹会指认另一个源，指纹说谎比没有指纹更糟）。
     */
    fun remember(project: Project, moduleKey: String?, fingerprint: String?) {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater(
                { remember(project, moduleKey, fingerprint) },
                com.intellij.openapi.application.ModalityState.any(),
            )
            return
        }
        runCatching {
            val pc = com.intellij.ide.util.PropertiesComponent.getInstance(project)
            val key = DataSourceSelector.LastUsedKeys.key(FINGERPRINT_KEY, moduleKey)
            if (fingerprint.isNullOrBlank()) pc.unsetValue(key) else pc.setValue(key, fingerprint)
        }
    }

    /**
     * 指纹兜底解析（纯函数核心，headless 直测）：在候选指纹清单里找与记忆指纹唯一相等的下标。
     * 唯一命中 → 下标；零命中或多条同指纹歧义 → null（歧义即弃，不猜）。
     */
    fun matchIn(candidateFingerprints: List<String?>, remembered: String?): Int? {
        if (remembered.isNullOrBlank()) return null
        val hits = candidateFingerprints.withIndex().filter { it.value != null && it.value == remembered }
        return if (hits.size == 1) hits.single().index else null
    }

    /** O-96 同款测试缝：headless 注入「候选数据源 (名, 指纹) 清单」替代真反射链；产线恒 null。 */
    internal var enumerateForTest: (() -> List<Pair<String, String?>>)? = null

    /**
     * 兜底解析（反射链，失败静默 null）：按记忆指纹在项目数据源清单里找唯一命中，返回其显示名。
     * 上层（DataSourceSelectorResults 静默复用链）拿名字到 runner 集合里精确匹配后走既有执行链。
     */
    fun resolveRunnerName(project: Project, moduleKey: String?): String? {
        val remembered = remembered(project, moduleKey) ?: return null
        val candidates = enumerateForTest?.invoke() ?: enumerateDataSourceFingerprints(project)
        if (candidates.isEmpty()) return null
        val at = matchIn(candidates.map { it.second }, remembered) ?: return null
        val name = candidates.getOrNull(at)?.first
        LOG.info("[D218] fingerprint fallback matched: $name (moduleKey=$moduleKey)")
        return name
    }

    /**
     * 枚举项目数据源的 (显示名, 指纹)（反射链与 [DataSourceUrlProbe] 同款类名面：
     * DataSourceManager.getManagers → getDataSources → getConnectionConfig.getUrl/getUser(或
     * getUserName) + DasDataSource.getDbms().getDisplayName）；读不到的维度按 null 归一。
     */
    private fun enumerateDataSourceFingerprints(project: Project): List<Pair<String, String?>> = runCatching {
        if (project.isDisposed) return emptyList()
        val managerClass = Class.forName(DATA_SOURCE_MANAGER_CLASS) ?: return emptyList()
        val managers = managerClass.getMethod("getManagers", Project::class.java).invoke(null, project) as? List<*>
            ?: return emptyList()
        val out = mutableListOf<Pair<String, String?>>()
        for (manager in managers.filterNotNull()) {
            val sources = runCatching { manager.javaClass.getMethod("getDataSources").invoke(manager) as? List<*> }
                .getOrNull() ?: continue
            for (ds in sources.filterNotNull()) {
                val name = runCatching { ds.javaClass.getMethod("getName").invoke(ds) as? String }
                    .getOrNull() ?: continue
                out += name to fingerprintOfDataSource(ds)
            }
        }
        out
    }.getOrDefault(emptyList())

    /** 单数据源 → 指纹（[rememberRunner] 写入与枚举共用同一口径）；读不到 url → null。 */
    internal fun fingerprintOfDataSource(ds: Any): String? {
        val config = runCatching { ds.javaClass.getMethod("getConnectionConfig").invoke(ds) }.getOrNull()
        val url = config?.let { runCatching { it.javaClass.getMethod("getUrl").invoke(it) as? String }.getOrNull() }
        val user = config?.let {
            runCatching { it.javaClass.getMethod("getUser").invoke(it) as? String }
                .getOrElse {
                    runCatching { it.javaClass.getMethod("getUserName").invoke(it) as? String }.getOrNull()
                }
        }
        val dbms = runCatching {
            val dasType = Class.forName(DAS_DATA_SOURCE_CLASS)
            val dbms = dasType?.getMethod("getDbms")?.invoke(ds)
            val dbmsClass = Class.forName("com.intellij.database.Dbms")
            (dbmsClass.getMethod("getDisplayName").invoke(dbms) as? String)
                ?: (dbmsClass.getMethod("getName").invoke(dbms) as? String)
        }.getOrNull()
        return compute(url, user, dbms)
    }

    private const val DATA_SOURCE_MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager"
    private const val DAS_DATA_SOURCE_CLASS = "com.intellij.database.model.DasDataSource"
}
