package cn.song.mybatisplus.visualizer.reverse;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;

import kotlin.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MyBatis-Plus 代码生成器（SQL→代码 反向链路，P0 + T-M36.2 lambda 模式）。
 *
 * 把 [SqlReverseResult] 渲染为 Wrapper 链式代码片段（字符串列名 / Lambda 两种形态）。
 * 本类为纯函数，不依赖 PSI / IDE。
 */
public final class JavaCodeGenerator {

    /** 单例（Kotlin object 转换）。 */
    public static final JavaCodeGenerator INSTANCE = new JavaCodeGenerator();

    private JavaCodeGenerator() {
    }

    /**
     * Lambda 模式实体上下文（列名 → 字段名映射；T-M37 含联表副实体）。
     *
     * @param className        实体类名
     * @param columnToField    列名 → 字段名映射
     * @param subEntities      联表副实体（键 = SQL 表别名）
     * @param logicDeleteColumn T-ReverseGroupLogicDelete：逻辑删除列（实体侧 {@code @TableLogic}，由 EntityResolver 解析）。
     *                          MP 会在 select/update/delete 上自动追加 {@code AND <列> = <未删除值>}，反向生成时该条件与
     *                          select 投影都属「框架注入」而非用户手写——命中即跳过，避免生成 {@code deleted = 0} 冗余条件。
     * @param logicDeleteValue 逻辑删除的「未删除」值（设置页 {@code logicDeleteValue}，默认 "0"）——只有值相等才判定为
     *                          框架注入条件；用户手写 {@code deleted = 1} 这类真过滤条件不受影响。
     */
    public record EntityTarget(
            String className,
            Map<String, String> columnToField,
            Map<String, EntityTarget> subEntities,
            String logicDeleteColumn,
            String logicDeleteValue
    ) {
        public EntityTarget(String className, Map<String, String> columnToField, Map<String, EntityTarget> subEntities) {
            this(className, columnToField, subEntities, null, "0");
        }

        public EntityTarget(String className, Map<String, String> columnToField) {
            this(className, columnToField, Map.of());
        }
    }

    /**
     * 调用上下文（T-M36.7）。
     *
     * @param callerClass    调用类（Service 或 Mapper 类名；特殊值 this/baseMapper）
     * @param voClass        可选返回 VO 类名（仅 SELECT 生效）
     * @param mpj            调用类是否具备 MPJ 能力（继承链实证）
     * @param methods        调用类继承链方法名集合（T-M44）
     * @param mapsResult     T-MapResultVo：Map 响应形态（`selectJoinMaps`/`selectMaps`/`listMaps` 族）——
     *                       返回 `List<Map<String, Object>>`（分页 `Page<Map<String, Object>>`）；Maps 族<b>不带 Class 参数</b>
     *                       （1.5.8 javap 实证），VO 角色转为驱动 select 列别名（selectAs 第二参）。
     * @param resultTypeText T-MapResultVo v2：返回类型声明原文（面板可编辑输入，如 `List<Map<String, Object>>` /
     *                       `List<HashMap<String, Object>>` / `List<Object>` / 类名）——按声明形态派生调用族：
     *                       含 Map → {@code mapsResult} 等效；含 Object → selectObjs/listObjs 族（单表，MPJ 无 selectJoinObjs）；
     *                       其他 → 按类名走 voClass（TYPED 档）。与 voClass/mapsResult 并存时 resultTypeText 优先。
     */
    public record InvocationTarget(
            String callerClass,
            String voClass,
            boolean mpj,
            Set<String> methods,
            boolean mapsResult,
            String resultTypeText
    ) {
        public InvocationTarget(String callerClass, String voClass, boolean mpj, Set<String> methods) {
            this(callerClass, voClass, mpj, methods, false, null);
        }

        public InvocationTarget(String callerClass, String voClass, boolean mpj) {
            this(callerClass, voClass, mpj, Set.of(), false, null);
        }

        public InvocationTarget(String callerClass) {
            this(callerClass, null, false, Set.of(), false, null);
        }
    }

    /** 生成完整代码片段（纯表达式或调用语句，按 invocation 决定）。 */
    public static String generate(SqlReverseResult result) {
        return generate(result, null, GeneratedWrapper.LAMBDA, null);
    }

    /** 生成完整代码片段（lambda 形态，无调用）。 */
    public static String generate(SqlReverseResult result, EntityTarget entity) {
        return generate(result, entity, GeneratedWrapper.LAMBDA, null);
    }

    /**
     * 生成完整代码片段（T-M36.7：增删改查全支持）。
     *
     * @param entity      非 null → lambda 列引用形态；null → 字符串列名形态
     * @param wrapperKind 实体可用时的 Wrapper 类型
     * @param invocation  T-M36.7：调用上下文；null → 生成纯 Wrapper/实体表达式
     */
    public static String generate(
            SqlReverseResult result,
            EntityTarget entity,
            GeneratedWrapper wrapperKind,
            InvocationTarget invocation
    ) {
        // 派生表无实体上下文时无法推断类名——在分发前拦下，避免产出不可编译代码
        requireConcreteTable(result.tableName(), entity);
        return switch (result.kind()) {
            case SELECT -> generateSelect(result, entity, wrapperKind, invocation);
            case UPDATE -> generateUpdate(result, entity, wrapperKind, invocation);
            case DELETE -> generateDelete(result, entity, wrapperKind, invocation);
            case INSERT -> generateInsert(result, entity, wrapperKind, invocation);
        };
    }

    // ------------------------------------------------------------------
    // 语句渲染（T-M36.7：增删改查）
    // ------------------------------------------------------------------

