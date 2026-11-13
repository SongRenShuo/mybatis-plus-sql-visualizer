package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.EntityResolver
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.impl.java.stubs.index.JavaAnnotationIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * 源码 SQL 补全上下文（T-M27-02）：XML 语句 / 注解方法 → [SqlCompletionContext] 主表 +
 * 联表候选 + 参数名，供 XML 与注解补全贡献器（T-M27-03/05）共用。
 *
 * @param main       主表上下文（可为 null → 降级只补关键字/动态标签，不阻塞补全）
 * @param candidates 联表候选：主表实体 + 项目内 `@TableName` 实体（按 tableName 去重、cap 50）
 * @param paramNames 语句方法参数名（@Param 值优先 + 实际名 + param1..N，复用
 *                   [AnnotationSqlPreviewService.paramNames]）
 */
data class SourceSqlContext(
    val main: SqlCompletionContext?,
    val candidates: List<TableInfo>,
    val paramNames: List<String>,
)

/**
 * XML/注解 SQL 补全上下文解析器（T-M27-02，M27 架构 §3.2）：
 *
 * **主实体链路**（优先级从高到低，对齐 M26-B XmlEwResolver 的 namespace→接口关联链路）：
 *  1. **Mapper 接口泛型基类实参**：XML 场景向上找根 `<mapper>` 的 namespace →
 *     [JavaPsiFacade.findClass]（[XmlEwResolver] 同款）；注解场景 method.containingClass 即
 *     Mapper 接口。沿接口继承链（DFS + 防环）找全限定名后缀 `BaseMapper`/`MPJBaseMapper`
 *     的基类，取 PsiClassType 泛型实参解析出的实体类；
 *  2. **语句方法实体参数**：XML 场景按语句 id → [PsiClass.findMethodsByName] 首方法；注解
 *     场景即 method 本身。取参数列表第一个**实体类型**参数——PsiClassType 可解析为 PsiClass，
 *     且简单名不在 Wrapper 族 / 基本类型包装 / String / Collection / Map / Page / IPage 集合中；
 *  3. 均失败 → 主表为 null（降级只补关键字/标签）。
 *
 * **@TableName 实体候选**（JOIN 后表名补全）：注解类可解析时走官方
 * [AnnotatedElementsSearch.searchPsiClasses]；不可解析（测试沙盒/缺依赖）时兜底直接按 FQN
 * 查注解 stub 索引 [JavaAnnotationIndex]（全限定名书写的 `@com.baomidou...TableName` 可命中）。
 * 查询失败/异常 → 空列表兜底（不抛异常）。
 *
 * **参数名**：复用提权后的 [AnnotationSqlPreviewService.paramNames]（@Param 值优先 + 实际名 +
 * param1..N，对齐 ParamNameResolver 语义）；方法找不到 → 空列表。
 *
 * 实体解析仅在补全触发时执行（用户主动触发，容忍一次 findClass + EntityResolver），
 * 不进入 daemon 路径（M27 性能红线 §3.5）。
 */
object XmlSqlCompletionContextResolver {

    /** @TableName 注解全限定名（真实 MP 注解，libs.mp.core 提供）。 */
    private const val TABLE_NAME_ANNOTATION = "com.baomidou.mybatisplus.annotation.TableName"

    /** 联表候选上限（防大项目候选爆炸，M27 §3.2）。 */
    private const val CANDIDATES_CAP = 50

    /** 候选解析预算（T-StartupReadLockFreeze §十九）：超预算即停，剩余候选等下次缓存重建。 */
    internal var candidatesBudgetMs = 400L

    /** 测试缝：表名 → 实体类（Light 沙盒里 EntityClassFinder 依赖索引、拿不到；生产恒 null）。 */
    internal var entityFinderForTest: ((Project, String) -> PsiClass?)? = null

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(XmlSqlCompletionContextResolver::class.java)

