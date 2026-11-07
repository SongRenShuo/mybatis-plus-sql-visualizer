package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver;
import cn.song.mybatisplus.visualizer.psi.jpa.RepositoryBaseTypes;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.ParamQuote;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JpaSpecificationExecutor 调用转换器（T-J5）。
 *
 * 把 `userRepository.findAll(spec)` / `findOne(...)` / `count(...)` / `exists(...)` / `delete(...)`
 * 的 Specification 实参当作谓词源，解析 lambda 体（`cb.equal(root.get("x"), ?)` 等）与
 * Specification 组合器（where/not/and/or/allOf/anyOf），生成 SELECT / COUNT / DELETE 语句。
 *
 * ## 识别（[isSpecificationExecutorCall]）
 *
 *  1. receiver 类型父链含 Spring Data Repository 基类（与 DerivedMethodLocator /
 *     JpaCrudOperationConverter 名单对齐）；
 *  2. 方法名 ∈ [EXECUTOR_METHODS]（JpaSpecificationExecutor 8 方法去 findBy fluent 后的可渲染子集）；
 *  3. 首实参「Specification 形态」：lambda（`->` 三参形态）或类型/初始化式含 Specification
 *     （`Example` 实参不命中——归 [QueryByExampleConverter]；无参 `findAll()` 也不命中——归
 *     JpaCrudOperationConverter 的 CRUD 语义）。
 *
 * ## 谓词解析面（lambda 体 / 组合器叶子）
 *
 *  - 比较：cb.equal/notEqual/greaterThan(greaterThanOrEqualTo/gt/ge)/lessThan(.../lt/le) → Simple；
 *  - 一元：isNull/isNotNull；区间：between；模糊：like/notLike；集合：in（链式 .value()/直接实参）；
 *  - 逻辑：cb.and/or → Group；cb.not → 取反组；isTrue/isFalse → = TRUE/FALSE；
 *  - 路径：`root.get("userName")` / `root.get(Xxx_.userName)`（静态字段引用取引用名）；
 *    嵌套路径 `root.get("a").get("b")` 静态降级注释（关联列需运行时元数据）；
 *  - 值：字面量/可追踪变量内联（[ExpressionEvaluator]），方法参数等运行时值 `?` 占位 + 参数行；
 *  - 组合：`Specification.where/and/or/not/allOf/anyOf`、spec 变量链式 `.and(...)/.or(...)`、
 *    谓词变量（`Predicate p = cb.equal(...)`）有界追踪（声明先于使用、深度 ≤ 5）。
 *
 * ## 降级（绝不抛异常）
 *
 * 无法解析的叶子调用 → `Condition.Raw` 原样注释透传；复杂组合整体不可解析 → SQL 呈现
 * 「运行时构建」注释卡；receiver 非 Spring Data 家族 / 无 Specification 实参 → [convert] 返回 null
 * （交回 JpaCrudOperationConverter / MP 批量路径，存量行为零变化）。
 */
public final class SpecificationQueryConverter {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final SpecificationQueryConverter INSTANCE = new SpecificationQueryConverter();

    private SpecificationQueryConverter() {
    }

    // ------------------------------------------------------------------
    // 结果模型
    // ------------------------------------------------------------------

    /**
     * Specification 预览结果。
     *
     * @param statement    语句模型（SELECT/COUNT/DELETE），供面板条件交互复用
     * @param sql          渲染 SQL（含语义注释行）
     * @param paramSources `?` 占位参数来源列表（按出现顺序）
     * @param paramHints   与 [paramSources] 并行的回填提示
     * @param tableName    实体表名
     * @param columns      实体列名（面板上下文）
     * @param warning      风险警示（delete(Spec) 无条件时 BlockAttack 风格）
     */
    public static final class SpecificationPreview {
        private final String methodName;
        private final SqlStatement statement;
        private final String sql;
        private final List<String> paramSources;
        private final List<ParamHint> paramHints;
        private final String tableName;
        private final List<String> columns;
        private final String warning;

        public SpecificationPreview(String methodName, SqlStatement statement, String sql,
                                    List<String> paramSources, List<ParamHint> paramHints,
                                    String tableName, List<String> columns, String warning) {
            this.methodName = methodName;
            this.statement = statement;
            this.sql = sql;
            this.paramSources = paramSources;
            this.paramHints = paramHints;
            this.tableName = tableName;
            this.columns = columns;
            this.warning = warning;
        }

        public SpecificationPreview(String methodName, SqlStatement statement, String sql) {
            this(methodName, statement, sql, List.of(), List.of(), "", List.of(), null);
        }

        public String getMethodName() { return methodName; }
        public SqlStatement getStatement() { return statement; }
        public String getSql() { return sql; }
        public List<String> getParamSources() { return paramSources; }
        public List<ParamHint> getParamHints() { return paramHints; }
        public String getTableName() { return tableName; }
        public List<String> getColumns() { return columns; }
        public String getWarning() { return warning; }
    }

    // ------------------------------------------------------------------
    // 名单
    // ------------------------------------------------------------------

