package cn.song.mybatisplus.visualizer.sql.perf.rules;

import java.util.List;
import java.util.regex.Pattern;

import cn.song.mybatisplus.visualizer.sql.perf.PerfRule;
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;

import net.sf.jsqlparser.statement.Statement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 第一批性能规则（正则即可，无需 AST 解析，共 6 条）。
 *
 * <p>规则清单：
 * <ul>
 *   <li>{@code missing_where}：UPDATE/DELETE 无 WHERE</li>
 *   <li>{@code leading_wildcard_like}：LIKE '%xxx' 前导通配符</li>
 *   <li>{@code select_star}：SELECT * 多表 JOIN</li>
 *   <li>{@code order_by_without_limit}：ORDER BY 无 LIMIT</li>
 *   <li>{@code order_by_rand}：ORDER BY RAND()</li>
 *   <li>{@code union_instead_of_union_all}：UNION 可改 UNION ALL</li>
 * </ul>
 */
public final class PerfRulesBatch1 {

    /** 单例（兼容 Kotlin {@code object} 语义）。 */
    @NotNull
    public static final PerfRulesBatch1 INSTANCE = new PerfRulesBatch1();

    /** 所有第一批规则实例。 */
    @NotNull
    public static final List<PerfRule> all = List.of(
            MissingWhereRule.INSTANCE,
            LeadingWildcardLikeRule.INSTANCE,
            SelectStarRule.INSTANCE,
            OrderByWithoutLimitRule.INSTANCE,
            OrderByRandRule.INSTANCE,
            UnionInsteadOfUnionAllRule.INSTANCE
    );

    private PerfRulesBatch1() {
    }

    /**
     * `missing_where`：UPDATE/DELETE 无 WHERE 条件（全表操作风险）。
     *
     * <p>检测逻辑：语句以 UPDATE 或 DELETE 开头（忽略前导空白/注释），且不含顶层 WHERE 关键字。
     */
    public static final class MissingWhereRule implements PerfRule {
        /** 单例（兼容 Kotlin {@code object} 语义）。 */
        @NotNull
        public static final MissingWhereRule INSTANCE = new MissingWhereRule();

