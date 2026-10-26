package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.converter.ConverterRegistry
import cn.song.mybatisplus.visualizer.history.SqlHistoryEntry
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.log.sqlLogManagerIfCreated
import cn.song.mybatisplus.visualizer.sql.interceptor.DataPermissionPlaceholder
import cn.song.mybatisplus.visualizer.sql.interceptor.DynamicTableNameProcessor
import cn.song.mybatisplus.visualizer.sql.interceptor.PaginationSimulator
import cn.song.mybatisplus.visualizer.sql.interceptor.TenantLineEnhancer
import cn.song.mybatisplus.visualizer.converter.EasyQueryConverter
import cn.song.mybatisplus.visualizer.converter.MdsConverter
import cn.song.mybatisplus.visualizer.converter.QuerydslConverter
import cn.song.mybatisplus.visualizer.converter.QueryWrapperParser
import cn.song.mybatisplus.visualizer.converter.jpa.CriteriaQueryConverter
import cn.song.mybatisplus.visualizer.converter.jpa.DerivedQueryConverter
import cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter
import cn.song.mybatisplus.visualizer.converter.jpa.JpaCrudOperationConverter
import cn.song.mybatisplus.visualizer.converter.jpa.JpaQueryEnhancer
import cn.song.mybatisplus.visualizer.converter.jpa.JpqlConverter
import cn.song.mybatisplus.visualizer.converter.jpa.PartTreeParser
import cn.song.mybatisplus.visualizer.converter.jpa.QueryByExampleConverter
import cn.song.mybatisplus.visualizer.converter.jpa.SpecificationQueryConverter
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator
import cn.song.mybatisplus.visualizer.psi.jpa.DaoQueryKind
import cn.song.mybatisplus.visualizer.psi.jpa.HqlEntityNameExtractor
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryInfo
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.AssociationInfo
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.psi.BatchOperationConverter
import cn.song.mybatisplus.visualizer.psi.WrapperCache
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryLocator
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.TableConfigSignature
import cn.song.mybatisplus.visualizer.xml.directTextOf
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

// 扩展函数看不到接收者类的 companion 成员，显式 import 引入：
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService.Companion.DEFAULT_TENANT_COLUMN
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService.Companion.LOG
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService.LogicDeleteState

// T-M53-II（2026-10-10）：以下为 SqlPreviewService 的「ORM 增强/条件注入/结果装配」内聚成员，
// 以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal。

// T-PerfSweep26 F104：本文件原六处函数体内现场 Regex(...) 构造提为文件级属性单例
// （同一 Regex 实例跨调用复用，模式逐字保留）。命名对齐 SqlPreviewService 伴生对象同款常量
// （SQL_WHITESPACE_RUN / LIMIT_OFFSET_FETCH_CLAUSE / ORDER_BY_CLAUSE，模式相同）；
// 不直接引用彼处常量：其为 private，且本文件是扩展函数文件无 companion——以文件级 private val
// 为同构形态。
private val SQL_WHITESPACE_RUN = Regex("\\s+")

private val LIMIT_OFFSET_FETCH_CLAUSE = Regex("(?i)\\b(LIMIT|OFFSET|FETCH)\\b")

private val ORDER_BY_CLAUSE = Regex("(?i)\\bORDER\\s+BY\\b")

