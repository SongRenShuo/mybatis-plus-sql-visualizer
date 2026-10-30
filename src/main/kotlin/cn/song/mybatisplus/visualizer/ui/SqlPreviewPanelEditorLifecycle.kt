package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.DocWriteContext
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContributor
import cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter
import cn.song.mybatisplus.visualizer.ui.inlay.PreviewInlayScope
import cn.song.mybatisplus.visualizer.util.EditorCommentShortcuts
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
// T-M53-II：扩展函数看不见接收者类的 companion 成员（Kotlin 规则），须逐项显式导入。
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.DEGRADED_GRACE_MS
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.PREWARM_WAIT_MS
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.PREVIEW_DIR_MAX_FILES
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.STALE_PREVIEW_MAX_AGE_MS
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.nextPreviewTempFileName
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.previewDialectMappingTarget

// T-M53-II（2026-10-10）：以下为 SqlPreviewPanel 的「预览编辑器装配、预热链与临时文件回收」内聚成员，
// 以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal。

internal fun SqlPreviewPanel.prewarmPreviewTempFile() {
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
    // T-PerfTail #2：预热线程产出 PrewarmAwait 完成态（成功 complete / 失败 fail）——
    // 消费侧（createEditor）不再 EDT sleep-poll
    // T-FixN1（N1 写安全）：派发模态由 any() 改 defaultModalityState()（写安全模态）。回调体是
    // resolvePreviewEditorSource 的 onReady/onFallback → assemblePreviewEditor，其内**写文档**
    // （WriteCommandAction { d.setText(sql) } + commitDocument）——而 any() **恒 write-unsafe**：
    // TransactionGuardImpl 的 myWriteSafeModalities 只预置 nonModal→true（构造期），
    // wrapLaterInvocation 只对 isWriteSafeModality(state)==true 的 runnable 套 runWithWritingAllowed
    // （any() 走 else 分支直接 run，myWritingAllowed 保持 false），于是 DocumentImpl.assertWriteAccess
    // → assertWriteActionAllowed 在 myWritingAllowed=false 时 LOG.error「Write-unsafe context!」
    // （真机必现：重开预览第 2 次即被平台 Suppressed 计数；243 平台 jar 反编译逐帧实证）。
    // 代价：模态对话框挂起期间派发推迟到模态关闭——面板本就在模态框后面（用户看不见），观感代价
    // 接近零。且冷态派发发生在预热池线程（无模态进度上下文），defaultModalityState() 常态落
    // NON_MODAL（恒 write-safe），与 11e2671 选 any() 的唯一理由「派发不被模态挡住、EDT 零阻塞」
    // 不冲突——该提交信息里没有「必须在模态对话框挂起期间完成装配」的场景。
    // 边界：控制台挂载/回填链路（M36.5，本文件 onConsole 回调的 post-attach 重渲染等站点）的
    // any()/nonModal() 是另一条链路，不在本改动范围。
    // T-PrewarmLateRecover F1：轮次令牌与迟到产物接收方都必须在预热线程可能发布**之前**就位
    // （令牌晚了会让旧轮产物盖掉新轮；接收方晚了会漏掉「超时已放弃、产物随后到达」这一态）。
    val round = ++prewarmRound
    val await = PrewarmAwait<Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>>(
        PREWARM_WAIT_MS,
        prewarmDispatcher,
    )
    await.onLateProduct { product -> prewarmDispatcher { tryUpgradeToLateProduct(product) } }
    prewarmAwait = await
    // T-StabilityTail：把「项目开始销毁」接到取消门——池线程不得比项目活得更久
    bindPrewarmToProjectLifecycle()
    ApplicationManager.getApplication().executeOnPooledThread {
        // T-SqlDialectPrewarm：本线程先完成 SQL 方言映射的一次性加载（BGT 上做，EDT 上做即平台慢操作
        // 断言）。顺序意图：预热产物 await 完成 → EDT 才装配预览编辑器（createEditor(doc, proj,
        // sqlFileType, …) 会按 SQL FileType 取高亮器）——排在产物之前即继承这个先后关系。
        // 放在耗时打点之前：prewarmStarteAt 量的仍是临时文件产物的自身耗时，不被这次预热污染。
        project?.let { cn.song.mybatisplus.visualizer.ui.highlight.SqlDialectMappingPrewarm.prewarmNow(it) }
        // T-PrewarmBudget（2026-09-20）：预热**自身耗时**打点起点——此前全链无完成打点，
        // 真机实测报告只能靠「方言映射行出现」与「临时文件被删」两条间接判据夹逼出区间
        // （最紧一次 ∈ (280ms, 1071ms]），无法定 `PREWARM_WAIT_MS` 预算。本打点即补这个洞：
        // 预热完成/失败时各记一行带耗时，用于在空闲机取样本后定预算。
        val prewarmStartedAt = System.nanoTime()
        if (previewTempFile != null || prewarmAbandoned()) {
            await.fail(IllegalStateException("prewarm skipped: disposed or already prepared"))
            return@executeOnPooledThread
        }
        runCatching {
            // T-PreviewExecDs-01a：预览临时文件迁项目内 .idea/sql-visualizer/preview/
            // ——系统 Temp 不在数据源关联范围，Database 插件不解析（M36.5.12 exec 同教训，
            // 预览路径此前未跟进；旧实现 09:34:40 起持续预热失败后回退裸文档，补全/注释全灭）。
            val io = projectPreviewFile() ?: run {
                prewarmFailed = true
                await.fail(PreviewPrewarmException(PreviewDegradationReason.FILE_UNAVAILABLE, "project preview file unavailable"))
                LOG.warn("[M36] prewarm FAILED in ${prewarmElapsedMs(prewarmStartedAt)}ms: project preview file unavailable (project/basePath null or mkdirs false)")
                return@executeOnPooledThread
            }
            // T-GifRerecord：VFS 忙时 refreshAndFindFileByIoFile 偶发查不到新文件（2026.2 沙盒
            // 录制期实证）——旧实现只重试一次仍持续失败（两项目同开 VFS 忙窗口）；T-PreviewExecDs-01b
            // 加重试到 3 次带退避，失败置 prewarmFailed 让 await 快速返回，不空等预算。
            val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            var vf = lfs.refreshAndFindFileByIoFile(io)
            var retries = 0
            while (vf == null && retries < 3 && !prewarmAbandoned()) {
                Thread.sleep(100L * (retries + 1))
                vf = lfs.refreshAndFindFileByIoFile(io)
                retries++
            }
            if (vf == null) {
                prewarmFailed = true
                await.fail(PreviewPrewarmException(PreviewDegradationReason.VFS_UNAVAILABLE, "VFS refresh null after ${retries + 1} tries"))
                LOG.warn("[M36] prewarm FAILED in ${prewarmElapsedMs(prewarmStartedAt)}ms: VFS refresh null after ${retries + 1} tries (${io.path}, exists=${io.exists()})")
                return@executeOnPooledThread
            }
            if (prewarmAbandoned()) {
                java.io.File(vf.path).delete()
                await.fail(IllegalStateException("disposed during prewarm"))
                return@executeOnPooledThread
            }
            val proj = project ?: return@executeOnPooledThread
            // T-StabilityTail：紧贴取服务再判一次——从上一处判定到这里之间项目可能已开始销毁
            // （closeProject 的写动作与本线程并行）；窗口内首次创建服务失败会把该服务在该项目
            // 实例上永久毒化（`LazyInstanceHolder` 缓存 CannotInitialize 后每次 getService 重抛，
            // 见 config/SectionConfigurable.kt KDoc），故宁可少取一次服务。
            if (prewarmAbandoned()) {
                await.fail(IllegalStateException("abandoned before service lookup"))
                return@executeOnPooledThread
            }
            // T-PreviewExecDs-01c：方言映射须在 findFile/PSI 创建前（映射决定 PSI 语言，
            // 创建后再映射需 push 重建；exec 路径同款顺序）
            val targetLanguageId = mapPreviewDialectBeforePsi(proj, vf)
            // T-DegradedPreviewUx P4：映射切换的语言替换重解析是异步的——等到本文件 PSI 的
            // 语言已等于目标再取实例，避免捕获即将失效的旧语言 PSI（真机 16:14:33 实证）。
            val psi = if (targetLanguageId != null) {
                awaitPreviewPsiLanguage(proj, vf, targetLanguageId)
            } else {
                // T-PreviewPsiDialect：同 awaitPreviewPsiLanguage —— findFile 抛平台契约异常时按
                // 「没有 PSI」处理（记 WARN 带堆栈），不让它升级成点名插件的 SEVERE。
                runCatching {
                    com.intellij.openapi.application.ReadAction.compute<PsiFile?, Throwable> {
                        if (prewarmAbandoned()) null else com.intellij.psi.PsiManager.getInstance(proj).findFile(vf)
                    }
                }.onFailure {
                    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                        .warn("[T-PreviewPsiDialect] PsiManager.findFile threw for ${vf.name}", it)
                }.getOrNull()
            }
            if (psi == null) {
                // 放弃（项目/面板已销毁）与真失败分开记账：前者不置 prewarmFailed、不记 WARN——
                // 它是正常生命周期事件，不是预热故障
                if (prewarmAbandoned()) {
                    await.fail(IllegalStateException("abandoned during findFile"))
                    return@executeOnPooledThread
                }
                prewarmFailed = true
                await.fail(PreviewPrewarmException(PreviewDegradationReason.PSI_UNAVAILABLE, "PsiManager.findFile null for ${vf.name}"))
                LOG.warn("[M36] prewarm FAILED in ${prewarmElapsedMs(prewarmStartedAt)}ms: PsiManager.findFile null for ${vf.name}")
                return@executeOnPooledThread
            }
            previewTempFile = vf
            // 结果可能恰在首次映射与 PSI 创建之间到达；再走一次幂等同步，避免早期 MySQL 映射赢竞态。
            var publishPsi = psi
            if (previewDesiredDialectName != null) {
                val remappedId = mapPreviewDialectBeforePsi(proj, vf)
                if (remappedId != null) {
                    // 映射若再次变化，旧实例同样会被重解析失效：等落地并重取
                    publishPsi = awaitPreviewPsiLanguage(proj, vf, remappedId) ?: publishPsi
                }
            }
            publishPrewarmProduct(await, vf to publishPsi, prewarmStartedAt, round)
        }.onFailure { e ->
            prewarmFailed = true
            await.fail(e)
            LOG.warn(
                "[M36] prewarm FAILED in ${prewarmElapsedMs(prewarmStartedAt)}ms: " +
                    "${e.javaClass.simpleName}: ${e.message}",
                e,
            )
        }
    }
}

