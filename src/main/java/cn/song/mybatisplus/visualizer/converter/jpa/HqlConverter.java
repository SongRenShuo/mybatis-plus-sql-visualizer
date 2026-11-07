package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import cn.song.mybatisplus.visualizer.psi.jpa.HqlEntityNameExtractor;
import com.intellij.openapi.project.Project;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * HQL → SQL 转换器（F-JPA-02 / Wave2-P1）。
 *
 * 核心设计：**保留原始 HQL 结构**，仅做安全的字符串级变换：
 *  1. 命名参数 `:param` → `?`
 *  2. 位置参数 `?1` → `?`
 *  3. IN 子句修正：`IN ?` → `IN (?)`
 *  4. `new Xxx(...)` 动态实例化表达式 → 提取构造参数（关键字大小写不敏感，见 {@link NewExpressionStripper}）
 *  5. JOIN FETCH / 关联 JOIN → LEFT/INNER JOIN（J7：ON 条件经 JpaJoinOnResolver
 *     按「PSI 注解 > 关联元数据 > 猜测降级」三级优先真实化）
 *  6. 实体名 → 表名映射（仅当有有效 TableInfo 时）
 *  6.5 别名作用域解析（O-57）：`FROM/JOIN 别名 → 该实体的 TableInfo`，供第 9 步按作用域分派；
 *     必须在实体名→表名替换**之前**扫描（那时实体名还在 SQL 里），见 JpaAliasTableResolver
 *  7. 关联字段路径映射：`alias.field.id` → 外键列名（仅当有有效 TableInfo 时）
 *  8. 属性名 → 列名映射（仅当有有效 TableInfo 时；O-57 起按别名作用域分派，
 *     副表别名的属性同样映射——此前只映射根实体列清单，`detail.pickTicketId` 原样透传
 *     导致预览 SQL 报 Unknown column）
 *  8.5 子查询 FROM/JOIN 中的其他实体名 → 表名（J13，需 Project；无 Project 跳过）
 *  9. SELECT 子句展开：`SELECT <别名>` → 列清单（T-HQL-SELECT-01）
 *     HQL 语义 `SELECT u` = 取整个实体，须展开为全部映射列，否则 MySQL 报 1054；
 *     无 SELECT 子句（裸 `from 实体`）补 SELECT 列清单（T-BareFromSelect）
 *     ——展开逻辑见 SelectClauseExpander，与 JpqlConverter 共用
 * 10. UPDATE 语句（J2）：`UPDATE 实体 [别名] SET 列 = 值[, ...] [WHERE ...]` →
 *     UpdateStatement（SET 对 / WHERE 解析见 JpaUpdateParser，不可精细解析的
 *     WHERE 整体作 Raw 条件透传）
 * 11. DELETE 语句 → DeleteStatement（既有行为不变）
 *
 * J19 边界（「识别 + 不破坏」）：HQL 高级特性——`INSERT INTO ... SELECT`、`WITH ... SELECT`
 * （CTE）、UNION/INTERSECT/EXCEPT、窗口函数 `OVER (...)`——在字符串管道中天然透传
 * （参数替换/实体替换按文本命中，SELECT 展开器对上述形态已短路不误改，见
 * SelectClauseExpander），检测到时仅在 statement 上附降级注释，不新增完整建模。
 *
 * 关键：返回的 SQL 字符串（含 ? 占位符）直接用于面板显示和参数回填。
 * 转换器不解析条件树（conditions 为空），因此必须使用转换后的 SQL 字符串。
 *
 * 无状态单例，纯函数逻辑，可独立单测。
 */
public final class HqlConverter {

    // T-Perf-23：函数体内 Regex(...) 提为常量（热路径免重复编译 Pattern）
    private static final Pattern HQL_NAMED_PARAM_TO_Q = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final Pattern HQL_POSITIONAL_PARAM_TO_Q = Pattern.compile("\\?\\d+");
    private static final Pattern HQL_IN_SPACE_Q = Pattern.compile("(?i)\\bIN\\s+\\?");
    private static final Pattern HQL_CAMEL_SPLIT_PATTERN = Pattern.compile("([a-z])([A-Z])");
    private static final Pattern HQL_MEMBER_OF_PATTERN = Pattern.compile("(.+)\\s+MEMBER OF\\s+(.+)", Pattern.CASE_INSENSITIVE);

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final HqlConverter INSTANCE = new HqlConverter();

    private HqlConverter() {
    }

