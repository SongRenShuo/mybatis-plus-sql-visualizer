package cn.song.mybatisplus.visualizer.sql

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * T-DialectInferFix（增补券）：**没有配置 IDE 数据源**时的方言证据。
 *
 * 用户实际场景里很常见「只在 `application.yml` 里写了 JDBC URL，Database 工具窗一个数据源都没配」，
 * 或「项目里配了数据源但从没连接过」。旧实现只认 agent 捕获 URL 与 IDE 数据源，两者都没有就一路
 * 兜底 MySQL——对这些项目等于「智能推断从未生效」。
 *
 * 这里补三层**只读、可解释、fail-closed** 的证据（全部低置信度，UI 必须披露来源）：
 *
 *  1. [configDialect]：项目内 `application*` / `bootstrap*` 配置文件里的 `jdbc:<子协议>:`。
 *     多个文件出现**不同**子协议 → 不猜（null）。
 *  2. [dependencyDialect]：`pom.xml` / `build.gradle(.kts)` / `libs.versions.toml` 里的数据库驱动坐标。
 *     多个不同驱动的方言 → 不猜。
 *  3. [syntaxHint]：SQL 文本里的**方言独占**特征（反引号、`LIMIT a, b`、`::type`、`ROWNUM`、`TOP n`…）。
 *     只认强特征，且要求「只有一个方言命中」，否则 null。
 *
 * 缓存：单槽 TTL（默认 30s）+ 项目弱引用；[enabled] 是测试缝（并发用例可关掉，避免真实工程扫描）。
 * 扫描范围有上界（[MAX_CONFIG_FILES] / [MAX_BUILD_FILES] / [MAX_TEXT_CHARS]），不会扫整仓。
 */
internal object ProjectDialectEvidence {

    data class Evidence(val dialectName: String, val source: DialectDecisionEngine.Source)

    /** 扫描的配置文件扩展名（按扩展名走 FilenameIndex，覆盖 Boot 的连字符命名）。 */
    private val CONFIG_EXTENSIONS = listOf("yml", "yaml", "properties")

    /** 参与驱动坐标扫描的构建文件（按名索引）。 */
    private val BUILD_FILE_NAMES = listOf("pom.xml", "build.gradle", "build.gradle.kts")

    private const val MAX_CONFIG_FILES = 24
    private const val MAX_BUILD_FILES = 12
    private const val MAX_TEXT_CHARS = 262_144

    /** JDBC URL 定位（只需子协议；连接串里的账号密码不进入任何返回值）。 */
    private val JDBC_URL = Regex("jdbc:([a-zA-Z0-9]+):")

    /**
     * 驱动坐标标记 → 方言名（小写子串匹配）。键取各驱动**发布坐标里独有**的片段，
     * 不用 `h2`/`dm` 这类短词（会在任意文本里误命中）。
     */
    private val DRIVER_MARKERS: List<Pair<String, String>> = listOf(
        "mysql-connector-j" to "MySQL",
        "mysql-connector-java" to "MySQL",
        "com.mysql:mysql" to "MySQL",
        "mariadb-java-client" to "MariaDB",
        "org.mariadb.jdbc" to "MariaDB",
        "org.postgresql:postgresql" to "PostgreSQL",
        "com.oracle.database.jdbc" to "Oracle",
        "ojdbc" to "Oracle",
        "mssql-jdbc" to "SQL Server",
        "sqljdbc" to "SQL Server",
        "sqlite-jdbc" to "SQLite",
        "com.h2database" to "H2",
        "com.dameng" to "DM（达梦）",
        "dmjdbc" to "DM（达梦）",
        "kingbase8" to "KingBase（人大金仓）",
        "opengauss" to "openGauss",
        "gaussdb" to "openGauss",
    )

