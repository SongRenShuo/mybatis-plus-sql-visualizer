package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * Resolve entity names inside the query's module, dependencies and libraries.
 * Call under read access. A missing visible class never falls back to unrelated modules;
 * equally valid candidates fail closed. Sorting is for diagnostics, not selection.
 *
 * This entry point performs only name/FQN lookups, never an all-class-name sweep.
 * Callers with an existing bounded alias search can supply its complete results through
 * [resolveWithAliasCandidates]. A truncated search cannot establish uniqueness.
 */
object EntityClassResolver {
    private val LOG = Logger.getInstance(EntityClassResolver::class.java)

    data class Resolution(val psiClass: PsiClass?, val candidates: List<PsiClass>) {
        val ambiguous: Boolean get() = candidates.size > 1
    }

    fun resolveByName(project: Project, entityName: String, context: PsiElement?): Resolution =
        resolveWithAliasCandidates(project, entityName, context, emptyList())

    fun resolveClassByName(project: Project, entityName: String, context: PsiElement?): PsiClass? =
        resolveByName(project, entityName, context).psiClass

    /**
     * Merge a COMPLETE, bounded alias-search result with direct name candidates.
     * The caller owns cancellation/budget and context-sensitive cache invalidation of that search.
     * Do not pass a first-hit or truncated result as proof of uniqueness. Explicit and default
     * entity names share one namespace: two visible owners are ambiguous, neither has priority.
     */
    fun resolveWithAliasCandidates(
        project: Project,
        entityName: String,
        context: PsiElement?,
        aliasCandidates: Collection<PsiClass>,
    ): Resolution {
        if (entityName.isBlank()) return Resolution(null, emptyList())
        // T-PerfSweep30 券02（02c）：dumb 态入口早退——下方 findClasses / PsiShortNamesCache 均为
        // 索引查询，索引构建期不可信（异常被 previewAsync 吞成 null）。返回空 Resolution 即既有
        // 「实体不可解析」降级语义（调用方回落 task.* 形态），不改扫描结构与缓存。
        if (com.intellij.openapi.project.DumbService.isDumb(project)) return Resolution(null, emptyList())
        ProgressManager.checkCanceled()
        val module = contextModule(project, context)
        val scope = searchScope(project, context)
        val facade = JavaPsiFacade.getInstance(project)
        val qualified = '.' in entityName
        val direct = if (qualified) facade.findClasses(entityName, scope).toList()
            else PsiShortNamesCache.getInstance(project).getClassesByName(entityName, scope).toList()
        val visible = (direct + aliasCandidates).filter { clazz ->
            ProgressManager.checkCanceled()
            clazz.isValid && clazz.containingFile?.virtualFile?.let(scope::contains) == true
        }.distinctBy { it.qualifiedName to it.containingFile?.virtualFile?.url }

        // Resolve duplicate source/binary definitions by classpath semantics.
        // T-ShadowEntityResolve P0a：同一 FQN 的多份定义里，**工程内容优先于依赖库**。
        // 库内宿主拿不到模块上下文（库文件 getModuleForFile 恒 null），allScope 下两份都留 → 假歧义。
        // 运行期任何标准布局都是工程输出先于依赖 jar，故工程里那份才是实际生效的版本。
        // **必须逐 FQN 组内判断**：跨 FQN 的同短名是 Hibernate 真正的重名冲突，不能借此收窄。
        //
        // T-ShadowEntityResolve W6：不再以「模块上下文存在」为前提仲裁。用户真机日志显示，宿主在 jar 里时
        // 两个候选是**两个模块各一份 `.java`**（jar 内那份已被平台同 FQN 去重淘汰），内容/形态两步都失效；
        // 但两个候选的**模块身份**是确定可得的，而模块依赖方向就是运行期 classpath 序——依赖方自己的类先命中。
        // 该步只看「分属两模块且一方依赖另一方」，无关模块（compare 无从判定）仍不收窄，保持 fail-closed。
        val canonical = visible.groupBy { it.qualifiedName }.flatMap { (fqn, definitions) ->
            val byModuleOrder = if (fqn == null || definitions.size == 1) definitions
            else preferDependentModule(project, definitions)
            // 门控 context != null：无宿主＝无从判断这份同 FQN 定义是不是这条查询该用的那份，
            // 保持既有 fail-closed 契约（同文件 testContextlessDuplicateFqnIsNotArbitrarilyDeduplicated）。
            if (context == null) byModuleOrder else preferProjectContent(project, byModuleOrder)
        }
        val candidates = canonical.filter { clazz ->
            ProgressManager.checkCanceled()
            if (qualified) clazz.qualifiedName == entityName
            else {
                val explicitName = JpaMetadataReader.entityNameOf(clazz)?.takeIf { it.isNotEmpty() }
                (explicitName ?: clazz.name) == entityName
            }
        }.let { matched ->
            // T-ShadowEntityResolve P0b：同短名的**非实体类**（普通 DTO/工具类）不构成 JPA 实体歧义。
            // Hibernate 解析 `FROM <名>` 只认映射实体，普通类根本不是候选；把它们算进歧义会让一个
            // 恰好同名的 DTO 把预览打成「实体不可解析」。仅在确有映射实体时收窄——收窄后若仍有多个
            // 实体，仍按歧义 fail closed（不伪造胜者）。
            //
            // **门控 context != null**：有宿主元素＝知道这条查询在哪（只是可能拿不到模块），此时按 JPA
            // 语义收窄是对的；完全无上下文（context == null，例如外部工具按名反查）一无所知，
            // 保持既有「跨源码/库必须唯一」的 fail-closed 契约
            // （EntityClassResolverHeavyTest#testContextlessLookupRequiresUniqueAcrossSourceAndLibrary）。
            if (matched.size <= 1 || context == null) matched
            else matched.filter { JpaMetadataReader.isLikelyMappedEntityClass(it) }.ifEmpty { matched }
        }.sortedWith(compareBy({ it.qualifiedName ?: it.name.orEmpty() }, { it.containingFile?.virtualFile?.url.orEmpty() }))
        if (candidates.isNotEmpty() || context == null || scope == GlobalSearchScope.allScope(project)) {
            if (candidates.size > 1) notifyAmbiguityOnce(project, entityName, module, candidates)
            return Resolution(candidates.singleOrNull(), candidates)
        }
        // T-ShadowEntityResolve W3：**零候选拓宽**。workspace 形态（Maven 多模块同载）下，宿主 DAO 在
        // 提供方模块源码里＝模块内容，`contextModule` 是提供方模块；实体却可能只存在于**下游**业务
        // 模块（依赖方向 demo→lib，lib 看不见 demo）——用户注释掉 lib 内实体后宿主作用域内零候选，
        // 预览降级为 `task.*`（20261023 真机复现）。宿主作用域「看不见」不等于「不存在」：放宽到
        // allScope 再查一次，**全项目唯一命中且确为映射实体**才采用；多命中维持 fail closed
        // （T-EntityNameScope 防「挑错实体」的核心保护不放松）。
        val wideScope = GlobalSearchScope.allScope(project)
        val wideDirect = if (qualified) facade.findClasses(entityName, wideScope).toList()
        else PsiShortNamesCache.getInstance(project).getClassesByName(entityName, wideScope).toList()
        val wideVisible = wideDirect.filter { clazz ->
            ProgressManager.checkCanceled()
            clazz.isValid && clazz.containingFile?.virtualFile?.let(wideScope::contains) == true
        }.distinctBy { it.qualifiedName to it.containingFile?.virtualFile?.url }
        val wideCandidates = wideVisible.filter { clazz ->
            ProgressManager.checkCanceled()
            if (qualified) clazz.qualifiedName == entityName
            else {
                val explicitName = JpaMetadataReader.entityNameOf(clazz)?.takeIf { it.isNotEmpty() }
                (explicitName ?: clazz.name) == entityName
            }
        }.filter { JpaMetadataReader.isLikelyMappedEntityClass(it) }
        return if (wideCandidates.size == 1) {
            Resolution(wideCandidates.single(), wideCandidates)
        } else {
            // 零个或多个宽候选：多命中属**全项目真歧义**——按既有纪律走「歧义终态」（不挑一个 +
            // 提示一次，LRU 去重），让用户看到候选清单而不是无声降级。
            if (wideCandidates.size > 1) notifyAmbiguityOnce(project, entityName, module, wideCandidates)
            Resolution(null, wideCandidates)
        }
    }

