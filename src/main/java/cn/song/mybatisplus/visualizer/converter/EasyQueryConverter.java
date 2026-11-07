package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.BatchColumn;
import cn.song.mybatisplus.visualizer.model.BatchKind;
import cn.song.mybatisplus.visualizer.model.BatchModel;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.ConditionToggle;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SetClause;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.psi.ArgResolver;
import cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader;
import cn.song.mybatisplus.visualizer.sql.GeneratedSql;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.ParamQuote;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Easy-Query proxy 风格转换器（EQ，M44 其他 ORM 第一批，矩阵 §4）。
 *
 * <p>官方主推 sql-api-proxy 的 APT 代理风格（javap sql-core/sql-api-proxy 3.2.15 实证）：
 * `easyEntityQuery.queryable(Topic.class).where(o -&gt; o.id().eq("123")).orderBy(o -&gt; o.createTime().desc())
 * .limit(1, 20).toList()`。where lambda 是 **void 注册式语句块**（多条 `o.col().op(args);` 分号分隔），
 * 按块内语句逐链收集（[parseConditionChain]），链形 = 列访问器（`o.id()` 无实参）+ 谓词方法。</p>
 *
 * <h2>条件映射（javap com.easy.query.core.proxy.predicate.* 实证）</h2>
 * <ul>
 *  <li>比较：eq/ne/gt/ge/lt/le（含 boolean 前缀重载 → ConditionToggle）；</li>
 *  <li>集合：in/notIn（Collection / 数组实参）；</li>
 *  <li>一元：isNull/isNotNull；字符串断言：isEmpty/isNotBlank（isNotEmpty/isBlank 同法近似）；</li>
 *  <li>模糊：like/notLike（%v%）、likeMatchLeft/likeMatchRight（%v / v%）；</li>
 *  <li>范围族：rangeClosed → BETWEEN；rangeOpen/rangeOpenClosed/rangeClosedOpen →
 *      开闭区间渲染为 AND 组（`col &gt; ? AND col &lt;= ?`，现有 Between 模型为闭区间，开区间语义在
 *      条件层组合表达）；</li>
 *  <li>or/and 闭包嵌套 → Group（与前一条件 OR/AND 连接）；布尔前缀 where(boolean, lambda) → 整组 toggle。</li>
 * </ul>
 *
 * <h2>链面</h2>
 * <ul>
 *  <li>orderBy lambda 块 → ORDER BY（`o.createTime().desc()` / 缺省 ASC）；</li>
 *  <li>limit(count) / limit(offset, count) → LIMIT/OFFSET；</li>
 *  <li>leftJoin/innerJoin/rightJoin(Class, (a,b) -&gt; a.uid().eq(b.id())) 双参 on lambda →
 *      JoinClause + ColumnComparison（首参=副表 proxy、次参=主表 proxy，主表别名 t / 副表 t1）；</li>
 *  <li>终结点 toList/firstOrNull/singleOrNull/count/any/toPageResult/toSQL（count → SELECT COUNT(*)）；</li>
 *  <li>写路径：insertable(entity/Class) → INSERT（BatchModel 模板）；updatable(...).setColumns(lambda)
 *      → UPDATE SET；deletable(...) → DELETE。</li>
 * </ul>
 *
 * <h2>边界（MVP，KDoc 文档化）</h2>
 * <ul>
 *  <li>core/String 风格（easyQueryClient.queryable + 字符串列名）与 sql-api4j 二期不做——
 *      本转换器只认 proxy 语句块 lambda；</li>
 *  <li>select(lambda) 投影、group/having、@Navigate 关联展开、JPAExpressions 式子查询暂不建模
 *      （优雅降级：忽略该段，SELECT 按实体全列呈现，不出错误 SQL）。</li>
 * </ul>
 *
 * <p>识别（[isEasyQueryCall]）：entry 方法名 queryable/insertable/updatable/deletable +
 * receiver 变量名（easyEntityQuery/easyQueryClient）或类型（EasyEntityQuery/EasyQueryClient）
 * 启发——普通类同名方法不识别（宁可无图标不可误亮）。</p>
 */
public final class EasyQueryConverter {

    /** 单例（Kotlin object 转换）。 */
    public static final EasyQueryConverter INSTANCE = new EasyQueryConverter();

    // ------------------------------------------------------------------
    // 结果模型
    // ------------------------------------------------------------------

    /**
     * Easy-Query 预览结果（与 SpecificationQueryConverter.SpecificationPreview 同构）。
     *
     * @param methodName   entry 方法名（queryable/insertable/updatable/deletable）
     * @param statement    语句模型（insert 路径为 null——BatchModel 承载）
     * @param sql          生成的 SQL（含尾部注释）
     * @param paramSources 未解析参数来源
     * @param paramHints   与 paramSources 并行的回填提示
     * @param tableName    表名
     * @param columns      实体全列名
     * @param warning      警示文本（全表删除等）；null 表示无
     * @param batchModel   批量列勾选模型（insertable 路径非 null）
     */
    public static final class EqPreview {
        private final String methodName;
        private final SqlStatement statement;
        private final String sql;
        private final List<String> paramSources;
        private final List<ParamHint> paramHints;
        private final String tableName;
        private final List<String> columns;
        private final String warning;
        private final BatchModel batchModel;

        public EqPreview(String methodName, SqlStatement statement, String sql, List<String> paramSources,
                         List<ParamHint> paramHints, String tableName, List<String> columns, String warning,
                         BatchModel batchModel) {
            this.methodName = methodName;
            this.statement = statement;
            this.sql = sql;
            this.paramSources = paramSources;
            this.paramHints = paramHints;
            this.tableName = tableName;
            this.columns = columns;
            this.warning = warning;
            this.batchModel = batchModel;
        }

        /** 便捷构造：默认参数（batchModel null）。 */
        public EqPreview(String methodName, SqlStatement statement, String sql, List<String> paramSources,
                         List<ParamHint> paramHints, String tableName, List<String> columns, String warning) {
            this(methodName, statement, sql, paramSources, paramHints, tableName, columns, warning, null);
        }

        /** 便捷构造：默认参数（paramSources/paramHints 空、tableName 空串、columns 空、warning/batchModel null）。 */
        public EqPreview(String methodName, SqlStatement statement, String sql) {
            this(methodName, statement, sql, List.of(), List.of(), "", List.of(), null, null);
        }

        public String getMethodName() {
            return methodName;
        }

        public SqlStatement getStatement() {
            return statement;
        }

