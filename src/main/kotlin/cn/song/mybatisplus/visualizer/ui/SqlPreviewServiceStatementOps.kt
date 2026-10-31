package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.context.ActualSqlPsiResolver
import cn.song.mybatisplus.visualizer.context.ModuleIdentityResolver
import cn.song.mybatisplus.visualizer.context.ModuleIdentityResolution
import cn.song.mybatisplus.visualizer.context.PreviewSourceContext
import cn.song.mybatisplus.visualizer.context.PreviewSourceOrigin
import cn.song.mybatisplus.visualizer.context.SqlPsiResolution
import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.converter.ConverterRegistry
import cn.song.mybatisplus.visualizer.history.SqlHistoryEntry
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager
import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.log.sqlLogManagerIfCreated
import cn.song.mybatisplus.visualizer.log.structureFingerprint
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
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryLocator
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.sql.DataSourceUrlProbe
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


// T-M53-II（2026-10-10）同法，D202 第六次登记：SqlPreviewService 的「语句后处理（分页/拦截器改写/条件视图）」
// 内聚成员，原样搬出到本文件——方法体逐字符搬运（仅整体左移一级缩进），可见性按原样保留。

/** 从 SQL 字符串提取 `?` 占位符参数名（简化：param1, param2, ...）。 */
private fun SqlPreviewService.extractPlaceholders(sql: String): List<String> {
    val count = sql.count { it == '?' }
    return (1..count).map { "param$it" }
}

/**
 * 由语句模型构建预览结果（应用方言与格式化配置）。
 * R12 T-PaginationSim：[countSql] 非 null 时透传 [PreviewResult.countSql]（面板在主 SQL 后并列渲染）。
 */
internal fun SqlPreviewService.paginationCountSql(
    chain: cn.song.mybatisplus.visualizer.model.WrapperChain,
    statement: SqlStatement,
    dialect: Dialect,
): String? {
    if (settings?.state?.paginationSimEnabled == false) return null
    if (settings?.state?.paginationCountEnabled == false) return null
    if (chain.countConsumed || chain.existsConsumed) return null
    if (!chain.pageSearchCount) return null
    val select = statement as? SelectStatement ?: return null
    if (select.limit == null) return null
    val base = SqlGenerator.generate(select.copy(limit = null), compact = true, dialect = dialect)
    return PaginationSimulator.wrapCountSql(base)
}

/**
 * 性能分析（仅 SELECT）：委托 [SqlPerfAnalyzer]（F-2）。
 * UPDATE/DELETE 走既有 buildWarning 路径，不重复分析。
 *
 * T-DialectQuoteWarn §十二：带方言 + 绑定数据源的 ANSI_QUOTES 三态分析——「MySQL 家族 + 双引号
 * 标识符」这一档只有在**确知连接声明了** `ANSI_QUOTES` 时才不报，否则降级 WARN（见
 * [boundAnsiQuotesOrNull]）。
 */
internal fun SqlPreviewService.analyzePerf(sql: String, statement: SqlStatement, dialect: Dialect = currentDialect()): List<PerfWarning>? {
    if (statement !is SelectStatement) return null
    val warnings = SqlPerfAnalyzer.analyze(sql, dialect, boundAnsiQuotesOrNull())
    return warnings.ifEmpty { null }
}

