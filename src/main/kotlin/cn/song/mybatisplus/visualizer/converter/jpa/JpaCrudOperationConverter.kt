package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.BatchKind
import cn.song.mybatisplus.visualizer.model.BatchModel
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator
import cn.song.mybatisplus.visualizer.psi.PreviewReceiverResolver
import cn.song.mybatisplus.visualizer.psi.currentDialect
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator
import cn.song.mybatisplus.visualizer.psi.jpa.DaoQueryKind
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.RepositoryBaseTypes
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParamQuote
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable

/**
 * JPA CRUD / 写路径转换器（T-J1 Spring Data CRUD + T-J6 EntityManager 写/单查 + T-J21 HibernateTemplate 实体操作）。
 *
 * 参照 [cn.song.mybatisplus.visualizer.psi.BatchOperationConverter] 的结构（方法 → Operation → SQL 模板构建），
 * 但**实体解析恒走 [JpaEntityResolver]**（@Table/@Column/@GeneratedValue 注解语义）——
 * MP 侧 [cn.song.mybatisplus.visualizer.psi.EntityResolver] 只认 @TableName，JPA 实体会被
 * 输出成「类名驼峰推断表 + 字段名当列名」的错误 SQL（J1 修复点：`UserEntity@Table(name="t_user")`
 * 曾被输出成 `INSERT INTO user_entity`）。
 *
 * ## 消歧规则（receiver 家族判定优先级，J1 最大风险点）
 *
 * 撞名方法（`save`/`deleteById`/`getById`/`count` 等通用名）在 MP 与 JPA 语境共存，派发以
 * **接收者（qualifier）类型**为唯一事实源，优先级从高到低：
 *
 *  1. **SPRING_DATA_REPOSITORY**：qualifier 变量/表达式类型沿父类型链找到 Spring Data Repository
 *     基类（[REPOSITORY_BASE_TYPES]，与 DerivedMethodLocator 名单对齐）→ 本转换器 J1 语义；
 *  2. **JPA_ENTITY_MANAGER**：qualifier 为 EntityManager/Session 家族（复用
 *     [PreviewReceiverResolver.classify]：变量名 em/session 或以 entityManager 结尾的通配形态、
 *     类型 EntityManager/Session、getSession()/getEntityManager() 方法调用）→ J6 语义（仅 EM 方法名单内）；
 *  3. **HIBERNATE_TEMPLATE**：qualifier 为 HibernateTemplate 家族（同上）→ T-J21 语义
 *     （按 [CustomDaoHqlLocator.classifyHibernateTemplateMethod] 类别：ENTITY_OP 认领、
 *     QBC 占位、HQL/NAMED_QUERY 返回 null 交回 [cn.song.mybatisplus.visualizer.ui.SqlPreviewService.previewCustomDaoHql]）；
 *  4. 以上均不命中（MP service/mapper、CUSTOM_DAO、UNKNOWN）→ 返回 null，
 *     **维持 MP 批量路径现状**（[cn.song.mybatisplus.visualizer.psi.BatchOperationConverter]）。
 *
 * 解析失败应回退「不识别」而非套错模板——宁可无图标，不可出错误 SQL：
 * 普通类 `userRepository` 变量（无 Spring Data 基类）`save(...)` → null；`list.selectAll()` → null。
 * `save` 等撞名方法在 receiver 无法判定时由 MP 路径兜底（对存量 MP 用户零行为变化）。
 *
 * ## INSERT 模板 Hibernate 语义（J14 基础形态）
 *
 *  - `@GeneratedValue(IDENTITY)` 主键列**不进 INSERT 列清单**（数据库自增）；
 *  - `SEQUENCE` / `TABLE` → 主键列保留 `?` 占位 + 注释说明 INSERT 前置取值（select nextval / 序列表 hi/lo）；
 *  - `@Column(insertable = false)` 列不进 INSERT 列清单；
 *  - 每列参数行登记 `实体变量.列名`（对齐 MP 批量面板惯例，列可勾选重建）。
 *
 * 优雅降级：全程不抛异常，方法不匹配 / 实体不可解析 / 主键缺失（按 id 语义操作时）一律返回 null。
 */
object JpaCrudOperationConverter {

    // ------------------------------------------------------------------
    // 结果模型
    // ------------------------------------------------------------------

    /**
     * JPA CRUD 预览结果（与 [cn.song.mybatisplus.visualizer.model.BatchPreview] 同构，
     * 增加 [warning]——deleteAll 等全表操作对齐 MP BlockAttack 警示通道）。
     */
    data class JpaCrudPreview(
        val methodName: String,
        val tableName: String,
        val sql: String,
        val paramGroups: String? = null,
        val paramSources: List<String> = emptyList(),
        val paramHints: List<ParamHint> = emptyList(),
        val warning: String? = null,
        val batchModel: BatchModel? = null,
    )

    /** 单个 `?` 的参数登记（source + 回填引号提示），语义同 BatchOperationConverter.ParamEntry。 */
    private data class ParamEntry(val source: String, val hint: ParamHint)

    /** 模板构建中间产物（SQL + 参数登记 + 语义注释 + 可选列勾选模型）。 */
    private data class Build(
        val sql: String,
        val params: List<ParamEntry> = emptyList(),
        val paramGroups: String? = null,
        val notes: List<String> = emptyList(),
        val warning: String? = null,
        val model: BatchModel? = null,
    )

    // ------------------------------------------------------------------
    // receiver 家族与方法名单
    // ------------------------------------------------------------------

    /** receiver 家族（消歧判定结果）。 */
    private enum class Family { SPRING_DATA_REPOSITORY, JPA_ENTITY_MANAGER, HIBERNATE_TEMPLATE }

