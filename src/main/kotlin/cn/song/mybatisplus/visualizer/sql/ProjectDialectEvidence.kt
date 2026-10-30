package cn.song.mybatisplus.visualizer.sql

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
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
 *  1. [configDialect]：`application*` / `bootstrap*` 配置文件里的 `jdbc:<子协议>:`。
 *     - **模块优先**（T-DialectInferSmart）：传了 [Module] 就只扫该模块自己的文件（多模块各自连不同库时，
 *       模块 A 的预览不再被模块 B 的配置搅成「无共识」）；模块内无结论才由调用方回落到项目级。
 *     - **排除测试资源**：`src/test/resources` 下的 `application-test.yml` 不参与（否则「主库 PG + 测试 H2」
 *       会被判成无共识，整条推断退化）。
 *     - **激活 profile 优先**：读 `spring.profiles.active` / `spring.profiles.include`，激活 profile 的
 *       `application-<profile>.*` 覆盖基础 `application.*`；**其它环境的文件直接不投票**。
 *       拿不到激活 profile（没写/占位符无默认值）时退回「全部文件求共识」，与旧行为一致。
 *  2. [dependencyDialect]：`pom.xml` / `build.gradle(.kts)` 里的数据库驱动坐标。多个不同驱动 → 不猜。
 *  3. [syntaxHint]：SQL 文本里的**方言独占**特征（反引号、`LIMIT a, b`、`::type`、`ROWNUM`、`TOP n`…）。
 *     只认强特征，且要求「只有一个方言命中」，否则 null。
 *
 * 缓存：TTL（默认 30s）+ 项目/模块弱引用，模块各占一槽；[enabled] 是测试缝（单测默认关闭真实扫描）。
 * 扫描范围有上界（[MAX_CONFIG_FILES] / [MAX_BUILD_FILES] / [MAX_TEXT_CHARS]），不会扫整仓。
 */
internal object ProjectDialectEvidence {

    data class Evidence(val dialectName: String, val source: DialectDecisionEngine.Source)

    /** 一个配置/构建文件的「文件名 + 正文」——profile 判定需要文件名后缀。 */
    data class ConfigFile(val name: String, val text: String)

    /** 扫描的配置文件扩展名（按扩展名走 FilenameIndex，覆盖 Boot 的连字符命名）。 */
    private val CONFIG_EXTENSIONS = listOf("yml", "yaml", "properties")

    /** 参与驱动坐标扫描的构建文件（按名索引）。 */
    private val BUILD_FILE_NAMES = listOf("pom.xml", "build.gradle", "build.gradle.kts")

    private const val MAX_CONFIG_FILES = 24
    private const val MAX_BUILD_FILES = 12
    private const val MAX_TEXT_CHARS = 262_144

    /** JDBC URL 定位（只需子协议；连接串里的账号密码不进入任何返回值）。 */
    private val JDBC_URL = Regex("jdbc:([a-zA-Z0-9]+):")

    /** 扁平键形态：`spring.profiles.active=dev` / `spring.profiles.active: dev`。 */
    private val FLAT_PROFILE_KEY = Regex(
        "(?i)^\\s*spring\\.profiles\\.(active|include)\\s*[:=]\\s*(.+?)\\s*$",
    )

    private val SPRING_KEY = Regex("^\\s*spring\\s*:\\s*$")
    private val PROFILES_KEY = Regex("^\\s*profiles\\s*:\\s*$")
    private val PROFILE_VALUE_KEY = Regex("^\\s*(active|include)\\s*:\\s*(.*)$")

