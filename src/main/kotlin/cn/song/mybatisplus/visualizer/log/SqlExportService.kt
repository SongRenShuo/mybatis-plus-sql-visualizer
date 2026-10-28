package cn.song.mybatisplus.visualizer.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import cn.song.mybatisplus.visualizer.util.DisplayMasker

/**
 * R4（W3 历史工作流包）：SQL 导出服务——历史/日志面板行集导出为 CSV / TSV / 纯文本 / SQL 脚本文件。
 *
 * 纯逻辑（格式化 + 写盘），不依赖平台 UI；面板侧负责行集收集（与当前过滤一致）与
 * 文件选择，写盘在后台线程（大集不卡 EDT，第四轮 R4 口径）。
 *
 * CSV：RFC 4180 口径——含逗号/引号/换行的字段双引号包裹、内部双引号加倍；首行表头。
 * TSV：制表符分隔，字段内 tab/换行替换为空格（TSV 无转义机制，防串列）。
 * TXT：人读形态——分隔线 + 时间/来源/耗时元信息 + 完整 SQL 原文（多行保形）。
 * SQL：可执行脚本形态（T-ExportSqlFormat，O-131/R77/D131/V154）——文件头计数注释 + 逐条
 * TXT 同构注释头 + 分号终止的 SQL（复用 [withTerminator]，单一真源）+ 空行分隔；
 * 内容源=restoredSql（参数回填后可执行），参数缺失条目如实保留 `?`（LogSqlRestorer 口径）。
 */
enum class SqlExportFormat(val ext: String) {
    CSV("csv"), TSV("tsv"), TXT("txt"), SQL("sql");
}

/** 导出行（面板行集 → 导出的中性投影）。 */
data class SqlExportRow(
    val time: String,
    val sql: String,
    val source: String,
    val durationMs: Long? = null,
)

object SqlExportService {

    /** 表头（CSV/TSV 共用列序；TXT 用分隔线形态）。 */
    private val HEADER = listOf("time", "source", "duration_ms", "sql")

    /** CSV 字段转义：含逗号/引号/换行/回车即包裹，内部双引号加倍。 */
    internal fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    /**
     * 行集格式化（四格式分发）。
     *
     * R60（T-LaneB D96）：[mask] 开时对行集 sql 字段做**投影级**脱敏（[DisplayMasker.maskDisplay]，
     * 与采集侧 Desensitizer 双条件同规则）——只改本次导出文本，调用方行集与数据层条目不动；
     * 开关值由调用方读设置传入（本类纯逻辑不触平台设置）。
     * UI-001 修复：[allowList] 白名单豁免随调用方设置贯穿导出链（为空时行为与旧签名逐字等价）。
     */
    fun format(
        rows: List<SqlExportRow>,
        format: SqlExportFormat,
        mask: Boolean = false,
        allowList: List<String> = emptyList(),
    ): String {
        val effective =
            if (mask) rows.map { it.copy(sql = DisplayMasker.maskDisplay(it.sql, DisplayMasker.MaskMode.MASK, allowList)) } else rows
        return when (format) {
            SqlExportFormat.CSV -> formatCsv(effective)
            SqlExportFormat.TSV -> formatTsv(effective)
            SqlExportFormat.TXT -> formatTxt(effective)
            SqlExportFormat.SQL -> formatSql(effective)
        }
    }

    /** 写盘（调用方保证后台线程）。 */
    fun exportToFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    /**
     * SQL 格式默认保存名（T-ExportSqlFormat 用户拍板 20261009）：`sql-export-<yyyyMMdd-HHmmss>.sql`。
     * 时间戳到秒防同日多次导出静默覆盖；`sql-export-` 前缀不与自动保存名族（`sql-history-*`）重叠，
     * 即便被手动存进自动保存目录也不参与保留上限清理（SqlLogAutoSaver 名族过滤，非匹配 .sql 不计数）；
     * CSV/TSV/TXT 默认名仍沿用 `sql-history./sql-log.${ext}`（面板各自拼）。
     */
    fun defaultSqlExportFileName(nowMs: Long = System.currentTimeMillis()): String =
        "sql-export-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(nowMs)) + ".sql"