internal fun SqlPreviewService.convertOtherOrm(call: PsiMethodCallExpression, resolveVariables: Boolean): PreviewResult? {
    val eq = EasyQueryConverter.convert(call, resolveVariables)
    if (eq != null) {
        return PreviewResult(
            sql = eq.sql,
            compactSql = eq.sql.replace(SQL_WHITESPACE_RUN, " ").trim(),
            unresolvedParams = eq.paramSources,
            paramTable = ParameterReplacer.buildParamTable(eq.paramSources),
            paramHints = eq.paramHints,
            wrapperType = WrapperType.EQ_PROXY,
            tableName = eq.tableName,
            warning = eq.warning,
            columns = eq.columns,
            batchModel = eq.batchModel,
        )
    }
    val mds = MdsConverter.convert(call, resolveVariables)
    if (mds != null) {
        return PreviewResult(
            sql = mds.sql,
            compactSql = mds.sql.replace(SQL_WHITESPACE_RUN, " ").trim(),
            unresolvedParams = mds.paramSources,
            paramTable = ParameterReplacer.buildParamTable(mds.paramSources),
            paramHints = mds.paramHints,
            wrapperType = WrapperType.MDS,
            tableName = mds.tableName,
            warning = mds.warning,
            columns = mds.columns,
        )
    }
    val qd = QuerydslConverter.convert(call, resolveVariables)
    if (qd != null) {
        return PreviewResult(
            sql = qd.sql,
            compactSql = qd.sql.replace(SQL_WHITESPACE_RUN, " ").trim(),
            unresolvedParams = qd.paramSources,
            paramTable = ParameterReplacer.buildParamTable(qd.paramSources),
            paramHints = qd.paramHints,
            wrapperType = WrapperType.QD,
            tableName = qd.tableName,
            warning = qd.warning,
            columns = qd.columns,
        )
    }
    return null
}

/**
 * JPA 推导查询预览（F-14 阶段四）：解析 Repository 方法名 → PartTree → SqlStatement → PreviewResult。
 *
 * 供 [cn.song.mybatisplus.visualizer.ui.gutter.JpaGutterLineMarkerProvider] 点击调用与
 * T-J9 调用点反查（ConvertToSqlAction）调用。
 *
 * @param method   Repository 声明方法
 * @param callArgs T-J9 调用点实参（`userRepository.findByName(x, PageRequest.of(0, 20))` 的实参列表）；
 *                 非 null 时对 PageRequest.of / Sort.by 做静态求值（有字面量就内联渲染，无则 ? 占位）
 */

internal fun SqlPreviewService.applyJpaMethodEnhancements(
    method: PsiMethod?,
    sql: String,
    table: TableInfo?,
    native: Boolean,
    limitAppliedInModel: Boolean = false,
    pageNotes: List<String>? = null,
    dialectOverride: Dialect? = null,
): String = runCatching {
    if (method == null) return@runCatching sql
    // T-PS5-03：方言由调用入口求一次后透传（未传则本地求一次，语义不变）——单次预览的
    // `DialectRegistry.effective` 调用数由「增强/分页/结果构建各一次」收敛为入口一次
    val dialect = dialectOverride ?: currentDialect()
    val notes = mutableListOf<String>()
    var enhanced = sql

    // T-J15：@EntityGraph → LEFT JOIN（JPQL/HQL 非原生路径；原生查询运行时不应用实体图）
    var joinLines: List<String> = emptyList()
    JpaQueryEnhancer.entityGraph(method)?.let { graph ->
        if (native || table == null) {
            notes += "@EntityGraph(attributePaths)：原生 SQL 查询运行时不应用实体图，预览不追加 JOIN"
        } else {
            val (joins, graphNotes) = JpaQueryEnhancer.resolveEntityGraphJoins(graph, table)
            notes += graphNotes
            val mainAlias = detectMainAlias(enhanced, table)
            joinLines = joins.map { JpaQueryEnhancer.renderJoinLine(it, mainAlias, dialect) }
            if (joins.isNotEmpty()) {
                notes += "@EntityGraph(attributePaths=${graph.paths.joinToString(", ")})：按实体图 LEFT JOIN 抓取（type=${graph.type ?: "LOAD"}）"
            }
        }
    }

    // T-J10：Pageable/Limit → LIMIT；Sort → ORDER BY（查询内已有子句时降级注释）。
    // limitAppliedInModel：派生/Criteria 路径 LIMIT 已在语句模型级挂载（传 true 跳过文本追加，
    // 避免同语句双 LIMIT）；
    // pageNotes 非 null：调用方已完成分页/排序分析（含调用点实参求值，T-J9/J10），notes 直接透传、
    // 跳过本方法内的二次分析（callArgs=null 的重分析会产出与实际内联结果相悖的「静态不可解析」注释）
    if (pageNotes != null) {
        notes += pageNotes
    } else {
        JpaQueryEnhancer.evaluatePageSort(method, null, dialect, table)?.let { pageSort ->
            notes += pageSort.notes
            pageSort.limit?.let { limit ->
                if (limitAppliedInModel ||
                    JpaQueryEnhancer.containsTopLevelClause(enhanced, LIMIT_OFFSET_FETCH_CLAUSE)
                ) {
                    if (!limitAppliedInModel) {
                        notes += "查询内已含分页子句，方法分页参数以查询内为准（预览不重复追加）"
                    }
                } else {
                    enhanced += "\n" + dialect.renderLimit(limit)
                }
            }
            if (pageSort.orderBy.isNotEmpty()) {
                if (JpaQueryEnhancer.containsTopLevelClause(enhanced, ORDER_BY_CLAUSE)) {
                    notes += "查询内已含 ORDER BY，Sort 参数运行时与其合并"
                } else {
                    enhanced += "\nORDER BY " + pageSort.orderBy.joinToString(", ") {
                        it.column.qualifiedName + if (it.asc) " ASC" else " DESC"
                    }
                }
            }
        }
    }

    // JOIN 行插入（置于 LIMIT/ORDER BY 之前的顶层位置）
    enhanced = JpaQueryEnhancer.insertJoinLines(enhanced, joinLines)

    // T-J15：@Lock → FOR UPDATE / 共享锁方言差异注释
    JpaQueryEnhancer.lockClause(method)?.let { (clause, note) ->
        notes += note
        if (clause != null) enhanced += " $clause"
    }
    // T-J15：@QueryHints 逐条注释
    notes += JpaQueryEnhancer.queryHintNotes(method)
    // T-J22：返回类型 notes + Page 返回时展示 @Query.countQuery 的 count SQL
    notes += JpaQueryEnhancer.returnTypeNotes(method)
    notes += countQueryNotes(method, table)

    if (notes.isEmpty()) return@runCatching sql
    enhanced + notes.filter { it.isNotBlank() }.joinToString("") { "\n-- $it" }
}.getOrDefault(sql)