/**
 * T-PreviewDocLock2 F3 回归修复：预热产物**发布**——字段与 await 必须拿到**同一个 Pair 实例**。
 *
 * 消费侧 [assemblePreviewEditor] 以「引用相等 + 随即置 null」表达「产物尚未被消费」，
 * 故发布侧不能把 `vf to psi` 写两遍（原实现 `prewarmedTempFile = vf to psi` 与
 * `await.complete(vf to psi)` 各造一个实例 → `===` 恒假 → 主路径恒判「产物不可用」，
 * 预览对话框每次打开都降级到内存 PSI 并挂降级横幅）。抽成本方法后「同一实例」由单次
 * 构造保证，并由 SqlPreviewPanelPrewarmConsumeTest 以真实消费链（预热产物就绪 → showResult
 * → 编辑器文档落在项目内临时文件上）锁死该不变式。
 */

internal fun SqlPreviewPanel.publishPrewarmProduct(
    await: PrewarmAwait<Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>>,
    product: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>,
    startedAtNanos: Long,
    round: Int,
) {
    // T-PrewarmBudget（2026-09-20）：预热**成功**耗时打点——消费侧预算 `PREWARM_WAIT_MS` 只有在
    // 「预热耗时 < 预算」时才可能走主路径；此前无此打点，预算取值只能靠间接夹逼。判读口径：
    // 本行耗时 > PREWARM_WAIT_MS ⇒ 该次打开必然落 onFallback 降级（可与紧随其后的
    // `[M36] in-memory PSI preview document created` 对照）。
    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        .info(
            "[M36] prewarm done in ${prewarmElapsedMs(startedAtNanos)}ms " +
                "(file=${product.first.name}, psi=${product.second.name})",
        )
    // T-PrewarmLateRecover F1：旧轮产物不入字段（见 [prewarmRound]）——文件仍由 [releaseEditor]
    // 按登记表回收，不会漏删。
    if (round != prewarmRound) {
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] prewarm product dropped: stale round=$round (current=$prewarmRound)")
        return
    }
    prewarmedTempFile = product
    // T-PrewarmLateRecover F1：`complete` 返回 false = 消费侧已放弃（超时以 null 完成 / 失败），
    // 此时产物走迟到通道换装——这正是「首开预热 3016ms、预算 500ms」那类场景的恢复出口。
    if (!await.complete(product)) {
        prewarmDispatcher { tryUpgradeToLateProduct(product) }
    }
}

/** T-PrewarmBudget：预热打点用耗时（毫秒）；`nanoTime` 差值，不受系统时钟回拨影响。 */

internal fun SqlPreviewPanel.prewarmElapsedMs(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos) / 1_000_000L

/**
 * T-StabilityTail：把「项目开始销毁」接到预热取消门（[prewarmAbandoned] 的项目侧判据）。
 *
 * **为什么挂 `ProjectEx.getEarlyDisposable()`**——平台 KDoc 原文即此用途：
 * 「a Disposable that will be disposed right after container started to be disposed … In unit test
 *   mode light project is not disposed, but this disposable is disposed for each test. So, you don't
 *   need to have another disposable and can use this one instead.」
 * 两个触发点：①真机关闭项目——`ProjectManagerImpl.closeProject` 非 light 分支在写动作内先
 * `disposeEarlyDisposable()` 再 `startDispose()`；②light 测试夹具的**每个测试方法 tearDown**——
 * 同方法 light 分支把 `disposeEarlyDisposable()` 与 `setTemporarilyDisposed(true)` 放进同一写动作
 * （`ProjectManagerImpl.kt:345-352`），后者正是「池线程在 temporarily-disposed 窗口内首次取服务 →
 * 服务被永久毒化」的时点。
 *
 * **为什么不挂 `Disposer.register(project, …)`**：light 项目的**主** disposable 在片内跨测试方法
 * 一直存活（`setTemporarilyDisposed(false)` 只重建 earlyDisposable），挂主树等于整轮不触发。
 *
 * **取消而非 join**：门一置，预热线程在其下一个检查点放弃——不再发起 `SqlDialectMappings` /
 * `PsiManager` 取服务，于是不会把「窗口内首次创建服务失败」的异常喂给平台 holder。不等待的两个理由：
 * 项目关闭路径上 join 会把在途预热的耗时加进用户可感的关闭时间；且平台对该 Disposable 的 dispose
 * 有硬要求——「must be self-contained and isolated (getting services is forbidden)」，本实现只置标志。
 *
 * **降级**：earlyDisposable 树的取入口是 `@ApiStatus.Internal`（Marketplace 审核对 internal
 * API 用法要求作者响应，20260923），且项目已 dispose 时抛 `AlreadyDisposedException`——取树改走
 * **反射**（字节码零平台 internal 类型引用；方法在 ProjectImpl 上实现，KDoc 口径不变），并用
 * [Disposer.tryRegister]（父已销毁即返回 false，不抛）加外层 runCatching 兜底；反射失败或
 * 挂不上时静默返回，行为退回改动前，预热主链不受影响。
 */

internal fun SqlPreviewPanel.bindPrewarmToProjectLifecycle() {
    if (prewarmLifecycleGate != null) return // 已挂：clear() 重预热不重复登记
    val proj = project ?: return
    val gate = Disposable { previewDisposed = true }
    val registered = runCatching {
        // T-MarketCompatProxy：getEarlyDisposable 标 @ApiStatus.Internal 且无公开等价——
        // 反射取树（仅此一处调用），消除 verifier 的 internal API usage。
        val early = runCatching {
            proj.javaClass.getMethod("getEarlyDisposable").invoke(proj) as? Disposable
        }.getOrNull() ?: return@runCatching false
        Disposer.tryRegister(early, gate)
    }.getOrDefault(false)
    if (registered) prewarmLifecycleGate = gate
}

/**
 * T-StabilityTail：预热线程的「放弃」判据——面板已销毁 或 项目已开始销毁。
 *
 * 项目侧判据取 `Project.isDisposed`：平台口径为 `super.isDisposed() || isTemporarilyDisposed`
 * （`ProjectImpl.isDisposed()`，243/262/2026.2 三版 javap 同形），temporarily-disposed 窗口内为真。
 * 它在这里是**门判据的补充**而非「盲目守卫」：真正的修法是上面那道生命周期取消门（让池线程
 * 不再比项目活得久），本判据只用来收掉「判定通过后、取服务前项目被并发关闭」的窄缝——那条缝
 * 平台侧无锁可挡（读动作会被 closeProject 的写动作阻塞，放行时窗口已开）。
 */

internal fun SqlPreviewPanel.prewarmAbandoned(): Boolean = previewDisposed || project?.isDisposed == true

/**
 * T-PreviewExecDs-01a：项目内预览临时文件——`<project>/.idea/sql-visualizer/preview/`。
 * 独立子目录（exec/ 互不污染，同 M36.5.13 语义）。文件在项目内容内，Database 插件才能解析
 * schema 补全/注释。VFS 安全写入三态（document 驱动 > setBinaryContent > FileUtil 兜底），
 * 杜绝 MemoryDiskConflictResolver 的 File Cache Conflict 弹窗（exec 路径 M36.5.13 同款）。
 *
 * T-FixN2：文件名由固定 `mpsql-preview.sql` 改为**每次预热唯一**（见 [nextPreviewTempFileName]）。
 * 固定名的缺陷链（真机必现，第二轮复核 3 次询问/2 次弹窗）：预热重写同一路径，而上一轮装配
 * 写进该文件 document 的真 SQL **从未落盘**（`assemblePreviewEditor` 只 `setText`，该文档不走
 * `saveDocument`），于是磁盘恒为预热占位空内容 + 内存文档是「已修改未保存」；平台
 * `MemoryDiskConflictResolver.beforeContentChange` 判据（243 sources 实证）正是
 * 「VFileContentChangeEvent 且该路径有 cached document 且 isDocumentUnsaved 且
 * documentStamp != oldFileStamp」——三条全中 → 弹模态「File Cache Conflict」抢焦点；
 * 用户选「Load File System Changes」即 `reloadFromDisk(document)` 把内存里的真 SQL 换成磁盘
 * 的占位空内容（表现为编辑区只剩占位文案，点【刷新】重新渲染才恢复）。
 * 唯一文件名让每次预热都落在**没有任何 document 的全新路径**上 → 首次创建裸写安全
 * （exec 路径 T-ExecDocSync3 同款判据：VFS 无该条目时不可能存在 document），冲突链从根上消失。
 * existing 分支保留为兜底（跨会话残留的同名文件 / 同 pid 复用），仍是 VFS 安全写三态。
 *
 * 建文件即登记进 [previewTempFilesCreated]，由 [releaseEditor] 回收（本面板文件数不随预览次数增长）。
 */
@Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用

internal fun SqlPreviewPanel.projectPreviewFile(): java.io.File? {
    val proj = project ?: return null
    val base = proj.basePath ?: return null
    val dir = java.io.File(
        base,
        ".idea${java.io.File.separator}${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}${java.io.File.separator}preview",
    )
    if (!dir.exists() && !dir.mkdirs()) return null
    // T-PerfSweep6 T-PS6-04（发现 4）：本面板首次解析预热目录时做一次目录级兜底清扫
    // （年龄 > 7 天 / 条数 > 200），只认本插件自有命名——见 [sweepStalePreviewFiles]。
    // 每次面板生命周期只扫一次（清扫是兜底，不必每次预览都付一遍 listFiles）。
    if (previewDirSwept.compareAndSet(false, true)) {
        sweepStalePreviewFiles(dir)
    }
    // T-FixN2 × T-InsertColumnInlay O-44b 合并口径：文件名**唯一**（每次预热换名，见上）且**由
    // inlay 作用域锚点同一份常量生成**——锚点靠文件名形态判定「是不是本面板预览编辑器」，
    // 名字来源与形态判据同处 [PreviewInlayScope]，两处各自硬编码字面量会在改名时静默打穿锚点
    // （inlay 不报错，只是永不出现）。
    val f = java.io.File(dir, nextPreviewTempFileName())
    val existing = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(f)
    if (existing != null) {
        val ok = runCatching {
            com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
                val fileDoc = com.intellij.openapi.fileEditor.FileDocumentManager
                    .getInstance().getDocument(existing)
                when {
                    fileDoc != null -> fileDoc.setText("") // 预热占位空内容，createEditor 装配时按实际 SQL 填充
                    fileDoc == null -> existing.setBinaryContent("".toByteArray())
                }
            }
            true
        }.getOrDefault(false)
        if (ok) {
            previewTempFilesCreated.add(f)
            return f
        }
    }
    com.intellij.openapi.util.io.FileUtil.writeToFile(f, "")
    previewTempFilesCreated.add(f)
    return f
}

/**
 * T-PreviewExecDs-01c / O-145 W5：预览文件方言预映射——PSI 创建时即按本次 SQL 的实际生成方言解析，
 * 让编辑器语言、补全和渲染保持一致。结果尚未到达时先用兼容 MySQL；结果到达后按
 * [SqlPreviewPanel.previewDesiredDialectName] 重映射。同目录幂等同时比较 language id，避免早到的
 * MySQL 映射阻断后到的 Oracle/PostgreSQL；平台缺对应方言时映射 GenericSQL，不反向冒充 MySQL。
 *
 * T-ExecSlowOps F1：反射段收编进 [DialectPreMapping]，本方法仅保留「面板级进程内幂等短路」语义 +
 * 成功回写 [previewDialectMappedVf]。真机实锤：本方法在 [prewarmPreviewTempFile] 后台线程内调用，
 * 旧实现反射直调 `setMapping` → 平台 `handleMappingChange → reparseFiles → runWriteAction →
 * VcsVFSListener.beforeContentsChange` 的 `assertEventDispatchThread` 硬抛
 * RuntimeExceptionWithAttachments（SEVERE ×3）。DialectPreMapping.ensureMapped 两段式：
 * 稳态（workspace.xml 映射幸存/本进程已设）读探测直接跳过，仅项目首见经 invokeLater 派 EDT 执行
 * 映射并 CountDownLatch 有界等待——映射写归 EDT、慢操作归 BGT，两侧 SEVERE 同消。
 *
 * T-FixN2：映射对象由「文件」改为**父目录**（文件名每次预热唯一后，按文件映射会让每个新文件名
 * 都判「未映射」→ 每轮预热白付一次 setMapping/push all dumb，且 workspace.xml 的
 * SqlDialectMappings 状态按文件路径逐个累积、只增不减）。平台 `PerFileMappingsBase.getMappingInner
 * → getMappingForHierarchy` 自下而上逐级查表（243 sources 实证：`for (cur = file; cur != null;
 * cur = cur.getParent())`），故**目录映射被其下所有文件继承**，一次映射覆盖全部预览文件
 * （含每次新名字）；[previewDialectMappedVf] 随之记「已映射的那个目录 vf」，
 * 身份判定（幂等短路）比较的是目录——目录 vf 在 VFS 里按路径唯一且本面板不删目录，跨预热稳定。
 * 目录 vf 取不到（极端）时退回按文件映射，语义同改动前。
 */

internal fun SqlPreviewPanel.mapPreviewDialectBeforePsi(proj: Project, vf: com.intellij.openapi.vfs.VirtualFile): String? {
    val target = previewDialectMappingTarget(vf)
    val desiredName = previewDesiredDialectName
    // T-DialectInferFix：没有预览级结论时**继承平台设置**，不再冒充 MySQL。
    // 历史版本在这里硬编码 MySQL，把「PG 项目的 preview SQL」按 MySQL 解析，`signal` 这类
    // PG 合法列名被 MySQL 保留字表判成语法错（真机假红）。
    if (desiredName == null) return inheritPlatformPreviewDialect(proj, target)
    val resolvedLanguage = cn.song.mybatisplus.visualizer.execute.ExecDialectRouting.resolvePluginDialectLanguage(proj, desiredName)
    // 已明确生成方言但平台无对应 parser 时用 GenericSQL，绝不反向冒充 MySQL。
    val targetLanguage = resolvedLanguage ?: cn.song.mybatisplus.visualizer.execute.DialectPreMapping.languageById(
        proj, cn.song.mybatisplus.visualizer.execute.ExecDialectRouting.GENERIC_LANGUAGE_ID,
    )
    if (targetLanguage == null) {
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .warn("[O-145] preview dialect '$desiredName' and GenericSQL platform Language are unavailable; keep existing mapping")
        return null
    }
    // T-PreviewPsiDialect（2026-10-30）：映射前先探一次「这个语言能不能建出 SQL PSI」。
    // 背景：插件自注册方言（DM/GaussDB/HighGo/Vastbase）原先只有 <sql.dialect> 没有
    // <lang.parserDefinition>，平台会沿语言链回落到基语言 SQL 的 parser definition，其 file node
    // type 绑的是基类 SqlLanguage → `SqlParserDefinitionBase.createFile` 的强转抛
    // ClassCastException → PSI 建不出来 → 整条预览降级（真机 262 实证，10/10 次）。
    // 注册面已在 database-optional.xml 成对补齐；这里留一道保险：将来再漏一个，最差是「这次不映射、
    // 补全按 GenericSQL 走」，不再拖垮整条预览。
    if (!canCreateSqlPsiFor(targetLanguage)) {
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java).info(
            "[T-PreviewPsiDialect] skip dialect mapping for '$desiredName': " +
                "language '${(targetLanguage as? com.intellij.lang.Language)?.id}' has no SQL parser definition; " +
                "keep platform default (completion falls back to GenericSQL)",
        )
        return null
    }
    val targetId = (targetLanguage as? com.intellij.lang.Language)?.id
        ?: cn.song.mybatisplus.visualizer.execute.ExecDialectRouting.GENERIC_LANGUAGE_ID
    val inProcessMapped = previewDialectMappedVf?.isValid == true && previewDialectMappedVf === target &&
        previewDialectMappedLanguageId.equals(targetId, ignoreCase = true)
    if (cn.song.mybatisplus.visualizer.execute.DialectPreMapping.ensureMapped(
            proj, target, targetLanguage, inProcessMapped,
        )) {
        previewDialectMappedVf = target
        previewDialectMappedLanguageId = targetId
        // T-DegradedPreviewUx P4：映射已就位才返回目标 id——调用方据此等语言替换重解析落地
        // 后再取 PSI（见 [awaitPreviewPsiLanguage]）。映射失败返回 null，不退化成白等 400ms。
        return targetId
    }
    return null
}

