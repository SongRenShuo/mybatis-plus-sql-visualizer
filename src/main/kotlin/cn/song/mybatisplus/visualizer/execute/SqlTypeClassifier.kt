package cn.song.mybatisplus.visualizer.execute

/**
 * SQL 语句类型分类器（M36）：区分查询类语句（免确认）与非查询类语句（需二次确认）。
 *
 * 分类规则（按首条有效语句首关键字判定）：
 *  - 查询类（免确认）：SELECT / SHOW / EXPLAIN / DESCRIBE / WITH（CTE）
 *  - DML（需确认）：INSERT / UPDATE / DELETE / MERGE
 *  - DDL（需确认）：CREATE / ALTER / DROP / TRUNCATE
 *  - 多条语句混合 → 按最高风险级别处理（需确认）
 *  - 注释（-- 单行 / /* */ 块注释）自动跳过
 */
object SqlTypeClassifier {

    /** SQL 语句类型枚举（风险升序）。 */
    enum class SqlType { QUERY, DML, DDL, UNKNOWN }

    /** 查询类关键字（免确认）。 */
    private val QUERY_KEYWORDS = setOf("SELECT", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "WITH")

    /** DML 关键字（需确认）。 */
    private val DML_KEYWORDS = setOf("INSERT", "UPDATE", "DELETE", "MERGE")

    /** DDL 关键字（需确认）。 */
    private val DDL_KEYWORDS = setOf("CREATE", "ALTER", "DROP", "TRUNCATE")

    /** 二级证明词法扫描：标识符形态词（字边界匹配，列名 deleted_at 不会被拆成 DELETE）。 */
    private val WORD_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** 二级证明词法扫描：INTO 字（\b 双侧边界，POINT 内含 INTO 不误配）。 */
    private val INTO_WORD_REGEX = Regex("\\bINTO\\b")

    /**
     * 判断 SQL 是否为查询类语句（免确认直接执行）。
     *
     * @param sql SQL 文本（可能含多条语句、注释）
     * @return true=查询类（SELECT/SHOW/EXPLAIN/DESCRIBE/WITH）；false=非查询或无法判定
     */
    fun isQuery(sql: String): Boolean {
        val statements = splitStatements(sql)
        if (statements.isEmpty()) return false
        // 多条语句：全部为查询类才免确认；任一条非查询 → 需确认
        return statements.all { classify(it) == SqlType.QUERY }
    }

    /**
     * 提取 SQL 首条有效语句的首关键字（大写）。
     *
     * @param sql SQL 文本
     * @return 首关键字大写（如 "SELECT"、"INSERT"），无有效语句时返回 null
     */
    fun getFirstKeyword(sql: String): String? {
        val first = splitStatements(sql).firstOrNull() ?: return null
        return extractKeyword(first)
    }

    /**
     * 对单条 SQL 语句分类。
     *
     * T-TxnReadOnlyProof（N-21）：首关键字命中免确认名单后追加只读二级证明——三个真实
     * 豁免盲区（可写 CTE / EXPLAIN ANALYZE 真执行写 / SELECT INTO 建表与写文件）任一
     * 命中即改判 UNKNOWN（需确认，fail-closed）；证明不了的形态一律维持确认弹窗。
     *
     * @param statement 单条 SQL 语句（已去除注释）
     * @return 分类结果
     */
    fun classify(statement: String): SqlType {
        val keyword = extractKeyword(statement) ?: return SqlType.UNKNOWN
        if (QUERY_KEYWORDS.contains(keyword) && !proveReadOnly(statement, keyword)) {
            return SqlType.UNKNOWN
        }
        return when {
            QUERY_KEYWORDS.contains(keyword) -> SqlType.QUERY
            DML_KEYWORDS.contains(keyword) -> SqlType.DML
            DDL_KEYWORDS.contains(keyword) -> SqlType.DDL
            else -> SqlType.UNKNOWN
        }
    }

    /**
     * QUERY 名单语句的只读二级证明（T-TxnReadOnlyProof，词法宽门控：宁错弹不放过）。
     *
     * 三个盲区（对齐 DBX「只读豁免必须逐语句证明且 fail-closed」）：
     *  1. 可写 CTE：`WITH del AS (DELETE …) SELECT * FROM del`——WITH 后正文出现
     *     INSERT/UPDATE/DELETE/MERGE 字（含尾随主语句形态），不豁免；
     *  2. EXPLAIN ANALYZE：PG 语义 ANALYZE 会**真实执行**被修饰语句——正文同时出现
     *     ANALYZE|ANALYSE 字与 DML 字，不豁免（`EXPLAIN SELECT` 无 ANALYZE 不受影响）；
     *  3. SELECT INTO：`INTO OUTFILE|DUMPFILE`（MySQL 写服务端文件）或 `INTO <identifier>`
     *     建表（PG SELECT … INTO new_table），不豁免；`INTO @var` 会话变量维持豁免。
     *
     * 词法门控对字符串字面量与引号标识符先屏蔽再按字边界匹配（列名 deleted_at 不误判）；
     * JSqlParser AST 精确化为可选增强，v1 词法先行（与计划 §三 T-TR-01 一致）。
     *
     * @return true=证明只读（维持 QUERY 豁免）；false=存在写证据或证明不了（转需确认）
     */
    private fun proveReadOnly(statement: String, keyword: String): Boolean {
        val masked = maskQuoted(statement).uppercase()
        val words = WORD_REGEX.findAll(masked).map { it.value }.toList()
        return when (keyword) {
            // WITH：CTE 体内或尾随主语句出现写关键字即不豁免（drop(1) 跳过 WITH 自身）
            "WITH" -> words.drop(1).none { it in DML_KEYWORDS }
            // EXPLAIN：ANALYZE|ANALYSE 与 DML 字并存即不豁免（EXPLAIN (ANALYZE, BUFFERS) DELETE 变体同拦）
            "EXPLAIN" -> !(words.any { it == "ANALYZE" || it == "ANALYSE" } && words.any { it in DML_KEYWORDS })
            // SELECT：任一 INTO 后随 OUTFILE/DUMPFILE/标识符即不豁免（@var 会话变量除外）
            "SELECT" -> !intoWrites(masked)
            else -> true
        }
    }