    /**
     * HQL 转换产物。
     *
     * @param sql        转换后的 SQL 字符串（命名参数已替换为 ?）
     * @param statement  语句模型（SelectStatement / UpdateStatement / DeleteStatement）
     * @param inClauseParams 使用 IN 子句的参数名集合
     * @param placeholderParamNames T-JpaParamOrder F3：HQL 命名参数名序（首现序，与替换后
     *         SQL 中 {@code ?} 一一对应）；位置参数 {@code ?N} 不在内。调用方据此做按名回填——
     *         仅当 SQL 全部 {@code ?} 均来自命名参数时可用（位置参数混入时数量不齐）。
     * @param degradations T-PagesHql：转换降级清单（关联路径需 JOIN / 推断 ON 条件 /
     *         实体不可解析 / 高级构造透传等）。空表 = 无降级。pages 链把它并入 warning 展示；
     *         其它调用方可忽略（默认空，零行为变化）。
     */
    public static final class Result {
    private final @NotNull String sql;
    private final @NotNull SqlStatement statement;
    private final @NotNull Set<String> inClauseParams;
    private final @NotNull List<String> placeholderParamNames;
    private final @NotNull List<String> degradations;

    public Result(@NotNull String sql, @NotNull SqlStatement statement, @NotNull Set<String> inClauseParams) {
        this(sql, statement, inClauseParams, List.of());
    }

    public Result(@NotNull String sql, @NotNull SqlStatement statement, @NotNull Set<String> inClauseParams,
                  @NotNull List<String> placeholderParamNames) {
        this(sql, statement, inClauseParams, placeholderParamNames, List.of());
    }

    public Result(@NotNull String sql, @NotNull SqlStatement statement, @NotNull Set<String> inClauseParams,
                  @NotNull List<String> placeholderParamNames, @NotNull List<String> degradations) {
        this.sql = sql;
        this.statement = statement;
        this.inClauseParams = inClauseParams;
        this.placeholderParamNames = placeholderParamNames;
        this.degradations = degradations;
    }