/**
 * T-DialectInferFix：无预览级方言结论时的**继承**路径。
 *
 * 我们自己的私有预览目录（`.idea/sql-visualizer/preview`）只有插件写过映射，因此这里的规则是：
 *  1. 读「项目级 → 全局默认 SQL 方言」（[DialectPreMapping.configuredLanguageId] 从项目根读，
 *     不读本目录自己的旧映射）——用户已经在设置里配过的方言优先；
 *  2. 读到就**显式改写**本目录映射（覆盖历史版本留下的 MySQL 兼容兜底，P-6 现场）；
 *  3. 平台什么都没配（全局也是 GenericSQL）→ 清掉本目录的旧映射，退回平台默认，
 *     既不冒充 MySQL 也不留旧值。
 *
 * 返回要等的 language id；拿不到平台 Language 对象时返回 null（调用方按「无映射」继续，
 * 最差等价 GenericSQL 旧行为，不会产生假红）。
 */
private fun SqlPreviewPanel.inheritPlatformPreviewDialect(
    proj: Project,
    target: com.intellij.openapi.vfs.VirtualFile,
): String? {
    val preMapping = cn.song.mybatisplus.visualizer.execute.DialectPreMapping
    val inheritedId = runCatching {
        preMapping.inheritedLanguageId(proj)
    }.getOrNull()
    val inheritedLanguage = inheritedId?.let { preMapping.languageById(proj, it) }
    if (inheritedLanguage != null) {
        val id = (inheritedLanguage as? com.intellij.lang.Language)?.id ?: return null
        val inProcessMapped = previewDialectMappedVf?.isValid == true && previewDialectMappedVf === target &&
            previewDialectMappedLanguageId.equals(id, ignoreCase = true)
        if (preMapping.ensureMapped(proj, target, inheritedLanguage, inProcessMapped)) {
            previewDialectMappedVf = target
            previewDialectMappedLanguageId = id
            return id
        }
        return null
    }
    // 平台没有可继承的方言：清掉本目录的旧插件映射（best-effort，失败不影响预览继续）。
    if (preMapping.configuredImmediateLanguageId(proj, target) != null) {
        preMapping.clearMapping(proj, target)
    }
    return cn.song.mybatisplus.visualizer.execute.ExecDialectRouting.GENERIC_LANGUAGE_ID
}


/**
 * T-DegradedPreviewUx P4：等方言映射引发的「语言替换重解析」落地，返回当前 PSI。
 *
 * `DialectPreMapping.ensureMapped` 只等到 EDT 的 `setMapping` 返回，而平台随后用异步任务对
 * 预览文件做 `LanguageSubstitutors` 重解析（真机日志 `Reparsing ... MySQL->PostgreSQL`）。
 * 立刻 `findFile` 会拿到**旧语言 PSI**，重解析落地后该实例失效——装配时 `getDocument` 抛错，
 * 整条预览落内存 PSI 降级（2026-10-20 16:14:33 沙盒实证：prewarm done 116ms → Reparsing →
 * in-memory PSI）。这里只轮询**本文件**的 PSI 语言是否已等于目标，不等待全局 dumb，
 * 有界预算用完即带当前实例返回（装配端还有一次按文件重取兜底）。
 */
internal fun SqlPreviewPanel.awaitPreviewPsiLanguage(
    proj: Project,
    vf: com.intellij.openapi.vfs.VirtualFile,
    targetLanguageId: String,
    budgetMs: Long = 400L,
): com.intellij.psi.PsiFile? {
    val deadline = System.nanoTime() + budgetMs * 1_000_000L
    while (true) {
        // T-PreviewPsiDialect：findFile 内部可能抛平台契约异常（典型是 SQL 方言缺 ParserDefinition
        // 导致的 ClassCastException）。这里只负责取实例，异常不该以 SEVERE 的形式记到插件头上——
        // 记一次 WARN 带堆栈，按「暂时没有 PSI」返回，让上层走既有的降级分型。
        val current = runCatching {
            com.intellij.openapi.application.ReadAction.compute<com.intellij.psi.PsiFile?, Throwable> {
                if (prewarmAbandoned()) null else com.intellij.psi.PsiManager.getInstance(proj).findFile(vf)
            }
        }.onFailure {
            com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java).warn(
                "[T-PreviewPsiDialect] PsiManager.findFile threw for ${vf.name} (target=$targetLanguageId)", it,
            )
        }.getOrNull()
        if (current == null || prewarmAbandoned()) return current
        if (current.language.id.equals(targetLanguageId, ignoreCase = true)) return current
        if (System.nanoTime() >= deadline) return current
        Thread.sleep(10L)
    }
}

/**
 * T-PreviewPsiDialect：该语言能否建出 SQL PSI。
 *
 * 判据取自平台行为本身——`SqlParserDefinitionBase.createFile` 要求
 * `getFileNodeType().getLanguage()` 是 `SqlLanguageDialect`。所以直接查「当前生效的
 * ParserDefinition 的 file node type 绑定的语言是不是 SQL 方言」。平台内置方言
 * （MySQL / PostgreSQL / GenericSQL / SQL92）与补齐后的四个自注册方言都返回 true；
 * 只有回落到基语言 `SQL` 的实现返回 false。
 *
 * **刻意不静态引用 `com.intellij.sql.*`**：本文件属常驻加载面，DatabaseTools 是可选依赖，
 * 直接引用会让无 Database 插件的环境类加载失败。故按类名沿继承链判定。
 */
internal fun canCreateSqlPsiFor(language: Any?): Boolean = runCatching {
    val lang = language as? com.intellij.lang.Language ?: return false
    val fileNodeLanguage = com.intellij.lang.LanguageParserDefinitions.INSTANCE
        .forLanguage(lang).fileNodeType.language
    isSqlLanguageDialectInstance(fileNodeLanguage)
}.getOrDefault(false)

/** 按类名沿父类链判断（`SqlLanguageDialect` 是抽象类，不是接口）。 */
private fun isSqlLanguageDialectInstance(language: Any?): Boolean {
    var c: Class<*>? = language?.javaClass
    while (c != null) {
        if (c.name == "com.intellij.sql.dialects.SqlLanguageDialect") return true
        c = c.superclass
    }
    return false
}

/** 结果已到达时把实际生成方言同步到预览目录；预热尚在途时由其稍后读取同一字段。 */
internal fun SqlPreviewPanel.syncPreviewDialectMapping(result: cn.song.mybatisplus.visualizer.model.PreviewResult) {
    previewDesiredDialectName = result.effectiveDialect?.name
    val vf = previewTempFile ?: prewarmedTempFile?.first ?: return
    val proj = project ?: return
    mapPreviewDialectBeforePsi(proj, vf)
}

/**
 * T-PerfTail #2：预热产物消费决策（替代原 `awaitPrewarmedTempFile` 的 EDT sleep-poll）。
 *
 *  - **热态（结果已出）**：同步取产物装配——主路径（成功）或快速降级（失败，原 prewarmFailed
 *    快速返回语义），EDT 零等待；
 *  - **冷态（预热线程未完成）**：`scheduleOnce` 注册完成回调，超时预算语义保留
 *    （PrewarmAwait 内 completeOnTimeout，预算=PREWARM_WAIT_MS），回调统一回 EDT 装配——
 *    EDT 零阻塞，超时/失败走降级链（T-PreviewExecDs 三级降级语义保留）；
 *  - **单元测试模式**：无真实 EDT 泵，为保留既有测试「showResult 返回时编辑器即就绪」语义，
 *    退回同步有界等待（awaitOrNullForTest，生产路径不走）。
 *
 * @return 产物（热态主路径非 null）；null = 本调用不装配（回调挂起中）或快速降级。
 *         消费装配由调用方按 `assembled` 标志区分。
 */

internal fun SqlPreviewPanel.resolvePreviewEditorSource(sql: String): Pair<Boolean, Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>?> {
    val await = prewarmAwait
    if (await == null) return true to null
    if (await.isDone()) return true to await.joinOrNull()
    if (ApplicationManager.getApplication()?.isUnitTestMode == true) {
        return true to await.awaitOrNullForTest()
    }
    // 冷态：注册回调（幂等），本次渲染不装配（编辑器挂起至回调完成，≤ 超时预算）
    await.scheduleOnce(
        onReady = { pair ->
            if (!previewDisposed) assemblePreviewEditor(sql, pair)
        },
        onFallback = {
            // T-DegradedPreviewUx P0：超时（产物在途）与硬失败在此分流——前者走宽限期静默，
            // 后者立即披露。原因由 await 的终态翻译，不再靠调用方传字符串。
            if (!previewDisposed) assemblePreviewEditor(sql, null)
        },
    )
    return false to null
}

/** T-DarkTheme：主题变更回调——重绘面板（命名色已自动更新，仅需触发 UI 刷新）。 */

internal fun SqlPreviewPanel.lookAndFeelChanged() {
    revalidate()
    repaint()
}


