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
 * T-ExecPlanEdtSlowOp：**EDT 入口**——把 [createTempPsiFile] 的慢段整体挪出 EDT。
 *
 * 缺陷实证（2026-11-01 真机，`docs/验收文档/20261101-1600-OB面板自动化验收第二轮`）：
 * 点「执行计划」时平台抛
 * ```
 * SEVERE SlowOperations - Plugin to blame: SQL Visualizer
 * explainSql → submitExplainOrStructured → promptDataSourceThenRetryStructured
 *   → DataSourceSelector.prepareConsole → chooseAndPrepare → createTempPsiFile
 *   → ReadAction.compute { PsiManager.getInstance(project).findFile(vf) }
 * ```
 * `ReadAction.compute` 只保证读语义，**不改变线程**——PSI 查找照跑在 EDT 上，243 平台的
 * `SlowOperations` 断言必然 SEVERE。冷路径（缓存未命中）在三条路上都可达：执行计划从
 * 「未绑定数据源」入口发起时没有预热、回滚快照对话框、显式执行首跑/预热被 skip 或过期。
 *
 * 线程契约：
 *  - **EDT 调用**（生产唯一形态）：立刻返回，慢段在池线程执行，结果经**write-safe 投递**回到 EDT
 *    交给 [onReady]——续接链里的弹窗/内嵌控制台/结果面板动作因此仍全部在 EDT；
 *  - 非 EDT 调用（防御分支，生产不可达）：慢段在调用线程就地跑（BGT 上合法），**回调同样经
 *    write-safe 投递回 EDT**——续接链是 UI + 文档写，不能由后台线程承接。
 *
 * **读锁（UI-002，2026-11-04 真机）**：慢段搬上池线程后，段内**两处 PSI 判定各自要读锁**——
 * 缓存命中路径的有效性判定（`consumeCachedTempPsiFile` 内 `psi.isValid`）与冷路径的
 * `PsiManager.findFile`。`ReadAction` 在这里解决的是**读权限**（BGT 没有），与「慢操作必须离开
 * EDT」是两条独立的纪律：前者缺了打 `Read access is allowed from inside read-action only`，
 * 后者缺了打 `Slow operations are prohibited on EDT`。EDT 侧回调里的 PSI 判定（[revalidateOnEdt]）
 * 同样显式取读锁，不依赖 EDT 隐式权限。守卫：ExecTempFileEdtGuardTest 规则六。
 *
 * **写安全（2026-11-01 真机回归，243.28141.41 字节码逐帧实证）**：投递模态必须是
 * `ModalityState.nonModal()`，**不能用 `any()`**。
 *  - `TransactionGuardImpl.<init>` 字节码：`createConcurrentWeakMap()` 之后立即
 *    `put(ModalityState.nonModal(), TRUE)`——**nonModal 是构造期就登记为 write-safe 的模态**；
 *    `any()` 不在表内，`isWriteSafeModality(any())` 恒 false。
 *  - `wrapLaterInvocation` 产出的 `TransactionGuardImpl$1.run`：`isWriteSafeModality(模态)` 为真
 *    → `assertWriteIntentLockAcquired()` + `runWithWritingAllowed(runnable)`（内部把
 *    `myWritingAllowed` 置 true 再跑，跑完还原）；否则**裸 run**。即 any() 排出的回调里
 *    `isWritingAllowed()` 不成立。
 *  - 而续接链 [continuePrepareAfterTempFile] 内含文档写（共享控制台 `editor.document.setText`、
 *    控制台文档 `setText` + `PsiDocumentManager.commitDocument`）——any() 投递实测每次点
 *    「执行计划」都紧跟一条 `SEVERE TransactionGuardImpl … Write-unsafe context`（真机
 *    16:41:44 / 16:48:03 / 16:53:49 三连，与点击时间戳一一对应）。
 *  - `TransactionGuard.submitTransaction(Disposable, Runnable)` 虽是平台等价 write-safe 入口
 *    （内部 `AppUIExecutor.onWriteThread(nonModal).later().expireWith(…)`），但 243 已
 *    `Deprecated: true`（`javap -v` 实证，四个重载全弃用），故采用非弃用的等价形态
 *    `invokeLater(…, ModalityState.nonModal())`——与仓内 `DocWriteContext.invokeLaterWriteSafe` 同款。
 *  - 模态语义：nonModal 在模态存续期**延后**执行，这正是写入类回调的正确语义（模态期间写文档
 *    本身被平台禁止，见 `DocWriteContext` 类注释定例）；本链的 runner 选择器/内嵌控制台是在
 *    续接链**内部**弹出，续接链不会被自己的弹窗阻塞。
 *
 * 契约影响：本函数**异步**——调用方不得在调用返回后立即使用临时文件，必须写在 [onReady] 里
 * （`chooseAndPrepare` 的后续全链已按此迁入 `continuePrepareAfterTempFile`）。
 *
 * @param onReady 结果回调；null = 建链失败（阶段原因在 `lastTempFileFailure`）。EDT 调用时
 *   回调保证在 EDT 上、且处于 write-safe 上下文（可写文档）。
 */
