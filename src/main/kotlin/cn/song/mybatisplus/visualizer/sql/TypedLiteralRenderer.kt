package cn.song.mybatisplus.visualizer.sql

/**
 * 类型感知字面量渲染器（F-01，闲时任务册 §一之二）：按「类型标注 × 方言」把参数值渲染为
 * 可执行 SQL 字面量。优先场景＝ORA-01722（数字列传字符串）：`Parameters: 1.0E10(BigDecimal)`
 * 这类值落进启发式引号兜底后在 Oracle 数字列上必炸——类型信息在场时应按类型直出。
 *
 * ## 输入契约
 *  - `typeText`：三种来源，统一归一为简单名再判定——
 *      1. Java 类型名（MyBatis 日志 `Parameters: 1(Long)`）：`Long` / `java.math.BigDecimal` / `byte[]`；
 *      2. JDBC 类型名（Hibernate 日志 / `#{p,jdbcType=TIMESTAMP}`）：`VARCHAR` / `TIMESTAMP` / `BLOB`；
 *      3. [cn.song.mybatisplus.visualizer.xml.ParamKind] 枚举名（mapper 签名类别链）：`TEXT` / `NUMBER` / `TEMPORAL`。
 *  - `dialectName`：[DialectRegistry] 注册名（`MySQL` / `Oracle` / `PostgreSQL`…，与
 *    [PreviewDialectResolver.knownDialect] 同一口径）；null = 方言未知，走通用档。
 *
 * ## 输出契约
 *  - 非 null：类型信息在场且值形态匹配 → 该类型 × 方言的可执行字面量；
 *  - **null：判定不了（无类型 / 类型不认识 / 值形态与类型不符）→ 调用方走既有启发式回退**。
 *    宁缺勿错：类型说 NUMBER 而值是 `abc`，宁可回退旧路径也不臆造裸 `abc`。
 *
 * ## 六类型的方言形态（判据出处见各分支 KDoc）
 *  | 类型 | 通用档（方言未知） | MySQL 族 | Oracle 族 | PostgreSQL 族 |
 *  |---|---|---|---|---|
 *  | 字符串 | `'…'`（`''` 转义） | 另加反斜杠加倍 | `'…'` | `'…'` |
 *  | 数字 | 原样（数值形态校验） | 同左 | 同左 | 同左 |
 *  | 日期/时间戳 | ANSI 类型字面量 | 带引号字符串 | ANSI 类型字面量 | ANSI 类型字面量 |
 *  | 布尔 | `TRUE`/`FALSE` | 同左 | `1`/`0` | `TRUE`/`FALSE` |
 *  | 二进制 | null（回退） | `X'…'` | `HEXTORAW('…')` | `decode('…','hex')` |
 *  | NULL | `NULL`（值恰为 `null` 字样且非字符串类） | 同左 | 同左 | 同左 |
 *
 * 纯函数、零平台依赖——可进 testFast 快桶直接单测。
 */
object TypedLiteralRenderer {

    /** 字面量类型（六分类；NULL 不是独立类别——它由「值恰为 null 字样」触发）。 */
    enum class LiteralType { TEXT, NUMBER, TEMPORAL, BOOLEAN, BINARY }

    // ------------------------------------------------------------------
    // 类型标注归一
    // ------------------------------------------------------------------

    /** 数值类（Java 简单名 + JDBC 名；与 [ParamQuote] 数值名单同语义、与 LogSqlRestorer.PLAIN_TYPES 数值段同集）。 */
    private val NUMBER_NAMES = setOf(
        "Long", "Integer", "Short", "Byte", "Float", "Double", "BigDecimal", "BigInteger", "Number",
        "int", "long", "short", "byte", "float", "double",
        "BIGINT", "INTEGER", "SMALLINT", "TINYINT", "NUMERIC", "DECIMAL", "FLOAT", "DOUBLE", "REAL", "NUMBER",
    )

    /** 字符序列类（`UUID` 在 SQL 里以字符串形态比较——沿 XmlParamTypeResolver 的 TEXT 归档）。 */
    private val TEXT_NAMES = setOf(
        "String", "CharSequence", "UUID", "char",
        "VARCHAR", "CHAR", "CLOB", "TEXT", "LONGVARCHAR", "NVARCHAR", "NCHAR", "NCLOB", "VARCHAR2",
    )

    /** 时间类（Java 时间族 + JDBC 名 + ParamKind 枚举名 TEMPORAL）。 */
    private val TEMPORAL_NAMES = setOf(
        "LocalDate", "LocalDateTime", "LocalTime", "OffsetDateTime", "OffsetTime", "ZonedDateTime",
        "Instant", "Year", "YearMonth", "MonthDay", "Date", "Calendar", "Timestamp", "Time",
        "DATE", "TIME", "TIMESTAMP", "DATETIME", "TEMPORAL",
    )

    /** 布尔类。 */
    private val BOOLEAN_NAMES = setOf("Boolean", "boolean", "BOOLEAN", "BIT")

