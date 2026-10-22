// T-SyncK2JMaster482 波D2 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewServiceEnhancements.kt
// （T-M53-II 2026-10-10：SqlPreviewService 的「ORM 增强/条件注入/结果装配」内聚成员，以同包扩展函数
//   形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal）
// 扩展函数 fun SqlPreviewService.foo(...) → public static R foo(SqlPreviewService receiver, ...)。
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.converter.EasyQueryConverter;
import cn.song.mybatisplus.visualizer.converter.MdsConverter;
import cn.song.mybatisplus.visualizer.converter.QuerydslConverter;
import cn.song.mybatisplus.visualizer.converter.jpa.JpaQueryEnhancer;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.ConditionView;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SetClause;
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlEntityResolver;
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code SqlPreviewServiceEnhancements.kt}（T-M53-II 拆分件）的 Java 承载类——扩展函数
 * {@code fun SqlPreviewService.foo(...)} 落为 {@code public static R foo(SqlPreviewService receiver, ...)}。
 *
 * <p>含 SqlPreviewService 的「ORM 增强 / 条件注入 / 结果装配」内聚成员。</p>
 */
public final class SqlPreviewServiceEnhancementsKt {

    private SqlPreviewServiceEnhancementsKt() {
    }

    /**
     * 其他 ORM 第一批（M44）前置派发：Easy-Query proxy（EQ_PROXY）/ MyBatis Dynamic SQL（MDS）/
     * Querydsl（QD）。三者按 entry + receiver 启发认领（普通类同名方法不命中，负例守卫），
     * 不认领返回 null（交回 JPA CRUD / MP 批量管道）。
     */
    @Nullable
    public static PreviewResult convertOtherOrm(
            @NotNull SqlPreviewService p,
            @NotNull PsiMethodCallExpression call,
            boolean resolveVariables) {
        EasyQueryConverter.EqPreview eq = EasyQueryConverter.convert(call, resolveVariables);
        if (eq != null) {
            return new PreviewResult(
                    eq.getSql(),
                    eq.getSql().replaceAll("\\s+", " ").trim(),
                    eq.getParamSources(),
                    ParameterReplacer.buildParamTable(eq.getParamSources()),
                    WrapperType.EQ_PROXY,
                    eq.getTableName(),
                    eq.getWarning(),
                    eq.getColumns(),
                    Map.of(),
                    null,
                    null,
                    eq.getParamHints(),
                    List.of(),
                    eq.getBatchModel(),
                    null,
                    Map.of(),
                    null,
                    null,
                    null,
                    null);
        }
        MdsConverter.MdsPreview mds = MdsConverter.convert(call, resolveVariables);
        if (mds != null) {
            return new PreviewResult(
                    mds.getSql(),
                    mds.getSql().replaceAll("\\s+", " ").trim(),
                    mds.getParamSources(),
                    ParameterReplacer.buildParamTable(mds.getParamSources()),
                    WrapperType.MDS,
                    mds.getTableName(),
                    mds.getWarning(),
                    mds.getColumns(),
                    Map.of(),
                    null,
                    null,
                    mds.getParamHints(),
                    List.of(),
                    null,
                    null,
                    Map.of(),
                    null,
                    null,
                    null,
                    null);
        }
        QuerydslConverter.QdPreview qd = QuerydslConverter.convert(call, resolveVariables);
        if (qd != null) {
            return new PreviewResult(
                    qd.getSql(),
                    qd.getSql().replaceAll("\\s+", " ").trim(),
                    qd.getParamSources(),
                    ParameterReplacer.buildParamTable(qd.getParamSources()),
                    WrapperType.QD,
                    qd.getTableName(),
                    qd.getWarning(),
                    qd.getColumns(),
                    Map.of(),
                    null,
                    null,
                    qd.getParamHints(),
                    List.of(),
                    null,
                    null,
                    Map.of(),
                    null,
                    null,
                    null,
                    null);
        }
        return null;
    }

    /**
     * JPA 方法级 SQL 增强（T-J10/J15/J22，文本级后处理，previewDerivedQuery / previewJpql /
     * previewHql / previewCriteriaQuery 各路径共用）。
     *
     * <p>Kotlin 默认参数 {@code limitAppliedInModel = false, pageNotes = null, dialectOverride = null}
     * 由以下重载展开（Java 无默认参）。</p>
     */
    @NotNull
    public static String applyJpaMethodEnhancements(
            @NotNull SqlPreviewService p,
            @Nullable PsiMethod method,
            @NotNull String sql,
            @Nullable TableInfo table,
            boolean nativeQuery) {
        return applyJpaMethodEnhancements(p, method, sql, table, nativeQuery, false, null);
    }

