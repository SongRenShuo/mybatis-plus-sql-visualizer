package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.ConditionToggle;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.FillClause;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SetClause;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import kotlin.Pair;

/**
 * SQL 生成器（M1-04 / M2-03 / M4-04）：把 {@link SqlStatement} 模型渲染为 SQL 文本。
 *
 * 支持语句类型：SELECT（含 MPJ 联表）、UPDATE、DELETE。
 *
 * 输出约定（与 docs/项目介绍.md 联表示例风格对齐）：
 *  - 关键字大写
 *  - 默认多行模式：每个子句独占一行，WHERE 后续条件以 4 空格缩进 + {@code AND} 开头
 *  - 压缩模式（compact）：子句间以空格连接，WHERE 条件内联（F-03 格式化视图切换）
 *  - 参数：psi 层已求值的字面量直接渲染；运行时值渲染为 {@code ?} 并收集来源（F-02）
 *
 * 本类为纯函数工具类，不依赖 PSI / IDE，可独立单测。
 */
public final class SqlGenerator {

    /**
     * 单例（兼容 Kotlin object 语义与原 Java/Kotlin 调用方 {@code SqlGenerator.INSTANCE.xxx()} 写法）。
     * 本类方法均为静态，可直接 {@code SqlGenerator.xxx()} 调用。
     */
    public static final SqlGenerator INSTANCE = new SqlGenerator();