    /** Spring Data Repository 基类 FQN（SD6 起统一走 [RepositoryBaseTypes] 名单，receiver 家族判定与其他三处对齐）。 */
    private static final Set<String> REPOSITORY_BASE_TYPES = RepositoryBaseTypes.FQNS;

    /** 基类简单名（canonicalText 不可解析时的兜底）。 */
    private static final Set<String> REPOSITORY_BASE_SIMPLE_NAMES = RepositoryBaseTypes.SIMPLE_NAMES;

    /** JpaSpecificationExecutor 家族方法名（锚点 + 转换共用）。 */
    public static final Set<String> EXECUTOR_METHODS = Set.of(
            "findAll", "findOne", "count", "exists", "delete", "findBy");

    /** cb 比较方法 → SQL 操作符。 */
    private static final Map<String, String> COMPARISON_OPS = Map.of(
            "equal", "=",
            "notEqual", "!=",
            "greaterThan", ">",
            "greaterThanOrEqualTo", ">=",
            "gt", ">",
            "ge", ">=",
            "lessThan", "<",
            "lessThanOrEqualTo", "<=",
            "lt", "<",
            "le", "<=");

    /** 谓词追踪最大深度（Specification 组合变量链）。 */
    private static final int MAX_TRACE_DEPTH = 5;

    // ------------------------------------------------------------------
    // 公开 API
    // ------------------------------------------------------------------