    /**
     * T-ShadowEntityResolve：同一 FQN 的多份定义里优先保留**工程内容**（content root）里的那份。
     *
     * 调用方保证 [definitions] 属于同一个 FQN。只在「组内既有工程内容定义、又有库定义」时收窄——
     * 两份都在工程里（跨模块同名）或两份都在库里时原样返回，仍由调用方按歧义 fail closed
     * （与 T-EntityNameScope 的既有契约一致：排序只用于展示，不伪造确定性）。
     * 理由是 classpath 语义而非偏好：工程输出恒排在依赖 jar 之前。
     *
     * **W5b**：`isInContent` 不足以区分——当 jar 文件物理放在 `src/main/resources/lib/` 时，IDE 会把
     * jar 内 class 也判成内容（用户现场两个候选都是 `[工程]`），于是 `inContent.size == size` ⇒ 不收窄
     * ⇒ 歧义 ⇒ 降级。故内容判据之外再叠一层**源码形态优先**：`.java` 对上 `.class` 时取源码——这正是
     * 「工程里新增一份同包同名类」的本质形态，且与挂载方式无关。
     */
    private fun preferProjectContent(project: Project, definitions: List<PsiClass>): List<PsiClass> {
        if (definitions.size <= 1) return definitions
        val fileIndex = ProjectFileIndex.getInstance(project)
        val depsCache = HashMap<Module, Set<Module>>()
        return narrowAmbiguous(
            definitions,
            isContent = { clazz ->
                clazz.containingFile?.virtualFile?.let { fileIndex.isInContent(it) } == true
            },
            moduleKey = { clazz -> clazz.containingFile?.virtualFile?.let { fileIndex.getModuleForFile(it) } },
            dependsOn = { a, b ->
                val dep = a as? Module
                val target = b as? Module
                dep != null && target != null && depsCache
                    .getOrPut(dep) { HashSet<Module>().also { ModuleUtilCore.getDependencies(dep, it) } }
                    .contains(target)
            },
        )
    }

