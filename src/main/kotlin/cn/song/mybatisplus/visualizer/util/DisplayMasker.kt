package cn.song.mybatisplus.visualizer.util

/**
 * R60（T-LaneB D95/D96）：展示/导出链字面量脱敏——规则**逐条对齐**采集侧 agent
 * `sub-projects/sql-visualizer-agent/.../util/Desensitizer.java:10-30`（V112.1 落档）：
 *
 * ①列名/参数名提示：hint 小写化后 contains 命中 [KEYWORDS] 任一项 → 打码；
 * ②值形态兜底（无 hint 也命中）：18 位身份证（前 17 位数字+末位数字/X/x）或
 *   11 位手机号（全数字且首位 1）→ 打码；打码结果固定 [MASK]，空/null 值原样返回。
 *
 * agent 是独立源集（随 jar 注入目标应用 JVM），主仓 classpath 不可引用——两份同规则文本。
 * **同步范围自 20261024（T-MagpieGems W2）起收窄为 KEYWORDS 与形态规则本体**：展示侧支持
 * mode/allowList 分级与命中计数（[MaskMode]/[MaskScan]），采集侧 Desensitizer 仍为双条件恒
 * ***——agent 无 UI 无状态，分级/白名单/计数是展示侧有意为之的差异，不做双侧同步；关键词
 * 表与身份证/手机号形态判定改动仍须双侧同步（D97 口径对齐声明收敛后版本）。
 *
 * 本类纯 Kotlin 零平台依赖（BLOCK 占位与警示行走 [MpSqlVisualizerBundle]，自管理 ResourceBundle，
 * 无平台类）；开关由调用方读设置传入（[maskDisplay]），MCP 面（sqlv_render_logs）与展示面
 * （列表/预览浮层/导出）各自走各自的设置键（D96 双开关）。
 */
object DisplayMasker {

    /** 打码结果（与采集侧 Desensitizer.MASK 一致）。 */
    const val MASK = "***"

    /** 敏感关键词表（Desensitizer.java:12-16 逐项同步；改动双侧同步）。 */
    private val KEYWORDS = arrayOf(
        "password", "passwd", "pwd", "token", "secret", "credential", "apikey", "api_key",
        "access_key", "private_key", "idcard", "id_card", "mobile", "phone",
        "身份证", "手机号", "密码", "密钥", "令牌",
    )

    // ------------------------------------------------------------------
    // T-MagpieGems W2：动作分级（OFF/MASK/WARN/BLOCK）+ Allow 白名单 + 命中计数
    // ------------------------------------------------------------------

    /**
     * 展示脱敏动作分级：
     *  - [OFF]：原样输出（不扫描）；
     *  - [MASK]：敏感片段打码（既有行为）；
     *  - [WARN]：原文输出 + [MaskScan.counts] 供预览面追加警示行（屏显面专用，离开 IDE 面禁用）；
     *  - [BLOCK]：整段 SQL 替换为阻断占位文本（含命中总数）。
     */
    enum class MaskMode { OFF, MASK, WARN, BLOCK }

    /** 计数类别键：关键词命中的列归并为 [CAT_SENSITIVE_COLUMN]，形态命中分身份证/手机号。 */
    const val CAT_SENSITIVE_COLUMN = "sensitive_column"

    /** 计数类别键：18 位身份证形态命中。 */
    const val CAT_ID_CARD = "id_card"

    /** 计数类别键：11 位手机号形态命中。 */
    const val CAT_MOBILE = "mobile"

    /** 模式串解析（设置存的是串）：非法/空回落 [MaskMode.MASK]（对齐旧开关语义的安全兜底）。 */
    fun parseMaskMode(raw: String?): MaskMode =
        runCatching { MaskMode.valueOf((raw ?: "").trim().uppercase()) }.getOrDefault(MaskMode.MASK)

