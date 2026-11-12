package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.canCreateSqlPsiFor
import cn.song.mybatisplus.visualizer.util.FatalErrors
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

// T-ExecPlanEdtSlowOp（2026-11-01）：exec 临时文件（`mpsql-exec.sql`）工厂从
// DataSourceSelectorResults.kt 拆出——`createTempPsiFile` 一族是**执行 SQL / 执行计划 /
// 回滚快照三路共用**的入口，内含 VFS refresh 与 `PsiManager.findFile` 两个平台慢操作，
// 且同函数内「方言映射须 EDT / refresh+findFile 须 BGT」两段线程要求相反。集中到本文件
// 便于把两段线程纪律与守卫（ExecTempFileEdtGuardTest / SyncRefreshWriteIntentGuardTest）
// 放在一起看。拆出同时给 DataSourceSelectorResults.kt（巨文件预算 2000 行）腾出余量。

/**
 * T-ExecSlowOps-BP：**EDT 入口**——把 [createTempPsiFile] 的慢段整体挪出 EDT。
 *
 * 缺陷实证（2026-11-04 真机 master 包 `v2026.11.4-beta.1`，`idea.log` 一轮 36 条）：
 * ```
 * SqlPreviewDialog.<init> → showResult → prepareDefaultConsole
 *   → SqlExecutor.prepareConsole → DataSourceSelector.prepareConsole
 *     → chooseAndPrepare → createTempPsiFile
 *       → ReadAction.compute { PsiManager.getInstance(project).findFile(vf) }
 *         → FileManagerImpl.findDirectory → ProjectFileIndexImpl.isUnderIgnored
 *           → WorkspaceFileIndexDataImpl.ensureIsUpToDate     ← 慢操作
 * ```
 * `ReadAction.compute` 只保证读语义、**不改变线程**——PSI 首次实例化照跑在 EDT 上，平台
 * `SlowOperations` 断言必然 SEVERE。冷路径（缓存未命中）三路皆可达：执行 SQL / 执行计划 /
 * 回滚快照都走 `prepareConsole → chooseAndPrepare → createTempPsiFile`。
 *
 * 线程契约：
 *  - **EDT 调用**（生产唯一形态）：立刻返回，慢段在池线程执行，结果经**write-safe 投递**回到 EDT
 *    交给 [onReady]——续接链里的弹窗/内嵌控制台/结果面板动作因此仍全部在 EDT；
 *  - 非 EDT 调用（防御分支，生产不可达）：慢段在调用线程就地跑（BGT 上合法），**回调同样经
 *    write-safe 投递回 EDT**——续接链是 UI + 文档写，不能由后台线程承接。
 *
 * **写安全**：投递模态必须是 `ModalityState.nonModal()`，**不能用 `any()`**。
 * `TransactionGuardImpl` 构造期只把 `nonModal` 登记为 write-safe；`any()` 排出的回调不套
 * `runWithWritingAllowed`，而续接链 [continuePrepareAfterTempFile] 内含文档写（共享控制台
 * `editor.document.setText`、控制台文档 `setText` + `PsiDocumentManager.commitDocument`），
 * 于是每次执行都紧跟一条 `SEVERE TransactionGuardImpl … Write-unsafe context`
 * （dev 2026-11-01 真机三连实证；仓内定案见 `ExecWriteSafeDispatchGuardTest`）。
 *
 * 契约影响：本函数**异步**——调用方不得在调用返回后立即使用临时文件，必须写在 [onReady] 里
 * （`chooseAndPrepare` 的后续全链已按此迁入 [continuePrepareAfterTempFile]）。
 *
 * @param onReady 结果回调；null = 建链失败（阶段原因在 `lastTempFileFailure`）。EDT 调用时
 *   回调保证在 EDT 上、且处于 write-safe 上下文（可写文档）。
 */
internal fun DataSourceSelector.createTempPsiFileAsync(
    project: Project,
    sql: String,
    renderedDialectName: String? = null,
    onReady: (Pair<VirtualFile, PsiFile>?, String?) -> Unit,
) {
    val app = ApplicationManager.getApplication()
    // T-ExecPsiGate F3（2026-11-11 验收失败）：失败原因**在失败现场就地捕获**、随回调带回。
    // 原实现让 EDT 续接去读 `lastTempFileFailure` 这个共享字段——而预热链在入口会把它清空
    // （预热与冷路径并发时，用户看到的就是「创建临时 SQL 文件失败：unknown」）。
    fun runSlow(report: (Pair<VirtualFile, PsiFile>?, String?) -> Unit) {
        val pair = createTempPsiFile(project, sql, renderedDialectName)
        val reason = if (pair == null) lastTempFileFailure else null
        // T-PreviewOpenPerfM2 M7：PSI 有效性校验留在**后台**完成，EDT 只做回调投递。
        // `psi.isValid` 会触发 `WorkspaceFileIndexDataImpl.ensureIsUpToDate`（平台标注的慢操作），
        // 在 EDT 上调用会被 SlowOperations 断言拦下并记「Plugin to blame: SQL Visualizer」
        // （真机 2026-11-12 11:08:53 SEVERE，栈落在下面这个函数里）。
        val revalidated = revalidate(project, pair)
        app.invokeLater({ report(revalidated, reason) }, ModalityState.nonModal())
    }
    if (!app.isDispatchThread) {
        // 已是后台线程：慢段就地跑（合法），但回调仍回 EDT + write-safe——续接链是 UI + 文档写，
        // 不能让调用方线程直接承接（否则弹窗越线程、文档写无写安全资格）
        runSlow(onReady)
        return
    }
    app.executeOnPooledThread { runSlow(onReady) }
}