    /**
     * Spring Data Repository 基类全限定名（SD6 起统一走 [RepositoryBaseTypes] 名单：
     * 含 3.0 List* 变体与 R2dbcRepository / ReactiveCrudRepository / ReactiveSortingRepository）。
     * superType 匹配用 canonicalText 前缀（真实 IDE 解析态）；未解析态（LightPlatform/跨模块）按
     * 简名后缀兜底（[RepositoryBaseTypes.SIMPLE_NAMES]）。
     */
    private val REPOSITORY_BASE_TYPES: Set<String> = RepositoryBaseTypes.FQNS

    /** Spring Data 基类简单名（canonicalText 前缀匹配失败时的兜底集合）。 */
    private val REPOSITORY_BASE_SIMPLE_NAMES: Set<String> = RepositoryBaseTypes.SIMPLE_NAMES

    /** T-J1：Spring Data Repository CRUD 方法名单（锚点 + 转换共用）。 */
    private val REPOSITORY_METHODS: Set<String> = setOf(
        "save", "saveAll", "saveAndFlush", "saveAllAndFlush",
        "findById", "getOne", "getById", "getReferenceById",
        "findAll", "findAllById", "existsById", "count",
        "deleteById", "delete", "deleteAllById", "deleteAll",
        "deleteAllInBatch", "deleteAllByIdInBatch", "deleteInBatch",
        "flush",
    )

    /** T-J6：EntityManager 写/单查方法名单（persist/merge/remove/find/getReference/refresh/flush + 存储过程）。 */
    private val ENTITY_MANAGER_METHODS: Set<String> = setOf(
        "persist", "merge", "remove", "find", "getReference", "refresh", "flush",
        "createStoredProcedureQuery", "createNamedStoredProcedureQuery",
    )

    /**
     * T-J21：HibernateTemplate 实体操作方法名（镜像 CustomDaoHqlLocator 登记名单 + executeFind；
     * 唯一事实源是 [CustomDaoHqlLocator.classifyHibernateTemplateMethod]，转换时以 classify 结果为准）。
     */
    private val HIBERNATE_TEMPLATE_METHODS: Set<String> = setOf(
        "save", "saveOrUpdate", "update", "delete", "deleteAll",
        "get", "load", "loadAll", "refresh", "merge", "persist", "replicate", "lock",
        "findByExample", "findByCriteria", "executeFind",
    )

    /** 全表删除警示（对齐 buildJpaWarning BlockAttack 文案）。 */
    private const val BLOCK_ATTACK_WARNING = "⚠ [BlockAttack] 无 WHERE 条件，将删除全表数据！"

    /**
     * 与 Wrapper 链工厂/链方法撞名、场景 C 短路后锚点不可达的方法名
     * （对齐 [cn.song.mybatisplus.visualizer.psi.WrapperLocator.wrapperFactoryMethodNames]
     * 与本转换器名单的交集：update / delete）。
     */
    private val WRAPPER_FACTORY_COLLIDING_NAMES: Set<String> = setOf("update", "delete")

    /**
     * JPA CRUD / EM / HibernateTemplate 方法名并集，剔除与 Wrapper 链工厂/链方法撞名者
     * （[cn.song.mybatisplus.visualizer.psi.WrapperLocator.wrapperFactoryMethodNames] 的
     * `update`/`delete`——WrapperAnchorDetector 场景 C 按工厂名单短路，二者即便入锚点名单也
     * 永远不可达，刻意剔除避免误导；转换入口 [convert] 不受影响，`ht.update/ht.delete` 经
     * ConvertToSqlAction 右键/快捷键仍可预览，仅无 gutter 图标——宁可漏标不可误标的文档化边界）。
     *
     * **撞名面大（save/count/find 等通用名），锚点必须叠加 receiver 家族判定**
     * （[hasJpaCrudReceiver]），单纯按名单亮图标会误亮 `stream.count()` 等海量负例。
     */
    val crudAnchorMethodNames: Set<String> =
        (REPOSITORY_METHODS + ENTITY_MANAGER_METHODS + HIBERNATE_TEMPLATE_METHODS) -
            WRAPPER_FACTORY_COLLIDING_NAMES

    // ------------------------------------------------------------------
    // 公开 API
    // ------------------------------------------------------------------

    /**
     * receiver 是否为可识别、且**该方法名可被本转换器认领**的 JPA CRUD 家族
     * （Spring Data Repository / EntityManager / HibernateTemplate）。
     *
     * [cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector] 场景 E 锚点判定用：常规批量方法
     * 名单锚点是纯方法名（容忍 gutter 正例宽松、preview 兜底 null），JPA CRUD 撞名面大
     * （`stream.count()` / `map.get(k)` 等），锚点即须做 receiver 判定——qualifier 变量一次
     * resolve + 类型父链文本匹配（BATCH_CHAIN_COLLISION_NAMES 同款先例，微秒级）。
     * **家族 × 方法名双重判定**：`hibernateTemplate.find(hql)` 家族命中但 find 归 HQL 承载
     * （CustomDaoHqlLocator 管道）→ false 不亮图标，避免「点击无结果」的死图标。
     * 判定失败返回 false——宁可无图标，不可误亮。
     */
    fun hasJpaCrudReceiver(call: PsiMethodCallExpression): Boolean = runCatching {
        val methodName = call.methodExpression.referenceName ?: return@runCatching false
        val family = resolveFamily(call) ?: return@runCatching false
        when (family) {
            Family.SPRING_DATA_REPOSITORY -> methodName in REPOSITORY_METHODS
            Family.JPA_ENTITY_MANAGER -> methodName in ENTITY_MANAGER_METHODS
            Family.HIBERNATE_TEMPLATE -> methodName in HIBERNATE_TEMPLATE_METHODS
        }
    }.getOrDefault(false)