    /**
     * 驱动坐标标记 → 方言名（小写子串匹配，**顺序敏感**：`dialectsInDependencies` 按本表顺序
     * 收集命中项）。键取各驱动**发布坐标里独有**的片段，不用 `h2`/`dm` 这类短词（会在任意
     * 文本里误命中）。
     *
     * PG 协议版金仓驱动（`cn.com.kingbase:postgresql-for-kingbase`，驱动类 `org.postgresql.Driver`、
     * 子协议 `jdbc:postgresql:`）无法从 URL 区分，靠坐标标记纠正——故其标记**必须排在**
     * `org.postgresql:postgresql` 之前；用户用 `jdbc:postgresql:` 连金仓时仍按 PostgreSQL
     * 渲染属已知边界（URL 层无 distinguishing 特征）。
     */
    private val DRIVER_MARKERS: List<Pair<String, String>> = listOf(
        "mysql-connector-j" to "MySQL",
        "mysql-connector-java" to "MySQL",
        "com.mysql:mysql" to "MySQL",
        "mariadb-java-client" to "MariaDB",
        "org.mariadb.jdbc" to "MariaDB",
        // 金仓 PG 协议版必须先于 org.postgresql:postgresql（见上 KDoc；子串含 "kingbase" 不会误伤原版 pg 坐标）
        "postgresql-for-kingbase" to "KingBase（人大金仓）",
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
        // T-XcDialects3（券 05）：瀚高两个制品 com.highgo:HgdbJdbc（jdbc:highgo:）与
        // com.highgo:hgdb-pgjdbc（jdbc:postgresql:，原版 pgjdbc 直用）都归 HighGo——后者
        // 用户拿它连瀚高时坐标纠正是唯一线索；海量 cn.com.vastdata:vastbase-jdbc。
        "com.highgo" to "HighGo",
        "hgdb" to "HighGo",
        "vastbase" to "Vastbase",
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

    /** 项目级槽（module == null）。 */
    @Volatile
    private var projectSlot: Slot? = null

    /** 模块级槽：多模块各自连不同库时必须分开缓存，否则 A 模块的结论会喂给 B。 */
    private val moduleSlots = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Module, Slot>(),
    )

    /** 测试缝：TTL 上界（毫秒）。 */
    internal var ttlMs: Long = 30_000L

    /** 测试缝：时间源。 */
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    /**
     * 是否允许**真实扫描**。单元测试模式默认关闭：
     *  - 全量 8500+ 例里凡走 `DialectRegistry.effective(project)` 的都会各扫一遍工程文件，纯浪费；
     *  - 更危险的是**仓库自身**的 `build.gradle.kts` 含 `libs.postgresql`（测试依赖），
     *    任何以真实工程为 basePath 的测试项目都会被它判成 PostgreSQL，污染既有断言。
     * 语义由 [configFilesForTest] / [buildTextsForTest] 缝覆盖，接线由真机覆盖。
     */
    internal var enabled: Boolean = com.intellij.openapi.application.ApplicationManager.getApplication()
        ?.isUnitTestMode != true

    /** 测试缝：配置文件替身（含文件名，供 profile 判定；null = 走真实扫描）。 */
    internal var configFilesForTest: ((Project) -> List<ConfigFile>)? = null

    /** 测试缝：构建文件文本替身。 */
    internal var buildTextsForTest: ((Project) -> List<String>)? = null

    /**
     * 补充证据：配置文件优先、驱动依赖次之；两者都无结论 → null（调用方保持 fail-closed）。
     *
     * @param module 预览来源文件所属模块；非 null 时配置扫描**只收该模块自己的文件**，
     *               模块内无结论由调用方再问项目级（[Module] 为 null 的那次调用）。
     */
    fun forProject(project: Project?, module: Module? = null): Evidence? {
        if (project == null) return null
        // 测试缝注入时不看 enabled——语义用例要能在「真实扫描关闭」的环境里跑
        val seamsInjected = configFilesForTest != null || buildTextsForTest != null
        if (!enabled && !seamsInjected) return null
        val now = clockMs()
        cachedSlot(project, module, now)?.let { return it.evidence }
        val evidence = runCatching {
            configDialect(project, module)?.let { Evidence(it, DialectDecisionEngine.Source.PROJECT_CONFIG) }
                ?: dependencyDialect(project)?.let { Evidence(it, DialectDecisionEngine.Source.PROJECT_DEPENDENCY) }
        }.getOrNull()
        val slot = Slot(java.lang.ref.WeakReference(project), now, evidence)
        if (module == null) projectSlot = slot else moduleSlots[module] = slot
        return evidence
    }

    /** 只读缓存（EDT 调用路径用：绝不在 EDT 上触发文件扫描）。 */
    fun cachedOrNull(project: Project?, module: Module? = null): Evidence? {
        if (project == null) return null
        return cachedSlot(project, module, clockMs())?.evidence
    }