/** T-J22：@Query.countQuery → count SQL 注释行（Page 返回语义；转换失败降级原文注释）。 */

internal fun SqlPreviewService.buildJpaPreviewResult(
    statement: SqlStatement,
    convertedSql: String,
    tableName: String,
    paramNames: List<String>,
    method: PsiMethod?,
    inClauseParams: Set<String> = emptySet(),
    dialectOverride: Dialect? = null,
    dialectDecision: cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision? = null,
    placeholderParamNames: List<String> = emptyList(),
    duplicateParamNames: Set<String> = emptySet(),
    initialParamValues: Map<String, String> = emptyMap(),
): PreviewResult {
    // T-PS5-03：方言由调用入口透传（未传则本地求一次）——见 applyJpaMethodEnhancements 同款说明
    val dialect = dialectOverride ?: currentDialect()
    val views = collectConditionViews(statement)
    // T-JpaParamOrder F3：占位符名序可用性判定——convertedSql 可能是增强后形态（LIMIT 等），
    // 但增强不增删 `?`；全部 `?` 均来自命名参数时启用按名回填，否则置空维持按序
    val usableNamedSources = usableNamedPlaceholderSources(convertedSql, placeholderParamNames).orEmpty()
    // T-JpaParamOrder F3：回填提示与「渲染实际用的 sources」平行——按名回填启用时随名序，
    // 否则随面板行序（paramNames），保证 backfillParamsBySource 的第 i 位提示对第 i 位来源
    val backfillSources = usableNamedSources.ifEmpty { paramNames }
    // 参数回填提示：根据方法签名参数类型判定是否需要引号
    // - IN 子句参数：数值类型不加引号，字符串加引号
    // - 非 IN 参数：数值类型不加引号，字符串加引号（quote=true）
    // - 无法判定类型时默认加引号（保守策略）
    val paramHints = if (backfillSources.isNotEmpty()) {
        backfillSources.map { name ->
            val inList = name in inClauseParams
            val isNumeric = isNumericParam(method, name)
            // 数值类型不加引号，其他类型（String/Date 等）加引号
            ParamHint(quote = !isNumeric, inList = inList)
        }
    } else emptyList()
    return PreviewResult(
        sql = convertedSql,
        compactSql = convertedSql.replace(SQL_WHITESPACE_RUN, " ").trim(),
        unresolvedParams = paramNames,
        paramTable = ParameterReplacer.buildParamTable(paramNames),
        paramHints = paramHints,
        wrapperType = null,
        tableName = tableName,
        warning = buildJpaWarning(statement),
        columns = (statement.table as? TableInfo)?.allColumnNames ?: emptyList(),
        previewModel = if (views.isEmpty()) null else SqlPreviewModel(statement, dialect, formatSql(), views),
        renderedDialect = dialect,
        dialectDecision = dialectDecision,
        // F-1 修复：JPA 联表派生查询同款缺口——图 Tab 判据与 previewModel 解耦
        diagramStatement = (statement as? SelectStatement)?.takeIf { it.joins.isNotEmpty() },
        placeholderParamNames = usableNamedSources,
        duplicateParamNames = duplicateParamNames,
        initialParamValues = initialParamValues,
        // T-PresetMethodScope：HQL/JPA 链路不落历史条目（recordHistory 未调用），作用域必须在此写入，
        // 否则同一段 HQL 写在两个方法里仍共用「表名 + SQL 指纹」键。
        originScopeKey = cn.song.mybatisplus.visualizer.psi.PreviewOriginScope.of(method, project),
    )
}