internal fun DataSourceSelector.createTempPsiFileAsync(
    project: Project,
    sql: String,
    renderedDialectName: String? = null,
    onReady: (Pair<VirtualFile, PsiFile>?) -> Unit,
) {
    val app = ApplicationManager.getApplication()
    if (!app.isDispatchThread) {
        // 已是后台线程：慢段就地跑（合法），但回调仍回 EDT + write-safe——续接链是 UI + 文档写，
        // 不能让调用方线程直接承接（否则弹窗越线程、文档写无写安全资格）
        val pair = createTempPsiFile(project, sql, renderedDialectName)
        // 投递模态 = nonModal（构造期即登记为 write-safe），**不是 any()**：any() 排出的回调
        // 不套 runWithWritingAllowed，续接链的文档写必触发 TransactionGuard 的 Write-unsafe 断言
        // （2026-11-01 真机回归，见方法 KDoc「写安全」节）。守卫：ExecTempFileEdtGuardTest。
        app.invokeLater({ onReady(revalidateOnEdt(project, pair)) }, ModalityState.nonModal())
        return
    }
    app.executeOnPooledThread {
        val pair = createTempPsiFile(project, sql, renderedDialectName)
        app.invokeLater({ onReady(revalidateOnEdt(project, pair)) }, ModalityState.nonModal())
    }
}

/**
 * T-ExecPsiInvalid（2026-11-01 全量门禁实证）：异步窗口内的 **PSI 失效兜底**。
 *
 * 缺陷形态：`createTempPsiFile` 在池线程 `findFile` 拿到的 [PsiFile] 到 EDT 回调消费之间有一个
 * 空窗。空窗里只要该文件的内容发生一次变化（doc 驱动的三方同步落盘、防抖自动落盘、用户/其他
 * 写方覆盖 `mpsql-exec.sql`），平台就会丢掉旧 PSI——`vf.isValid` 仍为 true（**只有内容变了**），
 * 而 `psi.text` 硬抛 `PsiInvalidElementAccessException: different providers: …; null(0)`。
 * 旧同步实现把整段压在 EDT 上，EDT 被占住时排队的内容变化落不下来，所以该窗口一直没暴露；
 * 改异步后它成了真窗口（门禁 shard2 实证：8900 例中唯一红点）。
 *
 * 兜底口径：**只有 PSI 失效才重解析**（有效即原样返回，热路径零额外开销）；重解析仍失败则
 * 按建链失败返回 null（续接链会走既有失败文案，不把死 PSI 递进 Runner 枚举）。
 * 调用点固定为 EDT 投递 lambda 内（守卫 ExecTempFileEdtGuardTest 规则五同时盯住投递模态）。
 *
 * 线程契约（UI-002）：本函数在 **EDT**（write-safe 投递内）执行，但 `psi.isValid` / `findFile`
 * 都是 PSI API，**读权限一律显式取**——不依赖 EDT 隐式读权限（新线程模型下
 * `Application.isReadAccessAllowed()` 只看本线程读许可，`TransactionGuard` 的 write-safe 只放开
 * 文档写、不放读；平台注释亦声明该隐式权限将在后续版本移除）。因此两处 `isValid` 都落在
 * `ReadAction` 作用域内（第二处随 `findFile` 同一次读锁），后台误调本函数也不会打平台读锁断言。
 */
