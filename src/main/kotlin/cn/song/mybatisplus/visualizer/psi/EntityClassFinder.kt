package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.FatalErrors
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * 实体类查找器（T-M36.2：SQL 反向转换的 LambdaQueryWrapper 上下文解析）。
 *
 * 按 SQL 表名在项目中查找对应实体类（供 [cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator]
 * 生成 `LambdaQueryWrapper<Entity>` 代码），查找策略：
 * 1. **类名推断快路径**：表名 → 驼峰类名候选（含 `Entity` 后缀变体 + 去首段变体——
 *    `sys_account_role_rel` → `AccountRoleRel[Entity]`，前缀不一致常见命名直接命中）→
 *    [PsiShortNamesCache] 按类名检索 → [EntityResolver] 解析校验表名精确匹配，防同名词误配；
 * 2. **@TableName 反查慢路径**（快路径未命中时兜底，结果项目级缓存）：
 *    全量扫描项目类，匹配「@TableName 注解值」或「推断表名」等于 SQL 表名的类——
 *    扫描结果（类名/全限定名）缓存于 Project userData，以 `PsiModificationTracker.modificationCount`
 *    失效（PSI 任何修改自动重扫一次；无实体匹配场景不再每次转换全量扫描——性能修复）。
 *
 * T-JarEmbedded W2（20261016）：**实体类可能来自依赖 JAR**（共享模型模块，工程无源码）——快路径
 * 此前用 `projectScope`，这类工程的实体一个都反查不到（用户报障场景）。现补**库兜底**：项目 scope
 * 0 命中后，再按候选类名在 `notScope(projectScope)`（依赖库）找一遍，命中后同样按表名精确校验；
 * 源码命中时行为逐字不变（库兜底只在 0 命中时执行）。
 *
 * **性能口径（勿越线）**：库兜底**只做候选名快路径**——至多 4 个候选名 × 一次索引查询，且
 * [EntityResolver.resolve] 自身有「文件 + 类名 + 配置签名」三维缓存，重复解析零成本。
 * **不做**「全库逐类扫描」的慢路径库兜底：那要遍历全库类名并逐类读注解/解析（O(库类数) 的 PSI 访问），
 * 会把「表名无对应实体」这条本已反馈过卡顿的路径成本推高——性能红线，明确不做。
 * 已知边界：库里的实体若类名与表名约定不符、仅靠显式 `@TableName` 反查 → 仍找不到（与改前一致，无回归）。
 *
 * 多模块同名实体可能返回多个候选（调用方走候选选择交互）；无候选返回空列表（调用方回落
 * 字符串列名 QueryWrapper 形态）。
 */
class EntityClassFinder(private val project: Project) {

    /**
     * 按表名查找实体类（须在 ReadAction 内调用——PSI 索引访问）。
     *
     * @param tableName SQL 表名（解析器已剥前缀/反引号）
     * @return 表名精确匹配的实体类列表（按候选类名顺序：纯驼峰名优先、Entity 后缀次之）
     */
    fun find(tableName: String): List<PsiClass> {
        if (tableName.isBlank()) return emptyList()
        // T-PerfSweep30 券02（02c）：dumb 态入口早退返回空列表——本方法覆盖的全部索引访问
        // （快路径 PsiShortNamesCache.getClassName 三个点 + @TableName 反查全量扫描 + 库扫描
        // 缓存回取 restoreMatched）在索引构建期都不可信，返回空列表即既有「无实体 → 字符串列名
        // 回落」语义，不改扫描结构与缓存。
        if (DumbService.isDumb(project)) return emptyList()
        val resolver = EntityResolver()
        // 源码优先：既有行为逐字不变（项目 scope 命中即返回）
        findByCandidateNames(tableName, resolver, GlobalSearchScope.projectScope(project))
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        // T-JarEmbedded W2：实体类可能来自**依赖 JAR**（无源码）——项目 scope 看不见，补库兜底。
        // 只在源码 0 命中时才跑，故不会改变既有工程的解析结果。
        findByCandidateNames(tableName, resolver, libraryScope())
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        // 项目慢路径（@TableName 反查，既有缓存）
        findByTableNameAnnotationCached(tableName).takeIf { it.isNotEmpty() }?.let { return it }
        // T-JarEmbedded 优化方案 1：库内**非常规命名**的实体（类名与表名约定不符，只能靠 @TableName 认出）
        // 查「全库一次性扫描」的缓存；未就绪则投递一次后台扫描，**本次原样返回空**（不阻塞、不等待）
        return findByLibrarySweepCached(tableName)
    }