    /** Kotlin 默认实参形态 {@code applyJpaMethodEnhancements(…, native = x, dialectOverride = d)} 的便捷重载。 */
    @NotNull
    public static String applyJpaMethodEnhancements(
            @NotNull SqlPreviewService p,
            @Nullable PsiMethod method,
            @NotNull String sql,
            @Nullable TableInfo table,
            boolean nativeQuery,
            @Nullable Dialect dialectOverride) {
        return applyJpaMethodEnhancements(p, method, sql, table, nativeQuery, false, null, dialectOverride);
    }

    @NotNull
    public static String applyJpaMethodEnhancements(
            @NotNull SqlPreviewService p,
            @Nullable PsiMethod method,
            @NotNull String sql,
            @Nullable TableInfo table,
            boolean nativeQuery,
            boolean limitAppliedInModel,
            @Nullable List<String> pageNotes) {
        return applyJpaMethodEnhancements(p, method, sql, table, nativeQuery, limitAppliedInModel, pageNotes, null);
    }

    @NotNull
    public static String applyJpaMethodEnhancements(
            @NotNull SqlPreviewService p,
            @Nullable PsiMethod method,
            @NotNull String sql,
            @Nullable TableInfo table,
            boolean nativeQuery,
            boolean limitAppliedInModel,
            @Nullable List<String> pageNotes,
            @Nullable Dialect dialectOverride) {
        try {
            if (method == null) return sql;
            // T-PS5-03：方言由调用入口求一次后透传（未传则本地求一次，语义不变）——单次预览的
            // `DialectRegistry.effective` 调用数由「增强/分页/结果构建各一次」收敛为入口一次
            Dialect dialect = dialectOverride != null ? dialectOverride : p.currentDialect();
            List<String> notes = new ArrayList<>();
            String enhanced = sql;

            // T-J15：@EntityGraph → LEFT JOIN（JPQL/HQL 非原生路径；原生查询运行时不应用实体图）
            List<String> joinLines = List.of();
            JpaQueryEnhancer.EntityGraphInfo graph = JpaQueryEnhancer.entityGraph(method);
            if (graph != null) {
                if (nativeQuery || table == null) {
                    notes.add("@EntityGraph(attributePaths)：原生 SQL 查询运行时不应用实体图，预览不追加 JOIN");
                } else {
                    JpaQueryEnhancer.GraphJoinResult graphResult =
                            JpaQueryEnhancer.resolveEntityGraphJoins(graph, table);
                    notes.addAll(graphResult.getNotes());
                    String mainAlias = p.detectMainAlias(enhanced, table);
                    List<String> rendered = new ArrayList<>();
                    for (JpaQueryEnhancer.GraphJoin join : graphResult.getJoins()) {
                        rendered.add(JpaQueryEnhancer.renderJoinLine(join, mainAlias, dialect));
                    }
                    joinLines = rendered;
                    if (!graphResult.getJoins().isEmpty()) {
                        notes.add("@EntityGraph(attributePaths=" + String.join(", ", graph.getPaths())
                                + ")：按实体图 LEFT JOIN 抓取（type=" + (graph.getType() != null ? graph.getType() : "LOAD") + "）");
                    }
                }
            }

            // T-J10：Pageable/Limit → LIMIT；Sort → ORDER BY（查询内已有子句时降级注释）。
            // limitAppliedInModel：派生/Criteria 路径 LIMIT 已在语句模型级挂载（传 true 跳过文本追加，
            // 避免同语句双 LIMIT）；
            // pageNotes 非 null：调用方已完成分页/排序分析（含调用点实参求值，T-J9/J10），notes 直接透传、
            // 跳过本方法内的二次分析（callArgs=null 的重分析会产出与实际内联结果相悖的「静态不可解析」注释）
            if (pageNotes != null) {
                notes.addAll(pageNotes);
            } else {
                JpaQueryEnhancer.PageSortEval pageSort = JpaQueryEnhancer.evaluatePageSort(method, null, dialect, table);
                if (pageSort != null) {
                    notes.addAll(pageSort.getNotes());
                    if (pageSort.getLimit() != null) {
                        if (limitAppliedInModel
                                || JpaQueryEnhancer.containsTopLevelClause(enhanced,
                                Pattern.compile("(?i)\\b(LIMIT|OFFSET|FETCH)\\b"))) {
                            if (!limitAppliedInModel) {
                                notes.add("查询内已含分页子句，方法分页参数以查询内为准（预览不重复追加）");
                            }
                        } else {
                            enhanced = enhanced + "\n" + dialect.renderLimit(pageSort.getLimit());
                        }
                    }
                    if (pageSort.getOrderBy() != null && !pageSort.getOrderBy().isEmpty()) {
                        if (JpaQueryEnhancer.containsTopLevelClause(enhanced, Pattern.compile("(?i)\\bORDER\\s+BY\\b"))) {
                            notes.add("查询内已含 ORDER BY，Sort 参数运行时与其合并");
                        } else {
                            StringBuilder ob = new StringBuilder();
                            for (OrderByItem item : pageSort.getOrderBy()) {
                                if (ob.length() > 0) ob.append(", ");
                                ob.append(item.getColumn().getQualifiedName()).append(item.isAsc() ? " ASC" : " DESC");
                            }
                            enhanced = enhanced + "\nORDER BY " + ob;
                        }
                    }
                }
            }

            // JOIN 行插入（置于 LIMIT/ORDER BY 之前的顶层位置）
            enhanced = JpaQueryEnhancer.insertJoinLines(enhanced, joinLines);

            // T-J15：@Lock → FOR UPDATE / 共享锁方言差异注释
            JpaQueryEnhancer.LockClause lock = JpaQueryEnhancer.lockClause(method);
            if (lock != null) {
                notes.add(lock.getNote());
                if (lock.getClause() != null) enhanced = enhanced + " " + lock.getClause();
            }
            // T-J15：@QueryHints 逐条注释
            notes.addAll(JpaQueryEnhancer.queryHintNotes(method));
            // T-J22：返回类型 notes + Page 返回时展示 @Query.countQuery 的 count SQL
            notes.addAll(JpaQueryEnhancer.returnTypeNotes(method));
            notes.addAll(p.countQueryNotes(method, table));

            if (notes.isEmpty()) return sql;
            StringBuilder suffix = new StringBuilder();
            for (String note : notes) {
                if (!note.isBlank()) suffix.append("\n-- ").append(note);
            }
            return enhanced + suffix;
        } catch (Throwable t) {
            return sql;
        }
    }