    /** 非实体参数简单名集合：MP Wrapper 族 + 基本类型包装 + 常见非实体类型（简单名匹配）。 */
    private val NON_ENTITY_SIMPLE_NAMES = setOf(
        // MP Wrapper 族（简单名匹配——测试环境类型未解析场景 canonicalText 为短名同样生效）
        "LambdaQueryWrapper", "QueryWrapper", "UpdateWrapper", "LambdaUpdateWrapper",
        "LambdaDeleteWrapper", "MPJLambdaWrapper", "MPJQueryWrapper", "DeleteWrapper", "JoinWrapper",
        // 防御补充：Wrapper 抽象基类同样非实体（XmlEwResolverTest 的 `Wrapper ew` 参数场景）
        "Wrapper", "AbstractWrapper",
        // 基本类型包装 / 常见非实体类型
        "String", "Integer", "Long", "Boolean", "Double", "Float", "Short", "Byte", "Character",
        "Object", "List", "Set", "Collection", "Map", "Page", "IPage",
    )

    /**
     * 解析 XML 语句标签的补全上下文。
     *
     * @param project      当前项目（findClass/注解索引/EntityResolver 均需）
     * @param statementTag `<select|insert|update|delete>` 语句标签（根 `<mapper>` 的 namespace
     *                     经 [XmlFile.rootTag] 向上获取；id 属性即语句 id）
     * @return [SourceSqlContext]；任何环节失败均降级（main=null/空候选/空参数名），不抛异常
     */
    fun resolveXml(
        project: Project,
        statementTag: XmlTag,
        /**
         * T-StartupReadLockFreeze §二十（2026-11-13 15:31 真机）：是否解析**联表候选**（全项目
         * `@TableName` 实体逐个 `EntityResolver.resolve`）。预览打开一律传 false——实测候选段
         * 2~3s 且最终只得到 1 个候选（多数实体解析结果为空表名，上限按"已解析"计数拦不住整轮扫描）；
         * 候选只服务 JOIN 表名补全，等用户真正触发补全时再算（默认 true）。
         */
        includeCandidates: Boolean = true,
    ): SourceSqlContext {
        // 向上找根 <mapper> tag 的 namespace（与 XmlEwResolver 的 namespace 来源一致）
        val namespace = (statementTag.containingFile as? XmlFile)?.rootTag?.getAttributeValue("namespace")
        val mapperClass = namespace?.takeIf { it.isNotEmpty() }?.let {
            JavaPsiFacade.getInstance(project).findClass(it, GlobalSearchScope.allScope(project))
        }
        // 语句 id → 接口同名方法（M26-B 同款 findMethodsByName，取第一个）
        val method = statementTag.getAttributeValue("id")?.let { id ->
            mapperClass?.findMethodsByName(id, false)?.firstOrNull()
        }
        // §二十六：mapper 接口/泛型实体解析不到时的兜底——直接从语句文本取 FROM/JOIN 表名，
        // 按表名反查实体（MBG 风格 mapper、沙盒没 Load Maven 时最常见）。
        val fallbackTable = runCatching {
            // 注意：这里的 `\\b` 必须是正则词界。上一版经脚本写入时被写成了退格符（U+0008），
            // 正则变成「退格 + from|join」，永远不匹配 ⇒ 兜底恒为 null（已修，勿再用脚本写这段）。
            Regex("(?i)\\b(?:from|join)\\s+([A-Za-z_][A-Za-z0-9_]*)")
                .find(statementTag.value.text)
                ?.groupValues?.getOrNull(1)
        }.getOrNull()
        return resolveCommon(project, mapperClass, method, includeCandidates, fallbackTable)
    }

    /**
     * 解析注解 SQL 方法（@Select/@Insert/... 所在 Mapper 方法）的补全上下文。
     * 所在接口即 Mapper 接口，主实体链路与 XML 场景一致（无 namespace 环节）。
     *
     * @param project 当前项目
     * @param method  Mapper 接口上的 SQL 注解方法
     */
    fun resolveAnnotation(project: Project, method: PsiMethod): SourceSqlContext =
        resolveCommon(project, method.containingClass, method)