    /** 二进制类（`byte[]` 传进来时简单名带 `[]`）。 */
    private val BINARY_NAMES = setOf("byte[]", "Byte[]", "BINARY", "VARBINARY", "BLOB", "LONGVARBINARY")

    /**
     * 类型标注 → 字面量类别；null = 不认识（调用方回退既有启发式）。
     *
     * 归一口径：剥全限定名（取最后一个 `.` 之后）、剥泛型（`List<Long>` → `List`）、去空白；
     * JDBC 名全大写、Java 名大小写敏感——两套名单不相交，统一按「先原样、再大写」双查。
     */
    fun classify(typeText: String?): LiteralType? {
        if (typeText.isNullOrBlank()) return null
        val trimmed = typeText.trim()
        // 数组形态不剥泛型（`List<Long>[]` 的「数组」事实与元素类别无关；byte[] 无泛型）
        val simple = if (trimmed.endsWith("[]")) {
            trimmed.substringAfterLast('.')
        } else {
            trimmed.substringBefore('<').trim().substringAfterLast('.')
        }
        for (names in arrayOf(NUMBER_NAMES, TEXT_NAMES, TEMPORAL_NAMES, BOOLEAN_NAMES, BINARY_NAMES)) {
            if (simple in names) return when (names) {
                NUMBER_NAMES -> LiteralType.NUMBER
                TEXT_NAMES -> LiteralType.TEXT
                TEMPORAL_NAMES -> LiteralType.TEMPORAL
                BOOLEAN_NAMES -> LiteralType.BOOLEAN
                else -> LiteralType.BINARY
            }
        }
        // 全大写归一再兜一层（JDBC 名偶见混合大小写：`Timestamp`/`TIMESTAMP` 已直查命中，
        // 这里补 `Varchar` 一类写法）
        val upper = simple.uppercase()
        return when {
            upper in NUMBER_NAMES -> LiteralType.NUMBER
            upper in TEXT_NAMES -> LiteralType.TEXT
            upper in TEMPORAL_NAMES -> LiteralType.TEMPORAL
            upper in BOOLEAN_NAMES -> LiteralType.BOOLEAN
            upper in BINARY_NAMES -> LiteralType.BINARY
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    /**
     * 参数值 → 可执行字面量。
     *
     * @param value       参数原文（面板输入 / 日志参数值）；空白视为「未填写」→ null（保留调用方占位语义）
     * @param typeText    类型标注（见类 KDoc 三来源）；null = 无类型信息 → null
     * @param dialectName 方言注册名（`Dialect.name`）；null = 方言未知 → 通用档
     * @return 字面量；判定不了返回 null（调用方走既有启发式，不得臆造）
     */
    fun render(value: String?, typeText: String?, dialectName: String?): String? {
        if (value.isNullOrBlank()) return null
        val type = classify(typeText) ?: return null
        val v = value.trim()
        // SQL NULL：类型在场且值恰为 `null` 字样（日志链 `Parameters: null` 无类型标注，走不到这里；
        // `${}` 内联链 / 用户显式输入 `null` 且带类型 → 出 NULL 字面量。字符串类例外：TEXT 列的
        // 字面值 `null` 是合法字符串，引号直出——见 TEXT 分支）
        if (!v.equals("null", ignoreCase = true) || type == LiteralType.TEXT) {
            return when (type) {
                LiteralType.TEXT -> renderText(v, dialectName)
                LiteralType.NUMBER -> if (NUMERIC_RE.matches(v)) v else null
                LiteralType.BOOLEAN -> renderBoolean(v, dialectName)
                LiteralType.TEMPORAL -> renderTemporal(v, dialectName)
                LiteralType.BINARY -> renderBinary(v, dialectName)
            }
        }
        return "NULL"
    }

    // ------------------------------------------------------------------
    // 分类型渲染
    // ------------------------------------------------------------------

    /**
     * 字符串：单引号包裹 + `''` 转义；MySQL 族另加**反斜杠加倍**（MySQL 默认 sql_mode 下
     * 反斜杠是转义符，`'a\b'` 里的 `\b` 会被吃成退格——`'a\\b'` 才是字面 `a\b`；
     * Oracle/PG 标准语义反斜杠是普通字符，不动）。已知近似（登记）：会话开
     * `NO_BACKSLASH_ESCAPES` 时 MySQL 反斜杠本就字面，加倍会多出一层——静态判不了
     * sql_mode，按默认模式取「正确优先」。
     */
    private fun renderText(v: String, dialectName: String?): String {
        val escaped = if (dialectName in MYSQL_FAMILY) {
            v.replace("\\", "\\\\").replace("'", "''")
        } else {
            v.replace("'", "''")
        }
        return "'$escaped'"
    }

    /**
     * 布尔：Oracle 族 → `1`/`0`（23c 之前 SQL 无 BOOLEAN 类型，数字列存标志位是主流形态；
     * 真 BOOLEAN 列 Oracle 23c 也接受 1/0 隐式转换）；其余 → `TRUE`/`FALSE` 关键字
     * （MySQL 的 BOOLEAN 即 TINYINT(1)，TRUE/FALSE 是合法字面量；PG 原生）。
     */
    private fun renderBoolean(v: String, dialectName: String?): String? = when (v.lowercase()) {
        "true" -> if (dialectName in ORACLE_FAMILY) "1" else "TRUE"
        "false" -> if (dialectName in ORACLE_FAMILY) "0" else "FALSE"
        else -> null
    }

    /**
     * 日期/时间戳：
     *  - **ANSI 类型字面量**（`DATE '…'` / `TIMESTAMP '…'`）为通用档——R3 缺口矩阵六引擎实测
     *    （Oracle 11g/23、达梦 DM8、金仓、MySQL 5.7/8.4、PostgreSQL 16、openGauss）全通，
     *    见 `docs/调研报告/20260929-1500-R3-日期类型缺口矩阵-验证报告.md`；
     *  - MySQL 族 / SQLite / SQL Server → 带引号字符串（MySQL datetime 字符串比较是原生形态；
     *    T-SQL 与 SQLite 均不收 ANSI 类型字面量，裸字符串走隐式转换即可）；
     *  - 纯时刻（无日期部分）恒带引号——Oracle 不支持 ANSI `TIME` 字面量（R3 登记边界），跨库
     *    比较语义分裂，宁缺勿错维持引号。
     */
    private fun renderTemporal(v: String, dialectName: String?): String? {
        val plain = dialectName in MYSQL_FAMILY || dialectName == "SQLite" || dialectName == "SQL Server"
        return when {
            DATE_RE.matches(v) -> if (plain) "'$v'" else "DATE '$v'"
            TS_RE.matches(v) -> {
                val normalized = normalizeTs(v)
                if (plain) "'$normalized'" else "TIMESTAMP '$normalized'"
            }
            TIME_RE.matches(v) -> "'$v'"
            else -> null // 值形态与类型不符（如 `2025` 简写、非 ISO 形态）→ 回退既有启发式
        }
    }

    /**
     * `LocalDateTime.toString` 的 `T` 分隔符归一为空格（ANSI 字面量不收 `T`）；缺秒补 `:00`
     * （Oracle 实测缺秒 ORA-01861，与 LogSqlRestorer.normalizeTs 同款归一）。
     */
    private fun normalizeTs(v: String): String {
        var out = if (v.length > 10 && v[10] == 'T') v.substring(0, 10) + ' ' + v.substring(11) else v
        if (TS_NO_SEC_RE.matches(out)) out += ":00"
        return out
    }

    /**
     * 二进制：值必须是偶长十六进制（字节序列才能反解；`[B@1a2b3c` 一类对象 toString 形态
     * 无法反解 → null 回退）。MySQL 族 `X'…'`、PG 族 `decode('…','hex')`、Oracle 族
     * `HEXTORAW('…')`；方言未知 / 其余方言 null（回退引号档，不臆造函数形态）。
     */
    private fun renderBinary(v: String, dialectName: String?): String? {
        if (v.length % 2 != 0 || !HEX_RE.matches(v)) return null
        return when {
            dialectName in MYSQL_FAMILY -> "X'$v'"
            dialectName in PG_FAMILY -> "decode('$v','hex')"
            dialectName in ORACLE_FAMILY -> "HEXTORAW('$v')"
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // 方言族（按 DialectRegistry 注册名；新方言入册时按兼容族补录）
    // ------------------------------------------------------------------

    /** Oracle 兼容族（达梦 DM8 与 Oracle 语法同族：TO_DATE/HEXTORAW/数字布尔皆同）。 */
    internal val ORACLE_FAMILY = setOf("Oracle", "DM（达梦）")

    /** PostgreSQL 兼容族（金仓/openGauss/GaussDB/瀚高/Vastbase 均为 PG 内核衍生）。 */
    internal val PG_FAMILY = setOf("PostgreSQL", "KingBase（人大金仓）", "openGauss", "GaussDB", "HighGo", "Vastbase")

    /** MySQL 兼容族（MariaDB 同族；OceanBase 默认 MySQL 租户模式）。 */
    internal val MYSQL_FAMILY = setOf("MySQL", "MariaDB", "OceanBase")

    /** 数值字面量（含小数与科学计数 `1.0E10`/`1E-7`——BigDecimal.toString 的科学计数形态正是 ORA-01722 场景）。 */
    private val NUMERIC_RE = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")

    /** 日期字面量形态（`yyyy-MM-dd`）。 */
    private val DATE_RE = Regex("""\d{4}-\d{2}-\d{2}""")

    /** 时间戳形态（`yyyy-MM-dd[ T]HH:mm[:ss][.f…]`，秒与小数可选）。 */
    private val TS_RE = Regex("""\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(:\d{2})?(\.\d{1,9})?""")

    /** 纯时刻形态（`HH:mm[:ss]`）。 */
    private val TIME_RE = Regex("""\d{2}:\d{2}(:\d{2})?(\.\d{1,9})?""")

    /** 缺秒时间戳（补 `:00` 用）。 */
    private val TS_NO_SEC_RE = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""")

    /** 十六进制串（二进制反解前提）。 */
    private val HEX_RE = Regex("""[0-9a-fA-F]+""")
}