/**
 * T-ExecPsiInvalid：异步窗口内的 **PSI 失效兜底**。
 *
 * 缺陷形态：`createTempPsiFile` 在池线程 `findFile` 拿到的 [PsiFile] 到 EDT 回调消费之间有一个
 * 空窗。空窗里只要该文件内容发生一次变化（doc 驱动的三方同步落盘、防抖自动落盘、用户/其他写方
 * 覆盖 `mpsql-exec.sql`），平台就会丢掉旧 PSI——`vf.isValid` 仍为 true（**只有内容变了**），
 * 而 `psi.text` 硬抛 `PsiInvalidElementAccessException`。旧同步实现把整段压在 EDT 上，EDT 被
 * 占住时排队的内容变化落不下来，所以该窗口一直没暴露；改异步后它成了真窗口（dev 全量门禁实证）。
 *
 * 兜底口径：**只有 PSI 失效才重解析**（有效即原样返回，热路径零额外开销）；重解析仍失败则
 * 按建链失败返回 null（续接链会走既有失败文案，不把死 PSI 递进 Runner 枚举）。
 *
 * 线程契约（T-PreviewOpenPerfM2 M7 修订）：本函数在**后台**执行（原设计是 EDT + 显式读锁）。
 * `psi.isValid` 内部会走 `FileManagerImpl.evaluateValidity → shouldResurrect → createFile →
 * findDirectory → isUnderIgnored → WorkspaceFileIndexDataImpl.ensureIsUpToDate`——**平台明确标注
 * 的慢操作**；在 EDT 上调用会被 `SlowOperations` 断言拦下，并给插件记一条
 * 「Plugin to blame: SQL Visualizer」（真机 2026-11-12 11:08:53 SEVERE 实证）。
 * 后台线程天然豁免该断言，读权限仍显式取（与 UI-002 同口径）。
 */
internal fun revalidate(
    project: Project,
    pair: Pair<VirtualFile, PsiFile>?,
): Pair<VirtualFile, PsiFile>? {
    if (pair == null) return null
    val (vf, psi) = pair
    val stillValid = try {
        ReadAction.compute<Boolean, Throwable> { psi.isValid }
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e
        false
    }
    if (stillValid) return pair
    // 重解析 + 有效性判定同一次读锁内完成——判出的 fresh 出了锁再判 isValid 会多一次读权限要求
    //（且中间仍有失效窗口），合并后只读一次平台状态。
    val fresh = try {
        ReadAction.compute<PsiFile?, Throwable> {
            PsiManager.getInstance(project).findFile(vf)?.takeIf { it.isValid }
        }
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e
        null
    }
    return if (fresh != null) vf to fresh else null
}