        public String getSql() {
            return sql;
        }

        public List<String> getParamSources() {
            return paramSources;
        }

        public List<ParamHint> getParamHints() {
            return paramHints;
        }

        public String getTableName() {
            return tableName;
        }

        public List<String> getColumns() {
            return columns;
        }

        public String getWarning() {
            return warning;
        }

        public BatchModel getBatchModel() {
            return batchModel;
        }
    }

    // ------------------------------------------------------------------
    // 名单
    // ------------------------------------------------------------------

    /** 链 entry 方法名（javap EasyQueryClient：queryable/insertable/updatable/deletable）。 */
    public static final Set<String> ENTRY_METHODS = Set.of("queryable", "insertable", "updatable", "deletable");

    /** receiver 变量名启发（官方文档示例名）。 */
    private static final Set<String> RECEIVER_VARIABLE_NAMES = Set.of("easyentityquery", "easyqueryclient");

    /** receiver 类型启发（EasyEntityQuery 在 easy-query-core artifact，测试环境按 stub 简名判定）。 */
    private static final Set<String> RECEIVER_TYPE_NAMES = Set.of("EasyEntityQuery", "EasyQueryClient");

    /** 比较 op → SQL 操作符（javap DSLValuePredicate：eq/ne/gt/ge/lt/le）。 */
    private static final Map<String, String> COMPARE_OPS = new LinkedHashMap<>();

    /** like 族 op → (position, negated)。 */
    private static final Map<String, LikeOp> LIKE_OPS = new LinkedHashMap<>();

    /** 一元 op → SQL 片段。 */
    private static final Map<String, String> UNARY_OPS = new LinkedHashMap<>();

    /** 集合 op（javap DSLValuesPredicate）。 */
    private static final Set<String> VALUES_OPS = Set.of("in", "notIn");

    /** 范围族 op（javap DSLRangePredicate / DSLRangeColumnFunctionPredicate）。 */
    private static final Set<String> RANGE_OPS = Set.of("rangeClosed", "rangeOpen", "rangeOpenClosed", "rangeClosedOpen");

    /** 谓词方法全集（列访问器 vs 谓词的区分依据）。 */
    private static final Set<String> OP_METHODS = new LinkedHashSet<>();

    /** 联表方法（javap Joinable1；on 双参 lambda）。 */
    private static final Map<String, JoinType> JOIN_METHODS = new LinkedHashMap<>();

    /** proxy 写路径 setColumns lambda 内的 set 方法（javap SQLColumnSet*）。 */
    private static final String SET_OP = "set";

    /** 查询链遍历深度守卫（异常 PSI 防御）。 */
    private static final int MAX_CHAIN_DEPTH = 32;

    static {
        COMPARE_OPS.put("eq", "=");
        COMPARE_OPS.put("ne", "!=");
        COMPARE_OPS.put("gt", ">");
        COMPARE_OPS.put("ge", ">=");
        COMPARE_OPS.put("lt", "<");
        COMPARE_OPS.put("le", "<=");
        LIKE_OPS.put("like", new LikeOp(LikePosition.ANYWHERE, false));
        LIKE_OPS.put("notLike", new LikeOp(LikePosition.ANYWHERE, true));
        LIKE_OPS.put("likeMatchLeft", new LikeOp(LikePosition.LEFT, false));
        LIKE_OPS.put("notLikeMatchLeft", new LikeOp(LikePosition.LEFT, true));
        LIKE_OPS.put("likeMatchRight", new LikeOp(LikePosition.RIGHT, false));
        LIKE_OPS.put("notLikeMatchRight", new LikeOp(LikePosition.RIGHT, true));
        LIKE_OPS.put("likeRaw", new LikeOp(LikePosition.ANYWHERE, false));
        LIKE_OPS.put("notLikeRaw", new LikeOp(LikePosition.ANYWHERE, true));
        LIKE_OPS.put("likeRawMatchLeft", new LikeOp(LikePosition.LEFT, false));
        LIKE_OPS.put("likeRawMatchRight", new LikeOp(LikePosition.RIGHT, false));
        UNARY_OPS.put("isNull", "IS NULL");
        UNARY_OPS.put("isNotNull", "IS NOT NULL");
        OP_METHODS.addAll(COMPARE_OPS.keySet());
        OP_METHODS.addAll(LIKE_OPS.keySet());
        OP_METHODS.addAll(UNARY_OPS.keySet());
        OP_METHODS.addAll(VALUES_OPS);
        OP_METHODS.addAll(RANGE_OPS);
        OP_METHODS.add("isEmpty");
        OP_METHODS.add("isNotEmpty");
        OP_METHODS.add("isBlank");
        OP_METHODS.add("isNotBlank");
        JOIN_METHODS.put("leftJoin", JoinType.LEFT);
        JOIN_METHODS.put("innerJoin", JoinType.INNER);
        JOIN_METHODS.put("rightJoin", JoinType.RIGHT);
    }

    private EasyQueryConverter() {
    }

    // ------------------------------------------------------------------
    // 识别（锚点 / preview 同源）
    // ------------------------------------------------------------------

    /**
     * 调用是否为 Easy-Query proxy 链（entry 方法名 + receiver 启发）。
     * 负例守卫：普通类同名方法（无 easyEntityQuery/easyQueryClient receiver）不识别。
     */
    public static boolean isEasyQueryCall(PsiMethodCallExpression call) {
        try {
            return isEntryCall(call);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isEntryCall(PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null || !ENTRY_METHODS.contains(methodName)) {
            return false;
        }
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) {
            return false;
        }
        // 变量名启发（O(1) 文本）
        String qualifierText = qualifier instanceof PsiReferenceExpression reference
                ? reference.getReferenceName()
                : qualifier.getText();
        if (qualifierText != null && RECEIVER_VARIABLE_NAMES.contains(qualifierText.toLowerCase(Locale.ROOT))) {
            return true;
        }
        // 类型启发：qualifier 变量 resolve 声明类型 / 表达式类型
        PsiType receiverType;
        if (qualifier instanceof PsiReferenceExpression reference) {
            PsiType variableType = reference.resolve() instanceof PsiVariable variable ? variable.getType() : null;
            receiverType = variableType != null ? variableType : qualifier.getType();
        } else {
            receiverType = qualifier.getType();
        }
        String typeName = receiverType instanceof PsiClassType classType ? classType.getClassName() : null;
        return typeName != null && RECEIVER_TYPE_NAMES.contains(typeName);
    }