    /** SELECT：wrapper 表达式 + 链；多表 JOIN → MPJ 形态。 */
    private static String generateSelect(
            SqlReverseResult result,
            EntityTarget entity,
            GeneratedWrapper wrapperKind,
            InvocationTarget invocation
    ) {
        StringBuilder sb = new StringBuilder();
        boolean isJoin = !result.joins().isEmpty();
        // T-MapResultVo v2：返回类型声明原文的 TYPED 档（非 Map/Object 族 → 按类名走 VO 语义；
        // voClass 优先保留既有 API 兼容）
        String voTyped = null;
        if (invocation != null) {
            if (invocation.voClass() != null) {
                voTyped = invocation.voClass();
            } else if (invocation.resultTypeText() != null) {
                String rt = invocation.resultTypeText().trim();
                if (!rt.isEmpty() && !rt.contains("Map") && !rt.contains("Object")) voTyped = rt;
            }
        }
        // VO 查询（selectJoinList 族）参数类型为 MPJBaseJoin——须调用类具备 MPJ 能力（继承链实证）
        // 且 wrapper 强制 MPJ 形态；调用类无 MPJ 时 VO 回落实体查询（selectInvocation 内注释提示）
        boolean voQuery = invocation != null && invocation.mpj() && voTyped != null
                && !voTyped.equals(entity != null ? entity.className() : entityClassName(result.tableName()));
        // T-M47：选中 VO 且为真实 VO 查询时，selectAs 第二参用 VO 属性方法引用（Vo::getXxx）——
        // 对齐正向 selectAs(列, Vo::getXxx)；否则字符串别名
        String selectAsVoClass = voQuery ? voTyped : null;
        if (isJoin && invocation != null && !invocation.mpj()) {
            throw new SqlReverseException(0, SqlReverseParser.KEY_UNSUPPORTED,
                    "多表 JOIN 需要 MyBatis-Plus-Join（MPJ）依赖，但调用类 " + invocation.callerClass() + " 无 MPJ 能力");
        }
        boolean forceMpjQuery = isJoin && !result.joins().stream().allMatch(j -> canLambdaJoin(j, entity) || canMConsumerJoin(j, entity));
        // T-M42：serialVersionUID 系列列过滤后渲染（全部被过滤 → 空列表 → 自然回落 selectAll/不生成
        // select 调用，MP 全列语义）
        List<SelectColumn> selectColumns = result.selectColumns();
        if (selectColumns != null) {
            // T-ReverseGroupLogicDelete：逻辑删除列由 MP 全列展开自动带出，显式再 select 一遍无意义
            selectColumns = selectColumns.stream()
                    .filter(c -> !isIgnoredColumn(c.expr()) && !isInjectedLogicDeleteProjection(c, entity))
                    .toList();
        }
        // MP core 的 Lambda 列参数只接受 SFunction；别名/表达式/未映射投影和分组
        // 必须整链使用字符串 Wrapper，不能只把单个列参数换成字符串。
        // （javap 实证：MP core 3.5.14/3.5.17 的 Query/AbstractWrapper/LambdaQueryWrapper/
        //  QueryWrapper 均无 selectAs 重载——lambda 链无法表达 `列 AS 别名`，只能整链回落。）
        boolean needsStringProjection = result.distinct()
                || (selectColumns != null && selectColumns.stream().anyMatch(it ->
                        it.alias() != null || renderSelectRef(it, entity).startsWith("\"")))
                || result.groupBy().stream().anyMatch(it ->
                        resolveColumn(it.getColumnName(), entity, it.getTableAlias()) == null);
        GeneratedWrapper effectiveKind;
        if (forceMpjQuery) effectiveKind = GeneratedWrapper.MPJ_QUERY;
        else if (JOIN_WRAPPERS.contains(wrapperKind)) effectiveKind = wrapperKind;
        else if (isJoin && entity != null) effectiveKind = GeneratedWrapper.MPJ_LAMBDA;
        else if (isJoin) effectiveKind = GeneratedWrapper.MPJ_QUERY;
        else if (voQuery) effectiveKind = entity != null ? GeneratedWrapper.MPJ_LAMBDA : GeneratedWrapper.MPJ_QUERY;
        else if (wrapperKind == GeneratedWrapper.WRAPPERS_LAMBDA && (entity == null || needsStringProjection)) {
            effectiveKind = GeneratedWrapper.WRAPPERS_QUERY;
        } else if (wrapperKind == GeneratedWrapper.LAMBDA && entity != null && needsStringProjection) {
            effectiveKind = GeneratedWrapper.QUERY;
        } else effectiveKind = wrapperKind;

        appendHeader(sb, result, entity, effectiveKind, invocation);
        if (effectiveKind != wrapperKind && !isJoin && !voQuery && !JOIN_WRAPPERS.contains(wrapperKind)) {
            sb.append("// 投影或分组需要字符串列，整条查询已回落字符串 Wrapper，保留全部投影及别名\n");
        }
        // 字符串形态只改变列渲染，不丢弃实体类型或调用上下文。
        boolean stringCore = effectiveKind == GeneratedWrapper.QUERY || effectiveKind == GeneratedWrapper.WRAPPERS_QUERY
                || (effectiveKind == GeneratedWrapper.LAMBDA && entity == null);
        EntityTarget columnEntity = stringCore ? null : entity;
        boolean rawMode = effectiveKind == GeneratedWrapper.MPJ_QUERY;
        StringBuilder chain = new StringBuilder();
        List<Condition> conditions = filterConditions(result.conditions(), entity);
        if (isJoin) {
            if (selectColumns == null || selectColumns.isEmpty()) {
                chain.append("\n    .selectAll(").append(entity != null ? entity.className() : entityClassName(result.tableName())).append(".class)");
            } else {
                for (SelectColumn it : selectColumns) {
                    chain.append("\n    .").append(renderSelectCall(it, entity, selectAsVoClass, rawMode));
                }
            }
            for (String j : renderJoins(result, entity)) chain.append("\n    .").append(j);
            for (Condition c : conditions) chain.append("\n    .").append(renderCondition(c, columnEntity));
            for (String g : renderGroupBy(result, columnEntity)) chain.append("\n    .").append(g);
            if (result.having() != null) chain.append("\n    .").append(renderHaving(result.having(), columnEntity));
            for (String o : renderOrderBy(result, columnEntity)) chain.append("\n    .").append(o);
            // 分页调用形态（invocation 存在）由 page 参数承载，不再生成 last("LIMIT...")
            if (result.limit() != null && invocation == null) {
                chain.append("\n    .last(\"").append(javaEscape(renderLimitTail(result.limit(), result.forUpdate()))).append("\")");
            } else if (result.forUpdate() && result.limit() == null) {
                chain.append("\n    .last(\"FOR UPDATE\")");
            }
        } else {
            if (selectColumns != null && !selectColumns.isEmpty()) {
                if (result.distinct()) {
                    // T-SqlToJavaPromote G3：MP 无 selectDistinct——DISTINCT 用字符串单串 select("DISTINCT a, b")
                    chain.append("\n    .select(")
                            .append(javaString("DISTINCT " + selectColumns.stream()
                                    .map(JavaCodeGenerator::renderSelectRaw).collect(Collectors.joining(", "))))
                            .append(")");
                } else if (stringCore) {
                    // MP core select 是覆盖，不是追加；所有投影必须在一次调用中提交。
                    chain.append("\n    .select(")
                            .append(selectColumns.stream()
                                    .map(it -> javaString(renderSelectRaw(it))).collect(Collectors.joining(", ")))
                            .append(")");
                } else if (selectColumns.stream().anyMatch(it -> it.alias() != null)) {
                    // 含 AS 别名的列无法并入单个 .select(...)，逐列渲染 .select/.selectAs（T-M46）
                    for (SelectColumn it : selectColumns) {
                        chain.append("\n    .").append(renderSelectCall(it, entity, selectAsVoClass, rawMode));
                    }
                } else {
                    chain.append("\n    .select(")
                            .append(selectColumns.stream().map(c -> renderSelectRef(c, entity)).collect(Collectors.joining(", ")))
                            .append(")");
                }
            }
            for (Condition c : conditions) chain.append("\n    .").append(renderCondition(c, columnEntity));
            for (String g : renderGroupBy(result, columnEntity)) chain.append("\n    .").append(g);
            if (result.having() != null) chain.append("\n    .").append(renderHaving(result.having(), columnEntity));
            for (String o : renderOrderBy(result, columnEntity)) chain.append("\n    .").append(o);
            // 分页调用形态（invocation 存在）由 page 参数承载，不再生成 last("LIMIT...")
            if (result.limit() != null && invocation == null) {
                chain.append("\n    .last(\"").append(javaEscape(renderLimitTail(result.limit(), result.forUpdate()))).append("\")");
            } else if (result.forUpdate() && result.limit() == null) {
                chain.append("\n    .last(\"FOR UPDATE\")");
            }
        }
        String wrapperClass = wrapperClassFor(QUERY_WRAPPER_CLASSES, entity, effectiveKind);
        String entityName = entity != null ? entity.className() : entityClassName(result.tableName());
        return appendWrapperAndInvocation(sb, wrapperClass, entityName, effectiveKind, chain.toString(), invocation,
                null, () -> selectInvocation(result, entity, invocation));
    }

    // ------------------------------------------------------------------
    // 多表 JOIN 渲染（T-M37）
    // ------------------------------------------------------------------

    /** JOIN 方法名（MPJ）。 */
    private static String joinMethod(JoinType type) {
        return switch (type) {
            case INNER -> "innerJoin";
            case LEFT -> "leftJoin";
            case RIGHT -> "rightJoin";
            case FULL -> "fullJoin";
        };
    }

    /** JOIN 链渲染。 */
    private static List<String> renderJoins(SqlReverseResult result, EntityTarget entity) {
        List<String> out = new ArrayList<>();
        for (ReverseJoin join : result.joins()) {
            if (join.subQuery() != null && entity != null) {
                String m = renderMConsumerJoin(join, entity);
                if (m != null) { out.add(m); continue; }
            }
            if (canLambdaJoin(join, entity)) {
                EntityTarget sub = entity.subEntities().get(join.alias());
                JoinKeyInfo info = extractJoinKey(join, sub);
                String leftField = capitalize(sub.columnToField().get(info.key().getLeft().getColumnName()));
                String rightField = capitalize(entity.columnToField().get(info.key().getRight().getColumnName()));
                String base = joinMethod(join.type()) + "(" + sub.className() + ".class, "
                        + sub.className() + "::get" + leftField + ", " + entity.className() + "::get" + rightField;
                if (info.extConditions().isEmpty()) {
                    out.add(base + ")");
                } else {
                    List<String> extParts = new ArrayList<>();
                    for (Condition.Simple ext : info.extConditions()) {
                        String colName = sub.columnToField().containsKey(ext.getColumn().getColumnName())
                                ? capitalize(sub.columnToField().get(ext.getColumn().getColumnName())) : ext.getColumn().getColumnName();
                        extParts.add("ext." + opMethod(ext.getOp()) + "(" + sub.className() + "::get" + colName
                                + ", " + javaValue(ext.getValue()) + ")");
                    }
                    out.add(base + ",\n            ext -> " + String.join("\n            ", extParts) + ")");
                }
            } else {
                out.add(joinMethod(join.type()) + "(\"" + javaEscape(joinString(join)) + "\")");
            }
        }
        return out;
    }

    /** 解析子查询关联实体（别名优先，回落类名/表名）。 */
    private static EntityTarget resolveSubEntity(EntityTarget entity, JoinSubQuery subQuery) {
        EntityTarget sub = entity.subEntities().get(subQuery.alias());
        if (sub != null) return sub;
        sub = entity.subEntities().values().stream()
                .filter(e -> e.className().equalsIgnoreCase(subQuery.alias())).findFirst().orElse(null);
        if (sub != null) return sub;
        String norm = subQuery.tableName().replace("_", "");
        String cap = norm.isEmpty() ? norm : norm.substring(0, 1).toUpperCase() + norm.substring(1);
        String capLower = cap.toLowerCase();
        return entity.subEntities().values().stream()
                .filter(e -> e.className().toLowerCase().contains(capLower)).findFirst().orElse(null);
    }

    /** 渲染 MConsumer 子查询 JOIN 形态。 */
    private static String renderMConsumerJoin(ReverseJoin join, EntityTarget entity) {
        JoinSubQuery subQuery = join.subQuery();
        if (subQuery == null) return null;
        EntityTarget sub = resolveSubEntity(entity, subQuery);
        if (sub == null) return null;

        JoinKeyInfo info = extractJoinKey(join, sub);
        Condition.ColumnComparison key = info != null ? info.key()
                : join.onConditions().stream().filter(c -> c instanceof Condition.ColumnComparison)
                .map(c -> (Condition.ColumnComparison) c).findFirst().orElse(null);
        if (key == null) return null;
        String leftField = capitalize(sub.columnToField().containsKey(key.getLeft().getColumnName())
                ? sub.columnToField().get(key.getLeft().getColumnName()) : key.getLeft().getColumnName());
        String rightField = capitalize(entity.columnToField().containsKey(key.getRight().getColumnName())
                ? entity.columnToField().get(key.getRight().getColumnName()) : key.getRight().getColumnName());

        String aliasExpr = "t.setAlias(\"" + subQuery.alias() + "\")";
        String selectExpr;
        if (subQuery.selectAll()) {
            selectExpr = "selectAll()";
        } else {
            List<String> cols = new ArrayList<>();
            for (String col : subQuery.selectColumns()) {
                String field = sub.columnToField().containsKey(col) ? capitalize(sub.columnToField().get(col)) : col;
                cols.add(sub.className() + "::get" + field);
            }
            selectExpr = "select(" + String.join(", ", cols) + ")";
        }
        String whereChain = subQuery.whereConditions().stream().map(cond -> {
            if (cond instanceof Condition.Simple s) {
                String colName = sub.columnToField().containsKey(s.getColumn().getColumnName())
                        ? capitalize(sub.columnToField().get(s.getColumn().getColumnName())) : s.getColumn().getColumnName();
                return opMethod(s.getOp()) + "(" + sub.className() + "::get" + colName + ", " + javaValue(s.getValue()) + ")";
            }
            if (cond instanceof Condition.ColumnComparison cc) {
                String colName = sub.columnToField().containsKey(cc.getLeft().getColumnName())
                        ? capitalize(sub.columnToField().get(cc.getLeft().getColumnName())) : cc.getLeft().getColumnName();
                return "eq(" + sub.className() + "::get" + colName + ", " + cc.getRight().getColumnName() + ")";
            }
            return null;
        }).filter(java.util.Objects::nonNull).collect(Collectors.joining("\n                "));

        StringBuilder consumerChain = new StringBuilder();
        consumerChain.append(aliasExpr).append("\n                .").append(selectExpr);
        if (!whereChain.isEmpty()) consumerChain.append("\n                .").append(whereChain);

        return joinMethod(join.type()) + "(" + sub.className() + ".class, t -> " + consumerChain + ",\n            "
                + sub.className() + "::get" + leftField + ", " + entity.className() + "::get" + rightField + ")";
    }