/**
 * T-DialectQuoteWarn §十二：绑定数据源是否**已声明** `sql_mode` 含 `ANSI_QUOTES`（三态：
 * `true`/`false`/`null`＝未知）。
 *
 * 绑定源与预览面板指示行**同源同优先级**（[cn.song.mybatisplus.visualizer.ui.boundDataSourceNameForDisplay]
 * 的链）：活控制台绑定 [DataSourceSelector.boundConsoleDataSourceName] > last-used 记忆
 * [DataSourceSelector.rememberedDataSourceName]（面板 `bindstate.associated`）> 未绑定（null）。
 * 拿到名字后按名取 URL（[DataSourceUrlProbe.urlByName]），**不用** [DataSourceUrlProbe.firstUrl]
 * ——首个数据源与绑定源可以不同且 `ANSI_QUOTES` 状态相反（本项目 demo：首个 jpa_demo、绑定 mpj_demo），
 * 拿首个当绑定源会给出错误结论。
 *
 * 取不到名字/URL/无法判定 → null（未知），规则据此降级 WARN（不假装知道连接选项）。
 * 全程 runCatching：无项目/无 Database 插件/反射失败一律回落 null，绝不影响预览主流程。
 */
private fun SqlPreviewService.boundAnsiQuotesOrNull(): Boolean? {
    val name = runCatching {
        cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSourceName()
    }.getOrNull() ?: runCatching {
        cn.song.mybatisplus.visualizer.execute.DataSourceSelector.rememberedDataSourceName(project)
    }.getOrNull() ?: return null
    val url = runCatching { DataSourceUrlProbe.urlByName(project, name) }.getOrNull() ?: return null
    return runCatching { DataSourceUrlProbe.ansiQuotesFromUrl(url) }.getOrNull()
}

/** 收集语句中的动态条件行（M12）：遍历 WHERE 条件树，扁平化 toggle 元数据（含嵌套 Group 内）。 */
internal fun SqlPreviewService.collectConditionViews(statement: SqlStatement): List<ConditionView> = when (statement) {
    is SelectStatement -> statement.conditions.flatMap { it.toConditionViews() }
    is UpdateStatement -> statement.conditions.flatMap { it.toConditionViews() }
    is DeleteStatement -> statement.conditions.flatMap { it.toConditionViews() }
}

/**
 * 逻辑删除条件注入（T-M15-07 + T-M20-05 精确化）：实体含 @TableLogic 字段时，查询/删除自动追加 `deleted = 0`。
 * 仅 SELECT/DELETE（UPDATE 不注入——更新可能修改 deleted 本身，真实项目发票域显式 `.eq(getDeleted, 0)`）。
 *
 * 主表/副表注入按 [LogicDeleteState] 独立控制（对齐 MPJ 官方六方法语义）：
 *  - 主表：mainActive 时 WHERE 追加 `t.deleted = 0`（T-M23.10：MPJ 联表带主表别名 statement.tableAlias，
 *    单表 tableAlias=null 渲染裸列名，维持历史格式）；
 *  - 副表：对每个带 [JoinClause.logicDeleteColumn] 的 join，subActive 时——
 *    - subToWhere=false（默认 / `logicDelToOn`）→ ON 条件追加 `alias.deleted = 0`；
 *    - subToWhere=true（`logicDelToWhere`）→ WHERE 末尾追加带别名的 `alias.deleted = 0`（主表在前、副表在后）。
 *
 * 副表仅 SELECT 有 joins；DELETE 无 joins 维持主表注入（受 mainActive 控制）。
 */
internal fun SqlPreviewService.injectOptimisticLockCondition(table: TableInfo, statement: SqlStatement): SqlStatement {
    val update = statement as? UpdateStatement ?: return statement
    val versionColumn = table.versionColumn ?: return update
    // 已显式 SET version（如 set(Entity::getVersion, x) / setSql）→ 不重复注入
    if (update.sets.any { it.column?.columnName == versionColumn }) return update
    // 已显式条件 version（如 eq(Entity::getVersion, x)）→ 不重复注入
    if (conditionReferencesColumn(update.conditions, versionColumn)) return update
    // T-M23.10：联表更新（UpdateJoinWrapper）主表有别名——version 列与 SET 表达式带 tableAlias
    // （单表 tableAlias=null 渲染裸列名，维持历史格式）
    val alias = update.tableAlias
    val prefix = alias?.let { "$it." } ?: ""
    return update.copy(
        sets = update.sets + SetClause(
            column = ColumnRef(tableAlias = alias, columnName = versionColumn),
            rawSql = "$prefix$versionColumn = $prefix$versionColumn + 1",
        ),
        conditions = update.conditions + Condition.Simple(
            ColumnRef(tableAlias = alias, columnName = versionColumn), "=", SqlValue.Placeholder("?"),
        ),
    )
}