    // ------------------------------------------------------------------
    // 主实体链路
    // ------------------------------------------------------------------

    /** 主实体解析（优先级从高到低）：① Mapper 泛型基类实参 ② 方法实体参数；均失败 → null。 */
    private fun resolveCommon(
        project: Project,
        mapperClass: PsiClass?,
        method: PsiMethod?,
        includeCandidates: Boolean = true,
        fallbackTableName: String? = null,
    ): SourceSqlContext {
        val t0 = System.currentTimeMillis()
        val mainEntity = findMainEntity(mapperClass, method)
        val t1 = System.currentTimeMillis()
        val resolvedTable = mainEntity?.let { runCatching { EntityResolver().resolve(it) }.getOrNull() }
            ?.takeIf { it.tableName.isNotEmpty() }
        // §二十六：接口/泛型都没解析到时，用 SQL 里的表名反查实体（表名 → @TableName/类名约定）
        val mainTable = resolvedTable ?: fallbackTableName?.let { table ->
            runCatching {
                val clazz = entityFinderForTest?.invoke(project, table)
                    ?: cn.song.mybatisplus.visualizer.psi.EntityClassFinder(project).find(table).firstOrNull()
                clazz?.let { EntityResolver().resolve(it) }?.takeIf { it.tableName.isNotEmpty() }
            }.getOrNull()
        }
        if (resolvedTable == null && mainTable != null) {
            LOG.info("[open-perf] ctx fallback: table=$fallbackTableName 反查到实体 ${mainTable.tableName}")
        }
        val t2 = System.currentTimeMillis()
        val candidates = if (includeCandidates) buildCandidates(project, mainTable) else emptyList()
        val t3 = System.currentTimeMillis()
        // §十九：分段埋点，下一次真机日志可直接读出「哪一段在吃时间」
        LOG.info(
            "[open-perf] ctx detail: entity=${t1 - t0}ms resolve=${t2 - t1}ms " +
                "candidates=${t3 - t2}ms candidatesCount=${candidates.size} include=$includeCandidates",
        )
        return SourceSqlContext(
            main = mainTable?.let { buildMainContext(it) },
            candidates = candidates,
            // T-PT-01：paramTypes 键序 = 命名参数序 + param1..N（与旧 paramNames 列表逐项一致）
            paramNames = AnnotationSqlPreviewService.paramTypes(method).keys.toList(),
        )
    }

    /** 主实体（优先级从高到低）：Mapper 泛型基类实参 → 语句方法实体参数。 */
    private fun findMainEntity(mapperClass: PsiClass?, method: PsiMethod?): PsiClass? {
        mapperClass?.let { resolveMapperGenericEntity(it) }?.let { return it }
        return findEntityParam(method)
    }

    /**
     * 沿 Mapper 接口继承链（DFS，visited 防环）找 `BaseMapper<X>` / `MPJBaseMapper<X>` 基类，
     * 返回泛型实参解析出的实体类；找不到/实参非实体 → null。
     * 全限定名后缀匹配（`com.baomidou.mybatisplus.core.mapper.BaseMapper` /
     * `com.github.yulichang.interfaces.MPJBaseMapper`）；`Service` 等非 Mapper 族不参与。
     */
    private fun resolveMapperGenericEntity(mapperClass: PsiClass): PsiClass? {
        val visited = mutableSetOf<PsiClass>()
        fun walk(clazz: PsiClass): PsiClass? {
            if (!visited.add(clazz)) return null
            for (superType in clazz.superTypes) {
                val superClass = superType.resolve() as? PsiClass ?: continue
                if (superClass is PsiTypeParameter) continue
                if (isMapperBase(superClass)) {
                    // 取 PsiClassType 泛型实参（BaseMapper<X> / MPJBaseMapper<X> 均单类型参数）
                    val entity = (superType.parameters.firstOrNull() as? PsiClassType)
                        ?.resolve() as? PsiClass ?: continue
                    if (entity is PsiTypeParameter) continue
                    return entity
                }
            }
            for (superType in clazz.superTypes) {
                val superClass = superType.resolve() as? PsiClass ?: continue
                walk(superClass)?.let { return it }
            }
            return null
        }
        return walk(mapperClass)
    }

