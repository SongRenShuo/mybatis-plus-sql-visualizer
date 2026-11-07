package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver;
import cn.song.mybatisplus.visualizer.psi.jpa.RepositoryBaseTypes;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.ParamQuote;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * QueryByExampleExecutor 调用转换器（T-J20）。
 *
 * 把 `userRepository.findAll(Example.of(probe[, matcher]))` / `findOne(...)` / `count(...)` / `exists(...)`
 * 的 Example 实参解析为等值 WHERE：probe 实体的「非 null 字段」（静态可得的 setter/字段赋值）
 * → `col = value`（字面量内联 / 运行时 `?` 占位）。
 *
 * ## 识别（[isExampleCall]）
 *
 *  1. receiver 类型父链含 Spring Data Repository 基类；
 *  2. 方法名 ∈ [EXAMPLE_METHODS]（QueryByExampleExecutor findOne/findAll/count/exists）；
 *  3. 首实参 Example 形态：类型 `Example` 或初始化式 `Example.of(...)`（Specification lambda 不命中——归
 *     [SpecificationQueryConverter]；无参 findAll() 归 JpaCrudOperationConverter）。
 *
 * ## ExampleMatcher 语义面（有界）
 *
 *  - `matching()/matchingAll()` → 默认 AND；`matchingAny()` → 条件 OR 连接；
 *  - `withIgnorePaths("age", ...)` → 剔除列（按实体字段名或列名匹配）；
 *  - `withIgnoreCase()` → 等值条件降级 `LOWER(col) = LOWER(v)`（Raw 形态，保语义可读）；
 *  - `withMatcher("field", GenericPropertyMatchers.contains()/startsWith()/endsWith()/exact())` →
 *    CONTAINING → `LIKE '%v%'`、STARTING → `'v%'`、ENDING → `'%v'`、EXACT → 等值；
 *  - `withStringMatcher(StringMatcher.CONTAINING/...)` → 全字段默认匹配器同上；
 *  - `withIncludeNulls()` / NullHandler → 运行时按 probe null 值生成 IS NULL，静态不可得 → 注释说明。
 *
 * ## 降级（绝不抛异常）
 *
 * probe 变量不可追踪（跨方法/构建器形态）→ SQL 呈现「运行时确定」注释卡；receiver 非 Spring Data
 * 家族 / 实参非 Example → 返回 null（交回既有管道，存量行为零变化）。
 */
public final class QueryByExampleConverter {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final QueryByExampleConverter INSTANCE = new QueryByExampleConverter();

    private QueryByExampleConverter() {
    }

    // ------------------------------------------------------------------
    // 结果模型
    // ------------------------------------------------------------------

    /**
     * Query By Example 预览结果。
     *
     * @param sql          渲染 SQL（含语义注释行）
     * @param paramSources `?` 占位参数来源列表（按出现顺序）
     * @param paramHints   与 [paramSources] 并行的回填提示（LIKE 条件带 likePosition）
     * @param tableName    实体表名
     * @param columns      实体列名
     * @param statement    语句模型（SELECT/COUNT），供上层复用
     */
    public static final class ExamplePreview {
        private final String methodName;
        private final String sql;
        private final List<String> paramSources;
        private final List<ParamHint> paramHints;
        private final String tableName;
        private final List<String> columns;
        private final SelectStatement statement;

        public ExamplePreview(String methodName, String sql, List<String> paramSources,
                              List<ParamHint> paramHints, String tableName, List<String> columns,
                              SelectStatement statement) {
            this.methodName = methodName;
            this.sql = sql;
            this.paramSources = paramSources;
            this.paramHints = paramHints;
            this.tableName = tableName;
            this.columns = columns;
            this.statement = statement;
        }

        public String getMethodName() { return methodName; }
        public String getSql() { return sql; }
        public List<String> getParamSources() { return paramSources; }
        public List<ParamHint> getParamHints() { return paramHints; }
        public String getTableName() { return tableName; }
        public List<String> getColumns() { return columns; }
        public SelectStatement getStatement() { return statement; }
    }

    // ------------------------------------------------------------------
    // 名单
    // ------------------------------------------------------------------