    /**
     * 库内实体的一次性全库扫描（T-JarEmbedded 优化方案 1）。
     *
     * 为什么需要它：库里的实体若类名与表名约定不符（`LegacyBooking` ↔ `t_booking`），快路径的候选名
     * 一个都不沾，只能靠 `@TableName` 反查；而「扫全库类名 + 逐类读注解」是 O(classpath 类数) 的
     * PSI 访问，直接放在反查热路径上会拖慢「表名根本没有对应实体」这条最常见也最敏感的路径。
     *
     * 做法（照 [cn.song.mybatisplus.visualizer.psi.ColumnNameCalibrator] 的既有护栏模式）：把全库扫描
     * 挪到**后台一次性执行**，结果按「表名 → 类」落**项目级缓存**（项目结构版本失效）；反查时只读
     * 缓存，未就绪就投递一次并**本次原样返回空**——由调用方按既有回落（字符串列名形态）走，
     * 下一次反查（缓存已就绪）即可命中。
     *
     * **本扫描是本文件里唯一会遍历整条 classpath 的路径，必须守住「有界 + 可取消」**——它的形态与
     * 20261008 真机冻结事故（T-GutterDupFreeze）同源：全量短名扫描跑在阻塞式 `ReadAction.compute`
     * 里、读锁全程不放 ⇒ EDT 等读许可被冻住（实测 70 秒、空白窗关不掉）。当时的收口是
     * [cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep]（上限 + 取消检查 + 命中即止）。
     * 本方法照同一口径：循环内定期 `ProgressManager.checkCanceled()`（平台需要写锁时可中断）+
     * 病态上限 [LIBRARY_SWEEP_SCAN_LIMIT]；另**只认 `@TableName`**，不再按类名推断表名（见方法内注释）。
     *
     * 四道闸：设置开关（可关）/ 本结构版本只投递一次 / 投递冷却窗口 / 全局在途上限。
     */
    private fun findByLibrarySweepCached(tableName: String): List<PsiClass> {
        val cached = librarySweepCache()?.tables?.get(tableName.lowercase())
        if (cached != null) return cached.mapNotNull { restoreMatched(it) }
        requestLibrarySweep()
        return emptyList()
    }

    /** 当前项目结构版本的库扫描缓存（版本不符返回 null，视为未就绪）。 */
    private fun librarySweepCache(): LibrarySweepCache? {
        val current = projectStructureVersion()
        return project.getUserData(LIBRARY_SWEEP_CACHE_KEY)?.takeIf { it.count == current }
    }

    /**
     * 库扫描的失效基准＝**项目结构版本**（[ProjectRootModificationTracker]）。
     *
     * 实测口径（`EntityClassFinderIntegrationTest.testLibrarySweepCacheSurvivesSourceEdit` 钉住）：
     * **改已有文件正文不递增**（打字不作废缓存），**文件增删/依赖与模块根变化递增**。
     * 不用 [PsiModificationTracker] 的理由见 [LibrarySweepCache]。
     */
    private fun projectStructureVersion(): Long =
        ProjectRootModificationTracker.getInstance(project).modificationCount

    /**
     * 投递一次「全库 @TableName 扫描」（方案 1）。四道闸任一未过即本次不投递（不阻塞、不等待）：
     *  1. 设置开关关闭（[isLibrarySweepEnabled]）；
     *  2. 本结构版本已投递过（[markSweepDispatched]）；
     *  3. 投递冷却窗口未过（[cooldownElapsed]）；
     *  4. 全局在途上限（[MAX_IN_FLIGHT_SWEEP]，防多项目同开时并发扫库）。
     *
     * 线程口径照 [ColumnNameCalibrator]：BGT 执行、内部自带 ReadAction、异常在下游被吞。
     */
    private fun requestLibrarySweep() {
        if (!isLibrarySweepEnabled()) return
        // T-PerfSweep30 券02（02c）：dumb 态不投递后台扫描（runLibrarySweepNow 内另有早退兜底，
        // 此处先行拦下可避免白占「本版本已投递」登记）。
        if (DumbService.isDumb(project)) return
        if (!markSweepDispatched()) return
        if (inFlightSweep.get() >= MAX_IN_FLIGHT_SWEEP) return
        sweepDispatchForTest?.let { sink ->
            sink(project)
            return
        }
        val app = ApplicationManager.getApplication() ?: return
        inFlightSweep.incrementAndGet()
        val future = runCatching {
            app.executeOnPooledThread {
                try {
                    // 不在这里包 ReadAction：runLibrarySweepNow 自带分片读动作，
                    // 外层再包一层会把读锁变回「整趟持有」，分片失效
                    val entries = runLibrarySweepNow()
                    // 触顶未扫完（-1）：本次作废，允许后续未命中重投——否则「本版本已投递过」
                    // 会把这一版彻底锁死，库内实体再也扫不出来
                    if (entries < 0) clearLibrarySweepDispatched()
                } catch (t: Throwable) {
                    // 被写动作抢占而取消（PCE）/ 关工程 / 任何取数异常：同样作废并允许重投
                    clearLibrarySweepDispatched()
                    if (FatalErrors.isFatal(t)) throw t // T-FatalPassthrough：JVM 级致命错误透传
                } finally {
                    inFlightSweep.decrementAndGet()
                }
            }
        }.getOrNull()
        // 没投出去（无 Application / 排程抛错）→ 立刻归还计数，避免在途数泄漏把后续投递全挡掉
        if (future == null) inFlightSweep.decrementAndGet()
    }