    /**
     * 转换 JPA CRUD / EntityManager 写路径 / HibernateTemplate 实体操作调用。
     *
     * @param call             方法调用表达式
     * @param resolveVariables 变量求值开关（settings.replaceParams）：false 时跳过实参静态求值，
     *                         一律 `?` + 参数行（对齐 MP 批量路径语义）
     * @return 预览结果；receiver 非 JPA 家族 / 方法不匹配 / 实体不可解析时返回 null（交回 MP 路径）
     */
    fun convert(call: PsiMethodCallExpression, resolveVariables: Boolean = true): JpaCrudPreview? = runCatching {
        convertInternal(call, resolveVariables)
    }.getOrNull()

    /**
     * F-1 修复：**声明行**入口——光标落在 Repository 接口方法声明上时预览（无调用表达式，
     * [convert] 永不认领）。
     *
     * 缺口形态（2026-10-12 演示录制中发现，诊断报告 `docs/调研报告/20261012-0900-*`）：
     * `deleteAllInBatch()` 这类 bulk 形态在派生查询线（不含 `By`、`*All` 词干白名单不放行 `InBatch`）
     * 与 CRUD 转换线（只收 PsiMethodCallExpression）**双缝隙**下零可达——而模板在转换层是完整的。
     *
     * 语义：声明行无实参可静态求值，一律按 `?` 占位呈现（与 `resolveVariables=false` 同口径）；
     * 非 Repository 接口 / 方法名不在本线 / 实体不可解析时返回 null（负例守卫，交回既有回落链）。
     */
    fun convertRepositoryDeclaration(method: PsiMethod, resolveVariables: Boolean = true): JpaCrudPreview? =
        runCatching {
            val iface = method.containingClass ?: return@runCatching null
            if (!iface.isInterface) return@runCatching null
            val methodName = method.name
            if (methodName !in REPOSITORY_METHODS) return@runCatching null
            val entityClass = resolveRepositoryEntityFromClass(iface) ?: return@runCatching null
            val table = JpaEntityResolver().resolve(entityClass)
            if (table.entityClassName.isBlank()) return@runCatching null
            val dialect = currentDialect(method.project)
            val idColumn = table.columns.firstOrNull { it.isId }
            val build = buildRepositoryMethod(
                methodName, table, idColumn, emptyArray(), "", dialect, resolveVariables,
            ) ?: return@runCatching null
            previewOf(methodName, table, build)
        }.getOrNull()

    // ------------------------------------------------------------------
    // 派发（receiver 家族 → 各语义线）
    // ------------------------------------------------------------------

    private fun convertInternal(call: PsiMethodCallExpression, resolveVariables: Boolean): JpaCrudPreview? {
        val methodName = call.methodExpression.referenceName ?: return null
        return when (resolveFamily(call) ?: return null) {
            Family.SPRING_DATA_REPOSITORY -> convertRepositoryCall(call, methodName, resolveVariables)
            Family.JPA_ENTITY_MANAGER -> convertEntityManagerCall(call, methodName, resolveVariables)
            Family.HIBERNATE_TEMPLATE -> convertHibernateTemplateCall(call, methodName, resolveVariables)
        }
    }

    /**
     * receiver 家族判定（优先级见类 KDoc）。
     * 1. Spring Data Repository：qualifier 类型父链含 Spring Data 基类（authoritative）；
     * 2. EntityManager / HibernateTemplate：复用 [PreviewReceiverResolver] 既有家族分类。
     */
    private fun resolveFamily(call: PsiMethodCallExpression): Family? {
        val qualifier = call.methodExpression.qualifierExpression ?: return null
        receiverClassType(qualifier)?.let { type ->
            if (isSpringDataRepositoryType(type)) return Family.SPRING_DATA_REPOSITORY
        }
        return when (PreviewReceiverResolver.classify(call)) {
            PreviewReceiverResolver.Family.JPA_SESSION -> Family.JPA_ENTITY_MANAGER
            PreviewReceiverResolver.Family.HIBERNATE_TEMPLATE -> Family.HIBERNATE_TEMPLATE
            else -> null
        }
    }

    /** qualifier 表达式的接收者类型（变量优先解析声明类型，方法调用 qualifier 用返回类型）。 */
    private fun receiverClassType(qualifier: PsiExpression): PsiClassType? = when (qualifier) {
        is PsiReferenceExpression ->
            (qualifier.resolve() as? PsiVariable)?.type as? PsiClassType ?: qualifier.type as? PsiClassType
        else -> qualifier.type as? PsiClassType
    }

    /** 类型（自身或父类型链）是否为 Spring Data Repository 基类（canonicalText 前缀 → 简名兜底）。 */
    private fun isSpringDataRepositoryType(type: PsiClassType): Boolean {
        val direct = type.canonicalText?.substringBefore('<') ?: return false
        if (direct in REPOSITORY_BASE_TYPES || direct in REPOSITORY_BASE_SIMPLE_NAMES) return true
        val clazz = type.resolve() ?: return false
        for (superType in clazz.superTypes) {
            val canonical = superType.canonicalText ?: continue
            val simple = canonical.substringBefore('<').substringAfterLast('.')
            if (REPOSITORY_BASE_TYPES.any { canonical.startsWith(it) } || simple in REPOSITORY_BASE_SIMPLE_NAMES) {
                return true
            }
        }
        return false
    }