internal fun SqlPreviewPanel.createEditor(sql: String) {
    // T-RefreshDbSteal：createEditor 是症状①的特征终点（自建编辑器带行号，DBN 控制台无），
    // 但真机故障期它零日志（71s 空窗）——带栈埋点定调用人路径，兜底断言非用户路径
    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        .info("[M36] createEditor: len=${sql.length}, nativeDoc=${nativeEditorDoc != null}, embedded=${System.identityHashCode(embeddedConsole)}", Throwable("createEditor path"))
    val proj = project
    if (proj == null) {
        assemblePreviewEditor(sql, null)
        return
    }
    // T-PerfTail #2：预热产物消费决策——热态同步装配（主路径/快速降级，EDT 零等待）、
    // 冷态注册回调回 EDT 装配（超时预算语义保留，EDT 零阻塞）、单元测试模式同步有界等待
    val (resolved, prewarmed) = resolvePreviewEditorSource(sql)
    if (!resolved) {
        // T-PrewarmBudget：装配延后至预热回调（冷态/预算内未就绪）——这期间编辑区本来是**纯空面板**
        // （真机连拍实测：亮像素计数 0，冷启空窗 584~926ms 肉眼可见），故补一个轻量「准备中」占位；
        // 回调走到 assemblePreviewEditor 时它会 removeAll 掉，无需另行清理。
        showPreviewPreparingPlaceholder()
        return
    }
    assemblePreviewEditor(sql, prewarmed)
}

/**
 * T-PrewarmBudget：预热等待期的编辑区占位。
 *
 * 装配延后到预热回调时，编辑区原本是**纯空面板**——真机连拍实测亮像素计数 0，冷启动空窗
 * 584~926ms 肉眼可见。这里放一个居中的轻量提示，回调装配时由 [assemblePreviewEditor] 的
 * `editorHolder.removeAll()` 自然替换，无需另行清理。
 *
 * 用 `GridBagLayout` 居中而非直接 add：`editorHolder` 是 `GridLayout(1,1)`，直接加会把标签拉满
 * 整格（文字贴左上角），与「准备中」应有的视觉不符。
 */

internal fun SqlPreviewPanel.showPreviewPreparingPlaceholder() {
    val box = JBPanel<JBPanel<*>>(java.awt.GridBagLayout())
    box.add(
        JBLabel(MpSqlVisualizerBundle.message("panel.preview.preparing")),
        java.awt.GridBagConstraints().apply {
            anchor = java.awt.GridBagConstraints.CENTER
            weightx = 1.0
            weighty = 1.0
        },
    )
    editorHolder.removeAll()
    editorHolder.add(box)
    editorHolder.revalidate()
    editorHolder.repaint()
}

/** T-PrewarmBudget：等待期占位（断言用）——显示路径的观测口（容器观测口复用既有的 [editorHolderForTest]）。 */

internal fun SqlPreviewPanel.showPreparingPlaceholderForTest() = showPreviewPreparingPlaceholder()

/**
 * T-PerfTail #2：编辑器装配（原 createEditor 后半抽取，同步主路径与异步回调共用同一收口）。
 *
 * @param prewarmed 预热产物（项目内文件 + PSI）；null → 降级链（内存 PSI → 裸 document，
 *   T-PreviewExecDs 降级顺序与诚实降级横幅层级提示保留）。主路径对 `prewarmedTempFile`
 *   做**一次性消费**（同引用校验）——releaseEditor 后二次 createEditor 时产物已被消费，
 *   与原 await 一次性语义一致地走降级。
 */

internal fun SqlPreviewPanel.assemblePreviewEditor(
    sql: String,
    prewarmed: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>?,
    reason: PreviewDegradationReason? = null,
) {
    val proj = project
    val sqlFileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
        .getStdFileType("SQL")
    val doc = if (proj != null) {
        // T-PreviewFlash：createTempFile + refresh + findFile 全在后台线程，
        // EDT 不再触发 WorkspaceFileIndex.ensureIsUpToDate 的 SEVERE 断言（allowSlowOperations(GENERIC)
        // 压不住：SlowOperations.startSection 只在非 EDT 线程压栈，EDT 上该逃生舱是空壳，见开发计划 §3.1 反编译）。
        // T-PreviewDocLock2 F3：临时文件支链「尽力」——产物缺失（预热超时/失败/已消费）时
        // 降级到内存 PSI → 裸文档（功能降级优于硬失败——主路径行为逐字节不变）。
        // T-PreviewDocLock2 F3：`===` 是「本次交付的产物就是字段里那份未被消费的产物」——
        // 实例由 [publishPrewarmProduct] 单次构造保证（发布侧写两遍 `vf to psi` 会让本判定恒假）。
        if (prewarmed != null && prewarmedTempFile === prewarmed) {
            prewarmedTempFile = null
            runCatching {
                val (vFile, _) = prewarmed
                // T-DegradedPreviewUx P4：不复用预热线程捕获的 PSI 实例——方言映射切换会让平台
                // 异步重解析该文件并作废旧实例（真机 16:14:33 日志：prewarm done 后紧接 Reparsing
                // MySQL->PostgreSQL）。装配时按文件重取当前 PSI，是这条竞态的最后一道兜底。
                @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
                val currentPsi = ReadAction.compute<PsiFile?, Throwable> {
                    com.intellij.psi.PsiManager.getInstance(proj).findFile(vFile)
                } ?: throw IllegalStateException("preview PSI unavailable after dialect remap")
                // T-PreviewUx-Pack #2：主路径成功（项目内文件 + PSI 就绪）——清除降级横幅
                // T-PrewarmLateRecover F2：动作一并摘除（横幅已清，留着会被后续 RENDER_WARNING 继承）
                degradationBanner.onRetry = null
                // T-DegradedPreviewUx P1：主路径装配成功 = 降级已恢复——宽限定时器必须取消，
                // 否则到点还会把过期横幅弹出来（瞬时超时场景的横幅一次都不该出现）
                cancelDegradedGraceTimer()
                degradationBanner.clear(DegradationBanner.Kind.DEGRADED)
                // T-PrewarmLateRecover F1：主路径装配 → 当前文档不再属于降级态
                degradedPreviewDocument = null
                previewTempFile = vFile
                psiFile = currentPsi
                // T-PreviewDocLock：262 平台给「PSI 文件 → 文档」解析加了运行期读权限断言（243 只有
                // @RequiresReadLock 注解，官方 243 sources jar 核实），故 EDT 鼠标事件里裸调即报内部错误：
                // 单次调用固定 4 条 SEVERE「Plugin to blame: SQL Visualizer」——vf→Document 解析 2 条
                //（FileDocumentManager.getDocument 顶部的 EditorLockFreeTyping.assertReadAccess +
                // FileDocumentManagerBase.getDocument 首行的 softAssertReadAccess）与 Document/PSI
                // 文本长度校验 2 条（PsiFileImpl.getTextLength → view provider content → getDocument）。
                // 生产模式因 Logger.shouldRethrow 只认控制流异常而不抛（功能未断），但已是 SEVERE + FUS 上报，
                // 且 UsefulTestCase 模式下直接抛 AssertionError（平台抬版跑测试即红）。处置同 T-M42（同文件
                // createPreviewFileForTest 包 PsiFileFactory.createFileFromText 的先例）：EDT 上同步执行
                // ReadAction，语义不变。约束：lambda 内只能做纯内存模型读——EDT 进 ReadAction 会 check out
                // write-intent lock，塞同步 VFS refresh 会复现 T-PreviewWiLock 的 write-safe 硬抛。
                @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
                val d = ReadAction.compute<com.intellij.openapi.editor.Document, Throwable> {
                    com.intellij.psi.PsiDocumentManager.getInstance(proj).getDocument(currentPsi)
                } ?: throw IllegalStateException("临时 SQL 文件无关联文档")
                // T-M36.6-03：预热文件为占位空内容 → 首次装配按实际 SQL 填充并同步 PSI
                //（同 EDT tick 内完成，编辑器尚未显示，无闪烁）；同步路径文件内容=SQL，幂等跳过
                if (d.text != sql) {
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(proj) {
                        d.setText(sql)
                        com.intellij.psi.PsiDocumentManager.getInstance(proj).commitDocument(d)
                    }
                }
                d
            }.getOrElse {
                // T-DegradedPreviewUx P0：主路径产物在途失败（文档解析/写入异常）——真实原因是 PSI 侧，
                // 不沿用 await 的终态（此刻 await 可能已成功）
                // T-DegradedPreviewUx P4：此前该异常只传到二级降级的 msg、仅第三级才落日志，
                // 二级降级时（真机首开形态）原因完全不可见——这里补一行不含 SQL 的 WARN 便于取证。
                com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                    .warn("[M36] preview main-path assembly FAILED: ${it.javaClass.simpleName}: ${it.message}")
                fallbackPreviewDocument(
                    sql,
                    PreviewDegradationReason.PSI_UNAVAILABLE,
                    it.message ?: it.javaClass.simpleName,
                )
            }
        } else {
            fallbackPreviewDocument(
                sql,
                reason ?: prewarmAwait?.degradationReasonOrNull() ?: PreviewDegradationReason.PRODUCT_CONSUMED,
                "prewarmed product unavailable (timeout/failed/consumed)",
            )
        }
    } else {
        null
    }
    val created = if (proj != null && doc != null) {
        EditorFactory.getInstance().createEditor(doc, proj, sqlFileType, false)
    } else {
        EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(sql))
    }
    // M16.2：补全上下文双载体——Editor userData 为主载体（用户输入后 PSI 可能被 commit 重建而丢失 userData，
    // Editor 对象编辑期间稳定）、PSI 文件 userData 作兜底
    val ctx = buildCompletionContext()
    created.putUserData(SqlCompletionContext.KEY, ctx)
    psiFile?.putUserData(SqlCompletionContext.KEY, ctx)
    // T-InsertColumnInlay O-44b：新编辑器同样写语句类型载体（复用路径由 refreshCompletionContext
    // 覆盖，新建路径在此覆盖——两条路径都必须写，漏一条即该路径上 inlay 静默不出现）。
    // 显式传 created：此处 editor 字段仍指向上一个编辑器，默认取字段会写错对象
    attachPreviewStatementKind(created)
    // M34 回归修复：三级回退的 Document→Context 映射须在此登记（首次创建时 editor 尚不存在，
    // refreshCompletionContext 的 editor?.document 为 null，只能在此写入）——否则 commitDocument
    // 重建 PSI 后补全落入空映射，实体表/列补全静默失效（documentContexts 恒空、level 3 死路径）。
    created.document.let { SqlCompletionContributor.registerContext(it, ctx) }
    (created as EditorEx).apply {
        // UI015：EditorFactory 默认只有 Cut/Copy/Paste；专用组复用基础菜单与模板组。
        setContextMenuGroupId("cn.song.mybatisplus.visualizer.PreviewEditorPopupMenu")
        // F-CASE：自研 SQL 高亮器（SqlLexer 将 CASE/WHEN/THEN/ELSE/END 标记为 KEYWORD，
        // 字段名标记为 IDENTIFIER → 双击选择不会跨 token 粘连；不依赖 Database 插件）
        highlighter = LexerEditorHighlighter(
            SqlSyntaxHighlighter(), EditorColorsManager.getInstance().globalScheme
        )
        settings.isLineNumbersShown = true
        settings.isFoldingOutlineShown = true
        settings.isIndentGuidesShown = false
        settings.isAdditionalPageAtBottom = false
        // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
        EditorWhitespaceSettings.applyTo(this)
        setPlaceholder(previewEmptyHint) // T-UxOnboard T-UX-02：空态提示原生件（LogToSqlPanel T-UN-14 同款）
    }
    editorHolder.removeAll()
    // T-M27.1：编辑器组件链补 DataProvider——2025.3 平台编辑器内部改为 UiCompatibleDataProvider
    // （仅新 UI 兼容查询路径生效），传统 DataManager.getDataContext 沿父链只认 DataProvider 接口，
    // 面板编辑器获得焦点时 Ctrl+Space 的 CodeCompletionAction 拿不到 CommonDataKeys.EDITOR
    // （断言 editor != null 静默失败）→ 补全无反应（用户实测）。外层包装 DataProvider 提供
    // EDITOR：真实 IDE 的 DataManagerImpl 沿组件父链查找即命中，快捷键路径恢复
    editorHolder.add(
        object : JBPanel<JBPanel<*>>(BorderLayout()), DataProvider {
            override fun getData(dataId: String): Any? =
                if (CommonDataKeys.EDITOR.`is`(dataId)) created else null
        }.apply { add(created.component, BorderLayout.CENTER) },
        BorderLayout.CENTER,
    )
    editorHolder.revalidate()
    editorHolder.repaint()
    // T-SqlCopyShortcuts O-122：无选中 Ctrl+C 复制全文、有选中只复制片段
    //（层位与理由见 EditorCopyShortcuts 的 KDoc；本处此前无任何键盘复制绑定）
    EditorCopyShortcuts.install(created, UiTextClipboard::copy)
    // T-PreviewLineCommentDash O-134/D136：预览文件被 DialectPreMapping 强制映射 MySQL 方言，
    // 平台 Ctrl+/ 取 MysqlCommenter 的 `#` 前缀，PG 执行报 42601——打标接管行注释统一插 `--`
    //（`#` 残留行同样可剥；块注释不接管=D137。机制与卸载对称见 EditorCommentShortcuts KDoc）
    EditorCommentShortcuts.install(created)
    editor = created
    bindAutoPopup(created)
    // UI025：配置目录（.idea）下的预览编辑器不走 daemon，错列波浪线由编辑器级通道兜底。
    // 非 .idea 文件由 PreviewUnknownColumnAnnotator 负责，两条通道在各自 recompute 内互斥。
    cn.song.mybatisplus.visualizer.ui.annotate.PreviewSchemaHighlighter.install(created)
    // T-AutoFold F4-a：createEditor 支链（含内存文档降级，同一函数收口）——新编辑器按初始内容应用
    cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(created, sql)
}