    /**
     * 本项目结构版本是否可投递库扫描；可投递则登记并返回 true。
     *
     * 两个条件：本版本尚未投递过（[LibrarySweepCache.dispatched]）+ 距上次投递已过冷却窗口
     * （[sweepCooldownMs]，照 [ColumnNameCalibrator] 的表名冷却窗口）——只有「每版本一次」不够：
     * 依赖/模块频繁变化时每个版本都会投一次，叠一道时间冷却把投递频率钉住。
     */
    private fun markSweepDispatched(): Boolean {
        if (!cooldownElapsed()) return false
        val current = projectStructureVersion()
        val existing = project.getUserData(LIBRARY_SWEEP_CACHE_KEY)
        if (existing != null && existing.count == current) {
            if (existing.dispatched) return false
            existing.dispatched = true
        } else {
            project.putUserData(LIBRARY_SWEEP_CACHE_KEY, LibrarySweepCache(current, mutableMapOf(), dispatched = true))
        }
        project.putUserData(LIBRARY_SWEEP_DISPATCH_AT_KEY, sweepNow())
        return true
    }

    /**
     * 投递冷却窗口是否已过（无上次扫描记录视为已过）。
     *
     * 窗口取 `max(配置窗口, 上趟实际耗时)`：扫描本身是秒级到十秒级（实测只挂 JDK 就 4.4s），
     * 固定 5s 的冷却短于一趟扫描 ⇒ 结构频繁变化时（重构中反复增删文件）会一趟接一趟连跑。
     * 用「上趟耗时」当窗口下界，等于强制「两趟之间至少歇一趟」。
     */
    private fun cooldownElapsed(): Boolean {
        val last = project.getUserData(LIBRARY_SWEEP_DISPATCH_AT_KEY) ?: return true
        val lastCost = project.getUserData(LIBRARY_SWEEP_LAST_COST_KEY) ?: 0L
        return sweepNow() - last >= maxOf(sweepCooldownMs, lastCost)
    }

    /** 当前时间（测试缝优先，便于把冷却窗口的用例做成确定性断言而不是时序抖动）。 */
    private fun sweepNow(): Long = sweepClockForTest?.invoke() ?: System.currentTimeMillis()

    /** 清掉「本版本已投递」登记（扫描被取消/触顶时调用，允许后续未命中重投）。 */
    private fun clearLibrarySweepDispatched() {
        project.getUserData(LIBRARY_SWEEP_CACHE_KEY)?.dispatched = false
    }

    /** 扫描开关（项目设置不可用时的兜底默认开）；测试缝优先。 */
    private fun isLibrarySweepEnabled(): Boolean =
        sweepEnabledForTest
            ?: runCatching { PluginSettings.getInstance().state.libraryEntitySweepEnabled }.getOrDefault(true)

    /** 单趟扫描上限（测试缝优先）。 */
    private fun sweepScanLimit(): Int = sweepScanLimitForTest ?: LIBRARY_SWEEP_SCAN_LIMIT