    /**
     * Spring Data Repository 泛型实体解析：沿父类型链找 Spring Data 基类 superType 的**第一个**
     * 类型参数（JpaRepository<Entity, ID> → Entity）；父链不可解析时回退变量类型自身泛型参数。
     * 解析失败返回 null（负例守卫：不识别）。
     */
    private fun resolveRepositoryEntity(call: PsiMethodCallExpression): PsiClass? {
        val varType = receiverClassType(call.methodExpression.qualifierExpression ?: return null) ?: return null
        return resolveRepositoryEntityFromClass(varType.resolve(), varType)
    }

    /**
     * F-1 修复：从 **PsiClass** 解析 Repository 实体（声明行入口复用）。
     *
     * 与调用点同一条严格判定：沿 superType 找 Spring Data 基类（[REPOSITORY_BASE_TYPES] /
     * [REPOSITORY_BASE_SIMPLE_NAMES]）的第一个类型参数；[fallbackType] 非空时再回退其自身
     * 第一个泛型参数（变量形态）。**非 Repository 接口返回 null**——声明行入口据此做负例守卫，
     * 不会把普通接口方法（如 `stream.count()`）误认成 CRUD。
     */
    private fun resolveRepositoryEntityFromClass(clazz: PsiClass?, fallbackType: PsiClassType? = null): PsiClass? {
        if (clazz != null) {
            for (superType in clazz.superTypes) {
                val canonical = superType.canonicalText ?: continue
                val simple = canonical.substringBefore('<').substringAfterLast('.')
                val isBase = REPOSITORY_BASE_TYPES.any { canonical.startsWith(it) } || simple in REPOSITORY_BASE_SIMPLE_NAMES
                if (isBase) {
                    (superType.parameters.firstOrNull() as? PsiClassType)?.resolve()?.let { return it }
                }
            }
        }
        // 兜底：类型自身第一个泛型参数（UserRepository<User> 变量形态 / 基类不可解析场景）
        return (fallbackType?.parameters?.firstOrNull() as? PsiClassType)?.resolve()
    }

    // ------------------------------------------------------------------
    // T-J1：Spring Data Repository CRUD
    // ------------------------------------------------------------------

    private fun convertRepositoryCall(
        call: PsiMethodCallExpression,
        methodName: String,
        resolveVariables: Boolean,
    ): JpaCrudPreview? {
        if (methodName !in REPOSITORY_METHODS) return null
        val entityClass = resolveRepositoryEntity(call) ?: return null
        val table = JpaEntityResolver().resolve(entityClass)
        if (table.entityClassName.isBlank()) return null
        val dialect = currentDialect(call.project)
        val idColumn = table.columns.firstOrNull { it.isId }
        val args = call.argumentList.expressions
        val prefix = columnParamPrefix(call)
        val build = buildRepositoryMethod(methodName, table, idColumn, args, prefix, dialect, resolveVariables)
            ?: return null
        return previewOf(methodName, table, build)
    }