    /**
     * SELECT 语句的 INTO 写语义判定：任一 `INTO` 字后随 OUTFILE/DUMPFILE（MySQL 写文件）
     * 或普通标识符（PG 建表）即有写证据；`INTO @var`/`INTO @@global.x` 会话变量形态无写；
     * INTO 位于语句尾部（畸形）按证明不了处理（fail-closed）。
     */
    private fun intoWrites(maskedUpper: String): Boolean {
        var match = INTO_WORD_REGEX.find(maskedUpper)
        while (match != null) {
            val rest = maskedUpper.substring(match.range.last + 1)
            val afterInto = rest.trimStart()
            when {
                // INTO 后无内容：畸形形态，证明不了 → 有写证据（fail-closed）
                afterInto.isEmpty() -> return true
                // 会话变量（@v / @@global.v）：维持豁免，继续看后续 INTO
                afterInto[0] == '@' -> {}
                else -> return true
            }
            match = INTO_WORD_REGEX.find(rest)
        }
        return false
    }

    /**
     * 屏蔽字符串字面量（'…'，含 '' 转义）与 `…`/"…" 引号标识符——字面量内的关键字
     * （如 'delete from t' 常量值）不参与二级证明词法扫描；替换为空格保持偏移稳定。
     */
    private fun maskQuoted(sql: String): String {
        val sb = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when (c) {
                '\'' -> {
                    sb.append(' ')
                    i++
                    while (i < sql.length) {
                        if (sql[i] == '\\') {
                            // T-HiddenBugPerfScan 券 O：反斜杠转义对成对消费（MySQL 口径——\' 使字符串继续、\\ 为字面反斜杠）。曾把 \' 当字符串结束，掩码错位可令 INTO OUTFILE 等写证据从掩码文本里消失、只读证明 fail-open（确认闸旁路，方向危险）；成对消费使 \\'（双反斜杠+引号）仍正确闭合。
                            sb.append("  ")
                            i += 2
                        } else if (sql[i] == '\'') {
                            if (i + 1 < sql.length && sql[i + 1] == '\'') {
                                // '' 转义：字面量内引号
                                sb.append("  ")
                                i += 2
                            } else {
                                sb.append(' ')
                                i++
                                break
                            }
                        } else {
                            sb.append(' ')
                            i++
                        }
                    }
                }
                '`', '"' -> {
                    val quote = c
                    sb.append(' ')
                    i++
                    while (i < sql.length && sql[i] != quote) {
                        sb.append(' ')
                        i++
                    }
                    if (i < sql.length) {
                        sb.append(' ')
                        i++
                    }
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /**
     * 按分号分割 SQL 为多条语句（忽略空语句），并逐条去注释。
     *
     * T-SemicolonGuard（O-13）：实现收敛委托至 util/SqlStatementSplitter（与
     * LogToSqlPanel/SqlToJavaConverter 单实现合一）；顺带修复两个历史缺陷——字符串内
     * 分号误切（`'a;b'`）、字符串内 `--` 被误剥注释（差异清单见开发计划 T-SG-01）。
     *
     * @param sql SQL 文本
     * @return 去注释后的非空语句列表（段已 trim）
     */
    internal fun splitStatements(sql: String): List<String> =
        cn.song.mybatisplus.visualizer.util.SqlStatementSplitter.splitStripped(sql)

    /**
     * 去除 SQL 语句中的注释（-- 单行注释 + /* */ 块注释）。
     *
     * @param sql 原始 SQL 片段
     * @return 去注释后的文本
     */
    internal fun stripComments(sql: String): String {
        val sb = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                // -- 单行注释：跳过至行尾
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    // 跳至行尾或文本结束
                    while (i < sql.length && sql[i] != '\n') i++
                }
                // /* */ 块注释
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    i += 2
                    while (i < sql.length && !(sql[i] == '*' && i + 1 < sql.length && sql[i + 1] == '/')) i++
                    i += 2 // 跳过 */
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /**
     * 提取语句的首关键字（大写）。跳过前导空白与左括号。
     *
     * @param statement 单条 SQL 语句（已去注释）
     * @return 首关键字大写，无有效关键字时返回 null
     */
    private fun extractKeyword(statement: String): String? {
        val trimmed = statement.trimStart()
        if (trimmed.isEmpty()) return null
        // 提取连续字母作为关键字
        val letters = trimmed.takeWhile { it.isLetter() }
        return letters.ifEmpty { null }?.uppercase()
    }
}