internal fun DataSourceSelector.createTempPsiFile(
    project: Project,
    sql: String,
    renderedDialectName: String? = null,
): Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? {
    // T-ExecSlowOps-BP：**慢段本体，只允许后台线程调用**（EDT 调用会撞平台
    // `Slow operations are prohibited on EDT`）。EDT 侧唯一入口是 [createTempPsiFileAsync]，
    // 由它派发到池线程；[prewarmExecTempFileInner] 走同款 BGT 裸调形态。
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
    // T-ExecCopyFix F2a：失败阶段记录（onError 文案携带，见 chooseAndPrepare）
    // F8：成因**编码进文案本身**（` [hint=XXX]`）。前几轮靠共享字段传递被三种机制各打掉一次
    //（预热入口清空 / 并发覆盖 / 第二次渲染时读方清空）；文案怎么走、成因就怎么走，见 withExecHint。
    fun fail(stage: String, detail: String = "", hint: ExecPsiFailureCause? = null): Nothing? {
        val reason = "$stage${if (detail.isNotEmpty()) ": $detail" else ""}"
        lastTempFileFailure = withExecHint(reason, hint)
        if (hint != null) lastTempFileHintRecord = TempFileHintRecord(stage, hint)
        LOG.warn("[M36] createTempPsiFile FAILED at $stage${if (detail.isNotEmpty()) " ($detail)" else ""}")
        return null
    }
    lastTempFileFailure = null
    // M36.5.12：临时文件改存项目内 `.idea/sql-visualizer/exec/`（F-44-4：目录随插件更名）
    //（参考 sql-history 保存位置），不再用系统 Temp——Temp 文件不在数据源关联范围，
    // 导致 IDE 报 "Unable to resolve table"（M36.5.11 问题一，日志实证挂载点在
    // file:///C:/Users/USER/AppData/Local/Temp/）。项目内文件可被 VFS 追踪、可被
    // Database 插件解析。文件名稳定 mpsql-exec.sql（每次覆盖，不膨胀）。
    //
    // T-ExecPsiGate F6（2026-11-11 用户定案，两次同一口径）：**落点不改写**——`.idea` 仍是默认，
    // 建不出 PSI 时把原因与修复入口摆给用户，只有用户点了「改用项目根落点重试」才走备用落点。
    // 本轮曾实现「探针判否就自动换项目根」，被用户否掉：自动改写会把项目配置的问题藏起来，
    // 与 13.11 的定案相悖。真机读数（IU-263.7307.200，`.idea/sql-visualizer/exec`）：
    //   dirPsi=false, dirExcluded=true, dirUnderIgnored=true, inContent=false, regHideExcluded=false
    // 所以失败文案必须**说清这一档**并给出可点的兜底入口（F5 的 DIR_NOT_INDEXED / IGNORED_BY_VCS）。
    val preferRoot = project in execRootFallbackProjects
    val ioFile = try {
        projectExecFile(project, sql, preferProjectRoot = preferRoot)
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
        // projectExecFile 尾段 FileUtil.writeToFile 可抛 IOException——原实现靠外层
        // runCatching 吞掉，拆除后这里必须自接，否则异常直冲 EDT
        return fail("writeFile", "${e.javaClass.simpleName}: ${e.message}")
    } ?: return fail("writeFile", "projectExecFile null (basePath=${project.basePath})")
    // T-PreviewFlash：EDT 同步 VFS refresh + PsiManager.findFile 已被跨点击缓存吸收——
    // mpsql-exec.sql 路径稳定，vf 仍有效时直接复用配对缓存，把
    // WorkspaceFileIndex.ensureIsUpToDate 的 SEVERE 断言移出每次预览的热路径。
    consumeCachedTempPsiFile(project)?.let { pair ->
        // O-136：缓存命中路径同样对齐方言映射（幂等读探测稳态零写）——目标数据源方言切换后
        // 缓存复用不得携带旧映射进 Runner 枚举。
        mapDialectBeforePsi(project, pair.first, LOG, renderedDialectName)
        return pair
    }
    // T-ExecDocSync2 F4：refresh 前强制收敛——写点之后、refresh 之前若被用户键入/其他写方
    // 插入（doc modified-unsaved + 磁盘≠文档），refresh 恰会命中 MemoryDiskConflictResolver
    // 弹窗条件；findFileByIoFile（与 projectExecFile 内裸调同形态）命中即过一遍分叉探测 +
    // EDT 派发 doc 写收敛，正常恒 no-op。详见 prewarmExecTempFileInner 同款注释。
    try {
        LocalFileSystem.getInstance().findFileByIoFile(ioFile)?.let { vf0 ->
            syncExecVfContentIfNeeded(project, vf0, sql)
        }
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
        return fail("preRefreshConverge", "${e.javaClass.simpleName}: ${e.message}")
    }
    // T-ExecCopyFix F2b：refresh 段包 WriteIntentReadAction（详见方法 KDoc）；
    // mapDialect 与 findFile 维持原线程原语义（setMapping 须 EDT；findFile 沿用
    // ReadAction.compute 既有包装，与 T-M36.6-03 前行为一致）。
    // T-ExecSlowOps-BP：**线程分流**——本函数现由 createTempPsiFileAsync 在池线程调用，
    // BGT 上包 WIRA × 同步 refresh 会自锁（T-PrewarmWilDeadlock：持写意向锁等 RefreshSession
    // 推进，而会话推进需 WIL/EDT，2026.2 真机 threadDump 全 IDE not responding 实证），故 BGT
    // **裸调**；EDT 分支（本券后生产不可达，仅防御）保留 WIRA——262 平台 EDT + plain ReadAction
    // 内同步 refresh 硬抛 write thread only（F2b 修复本体）。守卫：
    // SyncRefreshWriteIntentGuardTest 规则二/规则三 + ExecTempFileEdtGuardTest。
    val vf = try {
        if (ApplicationManager.getApplication().isDispatchThread) {
            com.intellij.openapi.application.WriteIntentReadAction
                .compute<com.intellij.openapi.vfs.VirtualFile?> {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                }
        } else {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
        }
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
        return fail("refreshVfs", "${e.javaClass.simpleName}: ${e.message}")
    } ?: return fail("refreshVfs", "VFS not visible after refresh (${ioFile.path}, exists=${ioFile.exists()})")
    // T-EC-04：VFS 缺条目裸写路径的防御收口——正常恒 no-op，异常残留在此收敛（含落盘）
    try {
        syncExecVfContentIfNeeded(project, vf, sql)
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
        return fail("syncVfContent", "${e.javaClass.simpleName}: ${e.message}")
    }
    mapDialectBeforePsi(project, vf, LOG, renderedDialectName)
    val psi = try {
        findExecPsi(project, vf)
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
        return fail("findPsi", "${e.javaClass.simpleName}: ${e.message}")
    } ?: (try {
        // T-ExecPsiGate F2：PSI 取不到时自愈——先看 vf 是否失效（重定位），再清映射重建。
        // 两支都不成立时下面带五元组诊断收口。
        healExecPsi(project, vf, LOG, ioFile)
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e
        null
    }) ?: run {
        // F6：成因随「阶段 + 成因」成对记下——失败文案＋面板蓝色可点入口都靠它，
        // 且读方不清空、下一次带成因的失败才覆盖（见 TempFileHintRecord）。
        // **永不落 null**：归因面自己抛异常时退到 UNKNOWN，而 UNKNOWN 也带落点入口——
        // 真机第三/四轮的「只剩红字」都是这条链上的 null 造成的。
        val hint = runCatching { execPsiFailureCauseNow(project, vf) }.getOrNull()
            ?: ExecPsiFailureCause.UNKNOWN
        return fail("findPsiAfterHeal", execPsiDiagnostics(project, vf), hint)
    }
    // 自愈可能换了一个新 vf（vf 失效重定位），缓存与后续 attach 必须用同一个
    val effectiveVf = runCatching { psi.virtualFile }.getOrNull() ?: vf
    // T-PerfSweep2-04：写点统一走 markCachedTempPsiFile（挂一次性项目关闭钩子）
    markCachedTempPsiFile(project, effectiveVf, psi)
    return effectiveVf to psi
}

/**
 * T-ExecPsiGate：`PsiManager.findFile` 的单点封装（read action 内取，调用方负责线程）。
 *
 * 抽成函数只为让自愈链与诊断共用同一份取法，语义与既有 `ReadAction.compute { findFile(vf) }` 逐字一致。
 */
internal fun findExecPsi(project: Project, vf: com.intellij.openapi.vfs.VirtualFile): PsiFile? =
    ReadAction.compute<PsiFile?, Throwable> {
        PsiManager.getInstance(project).findFile(vf)
    }

/**
 * T-ExecPsiGate：PSI 建不出来时的**自愈**（exec 链）。
 *
 * 两支，顺序固定：
 *
 *  1. **vf 失效重定位**（R2/R3 实测后升为主修）：exec 文件本身在盘上，只是手里的 vf 条目已失效
 *     ——watcher 与 refresh 抢跑、外部替换文件、网络盘/同步盘都是这个形态。此时平台 `findFile`
 *     只会返回 null（`FileManagerImpl` 的 vf 失效分支），清映射救不了它。重新 `refresh` 定位
 *     一次拿到新 vf 再建 PSI 即可。
 *  2. **清方言映射重建**：映射指向的语言在本机建不出 SQL PSI（自注册方言 parserDefinition
 *     未生效 / 平台缺该方言 / 历史坏映射）。清掉映射让平台回落默认语言，preview 链在同一
 *     状态下一直工作正常。
 *
 * 两支都不成立时返回 null，由调用方带五元组诊断报错。返回的 PSI 若挂在**新** vf 上，调用方必须
 * 用 `psi.virtualFile` 取回那个 vf（缓存与后续 attach 必须用同一个）。
 */
