package cn.song.mybatisplus.visualizer.sql

/**
 * 日期族 SQL 字面量渲染（T-DateLiteralBackfill，R3 规则上提）。
 *
 * 背景：`LocalDateTime.toString()` 产出 ISO `T` 形态（`2026-11-07T13:20:14.417`），裸单引号串在
 * Oracle 对 TIMESTAMP 列隐式转换恒败（ORA-01843 / ORA-01861）；把 `T` 换成空格也不够——Oracle 的
 * 隐式转换按 `NLS_TIMESTAMP_FORMAT` 解析，`'2024-01-01 10:00:00.0'` 同样 ORA-01861。ANSI 类型
 * 字面量（`DATE '…'` / `TIMESTAMP '…'`）由 SQL 文法直接解析，不依赖 NLS，R3 缺口矩阵在 Oracle
 * 11g/23、达梦 DM8、金仓、MySQL 5.7/8.4、PostgreSQL 16、openGauss 上全部实测通过。
 *
 * 本对象是**全链唯一实现**：`LogSqlRestorer`（控制台还原）、`AgentEventAdapter`（增强捕获还原）、
 * `ParameterReplacer`（复制可执行 SQL / 导出）、面板参数行（`renderSingleValue`）与模型链
 * （`SqlGenerator` / 批量 / JPA / XML）都经它产出日期字面量，避免多套口径再次分叉。
 *
 * 边界（与 R3 一致，刻意不包装）：
 * - `LocalTime` / `Time`：对 TIME 列各库皆可、对 datetime 列表现分裂，且 Oracle 不支持 ANSI `TIME`；
 * - `OffsetDateTime` / `ZonedDateTime` / `Instant`：ANSI TIMESTAMP 不收时区后缀；
 * - 无类型标注的日期形串：无法区分字符串列（`WHERE code = '2025-01-01'`），误包装会改变语义。
 *
 * 方言门禁（T-DateLiteralBackfill D1）：SQL Server 的 T-SQL 不支持 ANSI 类型字面量
 * （`DATE '2024-01-01'` 语法错误；官方只给 ODBC 转义 `{d '…'}` / `{ts '…'}` 与隐式转换），
 * SQLite 同样没有该文法；这两个方言回落既有引号渲染。其余方言（含方言未知）走 ANSI——
 * R3 实测的 8 个引擎全部支持，未知默认 ANSI 才能覆盖「方言证据缺失的 Oracle 条目」。
 */
object SqlDateLiteral {

    /** 可包装的日期族类型简单名（全限定名 / JDBC 类型名在调用处归一为简单名）。 */
    private val TEMPORAL_TYPES = setOf(
        "LocalDate", "Date", "DATE",
        "Timestamp", "LocalDateTime", "TIMESTAMP", "DATETIME",
    )

    /** 不支持 ANSI 类型字面量的方言名（[Dialect.name] 口径）。 */
    private val ANSI_UNSUPPORTED_DIALECTS = setOf("SQL Server", "SQLite")

    /** 类型简单名是否日期族（用于构造 `ParamHint.temporal`）。 */
    fun isTemporalType(type: String?): Boolean {
        if (type == null) return false
        return type.substringAfterLast('.') in TEMPORAL_TYPES
    }

    /** 方言是否支持 ANSI 类型字面量；null（未知）按支持处理。 */
    fun ansiTypedLiteralSupported(dialectName: String?): Boolean =
        dialectName == null || dialectName !in ANSI_UNSUPPORTED_DIALECTS

    /**
     * 按**值形态**产出 ANSI 字面量：纯日期 → `DATE '…'`，带时分秒 → `TIMESTAMP '…'`。
     *
     * 供只知道「这是日期族值」、不知道具体类型的链路（`ParamHint.temporal` 通道）使用。
     * 形态不匹配、值为空、方言不支持时返回 null——调用方回落既有引号渲染，**绝不产生非法字面量**。
     */
    fun renderByShape(value: String, dialectName: String? = null): String? {
        if (!ansiTypedLiteralSupported(dialectName)) return null
        val v = value.trim()
        if (v.isEmpty()) return null
        return when {
            DATE_RE.matches(v) -> "DATE '$v'"
            TS_RE.matches(v) -> "TIMESTAMP '${normalizeTs(v)}'"
            else -> null
        }
    }

    /**
     * 类型 + 值 → ANSI 字面量（R3 的 family 限制）。
     *
     * 与 [renderByShape] 的差别只在类型约束：
     * - `LocalDate` / `DATE` 只接受纯日期形态（带时分秒回落引号，与 R3 一致）；
     * - `Date` 两态都接受；
     * - `Timestamp` / `LocalDateTime` / `TIMESTAMP` / `DATETIME` 接受时间戳形态；纯日期形态产出
     *   `DATE '…'`（T-DateLiteralBackfill D4：Oracle 隐式 DATE→TIMESTAMP 等价当日 0 点，比回落
     *   引号后必然失败更合理）。
     */
    fun renderFor(value: String, type: String?, dialectName: String? = null): String? {
        if (!ansiTypedLiteralSupported(dialectName)) return null
        val v = value.trim()
        if (v.isEmpty()) return null
        return when (type?.substringAfterLast('.')) {
            "LocalDate", "DATE" ->
                if (DATE_RE.matches(v)) "DATE '$v'" else null
            "Date" ->
                when {
                    DATE_RE.matches(v) -> "DATE '$v'"
                    TS_RE.matches(v) -> "TIMESTAMP '${normalizeTs(v)}'"
                    else -> null
                }
            "Timestamp", "LocalDateTime", "TIMESTAMP", "DATETIME" ->
                when {
                    TS_RE.matches(v) -> "TIMESTAMP '${normalizeTs(v)}'"
                    DATE_RE.matches(v) -> "DATE '$v'"
                    else -> null
                }
            else -> null
        }
    }

    /** ISO `T` 分隔符归一为空格；缺秒补 `:00`（ANSI 字面量不接受省略秒，Oracle 实测 ORA-01861）。 */
    private fun normalizeTs(v: String): String {
        var out = if (v.length > 10 && v[10] == 'T') v.substring(0, 10) + ' ' + v.substring(11) else v
        if (TS_NO_SEC_RE.matches(out)) out += ":00"
        return out
    }

    /** 日期字面量形态（`yyyy-MM-dd`，Java 日期 toString 标准补零形态）。 */
    private val DATE_RE = Regex("""\d{4}-\d{2}-\d{2}""")

    /** 时间戳字面量形态（`yyyy-MM-dd[ T]HH:mm[:ss][.f…]`，秒与小数可选——缺秒由 [normalizeTs] 归一）。 */
    private val TS_RE = Regex("""\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(:\d{2})?(\.\d{1,9})?""")

    /** 缺秒时间戳（`yyyy-MM-dd HH:mm`）——[normalizeTs] 补 `:00` 用。 */
    private val TS_NO_SEC_RE = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""")
}
