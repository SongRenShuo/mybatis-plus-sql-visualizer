package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-ExplainConsoleFirst W7（2026-10-24）：{@code Unknown column} 的<b>跨源升格</b>判据。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>用户实测 {@code [42S22][1054] Unknown column 'first_name' in 'field list'}，上下文行三处读数全是
 * {@code mp_demo}（{@code [当前库=mp_demo，数据源=mp_demo@localhost，数据源默认库=mp_demo]}）——说明<b>源没选错</b>，
 * 错的是「SQL 与库」这一对：{@code first_name} 属 {@code jpa_demo} 库的同名 {@code user} 表，而 {@code mp_demo} 的 {@code user}
 * 表没有这一列。</p>
 *
 * <p>既有 {@link ExplainPanelChannelKt#classifyExplainFailure} 把 {@code Unknown column} <b>明确排除</b>在 WRONG_TARGET 之外
 * （ExplainPanelChannel 注释：「列级问题，同一张表在，换源解决不了」）。这个排除对
 * 「列名拼错 / 前缀写错」是对的，但对「同名表、不同列集」就漏判了——那是<b>源选错</b>，换源正是解。</p>
 *
 * <h2>判据（三条同时成立才升格，缺一条一律不升格）</h2>
 *
 * <ol>
 *  <li>错误文本是 {@code Unknown column '<col>'}（能解析出具体列名）；</li>
 *  <li>该列<b>不在</b>当前源同名表的列集里（当前源确证没有——缓存未热则无从证明，不升格）；</li>
 *  <li>存在<b>另一条在籍源</b>，其同名表<b>带这一列</b>（换个源就真能解决）。</li>
 * </ol>
 *
 * <p>第 3 条是关键：它把「拼错列名」（任何源都没有这列 → 不升格，照旧只报错）与「源选错」
 * （另一源确实有 → 升格，给就地换源引导）分开。<b>宁可漏判不可误判</b>——误判会把拼错列名的
 * 用户引导去换源，比不给引导更迷惑。</p>
 *
 * <h2>线程纪律</h2>
 *
 * <p>纯函数，零 IDE 依赖、零连接、零内省：调用方把「各源 → 表 → 列集」的查询器喂进来
 * （生产取 {@link DatabaseSchemaResolver#columnNameSourcesCached} 的只读缓存）。EDT 可直调。</p>
 *
 * <p>移植注记：master 侧为 {@code internal object}，本类落为私有构造器工具类（k2j object 承载惯例）；
 * 嵌套 {@code data class Evidence} 落 record。</p>
 */
public final class CrossSourceColumnEvidence {

    private CrossSourceColumnEvidence() {
    }

    /** MySQL / 通用形态：{@code Unknown column 'x.y' in 'field list' / in 'where clause'}。 */
    private static final Pattern UNKNOWN_COLUMN =
            Pattern.compile("unknown\\s+column\\s+'([^']+)'", Pattern.CASE_INSENSITIVE);

    /**
     * 从错误文本里取「报错列名」（已剥别名前缀，取末段）。
     *
     * <p>{@code Unknown column 'u.first_name'} → {@code first_name}；{@code Unknown column 't.area_id'} → {@code area_id}。
     * 取不到（不是该形态 / 引号缺失）返回 null。</p>
     */
    @Nullable
    public static String unknownColumnOf(String errorText) {
        if (errorText == null) return null;
        Matcher matcher = UNKNOWN_COLUMN.matcher(errorText);
        if (!matcher.find()) return null;
        String raw = matcher.group(1);
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.isEmpty()) return null;
        int dot = raw.lastIndexOf('.');
        String last = dot >= 0 ? raw.substring(dot + 1) : raw;
        return last.isBlank() ? null : last;
    }

    /**
     * 升格判定。
     *
     * @param errorText            驱动报文（可含插件拼的 {@code [当前库=…]} 上下文行，本函数只看列名形态）
     * @param sql                  被执行/被 EXPLAIN 的 SQL（用于取表名；取不到表名一律不升格）
     * @param currentSourceColumns 当前源的查询器：表名 → 该源该表的列集；未持有/未热返回 null
     * @param otherSourcesColumns  其余在籍源的查询器清单（逐个问「你有没有这张表且带这一列」）
     * @return 升格证据（含列名与表名）或 null（不升格）
     */
    @Nullable
    public static Evidence assess(
            String errorText,
            String sql,
            @Nullable Function<String, Set<String>> currentSourceColumns,
            List<Function<String, Set<String>>> otherSourcesColumns) {
        String column = unknownColumnOf(errorText);
        if (column == null) return null;
        List<String> tables = new java.util.ArrayList<>();
        try {
            for (String t : SchemaValidator.tableNames(sql)) {
                if (!t.isBlank()) tables.add(t);
            }
        } catch (Throwable ignored) {
            // runCatching{...}.getOrNull() 语义：取不到表名清单按空处理
        }
        if (tables.isEmpty()) return null;
        // 当前源必须**确证没有**这一列：缓存未热（null）说明无从证明，诚实不升格。
        boolean provenAbsent = false;
        for (String table : tables) {
            Set<String> cols = currentSourceColumns != null ? currentSourceColumns.apply(table) : null;
            if (cols == null) continue;
            if (cols.isEmpty()) continue;
            boolean has = false;
            for (String c : cols) {
                if (c.equalsIgnoreCase(column)) {
                    // 当前源其实有这一列——报错另有原因（大小写敏感/权限/别名遮蔽），不归因换源。
                    has = true;
                    break;
                }
            }
            if (has) return null;
            provenAbsent = true;
        }
        if (!provenAbsent) return null;
        // 存在另一条源持有「同名表 + 这一列」→ 换源真能解决。
        String holder = null;
        for (String table : tables) {
            boolean held = false;
            for (Function<String, Set<String>> src : otherSourcesColumns) {
                Set<String> cols;
                try {
                    cols = src.apply(table);
                } catch (Throwable ignored) {
                    cols = null;
                }
                if (cols == null) continue;
                boolean hit = false;
                for (String c : cols) {
                    if (c.equalsIgnoreCase(column)) {
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    held = true;
                    break;
                }
            }
            if (held) {
                holder = table;
                break;
            }
        }
        if (holder == null) return null;
        return new Evidence(column, holder);
    }

    /** 升格证据：报错列名 + 承载它的表名（供提示文案指名道姓）。 */
    public record Evidence(String column, String table) {
    }
}