internal fun DataSourceSelector.healExecPsi(
    project: Project,
    vf: com.intellij.openapi.vfs.VirtualFile,
    LOG: com.intellij.openapi.diagnostic.Logger,
    ioFile: java.io.File? = null,
): PsiFile? {
    // 支 1：vf 失效 → 重新定位
    if (!runCatching { vf.isValid }.getOrDefault(false)) {
        if (ioFile == null) {
            LOG.warn("[M36][T-ExecPsiGate] exec vf invalid and no ioFile for re-locate: ${vf.name}")
            return null
        }
        val refound = runCatching {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
        }.getOrNull()
        val psi = refound?.let { runCatching { findExecPsi(project, it) }.getOrNull() }
        if (psi != null) {
            LOG.warn(
                "[M36][T-ExecPsiGate] exec vf was INVALID — re-located by refresh and rebuilt PSI: ${refound!!.path}",
            )
            return psi
        }
        LOG.warn(
            "[M36][T-ExecPsiGate] exec vf invalid and re-locate FAILED (exists=${ioFile.exists()}): ${ioFile.path}",
        )
        return null
    }
    // 支 2：vf 有效但 PSI 建不出来 → 清映射重建
    val before = runCatching {
        ReadAction.compute<String?, Throwable> { DialectPreMapping.configuredLanguageId(project, vf) }
    }.getOrNull()
    val cleared = runCatching { DialectPreMapping.clearMapping(project, vf) }.getOrDefault(false)
    val healed = runCatching { findExecPsi(project, vf) }.getOrNull()
    if (healed != null) {
        LOG.warn(
            "[M36][T-ExecPsiGate] exec PSI rebuilt after clearing dialect mapping " +
                "(was=$before, cleared=$cleared): ${vf.name}",
        )
    } else {
        LOG.warn(
            "[M36][T-ExecPsiGate] exec PSI still null after clearing dialect mapping " +
                "(was=$before, cleared=$cleared): ${vf.name}",
        )
    }
    if (healed != null) return healed
    // 支 3（R4，2026-11-11 双平台真机五元组驱动）：清映射没用 ⇒ 毒源不是**我们写的**映射，
    // 而是**继承来的**方言——项目根目录映射 / 全局默认 SQL 方言（Settings → SQL Dialects）/
    // `SqlDialectPusher` 按数据源推的方言。这三者都靠“没有显式映射”生效，所以清掉我们的映射
    // 反而把它们请回来了：PSI 语言依旧没有可用 ParserDefinition，findFile 恒 null。
    // 唯一能覆盖继承值的手段是**显式钉一个已知可建 SQL PSI 的方言**（`canCreateSqlPsiFor` 判过）。
    for (pinId in listOf(ExecDialectRouting.MYSQL_LANGUAGE_ID, ExecDialectRouting.GENERIC_LANGUAGE_ID)) {
        val pinLanguage = runCatching { DialectPreMapping.languageById(project, pinId) }.getOrNull() ?: continue
        if (!canCreateSqlPsiFor(pinLanguage)) continue
        val mapped = runCatching { DialectPreMapping.ensureMapped(project, vf, pinLanguage, false) }.getOrDefault(false)
        if (!mapped) continue
        val pinned = runCatching { findExecPsi(project, vf) }.getOrNull()
        if (pinned != null) {
            LOG.warn(
                "[M36][T-ExecPsiGate] exec PSI rebuilt by PINNING dialect '$pinId' — the poison was an " +
                    "INHERITED dialect (project-root mapping / global default / pusher), not our own: ${vf.name}",
            )
            return pinned
        }
    }
    LOG.warn(
        "[M36][T-ExecPsiGate] exec PSI still unavailable after pinning MySQL/GenericSQL — " +
            "inherited=${runCatching { DialectPreMapping.inheritedLanguageId(project) }.getOrNull()}: ${vf.name}",
    )
    // 注意（2026-11-11 用户定案）：这里**不再自动换落点**。`.sql-visualizer` 是兜底而不是默认——
    // 由用户在失败行上点「改用项目根落点重试」触发（那一支会先把项目记进
    // [DataSourceSelector.execRootFallbackProjects]，之后执行直接走备用落点）。自动换落点会把
    // 用户项目配置的问题藏起来，正是要避免的。
    return null
}

/**
 * T-ExecPsiGate 13.12：**目录级**的成因判定（给「预览自动兜底」的告知文案用）。
 *
 * 与 [execPsiFailureCauseNow] 的区别：那个判的是**文件**（还要看语言/方言），这里判的是**目录**——
 * 预览在选出临时目录那一刻文件还没建出来，只能看「这层目录在平台内容模型里的待遇」。
 * 语言判据给中性值（`GenericSQL` + `canPsiActual=true`），让它落到 UNKNOWN 而不是误报方言问题。
 *
 * F5（2026-11-11 真机）：**目录级探针的结论必须显式带进判定**。否则文件级四项谓词全绿时这里仍落
 * UNKNOWN，调用方（失败文案）就只剩红字、没有任何可点入口——这正是用户第二轮的验收问题。
 *
 * @return null = 该目录能建 PSI（或探不出来，保守按能）；非 null = 已经落到备用落点，值为成因
 */
