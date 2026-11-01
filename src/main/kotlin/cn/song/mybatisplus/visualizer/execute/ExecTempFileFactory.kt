package cn.song.mybatisplus.visualizer.execute

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
        app.invokeLater({ onReady(pair) }, ModalityState.nonModal())
        return
    }
    app.executeOnPooledThread {
        val pair = createTempPsiFile(project, sql, renderedDialectName)
        app.invokeLater({ onReady(pair) }, ModalityState.nonModal())
    }
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
 *     （本券修复本体）。
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
        ReadAction.compute<PsiFile?, Throwable> {
            PsiManager.getInstance(project).findFile(vf)
        }
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
        return fail("findPsi", "${e.javaClass.simpleName}: ${e.message}")
    } ?: return fail("findPsi", "PsiManager.findFile null (${vf.name})")
    // T-PerfSweep2-04：写点统一走 markCachedTempPsiFile（挂一次性项目关闭钩子）
    markCachedTempPsiFile(project, vf, psi)
    return vf to psi
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
) {
    lastTempFileFailure = null
    // T-UiFixGate9：入口守卫——prewarmExecTempFile 的 isDisposed 检查在提交池线程之前，与本链
    // 实际执行之间隔着任意长的排队/执行窗口（victim 测试毫秒级跑完即收尾 dispose）。池线程链
    // 起跑时项目可能已 dispose（含 temporarily-disposed），整链放弃，不在死项目上写/refresh。
    if (project.isDisposed) {
        LOG.info("[M36] exec prewarm abandoned (project disposed, T-UiFixGate9)")
        return
    }
    // T-PrewarmWilDeadlock：下方 refresh 段**必须裸调**（BGT 严禁 WIRA×同步 refresh）——T-ExecSlowOps
    // 曾自 EDT 冷路径同款复制 WIRA 包装，2026.2 真机（threadDump-20261015-181620）实证自锁：本线程
    // 持写意向锁阻塞在 RefreshSessionImpl.waitFor，而会话推进需 WIL/EDT，互等成环（EDT 再被预览
    // 编辑器 mousePressed 排队等 WIL 即扩大为全 IDE「not responding」）。BGT 裸调即平台合法形态
    // （下方 pre-refresh 的 findFileByIoFile 同款；EDT 冷路径 createTempPsiFile 的 WIRA 语境不同，须保留）。
    val ioFile = runCatching { projectExecFile(project, sql) }.getOrElse { e ->
        // T-FatalPassthrough T-FP-02b（独立复核阻断 2）：fatal 上浮（预热跑在 BGT，透传由平台
        // 记录现场）；非 fatal 仍返回（writeFile 失败 → 跳过预热，语义逐字不变）。
        if (FatalErrors.isFatal(e)) throw e
        LOG.warn("[M36] exec prewarm writeFile failed: ${e.javaClass.simpleName}: ${e.message}")
        return
    } ?: return
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
    try {
        LocalFileSystem.getInstance().findFileByIoFile(ioFile)?.let { vf0 ->
            syncExecVfContentIfNeeded(project, vf0, sql)
        }
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
        LOG.warn("[M36] exec prewarm pre-refresh converge FAILED: ${e.javaClass.simpleName}: ${e.message}")
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
    try {
        syncExecVfContentIfNeeded(project, vf, sql)
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
        LOG.warn("[M36] exec prewarm syncVfContent failed: ${e.javaClass.simpleName}: ${e.message}")
        return
    }
    // T-ExecSlowOps F1：BGT 两段式映射（读探测稳态跳过 / 首见派 EDT + 有界等待）。
    // O-145 W7：预览提供了渲染方言时 exec PSI 同步该事实；旧调用仍走 O-136 数据源路由。
    mapDialectBeforePsi(project, vf, LOG, renderedDialectName)
    val psi = try {
        ReadAction.compute<PsiFile?, Throwable> {
            PsiManager.getInstance(project).findFile(vf)
        }
    } catch (e: Throwable) {
        if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
        LOG.warn("[M36] exec prewarm findPsi failed: ${e.javaClass.simpleName}: ${e.message}")
        return
    } ?: run {
        LOG.warn("[M36] exec prewarm: findFile null (${vf.name})")
        return
    }
    if (project.isDisposed) return
    markCachedTempPsiFile(project, vf, psi)
    LOG.info("[M36] exec prewarm DONE (cache filled off EDT): ${vf.name}")
}
