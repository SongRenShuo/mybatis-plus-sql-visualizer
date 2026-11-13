package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.FatalErrors
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.impl.java.stubs.index.JavaAnnotationIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实体类查找器（T-M36.2：SQL 反向转换的 LambdaQueryWrapper 上下文解析）。
 *
 * 按 SQL 表名在项目中查找对应实体类（供 [cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator]
 * 生成 `LambdaQueryWrapper<Entity>` 代码），查找策略：
 * 1. **类名推断快路径**：表名 → 驼峰类名候选（含 `Entity` 后缀变体 + 去首段变体——
 *    `sys_account_role_rel` → `AccountRoleRel[Entity]`，前缀不一致常见命名直接命中）→
 *    [PsiShortNamesCache] 按类名检索 → [EntityResolver] 解析校验表名精确匹配，防同名词误配；
 * 2. **@TableName 反查慢路径**（快路径未命中时兜底，结果项目级缓存）：
 *    全量扫描项目类，匹配「@TableName 注解值」或「推断表名」等于 SQL 表名的类。
 *    扫描**一次建全表索引**（表名 → 类名/全限定名）缓存于 Project userData，任意表名查询 O(1)；
 *    失效基准＝项目结构版本（[ProjectRootModificationTracker]）+ TTL（见 [ReverseLookupCache] 注释：
 *    挂 `PsiModificationTracker` 会被我们自己写 exec 文件/临时 PSI 顶掉，导致每次打开预览都重扫）。
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
                        // 依赖库里的实体同样按各 ORM 的表名注解认（MP `@TableName` / JPA `@Table(name)`）
                        val table = TableNameAnnotations.tableNameOfClass(clazz) ?: continue
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
     * **性能（用户反馈：无实体场景转换卡顿 / 打开预览仍慢）**：全量扫描为 O(项目类数) PSI 索引访问。
     * 现在**一次扫描建「表名 → 类」全表索引**（不是「查一个表名扫一遍」——实测 16804 类的工程一趟
     * 3.8s，用户每换一张表再吃一次），缓存于 Project userData，任意表名查询 O(1)；索引建立中时
     * 后来的调用方**不等待**，立即返回空（预览照常打开、上下文降级），下一次查询即命中。
     * 失效基准＝项目结构版本 + TTL（[ReverseLookupCache] 有实测依据）。
     * 缓存存类名字符串而非 PsiClass 引用（PSI 对象随文件修改失效，引用长期持有不安全）。
     * 须在 ReadAction 内调用。
     */
    private fun findByTableNameAnnotationCached(tableName: String): List<PsiClass> {
        val entry = reverseLookupCache()
        ensureIndexBuilt(entry)
        var hit = entry.index[tableName.lowercase()]
        // 跨 ORM 兜底：注解索引只覆盖「带表名注解」的实体（MP `@TableName`、JPA `@Table`）。工程里是
        // MBG/iBatis/Kotlin 或自定义映射、又没有注解时，这里会**全部未命中**——按查询做一次
        // 「全项目推断扫描」并合并进同一索引（只此一次，之后同结构版本都命中）。
        // 判据用「索引里是否有该表」，不是「有没有注解」：一个工程里MP 与 MBG 混用时，MP 部分走
        // 注解索引、MBG 部分靠这次回补。
        if (hit == null && !entry.inferredScanDone) {
            ensureInferredScan(entry)
            hit = entry.index[tableName.lowercase()]
        }
        // 顺序契约不变：旧实现按类名排序返回（多候选时调用方按此顺序处理）
        return (hit ?: emptyList())
            .sortedBy { it.shortName }
            .mapNotNull { restoreMatched(it) }
    }

    /**
     * 「全项目推断」回补（跨 ORM 兜底）：只在**注解索引未命中**时触发，结果合并进同一索引并置
     * [ReverseLookupCache.inferredScanDone]（每个结构版本/缓存期至多一次）。
     *
     * 成本与口径同 [ensureIndexBuilt]：派到应用线程池、EDT 不等、非 EDT 有界等一个预算；本次等不到
     * 就本次降级（返回空），下一次查询命中。
     */
    private fun ensureInferredScan(entry: ReverseLookupCache) {
        // 项目级「跨 ORM 回补结果」缓存（按结构版本）：命中就直接并进去，不再跑全项目推断。
        // 真机教训（2026-11-13 22:20 用户沙盒）：缓存条目按 TTL(5min) 重建，而回补标记原本挂在条目上
        // ⇒ 每个 TTL 周期都要重扫一遍全项目（该工程实测 `跨 ORM 回补完成 tables=8906`，一趟 4.7s+）。
        cachedInferredIndex()?.let { cached ->
            if (!entry.inferredScanDone) {
                for ((table, classes) in cached) entry.index.getOrPut(table) { classes }
                entry.inferredScanDone = true
                LOG.info("[open-perf] 跨 ORM 回补命中项目级缓存 tables=${cached.size}（跳过全项目推断）")
            }
            return
        }
        val app = ApplicationManager.getApplication()
        val onEdt = app.isDispatchThread
        if (entry.inferredBuilding.compareAndSet(false, true)) {
            if (syncIndexBuildForTest ?: app.isUnitTestMode) {
                runInferredScan(entry)
                return
            }
            if (!retryCooldownElapsed(entry)) {
                entry.inferredBuilding.set(false)
                return
            }
            val started = System.currentTimeMillis()
            ReadAction.nonBlocking<Map<String, List<MatchedClass>>?> {
                try {
                    scanInferredTableIndex()
                } catch (pce: com.intellij.openapi.progress.ProcessCanceledException) {
                    throw pce
                } catch (t: Throwable) {
                    if (FatalErrors.isFatal(t)) throw t
                    null
                }
            }
                .inSmartMode(project)
                .expireWith(project)
                .submit(AppExecutorUtil.getAppExecutorService())
                .onSuccess { built -> mergeInferred(entry, built, started) }
                .onError { t ->
                    LOG.info("[open-perf] 跨 ORM 回补未完成：${t.javaClass.simpleName}")
                    entry.lastAbortAt = System.currentTimeMillis()
                    entry.inferredBuilding.set(false)
                }
        }
        if (onEdt) return
        runCatching { entry.inferredLatch.await(INDEX_WAIT_BUDGET_MS, TimeUnit.MILLISECONDS) }
    }

    /** 同步口径（测试/预热线程）跑一次全项目推断扫描并合并。 */
    private fun runInferredScan(entry: ReverseLookupCache) {
        val started = System.currentTimeMillis()
        val built = runCatching {
            scanInferredTableIndex()
        }.getOrElse { t ->
            if (FatalErrors.isFatal(t)) throw t
            null
        }
        mergeInferred(entry, built, started)
    }

    private fun mergeInferred(entry: ReverseLookupCache, built: Map<String, List<MatchedClass>>?, startedMs: Long) {
        try {
            if (built == null) return
            if (project.getUserData(REVERSE_LOOKUP_CACHE_KEY) !== entry) return
            // 合并而非覆盖：注解索引那部分更准（值直接来自注解），不被推断结果冲掉
            for ((table, classes) in built) entry.index.getOrPut(table) { classes }
            entry.inferredScanDone = true
            entry.inferredScanCount++
            // 落项目级缓存（按结构版本失效）：TTL 重建的条目直接复用，不再重扫
            project.putUserData(INFERRED_INDEX_KEY, projectStructureVersion() to built)
            LOG.info(
                "[open-perf] 跨 ORM 回补完成 tables=${built.size} 耗时=${System.currentTimeMillis() - startedMs}ms",
            )
        } finally {
            entry.inferredBuilding.set(false)
            entry.inferredLatch.countDown()
        }
    }

    /**
     * 建索引：**一次全量扫描覆盖所有表名**，之后任意表名查询都是 O(1)。
     *
     * 旧口径是「查一个表名扫一遍」，而 `cust-big-demo` 这类工程一次扫描 3.8s（16804 个类逐个读
     * `@TableName` / 推断表名）——用户每打开一条不同表的语句就再吃一次。
     *
     * **扫描不上调用方线程**：派到应用线程池（`ReadAction.nonBlocking`，不持读锁等索引），
     * 非 EDT 调用方**至多**等 [INDEX_WAIT_BUDGET_MS]；超预算就用当前（多半空）的结果返回。
     * 真机实证（2026-11-13 沙盒）：一趟「一次建成」实测 **4737ms / 9675ms**，压在点击链上就是
     * 用户看到的「打开还是慢」。
     *
     * **EDT 一律不等**（补全每敲一个字符都会走到这里）：`ApplicationManager.isDispatchThread` 时
     * 只派发、立即返回。等于在 EDT 上等一次动辄数秒的后台扫描，正是本轮要根治的形态；
     * 索引由预热（BGT）/上一次派发在后台建好，下一次查询即命中。
     */
    private fun ensureIndexBuilt(entry: ReverseLookupCache) {
        if (entry.built) return
        val app = ApplicationManager.getApplication()
        val onEdt = app.isDispatchThread
        if (entry.building.compareAndSet(false, true)) {
            // 测试口径走同步：既有断言按「首次查询即建好」写（`indexBuildCount`/`scans` 读数）
            if (syncIndexBuildForTest ?: app.isUnitTestMode) {
                val started = System.currentTimeMillis()
                val built = runCatching {
                    scanAllTablesIndex()
                }.getOrElse { t ->
                    if (FatalErrors.isFatal(t)) throw t
                    null
                }
                applyBuiltIndex(entry, built, started)
                return
            }
            if (!retryCooldownElapsed(entry)) {
                // 刚被撤销/失败过：不立刻重投，否则连续输入会把「扫一段被取消」打成死循环
                releaseBuild(entry)
                return
            }
            dispatchIndexBuild(entry)
        }
        if (onEdt) return
        runCatching { entry.latch.await(INDEX_WAIT_BUDGET_MS, TimeUnit.MILLISECONDS) }
    }

    /** 上次被撤销/失败后的冷却窗口——防止「输入 → 扫描被写动作取消 → 下次输入再投」反复空转。 */
    private fun retryCooldownElapsed(entry: ReverseLookupCache): Boolean {
        val last = entry.lastAbortAt
        return last == 0L || System.currentTimeMillis() - last >= INDEX_RETRY_COOLDOWN_MS
    }

    /**
     * 把全量扫描派到应用线程池（不持读锁等索引；扫描内部自带取消检查）。
     *
     * 与写动作的关系：`ReadAction.nonBlocking` 在平台要拿写权限时会被撤销，本扫描每
     * [LIBRARY_SWEEP_CANCEL_INTERVAL] 个短名 `ProgressManager.checkCanceled()` 一次，
     * 所以「用户一打字」就能让出读锁（撤销即抛 PCE，见下方不吞 PCE 的写法）。
     */
    private fun dispatchIndexBuild(entry: ReverseLookupCache) {
        // 起点在**派发前**：日志里的耗时含排队 + 扫描，是调用方视角的真实成本（曾被写成只量落地那一下 = 1ms）
        val started = System.currentTimeMillis()
        ReadAction.nonBlocking<Map<String, List<MatchedClass>>?> {
            try {
                scanAllTablesIndex()
            } catch (pce: com.intellij.openapi.progress.ProcessCanceledException) {
                throw pce // 撤销要能被平台看见（否则非阻塞读动作退化成「不可撤销的长读者」）
            } catch (t: Throwable) {
                if (FatalErrors.isFatal(t)) throw t
                LOG.info("[open-perf] 反查索引扫描异常：${t.javaClass.simpleName}")
                null
            }
        }
            .inSmartMode(project)
            .expireWith(project)
            .submit(AppExecutorUtil.getAppExecutorService())
            .onSuccess { built -> applyBuiltIndex(entry, built, started) }
            .onError { t ->
                LOG.info("[open-perf] 反查索引建立未完成：${t.javaClass.simpleName}（冷却 ${INDEX_RETRY_COOLDOWN_MS}ms 后重试）")
                entry.lastAbortAt = System.currentTimeMillis()
                releaseBuild(entry)
            }
    }

    /**
     * 索引建成落地（同步/异步共用）。三件事按序做完：填索引 → 置 `built`（**@Volatile，写在其后**，
     * 保证读到 `built=true` 的线程一定看得到已填充的 `index`）→ 放行等待者。
     *
     * @param startedMs 扫描起点（派发前/同步分支进入前），用于日志给出调用方视角的真实耗时
     */
    private fun applyBuiltIndex(
        entry: ReverseLookupCache,
        built: Map<String, List<MatchedClass>>?,
        startedMs: Long,
    ) {
        try {
            if (built == null) return
            // 条目可能已被换掉（启动期结构版本抖动 / TTL 到期）。真机教训（2026-11-13 22:20 用户沙盒）：
            // 一个疗程里 `候选源=注解索引` 打了 7 次、只 1 次「一次建成」——说明 6 次扫描的结果被
            // 「条目已换」直接丢弃、然后又在新条目上重扫。改为**落到当前条目**：结果对当前结构仍成立
            // （本次扫描就是在新结构下跑的），丢弃才是浪费。
            val current = project.getUserData(REVERSE_LOOKUP_CACHE_KEY) ?: return
            val target = current
            if (target.built) return
            entry.index.putAll(built)
            target.index.putAll(built)
            // 已有项目级回补结果（同一结构版本）→ 直接并进来，新条目立刻具备 MBG/iBatis 那部分
            cachedInferredIndex()?.let { cached ->
                for ((table, classes) in cached) target.index.getOrPut(table) { classes }
                target.inferredScanDone = true
            }
            target.scans++
            target.built = true
            indexBuildCount(project).incrementAndGet()
            LOG.info(
                "[open-perf] 反查索引一次建成 tables=${built.size} 耗时=${System.currentTimeMillis() - startedMs}ms" +
                    " testSync=${syncIndexBuildForTest == true || ApplicationManager.getApplication().isUnitTestMode}",
            )
        } finally {
            releaseBuild(entry)
            // 顺手放行「当前条目」的等待者——等待者可能挂在被替换掉的旧条目上
            val current = project.getUserData(REVERSE_LOOKUP_CACHE_KEY)
            if (current != null && current !== entry) releaseBuild(current)
        }
    }

    /** 放行等待者并释放在途标记（失败路径同样要放行，否则等待者白等一个预算）。 */
    private fun releaseBuild(entry: ReverseLookupCache) {
        entry.building.set(false)
        entry.latch.countDown()
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

    /**
     * 建「表名（小写）→ 匹配类」索引。**候选源＝平台注解索引**，不再遍历全项目类。
     *
     * 演进（每步都有真机读数）：
     *  1. 「查一个表名就把全项目类扫一遍」——16804 类一趟 3.8s，用户每换一张表再吃一次；
     *  2. 「一次扫描建全表索引」——消掉重复，但这一趟本身 **4.7~9.7s**（逐类 `inferDecoratedTableName`
     *     要走命名策略/设置）；
     *  3. 现在：`JavaAnnotationIndex.getAnnotations("TableName", …)` **只取带注解的类**（索引查询，
     *     O(log n) + 候选数）。`cust-big-demo` 上候选从 16804 降到「带 `@TableName` 的实体数」，
     *     且不再为每个非实体类算推断表名——那才是 4.7s 的大头。
     *
     * 为什么可以丢掉「无注解按类名推断」这半边：慢路径存在的理由是「实体名与表名对不上、只有注解能
     * 说明」，而**按命名约定的匹配本来就走快路径**（[candidateClassNames] 4 个候选 + `EntityResolver`
     * 按真实策略校验表名，覆盖驼峰/Entity 后缀/`t_` 前缀等）。索引侧不再重复做这件事。
     *
     * 注解索引的键是**简单名**（243 字节码实证：`JavaAnnotationElementType.indexStub` 先 `indexOf('(')`
     * 截断再取短类名，属性名/属性值都不进索引）——所以下面按名字拿到候选后**自己读注解值**，
     * 并按注解原文筛掉别家同名注解（不 resolve，缺依赖时也能判）。
     *
     * 已知边界：`@TableName(SOME_CONSTANT)` 这类**非字面量**取值的实体取不到表名（老版本全量扫描同样
     * 取不到注解值，但会退化成「按类名推断」而可能命中）——约定命名场景仍由快路径兜住。
     */
    /** 项目级「跨 ORM 回补结果」——同结构版本才有效（结构变了就作废重扫）。 */
    private fun cachedInferredIndex(): Map<String, List<MatchedClass>>? {
        val cached = project.getUserData(INFERRED_INDEX_KEY) ?: return null
        return if (cached.first == projectStructureVersion()) cached.second else null
    }

    private fun scanAllTablesIndex(): Map<String, List<MatchedClass>> {
        indexScanDelayMsForTest?.let { if (it > 0) Thread.sleep(it) }
        // 只建「注解索引」这一半；未命中的表由 findByTableNameAnnotationCached 触发
        // `ensureInferredScan`（全项目推断，跨 ORM 兜底）按需回补，不在这里无条件扫全项目。
        return scanAnnotatedTableIndex()
    }

    /**
     * 候选源＝**平台注解索引**，覆盖各 ORM 的表名注解（MP `@TableName`、JPA `@Table`；
     * 见 [TableNameAnnotations]）。只取带注解的类，不遍历全项目类。
     *
     * 演进（每步都有真机读数）：
     *  1. 「查一个表名就把全项目类扫一遍」——16804 类一趟 3.8s，用户每换一张表再吃一次；
     *  2. 「一次扫描建全表索引」——消掉重复，但这一趟本身 **4.7~9.7s**（逐类 `inferDecoratedTableName`
     *     要走命名策略/设置）；
     *  3. 现在：注解索引查询（O(log n) + 候选数），候选从「全项目类数」降到「带表名注解的实体数」，
     *     且不再为非实体类算推断表名。真机实测 `tables=8688 → 812`、重建 `4737ms → 187ms`。
     *
     * 覆盖边界：**无注解**的实体（MBG 生成器、iBatis、Kotlin 或自定义映射）不在这里——它们由
     * ①命名约定快路径（[candidateClassNames] + `EntityResolver` 校验）与 ②未命中时的
     * [ensureInferredScan] 回补共同覆盖，两条都不需要注解在场。
     *
     * 注解索引的键是**简单名**（243 字节码实证：`JavaAnnotationElementType.indexStub` 先 `indexOf('(')`
     * 截断再取短类名，属性名/属性值都不进索引）——所以拿到候选后**自己读注解值**，
     * 并按注解原文筛掉别家同名注解（不 resolve，缺依赖时也能判）。
     *
     * 已知边界：`@TableName(SOME_CONSTANT)` 这类**非字面量**取值读不到表名（老口径会退化成按类名推断）
     * ——这类实体在未命中回补里仍会按推断命中。
     */
    private fun scanAnnotatedTableIndex(): Map<String, List<MatchedClass>> {
        val index = HashMap<String, MutableList<MatchedClass>>()
        val scope = GlobalSearchScope.projectScope(project)
        var scanned = 0
        var candidates = 0
        for (simpleName in TableNameAnnotations.simpleNames) {
            val annotations = runCatching {
                JavaAnnotationIndex.getInstance().getAnnotations(simpleName, project, scope)
            }.getOrElse { t ->
                if (FatalErrors.isFatal(t)) throw t
                emptyList()
            }
            candidates += annotations.size
            for (annotation in annotations) {
                // 取消检查：候选虽少，但读注解/取宿主类仍是 PSI 访问，平台要写权限时必须能被中断
                if (scanned % LIBRARY_SWEEP_CANCEL_INTERVAL == 0) ProgressManager.checkCanceled()
                scanned++
                val tableName = TableNameAnnotations.tableNameOf(annotation)?.takeIf { it.isNotBlank() } ?: continue
                val clazz = PsiTreeUtil.getParentOfType(annotation, PsiClass::class.java) ?: continue
                if (clazz.isInterface || clazz.isEnum || clazz.isAnnotationType) continue
                if (isVoDto(clazz)) continue
                val className = clazz.name ?: continue
                index.getOrPut(tableName.lowercase()) { mutableListOf() }
                    .add(MatchedClass(className, clazz.qualifiedName))
            }
        }
        LOG.info(
            "[open-perf] 反查索引候选源=注解索引 候选注解=$candidates " +
                "入索引类=${index.values.sumOf { it.size }}",
        )
        return index
    }

    /**
     * 退回口径：全项目短名遍历 + 逐类推断表名（`@TableName` 优先、无注解按命名策略推断）。
     * 只在注解索引一个候选都给不出时使用（见 [scanAllTablesIndex]）。
     */
    private fun scanInferredTableIndex(): Map<String, List<MatchedClass>> {
        val cache = PsiShortNamesCache.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val index = HashMap<String, MutableList<MatchedClass>>()
        var scanned = 0
        for (shortName in cache.getAllClassNames()) {
            // 取消检查（T-GutterDupFreeze 口径）：全量短名遍历跑在读动作里，平台需要写锁时须能中断
            if (scanned % LIBRARY_SWEEP_CANCEL_INTERVAL == 0) ProgressManager.checkCanceled()
            scanned++
            // 短名快筛：VO/DTO 结尾的直接跳过（避免逐类检查）
            if (shortName.endsWithIgnoreCase("VO") || shortName.endsWithIgnoreCase("DTO")) continue
            for (clazz in cache.getClassesByName(shortName, scope)) {
                if (clazz.isInterface || clazz.isEnum || clazz.isAnnotationType) continue
                if (isVoDto(clazz)) continue
                val className = clazz.name ?: continue
                // 跨 ORM：MP `@TableName` / JPA `@Table(name)` 都认（见 TableNameAnnotations）
                val annotated = TableNameAnnotations.tableNameOfClass(clazz)
                // T-TS-07：显式传候选类所属项目（= 本 finder 的项目，scope 亦为 projectScope）——
                // 反查表名的兜底推断不再受「首个打开项目」影响；clazz 是 PSI，project 必可得
                val resolvedTable = annotated
                    ?: EntityResolver.inferDecoratedTableName(className, clazz.project)
                if (resolvedTable.isNullOrBlank()) continue
                index.getOrPut(resolvedTable.lowercase()) { mutableListOf() }
                    .add(MatchedClass(className, clazz.qualifiedName))
            }
        }
        return index
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

    /**
     * 项目级反查缓存（Project userData）。
     *
     * 失效基准＝**项目结构版本**（[ProjectRootModificationTracker]）+ **TTL**（[reverseLookupTtlMs]）。
     *
     * 为什么不再挂 [PsiModificationTracker]：它对**任何** PSI 变化都递增，而预览链路自己就会改 PSI
     * （写 `mpsql-exec.sql`、临时 PsiFile、对话框里打字）。实测 `cust-big-demo`（16804 类）：
     * 打开预览 18:52:59 缓存热（`resolve=2ms`）→ 我们自己写 exec 文件 → 19:00:08、19:00:44 两次
     * `resolve=3994ms / 3737ms`，也就是**每次打开预览都在重扫全工程**，用户看到的就是「打开还是慢」。
     * 结构版本对「改已有文件正文」不递增（[LibrarySweepCache] 已有同样实证），TTL 负责兜住
     * 「改了实体上的 `@TableName`」这类正文修改——最迟一个 TTL 周期后自动重建。
     */
    private data class ReverseLookupCache(
        /** 建索引时的项目结构版本——结构变化（增删文件/依赖/模块根）后失效重建。 */
        val structureVersion: Long,
        /** 建索引时间（TTL 判据）。 */
        val createdAt: Long,
        /** 表名（小写）→ 匹配类（短名 + 全限定名）；一次扫描建全表索引，任意表名 O(1)。 */
        val index: MutableMap<String, List<MatchedClass>>,
        /** 索引是否已建成（未建成时是「没人扫过」，不是「该表确实没有实体」）。 */
        @Volatile var built: Boolean = false,
        /** 正在建索引的线程标记——后来者不重复派，只按预算等一等。 */
        val building: AtomicBoolean = AtomicBoolean(false),
        /** 建成/失败时放行等待者（等待有预算上限，见 [INDEX_WAIT_BUDGET_MS]）。 */
        val latch: java.util.concurrent.CountDownLatch = java.util.concurrent.CountDownLatch(1),
        /** 上次被撤销/失败的时间戳（0 = 从未）。冷却窗口用它，防连续输入把扫描打成「反复重投」。 */
        @Volatile var lastAbortAt: Long = 0L,
        /** 跨 ORM 回补（[ensureInferredScan]）是否已做过——每个结构版本/缓存期至多一次。 */
        @Volatile var inferredScanDone: Boolean = false,
        /** 回补在途标记（与注解索引建立互不阻塞）。 */
        val inferredBuilding: AtomicBoolean = AtomicBoolean(false),
        /** 回补完成/失败时放行等待者。 */
        val inferredLatch: java.util.concurrent.CountDownLatch = java.util.concurrent.CountDownLatch(1),
        /** 回补次数（守卫读数：注解索引未命中才允许 +1）。 */
        var inferredScanCount: Int = 0,
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
        val current = ProjectRootModificationTracker.getInstance(project).modificationCount
        val now = System.currentTimeMillis()
        val existing = project.getUserData(REVERSE_LOOKUP_CACHE_KEY)
        if (existing != null &&
            existing.structureVersion == current &&
            now - existing.createdAt in 0 until reverseLookupTtlMs
        ) {
            return existing
        }
        val fresh = ReverseLookupCache(current, now, mutableMapOf())
        project.putUserData(REVERSE_LOOKUP_CACHE_KEY, fresh)
        return fresh
    }

    companion object {
        private val LOG = Logger.getInstance(EntityClassFinder::class.java)

        private const val TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName"


        private val REVERSE_LOOKUP_CACHE_KEY = Key.create<ReverseLookupCache>("mp-visualizer.reverseLookupCache")

        /** 项目级「跨 ORM 回补结果」缓存（结构版本 → 表名索引）——TTL 重建条目时不重扫全项目。 */
        private val INFERRED_INDEX_KEY =
            Key.create<Pair<Long, Map<String, List<MatchedClass>>>>("sqlvis.reverseLookup.inferredIndex")

        /** 跨缓存实例的累计建索引次数（守卫用，见 [indexBuildCount]）。 */
        private val REVERSE_LOOKUP_SCAN_TOTAL_KEY =
            Key.create<java.util.concurrent.atomic.AtomicInteger>("mp-visualizer.reverseLookupScanTotal")

        /**
         * 调用方等待索引建成的**预算上限**（毫秒）。真机实测一趟全量扫描 4737/9675ms，压在点击链上
         * 就是「打开还是慢」；预算内建完就命中，建不完本次降级（返回空上下文）、下一次查询命中。
         */
        internal const val INDEX_WAIT_BUDGET_MS: Long = 800L

        /**
         * 扫描被撤销/失败后的**冷却窗口**。为什么需要：`ReadAction.nonBlocking` 在平台要拿写权限时会被
         * 撤销（用户一打字就要写锁），而补全在 EDT 上每敲一个字符都会问一次反查——没有冷却时
         * 「投一次 → 立刻被取消 → 再投」会把一个 5~10s 的扫描打成无限重试，机器白烧。
         */
        internal const val INDEX_RETRY_COOLDOWN_MS: Long = 10_000L

        /**
         * 测试缝：强制建索引走**同步**分支。默认 null ⇒ 看 [com.intellij.openapi.application.ApplicationManager.isUnitTestMode]
         * （单测/沙盒里为 true，走同步，既有断言按同步写）；置 false 可强制验异步分支。
         */
        internal var syncIndexBuildForTest: Boolean? = null

        /** 测试缝：给全量扫描注入人为延迟（毫秒），用于断言「调用方不被扫描阻塞」。 */
        internal var indexScanDelayMsForTest: Long? = null

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

        /** 测试访问器：跨 ORM 回补（全项目推断扫描）执行次数——注解索引未命中才允许 +1，每期至多一次。 */
        internal fun inferredScanCountForTest(project: Project): Int =
            project.getUserData(REVERSE_LOOKUP_CACHE_KEY)?.inferredScanCount ?: 0

        /** 测试访问器：项目级全量反查扫描次数（缓存命中后不再增长）。 */
        internal fun scanCountForTest(project: Project): Int =
            project.getUserData(REVERSE_LOOKUP_CACHE_KEY)?.scans ?: 0

        /**
         * **跨缓存实例**的累计建索引次数（索引建立方每建一次 +1；守卫与观测用）。
         *
         * 为什么需要它：缓存条目被判定失效时会重建（`scans` 归零），只看 [scanCountForTest] 分不清
         * 「没重扫」和「重扫了但新条目从 0 起算」——「打字不作废索引」这类守卫会**空绿**（实测踩过）。
         */
        internal fun indexBuildCount(project: Project): java.util.concurrent.atomic.AtomicInteger =
            project.getUserData(REVERSE_LOOKUP_SCAN_TOTAL_KEY)
                ?: java.util.concurrent.atomic.AtomicInteger().also {
                    project.putUserData(REVERSE_LOOKUP_SCAN_TOTAL_KEY, it)
                }

        /**
         * 反查索引 TTL（毫秒）。默认 5 分钟：结构版本兜住文件增删/依赖变化，
         * TTL 兜住「改已有实体文件的 `@TableName`」这类结构版本看不见的正文修改。
         * 测试可置 0 强制下一次查询重建。
         */
        internal var reverseLookupTtlMs: Long = 300_000L

        /** 反查缓存信息（设置页展示）：返回（索引表名条目数, 索引类名总数）；无缓存 → (0, 0)。 */
        internal fun reverseLookupCacheInfo(project: Project): Pair<Int, Int> {
            val entry = project.getUserData(REVERSE_LOOKUP_CACHE_KEY) ?: return 0 to 0
            return entry.index.size to entry.index.values.sumOf { it.size }
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
            for ((table, classes) in entry.index) {
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