    /**
     * F-1 修复：Repository 方法名 → 模板主体（调用点与**声明行**共用同一条语义线）。
     *
     * 抽出原因：声明行没有 `PsiMethodCallExpression`，[convert] 与调用点回落都永不认领
     * （`deleteAllInBatch` 这类 bulk 形态因此在声明行零可达）。本函数只依赖
     * 「方法名 + 表 + 主键列 + 实参 + 前缀」，两条入口都能构造。
     *
     * @param args 实参数组；声明行入口传 emptyArray（静态不可得，一律 `?` 占位）
     * @param prefix 列参数前缀；声明行入口传空串
     * @return 模板主体；方法名不在本线或缺少必要上下文时 null
     */
    private fun buildRepositoryMethod(
        methodName: String,
        table: TableInfo,
        idColumn: ColumnInfo?,
        args: Array<PsiExpression>,
        prefix: String,
        dialect: Dialect,
        resolveVariables: Boolean,
    ): Build? = when (methodName) {
            "save" -> insertBuild(table, prefix, dialect).let {
                it.copy(notes = it.notes + "save：isNew 判定——新实体 INSERT / 已存在 UPDATE（静态统一按 INSERT 呈现）")
            }
            "saveAndFlush" -> insertBuild(table, prefix, dialect).let {
                it.copy(notes = it.notes + "saveAndFlush = save + flush：flush 无独立 SQL（脏检查刷写），此处呈现 save 的 INSERT")
            }
            "saveAll" -> insertBuild(table, prefix, dialect).let {
                it.copy(
                    notes = it.notes + "saveAll：批量 INSERT，集合每元素一组参数（参数组 × N）",
                    paramGroups = batchParamGroups(buildInsertColumnNames(table), prefix),
                )
            }
            "saveAllAndFlush" -> insertBuild(table, prefix, dialect).let {
                it.copy(
                    notes = it.notes +
                        "saveAllAndFlush = saveAll + flush（脏检查刷写，无独立 flush SQL）；集合每元素一组参数（× N）",
                    paramGroups = batchParamGroups(buildInsertColumnNames(table), prefix),
                )
            }
            "findById", "getOne", "getById", "getReferenceById" -> {
                idColumn ?: return null
                val (where, params) = idCondition(args.firstOrNull(), idColumn, null, resolveVariables, dialect)
                val notes = when (methodName) {
                    "getReferenceById" -> listOf("getReferenceById：延迟代理，默认不发起 SQL（首次访问属性才按主键加载）")
                    "getOne", "getById" -> listOf(
                        "$methodName：已废弃（Deprecated），迁移 getReferenceById（同为延迟代理语义）",
                    )
                    else -> emptyList()
                }
                Build(
                    sql = selectSql(table, dialect, where),
                    params = params,
                    notes = notes,
                )
            }
            "findAll" -> Build(sql = selectSql(table, dialect, where = null))
            "findAllById" -> {
                idColumn ?: return null
                Build(sql = selectSql(table, dialect, "${dialect.quoteIfKeyword(idColumn.columnName)} IN (?, ?, ...)"))
            }
            "existsById" -> {
                idColumn ?: return null
                val (where, params) = idCondition(args.firstOrNull(), idColumn, null, resolveVariables, dialect)
                Build(
                    sql = "SELECT 1\nFROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE $where\n${dialect.renderLimit(LimitClause(count = 1))}",
                    params = params,
                    notes = listOf("existsById：命中即真语义（按当前方言只取首行，运行时语句以实际日志为准）"),
                )
            }
            "count" -> Build(sql = "SELECT COUNT(*)\nFROM ${dialect.quoteIfKeyword(table.tableName)}")
            "deleteById" -> {
                idColumn ?: return null
                val (where, params) = idCondition(args.firstOrNull(), idColumn, null, resolveVariables, dialect)
                Build(
                    sql = "DELETE FROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE $where",
                    params = params,
                    notes = listOf("deleteById：逐实体 EntityManager.remove 语义（非 bulk DELETE）"),
                )
            }
            "delete" -> {
                idColumn ?: return null
                val (where, params) = idCondition(null, idColumn, prefix, resolveVariables, dialect)
                Build(
                    sql = "DELETE FROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE $where",
                    params = params,
                    notes = listOf("delete(entity)：EntityManager.remove 语义，按实体主键删除"),
                )
            }
            "deleteAllById" -> {
                idColumn ?: return null
                Build(
                    sql = "DELETE FROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE ${dialect.quoteIfKeyword(idColumn.columnName)} IN (?, ?, ...)",
                    notes = listOf("deleteAllById：逐实体 remove 语义（集合每元素一条 DELETE）"),
                )
            }
            "deleteAll" ->
                if (args.isEmpty()) {
                    Build(
                        sql = "DELETE FROM ${dialect.quoteIfKeyword(table.tableName)}",
                        warning = BLOCK_ATTACK_WARNING,
                        notes = listOf("deleteAll()：逐实体 remove 语义的空表快捷形态——无 WHERE 全表删除"),
                    )
                } else {
                    idColumn ?: return null
                    val (where, params) = idCondition(null, idColumn, prefix, resolveVariables, dialect)
                    Build(
                        sql = "DELETE FROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE $where",
                        params = params,
                        notes = listOf("deleteAll(Iterable)：逐实体 remove 语义（集合每元素一条 DELETE）"),
                    )
                }
            "deleteAllInBatch" ->
                if (args.isEmpty()) {
                    Build(
                        sql = "DELETE FROM ${dialect.quoteIfKeyword(table.tableName)}",
                        warning = BLOCK_ATTACK_WARNING,
                        notes = listOf("deleteAllInBatch()：唯一 bulk DELETE 形态（单条语句、绕过级联与回调）"),
                    )
                } else {
                    idColumn ?: return null
                    Build(
                        sql = "DELETE FROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE ${dialect.quoteIfKeyword(idColumn.columnName)} IN (?, ?, ...)",
                        notes = listOf("deleteAllInBatch(Iterable)：bulk DELETE（单条 IN 语句、绕过级联与回调）"),
                    )
                }
            "deleteAllByIdInBatch" -> {
                idColumn ?: return null
                Build(
                    sql = "DELETE FROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE ${dialect.quoteIfKeyword(idColumn.columnName)} IN (?, ?, ...)",
                    notes = listOf("deleteAllByIdInBatch：bulk DELETE（单条 IN 语句、绕过级联与回调）"),
                )
            }
            "deleteInBatch" -> {
                idColumn ?: return null
                Build(
                    sql = "DELETE FROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE ${dialect.quoteIfKeyword(idColumn.columnName)} IN (?, ?, ...)",
                    notes = listOf(
                        "deleteInBatch：已废弃（Deprecated），迁移 deleteAllInBatch（同为 bulk DELETE）",
                    ),
                )
            }
            "flush" -> Build(
                sql = "",
                notes = listOf("flush()：无独立 SQL——脏检查批量刷写，将挂起的 INSERT/UPDATE/DELETE 一次性落库"),
            )
            else -> null
        }

    // ------------------------------------------------------------------
    // T-J6：EntityManager 写/单查
    // ------------------------------------------------------------------