/**
 * T-TenantLine：多租户条件注入（TenantLineInnerInterceptor 模拟）——
 *  - SELECT/UPDATE/DELETE：WHERE 末尾追加 `AND {tenantColumn} = ?`
 *    （语句已显式引用租户列时不重复注入）；
 *  - INSERT：不在本方法处理（INSERT 无 WHERE，租户列追加见 [BatchOperationConverter]）。
 *
 * 仅 [PluginSettings.injectTenantLine] = true 时生效；调用链方法标注
 * `@InterceptorIgnore(tenantLine = true)` 时跳过。
 *
 * 与 injectLogicDeleteCondition / injectOptimisticLockCondition 三层串行——
 * 三者按语句类型互补：逻辑删除仅 SELECT/DELETE、乐观锁仅 UPDATE、租户 SELECT/UPDATE/DELETE。
 */
internal fun SqlPreviewService.processDynamicTableName(table: TableInfo, statement: SqlStatement): SqlStatement {
    val rules = settings?.state?.dynamicTableNameRules?.filter { it.enabled && it.pattern.isNotBlank() }
        ?: return statement
    if (rules.isEmpty()) return statement
    return DynamicTableNameProcessor.process(table.tableName, rules)?.let { newTableName ->
        rewriteTableName(statement, newTableName)
    } ?: statement
}

/**
 * 将语句模型中的表名替换为新的表名（浅拷贝涉及表名的字段）。
 */
private fun SqlPreviewService.rewriteTableName(statement: SqlStatement, newTableName: String): SqlStatement = when (statement) {
    is SelectStatement -> statement.copy(table = statement.table.copy(tableName = newTableName))
    is UpdateStatement -> statement.copy(table = statement.table.copy(tableName = newTableName))
    is DeleteStatement -> statement.copy(table = statement.table.copy(tableName = newTableName))
}

/**
 * F-6 Phase C：数据权限模拟条件注入——开启 dataPermissionHint 后，追加用户填写的模拟 WHERE 片段。
 *
 * 运行时 DataPermissionInterceptor 根据用户角色动态追加条件，无法静态模拟；提供占位提示 +
 * 用户手动填写模拟条件（`dataPermissionMockCondition`），追加到 WHERE 末尾。
 *
 * 委托 [DataPermissionPlaceholder.injectMockCondition] 纯函数实现。
 */
internal fun SqlPreviewService.injectDataPermissionCondition(table: TableInfo, statement: SqlStatement): SqlStatement {
    if (settings?.state?.dataPermissionHint != true) return statement
    return DataPermissionPlaceholder.injectMockCondition(statement, settings.state.dataPermissionMockCondition)
}

/**
 * T-TenantLine：检测调用链所在方法是否标注 @InterceptorIgnore(tenantLine=true)——
 * 从 anchor 向上查找包含它的 PsiMethod，检查 modifierList 注解。
 * 注解类不可解析时按短名 "InterceptorIgnore" 兜底（与 EntityResolver.findAnnotationByShortName 同模式）；
 * 属性名 = [attrName]（如 "tenantLine"），值 = true 时返回 true。
 *
 * 边界（文档化，保守策略）：
 *  - 注解在类级别 → 不做类级检测（仅方法级，与 MP 实际行为对齐）；
 *  - 注解类不可解析 / 属性缺失 → 兜底 false（不跳过，保持注入——宁可多不可少）；
 *  - 扫描抛异常 → catch 兜底返回 false。
 */
