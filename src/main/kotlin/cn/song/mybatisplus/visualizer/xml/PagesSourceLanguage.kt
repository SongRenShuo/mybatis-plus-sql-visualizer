package cn.song.mybatisplus.visualizer.xml

/**
 * maintainPage `<datasource>` 的源语言（T-PagesHql）。
 *
 * 自研框架的 `<datasource>` 既可能是原生 SQL，也可能是 HQL——两者都长得像 SQL，
 * 必须靠「根标识符与 entityClass 的关系」等证据判定，不能按「有 entityClass 就是 HQL」
 * 一刀切（demo 的 `orderMaintain` 就是 entityClass=Order + 原生 SQL `FROM t_order`）。
 */
enum class PagesSourceLanguage { SQL, HQL }

/**
 * 判定证据（稳定 id：i18n、日志与测试共用，**已发布后不要改名**）。
 */
enum class PagesSourceEvidence(val id: String) {
    /** `<datasource type="hql|jpql">` 显式声明。 */
    EXPLICIT_TYPE("explicit-type"),

    /** `<datasource type="sql">` 显式声明原生 SQL。 */
    EXPLICIT_SQL("explicit-sql"),

    /** 根标识符 == entityClass 的实体名（简单名或 `@Entity(name)`）。 */
    ROOT_IS_ENTITY_CLASS("root-is-entity-class"),

    /** 根标识符 == entityClass 对应的表名（反向证据：这是原生 SQL）。 */
    ROOT_IS_TABLE("root-is-table"),

    /** 根标识符能解析为持久化实体，且其表名 ≠ 根标识符（或命中 HQL 专属记号）。 */
    ROOT_RESOLVES_ENTITY("root-resolves-entity"),

    /** 无足够证据，保持原生 SQL（零行为变化）。 */
    DEFAULT_SQL("default-sql"),
}

/** 一次判定的结果。 */
data class PagesSourceDetection(
    val language: PagesSourceLanguage,
    val evidence: PagesSourceEvidence,
    /** HQL 根标识符（也用作 HqlConverter 的 entityAlias 入参）；提不到为 null。 */
    val rootIdentifier: String?,
)

/**
 * 实体探针结果：判定只需要「实体名」与「表名」两个事实。
 * null 探针 = 不是可解析的持久化实体。
 */
data class PagesEntityProbe(val entityName: String?, val tableName: String?)

/**
 * `<datasource>` 语言判定（纯函数，无 PSI 依赖）。
 *
 * 判定顺序（首命中即定，默认 SQL）：
 * 1. `type="hql|jpql"` → HQL；`type="sql"` → SQL；
 * 2. 根标识符 == entityClass 实体名 → HQL；
 * 3. 根标识符 == entityClass 表名 → SQL；
 * 4. 根标识符解析为持久化实体且（表名 ≠ 根名 或 命中 HQL 专属记号）→ HQL；
 * 5. 其余 → SQL。
 *
 * [rootIdentifierOf] 与 [probeEntity] 由调用方注入（生产分别接
 * `HqlEntityNameExtractor::extract` 与 PSI 实体解析），使本文件不依赖 `psi` 包，
 * 也让纯 JUnit 能覆盖用户样本形态。
 */
object PagesSourceLanguageDetector {

    /** HQL 专属记号：只作加权，不单独定罪（SQL 里也可能出现 `?1` 之外的巧合）。 */
    private val HQL_ONLY_TOKENS = listOf(
        Regex("(?i)\\bJOIN\\s+FETCH\\b"),
        Regex("(?i)\\bMEMBER\\s+OF\\b"),
        Regex("(?i)\\bnew\\s+[A-Za-z_][A-Za-z0-9_.$]*\\s*\\("),
        Regex("\\?\\d+"),
    )

