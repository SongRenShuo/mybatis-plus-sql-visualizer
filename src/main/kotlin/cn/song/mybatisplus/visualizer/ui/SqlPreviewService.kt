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

/**
 * SQL 预览服务（M3-01 整合层，F-07 统一入口）。
 *
 * 职责：把编辑器中的任意 PSI 元素转换为可展示的 [PreviewResult]。
 * 集成链路：WrapperLocator 定位 → ConverterRegistry 分发（SPI）→ SqlGenerator 生成 →
 * ParameterReplacer 参数对照 → WrapperCache 缓存（F-20）。
 *
 * M5 起读取 [PluginSettings] 配置：方言（分页子句）、格式化开关（多行/压缩）。
 * 轻服务（M16）：`@Service(Service.Level.PROJECT)` 注解声明，无需 plugin.xml `<projectService>` 注册
 * （IDE 自动发现；构造函数仅依赖 Project，满足轻服务条件），获取方式不变（project.getService）。
 */
@Service(Service.Level.PROJECT)
class SqlPreviewService(internal val project: Project) {

    /** 文件级解析缓存（F-20）：文件内容变更（modificationStamp）即失效。 */
    private val cache = WrapperCache()

    /**
     * T-ErrorLocate T-EL-03：最近一次预览的源锚点（文件 + 0 基光标行），错误定位动作消费。
     * 预览触发入口（ConvertToSqlAction）刷新；null = 本会话尚未有可解析锚点（定位按钮不出现）。
     * 单槽即可：预览弹窗每项目至多一个（M36.5.47 单例化），同刻只有一个错误面板可定位。
     */
    var lastPreviewSourceAnchor: SourceAnchor? = null

    /** 预览源锚点（文件 + 0 基行）。 */
    data class SourceAnchor(val file: com.intellij.openapi.vfs.VirtualFile, val line0: Int)