    /**
     * 执行一次全库 `@TableName` 扫描并落缓存。
     *
     * **自带分片读动作，调用方不要再在外面包一层 `ReadAction`**——那会把读锁重新变成「整趟持有」，
     * 分片就白做了。原因：整趟枚举是 O(classpath 类数)（实测只挂 JDK 就有 23k 短名 / 27k 类、单趟
     * 约 4.5s），若全程持读锁，等待中的写动作（打字→文档提交、VFS 刷新、索引写阶段）就得干等，
     * 实测被阻塞 5.5s——这正是 20261008 冻结事故（T-GutterDupFreeze）的形态。分片后每次持锁只有
     * 一片的量级（[LIBRARY_SWEEP_SLICE] 个短名 ≈ 百毫秒），片间释放读锁让等待中的写动作立刻进入。
     *
     * 与项目慢路径 [scanAllClassesByTableName] 的区别：scope 是**整条 classpath**、结果形态是
     * 「一次扫全部表」（而非只找一张表）——这样扫描成本摊到「每个结构版本一次」，而不是「每张表一次」。
     * 方案 2 的显式触发也复用本方法。
     *
     * **只认 `@TableName`，不做「按类名推断表名」的兜底**：后者对任何类名都能推出非空表名，
     * 等于把整条 classpath（含 JDK）的类全建成「表」——实测只挂 JDK 就有 27k 类 → 20k 条目。
     * 而「类名与表名相符」的库内实体已由快路径的库兜底（W2）覆盖，本扫描存在的意义只有
     * 「类名与表名不符、只能靠 `@TableName` 认出」那一类。
     *
     * @return 扫到的「表名 → 类」条目数；**-1 = 触顶未扫完**（不落缓存，调用方应允许重投）
     */
    internal fun runLibrarySweepNow(): Int {
        // T-PerfSweep30 券02（02c）：dumb 态早退——短名清单与逐名检索（PsiShortNamesCache 两处）
        // 都是索引访问。返回 -1（触顶语义：未扫完 → 不落缓存，调用方允许重投），smart 恢复后
        // 重新投递即可，不改分片扫描结构与冷却窗口。
        if (DumbService.isDumb(project)) return -1
        val startedMs = sweepNow()
        // 版本号**先取**：扫描期间项目结构若变化，落盘的 count 仍是开始时的版本 ⇒ 缓存立即
        // 被判为过期（下次反查重投），不会把「基于旧状态的结果」当成当前版本的有效缓存
        val startedAt = projectStructureVersion()
        val scope = libraryScope()
        val limit = sweepScanLimit()
        val tables = mutableMapOf<String, MutableList<MatchedClass>>()

        // 短名清单本身要读索引：单独一个短读动作，不把它的持锁摊进循环
        val names = ReadAction.compute<Array<String>, RuntimeException> {
            PsiShortNamesCache.getInstance(project).getAllClassNames()
        }

        var scanned = 0
        var truncated = false
        var from = 0
        while (from < names.size) {
            // 片间取消检查：工程关闭 / 平台请求取消时尽早退出（T-GutterDupFreeze 口径）
            ProgressManager.checkCanceled()
            // 上限按**剩余预算**切分（不是按片判断）：否则上限小于一片时会被整片吞掉、形同不设限
            val remaining = limit - scanned
            if (remaining <= 0) {
                truncated = true
                break
            }
            val to = minOf(from + LIBRARY_SWEEP_SLICE, from + remaining, names.size)
            ReadAction.compute<Unit, RuntimeException> {
                val namesCache = PsiShortNamesCache.getInstance(project)
                for (j in from until to) {
                    val shortName = names[j]
                    scanned++
                    if (shortName.endsWithIgnoreCase("VO") || shortName.endsWithIgnoreCase("DTO")) continue
                    for (clazz in namesCache.getClassesByName(shortName, scope)) {
                        if (clazz.isInterface || clazz.isEnum || clazz.isAnnotationType) continue
                        if (isVoDto(clazz)) continue
                        val table = tableNameAnnotation(clazz) ?: continue
                        if (table.isBlank()) continue
                        tables.getOrPut(table.lowercase()) { mutableListOf() } +=
                            MatchedClass(shortName, clazz.qualifiedName)
                    }
                }
            }
            from = to
        }
        // 记录本趟耗时与完成时刻：冷却窗口用它们当基准（见 [cooldownElapsed]）。触顶也要记——代价已经付了。
        // **直接执行的扫描（如弹窗「在依赖库中查找」）同样要进冷却窗口**，否则紧接着的下一次未命中
        // 会立刻再投一趟后台扫描，白跑。下界取 max(1ms, 实测)：小夹具上耗时可能取整成 0。
        project.putUserData(LIBRARY_SWEEP_DISPATCH_AT_KEY, sweepNow())
        project.putUserData(LIBRARY_SWEEP_LAST_COST_KEY, maxOf(1L, sweepNow() - startedMs))
        // 触顶＝「没扫完」而非「确认没有」：不落缓存（残缺结果被当成完整结果长期复用更糟）
        if (truncated) return -1
        project.putUserData(
            LIBRARY_SWEEP_CACHE_KEY,
            LibrarySweepCache(startedAt, tables, dispatched = true),
        )
        return tables.size
    }