/**
 * T-PreviewExecDs-01d / T-PerfTail #2：预览文档降级链（第二级 → 第三级）——
 * 第二级 = 内存 PSI（PsiFileFactory.createFileFromText，无 VFS 文件但保留补全上下文载体）；
 * 第三级（兜底）= 纯内存文档编辑器（无 PSI，补全/注释灭）。
 * T-PreviewUx-Pack #2：降级链层级横幅——触发时明示当前层级与恢复指引（不破坏降级顺序）。
 * T-PrewarmLateRecover F1：产出的文档登记为 [degradedPreviewDocument]（迟到产物换装的身份判据）；
 * F2：横幅挂上「重试」动作。
 */

internal fun SqlPreviewPanel.fallbackPreviewDocument(
    sql: String,
    reason: PreviewDegradationReason,
    msg: String,
): com.intellij.openapi.editor.Document {
    // 第二级：内存 PSI（保留补全上下文载体）
    createInMemoryPreviewPsi(sql)?.let { memoryPsiDoc ->
        showDegradedBanner(reason)
        degradedPreviewDocument = memoryPsiDoc
        return memoryPsiDoc
    }
    // 第三级：裸 document（内存 PSI 也失败）
    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        .warn("[M36] preview temp file unavailable, fall back to bare in-memory editor: $msg")
    psiFile = null
    showDegradedBanner(PreviewDegradationReason.MEMORY_PSI_FAILED)
    return EditorFactory.getInstance().createDocument(sql).also { degradedPreviewDocument = it }
}

/**
 * T-DegradedPreviewUx P1：降级横幅按原因分档披露。
 *
 *  - **超时**（产物仍在途）：先不显示，挂 [DEGRADED_GRACE_MS] 宽限定时器；产物在此期间到达，
 *    主路径装配会取消定时器并清横幅——瞬时降级全程不打扰。
 *  - **硬失败**：立即显示（原因短句 + 「重试」），不延迟。
 *
 * 动作仍由 [retryPrewarm] 承接（T-PrewarmLateRecover F2 语义不变）。
 */

internal fun SqlPreviewPanel.showDegradedBanner(reason: PreviewDegradationReason) {
    // 新一次降级判定：上一轮宽限定时器作废（避免旧 reason 到点弹出）
    cancelDegradedGraceTimer()
    degradationBanner.onRetry = { retryPrewarm() }
    if (reason == PreviewDegradationReason.TIMEOUT) {
        // 面板复用（clear → showResult）时上一轮的降级横幅可能还挂着：本轮是「待披露」态，
        // 先收掉旧横幅，宽限期内保持静默，到点再按超时型显示。
        degradationBanner.clear(DegradationBanner.Kind.DEGRADED)
        degradedGraceReason = reason
        val timer = javax.swing.Timer(DEGRADED_GRACE_MS.toInt()) { flushDegradedGrace() }.apply { isRepeats = false }
        degradedGraceTimer = timer
        timer.start()
        return
    }
    showDegradedBannerNow(reason)
}

/** 立即显示降级横幅（硬失败路径与宽限期到点的共同收口）。 */
internal fun SqlPreviewPanel.showDegradedBannerNow(reason: PreviewDegradationReason) {
    val cause = reason.causeKey()?.let { MpSqlVisualizerBundle.message(it) }
    val text = if (cause == null) {
        MpSqlVisualizerBundle.message(reason.bannerKey())
    } else {
        MpSqlVisualizerBundle.message(reason.bannerKey(), cause)
    }
    // 宽度不足时正文会被裁——完整文案 + 降级期操作提示放 tooltip（不新造「详情」控件）
    // UI-022：headless 下改参数/勾选条件重渲染正常（见 SqlPreviewPanelDegradedUxTest），
    // 真实 GUI 表现仍待沙盒定位，故把「未生效时点重试」这条出路放在悬停提示里，不写进单行正文。
    val tooltip = text + "\n" + MpSqlVisualizerBundle.message("panel.banner.degradedHint")
    degradationBanner.show(DegradationBanner.Kind.DEGRADED, text, tooltip = tooltip)
}

/** 宽限期到点：停表并显示超时型横幅（幂等；测试后门与 Timer 动作共用）。 */
internal fun SqlPreviewPanel.flushDegradedGrace() {
    val pending = degradedGraceReason ?: return
    cancelDegradedGraceTimer()
    if (previewDisposed) return
    // 宽限期内预热可能已经硬失败（VFS 重试走完仍 null 等）：重取一次终态，
    // 避免把「永久失败」继续描述成「稍后会自动切换」。
    showDegradedBannerNow(prewarmAwait?.degradationReasonOrNull() ?: pending)
}

/** T-DegradedPreviewUx P1：停止并丢弃宽限定时器（幂等，四个收口点共用）。 */
internal fun SqlPreviewPanel.cancelDegradedGraceTimer() {
    degradedGraceTimer?.stop()
    degradedGraceTimer = null
    degradedGraceReason = null
}