internal fun execDirFailureCause(project: Project, dir: java.io.File): ExecPsiFailureCause? {
    if (execDirCanHostPsi(project, dir)) return null
    val dirVf = runCatching { LocalFileSystem.getInstance().findFileByIoFile(dir) }.getOrNull() ?: return null
    val fileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
    return classifyExecPsiFailure(
        excluded = runCatching { fileIndex.isExcluded(dirVf) }.getOrDefault(false),
        regHideExcluded = runCatching {
            com.intellij.openapi.util.registry.Registry.`is`("ide.hide.excluded.files")
        }.getOrDefault(false),
        fileTypesIgnored = runCatching {
            com.intellij.openapi.fileTypes.FileTypeManager.getInstance().isFileIgnored(dirVf)
        }.getOrDefault(false),
        underIgnored = runCatching { fileIndex.isUnderIgnored(dirVf) }.getOrDefault(false),
        psiLangId = "GenericSQL",
        canPsiActual = true,
        dirCanHostPsi = false,
    )
}

/** F6：主落点目录（`<base>/.idea/sql-visualizer/exec`）——**只算路径**，不建目录、不写盘。 */
internal fun execPrimaryDir(project: Project): java.io.File? {
    val base = project.basePath ?: return null
    val sep = java.io.File.separator
    return java.io.File(base, ".idea$sep${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}${sep}exec")
}

/**
 * T-ExecPsiGate R6（2026-11-11 用户真机）：**项目根备用执行文件**。
 *
 * 用户实测（UOS + Mac，同一个项目）：同一个 `mpsql-exec.sql`，**放桌面（项目外）用数据源右键
 * script 能正常执行**，只要放 `.idea/` 下就必报 `findPsi ... null`——即那台机器上 `.idea` 里的
 * `.sql` 建不出 PSI（`.idea` 被标成 excluded，或平台把它当配置目录对待）。
 *
 * 本函数给出一条「仍在项目内容根内、但不在 `.idea` 下」的落点：
 * `<projectBase>/.sql-visualizer/exec/mpsql-exec.sql`。目录里顺手写一份 `.gitignore`（内容 `*`），
 * 免得污染用户的 `git status`。**F6 起由落点探针自动选中**（`decideExecLandingRoot`），
 * 之后由 [DataSourceSelector.execRootFallbackProjects] 记住，后续执行直接走这条路。
 */
internal fun projectRootExecFile(project: Project, sql: String): java.io.File? =
    DataSourceSelector.projectExecFile(project, sql, preferProjectRoot = true)

/**
 * T-ExecPsiGate：`findFile` 失败的**诊断串**（进用户可见失败文案的 {0}）。
 *
 * 平台 `FileManagerImpl.findFile` 只有三条 null 分支（目录 / vf 无效 / PSI 创建被拒），三条
 * 在用户侧的表现完全相同（都只是 null）。原文案只带文件名，真机日志与用户反馈都无法区分——
 * 2026-11-10 的 Mac / UOS 报障就卡在这里。
 *
 * **R4 补两字段（2026-11-11）**：真机五元组显示 `vfValid=true / mappedLang=null` 却仍拿不到 PSI，
 * 说明决定 PSI 语言的是**继承来的**方言（项目根目录映射 / 全局默认 SQL 方言 / pusher 按数据源推的
 * 方言）——只报「显式映射」等于什么都没说。故加 `inheritedLang` 与它的 `canPsiInherited`：
 * 前者为空而后者 false 时，基本可以直接判「这台机器的继承方言建不出 SQL PSI」。
 */
/**
 * T-ExecPsiGate 13.10：把当前观测值交给纯函数 [classifyExecPsiFailure]，得「为什么建不出 PSI」。
 *
 * 观测面与 2026.3 平台闸门（`AbstractFileViewProvider.shouldCreatePsi`）一一对应：
 * `ProjectFileIndex.isExcluded` / `Registry("ide.hide.excluded.files")` / `FileTypeManager.isFileIgnored` /
 * `ProjectFileIndex.isUnderIgnored`，再加上「平台实际选中的语言能否建 PSI」。任何一项取不到都按
 * false/null 处理——判错最多是入口指偏，不会影响执行链本身。
 */
internal fun execPsiFailureCauseNow(project: Project, vf: com.intellij.openapi.vfs.VirtualFile): ExecPsiFailureCause {
    val fileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
    val excluded = runCatching { fileIndex.isExcluded(vf) }.getOrDefault(false)
    val regHideExcluded = runCatching {
        com.intellij.openapi.util.registry.Registry.`is`("ide.hide.excluded.files")
    }.getOrDefault(false)
    val underIgnored = runCatching { fileIndex.isUnderIgnored(vf) }.getOrDefault(false)
    val fileTypesIgnored = runCatching {
        com.intellij.openapi.fileTypes.FileTypeManager.getInstance().isFileIgnored(vf)
    }.getOrDefault(false)
    val psiLanguage = runCatching {
        ReadAction.compute<com.intellij.lang.Language?, Throwable> {
            com.intellij.lang.LanguageUtil.getLanguageForPsi(project, vf, vf.fileType)
        }
    }.getOrNull()
    // F5（2026-11-11 真机）：**平台闸门问的是父目录**，四项文件谓词看不见父目录的待遇。
    // 真机上出现过「文件级全绿、findFile 恒 null」，只靠文件谓词只能判成 UNKNOWN（= 只丢红字，
    // 没有可点入口，正是这一轮的验收问题）。所以目录级的「排除 / 忽略 / 忽略模式 / 能否建 PSI」
    // 一律与文件级取**并集**：闸门只要被父目录挡住就返回 null，取并集才与闸门口径一致。
    val parentVf = runCatching { vf.parent }.getOrNull()
    val parentExcluded = runCatching {
        parentVf != null && fileIndex.isExcluded(parentVf)
    }.getOrDefault(false)
    val parentUnderIgnored = runCatching {
        parentVf != null && fileIndex.isUnderIgnored(parentVf)
    }.getOrDefault(false)
    val parentTypeIgnored = runCatching {
        parentVf != null && com.intellij.openapi.fileTypes.FileTypeManager.getInstance().isFileIgnored(parentVf)
    }.getOrDefault(false)
    val dirCanHostPsi = runCatching {
        ReadAction.compute<Boolean, Throwable> {
            parentVf != null && PsiManager.getInstance(project).findDirectory(parentVf) != null
        }
    }.getOrNull()
    return classifyExecPsiFailure(
        excluded = excluded || parentExcluded,
        regHideExcluded = regHideExcluded,
        fileTypesIgnored = fileTypesIgnored || parentTypeIgnored,
        underIgnored = underIgnored || parentUnderIgnored,
        psiLangId = psiLanguage?.id,
        canPsiActual = runCatching { canCreateSqlPsiFor(psiLanguage) }.getOrDefault(false),
        dirCanHostPsi = dirCanHostPsi,
    )
}