    init {
        // T-PerfTail #1：编辑器关闭即清理该文件的 WrapperCache 条目——长会话中条目数随打开
        // 文件集有界（LRU 512 上限之外的主动回收）。连接绑定 project Disposable，项目关闭自动注销。
        // FileEditorManagerListener 的 fileClosed 携带 VirtualFile，与 WrapperCache 键的
        // VirtualFile.url 字符串维度（T-Perf-18）直接对齐。
        project.messageBus.connect(project).subscribe(
            com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                override fun fileClosed(
                    source: com.intellij.openapi.fileEditor.FileEditorManager,
                    file: com.intellij.openapi.vfs.VirtualFile,
                ) {
                    cache.evictFile(file.url)
                }
            },
        )
    }

    // T-TenantLine 诊断日志：输出注入决策链路（开关值/列名/语句类型/跳过原因），
    // 供沙盒排查「开启租户但不拼接」问题（无日志 = 本方法未被调用）。

    /**
     * T-M19.4（用户实测：表名配置修改后预览不生效——缓存按文件戳失效，设置变更不触发）：
     * 影响解析结果的表相关设置签名，作为 [WrapperCache] 缓存维度。
     * 复用 [cn.song.mybatisplus.visualizer.util.TableConfigSignature.projectSignature] 统一来源，
     * 与 [cn.song.mybatisplus.visualizer.psi.EntityResolver] 的解析缓存防漂移。
     *
     * O-5：项目级设置开启时追加项目级表配置签名——项目设置变更后缓存同样失效。
     */
    private fun tableConfigSignature(): String {
        val base = TableConfigSignature.projectSignature()
        val ps = projectSettings
        if (ps == null || !ps.isEnabled()) return base
        val s = ps.state
        // 项目级表配置（空 = 与全局一致，无需追加差异）
        val projectPart = listOf(
            s.tableNameInference.ifBlank { "" },
            s.ignoredFields.ifBlank { "" },
            s.tablePrefix.ifBlank { "" },
            s.tableSuffix.ifBlank { "" },
        ).joinToString("|")
        return if (projectPart.isBlank() || projectPart == "|||") base else "$base|proj:$projectPart"
    }

    /**
     * 全局配置（测试环境未注册 service 时为 null，使用默认值）。
     */
    internal val settings: PluginSettings? =
        runCatching { PluginSettings.getInstance() }.getOrNull()

    /**
     * O-5：项目级配置（project-level 轻服务；未启用时回退全局）。
     * 读取设置时优先查项目级（[ProjectPluginSettings]），开启时覆盖全局值。
     */
    private val projectSettings: ProjectPluginSettings? =
        runCatching { ProjectPluginSettings.getInstance(project) }.getOrNull()

    /**
     * O-5：获取有效的设置值（项目级优先，关闭/空值回退全局）。
     *
     * 项目级未启用或值为空/null 时使用全局 [PluginSettings] 值，保证单来源决策。
     */
    private fun <T> effective(global: () -> T, project: () -> T?): T {
        val ps = projectSettings
        return if (ps != null && ps.isEnabled()) {
            project() ?: global()
        } else {
            global()
        }
    }

    /**
     * O-5：获取有效的字符串设置值（项目级优先，空值回退全局）。
     * 专门用于 String 类型，避免泛型推断问题。
     */
    private fun effectiveString(global: String, project: String?): String {
        val ps = projectSettings
        return if (ps != null && ps.isEnabled() && !project.isNullOrBlank()) {
            project
        } else {
            global
        }
    }

    /**
     * 预览锚点元素处的 Wrapper 转换结果。
     *
     * 识别顺序：
     *  1. Wrapper 调用链（SELECT/UPDATE/DELETE/MPJ）
     *  2. 批量操作调用（F-15，`service.updateBatchById(list)` 等）
     *
     * @param anchor 光标处 / 悬停处的 PSI 元素
     * @return 预览结果；两者均未命中时返回 null
     */
    /**
     * F-1 修复：Repository **接口方法声明行**预览（无调用表达式，[preview] 的批量/JPA 管线永不认领）。
     *
     * 缺口形态（2026-10-12 演示录制中发现，诊断报告 `docs/调研报告/20261012-0900-*`）：
     * `deleteAllInBatch()` 这类 bulk 形态既不在派生查询线（不含 `By`、`*All` 词干白名单不放行
     * `InBatch`），也没有调用表达式给 CRUD 转换线——声明行光标下零可达，而模板在转换层完整存在。
     *
     * 语义与调用点 CRUD 线一致（同一 `buildRepositoryMethod` 模板），差别只有：声明行无实参可静态
     * 求值，一律 `?` 占位。非 Repository 接口 / 方法名不在名单 / 实体不可解析 → null（交回回落链）。
     */
    fun previewRepositoryDeclaration(method: PsiMethod): PreviewResult? {
        val jpaCrud = JpaCrudOperationConverter.convertRepositoryDeclaration(
            method,
            resolveVariables = effectiveReplaceParams(),
        ) ?: return null
        val result = PreviewResult(
            sql = jpaCrud.sql,
            compactSql = jpaCrud.sql,
            unresolvedParams = jpaCrud.paramSources,
            paramTable = jpaCrud.paramGroups,
            wrapperType = null,
            tableName = jpaCrud.tableName,
            warning = jpaCrud.warning,
            paramHints = jpaCrud.paramHints,
            batchModel = jpaCrud.batchModel,
        )
        recordHistory(result, method)
        return result
    }

    /** Wrapper 的 owner 是调用所在源码文件，不是实体类文件，也不是把 Java/Kotlin 当成 SQL PSI。 */
    fun resolveWrapperSourceContext(anchor: PsiElement): PreviewSourceContext {
        val file = anchor.containingFile
        val owner = PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java, false) ?: anchor
        val sqlResolution = if (file == null) SqlPsiResolution.None else
            ActualSqlPsiResolver.resolve(file, owner, anchor.textOffset)
        val moduleResolution = file?.virtualFile?.let { ModuleIdentityResolver.resolve(project, it) }
            ?: ModuleIdentityResolution.None
        return PreviewSourceContext(project, PreviewSourceOrigin.PSI, file,
            sqlPsiResolution = sqlResolution, moduleResolution = moduleResolution)
    }

    fun preview(anchor: PsiElement, sourceContext: PreviewSourceContext? = null): PreviewResult? {
        // JDBC 模板 SQL 字符串预览（正向）：queryForList/update 等方法的字符串 SQL 实参 → 预览
        // 放在最前：JDBC 分支不写 Wrapper 缓存、不依赖实体解析，与既有路径完全隔离
        if (settings?.state?.jdbcSqlPreviewEnabled != false) {
            JdbcTemplateSqlLocator.locate(anchor)?.let { call ->
                return JdbcTemplateSqlPreviewService.preview(call, anchor)
            }
        }

        val file = anchor.containingFile ?: return null
        val context = sourceContext?.takeIf { it.project == project && it.hostPsiFile == file }
            ?: resolveWrapperSourceContext(anchor)
        // 宿主文件提供 IDEA 有效方言映射；SQL PSI 参数只在确有真实 SqlFile 时提供注入作用域。
        val decision = if (context.sqlPsiResolution is SqlPsiResolution.Ambiguous ||
            context.moduleResolution is ModuleIdentityResolution.Ambiguous) {
            cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision(
                cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Status.AMBIGUOUS, null,
                cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Confidence.FALLBACK, null)
        } else DialectRegistry.decision(project, context.hostVirtualFile,
            context.sqlPsiFile, context.canonicalModuleKey)
        val dialect = DialectRegistry.byName(decision.dialectName)
        // 缓存键含锚点偏移：同文件多个 Wrapper（查询/删除/修改）各自独立缓存，避免串结果
        // M14.6：缓存键含「替换 ? 占位符」开关，开关切换后不命中旧结果
        // T-M19.4（用户实测：表名配置修改后预览不生效）：缓存键含**表配置签名**——设置变更
        // （文件戳不变的场景）不再命中旧解析结果
        val resolveVariables = effectiveReplaceParams()
        val located = cache.getOrCompute(file, anchor.textRange.startOffset, resolveVariables,
            "${tableConfigSignature()}|${decision.status}|${decision.dialectName}|${decision.source}|${decision.dataSourceIds}") {
            WrapperLocator.locateWithTable(anchor, resolveVariables)
        }
        // T-M19.4（用户实测：实体文件新增/修改 @TableName 后预览不生效——缓存按**锚点文件**戳失效，
        // 实体文件变化不触发）：命中缓存时校验实体文件戳（@TableName/@TableField 注解来源），
        // 变更则重算（重算结果由下一次 getOrCompute 写回缓存）
        val effectiveLocated = if (located != null && !located.entityFileValid()) {
            WrapperLocator.locateWithTable(anchor, resolveVariables)
        } else {
            located
        }

        // 1. Wrapper 转换（优先）
        effectiveLocated?.let { located ->
            val table = located.table ?: run {
                // O-8（缺陷 B）：字符串服务链实体解析失败（三通道与方法引用推断全失败）→
                // 返回带显式警示的空结果（面板红字），不再静默 return null——
                // 「返回 null = 非 wrapper 锚点」契约收窄保留：仅 located 带 entityResolutionFailed
                // 标记（服务链形态）时降级，工厂/Kotlin/构造形态与其余锚点行为不变
                if (located.entityResolutionFailed || file.language.id.equals("kotlin", ignoreCase = true)) {
                    LOG.info("preview: DEGRADE table=null, wrapperType=${located.chain.wrapperType}, " +
                             "anchor=${anchor.textRange}")
                    return PreviewResult(
                        sql = "",
                        compactSql = "",
                        unresolvedParams = emptyList(),
                        paramTable = null,
                        wrapperType = located.chain.wrapperType,
                        tableName = null,
                        warning = if (located.entityResolutionFailed) {
                            "⚠ 字符串服务链无法推断实体表：Wrapper 链未携带实体类型" +
                                "（无 <实体> 类型实参，服务接口/调用返回类型也无实体泛型），SQL 无法生成"
                        } else {
                            "Kotlin 源码无法推断实体表；SQL 无法生成"
                        },
                        renderedDialect = dialect,
                        dialectDecision = decision,
                    )
                }
                LOG.info("preview: RETURN_NULL table=null, wrapperType=${located.chain.wrapperType}, " +
                         "anchor=${anchor.textRange}")
                return null
            }
            val statement = when {
                // 计数消费：service.count(wrapper) / selectJoinCount(wrapper) / lambdaQuery()...count() → SELECT COUNT(*) FROM ...（保留联表与条件）
                located.chain.countConsumed && located.chain.wrapperType.isQueryLike() -> {
                    val base = parseQueryLike(located.chain, table)
                    SelectStatement(
                        table,
                        selectColumns = listOf(ColumnRef(columnName = "*", function = "COUNT")),
                        tableAlias = base.tableAlias,
                        joins = base.joins,
                        conditions = base.conditions,
                    )
                }
                // exists 链式终结点消费：service.lambdaQuery()...exists() → SELECT EXISTS (子查询)（Q14-③，T-M15-01）
                located.chain.existsConsumed && located.chain.wrapperType.isQueryLike() ->
                    parseQueryLike(located.chain, table)
                // 实体更新消费：service.update(entity, wrapper) 双参（U07-②，T-M15-04）——
                // wrapper 无 set 时用实体字段展开 SET 列（值静态不可求值 → 占位）；主键列排除（进 WHERE）
                // T-M21-08：UpdateJoin/DeleteJoin 类型的消费（`mapper.updateJoin(entity, JoinWrappers.update(...))`
                // / `updateJoinAndNull(entity, ...)`）不走此分支——联表实体 SET 列近似由
                // UpdateJoinConverter 处理（实体字段静态不可得 → 主表非主键全列 `t.col = ?` 占位），
                // 走下方 else → ConverterRegistry.convert
                located.chain.updateEntityConsumed &&
                    located.chain.wrapperType !in UPDATE_DELETE_JOIN_TYPES -> {
                    val parsed = QueryWrapperParser(table).parse(located.chain)
                    val sets = table.columns
                        .filter { !it.isId }
                        // T-M22-02：@Version 列排除——实体 SET 不含 version（对齐 MP
                        // OptimisticLockerInnerInterceptor：version 不进实体字段 SET，
                        // 由 [injectOptimisticLockCondition] 追加 `version = version + 1`）
                        .filter { it.columnName != table.versionColumn }
                        .map { SetClause(column = ColumnRef(columnName = it.columnName), value = SqlValue.Placeholder("?")) }
                    UpdateStatement(table, sets, parsed.conditions)
                }
                // MP 惯用删除写法：删除条件用 LambdaQueryWrapper 构造，`service.remove(wrapper)` / `mapper.delete(wrapper)` 消费；
                // 链式删除 `service.lambdaUpdate()...remove()`（U11-⑤，T-M15-01）同为删除。
                // T-M21-08：DeleteJoinWrapper 类型（`mapper.deleteJoin(JoinWrappers.delete(...))`）不走此分支——
                // 多表删除目标（delete(Class...)/deleteAll()）由 DeleteJoinConverter 处理（下方 else）
                located.chain.deleteConsumed &&
                    (located.chain.wrapperType.isQueryLike() || located.chain.wrapperType == WrapperType.LAMBDA_UPDATE) &&
                    located.chain.wrapperType !in UPDATE_DELETE_JOIN_TYPES -> {
                    val parsed = QueryWrapperParser(table).parse(located.chain)
                    // O-8（缺陷 C）：`last("LIMIT n")` 尾段透传 DELETE 渲染（此前落在 SelectStatement
                    // 独有字段被静默丢弃；跨方言渲染与否由 SqlGenerator 按 [Dialect.supportsDeleteLimit] 决定）
                    DeleteStatement(table, parsed.conditions, limit = parsed.limit)
                }
                // T-FS-04：显式传本服务绑定的 project（converter 内类名兜底推断的项目级表配置口径）
                else -> ConverterRegistry.convert(located.chain, table, project) ?: return null
            }
            // 缺陷②修复：参数历史键指纹取「注入前」语句文本——下面这一串注入（逻辑删除 / 乐观锁 /
            // 多租户 / 数据权限 / 分页 LIMIT）都会改写 SQL 文本，而历史键原先是 SHA-256(SQL 全文)，
            // 用户一开这些开关键就变 → 已回填参数召回 miss 退回占位符（且防抖后新键以空值覆盖落库）。
            val historyFingerprintSeed = SqlGenerator.generate(statement, compact = true, dialect = dialect)
            // T-M15-07：逻辑删除注入（@TableLogic 实体 + 配置开启）——查询/计数/exists/删除自动追加 `AND deleted = 0`
            // （对齐 MyBatis-Plus 全局 logic-delete-field 配置；UPDATE 不注入——更新可能修改 deleted 本身）
            // T-M20-05（P1-01 语义修正）：MPJ 六方法按源码顺序求值——主表/副表逻辑删除独立开关，
            // logicDelToOn/logicDelToWhere 仅控制**副表** deleted 条件位置（ON / WHERE 末尾），不再影响主表注入
            // （旧实现把二者当作全局开关是错误近似，见 [evaluateLogicDeleteState]）
            val logicDelete = evaluateLogicDeleteState(located.chain.calls)
            val baseStatement = if (effectiveInjectLogicDelete()) {
                injectLogicDeleteCondition(table, statement, logicDelete)
            } else {
                statement
            }
            // T-M22-02：乐观锁注入（@Version 实体 UPDATE——SET version = version + 1 + WHERE 末尾
            // AND version = ?；不依赖配置开关，对齐 MP OptimisticLockerInnerInterceptor 默认行为；
            // 与逻辑删除注入串行调用——二者按语句类型互斥：逻辑删除仅 SELECT/DELETE、乐观锁仅 UPDATE）
            val finalStatement = injectOptimisticLockCondition(table, baseStatement)
            // T-TenantLine：多租户注入（TenantLineInnerInterceptor 模拟）——SELECT/UPDATE/DELETE 自动追加
            // `AND {tenantColumn} = ?`（语句已显式引用租户列时不重复注入）；@InterceptorIgnore(tenantLine=true)
            // 方法级注解命中时跳过（保守策略：检测失败→注入，宁可多不可少）
            val hasInterceptorIgnore = hasInterceptorIgnoreAnnotation(anchor, "tenantLine")
            val tenantWhereStatement = injectTenantLineCondition(table, finalStatement, hasInterceptorIgnore)
            // F-6 Phase A：UPDATE 追加租户列 SET（`tenantColumn = tenantDefault`）——对齐 MP 运行时行为
            val tenantStatement = if (settings?.state?.injectTenantLine == true && tenantWhereStatement is UpdateStatement) {
                val tenantColumn = settings.state.tenantColumnName.ifBlank { DEFAULT_TENANT_COLUMN }
                TenantLineEnhancer.enhanceUpdateSet(
                    tenantWhereStatement, tenantColumn, settings.state.tenantColumnDefault.ifBlank { "1" },
                )
            } else {
                tenantWhereStatement
            }
            // F-6 Phase B：动态表名替换（按用户配置的正则规则替换表名）
            val tableNameStatement = processDynamicTableName(table, tenantStatement)
            // F-6 Phase C：数据权限占位提示（追加警告 + 模拟条件）
            val dataPermissionStatement = injectDataPermissionCondition(table, tableNameStatement)
            // T-M29-02/03：分页消费（`service.page(Page, wrapper)` / `selectPage` / 链式 `page()`）→ LIMIT 子句
            // （覆盖 last("limit ...")——MP 运行时两者并存属错误用法，预览以分页为准，文档化边界；
            // count/exists 消费不追加分页）
            val pagedStatement = if (located.chain.countConsumed || located.chain.existsConsumed) {
                dataPermissionStatement
            } else {
                applyPageLimit(dataPermissionStatement, located.chain.pageLimit)
            }
            // R12 T-PaginationSim：分页 COUNT 包裹预览（D1=A 预览附第二条语句；D2=A 基础包裹形态；
            // D3=A searchCount=false 跳过；count/exists 消费不产 COUNT——复用 T-M29-02/03 分页消费口径）
            val countSql = paginationCountSql(located.chain, pagedStatement, dialect)
            val result = buildPreviewResult(
                located.chain.wrapperType, table, pagedStatement, countSql,
                historyFingerprintSeed = historyFingerprintSeed,
                dialect = dialect,
                dialectDecision = decision,
            )
            recordHistory(result, anchor)
            // exists 消费：外层包裹 SELECT EXISTS（对齐 MP 3.5.15 BaseMapper.exists 语义）
            return if (located.chain.existsConsumed && located.chain.wrapperType.isQueryLike()) {
                result.copy(
                    sql = "SELECT EXISTS (${result.sql})",
                    compactSql = "SELECT EXISTS (${result.compactSql})",
                )
            } else {
                result
            }
        }

        // 2. 批量操作识别（anchor 或其父链上的方法调用）
        val batchCall = generateSequence(anchor) { it.parent }
            .filterIsInstance<PsiMethodCallExpression>()
            .firstOrNull()
        // T-J1/T-J6/T-J21：JPA CRUD / EntityManager 写路径 / HibernateTemplate 实体操作派发
        // （receiver 家族感知，消歧规则见 JpaCrudOperationConverter KDoc）——receiver 判定为
        // JPA 家族时必须走 JpaEntityResolver：MP EntityResolver 只认 @TableName，JPA 实体
        // （@Table(name="t_user")）会被输出成 `INSERT INTO user_entity` 错表错列；
        // 非 JPA 家族 convert 返回 null，维持下方 MP 批量路径现状（存量行为零变化）
        // T-J5/T-J20 前置分支：Specification / Query By Example executor 调用先于 JPA CRUD 家族判定——
        // `findAll(spec)` / `findAll(example)` 的方法名同在 JpaCrudOperationConverter.REPOSITORY_METHODS
        // 名单内，后置会被渲染成「无 WHERE 全表 SELECT」（JpaCrudOperationConverter KDoc 建议的扩面方式：
        // 在 SqlPreviewService 侧新增前置分支，不改 Wave2 交付文件）
        if (batchCall != null) {
            val specPreview = SpecificationQueryConverter.convert(batchCall, resolveVariables)
            if (specPreview != null) {
                val result = PreviewResult(
                    sql = specPreview.sql,
                    compactSql = specPreview.sql.replace(SQL_WHITESPACE_RUN, " ").trim(),
                    unresolvedParams = specPreview.paramSources,
                    paramTable = ParameterReplacer.buildParamTable(specPreview.paramSources),
                    paramHints = specPreview.paramHints,
                    wrapperType = null,
                    tableName = specPreview.tableName,
                    warning = specPreview.warning,
                    columns = specPreview.columns,
                )
                recordHistory(result, anchor)
                return result
            }
            val examplePreview = QueryByExampleConverter.convert(batchCall, resolveVariables)
            if (examplePreview != null) {
                val result = PreviewResult(
                    sql = examplePreview.sql,
                    compactSql = examplePreview.sql.replace(SQL_WHITESPACE_RUN, " ").trim(),
                    unresolvedParams = examplePreview.paramSources,
                    paramTable = ParameterReplacer.buildParamTable(examplePreview.paramSources),
                    paramHints = examplePreview.paramHints,
                    wrapperType = null,
                    tableName = examplePreview.tableName,
                    columns = examplePreview.columns,
                )
                recordHistory(result, anchor)
                return result
            }
            // EQ/DS/QD（M44 其他 ORM 第一批）前置派发：Easy-Query proxy 链 / MyBatis Dynamic SQL /
            // Querydsl——三者均非 MP Wrapper 链（locateWithTable 不命中），按 entry+receiver 启发认领；
            // 不认领返回 null 走后续 JPA CRUD / MP 批量管道（存量行为零变化）
            val ormPreview = convertOtherOrm(batchCall, resolveVariables)
            if (ormPreview != null) {
                recordHistory(ormPreview, anchor)
                return ormPreview
            }
        }
        val jpaCrud = batchCall?.let {
            JpaCrudOperationConverter.convert(
                it,
                resolveVariables = effectiveReplaceParams(),
            )
        }
        if (jpaCrud != null) {
            val result = PreviewResult(
                sql = jpaCrud.sql,
                compactSql = jpaCrud.sql,
                unresolvedParams = jpaCrud.paramSources,
                paramTable = jpaCrud.paramGroups,
                wrapperType = null,
                tableName = jpaCrud.tableName,
                warning = jpaCrud.warning,
                paramHints = jpaCrud.paramHints,
                batchModel = jpaCrud.batchModel,
            )
            recordHistory(result, anchor)
            return result
        }
        // T-M23-01/02：批量路径接线——逻辑删除注入开关（settings.injectLogicDelete）与变量求值开关
        // （settings.replaceParams，实参可静态求值时内联）对齐 Wrapper 路径；参数来源/回填提示透传面板
        val batch = batchCall?.let {
            BatchOperationConverter.convert(
                it,
                injectLogicDelete = effectiveInjectLogicDelete(),
                resolveVariables = effectiveReplaceParams(),
                // D2：INSERT 租户列追加 = 主开关 AND 子开关（injectTenantOnInsert）；UPDATE 的 SET 租户注入
                // 走主开关（见上方 enhanceUpdateSet 链），不受本参数影响
                injectTenantLine = (settings?.state?.injectTenantLine ?: true) &&
                    (settings?.state?.injectTenantOnInsert ?: true),
            )
        } ?: return null
        val result = PreviewResult(
            sql = batch.sql,
            compactSql = batch.sql,
            unresolvedParams = batch.paramSources,
            paramTable = batch.paramGroups,
            wrapperType = null,
            tableName = batch.tableName,
            paramHints = batch.paramHints,
            batchModel = batch.batchModel,
        )
        recordHistory(result, anchor)
        return result
    }

    /**
     * 其他 ORM 第一批（M44）前置派发：Easy-Query proxy（EQ_PROXY）/ MyBatis Dynamic SQL（MDS）/
     * Querydsl（QD）。三者按 entry + receiver 启发认领（普通类同名方法不命中，负例守卫），
     * 不认领返回 null（交回 JPA CRUD / MP 批量管道）。
     */
    fun previewDerivedQuery(method: PsiMethod, callArgs: List<PsiExpression>? = null): PreviewResult? {
        val methodInfo = DerivedMethodLocator.locate(method.containingFile ?: return null, method.textRange.startOffset)
            ?: return null
        val entityClass = methodInfo.entityClass ?: return null
        val table = JpaEntityResolver().resolve(entityClass) ?: return null
        val partTree = PartTreeParser.parse(methodInfo.methodName)
        var statement = DerivedQueryConverter.convert(partTree, table)
        // T-PS5-03：入口求一次方言（分页分析/方法级增强/结果构建共用同一实例，不再各求一次）
        val dialect = DialectRegistry.effective(project, method.containingFile?.virtualFile)
        // T-J10：Pageable/Page/Sort/Limit 参数全路径——Pageable/Page → LIMIT 占位（调用点形态静态求值）、
        // Sort → ORDER BY（Sort.by 字面量内联）、Limit（3.2） → 仅 LIMIT 无 OFFSET
        val pageSort = JpaQueryEnhancer.evaluatePageSort(method, callArgs, dialect, table)
        if (pageSort != null) {
            var select = statement as? SelectStatement
            pageSort.limit?.let { limit -> select = select?.copy(limit = limit) }
            if (pageSort.orderBy.isNotEmpty()) select = select?.copy(orderBy = pageSort.orderBy)
            if (select != null) statement = select
        }
        // 使用实际方法参数名（而非通用的 param1/param2）
        // 分页/排序/限流参数：排除参数本身，追加分页占位参数行（pageOffset/pageSize 等）
        val paramNames = method.parameterList.parameters
            .filter { param ->
                val typeName = param.type.canonicalText.substringBefore('<')
                !JpaQueryEnhancer.isPageableType(typeName) &&
                    !JpaQueryEnhancer.isSortType(typeName) &&
                    !JpaQueryEnhancer.isLimitType(typeName)
            }
            .map { it.name } + (pageSort?.extraParamNames ?: emptyList())
        // DerivedQueryConverter 解析条件树，SqlGenerator 生成的 SQL 含 ? 占位符
        val sql = cn.song.mybatisplus.visualizer.sql.SqlGenerator.generate(statement, compact = false, dialect = dialect)
        // T-J4：IN/NOT IN 条件参数接线——inClauseParameterOrdinals 序号对齐方法参数名
        // （过滤 Pageable 后的 paramNames 序），把 IN 参数挂进 buildJpaPreviewResult 的
        // inClauseParams（面板回填按逗号分割多值，对齐 JPQL/HQL 路径的 Result.inClauseParams 惯例）
        val inClauseParams = DerivedQueryConverter.inClauseParameterOrdinals(methodInfo.methodName)
            .mapNotNull { paramNames.getOrNull(it) }
            .toSet()
        // T-J15/J22：@Lock（FOR UPDATE）/ @QueryHints / @EntityGraph（LEFT JOIN）/ 返回类型 notes；
        // pageNotes：分页/排序分析已在上方完成（含调用点实参求值），透传语义注释、跳过二次分析
        val enhancedSql = applyJpaMethodEnhancements(
            method, sql, table, native = false,
            limitAppliedInModel = true,
            pageNotes = pageSort?.notes ?: emptyList(),
            dialectOverride = dialect,
        )
        return buildJpaPreviewResult(statement, enhancedSql, table.tableName, paramNames, method, inClauseParams, dialectOverride = dialect)
    }

    /**
     * JPA 方法级 SQL 增强（T-J10/J15/J22，文本级后处理，previewDerivedQuery / previewJpql /
     * previewHql / previewCriteriaQuery 各路径共用）：
     *
     *  - **T-J10**：宿主方法 `Pageable`/`Page`/`Limit` 参数 → LIMIT/OFFSET 占位（方言形态）；
     *    `Sort` 参数静态求值 → ORDER BY（与查询内已有子句并存时降级注释，避免双子句）；
     *  - **T-J15**：`@Lock(PESSIMISTIC_WRITE)` → SELECT 尾部 `FOR UPDATE`（共享锁形态注释说明方言差异）；
     *    `@QueryHints` 逐条注释；`@EntityGraph(attributePaths)` → LEFT JOIN 注入（按关联元数据解析
     *    真实表/列；解析失败 / 原生 SQL 路径降级注释）；
     *  - **T-J22**：返回类型 notes（Page count 二连 / Slice 多取一条 / Stream / 接口投影）；
     *    `Page` 返回 + `@Query.countQuery` 存在时一并展示 count SQL。
     *
     * 注释行以 `-- ` 前缀追加 SQL 尾部（对齐 JpaCrudOperationConverter notes 惯例）；
     * 全程无注解/无参数时返回原文（存量字节级行为零变化）。
     */
    internal fun countQueryNotes(method: PsiMethod, table: TableInfo?): List<String> {
        val countJpql = JpaQueryEnhancer.countQueryAttribute(method) ?: return emptyList()
        val countTable = if (extractEntityNameFromHql(countJpql) == null) table
        else resolveHqlTable(null, null, countJpql, method)
        val sql = JpqlConverter.convert(countJpql, countTable, project, context = method).sql
        return listOf("@Query.countQuery（Page 返回的 count 伴随查询）：") + sql.lines()
    }

    /**
     * 从 SQL 文本探测主表别名（EntityGraph JOIN 的 ON 左值用）：
     * `FROM t_user u` → `u`；无别名/别名是 SQL 关键字 → 表名本名。
     */
    internal fun detectMainAlias(sql: String, table: TableInfo): String = runCatching {
        val match = FROM_TABLE_ALIAS.find(sql)
        val alias = match?.groupValues?.get(2)
        if (alias == null || SQL_CLAUSE_KEYWORDS.any { alias.equals(it, ignoreCase = true) }) {
            table.tableName
        } else {
            alias
        }
    }.getOrDefault(table.tableName)

    /**
     * JPQL/@Query 预览（波次一）：解析 @Query 注解方法 → JPQL 字符串 → SQL → PreviewResult。
     *
     * 供 [cn.song.mybatisplus.visualizer.ui.gutter.JpqlGutterLineMarkerProvider] 点击调用。
     * T-J10/J15/J22：方法级增强（Pageable/Limit → LIMIT 占位、@Lock → FOR UPDATE、
     * @QueryHints 注释、@EntityGraph → LEFT JOIN、Page 返回 → count SQL 展示）见
     * [applyJpaMethodEnhancements]。
     */
    /** JPQL/HQL 字符串的宿主是 Java 源文件，不是 DatabaseTools SqlFile。跨文件命名查询
     * 使用定义所在文件；无文件/模块歧义时保留项目级兼容回退，不猜测调用点模块。 */
    internal fun jpaSourceContext(owner: PsiElement?): PreviewSourceContext {
        val host = owner?.containingFile
        val file = host?.virtualFile
        val module = file?.let {
            ReadAction.compute<ModuleIdentityResolution, RuntimeException> {
                ModuleIdentityResolver.resolve(project, it)
            }
        } ?: ModuleIdentityResolution.None
        return PreviewSourceContext(
            project = project,
            origin = PreviewSourceOrigin.PSI,
            hostPsiFile = host,
            moduleResolution = module,
        )
    }

    internal fun jpaSourceDecision(owner: PsiElement?): DialectDecisionEngine.Decision =
        DialectRegistry.decision(jpaSourceContext(owner))

    private fun dialectOf(decision: DialectDecisionEngine.Decision): Dialect =
        decision.dialectName?.let(DialectRegistry::byName) ?: DialectRegistry.byName(null)

    fun previewJpql(method: PsiMethod): PreviewResult? {
        val info = method.containingFile?.let { file ->
            JpqlQueryLocator.locate(file, method.textRange.startOffset)
        } ?: return null
        val jpql = info.jpql
        // 参数名提取：优先 @Param 注解值，否则用方法参数名
        val paramNames = extractJpqlParamNames(method)
        // 解析实体类 → TableInfo
        val entityClass = info.entityClass
        val table = if (info.isNative) entityClass?.let { JpaEntityResolver().resolve(it) }
        else resolveHqlTable(entityClass, null, jpql, method)
        // O-145：Java 方法宿主的非 URL 来源证据；不将 JPQL 字面量冒充 SqlFile。
        val decision = jpaSourceDecision(method)
        val dialect = dialectOf(decision)
        // T-J10：分页/排序参数占位名（无参数时空列表，存量行为零变化）
        val extraParamNames = JpaQueryEnhancer.evaluatePageSort(method, null, dialect, table)
            ?.extraParamNames ?: emptyList()
        val allParamNames = paramNames + extraParamNames
        // 原生 SQL：转换命名参数 :param → ? 后展示（保持可读性）
        if (info.isNative) {
            val convertedSql = convertNativeSqlParameters(jpql)
            // 检测 IN 子句参数（在命名参数替换前检测 :paramName）
            val inParams = JpqlConverter.findInClauseParams(jpql)
            val hints = if (inParams.isNotEmpty()) allParamNames.map { ParamHint(quote = true, inList = it in inParams) } else emptyList()
            // T-J15：@EntityGraph 对原生查询运行时不生效 → 仅注释说明；LIMIT/@Lock/notes 照常
            val enhancedSql = applyJpaMethodEnhancements(method, convertedSql, table, native = true, dialectOverride = dialect)
            return PreviewResult(
                sql = enhancedSql,
                // 无增强时保持旧字节级行为（convertedSql 原样），有增强时压缩多行空白
                compactSql = if (enhancedSql == convertedSql) convertedSql else enhancedSql.replace(SQL_WHITESPACE_RUN, " ").trim(),
                unresolvedParams = allParamNames,
                paramTable = ParameterReplacer.buildParamTable(allParamNames),
                paramHints = hints,
                wrapperType = null,
                tableName = table?.tableName
                    ?: info.entityClass?.name?.let { JpaEntityResolver.inferDecoratedTableName(it, project) } ?: "",
                columns = table?.allColumnNames ?: emptyList(),
                renderedDialect = dialect,
                dialectDecision = decision,
            )
        }
        // JPQL → JpqlConverter.convert → Result（转换后 SQL + 语句模型）
        // 关键：使用转换后的 SQL 字符串（含 ? 占位符）直接用于面板显示和参数回填。
        // 若用 SqlGenerator 重新生成，条件树为空会丢失 WHERE 子句和 ? 占位符。
        // Wave1 第三参 project：激活 PSI 级 JOIN ON（@JoinColumn 真实化）与子查询实体映射
        val result = JpqlConverter.convert(jpql, table, project, context = method)
        // T-J10/J15/J22：方法级增强（LIMIT/ORDER BY/FOR UPDATE/EntityGraph JOIN/notes/count SQL）
        val enhancedSql = applyJpaMethodEnhancements(method, result.sql, table, native = false, dialectOverride = dialect)
        return buildJpaPreviewResult(
            statement = result.statement,
            convertedSql = enhancedSql,
            tableName = table?.tableName ?: "",
            paramNames = allParamNames,
            method = method,
            inClauseParams = result.inClauseParams,
            dialectOverride = dialect,
            dialectDecision = decision,
        )
    }

    /**
     * 将原生 SQL 中的命名参数 :paramName 转换为 ? 占位符。
     * 示例：`:keyword` → `?`，`CONCAT('%', :keyword, '%')` → `CONCAT('%', ?, '%')`
     */
    // T-JpaParamOrder F3：正则提常量——[extractNativeParamNames] 与替换共用同一 pattern，
    // 保证名序与替换产物 `?` 序一致
    private val NATIVE_NAMED_PARAM = Regex("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)")

    private fun convertNativeSqlParameters(nativeSql: String): String {
        return nativeSql.replace(NATIVE_NAMED_PARAM, "?")
    }

    /**
     * T-JpaParamOrder F3：原生 SQL 命名参数名序（首现序，与 [convertNativeSqlParameters]
     * 替换出的 `?` 一一对应）。位置参数 `?N` 不在内。
     */
    private fun extractNativeParamNames(nativeSql: String): List<String> =
        NATIVE_NAMED_PARAM.findAll(nativeSql).map { it.groupValues[1] }.toList()

    /**
     * T-JpaParamOrder F3：全部 `?` 均来自命名参数替换时返回名序（供按名回填），否则 null
     * 维持按序回填。位置参数混入时名序数量与 `?` 不齐，按名会把无名的 `?` 配空值——
     * 保守回退既有语义。`?` 计数用朴素 count（判定用途，非替换位点）。
     */
    internal fun usableNamedPlaceholderSources(sql: String, namedParamNames: List<String>): List<String>? =
        namedParamNames.takeIf { it.isNotEmpty() && it.size == sql.count { ch -> ch == '?' } }

    // T-JpaParamOrder F6：静态值字面量形态（数字含 Java 类型后缀 / 枚举常量引用）
    private val NUMERIC_LITERAL = Regex("-?\\d+(\\.\\d+)?[LlFfDd]?")
    private val ENUM_CONSTANT_REF = Regex("[A-Za-z_$][A-Za-z0-9_$.]*\\.[A-Z][A-Z0-9_]*")

    // T-PerfSweep24 F84：函数体内现场 Regex 提常量（本文件 18 处收敛；含 :1289 命名参数
    // 与既有 NATIVE_NAMED_PARAM 同 pattern 直接收编复用）。同型守卫＝convention.InFunctionRegexSourceScanTest。
    /** 预览 SQL 折叠空白（compactSql 构建处共用，12 处）。 */
    private val SQL_WHITESPACE_RUN = Regex("\\s+")

    /** JPA 查询内顶层分页子句探测（LIMIT/OFFSET/FETCH）。 */
    private val LIMIT_OFFSET_FETCH_CLAUSE = Regex("(?i)\\b(LIMIT|OFFSET|FETCH)\\b")

    /** JPA 查询内顶层 ORDER BY 探测。 */
    private val ORDER_BY_CLAUSE = Regex("(?i)\\bORDER\\s+BY\\b")

    /**
     * 主表别名探测：`FROM t_user u`（detectMainAlias 用）。
     * F84 附带修正：原函数体内字面量带一个多余的 `)`（unbalanced paren——PatternSyntaxException
     * 被 detectMainAlias 的 runCatching 长期吞掉，别名探测实际一直走表名兜底）；提常量后构造期
     * 即爆出，顺手摘除多余括号恢复 KDoc 声明的探测语义。
     */
    private val FROM_TABLE_ALIAS = Regex("(?i)\\bFROM\\s+[`\"(]*([\\w.]+)[`\"]*\\s+([a-zA-Z_][\\w$]*)")

    /** HQL 位置参数 `?N`（extractParamNamesFromHql 用）。 */
    private val POSITIONAL_PARAM_INDEX = Regex("\\?(\\d+)")

    /** 集合元素类型提取：`Collection<X>`/`List<X>`/`Set<X>`（参数数字面量判定用）。 */
    private val GENERIC_COLLECTION_TYPE = Regex("""(?:Collection|List|Set)<(.+)>""")

    /**
     * T-JpaParamOrder F6：setParameter 静态值求值——参数框初值自动回显。
     *
     * 用户追问「为什么这些常量没有回显」（20261006）：`setParameter("locationStatus", "E")`
     * 这类字面量/枚举常量可静态求值，此前面板一律留空、渲染全 `?`，与代码事实不符。
     *
     * 识别面（保守，宁缺勿错）：
     *  - 字符串字面量 `"E"` → E（去引号 + 简单反转义）
     *  - 数字字面量 `0` / `1L` / `0.5f` → 去 Java 类型后缀
     *  - 布尔字面量 `true` / `false`
     *  - 枚举常量引用 `Enum.CONST`（取末段；`@Enumerated(STRING)` 运行期绑定值即该名）
     * 变量引用 / 方法调用 / 任意表达式 → null（保持空待填，用户手动输入）
     */
    private fun staticParamValue(valueExpr: String): String? {
        val expr = valueExpr.trim()
        if (expr.isEmpty()) return null
        // 注意：单字符字面量（数字 `0`）合法——长度守卫只挡空串，勿写成 length < 2
        if (expr.startsWith("\"") && expr.endsWith("\"") && expr.length >= 2) {
            return expr.substring(1, expr.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        if (NUMERIC_LITERAL.matches(expr)) {
            return expr.trimEnd('L', 'l', 'F', 'f', 'D', 'd')
        }
        if (expr == "true" || expr == "false") return expr
        if (ENUM_CONSTANT_REF.matches(expr)) return expr.substringAfterLast('.')
        return null
    }

    /** T-JpaParamOrder F6：setParameter 追踪列表 → 参数框初值表（仅静态可求值项入表）。 */
    private fun staticParamValues(
        parameters: List<cn.song.mybatisplus.visualizer.psi.jpa.HqlParameter>,
    ): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        parameters.forEach { p ->
            if (p.isPositional) return@forEach
            staticParamValue(p.valueExpr)?.let { map[p.paramName] = it }
        }
        return map
    }

    /**
     * 提取 JPQL/@Query 方法的参数名。
     * 优先 @Param 注解值（@Param("foo") → "foo"），无注解时用方法参数名。
     * T-J10：Pageable/Sort/Limit 类型参数本体不登记参数行（分页占位参数行 pageSize/pageOffset 等
     * 由 [JpaQueryEnhancer.evaluatePageSort] 的 extraParamNames 补充，避免同一参数双行）。
     */
    private fun extractJpqlParamNames(method: PsiMethod): List<String> {
        val parameters = method.parameterList.parameters
        val paramAnnotationQName = "org.springframework.data.repository.query.Param"
        return parameters.mapNotNull { param ->
            val typeName = param.type.canonicalText.substringBefore('<')
            if (JpaQueryEnhancer.isPageableType(typeName) ||
                JpaQueryEnhancer.isSortType(typeName) ||
                JpaQueryEnhancer.isLimitType(typeName)
            ) {
                return@mapNotNull null
            }
            val annotation = param.modifierList?.findAnnotation(paramAnnotationQName)
                ?: param.modifierList?.annotations?.firstOrNull {
                    it.nameReferenceElement?.referenceName == "Param"
                }
            if (annotation != null) {
                val value = annotation.findAttributeValue("value") as? com.intellij.psi.PsiLiteralExpression
                value?.value as? String ?: param.name
            } else {
                param.name
            }
        }
    }

    /**
     * CriteriaQuery 预览（波次一）：定位 CriteriaQueryModel → CriteriaQueryConverter → SQL → PreviewResult。
     *
     * 供 [cn.song.mybatisplus.visualizer.ui.gutter.CriteriaQueryGutterLineMarkerProvider] 点击调用。
     * T-J10：宿主方法 Pageable/Limit 参数 → LIMIT 子句（语句模型级，进 previewModel 交互重生成）。
     */
    fun previewCriteriaQuery(anchor: PsiElement): PreviewResult? {
        // 1. 用 CriteriaQueryLocator.locate(anchor) 获取 CriteriaQueryModel
        val model = CriteriaQueryLocator.locate(anchor) ?: return null
        // 2. CriteriaQueryConverter.convert(model) → SelectStatement
        // T-TS-07：显式传本服务所属项目——实体类/join/子查询的**类名兜底推断**按该项目读取
        // 项目级表配置（原先传 null → 取「首个打开项目」，多项目同开时表名修饰错项目）
        var statement = CriteriaQueryConverter.convert(model, project = project)
        val hostMethod = PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java)
        // 3. 参数名：从包含 CriteriaQuery 的宿主方法参数列表提取
        val paramNames = hostMethod?.parameterList?.parameters?.map { it.name } ?: emptyList()
        // Criteria 源是 createQuery 所在 Java 文件；不能把生成的 SQL 伪装成 SqlFile。
        val decision = jpaSourceDecision(anchor)
        val dialect = dialectOf(decision)
        // T-J10：宿主方法分页参数 → LIMIT 子句（criteria 路径是语句模型，直接挂 limit）
        val pageSort = JpaQueryEnhancer.evaluatePageSort(hostMethod, null, dialect, null)
        if (pageSort != null) {
            val select = statement as? SelectStatement
            val limited = pageSort.limit?.let { limit -> select?.copy(limit = limit) } ?: select
            if (limited != null) statement = limited
        }
        // CriteriaQueryConverter 解析条件树，SqlGenerator 生成的 SQL 含 ? 占位符
        val sql = cn.song.mybatisplus.visualizer.sql.SqlGenerator.generate(statement, compact = false, dialect = dialect)
        val resolvedTable = model.entityClass?.let { JpaEntityResolver().resolve(it) }
        val tableName = model.entityClassName?.let { JpaEntityResolver.inferDecoratedTableName(it, project) } ?: ""
        // T-J10/J15/J22：方法级增强（Sort ORDER BY/锁/hints/返回类型 notes；LIMIT 已在语句模型级挂载，
        // 分页/排序 notes 由上方分析透传）
        val enhancedSql = applyJpaMethodEnhancements(
            hostMethod, sql, resolvedTable, native = false,
            limitAppliedInModel = true,
            pageNotes = pageSort?.notes ?: emptyList(),
            dialectOverride = dialect,
        )
        val allParamNames = paramNames + (pageSort?.extraParamNames ?: emptyList())
        return buildJpaPreviewResult(statement, enhancedSql, tableName, allParamNames, hostMethod,
            dialectOverride = dialect, dialectDecision = decision)
    }

    /**
     * Hibernate HQL 预览（session.createQuery）。
     * 供 [cn.song.mybatisplus.visualizer.ui.gutter.HqlGutterLineMarkerProvider] 点击调用。
     */
    fun previewHql(anchor: PsiElement): PreviewResult? {
        val info = HqlQueryLocator.locate(anchor) ?: return null

        // 原生 SQL：不做实体→表名映射，仅做参数替换
        // 双重检测：isNativeQuery 标志 + 方法名 fallback（防止 IDE 环境中标志未正确设置）
        if (info.isNativeQuery || info.createQueryMethodName == "createNativeQuery") {
            return previewNativeSql(info, anchor)
        }

        // T-PreviewShortcut：createNamedQuery("name") 按名回查实体 @NamedQuery，解析真实 JPQL；
        // 跨文件 / 命名不规范解析不到时优雅降级为把名字当 HQL 处理（不抛、不挂起）。
        if (info.createQueryMethodName == "createNamedQuery") {
            val namedCandidates = NamedQueryLocator.findCandidatesByQueryName(project, info.hql, info.method ?: anchor)
            if (namedCandidates.size > 1) return null
            val named = namedCandidates.singleOrNull()
            if (named != null && named.query.isNotBlank()) {
                val entityClass = named.annotation?.let { PsiTreeUtil.getParentOfType(it, PsiClass::class.java, false) }
                val table = resolveHqlTable(entityClass, null, named.query, info.method ?: anchor)
                val result = JpqlConverter.convert(named.query, table, project, context = info.method ?: anchor)
                val paramNames = extractParamNamesFromHql(named.query)
                // 命名查询以定义注解的文件为 owner，而非 createNamedQuery 的调用文件。
                val decision = jpaSourceDecision(named.annotation)
                val dialect = dialectOf(decision)
                // T-J10/J15/J22：宿主方法级增强
                val enhancedSql = applyJpaMethodEnhancements(info.method, result.sql, table, native = false, dialectOverride = dialect)
                val extraParams = JpaQueryEnhancer.evaluatePageSort(info.method, null, dialect, table)
                    ?.extraParamNames ?: emptyList()
                return buildJpaPreviewResult(
                    statement = result.statement,
                    convertedSql = enhancedSql,
                    tableName = table?.tableName ?: "",
                    paramNames = paramNames + extraParams,
                    method = info.method,
                    inClauseParams = result.inClauseParams,
                    dialectOverride = dialect,
                    dialectDecision = decision,
                )
            }
            // T-H3：Java @NamedQuery 未命中 → hbm 命名查询 fallback（hbm 纯 POJO 项目无注解可查）
            val hbm = HbmXmlEntityResolver.findNamedQuery(info.hql, project, info.method ?: anchor)
            if (hbm != null) {
                return previewHbmNamedQuery(hbm, info.method, context = info.method ?: anchor)
            }
            // 同名 hbm 定义歧义不能把首项 SQL 或查询名误当 HQL 发给用户。
            if (HbmXmlEntityResolver.hasAmbiguousNamedQuery(info.hql, project, info.method ?: anchor)) return null
        }

        val table = resolveHqlTable(info.entityClass, info.entityClassName, info.hql, info.method ?: anchor)
        // Wave1 第三参 project：激活 PSI 级 JOIN ON 与子查询实体映射
        // T-HqlInhCols：@Entity(name=...) 实体别名透传，别名形态 HQL 实体名才能被表名替换
        val result = HqlConverter.convert(
            info.hql, table, project,
            entityAlias = extractEntityNameFromHql(info.hql),
            context = info.method ?: anchor,
        )
        // 参数名提取：
        // 1. 追踪 setParameter 结果（位置参数用 valueExpr 即变量名，命名参数用 paramName）
        // 2. 若为空（链式调用场景），直接从 HQL 提取
        val paramNames = info.parameters.map { if (it.isPositional) it.valueExpr else it.paramName }.ifEmpty {
            extractParamNamesFromHql(info.hql)
        }
        val decision = jpaSourceDecision(info.method ?: anchor)
        val dialect = dialectOf(decision)
        // T-J10/J15/J22：宿主方法级增强
        val enhancedSql = applyJpaMethodEnhancements(info.method, result.sql, table, native = false, dialectOverride = dialect)
        val extraParams = JpaQueryEnhancer.evaluatePageSort(info.method, null, dialect, table)
            ?.extraParamNames ?: emptyList()
        // 关键：使用 HqlConverter 转换后的 SQL 字符串（含 ? 占位符），而非从 statement 重新生成。
        return buildJpaPreviewResult(
            statement = result.statement,
            convertedSql = enhancedSql,
            tableName = table?.tableName ?: "",
            paramNames = paramNames + extraParams,
            method = info.method,
            inClauseParams = result.inClauseParams,
            dialectOverride = dialect,
            dialectDecision = decision,
            placeholderParamNames = result.placeholderParamNames,
            duplicateParamNames = info.duplicateParamNames,
            // T-JpaParamOrder F6：字面量/枚举常量自动预填参数框（变量引用不入表，保持待填）
            initialParamValues = staticParamValues(info.parameters),
        )
    }

    /**
     * 原生 SQL 预览（createNativeQuery）。
     * 不做实体→表名映射，仅替换 :param → ? 并解析实体表名。
     * T-J10/J15：宿主方法级增强（Pageable/Limit → LIMIT、@Lock → FOR UPDATE、notes）。
     */
    private fun previewNativeSql(info: HqlQueryInfo, anchor: PsiElement): PreviewResult? {
        val sql = convertNativeSqlParameters(info.hql)
        val paramNames = info.parameters.map { if (it.isPositional) it.valueExpr else it.paramName }.ifEmpty {
            extractParamNamesFromHql(info.hql)
        }
        // 解析实体表名（用于显示）
        val tableName = resolveHqlEntityTable(info.entityClass) ?: info.entityClassName ?: ""
        val decision = jpaSourceDecision(info.method ?: anchor)
        val dialect = dialectOf(decision)
        // T-J10/J15/J22：方法级增强（native 路径：EntityGraph 仅注释说明）
        val enhancedSql = applyJpaMethodEnhancements(info.method, sql, null, native = true, dialectOverride = dialect)
        val extraParams = JpaQueryEnhancer.evaluatePageSort(info.method, null, dialect, null)
            ?.extraParamNames ?: emptyList()
        val allParamNames = paramNames + extraParams
        // T-JpaParamOrder F3：native 命名参数名序透传（全部 `?` 来自 :name 替换时启用按名回填）
        val usableNamedSources = usableNamedPlaceholderSources(enhancedSql, extractNativeParamNames(info.hql))
        return PreviewResult(
            sql = enhancedSql,
            compactSql = enhancedSql.replace(SQL_WHITESPACE_RUN, " ").trim(),
            unresolvedParams = allParamNames,
            paramTable = ParameterReplacer.buildParamTable(allParamNames),
            paramHints = (usableNamedSources ?: allParamNames).map { ParamHint(quote = !isNumericParam(info.method, it)) },
            wrapperType = null,
            tableName = tableName,
            placeholderParamNames = usableNamedSources.orEmpty(),
            duplicateParamNames = info.duplicateParamNames,
            // T-JpaParamOrder F6：native 链同样预填静态常量
            initialParamValues = staticParamValues(info.parameters),
            renderedDialect = dialect,
            dialectDecision = decision,
        )
    }

    /**
     * 解析原生 SQL 对应的实体表名。
     */
    private fun resolveHqlEntityTable(entityClass: PsiClass?): String? {
        if (entityClass == null) return null
        return JpaEntityResolver().resolve(entityClass).tableName
    }

    /**
     * 实体级 @NamedQuery/@NamedQueries 预览（T-PreviewShortcut）：
     * 从光标所在 @NamedQuery 注解反查实体类 → 定位对应命名查询 → JPQL 转 SQL。
     *
     * 供 [cn.song.mybatisplus.visualizer.ui.gutter.NamedQueryGutterLineMarkerProvider] 点击与快捷键调用。
     */
    fun previewNamedQuery(anchor: PsiElement): PreviewResult? {
        val annotation = PsiTreeUtil.getParentOfType(anchor, PsiAnnotation::class.java, false) ?: return null
        val cls = PsiTreeUtil.getParentOfType(annotation, PsiClass::class.java, false) ?: return null
        val infos = NamedQueryLocator.locate(cls)
        if (infos.isEmpty()) return null
        // 优先取光标所在那一条 @NamedQuery（注解引用匹配）；多选 fallback 取第一条
        val info = infos.firstOrNull { it.annotation === annotation } ?: infos.first()
        val jpql = info.query
        val table = resolveHqlTable(cls, null, jpql, annotation)
        val result = JpqlConverter.convert(jpql, table, project, context = annotation)
        val paramNames = extractParamNamesFromHql(jpql)
        val decision = jpaSourceDecision(info.annotation ?: annotation)
        return buildJpaPreviewResult(
            statement = result.statement,
            convertedSql = result.sql,
            tableName = table?.tableName ?: "",
            paramNames = paramNames,
            method = null,
            inClauseParams = result.inClauseParams,
            dialectOverride = dialectOf(decision),
            dialectDecision = decision,
        )
    }

    /**
     * 自定义 DAO HQL 预览（commonDao.findByQuery 等）。
     * 供 [cn.song.mybatisplus.visualizer.ui.gutter.CustomDaoHqlGutterLineMarkerProvider] 点击调用。
     *
     * T-J21：按 [CustomDaoHqlLocator] 登记的 [DaoQueryKind] 分派——
     *  - HQL（默认）：既有 HqlConverter 管道（行为不变）；
     *  - NAMED_QUERY（findByNamedQuery 族）：hql 字段存查询名——复用 NamedQueryLocator.findByQueryName
     *    按名回查实体 @NamedQuery 的真实 JPQL 再走 JpqlConverter（参考 previewNamedQuery 既有做法）；
     *    回查失败优雅降级为可读占位（不抛异常、不出错误 SQL）；
     *  - QBC（executeFind）：可读占位「QBC/DetachedCriteria 形态暂不支持静态解析」；
     *  - ENTITY_OP（get/load/save/delete 等）：locate 不认领（无 SQL 字符串），由 preview 批量分支
     *    经 JpaCrudOperationConverter 派发，不经本方法。
     */
    fun previewCustomDaoHql(anchor: PsiElement): PreviewResult? {
        val info = CustomDaoHqlLocator.locate(anchor) ?: return null
        if (info.kind == DaoQueryKind.QBC) {
            return placeholderPreview(
                "-- QBC/DetachedCriteria 形态暂不支持静态解析（条件树运行时构建）",
            )
        }
        if (info.kind == DaoQueryKind.NAMED_QUERY) {
            val namedCandidates = NamedQueryLocator.findCandidatesByQueryName(project, info.hql, info.method ?: anchor)
            if (namedCandidates.size > 1) return null
            val named = namedCandidates.singleOrNull()
            if (named != null && named.query.isNotBlank()) {
                val entityClass = named.annotation?.let { PsiTreeUtil.getParentOfType(it, PsiClass::class.java, false) }
                val table = resolveHqlTable(entityClass, null, named.query, info.method ?: anchor)
                val result = JpqlConverter.convert(named.query, table, project, context = info.method ?: anchor)
                val paramNames = extractParamNamesFromHql(named.query)
                val decision = jpaSourceDecision(named.annotation)
                return buildJpaPreviewResult(
                    statement = result.statement,
                    convertedSql = result.sql,
                    tableName = table?.tableName ?: "",
                    paramNames = paramNames,
                    method = info.method,
                    inClauseParams = result.inClauseParams,
                    dialectOverride = dialectOf(decision),
                    dialectDecision = decision,
                )
            }
            // T-H3：Java @NamedQuery 未命中 → hbm 命名查询 fallback（与 previewHql 同款）
            val hbm = HbmXmlEntityResolver.findNamedQuery(info.hql, project, info.method ?: anchor)
            if (hbm != null) {
                return previewHbmNamedQuery(hbm, info.method, context = info.method ?: anchor)
            }
            // 回查失败：优雅降级占位（跨文件/命名不规范），不出错误 SQL
            return placeholderPreview(
                "-- 命名查询 \"${info.hql}\"：未能回查到 @NamedQuery 定义（跨文件/命名不规范），无法静态解析 JPQL",
            )
        }
        val table = resolveHqlTable(null, info.entityClassName, info.hql, info.method ?: anchor)
        // 使用已解析查询根名替换别名，不再全项目二次找类；JAR 实体也保留 DAO 的模块上下文。
        val result = HqlConverter.convert(
            info.hql, table, project,
            entityAlias = extractEntityNameFromHql(info.hql),
            context = info.method ?: anchor,
        )
        val paramNames = info.parameters.map { if (it.isPositional) it.valueExpr else it.paramName }.ifEmpty {
            extractParamNamesFromHql(info.hql)
        }
        val decision = jpaSourceDecision(info.method ?: anchor)
        return buildJpaPreviewResult(
            statement = result.statement,
            convertedSql = result.sql,
            tableName = table?.tableName ?: "",
            paramNames = paramNames,
            method = info.method,
            inClauseParams = result.inClauseParams,
            dialectOverride = dialectOf(decision),
            dialectDecision = decision,
            placeholderParamNames = result.placeholderParamNames,
            // T-JpaParamOrder F6：自定义 DAO 链同样预填静态常量
            initialParamValues = staticParamValues(info.parameters),
        )
    }

    /** T-J21：可读占位预览卡（优雅降级：无 SQL 模板时给出解释性注释，不抛异常）。 */
    private fun placeholderPreview(note: String): PreviewResult = PreviewResult(
        sql = note,
        compactSql = note,
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = null,
    )

    /**
     * T-H3：hbm 命名查询预览——按种类分派：
     *  - HQL（`<query>`）→ [HqlConverter] 管道（实体表经 [resolveHqlTable] 路径 3 hbm 映射取得）；
     *  - 原生（`<sql-query>`）→ 命名参数 `:param` → `?` 替换（与 previewNativeSql 同款）。
     *
     * 供 hbm 文件 gutter（[cn.song.mybatisplus.visualizer.ui.gutter.HbmNamedQueryGutterLineMarkerProvider]）
     * 与 createNamedQuery / 自定义 DAO 回查 fallback 共用。
     */
    fun previewHbmNamedQuery(
        query: HbmXmlMapping.NamedQuery,
        hostMethod: PsiMethod? = null,
        definitionTag: com.intellij.psi.xml.XmlTag? = null,
        context: PsiElement? = definitionTag ?: hostMethod,
    ): PreviewResult? {
        if (query.queryText.isBlank()) return null
        // 命名查询的 owner 是定义 XML 标签，绝不是跨文件 createNamedQuery 调用方法。
        val owner = definitionTag ?: HbmXmlEntityResolver.findNamedQueryDefinition(query, project, context)
        val decision = jpaSourceDecision(owner)
        val dialect = dialectOf(decision)
        val paramNames = extractParamNamesFromHql(query.queryText)
        return when (query.kind) {
            HbmXmlMapping.QueryKind.NATIVE -> {
                val sql = convertNativeSqlParameters(query.queryText)
                val enhancedSql = applyJpaMethodEnhancements(hostMethod, sql, null, native = true, dialectOverride = dialect)
                // T-JpaParamOrder F3：native 命名参数名序透传（与 previewNativeSql 同款判定）
                val usableNamedSources = usableNamedPlaceholderSources(enhancedSql, extractNativeParamNames(query.queryText))
                PreviewResult(
                    sql = enhancedSql,
                    compactSql = enhancedSql.replace(SQL_WHITESPACE_RUN, " ").trim(),
                    unresolvedParams = paramNames,
                    paramTable = ParameterReplacer.buildParamTable(paramNames),
                    paramHints = (usableNamedSources ?: paramNames).map { ParamHint(quote = true) },
                    wrapperType = null,
                    tableName = null,
                    placeholderParamNames = usableNamedSources.orEmpty(),
                    renderedDialect = dialect,
                    dialectDecision = decision,
                )
            }
            HbmXmlMapping.QueryKind.HQL -> {
                val entityName = extractEntityNameFromHql(query.queryText)
                val table = resolveHqlTable(null, entityName, query.queryText, context)
                val result = HqlConverter.convert(query.queryText, table, project, entityAlias = entityName, context = context)
                val enhancedSql = applyJpaMethodEnhancements(hostMethod, result.sql, table, native = false, dialectOverride = dialect)
                buildJpaPreviewResult(
                    statement = result.statement,
                    convertedSql = enhancedSql,
                    tableName = table?.tableName ?: "",
                    paramNames = paramNames,
                    method = hostMethod,
                    inClauseParams = result.inClauseParams,
                    dialectOverride = dialect,
                    dialectDecision = decision,
                    placeholderParamNames = result.placeholderParamNames,
                )
            }
        }
    }

    /** T-H2：hbm 文件 gutter 点击入口——从 `<query>`/`<sql-query>` XmlTag 构造命名查询后预览。 */
    fun previewHbmNamedQueryTag(project: Project, tag: com.intellij.psi.xml.XmlTag): PreviewResult? {
        val name = tag.getAttributeValue("name") ?: return null
        val kind = cn.song.mybatisplus.visualizer.ui.gutter.HbmNamedQueryGutterLineMarkerProvider.kindOf(tag.name)
            ?: return null
        // 文本提取复用 resolver 的解析口径（直属 XmlText、CDATA 剥包裹、排除子标签）；
        // tag 与 findNamedQuery 同源
        val text = directTextOf(tag)
        return previewHbmNamedQuery(
            HbmXmlMapping.NamedQuery(name = name, kind = kind, queryText = text),
            definitionTag = tag,
            context = tag,
        )
    }

    /**
     * JpaSpecificationExecutor 调用预览（T-J5）：定位 anchor 所在方法调用 →
     * [SpecificationQueryConverter] 解析 Specification lambda/组合器 → SELECT/COUNT/DELETE + WHERE。
     *
     * 供 gutter 点击与 T-J9 调用点动作路径调用；不识别（非 executor 家族/非 Specification 实参）返回 null。
     */
    fun previewSpecification(anchor: PsiElement): PreviewResult? {
        val call = generateSequence(anchor) { it.parent }
            .filterIsInstance<PsiMethodCallExpression>()
            .firstOrNull() ?: return null
        val preview = SpecificationQueryConverter.convert(call, effectiveReplaceParams()) ?: return null
        val decision = jpaSourceDecision(call)
        val result = PreviewResult(
            sql = preview.sql,
            compactSql = preview.sql.replace(SQL_WHITESPACE_RUN, " ").trim(),
            unresolvedParams = preview.paramSources,
            paramTable = ParameterReplacer.buildParamTable(preview.paramSources),
            paramHints = preview.paramHints,
            wrapperType = null,
            tableName = preview.tableName,
            warning = preview.warning,
            columns = preview.columns,
            renderedDialect = dialectOf(decision),
            dialectDecision = decision,
        )
        recordHistory(result, anchor)
        return result
    }

    /**
     * QueryByExampleExecutor 调用预览（T-J20）：定位 anchor 所在方法调用 →
     * [QueryByExampleConverter] 解析 Example.of(probe[, matcher]) → probe 非空属性等值 WHERE。
     */
    fun previewQueryByExample(anchor: PsiElement): PreviewResult? {
        val call = generateSequence(anchor) { it.parent }
            .filterIsInstance<PsiMethodCallExpression>()
            .firstOrNull() ?: return null
        val preview = QueryByExampleConverter.convert(call, effectiveReplaceParams()) ?: return null
        val decision = jpaSourceDecision(call)
        val result = PreviewResult(
            sql = preview.sql,
            compactSql = preview.sql.replace(SQL_WHITESPACE_RUN, " ").trim(),
            unresolvedParams = preview.paramSources,
            paramTable = ParameterReplacer.buildParamTable(preview.paramSources),
            paramHints = preview.paramHints,
            wrapperType = null,
            tableName = preview.tableName,
            columns = preview.columns,
            renderedDialect = dialectOf(decision),
            dialectDecision = decision,
        )
        recordHistory(result, anchor)
        return result
    }

    /**
     * @Procedure 存储过程预览（T-J16）：repository 方法注解 → CALL 渲染
     * （procedureName/value 优先、方法名兜底）+ outputParameterName/name 引用标注。
     *
     * 供 gutter 点击与 T-J9 调用点动作路径调用；无 @Procedure 注解返回 null。
     */
    fun previewProcedure(method: PsiMethod): PreviewResult? {
        val info = JpaQueryEnhancer.procedureInfo(method) ?: return null
        val decision = jpaSourceDecision(method)
        val placeholders = (1..info.paramCount).joinToString(", ") { "?" }
        val sql = buildString {
            append("CALL ").append(info.procName).append('(').append(placeholders).append(')')
            info.nameRef?.let { append("\n-- @Procedure(name=\"").append(it).append("\")：引用元数据具名存储过程查询，过程名以元数据注册为准") }
            info.outputParameterName?.let { append("\n-- 输出参数：").append(it).append("（注册为 OUT 参数，执行后按名取值）") }
        }
        val result = PreviewResult(
            sql = sql,
            compactSql = sql.replace(SQL_WHITESPACE_RUN, " ").trim(),
            unresolvedParams = emptyList(),
            paramTable = null,
            wrapperType = null,
            tableName = info.procName,
            renderedDialect = dialectOf(decision),
            dialectDecision = decision,
        )
        recordHistory(result, method)
        return result
    }

    /**
     * 从 HQL 字符串中提取参数名。
     * 支持命名参数（:paramName）和位置参数（?1 → param1, ?2 → param2）。
     */
    private fun extractParamNamesFromHql(hql: String): List<String> {
        val namedParams = NATIVE_NAMED_PARAM.findAll(hql)
            .map { it.groupValues[1] }.toList()
        val positionalParams = POSITIONAL_PARAM_INDEX.findAll(hql)
            .map { "param${it.groupValues[1]}" }.toList()
        return (namedParams + positionalParams).distinct()
    }

    /**
     * JPA 预览结果构建统一入口（派生查询 / JPQL / CriteriaQuery 共用）。
     *
     * 与 MP 路径的 [buildPreviewResult] 对齐：生成 SQL + 收集动态条件视图 + 构建参数对照表，
     * 使面板支持交互式参数输入与条件 toggle。
     */
    /**
     * JPA 预览结果构建统一入口。
     *
     * @param statement    语句模型（用于 previewModel / warning 判定）
     * @param convertedSql 转换后的 SQL 字符串（含 ? 占位符，直接用于面板显示和参数回填）
     * @param tableName    表名
     * @param paramNames   参数名列表（来自方法签名）
     * @param method       方法（用于提取实体列信息）
     */
    internal fun isNumericParam(method: PsiMethod?, paramName: String): Boolean {
        if (method == null) return false
        val param = method.parameterList.parameters.firstOrNull { it.name == paramName } ?: return false
        val type = param.type
        // 提取集合/数组元素类型
        val elementType = when {
            type is com.intellij.psi.PsiClassType -> {
                val className = type.canonicalText
                // 常见集合类型：Collection<X>, List<X>, Set<X>
                val match = GENERIC_COLLECTION_TYPE.find(className)
                if (match != null) match.groupValues[1] else className
            }
            type is com.intellij.psi.PsiArrayType -> type.componentType.canonicalText
            else -> type.canonicalText
        }
        return NUMERIC_TYPES.any { elementType.endsWith(it) }
    }

    private val NUMERIC_TYPES = setOf(
        "Integer", "int", "Long", "long", "Short", "short", "Byte", "byte",
        "Float", "float", "Double", "double", "BigDecimal", "BigInteger", "Number",
    )

    /**
     * JPA 语句风险警示：DELETE/UPDATE 无 WHERE 时提示全表删除/更新（BlockAttack 风格）。
     * T-JpaDeleteWhereGuard 普查批：补 UPDATE 分支——此前只报 DELETE，JPA 链无 WHERE 的
     * bulk UPDATE 静默（MP 链 buildWarning 两种都报，不对称）；UPDATE 的 conditions 空
     * ⟺ 文本无 WHERE（JpaUpdateParser.parseWhere 对非空 WHERE 必产 Raw 兜底），无错报面。
     */
    internal fun buildJpaWarning(statement: SqlStatement): String? = when (statement) {
        is UpdateStatement -> if (statement.conditions.isEmpty()) "⚠ [BlockAttack] 无 WHERE 条件，将更新全表数据！" else null
        is DeleteStatement -> if (statement.conditions.isEmpty()) "⚠ [BlockAttack] 无 WHERE 条件，将删除全表数据！" else null
        else -> null
    }

    /** 从 SQL 字符串提取 `?` 占位符参数名（简化：param1, param2, ...）。 */
    private fun extractPlaceholders(sql: String): List<String> {
        val count = sql.count { it == '?' }
        return (1..count).map { "param$it" }
    }

    /**
     * 由语句模型构建预览结果（应用方言与格式化配置）。
     * R12 T-PaginationSim：[countSql] 非 null 时透传 [PreviewResult.countSql]（面板在主 SQL 后并列渲染）。
     */
    private fun paginationCountSql(
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
    internal fun analyzePerf(sql: String, statement: SqlStatement, dialect: Dialect = currentDialect()): List<PerfWarning>? {
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
    private fun boundAnsiQuotesOrNull(): Boolean? {
        val name = runCatching {
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSourceName()
        }.getOrNull() ?: runCatching {
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.rememberedDataSourceName(project)
        }.getOrNull() ?: return null
        val url = runCatching { DataSourceUrlProbe.urlByName(project, name) }.getOrNull() ?: return null
        return runCatching { DataSourceUrlProbe.ansiQuotesFromUrl(url) }.getOrNull()
    }

    /** 收集语句中的动态条件行（M12）：遍历 WHERE 条件树，扁平化 toggle 元数据（含嵌套 Group 内）。 */
    internal fun collectConditionViews(statement: SqlStatement): List<ConditionView> = when (statement) {
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
    private fun injectOptimisticLockCondition(table: TableInfo, statement: SqlStatement): SqlStatement {
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
    private fun processDynamicTableName(table: TableInfo, statement: SqlStatement): SqlStatement {
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
    private fun rewriteTableName(statement: SqlStatement, newTableName: String): SqlStatement = when (statement) {
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
    private fun injectDataPermissionCondition(table: TableInfo, statement: SqlStatement): SqlStatement {
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
    private fun hasInterceptorIgnoreAnnotation(anchor: PsiElement, attrName: String): Boolean = runCatching {
        val method = PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java) ?: return false
        method.modifierList.annotations?.any { annotation ->
            val simpleName = annotation.nameReferenceElement?.referenceName
            simpleName == "InterceptorIgnore" &&
                (annotation.findAttributeValue(attrName) as? PsiLiteralExpression)?.value as? Boolean == true
        } ?: false
    }.getOrDefault(false)

    /** T-M29-03：分页 LIMIT 注入——仅 SELECT 语句生效（UPDATE/DELETE 不追加，维持现状）。 */
    private fun applyPageLimit(statement: SqlStatement, pageLimit: LimitClause?): SqlStatement {
        val limit = pageLimit ?: return statement
        return when (statement) {
            is SelectStatement -> statement.copy(limit = limit)
            else -> statement
        }
    }

    /** T-M22-02：条件树是否已提及指定列（Simple/In/Between/Like/Unary/ColumnComparison 按列名匹配，Group 递归）。 */
    internal fun conditionReferencesColumn(conditions: List<Condition>, columnName: String): Boolean =
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

    /**
     * O-5：当前方言（配置未加载时默认 MySQL）；T-DialectAutoInfer：auto 开启时智能推断。
     *
     * T-TS-FixC：**方言模式（auto/manual）不在本层判定**，整段委托 [DialectRegistry.effective]，
     * 本方法只负责把宿主 [project] 传下去。为什么不像其余 effective* 那样写
     * 「项目值 ?: 全局值」两段式：方言的模式判定是**跨层**的——项目级 auto 与全局 auto 参与同一次
     * 判定（项目级 true 必须压过全局 false），而项目级手动方言名又要压过全局手动值；拆成
     * 「先在本层算 auto、再让下层算 manual」就会出现两个事实源，正是本次缺陷的成因
     * （本层按项目级判 auto=true 进 effective，下层却按全局 false 返回全局手动值）。
     * 收口后优先级语义见 [DialectRegistry.effective] 的 KDoc：模式与手动值均「项目级 > 全局」。
     */
    fun currentDialect(): Dialect = DialectRegistry.effective(project = project)

    /**
     * 是否格式化输出（true=多行缩进，false=压缩；O-5：项目级优先）。
     */
    fun formatSql(): Boolean =
        effective({ settings?.state?.formatSql ?: true },
                  { projectSettings?.effectiveFormatSql() })

    // T-SettingsTruth P4：以下 7 项直读点全部改走项目级 effective——
    // useProjectSettings 开启时项目级覆盖全局，关闭/null/空回退全局（回退语义在
    // ProjectPluginSettings.effectiveXxx 内置，此处仅透传；effectiveString 由零调用转正）。

    /** 有效参数替换开关（项目级优先，回退全局）。 */
    private fun effectiveReplaceParams(): Boolean =
        effective({ settings?.state?.replaceParams ?: true },
                  { projectSettings?.effectiveReplaceParams() })

    /** 有效逻辑删除注入开关（项目级优先，回退全局）。 */
    private fun effectiveInjectLogicDelete(): Boolean =
        effective({ settings?.state?.injectLogicDelete ?: true },
                  { projectSettings?.effectiveInjectLogicDelete() })

    /** 有效逻辑删除值（项目级优先，空回退全局）。 */
    internal fun effectiveLogicDeleteValue(): String =
        effectiveString(settings?.state?.logicDeleteValue ?: "",
                        projectSettings?.effectiveLogicDeleteValue())

    /** 有效表前缀（项目级优先，空回退全局）。 */
    private fun effectiveTablePrefix(): String =
        effectiveString(settings?.state?.tablePrefix ?: "",
                        projectSettings?.effectiveTablePrefix())

    /** 有效表后缀（项目级优先，空回退全局）。 */
    private fun effectiveTableSuffix(): String =
        effectiveString(settings?.state?.tableSuffix ?: "",
                        projectSettings?.effectiveTableSuffix())

    /** 有效表名推断策略（项目级优先，空回退全局）。 */
    private fun effectiveTableNameInference(): String =
        effectiveString(settings?.state?.tableNameInference ?: PluginSettings.INFERENCE_SNAKE,
                        projectSettings?.effectiveTableNameInference())

    /** 有效忽略字段名（项目级优先，空回退全局）。 */
    private fun effectiveIgnoredFields(): String =
        effectiveString(settings?.state?.ignoredFields ?: "",
                        projectSettings?.effectiveIgnoredFields())

    /**
     * 风险警示（F-09/F-10 + F-6 Phase C BlockAttack 增强 + DataPermission 占位提示
     * + T-UpdateSetSqlConcat SET 解析失败留痕）。
     *
     * BlockAttackInnerInterceptor 阻止无 WHERE 的 UPDATE/DELETE——现有警示已覆盖核心功能；
     * 增强：标记为 BlockAttack 风格（拦截器名称标注）+ DataPermission 运行时差异提示。
     */
    internal fun buildWarning(statement: SqlStatement): String? {
        val baseWarning = when (statement) {
            is UpdateStatement -> if (statement.conditions.isEmpty()) "⚠ [BlockAttack] 无 WHERE 条件，将更新全表数据！" else null
            is DeleteStatement -> if (statement.conditions.isEmpty()) "⚠ [BlockAttack] 无 WHERE 条件，将删除全表数据！" else null
            else -> null
        }
        // T-UpdateSetSqlConcat：SET 片段解析失败留痕（`setSql(sqlVar)` 这类纯运行期片段静态不可还原）。
        // 省略是唯一正确选择，但不能静默产出「无 SET」的非法 SQL——用户至少要看到少了什么。
        val setWarning = (statement as? UpdateStatement)
            ?.takeIf { it.unparsedSets.isNotEmpty() }
            ?.let {
                "⚠ [预览] 有 ${it.unparsedSets.size} 处 SET 片段无法静态解析，已省略：" +
                    it.unparsedSets.joinToString("；")
            }
        val dataPermissionWarning = DataPermissionPlaceholder.buildWarning(settings?.state?.dataPermissionHint ?: false)
        return listOfNotNull(baseWarning, setWarning, dataPermissionWarning).joinToString("\n").ifBlank { null }
    }

    /**
     * 查询类 Wrapper 类型（可能被删除类消费方法 `remove(wrapper)`/`delete(wrapper)` 复用为删除条件，M10）。
     */
    internal fun WrapperType.isQueryLike(): Boolean = this in setOf(
        WrapperType.LAMBDA_QUERY,
        WrapperType.QUERY,
        WrapperType.MPJ_LAMBDA,
        WrapperType.MPJ_QUERY,
        WrapperType.MPJ_LAMBDA_QUERY,
    )

    /**
     * T-M21-08：联表更新/删除 Wrapper 类型集合——`updateJoin(entity, wrapper)` / `updateJoinAndNull` /
     * `deleteJoin(wrapper)` 消费由 UpdateJoin/DeleteJoinConverter 处理（实体 SET 列近似、多表删除目标），
     * 用于 [preview] 的 updateEntityConsumed / deleteConsumed 早退分支类型守卫，新类型走
     * ConverterRegistry 分派（保留主表别名与 JOIN，不走单表实体展开 / 单表 DeleteStatement 路径）。
     */
    private val UPDATE_DELETE_JOIN_TYPES: Set<WrapperType> = setOf(
        WrapperType.UPDATE_JOIN,
        WrapperType.DELETE_JOIN,
    )

    /**
     * 查询类解析统一入口（T-M20-06 补强）：优先走 [ConverterRegistry] 注册的 converter——
     * MPJ 类型由此保留**主表别名与 JOIN**（`selectJoinCount`/`exists` 的联表过滤语义：
     * 单表 QueryWrapperParser 会静默丢弃 join 调用，INNER JOIN 的行数语义会丢失）；
     * 非 SelectStatement 结果（异常路径）回退单表解析。与 `else -> ConverterRegistry.convert` 分支同源。
     *
     * T-FS-04：显式传本服务绑定的 [project]——converter 内「关联/collection 子表实体类 PSI 不可解析」
     * 的类名兜底推断据此读项目级表配置（不再受「首个打开项目」影响）。
     */
    private fun parseQueryLike(chain: cn.song.mybatisplus.visualizer.model.WrapperChain, table: TableInfo): SelectStatement =
        (ConverterRegistry.convert(chain, table, project) as? SelectStatement) ?: QueryWrapperParser(table).parse(chain)

    /**
     * 逻辑删除注入状态（T-M20-05）：MPJ 官方六方法按源码顺序求值的结果，驱动 [injectLogicDeleteCondition]。
     *
     * @param mainActive 主表逻辑删除开关（默认开）；false 时主表 deleted 不注入 WHERE
     * @param subActive  副表（join 表）逻辑删除开关（默认开）；false 时副表 deleted 不注入
     * @param subToWhere 副表 deleted 条件位置：true → WHERE 末尾（`logicDelToWhere`）；
     *                   false（默认 / `logicDelToOn`）→ join ON 条件
     */
    data class LogicDeleteState(
        val mainActive: Boolean = true,
        val subActive: Boolean = true,
        val subToWhere: Boolean = false,
    )

    /**
     * 逻辑删除控制求值（T-M20-05，对齐 MPJ 官方六方法语义）：
     * 按**源码顺序**扫描链调用维护三态状态（链上可先关后开 / 先开后关精确控制）：
     *  - `disableLogicDel` / `enableLogicDel` → 主表逻辑删除开关（默认开）；
     *  - `disableSubLogicDel` / `enableSubLogicDel` → 副表（join 表）逻辑删除开关（默认开）；
     *  - `logicDelToOn` / `logicDelToWhere` → 副表 deleted 条件位置（ON / WHERE 末尾），**不影响主表注入**
     *    （旧实现把二者当作全局开关是错误近似，T-M20-05 已修正）。
     *
     * 非 MPJ 链不出现上述方法 → 返回全默认状态（主表/副表均注入，行为与 T-M15-07 基线一致）。
     */
    private fun evaluateLogicDeleteState(calls: List<cn.song.mybatisplus.visualizer.model.WrapperCall>): LogicDeleteState {
        var state = LogicDeleteState()
        for (call in calls) {
            state = when (call.methodName) {
                METHOD_DISABLE_LOGIC_DEL -> state.copy(mainActive = false)
                METHOD_ENABLE_LOGIC_DEL -> state.copy(mainActive = true)
                METHOD_DISABLE_SUB_LOGIC_DEL -> state.copy(subActive = false)
                METHOD_ENABLE_SUB_LOGIC_DEL -> state.copy(subActive = true)
                METHOD_LOGIC_DEL_TO_ON -> state.copy(subToWhere = false)
                METHOD_LOGIC_DEL_TO_WHERE -> state.copy(subToWhere = true)
                else -> state
            }
        }
        return state
    }

    /**
     * 解析 HQL 对应的实体表信息。
     *
     * 优先使用 entityClass（PsiClass 直接解析）；
     * 否则按 entityClassName 查找 @Entity 类（含 @Entity(name=...) 实体别名反查）；
     * 最后从 HQL 的 FROM 子句提取类名查找 / hbm 映射兜底。
     *
     * T-HqlInhCols：列空结果不再直接短路——JpaEntityResolver 异常路径返回
     * 非空 TableInfo 但列为空（如继承链解析失败/抽象父类映射异常），若在此短路
     * 会跳过 hbm 兜底与别名反查，下游展开器必出「列信息未解析」降级注释；
     * 判据改为「非 null 且列非空」，列空时继续走后续路径。
     */
    internal fun findClassByEntityName(
        project: com.intellij.openapi.project.Project,
        entityName: String,
        scope: com.intellij.psi.search.GlobalSearchScope,
        context: PsiElement? = null,
    ): PsiClass? = cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep.resolve(project, entityName, context)
        .psiClass?.takeIf { candidate ->
            candidate.containingFile?.virtualFile?.let { scope.contains(it) } == true
        }

    /**
     * 从 HQL 提取实体类名（T-HqlUpdateEntityMap S1 起委托共享提取器）。
     * 适用于 createQuery(HQL) 单参数场景（无 Entity.class 二参）；
     * 语句头优先口径——UPDATE/DELETE 头同样命中，不再只认 FROM 子句。
     */
    internal fun extractEntityNameFromHql(hql: String): String? {
        return HqlEntityNameExtractor.extract(hql)
    }

    /**
     * 将 HBM XML 映射转换为 TableInfo（供 HqlConverter 使用）。
     */
    internal fun convertHbmMappingToTableInfo(mapping: HbmXmlMapping.ClassMapping): TableInfo {
        val columns = mapping.properties.map { prop ->
            ColumnInfo(
                fieldName = prop.fieldName,
                columnName = prop.columnName,
                isId = prop.isId,
            )
        }
        val associations = mapping.associations.map { assoc ->
            // 从全限定名提取简单类名作为目标表名（实际应用中应解析目标类的映射）
            val targetSimpleName = assoc.targetClassName.substringAfterLast('.')
            AssociationInfo(
                fieldName = assoc.fieldName,
                joinColumn = assoc.joinColumn,
                targetTable = targetSimpleName,
                targetAlias = targetSimpleName.replaceFirstChar { it.lowercaseChar() },
            )
        }
        return TableInfo(
            entityClassName = mapping.entityClassName,
            tableName = mapping.tableName,
            columns = columns,
            associations = associations,
        )
    }

    /**
     * 按短类名查找 PsiClass（JavaPsiFacade.findClass 需要全限定名）。
     * 复用共享消歧规则；不把索引顺序当优先级。
     */
    internal fun findClassByShortName(
        project: com.intellij.openapi.project.Project,
        shortName: String,
        scope: com.intellij.psi.search.GlobalSearchScope,
        context: PsiElement? = null,
    ): PsiClass? = cn.song.mybatisplus.visualizer.psi.EntityClassResolver.resolveClassByName(project, shortName, context)
        ?.takeIf { candidate -> candidate.containingFile?.virtualFile?.let { scope.contains(it) } == true }

    /**
     * 清空缓存（编辑器文件切换/项目关闭时由持有方调用）。
     */
    fun clearCache() = cache.clear()

    /** Wrapper 解析缓存条目数（设置页缓存统计，PluginCacheManager 调用）。 */
    fun wrapperCacheEntryCount(): Int = cache.size()

    /** Wrapper 解析缓存大小估算（设置页缓存统计，PluginCacheManager 调用）。 */
    fun wrapperCacheSizeBytes(): Long = cache.sizeBytes()

    /**
     * F-7：预览完成后自动记录历史。
     *
     * 从锚点元素提取来源文件名/行号，与预览结果一并写入 [SqlHistoryManager]。
     * 尝试从 [SqlLogManager] 最近日志条目中匹配相同 SQL 指纹补全执行耗时。
     * 服务不可用时静默跳过（不阻塞预览）。
     */
    private fun recordHistory(result: PreviewResult, anchor: PsiElement) {
        runCatching {
            val manager = SqlHistoryManager.getInstance()
            manager.maxSize = settings?.state?.historyMaxSize ?: SqlHistoryManager.DEFAULT_MAX_SIZE
            val file = anchor.containingFile
            val sourceFile = file?.name ?: ""
            val sourceLine = anchor.textRange.startOffset.let { offset ->
                file?.text?.let { text ->
                    text.substring(0, offset.coerceAtMost(text.length)).count { it == '\n' } + 1
                } ?: 0
            }
            // 尝试从最近日志条目补全耗时（SQL 指纹匹配；R54 两级化——exact 优先＋结构指纹兜底）
            // T-ST-02b2：读路径经 sqlLogManagerIfCreated（永不发起创建）；取不到即不做耗时补全（等同未命中）
            val executionTimeMs = backfillExecutionTimeMs(
                sqlLogManagerIfCreated(anchor.project)?.entries?.snapshot().orEmpty(),
                result.sql,
            )
            // T-PresetMethodScope：方法身份随结果与历史条目落库——面板据此算带作用域的缓存键，
            // R51 历史快照回落按同一口径匹配条目。
            val originScopeKey = runCatching {
                cn.song.mybatisplus.visualizer.psi.PreviewOriginScope.of(
                    PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java, false),
                    anchor.project,
                )
            }.getOrDefault("")
            result.originScopeKey = originScopeKey
            result.historyEntryId = manager.add(
                SqlHistoryEntry(
                    sql = result.sql,
                    compactSql = result.compactSql,
                    timestamp = System.currentTimeMillis(),
                    sourceFile = sourceFile,
                    sourceLine = sourceLine,
                    tableName = result.tableName ?: "",
                    executionTimeMs = executionTimeMs,
                    projectIdentity = cn.song.mybatisplus.visualizer.history.ExplainPlanKey.projectIdentity(project.basePath),
                    originScopeKey = originScopeKey,
                ),
            ).id
        }
    }

    companion object {
        internal val LOG = Logger.getInstance(SqlPreviewService::class.java)

        /**
         * R54（O-108）：历史耗时回填匹配键两级化——exact 命中优先（原全文精确匹配，现行为
         * 零回退），未命中（或命中条目无耗时值）改比 [LogSqlEntry.structureKey]
         * （[structureFingerprint]：字面量/数字参数化，`?` 占位与实值归一同 token——V81①
         * 判实在案）取同键**中位数**兜底（V81③：同模板循环 N 次时首条可能是冷启动离群值，
         * 中位数取样代表性优于 firstOrNull；无耗时的同键条目不参与取样）。
         * O-108 根因：10.6 起预览默认空参填 `?`，全文匹配对实值日志必失配——耗时列默认
         * 配置恒空。
         */
        internal fun backfillExecutionTimeMs(entries: List<LogSqlEntry>, previewSql: String): Long? {
            entries.firstOrNull { it.restoredSql == previewSql }?.executionTimeMs?.let { return it }
            val key = structureFingerprint(previewSql)
            val durations = ArrayList<Long>()
            for (e in entries) {
                val duration = e.executionTimeMs ?: continue
                if (e.structureKey == key) durations.add(duration)
            }
            if (durations.isEmpty()) return null
            durations.sort()
            val n = durations.size
            return if (n % 2 == 1) durations[n / 2] else (durations[n / 2 - 1] + durations[n / 2]) / 2L
        }

        /**
         * T-PreviewFast P7：最近一次预览请求的起始时刻（点击/快捷键入口记时）。三段耗时的锚点：
         * 请求 → 弹窗（[showResult] 记）→ 控制台可见（面板 embedConsole 记）。仅诊断用，
         * 无锁 volatile 读写，取不到 0 即忽略。
         */
        @Volatile
        var lastPreviewRequestMs: Long = 0L
            internal set

        /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

        /** 逻辑删除控制方法名（T-M20-05，MPJ 官方六方法；单一来源，评估逻辑 [evaluateLogicDeleteState] 使用）。 */
        private const val METHOD_DISABLE_LOGIC_DEL = "disableLogicDel"
        private const val METHOD_ENABLE_LOGIC_DEL = "enableLogicDel"
        private const val METHOD_DISABLE_SUB_LOGIC_DEL = "disableSubLogicDel"
        private const val METHOD_ENABLE_SUB_LOGIC_DEL = "enableSubLogicDel"
        private const val METHOD_LOGIC_DEL_TO_ON = "logicDelToOn"
        private const val METHOD_LOGIC_DEL_TO_WHERE = "logicDelToWhere"

        /** 默认租户列名（T-TenantLine）：tenantColumnName 空白时回退此值。 */
        internal const val DEFAULT_TENANT_COLUMN = "tenant_id"

        /** SQL 子句关键字（detectMainAlias 探测主表别名时排除的关键字形态）。 */
        private val SQL_CLAUSE_KEYWORDS = setOf(
            "WHERE", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "JOIN", "ON", "CROSS",
            "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "FETCH", "UNION", "SET", "AS",
        )

        /**
         * 统一展示入口（M10-03）：恒以弹窗（[SqlPreviewDialog]）展示预览结果，
         * 供 [cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction] 与 gutter 图标点击共用（M11 起收敛为弹窗）。
         *
         * T-RefreshDbSteal F2：弹窗单例化——同项目已有预览弹窗时先 dispose 旧弹窗再开新。
         * 旧实现每次调用都 new 一个 MODELESS 弹窗且从不关闭，多弹窗各自持有 rehost 监听，
         * 互相抢占插件级共享控制台（真机 6 连抢根因）；单例化后同一时刻每个项目至多一个
         * 预览弹窗存活，旧面板的监听随 dispose 显式移除（F3），僵尸源头消灭。
         * 代价：旧弹窗的滚动/尺寸状态不保留（新弹窗重新初始化，尺寸走 DimensionService 记忆）。
         *
         * @param result null 表示未找到可转换的 Wrapper → 弹友好提示（不抛异常）
         * @param sourceLocation 当前代码位置（文件名:行号），来源列默认值；null 表示未知
         */
        fun showResult(
            project: Project,
            result: PreviewResult?,
            sourceLocation: String? = null,
            sourceContext: cn.song.mybatisplus.visualizer.context.PreviewSourceContext? = null,
        ) {
            // T-PreviewFast P7：请求→弹窗耗时（第二段锚点；后续控制台可见由面板记）
            runCatching {
                val req = lastPreviewRequestMs
                if (req > 0 && result != null) {
                    LOG.info("[T-PreviewFast] request→dialog: ${System.currentTimeMillis() - req}ms")
                }
            }
            if (result == null) {
                // T-UiNative T-UN-02：未找到可转换 Wrapper 提示改轻通知（原模态弹窗——纯告知无需打断）；
                // 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）
                if (!ApplicationManager.getApplication().isUnitTestMode) {
                    runCatching {
                        Notification(
                            NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("action.notFound.title"),
                            MpSqlVisualizerBundle.message("action.notFound.message"),
                            NotificationType.INFORMATION,
                        ).notify(project)
                    }
                }
                return
            }
            // 关旧开新：旧弹窗 dispose 级联释放面板（rehost 监听随之移除），再开新弹窗
            val previous = activePreviewDialogs[project]?.get()
            if (previous != null) {
                if (previous.isDisposed) {
                    LOG.info("[M36] preview dialog singleton: previous dialog already disposed")
                } else {
                    LOG.info("[M36] preview dialog singleton: closing previous dialog (id=${System.identityHashCode(previous)})")
                    runCatching { previous.doDispose() }
                }
            }
            val dialog = SqlPreviewDialog(project, result, sourceLocation, sourceContext)
            activePreviewDialogs[project] = java.lang.ref.WeakReference(dialog)
            dialog.show()
        }

        /**
         * T-RefreshDbSteal F2：项目 → 当前存活的预览弹窗（弱引用——弹窗被用户关闭后不阻回收）。
         *
         * 券03（T-PerfSweep28-03，R28-4）：键改弱引用 + 同步包装——原 ConcurrentHashMap 以 Project
         * 强引用为键且无项目关闭摘除，每弹过一次预览的项目在长会话里永久驻留一个 Project 对象
         * （值已是弱引用，只剩键这一面）。项目存活期有强引用不会误回收；关闭后随 GC 摘除。
         * 访问点仅 put/get 三处，全在预览链 EDT 路径，同步包装开销可忽略。
         */
        private val activePreviewDialogs: MutableMap<Project, java.lang.ref.WeakReference<SqlPreviewDialog>> =
            java.util.Collections.synchronizedMap(
                java.util.WeakHashMap<Project, java.lang.ref.WeakReference<SqlPreviewDialog>>(),
            )

        /** T-RefreshDbSteal F2：测试访问器——当前项目登记的存活预览弹窗（单例化断言用）。 */
        internal fun activePreviewDialogForTest(project: Project): SqlPreviewDialog? =
            activePreviewDialogs[project]?.get()

        /**
         * 后台线程 + 读操作执行转换，EDT 回调结果。
         *
         * PSI 定位/解析（含注解解析）在 IDEA 2025.3+ 属于"慢操作"，禁止在 EDT 上执行；
         * 按钮/快捷键/编辑器跟随等 UI 触发方统一走此入口，避免
         * "Slow operations are prohibited on EDT"。
         *
         * @param project  项目
         * @param compute  在 ReadAction 内执行转换（锚点元素可先在 EDT 捕获，块内读取）
         * @param onResult 在 EDT 上回调转换结果（未找到 Wrapper 时为 null）
         */
        @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
        fun previewAsync(project: Project, compute: () -> PreviewResult?, onResult: (PreviewResult?) -> Unit) {
            // T-PreviewFast P7：三段耗时埋点起点（点击/快捷键触发即记时）——真机排障时直接给出
            // 「请求→弹窗」「弹窗→控制台可见」两段数字，取代靠多条阶段日志手工推时序
            lastPreviewRequestMs = System.currentTimeMillis()
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = try {
                    ReadAction.compute<PreviewResult?, Throwable> { compute() }
                } catch (e: Throwable) {
                    // T-FatalPassthrough T-FP-02b（独立复核条件项，收口原豁免）：本处是**预览主链**
                    // （点击/快捷键 → PSI 转换 → 弹窗），不是「可选能力探测」——原豁免理由把主链写成
                    // 探测，属借豁免放走真该收口的站点。fatal（VME/LinkageError/ThreadDeath）透传给池
                    // 线程平台处理器（原状：任何失败都返回 null，用户只看到「未找到可转换的 Wrapper」
                    // 提示，真实异常零日志且归因错误——真机排障无入口）。
                    // 非 fatal 仍返回 null（既有降级语义逐字不变），但补一条 WARN 留排查锚点。
                    if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(e)) throw e
                    LOG.warn("[M36] previewAsync compute FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
                // T-ExecSlowOps F2：确定性 SQL（无参数开关/批量/XML 模型）预览成功即后台预热
                // exec 临时文件（写盘→refresh→方言映射→findFile→填缓存槽），弹窗打开后
                // prepareDefaultConsole 命中缓存，EDT 冷路径（refresh+findFile 慢操作 SEVERE 本体）
                // 退化为兜底。独立池线程 fire-and-forget——预热含 VFS refresh 有界耗时，不得推迟
                // 弹窗；带参/批量/XML 预览 SQL 随面板勾选变化，预热内容不可信，跳过。
                if (result != null && result.previewModel == null && result.batchModel == null &&
                    result.xmlModel == null && result.fillClauses.isNullOrEmpty() &&
                    cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable()
                ) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        runCatching {
                            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.prewarmExecTempFile(
                                project, result.sql, result.effectiveDialect?.name,
                            )
                        }.onFailure {
                            // T-FatalPassthrough T-FP-02b（独立复核阻断 2 同族）：本 runCatching 包住整条
                            // 预热链——不放行 fatal 会让 DataSourceSelector 预热链的 6 处透传（缓存命中
                            // 刷新 / 写盘 / pre-refresh 收敛 / refresh / syncVf / findPsi）全部作废。
                            // 非 fatal 仍 warn 降级（fire-and-forget 语义不变）；fatal 透传到池线程
                            // 平台处理器记录现场。
                            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(it)) throw it
                            LOG.warn("[M36] exec prewarm dispatch failed: ${it.javaClass.simpleName}: ${it.message}")
                        }
                    }
                }
                ApplicationManager.getApplication().invokeLater { onResult(result) }
            }
        }
    }
}