    /** Spring Data Repository 基类 FQN（SD6 起统一走 [RepositoryBaseTypes] 名单，与 SpecificationQueryConverter/JpaCrudOperationConverter 对齐）。 */
    private static final Set<String> REPOSITORY_BASE_TYPES = RepositoryBaseTypes.FQNS;

    private static final Set<String> REPOSITORY_BASE_SIMPLE_NAMES = RepositoryBaseTypes.SIMPLE_NAMES;

    /** QueryByExampleExecutor 家族方法名（锚点 + 转换共用）。 */
    public static final Set<String> EXAMPLE_METHODS = Set.of("findOne", "findAll", "count", "exists");

    // ------------------------------------------------------------------
    // 公开 API
    // ------------------------------------------------------------------

    /** 调用是否为 Query By Example 形态（锚点判定与转换同源）。 */
    public static boolean isExampleCall(PsiMethodCallExpression call) {
        try {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null) return false;
            if (!EXAMPLE_METHODS.contains(methodName)) return false;
            if (call.getArgumentList().getExpressions().length == 0) return false;
            if (!hasRepositoryReceiver(call)) return false;
            return isExampleLike(call.getArgumentList().getExpressions()[0]);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 转换 Query By Example 调用。
     *
     * @param call             方法调用表达式
     * @param resolveVariables 变量求值开关（false 时 probe 字段值一律 `?` 占位）
     * @return 预览结果；receiver 非 Spring Data 家族 / 实参非 Example / 实体不可解析时 null
     */
    public static ExamplePreview convert(PsiMethodCallExpression call) {
        return convert(call, true);
    }

    public static ExamplePreview convert(PsiMethodCallExpression call, boolean resolveVariables) {
        try {
            return convertInternal(call, resolveVariables);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 转换
    // ------------------------------------------------------------------

    private static ExamplePreview convertInternal(PsiMethodCallExpression call, boolean resolveVariables) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return null;
        if (!EXAMPLE_METHODS.contains(methodName)) return null;
        PsiExpression[] argExpressions = call.getArgumentList().getExpressions();
        if (argExpressions.length == 0) return null;
        PsiExpression firstArg = argExpressions[0];
        if (!isExampleLike(firstArg)) return null;
        if (!hasRepositoryReceiver(call)) return null;

        PsiClass entityClass = resolveRepositoryEntity(call);
        if (entityClass == null) return null;
        TableInfo table = new JpaEntityResolver().resolve(entityClass);
        if (table.getEntityClassName().isBlank()) return null;

        // Example.of(probe[, matcher])：实参直取或变量初始化式回溯
        PsiMethodCallExpression ofCall = exampleOfCall(firstArg);
        if (ofCall == null) {
            return degradeCard(methodName, table, "Example 实参不可静态追踪（跨方法传递/构建器形态）");
        }
        PsiExpression[] ofArgs = ofCall.getArgumentList().getExpressions();
        PsiExpression probeExpr = ofArgs.length > 0 ? ofArgs[0] : null;
        PsiExpression matcherExpr = ofArgs.length > 1 ? ofArgs[1] : null;

        Probe probe = probeExpr != null ? resolveProbe(probeExpr) : null;
        if (probe == null) {
            return degradeCard(methodName, table,
                    "probe 实体字段值运行时确定（静态不可得），WHERE 条件按非空属性生成");
        }

        Matcher matcher = matcherExpr != null ? resolveMatcher(matcherExpr) : null;
        if (matcher == null) matcher = new Matcher();
        Ctx ctx = new Ctx(resolveVariables);

        // probe 显式赋值字段 → 条件（按实体列顺序渲染，剔除 ignorePaths）
        List<Condition> conditions = new ArrayList<>();
        for (ProbeField field : probe.fields) {
            ColumnInfo columnInfo = table.columnByField(field.fieldName);
            String columnName = columnInfo != null && columnInfo.getColumnName() != null
                    ? columnInfo.getColumnName()
                    : NamingStrategy.INSTANCE.toSnakeCase(field.fieldName);
            if (matcher.isIgnored(field.fieldName, columnName)) continue;
            ColumnRef column = new ColumnRef(columnName);
            StringMatcher stringMatcher = matcher.matcherFor(field.fieldName);
            LikePosition likePosition;
            switch (stringMatcher) {
                case CONTAINING:
                    likePosition = LikePosition.ANYWHERE;
                    break;
                case STARTING:
                    likePosition = LikePosition.RIGHT;
                    break;
                case ENDING:
                    likePosition = LikePosition.LEFT;
                    break;
                default:
                    likePosition = null;
                    break;
            }
            SqlValue value = evaluateValue(field.valueExpr, ctx,
                    ParamQuote.decide(columnInfo != null ? columnInfo.getFieldType() : null), likePosition,
                    ParamQuote.isTemporal(columnInfo != null ? columnInfo.getFieldType() : null));
            conditions.add(buildFieldCondition(column, value, stringMatcher, matcher.ignoreCase));
        }

        List<String> notes = new ArrayList<>(ctx.notes);
        if (matcher.matchAny) notes.add("ExampleMatcher.matchingAny：任意属性匹配即命中（条件 OR 连接）");
        if (matcher.ignoreCase) notes.add("ExampleMatcher.withIgnoreCase：等值条件按 LOWER(col) = LOWER(?) 呈现");
        if (matcher.includeNulls != null) {
            notes.add("withIncludeNulls/NullHandler：probe 的 null 属性按 IS NULL 参与（静态不可得，运行时生成）");
        }
        if (probe.fields.isEmpty()) {
            notes.add("probe 无静态可得的非空属性：运行时按实际非 null 字段生成等值 WHERE");
        }

        List<Condition> conditionsFinal = matcher.matchAny && conditions.size() > 1
                ? List.of(new Condition.Group(Connector.OR, conditions, null))
                : conditions;

        StringBuilder columnListBuilder = new StringBuilder();
        for (ColumnInfo column : table.getColumns()) {
            if (columnListBuilder.length() > 0) columnListBuilder.append(", ");
            columnListBuilder.append(column.getColumnName());
        }
        String columnList = columnListBuilder.length() == 0 ? "*" : columnListBuilder.toString();

        List<ColumnRef> selectColumns;
        String head;
        switch (methodName) {
            case "count": {
                selectColumns = List.of(new ColumnRef(null, "*", null, "COUNT"));
                head = "SELECT COUNT(*)\nFROM " + table.getTableName();
                break;
            }
            case "exists": {
                selectColumns = List.of(new ColumnRef(null, "1"));
                head = "SELECT 1\nFROM " + table.getTableName();
                notes.add("exists：命中即真语义（运行时渲染 SELECT 1 ... LIMIT 1 形态）");
                break;
            }
            default: {
                selectColumns = List.of();
                head = "SELECT " + columnList + "\nFROM " + table.getTableName();
                if (methodName.equals("findOne")) {
                    notes.add("findOne：单结果语义（运行时非唯一结果抛异常），SQL 与 findAll 同形");
                }
                break;
            }
        }
        SelectStatement statement = new SelectStatement(table, selectColumns, null, conditionsFinal);
        StringBuilder sqlBuilder = new StringBuilder(head);
        if (!conditionsFinal.isEmpty()) {
            StringBuilder rendered = new StringBuilder();
            for (Condition condition : conditionsFinal) {
                if (rendered.length() > 0) rendered.append("\n  AND ");
                rendered.append(renderCondition(condition));
            }
            sqlBuilder.append("\nWHERE ").append(rendered);
        }
        for (String note : notes) {
            if (!note.isBlank()) sqlBuilder.append("\n-- ").append(note);
        }
        return new ExamplePreview(methodName, sqlBuilder.toString(),
                ctx.paramSources, ctx.paramHints, table.getTableName(), table.getAllColumnNames(), statement);
    }

    /** 无 Example.of 细节可解析时的降级注释卡（不产生错误 SQL）。 */
    private static ExamplePreview degradeCard(String methodName, TableInfo table, String reason) {
        return new ExamplePreview(methodName,
                "-- Query By Example（" + methodName + "）：" + reason
                        + "\n-- 运行时 SELECT " + table.getTableName() + " WHERE <probe 非空属性等值>",
                List.of(), List.of(), table.getTableName(), table.getAllColumnNames(), null);
    }

    // ------------------------------------------------------------------
    // 条件构建
    // ------------------------------------------------------------------

    /** 字段条件：匹配器形态 → 等值 / LIKE；ignoreCase → LOWER 包裹（Raw 语义可读形态）。 */
    private static Condition buildFieldCondition(ColumnRef column, SqlValue value,
                                                 StringMatcher stringMatcher, boolean ignoreCase) {
        String col = column.getColumnName();
        switch (stringMatcher) {
            case EXACT:
                if (ignoreCase) {
                    return new Condition.Raw("LOWER(" + col + ") = LOWER(" + renderValue(value) + ")");
                }
                return new Condition.Simple(column, "=", value, null);
            case CONTAINING:
                return likeCondition(column, value, LikePosition.ANYWHERE, ignoreCase);
            case STARTING:
                return likeCondition(column, value, LikePosition.RIGHT, ignoreCase);
            case ENDING:
                return likeCondition(column, value, LikePosition.LEFT, ignoreCase);
            case REGEX:
            default:
                return new Condition.Raw("/* " + col + " REGEX 匹配（" + valueText(value)
                        + "）：方言相关（RLIKE/REGEXP_LIKE），静态预览降级 */");
        }
    }

    private static Condition likeCondition(ColumnRef column, SqlValue value, LikePosition position,
                                           boolean ignoreCase) {
        SqlValue pattern;
        if (value instanceof SqlValue.Literal literal) {
            // Literal：raw 带引号（renderCondition 直用），unquoted 带 %（SqlGenerator 语义一致）
            String core = wrapLike(literal.getUnquoted(), position);
            pattern = new SqlValue.Literal("'" + core + "'", core);
        } else {
            // 占位值：SQL 呈 `?`，% 按位置由面板回填（ParamHint.likePosition）
            pattern = value;
        }
        String lower = ignoreCase ? "LOWER(" : "";
        String close = ignoreCase ? ")" : "";
        if (ignoreCase) {
            return new Condition.Raw(lower + column.getColumnName() + close + " LIKE "
                    + lower + renderValue(pattern) + close);
        }
        return new Condition.Like(column, false, pattern, position, null);
    }

    /** LIKE 模式拼接（Literal 值直接拼 %；占位值由面板按 likePosition 回填）。 */
    private static String wrapLike(String unquoted, LikePosition position) {
        switch (position) {
            case ANYWHERE:
                return "%" + unquoted + "%";
            case LEFT:
                return "%" + unquoted;
            case RIGHT:
                return unquoted + "%";
            case NONE:
            default:
                return unquoted;
        }
    }

    private static String renderValue(SqlValue value) {
        if (value instanceof SqlValue.Literal literal) return literal.getRaw();
        if (value instanceof SqlValue.Placeholder placeholder) return "?";
        return "?";
    }

    private static String valueText(SqlValue value) {
        if (value instanceof SqlValue.Literal literal) return literal.getUnquoted();
        if (value instanceof SqlValue.Placeholder placeholder) return "?";
        return "?";
    }

    /** Condition → SQL 文本（与 SqlGenerator 条件渲染同形）。 */
    private static String renderCondition(Condition condition) {
        if (condition instanceof Condition.Simple simple) {
            return simple.getColumn().getQualifiedName() + " " + simple.getOp() + " "
                    + renderValue(simple.getValue());
        }
        if (condition instanceof Condition.Like like) {
            return like.getColumn().getQualifiedName() + " LIKE " + renderValue(like.getPattern());
        }
        if (condition instanceof Condition.Raw raw) {
            return raw.getSqlText();
        }
        if (condition instanceof Condition.Group group) {
            StringBuilder inner = new StringBuilder();
            for (Condition child : group.getChildren()) {
                if (inner.length() > 0) inner.append(" ").append(group.getConnector().name()).append(" ");
                inner.append(renderCondition(child));
            }
            return "(" + inner + ")";
        }
        if (condition instanceof Condition.Unary unary) {
            return unary.getColumn().getQualifiedName() + " " + unary.getOp();
        }
        if (condition instanceof Condition.In in) {
            StringBuilder values = new StringBuilder();
            for (SqlValue value : in.getValues()) {
                if (values.length() > 0) values.append(", ");
                values.append(renderValue(value));
            }
            return in.getColumn().getQualifiedName() + " IN (" + values + ")";
        }
        if (condition instanceof Condition.Between between) {
            return between.getColumn().getQualifiedName() + " BETWEEN " + renderValue(between.getLo())
                    + " AND " + renderValue(between.getHi());
        }
        if (condition instanceof Condition.ColumnComparison comparison) {
            return comparison.getLeft().getQualifiedName() + " = " + comparison.getRight().getQualifiedName();
        }
        throw new IllegalArgumentException("未知的 Condition 类型: " + condition);
    }

    // ------------------------------------------------------------------
    // Example / ExampleMatcher 解析
    // ------------------------------------------------------------------

    /** probe 显式赋值字段（字段名 + 值表达式文本）。 */
    private static final class ProbeField {
        final String fieldName;
        final PsiExpression valueExpr;

        ProbeField(String fieldName, PsiExpression valueExpr) {
            this.fieldName = fieldName;
            this.valueExpr = valueExpr;
        }
    }

    /** probe 解析结果。 */
    private static final class Probe {
        final List<ProbeField> fields;

        Probe(List<ProbeField> fields) {
            this.fields = fields;
        }
    }

    /** StringMatcher 语义枚举（ExampleMatcher.StringMatcher 的静态可解析子集）。 */
    private enum StringMatcher { EXACT, CONTAINING, STARTING, ENDING, REGEX }

    /** ExampleMatcher 解析结果（默认 ALL + EXACT + 大小写敏感）。 */
    private static final class Matcher {
        boolean matchAny = false;
        boolean ignoreCase = false;
        String includeNulls = null;
        final Set<String> ignorePaths = new HashSet<>();
        final Map<String, StringMatcher> perField = new HashMap<>();
        StringMatcher defaultMatcher = StringMatcher.EXACT;

        boolean isIgnored(String fieldName, String columnName) {
            return ignorePaths.contains(fieldName) || ignorePaths.contains(columnName);
        }

        StringMatcher matcherFor(String fieldName) {
            StringMatcher mapped = perField.get(fieldName);
            return mapped != null ? mapped : defaultMatcher;
        }
    }

    /** 解析上下文：参数行收集 + 注释。 */
    private static final class Ctx {
        final boolean resolveVariables;
        final List<String> paramSources = new ArrayList<>();
        final List<ParamHint> paramHints = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        Ctx(boolean resolveVariables) {
            this.resolveVariables = resolveVariables;
        }
    }

    /** 实参 → `Example.of(...)` 调用（直接调用或变量初始化式回溯一次）。 */
    private static PsiMethodCallExpression exampleOfCall(PsiExpression arg) {
        if (arg instanceof PsiMethodCallExpression direct && isExampleOfCall(direct)) return direct;
        PsiElement resolved = arg instanceof PsiReferenceExpression reference ? reference.resolve() : null;
        if (resolved instanceof PsiVariable variable
                && variable.getInitializer() instanceof PsiMethodCallExpression viaVariable
                && isExampleOfCall(viaVariable)) {
            return viaVariable;
        }
        // Example probe = ...; Example.of(example) 的再包一层变量形态：实参变量初始化式已是 of 调用
        return null;
    }

    private static boolean isExampleOfCall(PsiMethodCallExpression call) {
        if (!"of".equals(call.getMethodExpression().getReferenceName())) return false;
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        String qualifierText = qualifier != null && qualifier.getText() != null ? qualifier.getText() : "";
        return substringAfterLast(qualifierText, '.').equals("Example");
    }

    /**
     * probe 解析：变量声明 `UserEntity probe = new UserEntity()` + 同作用域内 `probe.setX(v)` /
     * `probe.x = v` 的静态收集（值字面量/变量均可，求值交给 [ExpressionEvaluator]）。
     */
    private static Probe resolveProbe(PsiExpression expr) {
        try {
            PsiElement resolved = expr instanceof PsiReferenceExpression reference ? reference.resolve() : null;
            if (!(resolved instanceof PsiVariable variable)) return null;
            // probe 必须初始化为 new 实体（builder/工厂形态静态不可得）
            if (!(variable.getInitializer() instanceof PsiNewExpression initializer)) return null;
            PsiClass newClass = null;
            if (initializer.getClassReference() != null
                    && initializer.getClassReference().resolve() instanceof PsiClass byReference) {
                newClass = byReference;
            }
            if (newClass == null && initializer.getType() instanceof PsiClassType classType) {
                newClass = classType.resolve();
            }
            if (newClass == null) return null;
            String qualifiedName = newClass.getQualifiedName();
            if (qualifiedName != null && (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax."))) {
                return null;
            }

            List<ProbeField> fields = new ArrayList<>();
            // 收集范围：probe 变量所在语句块/方法体（向上找到首个 CodeBlock/Method/Class 边界）
            PsiElement scope = variable;
            while (scope != null && !(scope instanceof PsiCodeBlock)
                    && !(scope instanceof PsiMethod) && !(scope instanceof PsiClass)) {
                scope = scope.getParent();
            }
            if (scope == null) return null;
            String probeName = variable.getName();
            if (probeName == null) return null;
            collectProbeAssignments(scope, probeName, fields);
            return new Probe(fields);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 块内收集 `probe.setX(v)` 与 `probe.x = v`（qualifier 文本精确匹配 probe 变量名）。 */
    private static void collectProbeAssignments(PsiElement scope, String probeName, List<ProbeField> sink) {
        scope.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(PsiElement element) {
                // 不深入 probe 所在声明自身之外的限制：无（值表达式嵌套 probe 引用属罕见形态，忽略）
                super.visitElement(element);
                if (element instanceof PsiMethodCallExpression call) {
                    String name = call.getMethodExpression().getReferenceName();
                    if (name == null || !name.startsWith("set") || name.length() <= 3) return;
                    if (!(call.getMethodExpression().getQualifierExpression() instanceof PsiReferenceExpression qualifier)) {
                        return;
                    }
                    if (!probeName.equals(qualifier.getReferenceName())) return;
                    PsiExpression[] args = call.getArgumentList().getExpressions();
                    if (args.length == 0) return;
                    String fieldName = decapitalize(name.substring(3));
                    sink.add(new ProbeField(fieldName, args[0]));
                } else if (element instanceof PsiAssignmentExpression assignment) {
                    if (!(assignment.getLExpression() instanceof PsiReferenceExpression lhs)) return;
                    if (!(lhs.getQualifierExpression() instanceof PsiReferenceExpression qualifier)) return;
                    if (!probeName.equals(qualifier.getReferenceName())) return;
                    PsiExpression rhs = assignment.getRExpression();
                    if (rhs == null) return;
                    if (lhs.getReferenceName() == null) return;
                    sink.add(new ProbeField(lhs.getReferenceName(), rhs));
                }
            }
        });
    }

    /**
     * matcher 解析：`ExampleMatcher.matching()/matchingAll()/matchingAny()` 链式
     * withIgnorePaths / withIgnoreCase / withMatcher / withStringMatcher / withIncludeNulls。
     */
    private static Matcher resolveMatcher(PsiExpression expr) {
        try {
            PsiMethodCallExpression call = expr instanceof PsiMethodCallExpression direct ? direct : null;
            if (call == null && expr instanceof PsiReferenceExpression reference
                    && reference.resolve() instanceof PsiVariable variable
                    && variable.getInitializer() instanceof PsiMethodCallExpression viaVariable) {
                call = viaVariable;
            }
            if (call == null) return null;
            Matcher matcher = new Matcher();
            // 从最外层沿 qualifier 下钻（PSI：外层调用 qualifier 是内层调用）
            int guard = 0;
            while (call != null && guard++ < 20) {
                applyMatcherCall(call, matcher);
                call = call.getMethodExpression().getQualifierExpression() instanceof PsiMethodCallExpression qualifier
                        ? qualifier : null;
            }
            return matcher;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 单个 matcher 链方法应用（未知方法忽略——优雅降级）。 */
    private static void applyMatcherCall(PsiMethodCallExpression call, Matcher matcher) {
        String name = call.getMethodExpression().getReferenceName();
        if (name == null) return;
        PsiExpression[] args = call.getArgumentList().getExpressions();
        switch (name) {
            case "matchingAny":
                matcher.matchAny = true;
                break;
            case "matchingAll":
            case "matching":
                break;
            case "withIgnorePaths":
                for (PsiExpression arg : args) {
                    if (arg instanceof PsiLiteralExpression literal && literal.getValue() instanceof String path) {
                        matcher.ignorePaths.add(path);
                    }
                }
                break;
            case "withIgnoreCase":
                matcher.ignoreCase = !(args.length > 0 && "false".equals(args[0].getText()));
                break;
            case "withIncludeNulls":
                matcher.includeNulls = "includeNulls";
                break;
            case "withNullHandler":
                String nullHandler = null;
                if (args.length > 0 && args[0] instanceof PsiReferenceExpression reference) {
                    nullHandler = reference.getReferenceName();
                }
                matcher.includeNulls = nullHandler != null ? nullHandler : "include";
                break;
            case "withStringMatcher": {
                String enumName = null;
                if (args.length > 0 && args[0] instanceof PsiReferenceExpression reference) {
                    enumName = reference.getReferenceName();
                }
                StringMatcher mapped = mapStringMatcher(enumName);
                if (mapped != null) matcher.defaultMatcher = mapped;
                break;
            }
            case "withMatcher": {
                if (args.length < 2) return;
                String fieldName = args[0] instanceof PsiLiteralExpression literal
                        && literal.getValue() instanceof String literalText ? literalText : null;
                if (fieldName == null) return;
                StringMatcher parsed = parseGenericPropertyMatcher(args[1]);
                matcher.perField.put(fieldName, parsed != null ? parsed : matcher.matcherFor(fieldName));
                break;
            }
            default:
                break;
        }
    }

    /** `GenericPropertyMatchers.contains()/startsWith()/endsWith()/exact()/regex()` 链解析。 */
    private static StringMatcher parseGenericPropertyMatcher(PsiExpression expr) {
        PsiMethodCallExpression call = expr instanceof PsiMethodCallExpression direct ? direct : null;
        if (call == null) return null;
        int guard = 0;
        StringMatcher result = null;
        while (call != null && guard++ < 10) {
            String name = call.getMethodExpression().getReferenceName();
            StringMatcher mapped = null;
            if (name != null) {
                switch (name) {
                    case "contains":
                        mapped = StringMatcher.CONTAINING;
                        break;
                    case "startsWith":
                        mapped = StringMatcher.STARTING;
                        break;
                    case "endsWith":
                        mapped = StringMatcher.ENDING;
                        break;
                    case "exact":
                        mapped = StringMatcher.EXACT;
                        break;
                    case "regex":
                        mapped = StringMatcher.REGEX;
                        break;
                    default:
                        mapped = null; // caseInsensitive → 记录忽略：等值 LOWER 已由 withIgnoreCase 覆盖
                        break;
                }
            }
            if (mapped != null) result = mapped; // 最外层方法即最终形态（链尾语义）
            call = call.getMethodExpression().getQualifierExpression() instanceof PsiMethodCallExpression qualifier
                    ? qualifier : null;
        }
        return result;
    }

    /** ExampleMatcher.StringMatcher 枚举名映射。 */
    private static StringMatcher mapStringMatcher(String enumName) {
        if (enumName == null) return null;
        switch (enumName) {
            case "EXACT":
                return StringMatcher.EXACT;
            case "CONTAINING":
                return StringMatcher.CONTAINING;
            case "STARTING":
                return StringMatcher.STARTING;
            case "ENDING":
                return StringMatcher.ENDING;
            case "REGEX":
                return StringMatcher.REGEX;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------
    // 值求值与 receiver/实体解析
    // ------------------------------------------------------------------

    /** probe 字段值求值：字面量/可追踪变量内联；运行时值 `?` 占位 + 参数行（LIKE 条件带 likePosition）。 */
    private static SqlValue evaluateValue(PsiExpression expr, Ctx ctx, Boolean quoteHint) {
        return evaluateValue(expr, ctx, quoteHint, null);
    }

    private static SqlValue evaluateValue(PsiExpression expr, Ctx ctx, Boolean quoteHint,
                                          LikePosition likePosition) {
        return evaluateValue(expr, ctx, quoteHint, likePosition, false);
    }

    private static SqlValue evaluateValue(PsiExpression expr, Ctx ctx, Boolean quoteHint,
                                          LikePosition likePosition, boolean temporal) {
        SqlValue value = ctx.resolveVariables
                ? ExpressionEvaluator.evaluate(expr)
                : new SqlValue.Placeholder(expr.getText());
        if (value instanceof SqlValue.Literal literal) return literal;
        String text = expr.getText();
        String source = text != null && !text.isBlank() ? text : "?";
        ctx.paramSources.add(source);
        ctx.paramHints.add(new ParamHint(quoteHint, likePosition, false, temporal));
        return new SqlValue.Placeholder(source);
    }

    /** 首实参是否 Example 形态（类型 Example / 初始化式 Example.of(...)）。 */
    private static boolean isExampleLike(PsiExpression arg) {
        try {
            if (arg instanceof PsiLambdaExpression) return false;
            if (arg instanceof PsiMethodCallExpression direct && isExampleOfCall(direct)) return true;
            String typeText = arg.getType() instanceof PsiClassType classType
                    && classType.getCanonicalText() != null ? classType.getCanonicalText() : "";
            if (substringAfterLast(substringBefore(typeText, '<'), '.').equals("Example")) return true;
            PsiElement resolved = arg instanceof PsiReferenceExpression reference ? reference.resolve() : null;
            if (resolved instanceof PsiVariable variable
                    && variable.getType() instanceof PsiClassType variableType
                    && variableType.getCanonicalText() != null) {
                return substringAfterLast(substringBefore(variableType.getCanonicalText(), '<'), '.')
                        .equals("Example");
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** receiver 类型父链是否含 Spring Data Repository 基类。 */
    private static boolean hasRepositoryReceiver(PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) return false;
        PsiClassType varType = receiverClassType(qualifier);
        if (varType == null) return false;
        String canonical = varType.getCanonicalText();
        if (canonical == null) return false;
        String direct = substringBefore(canonical, '<');
        if (REPOSITORY_BASE_TYPES.contains(direct) || REPOSITORY_BASE_SIMPLE_NAMES.contains(direct)) return true;
        PsiClass clazz = varType.resolve();
        if (clazz == null) return false;
        for (PsiType superType : clazz.getSuperTypes()) {
            String canonicalSuper = superType.getCanonicalText();
            if (canonicalSuper == null) continue;
            String simple = substringAfterLast(substringBefore(canonicalSuper, '<'), '.');
            if (startsWithAny(canonicalSuper, REPOSITORY_BASE_TYPES)
                    || REPOSITORY_BASE_SIMPLE_NAMES.contains(simple)) {
                return true;
            }
        }
        return false;
    }

    /** Repository 泛型实体解析（Spring Data 基类第一个类型参数；不可解析回退变量类型首泛型）。 */
    private static PsiClass resolveRepositoryEntity(PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) return null;
        PsiClassType varType = receiverClassType(qualifier);
        if (varType == null) return null;
        PsiClass clazz = varType.resolve();
        if (clazz != null) {
            for (PsiType superType : clazz.getSuperTypes()) {
                if (!(superType instanceof PsiClassType classType)) continue;
                PsiType[] parameters = classType.getParameters();
                if (parameters.length == 0 || !(parameters[0] instanceof PsiClassType firstParam)) continue;
                PsiClass entity = firstParam.resolve();
                if (entity != null) return entity;
            }
        }
        PsiType[] ownParameters = varType.getParameters();
        if (ownParameters.length > 0 && ownParameters[0] instanceof PsiClassType firstParam) {
            return firstParam.resolve();
        }
        return null;
    }

    private static PsiClassType receiverClassType(PsiExpression qualifier) {
        if (qualifier instanceof PsiReferenceExpression reference) {
            if (reference.resolve() instanceof PsiVariable variable
                    && variable.getType() instanceof PsiClassType declared) {
                return declared;
            }
            return qualifier.getType() instanceof PsiClassType fallback ? fallback : null;
        }
        return qualifier.getType() instanceof PsiClassType type ? type : null;
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

    /** 首字母小写（Kotlin replaceFirstChar { it.lowercaseChar() } 语义）。 */
    private static String decapitalize(String text) {
        if (text == null || text.isEmpty()) return text;
        char first = Character.toLowerCase(text.charAt(0));
        return first + text.substring(1);
    }
}