    /** 按候选类名在给定 scope 内找表名精确匹配的实体（快路径）。 */
    private fun findByCandidateNames(
        tableName: String,
        resolver: EntityResolver,
        scope: GlobalSearchScope,
    ): List<PsiClass> {
        val result = mutableListOf<PsiClass>()
        for (candidateName in candidateClassNames(tableName)) {
            val classes = PsiShortNamesCache.getInstance(project).getClassesByName(candidateName, scope)
            for (clazz in classes) {
                // T-M36.3：VO/DTO 类排除——候选类名以 VO/DTO 结尾的（如 UserVO）不参与转换
                // （表名校验已覆盖绝大多数；此为命名惯例的显式防御）
                if (isVoDto(clazz)) {
                    continue
                }
                // 解析表名精确匹配（大小写不敏感——表名大小写因方言/平台而异）；
                // resolve 异常情况返回空表名 → 自然排除
                if (resolver.resolve(clazz).tableName.equals(tableName, ignoreCase = true)) {
                    result.add(clazz)
                }
            }
        }
        return result
    }

    /**
     * 库兜底 scope（T-JarEmbedded W2）：`allScope`（项目内容 + 依赖库 + SDK）。
     *
     * 为什么不用 `notScope(projectScope)`：那个 scope 会把 `isSearchInModuleContent` 一并取反，
     * 挂在模块上的**库根**可能被一起排除（实测：库源码根里的实体查不到）。`allScope` 语义明确、
     * 覆盖依赖库；代价只是项目类被再查一遍的几次索引查询——而项目类在上一步已经查过且 0 命中，
     * 解析结果又有 [EntityResolver] 的三维缓存，重复代价可忽略。
     */
    private fun libraryScope(): GlobalSearchScope = GlobalSearchScope.allScope(project)

    /**
     * @TableName 反查（缓存化兜底）：实体命名与表名驼峰不一致时（如 `AccountRoleRelEntity`
     * ↔ `sys_account_role_rel`）按类名推断无法命中——全量扫描项目全部类短名，匹配
     * 「@TableName 注解值」或「推断表名（[EntityResolver.inferDecoratedTableName]）」等于 SQL 表名
     * （大小写不敏感）的类。
     *
     * **性能（用户反馈：无实体场景转换卡顿）**：全量扫描为 O(项目类数) PSI 索引访问，此前每次
     * 转换都执行（快路径 0 命中时必触发，JOIN 每张副表各一次）——现把扫描结果（类全限定名）
     * 缓存于 Project userData：同表名后续 find 直接按类名重取（几次索引查询）；缓存以
     * `PsiModificationTracker.modificationCount` 失效（PSI 修改后自动重扫一次）。
     * 缓存存类名字符串而非 PsiClass 引用（PSI 对象随文件修改失效，引用长期持有不安全）。
     * 须在 ReadAction 内调用。
     */
    private fun findByTableNameAnnotationCached(tableName: String): List<PsiClass> {
        val entry = reverseLookupCache()
        val key = tableName.lowercase()
        val cached = entry.names[key]
        if (cached != null) {
            return cached.mapNotNull { restoreMatched(it) }
        }
        entry.scans++
        val matched = scanAllClassesByTableName(tableName)
        entry.names[key] = matched.map { MatchedClass(it.name ?: return@map null, it.qualifiedName) }
            .filterNotNull()
        return matched
    }

    /** 缓存条目按类名重取 PsiClass（shortName 索引查询 + qualifiedName 精确比对防同名误配）。 */
    private fun restoreMatched(matched: MatchedClass): PsiClass? {
        // 用 allScope：慢路径缓存里是**项目类**，而库扫描缓存（方案 1）里是**依赖库里的类**——
        // 只查 projectScope 会让库扫描结果永远取不回来（qualifiedName 精确比对仍防同名误配）。
        val scope = GlobalSearchScope.allScope(project)
        return PsiShortNamesCache.getInstance(project)
            .getClassesByName(matched.shortName, scope)
            .firstOrNull { it.qualifiedName == matched.qualifiedName && !isVoDto(it) }
    }

