package cn.song.mybatisplus.visualizer.sql;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 日期族 SQL 字面量渲染（T-DateLiteralBackfill，R3 规则上提）。
 *
 * <p>背景：{@code LocalDateTime.toString()} 产出 ISO {@code T} 形态（{@code 2026-11-07T13:20:14.417}），裸单引号串在
 * Oracle 对 TIMESTAMP 列隐式转换恒败（ORA-01843 / ORA-01861）；把 {@code T} 换成空格也不够——Oracle 的
 * 隐式转换按 {@code NLS_TIMESTAMP_FORMAT} 解析，{@code '2024-01-01 10:00:00.0'} 同样 ORA-01861。ANSI 类型
 * 字面量（{@code DATE '…'} / {@code TIMESTAMP '…'}）由 SQL 文法直接解析，不依赖 NLS，R3 缺口矩阵在 Oracle
 * 11g/23、达梦 DM8、金仓、MySQL 5.7/8.4、PostgreSQL 16、openGauss 上全部实测通过。</p>
 *
 * <p>本对象是<b>全链唯一实现</b>：{@code LogSqlRestorer}（控制台还原）、{@code AgentEventAdapter}（增强捕获还原）、
 * {@code ParameterReplacer}（复制可执行 SQL / 导出）、面板参数行（{@code renderSingleValue}）与模型链
 * （{@code SqlGenerator} / 批量 / JPA / XML）都经它产出日期字面量，避免多套口径再次分叉。</p>
 *
 * <p>边界（与 R3 一致，刻意不包装）：</p>
 * <ul>
 *  <li>{@code LocalTime} / {@code Time}：对 TIME 列各库皆可、对 datetime 列表现分裂，且 Oracle 不支持 ANSI {@code TIME}；</li>
 *  <li>{@code OffsetDateTime} / {@code ZonedDateTime} / {@code Instant}：ANSI TIMESTAMP 不收时区后缀；</li>
 *  <li>无类型标注的日期形串：无法区分字符串列（{@code WHERE code = '2025-01-01'}），误包装会改变语义。</li>
 * </ul>
 *
 * <p>方言门禁（T-DateLiteralBackfill D1）：SQL Server 的 T-SQL 不支持 ANSI 类型字面量
 * （{@code DATE '2024-01-01'} 语法错误；官方只给 ODBC 转义 {@code {d '…'}} / {@code {ts '…'}} 与隐式转换），
 * SQLite 同样没有该文法；这两个方言回落既有引号渲染。其余方言（含方言未知）走 ANSI——
 * R3 实测的 8 个引擎全部支持，未知默认 ANSI 才能覆盖「方言证据缺失的 Oracle 条目」。</p>
 */
public final class SqlDateLiteral {

    private SqlDateLiteral() {}

    /** 可包装的日期族类型简单名（全限定名 / JDBC 类型名在调用处归一为简单名）。 */
    private static final Set<String> TEMPORAL_TYPES = Set.of(
        "LocalDate", "Date", "DATE",
        "Timestamp", "LocalDateTime", "TIMESTAMP", "DATETIME"
    );

    /** 不支持 ANSI 类型字面量的方言名（{@link Dialect#getName()} 口径）。 */
    private static final Set<String> ANSI_UNSUPPORTED_DIALECTS = Set.of("SQL Server", "SQLite");