    private fun cachedSlot(project: Project, module: Module?, now: Long): Slot? {
        val cached = if (module == null) projectSlot else moduleSlots[module]
        if (cached == null) return null
        if (cached.project.get() !== project) return null
        if (now - cached.atMs > ttlMs) return null
        return cached
    }

    /**
     * 配置文件里的 JDBC 子协议共识（唯一才返回）。
     *
     * 激活 profile 已知时按 Spring 的覆盖语义分层：`application-<profile>` 优先，
     * **其它 profile 的文件不投票**；profile 段没有任何 URL 才看基础文件。
     */
    fun configDialect(project: Project, module: Module? = null): String? = runCatching {
        val files = configFilesForTest?.invoke(project) ?: readConfigFiles(project, module)
        val active = activeProfilesIn(files)
        if (active.isEmpty()) {
            // 拿不到激活 profile：全部文件求共识（旧行为）
            return consensus(files.flatMap { dialectsInUrls(it.text) })
        }
        val profileFiles = files.filter { profileSuffix(it.name)?.lowercase() in active }
        val baseFiles = files.filter { profileSuffix(it.name) == null }
        val profileUrls = profileFiles.flatMap { dialectsInUrls(it.text) }
        if (profileUrls.isNotEmpty()) {
            // profile 段有证据：只认它（冲突即 null，不拿 base 的旧值兜底）
            return consensus(profileUrls)
        }
        consensus(baseFiles.flatMap { dialectsInUrls(it.text) })
    }.getOrNull()