    /** 全量扫描匹配表名的类（P2：跳过接口/枚举/注解类——MP 实体恒为 class）。 */
    private fun scanAllClassesByTableName(tableName: String): List<PsiClass> {
        val cache = PsiShortNamesCache.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val result = mutableListOf<PsiClass>()
        var scanned = 0
        for (shortName in cache.getAllClassNames()) {
            // 取消检查（T-GutterDupFreeze 口径，与库扫描同一收口）：全量短名遍历跑在阻塞式
            // ReadAction 里，读锁全程不放——平台需要写锁时须能中断，否则冻住 EDT。
            if (scanned % LIBRARY_SWEEP_CANCEL_INTERVAL == 0) ProgressManager.checkCanceled()
            scanned++
            // 短名快筛：VO/DTO 结尾的直接跳过（避免逐类检查）
            if (shortName.endsWithIgnoreCase("VO") || shortName.endsWithIgnoreCase("DTO")) continue
            for (clazz in cache.getClassesByName(shortName, scope)) {
                // P2：接口/枚举/注解类不可能是 MP 实体，跳过（减少 PSI 访问）
                if (clazz.isInterface || clazz.isEnum || clazz.isAnnotationType) continue
                if (isVoDto(clazz)) continue
                val className = clazz.name ?: continue
                val annotated = tableNameAnnotation(clazz)
                // T-TS-07：显式传候选类所属项目（= 本 finder 的项目，scope 亦为 projectScope）——
                // 反查表名的兜底推断不再受「首个打开项目」影响；clazz 是 PSI，project 必可得
                val resolvedTable = annotated ?: EntityResolver.inferDecoratedTableName(className, clazz.project)
                if (resolvedTable.equals(tableName, ignoreCase = true)) {
                    result.add(clazz)
                }
            }
        }
        return result.sortedBy { it.name }
    }

    /** 类级 @TableName 注解值；无注解或值非字符串返回 null（短名兜底——注解类不可解析环境仍可识别）。 */
    private fun tableNameAnnotation(clazz: PsiClass): String? {
        val annotations = clazz.modifierList?.annotations ?: return null
        val annotation = annotations.firstOrNull { it.qualifiedName == TABLE_NAME }
            ?: annotations.firstOrNull { it.nameReferenceElement?.referenceName == "TableName" }
            ?: return null
        val value = annotation.findAttributeValue("value") as? PsiLiteralExpression ?: return null
        return value.value as? String
    }

    private fun isVoDto(clazz: PsiClass): Boolean =
        clazz.name?.endsWithIgnoreCase("VO") == true || clazz.name?.endsWithIgnoreCase("DTO") == true

    private fun String.endsWithIgnoreCase(suffix: String): Boolean =
        endsWith(suffix, ignoreCase = true)

    /**
     * 候选类名：表名驼峰（`car_device_info` → `CarDeviceInfo`，复用
     * [cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator.entityClassName]）、`Entity`
     * 后缀变体（`CarDeviceInfoEntity`——MP 项目实体命名惯例），以及**去首段变体**
     * （`sys_account_role_rel` → `AccountRoleRel[Entity]`——`sys_`/`t_`/`biz_` 等前缀不一致的
     * 常见命名直接快路径命中，避免全量反查）；去重。
     */
    internal fun candidateClassNames(tableName: String): List<String> {
        val pascal = cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator.entityClassName(tableName)
        val noFirstSeg = cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator
            .entityClassName(tableName.substringAfter('_'))
        return listOf(pascal, "${pascal}Entity", noFirstSeg, "${noFirstSeg}Entity").distinct()
    }

    /** 项目级反查缓存（Project userData；PsiModificationTracker 失效）。 */
    private data class ReverseLookupCache(
        /** 扫描时的 modificationCount——PSI 修改后失效重建。 */
        val count: Long,
        /** 表名（小写）→ 匹配类（短名 + 全限定名）。 */
        val names: MutableMap<String, List<MatchedClass>>,
        /** 全量扫描次数（测试访问器断言缓存命中不再扫描）。 */
        var scans: Int = 0,
    )

    /** 缓存条目：短名（索引查询键）+ 全限定名（精确比对防同名误配）。 */
    private data class MatchedClass(val shortName: String, val qualifiedName: String?)

    /**
     * 库内实体的一次性全库扫描缓存（T-JarEmbedded 优化方案 1；Project userData）。
     *
     * 失效基准＝**项目结构版本**（[ProjectRootModificationTracker]）：模块/依赖/库根变化、以及
     * **文件增删**才递增；**改文件正文（打字）不递增**（实测：改已有文件正文后 PSI 版本涨、
     * 本计数不变；新增文件则本计数会涨——新增文件属结构变化，可接受）。
     * 不用 [PsiModificationTracker]：它对任何 PSI 变化都递增（实测改一个文件 4→16），会把
     * 「每版本一次」退化成「每次编辑一次」反复重扫整条 classpath。
     */
    private data class LibrarySweepCache(
        /** 扫描时的项目结构版本——结构变化后失效重建。 */
        val count: Long,
        /** 表名（小写）→ 库内匹配类（短名 + 全限定名）。 */
        val tables: Map<String, List<MatchedClass>>,
        /** 本版本是否已投递过后台扫描（每项目版本一次）。 */
        var dispatched: Boolean = false,
    )