        private MissingWhereRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "missing_where";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            String normalized = stripLeadingComments(sql).trim();
            boolean isUpdate = normalized.toUpperCase().startsWith("UPDATE ");
            boolean isDelete = normalized.toUpperCase().startsWith("DELETE ");
            if (!isUpdate && !isDelete) {
                return List.of();
            }
            // 检测顶层 WHERE（不在括号内）。T-McpPerfWhereCase：hasTopLevelKeyword 按大写比对
            // （形参名 upperSql 即约定），此处必须传大写串——SQL 关键字大小写不敏感，而 mapper
            // 渲染产物普遍小写（`update ... where id = ?`），传原文会导致「有 WHERE 却报全表操作」。
            if (hasTopLevelKeyword(normalized.toUpperCase(), "WHERE")) {
                return List.of();
            }
            String stmtType = isUpdate ? "UPDATE" : "DELETE";
            return List.of(new PerfWarning(getRuleId(), PerfSeverity.ERROR, "perf.missing_where", List.of(stmtType), 1));
        }
    }

    /**
     * `leading_wildcard_like`：LIKE '%xxx' 前导通配符（无法走索引）。
     *
     * <p>检测逻辑：正则匹配 `LIKE '...'` / `NOT LIKE '...'`，模式以 `%` 开头。
     */
    public static final class LeadingWildcardLikeRule implements PerfRule {
        /** 单例（兼容 Kotlin {@code object} 语义）。 */
        @NotNull
        public static final LeadingWildcardLikeRule INSTANCE = new LeadingWildcardLikeRule();

        private static final Pattern LIKE_PATTERN = Pattern.compile(
                "(?:NOT\\s+)?LIKE\\s+['\"](%[^\"']*)['\"]",
                Pattern.CASE_INSENSITIVE
        );

        private LeadingWildcardLikeRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "leading_wildcard_like";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            boolean hasLeading = LIKE_PATTERN.matcher(sql).results()
                    .anyMatch(match -> match.group(1).startsWith("%"));
            if (hasLeading) {
                return List.of(new PerfWarning(getRuleId(), PerfSeverity.WARN, "perf.leading_wildcard"));
            }
            return List.of();
        }
    }

    /**
     * `select_star`：SELECT * 多表 JOIN（字段冗余 + 列变更风险）。
     *
     * <p>检测逻辑：SELECT * 或 SELECT alias.*，且含至少一个 JOIN 子句。
     * 单表查询的 SELECT * 不报（反而不如明确列名风险低，但仍提示 INFO）。
     */
    public static final class SelectStarRule implements PerfRule {
        /** 单例（兼容 Kotlin {@code object} 语义）。 */
        @NotNull
        public static final SelectStarRule INSTANCE = new SelectStarRule();

        private SelectStarRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "select_star";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            String upper = sql.toUpperCase();
            int selectIdx = upper.indexOf("SELECT");
            int fromIdx = upper.indexOf("FROM");
            if (selectIdx < 0 || fromIdx < 0 || fromIdx <= selectIdx) {
                return List.of();
            }
            String selectSegment = sql.substring(selectIdx + "SELECT".length(), fromIdx).trim();
            String cleaned = selectSegment;
            if (cleaned.startsWith("DISTINCT")) {
                cleaned = cleaned.substring("DISTINCT".length());
            }
            if (cleaned.startsWith("ALL")) {
                cleaned = cleaned.substring("ALL".length());
            }
            cleaned = cleaned.trim();
            boolean isStar = cleaned.equals("*") || cleaned.matches("[\\w]+\\.\\*$");
            if (!isStar) {
                return List.of();
            }
            // 有 JOIN → WARN（多表字段冗余），无 JOIN → INFO
            boolean hasJoin = upper.contains(" JOIN ");
            if (hasJoin) {
                return List.of(new PerfWarning(getRuleId(), PerfSeverity.WARN, "perf.select_star_with_join"));
            }
            return List.of(new PerfWarning(getRuleId(), PerfSeverity.INFO, "perf.select_star"));
        }
    }

    /**
     * `order_by_without_limit`：ORDER BY 无 LIMIT（可能返回大量数据）。
     */
    public static final class OrderByWithoutLimitRule implements PerfRule {
        /** 单例（兼容 Kotlin {@code object} 语义）。 */
        @NotNull
        public static final OrderByWithoutLimitRule INSTANCE = new OrderByWithoutLimitRule();

        private OrderByWithoutLimitRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "order_by_without_limit";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            String upper = sql.toUpperCase();
            if (!upper.contains("ORDER BY")) {
                return List.of();
            }
            // 有 LIMIT 时不报
            if (hasTopLevelKeyword(upper, "LIMIT")) {
                return List.of();
            }
            return List.of(new PerfWarning(getRuleId(), PerfSeverity.INFO, "perf.order_by_without_limit"));
        }
    }

    /**
     * `order_by_rand`：ORDER BY RAND()（全表排序，性能杀手）。
     */
    public static final class OrderByRandRule implements PerfRule {
        /** 单例（兼容 Kotlin {@code object} 语义）。 */
        @NotNull
        public static final OrderByRandRule INSTANCE = new OrderByRandRule();

        private static final Pattern RAND_PATTERN = Pattern.compile(
                "ORDER\\s+BY\\s+[^()]*\\bRAND\\s*\\(",
                Pattern.CASE_INSENSITIVE
        );

        private OrderByRandRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "order_by_rand";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (RAND_PATTERN.matcher(sql).find()) {
                return List.of(new PerfWarning(getRuleId(), PerfSeverity.WARN, "perf.order_by_rand"));
            }
            return List.of();
        }
    }

    /**
     * `union_instead_of_union_all`：UNION 可改 UNION ALL（去重有额外开销）。
     *
     * <p>检测逻辑：存在 {@code UNION} 但非 {@code UNION ALL}。
     */
    public static final class UnionInsteadOfUnionAllRule implements PerfRule {
        /** 单例（兼容 Kotlin {@code object} 语义）。 */
        @NotNull
        public static final UnionInsteadOfUnionAllRule INSTANCE = new UnionInsteadOfUnionAllRule();

        private static final Pattern UNION_PATTERN = Pattern.compile("\\bUNION\\b(?!\\s+ALL\\b)");

        private UnionInsteadOfUnionAllRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "union_instead_of_union_all";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            String upper = sql.toUpperCase();
            // 匹配 UNION 但排除 UNION ALL
            if (UNION_PATTERN.matcher(upper).find()) {
                return List.of(new PerfWarning(getRuleId(), PerfSeverity.INFO, "perf.union_instead_of_union_all"));
            }
            return List.of();
        }
    }

    // 辅助函数（原文件底部顶层私有函数，转为类私有静态方法）

    /** 剥离前导 SQL 注释（单行注释 + 块注释）。 */
    private static String stripLeadingComments(String sql) {
        String s = sql.trim();
        while (true) {
            s = s.trim();
            if (s.startsWith("--")) {
                int nl = s.indexOf('\n');
                s = nl >= 0 ? s.substring(nl + 1) : "";
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                s = end >= 0 ? s.substring(end + 2) : "";
            } else {
                break;
            }
        }
        return s;
    }

    /** 检测顶层关键字（不在括号内）。 */
    private static boolean hasTopLevelKeyword(String upperSql, String keyword) {
        int depth = 0;
        int i = 0;
        while (i < upperSql.length()) {
            char c = upperSql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else {
                if (depth == 0 && upperSql.substring(i).startsWith(keyword)) {
                    Character before = i - 1 >= 0 ? upperSql.charAt(i - 1) : null;
                    Character after = i + keyword.length() < upperSql.length()
                            ? upperSql.charAt(i + keyword.length()) : null;
                    if ((before == null || !(Character.isLetterOrDigit(before) || before == '_'))
                            && (after == null || !(Character.isLetterOrDigit(after) || after == '_'))) {
                        return true;
                    }
                }
            }
            i++;
        }
        return false;
    }
}