    /** 构建文件里的数据库驱动坐标共识（唯一才返回）。 */
    fun dependencyDialect(project: Project): String? = runCatching {
        val files = buildTextsForTest?.invoke(project)?.map { ConfigFile("", it) } ?: readBuildTexts(project)
        consensus(files.flatMap { dialectsInDependencies(it.text) })
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
     * 纯函数：从配置文本里取激活 profile 集合（`spring.profiles.active|include`）。
     *
     * 支持：扁平的 `spring.profiles.active: dev` / `.properties` 的 `=dev` / YAML 缩进块
     * （`spring: → profiles: → active:`）/ 逗号与 `[a, b]` 列表 / 后续 `- item` 列表 /
     * `${ENV:default}` 取默认值。`${ENV}`（无默认值）与 `@maven.filter@` 视为未知 → 跳过。
     * 拿不到任何值 → 空集（调用方按「未知」处理，全量求共识）。
     */
    fun activeProfilesIn(files: List<ConfigFile>): Set<String> =
        files.flatMap { activeProfilesIn(it.text) }.map { it.lowercase() }.toSet()

    fun activeProfilesIn(text: String): List<String> {
        val out = mutableListOf<String>()
        var springIndent = -1
        var profilesIndent = -1
        val lines = text.lineSequence().toList()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            i++
            val line = stripYamlComment(raw)
            if (line.isBlank()) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            // 缩进回退即离开 spring/profiles 块
            if (springIndent >= 0 && indent <= springIndent && !SPRING_KEY.matches(line)) {
                springIndent = -1
                profilesIndent = -1
            } else if (profilesIndent >= 0 && indent <= profilesIndent && !PROFILES_KEY.matches(line)) {
                profilesIndent = -1
            }
            FLAT_PROFILE_KEY.find(line)?.let { m ->
                out += parseProfileValues(m.groupValues[2])
                continue
            }
            if (SPRING_KEY.matches(line)) {
                springIndent = indent
                continue
            }
            if (springIndent >= 0 && indent > springIndent && PROFILES_KEY.matches(line)) {
                profilesIndent = indent
                continue
            }
            if (profilesIndent >= 0 && indent > profilesIndent) {
                PROFILE_VALUE_KEY.find(line)?.let { m ->
                    var value = m.groupValues[2].trim()
                    if (value.isEmpty()) {
                        // 列表形态：后续更深缩进的 "- item"
                        val items = mutableListOf<String>()
                        while (i < lines.size) {
                            val next = stripYamlComment(lines[i])
                            if (next.isBlank()) { i++; continue }
                            val nextIndent = next.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
                            if (nextIndent <= indent) break
                            i++
                            val item = next.trim().removePrefix("-").trim()
                            if (item.isNotEmpty()) items += item
                        }
                        value = items.joinToString(",")
                    }
                    out += parseProfileValues(value)
                }
            }
        }
        return out
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

    /** `application-dev.yml` → `dev`；`application.yml` / `bootstrap.yml` → null（基础文件）。 */
    internal fun profileSuffix(name: String): String? {
        val lower = name.lowercase()
        val stem = lower.substringBeforeLast('.', lower)
        if (stem == "application" || stem == "bootstrap") return null
        if (!stem.startsWith("application-") && !stem.startsWith("bootstrap-")) return null
        return stem.substringAfter('-').takeIf { it.isNotBlank() }
    }

    /** `dev, test` / `[dev, test]` / `${ENV:dev}` → 去引号后的 profile 列表。 */
    private fun parseProfileValues(raw: String): List<String> =
        raw.removeSurrounding("[", "]")
            .split(',')
            .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
            .map { normalizeProfileToken(it) }
            .filter { it.isNotEmpty() }

    /** `${ENV:dev}` → `dev`；`${ENV}` 与 `@x@`（Maven 过滤）→ 空（未知，不猜）。 */
    private fun normalizeProfileToken(token: String): String {
        if (token.isEmpty()) return ""
        if (token.startsWith("@") && token.endsWith("@")) return ""
        val placeholder = Regex("^\\$\\{([^:}]+)(?::([^}]*))?}$").find(token) ?: return token
        return placeholder.groupValues[2].trim()
    }

    /** 去掉行尾 `#` 注释（只在引号外生效，够 YAML/properties 用）。 */
    private fun stripYamlComment(line: String): String {
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < line.length) {
            when (line[i]) {
                '\'' -> if (!inDouble) inSingle = !inSingle
                '"' -> if (!inSingle) inDouble = !inDouble
                '#' -> if (!inSingle && !inDouble) return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun readConfigFiles(project: Project, module: Module?): List<ConfigFile> = readProjectTexts(
        project = project,
        module = module,
        maxFiles = MAX_CONFIG_FILES,
        accept = { file -> isApplicationConfig(file.name) },
        byName = emptyList(),
        byExtensions = CONFIG_EXTENSIONS,
    )

    private fun readBuildTexts(project: Project): List<ConfigFile> = readProjectTexts(
        project = project,
        module = null,
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
     *
     * 过滤两件事：**测试资源不参与**（`isInTestSourceContent`，否则测试库会把真实库挤成「无共识」）；
     * 传了 [module] 时**只收该模块的文件**（多模块各自连不同库时互不干扰）。
     * 读失败的文件静默跳过（推断是可选能力，不得影响预览主链）。
     */
    private fun readProjectTexts(
        project: Project,
        module: Module?,
        maxFiles: Int,
        accept: (VirtualFile) -> Boolean,
        byName: List<String>,
        byExtensions: List<String>,
    ): List<ConfigFile> = ReadAction.compute<List<ConfigFile>, Throwable> {
        if (project.isDisposed) return@compute emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        val fileIndex = ProjectFileIndex.getInstance(project)
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
            .filter { !fileIndex.isInTestSourceContent(it) }
            .filter { module == null || ModuleUtilCore.findModuleForFile(it, project) === module }
            .take(maxFiles)
            .mapNotNull { file ->
                runCatching {
                    if (file.length > MAX_TEXT_CHARS) return@runCatching null
                    ConfigFile(file.name, VfsUtilCore.loadText(file))
                }.getOrNull()
            }
            .toList()
    }

    /** 测试缝：清缓存。 */
    internal fun invalidate() {
        projectSlot = null
        moduleSlots.clear()
    }

    /** 测试缝：清缓存 + 恢复默认（用例隔离）。 */
    internal fun resetForTest() {
        projectSlot = null
        moduleSlots.clear()
        configFilesForTest = null
        buildTextsForTest = null
        enabled = com.intellij.openapi.application.ApplicationManager.getApplication()?.isUnitTestMode != true
        ttlMs = 30_000L
        clockMs = { System.currentTimeMillis() }
    }
}