internal fun execPsiDiagnostics(project: Project, vf: com.intellij.openapi.vfs.VirtualFile): String {
    val mappedId = runCatching {
        ReadAction.compute<String?, Throwable> { DialectPreMapping.configuredLanguageId(project, vf) }
    }.getOrNull()
    val canPsi = runCatching {
        canCreateSqlPsiFor(mappedId?.let { DialectPreMapping.languageById(project, it) })
    }.getOrDefault(false)
    // 继承链（项目根映射 → 全局默认）：这才是「没有显式映射」时真正生效的方言
    val inheritedId = runCatching { DialectPreMapping.inheritedLanguageId(project) }.getOrNull()
    val canPsiInherited = runCatching {
        canCreateSqlPsiFor(inheritedId?.let { DialectPreMapping.languageById(project, it) })
    }.getOrDefault(false)
    val vfValid = runCatching { vf.isValid }.getOrDefault(false)
    val isDir = runCatching { vf.isDirectory }.getOrDefault(false)
    val fileTypeName = runCatching { vf.fileType.name }.getOrDefault("?")
    val path = runCatching { vf.path }.getOrDefault("?")
    // R5（2026-11-11）：真机两台的设置里 exec 文件显式映射为 MySQL、全局/项目方言都是 <None>，
    // 却仍拿不到 PSI——说明「映射到什么」和「平台实际给这个文件什么语言」已经对不上了。
    // 直接把平台**实际选中的 PSI 语言**与 IDE Build 打进失败文案：前者是决定性证据（若它抛异常，
    // 就是方言替换器在用户那台机器上直接崩），后者是唯一还没拿到的环境变量。
    val psiLangProbe = runCatching {
        ReadAction.compute<com.intellij.lang.Language?, Throwable> {
            com.intellij.lang.LanguageUtil.getLanguageForPsi(project, vf, vf.fileType)
        }
    }
    val psiLang = psiLangProbe.getOrNull()?.id
        ?: psiLangProbe.exceptionOrNull()?.javaClass?.simpleName?.let { "EX:$it" }
    // 决定性组合：psiLang=MySQL 而 canPsiActual=false ⇒ 那台机器上 MySQL 方言没有可用的
    // ParserDefinition（SQL 方言模块缺失/版本不匹配），与本插件无关，但我们可以绕开（钉别的方言）。
    val canPsiActual = runCatching { canCreateSqlPsiFor(psiLangProbe.getOrNull()) }.getOrDefault(false)
    val ideBuild = runCatching {
        com.intellij.openapi.application.ApplicationInfo.getInstance().build.asString()
    }.getOrNull()
    // 2026.3 的 shouldCreatePsi 只看「忽略集」或（Registry 勾选时）「排除」；Project 视图里看不到
    // 不等于被排除——这两个是两个独立谓词。三项一次打全，用户截一张图就能判型。
    val excluded = runCatching {
        com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isExcluded(vf)
    }.getOrNull()
    val underIgnored = runCatching {
        com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isUnderIgnored(vf)
    }.getOrNull()
    val regHideExcluded = runCatching {
        com.intellij.openapi.util.registry.Registry.`is`("ide.hide.excluded.files")
    }.getOrNull()
    // F5：**文件级谓词看不见父目录**——平台闸门问的是 `findDirectory(父目录)`。真机上出现过
    // 文件级四项全绿而 findFile 恒 null 的形态，只打文件级等于把唯一能判型的信息落下了。
    val parentVf = runCatching { vf.parent }.getOrNull()
    val dirCanHostPsi = runCatching {
        parentVf != null && ReadAction.compute<com.intellij.psi.PsiDirectory?, Throwable> {
            PsiManager.getInstance(project).findDirectory(parentVf)
        } != null
    }.getOrNull()
    val dirExcluded = runCatching {
        parentVf != null && com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isExcluded(parentVf)
    }.getOrNull()
    val dirUnderIgnored = runCatching {
        parentVf != null && com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isUnderIgnored(parentVf)
    }.getOrNull()
    return "name=${vf.name}, vfValid=$vfValid, dir=$isDir, fileType=$fileTypeName, " +
        "mappedLang=$mappedId, canPsi=$canPsi, inheritedLang=$inheritedId, " +
        "canPsiInherited=$canPsiInherited, psiLang=$psiLang, canPsiActual=$canPsiActual, " +
        "excluded=$excluded, underIgnored=$underIgnored, regHideExcluded=$regHideExcluded, " +
        "dirPsi=${dirCanHostPsi}, dirExcluded=$dirExcluded, dirUnderIgnored=$dirUnderIgnored, " +
        "ideBuild=$ideBuild, path=$path"
}

/**
 * T-ExecPsiGate F5（2026-11-11 真机）：**目录级诊断串**（进 `createTempPsiFile` 的 `dirNoPsi` 阶段）。
 *
 * 为什么另起一条：真机实测文件级四项谓词可以全绿而 `findFile` 恒 null——平台闸门问的是父目录，
 * 文件谓词看不见父目录的待遇。把目录级的同一个谓词与探针结论直接打进文案，下一次截图就能定案。
 */