    /**
     * JPA 预览结果构建统一入口（派生查询 / JPQL / CriteriaQuery 共用）。
     *
     * <p>Kotlin 默认参数 {@code inClauseParams = emptySet(), dialectOverride = null,
     * placeholderParamNames = emptyList(), duplicateParamNames = emptySet(), initialParamValues = emptyMap()}
     * 由以下重载展开。</p>
     */
    @NotNull
    public static PreviewResult buildJpaPreviewResult(
            @NotNull SqlPreviewService p,
            @NotNull SqlStatement statement,
            @NotNull String convertedSql,
            @NotNull String tableName,
            @NotNull List<String> paramNames,
            @Nullable PsiMethod method) {
        return buildJpaPreviewResult(p, statement, convertedSql, tableName, paramNames, method, Set.of());
    }

    @NotNull
    public static PreviewResult buildJpaPreviewResult(
            @NotNull SqlPreviewService p,
            @NotNull SqlStatement statement,
            @NotNull String convertedSql,
            @NotNull String tableName,
            @NotNull List<String> paramNames,
            @Nullable PsiMethod method,
            @NotNull Set<String> inClauseParams) {
        return buildJpaPreviewResult(p, statement, convertedSql, tableName, paramNames, method, inClauseParams, null);
    }

    @NotNull
    public static PreviewResult buildJpaPreviewResult(
            @NotNull SqlPreviewService p,
            @NotNull SqlStatement statement,
            @NotNull String convertedSql,
            @NotNull String tableName,
            @NotNull List<String> paramNames,
            @Nullable PsiMethod method,
            @NotNull Set<String> inClauseParams,
            @Nullable Dialect dialectOverride) {
        return buildJpaPreviewResult(p, statement, convertedSql, tableName, paramNames, method, inClauseParams,
                dialectOverride, null, List.of(), Set.of(), Map.of());
    }