/**
 * 判断方法参数是否为数值类型（Integer/Long/BigDecimal 等）。
 *
 * 用于 IN 子句参数回填时决定是否需要引号：数值类型不加引号，字符串加引号。
 * 支持 Collection<NumericType>、NumericType[] 等集合/数组形态。
 */

internal fun SqlPreviewService.buildPreviewResult(
    wrapperType: cn.song.mybatisplus.visualizer.model.WrapperType?,
    table: cn.song.mybatisplus.visualizer.model.TableInfo,
    statement: SqlStatement,
    countSql: String? = null,
    /** 注入前语句文本（参数历史键指纹种子）；null = 面板回落用 result.sql 算键。 */
    historyFingerprintSeed: String? = null,
    dialect: cn.song.mybatisplus.visualizer.sql.Dialect = currentDialect(),
    dialectDecision: cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision? = null,
): PreviewResult {
    val compact = !formatSql()
    val generated = SqlGenerator.generateWithParams(statement, compact = compact, dialect = dialect)
    // 动态条件视图（扁平化，含嵌套组内）；无动态条件时 previewModel 为 null → 面板走旧文本渲染路径
    val views = collectConditionViews(statement)
    return PreviewResult(
        sql = generated.sql,
        compactSql = SqlGenerator.generate(statement, compact = true, dialect = dialect),
        unresolvedParams = generated.unresolvedParams,
        paramTable = ParameterReplacer.buildParamTable(generated.unresolvedParams),
        wrapperType = wrapperType,
        tableName = table.tableName,
        warning = buildWarning(statement),
        columns = table.allColumnNames,
        // M16：列注释与表注释（补全 tailText / 关键词检索；来自 EntityResolver 的四级优先级提取）
        columnComments = table.columns.mapNotNull { col ->
            col.comment?.takeIf { it.isNotBlank() }?.let { col.columnName to it }
        }.toMap(),
        tableComment = table.comment,
        previewModel = if (views.isEmpty()) null else SqlPreviewModel(statement, dialect, formatSql(), views),
        renderedDialect = dialect,
        dialectDecision = dialectDecision,
        // F-1 修复：JOIN 图判据独立于 previewModel（无条件联表链 previewModel 为 null，但仍含 JOIN 可画图）
        diagramStatement = (statement as? SelectStatement)?.takeIf { it.joins.isNotEmpty() },
        paramHints = generated.paramHints,
        // F-2：SQL 性能分析（仅 SELECT）：委托 [SqlPerfAnalyzer]；UPDATE/DELETE 走既有 buildWarning 路径
        perfWarnings = analyzePerf(generated.sql, statement, dialect),
        // T-MpjFill：fill 附加查询子句（MPJ 1.5.1+）——透传至面板追加渲染
        fillClauses = (statement as? SelectStatement)?.fillClauses,
        // R12 T-PaginationSim：分页 COUNT 包裹（面板在主 SQL 后并列渲染第二条语句）
        countSql = countSql,
        // 缺陷②修复：参数历史键指纹用「注入前」文本（开关切换不再改键，回填不丢）
        historyFingerprintSeed = historyFingerprintSeed,
    )
}