    private fun convertEntityManagerCall(
        call: PsiMethodCallExpression,
        methodName: String,
        resolveVariables: Boolean,
    ): JpaCrudPreview? {
        if (methodName !in ENTITY_MANAGER_METHODS) return null
        val dialect = currentDialect(call.project)
        val args = call.argumentList.expressions

        // 存储过程（T-J16 基础形态）：过程名直传 + ? 占位（@StoredProcedureParameter 静态不可得）
        if (methodName == "createStoredProcedureQuery" || methodName == "createNamedStoredProcedureQuery") {
            val procName = (args.firstOrNull() as? com.intellij.psi.PsiLiteralExpression)?.value as? String
                ?: args.firstOrNull()?.text
                ?: return null
            return JpaCrudPreview(
                methodName = methodName,
                tableName = procName,
                sql = "CALL $procName(?)\n-- 存储过程参数：@StoredProcedureParameter 的名称/方向/类型静态不可得，" +
                    "? 占位请按存储过程实际定义回填",
            )
        }

        if (methodName == "flush") {
            return previewOf(
                methodName, null, Build(
                    sql = "",
                    notes = listOf("flush()：无独立 SQL——脏检查批量刷写，将挂起的 INSERT/UPDATE/DELETE 一次性落库"),
                ),
            )
        }

        // 其余操作均需实体（Class 字面量实参或实体类型实参，isJpaMappedEntity 负例守卫）
        val entity = resolveEntityArg(call) ?: return null
        val table = JpaEntityResolver().resolve(entity.psiClass)
        if (table.entityClassName.isBlank()) return null
        val idColumn = table.columns.firstOrNull { it.isId }
        val prefix = entity.argText

        val build: Build = when (methodName) {
            "persist" -> insertBuild(table, prefix, dialect)
            "merge" -> {
                idColumn ?: return null
                val setColumns = table.columns.filter { it.columnName != idColumn.columnName }
                val setSql = setColumns.joinToString(", ") { "${dialect.quoteIfKeyword(it.columnName)} = ?" }
                val (where, whereParams) = idCondition(null, idColumn, prefix, resolveVariables, dialect)
                Build(
                    sql = "UPDATE ${dialect.quoteIfKeyword(table.tableName)}\nSET $setSql\nWHERE $where",
                    params = setColumns.map { ParamEntry(idSource(prefix, it.columnName), ParamQuote.hint(it.fieldType)) } + whereParams,
                    notes = listOf("merge：托管/detached 实体 → UPDATE；new 实体（无主键）→ INSERT（静态按 UPDATE 呈现，二义性标注）"),
                )
            }
            "remove" -> {
                idColumn ?: return null
                val (where, params) = idCondition(null, idColumn, prefix, resolveVariables, dialect)
                Build(sql = "DELETE FROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE $where", params = params)
            }
            "find", "getReference" -> {
                idColumn ?: return null
                val idExpr = args.getOrNull(1)
                val (where, params) = idCondition(idExpr, idColumn, null, resolveVariables, dialect)
                val notes = if (methodName == "getReference") {
                    listOf("getReference：延迟代理，默认不发起 SQL（首次访问属性才按主键加载）")
                } else {
                    emptyList()
                }
                Build(sql = selectSql(table, dialect, where), params = params, notes = notes)
            }
            "refresh" -> {
                idColumn ?: return null
                val (where, params) = idCondition(null, idColumn, prefix, resolveVariables, dialect)
                Build(
                    sql = selectSql(table, dialect, where),
                    params = params,
                    notes = listOf("refresh：按主键重查并覆盖实体内存状态（DB 侧为普通 SELECT）"),
                )
            }
            else -> return null
        }
        return previewOf(methodName, table, build)
    }

    // ------------------------------------------------------------------
    // T-J21：HibernateTemplate 实体操作 / QBC
    // ------------------------------------------------------------------

    private fun convertHibernateTemplateCall(
        call: PsiMethodCallExpression,
        methodName: String,
        resolveVariables: Boolean,
    ): JpaCrudPreview? {
        val kind = CustomDaoHqlLocator.classifyHibernateTemplateMethod(methodName)
        // QBC 形态（executeFind / findByCriteria）：DetachedCriteria 条件树运行时构建，静态不可解析 → 可读占位
        if (kind == DaoQueryKind.QBC || methodName == "executeFind" || methodName == "findByCriteria") {
            return qbcPlaceholder(methodName)
        }
        // HQL / NAMED_QUERY 字符串承载 → 交回 previewCustomDaoHql 管道（本转换器不认领）
        if (kind != DaoQueryKind.ENTITY_OP) return null

        val dialect = currentDialect(call.project)
        val args = call.argumentList.expressions

        // 实体操作均需实体（Class 字面量实参或实体类型实参，isJpaMappedEntity 负例守卫）
        val entity = resolveEntityArg(call) ?: return null
        val table = JpaEntityResolver().resolve(entity.psiClass)
        if (table.entityClassName.isBlank()) return null
        val idColumn = table.columns.firstOrNull { it.isId }
        val prefix = entity.argText

        val build: Build = when (methodName) {
            "get" -> {
                idColumn ?: return null
                val (where, params) = idCondition(args.getOrNull(1), idColumn, null, resolveVariables, dialect)
                Build(sql = selectSql(table, dialect, where), params = params)
            }
            "load" -> {
                idColumn ?: return null
                val (where, params) = idCondition(args.getOrNull(1), idColumn, null, resolveVariables, dialect)
                Build(
                    sql = selectSql(table, dialect, where),
                    params = params,
                    notes = listOf("load：延迟代理，默认不发起 SQL（首次访问属性才按主键加载）"),
                )
            }
            "loadAll" -> Build(
                sql = selectSql(table, dialect, where = null),
                notes = listOf("loadAll(Class)：等价 find(\"from Entity\") 全量加载；元素默认为延迟代理"),
            )
            "save", "persist" -> insertBuild(table, prefix, dialect)
            "saveOrUpdate" -> insertBuild(table, prefix, dialect).let {
                it.copy(notes = it.notes + "saveOrUpdate：isNew 判定——新实体 INSERT / 已存在 UPDATE（静态统一按 INSERT 呈现）")
            }
            "merge" -> {
                idColumn ?: return null
                val setColumns = table.columns.filter { it.columnName != idColumn.columnName }
                val setSql = setColumns.joinToString(", ") { "${dialect.quoteIfKeyword(it.columnName)} = ?" }
                val (where, whereParams) = idCondition(null, idColumn, prefix, resolveVariables, dialect)
                Build(
                    sql = "UPDATE ${dialect.quoteIfKeyword(table.tableName)}\nSET $setSql\nWHERE $where",
                    params = setColumns.map { ParamEntry(idSource(prefix, it.columnName), ParamQuote.hint(it.fieldType)) } + whereParams,
                    notes = listOf("merge：detached → UPDATE；new 实体 → INSERT（静态按 UPDATE 呈现，二义性标注）"),
                )
            }
            "update", "replicate" -> {
                idColumn ?: return null
                val setColumns = table.columns.filter { it.columnName != idColumn.columnName }
                val setSql = setColumns.joinToString(", ") { "${dialect.quoteIfKeyword(it.columnName)} = ?" }
                val (where, whereParams) = idCondition(null, idColumn, prefix, resolveVariables, dialect)
                val notes = if (methodName == "replicate") {
                    listOf("replicate：按指定复制模式全量覆写持久态（静态按全列 UPDATE 呈现）")
                } else {
                    emptyList()
                }
                Build(
                    sql = "UPDATE ${dialect.quoteIfKeyword(table.tableName)}\nSET $setSql\nWHERE $where",
                    params = setColumns.map { ParamEntry(idSource(prefix, it.columnName), ParamQuote.hint(it.fieldType)) } + whereParams,
                    notes = notes,
                )
            }
            "delete" -> {
                idColumn ?: return null
                val (where, params) = idCondition(null, idColumn, prefix, resolveVariables, dialect)
                Build(sql = "DELETE FROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE $where", params = params)
            }
            "deleteAll" -> {
                idColumn ?: return null
                val (where, params) = idCondition(null, idColumn, prefix, resolveVariables, dialect)
                Build(
                    sql = "DELETE FROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE $where",
                    params = params,
                    notes = listOf("deleteAll：逐实体 remove 语义（集合每元素一条 DELETE）"),
                )
            }
            "refresh" -> {
                idColumn ?: return null
                val (where, params) = idCondition(null, idColumn, prefix, resolveVariables, dialect)
                Build(
                    sql = selectSql(table, dialect, where),
                    params = params,
                    notes = listOf("refresh：按主键重查并覆盖实体内存状态（DB 侧为普通 SELECT）"),
                )
            }
            "lock" -> Build(
                sql = "",
                notes = listOf("lock：仅申请锁模式（无独立 SQL；锁语义归 J15）"),
            )
            "findByExample" -> Build(
                sql = "SELECT ${selectColumnList(table, dialect)}\nFROM ${dialect.quoteIfKeyword(table.tableName)}\nWHERE <Example 非空属性等值条件: col = ? AND col = ? ...>",
                notes = listOf("findByExample：以实体非空属性为等值条件（NullHandler 剔除 null 属性），条件列运行时确定"),
            )
            else -> return null
        }
        return previewOf(methodName, table, build)
    }