internal fun SqlPreviewService.hasInterceptorIgnoreAnnotation(anchor: PsiElement, attrName: String): Boolean = runCatching {
    val method = PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java) ?: return false
    method.modifierList.annotations?.any { annotation ->
        val simpleName = annotation.nameReferenceElement?.referenceName
        simpleName == "InterceptorIgnore" &&
            (annotation.findAttributeValue(attrName) as? PsiLiteralExpression)?.value as? Boolean == true
    } ?: false
}.getOrDefault(false)

/** T-M29-03：分页 LIMIT 注入——仅 SELECT 语句生效（UPDATE/DELETE 不追加，维持现状）。 */
internal fun SqlPreviewService.applyPageLimit(statement: SqlStatement, pageLimit: LimitClause?): SqlStatement {
    val limit = pageLimit ?: return statement
    return when (statement) {
        is SelectStatement -> statement.copy(limit = limit)
        else -> statement
    }
}

/** T-M22-02：条件树是否已提及指定列（Simple/In/Between/Like/Unary/ColumnComparison 按列名匹配，Group 递归）。 */
internal fun SqlPreviewService.conditionReferencesColumn(conditions: List<Condition>, columnName: String): Boolean =
    conditions.any { condition ->
        when (condition) {
            is Condition.Simple -> condition.column.columnName == columnName
            is Condition.Unary -> condition.column.columnName == columnName
            is Condition.In -> condition.column.columnName == columnName
            is Condition.Between -> condition.column.columnName == columnName
            is Condition.Like -> condition.column.columnName == columnName
            is Condition.ColumnComparison ->
                condition.left.columnName == columnName || condition.right.columnName == columnName
            is Condition.Group -> conditionReferencesColumn(condition.children, columnName)
            is Condition.Raw -> false
        }
    }

/**
 * 单个条件节点 → 条件视图。
 * 组带开关（布尔重载，T-M29.1 用户反馈：组行与子条件行重复展示）：子条件**并入组行**——
 * 组复选框 + 组内子条件值输入框（来源递归收集去重，按渲染顺序），子条件不再单独成行；
 * 组无开关（无布尔标志 `and(w -> ...)`）与非组条件：自身 toggle + 嵌套子条件扁平化（M19.6 存量）。
 */
internal fun Condition.toConditionViews(): List<ConditionView> {
    val ownToggle = toggle
    if (this is Condition.Group && ownToggle != null) {
        val sources = groupValueSources()
        return listOf(
            ConditionView(
                ownToggle.id, ownToggle.label, ownToggle.flagSource, ownToggle.defaultEnabled,
                sources.firstOrNull(), sources,
            ),
        )
    }
    val own = ownToggle?.let {
        // valueSources 为空时回退单值（旧路径仅设 valueSource，M19.5 兼容）
        val sources = it.valueSources.ifEmpty { listOfNotNull(it.valueSource) }
        ConditionView(it.id, it.label, it.flagSource, it.defaultEnabled, it.valueSource, sources)
    }
    val nested = when (this) {
        is Condition.Group -> children.flatMap { it.toConditionViews() }
        else -> emptyList()
    }
    return listOfNotNull(own) + nested
}

/** T-M29.1：组内子条件值来源递归收集（Simple/Like/Between/In 占位符，组递归；去重保持渲染顺序）。 */
internal fun Condition.groupValueSources(): List<String> = when (this) {
    is Condition.Simple -> listOfNotNull((value as? SqlValue.Placeholder)?.source)
    is Condition.Like -> listOfNotNull((pattern as? SqlValue.Placeholder)?.source)
    is Condition.Between -> listOfNotNull(
        (lo as? SqlValue.Placeholder)?.source,
        (hi as? SqlValue.Placeholder)?.source,
    )
    is Condition.In -> values.mapNotNull { (it as? SqlValue.Placeholder)?.source }
    is Condition.Group -> children.flatMap { it.groupValueSources() }
    else -> emptyList()
}.distinct()