    /**
     * JPA 预览结果构建统一入口（T-JpaParamOrder：带占位符名序 + 初值预填，方言取本地）。
     * Kotlin 默认参数 {@code duplicateParamNames = emptySet()} 等价重载。
     */
    @NotNull
    public static PreviewResult buildJpaPreviewResult(
            @NotNull SqlPreviewService p,
            @NotNull SqlStatement statement,
            @NotNull String convertedSql,
            @NotNull String tableName,
            @NotNull List<String> paramNames,
            @Nullable PsiMethod method,
            @NotNull Set<String> inClauseParams,
            @Nullable Dialect dialectOverride,
            @Nullable cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision dialectDecision) {
        return buildJpaPreviewResult(p, statement, convertedSql, tableName, paramNames, method, inClauseParams,
                dialectOverride, dialectDecision, List.of(), Set.of(), Map.of());
    }

    /** Kotlin 默认参数 {@code dialectOverride/dialectDecision} 为 null 的显式重载。 */
    @NotNull
    public static PreviewResult buildJpaPreviewResult(
            @NotNull SqlPreviewService p,
            @NotNull SqlStatement statement,
            @NotNull String convertedSql,
            @NotNull String tableName,
            @NotNull List<String> paramNames,
            @Nullable PsiMethod method,
            @NotNull Set<String> inClauseParams,
            @NotNull List<String> placeholderParamNames,
            @NotNull Map<String, String> initialParamValues) {
        return buildJpaPreviewResult(p, statement, convertedSql, tableName, paramNames, method, inClauseParams,
                null, null, placeholderParamNames, Set.of(), initialParamValues);
    }

    /**
     * JPA 预览结果构建统一入口（全量形态，T-JpaParamOrder F3/F5/F6）。
     *
     * @param placeholderParamNames JPA 命名参数占位符名序（HQL {@code :name} 首现序）；空 = 按序回填
     * @param duplicateParamNames   同名命名参数多次 setParameter 的参数名集合；空集 = 无重复
     * @param initialParamValues    参数行初值预填（静态可求值的字面量/枚举常量）
     */
    @NotNull
    public static PreviewResult buildJpaPreviewResult(
            @NotNull SqlPreviewService p,
            @NotNull SqlStatement statement,
            @NotNull String convertedSql,
            @NotNull String tableName,
            @NotNull List<String> paramNames,
            @Nullable PsiMethod method,
            @NotNull Set<String> inClauseParams,
            @Nullable Dialect dialectOverride,
            @NotNull List<String> placeholderParamNames,
            @NotNull Set<String> duplicateParamNames,
            @NotNull Map<String, String> initialParamValues) {
        return buildJpaPreviewResult(p, statement, convertedSql, tableName, paramNames, method, inClauseParams,
                dialectOverride, null, placeholderParamNames, duplicateParamNames, initialParamValues);
    }