internal fun execDirDiagnostics(project: Project, dir: java.io.File): String {
    val dirVf = runCatching { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir) }.getOrNull()
    val dirPsi = runCatching {
        if (dirVf == null) null else ReadAction.compute<com.intellij.psi.PsiDirectory?, Throwable> {
            PsiManager.getInstance(project).findDirectory(dirVf)
        }
    }.getOrNull() != null
    val fileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
    val dirExcluded = runCatching { dirVf != null && fileIndex.isExcluded(dirVf) }.getOrDefault(false)
    val dirUnderIgnored = runCatching { dirVf != null && fileIndex.isUnderIgnored(dirVf) }.getOrDefault(false)
    val dirTypeIgnored = runCatching {
        dirVf != null && com.intellij.openapi.fileTypes.FileTypeManager.getInstance().isFileIgnored(dirVf)
    }.getOrDefault(false)
    val regHideExcluded = runCatching {
        com.intellij.openapi.util.registry.Registry.`is`("ide.hide.excluded.files")
    }.getOrDefault(false)
    val inContent = runCatching { dirVf != null && fileIndex.isInContent(dirVf) }.getOrDefault(false)
    val ideBuild = runCatching {
        com.intellij.openapi.application.ApplicationInfo.getInstance().build.asString()
    }.getOrNull()
    return "dir=${dir.path}, dirVfValid=${dirVf != null}, dirPsi=$dirPsi, inContent=$inContent, " +
        "dirExcluded=$dirExcluded, dirUnderIgnored=$dirUnderIgnored, dirTypeIgnored=$dirTypeIgnored, " +
        "regHideExcluded=$regHideExcluded, ideBuild=$ideBuild"
}

/**
 * M36.5.3：临时文件方言预映射（MySQL）——须在 [PsiManager.findFile] 之前调用，
 * PSI 创建时即按映射解析为 MySQL（否则走 SqlDialectPusher，dumb mode 下被推迟）。
 * 方言实例经 SqlDialectMappings.getAvailableValues() 按 Language.id 匹配（不硬编码实现类），
 * 任何环节失败静默跳过（保持原行为，仅记录日志）。
 *
 * T-ExecSlowOps F1：反射段收编 [DialectPreMapping]（与预览路径 mapPreviewDialectBeforePsi 共用），
 * 本方法保留 exec 侧「进程内同 vf 幂等短路」语义 + 成功回写 [dialectMappedVf]；[ensureMapped]
 * 线程自适应——EDT 直调安全（本方法现调用点全在 EDT），BGT 调用自动走两段式派发。
 */