    private fun formatCsv(rows: List<SqlExportRow>): String {
        val sb = StringBuilder(HEADER.joinToString(","))
        sb.append('\n')
        rows.forEach { r ->
            sb.append(csvField(r.time)).append(',')
                .append(csvField(r.source)).append(',')
                .append(csvField(r.durationMs?.toString() ?: "")).append(',')
                .append(csvField(r.sql))
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun formatTsv(rows: List<SqlExportRow>): String {
        val sb = StringBuilder(HEADER.joinToString("\t"))
        sb.append('\n')
        rows.forEach { r ->
            val flatten = { v: String -> v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }
            sb.append(flatten(r.time)).append('\t')
                .append(flatten(r.source)).append('\t')
                .append(flatten(r.durationMs?.toString() ?: "")).append('\t')
                .append(flatten(r.sql))
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 逐条注释头（`-- === SQL #N ===` + time/source/duration 元信息行）——TXT/SQL 两格式同构共用（勿两份拷贝）。 */
    private fun appendSqlCommentHeader(sb: StringBuilder, index: Int, r: SqlExportRow) {
        sb.append("-- === SQL #").append(index + 1).append(" ===\n")
        sb.append("-- time: ").append(r.time)
        if (r.source.isNotBlank()) sb.append(" | source: ").append(r.source)
        if (r.durationMs != null) sb.append(" | ").append(r.durationMs).append(" ms")
        sb.append('\n')
    }

    private fun formatTxt(rows: List<SqlExportRow>): String {
        val sb = StringBuilder()
        rows.forEachIndexed { i, r ->
            appendSqlCommentHeader(sb, i, r)
            sb.append(r.sql.trimEnd())
            sb.append("\n\n")
        }
        return sb.toString()
    }

    /**
     * SQL 文件格式（T-ExportSqlFormat）：文件头计数注释 + 逐条 TXT 同构注释头 + 分号终止的
     * SQL + 空行分隔——目标形态=数据库客户端可直接执行（缺分号补 `;`，已有分号不重复，
     * 复用 [withTerminator] 单一真源）；参数缺失条目的 `?` 原样保留（LogSqlRestorer 口径，
     * 导出文件出现 `?` 属预期）。空行集入口已挡（两面板），服务层防一把：仅文件头。
     */
    private fun formatSql(rows: List<SqlExportRow>): String {
        val sb = StringBuilder()
        sb.append("-- Exported ").append(rows.size)
            .append(" SQL statements by MyBatis-Plus SQL Visualizer at ")
            .append(formatTime(System.currentTimeMillis()))
            .append('\n')
        rows.forEachIndexed { i, r ->
            appendSqlCommentHeader(sb, i, r)
            sb.append(withTerminator(r.sql.trimEnd()))
            sb.append("\n\n")
        }
        return sb.toString()
    }

    /**
     * 时间戳 → 导出用显示文本（毫秒精度，调用方口径统一）。
     * F43（T-PerfSweep16）：SDF ThreadLocal 缓存化——照 SqlSuppressManagerDialog F22 范式
     * （SimpleDateFormat 非线程安全；导出 EDT 行投影调用，ThreadLocal 同时覆盖后台线程触达面）。
     * 复用实例输出与逐次新建逐字节一致（format 不读旧状态），此前每行投影新建一个实例。
     */
    private val TIME_FORMAT = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    }

    fun formatTime(ts: Long): String = TIME_FORMAT.get().format(Date(ts))

    // ------------------------------------------------------------------
    // O-91 可移植导出（缩范围首版）：CRUD/回滚语句 + 方言形态标注，走既有三格式通道
    // ------------------------------------------------------------------

    /** 可移植脚本可执行格式（CSV/TSV 共用列序；TXT 走注释头形态）。 */
    private val PORTABLE_HEADER = listOf("dialect", "kind", "upsert", "sql")

    /** 方言形态注释头（TXT 可移植脚本逐字形态；CSV/TSV 同三字段作列）。 */
    internal fun portableAnnotation(row: PortableSqlRow): String =
        "-- dialect: ${row.dialect} | kind: ${row.kind.name} | upsert: ${row.upsert}"

    /**
     * O-91 可移植导出（[PortableSqlRow] 行集 → 三格式，行集由
     * [PortableSqlExport.collectRows] 串联：CRUD 行 + 回滚链产物行）。语句本体不改写；
     * TXT 每条语句前带注释头（`-- dialect: X | kind: Y | upsert: Z`），CSV/TSV 把同三字段
     * 作列（机器可读形态），SQL 字段沿用既有转义/扁平化口径。
     *
     * R60（T-LaneB D96）：[mask] 开时行集 sql 投影级脱敏（同 [format] 口径）；[allowList] 同 UI-001 贯穿。
     */
    fun formatPortable(
        rows: List<PortableSqlRow>,
        format: SqlExportFormat,
        mask: Boolean = false,
        allowList: List<String> = emptyList(),
    ): String {
        val effective =
            if (mask) rows.map { it.copy(sql = DisplayMasker.maskDisplay(it.sql, DisplayMasker.MaskMode.MASK, allowList)) } else rows
        return when (format) {
            SqlExportFormat.CSV -> formatPortableFlat(effective, ",") { csvField(it) }
            SqlExportFormat.TSV -> formatPortableFlat(effective, "\t") { v ->
                v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
            }
            SqlExportFormat.TXT -> formatPortableTxt(effective)
            // T-ExportSqlFormat 枚举扩员波及此 when：可移植导出是 O-91 独立通道（预览面板自己的
            // 三选对话框，不提供 SQL 选项），与历史/日志导出的 SQL 格式不合并互不替代（计划 §三
            // 边界写死防顺手统一）——UI 不可能传入 SQL，防御性显式失败而非静默伪装成 TXT。
            SqlExportFormat.SQL -> error("formatPortable: SQL format is not offered (O-91 portable export keeps CSV/TSV/TXT)")
        }
    }

    private fun formatPortableFlat(rows: List<PortableSqlRow>, sep: String, escape: (String) -> String): String {
        val sb = StringBuilder(PORTABLE_HEADER.joinToString(sep))
        sb.append('\n')
        rows.forEach { r ->
            sb.append(escape(r.dialect)).append(sep)
                .append(escape(r.kind.name)).append(sep)
                .append(escape(r.upsert)).append(sep)
                .append(escape(r.sql))
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun formatPortableTxt(rows: List<PortableSqlRow>): String {
        val sb = StringBuilder()
        rows.forEachIndexed { i, r ->
            sb.append("-- === SQL #").append(i + 1).append(" ===\n")
            sb.append(portableAnnotation(r)).append('\n')
            sb.append(r.sql.trimEnd())
            sb.append("\n\n")
        }
        return sb.toString()
    }
}