    /** 方言独占语法特征（小写正则；只认强特征，宁可漏判）。 */
    private val SYNTAX_MARKERS: List<Pair<String, List<Regex>>> = listOf(
        "MySQL" to listOf(
            Regex("`[A-Za-z_][A-Za-z0-9_]*`"),
            Regex("\\blimit\\s+\\d+\\s*,\\s*\\d+"),
            Regex("\\bon\\s+duplicate\\s+key\\b"),
            Regex("\\bifnull\\s*\\("),
            Regex("\\bgroup_concat\\s*\\("),
            Regex("\\bauto_increment\\b"),
        ),
        "PostgreSQL" to listOf(
            Regex("::[a-z][a-z0-9_]*(\\[\\])?"),
            Regex("\\bilike\\b"),
            Regex("\\bnextval\\s*\\("),
            Regex("\\bjsonb\\b"),
            Regex("\\bserial\\b"),
        ),
        "Oracle" to listOf(
            Regex("\\bnvl\\s*\\("),
            Regex("\\brownum\\b"),
            Regex("\\(\\+\\)"),
            Regex("\\bminus\\b"),
            Regex("\\bvarchar2\\b"),
            Regex("\\bdual\\b"),
        ),
        "SQL Server" to listOf(
            Regex("\\bselect\\s+top\\s+\\d+"),
            Regex("\\bgetdate\\s*\\("),
            Regex("\\bisnull\\s*\\("),
            Regex("\\[[A-Za-z_][A-Za-z0-9_]*\\]"),
        ),
    )

    private class Slot(
        val project: java.lang.ref.WeakReference<Project>,
        val atMs: Long,
        val evidence: Evidence?,
    )

    @Volatile
    private var slot: Slot? = null

    /** 测试缝：TTL 上界（毫秒）。 */
    internal var ttlMs: Long = 30_000L

    /** 测试缝：时间源。 */
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    /**
     * 是否允许**真实扫描**。单元测试模式默认关闭：
     *  - 全量 8500+ 例里凡走 `DialectRegistry.effective(project)` 的都会各扫一遍工程文件，纯浪费；
     *  - 更危险的是**仓库自身**的 `build.gradle.kts` 含 `libs.postgresql`（测试依赖），
     *    任何以真实工程为 basePath 的测试项目都会被它判成 PostgreSQL，污染既有断言。
     * 语义由 [configTextsForTest] / [buildTextsForTest] 缝覆盖，接线由真机覆盖。
     */
    internal var enabled: Boolean = com.intellij.openapi.application.ApplicationManager.getApplication()
        ?.isUnitTestMode != true

    /** 测试缝：配置文件文本替身（null = 走真实扫描）。 */
    internal var configTextsForTest: ((Project) -> List<String>)? = null

    /** 测试缝：构建文件文本替身。 */
    internal var buildTextsForTest: ((Project) -> List<String>)? = null

    /**
     * 项目级补充证据：配置文件优先、驱动依赖次之；两者都无结论 → null（调用方保持 fail-closed）。
     * 只在后台线程调用（内部包 ReadAction；EDT 调用会退化为读动作，平台允许但勿在热路径用）。
     */
    fun forProject(project: Project?): Evidence? {
        if (project == null) return null
        // 测试缝注入时不看 enabled——语义用例要能在「真实扫描关闭」的环境里跑
        val seamsInjected = configTextsForTest != null || buildTextsForTest != null
        if (!enabled && !seamsInjected) return null
        val now = clockMs()
        slot?.let { cached ->
            if (cached.project.get() === project && now - cached.atMs <= ttlMs) return cached.evidence
        }
        val evidence = runCatching {
            configDialect(project)?.let { Evidence(it, DialectDecisionEngine.Source.PROJECT_CONFIG) }
                ?: dependencyDialect(project)?.let { Evidence(it, DialectDecisionEngine.Source.PROJECT_DEPENDENCY) }
        }.getOrNull()
        slot = Slot(java.lang.ref.WeakReference(project), now, evidence)
        return evidence
    }

    /** 只读缓存（EDT 调用路径用：绝不在 EDT 上触发文件扫描）。 */
    fun cachedOrNull(project: Project?): Evidence? {
        if (project == null) return null
        val cached = slot ?: return null
        if (cached.project.get() !== project) return null
        if (clockMs() - cached.atMs > ttlMs) return null
        return cached.evidence
    }

    /** 配置文件里的 JDBC 子协议共识（唯一才返回）。 */
    fun configDialect(project: Project): String? = runCatching {
        val texts = configTextsForTest?.invoke(project) ?: readConfigTexts(project)
        consensus(texts.flatMap { dialectsInUrls(it) })
    }.getOrNull()

    /** 构建文件里的数据库驱动坐标共识（唯一才返回）。 */
    fun dependencyDialect(project: Project): String? = runCatching {
        val texts = buildTextsForTest?.invoke(project) ?: readBuildTexts(project)
        consensus(texts.flatMap { dialectsInDependencies(it) })
    }.getOrNull()