    /**
     * 转换 Easy-Query proxy 链。
     *
     * @param call             链上任意位置的方法调用（自动回溯 entry）
     * @param resolveVariables 变量求值开关
     * @return 预览结果；非 Easy-Query 链 / 实体不可解析时 null（交回既有管道）
     */
    public static EqPreview convert(PsiMethodCallExpression call) {
        return convert(call, true);
    }

    public static EqPreview convert(PsiMethodCallExpression call, boolean resolveVariables) {
        try {
            return convertInternal(call, resolveVariables);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static EqPreview convertInternal(PsiMethodCallExpression call, boolean resolveVariables) {
        PsiMethodCallExpression entry = findEntryCall(call, MAX_CHAIN_DEPTH);
        if (entry == null) {
            return null;
        }
        String methodName = entry.getMethodExpression().getReferenceName();
        if (methodName == null) {
            return null;
        }
        // 实体：queryable(Class)/insertable(entity)/updatable(...)/deletable(...) 首参
        PsiClass entityClass = resolveEntityArg(entry);
        if (entityClass == null) {
            return null;
        }
        TableInfo table = new JpaEntityResolver().resolve(entityClass);
        if (table.getEntityClassName().isBlank()) {
            return null;
        }

        ParseCtx ctx = new ParseCtx(table, entityClass, resolveVariables);
        List<PsiMethodCallExpression> segments = collectSegments(entry);
        for (int index = 1; index < segments.size(); index++) {
            PsiMethodCallExpression segment = segments.get(index);
            String name = segment.getMethodExpression().getReferenceName();
            if (name == null) {
                continue;
            }
            switch (name) {
                case "where" -> ctx.addConditions(parseWhereSegment(segment, ctx));
                case "orderBy" -> ctx.addOrderBy(parseOrderBySegment(segment, ctx));
                case "limit" -> ctx.limit = parseLimit(segment);
                case "select" -> {
                    // MVP：投影暂不建模（SELECT 全列降级，见类 KDoc）
                }
                case "setColumns" -> ctx.addSets(parseSetColumns(segment, ctx));
                default -> {
                    if (JOIN_METHODS.containsKey(name)) {
                        ctx.addJoin(parseJoin(segment, ctx));
                    }
                }
            }
        }
        String terminal = null;
        if (!segments.isEmpty()) {
            terminal = segments.get(segments.size() - 1).getMethodExpression().getReferenceName();
        }

        switch (methodName) {
            case "queryable":
                return selectPreview(entry, table, ctx, terminal);
            case "insertable":
                return insertPreview(entry, table, ctx);
            case "updatable":
                return updatePreview(entry, table, ctx);
            case "deletable":
                return deletePreview(entry, table, ctx);
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------
    // 查询（queryable）
    // ------------------------------------------------------------------

    private static EqPreview selectPreview(PsiMethodCallExpression entry, TableInfo table, ParseCtx ctx,
                                           String terminal) {
        boolean hasJoins = !ctx.joins.isEmpty();
        String mainAlias = hasJoins ? "t" : null;
        ctx.applyMainAlias(mainAlias);
        List<Condition> conditions = ctx.conditions;
        SqlStatement statement;
        if ("count".equals(terminal)) {
            statement = new SelectStatement(table,
                    List.of(new ColumnRef(null, "*", null, "COUNT")),
                    mainAlias, ctx.joins, false, conditions, List.of(), null, ctx.orderBy, ctx.limit,
                    null, null, null, List.of(), null, null);
        } else {
            statement = new SelectStatement(table, List.of(), mainAlias, ctx.joins, false, conditions,
                    List.of(), null, ctx.orderBy, ctx.limit, null, null, null, List.of(), null, null);
        }
        List<String> notes = new ArrayList<>();
        if ("toSQL".equals(terminal)) {
            notes.add("toSQL()：仅生成 SQL 不执行");
        }
        if (terminal != null && Set.of("firstOrNull", "singleOrNull", "any").contains(terminal)) {
            notes.add(terminal + "()：取单条语义（预览不追加 LIMIT 1，运行时按实现）");
        }
        if ("toPageResult".equals(terminal)) {
            notes.add("toPageResult()：分页查询（LIMIT/OFFSET 以链上 limit 段为准）");
        }
        GeneratedSql generated = SqlGenerator.generateWithParams(statement);
        return previewOf(entry, statement, generated, notes, table, ctx, null);
    }

    // ------------------------------------------------------------------
    // 写路径（insertable / updatable / deletable）
    // ------------------------------------------------------------------

    private static EqPreview insertPreview(PsiMethodCallExpression entry, TableInfo table, ParseCtx ctx) {
        // EQ8：@Column(primaryKey = true, generatedKey = true) 主键应用端/数据库生成 → 不进 INSERT 列
        List<BatchColumn> toggleColumns = new ArrayList<>();
        for (ColumnInfo col : table.getColumns()) {
            if (col.isInsertable() && !ctx.generatedKeyColumns().contains(col.getColumnName())) {
                toggleColumns.add(new BatchColumn(col.getColumnName(), col.getColumnName(),
                        ParamQuote.decide(col.getFieldType()), true, false, null,
                        ParamQuote.isTemporal(col.getFieldType())));
            }
        }
        BatchModel model = new BatchModel(BatchKind.INSERT, table.getTableName(), toggleColumns);
        Set<String> active = new LinkedHashSet<>();
        for (BatchColumn col : toggleColumns) {
            active.add(col.getColumnName());
        }
        List<String> notes = new ArrayList<>();
        if (!ctx.generatedKeyColumns().isEmpty()) {
            notes.add("主键 " + String.join(", ", ctx.generatedKeyColumns())
                    + "：@Column(generatedKey = true) 应用端/数据库生成，不进 INSERT 列清单");
        }
        List<String> sqlParts = new ArrayList<>();
        sqlParts.add(model.renderSql(active));
        for (String note : notes) {
            sqlParts.add("-- " + note);
        }
        String referenceName = entry.getMethodExpression().getReferenceName();
        return new EqPreview(referenceName != null ? referenceName : "insertable",
                null,
                String.join("\n", sqlParts),
                model.orderedSources(active),
                model.orderedHints(active),
                table.getTableName(),
                table.getAllColumnNames(),
                null,
                model);
    }

    private static EqPreview updatePreview(PsiMethodCallExpression entry, TableInfo table, ParseCtx ctx) {
        List<Condition> conditions = ctx.conditions;
        if (ctx.sets.isEmpty()) {
            // updatable(entity) 无 setColumns：实体全列更新 + 主键 WHERE（对齐 merge 语义的静态近似）
            ColumnInfo idColumn = null;
            for (ColumnInfo col : table.getColumns()) {
                if (col.isId()) {
                    idColumn = col;
                    break;
                }
            }
            List<SetClause> sets = new ArrayList<>();
            for (ColumnInfo col : table.getColumns()) {
                if (!col.isId()) {
                    sets.add(new SetClause(new ColumnRef(col.getColumnName()), new SqlValue.Placeholder("?")));
                }
            }
            List<Condition> where = idColumn != null
                    ? List.of(new Condition.Simple(new ColumnRef(idColumn.getColumnName()), "=",
                            new SqlValue.Placeholder("?"), null))
                    : conditions;
            SqlStatement statement = new UpdateStatement(table, sets, where);
            GeneratedSql generated = SqlGenerator.generateWithParams(statement);
            // 普查批：无主键且无 where 段 → WHERE 整体缺位（全表更新），notes 按实际 WHERE 语义给
            String whereNote = idColumn != null ? "主键 WHERE"
                    : !conditions.isEmpty() ? "沿用 where 段条件"
                    : "无 WHERE 条件，将更新全表";
            return previewOf(entry, statement, generated,
                    List.of("updatable(entity)：未调用 setColumns，按实体全列 SET 近似（" + whereNote + "）"),
                    table, ctx,
                    idColumn == null && conditions.isEmpty() ? "⚠ 无 WHERE 条件，将更新全表数据！" : null);
        }
        SqlStatement statement = new UpdateStatement(table, ctx.sets, conditions);
        GeneratedSql generated = SqlGenerator.generateWithParams(statement);
        // 普查批：update 无 where → 全表更新警示（对齐同文件 deletePreview 既有形态）
        return previewOf(entry, statement, generated, List.of(), table, ctx,
                conditions.isEmpty() ? "⚠ 无 WHERE 条件，将更新全表数据！" : null);
    }

    private static EqPreview deletePreview(PsiMethodCallExpression entry, TableInfo table, ParseCtx ctx) {
        List<Condition> conditions = ctx.conditions;
        SqlStatement statement = new DeleteStatement(table, conditions);
        GeneratedSql generated = SqlGenerator.generateWithParams(statement);
        String warning = conditions.isEmpty() ? "⚠ 无 WHERE 条件，将删除全表数据！" : null;
        return previewOf(entry, statement, generated, List.of(), table, ctx, warning);
    }

    // ------------------------------------------------------------------
    // 段解析：where / orderBy / limit / join / setColumns
    // ------------------------------------------------------------------

    /** where(boolean, lambda) / where(lambda) → 条件列表（布尔前缀 → 条件/组级 toggle）。 */
    private static List<Condition> parseWhereSegment(PsiMethodCallExpression segment, ParseCtx ctx) {
        PsiExpression[] args = segment.getArgumentList().getExpressions();
        if (args.length == 0) {
            return List.of();
        }
        if (!(args[args.length - 1] instanceof PsiLambdaExpression lambda)) {
            return List.of();
        }
        List<Condition> conditions = parseWhereLambda(lambda, ctx);
        if (args.length >= 2) {
            Flag flag = ctx.flagOf(args[0]);
            if (flag == null) {
                return conditions;
            }
            if (conditions.isEmpty()) {
                return List.of();
            }
            if (conditions.size() == 1) {
                // 单条件：toggle 直接挂条件（避免 Group 双层括号）
                Condition first = conditions.get(0);
                return List.of(first.withToggle(ctx.toggleOf(first, flag)));
            }
            Condition.Group group = new Condition.Group(Connector.AND, conditions, null);
            return List.of(group.withToggle(ctx.toggleOf(group, flag)));
        }
        return conditions;
    }

    /**
     * where lambda 解析入口：语句块 `{ o.id().eq("1"); o.name().like("x"); }` 逐语句收集；
     * 表达式体 `o -&gt; o.id().eq("1")` 直接收链。
     */
    private static List<Condition> parseWhereLambda(PsiLambdaExpression lambda, ParseCtx ctx) {
        PsiElement body = lambda.getBody();
        if (body == null) {
            return List.of();
        }
        List<Condition> result = new ArrayList<>();
        if (body instanceof PsiCodeBlock codeBlock) {
            for (PsiStatement statement : codeBlock.getStatements()) {
                if (statement instanceof PsiExpressionStatement expressionStatement
                        && expressionStatement.getExpression() instanceof PsiMethodCallExpression call) {
                    result.addAll(parseConditionChain(call, ctx));
                }
            }
        } else if (body instanceof PsiMethodCallExpression call) {
            result.addAll(parseConditionChain(call, ctx));
        }
        return result;
    }

    /**
     * 单条注册语句链解析：`o.id().eq("1")` / `o.age().gt(1).or(o2 -&gt; o2.name().eq("2"))`。
     * 链形 = 若干列访问器（无实参 proxy 列方法）+ 谓词/闭包方法；
     * or/and 闭包与**前一条件**组成 Group（Easy-Query 注册式语义）。
     */
    private static List<Condition> parseConditionChain(PsiMethodCallExpression tailCall, ParseCtx ctx) {
        List<PsiMethodCallExpression> chain = ArgResolver.collectCallChain(tailCall);
        List<Condition> conditions = new ArrayList<>();
        String pendingColumn = null;
        for (PsiMethodCallExpression call : chain) {
            String name = call.getMethodExpression().getReferenceName();
            if (name == null) {
                continue;
            }
            if (OP_METHODS.contains(name)) {
                if (pendingColumn != null) {
                    Condition condition = buildCondition(pendingColumn, name, call, ctx);
                    if (condition != null) {
                        conditions.add(condition);
                    }
                }
                pendingColumn = null;
            } else if ("or".equals(name) || "and".equals(name)) {
                PsiExpression[] callArgs = call.getArgumentList().getExpressions();
                PsiLambdaExpression lambda = callArgs.length > 0 && callArgs[0] instanceof PsiLambdaExpression le
                        ? le
                        : null;
                List<Condition> nested = lambda != null ? parseWhereLambda(lambda, ctx) : List.of();
                if (!nested.isEmpty()) {
                    Connector connector = "or".equals(name) ? Connector.OR : Connector.AND;
                    Condition last = conditions.isEmpty() ? null : conditions.remove(conditions.size() - 1);
                    List<Condition> children = new ArrayList<>();
                    if (last != null) {
                        children.add(last);
                    }
                    children.addAll(nested);
                    conditions.add(new Condition.Group(connector, children, null));
                }
                pendingColumn = null;
            } else {
                if (call.getArgumentList().getExpressions().length == 0) {
                    pendingColumn = name;
                }
            }
        }
        return conditions;
    }

    /** 谓词方法 → Condition（op 名单见类 KDoc）；属性名按实体注解映射列名；布尔前缀剥离并挂 toggle。 */
    private static Condition buildCondition(String column, String op, PsiMethodCallExpression call, ParseCtx ctx) {
        ColumnRef columnRef = new ColumnRef(ctx.columnNameOf(column));
        WrapperCall wrapperCall = ArgResolver.toWrapperCall(call, ctx.table, ctx.table.getEntityClassName(),
                ctx.resolveVariables);
        List<WrapperArg> args = wrapperCall.getArgs();
        // boolean 前缀重载（eq(boolean, v) / rangeClosed(boolean, lo, hi) / in(boolean, values)...）
        Flag flag = null;
        if (args.size() >= 2) {
            Flag candidate = ctx.flagOfArg(args.get(0));
            if (candidate != null) {
                flag = candidate;
                args = new ArrayList<>(args.subList(1, args.size()));
            }
        }
        Condition condition = null;
        if (COMPARE_OPS.containsKey(op)) {
            SqlValue value = !args.isEmpty() ? WrapperConverterKt.asValue(args.get(0)) : null;
            if (value != null) {
                condition = new Condition.Simple(columnRef, COMPARE_OPS.get(op), value, null);
            }
        } else if (UNARY_OPS.containsKey(op)) {
            condition = new Condition.Unary(columnRef, UNARY_OPS.get(op), null);
        } else if (VALUES_OPS.contains(op)) {
            List<SqlValue> values = new ArrayList<>();
            for (WrapperArg arg : args) {
                SqlValue value = WrapperConverterKt.asValue(arg);
                if (value != null) {
                    values.add(value);
                }
            }
            if (!values.isEmpty()) {
                condition = new Condition.In(columnRef, "notIn".equals(op), values, null);
            }
        } else if (LIKE_OPS.containsKey(op)) {
            LikeOp likeOp = LIKE_OPS.get(op);
            SqlValue value = !args.isEmpty() ? WrapperConverterKt.asValue(args.get(0)) : null;
            if (value != null) {
                condition = new Condition.Like(columnRef, likeOp.negated, value, likeOp.position, null);
            }
        } else if (RANGE_OPS.contains(op)) {
            condition = rangeCondition(columnRef, op, args);
        } else if ("isEmpty".equals(op) || "isBlank".equals(op)) {
            condition = new Condition.Raw("( " + columnRef.getColumnName() + " = '' OR "
                    + columnRef.getColumnName() + " IS NULL )");
        } else if ("isNotEmpty".equals(op) || "isNotBlank".equals(op)) {
            condition = new Condition.Raw("( " + columnRef.getColumnName() + " IS NOT NULL AND "
                    + columnRef.getColumnName() + " <> '' )");
        }
        if (condition == null) {
            return null;
        }
        return flag != null ? condition.withToggle(ctx.toggleOf(condition, flag)) : condition;
    }

    /** 范围族 → 开闭区间条件（BETWEEN 闭区间 / AND 组表达开区间，见类 KDoc）。 */
    private static Condition rangeCondition(ColumnRef columnRef, String op, List<WrapperArg> args) {
        SqlValue lo = !args.isEmpty() ? WrapperConverterKt.asValue(args.get(0)) : null;
        if (lo == null) {
            return null;
        }
        SqlValue hi = args.size() > 1 ? WrapperConverterKt.asValue(args.get(1)) : null;
        if (hi == null) {
            return null;
        }
        switch (op) {
            case "rangeClosed":
                return new Condition.Between(columnRef, false, lo, hi, null);
            case "rangeOpen":
                return new Condition.Group(Connector.AND, List.of(
                        new Condition.Simple(columnRef, ">", lo, null),
                        new Condition.Simple(columnRef, "<", hi, null)), null);
            case "rangeOpenClosed":
                return new Condition.Group(Connector.AND, List.of(
                        new Condition.Simple(columnRef, ">", lo, null),
                        new Condition.Simple(columnRef, "<=", hi, null)), null);
            case "rangeClosedOpen":
                return new Condition.Group(Connector.AND, List.of(
                        new Condition.Simple(columnRef, ">=", lo, null),
                        new Condition.Simple(columnRef, "<", hi, null)), null);
            default:
                return null;
        }
    }

    /** orderBy lambda 块 → ORDER BY（`o.createTime().desc()` / 缺省 ASC）。 */
    private static List<OrderByItem> parseOrderBySegment(PsiMethodCallExpression segment, ParseCtx ctx) {
        PsiExpression[] args = segment.getArgumentList().getExpressions();
        if (args.length == 0 || !(args[args.length - 1] instanceof PsiLambdaExpression lambda)) {
            return List.of();
        }
        List<PsiMethodCallExpression> chains = lambdaChains(lambda);
        List<OrderByItem> items = new ArrayList<>();
        for (PsiMethodCallExpression chainTail : chains) {
            List<PsiMethodCallExpression> chain = ArgResolver.collectCallChain(chainTail);
            String column = null;
            boolean asc = true;
            for (PsiMethodCallExpression call : chain) {
                String name = call.getMethodExpression().getReferenceName();
                if ("asc".equals(name)) {
                    asc = true;
                } else if ("desc".equals(name)) {
                    asc = false;
                } else {
                    if (call.getArgumentList().getExpressions().length == 0) {
                        column = name;
                    }
                }
            }
            if (column != null) {
                items.add(new OrderByItem(ctx.columnWithAliasRef(column), asc));
            }
        }
        return items;
    }

    /** limit(count) / limit(offset, count) → LimitClause（运行时值渲染 `?`）。 */
    private static LimitClause parseLimit(PsiMethodCallExpression segment) {
        PsiExpression[] args = segment.getArgumentList().getExpressions();
        if (args.length == 0) {
            return null;
        }
        List<SqlValue> values = new ArrayList<>();
        for (PsiExpression arg : args) {
            values.add(ExpressionEvaluator.evaluate(arg));
        }
        if (values.size() >= 2) {
            return new LimitClause(values.get(0), values.get(1));
        }
        return new LimitClause(null, values.get(0));
    }

    /** leftJoin(Class, (a,b) -&gt; a.uid().eq(b.id())) → JoinClause（首参=副表、次参=主表）。 */
    private static JoinClause parseJoin(PsiMethodCallExpression segment, ParseCtx ctx) {
        PsiExpression[] args = segment.getArgumentList().getExpressions();
        if (args.length < 2) {
            return null;
        }
        JoinType joinType = JOIN_METHODS.get(segment.getMethodExpression().getReferenceName());
        if (joinType == null) {
            return null;
        }
        // 副表实体：Class 字面量或实体类型实参
        PsiClass joinEntity = resolveEntityArgFrom(args[0]);
        if (joinEntity == null) {
            return null;
        }
        TableInfo joinTable = new JpaEntityResolver().resolve(joinEntity);
        String joinAlias = "t1";
        if (!(args[1] instanceof PsiLambdaExpression lambda)) {
            return null;
        }
        PsiParameter[] params = lambda.getParameterList().getParameters();
        if (params.length < 2) {
            return null;
        }
        String joinParamName = params[0].getName();
        String mainParamName = params[1].getName();
        List<Condition> onConditions = parseOnLambda(lambda, ctx, joinTable, joinAlias, joinParamName, mainParamName);
        return new JoinClause(joinType, joinTable.getTableName(), joinAlias, onConditions, null, null, null, joinTable);
    }

    /**
     * on 双参 lambda 解析：`a.uid().eq(b.id())` → ColumnComparison（a=副表列 + 副表别名 t1，
     * b=主表列 + 主表别名 t）。列名按各自实体注解映射（副表 joinTable / 主表 ctx.table），
     * 未知属性降级 snake。实参为 `&lt;param&gt;.&lt;col&gt;()` 方法调用形态（列对列），值形态降级 `col = ?`。
     * 链沿 qualifier 收集（on 实参里的列调用不在链内，无双重计数）。
     */
    private static List<Condition> parseOnLambda(PsiLambdaExpression lambda, ParseCtx ctx, TableInfo joinTable,
                                                 String joinAlias, String joinParamName,
                                                 @SuppressWarnings("unused") String mainParamName) {
        List<PsiMethodCallExpression> chains = lambdaChains(lambda);
        List<Condition> conditions = new ArrayList<>();
        for (PsiMethodCallExpression chainTail : chains) {
            List<PsiMethodCallExpression> chain = ArgResolver.collectCallChain(chainTail);
            String pendingProperty = null;
            boolean pendingIsJoinColumn = false;
            for (PsiMethodCallExpression call : chain) {
                String name = call.getMethodExpression().getReferenceName();
                if (name == null) {
                    continue;
                }
                if (COMPARE_OPS.containsKey(name)) {
                    if (pendingProperty == null) {
                        continue;
                    }
                    String columnName;
                    if (pendingIsJoinColumn) {
                        ColumnInfo joinColumn = joinTable.columnByField(pendingProperty);
                        columnName = joinColumn != null ? joinColumn.getColumnName()
                                : NamingStrategy.INSTANCE.toSnakeCase(pendingProperty);
                    } else {
                        columnName = ctx.columnNameOf(pendingProperty);
                    }
                    ColumnRef left = new ColumnRef(pendingIsJoinColumn ? joinAlias : "t", columnName);
                    PsiExpression[] callArgs = call.getArgumentList().getExpressions();
                    PsiExpression arg = callArgs.length > 0 ? callArgs[0] : null;
                    String rightColumn = null;
                    String rightQualifier = null;
                    if (arg instanceof PsiMethodCallExpression rightCall) {
                        rightColumn = rightCall.getMethodExpression().getReferenceName();
                        if (rightCall.getMethodExpression().getQualifierExpression()
                                instanceof PsiReferenceExpression rightRef) {
                            rightQualifier = rightRef.getReferenceName();
                        }
                    }
                    if (rightColumn != null && rightQualifier != null) {
                        boolean rightIsJoin = rightQualifier.equals(joinParamName);
                        String rightColumnName;
                        if (rightIsJoin) {
                            ColumnInfo joinColumn = joinTable.columnByField(rightColumn);
                            rightColumnName = joinColumn != null ? joinColumn.getColumnName()
                                    : NamingStrategy.INSTANCE.toSnakeCase(rightColumn);
                        } else {
                            rightColumnName = ctx.columnNameOf(rightColumn);
                        }
                        conditions.add(new Condition.ColumnComparison(
                                left,
                                new ColumnRef(rightIsJoin ? joinAlias : "t", rightColumnName),
                                null));
                    } else {
                        conditions.add(new Condition.Simple(
                                left,
                                COMPARE_OPS.containsKey(name) ? COMPARE_OPS.get(name) : "=",
                                arg != null ? ExpressionEvaluator.evaluate(arg) : new SqlValue.Placeholder("?"),
                                null));
                    }
                    pendingProperty = null;
                    pendingIsJoinColumn = false;
                } else if (call.getArgumentList().getExpressions().length == 0) {
                    String qualifierName = call.getMethodExpression().getQualifierExpression()
                            instanceof PsiReferenceExpression qualifierRef
                            ? qualifierRef.getReferenceName()
                            : null;
                    pendingProperty = name;
                    pendingIsJoinColumn = Objects.equals(qualifierName, joinParamName) || joinParamName == null;
                }
            }
        }
        return conditions;
    }

    /** setColumns lambda 块 → SET 子句（`s -&gt; s.name().set("x")`）。 */
    private static List<SetClause> parseSetColumns(PsiMethodCallExpression segment, ParseCtx ctx) {
        PsiExpression[] args = segment.getArgumentList().getExpressions();
        if (args.length == 0 || !(args[0] instanceof PsiLambdaExpression lambda)) {
            return List.of();
        }
        List<PsiMethodCallExpression> chains = lambdaChains(lambda);
        List<SetClause> sets = new ArrayList<>();
        for (PsiMethodCallExpression chainTail : chains) {
            List<PsiMethodCallExpression> chain = ArgResolver.collectCallChain(chainTail);
            String pendingColumn = null;
            for (PsiMethodCallExpression call : chain) {
                String name = call.getMethodExpression().getReferenceName();
                if (SET_OP.equals(name)) {
                    if (pendingColumn != null) {
                        PsiExpression[] callArgs = call.getArgumentList().getExpressions();
                        if (callArgs.length > 0) {
                            SqlValue value = ExpressionEvaluator.evaluate(callArgs[0]);
                            sets.add(new SetClause(new ColumnRef(ctx.columnNameOf(pendingColumn)), value));
                        }
                    }
                    pendingColumn = null;
                } else {
                    if (call.getArgumentList().getExpressions().length == 0) {
                        pendingColumn = name;
                    }
                }
            }
        }
        return sets;
    }

    /** lambda 体 → 语句/表达式调用链尾列表（语句块取每条表达式语句，表达式体单链）。 */
    private static List<PsiMethodCallExpression> lambdaChains(PsiLambdaExpression lambda) {
        PsiElement body = lambda.getBody();
        List<PsiMethodCallExpression> chains = new ArrayList<>();
        if (body == null) {
            return chains;
        }
        if (body instanceof PsiCodeBlock codeBlock) {
            for (PsiStatement statement : codeBlock.getStatements()) {
                if (statement instanceof PsiExpressionStatement expressionStatement
                        && expressionStatement.getExpression() instanceof PsiMethodCallExpression call) {
                    chains.add(call);
                }
            }
        } else if (body instanceof PsiMethodCallExpression call) {
            chains.add(call);
        }
        return chains;
    }

    // ------------------------------------------------------------------
    // 公共辅助
    // ------------------------------------------------------------------

    /** 从 entry 沿 qualifier 链回溯找到 entry 调用（深度守卫防异常 PSI）。 */
    private static PsiMethodCallExpression findEntryCall(PsiMethodCallExpression from, int maxDepth) {
        PsiMethodCallExpression current = from;
        int depth = 0;
        while (depth++ < maxDepth) {
            if (isEntryCall(current)) {
                return current;
            }
            PsiExpression qualifier = current.getMethodExpression().getQualifierExpression();
            if (!(qualifier instanceof PsiMethodCallExpression qualifierCall)) {
                return null;
            }
            current = qualifierCall;
        }
        return null;
    }

    /**
     * 收集 entry → 链尾的完整段列表（头=entry）。
     *
     * <p>回溯必须处理**两层**延续（与 WrapperLocator.collectExtendingChain 同构）：</p>
     * <ul>
     *  <li>调用层：外层调用的 qualifier 是当前链尾；</li>
     *  <li>**引用层**：链式调用中 entry.parent 是外层调用的 methodExpression（PsiReferenceExpression），
     *      其 qualifier 才是 entry——漏掉这层则回溯第一步即 break，segments 恒为 [entry]，全部链段丢失
     *      （表名来自 entry 实参而 where/orderBy/limit/join 全丢的共性根因）。</li>
     * </ul>
     */
    private static List<PsiMethodCallExpression> collectSegments(PsiMethodCallExpression entry) {
        PsiMethodCallExpression outermost = entry;
        PsiElement cursor = entry.getParent();
        while (cursor != null) {
            if (cursor instanceof PsiMethodCallExpression cursorCall
                    && cursorCall.getMethodExpression().getQualifierExpression() == outermost) {
                outermost = cursorCall;
                cursor = cursorCall.getParent();
            } else if (cursor instanceof PsiReferenceExpression cursorRef
                    && cursorRef.getQualifierExpression() == outermost) {
                if (!(cursorRef.getParent() instanceof PsiMethodCallExpression next)) {
                    break;
                }
                outermost = next;
                cursor = next.getParent();
            } else {
                break;
            }
        }
        return ArgResolver.collectCallChain(outermost);
    }

    /** 实体实参解析：Class 字面量（queryable(Topic.class)/insertable(Topic.class)）或实体类型实参。 */
    private static PsiClass resolveEntityArg(PsiMethodCallExpression call) {
        for (PsiExpression expr : call.getArgumentList().getExpressions()) {
            PsiClass resolved = resolveEntityArgFrom(expr);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static PsiClass resolveEntityArgFrom(PsiExpression expr) {
        if (expr instanceof PsiClassObjectAccessExpression access) {
            return access.getOperand().getType() instanceof PsiClassType classType ? classType.resolve() : null;
        }
        return expr.getType() instanceof PsiClassType classType ? classType.resolve() : null;
    }

    /** 布尔前缀标志（true/false 字面量或运行时布尔占位）。 */
    private static final class Flag {
        final String source;
        final boolean enabled;

        Flag(String source, boolean enabled) {
            this.source = source;
            this.enabled = enabled;
        }
    }

    /** like 族 op 的 (position, negated) 二元组。 */
    private static final class LikeOp {
        final LikePosition position;
        final boolean negated;

        LikeOp(LikePosition position, boolean negated) {
            this.position = position;
            this.negated = negated;
        }
    }

    private static String joinNotes(List<String> notes) {
        StringBuilder sb = new StringBuilder();
        for (String note : notes) {
            sb.append("\n-- ").append(note);
        }
        return sb.toString();
    }

    private static EqPreview previewOf(PsiMethodCallExpression entry, SqlStatement statement, GeneratedSql generated,
                                       List<String> notes, TableInfo table, ParseCtx ctx, String warning) {
        String sql = notes.isEmpty() ? generated.getSql() : generated.getSql() + joinNotes(notes);
        String referenceName = entry.getMethodExpression().getReferenceName();
        return new EqPreview(referenceName != null ? referenceName : "",
                statement,
                sql,
                generated.getUnresolvedParams(),
                generated.getParamHints(),
                table.getTableName(),
                table.getAllColumnNames(),
                warning);
    }

    /** 转换过程上下文（每次 convert 独立实例——object 无状态可重入）。 */
    private static final class ParseCtx {
        final TableInfo table;
        final PsiClass entityClass;
        final boolean resolveVariables;
        List<Condition> conditions = new ArrayList<>();
        List<OrderByItem> orderBy = new ArrayList<>();
        List<JoinClause> joins = new ArrayList<>();
        List<SetClause> sets = new ArrayList<>();
        LimitClause limit;
        String mainAlias;
        private int toggleSeq = 0;

        /** EQ8：generatedKeyColumns 惰性求值缓存（对应 Kotlin by lazy）。 */
        private Set<String> generatedKeyColumnsCache;

        ParseCtx(TableInfo table, PsiClass entityClass, boolean resolveVariables) {
            this.table = table;
            this.entityClass = entityClass;
            this.resolveVariables = resolveVariables;
        }

        /** EQ8：@Column(primaryKey = true, generatedKey = true) 的主键列（INSERT 排除）。 */
        Set<String> generatedKeyColumns() {
            if (generatedKeyColumnsCache == null) {
                Set<String> result = new LinkedHashSet<>();
                for (PsiField field : entityClass.getAllFields()) {
                    PsiAnnotation column = JpaMetadataReader.findFlex(field, JpaMetadataReader.FQN_EASY_QUERY_COLUMN);
                    if (column == null) {
                        continue;
                    }
                    String fieldName = field.getName();
                    if (fieldName == null) {
                        continue;
                    }
                    if (Boolean.TRUE.equals(JpaMetadataReader.attrBoolean(column, "primaryKey"))
                            && Boolean.TRUE.equals(JpaMetadataReader.attrBoolean(column, "generatedKey"))) {
                        result.add(JpaMetadataReader.columnValueOrSnake(field, fieldName));
                    }
                }
                generatedKeyColumnsCache = result;
            }
            return generatedKeyColumnsCache;
        }

        /**
         * 布尔前缀标志解析（true/false 字面量或运行时布尔占位）。
         *
         * <p>R49 口径统一：字面量 true 与 false 一样携带源码文本（{@code "true"}/{@code "false"}）——null 保留给
         * 「非布尔重载」（见 ConditionToggle.flagSource 契约）。此前 true 被折叠成 null，与 QueryWrapperParser 侧
         * 不一致，且使同一用户表达式在两条解析链上标注不同（R49 Wrapper 布尔条件探镜据此显示）。
         * {@code enabled} 语义不变（字面量 true 仍默认勾选）。
         */
        Flag flagOf(PsiExpression expr) {
            SqlValue value = ExpressionEvaluator.evaluate(expr);
            if (value instanceof SqlValue.Literal literal) {
                if ("true".equals(literal.getUnquoted())) {
                    return new Flag("true", true);
                }
                if ("false".equals(literal.getUnquoted())) {
                    return new Flag("false", false);
                }
                return null;
            }
            if (value instanceof SqlValue.Placeholder placeholder) {
                return new Flag(placeholder.getSource(), true);
            }
            return null;
        }

        /** WrapperArg 实参形态的布尔前缀标志解析。 */
        Flag flagOfArg(WrapperArg arg) {
            if (arg instanceof WrapperArg.ColumnArg || arg instanceof WrapperArg.NestedArg) {
                return null;
            }
            SqlValue value = WrapperConverterKt.asValue(arg);
            if (value == null) {
                return null;
            }
            if (value instanceof SqlValue.Literal literal) {
                if ("true".equals(literal.getUnquoted())) {
                    return new Flag("true", true);
                }
                if ("false".equals(literal.getUnquoted())) {
                    return new Flag("false", false);
                }
                return null;
            }
            if (value instanceof SqlValue.Placeholder placeholder) {
                return new Flag(placeholder.getSource(), true);
            }
            return null;
        }

        void addConditions(List<Condition> conditions) {
            this.conditions.addAll(conditions);
        }

        void addOrderBy(List<OrderByItem> items) {
            orderBy.addAll(items);
        }

        void addJoin(JoinClause join) {
            if (join != null) {
                joins.add(join);
            }
        }

        void addSets(List<SetClause> sets) {
            this.sets.addAll(sets);
        }

        /** join 存在时主表别名 t——渲染前统一回填条件/排序列别名（条件构建期别名未定，逐项补齐）。 */
        void applyMainAlias(String alias) {
            mainAlias = alias;
            if (alias == null) {
                return;
            }
            List<Condition> mappedConditions = new ArrayList<>();
            for (Condition condition : conditions) {
                mappedConditions.add(withMainAlias(condition, alias));
            }
            conditions = mappedConditions;
            List<OrderByItem> mappedOrderBy = new ArrayList<>();
            for (OrderByItem item : orderBy) {
                mappedOrderBy.add(new OrderByItem(WrapperConverterKt.withTableAlias(item.getColumn(), alias),
                        item.isAsc()));
            }
            orderBy = mappedOrderBy;
        }

        /** proxy 列访问器方法名（Java 属性名）→ 数据库列名（@Column 注解映射，缺省 snake 兜底）。 */
        String columnNameOf(String property) {
            ColumnInfo column = table.columnByField(property);
            return column != null ? column.getColumnName() : NamingStrategy.INSTANCE.toSnakeCase(property);
        }

        /** toggle 构造（id 按 pre-order 递增）。 */
        ConditionToggle toggleOf(Condition condition, Flag flag) {
            return new ConditionToggle("c" + (toggleSeq++), flag.source, flag.enabled,
                    SqlGenerator.renderConditionText(condition));
        }

        ColumnRef columnWithAliasRef(String column) {
            return mainAlias != null
                    ? new ColumnRef(mainAlias, columnNameOf(column))
                    : new ColumnRef(columnNameOf(column));
        }

        private static Condition withMainAlias(Condition condition, String alias) {
            if (condition instanceof Condition.Simple simple) {
                return simple.copy(WrapperConverterKt.withTableAlias(simple.getColumn(), alias),
                        simple.getOp(), simple.getValue(), simple.getToggle());
            }
            if (condition instanceof Condition.Unary unary) {
                return unary.copy(WrapperConverterKt.withTableAlias(unary.getColumn(), alias),
                        unary.getOp(), unary.getToggle());
            }
            if (condition instanceof Condition.In inCondition) {
                return inCondition.copy(WrapperConverterKt.withTableAlias(inCondition.getColumn(), alias),
                        inCondition.isNegated(), inCondition.getValues(), inCondition.getToggle());
            }
            if (condition instanceof Condition.Between between) {
                return between.copy(WrapperConverterKt.withTableAlias(between.getColumn(), alias),
                        between.isNegated(), between.getLo(), between.getHi(), between.getToggle());
            }
            if (condition instanceof Condition.Like like) {
                return like.copy(WrapperConverterKt.withTableAlias(like.getColumn(), alias),
                        like.isNegated(), like.getPattern(), like.getPosition(), like.getToggle());
            }
            if (condition instanceof Condition.ColumnComparison comparison) {
                return comparison.copy(WrapperConverterKt.withTableAlias(comparison.getLeft(), alias),
                        comparison.getRight(), comparison.getToggle());
            }
            if (condition instanceof Condition.Raw raw) {
                return raw;
            }
            if (condition instanceof Condition.Group group) {
                List<Condition> children = new ArrayList<>();
                for (Condition child : group.getChildren()) {
                    children.add(withMainAlias(child, alias));
                }
                return group.copy(group.getConnector(), children, group.getToggle(), group.isNegated());
            }
            return condition;
        }
    }
}