    /** 是否为 Mapper 族基类（全限定名后缀 BaseMapper / MPJBaseMapper）。 */
    private fun isMapperBase(clazz: PsiClass): Boolean {
        val fqn = clazz.qualifiedName ?: return false
        return fqn.endsWith(".BaseMapper") || fqn.endsWith(".MPJBaseMapper")
    }

    /**
     * 语句方法参数中第一个实体类型参数：
     *  - PsiClassType 可解析为 PsiClass（PsiPrimitiveType/PsiArrayType 天然排除）；
     *  - 非 Wrapper 类型（简单名不在 [NON_ENTITY_SIMPLE_NAMES] 的 Wrapper 族）；
     *  - 非基本类型包装 / String / Collection / Map / Page / IPage。
     * 找不到 → null。
     */
    private fun findEntityParam(method: PsiMethod?): PsiClass? {
        if (method == null) return null
        for (param in method.parameterList.parameters) {
            val type = param.type as? PsiClassType ?: continue
            val clazz = type.resolve() as? PsiClass ?: continue
            if (clazz is PsiTypeParameter) continue
            val name = clazz.name ?: continue
            if (name in NON_ENTITY_SIMPLE_NAMES) continue
            if (name.endsWith("Example")) {
                // T-StartupReadLockFreeze §二十五（2026-11-13 真机日志）：MBG 的 `selectByExample(XxxExample)`
                // 参数是**查询条件对象**，不是实体。原实现把它当实体 ⇒ 表名推成 `cust_channel_example`、
                // 列只有 Example 的 3 个字段（真机 `[completion] ctx: main=cust_channel_example columns=3`），
                // 列名补全于是给出完全错误的候选。
                // 按 MBG 命名约定回查同包实体 `Xxx`；查不到就跳过（宁可 main=null，也不拿 Example 当实体）。
                exampleEntityOf(clazz)?.let { return it }
                continue
            }
            return clazz
        }
        return null
    }