    /** 纯函数：文本里出现的 JDBC 子协议 → 注册方言名（未收录子协议自然被忽略）。 */
    fun dialectsInUrls(text: String): List<String> =
        JDBC_URL.findAll(text).mapNotNull { UrlSubprotocolDialects.dialectName("jdbc:${it.groupValues[1]}:") }.toList()

    /** 纯函数：构建文件文本里的驱动坐标 → 注册方言名。 */
    fun dialectsInDependencies(text: String): List<String> {
        val lower = text.lowercase()
        return DRIVER_MARKERS.filter { (marker, _) -> lower.contains(marker) }.map { it.second }
    }

    /**
     * 纯函数：SQL 文本的方言独占特征。
     *
     * 只有**恰好一个**方言命中才返回（`LIMIT a, b` 与反引号同时出现仍算 MySQL 一个方言）；
     * 两个方言各有特征 → null（例如混写了方言方言的脚本）。
     */
    fun syntaxHint(sql: String?): String? {
        if (sql.isNullOrBlank()) return null
        val lower = sql.lowercase()
        val hits = SYNTAX_MARKERS.filter { (_, markers) -> markers.any { it.containsMatchIn(lower) } }
            .map { it.first }
            .distinct()
        return if (hits.size == 1) hits.first() else null
    }

    /** 唯一共识：去重后恰好一个才采用；否则不猜。 */
    private fun consensus(names: List<String>): String? =
        names.filter { it.isNotBlank() }.distinct().singleOrNull()

    private fun readConfigTexts(project: Project): List<String> = readProjectTexts(
        project = project,
        maxFiles = MAX_CONFIG_FILES,
        accept = { file -> isApplicationConfig(file.name) },
        byName = emptyList(),
        byExtensions = CONFIG_EXTENSIONS,
    )

    private fun readBuildTexts(project: Project): List<String> = readProjectTexts(
        project = project,
        maxFiles = MAX_BUILD_FILES,
        accept = { true },
        byName = BUILD_FILE_NAMES,
        byExtensions = emptyList(),
    )

    /** `application.yml` / `application-dev.yml` / `bootstrap.properties` 一族。 */
    private fun isApplicationConfig(name: String): Boolean {
        val lower = name.lowercase()
        val stem = lower.substringBeforeLast('.')
        return (stem == "application" || stem == "bootstrap" || stem.startsWith("application-") ||
            stem.startsWith("bootstrap-"))
    }

    /**
     * 有界读取：按名 + 按扩展名两次索引查询，读前限制文件大小，ReadAction 内完成。
     * 读失败的文件静默跳过（推断是可选能力，不得影响预览主链）。
     */
    private fun readProjectTexts(
        project: Project,
        maxFiles: Int,
        accept: (VirtualFile) -> Boolean,
        byName: List<String>,
        byExtensions: List<String>,
    ): List<String> = ReadAction.compute<List<String>, Throwable> {
        if (project.isDisposed) return@compute emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        val files = LinkedHashSet<VirtualFile>()
        byName.forEach { name ->
            runCatching { FilenameIndex.getVirtualFilesByName(project, name, scope) }
                .getOrDefault(emptyList()).forEach { files += it }
        }
        byExtensions.forEach { ext ->
            runCatching { FilenameIndex.getAllFilesByExt(project, ext, scope) }
                .getOrDefault(emptyList()).forEach { files += it }
        }
        files.asSequence()
            .filter { it.isValid && !it.isDirectory && accept(it) }
            .take(maxFiles)
            .mapNotNull { file ->
                runCatching {
                    if (file.length > MAX_TEXT_CHARS) return@runCatching null
                    VfsUtilCore.loadText(file)
                }.getOrNull()
            }
            .toList()
    }

    /** 测试缝：清缓存。 */
    internal fun invalidate() {
        slot = null
    }

    /** 测试缝：清缓存 + 恢复默认（用例隔离）。 */
    internal fun resetForTest() {
        slot = null
        configTextsForTest = null
        buildTextsForTest = null
        enabled = com.intellij.openapi.application.ApplicationManager.getApplication()?.isUnitTestMode != true
        ttlMs = 30_000L
        clockMs = { System.currentTimeMillis() }
    }
}