    /** 日期字面量形态（{@code yyyy-MM-dd}，Java 日期 toString 标准补零形态）。 */
    private static final Pattern DATE_RE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** 时间戳字面量形态（{@code yyyy-MM-dd[ T]HH:mm[:ss][.f…]}，秒与小数可选——缺秒由 {@link #normalizeTs} 归一）。 */
    private static final Pattern TS_RE =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(:\\d{2})?(\\.\\d{1,9})?");

    /** 缺秒时间戳（{@code yyyy-MM-dd HH:mm}）——{@link #normalizeTs} 补 {@code :00} 用。 */
    private static final Pattern TS_NO_SEC_RE = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");

    /** 类型简单名是否日期族（用于构造 {@code ParamHint.temporal}）。 */
    public static boolean isTemporalType(String type) {
        if (type == null) return false;
        int dot = type.lastIndexOf('.');
        String simple = dot >= 0 ? type.substring(dot + 1) : type;
        return TEMPORAL_TYPES.contains(simple);
    }

    /** 方言是否支持 ANSI 类型字面量；null（未知）按支持处理。 */
    public static boolean ansiTypedLiteralSupported(String dialectName) {
        return dialectName == null || !ANSI_UNSUPPORTED_DIALECTS.contains(dialectName);
    }

    /**
     * 按<b>值形态</b>产出 ANSI 字面量：纯日期 → {@code DATE '…'}，带时分秒 → {@code TIMESTAMP '…'}。
     *
     * <p>供只知道「这是日期族值」、不知道具体类型的链路（{@code ParamHint.temporal} 通道）使用。
     * 形态不匹配、值为空、方言不支持时返回 null——调用方回落既有引号渲染，<b>绝不产生非法字面量</b>。</p>
     */
    public static String renderByShape(String value, String dialectName) {
        if (!ansiTypedLiteralSupported(dialectName)) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        if (DATE_RE.matcher(v).matches()) {
            return "DATE '" + v + "'";
        }
        if (TS_RE.matcher(v).matches()) {
            return "TIMESTAMP '" + normalizeTs(v) + "'";
        }
        return null;
    }

    /** Kotlin 默认参数（dialectName=null）等价重载。 */
    public static String renderByShape(String value) {
        return renderByShape(value, null);
    }

    /**
     * 类型 + 值 → ANSI 字面量（R3 的 family 限制）。
     *
     * <p>与 {@link #renderByShape} 的差别只在类型约束：</p>
     * <ul>
     *  <li>{@code LocalDate} / {@code DATE} 只接受纯日期形态（带时分秒回落引号，与 R3 一致）；</li>
     *  <li>{@code Date} 两态都接受；</li>
     *  <li>{@code Timestamp} / {@code LocalDateTime} / {@code TIMESTAMP} / {@code DATETIME} 接受时间戳形态；纯日期形态产出
     *      {@code DATE '…'}（T-DateLiteralBackfill D4：Oracle 隐式 DATE→TIMESTAMP 等价当日 0 点，比回落
     *      引号后必然失败更合理）。</li>
     * </ul>
     */
    public static String renderFor(String value, String type, String dialectName) {
        if (!ansiTypedLiteralSupported(dialectName)) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        String simple = type == null ? null : simpleName(type);
        switch (simple == null ? "" : simple) {
            case "LocalDate":
            case "DATE":
                return DATE_RE.matcher(v).matches() ? "DATE '" + v + "'" : null;
            case "Date":
                if (DATE_RE.matcher(v).matches()) return "DATE '" + v + "'";
                if (TS_RE.matcher(v).matches()) return "TIMESTAMP '" + normalizeTs(v) + "'";
                return null;
            case "Timestamp":
            case "LocalDateTime":
            case "TIMESTAMP":
            case "DATETIME":
                if (TS_RE.matcher(v).matches()) return "TIMESTAMP '" + normalizeTs(v) + "'";
                if (DATE_RE.matcher(v).matches()) return "DATE '" + v + "'";
                return null;
            default:
                return null;
        }
    }

    /** Kotlin 默认参数（dialectName=null）等价重载。 */
    public static String renderFor(String value, String type) {
        return renderFor(value, type, null);
    }

    /** ISO {@code T} 分隔符归一为空格；缺秒补 {@code :00}（ANSI 字面量不接受省略秒，Oracle 实测 ORA-01861）。 */
    private static String normalizeTs(String v) {
        String out = v.length() > 10 && v.charAt(10) == 'T'
                ? v.substring(0, 10) + ' ' + v.substring(11)
                : v;
        if (TS_NO_SEC_RE.matcher(out).matches()) {
            out += ":00";
        }
        return out;
    }

    private static String simpleName(String type) {
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }
}