    /**
     * 同一 FQN 多份定义的收窄决策**纯逻辑**（与 PSI 挂载解耦，可用假数据稳定钉住）。三步依次执行，
     * **每一步只在能明确分出胜负时收窄**，否则进入下一步；全部不奏效则原样返回，交回调用方按歧义
     * fail closed（不伪造胜者）：
     *
     *  1. **工程内容优先**——组内既有内容定义又有库定义时取内容；全内容/全非内容时不收窄。
     *  2. **模块依赖方向**——恰好分属两个模块且一方（传递）依赖另一方时取**依赖方**；依赖方向
     *     即运行期 classpath 序（本模块输出先于其依赖），也是「新增同名类覆盖依赖方」的生效规则。
     *     无关模块、互为依赖、或组内含无可归属定义（库内）时此步不收窄。
     *  3. **源码形态优先**——`.java` 对上 `.class` 时取源码；全源码/全二进制时不收窄。
     *
     * 顺序有意为之：先按内容剔除库定义，模块方向才可能落在「恰好两个模块」上（含库定义时模块不可
     * 归属，方向无从判定）。
     */
    internal fun narrowAmbiguous(
        definitions: List<PsiClass>,
        isContent: (PsiClass) -> Boolean,
        moduleKey: (PsiClass) -> Any? = { null },
        dependsOn: (Any, Any) -> Boolean = { _, _ -> false },
    ): List<PsiClass> {
        if (definitions.size <= 1) return definitions

        // 1) 工程内容优先
        val content = definitions.filter(isContent)
        var current = if (content.isEmpty() || content.size == definitions.size) definitions else content
        if (current.size <= 1) return current

        // 2) 模块依赖方向（依赖方生效）
        val byModule = current.groupBy(moduleKey)
        if (byModule.size == 2 && !byModule.containsKey(null)) {
            val first = byModule.keys.elementAt(0)
            val second = byModule.keys.elementAt(1)
            val firstDependsOnSecond = dependsOn(first!!, second!!)
            val secondDependsOnFirst = dependsOn(second, first)
            if (firstDependsOnSecond != secondDependsOnFirst) {
                current = byModule[if (firstDependsOnSecond) first else second].orEmpty()
                if (current.size <= 1) return current
            }
        }

        // 3) 源码形态优先
        val sourceForm = current.filter { it.containingFile?.virtualFile?.extension == "java" }
        return if (sourceForm.isEmpty() || sourceForm.size == current.size) current else sourceForm
    }