    /**
     * 调用是否为 Specification executor 形态（锚点判定与转换同源，宁可无图标不可死图标）。
     */
    public static boolean isSpecificationExecutorCall(PsiMethodCallExpression call) {
        try {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null) return false;
            if (!EXECUTOR_METHODS.contains(methodName)) return false;
            if (call.getArgumentList().getExpressions().length == 0) return false;
            if (!hasRepositoryReceiver(call)) return false;
            return isSpecificationLike(call.getArgumentList().getExpressions()[0]);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 转换 Specification executor 调用。
     *
     * @param call             方法调用表达式
     * @param resolveVariables 变量求值开关（false 时实参一律 `?` 占位）
     * @return 预览结果；receiver 非 Spring Data 家族 / 无 Specification 实参 / 实体不可解析时 null
     */
    public static SpecificationPreview convert(PsiMethodCallExpression call) {
        return convert(call, true);
    }

    public static SpecificationPreview convert(PsiMethodCallExpression call, boolean resolveVariables) {
        try {
            return convertInternal(call, resolveVariables);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // receiver / 实体解析
    // ------------------------------------------------------------------

    private static SpecificationPreview convertInternal(PsiMethodCallExpression call, boolean resolveVariables) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return null;
        if (!EXECUTOR_METHODS.contains(methodName)) return null;
        PsiExpression[] argExpressions = call.getArgumentList().getExpressions();
        if (argExpressions.length == 0) return null;
        PsiExpression firstArg = argExpressions[0];
        if (!isSpecificationLike(firstArg)) return null;
        if (!hasRepositoryReceiver(call)) return null;

        PsiClass entityClass = resolveRepositoryEntity(call);
        if (entityClass == null) return null;
        TableInfo table = new JpaEntityResolver().resolve(entityClass);
        if (table.getEntityClassName().isBlank()) return null;

        ParseContext parseCtx = new ParseContext(resolveVariables);
        List<Condition> conditions = resolveSpecificationArg(firstArg, table, parseCtx, 0);

        List<String> notes = new ArrayList<>(parseCtx.notes);
        if (parseCtx.unresolvedCombo) {
            notes.add("Specification 组合含静态不可解析片段（嵌套变量/远端组合），仅呈现已解析部分");
        }

        StringBuilder columnListBuilder = new StringBuilder();
        for (ColumnInfo column : table.getColumns()) {
            if (columnListBuilder.length() > 0) columnListBuilder.append(", ");
            columnListBuilder.append(column.getColumnName());
        }
        String selectColumnList = columnListBuilder.length() == 0 ? "*" : columnListBuilder.toString();
        String from = "FROM " + table.getTableName();

        if (methodName.equals("count")) {
            SqlStatement statement = new SelectStatement(table,
                    List.of(new ColumnRef(null, "*", null, "COUNT")), null, conditions);
            return new SpecificationPreview(methodName, statement,
                    renderWithNotes("SELECT COUNT(*)\n" + from, conditions, notes),
                    parseCtx.paramSources, parseCtx.paramHints,
                    table.getTableName(), table.getAllColumnNames(), null);
        }
        if (methodName.equals("exists")) {
            SqlStatement statement = new SelectStatement(table,
                    List.of(new ColumnRef(null, "1")), null, conditions);
            List<String> existsNotes = new ArrayList<>(notes);
            existsNotes.add("exists：命中即真语义（运行时渲染 SELECT 1 ... LIMIT 1 形态）");
            return new SpecificationPreview(methodName, statement,
                    renderWithNotes("SELECT 1\n" + from, conditions, existsNotes),
                    parseCtx.paramSources, parseCtx.paramHints,
                    table.getTableName(), table.getAllColumnNames(), null);
        }
        if (methodName.equals("delete")) {
            SqlStatement statement = new DeleteStatement(table, conditions);
            String warning = conditions.isEmpty() ? "⚠ [BlockAttack] 无 WHERE 条件，将删除全表数据！" : null;
            return new SpecificationPreview(methodName, statement,
                    renderWithNotes("DELETE FROM " + table.getTableName(), conditions, notes),
                    parseCtx.paramSources, parseCtx.paramHints,
                    table.getTableName(), table.getAllColumnNames(), warning);
        }
        // findAll / findOne / findBy(fluent) 同为实体 SELECT
        SqlStatement statement = new SelectStatement(table, List.of(), null, conditions);
        List<String> selectNotes = new ArrayList<>(notes);
        if (methodName.equals("findBy")) {
            selectNotes.add("findBy(spec, function) fluent 形态：按 findAll 语义呈现（fetch function 运行时回调不解析）");
        }
        return new SpecificationPreview(methodName, statement,
                renderWithNotes("SELECT " + selectColumnList + "\n" + from, conditions, selectNotes),
                parseCtx.paramSources, parseCtx.paramHints,
                table.getTableName(), table.getAllColumnNames(), null);
    }

    /** 条件 → WHERE SQL 文本（顶层 AND 连接；组/原样透传）。 */
    private static String whereText(List<Condition> conditions) {
        if (conditions.isEmpty()) return null;
        StringBuilder rendered = new StringBuilder();
        for (Condition condition : conditions) {
            if (rendered.length() > 0) rendered.append("\n  AND ");
            rendered.append(ConditionText.render(condition));
        }
        return rendered.toString();
    }

    /** 拼装 SQL + WHERE + 语义注释行（`-- ` 前缀，对齐 JpaCrudOperationConverter 惯例）。 */
    private static String renderWithNotes(String head, List<Condition> conditions, List<String> notes) {
        StringBuilder sb = new StringBuilder(head);
        String where = whereText(conditions);
        if (where != null) {
            sb.append("\nWHERE ").append(where);
        }
        for (String note : notes) {
            if (!note.isBlank()) sb.append("\n-- ").append(note);
        }
        return sb.toString();
    }

    /** Condition → SQL 文本渲染器（与 SqlGenerator 条件渲染同形，供字符串拼装路径复用）。 */
    private static final class ConditionText {
        static String render(Condition condition) {
            if (condition instanceof Condition.Simple simple) {
                return simple.getColumn().getQualifiedName() + " " + simple.getOp() + " "
                        + renderValue(simple.getValue());
            }
            if (condition instanceof Condition.Unary unary) {
                return unary.getColumn().getQualifiedName() + " " + unary.getOp();
            }
            if (condition instanceof Condition.In in) {
                String op = in.isNegated() ? "NOT IN" : "IN";
                StringBuilder values = new StringBuilder();
                for (SqlValue value : in.getValues()) {
                    if (values.length() > 0) values.append(", ");
                    values.append(renderValue(value));
                }
                return in.getColumn().getQualifiedName() + " " + op + " (" + values + ")";
            }
            if (condition instanceof Condition.Between between) {
                return between.getColumn().getQualifiedName() + " "
                        + (between.isNegated() ? "NOT BETWEEN" : "BETWEEN") + " "
                        + renderValue(between.getLo()) + " AND " + renderValue(between.getHi());
            }
            if (condition instanceof Condition.Like like) {
                String op = like.isNegated() ? "NOT LIKE" : "LIKE";
                return like.getColumn().getQualifiedName() + " " + op + " " + renderValue(like.getPattern());
            }
            if (condition instanceof Condition.Group group) {
                StringBuilder inner = new StringBuilder();
                for (Condition child : group.getChildren()) {
                    if (inner.length() > 0) inner.append(" ").append(group.getConnector().name()).append(" ");
                    inner.append(render(child));
                }
                String body = "(" + inner + ")";
                return group.isNegated() ? "NOT " + body : body;
            }
            if (condition instanceof Condition.ColumnComparison comparison) {
                return comparison.getLeft().getQualifiedName() + " = " + comparison.getRight().getQualifiedName();
            }
            if (condition instanceof Condition.Raw raw) {
                return raw.getSqlText();
            }
            throw new IllegalArgumentException("未知的 Condition 类型: " + condition);
        }

        private static String renderValue(SqlValue value) {
            if (value instanceof SqlValue.Literal literal) return literal.getRaw();
            if (value instanceof SqlValue.Placeholder placeholder) return "?";
            return "?";
        }
    }

    // ------------------------------------------------------------------
    // Specification 实参解析（组合器树）
    // ------------------------------------------------------------------

    /** 解析上下文：参数行收集 + 注释 + 降级标志。 */
    private static final class ParseContext {
        final boolean resolveVariables;
        final List<String> paramSources = new ArrayList<>();
        final List<ParamHint> paramHints = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        boolean unresolvedCombo = false;

        ParseContext(boolean resolveVariables) {
            this.resolveVariables = resolveVariables;
        }

        void addParam(String source, ParamHint hint) {
            paramSources.add(source);
            paramHints.add(hint);
        }
    }

    /**
     * 解析 Specification 实参（lambda / 变量 / 组合器链）→ 顶层条件列表（AND 连接）。
     */
    private static List<Condition> resolveSpecificationArg(
            PsiExpression arg, TableInfo table, ParseContext ctx, int depth) {
        SpecNode node = resolveSpecNode(arg, table, ctx, depth);
        return flattenNode(node, ctx);
    }

    /** Specification 组合节点（有界建模）。 */
    private abstract static class SpecNode {
        /** lambda / 谓词变量已解析出的条件（AND 连接）。 */
        static final class Preds extends SpecNode {
            final List<Condition> conditions;

            Preds(List<Condition> conditions) {
                this.conditions = conditions;
            }
        }

        static final class And extends SpecNode {
            final List<SpecNode> parts;

            And(List<SpecNode> parts) {
                this.parts = parts;
            }
        }

        static final class Or extends SpecNode {
            final List<SpecNode> parts;

            Or(List<SpecNode> parts) {
                this.parts = parts;
            }
        }

        static final class Not extends SpecNode {
            final SpecNode inner;

            Not(SpecNode inner) {
                this.inner = inner;
            }
        }

        static final SpecNode UNKNOWN = new SpecNode() {
        };
    }

    private static SpecNode resolveSpecNode(PsiExpression expr, TableInfo table, ParseContext ctx, int depth) {
        if (expr == null || depth > MAX_TRACE_DEPTH) {
            ctx.unresolvedCombo = true;
            return SpecNode.UNKNOWN;
        }
        if (expr instanceof PsiLambdaExpression lambda) {
            return new SpecNode.Preds(parseLambdaBody(lambda, table, ctx));
        }
        if (expr instanceof PsiMethodCallExpression call) {
            return resolveCombinatorCall(call, table, ctx, depth);
        }
        if (expr instanceof PsiReferenceExpression reference) {
            PsiElement resolved = reference.resolve();
            PsiElement initializer = resolved instanceof PsiVariable variable ? variable.getInitializer() : null;
            if (initializer instanceof PsiExpression initializerExpr) {
                return resolveSpecNode(initializerExpr, table, ctx, depth + 1);
            }
            ctx.unresolvedCombo = true;
            return SpecNode.UNKNOWN;
        }
        ctx.unresolvedCombo = true;
        return SpecNode.UNKNOWN;
    }

    /** 组合器调用解析：Specification.where/not/allOf/anyOf 与 spec 变量链式 and/or。 */
    private static SpecNode resolveCombinatorCall(PsiMethodCallExpression call, TableInfo table,
                                                  ParseContext ctx, int depth) {
        String name = call.getMethodExpression().getReferenceName();
        if (name == null) return SpecNode.UNKNOWN;
        PsiExpression[] args = call.getArgumentList().getExpressions();
        // 链式 and/or 的 receiver：Specification 静态工厂（qualifier 文本 Specification）或 spec 变量
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        String qualifierText = qualifier != null && qualifier.getText() != null ? qualifier.getText() : "";
        boolean isStaticFactory = substringAfterLast(qualifierText, '.').equals("Specification");
        if (isStaticFactory && name.equals("where")) {
            return resolveSpecNode(args.length > 0 ? args[0] : null, table, ctx, depth + 1);
        }
        if (isStaticFactory && name.equals("not")) {
            return new SpecNode.Not(resolveSpecNode(args.length > 0 ? args[0] : null, table, ctx, depth + 1));
        }
        if (isStaticFactory && name.equals("allOf")) {
            List<SpecNode> parts = new ArrayList<>();
            for (PsiExpression arg : args) {
                parts.add(resolveSpecNode(arg, table, ctx, depth + 1));
            }
            return new SpecNode.And(parts);
        }
        if (isStaticFactory && name.equals("anyOf")) {
            List<SpecNode> parts = new ArrayList<>();
            for (PsiExpression arg : args) {
                parts.add(resolveSpecNode(arg, table, ctx, depth + 1));
            }
            return new SpecNode.Or(parts);
        }
        if (name.equals("and")) {
            List<SpecNode> parts = new ArrayList<>();
            parts.add(resolveSpecNode(qualifier, table, ctx, depth + 1));
            for (PsiExpression arg : args) {
                parts.add(resolveSpecNode(arg, table, ctx, depth + 1));
            }
            return new SpecNode.And(parts);
        }
        if (name.equals("or")) {
            List<SpecNode> parts = new ArrayList<>();
            parts.add(resolveSpecNode(qualifier, table, ctx, depth + 1));
            for (PsiExpression arg : args) {
                parts.add(resolveSpecNode(arg, table, ctx, depth + 1));
            }
            return new SpecNode.Or(parts);
        }
        // Specification 变量上的其他方法（isSatisfiedBy 等）不解析
        ctx.unresolvedCombo = true;
        return SpecNode.UNKNOWN;
    }

    /** 组合树扁平化：顶层 AND 展平；OR/NOT 物化为 Group。 */
    private static List<Condition> flattenNode(SpecNode node, ParseContext ctx) {
        if (node instanceof SpecNode.Preds preds) {
            List<Condition> result = new ArrayList<>();
            for (Condition condition : preds.conditions) {
                result.addAll(flattenAndGroup(condition));
            }
            return result;
        }
        if (node instanceof SpecNode.And and) {
            List<Condition> result = new ArrayList<>();
            for (SpecNode part : and.parts) {
                result.addAll(flattenNode(part, ctx));
            }
            return result;
        }
        if (node instanceof SpecNode.Or or) {
            List<Condition> children = new ArrayList<>();
            for (SpecNode part : or.parts) {
                children.addAll(flattenNode(part, ctx));
            }
            if (children.isEmpty()) return List.of();
            return List.of(new Condition.Group(Connector.OR, children, null));
        }
        if (node instanceof SpecNode.Not not) {
            List<Condition> children = flattenNode(not.inner, ctx);
            if (children.isEmpty()) return List.of();
            return List.of(new Condition.Group(Connector.AND, children, null, true));
        }
        return List.of(); // SpecNode.UNKNOWN
    }

    /** 顶层非取反 AND 组展平（`cb.and(a, b)` 与条件列表语义一致，渲染按顶层 AND 连接）。 */
    private static List<Condition> flattenAndGroup(Condition condition) {
        if (condition instanceof Condition.Group group
                && group.getConnector() == Connector.AND && !group.isNegated()) {
            List<Condition> result = new ArrayList<>();
            for (Condition child : group.getChildren()) {
                result.addAll(flattenAndGroup(child));
            }
            return result;
        }
        return List.of(condition);
    }

    // ------------------------------------------------------------------
    // lambda 体谓词解析
    // ------------------------------------------------------------------

    /**
     * 解析 Specification lambda 体（`(root, query, cb) -> ...`）。
     *
     * 表达式体 → 单谓词；块体 → `return` 表达式与 `query.where(...)` 调用收集（AND 连接）。
     */
    private static List<Condition> parseLambdaBody(PsiLambdaExpression lambda, TableInfo table, ParseContext ctx) {
        PsiParameter[] params = lambda.getParameterList().getParameters();
        if (params.length < 3) {
            ctx.unresolvedCombo = true;
            return List.of();
        }
        LambdaContext lambdaCtx = new LambdaContext(
                params[0].getName() != null ? params[0].getName() : "root",
                params[1].getName() != null ? params[1].getName() : "query",
                params[2].getName() != null ? params[2].getName() : "cb",
                table,
                ctx);
        PsiElement body = lambda.getBody();
        if (body == null) return List.of();
        List<Condition> conditions = new ArrayList<>();
        if (body instanceof PsiExpression bodyExpr) {
            Condition condition = parsePredicate(bodyExpr, lambdaCtx);
            if (condition != null) conditions.add(condition);
        } else if (body instanceof PsiCodeBlock codeBlock) {
            // return <谓词>/null 语句 + query.where(p1, p2) 调用（谓词变量优先按声明登记解析）
            for (PsiStatement statement : codeBlock.getStatements()) {
                collectBlockPredicates(statement, lambdaCtx, conditions);
            }
        }
        return conditions;
    }

    private static final class LambdaContext {
        final String rootParamName;
        final String queryParamName;
        final String cbParamName;
        final TableInfo table;
        final ParseContext ctx;
        /** 谓词变量登记：`Predicate p = cb.equal(...)`（块体内声明先于使用）。 */
        final Map<String, Condition> predicateVars = new LinkedHashMap<>();

        LambdaContext(String rootParamName, String queryParamName, String cbParamName,
                      TableInfo table, ParseContext ctx) {
            this.rootParamName = rootParamName;
            this.queryParamName = queryParamName;
            this.cbParamName = cbParamName;
            this.table = table;
            this.ctx = ctx;
        }
    }

    /** 块体单语句的谓词收集（声明/return/query.where）。 */
    private static void collectBlockPredicates(PsiStatement statement, LambdaContext lc, List<Condition> sink) {
        if (statement instanceof PsiDeclarationStatement declaration) {
            for (PsiElement element : declaration.getDeclaredElements()) {
                if (!(element instanceof PsiLocalVariable variable)) continue;
                PsiExpression initializer = variable.getInitializer();
                if (initializer == null) continue;
                Condition condition = parsePredicate(initializer, lc);
                if (condition != null) {
                    lc.predicateVars.put(variable.getName(), condition);
                    sink.add(condition);
                }
            }
        } else if (statement instanceof PsiReturnStatement returnStatement) {
            Condition condition = parsePredicate(returnStatement.getReturnValue(), lc);
            if (condition != null) sink.add(condition);
        } else if (statement instanceof PsiExpressionStatement expressionStatement) {
            if (!(expressionStatement.getExpression() instanceof PsiMethodCallExpression call)) return;
            // query.where(p1, p2)：谓词收集（cb.and 已由解析器处理组合）
            if ("where".equals(call.getMethodExpression().getReferenceName())) {
                for (PsiExpression arg : call.getArgumentList().getExpressions()) {
                    Condition condition = parsePredicate(arg, lc);
                    if (condition != null) sink.add(condition);
                }
            }
        }
    }

    /**
     * 谓词表达式 → Condition。识别 cb.* 条件方法与谓词变量引用；
     * 未知形态降级 Raw（原样注释透传，不产生错误 SQL）。
     */
    private static Condition parsePredicate(PsiExpression expr, LambdaContext lc) {
        if (expr == null) return null;
        if (expr instanceof PsiMethodCallExpression call) {
            String name = call.getMethodExpression().getReferenceName();
            if (name == null) return rawCondition(call);
            if (COMPARISON_OPS.containsKey(name)) return buildComparison(call, lc);
            switch (name) {
                case "isNull":
                    return unary(call, "IS NULL", lc);
                case "isNotNull":
                    return unary(call, "IS NOT NULL", lc);
                case "isTrue":
                    return booleanPredicate(call, "TRUE", lc);
                case "isFalse":
                    return booleanPredicate(call, "FALSE", lc);
                case "like":
                    return like(call, false, lc);
                case "notLike":
                    return like(call, true, lc);
                case "between":
                    return between(call, lc);
                case "in":
                    return inPredicate(call, lc);
                case "and":
                    return group(call, Connector.AND, lc);
                case "or":
                    return group(call, Connector.OR, lc);
                case "not": {
                    PsiExpression[] args = call.getArgumentList().getExpressions();
                    Condition inner = parsePredicate(args.length > 0 ? args[0] : null, lc);
                    if (inner == null) return rawCondition(call);
                    return new Condition.Group(Connector.AND, List.of(inner), null, true);
                }
                case "conjunction":
                    return new Condition.Raw("1 = 1");
                case "disjunction":
                    return new Condition.Raw("1 = 0");
                default:
                    break;
            }
            // 谓词变量引用（cb 之外：`p` 等已登记变量）→ 查登记表；其余降级 Raw
            if (isCbCall(call, lc)) return rawCondition(call);
            Condition variableCondition = predicateVarOrNull(call, lc);
            return variableCondition != null ? variableCondition : rawCondition(call);
        }
        // 纯变量引用 → 谓词变量登记表
        if (expr instanceof PsiReferenceExpression) {
            return predicateVarOrNull(expr, lc);
        }
        return expr instanceof PsiMethodCallExpression methodCall
                ? rawCondition(methodCall)
                : new Condition.Raw(expr.getText());
    }

    /** 谓词变量解析（登记表命中 → 已解析 Condition；未命中 → null）。 */
    private static Condition predicateVarOrNull(PsiExpression expr, LambdaContext lc) {
        String name = expr instanceof PsiReferenceExpression reference ? reference.getReferenceName() : null;
        if (name == null) return null;
        return lc.predicateVars.get(name);
    }

    private static boolean isCbCall(PsiMethodCallExpression call, LambdaContext lc) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        String text = qualifier != null && qualifier.getText() != null ? qualifier.getText() : null;
        return lc.cbParamName.equals(text);
    }

    private static Condition rawCondition(PsiMethodCallExpression call) {
        return new Condition.Raw("/* " + call.getText() + "（Specification 条件运行时构建，静态预览降级） */");
    }

    /** cb.equal(root.get("x"), value) → Simple；路径/值不可解析降级 Raw。 */
    private static Condition buildComparison(PsiMethodCallExpression call, LambdaContext lc) {
        String op = COMPARISON_OPS.get(call.getMethodExpression().getReferenceName());
        if (op == null) return rawCondition(call);
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length < 2) return rawCondition(call);
        ColumnRef column = columnRef(args[0], lc);
        if (column == null) return rawCondition(call);
        String fieldType = fieldTypeOf(args[0], lc);
        SqlValue value = evaluateValue(args[1], lc, ParamQuote.decide(fieldType),
                ParamQuote.isTemporal(fieldType));
        return new Condition.Simple(column, op, value, null);
    }