/**
 * R12 T-PaginationSim：分页 COUNT 包裹预览文本（D2=A 基础包裹形态）。
 *
 * MP PaginationInnerInterceptor 运行时先对**原语句（不含 LIMIT）**执行 COUNT、再给分页语句
 * 追加 LIMIT（willDoQuery → autoCountSql → handlerLimit 顺序，3.5.17 反编译实证）——故包裹体
 * 取 `copy(limit = null)` 的语句重生成（模型级去 LIMIT，避免文本截断）。
 *
 * 产出条件（全部满足）：
 *  - 设置开关 [PluginSettings.State.paginationSimEnabled]（默认开）；
 *  - 子开关 [PluginSettings.State.paginationCountEnabled]（T-CountSimToggle，默认开；
 *    关=只追加 LIMIT 不产 COUNT，父开关优先）；
 *  - 语句为 SELECT 且已应用分页 LIMIT（`mapper.selectList(page, w)` 等分页消费产物）；
 *  - 非 count/exists 消费（其本身即计数语义，不再包裹——对齐 T-M29-02/03 分页消费排除口径）；
 *  - Page.searchCount != false（D3=A；MP 运行时 searchCount=false 不执行 COUNT，反编译实证）。
 */

internal fun SqlPreviewService.injectLogicDeleteCondition(
    table: TableInfo,
    statement: SqlStatement,
    state: LogicDeleteState,
): SqlStatement {
    // T-M31.18：逻辑删除值可配置（默认 0，用户可调整——对齐 MP logic-delete-value）
    // T-SettingsTruth P4：项目级 logicDeleteValue 覆盖（空回退全局，再兜底 "0"）
    val logicDeleteValue = effectiveLogicDeleteValue().ifBlank { "0" }
    return when (statement) {
        is SelectStatement -> {
            var conditions = statement.conditions
            if (state.mainActive) {
                table.logicDeleteColumn?.let { column ->
                    conditions = conditions + Condition.Simple(
                        ColumnRef(tableAlias = statement.tableAlias, columnName = column), "=", SqlValue.Literal(logicDeleteValue),
                    )
                }
            }
            // 副表注入：ON 追加 / WHERE 末尾（子查询形态 tableName 以 '(' 开头无 logicDeleteColumn，天然跳过）
            val subWhere = mutableListOf<Condition>()
            val joins = statement.joins.map { join ->
                val column = join.logicDeleteColumn ?: return@map join
                if (!state.subActive) return@map join
                val cond = Condition.Simple(
                    ColumnRef(tableAlias = join.alias, columnName = column), "=", SqlValue.Literal(logicDeleteValue),
                )
                if (state.subToWhere) {
                    subWhere += cond
                    join
                } else {
                    join.copy(onConditions = join.onConditions + cond)
                }
            }
            if (subWhere.isNotEmpty()) conditions = conditions + subWhere
            statement.copy(conditions = conditions, joins = joins)
        }
        is DeleteStatement -> {
            val column = table.logicDeleteColumn ?: return statement
            if (!state.mainActive) return statement
            statement.copy(
                conditions = statement.conditions + Condition.Simple(
                    ColumnRef(tableAlias = statement.tableAlias, columnName = column), "=", SqlValue.Literal(logicDeleteValue),
                ),
            )
        }
        else -> statement
    }
}

/**
 * T-M22-02：乐观锁条件注入（@Version 实体 + MP OptimisticLockerInnerInterceptor 语义）——
 * UPDATE 语句自动追加 `SET version = version + 1` 与 WHERE 末尾 `AND version = ?`；
 * 语句已显式涉及 version 列（SET 赋值或 WHERE 条件）时不重复注入。
 * **不依赖配置开关**（对齐 MP 拦截器默认行为，无 @Version 字段的实体零影响）；
 * 仅 [UpdateStatement] 注入——SelectStatement/DeleteStatement 不注入。
 *
 * 边界（文档化，不实现）：
 *  - `@InterceptorIgnore`（方法级注解，静态代码不可映射调用方意图）不做抑制处理；
 *  - `@KeySequence`（序列取主键）不在主键解析范围内——主键恒以列名占位。
 */