    public @NotNull String getSql() { return sql; }
    public @NotNull String sql() { return sql; }
    public @NotNull SqlStatement getStatement() { return statement; }
    public @NotNull SqlStatement statement() { return statement; }
    public @NotNull Set<String> getInClauseParams() { return inClauseParams; }
    public @NotNull Set<String> inClauseParams() { return inClauseParams; }
    public @NotNull List<String> getPlaceholderParamNames() { return placeholderParamNames; }
    public @NotNull List<String> placeholderParamNames() { return placeholderParamNames; }
    public @NotNull List<String> getDegradations() { return degradations; }
    public @NotNull List<String> degradations() { return degradations; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Result)) return false;
        Result that = (Result) o;
        return java.util.Objects.equals(this.sql, that.sql) && java.util.Objects.equals(this.statement, that.statement) && java.util.Objects.equals(this.inClauseParams, that.inClauseParams) && java.util.Objects.equals(this.placeholderParamNames, that.placeholderParamNames) && java.util.Objects.equals(this.degradations, that.degradations);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(sql, statement, inClauseParams, placeholderParamNames, degradations);
    }

    @Override
    public String toString() {
        return "Result[" + "sql=" + sql + ", " + "statement=" + statement + ", " + "inClauseParams=" + inClauseParams + ", " + "placeholderParamNames=" + placeholderParamNames + ", " + "degradations=" + degradations + "]";
    }
}

    /**
     * 转换 HQL 字符串为 Result（tableInfo / project 取默认 null）。
     *
     * @param hql       HQL 查询字符串
     * @return Result（转换后 SQL + 语句模型）
     */
    public static Result convert(@NotNull String hql) {
        return convert(hql, null, null);
    }

    /**
     * 转换 HQL 字符串为 Result（project 取默认 null）。
     *
     * @param hql       HQL 查询字符串
     * @param tableInfo 实体表信息（列映射用）；可为 null，此时仅做参数替换
     * @return Result（转换后 SQL + 语句模型）
     */
    public static Result convert(@NotNull String hql, @Nullable TableInfo tableInfo) {
        return convert(hql, tableInfo, null);
    }

    /**
     * 转换 HQL 字符串为 Result（project / entityAlias 取默认 null）。
     *
     * @param hql       HQL 查询字符串
     * @param tableInfo 实体表信息（列映射用）；可为 null，此时仅做参数替换
     * @param project   项目（J7 JOIN ON 注解级解析 / J13 子查询实体映射用）；
     *                  null 时两处降级为关联元数据 / 跳过，行为与历史版本一致
     * @return Result（转换后 SQL + 语句模型）
     */
    public static Result convert(@NotNull String hql, @Nullable TableInfo tableInfo, @Nullable Project project) {
        return convert(hql, tableInfo, project, null);
    }

    /**
     * 转换 HQL 字符串为 Result（完整形态）。
     *
     * @param hql       HQL 查询字符串
     * @param tableInfo 实体表信息（列映射用）；可为 null，此时仅做参数替换
     * @param project   项目（J7 JOIN ON 注解级解析 / J13 子查询实体映射用）；
     *                  null 时两处降级为关联元数据 / 跳过，行为与历史版本一致
     * @param entityAlias @Entity(name=...) 实体别名（T-HqlInhCols）；HQL {@code FROM Alias}
     *                  的 Alias 是实体名而非类名，传入后 mapEntityToTable 同样按别名替换表名。
     *                  null 时行为与历史版本一致
     * @return Result（转换后 SQL + 语句模型）
     */
    public static Result convert(@NotNull String hql, @Nullable TableInfo tableInfo, @Nullable Project project,
                                 @Nullable String entityAlias) {
        return convert(hql, tableInfo, project, entityAlias, null);
    }

    public static Result convert(@NotNull String hql, @Nullable TableInfo tableInfo, @Nullable Project project,
                                 @Nullable String entityAlias, @Nullable com.intellij.psi.PsiElement context) {
        return convertInternal(hql, tableInfo, project, entityAlias, null, context);
    }

    /**
     * 实体解析函数注入形态（O-57）：调用方已有 PsiClass / 解析结果时直入，
     * 免按名 PSI 检索（LightPlatform 测试环境无需依赖索引；生产入口见 {@link #convert} 的 project 形态）。
     *
     * @param resolveEntity 收到 FROM/JOIN 中的实体标识符，返回其表信息；返回 null 表示无元数据，
     *                      该别名回落既有根实体列清单行为（与无 Project 时一致）
     * @return Result（转换后 SQL + 语句模型）
     */
    public static Result convertWithEntityResolver(@NotNull String hql, @Nullable TableInfo tableInfo,
                                                   @Nullable String entityAlias,
                                                   @NotNull kotlin.jvm.functions.Function1<String, TableInfo> resolveEntity) {
        return convertInternal(hql, tableInfo, null, entityAlias, resolveEntity, null);
    }

    /** Kotlin 默认参数等价重载：{@code entityAlias = null}。 */
    public static Result convertWithEntityResolver(@NotNull String hql, @Nullable TableInfo tableInfo,
                                                   @NotNull kotlin.jvm.functions.Function1<String, TableInfo> resolveEntity) {
        return convertWithEntityResolver(hql, tableInfo, null, resolveEntity);
    }

    /**
     * 内部转换实现（全量形态）。
     *
     * @param resolveEntity O-57 别名作用域解析的实体解析函数；null 走生产入口（按 project + PSI 检索）
     * @return Result（转换后 SQL + 语句模型）
     */
    private static Result convertInternal(@NotNull String hql, @Nullable TableInfo tableInfo,
                                          @Nullable Project project, @Nullable String entityAlias,
                                          @Nullable kotlin.jvm.functions.Function1<String, TableInfo> resolveEntity,
                                          @Nullable com.intellij.psi.PsiElement context) {
        JpaQueryEntityLookup lookup = new JpaQueryEntityLookup(project, context);
        String rootName = JpaQueryEntityLookup.rootEntity(hql);
        TableInfo resolved = tableInfo != null ? tableInfo : rootName != null ? lookup.resolveTable(rootName) : null;
        TableInfo table = resolved != null ? resolved : createFallbackTable();
        String mappedRootName = rootName != null && (tableInfo == null
                || rootName.substring(rootName.lastIndexOf('.') + 1).equals(table.getEntityClassName()) || rootName.equals(entityAlias)
                || (lookup.resolveClass(rootName) != null && table.getEntityClassName().equals(lookup.resolveClass(rootName).getName()))) ? rootName : null;
        kotlin.jvm.functions.Function1<String, TableInfo> resolveTable = name -> {
            if (name.equals(mappedRootName) && !table.getEntityClassName().isBlank()) return table;
            TableInfo supplied = resolveEntity != null ? resolveEntity.invoke(name) : null;
            return supplied != null ? supplied : lookup.resolveTable(name);
        };

        // 0. 检测 IN 子句参数（在命名参数替换前检测）
        Set<String> inClauseParams = findInClauseParams(hql);

        // 1. 替换命名参数 :param → ?
        // T-JpaParamOrder F3：替换前用同一正则收集名序（findAll 顺序=首现序=替换后 `?` 序），
        // 供渲染期按名回填；`::` 双冒号由正则负向断言排除，与替换语义一致
        List<String> placeholderParamNames = new ArrayList<>();
        Matcher namedParamMatcher = HQL_NAMED_PARAM_TO_Q.matcher(hql);
        while (namedParamMatcher.find()) {
            placeholderParamNames.add(namedParamMatcher.group(1));
        }
        String sql = replaceNamedParameters(hql);

        // 2. 替换位置参数 ?1 → ?
        sql = replacePositionalParameters(sql);

        // 3. 处理 IN 子句：IN ? → IN (?)
        sql = handleInClause(sql);

        // 4. 处理 `new Xxx(...)` 动态实例化表达式（关键字大小写不敏感）
        sql = NewExpressionStripper.strip(sql);

        // 5. 处理 JOIN FETCH / 关联 JOIN → LEFT/INNER JOIN（J7：ON 条件真实化）
        sql = JpaJoinRewriter.rewriteWithLookup(sql, table, lookup);

        // 6. 处理 MEMBER OF（降级为注释提示）
        sql = handleMemberOf(sql);

        // 6.5 别名作用域解析（O-57）：须在实体名→表名替换之前扫描——那时 FROM/JOIN 后的
        // 实体标识符还在 SQL 里，替换成表名后 PSI 短名检索不再命中；
        // 又须在第 5 步 JOIN 改写之后——改写新增的副表别名只有那时才存在
        JpaAliasTableResolver.JpaAliasScope scope = JpaAliasTableResolver.resolveAliases(sql, table, resolveTable);

        // 7. 实体名 → 表名映射
        if (!table.getEntityClassName().isBlank()) {
            sql = mapEntityToTable(sql, table, entityAlias != null ? entityAlias : mappedRootName);
            // 8. 关联字段路径映射：alias.field.id → 外键列名（须在属性映射之前）
            sql = mapAssociationPath(sql, table);
            // 9. 属性名 → 列名映射（按别名作用域分派，副表别名同样映射）
            sql = mapPropertyToColumn(sql, table, scope);
        } else {
            // Fallback：实体不可解析时，从 FROM 子句提取实体名替换为 unknown_table
            sql = mapFallbackEntityToTable(sql, table.getTableName());
        }

        // 8.5 子查询 FROM/JOIN 中其他实体名 → 表名（J13；需 Project，无则跳过）。
        // 别名作用域已解析出实体→表名，透传给扫描器复用，免二次 PSI 检索
        sql = JpaFromEntityMapper.mapSubqueryEntities(sql, table, name -> scope.getEntityTableNames().get(name));

        // 9. SELECT 子句展开（T-HQL-SELECT-01 / T-BareFromSelect）
        // 必须排在步骤 5（JOIN 改写）之后——JOIN 别名只有改写后才存在，
        // 早于该步解析会取不到副表别名。DELETE/UPDATE/INSERT/CTE/UNION 内部已短路。
        sql = SelectClauseExpander.expand(sql, table);

        // 10. 检测 DELETE HQL → DeleteStatement
        if (sql.trim().toUpperCase().startsWith("DELETE")) {
            // T-JpaDeleteWhereGuard：WHERE 接进条件树（与 UPDATE 同一套白名单 + Raw 兜底）——
            // 此前 conditions 恒空，buildJpaWarning 对带 WHERE 的 DELETE 误报 BlockAttack 全表删除
            JpaUpdateParser.ParsedDelete parsedDelete = JpaUpdateParser.parseDelete(sql);
            return new Result(
                    sql,
                    new DeleteStatement(table, parsedDelete != null ? parsedDelete.getConditions() : List.of()),
                    inClauseParams,
                    placeholderParamNames,
                    degradationsOf(sql, null));
        }

        // 10.5 检测 UPDATE HQL → UpdateStatement（J2）
        if (sql.trim().toUpperCase().startsWith("UPDATE")) {
            JpaUpdateParser.ParsedUpdate parsed = JpaUpdateParser.parse(sql);
            if (parsed != null) {
                return new Result(
                        sql,
                        new UpdateStatement(table, parsed.getSets(), parsed.getConditions(),
                                null, parsed.getTableAlias(), List.of()),
                        inClauseParams,
                        placeholderParamNames,
                        degradationsOf(sql, null));
            }
            // 头部结构异常（非法 HQL）→ 维持历史 SelectStatement 透传，不抛异常
        }

        String statementComment = advancedConstructComment(sql);
        if (statementComment == null) {
            statementComment = inheritanceBoundaryComment(table);
        }
        return new Result(
                sql,
                new SelectStatement(
                        table,
                        List.of(),
                        null,
                        List.of(),
                        false,
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        statementComment,
                        List.of(),
                        null,
                        null),
                inClauseParams,
                placeholderParamNames,
                degradationsOf(sql, statementComment));
    }

    /**
     * T-PagesHql：从转换产物里收集降级说明。
     *
     * <p>转换器的降级信息以 SQL 注释/语句 comment 形式落地（「关联字段，需 JOIN …」等注释），
     * 这里按已知文案标记归一成人话清单——不改写任何转换行为，只让调用方能把「产物不可直接执行
     * 的原因」展示到面板上（用户不看 SQL 注释就不知道）。
     */
    private static List<String> degradationsOf(@NotNull String sql, @org.jetbrains.annotations.Nullable String statementComment) {
        LinkedHashSet<String> notes = new LinkedHashSet<>();
        if (statementComment != null && !statementComment.isBlank()) {
            String mapped = null;
            for (kotlin.Pair<String, String> marker : Degradations.DEGRADATION_MARKERS) {
                if (statementComment.contains(marker.getFirst())) {
                    mapped = marker.getSecond();
                    break;
                }
            }
            if (mapped != null) {
                notes.add(mapped);
            } else {
                // Kotlin removeSurrounding("/* ", " */")：首尾匹配才剥，否则原文；take(120) 截断
                String stripped = statementComment;
                if (stripped.startsWith("/* ") && stripped.endsWith(" */")) {
                    stripped = stripped.substring(3, stripped.length() - 3);
                }
                notes.add(stripped.substring(0, Math.min(120, stripped.length())));
            }
        }
        for (kotlin.Pair<String, String> marker : Degradations.DEGRADATION_MARKERS) {
            if (sql.contains(marker.getFirst())) {
                notes.add(marker.getSecond());
            }
        }
        return List.copyOf(notes);
    }

    /** Kotlin private object Degradations 等价。 */
    private static final class Degradations {
        /** 标记 → 展示文案（顺序即输出顺序，保证产物稳定可断言）。 */
        static final List<kotlin.Pair<String, String>> DEGRADATION_MARKERS = List.of(
                new kotlin.Pair<>("关联字段，需 JOIN", "关联字段路径需 JOIN（已保留原文）"),
                new kotlin.Pair<>("推断的ON条件，请验证", "JOIN ON 条件为推断值，需人工验证"),
                new kotlin.Pair<>("实体不可解析", "存在无法解析的实体（列清单未展开）"),
                new kotlin.Pair<>("ON 条件中的", "ON 条件含未映射限定符"),
                new kotlin.Pair<>("HQL 高级特性", "HQL 高级特性（CTE/UNION/INSERT-SELECT）仅透传"),
                new kotlin.Pair<>("@Inheritance(", "继承策略与单表列口径不一致"),
                new kotlin.Pair<>("MEMBER OF", "MEMBER OF 未转成 SQL"),
                new kotlin.Pair<>("副表", "副表列元数据缺失，已按别名.* 降级"));

        private Degradations() {
        }
    }

    // ------------------------------------------------------------------
    // 参数替换
    // ------------------------------------------------------------------

    /**
     * 替换命名参数 `:paramName` → `?`（排除 `::` 类型转换运算符）。
     */
    private static String replaceNamedParameters(@NotNull String hql) {
        return HQL_NAMED_PARAM_TO_Q.matcher(hql).replaceAll("?");
    }

    /**
     * 替换位置参数 `?1`, `?2` 等 → `?`。
     */
    private static String replacePositionalParameters(@NotNull String hql) {
        return HQL_POSITIONAL_PARAM_TO_Q.matcher(hql).replaceAll("?");
    }

    // ------------------------------------------------------------------
    // IN 子句
    // ------------------------------------------------------------------

    /**
     * 检测 HQL 中使用 IN 子句的命名参数。
     */
    private static Set<String> findInClauseParams(@NotNull String hql) {
        Pattern pattern = Pattern.compile("(?i)\\bIN\\s+:([a-zA-Z_][a-zA-Z0-9_]*)");
        Matcher matcher = pattern.matcher(hql);
        Set<String> result = new LinkedHashSet<>();
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /**
     * 处理 `IN ?` → `IN (?)`。
     */
    private static String handleInClause(@NotNull String hql) {
        return HQL_IN_SPACE_Q.matcher(hql).replaceAll("IN (?)");
    }

    // ------------------------------------------------------------------
    // JOIN FETCH / 关联 JOIN（J7：改写逻辑统一在 JpaJoinRewriter，ON 条件真实化）
    // ------------------------------------------------------------------

    /**
     * J19 高级特性降级注释：检测 HQL `WITH ... SELECT`（CTE）、UNION/INTERSECT/EXCEPT、
     * `INSERT INTO ... SELECT`。这些形态在字符串管道中透传（参数替换/实体替换仍生效，
     * SELECT 展开器已短路不误改），不新增完整建模，仅附注释说明边界。
     */
    @Nullable
    private static String advancedConstructComment(@NotNull String sql) {
        String head = sql.trim().toUpperCase();
        boolean isInsertSelect = head.startsWith("INSERT");
        boolean hasCte = SelectClauseExpander.hasTopLevelKeyword(sql, "WITH");
        boolean hasSetOp = SelectClauseExpander.hasTopLevelKeyword(sql, "UNION")
                || SelectClauseExpander.hasTopLevelKeyword(sql, "INTERSECT")
                || SelectClauseExpander.hasTopLevelKeyword(sql, "EXCEPT");
        if (isInsertSelect || hasCte || hasSetOp) {
            return "/* HQL 高级特性（INSERT-SELECT / CTE / UNION 等），仅做参数与实体替换透传 */";
        }
        return null;
    }

    /**
     * T-HIC-04：@Inheritance(JOINED/TABLE_PER_CLASS) 边界标注——只注释不改行为。
     * JOINED 把父类字段存于父表（SELECT 需跨表连接），TABLE_PER_CLASS 每子类独立表；
     * 本插件列清单按「父类字段合并进子表」的单表口径渲染，与实际建表不符时
     * 预览产物需自行改写。SINGLE_TABLE（默认）合并语义正确，不标注。
     */
    @Nullable
    private static String inheritanceBoundaryComment(@NotNull TableInfo table) {
        String strategy = table.getInheritanceStrategy();
        if ("JOINED".equals(strategy) || "TABLE_PER_CLASS".equals(strategy)) {
            return "/* @Inheritance(" + strategy + ")：父类字段实际存于"
                    + ("JOINED".equals(strategy) ? "父表（JOIN 连接获取）" : "各子类独立表")
                    + "，列清单按单表口径合并，执行前请按实际建表改写 */";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // MEMBER OF
    // ------------------------------------------------------------------

    /**
     * 处理 `MEMBER OF` 语法（降级为注释提示）。
     */
    private static String handleMemberOf(@NotNull String hql) {
        if (!hql.toLowerCase().contains("member of")) return hql;
        Matcher matcher = HQL_MEMBER_OF_PATTERN.matcher(hql);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(1)
                    + " /* MEMBER OF → 建议使用 IN/EXISTS 重写 */ " + matcher.group(2);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 实体名 → 表名映射
    // ------------------------------------------------------------------

    /**
     * 将 HQL 中的实体类名映射为数据库表名。
     * 仅在 TableInfo 有效时调用。
     * T-HqlInhCols：{@code @Entity(name=...)} 实体别名同样命中替换（HQL {@code FROM Alias} 的
     * Alias 是实体名而非类名，JPA 语义）；别名经 entityAlias 参数传入，
     * resolve 链由 JpaMetadataReader.entityNameOf 读取。
     */
    private static String mapEntityToTable(@NotNull String hql, @NotNull TableInfo table, @Nullable String entityAlias) {
        if (table.getEntityClassName().isBlank()) return hql;
        // 普查批：字面量掩码——`like '%UserEntity%'` 等单引号字面量内的实体名不得被替换
        JpaSqlLiteralMasking.Masked maskResult = JpaSqlLiteralMasking.mask(hql);
        String result = maskResult.text();
        Pattern classNamePattern = entityPattern(table.getEntityClassName());
        if (classNamePattern != null && (entityAlias == null || !entityAlias.contains("."))) {
            result = classNamePattern.matcher(result).replaceAll(Matcher.quoteReplacement(table.getTableName()));
        }
        if (entityAlias != null && !entityAlias.isBlank() && !entityAlias.equals(table.getEntityClassName())) {
            Pattern aliasPattern = entityPattern(entityAlias);
            if (aliasPattern != null) {
                result = aliasPattern.matcher(result).replaceAll(Matcher.quoteReplacement(table.getTableName()));
            }
        }
        return JpaSqlLiteralMasking.unmask(result, maskResult.literals());
    }

    /** 实体标识符全词匹配 Pattern（词边界防子串误伤；构造失败容错返回 null）。 */
    @Nullable
    private static Pattern entityPattern(@NotNull String identifier) {
        return Pattern.compile("(?<![\\w.$])" + Pattern.quote(identifier) + "\\b");
    }

    // ------------------------------------------------------------------
    // 关联字段路径映射
    // ------------------------------------------------------------------

    /**
     * 处理关联字段路径：`alias.field.id` → `alias.joinColumn`。
     *
     * HQL 中关联字段路径的语义：
     * - `rti.ruleTable.id` → 关联字段 `ruleTable` 的主键，等价于外键列 `RULE_TABLE_ID`
     * - `rti.ruleTable.name` → 关联实体的 `name` 属性（需要 JOIN，暂用注释提示）
     */
    private static String mapAssociationPath(@NotNull String hql, @NotNull TableInfo table) {
        if (table.getAssociations().isEmpty()) return hql;

        // 普查批：字面量掩码——字面量内的 `x.<关联字段名>.y` 点号串不得被改写或注入注释
        JpaSqlLiteralMasking.Masked maskResult = JpaSqlLiteralMasking.mask(hql);
        String result = maskResult.text();
        // 匹配三段式路径：alias.fieldName.propertyName
        Pattern threeLevelPattern = Pattern.compile(
                "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\b");

        Matcher matcher = threeLevelPattern.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String alias = matcher.group(1);
            String fieldName = matcher.group(2);
            String propertyName = matcher.group(3);

            AssociationInfo association = table.associationByField(fieldName);
            if (association != null) {
                if (propertyName.equalsIgnoreCase("id")) {
                    // alias.field.id → alias.joinColumn（外键列）
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(alias + "." + association.getJoinColumn()));
                } else {
                    // alias.field.property → 需要 JOIN，添加注释提示
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(
                            alias + "." + fieldName + "." + propertyName + " /* 关联字段，需 JOIN " + association.getTargetTable() + " */"));
                }
            } else {
                // 非关联字段，保持原样
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return JpaSqlLiteralMasking.unmask(sb.toString(), maskResult.literals());
    }

    // ------------------------------------------------------------------
    // 属性名 → 列名映射
    // ------------------------------------------------------------------

    /**
     * 将 HQL 中的属性名映射为数据库列名（O-57 起按别名作用域分派）。
     *
     * 替换核心见 {@link JpaAliasTableResolver.JpaAliasPropertyMapper}（与 JpqlConverter 共用同一份实现）。
     * 仅在 TableInfo 有效时调用。
     */
    private static String mapPropertyToColumn(@NotNull String hql, @NotNull TableInfo table,
                                              @NotNull JpaAliasTableResolver.JpaAliasScope scope) {
        return JpaAliasTableResolver.JpaAliasPropertyMapper.mapProperties(hql, table, scope);
    }

    /**
     * Fallback：实体不可解析时，从语句提取实体名替换为 unknown_table。
     * 仅替换第一个匹配（主表），避免重复替换导致 SQL 异常。
     * T-HqlUpdateEntityMap S1：实体名提取走共享提取器（语句头优先）——
     * UPDATE/DELETE 头同样命中，不再只认 FROM 子句。
     */
    private static String mapFallbackEntityToTable(@NotNull String hql, @NotNull String tableName) {
        String entityName = HqlEntityNameExtractor.extract(hql);
        if (entityName == null) return hql;
        // 普查批：掩码 + 词边界——裸 replaceFirst 会命中更长标识符的前缀子串
        // （`OwnerInfo` 命中 `OwnerInfoHelper` → `unknown_tableHelper`）或字面量内容
        JpaSqlLiteralMasking.Masked maskResult = JpaSqlLiteralMasking.mask(hql);
        Pattern entityNamePattern = entityPattern(entityName);
        String replaced = entityNamePattern != null
                ? entityNamePattern.matcher(maskResult.text()).replaceFirst(Matcher.quoteReplacement(tableName))
                : maskResult.text();
        return JpaSqlLiteralMasking.unmask(replaced, maskResult.literals());
    }

    // ------------------------------------------------------------------
    // SELECT 子句展开（T-HQL-SELECT-01）
    // ------------------------------------------------------------------

        /** 渲染单个别名的展开结果：`alias.col1, alias.col2` 或降级 `alias.*`。 */
    private static String renderColumns(@NotNull String alias, @Nullable List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return alias + ".* /* 列信息未解析，降级为 " + alias + ".* */";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(alias).append(".").append(columns.get(i));
        }
        return sb.toString();
    }

    /**
     * 解析 FROM 子句区域中的「别名 → 列清单」映射。
     *
     * 主表别名取主表列清单；JOIN 副表无列元数据（TableInfo 只含主表列），降级为 `alias.*`。
     */
    private static LinkedHashMap<String, List<String>> parseAliasColumns(@NotNull String sql, int fromIdx, @NotNull TableInfo table) {
        String region = fromRegion(sql, fromIdx);
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();

        Pattern rootPattern = Pattern.compile(
                "^\\s*([a-zA-Z_][a-zA-Z0-9_.$]*)\\s*(?:AS\\s+)?\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b",
                Pattern.CASE_INSENSITIVE);
        Matcher rootMatch = rootPattern.matcher(region);
        if (rootMatch.find()) {
            String alias = rootMatch.group(2);
            List<String> columns = table.getColumns().stream().map(ColumnInfo::getColumnName).toList();
            result.put(alias, columns.isEmpty() ? null : columns);
        }

        Pattern joinPattern = Pattern.compile(
                "\\bJOIN\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)\\s*(?:AS\\s+)?\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b",
                Pattern.CASE_INSENSITIVE);
        Matcher joinMatcher = joinPattern.matcher(region);
        while (joinMatcher.find()) {
            String alias = joinMatcher.group(2);
            // 过滤误命中：JOIN 无别名时下一个词可能是 ON / WHERE 等子句关键字
            if ("ON".equalsIgnoreCase(alias) || "WHERE".equalsIgnoreCase(alias)) continue;
            if (!result.containsKey(alias)) result.put(alias, null);
        }
        return result;
    }

    /** 截取 FROM 之后、WHERE/GROUP BY/ORDER BY/HAVING/UNION/LIMIT 之前的子句区域。 */
    private static String fromRegion(@NotNull String sql, int fromIdx) {
        List<String> endKeywords = List.of("WHERE", "GROUP BY", "ORDER BY", "HAVING", "UNION", "LIMIT");
        int end = sql.length();
        for (String keyword : endKeywords) {
            int idx = indexOfTopLevelKeyword(sql, keyword, fromIdx + "FROM".length());
            if (idx >= 0 && idx < end) end = idx;
        }
        return sql.substring(fromIdx + "FROM".length(), end);
    }

    /** 剥离 SELECT 后的 DISTINCT 关键字，返回 [DISTINCT 原文或 null, 剩余文本]。 */
    private static String[] splitDistinct(@NotNull String region) {
        Matcher matcher = Pattern.compile("^\\s*(DISTINCT)\\s+", Pattern.CASE_INSENSITIVE).matcher(region);
        if (!matcher.find()) return new String[]{null, region};
        return new String[]{matcher.group(1), region.substring(matcher.group(0).length())};
    }

    /** 按顶层逗号切分（括号内的逗号不切分）。 */
    private static List<String> splitTopLevelCommas(@NotNull String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (depth > 0) depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /**
     * 查找顶层（括号深度为 0）关键字的起始下标；未找到返回 -1。
     *
     * 括号内的同名词（如子查询中的 FROM）不参与匹配。
     */
    private static int indexOfTopLevelKeyword(@NotNull String sql, @NotNull String keyword) {
        return indexOfTopLevelKeyword(sql, keyword, 0);
    }

    private static int indexOfTopLevelKeyword(@NotNull String sql, @NotNull String keyword, int fromIndex) {
        String upper = sql.toUpperCase();
        String key = keyword.toUpperCase();
        int depth = 0;
        for (int i = 0; i < fromIndex; i++) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (depth > 0) depth--;
            }
        }
        int i = fromIndex;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (depth > 0) depth--;
            }
            if (depth == 0 && upper.startsWith(key, i) && isWordBoundary(sql, i, i + key.length())) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** 判断 [start, end) 区间前后是否为标识符边界（避免 SELECT 命中 SELECTED 之类）。 */
    private static boolean isWordBoundary(@NotNull String sql, int start, int end) {
        if (start > 0 && isIdentChar(sql.charAt(start - 1))) return false;
        if (end < sql.length() && isIdentChar(sql.charAt(end))) return false;
        return true;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 创建兜底 TableInfo（当实体类不可解析时使用）。
     */
    private static TableInfo createFallbackTable() {
        return new TableInfo("", "unknown_table", List.of(), null, null, null, List.of());
    }
}