    private fun reverseLookupCache(): ReverseLookupCache {
        // 失效计数：PsiModificationTracker（IPGP 中位于 com.intellij.psi.util 包，编译实证；
        // PSI 任何修改递增，缓存自动失效重扫）
        val current = PsiModificationTracker.getInstance(project).modificationCount
        val existing = project.getUserData(REVERSE_LOOKUP_CACHE_KEY)
        if (existing != null && existing.count == current) return existing
        val fresh = ReverseLookupCache(current, mutableMapOf())
        project.putUserData(REVERSE_LOOKUP_CACHE_KEY, fresh)
        return fresh
    }

    companion object {
        private const val TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName"

        private val REVERSE_LOOKUP_CACHE_KEY = Key.create<ReverseLookupCache>("mp-visualizer.reverseLookupCache")

        /** 库扫描缓存键（T-JarEmbedded 方案 1）。 */
        private val LIBRARY_SWEEP_CACHE_KEY = Key.create<LibrarySweepCache>("mp-visualizer.librarySweepCache")

        /** 上次投递库扫描的时间戳（冷却窗口用）。与缓存条目分开存——缓存条目会随项目结构版本重建。 */
        private val LIBRARY_SWEEP_DISPATCH_AT_KEY = Key.create<Long>("mp-visualizer.librarySweepDispatchAt")

        /** 上趟库扫描的实际耗时（冷却窗口下界，见 [cooldownElapsed]）。 */
        private val LIBRARY_SWEEP_LAST_COST_KEY = Key.create<Long>("mp-visualizer.librarySweepLastCostMs")

        /**
         * 取消检查间隔（每 N 个短名查一次）——`checkCanceled` 自身有开销，不必逐个查；
         * 口径与 [cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep] 一致（T-GutterDupFreeze 收口）。
         * 目前由项目慢路径 [scanAllClassesByTableName] 使用（库扫描改走分片，见 [LIBRARY_SWEEP_SLICE]）。
         */
        internal const val LIBRARY_SWEEP_CANCEL_INTERVAL = 64

        /**
         * 库扫描的分片大小（每片一个**独立**的短读动作）。
         *
         * 为什么不是「一整趟一个读动作 + 取消检查」：实测那样做等待中的写动作仍被阻塞 5.5s——
         * `ReadAction.compute` 是整趟持锁，取消检查没有可取消的进度上下文，平台不会替我们让锁。
         * 分片后读锁在片间**真正释放**，等待中的写动作（打字→文档提交）能在片间立刻进入，
         * 把「一次等 N 秒」压成「每次等约一片」。取 500：按实测约 0.17ms/短名估算，单片持锁 ≈ 百毫秒级。
         */
        internal const val LIBRARY_SWEEP_SLICE = 500

        /**
         * 单趟库扫描允许遍历的短名数上限——**病态兜底，不是常规截断线**。
         *
         * 取 20 万：classpath 级枚举（含 JDK）常态就是 2~3 万个短名，任何「常规量级」的上限都会把
         * 真实工程一律截断、让功能静默失效；这里只钉住索引异常/病态工程的最坏代价，
         * 真正的响应性保障是取消检查（平台需要写锁时可中断）。
         */
        internal const val LIBRARY_SWEEP_SCAN_LIMIT = 200_000

        /** 投递冷却窗口（照 [ColumnNameCalibrator] 的 `PREFETCH_COOLDOWN_MS`）。 */
        internal const val LIBRARY_SWEEP_COOLDOWN_MS = 5_000L

        /** 测试缝：扫描上限替身（null = [LIBRARY_SWEEP_SCAN_LIMIT]）——确定性验证「触顶不落缓存」。 */
        internal var sweepScanLimitForTest: Int? = null

        /** 测试缝：投递冷却窗口（0 = 关闭冷却）。 */
        internal var sweepCooldownMs: Long = LIBRARY_SWEEP_COOLDOWN_MS

        /** 测试缝：时钟替身（null = `System.currentTimeMillis()`）——把冷却窗口的用例做成确定性断言。 */
        internal var sweepClockForTest: (() -> Long)? = null

        /** 全局在途库扫描上限（方案 1；多项目同开时防并发扫库，照 ColumnNameCalibrator 的在途闸）。 */
        private const val MAX_IN_FLIGHT_SWEEP = 1

        /** 在途库扫描计数。 */
        private val inFlightSweep = java.util.concurrent.atomic.AtomicInteger(0)

        /** 测试缝：库扫描开关替身（null = 读设置）。 */
        internal var sweepEnabledForTest: Boolean? = null

        /** 测试缝：库扫描**投递**替身（null = 投递 executeOnPooledThread）——照 ColumnNameCalibrator.prefetchSinkForTest。 */
        internal var sweepDispatchForTest: ((Project) -> Unit)? = null

        /**
         * 测试缝：注入一条库扫描结果（验「缓存 → find 命中」这条接线）。
         *
         * 真扫由 `runLibrarySweepNow()` 的正向用例覆盖（真实库类）；这里注入是为了在**不依赖**
         * 「库里恰好有个 @TableName 与类名不符的类」的前提下，确定性验证缓存命中路径。
         */
        internal fun seedLibrarySweepForTest(project: Project, table: String, clazz: PsiClass) {
            val current = ProjectRootModificationTracker.getInstance(project).modificationCount
            val existing = project.getUserData(LIBRARY_SWEEP_CACHE_KEY)
            val tables = if (existing != null && existing.count == current) {
                existing.tables.toMutableMap()
            } else {
                mutableMapOf()
            }
            val shortName = clazz.name ?: return
            tables[table.lowercase()] = listOf(MatchedClass(shortName, clazz.qualifiedName))
            project.putUserData(LIBRARY_SWEEP_CACHE_KEY, LibrarySweepCache(current, tables, dispatched = true))
        }

        /** 测试访问器：本版本是否已投递过库扫描（每项目版本一次）。 */
        internal fun sweepDispatchedForTest(project: Project): Boolean =
            project.getUserData(LIBRARY_SWEEP_CACHE_KEY)?.dispatched ?: false

        /** 测试访问器：库扫描缓存条目数（0 = 未扫或库里没有可识别实体）。 */
        internal fun sweepTableCountForTest(project: Project): Int =
            project.getUserData(LIBRARY_SWEEP_CACHE_KEY)?.tables?.size ?: 0

        /** 测试访问器：库扫描缓存里的表名集合（判「某表是否被索引」，不受夹具外的类干扰）。 */
        internal fun sweepTablesForTest(project: Project): Set<String> =
            project.getUserData(LIBRARY_SWEEP_CACHE_KEY)?.tables?.keys ?: emptySet()

        /** 测试访问器：清掉「本版本已投递」登记（模拟扫描被取消/触顶后的可重投状态）。 */
        internal fun clearLibrarySweepDispatchedForTest(project: Project) {
            project.getUserData(LIBRARY_SWEEP_CACHE_KEY)?.dispatched = false
        }

        /** 清除库扫描缓存（与 [clearReverseLookupCache] 同步清）；连投递时间戳与耗时一起清，「清除缓存」即真正的重来。 */
        internal fun clearLibrarySweepCache(project: Project) {
            project.putUserData(LIBRARY_SWEEP_CACHE_KEY, null)
            project.putUserData(LIBRARY_SWEEP_DISPATCH_AT_KEY, null)
            project.putUserData(LIBRARY_SWEEP_LAST_COST_KEY, null)
        }

        /** 测试访问器：项目级全量反查扫描次数（缓存命中后不再增长）。 */
        internal fun scanCountForTest(project: Project): Int =
            project.getUserData(REVERSE_LOOKUP_CACHE_KEY)?.scans ?: 0

        /** 反查缓存信息（设置页展示）：返回（缓存表名条目数, 缓存类名总数）；无缓存 → (0, 0)。 */
        internal fun reverseLookupCacheInfo(project: Project): Pair<Int, Int> {
            val entry = project.getUserData(REVERSE_LOOKUP_CACHE_KEY) ?: return 0 to 0
            return entry.names.size to entry.names.values.sumOf { it.size }
        }

        /** 清除项目级反查缓存（设置页「清除缓存」；下次 find 重新全量扫描）。 */
        internal fun clearReverseLookupCache(project: Project) {
            project.putUserData(REVERSE_LOOKUP_CACHE_KEY, null)
            // T-JarEmbedded 方案 1：库扫描缓存同类，一并清（否则「清除缓存」后库内实体仍按旧结果命中）
            clearLibrarySweepCache(project)
        }

        /**
         * 反查缓存大小估算（设置页展示 KB/MB）：缓存内容为字符串（表名/类短名/全限定名），
         * 按 UTF-16 字符 2 字节 + 每条目/每值固定开销近似——展示用，非精确 JVM 内存。
         */
        internal fun reverseLookupCacheSizeBytes(project: Project): Long {
            val entry = project.getUserData(REVERSE_LOOKUP_CACHE_KEY) ?: return 0L
            var bytes = 0L
            for ((table, classes) in entry.names) {
                bytes += table.length * 2L + 16
                for (matched in classes) {
                    val nameLen = matched.shortName.length + (matched.qualifiedName?.length ?: 0)
                    bytes += nameLen * 2L + 24
                }
            }
            return bytes
        }
    }
}