internal fun SqlPreviewService.injectTenantLineCondition(
    table: TableInfo,
    statement: SqlStatement,
    hasInterceptorIgnore: Boolean,
): SqlStatement {
    val settings = PluginSettings.getInstance().state
    if (!settings.injectTenantLine) {
        LOG.info("injectTenantLine: SKIP switch=off, stmt=${statement::class.simpleName}, table=${table.tableName}")
        return statement
    }
    if (hasInterceptorIgnore) {
        LOG.info("injectTenantLine: SKIP @InterceptorIgnore(tenantLine=true), stmt=${statement::class.simpleName}")
        return statement
    }
    val tenantColumn = settings.tenantColumnName.ifBlank { DEFAULT_TENANT_COLUMN }
    return when (statement) {
        is SelectStatement -> {
            if (conditionReferencesColumn(statement.conditions, tenantColumn)) {
                LOG.info("injectTenantLine: SKIP column already in WHERE, col=$tenantColumn, stmt=SELECT")
                return statement
            }
            LOG.info("injectTenantLine: INJECT col=$tenantColumn, stmt=SELECT, table=${table.tableName}")
            statement.copy(
                conditions = statement.conditions + Condition.Simple(
                    ColumnRef(tableAlias = statement.tableAlias, columnName = tenantColumn),
                    "=", SqlValue.Placeholder("$tenantColumn (= ?)"),
                ),
            )
        }
        is UpdateStatement -> {
            if (statement.sets.any { it.column?.columnName == tenantColumn }) {
                LOG.info("injectTenantLine: SKIP column in SET, col=$tenantColumn, stmt=UPDATE")
                return statement
            }
            if (conditionReferencesColumn(statement.conditions, tenantColumn)) {
                LOG.info("injectTenantLine: SKIP column already in WHERE, col=$tenantColumn, stmt=UPDATE")
                return statement
            }
            LOG.info("injectTenantLine: INJECT col=$tenantColumn, stmt=UPDATE, table=${table.tableName}")
            statement.copy(
                conditions = statement.conditions + Condition.Simple(
                    ColumnRef(tableAlias = statement.tableAlias, columnName = tenantColumn),
                    "=", SqlValue.Placeholder("$tenantColumn (= ?)"),
                ),
            )
        }
        is DeleteStatement -> {
            if (conditionReferencesColumn(statement.conditions, tenantColumn)) {
                LOG.info("injectTenantLine: SKIP column already in WHERE, col=$tenantColumn, stmt=DELETE")
                return statement
            }
            LOG.info("injectTenantLine: INJECT col=$tenantColumn, stmt=DELETE, table=${table.tableName}")
            statement.copy(
                conditions = statement.conditions + Condition.Simple(
                    ColumnRef(tableAlias = statement.tableAlias, columnName = tenantColumn),
                    "=", SqlValue.Placeholder("$tenantColumn (= ?)"),
                ),
            )
        }
    }
}

/**
 * F-6 Phase B：动态表名替换——按用户配置的 [TableNameRule] 列表逐条匹配替换表名。
 *
 * 规则按列表顺序应用（首个匹配即停）；正则编译失败时跳过该规则并日志警告（不中断预览）。
 * 替换模板支持 `$1`/`$2` 引用捕获组、`$tableName` 引用完整原始表名。
 *
 * @param table     当前语句关联的实体表（用于 DynamicTableNameProcessor 的上下文）
 * @param statement 当前语句模型
 * @return 表名替换后的新语句（无规则命中时返回原实例）
 */