    /** 白名单串解析：换行或逗号分隔的关键词，trim + 小写化（匹配对 hint contains，大小写不敏感）。 */
    fun parseAllowList(raw: String?): List<String> =
        (raw ?: "").split('\n', ',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /**
     * 单值识别（W2 核心判定，[mask]/[scanSqlText] 共用）：返回类别键，null = 不敏感。
     *
     * **白名单优先于一切识别**：hint 命中 allowList 任一项 → 直接豁免，连身份证/手机号
     * 形态判定都跳过（用户显式声明该列不敏感）。关键词命中归并 [CAT_SENSITIVE_COLUMN]，
     * 形态命中分 [CAT_ID_CARD]/[CAT_MOBILE]——类别只进计数，绝不携带原值片段。
     */
    internal fun classify(columnHint: String?, value: String, allowList: List<String>): String? {
        if (allowList.isNotEmpty() && columnHint != null) {
            val hint = columnHint.lowercase()
            if (allowList.any { hint.contains(it) }) return null
        }
        if (columnHint != null) {
            val hint = columnHint.lowercase()
            if (KEYWORDS.any { hint.contains(it) }) return CAT_SENSITIVE_COLUMN
        }
        return when {
            looksLikeIdCard(value) -> CAT_ID_CARD
            looksLikeMobile(value) -> CAT_MOBILE
            else -> null
        }
    }

    private fun bump(counts: MutableMap<String, Int>, category: String) {
        counts[category] = (counts[category] ?: 0) + 1
    }

    /**
     * SQL 扫描产物：[maskedSql] 为按 mode 渲染的展示文本（OFF/WARN=原文、MASK=打码、BLOCK=阻断
     * 占位或原文），[counts] 为命中计数（仅三类类别键，**绝不包含任何原值片段**——Hint 不泄密
     * 红线：警示行/审计只允许出现类别与次数）。
     */
    data class MaskScan(val maskedSql: String, val counts: Map<String, Int>) {
        /** 命中总数（BLOCK 占位文本里的 N）。 */
        val total: Int get() = counts.values.sum()
    }

    /**
     * 双条件打码（Desensitizer.mask 等价移植）：列名提示 contains 命中或值形态命中 → [MASK]；
     * 其余原样。空/null 值原样（不打码）。无白名单（allowList 空）——既有调用方语义不变。
     */
    fun mask(columnHint: String?, value: String?): String? = mask(columnHint, value, emptyList())

    /** 带白名单重载：hint 命中 allowList 任一项 → 原样返回（连形态判定都跳过）。 */
    fun mask(columnHint: String?, value: String?, allowList: List<String>): String? {
        if (value == null || value.isEmpty()) {
            return value
        }
        return if (classify(columnHint, value, allowList) != null) MASK else value
    }

    /** 18 位身份证形态（前 17 位数字 + 末位数字/X/x）。 */
    fun looksLikeIdCard(v: String): Boolean {
        if (v.length != 18) return false
        for (i in 0..16) {
            if (!Character.isDigit(v[i])) return false
        }
        val last = v[17]
        return Character.isDigit(last) || last == 'X' || last == 'x'
    }

    /** 11 位手机号形态（全数字且首位 1）。 */
    fun looksLikeMobile(v: String): Boolean {
        if (v.length != 11) return false
        for (i in v.indices) {
            if (!Character.isDigit(v[i])) return false
        }
        return v[0] == '1'
    }

    // ------------------------------------------------------------------
    // SQL 文本级扫描（展示/导出/MCP 链共用）
    // ------------------------------------------------------------------

    /** SQL 关键字黑名单（列名 hint 推导时跳过——`WHERE x = 'v'` 的 hint 是 x 不是 WHERE）。 */
    private val SQL_KEYWORDS = setOf(
        "select", "from", "where", "and", "or", "not", "in", "is", "like", "set", "values",
        "insert", "update", "delete", "into", "join", "on", "as", "by", "order", "group",
        "having", "limit", "offset", "distinct", "case", "when", "then", "else", "end",
        "between", "exists", "union", "all", "asc", "desc", "null", "true", "false",
    )

    /** 字符串字面量 `'...'`（含 `''` 转义）或裸数字形态（11 位手机号/18 位身份证）的联合扫描。 */
    private val TOKEN_REGEX = Regex("'(?:[^']|'')*'|\\b\\d{11}\\b|\\b\\d{17}[0-9Xx]\\b")

    /**
     * SQL 文本脱敏（MASK 语义，既有行为不变）：字符串字面量按「前置最近标识符作列名 hint」
     * 双条件打码为 `'***'`；裸数字仅按值形态打码为 `***`（保留原引号形态，展示面可读）。
     * 非敏感片段逐字保留。等价 [scanSqlText]`(sql, MASK)` 的 maskedSql。
     *
     * 值内子串命中的形态**不**打码（对齐采集侧「参数值整体判形态」语义）——联合正则天然
     * 保证：引号字面量整体命中后其内部数字不再单独扫描。
     *
     * UI-021：`INSERT ... VALUES` 形态按**列清单与值序列位置对位**取列名 hint——VALUES 列表里
     * 字面量之间没有列名，旧的「前置最近标识符」会把**前一个字面量值**当列名（如 password 列值
     * Secret123X 的 hint 变成上一列的 ui_mask_probe → 漏打；下一列值的 hint 变成 Secret123X →
     * 因含 secret 误打）。列清单解析失败/值数超出列数时逐位回退旧规则。
     */
    fun maskSqlText(sql: String): String = safely { scanSqlText(sql, MaskMode.MASK).maskedSql }

    /**
     * SQL 文本按 [mode] 扫描（W2 主入口）：OFF 原样不扫描；MASK 打码；WARN 原文 + 计数；
     * BLOCK 命中总数 > 0 时整段替换为阻断占位文本（bundle `log.preview.maskBlocked`，N=计数和），
     * 白名单豁免后计数为 0 时按 OFF 处理（原样）。扫描逻辑复用 hint 推导与 INSERT 列对位。
     */
    fun scanSqlText(sql: String, mode: MaskMode, allowList: List<String> = emptyList()): MaskScan {
        if (mode == MaskMode.OFF) return MaskScan(sql, emptyMap())
        val counts = linkedMapOf<String, Int>()
        val insertCols = insertColumnsForValues(sql)
        var literalOrdinal = -1
        val out = transform(sql) { ctx ->
            when (ctx.kind) {
                TokenKind.LITERAL -> {
                    literalOrdinal++
                    val inner = ctx.text.substring(1, ctx.text.length - 1).replace("''", "'")
                    val hint = insertCols?.getOrNull(literalOrdinal) ?: ctx.hint
                    val category = classify(hint, inner, allowList)
                    if (category == null) {
                        ctx.text
                    } else {
                        bump(counts, category)
                        if (mode == MaskMode.MASK) "'$MASK'" else ctx.text
                    }
                }
                TokenKind.BARE_NUMBER -> {
                    // UI-001 修复：裸数字同样受白名单豁免（hint 命中 allowList → 原样）——对齐
                    // 「白名单优先于一切识别」语义；allowList 为空时与旧口径逐字一致（无条件按形态打码）。
                    if (allowList.isNotEmpty() && ctx.hint != null &&
                        allowList.any { ctx.hint.lowercase().contains(it) }
                    ) {
                        ctx.text
                    } else {
                        val category = if (looksLikeIdCard(ctx.text)) CAT_ID_CARD else CAT_MOBILE
                        bump(counts, category)
                        if (mode == MaskMode.MASK) MASK else ctx.text
                    }
                }
            }
        }
        // BLOCK：有命中才阻断占位；白名单豁免后剩 0 处 = 按 OFF（原样）。
        val masked = if (mode == MaskMode.BLOCK && counts.isNotEmpty()) blockedPlaceholder(counts.values.sum()) else out
        return MaskScan(masked, counts)
    }

    /** 链入口（旧布尔开关，向后兼容）：[enabled] 开 → [maskSqlText]（内部已 fail-closed），关 → 原文逐字。 */
    fun maskDisplay(sql: String, enabled: Boolean): String = if (enabled) maskSqlText(sql) else sql

    /**
     * 链入口（W2 主入口，按 mode 渲染）：OFF/WARN → 原文、MASK → 打码、BLOCK → 阻断占位
     * （或白名单豁免后按 OFF 原样）。屏显面直接用；离开 IDE 面的调用方须先归一
     * （mode != OFF 一律按 [MaskMode.MASK] 传入）再走本入口。
     *
     * **fail-closed 契约（T-MagpieGems W5，code-standards M1）**：脱敏引擎任何异常一律按
     * 阻断兜底（bundle `log.preview.maskFailed`），**绝不裸出原文**——脱敏面宁可误伤展示
     * 也不泄漏（对齐 magpie `ErrUnmasked` 语义）；OFF 档在入口短路（不扫描=引擎不参与，
     * 不进兜底）。
     */
    fun maskDisplay(sql: String, mode: MaskMode, allowList: List<String> = emptyList()): String =
        if (mode == MaskMode.OFF) sql else safely { scanSqlText(sql, mode, allowList).maskedSql }

    /**
     * fail-closed 兜底包装：脱敏抛错 → 记日志 + 阻断文案，不把异常透给渲染链
     * （透传等于把「引擎坏了」变成「原文全裸」，两者取其轻）。
     */
    private fun safely(block: () -> String): String =
        try {
            faultHook?.invoke()
            block()
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t
            // 保持本类零平台依赖：故障走 stderr（Logger 需要平台类，KDoc 口径同步）
            System.err.println("[DisplayMasker] fail-closed: 脱敏引擎异常，按阻断兜底：$t")
            maskedFailedText()
        }

    /** 引擎异常时的兜底文案（bundle `log.preview.maskFailed`）。 */
    private fun maskedFailedText(): String = MpSqlVisualizerBundle.message("log.preview.maskFailed")

    /**
     * W5 反向探针缝：测试注入脱敏引擎故障用（生产恒 null，scanSqlText 入口处触发一次）。
     * OFF 档在触发点之前返回，不受注入影响——这是契约的一部分（OFF=不扫描，引擎不参与）。
     */
    @Volatile
    internal var faultHook: (() -> Unit)? = null

    /** BLOCK 占位文本（bundle `log.preview.maskBlocked`，{0}=命中总数）。 */
    private fun blockedPlaceholder(total: Int): String = MpSqlVisualizerBundle.message("log.preview.maskBlocked", total)

    /**
     * WARN 警示行（预览浮层/停靠预览文本头部）：`⚠ 检测到疑似敏感值：身份证×a、手机号×b`。
     * [counts] 为 0 或空返回 null（不显示）；类别顺序固定（身份证→手机号→密码类列名），
     * 文案只含类别与次数——**绝不回显原值**（W2-c Hint 不泄密红线）。
     */
    fun warnLine(counts: Map<String, Int>): String? {
        if (counts.isEmpty()) return null
        val summary = listOf(CAT_ID_CARD, CAT_MOBILE, CAT_SENSITIVE_COLUMN).mapNotNull { cat ->
            counts[cat]?.takeIf { it > 0 }?.let { n ->
                MpSqlVisualizerBundle.message("mask.category.$cat") + "×$n"
            }
        }.joinToString(MpSqlVisualizerBundle.message("mask.category.join"))
        if (summary.isEmpty()) return null
        return MpSqlVisualizerBundle.message("log.preview.maskWarn", summary)
    }

    /**
     * R37 参数化产物：敏感字面量替换为 `?` 的 SQL + 脱敏参数表（值恒 [MASK]，原值不出本方法）。
     *
     * @param sql 参数化后的 SQL（非敏感字面量保留原文）
     * @param maskedParams 按出现序的脱敏参数值（恒 "***"）
     */
    data class ParameterizedSql(val sql: String, val maskedParams: List<String>)

    /**
     * R37 敏感字面量参数化（V51.3 规则）：敏感字面量（引号/裸数字）替换为 `?`，
     * 打码值进 [ParameterizedSql.maskedParams]；非敏感片段逐字保留。零敏感时 SQL 即原文、参数表空。
     */
    fun parameterize(sql: String): ParameterizedSql {
        val params = mutableListOf<String>()
        val insertCols = insertColumnsForValues(sql)
        var literalOrdinal = -1
        val out = transform(sql) { ctx ->
            when (ctx.kind) {
                TokenKind.LITERAL -> {
                    literalOrdinal++
                    val inner = ctx.text.substring(1, ctx.text.length - 1).replace("''", "'")
                    val hint = insertCols?.getOrNull(literalOrdinal) ?: ctx.hint
                    if (mask(hint, inner) == MASK) {
                        params += MASK
                        "?"
                    } else {
                        ctx.text
                    }
                }
                TokenKind.BARE_NUMBER -> {
                    params += MASK
                    "?"
                }
            }
        }
        return ParameterizedSql(out, params)
    }

    /** 扫描 token 形态。 */
    private enum class TokenKind { LITERAL, BARE_NUMBER }

    /** 命中 token + 推导的列名 hint（裸数字同样推导——UI-001 白名单豁免需要；旧消费方不读它的保持原行为）。 */
    private class TokenContext(val text: String, val kind: TokenKind, val hint: String?)

    /** SQL 字符串字面量的引号字符。 */
    private const val SQL_QUOTE = '\''

    /** 联合扫描 + 逐 token 变换（非命中区间逐字保留）。 */
    private inline fun transform(sql: String, mapper: (TokenContext) -> String): String {
        val sb = StringBuilder(sql.length)
        var last = 0
        for (m in TOKEN_REGEX.findAll(sql)) {
            val token = m.value
            val kind = if (token.first() == SQL_QUOTE) TokenKind.LITERAL else TokenKind.BARE_NUMBER
            sb.append(sql, last, m.range.first)
            sb.append(mapper(TokenContext(token, kind, columnHintBefore(sql, m.range.first))))
            last = m.range.last + 1
        }
        sb.append(sql, last, sql.length)
        return sb.toString()
    }

    /**
     * 字面量前置上下文的列名 hint：取字面量起点前 [HINT_WINDOW] 字符内的**最后一个**
     * 标识符（跳过 SQL 关键字）——`WHERE phone = '…'` 取 phone、`IN ('a','b')` 取 IN 前的
     * 列名、`VALUES ('x')` 取最近列名或无。取不到返回 null（只走形态兜底）。
     */
    private fun columnHintBefore(sql: String, literalStart: Int): String? {
        val from = maxOf(0, literalStart - HINT_WINDOW)
        val window = sql.substring(from, literalStart)
        val identifiers = ID_REGEX.findAll(window).map { it.value }.filter { it.lowercase() !in SQL_KEYWORDS }.toList()
        return identifiers.lastOrNull()
    }

    /**
     * UI-021：`INSERT ... VALUES` 的列清单（供按值位置取列名 hint）。
     *
     * VALUES 列表里字面量之间没有列名——旧 hint 推导会把前一个字面量值当列名；本函数解析
     * 「`INSERT INTO <表> (列1, 列2, ...) VALUES (`」段，返回列名序列，由调用方按 LITERAL 出现序
     * 对位。解析失败（非 INSERT/无列清单）返回 null，调用方逐位回退旧规则。
     * 列名支持裸标识符与反引号/双引号/方括号包裹；列清单内不含逗号嵌套。
     */
    private val INSERT_COLUMNS = Regex(
        "(?is)\\bINSERT\\s+INTO\\s+[\\w.`\"\\[\\]]+\\s*\\(([^()]*)\\)\\s*VALUES\\s*\\("
    )

    private fun insertColumnsForValues(sql: String): List<String>? {
        val m = INSERT_COLUMNS.find(sql) ?: return null
        val cols = m.groupValues[1].split(',').map { raw -> raw.trim().trim('`', '"', '[', ']') }
        return cols.takeIf { list -> list.isNotEmpty() && list.all { it.isNotEmpty() } }
    }

    /** 标识符（含中文列名/别名形态）。 */
    private val ID_REGEX = Regex("[A-Za-z_][A-Za-z0-9_$]*|[\\u4e00-\\u9fa5]+")

    /** 列名 hint 回看窗口（字符；覆盖 `WHERE very_long_column_name = ` 量级）。 */
    private const val HINT_WINDOW = 48
}