    /** `xxx.CustChannelExample` → 同包 `xxx.CustChannel`（MBG 生成器的固定命名对）。 */
    private fun exampleEntityOf(exampleClass: PsiClass): PsiClass? {
        val qualified = exampleClass.qualifiedName ?: return null
        if (!qualified.endsWith("Example")) return null
        val entityFqn = qualified.removeSuffix("Example")
        if (entityFqn.substringAfterLast('.').isEmpty()) return null
        return runCatching {
            JavaPsiFacade.getInstance(exampleClass.project)
                .findClass(entityFqn, GlobalSearchScope.allScope(exampleClass.project))
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 主表上下文 / 联表候选
    // ------------------------------------------------------------------

    /** TableInfo → SqlCompletionContext（tableAlias 恒 null；列注释非空才收录；表注释原样透传）。 */
    private fun buildMainContext(table: TableInfo): SqlCompletionContext = SqlCompletionContext(
        tableName = table.tableName,
        columns = table.columns.map { it.columnName },
        tableAlias = null,
        columnComments = table.columns.mapNotNull { col ->
            col.comment?.takeIf { it.isNotBlank() }?.let { col.columnName to it }
        }.toMap(),
        tableComment = table.comment,
    )

    /**
     * 联表候选：主表实体 TableInfo 置顶 + 项目内 `@TableName` 实体（按 tableName 去重、cap 50）。
     * 查询失败/异常 → 仅主表或空列表兜底（不抛异常）。
     */
    private fun buildCandidates(project: Project, mainTable: TableInfo?): List<TableInfo> {
        val result = mutableListOf<TableInfo>()
        val seen = mutableSetOf<String>()
        mainTable?.let {
            result += it
            seen += it.tableName
        }
        // T-StartupReadLockFreeze §十九（2026-11-13 15:00 真机）：原实现把**全项目** `@TableName` 实体
        // 逐个 `EntityResolver.resolve`，最后才 `take(50)`——2500 表工程上一次 6~10s（`pre-assembly`
        // 埋点直接读出 completion=10327ms/5924ms，且无缓存、第二次照样慢）。
        // 现在：①命中项目级缓存直接返回；②边解析边判上限，够 50 就停；③超过预算立刻放弃剩余。
        val now = System.currentTimeMillis()
        val cachedBase = candidatesCache.get(project.locationHash)?.let { entry ->
            // §二十：失效判据从 PsiModificationTracker 改成 TTL——本插件预览链自己会写临时 SQL 文档，
            // 每次打开都把版本号顶掉，实测第二次打开仍要 2116ms（缓存等于没有）。
            if (now - entry.createdAt in 0 until candidatesTtlMs) {
                candidatesCacheHits++
                entry.tables
            } else {
                null
            }
        }
        val base = cachedBase ?: run {
            val resolved = mutableListOf<TableInfo>()
            val resolvedSeen = mutableSetOf<String>()
            val deadline = System.currentTimeMillis() + candidatesBudgetMs
            var scanned = 0
            val entities = tableNameEntitiesProviderForTest?.invoke(project)
                ?: findTableNameEntities(project)
            for (entityClass in entities) {
                if (resolved.size >= CANDIDATES_CAP) break
                if (System.currentTimeMillis() >= deadline) {
                    LOG.info(
                        "[open-perf] ctx candidates budget hit: scanned=$scanned resolved=${resolved.size} " +
                            "budget=${candidatesBudgetMs}ms",
                    )
                    break
                }
                scanned++
                entitiesResolved++
                val table = runCatching { EntityResolver().resolve(entityClass) }.getOrNull() ?: continue
                if (table.tableName.isEmpty()) continue
                if (resolvedSeen.add(table.tableName)) resolved += table
            }
            candidatesCache[project.locationHash] = CandidatesEntry(System.currentTimeMillis(), resolved)
            resolved
        }
        base.forEach { table -> if (seen.add(table.tableName)) result += table }
        return result.take(CANDIDATES_CAP)
    }

    /** 候选缓存条目：按 `PsiModificationTracker` 版本失效（改代码/加实体即重建一次）。 */
    private data class CandidatesEntry(val createdAt: Long, val tables: List<TableInfo>)

    /** 候选缓存 TTL（§二十）：候选只服务补全提示，容忍 2 分钟陈旧，换掉被自写文档顶掉的版本判据。 */
    internal var candidatesTtlMs = 120_000L

    private val candidatesCache: MutableMap<String, CandidatesEntry> = java.util.concurrent.ConcurrentHashMap()

    /**
     * T-StartupReadLockFreeze §二十一：**后台补齐候选**（预览打开不再同步算它）。
     *
     * 供面板在预览显示后调用：BGT 里把全项目 `@TableName` 实体解析进 TTL 缓存，落地后由调用方
     * `refreshCompletionContext()` 回填补全上下文，用户真正补全时就有表名候选。
     *
     * @return true＝缓存已可用（本次刚算完或本来就热）
     */
    fun warmCandidates(project: Project?, onReady: () -> Unit = {}): Boolean {
        if (project == null) return false
        if (runCatching { com.intellij.openapi.project.DumbService.isDumb(project) }.getOrDefault(false)) return false
        val now = System.currentTimeMillis()
        val entry = candidatesCache[project.locationHash]
        if (entry != null && now - entry.createdAt in 0 until candidatesTtlMs) {
            onReady()
            return true
        }
        // T-StartupReadLockFreeze §二十四（2026-11-13 真机栈实证）：本方法由面板在工作线程上调用，
        // 而 `buildCandidates → findTableNameEntities → JavaPsiFacade.findClass` 全是 PSI/索引读——
        // 不包读动作会抛 `Read access is allowed from inside read-action only`，异常被 runCatching 吞掉
        // ⇒ 候选永远补不进去（用户看到的就是「补全一直是空的」），同时刷一屏 IDEA 报错。
        // 用 nonBlocking + inSmartMode：等待索引时不持读锁、可取消，与 S1 预取链同款。
        val started = System.currentTimeMillis()
        // 单测走同步路径（测试线程持读动作、没有真正的索引等待），生产走 nonBlocking
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode) {
            val ok = runCatching {
                buildCandidates(project, null)
                true
            }.getOrDefault(false)
            if (ok) onReady()
            LOG.info("[completion] warmCandidates(unit-test) ok=$ok cached=${cachedCandidateTables(project).size}")
            return ok
        }
        return runCatching {
            ReadAction.nonBlocking<Unit> {
                buildCandidates(project, null)
                Unit
            }
                .inSmartMode(project)
                .expireWith(project)
                .coalesceBy(this, "warmCandidates:${project.locationHash}")
                .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
                .onSuccess {
                    LOG.info(
                        "[completion] warmCandidates ok=true cached=${cachedCandidateTables(project).size} " +
                            "elapsed=${System.currentTimeMillis() - started}ms",
                    )
                    onReady()
                }
                .onError { t ->
                    LOG.info("[completion] warmCandidates fail ${t.javaClass.simpleName}: ${t.message}")
                }
            true
        }.getOrDefault(false)
    }

    /** 只读候选缓存（补全上下文构造用）：未预热/已过期 → 空列表，绝不触发解析。 */
    fun cachedCandidateTables(project: Project?): List<TableInfo> {
        if (project == null) return emptyList()
        val entry = candidatesCache[project.locationHash] ?: return emptyList()
        val now = System.currentTimeMillis()
        return if (now - entry.createdAt in 0 until candidatesTtlMs) entry.tables else emptyList()
    }

    /** 测试缝：清空候选缓存。 */
    internal fun clearCandidatesCacheForTest() {
        candidatesCache.clear()
        candidatesCacheHits = 0
        entitiesResolved = 0
    }

    /**
     * 测试缝：候选实体来源。Light 沙盒拿不到 `@TableName` 注解索引（实测返回空 ⇒ 用例会空绿），
     * 用它直接喂 PSI 类，才能真的压到「全项目逐个 resolve」这条循环。生产恒为 null。
     */
    internal var tableNameEntitiesProviderForTest: ((Project) -> List<com.intellij.psi.PsiClass>)? = null

    /** 测试缝：缓存命中次数（探针用——真机那 6~10s 在 60 实体的单测规模里量不出来，只能钉住行为）。 */
    internal var candidatesCacheHits = 0
        private set

    /** 测试缝：真正执行 `EntityResolver.resolve` 的实体数（上限断言的直接证据）。 */
    internal var entitiesResolved = 0
        private set

    /**
     * 项目内 `@TableName` 实体类：
     *  - 注解类可解析（真实 IDE）→ [AnnotatedElementsSearch.searchPsiClasses]（官方注解索引）；
     *  - 注解类不可解析（测试沙盒/缺依赖）→ 兜底按 FQN 直查 [JavaAnnotationIndex] 注解 stub
     *    索引（源文件中**全限定名**书写的注解可命中，实体类注解场景即类级注解）。
     * 任一环节异常 → 空列表（不抛异常）。
     */
    private fun findTableNameEntities(project: Project): List<PsiClass> {
        val scope = GlobalSearchScope.allScope(project)
        return runCatching {
            val annotationClass = JavaPsiFacade.getInstance(project).findClass(TABLE_NAME_ANNOTATION, scope)
            if (annotationClass != null) {
                AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll().toList()
            } else {
                JavaAnnotationIndex.getInstance().getAnnotations(TABLE_NAME_ANNOTATION, project, scope)
                    .mapNotNull { PsiTreeUtil.getParentOfType(it, PsiClass::class.java) }
            }
        }.getOrElse { emptyList() }
    }
}