    /** QBC/DetachedCriteria 可读占位（条件树运行时构建，静态不可解析）。 */
    private fun qbcPlaceholder(methodName: String): JpaCrudPreview = JpaCrudPreview(
        methodName = methodName,
        tableName = "",
        sql = "-- QBC/DetachedCriteria 形态暂不支持静态解析（条件树运行时构建）",
    )

    // ------------------------------------------------------------------
    // 模板构建辅助
    // ------------------------------------------------------------------

    /** SELECT 全列模板（列清单为空时降级 `*`）。 */
    private fun selectSql(table: TableInfo, dialect: Dialect, where: String?): String =
        "SELECT ${selectColumnList(table, dialect)}\nFROM ${dialect.quoteIfKeyword(table.tableName)}" +
            (where?.let { "\nWHERE $it" } ?: "")

    private fun selectColumnList(table: TableInfo, dialect: Dialect): String =
        table.columns.joinToString(", ") { dialect.quoteIfKeyword(it.columnName) }.ifEmpty { "*" }

    /**
     * INSERT 模板（JPA/Hibernate 语义，见类 KDoc）：
     *  - IDENTITY 主键不进列清单（注释说明）；
     *  - SEQUENCE/TABLE 主键保留 `?` 占位（注释说明前置取值）；
     *  - `@Column(insertable = false)` 列不进列清单。
     */
    private fun insertBuild(table: TableInfo, prefix: String, dialect: Dialect): Build {
        val idColumn = table.columns.firstOrNull { it.isId }
        val strategy = idColumn?.generatedValueStrategy?.uppercase()
        val insertColumns = table.columns
            .filter { it.insertable }
            .filter { !(it.isId && strategy == "IDENTITY") }
        val toggleColumns = insertColumns.map { col ->
            BatchColumn(
                columnName = col.columnName,
                source = idSource(prefix, col.columnName),
                quote = ParamQuote.decide(col.fieldType),
                checked = true,
                temporal = ParamQuote.isTemporal(col.fieldType),
            )
        }
        val active = toggleColumns.map { it.columnName }.toSet()
        val model = BatchModel(BatchKind.INSERT, table.tableName, toggleColumns).copy(dialect = dialect)
        val notes = buildList {
            if (strategy == "IDENTITY" && idColumn != null) {
                add("主键 ${idColumn.columnName}：@GeneratedValue(IDENTITY) 数据库自增，不进 INSERT 列清单")
            }
            if (strategy == "SEQUENCE" && idColumn != null) {
                add("主键 ${idColumn.columnName}：@GeneratedValue(SEQUENCE)——INSERT 前置取值（select nextval），占位 ? 由应用回填")
            }
            if (strategy == "TABLE" && idColumn != null) {
                add("主键 ${idColumn.columnName}：@GeneratedValue(TABLE)——INSERT 前置访问序列表取值（hi/lo），占位 ? 由应用回填")
            }
        }
        return Build(
            sql = model.renderSql(active),
            params = model.orderedColumns(active).map {
                ParamEntry(it.source, ParamHint(quote = it.quote, temporal = it.temporal))
            },
            paramGroups = batchParamGroups(insertColumns.map { it.columnName }, prefix),
            notes = notes,
            model = model,
        )
    }

