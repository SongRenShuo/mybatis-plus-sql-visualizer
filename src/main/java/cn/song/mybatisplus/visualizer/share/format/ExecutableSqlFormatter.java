package cn.song.mybatisplus.visualizer.share.format;

import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.sql.RuntimeSlot;
import cn.song.mybatisplus.visualizer.sql.RuntimeSlotValues;
import cn.song.mybatisplus.visualizer.sql.SlotWarning;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 可执行 SQL 格式化器（F-8 Phase C）。
 *
 * <p>复用 {@link ParameterReplacer#buildExecutableSql(String, List, Map, List)} 将 {@code ?} 替换为带类型处理的字面值，
 * 额外提供 MR/PR 评论模板（Code Review 场景贴可执行 SQL + 来源）。</p>
 *
 * <p>T-RuntimeSlotFill 券 B：本类同时是「可执行 SQL」三条链路的<b>唯一入口</b>——运行期占位回填、
 * {@code ?} 回填、{@code :name} 回填都在 {@link ParameterReplacer#buildExecutableSqlWithSlots} 里一次扫描完成，
 * 复制 / 分享 / 执行 / 导出都从这里取文本，避免出现「预览填了、复制出来还是注释」。</p>
 */
public final class ExecutableSqlFormatter {

    /** 单例（兼容 Kotlin {@code object} 语义）。 */
    @NotNull
    public static final ExecutableSqlFormatter INSTANCE = new ExecutableSqlFormatter();

    private ExecutableSqlFormatter() {
    }

    /**
     * 可执行 SQL 产物（文本 + 回填诊断）。
     *
     * <p>Kotlin {@code data class Product} 的 Java 承载：字段相等语义（equals/hashCode）与
     * 计算属性 {@code warnings} / {@code appliedSlotIds}（getter 委托 {@code slotRender}）照搬。</p>
     */
    public static final class Product {

        /** 最终文本。 */
        private final String sql;
        /** 运行期占位的回填账：哪些填进去了、哪些没填（{@link #getWarnings()} 是给用户看的原因）。 */
        private final ParameterReplacer.RuntimeSlotRender slotRender;

        public Product(@NotNull String sql, @NotNull ParameterReplacer.RuntimeSlotRender slotRender) {
            this.sql = sql;
            this.slotRender = slotRender;
        }

        /** Kotlin 属性 {@code sql}。 */
        @NotNull
        public String getSql() {
            return sql;
        }

        /** Kotlin 属性 {@code slotRender}。 */
        @NotNull
        public ParameterReplacer.RuntimeSlotRender getSlotRender() {
            return slotRender;
        }

        /** Kotlin 计算属性 {@code warnings}（委托 slotRender）。 */
        @NotNull
        public List<SlotWarning> getWarnings() {
            return slotRender.getWarnings();
        }

        /** Kotlin 计算属性 {@code appliedSlotIds}（委托 slotRender）。 */
        @NotNull
        public List<String> getAppliedSlotIds() {
            return slotRender.getAppliedSlotIds();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Product)) return false;
            Product other = (Product) o;
            return sql.equals(other.sql) && slotRender.equals(other.slotRender);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sql, slotRender);
        }

        @Override
        public String toString() {
            return "Product(sql=" + sql + ", slotRender=" + slotRender + ")";
        }
    }

    /**
     * 生成单行可执行 SQL（替换 {@code ?} 为字面值）。
     *
     * @param sql         含 {@code ?} 的 SQL
     * @param sources     参数来源列表（与 {@code ?} 按序对应）
     * @param paramValues 参数值映射（source → 值）
     * @param hints       回填提示（可空，走启发式）
     * @param slots       运行期占位表（空表 = 无占位，产物与改前逐字节一致）
     * @param slotValues  占位值（空表 = 占位原样保留）
     * @param namedHints  {@code :name} 引号提示（空表 = 不做命名参数回填）
     * @param dialectName 当前方言名（布尔字面量判定：Oracle / 达梦无布尔字面量）
     * @return 可执行 SQL
     */
    @NotNull
    public static String format(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable List<ParamHint> hints,
            @NotNull List<RuntimeSlot> slots,
            @NotNull RuntimeSlotValues slotValues,
            @NotNull Map<String, ParamHint> namedHints,
            @Nullable String dialectName) {
        return formatWithDiagnostics(
                        sql,
                        sources,
                        paramValues,
                        hints,
                        slots,
                        slotValues,
                        namedHints,
                        dialectName)
                .getSql();
    }

    /** 便捷重载：无回填提示时调用（对应 Kotlin 默认值 {@code hints = emptyList()}）。 */
    @NotNull
    public static String format(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues) {
        return format(sql, sources, paramValues, List.of());
    }

    /** 便捷重载：无提示（对应 Kotlin 默认值 {@code hints = emptyList()}）。 */
    @NotNull
    public static String format(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable List<ParamHint> hints) {
        return format(sql, sources, paramValues, hints, List.of(), RuntimeSlotValues.EMPTY, Map.of(), null);
    }

    /**
     * 同 {@link #format(String, List, Map, List, List, RuntimeSlotValues, Map, String)}，但把回填账
     * （警告 / 已填占位）一并返回，供面板提示与测试断言。
     *
     * <p>占位为空且无命名参数时，内部直接走既有 {@link ParameterReplacer#buildExecutableSql}——
     * 既有链路的产物逐字节不变。</p>
     */
    @NotNull
    public static Product formatWithDiagnostics(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable List<ParamHint> hints,
            @NotNull List<RuntimeSlot> slots,
            @NotNull RuntimeSlotValues slotValues,
            @NotNull Map<String, ParamHint> namedHints,
            @Nullable String dialectName) {
        // null hints 归一空表：回填引擎按 hints.get(paramIndex) 索引访问（null 会 NPE），
        // 空表 = 无提示 = 走启发式，与既有 buildExecutableSql 的 null 口径等价
        List<ParamHint> safeHints = hints == null ? List.of() : hints;
        ParameterReplacer.RuntimeSlotRender render = ParameterReplacer.buildExecutableSqlWithSlots(
                sql,
                slots,
                slotValues,
                sources,
                paramValues,
                safeHints,
                namedHints,
                dialectName);
        return new Product(render.getSql(), render);
    }

    /** 便捷重载：对应 Kotlin 默认参（slots/slotValues/namedHints/dialectName 缺省）。 */
    @NotNull
    public static Product formatWithDiagnostics(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable List<ParamHint> hints) {
        return formatWithDiagnostics(sql, sources, paramValues, hints,
                List.of(), RuntimeSlotValues.EMPTY, Map.of(), null);
    }

    /** 便捷重载：对应 Kotlin 默认参（hints 起全部缺省）。 */
    @NotNull
    public static Product formatWithDiagnostics(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues) {
        return formatWithDiagnostics(sql, sources, paramValues, List.of());
    }

    /**
     * 生成 MR/PR 评论格式（可执行 SQL + 来源前缀）。
     *
     * <p>模板：
     * <pre>
     * [SQL Review] UserService.java:42
     * ```sql
     * SELECT id, name FROM user WHERE name = 'zhangsan'
     * ```
     * </pre>
     *
     * @param sql        含 {@code ?} 的 SQL
     * @param sources    参数来源列表
     * @param paramValues 参数值映射
     * @param source     来源位置（文件名:行号，可 null）
     * @param prefix     标题前缀（默认 {@code [SQL Review]}）
     * @param hints      回填提示
     * @param slots      运行期占位表
     * @param slotValues 占位值
     * @param dialectName 当前方言名
     * @return MR/PR 评论文本
     */
    @NotNull
    public static String formatAsMrComment(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable String source,
            @NotNull String prefix,
            @Nullable List<ParamHint> hints,
            @NotNull List<RuntimeSlot> slots,
            @NotNull RuntimeSlotValues slotValues,
            @Nullable String dialectName) {
        String executable = format(sql, sources, paramValues, hints, slots, slotValues, Map.of(), dialectName);
        StringBuilder sb = new StringBuilder(256);
        sb.append(prefix);
        if (source != null) {
            sb.append(' ').append(source);
        }
        sb.append("\n```sql\n");
        sb.append(rtrim(executable));
        sb.append("\n```");
        return sb.toString();
    }

    /** 便捷重载：默认前缀与空提示（对应 Kotlin 默认值）。 */
    @NotNull
    public static String formatAsMrComment(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable String source) {
        return formatAsMrComment(sql, sources, paramValues, source, "[SQL Review]", List.of());
    }

    /** 便捷重载：无占位形态（对应 Kotlin 默认参 slots/slotValues/dialectName 缺省）。 */
    @NotNull
    public static String formatAsMrComment(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable String source,
            @NotNull String prefix,
            @Nullable List<ParamHint> hints) {
        return formatAsMrComment(sql, sources, paramValues, source, prefix, hints,
                List.of(), RuntimeSlotValues.EMPTY, null);
    }

    private static String rtrim(@NotNull String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) <= ' ') {
            end--;
        }
        return s.substring(0, end);
    }
}