/**
 * T-PrewarmLateRecover F1：迟到产物换装——把已经装配好的降级编辑器换成项目内文件背书的编辑器。
 *
 * 这是「首开预热超过预算 → 降级挂横幅 → 产物随后到达」的恢复出口（实测形态：预算 500ms、
 * 冷启首开预热 3016ms）。**调用方负责线程归位**（[prewarmDispatcher] 回 EDT，写安全模态）。
 *
 * 换装判据（全部满足才动，任一条不满足就静默保持现状——产物留在 [prewarmedTempFile] 里，
 * 留给后续 [createEditor] 消费）：
 *  - 面板与项目未销毁；
 *  - 已有编辑器，且当前文档就是降级装配的那一份（[degradedPreviewDocument] 身份相等）；
 *  - 文档文本与插件最后一次写入一致（[lastRenderedSql]）——用户动过就不换，避免抢走编辑现场。
 *    该条**只在自动换装时**生效：用户显式点「重试」时按 [userRequested] 跳过，改把当前文档文本
 *    作为新装配的 SQL（内容不丢）——否则用户编辑过之后重试就成了按不出反应的假动作。
 *
 * 换装走主路径装配（[assemblePreviewEditor]），因此横幅由它统一清除；光标与焦点尽量复位
 * （文本逐字相同，逻辑位置可直接搬）。
 */

internal fun SqlPreviewPanel.tryUpgradeToLateProduct(
    product: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>,
    userRequested: Boolean = false,
) {
    if (previewDisposed || project?.isDisposed == true) return
    val current = editor ?: return
    val degradedDoc = degradedPreviewDocument ?: return
    if (current.document !== degradedDoc) return
    val text = current.document.text
    if (!userRequested && text != lastRenderedSql) {
        // 用户已经在编辑（或另有渲染尚未落定）：不抢编辑现场，产物留给下一次装配
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] late prewarm product kept: document edited since last render (len=${text.length})")
        return
    }
    val caret = runCatching { current.caretModel.logicalPosition }.getOrNull()
    val hadFocus = runCatching { current.contentComponent?.isFocusOwner == true }.getOrDefault(false)
    disposeCurrentEditorOnly()
    // 与 [assemblePreviewEditor] 的「引用相等 + 随即置 null」消费判定对齐：本次要消费的就是这一份
    prewarmedTempFile = product
    assemblePreviewEditor(text, product)
    if (caret != null) runCatching { editor?.caretModel?.moveToLogicalPosition(caret) }
    if (hadFocus) runCatching { editor?.contentComponent?.requestFocusInWindow() }
    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        .info("[M36] late prewarm product consumed: editor upgraded to ${product.first.name}")
}

/**
 * T-PrewarmLateRecover F2：横幅「重试」——重新臂一轮预热，产物到达后原地换装并清横幅。
 *
 * 两条出口都接上：预算内就绪走 [PrewarmAwait.scheduleOnce] 的 onReady；超预算到达走
 * [PrewarmAwait.onLateProduct]（预热线程发布时发现消费侧已放弃）——所以重试**不会**因为
 * 「预热又慢了一次」而白点。失败（硬失败）时什么都不做：横幅与动作保留，用户可再点。
 *
 * 重臂本身受 [prewarmRound] 保护：新轮自增令牌，旧轮迟到产物不再入字段（P9 记录的
 * 「两次预热竞争 previewTempFile」即由该令牌挡住）。
 */

internal fun SqlPreviewPanel.retryPrewarm() {
    if (previewDisposed || project == null) return
    // 上一轮若已有产物躺着（迟到但没人消费），直接换装，不必再预热一轮
    prewarmedTempFile?.let { pending ->
        tryUpgradeToLateProduct(pending, userRequested = true)
        if (degradedPreviewDocument == null) return
    }
    // 降级态下「当前在用文件」这个指针没有意义（降级文档不在项目文件上），而 [prewarmPreviewTempFile]
    // 的入口守卫见它非空即判「已备好」直接 fail——主路径设了指针之后才失败的那条窄路（如
    // `PsiDocumentManager.getDocument` 返回 null）会让重试变成空动作。这里只丢指针、不删文件：
    // 文件仍在 [previewTempFilesCreated] 登记表里，[releaseEditor] 统一回收。
    previewTempFile = null
    prewarmFailed = false
    prewarmPreviewTempFile()
    prewarmAwait?.scheduleOnce(
        onReady = { product ->
            if (!previewDisposed) tryUpgradeToLateProduct(product, userRequested = true)
        },
        // 超时/失败：保持现状（横幅与动作仍在）——迟到通道会在产物真正到达时收尾
        onFallback = {},
    )
}

/**
 * T-PreviewExecDs-01d：降级链第二级——内存 PSI 预览文档（无项目内 VFS 文件，但保留
 * [SqlCompletionContext] 补全载体，避免掉到裸 document 后补全/注释全灭）。createFileFromText
 * 包 ReadAction（T-M42 同机制先例：EDT 直调触发 markGenerated softAssertReadAccess 硬抛）。
 * 失败返回 null，由调用方回落到第三级裸文档。
 */
@Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用

internal fun SqlPreviewPanel.createInMemoryPreviewPsi(sql: String): com.intellij.openapi.editor.Document? {
    val proj = project ?: return null
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
    return runCatching {
        val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("SQL")
        val psi = com.intellij.openapi.application.ReadAction.compute<PsiFile, Throwable> {
            com.intellij.psi.PsiFileFactory.getInstance(proj)
                .createFileFromText(PreviewInlayScope.MEMORY_PREVIEW_FILE_NAME, fileType, sql)
        }
        psiFile = psi
        LOG.info("[M36] in-memory PSI preview document created (len=${sql.length})")
        com.intellij.psi.PsiDocumentManager.getInstance(proj).getDocument(psi)
            ?: EditorFactory.getInstance().createDocument(sql)
    }.onFailure { e ->
        LOG.warn("[M36] in-memory PSI preview fallback FAILED: ${e.message}")
        psiFile = null
    }.getOrNull()
}

/**
 * 创建预览 PSI 文件（测试缝）：抽取 [createEditor] 中的 PsiFileFactory 创建逻辑为独立方法，
 * 供测试直接调用（绕过 EDT 回调 + EditorFactory 创建）。
 *
 * T-M42（用户实测堆栈）：PsiFileFactory.createFileFromText 在 EDT 上直接调用会触发
 * 2026.2 平台 markGenerated 树遍历 softAssertReadAccess 硬抛 RuntimeExceptionWithAttachments——
 * 包 ReadAction（EDT 上同步执行，语义不变）。
 *
 * @param proj 当前项目（非 null）
 * @param sql  预览 SQL 文本
 * @return 创建的 PsiFile
 */
@Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用

internal fun SqlPreviewPanel.createPreviewFileForTest(proj: Project, sql: String): PsiFile {
    return ReadAction.compute<PsiFile, Throwable> {
        val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
            .getStdFileType("SQL")
        PsiFileFactory.getInstance(proj)
            .createFileFromText("preview.sql", fileType, sql)
    }
}

/**
 * T-M27.2：自建输入自动补全（用户实测：面板内输入字符无自动提示，与 Java 代码编辑器行为不一致）。
 *
 * 平台 autopopup 链路（TypedHandler → CompletionAutoPopupHandler → AutoPopupController →
 * CodeCompletionHandlerBase(autopopup=true)）对**非项目文件编辑器**不生效——`CompletionProgressIndicator`
 * 的 autopopup 流程显示**空弹窗**（contributor 未被调度；探针实证：手动模式 contributor 正常被调用、
 * forParameters 含本插件 contributor，仅 autopopup 模式未调度，与语言无关——Java 文件同为空）。
 * 修复：面板监听文档输入（标识符字符），直接调**手动模式**补全处理器，绕开平台
 * autopopup 调度。关键参数：构造第 4 参 explicit=true（探针实证：false 时 contributor
 * 不被调度、弹空窗）+ 补全前 commitDocument（未 commit 时前缀按旧 PSI 文本计算误过滤）。
 * 防回环：有效 lookup 激活/删除/非标识符/调度中均不重复触发（平台空弹窗不拦截，
 * 由自建补全接管）。
 */