    /** Kotlin 默认参数等价全量形态：{@code dialectDecision} 显式透传（O-145 方言决策入结果）。 */
    @NotNull
    public static PreviewResult buildJpaPreviewResult(
            @NotNull SqlPreviewService p,
            @NotNull SqlStatement statement,
            @NotNull String convertedSql,
            @NotNull String tableName,
            @NotNull List<String> paramNames,
            @Nullable PsiMethod method,
            @NotNull Set<String> inClauseParams,
            @Nullable Dialect dialectOverride,
            @Nullable cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision dialectDecision,
            @NotNull List<String> placeholderParamNames,
            @NotNull Set<String> duplicateParamNames,
            @NotNull Map<String, String> initialParamValues) {
        // T-PS5-03：方言由调用入口透传（未传则本地求一次）——见 applyJpaMethodEnhancements 同款说明
        Dialect dialect = dialectOverride != null ? dialectOverride : p.currentDialect();
        List<ConditionView> views = p.collectConditionViews(statement);
        // T-JpaParamOrder F3：占位符名序可用性判定——convertedSql 可能是增强后形态（LIMIT 等），
        // 但增强不增删 `?`；全部 `?` 均来自命名参数时启用按名回填，否则置空维持按序
        List<String> usableNamed = p.usableNamedPlaceholderSources(convertedSql, placeholderParamNames);
        List<String> usableNamedSources = usableNamed != null ? usableNamed : List.of();
        // T-JpaParamOrder F3：回填提示与「渲染实际用的 sources」平行——按名回填启用时随名序，
        // 否则随面板行序（paramNames），保证 backfillParamsBySource 的第 i 位提示对第 i 位来源
        List<String> backfillSources = !usableNamedSources.isEmpty() ? usableNamedSources : paramNames;
        // 参数回填提示：根据方法签名参数类型判定是否需要引号
        // - IN 子句参数：数值类型不加引号，字符串加引号
        // - 非 IN 参数：数值类型不加引号，字符串加引号（quote=true）
        // - 无法判定类型时默认加引号（保守策略）
        List<ParamHint> paramHints;
        if (!backfillSources.isEmpty()) {
            paramHints = new ArrayList<>();
            for (String name : backfillSources) {
                boolean inList = inClauseParams.contains(name);
                boolean isNumeric = p.isNumericParam(method, name);
                paramHints.add(new ParamHint(Boolean.valueOf(!isNumeric), null, inList));
            }
        } else {
            paramHints = List.of();
        }
        TableInfo stmtTable = (statement.getTable() instanceof TableInfo) ? (TableInfo) statement.getTable() : null;
        List<String> columns = (stmtTable != null) ? stmtTable.getAllColumnNames() : List.of();
        // F-1 修复：JPA 联表派生查询同款缺口——图 Tab 判据与 previewModel 解耦
        SelectStatement selForDiagram = (statement instanceof SelectStatement) ? (SelectStatement) statement : null;
        SelectStatement diagramStatement = (selForDiagram != null && !selForDiagram.getJoins().isEmpty()) ? selForDiagram : null;
        return new PreviewResult(
                convertedSql,
                convertedSql.replaceAll("\\s+", " ").trim(),
                paramNames,
                ParameterReplacer.buildParamTable(paramNames),
                null,
                tableName,
                p.buildJpaWarning(statement),
                columns,
                Map.of(),
                null,
                views.isEmpty() ? null : new SqlPreviewModel(statement, dialect, p.formatSql(), views),
                paramHints,
                List.of(),
                null,
                null,
                initialParamValues,
                null,
                null,
                null,
                null,
                Map.of(),
                null,
                false,
                null,
                null,
                usableNamedSources,
                duplicateParamNames,
                diagramStatement,
                // historyFingerprintSeed 未用（JPA 链无注入前文本种子）；下方透传 renderedDialect/dialectDecision
                null,
                null,
                dialect,
                dialectDecision,
                null,
                null);
    }

    /**
     * 由语句模型构建预览结果（应用方言与格式化配置）。
     *
     * <p>Kotlin 默认参数 {@code countSql = null} 的等价重载。</p>
     */
    @NotNull
    public static PreviewResult buildPreviewResult(
            @NotNull SqlPreviewService p,
            @Nullable WrapperType wrapperType,
            @NotNull TableInfo table,
            @NotNull SqlStatement statement) {
        return buildPreviewResult(p, wrapperType, table, statement, null);
    }

    @NotNull
    public static PreviewResult buildPreviewResult(
            @NotNull SqlPreviewService p,
            @Nullable WrapperType wrapperType,
            @NotNull TableInfo table,
            @NotNull SqlStatement statement,
            @Nullable String countSql) {
        return buildPreviewResult(p, wrapperType, table, statement, countSql, null);
    }

    /**
     * 由语句模型构建预览结果（应用方言与格式化配置）。
     *
     * @param historyFingerprintSeed 注入前语句文本（参数历史键指纹种子）；null = 面板回落用 result.sql 算键
     */
    @NotNull
    public static PreviewResult buildPreviewResult(
            @NotNull SqlPreviewService p,
            @Nullable WrapperType wrapperType,
            @NotNull TableInfo table,
            @NotNull SqlStatement statement,
            @Nullable String countSql,
            @Nullable String historyFingerprintSeed) {
        return buildPreviewResult(p, wrapperType, table, statement, countSql, historyFingerprintSeed,
                p.currentDialect(), null);
    }