    fun detect(
        sql: String,
        entityClassName: String?,
        declaredType: String?,
        rootIdentifierOf: (String) -> String?,
        probeEntity: (String) -> PagesEntityProbe? = { null },
    ): PagesSourceDetection {
        val root = runCatching { rootIdentifierOf(sql) }.getOrNull()?.takeIf { it.isNotBlank() }

        // 1. 显式类型（框架若有 type 属性，最权威）
        val type = declaredType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (type == "hql" || type == "jpql") {
            return PagesSourceDetection(PagesSourceLanguage.HQL, PagesSourceEvidence.EXPLICIT_TYPE, root)
        }
        if (type == "sql") {
            return PagesSourceDetection(PagesSourceLanguage.SQL, PagesSourceEvidence.EXPLICIT_SQL, root)
        }

        // 2/3. entityClass 相关性
        val entityFqcn = entityClassName?.trim()?.takeIf { it.isNotEmpty() }
        if (entityFqcn != null) {
            val simpleName = entityFqcn.substringAfterLast('.')
            val probe = runCatching { probeEntity(entityFqcn) }.getOrNull()
            val entityName = probe?.entityName?.takeIf { it.isNotBlank() } ?: simpleName
            if (root != null && (root == simpleName || root == entityName)) {
                return PagesSourceDetection(
                    PagesSourceLanguage.HQL,
                    PagesSourceEvidence.ROOT_IS_ENTITY_CLASS,
                    root,
                )
            }
            val tableName = probe?.tableName?.takeIf { it.isNotBlank() }
            if (root != null && tableName != null && root.equals(tableName, ignoreCase = true)) {
                return PagesSourceDetection(PagesSourceLanguage.SQL, PagesSourceEvidence.ROOT_IS_TABLE, root)
            }
        }

        // 4. 根标识符能解析为持久化实体：表名与根名不同（根名是实体名）或命中 HQL 记号
        if (root != null) {
            val probe = runCatching { probeEntity(root) }.getOrNull()
            if (probe != null) {
                val tableName = probe.tableName?.takeIf { it.isNotBlank() }
                val tableDiffers = tableName == null || !root.equals(tableName, ignoreCase = true)
                if (tableDiffers || hasHqlOnlyToken(sql)) {
                    return PagesSourceDetection(
                        PagesSourceLanguage.HQL,
                        PagesSourceEvidence.ROOT_RESOLVES_ENTITY,
                        root,
                    )
                }
            }
        }

        // 5. 保守默认
        return PagesSourceDetection(PagesSourceLanguage.SQL, PagesSourceEvidence.DEFAULT_SQL, root)
    }

    /** 语句里是否出现 HQL 专属记号。 */
    fun hasHqlOnlyToken(sql: String): Boolean = HQL_ONLY_TOKENS.any { it.containsMatchIn(sql) }
}

/**
 * `#{}` 框架占位符改写结果。
 *
 * @param text 改写后文本（`#{x}` → `:x`；非法标识符 → `:__p_N`）
 * @param displayNames 参数名 → 面板展示名（合法标识符时为同一字符串；`a.b.c` 这类保留原名）
 */
data class PagesPlaceholderRewrite(
    val text: String,
    val displayNames: Map<String, String>,
)

/**
 * 框架占位符 `#{...}` 预处理（T-PagesHql）。
 *
 * `<datasource>` 的 HQL 里会出现 `#{SESSION_BASE_ORGAN}` 这类**框架层**占位符——框架在把
 * HQL 交给 Hibernate 之前做值替换，Hibernate 本身不认识 `#{}`。预览必须在转换前把它改写成
 * HQL 命名参数 `:name`，才能复用 HqlConverter 的命名参数链（`:name` → `?` + 名称序）。
 *
 * 规则：
 * - 单引号字面量内不替换（`'#{x}'` 是业务文本）；
 * - `#{}` 空表达式原样保留（不产生参数）；
 * - 合法标识符直接沿用原名；含点/空格等非法字符时用 `__p_N` 占位，展示名保留原文。
 */
object PagesFrameworkPlaceholder {

    private val VALID_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun rewrite(sql: String): PagesPlaceholderRewrite {
        if (!sql.contains("#{")) return PagesPlaceholderRewrite(sql, emptyMap())
        val out = StringBuilder(sql.length)
        val displayNames = LinkedHashMap<String, String>()
        var i = 0
        var inQuote = false
        while (i < sql.length) {
            val c = sql[i]
            if (inQuote) {
                out.append(c)
                if (c == '\'') {
                    // SQL 单引号转义：'' 视为字面量内的一个引号
                    if (i + 1 < sql.length && sql[i + 1] == '\'') {
                        out.append('\'')
                        i += 2
                        continue
                    }
                    inQuote = false
                }
                i++
                continue
            }
            if (c == '\'') {
                inQuote = true
                out.append(c)
                i++
                continue
            }
            if (c == '#' && i + 1 < sql.length && sql[i + 1] == '{') {
                val close = sql.indexOf('}', i + 2)
                if (close > i + 2) {
                    val raw = sql.substring(i + 2, close).trim()
                    if (raw.isNotEmpty()) {
                        val paramName = if (VALID_NAME.matches(raw)) raw else nextSyntheticName(displayNames)
                        displayNames[paramName] = raw
                        out.append(':').append(paramName)
                        i = close + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return PagesPlaceholderRewrite(out.toString(), displayNames)
    }

    private fun nextSyntheticName(used: Map<String, String>): String {
        var seq = used.size + 1
        while (used.containsKey("__p_$seq")) seq++
        return "__p_$seq"
    }
}