    /**
     * T-ShadowEntityResolve W6：同 FQN 的多份定义**分属两个模块**时，优先保留**依赖方**那份。
     *
     * 依据是运行期 classpath 序：本模块的输出先于它所依赖的模块 ⇒ 依赖方自己的同名类先命中。
     * 这正是「在工程里新增一份同包同名类去覆盖依赖模块/jar 里的类」在运行期生效的规则。
     *
     * 只在能明确判定方向（一方（传递）依赖另一方，且反向不成立）时收窄；其余情形（无关模块、
     * 互为依赖、牵扯库定义导致模块不可知）原样返回，交回调用方按歧义 fail closed，不伪造胜者。
     */
    private fun preferDependentModule(project: Project, definitions: List<PsiClass>): List<PsiClass> {
        if (definitions.size <= 1) return definitions
        val fileIndex = ProjectFileIndex.getInstance(project)
        val byModule = definitions.groupBy { clazz ->
            clazz.containingFile?.virtualFile?.let { fileIndex.getModuleForFile(it) }
        }
        // 必须恰好两个模块且都可知；有库定义（module == null）时无法比方向
        if (byModule.size != 2 || byModule.containsKey(null)) return definitions
        val (moduleA, moduleB) = byModule.keys.filterNotNull()
        val depsOfA = HashSet<Module>().also { ModuleUtilCore.getDependencies(moduleA, it) }
        val depsOfB = HashSet<Module>().also { ModuleUtilCore.getDependencies(moduleB, it) }
        val aDependsOnB = depsOfA.contains(moduleB)
        val bDependsOnA = depsOfB.contains(moduleA)
        return when {
            aDependsOnB == bDependsOnA -> definitions  // 互不依赖（或互为依赖）⇒ 不猜
            aDependsOnB -> byModule[moduleA].orEmpty()  // A 依赖 B ⇒ A 是依赖方，A 的那份生效
            else -> byModule[moduleB].orEmpty()
        }
    }

    /** Shared scope for direct, alias and metadata lookup; never broadens a known module. */
    fun searchScope(project: Project, context: PsiElement?): GlobalSearchScope =
        contextModule(project, context)?.let { GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(it) }
            ?: GlobalSearchScope.allScope(project)

    /** Module identity for context-sensitive caller caches (null means no module context). */
    fun contextModule(project: Project, context: PsiElement?): Module? {
        val file = when (context) {
            null -> return null
            is PsiFile -> context
            else -> context.containingFile
        }
        val vf = file?.originalFile?.virtualFile ?: file?.virtualFile ?: return null
        return ProjectFileIndex.getInstance(project).getModuleForFile(vf)
    }

    private data class NoticeKey(val module: Module?, val entityName: String, val candidates: List<String>)