    /** Kotlin 默认参数等价全量形态：{@code dialect}/{@code dialectDecision} 显式透传（O-145 方言决策入结果）。 */
    @NotNull
    public static PreviewResult buildPreviewResult(
            @NotNull SqlPreviewService p,
            @Nullable WrapperType wrapperType,
            @NotNull TableInfo table,
            @NotNull SqlStatement statement,
            @Nullable String countSql,
            @Nullable String historyFingerprintSeed,
            @NotNull Dialect dialect,
            @Nullable cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision dialectDecision) {
        boolean compact = !p.formatSql();
        cn.song.mybatisplus.visualizer.sql.GeneratedSql generated =
                SqlGenerator.generateWithParams(statement, compact, dialect, null);
        // 动态条件视图（扁平化，含嵌套组内）；无动态条件时 previewModel 为 null → 面板走旧文本渲染路径
        List<ConditionView> views = p.collectConditionViews(statement);
        // M16：列注释与表注释（补全 tailText / 关键词检索；来自 EntityResolver 的四级优先级提取）
        java.util.Map<String, String> columnComments = new java.util.HashMap<>();
        for (ColumnInfo col : table.getColumns()) {
            if (col.getComment() != null && !col.getComment().isBlank()) {
                columnComments.put(col.getColumnName(), col.getComment());
            }
        }
        List<cn.song.mybatisplus.visualizer.model.FillClause> fillClauses = (statement instanceof SelectStatement)
                ? ((SelectStatement) statement).getFillClauses() : null;
        // F-1 修复：JOIN 图判据独立于 previewModel（无条件联表链 previewModel 为 null，但仍含 JOIN 可画图）
        SelectStatement selForDiagram = (statement instanceof SelectStatement) ? (SelectStatement) statement : null;
        SelectStatement diagramStatement = (selForDiagram != null && !selForDiagram.getJoins().isEmpty()) ? selForDiagram : null;
        return new PreviewResult(
                generated.getSql(),
                SqlGenerator.generate(statement, true, dialect, null),
                generated.getUnresolvedParams(),
                ParameterReplacer.buildParamTable(generated.getUnresolvedParams()),
                wrapperType,
                table.getTableName(),
                p.buildWarning(statement),
                table.getAllColumnNames(),
                columnComments,
                table.getComment(),
                views.isEmpty() ? null : new SqlPreviewModel(statement, dialect, p.formatSql(), views),
                generated.getParamHints(),
                List.of(),
                null,
                null,
                Map.of(),
                // F-2：SQL 性能分析（仅 SELECT）：委托 SqlPerfAnalyzer；UPDATE/DELETE 走既有 buildWarning 路径
                p.analyzePerf(generated.getSql(), statement, dialect),
                fillClauses,
                null,
                null,
                Map.of(),
                null,
                false,
                countSql,
                null,
                List.of(),
                Set.of(),
                diagramStatement,
                // 缺陷②修复：参数历史键指纹用「注入前」文本（开关切换不再改键，回填不丢）
                historyFingerprintSeed,
                null,
                dialect,
                dialectDecision,
                null,
                null);
    }

    /**
     * T-M15-07 + T-M20-05 精确化：逻辑删除条件注入。
     */
    @NotNull
    public static SqlStatement injectLogicDeleteCondition(
            @NotNull SqlPreviewService p,
            @NotNull TableInfo table,
            @NotNull SqlStatement statement,
            @NotNull SqlPreviewService.LogicDeleteState state) {
        // T-M31.18：逻辑删除值可配置（默认 0，用户可调整——对齐 MP logic-delete-value）
        // T-SettingsTruth P4：项目级 logicDeleteValue 覆盖（空回退全局，再兜底 "0"）
        String logicDeleteValue = p.effectiveLogicDeleteValue();
        if (logicDeleteValue.isBlank()) logicDeleteValue = "0";
        if (statement instanceof SelectStatement) {
            SelectStatement sel = (SelectStatement) statement;
            List<Condition> conditions = new ArrayList<>(sel.getConditions());
            if (state.mainActive && table.getLogicDeleteColumn() != null) {
                conditions.add(new Condition.Simple(
                        new ColumnRef(sel.getTableAlias(), table.getLogicDeleteColumn(), null, null, null, null, null),
                        "=", new SqlValue.Literal(logicDeleteValue, logicDeleteValue), null));
            }
            // 副表注入：ON 追加 / WHERE 末尾（子查询形态 tableName 以 '(' 开头无 logicDeleteColumn，天然跳过）
            List<Condition> subWhere = new ArrayList<>();
            List<JoinClause> joins = new ArrayList<>();
            for (JoinClause join : sel.getJoins()) {
                String column = join.getLogicDeleteColumn();
                if (column == null) { joins.add(join); continue; }
                if (!state.subActive) { joins.add(join); continue; }
                Condition cond = new Condition.Simple(
                        new ColumnRef(join.getAlias(), column, null, null, null, null, null),
                        "=", new SqlValue.Literal(logicDeleteValue, logicDeleteValue), null);
                if (state.subToWhere) {
                    subWhere.add(cond);
                    joins.add(join);
                } else {
                    List<Condition> onConditions = new ArrayList<>(join.getOnConditions());
                    onConditions.add(cond);
                    joins.add(new JoinClause(join.getType(), join.getTableName(), join.getAlias(), onConditions,
                            join.getRawSql(), join.getCustomKeyword(), join.getLogicDeleteColumn(), join.getTableInfo()));
                }
            }
            if (!subWhere.isEmpty()) conditions.addAll(subWhere);
            return new SelectStatement(sel.getTable(), sel.getSelectColumns(), sel.getTableAlias(), joins, sel.getDistinct(),
                    conditions, sel.getGroupBy(), sel.getHaving(), sel.getOrderBy(), sel.getLimit(), sel.getRawFirst(),
                    sel.getRawAround(), sel.getComment(), sel.getUnions(), sel.getFromSubQuery(), sel.getFillClauses());
        } else if (statement instanceof DeleteStatement) {
            DeleteStatement del = (DeleteStatement) statement;
            String column = table.getLogicDeleteColumn();
            if (column == null || !state.mainActive) return del;
            List<Condition> conditions = new ArrayList<>(del.getConditions());
            conditions.add(new Condition.Simple(
                    new ColumnRef(del.getTableAlias(), column, null, null, null, null, null),
                    "=", new SqlValue.Literal(logicDeleteValue, logicDeleteValue), null));
            return new DeleteStatement(del.getTable(), conditions, del.getDeleteTargets(), del.getTableAlias(), del.getJoins());
        }
        return statement;
    }