    /** 路径表达式的实体字段类型（列回填引号提示用；不可解析 null）。 */
    private static String fieldTypeOf(PsiExpression expr, LambdaContext lc) {
        if (!(expr instanceof PsiMethodCallExpression call)) return null;
        if (!"get".equals(call.getMethodExpression().getReferenceName())) return null;
        PsiExpression[] args = call.getArgumentList().getExpressions();
        PsiExpression arg = args.length > 0 ? args[0] : null;
        if (arg == null) return null;
        String fieldName = null;
        if (arg instanceof PsiLiteralExpression literal && literal.getValue() instanceof String literalText) {
            fieldName = literalText;
        } else if (arg instanceof PsiReferenceExpression reference) {
            fieldName = reference.getReferenceName();
        }
        if (fieldName == null) return null;
        ColumnInfo columnInfo = lc.table.columnByField(fieldName);
        return columnInfo != null ? columnInfo.getFieldType() : null;
    }

    private static Condition unary(PsiMethodCallExpression call, String op, LambdaContext lc) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        ColumnRef column = columnRef(args.length > 0 ? args[0] : null, lc);
        if (column == null) return rawCondition(call);
        return new Condition.Unary(column, op, null);
    }

    private static Condition booleanPredicate(PsiMethodCallExpression call, String literal, LambdaContext lc) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        ColumnRef column = columnRef(args.length > 0 ? args[0] : null, lc);
        if (column == null) return rawCondition(call);
        return new Condition.Simple(column, "=", new SqlValue.Literal(literal, literal), null);
    }

    private static Condition like(PsiMethodCallExpression call, boolean negated, LambdaContext lc) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length < 2) return rawCondition(call);
        ColumnRef column = columnRef(args[0], lc);
        if (column == null) return rawCondition(call);
        // Specification like 的模式自带 %（与 Criteria 一致），NONE 表示不额外拼 %
        return new Condition.Like(column, negated, evaluateValue(args[1], lc), LikePosition.NONE, null);
    }

    private static Condition between(PsiMethodCallExpression call, LambdaContext lc) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length < 3) return rawCondition(call);
        ColumnRef column = columnRef(args[0], lc);
        if (column == null) return rawCondition(call);
        boolean temporal = ParamQuote.isTemporal(fieldTypeOf(args[0], lc));
        return new Condition.Between(column, false,
                evaluateValue(args[1], lc, null, temporal),
                evaluateValue(args[2], lc, null, temporal), null);
    }

    /** cb.in(root.get("x"), v1, v2) / cb.in(x).value(v).value(v)。 */
    private static Condition inPredicate(PsiMethodCallExpression call, LambdaContext lc) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) return rawCondition(call);
        ColumnRef column = columnRef(args[0], lc);
        if (column == null) return rawCondition(call);
        List<SqlValue> values = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            values.add(evaluateValue(args[i], lc));
        }
        values.addAll(collectInChainValues(call, lc));
        if (values.isEmpty()) return rawCondition(call);
        return new Condition.In(column, false, values, null);
    }

    /** 沿 `.value(...)` 链收集值实参（cb.in(x).value(a).value(b)）。 */
    private static List<SqlValue> collectInChainValues(PsiMethodCallExpression call, LambdaContext lc) {
        List<SqlValue> values = new ArrayList<>();
        PsiElement current = call.getParent();
        while (true) {
            if (!(current instanceof PsiReferenceExpression reference)) break;
            if (!(reference.getParent() instanceof PsiMethodCallExpression chained)) break;
            if (!"value".equals(chained.getMethodExpression().getReferenceName())) break;
            for (PsiExpression arg : chained.getArgumentList().getExpressions()) {
                values.add(evaluateValue(arg, lc));
            }
            current = chained.getParent();
        }
        return values;
    }

    private static Condition group(PsiMethodCallExpression call, Connector connector, LambdaContext lc) {
        List<Condition> children = new ArrayList<>();
        for (PsiExpression arg : call.getArgumentList().getExpressions()) {
            Condition child = parsePredicate(arg, lc);
            if (child != null) children.add(child);
        }
        if (children.isEmpty()) return rawCondition(call);
        return new Condition.Group(connector, children, null);
    }

    // ------------------------------------------------------------------
    // 路径与值
    // ------------------------------------------------------------------

    /**
     * 路径表达式 → ColumnRef。
     *
     * `root.get("userName")` / `root.get(Xxx_.userName)`（静态字段引用取引用名）；
     * 嵌套路径 `root.get("a").get("b")` 静态降级（注释 Raw 由调用方处理——此处返回 null）。
     */
    private static ColumnRef columnRef(PsiExpression expr, LambdaContext lc) {
        if (!(expr instanceof PsiMethodCallExpression call)) return null;
        if (!"get".equals(call.getMethodExpression().getReferenceName())) return null;
        // receiver 必须是 root 参数（或 root.get 链——嵌套时不解析）
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (!(qualifier instanceof PsiReferenceExpression qualifierReference)
                || !lc.rootParamName.equals(qualifierReference.getReferenceName())) {
            return null;
        }
        PsiExpression[] args = call.getArgumentList().getExpressions();
        PsiExpression arg = args.length > 0 ? args[0] : null;
        if (arg == null) return null;
        String fieldName = null;
        if (arg instanceof PsiLiteralExpression literal && literal.getValue() instanceof String literalText) {
            fieldName = literalText;
        } else if (arg instanceof PsiReferenceExpression reference) {
            fieldName = reference.getReferenceName();
        }
        if (fieldName == null) return null;
        ColumnInfo columnInfo = lc.table.columnByField(fieldName);
        String columnName = columnInfo != null && columnInfo.getColumnName() != null
                ? columnInfo.getColumnName()
                : NamingStrategy.INSTANCE.toSnakeCase(fieldName);
        return new ColumnRef(columnName);
    }

    /** 值表达式求值：字面量/可追踪变量内联；运行时值 {@code ?} 占位 + 参数行登记。 */
    private static SqlValue evaluateValue(PsiExpression expr, LambdaContext lc) {
        return evaluateValue(expr, lc, null);
    }

    private static SqlValue evaluateValue(PsiExpression expr, LambdaContext lc, Boolean quoteHint) {
        return evaluateValue(expr, lc, quoteHint, false);
    }

    private static SqlValue evaluateValue(PsiExpression expr, LambdaContext lc, Boolean quoteHint,
                                          boolean temporal) {
        if (expr == null) return new SqlValue.Placeholder("?");
        SqlValue value = lc.ctx.resolveVariables
                ? ExpressionEvaluator.evaluate(expr)
                : new SqlValue.Placeholder(expr.getText());
        if (value instanceof SqlValue.Literal literal) return literal;
        if (value instanceof SqlValue.Placeholder) {
            String text = expr.getText();
            String source = text != null && !text.isBlank() ? text : "?";
            lc.ctx.addParam(source, new ParamHint(quoteHint, null, false, temporal));
            return new SqlValue.Placeholder(source);
        }
        return value;
    }

    // ------------------------------------------------------------------
    // 实参形态判定与实体解析
    // ------------------------------------------------------------------

    /** 首实参是否为 Specification 形态（lambda 三参形态 / 类型或初始化式含 Specification）。 */
    private static boolean isSpecificationLike(PsiExpression arg) {
        try {
            if (arg instanceof PsiLambdaExpression lambda) {
                // Specification.toPredicate(root, query, cb) 三参；两参 lambda（Root/Query）也为 Specification 变体
                return lambda.getParameterList().getParameters().length >= 2;
            }
            String typeText = arg.getType() instanceof PsiClassType classType
                    && classType.getCanonicalText() != null ? classType.getCanonicalText() : "";
            if (substringBefore(typeText, '<').endsWith("Specification")) return true;
            // 变量初始化式（Specification<UserEntity> spec = ...）类型即可判定；再兜底初始化式文本
            PsiElement resolved = arg instanceof PsiReferenceExpression reference ? reference.resolve() : null;
            PsiVariable variable = resolved instanceof PsiVariable resolvedVariable ? resolvedVariable : null;
            String varTypeText = variable != null && variable.getType() instanceof PsiClassType variableType
                    && variableType.getCanonicalText() != null ? variableType.getCanonicalText() : "";
            if (substringBefore(varTypeText, '<').endsWith("Specification")) return true;
            return variable != null && variable.getInitializer() != null
                    && variable.getInitializer().getText() != null
                    && variable.getInitializer().getText().contains("Specification");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** receiver 类型父链是否含 Spring Data Repository 基类。 */
    private static boolean hasRepositoryReceiver(PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) return false;
        PsiClassType varType;
        if (qualifier instanceof PsiReferenceExpression reference) {
            PsiElement resolved = reference.resolve();
            varType = resolved instanceof PsiVariable variable && variable.getType() instanceof PsiClassType declared
                    ? declared
                    : (qualifier.getType() instanceof PsiClassType fallback ? fallback : null);
        } else {
            varType = qualifier.getType() instanceof PsiClassType type ? type : null;
        }
        if (varType == null) return false;
        return isRepositoryType(varType);
    }

    private static boolean isRepositoryType(PsiClassType type) {
        String canonicalText = type.getCanonicalText();
        if (canonicalText == null) return false;
        String direct = substringBefore(canonicalText, '<');
        if (REPOSITORY_BASE_TYPES.contains(direct) || REPOSITORY_BASE_SIMPLE_NAMES.contains(direct)) return true;
        PsiClass clazz = type.resolve();
        if (clazz == null) return false;
        for (PsiType superType : clazz.getSuperTypes()) {
            String canonical = superType.getCanonicalText();
            if (canonical == null) continue;
            String simple = substringAfterLast(substringBefore(canonical, '<'), '.');
            if (startsWithAny(canonical, REPOSITORY_BASE_TYPES) || REPOSITORY_BASE_SIMPLE_NAMES.contains(simple)) {
                return true;
            }
        }
        return false;
    }

    /** Repository 泛型实体解析（Spring Data 基类第一个类型参数；不可解析回退变量类型首泛型）。 */
    private static PsiClass resolveRepositoryEntity(PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) return null;
        PsiClassType varType;
        if (qualifier instanceof PsiReferenceExpression reference) {
            PsiElement resolved = reference.resolve();
            varType = resolved instanceof PsiVariable variable && variable.getType() instanceof PsiClassType declared
                    ? declared
                    : (qualifier.getType() instanceof PsiClassType fallback ? fallback : null);
        } else {
            varType = qualifier.getType() instanceof PsiClassType type ? type : null;
        }
        if (varType == null) return null;
        PsiClass clazz = varType.resolve();
        if (clazz != null) {
            for (PsiType superType : clazz.getSuperTypes()) {
                PsiClass entity = entityFromSuperType(superType);
                if (entity != null) return entity;
            }
        }
        PsiType[] ownParameters = varType.getParameters();
        if (ownParameters.length > 0 && ownParameters[0] instanceof PsiClassType firstParam) {
            return firstParam.resolve();
        }
        return null;
    }

    /** superType 第一个类型参数（Spring Data 基类泛型约定：第一参数为实体类）。 */
    private static PsiClass entityFromSuperType(PsiType superType) {
        if (!(superType instanceof PsiClassType classType)) return null;
        if (classType.getParameters().length == 0) return null;
        return classType.getParameters()[0] instanceof PsiClassType firstParam ? firstParam.resolve() : null;
    }

    // ------------------------------------------------------------------
    // 通用辅助
    // ------------------------------------------------------------------

    private static String substringBefore(String text, char c) {
        if (text == null) return "";
        int index = text.indexOf(c);
        return index >= 0 ? text.substring(0, index) : text;
    }

    private static String substringAfterLast(String text, char c) {
        if (text == null) return "";
        int index = text.lastIndexOf(c);
        return index >= 0 ? text.substring(index + 1) : text;
    }

    private static boolean startsWithAny(String text, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) return true;
        }
        return false;
    }
}