internal fun SqlPreviewPanel.bindAutoPopup(created: Editor) {
    val listener = object : com.intellij.openapi.editor.event.DocumentListener {
        override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
            if (suppressAutoPopup) return
            if (event.newLength == 0 || event.oldLength > event.newLength) return
            val ed = editor ?: return
            if (ed.isDisposed || project == null) { return }
            // 平台 autopopup 会先弹**空弹窗**（contributor 未调度）——空弹窗不拦截，
            // 由自建补全接管；仅"已有有效项"的补全（lookup 选中插入回环）拦截
            val active = LookupManager.getActiveLookup(ed)
            if (active != null && active.items.isNotEmpty()) { return }
            if (autoPopupScheduled) { return }
            // 插入的最后一个字符为标识符（字母/数字/_）才弹（中文输入等场景不触发）。
            // 用**事件插入位置**而非 caret——DocumentListener 在 caret 移动前触发（真实输入同时序）
            val insertEnd = event.offset + event.newLength
            if (insertEnd <= 0) return
            val prev = ed.document.getText(TextRange.create(insertEnd - 1, insertEnd)).firstOrNull() ?: return
            if (!prev.isLetterOrDigit() && prev != '_') { return }
            autoPopupScheduled = true
            // write-safe: 同 SqlInputEditor.bindAutoPopup——本拍只由**用户输入活动**派生
            // （程序性写入经 suppressAutoPopup 抑制，不调度补全），无进度模态时平台取模态链
            // （defaultModalityImpl）落 NON_MODAL（恒 write-safe，见 DocWriteContext KDoc）；
            // commitDocument 只做 PSI 同步、不改文档文本。改投递为 nonModal 会把补全弹窗延后到
            // 模态关闭后，输入期补全退化。
            ApplicationManager.getApplication().invokeLater {
                autoPopupScheduled = false
                val ed2 = editor ?: return@invokeLater
                val proj = project ?: return@invokeLater
                if (ed2.isDisposed) return@invokeLater
                val active2 = LookupManager.getActiveLookup(ed2)
                if (active2 != null && active2.items.isNotEmpty()) return@invokeLater
                try {
                    // 先提交文档（PSI 同步）——补全前缀按编辑器文档计算，未 commit 时用旧 PSI
                    // 文本算前缀导致误过滤（如 "FROM Stbl" 按旧文本 "FROM tbl" 前缀算成 "t"）
                    com.intellij.psi.PsiDocumentManager.getInstance(proj).commitDocument(ed2.document)
                    CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true)
                        .invokeCompletion(proj, ed2, 1, false)
                } catch (t: Throwable) {
                    // 补全异常不影响输入（弹窗失败下次输入重试）
                }
            }
        }
    }
    // T-M52：双参数版（Disposable 管理监听生命周期，releaseEditor 时自动移除）。
    // T-RA-02：移除职责只归 autoPopupDisposable——不要再用字段存 listener 做「记账」，
    // 那会让「谁负责摘监听」出现两个说法（T-M52 前的手动 removeDocumentListener 已删，
    // 二次移除会报 "Can't remove document listener"）。重新注册前先 dispose 旧 disposable，
    // 防同一文档挂上两个 listener。
    if (autoPopupDisposable != null) Disposer.dispose(autoPopupDisposable!!)
    autoPopupDisposable = Disposer.newDisposable("SqlPreviewPanel.autoPopup")
    created.document.addDocumentListener(listener, autoPopupDisposable!!)
}

/**
 * T-PrewarmLateRecover F1：只释放当前编辑器（含 autopopup 监听），不动临时文件与预热产物。
 *
 * 从 [releaseEditor] 抽出——迟到产物换装要在**保留**临时文件与产物的前提下换掉编辑器，
 * 两条路径共用同一段释放逻辑，避免「谁负责 releaseEditor」出现两个说法（Editor 泄漏会被
 * 平台报 "Memory leak detected"）。
 */

internal fun SqlPreviewPanel.disposeCurrentEditorOnly() {
    // T-DegradedPreviewUx P1：编辑器被释放 = 当前降级装配作废，宽限定时器一并停（幂等）
    cancelDegradedGraceTimer()
    editor?.let {
        // UI025：先摘编辑器级错列标注通道（监听 + RangeHighlighter），再释放编辑器
        cn.song.mybatisplus.visualizer.ui.annotate.PreviewSchemaHighlighter.uninstall(it)
        // T-M52：监听器由 Disposable 管理，dispose 即移除（无需手动 removeDocumentListener，
        // 也不再有 listener 字段需要置 null——T-RA-02 删除）
        autoPopupDisposable?.let { d -> Disposer.dispose(d) }
        autoPopupDisposable = null
        // T-PreviewLineCommentDash O-134：行注释接管的减计数端点（与 assemblePreviewEditor 的
        // install 对称；引用归零即换回平台原动作。幂等，未打标零动作）
        EditorCommentShortcuts.uninstall(it)
        EditorFactory.getInstance().releaseEditor(it)
        editor = null
    }
    // T-PrewarmLateRecover F1：编辑器没了，降级文档身份随之失效
    degradedPreviewDocument = null
}

/** 释放当前编辑器（幂等）。 */

internal fun SqlPreviewPanel.releaseEditor() {
    // T-RefreshDbSteal：createEditor 兜底断言的对称端点——谁释放了自建编辑器决定了
    // 下一次 showSql 走哪个分支，旧实现零日志
    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        .info("[M36] releaseEditor: editor=${System.identityHashCode(editor)}, nativeDoc=${nativeEditorDoc != null}", Throwable("releaseEditor path"))
    disposeCurrentEditorOnly()
    psiFile = null
    // M34 回归修复：临时 SQL 文件删除（java.io 删除免 write action；deleteOnExit 兜底）
    previewTempFile?.let { vFile -> java.io.File(vFile.path).delete() }
    previewTempFile = null
    // T-FixN2：唯一文件名后本面板**创建过的全部**预览临时文件统一回收——除上面那个「当前在用」
    // 的以外，还含「预热已发布但从未被装配消费」（面板关闭/clear 早于消费）与「两次预热竞态中
    // 晚到的那一个」（clear 时旧预热仍在途、previewTempFile 被后来者覆盖）两类无主文件，
    // 否则 preview/ 目录会随预览次数只增不减。清理策略三选一取「按面板生命周期删除」：
    // 保留上限/文件池两条都要跨面板判断「谁在用」（同项目多开进程另有自己的文件），
    // 误删在用的预览文件会立刻复现 File Cache Conflict；本表只装本面板自己建的文件，天然无误删面。
    // T-PerfSweep6 T-PS6-04③：抽成 [recoverPreviewTempFiles]，与 [dispose] 共用同一份回收逻辑
    recoverPreviewTempFiles()
    // T-M36.6-03：预热产物作废（面板重用时由 clear() 重新预热；dispose 由 previewDisposed 兜底）
    prewarmedTempFile = null
}

/**
 * T-PerfSweep6 T-PS6-04③：回收本面板创建过的全部预览临时文件（幂等）。
 *
 * 抽成独立方法是为了让 [releaseEditor] 与 [dispose] 共用同一份回收逻辑——此前只有
 * `removeNotify` → [releaseEditor] 一条链会走到，而三种宿主（弹窗 / 工具窗 / 嵌入控制台）下
 * `dispose` 与 `removeNotify` 的先后未穷尽，dispose 先到即漏删。
 */

internal fun SqlPreviewPanel.recoverPreviewTempFiles() {
    previewTempFilesCreated.forEach { f -> runCatching { f.delete() } }
    previewTempFilesCreated.clear()
}

/**
 * T-PerfSweep6 T-PS6-04（性能报告发现 4）：预览目录级兜底清扫（**每个面板启动时一次**）。
 *
 * **为什么需要**：唯一命名（T-FixN2）把「覆盖写同一路径」变成「每次新建一个文件」，异常路径
 * （kill / 崩溃 / 未走 `removeNotify` / 未走 `dispose`）留下的文件不再被任何后续预览复用，
 * `preview/` 只增不减。三条兜底按性价比取二（计划 §三 T-PS6-04）：
 *  ① **年龄**：mtime 早于 [STALE_PREVIEW_MAX_AGE_MS]（7 天）的残留直接删；
 *  ② **条数**：本插件命名的文件超过 [PREVIEW_DIR_MAX_FILES]（200）时按 mtime 从最旧删到上限。
 *  （第三条「dispose 补同款回收」落在 [recoverPreviewTempFiles]，见其调用点。）
 *
 * **宁漏删不误删**：只删**本插件自有命名**的文件——判据直接复用建名处的
 * [PreviewInlayScope.isPrimaryPreviewFileName]（与生成端同一份形态常量，杜绝判据漂移）；
 * 用户自己放进该目录的任何文件、二级内存名（`mpsql-preview-memory.sql`）、原生控制台临时文件
 * （`mpsql-exec*.sql`）都不匹配即不触碰（照 `SqlLogAutoSaver.pruneRetainedFiles` 的
 * familyRegex「先按名过滤再删」先例）。目录不存在/不可读时静默返回——清扫是兜底，
 * 不得影响预览主链。
 */

internal fun SqlPreviewPanel.sweepStalePreviewFiles(dir: java.io.File) {
    val files = runCatching {
        dir.listFiles { f -> f.isFile && PreviewInlayScope.isPrimaryPreviewFileName(f.name) }
    }.getOrNull() ?: return
    if (files.isEmpty()) return
    val now = System.currentTimeMillis()
    files.filter { now - it.lastModified() > STALE_PREVIEW_MAX_AGE_MS }
        .forEach { runCatching { it.delete() } }
    val rest = files.filter { it.exists() }
    if (rest.size > PREVIEW_DIR_MAX_FILES) {
        rest.sortedBy { it.lastModified() }
            .take(rest.size - PREVIEW_DIR_MAX_FILES)
            .forEach { runCatching { it.delete() } }
    }
}

// ------------------------------------------------------------------
// 参数输入与回填（M12：动态条件复选框行 + 按 source 绑定 + 空值语义）
// ------------------------------------------------------------------

/** 重建左侧面板：动态条件复选框行 + 普通参数值输入行（T-M23-04：批量列级勾选行）。 */