    /**
     * T-TenantLine：多租户条件注入（TenantLineInnerInterceptor 模拟）。
     */
    @NotNull
    public static SqlStatement injectTenantLineCondition(
            @NotNull SqlPreviewService p,
            @NotNull TableInfo table,
            @NotNull SqlStatement statement,
            boolean hasInterceptorIgnore) {
        PluginSettings.State settings = PluginSettings.getInstance().getState();
        if (!settings.getInjectTenantLine()) {
            SqlPreviewService.LOG.info("injectTenantLine: SKIP switch=off, stmt=" + statement.getClass().getSimpleName()
                    + ", table=" + table.getTableName());
            return statement;
        }
        if (hasInterceptorIgnore) {
            SqlPreviewService.LOG.info("injectTenantLine: SKIP @InterceptorIgnore(tenantLine=true), stmt="
                    + statement.getClass().getSimpleName());
            return statement;
        }
        String tenantColumn = settings.getTenantColumnName();
        if (tenantColumn == null || tenantColumn.isBlank()) tenantColumn = SqlPreviewService.DEFAULT_TENANT_COLUMN;
        if (statement instanceof SelectStatement) {
            SelectStatement sel = (SelectStatement) statement;
            if (p.conditionReferencesColumn(sel.getConditions(), tenantColumn)) {
                SqlPreviewService.LOG.info("injectTenantLine: SKIP column already in WHERE, col=" + tenantColumn + ", stmt=SELECT");
                return statement;
            }
            SqlPreviewService.LOG.info("injectTenantLine: INJECT col=" + tenantColumn + ", stmt=SELECT, table=" + table.getTableName());
            List<Condition> conditions = new ArrayList<>(sel.getConditions());
            conditions.add(new Condition.Simple(
                    new ColumnRef(sel.getTableAlias(), tenantColumn, null, null, null, null, null),
                    "=", new SqlValue.Placeholder(tenantColumn + " (= ?)"), null));
            return new SelectStatement(sel.getTable(), sel.getSelectColumns(), sel.getTableAlias(), sel.getJoins(), sel.getDistinct(),
                    conditions, sel.getGroupBy(), sel.getHaving(), sel.getOrderBy(), sel.getLimit(), sel.getRawFirst(),
                    sel.getRawAround(), sel.getComment(), sel.getUnions(), sel.getFromSubQuery(), sel.getFillClauses());
        } else if (statement instanceof UpdateStatement) {
            UpdateStatement upd = (UpdateStatement) statement;
            for (SetClause set : upd.getSets()) {
                if (set.getColumn() != null && tenantColumn.equals(set.getColumn().getColumnName())) {
                    SqlPreviewService.LOG.info("injectTenantLine: SKIP column in SET, col=" + tenantColumn + ", stmt=UPDATE");
                    return statement;
                }
            }
            if (p.conditionReferencesColumn(upd.getConditions(), tenantColumn)) {
                SqlPreviewService.LOG.info("injectTenantLine: SKIP column already in WHERE, col=" + tenantColumn + ", stmt=UPDATE");
                return statement;
            }
            SqlPreviewService.LOG.info("injectTenantLine: INJECT col=" + tenantColumn + ", stmt=UPDATE, table=" + table.getTableName());
            List<Condition> conditions = new ArrayList<>(upd.getConditions());
            conditions.add(new Condition.Simple(
                    new ColumnRef(upd.getTableAlias(), tenantColumn, null, null, null, null, null),
                    "=", new SqlValue.Placeholder(tenantColumn + " (= ?)"), null));
            return new UpdateStatement(upd.getTable(), upd.getSets(), conditions, upd.getRawTail(), upd.getTableAlias(), upd.getJoins());
        } else if (statement instanceof DeleteStatement) {
            DeleteStatement del = (DeleteStatement) statement;
            if (p.conditionReferencesColumn(del.getConditions(), tenantColumn)) {
                SqlPreviewService.LOG.info("injectTenantLine: SKIP column already in WHERE, col=" + tenantColumn + ", stmt=DELETE");
                return statement;
            }
            SqlPreviewService.LOG.info("injectTenantLine: INJECT col=" + tenantColumn + ", stmt=DELETE, table=" + table.getTableName());
            List<Condition> conditions = new ArrayList<>(del.getConditions());
            conditions.add(new Condition.Simple(
                    new ColumnRef(del.getTableAlias(), tenantColumn, null, null, null, null, null),
                    "=", new SqlValue.Placeholder(tenantColumn + " (= ?)"), null));
            return new DeleteStatement(del.getTable(), conditions, del.getDeleteTargets(), del.getTableAlias(), del.getJoins());
        }
        return statement;
    }