    private fun notifyAmbiguityOnce(project: Project, entityName: String, module: Module?, pool: List<PsiClass>) {
        // T-ShadowEntityResolve P2：**用户可见文案**带来源标签。同 FQN 双份时候选 FQN 完全相同，
        // 只列 FQN 等于打印「候选：A, A」——现场无法判断是「同名不同类」还是「同一个类的两份定义」。
        // 标签取「工程 / 库」，正是这两类形态的判别维度。
        // 测试缝与日志保留**原始 FQN**：既有用例按 FQN 清单断言契约（EntityClassResolverHeavyTest），
        // 标签属展示层，不进可编程契约。
        val fileIndex = ProjectFileIndex.getInstance(project)
        val plainNames = pool.map { it.qualifiedName ?: it.name ?: "?" }
        val labelledNames = pool.map { clazz ->
            val fqn = clazz.qualifiedName ?: clazz.name ?: "?"
            val inContent = clazz.containingFile?.virtualFile?.let { fileIndex.isInContent(it) } == true
            val origin = MpSqlVisualizerBundle.message(
                if (inContent) "preview.entityNameAmbiguity.originProject" else "preview.entityNameAmbiguity.originLibrary",
            )
            "$fqn [$origin]"
        }
        // T-ShadowEntityResolve 诊断：歧义时把每个候选的判定依据写进 IDE 日志（Help → Show Log in Explorer）。
        // 用户现场出现过「两个候选都是 [工程]」的读数，需要区分 isInContent / isInLibraryClasses / 形态，
        // 否则只能靠猜工程配置。日志只在此路径输出，不影响正常解析。
        runCatching {
            pool.forEachIndexed { i, clazz ->
                val vf = clazz.containingFile?.virtualFile
                LOG.info(
                    "entity-ambiguity[$i] ${clazz.qualifiedName} ext=${vf?.extension} " +
                        "inContent=${vf?.let { fileIndex.isInContent(it) }} " +
                        "inLibClasses=${vf?.let { fileIndex.isInLibraryClasses(it) }} " +
                        "inLibSource=${vf?.let { fileIndex.isInLibrarySource(it) }} " +
                        "module=${vf?.let { fileIndex.getModuleForFile(it) }?.name} " +
                        "url=${vf?.url}",
                )
            }
        }
        val key = NoticeKey(module, entityName, pool.map { it.containingFile?.virtualFile?.url.orEmpty() })
        // F102（T-PerfSweep26）：去重集改有界 LRU——此前按 (module, entity, urls) 元组在项目
        // 生命周期内只增不减（量级=项目内歧义实体数，实际很小但无上界）。超出 MAX 后淘汰最旧，
        // 代价=极老歧义项可能再次提示一次（可接受：提示是幂等告知，非状态机）。
        val seen = synchronized(project) {
            project.getUserData(AMBIGUITY_NOTIFIED_KEY) ?: object : LinkedHashMap<NoticeKey, Boolean>(
                AMBIGUITY_NOTICE_MAX + 1,
                0.75f,
                true,
            ) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NoticeKey, Boolean>?): Boolean =
                    size > AMBIGUITY_NOTICE_MAX
            }.also {
                project.putUserData(AMBIGUITY_NOTIFIED_KEY, it)
            }
        }
        val firstTime = synchronized(seen) {
            if (seen.containsKey(key)) {
                seen[key] = true // accessOrder：命中即刷新 LRU 位置
                false
            } else {
                seen[key] = true
                true
            }
        }
        if (!firstTime) return
        val scopeDescription = module?.name ?: "project / libraries"
        LOG.info("Entity name $entityName is ambiguous in $scopeDescription; no class selected: ${plainNames.joinToString()}")
        val sink = ambiguitySinkForTest
        if (sink != null) {
            sink(project, entityName, scopeDescription, plainNames)
            return
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(PREVIEW_NOTIFICATION_GROUP)
            .createNotification(
                MpSqlVisualizerBundle.message("preview.entityNameAmbiguity.title", entityName),
                MpSqlVisualizerBundle.message("preview.entityNameAmbiguity.content", scopeDescription, labelledNames.joinToString(", ")),
                NotificationType.WARNING,
            ).notify(project)
    }

    private val AMBIGUITY_NOTIFIED_KEY = Key.create<MutableMap<NoticeKey, Boolean>>("mp-visualizer.entityNameAmbiguityNotified")

    /** F102：歧义提示去重集容量上界（超出按 LRU 淘汰最旧）。 */
    private const val AMBIGUITY_NOTICE_MAX = 64
    private const val PREVIEW_NOTIFICATION_GROUP = "cn.song.mybatisplus.visualizer.preview"

    /** Callback arguments: project, entity name, scope label, all candidates (none selected). */
    @Volatile
    internal var ambiguitySinkForTest: ((Project, String, String, List<String>) -> Unit)? = null

    internal fun clearAmbiguityNotifiedForTest(project: Project) {
        synchronized(project) { project.putUserData(AMBIGUITY_NOTIFIED_KEY, null) }
    }

    /** F102 测试缝：当前去重集条目数（有界性断言用；未初始化返回 0）。 */
    internal fun ambiguityNoticeCountForTest(project: Project): Int =
        synchronized(project) { project.getUserData(AMBIGUITY_NOTIFIED_KEY)?.size ?: 0 }

    /** F102 测试缝：去重集容量上界（与实现常量同源，避免用例写死数字漂移）。 */
    internal fun ambiguityNoticeMaxForTest(): Int = AMBIGUITY_NOTICE_MAX
}