    /** 子查询 JOIN 是否能渲染 MConsumer 形态。 */
    private static boolean canMConsumerJoin(ReverseJoin join, EntityTarget entity) {
        if (join.subQuery() == null || entity == null) return false;
        return renderMConsumerJoin(join, entity) != null;
    }

    /** 字符串形态 join 的完整参数文本。 */
    private static String joinString(ReverseJoin join) {
        String onText = join.rawOn() != null ? join.rawOn()
                : String.join(" AND ", join.onConditions().stream().map(JavaCodeGenerator::renderRawCondition).toList());
        onText = onText.replaceAll("\\s+", " ").trim();
        String tablePart = join.tableName() + (join.alias() != null ? " " + join.alias() : "");
        return tablePart + " ON " + onText;
    }

    /** join 是否可三参 lambda 化。 */
    private static boolean canLambdaJoin(ReverseJoin join, EntityTarget entity) {
        if (join.alias() == null) return false;
        EntityTarget sub = entity != null ? entity.subEntities().get(join.alias()) : null;
        if (sub == null) return false;
        JoinKeyInfo info = extractJoinKey(join, sub);
        if (info == null) return false;
        String leftField = sub.columnToField().get(info.key().getLeft().getColumnName());
        String rightField = entity != null && entity.columnToField() != null
                ? entity.columnToField().get(info.key().getRight().getColumnName()) : null;
        return leftField != null && rightField != null;
    }

    /** JOIN 键信息：等值列对（key）+ 副表额外 ext 条件。 */
    private record JoinKeyInfo(Condition.ColumnComparison key, List<Condition.Simple> extConditions) {
    }

    /**
     * 从 ON 条件提取 join key + ext 条件（恰好 1 个 ColumnComparison，其余为副表 Simple 条件）。
     */
    private static JoinKeyInfo extractJoinKey(ReverseJoin join, EntityTarget sub) {
        List<Condition> keyConditions = join.onConditions().stream()
                .filter(c -> !isSubLogicalDelete(c, sub, join.alias())).toList();
        List<Condition.ColumnComparison> comparisons = keyConditions.stream()
                .filter(c -> c instanceof Condition.ColumnComparison).map(c -> (Condition.ColumnComparison) c).toList();
        if (comparisons.size() != 1) return null;
        Condition.ColumnComparison key = comparisons.get(0);
        List<Condition.Simple> ext = keyConditions.stream()
                .filter(c -> c instanceof Condition.Simple).map(c -> (Condition.Simple) c)
                .filter(s -> s.getColumn().getTableAlias() != null && s.getColumn().getTableAlias().equals(join.alias()))
                .toList();
        return new JoinKeyInfo(key, ext);
    }

    /** 常见逻辑删除列名（归一化后比对）。 */
    private static final Set<String> LOGICAL_DELETE_COLUMNS = Set.of(
            "deleted", "isdeleted", "isdelete", "delflag", "deletedflag", "del", "isdel");

    /** 副表逻辑删除类条件。 */
    private static boolean isSubLogicalDelete(Condition condition, EntityTarget sub, String joinAlias) {
        if (!(condition instanceof Condition.Simple s)) return false;
        if (!"=".equals(s.getOp())) return false;
        if (s.getColumn().getTableAlias() == null || !s.getColumn().getTableAlias().equals(joinAlias)) return false;
        if (!sub.columnToField().containsKey(s.getColumn().getColumnName())) return false;
        String colNorm = s.getColumn().getColumnName().toLowerCase().replace("_", "");
        if (!LOGICAL_DELETE_COLUMNS.contains(colNorm)) return false;
        return s.getValue() instanceof SqlValue.Literal lit && ("0".equals(lit.getRaw()) || "1".equals(lit.getRaw()));
    }

    /** 条件 → SQL 文本（raw join 的 ON 原文重建）。 */
    private static String renderRawCondition(Condition condition) {
        if (condition instanceof Condition.ColumnComparison cc) {
            return cc.getLeft().getQualifiedName() + " = " + cc.getRight().getQualifiedName();
        }
        if (condition instanceof Condition.Simple s) {
            return s.getColumn().getQualifiedName() + " " + s.getOp() + " "
                    + (s.getValue() instanceof SqlValue.Literal lit ? lit.getRaw() : "?");
        }
        if (condition instanceof Condition.Raw r) return r.getSqlText();
        return condition.toString();
    }

    /** MPJ 形态集合（多表强制分发判定）。 */
    private static final Set<GeneratedWrapper> JOIN_WRAPPERS = Set.of(
            GeneratedWrapper.MPJ_LAMBDA, GeneratedWrapper.MPJ_QUERY);

    /** 调用对象变量名（T-M42）。 */
    private static String callerVar(InvocationTarget invocation) {
        if ("this".equals(invocation.callerClass()) || "baseMapper".equals(invocation.callerClass())) {
            return invocation.callerClass();
        }
        return invocation.callerClass().substring(0, 1).toLowerCase() + invocation.callerClass().substring(1);
    }