internal fun SqlPreviewService.resolveHqlTable(
    entityClass: PsiClass?,
    entityClassName: String?,
    hql: String,
    context: PsiElement? = null,
): TableInfo? {
    // 查询中的根实体才是权威；TypedQuery 的结果类型、NamedQuery 宿主、查询名前缀都可能不是根实体。
    val className = extractEntityNameFromHql(hql) ?: entityClassName ?: return null
    val resolver = JpaEntityResolver()
    val resolution = cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep.resolve(project, className, context)
    // 歧义是终态：不借显式类、XML 映射或别名 fallback 再偷偷挑一个。
    if (resolution.ambiguous) {
        // T-EmbeddedIdentify W1：实体解析诊断日志——外部报障「@Embedded 不识别」排障锚点之一
        LOG.info(
            "[T-EmbeddedIdentify] resolveHqlTable entity=$className → 歧义终态 " +
                "candidates=[${resolution.candidates.joinToString { it.qualifiedName ?: it.name ?: "?" }}]",
        )
        return null
    }
    // 显式传入的类（TypedQuery 结果类型 / @NamedQuery 宿主）**不是旁路**——scope 内解析出根实体时以它为准；
    // 解析不出时，只有显式类本身在查询宿主可见范围内才可用。否则它属于别的模块/持久化单元，拿它当根实体会
    // 渲染出另一张表（T-EntityNameScope：同名实体跨模块误选即此形态）。
    val explicit = entityClass?.takeIf { classMatchesQueryEntity(it, className) }
    val target = resolution.psiClass
        ?: explicit?.takeIf { isVisibleInQueryScope(it, context) }
    // 类解析不到不是终态：HBM 纯 XML 映射工程里根本没有 Java 类（既有路径必须保持可达）。
    if (target == null) {
        LOG.info("[T-EmbeddedIdentify] resolveHqlTable entity=$className → 未解析（走 HBM 回退判定） hasContext=${context != null}")
        val hbmOnly = HbmXmlEntityResolver.resolve(className, project, context)
        return hbmOnly?.let { convertHbmMappingToTableInfo(it) }
    }
    val resolved = resolver.resolve(target, context)
    LOG.info(
        "[T-EmbeddedIdentify] resolveHqlTable entity=$className → class=${target.qualifiedName} " +
            "url=${target.containingFile?.virtualFile?.url} columns=${resolved.columns.size} " +
            "embedded=${resolved.columns.count { it.embedded }}",
    )
    if (resolved.columns.isNotEmpty()) return resolved
    val hbmMapping = HbmXmlEntityResolver.resolve(className, project, context)
    return hbmMapping?.let { convertHbmMappingToTableInfo(it) } ?: resolved
}

/** 显式类是否就是查询里那个实体（根实体名匹配；FQN 与 `@Entity(name)` 两种写法都认）。 */
private fun classMatchesQueryEntity(clazz: PsiClass, rootName: String): Boolean {
    val entityName = cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader.entityNameOf(clazz)
        ?.takeIf(String::isNotBlank) ?: clazz.name
    return rootName == clazz.qualifiedName || rootName == entityName
}

/** 显式类是否落在查询宿主的可见范围内（模块 + 依赖 + 库）。 */
private fun isVisibleInQueryScope(clazz: PsiClass, context: PsiElement?): Boolean = runCatching {
    val scope = cn.song.mybatisplus.visualizer.psi.EntityClassResolver.searchScope(clazz.project, context)
    clazz.containingFile?.virtualFile?.let(scope::contains) == true
}.getOrDefault(false)

/**
 * T-HqlInhCols：按 @Entity(name=...) 实体别名查找实体类。
 *
 * HQL `FROM Alias` 的 Alias 可为实体名（JPA 语义）而非类名——
 * 类名快路径（findClass/findClassByShortName）未命中时按注解值反查。
 * 扫描结果（实体名 → 全限定名）缓存于 Project userData，以
 * PsiModificationTracker.modificationCount 失效（照抄 EntityClassFinder 的
 * @TableName 反查同款缓存惯例；未命中也入缓存防重复全量扫描）。
 *
 * T-GutterDupFreeze（S3，20261008 冻结事故）：慢路径原为**无上限全项目扫描**，且不判索引就绪；
 * 误判输入（链源码里抓到的表名 `t_order`）在索引构建期实测 70 秒、读锁全程不放。现两道护栏：
 *  1. 索引构建期（dumb）直接按未命中降级，**不写负缓存**（索引就绪后重试仍可命中）；
 *  2. 扫描改有界（上限 + 取消检查），收口 [cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep]。
 */