    /**
     * 解析 HQL 对应的实体表信息。
     *
     * <p>优先使用 entityClass（PsiClass 直接解析）；否则按 entityClassName 查找 {@code @Entity} 类
     * （含 {@code @Entity(name=...)} 实体别名反查）；最后从 HQL 的 FROM 子句提取类名查找 / hbm 映射兜底。</p>
     */
    @Nullable
    public static TableInfo resolveHqlTable(
            @NotNull SqlPreviewService p,
            @Nullable PsiClass entityClass,
            @Nullable String entityClassName,
            @NotNull String hql) {
        return resolveHqlTable(p, entityClass, entityClassName, hql, null);
    }

    @Nullable
    public static TableInfo resolveHqlTable(@NotNull SqlPreviewService p, @Nullable PsiClass entityClass,
            @Nullable String entityClassName, @NotNull String hql, @Nullable PsiElement context) {
        // 查询根实体才是权威；TypedQuery 结果类型、NamedQuery 宿主和查询名前缀都可能不是根实体。
        String rootName = p.extractEntityNameFromHql(hql);
        String className = rootName != null ? rootName : entityClassName;
        if (className == null) return null;
        var resolution = cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep.resolve(p.project, className, context);
        // 歧义是终态，不借显式类、XML 或别名 fallback 偷偷挑选一个。
        if (resolution.getAmbiguous()) return null;
        PsiClass target = resolution.psiClass();
        if (target == null && entityClass != null && classMatchesQueryEntity(entityClass, className)
                && isVisibleInQueryScope(entityClass, context)) target = entityClass;
        // 纯 XML 映射工程可以没有 Java 类，保留 HBM 路径。
        if (target == null) {
            var hbmOnly = HbmXmlEntityResolver.resolve(className, p.project, context);
            return hbmOnly == null ? null : p.convertHbmMappingToTableInfo(hbmOnly);
        }
        TableInfo resolved = new JpaEntityResolver().resolve(target);
        if (!resolved.getColumns().isEmpty()) return resolved;
        var hbm = HbmXmlEntityResolver.resolve(className, p.project, context);
        return hbm == null ? resolved : p.convertHbmMappingToTableInfo(hbm);
    }

    private static boolean classMatchesQueryEntity(PsiClass clazz, String rootName) {
        String name = cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader.entityNameOf(clazz);
        if (name == null || name.isBlank()) name = clazz.getName();
        return rootName.equals(clazz.getQualifiedName()) || rootName.equals(name);
    }

    private static boolean isVisibleInQueryScope(PsiClass clazz, PsiElement context) {
        Boolean visible = cn.song.mybatisplus.visualizer.converter.jpa.JpaAliasTableResolver.recoverJpaPsi(() -> {
            var scope = cn.song.mybatisplus.visualizer.psi.EntityClassResolver.searchScope(clazz.getProject(), context);
            return clazz.getContainingFile() != null && clazz.getContainingFile().getVirtualFile() != null
                    && scope.contains(clazz.getContainingFile().getVirtualFile());
        });
        return Boolean.TRUE.equals(visible);
    }
}