    private SqlGenerator() {
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 生成 SELECT SQL（多行模式，兼容旧调用）。
     */
    public static String generate(SelectStatement statement) {
        return generate((SqlStatement) statement, false);
    }

    /**
     * 生成 SQL（多行模式，任意语句类型）。
     */
    public static String generate(SqlStatement statement) {
        return generate(statement, false);
    }

    /**
     * 生成 SQL（按语句类型分发，多行缩进模式默认值）。
     *
     * @param statement          语句模型（SELECT / UPDATE / DELETE）
     * @param compact            true=压缩模式，false=多行缩进模式
     * @return SQL 文本
     */
    public static String generate(SqlStatement statement, boolean compact) {
        return generate(statement, compact, MySqlDialect.INSTANCE, null);
    }

    /**
     * 生成 SQL（按语句类型分发，多行缩进模式默认值，指定动态条件启用集合、方言默认 MySQL）。
     *
     * @param statement          语句模型（SELECT / UPDATE / DELETE）
     * @param compact            true=压缩模式，false=多行缩进模式
     * @param enabledConditionIds 动态条件启用集合（M12）；null → 按 toggle.defaultEnabled
     * @return SQL 文本
     */
    public static String generate(SqlStatement statement, boolean compact, Set<String> enabledConditionIds) {
        return generate(statement, compact, MySqlDialect.INSTANCE, enabledConditionIds);
    }

    /**
     * 生成 SQL（按语句类型分发）。
     *
     * @param statement          语句模型（SELECT / UPDATE / DELETE）
     * @param compact            true=压缩模式，false=多行缩进模式
     * @param dialect            数据库方言（影响分页子句，默认 MySQL）
     * @param enabledConditionIds 动态条件启用集合（M12）；null（默认）→ 按 toggle.defaultEnabled
     * @return SQL 文本
     */
    public static String generate(
            SqlStatement statement,
            boolean compact,
            Dialect dialect,
            Set<String> enabledConditionIds
    ) {
        return generateInternal(statement, compact, null, null, dialect, enabledConditionIds);
    }

    /**
     * 生成 SQL 并收集未解析参数来源与回填提示（F-02 参数替换与对照表，M13 hints）。
     *
     * @param statement          语句模型
     * @param compact            true=压缩模式，false=多行缩进模式
     * @param dialect            数据库方言（影响分页子句）
     * @param enabledConditionIds 动态条件启用集合（M12）：仅渲染 id 在该集合内的动态条件；
     *                             null（默认）→ 按各条件 toggle.defaultEnabled 渲染（与 M11 基线一致）
     * @return SQL、参数来源列表与并行的 {@link ParamHint} 回填提示
     */
    public static GeneratedSql generateWithParams(SqlStatement statement) {
        return generateWithParams(statement, false, MySqlDialect.INSTANCE, null);
    }

    public static GeneratedSql generateWithParams(SqlStatement statement, boolean compact) {
        return generateWithParams(statement, compact, MySqlDialect.INSTANCE, null);
    }

    public static GeneratedSql generateWithParams(
            SqlStatement statement,
            boolean compact,
            Dialect dialect,
            Set<String> enabledConditionIds
    ) {
        List<String> params = new ArrayList<>();
        List<ParamHint> hints = new ArrayList<>();
        String sql = generateInternal(statement, compact, params, hints, dialect, enabledConditionIds);
        return new GeneratedSql(sql, params, hints);
    }

    /**
     * 渲染单个条件为文本（占位符渲染为 {@code ?}，不收集参数）。
     * 供动态条件复选框标签 / 条件视图复用（M12）。
     */
    public static String renderConditionText(Condition condition) {
        return renderCondition(condition, null, null, null, null, MySqlDialect.INSTANCE);
    }

    // ------------------------------------------------------------------
    // 内部渲染
    // ------------------------------------------------------------------

    private static String generateInternal(
            SqlStatement statement,
            boolean compact,
            List<String> paramSink,
            List<ParamHint> hintSink,
            Dialect dialect,
            Set<String> enabledConditionIds
    ) {
        if (statement instanceof SelectStatement s) {
            return generateSelect(s, compact, paramSink, hintSink, dialect, enabledConditionIds);
        }
        if (statement instanceof UpdateStatement u) {
            return generateUpdate(u, compact, paramSink, hintSink, dialect, enabledConditionIds);
        }
        if (statement instanceof DeleteStatement d) {
            return generateDelete(d, compact, paramSink, hintSink, dialect, enabledConditionIds);
        }
        throw new IllegalStateException("Unknown statement type: " + statement.getClass());
    }

    /** SELECT 渲染（含 MPJ 主表别名与联表子句）。 */
    private static String generateSelect(
            SelectStatement statement,
            boolean compact,
            List<String> paramSink,
            List<ParamHint> hintSink,
            Dialect dialect,
            Set<String> enabledConditionIds
    ) {
        String nl = compact ? " " : "\n";

        // SELECT 列：未显式指定时展开为表全部列（联表场景同主表列）
        // T-M23.4：展开列改为 tableAlias + columnName 构造（原 `prefix + it` 拼进 columnName，
        // 关键字引用无法拆分别名前缀；顺带修复 quoteHint 按 columnName 匹配的隐患）
        List<ColumnRef> selectColumns;
        if (statement.getSelectColumns().isEmpty()) {
            selectColumns = new ArrayList<>();
            for (String it : statement.getTable().getAllColumnNames()) {
                selectColumns.add(new ColumnRef(statement.getTableAlias(), it, null, null, null, null, null));
            }
        } else {
            selectColumns = statement.getSelectColumns();
        }

        // first 片段（官方 API）：置于 SELECT 之前（如 `/* hint */ SELECT ...`）
        // T-UpdateSetSqlConcat：片段里的 `?` 按序登记来源（SELECT 之前，与文本顺序一致）
        registerFragmentSources(statement.getRawFirstParams(), paramSink, hintSink);
        StringBuilder sb = new StringBuilder(statement.getRawFirst() != null ? statement.getRawFirst() + " " : "");
        sb.append("SELECT ");
        if (statement.isDistinct()) {
            sb.append("DISTINCT ");
        }
        final List<String> sink = paramSink;
        final List<ParamHint> hints = hintSink;
        sb.append(selectColumns.stream().map(it -> renderColumnRef(it, dialect, sink, hints)).collect(Collectors.joining(", ")));

        // FROM 主表（可带别名）；T-M21-07：from(子查询主表) 形态 → FROM (子查询SQL) 外层别名
        // （主表实体表名不再直接出现——在子查询内部）
        Pair<String, String> sub = statement.getFromSubQuery();
        if (sub != null) {
            sb.append(nl).append("FROM (").append(sub.getFirst()).append(") ").append(sub.getSecond());
        } else {
            sb.append(nl).append("FROM ").append(renderTableName(statement.getTable().getTableName(), dialect));
            if (statement.getTableAlias() != null) {
                sb.append(" ").append(statement.getTableAlias());
            }
        }

        // JOIN 子句
        for (JoinClause join : statement.getJoins()) {
            sb.append(nl).append(renderJoin(join, compact, paramSink, hintSink, enabledConditionIds, dialect));
        }

        // WHERE 条件
        String whereSql = renderConditionList(
                statement.getConditions(), Connector.AND, !compact, paramSink, enabledConditionIds, hintSink,
                statement.getTable(), dialect);
        if (!whereSql.isEmpty()) {
            sb.append(nl).append("WHERE ").append(whereSql);
        }

        // GROUP BY
        if (!statement.getGroupBy().isEmpty()) {
            sb.append(nl).append("GROUP BY ")
                    .append(statement.getGroupBy().stream().map(it -> renderQualified(it, dialect)).collect(Collectors.joining(", ")));
        }

        // HAVING
        // T-M29.1：HAVING 子条件不在面板展示（无开关行）——按各自 defaultEnabled 渲染，
        // 不按 enabledConditionIds 过滤（否则面板流程恒丢失 HAVING，M19.5 预存缺陷）
        Condition h = statement.getHaving();
        if (h != null && isConditionVisible(h, enabledConditionIds)) {
            String havingSql = renderCondition(h, paramSink, hintSink, statement.getTable(), null, dialect);
            if (!havingSql.isEmpty()) {
                sb.append(nl).append("HAVING ").append(havingSql);
            }
        }

        // ORDER BY
        if (!statement.getOrderBy().isEmpty()) {
            sb.append(nl).append("ORDER BY ")
                    .append(statement.getOrderBy().stream().map(it ->
                            renderQualified(it.getColumn(), dialect) + " " + (it.isAsc() ? "ASC" : "DESC")
                    ).collect(Collectors.joining(", ")));
        } else if (statement.getLimit() != null && dialect.limitRequiresOrderBy()) {
            // T-SqlServerLimitOrder（2026-10-30 真机）：SQL Server 的 OFFSET/FETCH 必须跟在 ORDER BY
            // 之后，否则 `Incorrect syntax near '0'`。无排序 + 分页时补语义中性的
            // `ORDER BY (SELECT NULL)`（T-SQL 社区标准写法 = 不指定顺序），保证产物可执行。
            sb.append(nl).append("ORDER BY (SELECT NULL)");
        }

        // around 片段（官方 API，T-M21-09）：渲染于 ORDER BY 之后、LIMIT 之前
        if (statement.getRawAround() != null) {
            // T-UpdateSetSqlConcat：片段里的 `?` 按序登记来源（位于 ORDER BY 之后）
            registerFragmentSources(statement.getRawAroundParams(), paramSink, hintSink);
            sb.append(nl).append(statement.getRawAround());
        }

        // LIMIT（分页子句按方言渲染；T-M29-03：分页运行时值占位符参与参数收集——面板回填）
        if (statement.getLimit() != null) {
            collectLimitValue(statement.getLimit().getOffset(), paramSink, hintSink);
            collectLimitValue(statement.getLimit().getCount(), paramSink, hintSink);
            sb.append(nl).append(dialect.renderLimit(statement.getLimit()));
        }

        // UNION / UNION ALL 子查询（MPJ 官方 API，M15 增补）
        for (var union : statement.getUnions()) {
            // T-UpdateSetSqlConcat：子语句里的 `?` 按序登记来源（子语句整段渲染于此处）
            registerFragmentSources(union.getParams(), paramSink, hintSink);
            sb.append(nl).append(union.getKeyword()).append(" (").append(union.getSql()).append(")");
        }

        // comment 片段（官方 API）：追加到语句末尾（`/* comment */`）
        if (statement.getComment() != null) {
            sb.append(nl).append("/* ").append(statement.getComment()).append(" */");
        }

        return sb.toString();
    }

    /**
     * UPDATE 渲染：UPDATE 表 [别名] [JOIN ...] SET 列 = 值, ... WHERE ...
     *
     * T-M21-08：UpdateJoinWrapper 联表更新形态——tableAlias 非空时 {@code UPDATE 表名 别名}，
     * joins 逐行渲染于 FROM 之后、SET 之前（复用 {@link #renderJoin}）。
     */
    private static String generateUpdate(
            UpdateStatement statement,
            boolean compact,
            List<String> paramSink,
            List<ParamHint> hintSink,
            Dialect dialect,
            Set<String> enabledConditionIds
    ) {
        String nl = compact ? " " : "\n";
        StringBuilder sb = new StringBuilder("UPDATE ").append(renderTableName(statement.getTable().getTableName(), dialect));
        if (statement.getTableAlias() != null) {
            sb.append(" ").append(statement.getTableAlias());
        }
        for (JoinClause join : statement.getJoins()) {
            sb.append(nl).append(renderJoin(join, compact, paramSink, hintSink, enabledConditionIds, dialect));
        }

        if (!statement.getSets().isEmpty()) {
            sb.append(nl).append("SET ")
                    .append(statement.getSets().stream().map(set -> {
                        if (set.isRaw()) {
                            // T-UpdateSetSqlConcat：rawSql 里的 `?` 按序登记来源——不登记会让面板把
                            // 用户的 WHERE 值填到 SET 的占位符上（首例即 setSql 拼接形态）
                            registerFragmentSources(set.getRawParams(), paramSink, hintSink);
                            return set.getRawSql() != null ? set.getRawSql() : "";
                        }
                        if (set.getColumn() != null) {
                            return renderQualified(set.getColumn(), dialect) + " = " + renderValue(
                                    set.getValue() != null ? set.getValue() : new SqlValue.Placeholder("?"),
                                    paramSink, hintSink, quoteHint(statement.getTable(), set.getColumn()));
                        }
                        return "";
                    }).collect(Collectors.joining(", ")));
        }

        appendWhere(sb, statement.getConditions(), nl, compact, paramSink, hintSink, enabledConditionIds, statement.getTable(), dialect);
        // 非 limit 尾缀透传（U05，T-M15-04）：`last("AND (last_total - n >= 0)")` 追加到语句末尾
        if (statement.getRawTail() != null) {
            // T-UpdateSetSqlConcat：尾缀片段里的 `?` 按序登记来源（与文本顺序一致——WHERE 之后）
            registerFragmentSources(statement.getRawTailParams(), paramSink, hintSink);
            sb.append(nl).append(statement.getRawTail());
        }
        return sb.toString();
    }

    /**
     * DELETE 渲染：DELETE FROM 表 WHERE ...；T-M21-08 DeleteJoinWrapper 多表删除形态：
     * {@code delete(Class...)/delete(String...)/deleteAll()} 的删除目标列表非空时渲染
     * {@code DELETE t, t1 FROM 表名 t}（主表别名拼在 FROM 后）+ joins + WHERE。
     * deleteTargets 为空（单表删除）走既有 {@code DELETE FROM 表名} 形态，回归不变。
     *
     * <p>O-8（缺陷 C）：{@code last("LIMIT n")} 尾段渲染于语句末尾（MP 语义 MySQL
     * {@code DELETE ... LIMIT n}）——仅 {@link Dialect#getSupportsDeleteLimit} 为 true 的方言
     * （MySQL 语法族）输出，PostgreSQL/Oracle 等不支持的方言静默省略（与分页子句方言策略一致）。
     */
    private static String generateDelete(
            DeleteStatement statement,
            boolean compact,
            List<String> paramSink,
            List<ParamHint> hintSink,
            Dialect dialect,
            Set<String> enabledConditionIds
    ) {
        String nl = compact ? " " : "\n";
        StringBuilder sb = new StringBuilder();
        if (statement.getDeleteTargets().isEmpty()) {
            sb.append("DELETE FROM ").append(renderTableName(statement.getTable().getTableName(), dialect));
        } else {
            sb.append("DELETE ").append(String.join(", ", statement.getDeleteTargets()));
            sb.append(" FROM ").append(renderTableName(statement.getTable().getTableName(), dialect));
            if (statement.getTableAlias() != null) {
                sb.append(" ").append(statement.getTableAlias());
            }
        }
        for (JoinClause join : statement.getJoins()) {
            sb.append(nl).append(renderJoin(join, compact, paramSink, hintSink, enabledConditionIds, dialect));
        }
        appendWhere(sb, statement.getConditions(), nl, compact, paramSink, hintSink, enabledConditionIds, statement.getTable(), dialect);
        // O-8（缺陷 C）：limit 尾段仅在方言支持 DELETE LIMIT 时渲染（MySQL 语法族），其余静默省略
        if (statement.getLimit() != null && dialect.getSupportsDeleteLimit()) {
            sb.append(nl).append(dialect.renderLimit(statement.getLimit()));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // T-MpjFill：fill 附加 SELECT 生成（MPJ 1.5.1+）
    // ------------------------------------------------------------------

    /**
     * 生成 fill 附加 SELECT SQL（MPJ 1.5.1+，T-MpjFill）。
     *
     * 渲染形态：
     * <pre>
     * SELECT &lt;列&gt; FROM &lt;表&gt; &lt;别名&gt; WHERE &lt;tagField&gt; IN (?)[ LIMIT &lt;n&gt;]
     * </pre>
     *
     * IN 列表为主查询结果集关联键，静态预览无法获取，统一渲染 {@code IN (?)} 占位符（与面板参数对照表语义一致）。
     *
     * @param fillClause  fill 子句模型
     * @param dialect     数据库方言（影响标识符引用与 LIMIT 形态，默认 MySQL）
     * @return fill 附加 SELECT SQL（无末尾分号）
     */
    public static String generateFill(FillClause fillClause) {
        return generateFill(fillClause, MySqlDialect.INSTANCE);
    }

    public static String generateFill(FillClause fillClause, Dialect dialect) {
        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(fillClause.getSelectColumns().stream().map(it -> renderColumnRef(it, dialect)).collect(Collectors.joining(", ")));
        sb.append(" FROM ").append(renderTableName(fillClause.getChildTable().getTableName(), dialect));
        sb.append(" ").append(fillClause.getChildAlias());
        sb.append(" WHERE ").append(
                renderQualified(new ColumnRef(fillClause.getChildAlias(), fillClause.getTagField(), null, null, null, null, null), dialect));
        sb.append(" IN (?)");
        if (fillClause.getLimit() != null) {
            sb.append(" LIMIT ").append(fillClause.getLimit());
        }
        return sb.toString();
    }

    /** 追加 WHERE 子句（无条件时不输出）。 */
    private static void appendWhere(
            StringBuilder sb,
            List<Condition> conditions,
            String nl,
            boolean compact,
            List<String> paramSink,
            List<ParamHint> hintSink,
            Set<String> enabledConditionIds,
            TableInfo table,
            Dialect dialect
    ) {
        String whereSql = renderConditionList(conditions, Connector.AND, !compact, paramSink, enabledConditionIds, hintSink, table, dialect);
        if (!whereSql.isEmpty()) {
            sb.append(nl).append("WHERE ").append(whereSql);
        }
    }

    /** JOIN 子句渲染：`INNER JOIN 表 别名 ON 条件1 AND 条件2`；rawSql/customKeyword 形态原样透传（M15-P0）。 */
    private static String renderJoin(
            JoinClause join,
            boolean compact,
            List<String> paramSink,
            List<ParamHint> hintSink,
            Set<String> enabledConditionIds,
            Dialect dialect
    ) {
        String typeName = join.getCustomKeyword() != null ? join.getCustomKeyword() : joinTypeName(join.getType());
        // 原生 join 子句（leftJoin("address t1 on t1.user_id = t.id", args)）：整段原样
        if (join.getRawSql() != null) {
            // T-UpdateSetSqlConcat：join 片段里的 `?` 按序登记（渲染位置在 SET/WHERE 之前）
            registerFragmentSources(join.getRawParams(), paramSink, hintSink);
            return typeName + " " + join.getRawSql();
        }
        String onPart = join.getOnConditions().isEmpty()
                ? ""
                : " ON " + renderConditionList(join.getOnConditions(), Connector.AND, false, paramSink, enabledConditionIds, hintSink, null, dialect);
        return typeName + " " + renderTableName(join.getTableName(), dialect) + " " + join.getAlias() + onPart;
    }

    private static String joinTypeName(JoinType type) {
        return switch (type) {
            case INNER -> "INNER JOIN";
            case LEFT -> "LEFT JOIN";
            case RIGHT -> "RIGHT JOIN";
            case FULL -> "FULL JOIN";
        };
    }

    // ------------------------------------------------------------------
    // 条件渲染（SELECT/UPDATE/DELETE 共用）
    // ------------------------------------------------------------------

    /**
     * 渲染一组条件。
     *
     * @param indent   true=顶层多条件换行缩进（如 `    AND xxx`），false=压缩连接
     * @param paramSink 参数收集器（可为 null）
     * @param dialect  方言（T-M23.4 关键字标识符引用；默认 MySQL）
     */
    private static String renderConditionList(
            List<Condition> conditions,
            Connector connector,
            boolean indent,
            List<String> paramSink,
            Set<String> enabledIds,
            List<ParamHint> hintSink,
            TableInfo table,
            Dialect dialect
    ) {
        // 动态条件按启用集合/defaultEnabled 过滤；组内全空的 Group 渲染为空字符串后剔除
        List<String> rendered = new ArrayList<>();
        for (Condition it : conditions) {
            if (!isConditionVisible(it, enabledIds)) {
                continue;
            }
            String r = renderCondition(it, paramSink, hintSink, table, enabledIds, dialect);
            if (!r.isEmpty()) {
                rendered.add(r);
            }
        }
        if (rendered.isEmpty()) {
            return "";
        }
        if (indent) {
            StringBuilder sb = new StringBuilder();
            for (int index = 0; index < rendered.size(); index++) {
                if (index == 0) {
                    sb.append(rendered.get(index));
                } else {
                    sb.append("\n    ").append(connector.name()).append(" ").append(rendered.get(index));
                }
            }
            return sb.toString();
        }
        return String.join(" " + connector.name() + " ", rendered);
    }

    /**
     * 动态条件可见性（M12）：无 toggle 恒可见；有 toggle 时按启用集合（enabledIds 非 null）
     * 或 defaultEnabled（enabledIds 为 null，默认渲染与 M11 基线一致）判定。
     */
    private static boolean isConditionVisible(Condition condition, Set<String> enabledIds) {
        ConditionToggle t = condition.getToggle();
        if (t == null) {
            return true;
        }
        return enabledIds != null ? enabledIds.contains(t.getId()) : t.isDefaultEnabled();
    }

    /**
     * 渲染单个条件节点。
     *
     * @param enabledIds 动态条件启用集合（递归传给 Group 子条件；默认 null 按 defaultEnabled）
     * @param hintSink   回填提示收集器（M13，可为 null）
     * @param table      主表信息（列类型查询用；join ON 条件等无法匹配时类型未知）
     * @param dialect    方言（T-M23.4 关键字标识符引用；默认 MySQL）
     */
    private static String renderCondition(
            Condition condition,
            List<String> paramSink,
            List<ParamHint> hintSink,
            TableInfo table,
            Set<String> enabledIds,
            Dialect dialect
    ) {
        if (condition instanceof Condition.Simple s) {
            return renderQualified(s.getColumn(), dialect) + " " + s.getOp() + " " + renderValue(
                    s.getValue(), paramSink, hintSink, quoteHint(table, s.getColumn()));
        }
        if (condition instanceof Condition.Unary u) {
            return renderQualified(u.getColumn(), dialect) + " " + u.getOp();
        }
        if (condition instanceof Condition.In in) {
            String op = in.isNegated() ? "NOT IN" : "IN";
            // M19.6（用户实测）：IN 列表占位符标记 inList 提示——面板回填按逗号分割多值、每个值独立引号
            String values = in.getValues().stream().map(value -> {
                if (value instanceof SqlValue.Placeholder p) {
                    if (paramSink != null) {
                        paramSink.add(p.getSource());
                    }
                    if (hintSink != null) {
                        hintSink.add(new ParamHint(quoteHint(table, in.getColumn()), null, true));
                    }
                    return "?";
                }
                return ((SqlValue.Literal) value).getRaw();
            }).collect(Collectors.joining(", "));
            return renderQualified(in.getColumn(), dialect) + " " + op + " (" + values + ")";
        }
        if (condition instanceof Condition.Between b) {
            String op = b.isNegated() ? "NOT BETWEEN" : "BETWEEN";
            return renderQualified(b.getColumn(), dialect) + " " + op + " "
                    + renderValue(b.getLo(), paramSink, hintSink, quoteHint(table, b.getColumn())) + " AND "
                    + renderValue(b.getHi(), paramSink, hintSink, quoteHint(table, b.getColumn()));
        }
        if (condition instanceof Condition.Like l) {
            String op = l.isNegated() ? "NOT LIKE" : "LIKE";
            return renderQualified(l.getColumn(), dialect) + " " + op + " "
                    + renderLike(l.getPattern(), l.getPosition(), paramSink, hintSink);
        }
        if (condition instanceof Condition.Group g) {
            // T-M29.1：组带开关（布尔重载）时子条件开关并入组行、不在面板单独展示——
            // 子条件按各自 defaultEnabled 渲染（enabledIds 仅控制组本身显隐）；
            // 无开关组维持 enabledIds 透传（子条件行仍可单独勾选）
            Set<String> childIds = g.getToggle() != null ? null : enabledIds;
            String childrenSql = renderConditionList(g.getChildren(), g.getConnector(), false, paramSink, childIds, hintSink, table, dialect);
            if (childrenSql.isEmpty()) {
                return "";
            }
            return g.isNegated() ? "NOT (" + childrenSql + ")" : "(" + childrenSql + ")";
        }
        if (condition instanceof Condition.ColumnComparison cc) {
            return renderQualified(cc.getLeft(), dialect) + " = " + renderQualified(cc.getRight(), dialect);
        }
        if (condition instanceof Condition.Raw r) {
            // T-UpdateSetSqlConcat：片段里的 `?` 按序登记来源——不登记会让面板按位置回填整体错位
            // （片段里的 `?` 会被当成下一个条件的参数）
            registerFragmentSources(r.getParams(), paramSink, hintSink);
            return r.getSqlText();
        }
        throw new IllegalStateException("Unknown condition type: " + condition.getClass());
    }

    // ------------------------------------------------------------------
    // T-M23.4：标识符渲染统一入口（关键字命中 → 按方言符号包裹；模型层列名保持原始值）
    // ------------------------------------------------------------------

    /**
     * 渲染带表限定的列引用：`别名.列`——别名前缀不包，列名按需引用（`t.`table``）。
     * 与 {@link ColumnRef#qualifiedName(Dialect)}（dialect 重载）同一入口。
     */
    private static String renderQualified(ColumnRef column, Dialect dialect) {
        return column.qualifiedName(dialect);
    }

    /**
     * 渲染完整列表达式（含聚合函数与 AS 别名）：rawExpr 原样透传（selectFunc 表达式，不拆解——
     * 文档化边界）；否则 `函数(限定列)` 或 `限定列`；别名 `AS xxx` 不引用。
     */
    /**
     * 渲染完整列表达式（含聚合函数与 AS 别名）：rawExpr 原样透传（selectFunc 表达式，不拆解——
     * 文档化边界）；否则 `函数(限定列)` 或 `限定列`；别名 `AS xxx` 不引用。
     */
    private static String renderColumnRef(ColumnRef column, Dialect dialect,
                                          List<String> paramSink, List<ParamHint> hintSink) {
        // T-UpdateSetSqlConcat：`select("col AS " + x)` 这类片段列的 `?` 按序登记（SELECT 列表渲染点）
        registerFragmentSources(column.getRawParams(), paramSink, hintSink);
        String expr = column.getRawExpr() != null
                ? column.getRawExpr()
                : (column.getFunction() != null ? column.getFunction() + "(" + renderQualified(column, dialect) + ")" : renderQualified(column, dialect));
        return column.getAlias() != null ? expr + " AS " + column.getAlias() : expr;
    }

    private static String renderColumnRef(ColumnRef column, Dialect dialect) {
        return renderColumnRef(column, dialect, null, null);
    }

    /**
     * T-UpdateSetSqlConcat：片段（setSql/apply/last/first/around/UNION/join/rawTail/片段列）里
     * `?` 的来源按序登记进参数表——「每个 `?` 都有来源槽」，漏登记会让面板按位置回填整体错位。
     * sink 为 null 的调用形态（纯文本渲染，无参数收集）直接跳过。
     */
    private static void registerFragmentSources(List<String> sources,
                                                List<String> paramSink, List<ParamHint> hintSink) {
        if (sources == null || sources.isEmpty() || paramSink == null) {
            return;
        }
        for (String source : sources) {
            paramSink.add(source);
            if (hintSink != null) {
                hintSink.add(new ParamHint(null));
            }
        }
    }

    /** 渲染表名（关键字命中 → 按方言符号包裹）。 */
    private static String renderTableName(String tableName, Dialect dialect) {
        return dialect.quoteIfKeyword(tableName);
    }

    /**
     * 渲染参数值：字面量原样输出；占位符输出 {@code ?} 并收集来源与回填提示（M13）。
     */
    private static String renderValue(SqlValue value, List<String> paramSink, List<ParamHint> hintSink, Boolean quote) {
        if (value instanceof SqlValue.Literal l) {
            return l.getRaw();
        }
        if (value instanceof SqlValue.Placeholder p) {
            if (paramSink != null) {
                paramSink.add(p.getSource());
            }
            if (hintSink != null) {
                hintSink.add(new ParamHint(quote));
            }
            return "?";
        }
        throw new IllegalStateException("Unknown SqlValue type: " + value.getClass());
    }

    /**
     * 渲染 LIKE 模式：按 {@link LikePosition} 在字面量两侧拼接 {@code %}。
     * 占位符值输出 {@code ?} 并收集回填提示（M13：按位置拼 {@code %}、恒引号——LIKE 模式恒为字符串）。
     */
    private static String renderLike(SqlValue pattern, LikePosition position, List<String> paramSink, List<ParamHint> hintSink) {
        if (pattern instanceof SqlValue.Placeholder p) {
            if (paramSink != null) {
                paramSink.add(p.getSource());
            }
            if (hintSink != null) {
                hintSink.add(new ParamHint(true, position));
            }
            return "?";
        }
        SqlValue.Literal lit = (SqlValue.Literal) pattern;
        String v = lit.getUnquoted();
        String core = switch (position) {
            case ANYWHERE -> "%" + v + "%";
            case LEFT -> "%" + v;
            case RIGHT -> v + "%";
            case NONE -> v;
        };
        return "'" + core + "'";
    }

    /**
     * T-M29-03：分页子句占位符收集（`service.page` 的运行时 pageNum/pageSize）——
     * 数值参数无引号提示（与 {@link #renderValue} 的 quote 参数语义一致）。
     */
    private static void collectLimitValue(SqlValue value, List<String> paramSink, List<ParamHint> hintSink) {
        if (value instanceof SqlValue.Placeholder p) {
            if (paramSink != null) {
                paramSink.add(p.getSource());
            }
            if (hintSink != null) {
                hintSink.add(new ParamHint(false));
            }
        }
    }

    /**
     * 列值引号提示（M13，T-M23-03 修正）：按实体字段类型判定——
     * **除确定的数值类型（int/long 等）外，其他类型默认带引号**（用户确认：LocalDateTime/Date/Boolean
     * 等非数值类型原实现一律不加引号，回填后 SQL 无效）；列或类型未知返回 null（面板走原数字启发式）。
     * 判定逻辑见 {@link ParamQuote}（批量模板路径共用，T-M23-02）。
     */
    private static Boolean quoteHint(TableInfo table, ColumnRef column) {
        if (table == null) {
            return null;
        }
        for (ColumnInfo it : table.getColumns()) {
            if (it.getColumnName().equals(column.getColumnName())) {
                return ParamQuote.decide(it.getFieldType());
            }
        }
        return null;
    }
}