    private fun buildInsertColumnNames(table: TableInfo): List<String> =
        table.columns.filter { it.insertable }.map { it.columnName }

    /**
     * 主键条件渲染：实参可静态求值（字面量/可追踪变量）→ 内联；否则 `?` + 参数行；
     * 无实参（实体主键形态 delete(entity)/remove(entity)）→ `?` + `实体.主键列` 参数行。
     */
    private fun idCondition(
        idExpr: PsiExpression?,
        idColumn: ColumnInfo,
        entityPrefix: String?,
        resolveVariables: Boolean,
        dialect: Dialect,
    ): Pair<String, List<ParamEntry>> {
        val idName = dialect.quoteIfKeyword(idColumn.columnName)
        if (idExpr != null) {
            val value = if (resolveVariables) ExpressionEvaluator.evaluate(idExpr) else SqlValue.Placeholder(idExpr.text)
            return when (value) {
                is SqlValue.Literal -> "$idName = ${value.raw}" to emptyList()
                is SqlValue.Placeholder ->
                    "$idName = ?" to listOf(ParamEntry(value.source, ParamQuote.hint(idColumn.fieldType)))
            }
        }
        return "$idName = ?" to listOf(
            ParamEntry(idSource(entityPrefix.orEmpty(), idColumn.columnName), ParamQuote.hint(idColumn.fieldType)),
        )
    }

    /** 实体/集合参数前缀：首实参文本（`save(entity)` → `entity`）；无实参 → qualifier 文本；均无 → 空串。 */
    private fun columnParamPrefix(call: PsiMethodCallExpression): String {
        call.argumentList.expressions.firstOrNull()?.let { return it.text }
        return (call.methodExpression.qualifierExpression as? PsiReferenceExpression)?.text.orEmpty()
    }

    /** 参数组说明（对齐 MP 批量面板惯例）：`-- 实际参数: [col=?, ...] × N`。 */
    private fun batchParamGroups(columns: List<String>, prefix: String): String {
        val group = columns.joinToString(", ") { "$it=?" }
        return "-- 实际参数: [$group] × N（每行一组，运行时值不可静态获取）"
    }

    /** 实体字段参数来源：`实体变量.列名`；无前缀时仅列名。 */
    private fun idSource(prefix: String, columnName: String): String =
        if (prefix.isEmpty()) columnName else "$prefix.$columnName"

    /** Build → JpaCrudPreview：SQL 尾部追加语义注释行（note-only 时 SQL 即注释卡）。 */
    private fun previewOf(methodName: String, table: TableInfo?, build: Build): JpaCrudPreview {
        val sql = when {
            build.sql.isBlank() -> build.notes.joinToString("\n") { "-- $it" }
            build.notes.isEmpty() -> build.sql
            else -> build.sql + build.notes.joinToString("") { "\n-- $it" }
        }
        return JpaCrudPreview(
            methodName = methodName,
            tableName = table?.tableName.orEmpty(),
            sql = sql,
            paramGroups = build.paramGroups,
            paramSources = build.params.map { it.source },
            paramHints = build.params.map { it.hint },
            warning = build.warning,
            batchModel = build.model,
        )
    }

    // ------------------------------------------------------------------
    // 实体实参解析（T-J6 / T-J21：Class 字面量实参 / 实体类型实参）
    // ------------------------------------------------------------------

    /** 实体实参解析结果（psiClass + 实参文本，实参文本作参数行前缀 `entity.列名`）。 */
    private data class EntityArg(val psiClass: PsiClass, val argText: String)

    /**
     * 从调用实参解析实体类：
     *  1. Class 字面量实参（`em.find(UserEntity.class, id)` / `ht.get(UserEntity.class, id)` / `loadAll(UserEntity.class)`）；
     *  2. 实体类型实参（`persist(entity)` / `remove(entity)`，[isJpaMappedEntity] 负例守卫——
     *     String/Long 等实参类型不误判）。
     * 两路均失败返回 null（不识别）。
     */
    private fun resolveEntityArg(call: PsiMethodCallExpression): EntityArg? {
        val args = call.argumentList.expressions
        for (arg in args) {
            if (arg !is PsiClassObjectAccessExpression) continue
            val cls = (arg.operand.type as? PsiClassType)?.resolve() ?: continue
            if (isJpaMappedEntity(cls)) return EntityArg(cls, args.getOrNull(1)?.text.orEmpty())
        }
        for (arg in args) {
            val cls = (arg.type as? PsiClassType)?.resolve() ?: continue
            if (isJpaMappedEntity(cls)) return EntityArg(cls, arg.text)
        }
        return null
    }

    /**
     * JPA 实体映射守卫（负例）：@Entity / @Table 注解（jakarta/javax 双包名 + 短名兜底）
     * 至少其一存在才视为 JPA 实体——`em.find(SomeDto.class, id)` / `ht.save(string)` 不出 SQL。
     */
    private fun isJpaMappedEntity(cls: PsiClass): Boolean {
        val qualifiedName = cls.qualifiedName ?: return false
        if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) return false
        return cls.annotations.any { annotation ->
            val qName = annotation.qualifiedName
            val shortName = annotation.nameReferenceElement?.referenceName
            qName in JPA_ENTITY_ANNOTATIONS || qName in JPA_TABLE_ANNOTATIONS ||
                shortName == "Entity" || shortName == "Table"
        }
    }

    private val JPA_ENTITY_ANNOTATIONS = setOf("jakarta.persistence.Entity", "javax.persistence.Entity")
    private val JPA_TABLE_ANNOTATIONS = setOf("jakarta.persistence.Table", "javax.persistence.Table")
}
