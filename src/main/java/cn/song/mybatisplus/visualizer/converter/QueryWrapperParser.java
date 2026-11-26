package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.ConditionToggle;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlFragment;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.psi.PsiDialect;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.MySqlDialect;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;

import kotlin.Pair;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LambdaQueryWrapper → SelectStatement 转换器（M1 核心，F-01）。
 *
 * 遍历 [WrapperChain.calls]，按方法名分派构建 SELECT 语句的各个部分。
 * 纯逻辑、无 PSI 依赖，可独立单测。
 *
 * @param table        实体表信息
 * @param columnPrefix 列引用表别名前缀（MPJ 联表场景传主表别名 "t"；单表查询为 null）
 * @param dialect      方言（解析期 raw 拼接的保留字引用；默认读当前设置，测试环境兜底 MySQL）
 */
public final class QueryWrapperParser {

    /** 动态条件 toggle id 序号（M12，pre-order 递增，同一 parse 树内稳定）。 */
    private int toggleSeq = 0;

    /** setEntity 展开序号（D255）：同一条链多次 setEntity 时用于区分参数键前缀。 */
    private int setEntitySeq = 0;

    private final TableInfo table;
    private final String columnPrefix;
    private final Dialect dialect;

    /** 单表查询（无列前缀，方言取当前设置）。 */
    public QueryWrapperParser(TableInfo table) {
        this(table, null, PsiDialect.currentDialect());
    }

    /** 联表场景（指定列前缀，方言取当前设置）。 */
    public QueryWrapperParser(TableInfo table, String columnPrefix) {
        this(table, columnPrefix, PsiDialect.currentDialect());
    }

    /** 完整构造（子查询递归沿用同一方言）。 */
    public QueryWrapperParser(TableInfo table, String columnPrefix, Dialect dialect) {
        this.table = table;
        this.columnPrefix = columnPrefix;
        this.dialect = dialect;
    }

    /** 支持布尔条件重载的方法集合（eq/like/in/between 等）。 */
    private static final Set<String> CONDITION_METHODS = Set.of(
            "eq", "ne", "gt", "ge", "lt", "le",
            "eqOrIsNull",
            "like", "notLike", "likeLeft", "likeRight",
            "in", "notIn", "between", "notBetween",
            "isNull", "isNotNull",
            "allEq"
    );

    /** 一元条件方法（isNull/isNotNull 布尔重载形态为 [flag, col] 2 参，区别于 3 参二元方法）。 */
    private static final Set<String> UNARY_METHODS = Set.of("isNull", "isNotNull");

    /** 支持布尔条件重载的分组方法（and/or/nested/not/func 的 condition 首参）。 */
    private static final Set<String> GROUP_FLAG_METHODS = Set.of("and", "or", "nested", "not", "func");