internal fun DataSourceSelector.prewarmExecTempFileInner(
    project: Project,
    sql: String,
    LOG: com.intellij.openapi.diagnostic.Logger,
    renderedDialectName: String? = null,
    cacheOnly: Boolean = false,
) {
    // T-ExecPsiGate F3/F6：预热链是并发写方——它只能写自己的结果，不能清别人的失败记录。
    // F3 收掉了「入口清空 lastTempFileFailure」；F6 把成因改成「阶段+成因」成对记录后，
    // 连「谁写谁清」这条隐患也不存在了（消费方按阶段匹配）。
    // T-UiFixGate9：入口守卫——prewarmExecTempFile 的 isDisposed 检查在提交池线程之前，与本链
    // 实际执行之间隔着任意长的排队/执行窗口（victim 测试毫秒级跑完即收尾 dispose）。池线程链
    // 起跑时项目可能已 dispose（含 temporarily-disposed），整链放弃，不在死项目上写/refresh。
    if (project.isDisposed) {
        LOG.info("[M36] exec prewarm abandoned (project disposed, T-UiFixGate9)")
        return
    }
    // T-PerfSweep30 01a：cache-only 形态——VFS 已有 exec 条目且内容与本次 SQL 一致时**跳过写盘**，
    // 只做 refresh＋方言映射＋findFile＋填缓存槽（性能分析 §4.1 账本①：同一预览打开内预热原文写盘
    // 与渲染回填/方言重渲染预热重复改写同一文件）。两条不变量保持不变：
    //  ① 首建（VFS 无条目）裸写仍在 execFileWriteLock 锁内、与存在性检查原子（下方 projectExecFile 未走）；
    //  ② VFS 已有条目绝不裸写（T-ExecDocSync3）——cache-only 判等成立即返回，判等不成立才走既有写路径。
    // 判等口径与写路径**同函数**（projectExecFile 锁内即用它）：document 文本优先、否则读「最近写入
    // 内容+写入时 VFS 时间戳」快照；两者皆不可用（外部写入/无 document）时不采信 → 保守走写路径。
    var cacheOnlyHit = false
    if (cacheOnly) {
        val base = project.basePath
        if (base == null) {
            LOG.warn("[M36] exec prewarm cache-only abandoned: project.basePath null")
            return
        }
        val f = java.io.File(
            java.io.File(base, ".idea${java.io.File.separator}${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}${java.io.File.separator}exec"),
            "mpsql-exec.sql",
        )
        // 判等前先 refresh 到 VFS 可见——首次裸写（FileUtil.writeToFile）后 VFS 条目可能尚未建立，
        // findFileByIoFile 会返回 null（误判「not visible」→ 保守走写路径）。refresh 是只读操作，
        // 不触写；与下方主链 refresh 段同款（BGT 裸调合法）。
        val existing = runCatching {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
        }.getOrNull()
        if (existing != null && execFileContentMatches(project, existing, sql)) {
            LOG.info("[M36] exec prewarm cache-only: content already matches — write SKIPPED (T-PerfSweep30 01a)")
            // 零写盘：跳过下方 projectExecFile（其差量快路径同判据，但会多一次 document 派发探测），
            // 保留 pre-refresh 收敛（恒 no-op）＋ refresh＋映射＋findFile＋填槽。
            cacheOnlyHit = true
        } else {
            LOG.info(
                "[M36] exec prewarm cache-only: content mismatch (or not visible) — falling back to write path " +
                    "(T-PerfSweep30 01a)",
            )
        }
    }
    // T-PrewarmWilDeadlock：下方 refresh 段**必须裸调**（BGT 严禁 WIRA×同步 refresh）——T-ExecSlowOps
    // 曾自 EDT 冷路径同款复制 WIRA 包装，2026.2 真机（threadDump-20261015-181620）实证自锁：本线程
    // 持写意向锁阻塞在 RefreshSessionImpl.waitFor，而会话推进需 WIL/EDT，互等成环（EDT 再被预览
    // 编辑器 mousePressed 排队等 WIL 即扩大为全 IDE「not responding」）。BGT 裸调即平台合法形态
    // （下方 pre-refresh 的 findFileByIoFile 同款；EDT 冷路径 createTempPsiFile 的 WIRA 语境不同，须保留）。
    val ioFile = if (cacheOnlyHit) {
        java.io.File(
            java.io.File(project.basePath!!, ".idea${java.io.File.separator}${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}${java.io.File.separator}exec"),
            "mpsql-exec.sql",
        )
    } else {
        runCatching { projectExecFile(project, sql, preferProjectRoot = project in execRootFallbackProjects) }.getOrElse { e ->
            // T-FatalPassthrough T-FP-02b（独立复核阻断 2）：fatal 上浮（预热跑在 BGT，透传由平台
            // 记录现场）；非 fatal 仍返回（writeFile 失败 → 跳过预热，语义逐字不变）。
            if (FatalErrors.isFatal(e)) throw e
            LOG.warn("[M36] exec prewarm writeFile failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        } ?: return
    }
    // T-UiFixGate9：阶段守卫——写链推进期间（文件 IO 毫秒级，VFS refresh 更长）项目随时可能
    // 进入收尾 dispose；每段写/收敛动作前复查，死项目即放弃（投机性写入不重跑）。
    if (project.isDisposed) {
        LOG.info("[M36] exec prewarm abandoned before pre-refresh converge (project disposed, T-UiFixGate9)")
        return
    }
    // T-ExecDocSync2 F4：refresh 前强制收敛——projectExecFile 的 doc 写在 EDT 落盘后、本
    // refresh 之前，用户在控制台（document 即 exec 文件 document）键入可插入 modified-unsaved
    // 态；此时 refresh 命中「doc 已修改 && 磁盘≠文档」即弹 File Cache Conflict（第四轮残余竞态，
    // 开发计划 §1.3）。findFileByIoFile（与 projectExecFile 内裸调同形态，prewarm DONE 日志
    // 实证 BGT 可调）命中即过一遍分叉探测 + EDT 派发收敛，正常恒 no-op。
    // T-PerfSweep30 01a：cache-only 命中形态**跳过本收敛**——判等已证内容一致（document 文本或
    // 写入快照），不存在分叉面；且本收敛的 dispatchDocWriteOnEdt 会把相同内容再派一次 document
    // 写（旧实现下 cache-only 快路径形同虚设）。
    if (!cacheOnlyHit) {
        try {
            LocalFileSystem.getInstance().findFileByIoFile(ioFile)?.let { vf0 ->
                syncExecVfContentIfNeeded(project, vf0, sql)
            }
        } catch (e: Throwable) {
            if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
            LOG.warn("[M36] exec prewarm pre-refresh converge FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    val vf = try {
        // T-PrewarmWilDeadlock：裸调（见本方法首部注释）——262 平台 BGT 持 WIL 时同步 refresh
        // 会话永不可能完成（自锁），裸调的异步会话在 EDT 健康时正常推进
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传（prewarm 跑在 BGT，透传由平台记录现场）
        LOG.warn("[M36] exec prewarm refreshVfs failed: ${e.javaClass.simpleName}: ${e.message}")
        return
    } ?: run {
        LOG.warn("[M36] exec prewarm: VFS not visible after refresh (${ioFile.path}, exists=${ioFile.exists()})")
        return
    }
    // T-UiFixGate9：阶段守卫——refresh 是链中最慢一段，此刻项目可能已开始收尾；后续
    // syncExecVfContentIfNeeded 派发的 doc 写虽已被 docWriteOnce 咽喉守卫拦截，此处直接放弃
    // 免得再付一次 refresh 后探测与方言映射（死项目上的 PSI 访问只会刷 AlreadyDisposed 噪音）。
    if (project.isDisposed) {
        LOG.info("[M36] exec prewarm abandoned after refresh (project disposed, T-UiFixGate9)")
        return
    }
    // T-EC-04：同冷路径——裸写残留的防御收口（正常恒 no-op）
    if (!cacheOnlyHit) {
        try {
            syncExecVfContentIfNeeded(project, vf, sql)
        } catch (e: Throwable) {
            if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
            LOG.warn("[M36] exec prewarm syncVfContent failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
    }
    // T-ExecSlowOps F1：BGT 两段式映射（读探测稳态跳过 / 首见派 EDT + 有界等待）。
    // O-145 W7：预览提供了渲染方言时 exec PSI 同步该事实；旧调用仍走 O-136 数据源路由。
    mapDialectBeforePsi(project, vf, LOG, renderedDialectName)
    val psi = try {
        findExecPsi(project, vf)
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
        LOG.warn("[M36] exec prewarm findPsi failed: ${e.javaClass.simpleName}: ${e.message}")
        return
    } ?: (runCatching {
        // T-ExecPsiGate：预热链与冷路径同款自愈（vf 失效重定位 / 清映射重建）。这里只为把缓存
        // 填上——自愈失败不阻断，冷路径自己还有一次机会。
        healExecPsi(project, vf, LOG, ioFile)
    }.getOrNull()) ?: run {
        LOG.warn("[M36][T-ExecPsiGate] exec prewarm: findFile null — ${execPsiDiagnostics(project, vf)}")
        return
    }
    if (project.isDisposed) return
    markCachedTempPsiFile(project, runCatching { psi.virtualFile }.getOrNull() ?: vf, psi)
    LOG.info("[M36] exec prewarm DONE (cache filled off EDT): ${vf.name}")
}

