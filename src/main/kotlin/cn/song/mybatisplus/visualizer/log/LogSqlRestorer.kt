package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.SqlDateLiteral
import cn.song.mybatisplus.visualizer.sql.placeholderPositions

/**
 * 日志参数还原器（T-M31-02）：把 `?` 占位符按 [LogSqlParam] 顺序替换为可执行 SQL 值。
 *
 * 引号规则（对齐 MyBatis 参数绑定语义与既有 ParameterReplacer 的引号判定经验）：
 * - 数值/布尔类型标注（Long/Integer/Double/Boolean 等）→ 原样
 * - **日期族类型标注（R3，2026-09-29）→ ANSI 类型字面量** `DATE '…'` / `TIMESTAMP '…'`：
 *   裸引号串在 Oracle 对日期/时间戳列隐式转换恒败（ORA-01861/01843，竞品同款差评形态；
 *   dbx 六引擎缺口矩阵实测见 `docs/调研报告/20260929-1500-R3-日期类型缺口矩阵-验证报告.md`），
 *   而 ANSI 类型字面量在全部实测引擎（Oracle 11g/23、达梦 DM8、金仓、MySQL 5.7/8.4、
 *   PostgreSQL 16、openGauss）通用——无需方言分支。值文本不匹配日期形态时回落引号，
 *   包装永不产生非法字面量；`LocalTime`/带时区值/无类型标注不包装（边界见 renderDateFamily KDoc）
 * - String/其他类型标注 → 引号包裹 + 内部 `'` 转义为 `''`（标准 SQL 字符串转义）
 * - null 字面量 → `NULL`
 * - 无类型标注（旧版/第三方日志）→ 启发式：数值（含科学计数）与 true/false 原样，其余引号
 *
 * 占位符数量与参数数量不匹配：多出的参数忽略，缺少的参数保留 `?`（面板警示提示）。
 */
class LogSqlRestorer {

    /** 按序替换 SQL 中 `?` 占位符；无参数时原样返回。 */
    fun restore(sql: String, params: List<LogSqlParam>): String {
        if (params.isEmpty() || sql.isEmpty()) return sql
        val positions = placeholderPositions(sql)
        if (positions.isEmpty()) return sql
        val sb = StringBuilder(sql.length + params.size * 8)
        var prev = 0
        var paramIdx = 0
        for (pos in positions) {
            if (paramIdx >= params.size) break // 参数多于占位符：忽略剩余
            sb.append(sql, prev, pos).append(renderParam(params[paramIdx]))
            prev = pos + 1
            paramIdx++
        }
        sb.append(sql, prev, sql.length)
        return sb.toString()
    }

    /** 单个参数渲染为 SQL 字面量（测试直接断言用）。 */
    fun renderParam(param: LogSqlParam): String {
        val value = param.value ?: return NULL_SQL // null 字面量（value/type 均 null）
        val type = param.type ?: return renderUntyped(value)
        renderDateFamily(value, type)?.let { return it }
        // F-15（DBX #319）：空间二进制反解分支——0x 十六进制形态 + EWKB 结构解析成功才出
        // `geometry::STGeomFromText('WKT', SRID)`（SQL Server 方言）；解析失败/非空间二进制
        // （`[B@…`/`<byte[]>` 等）一律回落下方原渲染路径（引号包裹），双门槛判据见 SpatialLiteral。
        SpatialLiteral.render(value)?.let { return it }
        return if (type in PLAIN_TYPES) value else quote(value)
    }

    /**
     * 日期族类型标注 → ANSI 类型字面量（R3 缺口矩阵修复）。
     *
     * T-DateLiteralBackfill：规则已上提至 [SqlDateLiteral.renderFor]（全链唯一实现），本函数只做
     * 委托；R3 的 family 限制与「形态不匹配回落引号」语义保持不变（D4 的 TIMESTAMP 族纯日期
     * 边界由共享渲染器统一产出 `DATE`）。
     */
    private fun renderDateFamily(value: String, type: String): String? =
        SqlDateLiteral.renderFor(value, type)

    private fun renderUntyped(value: String): String =
        if (value.matches(NUMERIC_REGEX) || value == TRUE || value == FALSE) value else quote(value)

    private fun quote(value: String): String = "'" + value.replace("'", "''") + "'"

    companion object {
        private const val NULL_SQL = "NULL"
        private const val TRUE = "true"
        private const val FALSE = "false"

        /**
         * 原样直出（数值/布尔）；其余类型标注一律引号（String/时间/枚举/byte[] 等）。
         *
         * 包含两类类型名：
         * - Java 类型（MyBatis 日志）：Long, Integer, String 等
         * - JDBC 类型（Hibernate 日志）：BIGINT, INTEGER, VARCHAR 等
         */
        internal val PLAIN_TYPES = setOf(
            // Java 类型（MyBatis 日志）
            "Long", "Integer", "Short", "Byte", "BigDecimal", "BigInteger", "Double", "Float", "Number",
            "long", "int", "short", "byte", "double", "float",
            "Boolean", "boolean",
            // JDBC 类型（Hibernate 日志）—— 数值/布尔型原样直出
            "BIGINT", "INTEGER", "SMALLINT", "TINYINT", "FLOAT", "DOUBLE", "NUMERIC", "DECIMAL",
            "BIT", "BOOLEAN",
            "REAL",
            // Java 全限定类型（Hibernate 偶尔输出）
            "java.lang.Long", "java.lang.Integer", "java.lang.Short", "java.lang.Byte",
            "java.lang.Double", "java.lang.Float", "java.lang.Boolean",
            "java.math.BigDecimal", "java.math.BigInteger",
        )

        /** 数值字面量（含小数与科学计数，如 `1.0E10`）。 */
        private val NUMERIC_REGEX = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")
    }
}

/**
 * 日志参数 → 回填提示（T-M42）：数值/布尔原样直出（[LogSqlRestorer.PLAIN_TYPES]），
 * 其余类型标注引号（对齐 [LogSqlRestorer] 还原语义）；无类型标注走面板启发式（null）。
 * 日期族额外带 `temporal = true`（T-DateLiteralBackfill）：面板参数行据此产出 ANSI 类型字面量，
 * 与还原链同口径。
 * 供 [LogSqlEntry.toRecognizedPreview] 识别参数时生成参数行回填提示。
 */
internal fun LogSqlParam.toHint(): ParamHint = when {
    type == null -> ParamHint()
    type in LogSqlRestorer.PLAIN_TYPES -> ParamHint(quote = false)
    else -> ParamHint(quote = true, temporal = SqlDateLiteral.isTemporalType(type))
}

// T-PSG-01：string-aware `?` 占位符定位实现已上提至
// cn.song.mybatisplus.visualizer.sql.placeholderPositions（日志还原 / 复制可执行 SQL / 面板回填
// 三条拼接链跨包共用同一份位点口径；`internal` 跨包不可达，故搬迁至 sql 包而非放宽可见性）。
// 本包内的调用点经顶部 import 解析到新落点，行为逐字节不变；[countPlaceholders] 仍是本包薄封装。