    /** LIMIT 解析正则（(?i)limit\s+(\d+)(?:\s*,\s*(\d+))?）。 */
    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?i)limit\\s+(\\d+)(?:\\s*,\\s*(\\d+))?");

    /** M38.2：MySQL limit 的另一形态 "limit 5 OFFSET 10"（MP last 常用）。 */
    private static final Pattern OFFSET_PATTERN = Pattern.compile("(?i)offset\\s+(\\d+)");

    /** 占位 LIMIT 片段头（T-UpdateSetSqlConcat）：(?i)limit\s+(.+)$。 */
    private static final Pattern LIMIT_FRAGMENT_PATTERN = Pattern.compile("(?i)limit\\s+(.+)$");

    /** 占位 LIMIT 的 offset 形态（T-UpdateSetSqlConcat）：(?i)^(.+?)\s+offset\s+(.+)$。 */
    private static final Pattern LIMIT_FRAGMENT_OFFSET_PATTERN = Pattern.compile("(?i)^(.+?)\\s+offset\\s+(.+)$");

    /** 占位 LIMIT 数字 token（T-UpdateSetSqlConcat）：\d+。 */
    private static final Pattern LIMIT_DIGIT_PATTERN = Pattern.compile("\\d+");

    /**
     * 解析调用链为 SELECT 语句模型。
     *
     * @param chain PSI 解析产物
     * @return 可直接交给 SqlGenerator 的语句模型
     */
    public SelectStatement parse(WrapperChain chain) {
        toggleSeq = 0;
        setEntitySeq = 0;
        return parseInternal(chain);
    }

    /** 递归解析体：顶层 parse 重置 toggleSeq，嵌套调用走本方法（id 延续同一序列）。 */
    private SelectStatement parseInternal(WrapperChain chain) {
        List<ColumnRef> selectColumns = new ArrayList<>();
        List<Condition> conditions = new ArrayList<>();
        List<ColumnRef> groupBy = new ArrayList<>();
        Condition having = null;
        List<OrderByItem> orderBy = new ArrayList<>();
        LimitClause limit = null;
        String rawFirst = null;
        List<String> rawFirstParams = List.of();
        String comment = null;
        boolean distinct = false;
        boolean orPending = false;

        for (WrapperCall rawCall : chain.getCalls()) {
            // IfExists / IfNotEmpty 条件族：剥离后缀归一化为普通方法名
            String rawName = rawCall.getMethodName();
            String effectiveName = rawName;
            if (rawName.endsWith("IfExists")) {
                effectiveName = rawName.substring(0, rawName.length() - "IfExists".length());
            } else if (rawName.endsWith("IfNotEmpty")) {
                effectiveName = rawName.substring(0, rawName.length() - "IfNotEmpty".length());
            }
            WrapperCall effectiveCall = new WrapperCall(effectiveName, rawCall.getArgs());

            // 布尔条件重载（P02/M12）：eq(boolean, col, val) 首参为布尔标志 →
            // 剥离标志并固化为动态条件 toggle 元数据（不再丢弃 false 条件，改由生成层默认排除）
            FlagAnalysis analysis = analyzeFlag(effectiveCall);
            WrapperCall call = analysis != null ? analysis.call() : effectiveCall;
            if (call.getArgs().isEmpty() && CONDITION_METHODS.contains(call.getMethodName())) continue;

            switch (call.getMethodName()) {
                // ---------- SELECT 列 ----------
                case "select" -> {
                    WrapperArg.EntityRefArg classRef = argAt(call.getArgs(), 0) instanceof WrapperArg.EntityRefArg e ? e : null;
                    WrapperArg.PredicateArg predicate = null;
                    for (WrapperArg a : call.getArgs()) {
                        if (a instanceof WrapperArg.PredicateArg p) { predicate = p; break; }
                    }
                    if (classRef != null && predicate != null) {
                        selectColumns.addAll(selectColumnsByPredicate(predicate));
                    } else {
                        // T-UpdateSetSqlConcat：字符串表达式按片段解析（运行期段写 `?` 并带来源，
                        // 在 SELECT 列表渲染点按序登记；列引用（Lambda）不受影响）
                        selectColumns.addAll(selectColumnArgs(call));
                    }
                }
                case "selectFilter" -> {
                    WrapperArg.PredicateArg predicate = null;
                    for (WrapperArg a : call.getArgs()) {
                        if (a instanceof WrapperArg.PredicateArg p) { predicate = p; break; }
                    }
                    selectColumns.addAll(predicate != null ? selectColumnsByPredicate(predicate) : List.of());
                }
                case "selectMax" -> selectColumns.addAll(parseAggregate(call, "MAX"));
                case "selectSum" -> selectColumns.addAll(parseAggregate(call, "SUM"));
                case "selectMin" -> selectColumns.addAll(parseAggregate(call, "MIN"));
                case "selectAvg" -> selectColumns.addAll(parseAggregate(call, "AVG"));
                case "selectLen" -> selectColumns.addAll(parseAggregate(call, "LENGTH"));
                case "selectCount" -> selectColumns.addAll(parseAggregate(call, "COUNT"));
                case "selectFunc" -> selectColumns.addAll(parseSelectFunc(call));
                case "distinct" -> distinct = true;

                // ---------- 二元比较 ----------
                case "eq" -> orPending = addConditionFlagged(conditions, flagged(simple(call, "="), analysis), orPending);
                case "eqOrIsNull" -> orPending = addConditionFlagged(conditions, flagged(eqOrIsNull(call), analysis), orPending);
                // ne 渲染 <>（M38.2 语义对照矩阵实证：MP SqlKeyword.NE = "<>"，原 "!=" 与 MP 不一致）
                case "ne" -> orPending = addConditionFlagged(conditions, flagged(simple(call, "<>"), analysis), orPending);
                case "gt" -> orPending = addConditionFlagged(conditions, flagged(simple(call, ">"), analysis), orPending);
                case "ge" -> orPending = addConditionFlagged(conditions, flagged(simple(call, ">="), analysis), orPending);
                case "lt" -> orPending = addConditionFlagged(conditions, flagged(simple(call, "<"), analysis), orPending);
                case "le" -> orPending = addConditionFlagged(conditions, flagged(simple(call, "<="), analysis), orPending);
                case "eqSql" -> orPending = addConditionFlagged(conditions, flagged(compareSql(call, "="), analysis), orPending);
                case "gtSql" -> orPending = addConditionFlagged(conditions, flagged(compareSql(call, ">"), analysis), orPending);
                case "geSql" -> orPending = addConditionFlagged(conditions, flagged(compareSql(call, ">="), analysis), orPending);
                case "ltSql" -> orPending = addConditionFlagged(conditions, flagged(compareSql(call, "<"), analysis), orPending);
                case "leSql" -> orPending = addConditionFlagged(conditions, flagged(compareSql(call, "<="), analysis), orPending);
                case "allEq" -> orPending = addConditionFlagged(conditions, flagged(parseAllEq(call), analysis), orPending);
                case "setEntity" -> {
                    for (Condition cond : parseSetEntity(call)) {
                        orPending = addConditionFlagged(conditions, flagged(cond, analysis), orPending);
                    }
                }

                // ---------- 模糊 ----------
                case "like" -> orPending = addConditionFlagged(conditions, flagged(like(call, false, LikePosition.ANYWHERE), analysis), orPending);
                case "notLike" -> orPending = addConditionFlagged(conditions, flagged(like(call, true, LikePosition.ANYWHERE), analysis), orPending);
                case "likeLeft" -> orPending = addConditionFlagged(conditions, flagged(like(call, false, LikePosition.LEFT), analysis), orPending);
                case "likeRight" -> orPending = addConditionFlagged(conditions, flagged(like(call, false, LikePosition.RIGHT), analysis), orPending);
                case "notLikeLeft" -> orPending = addConditionFlagged(conditions, flagged(like(call, true, LikePosition.LEFT), analysis), orPending);
                case "notLikeRight" -> orPending = addConditionFlagged(conditions, flagged(like(call, true, LikePosition.RIGHT), analysis), orPending);

                // ---------- 范围 ----------
                case "in" -> orPending = addConditionFlagged(conditions, flagged(inList(call, false), analysis), orPending);
                case "notIn" -> orPending = addConditionFlagged(conditions, flagged(inList(call, true), analysis), orPending);
                case "between" -> orPending = addConditionFlagged(conditions, flagged(between(call, false), analysis), orPending);
                case "notBetween" -> orPending = addConditionFlagged(conditions, flagged(between(call, true), analysis), orPending);
                case "inSql" -> orPending = addConditionFlagged(conditions, flagged(inSql(call, false), analysis), orPending);
                case "notInSql" -> orPending = addConditionFlagged(conditions, flagged(inSql(call, true), analysis), orPending);

                // ---------- 空值 ----------
                case "isNull" -> orPending = addConditionFlagged(conditions, flagged(unary(call, "IS NULL"), analysis), orPending);
                case "isNotNull" -> orPending = addConditionFlagged(conditions, flagged(unary(call, "IS NOT NULL"), analysis), orPending);

                // ---------- 自定义片段 ----------
                case "apply" -> orPending = addConditionFlagged(conditions, parseApply(call), orPending);
                case "applyFunc" -> orPending = addConditionFlagged(conditions, flagged(parseApplyFunc(call), analysis), orPending);
                case "exists" -> orPending = addConditionFlagged(conditions, flagged(parseExists(call, false), analysis), orPending);
                case "notExists" -> orPending = addConditionFlagged(conditions, flagged(parseExists(call, true), analysis), orPending);

                // ---------- 结构 ----------
                case "groupBy", "groupByStr" -> groupBy.addAll(columnArgs(call));
                case "having" -> having = parseHaving(call);
                case "orderByAsc" -> orderBy.addAll(orderItems(call, true));
                case "orderByDesc" -> orderBy.addAll(orderItems(call, false));
                case "orderByStr" -> orderBy.addAll(orderByStrItems(call));
                case "orderBy" -> {
                    List<WrapperArg> orderArgs = call.getArgs();
                    SqlValue condValue = WrapperConverterKt.asValue(argAt(orderArgs, 0));
                    if (orderArgs.size() >= 3 && condValue instanceof SqlValue.Literal lit
                            && ("true".equals(lit.getUnquoted()) || "false".equals(lit.getUnquoted()))) {
                        orderArgs = orderArgs.size() > 1 ? orderArgs.subList(1, orderArgs.size()) : java.util.List.of();
                    }
                    SqlValue ascValue = WrapperConverterKt.asValue(argAt(orderArgs, 0));
                    boolean isAscLiteral = ascValue instanceof SqlValue.Literal lit2
                            && ("true".equals(lit2.getUnquoted()) || "false".equals(lit2.getUnquoted()));
                    WrapperCall columnsCall;
                    if (isAscLiteral) {
                        columnsCall = new WrapperCall(call.getMethodName(), orderArgs.size() > 1 ? orderArgs.subList(1, orderArgs.size()) : java.util.List.of());
                    } else {
                        columnsCall = new WrapperCall(call.getMethodName(), orderArgs);
                    }
                    boolean ascFlag = isAscLiteral && "true".equals(((SqlValue.Literal) ascValue).getUnquoted());
                    orderBy.addAll(orderItems(columnsCall, ascFlag));
                }
                case "last" -> limit = parseLimit(call);
                // first / comment（官方 API，M15 增补）：SQL 最前片段与注释原样透传
                // T-UpdateSetSqlConcat：片段位取 asSqlFragment；first 的来源随片段登记（SELECT 之前渲染）
                // ——comment 的 `?` 落在 `/* ... */` 注释里，placeholderPositions 不计，故无来源
                case "first" -> {
                    SqlFragment firstFragment = WrapperConverterKt.asSqlFragment(argAt(call.getArgs(), 0));
                    if (firstFragment != null) {
                        rawFirst = firstFragment.getText();
                        rawFirstParams = firstFragment.getParams();
                    }
                }
                case "comment" -> {
                    SqlFragment commentFragment = WrapperConverterKt.asSqlFragment(argAt(call.getArgs(), 0));
                    if (commentFragment != null) comment = commentFragment.getText();
                }

                // ---------- 逻辑分组 ----------
                case "or" -> {
                    if (call.getArgs().isEmpty()) {
                        orPending = true;
                    } else {
                        // M38.2：or(Consumer) = 外层 OR 连接 + 组内恒 AND（MP 语义）。强制 orPending
                        // 让组与前一条件组成 OR 分组（addCondition 的 OR 语义），无前条件时组直接落位。
                        // 原实现把 OR 用作组内连接词 → or(w -> w.eq(2).eq(3)) 错渲染为 `AND (2 OR 3)`（内外颠倒）。
                        Condition group = parseGroupFlagged(call);
                        if (group != null) {
                            orPending = addConditionFlagged(conditions, group, true);
                        }
                    }
                }
                case "and" -> orPending = addConditionFlagged(conditions, parseGroupFlagged(call), orPending);
                case "nested" -> orPending = addConditionFlagged(conditions, parseGroupFlagged(call), orPending);
                case "not" -> orPending = addConditionFlagged(conditions, parseNotFlagged(call), orPending);
                case "func" -> {
                    FlagAnalysis funcAnalysis = stripGroupFlag(call);
                    WrapperCall effective = funcAnalysis != null ? funcAnalysis.call() : call;
                    WrapperArg.NestedArg nested = argAt(effective.getArgs(), 0) instanceof WrapperArg.NestedArg n ? n : null;
                    if (nested != null) {
                        List<Condition> children = parseInternal(nested.getChain()).getConditions();
                        if (funcAnalysis != null) {
                            if (!children.isEmpty()) {
                                orPending = addConditionFlagged(conditions,
                                        flagged(new Condition.Group(Connector.AND, children, null, false), funcAnalysis), orPending);
                            }
                        } else {
                            conditions.addAll(children);
                        }
                    }
                }

                // 其他方法（not/嵌套条件等）暂忽略，保证健壮性
                default -> { /* 忽略 */ }
            }
        }

        return new SelectStatement(
                table,
                selectColumns,
                null,
                List.of(),
                distinct,
                conditions,
                groupBy,
                having,
                orderBy,
                limit,
                rawFirst,
                null,
                rawFirstParams,
                List.of(),
                comment,
                List.of(),
                null,
                null
        );
    }

    /**
     * 解析聚合列（selectCount/selectSum/selectMin/selectMax/selectAvg/selectLen，官方 API）。
     *
     * @param call     聚合调用
     * @param function SQL 聚合函数名（MAX/SUM/...）
     * @return 聚合列（带别名时返回 AS 别名）
     */
    private List<ColumnRef> parseAggregate(WrapperCall call, String function) {
        List<ColumnRef> cols = columnArgs(call);
        ColumnRef column = cols.isEmpty() ? null : cols.get(0);
        if (column == null) return List.of();
        String alias = null;
        WrapperArg arg1 = argAt(call.getArgs(), 1);
        if (arg1 instanceof WrapperArg.AliasArg aa) alias = aa.getPropertyName();
        else if (arg1 instanceof WrapperArg.ValueArg va) {
            SqlValue v = va.getValue();
            if (v instanceof SqlValue.Literal lit) alias = lit.getUnquoted();
        }
        if (alias != null) {
            return List.of(new ColumnRef(column.getTableAlias(), column.getColumnName(), alias, function,
                    column.getRawExpr(), column.getEntityClass(), column.getType()));
        }
        return List.of(new ColumnRef(column.getTableAlias(), column.getColumnName(), column.getAlias(), function,
                column.getRawExpr(), column.getEntityClass(), column.getType()));
    }

    /**
     * 解析 selectFunc（模板 + FuncArg）。
     *
     * @param call selectFunc 调用
     * @return 完整聚合列
     */
    private List<ColumnRef> parseSelectFunc(WrapperCall call) {
        SqlValue first = WrapperConverterKt.asValue(argAt(call.getArgs(), 0));
        WrapperArg.FuncArg func = argAt(call.getArgs(), 1) instanceof WrapperArg.FuncArg fa ? fa : null;
        if (func == null) return List.of();
        String alias = null;
        WrapperArg arg2 = argAt(call.getArgs(), 2);
        if (arg2 instanceof WrapperArg.AliasArg aa) alias = aa.getPropertyName();
        else if (arg2 instanceof WrapperArg.ValueArg va) {
            SqlValue v = va.getValue();
            if (v instanceof SqlValue.Literal lit) alias = lit.getUnquoted();
        }
        // 枚举形态（T-M21-06）：SUM(t.col) [AS alias]
        if (first instanceof SqlValue.Placeholder ph) {
            String fn = enumFunctionOf(ph.getSource());
            if (fn == null) return List.of();
            ColumnRef column = func.getColumns().isEmpty() ? null : func.getColumns().get(0);
            if (column == null) return List.of();
            ColumnRef withPrefix = columnPrefix != null ? WrapperConverterKt.withTableAlias(column, columnPrefix) : column;
            String expr = fn + "(" + withPrefix.qualifiedName(dialect) + ")";
            return List.of(new ColumnRef(null, expr, alias, null, expr, null, null));
        }
        // T-UpdateSetSqlConcat：片段位取 asSqlFragment（模板含运行期段时静态段保留、运行期段写 `?`）
        SqlFragment templateFragment = WrapperConverterKt.asSqlFragment(argAt(call.getArgs(), 0));
        if (templateFragment == null) return List.of();
        String template = templateFragment.getText();
        String expr = template;
        for (ColumnRef col : func.getColumns()) {
            if (!expr.contains("%s")) break;
            ColumnRef withPrefix = columnPrefix != null ? WrapperConverterKt.withTableAlias(col, columnPrefix) : col;
            expr = expr.replaceFirst("%s", withPrefix.qualifiedName(dialect));
        }
        int idx = 0;
        for (SqlValue value : func.getValues()) {
            expr = expr.replace("{" + idx + "}", renderFuncValue(value));
            idx++;
        }
        if (expr.contains("%s")) return List.of();
        return List.of(new ColumnRef(null, expr, alias, null, expr, null, null));
    }

    /**
     * MPJ 内置函数枚举名 → SQL 函数名映射（未知枚举返回 null）。
     *
     * @param source 枚举引用占位（形如 "DefaultFuncEnum.SUM" / "SUM"）
     * @return SQL 函数名；未识别返回 null
     */
    private static String enumFunctionOf(String source) {
        int dot = source.lastIndexOf('.');
        String enumName = (dot >= 0 ? source.substring(dot + 1) : source).trim().toUpperCase();
        return switch (enumName) {
            case "SUM" -> "SUM";
            case "COUNT" -> "COUNT";
            case "MAX" -> "MAX";
            case "MIN" -> "MIN";
            case "AVG" -> "AVG";
            case "LEN" -> "LENGTH";
            case "CONCAT" -> "CONCAT";
            default -> null;
        };
    }

    /** selectFunc 绑定参数渲染：字面量原样（raw），运行时值渲染为 `?`。 */
    private static String renderFuncValue(SqlValue value) {
        if (value instanceof SqlValue.Literal lit) return lit.getRaw();
        if (value instanceof SqlValue.Placeholder) return "?";
        return "";
    }

    /** 二元比较：`column op value`；列对列与子查询形态。 */
    private Condition simple(WrapperCall call, String op) {
        ColumnRef column = argAsColumn(argAt(call.getArgs(), 0));
        if (column == null) return null;
        if (call.getArgs().size() >= 3 && argAt(call.getArgs(), 2) instanceof WrapperArg.NestedArg) {
            return subQueryCondition(call, op);
        }
        WrapperArg right = argAt(call.getArgs(), 1);
        if (right instanceof WrapperArg.ColumnArg ca) {
            return new Condition.ColumnComparison(column, ca.getColumn(), null);
        }
        SqlValue value = argAsValue(argAt(call.getArgs(), 1));
        if (value == null) return null;
        return new Condition.Simple(column, op, value, null);
    }

    /** eqOrIsNull：值静态求值为 null 时 `col IS NULL`，否则 `col = 值`。 */
    private Condition eqOrIsNull(WrapperCall call) {
        ColumnRef column = argAsColumn(argAt(call.getArgs(), 0));
        if (column == null) return null;
        SqlValue value = argAsValue(argAt(call.getArgs(), 1));
        if (value == null) return null;
        if (value instanceof SqlValue.Literal lit && "NULL".equals(lit.getRaw())) {
            return new Condition.Unary(column, "IS NULL", null);
        }
        return new Condition.Simple(column, "=", value, null);
    }

    /** allEq：Map 键值对展开为多条 `col = ?` AND 连接。 */
    private Condition parseAllEq(WrapperCall call) {
        WrapperArg.MapArg mapArg = null;
        for (WrapperArg a : call.getArgs()) {
            if (a instanceof WrapperArg.MapArg ma) { mapArg = ma; break; }
        }
        if (mapArg == null) return null;
        if (mapArg.getEntries().isEmpty()) {
            return new Condition.Raw("<Map 等值条件: col = ? AND col = ? ...>", null);
        }
        List<Condition> children = new ArrayList<>();
        for (Pair<ColumnRef, SqlValue> entry : mapArg.getEntries()) {
            ColumnRef column = entry.getFirst();
            SqlValue value = entry.getSecond();
            ColumnRef qualified = columnPrefix != null ? WrapperConverterKt.withTableAlias(column, columnPrefix) : column;
            if (mapArg.getNull2IsNull() && value instanceof SqlValue.Literal lit && "NULL".equals(lit.getRaw())) {
                children.add(new Condition.Unary(qualified, "IS NULL", null));
            } else {
                children.add(new Condition.Simple(qualified, "=", value, null));
            }
        }
        if (children.isEmpty()) return null;
        return new Condition.Group(Connector.AND, children, null, false);
    }

    /**
     * setEntity：实体非 null 字段追加 where 等值条件（静态近似：全列 {@code col = ?}）。
     *
     * <p>D255：每列的占位符必须带<b>唯一参数键</b>（{@code query.id}、{@code query.password}…），
     * 不能共用匿名 {@code "?"}。预览参数面板按 source 建输入框、按 source 取值回填——共用 {@code "?"}
     * 会让 15 个输入框读写同一个 {@code paramValues} 键：改一个框全列跟着变，清除只写回第一个框。
     * 前缀取实参表达式（{@code setEntity(query)} → {@code query}），不可命名时回退 {@code entity}；
     * 同一条链多次 setEntity 用序号区分。</p>
     */
    private List<Condition> parseSetEntity(WrapperCall call) {
        String prefix = ParamSourceNaming.uniquePrefix(
                ParamSourceNaming.prefixOf(call.getArgs()), ++setEntitySeq);
        List<Condition> result = new ArrayList<>();
        for (ColumnInfo col : table.getColumns()) {
            ColumnRef column = new ColumnRef(null, col.getColumnName(), null, null, null, null, null);
            ColumnRef qualified = columnPrefix != null ? WrapperConverterKt.withTableAlias(column, columnPrefix) : column;
            result.add(new Condition.Simple(qualified, "=",
                    new SqlValue.Placeholder(ParamSourceNaming.source(prefix, col.getColumnName())), null));
        }
        return result;
    }

    /** 子查询条件：`col op (SELECT ...)`。 */
    private Condition subQueryCondition(WrapperCall call, String op) {
        ColumnRef column = argAsColumn(argAt(call.getArgs(), 0));
        if (column == null) return null;
        WrapperArg.EntityRefArg subTable = argAt(call.getArgs(), 1) instanceof WrapperArg.EntityRefArg e ? e : null;
        if (subTable == null) return null;
        WrapperArg.NestedArg nested = argAt(call.getArgs(), 2) instanceof WrapperArg.NestedArg n ? n : null;
        if (nested == null) return null;
        SelectStatement subStatement = new QueryWrapperParser(subTable.getTableInfo(), null, dialect).parse(nested.getChain());
        String subSql = SqlGenerator.generate(subStatement, true, MySqlDialect.INSTANCE, null);
        return new Condition.Raw(column.qualifiedName(dialect) + " " + op + " (" + subSql + ")", null);
    }

    /** LIKE 条件。 */
    private Condition like(WrapperCall call, boolean negated, LikePosition position) {
        ColumnRef column = argAsColumn(argAt(call.getArgs(), 0));
        if (column == null) return null;
        SqlValue value = argAsValue(argAt(call.getArgs(), 1));
        if (value == null) return null;
        return new Condition.Like(column, negated, value, position, null);
    }

    /** IN 列表条件；子查询形态 `column IN (SELECT ...)`。 */
    private Condition inList(WrapperCall call, boolean negated) {
        ColumnRef column = argAsColumn(argAt(call.getArgs(), 0));
        if (column == null) return null;
        if (call.getArgs().size() >= 3 && argAt(call.getArgs(), 2) instanceof WrapperArg.NestedArg) {
            return subQueryCondition(call, negated ? "NOT IN" : "IN");
        }
        List<SqlValue> values = new ArrayList<>();
        List<WrapperArg> callArgsTail = call.getArgs().size() > 1 ? call.getArgs().subList(1, call.getArgs().size()) : java.util.Collections.emptyList();
        for (WrapperArg a : callArgsTail) {
            SqlValue v = argAsValue(a);
            if (v != null) values.add(v);
        }
        if (values.isEmpty()) return null;
        return new Condition.In(column, negated, values, null);
    }

    /** BETWEEN 条件。 */
    private Condition between(WrapperCall call, boolean negated) {
        ColumnRef column = argAsColumn(argAt(call.getArgs(), 0));
        if (column == null) return null;
        SqlValue lo = argAsValue(argAt(call.getArgs(), 1));
        if (lo == null) return null;
        SqlValue hi = argAsValue(argAt(call.getArgs(), 2));
        if (hi == null) return null;
        return new Condition.Between(column, negated, lo, hi, null);
    }

    /** IN SQL 片段条件（inSql / notInSql）。 */
    private Condition inSql(WrapperCall call, boolean negated) {
        ColumnRef column = argAsColumn(argAt(call.getArgs(), 0));
        if (column == null) return null;
        // T-UpdateSetSqlConcat：片段位取 asSqlFragment（`"SELECT id FROM t WHERE x = " + x` → `... x = ?`）
        SqlFragment frag = WrapperConverterKt.asSqlFragment(argAt(call.getArgs(), 1));
        if (frag == null) return null;
        String op = negated ? "NOT IN" : "IN";
        return new Condition.Raw(column.qualifiedName(dialect) + " " + op + " (" + frag.getText() + ")", null, frag.getParams());
    }

    /** 比较 SQL 片段条件（eqSql/gtSql/geSql/ltSql/leSql）。 */
    private Condition compareSql(WrapperCall call, String op) {
        ColumnRef column = argAsColumn(argAt(call.getArgs(), 0));
        if (column == null) return null;
        SqlFragment frag = WrapperConverterKt.asSqlFragment(argAt(call.getArgs(), 1));
        if (frag == null) return null;
        return new Condition.Raw(column.qualifiedName(dialect) + " " + op + " (" + frag.getText() + ")", null, frag.getParams());
    }

    /** NOT 嵌套条件（not(consumer)）。 */
    private Condition parseNot(WrapperCall call) {
        WrapperArg.NestedArg nested = argAt(call.getArgs(), 0) instanceof WrapperArg.NestedArg n ? n : null;
        if (nested == null) return null;
        List<Condition> children = parseInternal(nested.getChain()).getConditions();
        if (children.isEmpty()) return null;
        return new Condition.Group(Connector.AND, children, null, true);
    }

    /** 一元条件：IS NULL / IS NOT NULL。 */
    private Condition unary(WrapperCall call, String op) {
        ColumnRef column = argAsColumn(argAt(call.getArgs(), 0));
        if (column == null) return null;
        return new Condition.Unary(column, op, null);
    }

    /** 逻辑分组：and(lambda) / or(lambda) / and(wrapper) / or(wrapper)。 */
    private Condition parseGroup(WrapperCall call) {
        // M38.2（语义对照矩阵实证）：组内连接词恒为 AND——lambda 内条件默认 AND 连接，lambda 内
        // 显式无参 or() 由嵌套解析自身的 orPending 产生 OR 子组（下方展平保留）。组与前条件的
        // **外层**连接词由调用方的 orPending 表达：or(Consumer) → OR，and/nested → AND。
        // 原实现把方法名 or 用作组内连接词 → or(w -> w.eq(2).eq(3)) 错渲染为 `AND (2 OR 3)`（内外颠倒）。
        Connector connector = Connector.AND;
        WrapperArg.NestedArg nested = argAt(call.getArgs(), 0) instanceof WrapperArg.NestedArg n ? n : null;
        if (nested == null) return null;
        List<Condition> children = parseInternal(nested.getChain()).getConditions();
        if (children.isEmpty()) return null;
        if (children.size() == 1 && children.get(0) instanceof Condition.Group) return children.get(0);
        return new Condition.Group(connector, children, null, false);
    }

    /** 分组方法布尔条件重载（and/or/nested/not/func 的 condition 首参）。 */
    private Condition parseGroupFlagged(WrapperCall call) {
        FlagAnalysis analysis = stripGroupFlag(call);
        WrapperCall effective = analysis != null ? analysis.call() : call;
        Condition group = parseGroup(effective);
        if (group == null) return null;
        return analysis != null ? flagged(group, analysis) : group;
    }

    /** not(condition, w -> ...) 布尔重载（与 parseGroupFlagged 同模式）。 */
    private Condition parseNotFlagged(WrapperCall call) {
        FlagAnalysis analysis = stripGroupFlag(call);
        WrapperCall effective = analysis != null ? analysis.call() : call;
        Condition group = parseNot(effective);
        if (group == null) return null;
        return analysis != null ? flagged(group, analysis) : group;
    }

    /** 剥离分组方法的布尔条件首参。 */
    private FlagAnalysis stripGroupFlag(WrapperCall call) {
        if (!GROUP_FLAG_METHODS.contains(call.getMethodName())) return null;
        WrapperArg first = argAt(call.getArgs(), 0);
        if (first == null) return null;
        if (first instanceof WrapperArg.NestedArg) return null;
        SqlValue firstValue = WrapperConverterKt.asValue(first);
        if (firstValue == null) return null;
        String flagText;
        if (firstValue instanceof SqlValue.Literal lit) flagText = lit.getUnquoted();
        else if (firstValue instanceof SqlValue.Placeholder ph) flagText = ph.getSource();
        else flagText = null;
        if (firstValue instanceof SqlValue.Literal && !"true".equals(flagText) && !"false".equals(flagText)) return null;
        boolean defaultEnabled = !(firstValue instanceof SqlValue.Literal lit && "false".equals(lit.getUnquoted()));
        return new FlagAnalysis(new WrapperCall(call.getMethodName(), call.getArgs().size() > 1 ? call.getArgs().subList(1, call.getArgs().size()) : java.util.List.of()),
                flagText, defaultEnabled);
    }

    /** HAVING：嵌套链全部条件组成 AND 组。 */
    private Condition parseHaving(WrapperCall call) {
        // M38.2（语义对照矩阵实证）：字符串版 having("count(*) > 3")（MP 官方 API）——
        // 原实现只认 lambda 嵌套形态，字符串片段被静默丢弃。按原生片段透传渲染。
        WrapperArg firstArg = argAt(call.getArgs(), 0);
        if (firstArg instanceof WrapperArg.ValueArg) {
            // T-UpdateSetSqlConcat：片段位取 asSqlFragment（`having("count(*) > " + n)` → `count(*) > ?`）
            SqlFragment frag = WrapperConverterKt.asSqlFragment(firstArg);
            if (frag != null && !frag.getText().isBlank()) {
                return new Condition.Raw(frag.getText(), null, frag.getParams());
            }
        }
        WrapperArg.NestedArg nested = argAt(call.getArgs(), 0) instanceof WrapperArg.NestedArg n ? n : null;
        if (nested == null) return null;
        List<Condition> conditions = parseInternal(nested.getChain()).getConditions();
        if (conditions.isEmpty()) return null;
        return conditions.size() == 1 ? conditions.get(0) : new Condition.Group(Connector.AND, conditions, null, false);
    }

    /** ORDER BY 项列表；支持 orderByAsc("alias", Entity::getX) 字符串别名前缀。 */
    private List<OrderByItem> orderItems(WrapperCall call, boolean asc) {
        WrapperArg first = argAt(call.getArgs(), 0);
        if (first instanceof WrapperArg.ValueArg va && call.getArgs().size() >= 2) {
            SqlValue v = va.getValue();
            String alias = v instanceof SqlValue.Literal lit ? lit.getUnquoted() : null;
            if (alias != null && !alias.isEmpty()) {
                List<OrderByItem> result = new ArrayList<>();
                List<WrapperArg> callArgsTail = call.getArgs().size() > 1 ? call.getArgs().subList(1, call.getArgs().size()) : java.util.Collections.emptyList();
        for (WrapperArg a : callArgsTail) {
                    if (a instanceof WrapperArg.ColumnArg ca) {
                        result.add(new OrderByItem(WrapperConverterKt.withTableAlias(ca.getColumn(), alias), asc));
                    }
                }
                return result;
            }
        }
        List<OrderByItem> result = new ArrayList<>();
        for (ColumnRef col : columnArgs(call)) result.add(new OrderByItem(col, asc));
        return result;
    }

    /** orderByStr：字符串列原样、默认 ASC。 */
    private List<OrderByItem> orderByStrItems(WrapperCall call) {
        List<OrderByItem> result = new ArrayList<>();
        for (ColumnRef col : columnArgs(call)) result.add(new OrderByItem(col, true));
        return result;
    }

    /**
     * LIMIT 解析：last("limit 10") / last("limit 5, 10")（MP 语义：offset, count）。
     *
     * <p>T-UpdateSetSqlConcat：运行期段渲染成 {@code ?} 后按<b>占位形态</b>建模（{@code last("LIMIT " + n)}）——
     * LimitClause 本就持有 SqlValue，生成器的 limit 渲染点会按序登记占位符来源。
     */
    private LimitClause parseLimit(WrapperCall call) {
        SqlValue v = argAsValue(argAt(call.getArgs(), 0));
        String literalText = v instanceof SqlValue.Literal lit ? lit.getUnquoted() : null;
        if (literalText != null) return parseLimitText(literalText);
        SqlFragment fragment = WrapperConverterKt.asSqlFragment(argAt(call.getArgs(), 0));
        if (fragment == null) return null;
        return parseLimitFragment(fragment);
    }

    /** 纯文本 LIMIT（既有口径：数字 / `5, 10` / `5 OFFSET 10`）。 */
    private LimitClause parseLimitText(String text) {
        Matcher m = LIMIT_PATTERN.matcher(text);
        if (!m.find()) return null;
        Long first = parseLongOrNull(m.group(1));
        if (first == null) return null;
        Long second = parseLongOrNull(m.group(2));
        if (second != null) return new LimitClause(first, second);
        // M38.2（语义对照矩阵实证）：MySQL 另一形态 "limit 5 OFFSET 10"（MP last 常用）——
        // 原正则不认 OFFSET，OFFSET 被静默丢弃
        Matcher om = OFFSET_PATTERN.matcher(text);
        Long offset = om.find() ? parseLongOrNull(om.group(1)) : null;
        return offset != null ? new LimitClause(offset, first) : new LimitClause(null, first);
    }

    /**
     * 占位 LIMIT（T-UpdateSetSqlConcat）：{@code limit ?} / {@code limit ?, ?} / {@code limit ? offset ?}，
     * 也接受数字与占位混排（{@code limit 5, ?}）。占位符按片段来源顺序取名。
     */
    private LimitClause parseLimitFragment(SqlFragment fragment) {
        Matcher m = LIMIT_FRAGMENT_PATTERN.matcher(fragment.getText().trim());
        if (!m.find()) return null;
        String body = m.group(1).trim();
        int[] paramIndex = {0};
        Matcher om = LIMIT_FRAGMENT_OFFSET_PATTERN.matcher(body);
        if (om.find()) {
            SqlValue count = limitValueOf(om.group(1).trim(), fragment, paramIndex);
            if (count == null) return null;
            SqlValue offset = limitValueOf(om.group(2).trim(), fragment, paramIndex);
            if (offset == null) return null;
            return new LimitClause(offset, count);
        }
        String[] parts = body.split(",");
        if (parts.length == 1) {
            SqlValue count = limitValueOf(parts[0].trim(), fragment, paramIndex);
            return count != null ? new LimitClause(null, count) : null;
        }
        if (parts.length == 2) {
            SqlValue offset = limitValueOf(parts[0].trim(), fragment, paramIndex);
            if (offset == null) return null;
            SqlValue count = limitValueOf(parts[1].trim(), fragment, paramIndex);
            if (count == null) return null;
            return new LimitClause(offset, count);
        }
        return null;
    }

    /** LIMIT 片段 token 取值：`?` 按来源顺序取占位来源、纯数字为字面量、其余无效。 */
    private SqlValue limitValueOf(String token, SqlFragment fragment, int[] paramIndex) {
        if ("?".equals(token)) {
            List<String> params = fragment.getParams();
            String source = paramIndex[0] < params.size() ? params.get(paramIndex[0]) : WrapperConverterKt.UNKNOWN_PARAM;
            paramIndex[0]++;
            return new SqlValue.Placeholder(source);
        }
        if (LIMIT_DIGIT_PATTERN.matcher(token).matches()) {
            return new SqlValue.Literal(token);
        }
        return null;
    }

    /**
     * 布尔条件重载分析结果：剥离布尔标志后的调用 + 动态条件 toggle 预置信息。
     *
     * @param call          剥离布尔标志后的调用
     * @param flagSource    布尔条件实参源码文本（tooltip）；null 表示非布尔重载
     * @param defaultEnabled 默认勾选态（字面量 true→true、false→false；运行时布尔→true）
     */
    private record FlagAnalysis(WrapperCall call, String flagSource, boolean defaultEnabled) {
    }

    /**
     * 分析条件调用的布尔条件重载（P02/M12）：首参为布尔标志 → 剥离并固化动态条件元数据。
     *
     * 标志判定：一元方法（isNull/isNotNull）标志形态为 [flag, col]（2 参）；
     * 其余为 [flag, col, val...]（≥3 参）。首参为列引用时恒非标志。
     *
     * @param call 条件调用
     * @return 分析结果；非布尔重载（普通条件/其他方法）返回 null
     */
    private FlagAnalysis analyzeFlag(WrapperCall call) {
        if (!CONDITION_METHODS.contains(call.getMethodName())) return null;
        WrapperArg first = argAt(call.getArgs(), 0);
        if (first instanceof WrapperArg.ColumnArg || first instanceof WrapperArg.NestedArg
                || first instanceof WrapperArg.OnConditionArg) return null;
        SqlValue firstValue = WrapperConverterKt.asValue(first);
        if (firstValue == null) return null;
        boolean flaggedArity;
        if (UNARY_METHODS.contains(call.getMethodName())) {
            flaggedArity = call.getArgs().size() == 2;
        } else if ("allEq".equals(call.getMethodName())) {
            flaggedArity = call.getArgs().size() >= 2;
        } else {
            flaggedArity = call.getArgs().size() >= 3;
        }
        if (!flaggedArity) return null;

        String flagText;
        if (firstValue instanceof SqlValue.Literal lit) flagText = lit.getUnquoted();
        else if (firstValue instanceof SqlValue.Placeholder ph) flagText = ph.getSource();
        else flagText = null;
        if (firstValue instanceof SqlValue.Literal && !"true".equals(flagText) && !"false".equals(flagText)) return null;
        boolean defaultEnabled = !(firstValue instanceof SqlValue.Literal lit && "false".equals(lit.getUnquoted()));
        return new FlagAnalysis(new WrapperCall(call.getMethodName(), call.getArgs().size() > 1 ? call.getArgs().subList(1, call.getArgs().size()) : java.util.List.of()),
                flagText, defaultEnabled);
    }

    /** 为条件挂载动态条件元数据（值来源/标签由条件模型计算，id 按 pre-order 递增）。 */
    private Condition bindToggle(Condition condition, FlagAnalysis analysis) {
        List<String> sources = valueSourcesOf(condition);
        ConditionToggle toggle = new ConditionToggle(
                "c" + (toggleSeq++),
                analysis != null ? analysis.flagSource() : null,
                analysis != null ? analysis.defaultEnabled() : true,
                SqlGenerator.renderConditionText(condition),
                sources.isEmpty() ? null : sources.get(0),
                sources
        );
        return condition.withToggle(toggle);
    }

    /** 条件挂载动态条件元数据（M12 + M19.6）：所有条件均可勾选开关。 */
    private Condition flagged(Condition condition, FlagAnalysis analysis) {
        if (condition == null) return null;
        return bindToggle(condition, analysis);
    }

    /** 条件关联的未解析参数来源（Simple/Like 单值；Between 双值；In 各值）。 */
    private static List<String> valueSourcesOf(Condition condition) {
        if (condition instanceof Condition.Simple s) {
            return listOfNotNull(s.getValue() instanceof SqlValue.Placeholder ph ? ph.getSource() : null);
        }
        if (condition instanceof Condition.Like l) {
            return listOfNotNull(l.getPattern() instanceof SqlValue.Placeholder ph ? ph.getSource() : null);
        }
        if (condition instanceof Condition.Between b) {
            List<String> r = new ArrayList<>();
            if (b.getLo() instanceof SqlValue.Placeholder ph) r.add(ph.getSource());
            if (b.getHi() instanceof SqlValue.Placeholder ph) r.add(ph.getSource());
            return r;
        }
        if (condition instanceof Condition.In i) {
            List<String> r = new ArrayList<>();
            for (SqlValue v : i.getValues()) {
                if (v instanceof SqlValue.Placeholder ph) r.add(ph.getSource());
            }
            return r;
        }
        return List.of();
    }

    /** 添加条件并维护 orPending 状态。 */
    private boolean addConditionFlagged(List<Condition> conditions, Condition condition, boolean orPending) {
        if (condition == null) return orPending;
        addCondition(conditions, condition, orPending);
        return false;
    }

    /** 添加条件；orPending 为 true 时与上一条件组成 OR 分组。 */
    private void addCondition(List<Condition> conditions, Condition condition, boolean orPending) {
        if (orPending && !conditions.isEmpty()) {
            Condition prev = conditions.remove(conditions.size() - 1);
            conditions.add(new Condition.Group(Connector.OR, List.of(prev, condition), null, false));
        } else {
            conditions.add(condition);
        }
    }

    /**
     * apply / exists 共用的「布尔前缀 + SQL 模板 + {i} 绑定参数」解析。
     *
     * @param call apply / exists 调用
     * @return 解析模板；参数不足返回 null
     */
    private RawTemplate parseRawTemplate(WrapperCall call) {
        List<WrapperArg> args = call.getArgs();
        if (args.isEmpty()) return null;
        WrapperArg first = args.get(0);
        SqlValue flagValue = WrapperConverterKt.asValue(first);
        boolean hasFlag;
        if (args.size() >= 2) {
            if (flagValue instanceof SqlValue.Literal lit) {
                hasFlag = "true".equals(lit.getUnquoted()) || "false".equals(lit.getUnquoted());
            } else if (flagValue instanceof SqlValue.Placeholder) {
                // T-UpdateSetSqlConcat：带片段形态的运行期实参是 SQL 模板（`apply("x > " + v, w)`），
                // 不是布尔前缀——否则模板会被当 flag 吞掉，真正的模板位被错取成第二参
                hasFlag = WrapperConverterKt.asSqlFragment(first) == null;
            } else {
                hasFlag = !(first instanceof WrapperArg.ValueArg);
            }
        } else {
            hasFlag = false;
        }
        int startIndex = hasFlag ? 1 : 0;
        if (args.size() <= startIndex) return null;
        // T-UpdateSetSqlConcat：片段位取 asSqlFragment（`apply("amount > " + x)` → `amount > ?`）
        SqlFragment templateFragment = WrapperConverterKt.asSqlFragment(argAt(args, startIndex));
        if (templateFragment == null) return null;
        // `{i}` 绑定参数替换 + 按最终文本顺序登记占位符来源（不登记会让面板回填整体错位）
        // 注意：null 值保留在列表里（{i} 索引对齐），缺失绑定原样保留 `{i}`
        List<SqlValue> values = new ArrayList<>();
        List<WrapperArg> tailArgs = args.size() > startIndex + 1 ? args.subList(startIndex + 1, args.size()) : java.util.Collections.emptyList();
        for (WrapperArg a : tailArgs) {
            values.add(WrapperConverterKt.asValue(a));
        }
        SqlFragment rendered = WrapperConverterKt.renderSqlFragment(templateFragment, values);
        String sql = rendered.getText();
        String flagSource = null;
        if (hasFlag && first instanceof WrapperArg.ValueArg va) {
            SqlValue fv = va.getValue();
            if (fv instanceof SqlValue.Literal fl) flagSource = fl.getUnquoted();
            else if (fv instanceof SqlValue.Placeholder ph) flagSource = ph.getSource();
        }
        boolean defaultEnabled = !"false".equals(flagSource);
        return new RawTemplate(hasFlag, flagSource, defaultEnabled, sql, rendered.getParams());
    }

    /** 解析 apply：原样 SQL 片段透传 + {i} 参数替换 + 布尔前缀 toggle。 */
    private Condition parseApply(WrapperCall call) {
        RawTemplate t = parseRawTemplate(call);
        if (t == null) return null;
        return new Condition.Raw(t.sql(), new ConditionToggle(
                "c" + (toggleSeq++),
                t.flagSource(),
                t.defaultEnabled(),
                SqlGenerator.renderConditionText(new Condition.Raw(t.sql(), null)),
                null,
                List.of()
        ), t.params());
    }

    /** 解析 applyFunc（MPJ 官方 API，1.4.13+）。 */
    private Condition parseApplyFunc(WrapperCall call) {
        // T-UpdateSetSqlConcat：片段位取 asSqlFragment（模板含运行期段时静态段保留、运行期段写 `?`）
        SqlFragment template = WrapperConverterKt.asSqlFragment(argAt(call.getArgs(), 0));
        if (template == null) return null;
        WrapperArg.FuncArg func = argAt(call.getArgs(), 1) instanceof WrapperArg.FuncArg fa ? fa : null;
        if (func == null) return null;
        String sql = template.getText();
        for (ColumnRef col : func.getColumns()) {
            ColumnRef withPrefix = columnPrefix != null ? WrapperConverterKt.withTableAlias(col, columnPrefix) : col;
            sql = sql.replaceFirst("%s", withPrefix.qualifiedName(dialect));
        }
        if (sql.contains("%s")) return null;
        // `{i}` 绑定参数替换 + 按最终文本顺序登记占位符来源
        SqlFragment rendered = WrapperConverterKt.renderSqlFragment(new SqlFragment(sql, template.getParams()), func.getValues());
        return new Condition.Raw(rendered.getText(), null, rendered.getParams());
    }

    /** 解析 exists / notExists。 */
    private Condition parseExists(WrapperCall call, boolean negated) {
        RawTemplate t = parseRawTemplate(call);
        if (t == null) return null;
        String sql = (negated ? "NOT EXISTS" : "EXISTS") + " (" + t.sql() + ")";
        ConditionToggle toggle = null;
        if (t.hasFlag()) {
            toggle = new ConditionToggle(
                    "c" + (toggleSeq++),
                    t.flagSource(),
                    t.defaultEnabled(),
                    SqlGenerator.renderConditionText(new Condition.Raw(sql, null)),
                    null,
                    List.of()
            );
        }
        return new Condition.Raw(sql, toggle, t.params());
    }

    /** 取调用中所有列实参（带主表别名前缀）。 */
    private List<ColumnRef> columnArgs(WrapperCall call) {
        List<ColumnRef> result = new ArrayList<>();
        for (WrapperArg arg : call.getArgs()) {
            ColumnRef col = argAsColumn(arg);
            if (col != null) result.add(col);
        }
        return result;
    }

    /**
     * SELECT 列表实参（T-UpdateSetSqlConcat）：字符串实参按<b>片段</b>解析——
     * {@code select("amount * " + x + " AS v")} 的运行期段渲染成 {@code ?} 并带来源，由生成器在列列表渲染点按序登记。
     *
     * <p>与 {@link #columnArgs} 的区别只在字符串实参：条件/排序/分组是<b>标识符</b>位置（拼运行期值会产出非法标识符，
     * 保持 {@code Literal} 口径），SELECT 列表才是「原样拼进语句的 SQL 表达式」位置。
     */
    private List<ColumnRef> selectColumnArgs(WrapperCall call) {
        List<ColumnRef> result = new ArrayList<>();
        for (WrapperArg arg : call.getArgs()) {
            if (arg instanceof WrapperArg.ColumnArg || arg instanceof WrapperArg.AliasArg) {
                ColumnRef col = argAsColumn(arg);
                if (col != null) result.add(col);
            } else if (arg instanceof WrapperArg.ValueArg) {
                SqlFragment frag = WrapperConverterKt.asSqlFragment(arg);
                if (frag != null) {
                    result.add(new ColumnRef(null, frag.getText(), null, null, null, null, null, frag.getParams()));
                }
            } else {
                ColumnRef col = argAsColumn(arg);
                if (col != null) result.add(col);
            }
        }
        return result;
    }

    /**
     * 按谓词白/黑名单过滤实体列（静态近似，selectFilter / select(Class, Predicate)）。
     *
     * @param predicate 谓词（白名单 include / 黑名单 exclude）
     * @return 过滤后的列（带主表别名前缀）
     */
    private List<ColumnRef> selectColumnsByPredicate(WrapperArg.PredicateArg predicate) {
        List<String> allColumns = table.getAllColumnNames();
        // T-Perf-23：List.contains O(n^2) -> Set O(n)（列数多时显著）
        Set<String> includeSet = new HashSet<>(predicate.getInclude());
        Set<String> excludeSet = new HashSet<>(predicate.getExclude());
        List<String> selected;
        if (!includeSet.isEmpty()) {
            selected = new ArrayList<>();
            for (String c : allColumns) {
                if (includeSet.contains(c)) selected.add(c);
            }
        } else {
            selected = new ArrayList<>();
            for (String c : allColumns) {
                if (!excludeSet.contains(c)) selected.add(c);
            }
        }
        List<ColumnRef> result = new ArrayList<>();
        for (String col : selected) {
            ColumnRef ref = new ColumnRef(null, col, null, null, null, null, null);
            result.add(columnPrefix != null ? WrapperConverterKt.withTableAlias(ref, columnPrefix) : ref);
        }
        return result;
    }

    /** 实参转列：Lambda 列补主表别名前缀；字符串列名原样。 */
    private ColumnRef argAsColumn(WrapperArg arg) {
        if (arg instanceof WrapperArg.ColumnArg ca) {
            return columnPrefix != null ? WrapperConverterKt.withTableAlias(ca.getColumn(), columnPrefix) : ca.getColumn();
        }
        if (arg instanceof WrapperArg.ValueArg va) {
            SqlValue value = va.getValue();
            if (value instanceof SqlValue.Literal lit) {
                return new ColumnRef(null, lit.getUnquoted(), null, null, null, null, null);
            }
        }
        return null;
    }

    /** 实参转值。 */
    private SqlValue argAsValue(WrapperArg arg) {
        return WrapperConverterKt.asValue(arg);
    }

    /** 安全取列表元素（越界返回 null）。 */
    private static WrapperArg argAt(List<WrapperArg> args, int i) {
        return (i >= 0 && i < args.size()) ? args.get(i) : null;
    }

    /** Long 解析（失败返回 null）。 */
    private static Long parseLongOrNull(String s) {
        if (s == null) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 单元素/空列表归一化。 */
    private static List<String> listOfNotNull(String s) {
        return s != null ? List.of(s) : List.of();
    }

    /**
     * apply / exists 共用的解析模板。
     *
     * @param hasFlag       是否含布尔条件前缀
     * @param flagSource    布尔条件实参源码文本（tooltip）
     * @param defaultEnabled 默认勾选态
     * @param sql           替换后的 SQL 片段
     * @param params        片段中占位符 {@code ?} 的来源（T-UpdateSetSqlConcat）：渲染时按序登记进参数表
     */
    private record RawTemplate(boolean hasFlag, String flagSource, boolean defaultEnabled, String sql,
                               List<String> params) {
    }
}