    /** UPDATE：UpdateWrapper 链。 */
    private static String generateUpdate(
            SqlReverseResult result,
            EntityTarget entity,
            GeneratedWrapper wrapperKind,
            InvocationTarget invocation
    ) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, result, entity, wrapperKind, invocation);
        if (result.conditions().isEmpty()) {
            sb.append("// ⚠ [BlockAttack] 无 WHERE 条件，将更新全表数据！\n");
        }
        StringBuilder chain = new StringBuilder();
        for (ReverseSet set : result.sets().stream().filter(rs -> !isIgnoredColumn(rs.column())).toList()) {
            chain.append("\n    .");
            if (set.rawSql() != null) {
                chain.append("setSql(\"").append(javaEscape(set.rawSql())).append("\")");
            } else {
                chain.append("set(").append(renderColumnRef(set.column(), entity, null)).append(", ")
                        .append(set.value() != null ? javaValue(set.value()) : "null").append(")");
            }
        }
        for (Condition c : filterConditions(result.conditions(), entity)) chain.append("\n    .").append(renderCondition(c, entity));
        for (String o : renderOrderBy(result, entity)) chain.append("\n    .").append(o);
        if (result.limit() != null) {
            chain.append("\n    .last(\"").append(javaEscape(renderLimit(result.limit()))).append("\")");
        }
        String wrapperClass = wrapperClassFor(UPDATE_WRAPPER_CLASSES, entity, wrapperKind);
        String entityName = entity != null ? entity.className() : entityClassName(result.tableName());
        return appendWrapperAndInvocation(sb, wrapperClass, entityName, wrapperKind, chain.toString(), invocation,
                null, () -> {
                    String m = prefer(invocation, "update", "updateJoin");
                    if ("updateJoin".equals(m)) return callerVar(invocation) + ".updateJoin(null, wrapper);";
                    if (isMapper(invocation)) return callerVar(invocation) + ".update(null, wrapper);";
                    return callerVar(invocation) + ".update(wrapper);";
                });
    }

    /** DELETE：单表 / 多表（MPJ DeleteJoinWrapper）。 */
    private static String generateDelete(
            SqlReverseResult result,
            EntityTarget entity,
            GeneratedWrapper wrapperKind,
            InvocationTarget invocation
    ) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, result, entity, wrapperKind, invocation);
        if (result.conditions().isEmpty()) {
            sb.append("// ⚠ [BlockAttack] 无 WHERE 条件，将删除全表数据！\n");
        }
        boolean multiTable = !result.deleteTargets().isEmpty();
        if (multiTable) {
            if (entity == null) {
                throw new SqlReverseException(0, SqlReverseParser.KEY_UNSUPPORTED, "多表删除需要实体类上下文");
            }
            if (invocation != null && !invocation.mpj()) {
                throw new SqlReverseException(0, SqlReverseParser.KEY_UNSUPPORTED,
                        "多表删除需要 MyBatis-Plus-Join（MPJ）依赖，但调用类 " + invocation.callerClass() + " 无 MPJ 能力");
            }
            if (!result.joins().stream().allMatch(j -> canLambdaJoin(j, entity))) {
                throw new SqlReverseException(0, SqlReverseParser.KEY_UNSUPPORTED,
                        "多表删除的 JOIN 无法映射为 lambda join（需单等值 ON 且主副实体字段齐全），无法生成 DeleteJoinWrapper");
            }
            StringBuilder chain = new StringBuilder();
            if (!result.deleteTargets().isEmpty()) {
                chain.append("\n    .delete(")
                        .append(result.deleteTargets().stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(", ")))
                        .append(")");
            }
            for (String j : renderJoins(result, entity)) chain.append("\n    .").append(j);
            for (Condition c : filterConditions(result.conditions(), entity)) chain.append("\n    .").append(renderCondition(c, entity));
            return appendWrapperAndInvocation(sb, "DeleteJoinWrapper", entity.className(), wrapperKind,
                    chain.toString(), invocation,
                    () -> "new DeleteJoinWrapper<>(" + entity.className() + ".class)",
                    () -> callerVar(invocation) + ".deleteJoin(wrapper);");
        }
        // 单表删除：现状不变
        StringBuilder chain = new StringBuilder();
        for (Condition c : filterConditions(result.conditions(), entity)) chain.append("\n    .").append(renderCondition(c, entity));
        for (String o : renderOrderBy(result, entity)) chain.append("\n    .").append(o);
        if (result.limit() != null) {
            chain.append("\n    .last(\"").append(javaEscape(renderLimit(result.limit()))).append("\")");
        }
        String wrapperClass = wrapperClassFor(QUERY_WRAPPER_CLASSES, entity, wrapperKind);
        String entityName = entity != null ? entity.className() : entityClassName(result.tableName());
        return appendWrapperAndInvocation(sb, wrapperClass, entityName, wrapperKind, chain.toString(), invocation,
                null, () -> {
                    if (isMapper(invocation)) {
                        return callerVar(invocation) + "." + prefer(invocation, "delete", "deleteJoin") + "(wrapper);";
                    }
                    return callerVar(invocation) + "." + prefer(invocation, "remove", "deleteJoin") + "(wrapper);";
                });
    }

    /** INSERT：实体 setter 链。 */
    private static String generateInsert(
            SqlReverseResult result,
            EntityTarget entity,
            GeneratedWrapper wrapperKind,
            InvocationTarget invocation
    ) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, result, entity, wrapperKind, invocation);
        String entityName = entity != null ? entity.className() : entityClassName(result.tableName());
        String entityVar = entityName.substring(0, 1).toLowerCase() + entityName.substring(1);
        List<List<SqlValue>> rows = result.insertValues();
        List<Pair<Integer, String>> insertPairs = new ArrayList<>();
        for (int idx = 0; idx < result.insertColumns().size(); idx++) {
            String column = result.insertColumns().get(idx);
            if (!isIgnoredColumn(column)) insertPairs.add(new Pair<>(idx, column));
        }

        if (rows.size() <= 1) {
            String chain = setterChain(rows.isEmpty() ? List.of() : rows.get(0), insertPairs, entityName, entity);
            if (invocation != null) {
                String line = entityName + " " + entityVar + " = " + chain + ";\n\n"
                        + (isMapper(invocation) ? callerVar(invocation) + ".insert(" + entityVar + ");"
                        : callerVar(invocation) + ".save(" + entityVar + ");");
                sb.append(line);
                return sb.toString();
            }
            sb.append(chain).append(";");
            return sb.toString();
        }
        if (invocation == null) {
            sb.append(rows.stream().map(r -> setterChain(r, insertPairs, entityName, entity) + ";")
                    .collect(Collectors.joining("\n")));
            return sb.toString();
        }
        for (int i = 0; i < rows.size(); i++) {
            sb.append(entityName).append(" ").append(entityVar).append(i + 1).append(" = ")
                    .append(setterChain(rows.get(i), insertPairs, entityName, entity)).append(";\n");
        }
        sb.append("\n");
        if (isMapper(invocation)) {
            for (int i = 0; i < rows.size(); i++) {
                sb.append(callerVar(invocation)).append(".insert(").append(entityVar).append(i + 1).append(");");
                if (i < rows.size() - 1) sb.append("\n");
            }
        } else {
            StringBuilder vars = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) vars.append(", ");
                vars.append(entityVar).append(i + 1);
            }
            sb.append(callerVar(invocation)).append(".saveBatch(Arrays.asList(").append(vars).append("));");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // SELECT 调用行（分页/普通）
    // ------------------------------------------------------------------

    /** SELECT 调用行（分页/普通二选一）。 */
    private static String selectInvocation(
            SqlReverseResult result,
            EntityTarget entity,
            InvocationTarget invocation
    ) {
        String entityName = entity != null ? entity.className() : entityClassName(result.tableName());
        // T-MapResultVo v2：返回类型声明原文派生调用族（面板可编辑输入）——
        //  含 Map → Maps 族（mapsResult 等效，声明 HashMap 等亦归此档，前缀按方法真实返回类型渲染）；
        //  含 Object → Objs 族（单表 selectObjs/listObjs，MPJ 无 selectJoinObjs）；
        //  其他文本 → 按类名走 voClass（TYPED 档，项目索引可解析 → lambda/别名驱动，不可解析按字面生成+注释）。
        String rtText = invocation.resultTypeText() != null ? invocation.resultTypeText().trim() : null;
        if (rtText != null && rtText.isEmpty()) rtText = null;
        boolean maps = invocation.mapsResult() || (rtText != null && rtText.contains("Map"));
        boolean objs = !maps && rtText != null && rtText.contains("Object");
        String typedRequested = (maps || objs) ? null : (invocation.voClass() != null ? invocation.voClass() : rtText);
        String voRequested = typedRequested != null && !typedRequested.equals(entityName) ? typedRequested : null;
        // VO 查询依赖 MPJ 能力（调用类继承链实证）；无 MPJ → 回落实体查询并注释提示
        String vo = (voRequested != null && invocation.mpj()) ? voRequested : null;
        boolean voUnsupported = voRequested != null && vo == null;
        boolean paged = result.limit() != null;
        boolean join = !result.joins().isEmpty();
        // Objs 族无分页重载（BaseMapper.selectObjs javap 实证）——分页需求转注释提示
        if (objs && join) {
            throw new SqlReverseException(
                    0,
                    SqlReverseParser.KEY_UNSUPPORTED,
                    "JOIN 场景不支持 Object 单列响应（MPJ 无 selectJoinObjs）——可用 Map 列表（List<Map<String, Object>>）或实体列表替代"
            );
        }
        // T-MapResultVo：Map 响应形态（签名 1.5.8 javap 实证）——
        //  join → JoinMapper/JoinService `selectJoinMaps(wrapper)` / 分页 `selectJoinMapsPage(page, wrapper)`（不带 Class）；
        //  单表 Mapper → `selectMaps(wrapper)` / 分页 `selectMaps(page, wrapper)`（MP 同名重载）；
        //  单表 Service → `listMaps(wrapper)` / 分页 `listMaps(page, wrapper)`（IService）。
        // VO 在 Maps 场景不进签名（转 select 列别名驱动）→ Class 参数恒不加。
        // T-M44：方法选择按调用类真实方法集（prefer 回退链）——部分 MPJ 依赖的 MPJBaseMapper
        // 无 BaseMapper 的 selectPage/selectList 等普通方法，缺失时回落 JoinMapper/JoinService 的
        // selectJoin 族（join 方法需 Class 参数，args 按方法形态构建）；特殊值/类不可解析 → 普通方法
        String method;
        if (maps && join) {
            method = paged ? "selectJoinMapsPage" : "selectJoinMaps";
        } else if (maps && isMapper(invocation)) {
            method = paged ? "selectMaps" : prefer(invocation, "selectMaps", "selectJoinMaps");
        } else if (maps) {
            method = paged ? "listMaps" : prefer(invocation, "listMaps", "selectMaps");
        } else if (objs && isMapper(invocation)) {
            method = "selectObjs";
        } else if (objs) {
            method = prefer(invocation, "listObjs", "selectObjs");
        } else if (isMapper(invocation)) {
            if (paged && vo != null) method = "selectJoinPage";
            else if (paged) method = prefer(invocation, "selectPage", "selectJoinPage");
            else if (vo != null) method = "selectJoinList";
            else method = prefer(invocation, "selectList", "selectJoinList");
        } else {
            if (paged && vo != null) method = "selectJoinListPage";
            else if (paged) method = prefer(invocation, "page", "selectJoinListPage");
            else if (vo != null) method = "selectJoinList";
            else method = prefer(invocation, "list", "selectJoinList");
        }
        // 参数顺序（1.5.6 实证）：普通 `(wrapper)`；分页 `(page, wrapper)`；
        // VO/join 方法 `(page?, Class, wrapper)`——回退到 selectJoin 族时 Class 参数用 VO 或实体；
        // Maps/Objs 族不带 Class（selectJoinMaps 同为 selectJoin 前缀但签名无 Class——1.5.8 javap 实证）
        boolean usePageArg = paged && !objs;
        String pageArg = usePageArg ? renderPageArg(result.limit()) : "";
        boolean classArg = !maps && !objs && (vo != null || (method.startsWith("selectJoin") && !method.contains("Maps")));
        StringBuilder args = new StringBuilder();
        if (usePageArg) args.append(pageArg).append(", ");
        if (classArg) args.append((vo != null ? vo : entityName)).append(".class, ");
        args.append("wrapper");
        String generic;
        if (maps) generic = "Map<String, Object>";
        else if (objs) generic = "Object";
        else generic = vo != null ? vo : entityName;
        String prefix = usePageArg ? "Page<" + generic + "> page = " : "List<" + generic + "> list = ";
        String call = prefix + callerVar(invocation) + "." + method + "(" + args + ");";
        // 说明注释独立成行（T-M45：行尾注释 → 行注释——整行语义清晰，复制剥离注释时行结构稳定）
        StringBuilder comments = new StringBuilder();
        if (voUnsupported) comments.append("// 调用类无 MyBatis-Plus-Join（MPJ）能力，VO 查询不可用，已回落实体查询\n");
        if (vo != null) comments.append("// VO 查询需 MyBatis-Plus-Join（MPJ）依赖\n");
        if (paged && !usePageArg) comments.append("// selectObjs 无分页重载——请在 wrapper 内分页（last(\"LIMIT ...\")）或改用实体/Map 列表\n");
        if (paged && usePageArg) comments.append("// 分页 current/size 已占位，请替换为实际参数\n");
        if (voRequested != null && vo != null && rtText != null) {
            comments.append("// 返回类型按声明 ").append(rtText).append(" 生成（调用类无该类时请核对类名）\n");
        }
        return comments + call;
    }

    /** 分页参数：LIMIT 字面量推断 current/size；占位符 → 回落 1/10。 */
    private static String renderPageArg(LimitClause limit) {
        String size = (limit.getCount() instanceof SqlValue.Literal lit) ? lit.getRaw() : "10";
        Long sizeNum = parseLongOrNull(size);
        String current;
        if (limit.getOffset() instanceof SqlValue.Literal lit && sizeNum != null && sizeNum > 0) {
            Long offset = parseLongOrNull(lit.getRaw());
            current = String.valueOf(Math.max((offset != null ? offset / sizeNum + 1 : 1), 1));
        } else {
            current = "1";
        }
        return "new Page<>(" + current + ", " + size + ")";
    }

    /** wrapper 变量化 + 调用行；无调用 → 纯表达式。 */
    private static String appendWrapperAndInvocation(
            StringBuilder sb,
            String wrapperClass,
            String entityName,
            GeneratedWrapper wrapperKind,
            String chain,
            InvocationTarget invocation,
            java.util.function.Supplier<String> headOverride,
            java.util.function.Supplier<String> invocationLine
    ) {
        String head = headOverride != null ? headOverride.get() : wrapperHead(wrapperClass, entityName, wrapperKind);
        boolean hasCaller = invocation != null && invocation.callerClass() != null && !invocation.callerClass().isEmpty();
        if (invocation == null || !hasCaller) {
            sb.append(head).append(chain).append(";");
            return sb.toString();
        }
        sb.append(wrapperClass).append('<').append(entityName).append("> wrapper = ").append(head)
                .append(chain).append(";\n\n").append(invocationLine.get());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 包装形态公共（T-M36.6 Wrappers 工厂 / T-M36.7 UPDATE/DELETE 类映射）
    // ------------------------------------------------------------------

    /** QUERY 族 wrapper 类名（SELECT 条件/DELETE 条件）。 */
    private static final WrapperClassMap QUERY_WRAPPER_CLASSES = new WrapperClassMap(
            "QueryWrapper", "LambdaQueryWrapper", "MPJLambdaWrapper", "QueryWrapper");

    /** UPDATE 族 wrapper 类名。 */
    private static final WrapperClassMap UPDATE_WRAPPER_CLASSES = new WrapperClassMap(
            "UpdateWrapper", "LambdaUpdateWrapper", "UpdateWrapper", "UpdateWrapper");

    /** 形态 → wrapper 类名映射。 */
    private record WrapperClassMap(String plain, String lambda, String mpj, String mpjPlain) {
    }

    /** 形态 → wrapper 变量类名。 */
    private static String wrapperClassFor(WrapperClassMap map, EntityTarget entity, GeneratedWrapper wrapperKind) {
        return switch (wrapperKind) {
            case QUERY, WRAPPERS_QUERY -> map.plain();
            case LAMBDA, WRAPPERS_LAMBDA -> entity != null ? map.lambda() : map.plain();
            case MPJ_LAMBDA -> entity != null ? map.mpj() : map.mpjPlain();
            case MPJ_QUERY -> map.mpjPlain();
        };
    }

    /** wrapper 构造表达式。 */
    private static String wrapperHead(String wrapperClass, String entityName, GeneratedWrapper wrapperKind) {
        return switch (wrapperKind) {
            case WRAPPERS_QUERY -> "Wrappers.<" + entityName + ">query()";
            case WRAPPERS_LAMBDA -> "Wrappers.<" + entityName + ">lambdaQuery()";
            case MPJ_QUERY -> "new MPJQueryWrapper<>(" + entityName + ".class)";
            default -> "new " + wrapperClass + "<" + entityName + ">()";
        };
    }

    /** 注释头。 */
    private static void appendHeader(
            StringBuilder sb,
            SqlReverseResult result,
            EntityTarget entity,
            GeneratedWrapper wrapperKind,
            InvocationTarget invocation
    ) {
        String kindLabel = switch (result.kind()) {
            case SELECT -> "QueryWrapper";
            case UPDATE -> "UpdateWrapper";
            case DELETE -> "DeleteWrapper";
            case INSERT -> "Entity";
        };
        String formLabel;
        if (invocation != null) {
            formLabel = "（含 " + invocation.callerClass() + " 调用"
                    + (invocation.voClass() != null ? "，返回 " + invocation.voClass() : "") + "）";
        } else if (wrapperKind == GeneratedWrapper.WRAPPERS_QUERY || wrapperKind == GeneratedWrapper.WRAPPERS_LAMBDA) {
            formLabel = "（Wrappers 工厂形态）";
        } else if (wrapperKind == GeneratedWrapper.MPJ_LAMBDA || wrapperKind == GeneratedWrapper.MPJ_QUERY) {
            formLabel = "（MPJ 形态）";
        } else {
            formLabel = "";
        }
        sb.append("// SQL → MyBatis-Plus ").append(kindLabel).append(" 反向生成（SQL Visualizer）").append(formLabel).append("\n");
        if (entity == null) {
            String entityName = entityClassName(result.tableName());
            sb.append("// 实体类名 ").append(entityName).append(" 由表名 ").append(result.tableName()).append(" 推断，请按实际类名调整\n");
        } else {
            sb.append("// 实体类 ").append(entity.className()).append(" 由表名 ").append(result.tableName()).append(" 匹配\n");
            List<String> unmapped = unmappedColumns(result, entity);
            if (!unmapped.isEmpty()) {
                sb.append("// 注意：以下列未在实体中找到对应字段，已回落字符串列名：")
                        .append(String.join(", ", unmapped)).append("\n");
            }
            // T-ReverseGroupLogicDelete：交代被跳过的逻辑删除条件，避免用户误以为生成器漏了条件
            if (entity.logicDeleteColumn() != null) {
                sb.append("// 逻辑删除列 ").append(entity.logicDeleteColumn())
                        .append(" 由 MP 依据 @TableLogic 自动追加，未重复生成该条件与投影\n");
            }
        }
        if (wrapperKind == GeneratedWrapper.MPJ_LAMBDA && entity != null) {
            sb.append("// 需引入 MyBatis-Plus-Join（MPJ）依赖\n");
        }
        if (hasPlaceholder(result)) {
            sb.append("// 注意：SQL 含参数占位符 ?，无法恢复实际参数，已生成 \"?\" 占位，请替换为实际参数\n");
        }
        for (String w : result.warnings()) sb.append("// 注意：").append(w).append("\n");
        if (!result.unions().isEmpty()) {
            // T-SqlToJavaPromote G8：MP core 无 union() 方法、MPJ 签名本地不可得——降级为原文注释提示
            sb.append("// 注意：UNION 子句需 MyBatis-Plus-Join 的 union() API 或原生 SQL，请手动处理：\n");
            for (ReverseUnion u : result.unions()) {
                sb.append("//   ").append(u.keyword()).append(" ").append(u.sql()).append("\n");
            }
        }
    }

    /** 结果中是否存在 `?` 占位符。 */
    private static boolean hasPlaceholder(SqlReverseResult result) {
        if (scanConditions(result.conditions())) return true;
        for (ReverseSet rs : result.sets()) {
            if (rs.value() instanceof SqlValue.Placeholder) return true;
        }
        for (List<SqlValue> row : result.insertValues()) {
            for (SqlValue v : row) {
                if (v instanceof SqlValue.Placeholder) return true;
            }
        }
        if (result.limit() != null) {
            if (result.limit().getOffset() instanceof SqlValue.Placeholder) return true;
            if (result.limit().getCount() instanceof SqlValue.Placeholder) return true;
        }
        return false;
    }

    private static boolean scanConditions(List<Condition> conditions) {
        for (Condition c : conditions) {
            if (c instanceof Condition.Simple s && s.getValue() instanceof SqlValue.Placeholder) return true;
            if (c instanceof Condition.In in && in.getValues().stream().anyMatch(v -> v instanceof SqlValue.Placeholder)) return true;
            if (c instanceof Condition.Between b && (b.getLo() instanceof SqlValue.Placeholder || b.getHi() instanceof SqlValue.Placeholder)) return true;
            if (c instanceof Condition.Like l && l.getPattern() instanceof SqlValue.Placeholder) return true;
            if (c instanceof Condition.Group g && scanConditions(g.getChildren())) return true;
        }
        return false;
    }

    /** 调用类是否为 Mapper（T-M42）。 */
    private static boolean isMapper(InvocationTarget invocation) {
        if ("baseMapper".equals(invocation.callerClass())) return true;
        if ("this".equals(invocation.callerClass())) return false;
        return invocation.callerClass().endsWith("Mapper");
    }

    // ------------------------------------------------------------------
    // 列渲染（T-M36.2：lambda 模式列→属性）
    // ------------------------------------------------------------------

    /** serialVersionUID 系列列名默认忽略。 */
    private static boolean isIgnoredColumn(String expr) {
        String s = stripBt(afterLast(expr, '.')).toLowerCase().replace("_", "");
        return s.equals("serialversionuid");
    }

    /** 条件树过滤（T-M42：默认参重载，entity = null）。 */
    private static List<Condition> filterConditions(List<Condition> conditions) {
        return filterConditions(conditions, null);
    }

    /**
     * 条件树过滤（T-M42）：命中 serialVersionUID 列的 Simple/In/Between/Like/Unary 条件跳过；
     * Group 递归过滤（子条件全被过滤的组整组跳过，避免生成空 nested/not lambda）。
     *
     * <p>T-ReverseGroupLogicDelete：{@code entity} 非 null 时额外跳过「逻辑删除注入条件」——MP 依据
     * {@code @TableLogic} 在 select/update/delete 上自动追加 {@code AND <列> = <未删除值>}，反解出的 SQL 里
     * 该条件同样存在，但用户代码从未手写它，再生成一遍就是冗余（{@code deleted = 0 AND deleted = 0}）。
     */
    private static List<Condition> filterConditions(List<Condition> conditions, EntityTarget entity) {
        List<Condition> out = new ArrayList<>();
        for (Condition condition : conditions) {
            if (condition instanceof Condition.Group g) {
                List<Condition> children = filterConditions(g.getChildren(), entity);
                if (children.isEmpty()) continue;
                out.add(new Condition.Group(g.getConnector(), children, g.getToggle(), g.getNegated()));
            } else if (condition instanceof Condition.Simple s) {
                if (!isIgnoredColumn(s.getColumn().getColumnName()) && !isInjectedLogicDelete(s, entity)) out.add(condition);
            } else if (condition instanceof Condition.Unary u) {
                if (!isIgnoredColumn(u.getColumn().getColumnName())) out.add(condition);
            } else if (condition instanceof Condition.In in) {
                if (!isIgnoredColumn(in.getColumn().getColumnName()) && !isInjectedLogicDelete(in, entity)) out.add(condition);
            } else if (condition instanceof Condition.Between b) {
                if (!isIgnoredColumn(b.getColumn().getColumnName())) out.add(condition);
            } else if (condition instanceof Condition.Like l) {
                if (!isIgnoredColumn(l.getColumn().getColumnName())) out.add(condition);
            } else {
                out.add(condition);
            }
        }
        return out;
    }

    /** 列归属解析：返回 (实体类名, 字段名)；null = 未映射。
     * alias 非空但非副实体键（主表别名如 t）时回落主实体（Kotlin elvis 语义：
     * {@code entity?.subEntities?.get(alias) ?: entity}）。 */
    private static Pair<String, String> resolveColumn(String columnName, EntityTarget entity, String alias) {
        EntityTarget target = entity;
        if (alias != null && entity != null) {
            EntityTarget sub = entity.subEntities().get(alias);
            if (sub != null) target = sub;
        }
        // T-FixCamelMap：列名查询双向归一（原样 / snake_case / camelCase）——SQL 列与实体列名
        // 形态差异（`role_id` ↔ `roleId`，含 @TableField 显式驼峰列）不再回落字符串
        if (target != null && target.columnToField() != null) {
            for (String key : columnKeyVariants(columnName)) {
                String field = target.columnToField().get(key);
                if (field != null) return new Pair<>(target.className(), field);
            }
        }
        if (alias == null || (entity != null && !entity.subEntities().containsKey(alias))) {
            if (entity != null) {
                List<EntityTarget> matches = entity.subEntities().values().stream()
                        .filter(sub -> columnKeyVariants(columnName).stream().anyMatch(sub.columnToField()::containsKey))
                        .toList();
                if (matches.size() == 1) {
                    EntityTarget m = matches.get(0);
                    String key = columnKeyVariants(columnName).stream()
                            .filter(m.columnToField()::containsKey).findFirst().orElse(null);
                    return new Pair<>(m.className(), m.columnToField().get(key));
                }
            }
        }
        return null;
    }

    /** 列名查询变体（T-FixCamelMap）：原样 / snake_case / camelCase 去重。 */
    private static List<String> columnKeyVariants(String name) {
        String snake = cn.song.mybatisplus.visualizer.util.NamingStrategy.INSTANCE.toSnakeCase(name);
        List<String> variants = new ArrayList<>();
        variants.add(name);
        variants.add(snake);
        variants.add(snakeToCamel(name));
        return variants.stream().distinct().toList();
    }

    /** snake_case → camelCase（`role_id` → `roleId`）；无下划线原样返回。 */
    private static String snakeToCamel(String name) {
        if (name.indexOf('_') < 0) return name;
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String seg : name.split("_")) {
            if (seg.isEmpty()) continue;
            sb.append(first ? seg : capitalize(seg));
            first = false;
        }
        return sb.toString();
    }

    /** 列引用渲染。 */
    private static String renderColumnRef(String columnName, EntityTarget entity, String alias) {
        Pair<String, String> resolved = resolveColumn(columnName, entity, alias);
        if (resolved != null) {
            return resolved.getFirst() + "::get" + capitalize(resolved.getSecond());
        }
        String qualified = alias != null ? alias + "." + columnName : columnName;
        return javaString(qualified);
    }

    /** SELECT 列列引用（原文拆分别名前缀后走列归属）。 */
    private static String renderSelectRef(SelectColumn sel, EntityTarget entity) {
        String expr = sel.expr();
        List<String> parts = Arrays.asList(expr.split("\\."));
        String alias;
        String column;
        if (parts.size() > 1) {
            alias = stripBt(String.join(".", parts.subList(0, parts.size() - 1)));
            column = stripBt(parts.get(parts.size() - 1));
        } else {
            alias = null;
            column = stripBt(expr);
        }
        return renderColumnRef(column, entity, alias);
    }

    /** SELECT 列整条调用。 */
    private static String renderSelectCall(SelectColumn sel, EntityTarget entity, String voClass, boolean rawMode) {
        // 聚合/函数列（COUNT(*) 等含 `(`，T-SqlToJavaPromote G5）与 MPJQueryWrapper 字符串联接：
        // 不可 lambda 引用——统一回落 select("expr [AS alias]") 字符串（QueryWrapper.select(String...) 合法）
        boolean isAggregate = sel.expr().indexOf('(') >= 0;
        if (rawMode || isAggregate) {
            String col = sel.expr();
            return sel.alias() != null ? "select(" + javaString(col + " AS " + sel.alias()) + ")" : "select(" + javaString(col) + ")";
        }
        String ref = renderSelectRef(sel, entity);
        if (sel.alias() != null) {
            String aliasRef = voClass != null ? voClass + "::get" + capitalize(sel.alias()) : javaString(sel.alias());
            return "selectAs(" + ref + ", " + aliasRef + ")";
        }
        return "select(" + ref + ")";
    }

    /** 未映射列（按出现顺序去重）。 */
    private static List<String> unmappedColumns(SqlReverseResult result, EntityTarget entity) {
        List<String> used = new ArrayList<>();
        if (result.selectColumns() != null) {
            for (SelectColumn c : result.selectColumns()) used.add(c.expr());
        }
        for (Condition condition : result.conditions()) collectUsed(condition, used);
        for (OrderByItem o : result.orderBy()) used.add(o.getColumn().getQualifiedName());
        return used.stream().distinct()
                .filter(u -> !isIgnoredColumn(u))
                .filter(u -> resolveColumn(normalizedColumnName(u), entity, columnAliasOf(u)) == null)
                .toList();
    }

    private static void collectUsed(Condition condition, List<String> used) {
        if (condition instanceof Condition.Simple s) used.add(s.getColumn().getQualifiedName());
        else if (condition instanceof Condition.Unary u) used.add(u.getColumn().getQualifiedName());
        else if (condition instanceof Condition.In in) used.add(in.getColumn().getQualifiedName());
        else if (condition instanceof Condition.Between b) used.add(b.getColumn().getQualifiedName());
        else if (condition instanceof Condition.Like l) used.add(l.getColumn().getQualifiedName());
        else if (condition instanceof Condition.Group g) for (Condition c : g.getChildren()) collectUsed(c, used);
    }

    /** 列名归一化（剥别名前缀与反引号）。 */
    private static String normalizedColumnName(String expr) {
        return stripBt(afterLast(expr, '.'));
    }

    /** 引用串的表别名前缀。 */
    private static String columnAliasOf(String expr) {
        List<String> parts = Arrays.asList(expr.split("\\."));
        return parts.size() > 1 ? stripBt(String.join(".", parts.subList(0, parts.size() - 1))) : null;
    }

    // ------------------------------------------------------------------
    // 条件渲染
    // ------------------------------------------------------------------

    /** 条件 → wrapper 方法调用片段。 */
    private static String renderCondition(Condition condition, EntityTarget entity) {
        if (condition instanceof Condition.Simple s) {
            return opMethod(s.getOp()) + "(" + renderColumnRef(s.getColumn().getColumnName(), entity, s.getColumn().getTableAlias())
                    + ", " + javaValue(s.getValue()) + ")";
        }
        if (condition instanceof Condition.Unary u) {
            if ("IS NULL".equals(u.getOp())) {
                return "isNull(" + renderColumnRef(u.getColumn().getColumnName(), entity, u.getColumn().getTableAlias()) + ")";
            }
            return "isNotNull(" + renderColumnRef(u.getColumn().getColumnName(), entity, u.getColumn().getTableAlias()) + ")";
        }
        if (condition instanceof Condition.In in) {
            String method = in.getNegated() ? "notIn" : "in";
            String vals = in.getValues().stream().map(JavaCodeGenerator::javaValue).collect(Collectors.joining(", "));
            return method + "(" + renderColumnRef(in.getColumn().getColumnName(), entity, in.getColumn().getTableAlias()) + ", " + vals + ")";
        }
        if (condition instanceof Condition.Between b) {
            String method = b.getNegated() ? "notBetween" : "between";
            return method + "(" + renderColumnRef(b.getColumn().getColumnName(), entity, b.getColumn().getTableAlias())
                    + ", " + javaValue(b.getLo()) + ", " + javaValue(b.getHi()) + ")";
        }
        if (condition instanceof Condition.Like l) {
            String method = switch (l.getPosition()) {
                case ANYWHERE -> l.getNegated() ? "notLike" : "like";
                case LEFT -> l.getNegated() ? "notLikeLeft" : "likeLeft";
                case RIGHT -> l.getNegated() ? "notLikeRight" : "likeRight";
                default -> l.getNegated() ? "notLike" : "like";
            };
            return method + "(" + renderColumnRef(l.getColumn().getColumnName(), entity, l.getColumn().getTableAlias())
                    + ", " + javaValue(l.getPattern()) + ")";
        }
        if (condition instanceof Condition.Raw r) {
            // T-SqlToJavaPromote G7：可识别「表达式 比较符 字面量」形态 → 参数化 apply("... = {0}", 值)；
            // 无法识别 → 整段字符串 apply("...") 透传
            Pair<String, List<SqlValue>> parameterized = parameterizeRaw(r.getSqlText());
            List<SqlValue> values = parameterized.getSecond();
            if (values.isEmpty()) {
                return "apply(" + javaString(r.getSqlText()) + ")";
            }
            String joined = values.stream().map(JavaCodeGenerator::javaValue).collect(Collectors.joining(", "));
            return "apply(" + javaString(parameterized.getFirst()) + ", " + joined + ")";
        }
        if (condition instanceof Condition.Group g) return renderGroup(g, entity);
        if (condition instanceof Condition.ColumnComparison cc) {
            return "apply(\"" + javaEscape(cc.getLeft().getColumnName()) + " = " + cc.getRight().getColumnName() + "\")";
        }
        return "";
    }

    /** 组渲染（T-M36.1）：`(a = 1 OR b = 2)` → `nested(w -> w.eq(...))`；`NOT (...)` → `not(w -> ...)`；组内嵌套子组见 [renderGroupChildren]。 */
    private static String renderGroup(Condition.Group group, EntityTarget entity) {
        String wrapper = group.getNegated() ? "not" : "nested";
        return wrapper + "(w -> w" + renderGroupChildren(group, 2, entity) + ")";
    }

    /**
     * 组内子条件链（nextVarIndex：嵌套 lambda 变量名序号，w2/w3...）。
     *
     * <p>子组的方法选择按语义分三档（MP 3.5.15 {@code NormalSegmentList.transformList} 实证）：
     * <ul>
     *   <li>子组取反 → {@code not(wN -> ...)}：negated 必须体现在方法名上，否则 NOT 整个丢失；</li>
     *   <li>父组 OR 连接 → {@code or(wN -> ...)}：<b>用带 consumer 的 or 直接承载连接</b>，不再预发无参 {@code .or()}。
     *       预发 {@code .or()} 再发 {@code .and(...)} 会命中 MP 的「上一分隔符 OR、新分隔符 AND → 移除 OR」逻辑，
     *       把 OR 静默改成 AND（{@code a = 1 OR (b = 2 AND c = 3)} 会渲染成 {@code a = 1 AND (b = 2 AND c = 3)}）；</li>
     *   <li>其余 → {@code nested(wN -> ...)}：等价于旧 {@code .and(wN -> ...)}，但不依赖「链首 AND 被丢弃」这一巧合。</li>
     * </ul>
     */
    private static String renderGroupChildren(Condition.Group group, int nextVarIndex, EntityTarget entity) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < group.getChildren().size(); i++) {
            Condition child = group.getChildren().get(i);
            // OR 组内子组自带 or 连接（见下），无需再预发分隔符
            if (i > 0 && group.getConnector() == Connector.OR && !isOrJoinedGroup(child)) sb.append("\n        .or()");
            if (child instanceof Condition.Group g) {
                String method;
                if (g.getNegated()) method = "not";
                else if (group.getConnector() == Connector.OR) method = "or";
                else method = "nested";
                String varName = "w" + nextVarIndex;
                sb.append("\n        .").append(method).append("(").append(varName).append(" -> ").append(varName)
                        .append(renderGroupChildren(g, nextVarIndex + 1, entity)).append(")");
            } else {
                sb.append("\n        .").append(renderCondition(child, entity));
            }
        }
        return sb.toString();
    }

    /** 该子组是否以带 consumer 的 `or(wN -> ...)` 承载父组的 OR 连接（此时不预发 `.or()` 分隔符）。 */
    private static boolean isOrJoinedGroup(Condition condition) {
        return condition instanceof Condition.Group g && !g.getNegated();
    }

    /**
     * T-ReverseGroupLogicDelete：该条件是否为 MP 依据 `@TableLogic` 自动注入的逻辑删除条件。
     *
     * <p>判定三要素同时满足才算：列名命中实体的逻辑删除列、运算符为 `=`、值等于「未删除值」。
     * 用户手写 `deleted = 1`（查已删除数据）不满足值条件，照常生成。
     */
    private static boolean isInjectedLogicDelete(Condition.Simple condition, EntityTarget entity) {
        if (entity == null) return false;
        String column = entity.logicDeleteColumn();
        if (column == null) return false;
        if (!"=".equals(condition.getOp())) return false;
        if (!columnNameMatches(condition.getColumn().getColumnName(), column)) return false;
        if (!(condition.getValue() instanceof SqlValue.Literal lit)) return false;
        return trimQuotes(lit.getRaw().trim())
                .equalsIgnoreCase(trimQuotes(entity.logicDeleteValue().trim()));
    }

    /** IN 形态（`deleted IN (0)`）同样属注入——MP 个别场景以单值 IN 表达。 */
    private static boolean isInjectedLogicDelete(Condition.In condition, EntityTarget entity) {
        if (entity == null) return false;
        String column = entity.logicDeleteColumn();
        if (column == null) return false;
        if (condition.getNegated() || condition.getValues().size() != 1) return false;
        if (!columnNameMatches(condition.getColumn().getColumnName(), column)) return false;
        if (!(condition.getValues().get(0) instanceof SqlValue.Literal lit)) return false;
        return trimQuotes(lit.getRaw().trim())
                .equalsIgnoreCase(trimQuotes(entity.logicDeleteValue().trim()));
    }

    /** 列名匹配（复用 T-FixCamelMap 归一：原样 / snake_case / camelCase 三形态互认）。 */
    private static boolean columnNameMatches(String actual, String expected) {
        for (String variant : columnKeyVariants(actual)) {
            if (variant.equalsIgnoreCase(expected)) return true;
        }
        for (String variant : columnKeyVariants(expected)) {
            if (variant.equalsIgnoreCase(actual)) return true;
        }
        return false;
    }

    /** T-ReverseGroupLogicDelete：select 投影中的逻辑删除列（MP 全列展开已含，显式再列冗余）。 */
    private static boolean isInjectedLogicDeleteProjection(SelectColumn sel, EntityTarget entity) {
        if (entity == null) return false;
        String column = entity.logicDeleteColumn();
        if (column == null) return false;
        String name = stripBt(afterLast(sel.expr(), '.'));
        return columnNameMatches(name, column);
    }

    /** Kotlin `trim('\'')`：剥首尾单引号（可重复剥）。 */
    private static String trimQuotes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '\'') start++;
        while (end > start && s.charAt(end - 1) == '\'') end--;
        return s.substring(start, end);
    }

    /** 二元比较操作符 → 方法名。 */
    private static String opMethod(String op) {
        return switch (op) {
            case "=" -> "eq";
            case "!=", "<>" -> "ne";
            case ">" -> "gt";
            case ">=" -> "ge";
            case "<" -> "lt";
            case "<=" -> "le";
            default -> "apply";
        };
    }

    /**
     * 值渲染：字符串（引号字面量）→ Java 字符串；数字/布尔 → 原样。
     *
     * <p>ANSI 类型字面量（{@code DATE '2024-01-01'} / {@code TIMESTAMP '...'}，解析侧 raw 保留前缀）不能原样
     * 拼进 Java——{@code DATE '2024-01-01'} 在 Java 里是未闭合字符字面量，编译不过。这里只取引号内的
     * 值渲染为字符串，交由驱动/数据库按列类型隐式转换（与 MP 传 String 参数的实际用法一致）。
     */
    private static String javaValue(SqlValue value) {
        if (value instanceof SqlValue.Placeholder) return "\"?\"";
        if (value instanceof SqlValue.Literal lit) {
            java.util.regex.Matcher ansiTyped = ANSI_TYPED_LITERAL.matcher(lit.getRaw().trim());
            if (ansiTyped.matches()) {
                return javaString(ansiTyped.group(2).replace("''", "'"));
            }
            if (lit.getRaw().startsWith("'") && lit.getRaw().endsWith("'") && lit.getRaw().length() >= 2) {
                return javaString(lit.getUnquoted());
            }
            return lit.getRaw();
        }
        return "";
    }

    /** ANSI 类型字面量：`DATE '2024-01-01'` / `TIMESTAMP '2024-01-01 10:00:00'`（分组 2 = 引号内原文）。 */
    private static final java.util.regex.Pattern ANSI_TYPED_LITERAL = java.util.regex.Pattern.compile(
            "^(DATE|TIME|TIMESTAMP|DATETIME)\\s+'((?:[^']|'')*)'$", java.util.regex.Pattern.CASE_INSENSITIVE);

    // ------------------------------------------------------------------
    // 参数化 apply（T-SqlToJavaPromote G7）
    // ------------------------------------------------------------------

    /** `表达式 比较符 字面量` 形态识别正则（右值：SQL 字符串或数字）。 */
    private static final java.util.regex.Pattern RAW_PARAM_REGEX = java.util.regex.Pattern.compile(
            "^(.*?)\\s*(=|!=|<>|>=|<=|>|<)\\s*('(?:[^']|'')*'|-?\\d+(?:\\.\\d+)?)$");

    /**
     * Raw 条件参数化：`DATE_FORMAT(create_time, '%Y') = '2024'` → ("DATE_FORMAT(create_time, '%Y') = {0}", ['2024'])。
     * 不可识别形态返回 (原文, 空)——调用方回落整段字符串 apply。
     */
    private static Pair<String, List<SqlValue>> parameterizeRaw(String sql) {
        java.util.regex.Matcher m = RAW_PARAM_REGEX.matcher(sql);
        if (!m.matches()) return new Pair<>(sql, List.of());
        String expr = m.group(1).trim();
        String op = m.group(2);
        String literal = m.group(3);
        SqlValue value;
        if (literal.startsWith("'")) {
            value = new SqlValue.Literal(literal, literal.substring(1, literal.length() - 1).replace("''", "'"));
        } else {
            value = new SqlValue.Literal(literal);
        }
        return new Pair<>(expr + " " + op + " {0}", List.of(value));
    }

    // ------------------------------------------------------------------
    // ORDER BY / LIMIT
    // ------------------------------------------------------------------

    /** ORDER BY 渲染：相邻同向合并。 */
    private static List<String> renderOrderBy(SqlReverseResult result, EntityTarget entity) {
        List<OrderByItem> orderBy = result.orderBy().stream().filter(o -> !isIgnoredColumn(o.getColumn().getColumnName())).toList();
        if (orderBy.isEmpty()) return List.of();
        List<String> calls = new ArrayList<>();
        List<Pair<String, String>> group = new ArrayList<>();
        boolean groupAsc = orderBy.get(0).getAsc();
        for (OrderByItem item : orderBy) {
            if (item.getAsc() != groupAsc && !group.isEmpty()) {
                calls.add(orderByCall(groupAsc, group, entity));
                group = new ArrayList<>();
                groupAsc = item.getAsc();
            }
            group.add(new Pair<>(item.getColumn().getColumnName(), item.getColumn().getTableAlias()));
        }
        if (!group.isEmpty()) calls.add(orderByCall(groupAsc, group, entity));
        return calls;
    }

    private static String orderByCall(boolean asc, List<Pair<String, String>> columns, EntityTarget entity) {
        String method = asc ? "orderByAsc" : "orderByDesc";
        String args = columns.stream().map(p -> renderColumnRef(p.getFirst(), entity, p.getSecond()))
                .collect(Collectors.joining(", "));
        return method + "(" + args + ")";
    }

    /** LIMIT 渲染（MySQL 形态）。offset 非字面量（占位符等）渲染为 "?" 并保留偏移形态。 */
    private static String renderLimit(LimitClause limit) {
        String offset = limit.getOffset() == null ? null
                : (limit.getOffset() instanceof SqlValue.Literal lit ? lit.getRaw() : "?");
        String count = (limit.getCount() instanceof SqlValue.Literal lit) ? lit.getRaw() : "?";
        return offset != null ? "LIMIT " + offset + ", " + count : "LIMIT " + count;
    }

    /** LIMIT 尾段（T-SqlToJavaPromote G4：FOR UPDATE 与 LIMIT 并存时合并单 last——MP last 只保留末位）。 */
    private static String renderLimitTail(LimitClause limit, boolean forUpdate) {
        return renderLimit(limit) + (forUpdate ? " FOR UPDATE" : "");
    }

    /** SELECT 列原文（含 AS 别名）——DISTINCT 单串 select 用。 */
    private static String renderSelectRaw(SelectColumn sel) {
        return sel.alias() != null ? sel.expr() + " AS " + sel.alias() : sel.expr();
    }

    /** GROUP BY 渲染（T-SqlToJavaPromote G1）：列引用走 renderColumnRef（lambda 映射/字符串回落）。 */
    private static List<String> renderGroupBy(SqlReverseResult result, EntityTarget entity) {
        if (result.groupBy().isEmpty()) return List.of();
        String cols = result.groupBy().stream()
                .map(it -> renderColumnRef(it.getColumnName(), entity, it.getTableAlias()))
                .collect(Collectors.joining(", "));
        return List.of("groupBy(" + cols + ")");
    }

    /** HAVING 渲染（T-SqlToJavaPromote G2）：聚合表达式经 Raw 降级后原样进 having(String, Object...) 首参。 */
    private static String renderHaving(Condition h, EntityTarget entity) {
        return "having(" + javaString(renderHavingText(h)) + ")";
    }

    /** HAVING 条件 → SQL 片段文本（值内嵌；参数化升级在 G7）。 */
    private static String renderHavingText(Condition h) {
        if (h instanceof Condition.Raw r) return r.getSqlText();
        if (h instanceof Condition.Simple s) return s.getColumn().getColumnName() + " " + s.getOp() + " " + havingValue(s.getValue());
        if (h instanceof Condition.Unary u) return u.getColumn().getColumnName() + " " + u.getOp();
        if (h instanceof Condition.In in) {
            return in.getColumn().getColumnName() + " " + (in.getNegated() ? "NOT IN" : "IN") + " ("
                    + in.getValues().stream().map(JavaCodeGenerator::havingValue).collect(Collectors.joining(", ")) + ")";
        }
        if (h instanceof Condition.Between b) {
            return b.getColumn().getColumnName() + " " + (b.getNegated() ? "NOT BETWEEN" : "BETWEEN") + " "
                    + havingValue(b.getLo()) + " AND " + havingValue(b.getHi());
        }
        if (h instanceof Condition.Like l) {
            return l.getColumn().getColumnName() + " " + (l.getNegated() ? "NOT LIKE" : "LIKE") + " " + havingValue(l.getPattern());
        }
        if (h instanceof Condition.ColumnComparison cc) {
            return cc.getLeft().getColumnName() + " = " + cc.getRight().getColumnName();
        }
        if (h instanceof Condition.Group g) {
            return g.getChildren().stream().map(JavaCodeGenerator::renderHavingText).collect(Collectors.joining(" AND "));
        }
        return "";
    }

    /** HAVING 值：字面量原样；占位符 `?`。 */
    private static String havingValue(SqlValue v) {
        return (v instanceof SqlValue.Literal lit) ? lit.getRaw() : "?";
    }

    // ------------------------------------------------------------------
    // 文本工具
    // ------------------------------------------------------------------

    /** Java 字符串字面量（含引号与转义）。 */
    private static String javaString(String value) {
        return "\"" + javaEscape(value) + "\"";
    }

    /** Java 字符串转义（引号/反斜杠/控制字符）。 */
    public static String javaEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 4);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 表名 → 实体类名推断（下划线 → 驼峰首字母大写）：`car_device_info` → `CarDeviceInfo`。 */
    public static String entityClassName(String tableName) {
        // 带引号表名（`"user"`）先剥包裹再分段驼峰（Kotlin removeSurrounding("\"") 语义）
        String name = tableName;
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
            name = name.substring(1, name.length() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : name.split("_")) {
            if (!seg.isEmpty()) sb.append(seg.substring(0, 1).toUpperCase()).append(seg.substring(1));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 首字母大写。 */
    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    /** 下划线列名 → 首字母小写驼峰。 */
    private static String camelFromUnderscore(String column) {
        StringBuilder sb = new StringBuilder();
        for (String seg : column.split("_")) {
            if (!seg.isEmpty()) sb.append(seg.substring(0, 1).toUpperCase()).append(seg.substring(1));
        }
        String s = sb.toString();
        return s.isEmpty() ? s : s.substring(0, 1).toLowerCase() + s.substring(1);
    }

    /** setter 链（列→字段：实体映射优先，缺失按列名驼峰推断）。 */
    private static String setterChain(List<SqlValue> row, List<Pair<Integer, String>> insertPairs, String entityName, EntityTarget entity) {
        if (insertPairs.isEmpty()) return "new " + entityName + "()";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < insertPairs.size(); i++) {
            Pair<Integer, String> p = insertPairs.get(i);
            int colIndex = p.getFirst();
            String column = p.getSecond();
            String fieldBase = (entity != null && entity.columnToField().containsKey(column))
                    ? entity.columnToField().get(column) : camelFromUnderscore(column);
            SqlValue value = colIndex < row.size() ? row.get(colIndex) : null;
            if (i == 0) sb.append("new ").append(entityName).append("()");
            else sb.append("\n    ");
            // T-SqlToJavaPromote G6：INSERT NULL 建模为 Literal("NULL") → 生成 Java null
            String rendered = (value instanceof SqlValue.Literal lit && "NULL".equals(lit.getRaw()))
                    ? "null" : (value != null ? javaValue(value) : "null");
            sb.append(".set").append(capitalize(fieldBase)).append("(").append(rendered).append(")");
        }
        return sb.toString();
    }

    /** 按调用类真实方法集选择调用方法（T-M44）。 */
    private static String prefer(InvocationTarget inv, String primary, String... fallbacks) {
        if (inv.methods().isEmpty()) return primary;
        List<String> all = new ArrayList<>();
        all.add(primary);
        all.addAll(Arrays.asList(fallbacks));
        for (String m : all) {
            if (inv.methods().contains(m)) return m;
        }
        return primary;
    }

    /** 剥反引号（首尾）。 */
    private static String stripBt(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '`') start++;
        while (end > start && s.charAt(end - 1) == '`') end--;
        return s.substring(start, end);
    }

    /** 取末段（最后一个 c 之后）。 */
    private static String afterLast(String s, char c) {
        int i = s.lastIndexOf(c);
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** Long 解析（失败返回 null）。 */
    private static Long parseLongOrNull(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 派生表（`FROM (SELECT …) t`）无法推断实体类名——生成 `QueryWrapper<(SELECT …)>` 必然编译不过。
     * 无实体上下文时明确报错，优于产出不可编译代码（有实体上下文则由用户选定实体，不受影响）。
     */
    private static void requireConcreteTable(String tableName, EntityTarget entity) {
        if (entity != null) return;
        if (tableName != null && tableName.stripLeading().startsWith("(")) {
            throw new SqlReverseException(
                    0,
                    SqlReverseParser.KEY_UNSUPPORTED,
                    "FROM 派生表（子查询）无法推断实体类名，请选择实体或改写为 JOIN 形态"
            );
        }
    }
}