internal fun revalidateOnEdt(
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
    // （且中间仍有失效窗口），合并后只读一次平台状态。
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

/**
 * M36.5.75：exec 临时文件 + VF + PsiFile 的**慢段本体**——写盘 → VFS refresh → 方言映射 →
 * `PsiManager.findFile` → 填缓存槽。**只允许后台线程调用**（EDT 调用会撞平台慢操作断言；
 * 唯一的 EDT 侧入口是 [createTempPsiFileAsync]，由它派发到池线程）。
 *
 * 返回值：[VirtualFile] 与 [PsiFile] 配对（runner 上下文 + 控制台挂载点，VF 须保持引用）；
 * null = 失败，阶段原因见 `lastTempFileFailure`（T-ExecCopyFix F2a）。
 *
 * 线程要求逐段标注（**两段相反，不得整体搬**）：
 *  1. 写盘 / 分叉收敛：[projectExecFile]、[syncExecVfContentIfNeeded] 自带线程分流
 *     （document 写统一派 EDT + 有界等待），BGT 直调合法；
 *  2. VFS refresh（下方 [REFRESH] 段）：BGT **必须裸调**——包 `WriteIntentReadAction` 会
 *     「持写意向锁等需要 WIL/EDT 的刷新会话」自锁（T-PrewarmWilDeadlock，threadDump-20261015-181620
 *     真机实证全 IDE not responding）；EDT 分支（本券后生产不可达，仅防御）仍保留 WIRA 包装——
 *     262 平台 EDT + plain ReadAction 内同步 refresh 硬抛 write thread only（T-ExecCopyFix F2b）；
 *  3. 方言映射 [mapDialectBeforePsi]（下方 [MAPPING] 段）：**必须在 findFile 之前**完成，且
 *     `setMapping` 必须在 EDT——本调用点留在慢段里，由 `DialectPreMapping.ensureMapped` 的两段式
 *     承担（BGT 读探测 → invokeLater 派 EDT 写 → 有界等待，EDT 永不反向阻塞；与
 *     [prewarmExecTempFileInner] 同款）。此处**不得**改成裸反射 setMapping；
 *  4. PSI 查找（下方 [PSI] 段）：ReadAction 只保证读语义、不改变线程，故必须整段在 BGT 上
 *     （本券修复本体）。**读权限与线程是两条纪律**：本段是 BGT，`ReadAction` 在这里是**必需**的
 *     （不是可选的语义包装）——BGT 无读许可，裸读 PSI 打 `Read access is allowed from inside
 *     read-action only`；
 *  5. 缓存命中路径的 PSI 有效性判定（[consumeCachedTempPsiFile] 内 `psi.isValid`）：同样在 BGT，
 *     同样必须包 `ReadAction`（UI-002 真机栈本体；`vf.isValid` 是 VFS 判定不需要读锁）。
 */
internal fun DataSourceSelector.createTempPsiFile(
    project: Project,
    sql: String,
    renderedDialectName: String? = null,
): Pair<VirtualFile, PsiFile>? {
    val LOG = Logger.getInstance(DataSourceSelector::class.java)
    // T-ExecCopyFix F2a：失败阶段记录（onError 文案携带，见 chooseAndPrepare）
    fun fail(stage: String, detail: String = ""): Nothing? {
        lastTempFileFailure = "$stage${if (detail.isNotEmpty()) ": $detail" else ""}"
        LOG.warn("[M36] createTempPsiFile FAILED at $stage${if (detail.isNotEmpty()) " ($detail)" else ""}")
        return null
    }
    lastTempFileFailure = null
    // M36.5.12：临时文件改存项目内 `.idea/sql-visualizer/exec/`（F-44-4：目录随插件更名）
    //（参考 sql-history 保存位置），不再用系统 Temp——Temp 文件不在数据源关联范围，
    // 导致 IDE 报 "Unable to resolve table"（M36.5.11 问题一，日志实证挂载点在
    // file:///C:/Users/USER/AppData/Local/Temp/）。项目内文件可被 VFS 追踪、可被
    // Database 插件解析。文件名稳定 mpsql-exec.sql（每次覆盖，不膨胀）。
    val ioFile = try {
        projectExecFile(project, sql)
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
        // [MAPPING] 段：BGT 上调 ensureMapped 走「读探测 + 派 EDT 写」，写仍落 EDT（见 KDoc 第 3 条）
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
    // [REFRESH] 段（慢操作，线程要求见方法 KDoc 第 2 条）：BGT 裸调 / EDT 防御分支保留 WIRA
    val vf = try {
        if (ApplicationManager.getApplication().isDispatchThread) {
            // 防御分支：本券后生产不可达（EDT 侧唯一入口是 createTempPsiFileAsync）
            WriteIntentReadAction.compute<VirtualFile?> {
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
    // [MAPPING] 段：须在 PSI 创建之前（setMapping 写仍由 ensureMapped 派 EDT 完成，见 KDoc 第 3 条）
    mapDialectBeforePsi(project, vf, LOG, renderedDialectName)
    // [PSI] 段（本券修复本体）：ReadAction 不改变线程——本段必须已在 BGT 上，否则即
    // 2026-11-01 真机的 SEVERE SlowOperations
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
    }) ?: return fail("findPsiAfterHeal", execPsiDiagnostics(project, vf))
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
internal fun findExecPsi(project: Project, vf: VirtualFile): PsiFile? =
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
    vf: VirtualFile,
    LOG: Logger,
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
    return healed
}

/**
 * T-ExecPsiGate：`findFile` 失败的**五元组诊断**（进用户可见失败文案的 {0}）。
 *
 * 平台 `FileManagerImpl.findFile` 只有三条 null 分支（目录 / vf 无效 / PSI 创建被拒），三条
 * 在用户侧的表现完全相同（都只是 null）。原文案只带文件名，真机日志与用户反馈都无法区分——
 * 2026-11-10 的 Mac / UOS 报障就卡在这里。五项一次给全：vf 有效性、是否目录、文件类型、
 * 当前生效映射到的语言、该语言能否建 SQL PSI（附绝对路径便于回传）。
 */
internal fun execPsiDiagnostics(project: Project, vf: VirtualFile): String {
    val mappedId = runCatching {
        ReadAction.compute<String?, Throwable> { DialectPreMapping.configuredLanguageId(project, vf) }
    }.getOrNull()
    val canPsi = runCatching {
        canCreateSqlPsiFor(mappedId?.let { DialectPreMapping.languageById(project, it) })
    }.getOrDefault(false)
    val vfValid = runCatching { vf.isValid }.getOrDefault(false)
    val isDir = runCatching { vf.isDirectory }.getOrDefault(false)
    val fileTypeName = runCatching { vf.fileType.name }.getOrDefault("?")
    val path = runCatching { vf.path }.getOrDefault("?")
    return "name=${vf.name}, vfValid=$vfValid, dir=$isDir, fileType=$fileTypeName, " +
        "mappedLang=$mappedId, canPsi=$canPsi, path=$path"
}

/**
 * M36.5.3：临时文件方言预映射（MySQL）——须在 [PsiManager.findFile] 之前调用，
 * PSI 创建时即按映射解析为 MySQL（否则走 SqlDialectPusher，dumb mode 下被推迟）。
 * 方言实例经 SqlDialectMappings.getAvailableValues() 按 Language.id 匹配（不硬编码实现类），
 * 任何环节失败静默跳过（保持原行为，仅记录日志）。
 *
 * T-ExecSlowOps F1：反射段收编 [DialectPreMapping]（与预览路径 mapPreviewDialectBeforePsi 共用），
 * 本方法保留 exec 侧「进程内同 vf 幂等短路」语义 + 成功回写 [dialectMappedVf]；[ensureMapped]
 * 线程自适应——EDT 直调安全，BGT 调用自动走两段式派发（`setMapping` 写始终落在 EDT）。
 */
internal fun DataSourceSelector.prewarmExecTempFileInner(
    project: Project,
    sql: String,
    LOG: Logger,
    renderedDialectName: String? = null,
    cacheOnly: Boolean = false,
) {
    lastTempFileFailure = null
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
        runCatching { projectExecFile(project, sql) }.getOrElse { e ->
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
    // T-PerfSweep30 01a：cache-only 命中同样跳过（判等已证内容一致，无防御面；避免重复派写）
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
