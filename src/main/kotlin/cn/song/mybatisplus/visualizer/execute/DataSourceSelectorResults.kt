package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector.FillTargetLayout
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
import cn.song.mybatisplus.visualizer.util.FatalErrors
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import javax.swing.Icon

// T-M53-II（2026-10-10）：以下为 DataSourceSelector 的「结果 Tab 重试、钩子挂载与临时文件」内聚成员，
// 以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal。

internal fun DataSourceSelector.startResultTabRetry(
    project: Project,
    ui: Any,
    onError: ((String) -> Unit)? = null,
    preExistingNames: Set<String> = emptySet(),
    generation: Int = swapGeneration,
    dsName: String? = null,
    demotedOverride: Boolean = false,
    sqlText: String? = null,
    onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
) {
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
    val selectRetry = object : java.awt.event.ActionListener {
        var attempts = 0
        val timer = javax.swing.Timer(400, null)
        override fun actionPerformed(e: java.awt.event.ActionEvent) {
            attempts++
            // M36.5.44：代际不匹配 → 新执行已覆盖本 watch，立即停止（否则空转到阈值，
            // 期间用户看到上一次结果 + "执行中..."——滞后一轮的另一来源）。
            if (generation != swapGeneration) {
                timer.stop()
                LOG.debug("[M36] result tab retry cancelled (generation $generation != ${swapGeneration})")
                return
            }
            // M36.5.56：每拍开头统一早检错误（**不再只放 else 分支**）——DBN 查询失败写
            // console history 后应立即上报，无论结果 Tab 是否出现。原实现把早检放 else
            //（result==null）分支，第一次执行结果 Tab 出现（复用）时走 if 分支不检查，
            // 错误要等第二次执行（result=null 走 else）才报——用户"执行两次才出现错误"。
            // extractConsoleError 用 ERROR_SIGNATURES+isSqlLine 精确匹配，不误判正常 SQL。
            extractConsoleError(sharedConsole ?: preparedConsole, LOG, sqlText, project)
                ?.takeIf { it != lastReportedError }?.let { err ->
                    timer.stop()
                    runPostExecutionInvalidate(sqlText, LOG)
                    lastReportedError = err
                    LOG.info("[M36] early console error detected (attempts=$attempts): ${err.take(120)}")
                    // write-safe: UI-only（纯 Swing/只读，无文档写）
                    // T-ELU-01/03：DBN 写进 console history 的**语句级**错误也走富通道——
                    // 这是「正常 SQL 执行失败」的主路径（提交链不抛异常，错误只落在 history），
                    // 原实现只投旧 onError ⇒ 定位按钮永不出现。cause 传 null（文本里已有
                    // 位置 marker，SQLState/errorCode 由驱动器在 history 文本中给出）。
                    ApplicationManager.getApplication().invokeLater({
                        deliverError(onErrorRich, onError, null, err, sqlText?.length)
                    }, com.intellij.openapi.application.ModalityState.any())
                    return
                }
            val contents = runCatching {
                ui.javaClass.getMethod("getContents").invoke(ui) as? Array<com.intellij.ui.content.Content>
            }.getOrNull() ?: emptyArray()
            val result = pickResultTab(contents, preExistingNames, attempts)
            if (result != null && result.displayName in preExistingNames) {
                LOG.info("[M36] result tab reused (name=${result.displayName}) - accepting existing tab")
            }
            // T-PerfSweep2-01：列表构建仅喂 LOG.debug——debug 关闭时不再逐拍白建两份列表
            //（400ms 定时器最长 100 拍，此前每拍 2 次 map 空转）。
            if (LOG.isDebugEnabled) {
                val all = contents.map { it.displayName }
                val ids = contents.map { System.identityHashCode(it) }
                LOG.debug("[M36] retry#$attempts contents=$all, ids=$ids, select=${result?.displayName}, preExisting=$preExistingNames")
            }
            if (result != null) {
                timer.stop()
                runPostExecutionInvalidate(sqlText, LOG)
                // M36.5.48 勘误：此处**不**调用 extractConsoleError——结果 Tab 复用成功时
                // console history 含正常 SQL 文本（如 "WHERE t.id = 1"），会被误判为错误并
                // onError（日志实证 `extracted console error: WHERE t.id = 1`，误报 + 一闪而过）。
                // 真实失败原因只在"超时无结果"路径提取（那里才是真失败/超时）。
                runCatching {
                    val select = ui.javaClass.getMethod(
                        "selectAndFocus",
                        com.intellij.ui.content.Content::class.java,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    )
                    select.invoke(ui, result, false, true)
                }.onFailure { ex ->
                    LOG.warn("[M36] select result tab FAILED: ${ex.message}")
                }
                // M36.5.8：执行后结果区组件被 RunnerLayoutUi 重建，旧引用显示停留在上一轮
                //（用户实测：新 SQL 执行后一直加载中、重开面板才见新结果）——重取组件换入。
                // M36.5.13：forceSwapIn=true——结果 Tab 出现即有新结果，强制换入一次
                //（组件实例可能恒定，"实例变化才换入"导致新结果不显示）
                refreshResultsComponent(ui, LOG, forceSwapIn = true, generation = generation, onError = onError, onErrorRich = onErrorRich, sqlText = sqlText)
            } else {
                // M36.5.52：每拍错误早检——DBN 查询失败（Unknown column/syntax error 等）
                // 会立即写入 console history，无需等 40 拍（16s）超时；发现真实错误立即上报
                // M36.5.3：索引中放宽窗口（查询提交可能被 dumb mode 推迟）；连接等待期由
                // 提交前的 8s 监控覆盖（M36.5.15），此处窗口保持原语义。
                val dumb = runCatching { com.intellij.openapi.project.DumbService.isDumb(project) }.getOrDefault(false)
                // M36.5.22：窗口统一放宽到 40 拍（16s）——监听已在真实提交后启动，且需覆盖
                // 15 拍（6s）的 "DBN 复用旧 Tab" 回退判定；连接等待另有 8s 监控负责提示。
                val limit = if (dumb) 100 else 40
                if (attempts >= limit) {
                    timer.stop()
                    runPostExecutionInvalidate(sqlText, LOG)
                    LOG.info("[M36] result tab not appeared after $attempts attempts (dumb=$dumb)")
                    // M36.5.14：提示改走 onError（**面板内显示**，resultPanel.showError）——
                    // 此前 Messages.showWarningDialog 弹窗也会被模态预览窗口遮挡（用户实测
                    // "关闭窗口才出现提示"）；面板内红字必然可见，且不叠加新模态。
                    // ModalityState.any() 保证模态弹窗（如密码框）期间提示照常落地。
                    // M36.5.48：优先取 console 真实错误文本（DBN 查询失败写入 history）；
                    // 读不到才 fallback 笼统 hint（超时/索引）。
                    val realError = extractConsoleError(sharedConsole ?: preparedConsole, LOG, sqlText, project)
                    // T-CEC-03：判据认不出的失败也要有下文——控制台确有新增输出（去掉本次 SQL
                    // 回显后的残余）时如实呈现，而不是把语法错归因成密码/驱动（真机 P15 实证）。
                    val residue = if (realError == null) {
                        extractConsoleResidue(sharedConsole ?: preparedConsole, sqlText, LOG)
                    } else {
                        null
                    }
                    // T-ExecSilentNoop：40 拍超时窗口与 8s 监控共用 executionHintReported 去重。
                    // 真实错误优先（DBN 已把失败写进 history 才叫真失败/真超时）；无错误痕迹时
                    // 不再一律归因凭据——先看本次是否探到"未提交"（freshModel 回落，DBN 侧静默
                    // 拒绝），按情况给 notSubmitted / timeout / indexing 三种真实提示。
                    if (realError == null && !executionHintReported) {
                        executionHintReported = true
                        val message = if (residue != null) {
                            // 如实标注：这是控制台新增输出，**不是**已识别的错误形态（不猜原因）
                            "${MpSqlVisualizerBundle.message("execute.error.unknownShape")}\n$residue"
                        } else {
                            when {
                                dumb -> MpSqlVisualizerBundle.message("execute.indexing.hint")
                                demotedOverride -> MpSqlVisualizerBundle.message("execute.notSubmitted.hint", dsName ?: "")
                                else -> MpSqlVisualizerBundle.message("execute.timeout.hint")
                            }
                        }
                        LOG.info(
                            "[M36] result tab not appeared, no console error (dumb=$dumb, " +
                                "demoted=$demotedOverride, residue=${residue != null}) - hint via 40-tick window",
                        )
                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                        ApplicationManager.getApplication().invokeLater({
                            onError?.invoke(message)
                        }, com.intellij.openapi.application.ModalityState.any())
                    } else if (realError != null) {
                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                        // T-ELU-01/03：语句级错误同早检路径，走富通道收口
                        ApplicationManager.getApplication().invokeLater({
                            deliverError(onErrorRich, onError, null, realError, sqlText?.length)
                        }, com.intellij.openapi.application.ModalityState.any())
                    }
                }
            }
        }
        init { timer.addActionListener(this) }
    }
    selectRetry.timer.start()
}

/** M36.5：读取 last-used 数据源名（prepareConsole 静默复用；异常兜底 null）。 */

/**
 * T-DialectGateExit：是否允许复用已准备好的共享控制台。
 *
 * `forceChoose`（用户显式要求换数据源）必须跳过复用，否则「切换数据源」会静默回到原目标。
 */
internal fun allowsSharedConsoleReuse(forceChoose: Boolean): Boolean = !forceChoose

/**
 * T-DialectGateExit：是否允许 last-used 数据源静默命中。
 *
 * 显式执行路径本来就不静默（保持弹窗可选）；预览准备链在 `forceChoose` 时同样不静默。
 */
internal fun allowsSilentLastUsed(executeAfterPrepare: Boolean, forceChoose: Boolean): Boolean =
    !executeAfterPrepare && !forceChoose

internal fun DataSourceSelector.chooseAndPrepare(
    project: Project,
    sql: String,
    executeAfterPrepare: Boolean,
    onConsole: ((javax.swing.JComponent) -> Unit)?,
    onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)?,
    onError: ((String) -> Unit)?,
    callback: ((Boolean) -> Unit)?,
    onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
    wholeScriptExec: Boolean = false,
    dialectGateRequest: ExecutionDialectGate.Request? = null,
    onDialectGate: ((ExecutionDialectGate.Verdict) -> Unit)? = null,
    onSubmission: ((ExecutionSubmission) -> Unit)? = null,
    /** T-DialectGateExit：强制弹数据源选择器（跳过共享控制台复用与 last-used 静默命中）。 */
    forceChoose: Boolean = false,
    /**
     * T-ExplainFailUx W4（2026-10-24 用户实测「为什么我需要选两次数据源」）：**用户已显式指定
     * 数据源名**——按名在 runner 枚举里唯一命中就直接建链，**不再弹平台选择器**（此前预览的
     * 数据源下拉调 `prepareConsole(forceChoose=true)`，而 forceChoose 的语义恰是「强制弹窗」，
     * 于是用户在自己的下拉里选一次、平台选择器又让他选第二次）。
     *
     * 语义边界：只跳过「选择」这一步，不跳过方言闸（闸门在 sessionConsumer 内照旧执行）；
     * 名字未命中/同名歧义 → 回落既有弹窗（不猜）。
     */
    preferredRunnerName: String? = null,
    /** T-ExplainSwitchNative W6：必须弹选择器（跳过 `shouldBeChosenIfAlone` 直选分支）。 */
    forceChoosePopup: Boolean = false,
) {
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
    if (!ExecuteSqlSupport.isAvailable()) {
        LOG.warn("[M36] database plugin NOT available, abort")
        onSubmission?.invoke(ExecutionSubmission.BLOCKED)
        onError?.invoke(MpSqlVisualizerBundle.message("execute.noDatabase"))
        callback?.invoke(false)
        return
    }

    // T-FirstExecResultInvisible F1：准备链单飞 + 执行意图排队——打开预览的准备链（远程 PG
    // 会话需数秒）未落地时点执行，旧代码在下方无任何去重，会再建第二条临时文件/会话/控制台；
    // 两条链的 executionGeneration++ 与提交/取消判定交错 → 提交被取消或结果组件被换掉
    //（结果区空白、二击正常的 H1 根因）。单飞判定先于共享复用：冷启时 sharedConsole 尚未
    // 落地，复用必不命中，单飞是唯一闸门。
    // 预览也持有身份，超时后旧 session 回调不能消费下一链的队列。
    val queuedIntent = QueuedExecuteIntent(
        onConsole, onEditor, onError, onErrorRich, wholeScriptExec,
        dialectGateRequest, onDialectGate, onSubmission,
    )
    val onSubmission = queuedIntent.onSubmission
    var ownsGate = false
    when (prepareGate.tryEnter(executeAfterPrepare, queuedIntent)) {
        PrepareGateDecision.QUEUE_ONLY -> {
            LOG.info("[M36][T-FirstExec F1] prepare chain in flight: execute intent queued, no second session/console chain")
            // T-DialectInferPollution W11（2026-10-24 用户真机「点执行 SQL 卡住」）：排队意图
            // 原本只能等 owner 的 60s 逃生被下一链接管才结算——排队者自身无触发点，UI 永远
            // 「执行中…」。给排队意图自己的有界结算：15s 未消费即 BLOCKED 解除加载态（门不动，
            // owner 链照常；用户重试即可）。
            val queuedForTimeout = queuedIntent
            javax.swing.Timer(PENDING_EXECUTE_TIMEOUT_MS.toInt()) { event ->
                if (prepareGate.expirePendingExecute(queuedForTimeout, PENDING_EXECUTE_TIMEOUT_MESSAGE)) {
                    (event.source as javax.swing.Timer).stop()
                }
            }.apply { isRepeats = false }.start()
            callback?.invoke(true)
            return
        }
        PrepareGateDecision.PROCEED -> {
            LOG.info("[M36][T-FirstExec F1] prepare gate PROCEED: owning prepare chain (executeAfterPrepare=$executeAfterPrepare)")
            ownsGate = true
        }
        PrepareGateDecision.PASS_THROUGH ->
            LOG.info("[M36][T-FirstExec F1] prepare chain in flight: preview prepare passes through (no gate ownership)")
    }

    val prepareOwner = queuedIntent.takeIf { ownsGate }
    // 只有 owner 看守后台；排队 B 不另开计时器截断 A 的用户交互。
    if (prepareOwner != null) javax.swing.Timer(1000) { event ->
        if (prepareGate.expire(prepareOwner)) (event.source as javax.swing.Timer).stop()
    }.start()

    // T-MysqlGStrip T-MG-02：提交链总口——SQL 文本进入 exec 文件/控制台文档之前剥离 MySQL CLI
    // 的 `\G` 后缀（DBX #35）。此点数据源尚未选定（runner 弹窗在下方），方言不可得 → 按计划
    // §三 定案默认剥离（`\G` 非任何方言合法语法，剥离无害）。剥后文本写入 exec 文件，
    // 控制台编辑器所见即所执行；历史链在预览侧记录原文，不受此处影响（两态分离）。
    val submitSql = stripGSuffixForSubmit(sql, null).submitText

    // M36.5.5：共享控制台命中（last-used 数据源一致且控制台仍有效）→ 直接复用——
    // 不同步 session/连接/临时文件/方言映射，规避"第二个会话连接永不发起"挂死（见字段注释）。
    // 显式执行路径同样复用（结果落在已嵌入面板）；用户切换数据源走弹窗 → 新建并替换共享缓存。
    if (allowsSharedConsoleReuse(forceChoose) && reuseSharedConsole(
            project, submitSql, executeAfterPrepare, onConsole, onEditor, onError, callback, onErrorRich,
            wholeScriptExec, dialectGateRequest, onDialectGate, onSubmission, prepareOwner,
        )) {
        // T-FirstExecResultInvisible F1：复用路径同步收敛（不建链、不经 prepareAfterEditorResolved）
        // ——门持有链就地终结复位；PASS_THROUGH 预览链不动门。
        if (ownsGate) prepareGate.finishPrepare(prepareOwner)
        return
    }

    // 临时文件 + VF + PsiFile：Runner 上下文 + 控制台挂载点（VF 须保持引用）
    val temp = createTempPsiFile(project, submitSql, dialectGateRequest?.renderedDialectName)
    if (temp == null) {
        // T-ExecCopyFix F2a：携带阶段原因（stage 标识/异常摘要），本地化文案——
        // 用户真机曾只见笼统英文 "Failed to create temporary SQL file"，无从归因
        LOG.warn("[M36] createTempPsiFile failed (reason=${lastTempFileFailure}), abort")
        failPrepareRequests(prepareGate, prepareOwner, onSubmission, onError, null,
            MpSqlVisualizerBundle.message("execute.tempFile.failed", lastTempFileFailure ?: "unknown"))
        callback?.invoke(false)
        return
    }
    val (vf, psiFile) = temp

    // consumer：runner.run 建好 session 后回调 → 续接准备链（attachConsole → 拼合 → 可选执行）
    var chosenRunnerName: String? = null
    fun isPrepareActive(): Boolean = !queuedIntent.isCompleted && (!ownsGate || prepareGate.isOwner(queuedIntent))
    val runnerError: (String) -> Unit = { message ->
        if (isPrepareActive()) failPrepareRequests(prepareGate, prepareOwner, onSubmission, onError, null, message)
    }
    val sessionConsumer = com.intellij.util.Consumer<Any> { session ->
        if (!isPrepareActive()) return@Consumer
        val actualTarget = ExecutionDialectGate.fromSession(session)
        LOG.info(
            "[M36][O-145] session created: ${session?.javaClass?.name}, " +
                "target=${actualTarget?.stableId}/${actualTarget?.dialectName}, execute=$executeAfterPrepare",
        )
        val allowed = prepareGate.duringInteraction(prepareOwner) {
            allowDialectGate(project, dialectGateRequest, actualTarget, onDialectGate, onError, onSubmission)
        }
        if (!allowed) {
            abortPrepareGate(prepareOwner, dialectGateRequest?.let {
                dialectGateMessage(ExecutionDialectGate.evaluate(it, actualTarget?.dialectName))
            }, LOG)
            callback?.invoke(false)
            return@Consumer
        }
        // 确认框的模态事件泵期间可能超时/被替代，返回 Yes 也不能复活旧准备链。
        if (!isPrepareActive()) return@Consumer
        prepareInConsole(
            project, session, vf, executeAfterPrepare, chosenRunnerName, onConsole, onEditor, onError,
            onErrorRich, wholeScriptExec, prepareOwner = prepareOwner,
            dialectGateRequest = dialectGateRequest, onDialectGate = onDialectGate,
            onSubmission = onSubmission, isPrepareActive = ::isPrepareActive,
        )
    }

    // T-ExplainSwitchNative W8-hotfix（2026-10-24 用户实测「点击切换数据源就报错」）：
    // 首版把切换入口改成反射 `getSwitchDataSourceRunners`，但该方法的真实签名是
    // **(PsiFile, String, DatabaseSession, Consumer) 四参**（javap 实证），而准备链此刻
    // **尚无已 attach 会话**（切换的常态）→ NoSuchMethodException 被 runCatching 吞成空列表
    // → runners=0 → 用户看到「没有与该 SQL 方言匹配的已配置数据源」红条。
    // 现退回 attach 版（这正是此前一直正常、能弹原生列表的形态）；切换专用入口只在
    // 已有会话时才有意义，本链不持有会话，故不启用。
    val enumerated = getAttachDataSourceRunners(psiFile, submitSql, sessionConsumer)
    // O-136 T-PreviewExecDsDialect：组 Runner 展开——平台在「方言匹配集 ≠ 全部数据源」时返回
    // "All" 组 Runner（getSubRunners 携带全部数据源的 Runner，组项自身 runnable 为空操作），
    // 插件弹窗不展开子级 → 方言不匹配的数据源（DM 等）藏在死项里不可选。展开后按显示名去重，
    // 方言不匹配的数据源在弹窗内直接可见可选（计划 §三「按可选数据源的方言出 Runner」）。
    val runners = ExecDialectRouting.expandGroupRunners(enumerated)
    if (runners.size != enumerated.size) {
        LOG.info("[M36] runners=${enumerated.size} expanded to ${runners.size} (O-136 group runners flattened)")
    } else {
        LOG.info("[M36] runners=${runners.size}")
    }

    // M36.5：last-used 数据源静默命中（仅 prepareConsole 路径；显式执行保持弹窗可选）
    // T-SI-02：命中判定升级为「UUID 精确命中 → 名字唯一命中」，同名多条判歧义弃记忆走弹窗
    //（旧实现 firstOrNull 静默取首条，选错数据源时用户无从察觉）。
    // T-DialectGateExit：forceChoose（用户点「切换数据源」）同样跳过——否则点了等于没点。
    if (allowsSilentLastUsed(executeAfterPrepare, forceChoose)) {
        // T-ModuleScopedLastUsed W2：静默复用也按模块读——模块 A 上次用的源不该被模块 B 复用。
        val memory = lastUsedRunner(project, dialectGateRequest?.moduleKey)
        val resolution = resolveLastUsedRunnerIndex(memory, runners.map { getRunnerName(it) }) { id ->
            runCatching {
                DataSourceSettings.getDataSourceById(project, id)?.let { DataSourceSettings.getName(it) }
            }.getOrNull()
        }
        val matched = resolution.index?.let { runners.getOrNull(it) }
        if (matched != null) {
            val matchedName = getRunnerName(matched)
            LOG.info("[M36] silent reuse last runner: $matchedName (memory id=${memory.dataSourceId})")
            chosenRunnerName = matchedName
            runRunner(project, matched, silent = true, onError = runnerError, moduleKey = dialectGateRequest?.moduleKey)
            callback?.invoke(true)
            return
        }
        if (resolution.ambiguous) {
            LOG.info("[M36] last-used memory ambiguous (id=${memory.dataSourceId}, name=${memory.displayName}) - drop memory, show popup")
            // 只清该模块那一层：其它模块的记忆与项目级兜底不该被一处的歧义连坐。
            forgetLastRunner(project, dialectGateRequest?.moduleKey)
        }
    }

    when {
        // T-ExplainFailUx W4：用户已在下拉里显式指定源名 → 按名唯一命中直接建链，不再弹选择器
        //（一次选择即生效；名字未命中/歧义回落弹窗，不猜）。
        preferredRunnerName != null && runners.count { getRunnerName(it) == preferredRunnerName } == 1 -> {
            val picked = runners.first { getRunnerName(it) == preferredRunnerName }
            LOG.info("[M36] preferred runner by name: $preferredRunnerName (skip platform chooser)")
            chosenRunnerName = preferredRunnerName
            if (!executeAfterPrepare) rememberRunnerName(project, preferredRunnerName, dialectGateRequest?.moduleKey)
            runRunner(project, picked, silent = !executeAfterPrepare, onError = runnerError, moduleKey = dialectGateRequest?.moduleKey)
            callback?.invoke(true)
        }
        runners.isEmpty() -> {
            // O-136 T-PreviewExecDsDialect D3：空 Runner 二分归因——有在册数据源 = 方言不匹配
            //（或数据源均不可连接），给可行动文案（V3），不再让用户笼统「没数据源」或等超时；
            // 真无数据源保持既有引导文案。
            val registered = runCatching { DataSourceSettings.getAllDataSources(project) }.getOrDefault(emptyList())
            LOG.warn("[M36] runners empty (execute=$executeAfterPrepare, registered=${registered.size})")
            if (executeAfterPrepare) openDatabaseToolWindow(project)
            // 用户定案（2026-10-25）：「方言不能影响最终的执行。哪怕方言是错的，用户手动选了
            // 数据源，可以正常提示方言不一致，但是还是要能成功执行」。
            //
            // 平台 runner 枚举按 **psiFile 已映射的 SQL 方言**筛；数据源在平台侧没有 Dialects
            // 映射时，该方言一条 runner 都筛不出（runners=0）——数据源其实完全可用。
            // 兜底＝按**目标源方言**把 exec 文件重映射一次，再枚举一遍 runner：命中即走既有
            // 全链（控制台、结果表、审计全部复用），不新建任何执行通道。
            val remapLanguage = runCatching {
                val targetDs = ExecDialectRouting.resolveTargetDataSource(project)
                ExecDialectRouting.resolveTargetLanguage(project, targetDs)
            }.getOrNull()
            if (remapLanguage != null && ExecDialectRouting.remapExecFileDialect(project, vf, remapLanguage)) {
                val retry = runCatching { ExecDialectRouting.expandGroupRunners(
                    DataSourceSelector.getAttachDataSourceRunners(psiFile, submitSql, sessionConsumer),
                ) }.getOrDefault(emptyList())
                LOG.info("[M36] runners empty → remapped exec file to target dialect, retry runners=${retry.size}")
                if (retry.isNotEmpty()) {
                    // 用重映射后的 runner 集重新进入同一分派逻辑（递归一次，runner 非空不会再回到本分支）
                    return@chooseAndPrepare chooseAndPrepare(
                        project, sql, executeAfterPrepare, onConsole, onEditor, onError, callback,
                        onErrorRich, wholeScriptExec, dialectGateRequest, onDialectGate, onSubmission,
                        forceChoose, preferredRunnerName, forceChoosePopup,
                    )
                }
            }
            if (registered.isEmpty()) {
                onError?.invoke(MpSqlVisualizerBundle.message("execute.datasource.empty"))
            } else {
                // runners 为空的原因是「**SQL 渲染方言**在在册数据源里找不到匹配」，所以文案里的
                // 方言必须报渲染方言。此前只从目标数据源反推（resolveTargetLanguage），而目标源是
                // 上次使用的 MySQL 源 → 推出 MySQL → 用户看到「没有与该 SQL 方言（MySQL）匹配的
                // 数据源」，可他眼前预览里明明写着 `方言: PostgreSQL（手动指定）`、SQL 也是 PG 语法，
                // 文案自相矛盾（2026-10-25 实测）。O-145 W7 已让 mapDialectBeforePsi 跟随渲染方言，
                // 这里同步跟上：有渲染事实优先用它，没有才回退旧的按源推断。
                val renderedName = dialectGateRequest?.renderedDialectName?.takeIf { it.isNotBlank() }
                val dialectDisplay = renderedName
                    ?: run {
                        val targetDs = ExecDialectRouting.resolveTargetDataSource(project)
                        val targetLanguage = ExecDialectRouting.resolveTargetLanguage(project, targetDs)
                        (targetLanguage as? com.intellij.lang.Language)?.id
                            ?: ExecDialectRouting.MYSQL_LANGUAGE_ID
                    }
                val names = registered.joinToString(", ") { DataSourceSettings.getName(it) }
                onError?.invoke(
                    MpSqlVisualizerBundle.message("execute.datasource.dialectMismatch", dialectDisplay, names),
                )
            }
            // T-DsCover：仅显式执行引导打开 Database 工具窗；预览默认准备静默——
            // 补全 M36.5 预览路径（onError=null）设计意图，避免每次双击预览抢占侧边栏。
            // M36.3：无数据源时通知 UI 从「执行中」恢复
            // T-FirstExecResultInvisible F1：链就此终结，复位单飞位（否则后续执行被永久排队）
            onSubmission(ExecutionSubmission.BLOCKED)
            if (ownsGate) prepareGate.abort(prepareOwner, ExecutionSubmission.BLOCKED)
            callback?.invoke(false)
        }
        runners.size == 1 && shouldBeChosenIfAlone(runners.first()) && !forceChoosePopup -> {
            LOG.info("[M36] single runner, run directly")
            chosenRunnerName = getRunnerName(runners.first())
            runRunner(project, runners.first(), silent = !executeAfterPrepare, onError = runnerError, moduleKey = dialectGateRequest?.moduleKey)
            callback?.invoke(true)
        }
        else -> {
            LOG.info("[M36] show popup with ${runners.size} runners")
            prepareGate.beginInteraction(prepareOwner)
            showRunnerPopup(project, runners) { runner ->
                prepareGate.endInteraction(prepareOwner)
                if (!isPrepareActive()) return@showRunnerPopup
                LOG.info("[M36] popup selected: ${runner?.let { getRunnerName(it) }}")
                // M36.5：记住选择（prepareConsole 路径下次静默复用；控制台创建时也会统一记忆）
                if (!executeAfterPrepare && runner != null) {
                    rememberRunnerName(project, getRunnerName(runner), dialectGateRequest?.moduleKey)
                }
                if (runner != null) chosenRunnerName = getRunnerName(runner)
                callback?.invoke(runner != null)
                // T-FirstExecResultInvisible F1：用户取消弹窗 = 链就此终结，复位单飞位
                if (runner == null) {
                    if (ownsGate) prepareGate.abort(prepareOwner, ExecutionSubmission.CANCELLED)
                    onSubmission(ExecutionSubmission.CANCELLED)
                }
                // 弹窗中用户刚做了主动选择，失败仍需模态引导（silent=false）
                if (runner != null) runRunner(project, runner, onError = runnerError, moduleKey = dialectGateRequest?.moduleKey)
            }
        }
    }
}

/**
 * M36.5.5：共享控制台复用检查（last-used 数据源 == 共享缓存数据源且控制台/编辑器仍有效）。
 * 命中 → SQL 同步进控制台文档、重挂 UI、回调 true，返回 true；未命中返回 false 走全链。
 */

internal fun DataSourceSelector.reuseSharedConsole(
    project: Project,
    sql: String,
    executeAfterPrepare: Boolean,
    onConsole: ((javax.swing.JComponent) -> Unit)?,
    onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)?,
    onError: ((String) -> Unit)?,
    callback: ((Boolean) -> Unit)?,
    onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
    wholeScriptExec: Boolean = false,
    dialectGateRequest: ExecutionDialectGate.Request? = null,
    onDialectGate: ((ExecutionDialectGate.Verdict) -> Unit)? = null,
    onSubmission: ((ExecutionSubmission) -> Unit)? = null,
    prepareOwner: QueuedExecuteIntent? = null,
): Boolean {
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
    val console = sharedConsole ?: return false
    val editor = sharedEditor ?: return false
    val top = sharedTopBlock ?: return false
    val dsName = sharedDataSourceName ?: return false
    val scriptModel = sharedScriptModel ?: return false
    val execOption = sharedExecOption ?: return false
    if (lastRunnerName(project) != dsName) return false
    // M36.5.10：代际递增——复用会覆盖控制台文档（setText），作废挂起的 runWhenSmart 提交，
    // 防止旧预览 SQL 在新文档上执行（文档共享串扰）
    executionGeneration++
    val valid = runCatching {
        console.javaClass.getMethod("isValid").invoke(console) == true && !editor.isDisposed
    }.getOrDefault(false)
    // T-SI-01：复用身份 fail-closed——复用判定从「显示名相等 + isValid」升级为「显示名相等 +
    // isValid + 三分量探测的 dsInFacade 分量」（复用 attach 路径既有探测，不重造）。复用是
    // 低频路径，探测是内存级反查（DbPsiFacade.findDataSource），成本可接受。
    // 身份被拒时弃共享会话并清 last-used 记忆——旧实现只丢会话不清记忆，下一轮仍会按显示名
    // 静默命中（同名数据源场景直接钉错库），与 attach 路径同规：身份不可信即弃，不猜。
    val identity = probeConsoleValidity(console, project, LOG)
    if (!valid || !reuseIdentityAccepted(identity)) {
        LOG.warn("[M36] shared console identity rejected (ds=$dsName, isValid=$valid, $identity), drop session")
        dropSharedConsole()
        if (identity?.dsInFacade == false) forgetLastRunner(project, dialectGateRequest?.moduleKey)
        return false
    }
    runCatching {
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText(sql)
        }
    }
    // M36.5.24：复用路径同款刷新——setText 只更新 console document，console.getScriptModel()
    // 的缓存 model（isActual 仅比较 splitScriptMode）仍基于旧内容/空 vf → statementsRange=(0,0)
    // → ▶ 灰 + 无绿色边框。同步 vf document 并 commit；model 重建移入 runWhenSmart（dialect
    // push dumb task 完成后 PSI 才完整，M36.5.24g 22:30 实证）。
    var refreshedModel: Any? = scriptModel
    runCatching {
        val consoleVf = console.javaClass.getMethod("getVirtualFile").invoke(console)
            as? com.intellij.openapi.vfs.VirtualFile
        if (consoleVf != null) {
            @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
            com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
                // T-ExecDocSync2 F1：同步收口为 writeVfTextViaDocument（T-EC-01 语义超集：
                // setText-if-differ + commit + saveDocument；fileDoc==null 走 setBinaryContent）。
                // 原实现只 commit 不落盘——console document 即 exec 文件 document（attachConsole
                // 挂载点同文件），滞留「已修改未保存」+ 窗口期任意外部改盘 + VFS refresh =
                // File Cache Conflict 弹窗（第四轮根因 G2，开发计划 §1.2）
                writeVfTextViaDocument(project, consoleVf, sql)
            }
            // T-ConsoleDbContext B：复用链同样对齐库上下文（控制台可能来自别的库记忆）
            LOG.info("[M36] shared console db-context: ${ExecConsoleContext.alignToDataSourceDatabase(console)}")
            // M36.5.24g：dumb 退出后重建（同 prepareInConsole）
            val consoleForModel = console
            val vfForModel = consoleVf
            com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart {
                // write-safe: 无 document 写（反射替换 DBN script model）
                ApplicationManager.getApplication().invokeLater({
                    runCatching {
                        refreshConsoleModel(project, consoleForModel, vfForModel, LOG)
                    }.onFailure { e ->
                        LOG.warn("[M36] shared scriptModel refresh (smart) FAILED: ${e.message}")
                    }
                }, com.intellij.openapi.application.ModalityState.any())
            }
        }
    }.onFailure { e ->
        LOG.warn("[M36] shared scriptModel vf-sync FAILED (keep cached): ${e.message}")
    }
    // M36.5.50：预览（execute=false 复用 console）时清空上个 SQL 的结果区残留——用户
    // "还没执行就显示 t.id=1 旧结果"（预览 A=t.id=2 时结果区仍显示 B=t.id=1）。预览应显示
    // 空态，执行后才出现结果。M36.5.46 的 realignSplitDivider 已保证清空后 bottom 为空
    // 组件也校准 divider（不塌缩，M36.5.45b 的塌缩问题已修）。
    // M36.5.62：连 console 历史（Output tab 回显的上个 SQL）一起清空——预览时应显示干净空态，
    // 不残留上个执行/预览 SQL 的回显文本（用户实测"预览 sqlA 时下方显示之前执行过的 sqlB"）。
    if (!executeAfterPrepare) {
        clearResultContents(sharedUi, LOG)
        clearConsoleHistory(console, LOG)
    }
    // M36.5.7：完整 UI 按**实时结果区组件**刷新——M36.5.41-A4 起不再重建 SplitPane：
    // 持久整控台原位换底（身份稳定，sameAsPrev=true），仅整控台容器缺失时回退重建。
    // （组合引用在面板分离/重挂后会过期——M36.5.5 复用面板空显示根因；顶部块（原生头部+编辑器）
    // 自旧面板"偷"入新面板。）
    val liveResult = runCatching {
        sharedUi?.javaClass?.getMethod("getComponent")?.invoke(sharedUi) as? javax.swing.JComponent
    }.getOrNull()
    // ★A49：**无嵌入宿主（onConsole == null）时不重摘 DBN 结果组件**——prepare 侧已 gate，但复用链
    // 若仍 swap 会再次把结果区组件从 DBN 原生宿主摘进我方永不展示的 SplitPane（`swapResultIntoPersistentConsole`
    // 的强制换入分支会 remove 外部宿主 + 设 bottomComponent）。无宿主时摘取无收益、只扰动 DBN 组件树
    // （详见 [shouldComposeEmbedSplitPane] 的归因更正：这是排查「网格 0 rows」时顺带发现的收敛，
    // 该现象真因是调用方传了不存在的表名）。无宿主时沿用既有缓存（可能为 null）；有宿主（预览面板
    // 复用）时判据化简后与改前逐字等价，行为不变。
    val hasHost = onConsole != null
    val composeNow = shouldComposeEmbedSplitPane(hasHost, liveResult != null)
    val full: javax.swing.JComponent? = when {
        composeNow && liveResult != null ->
            // M36.5.43：swap 助手强制换入（不再返回 null）——结果区从 DBN 宿主摘入本 SplitPane
            swapResultIntoPersistentConsole(sharedFullComponent, liveResult, top, LOG)
        else -> sharedFullComponent
    }
    if (hasHost && full == null) return false
    if (liveResult != null) lastLiveResultSwapped = liveResult
    if (hasHost) sharedFullComponent = full
    preparedConsole = console
    preparedEditor = editor
    preparedScriptModel = refreshedModel ?: scriptModel
    preparedExecOption = execOption
    preparedDialectGateRequest = dialectGateRequest ?: sharedDialectGateRequest
    preparedDialectGateCallback = onDialectGate ?: sharedDialectGateCallback
    sharedDialectGateRequest = dialectGateRequest ?: sharedDialectGateRequest
    sharedDialectGateCallback = onDialectGate ?: sharedDialectGateCallback
    sharedScriptModel = refreshedModel ?: scriptModel
    preparedUi = sharedUi
    preparedTopBlock = top
    preparedResultComponent = liveResult
    if (hasHost) preparedFullComponent = full
    onEditor?.invoke(editor)
    full?.let { onConsole?.invoke(it) }
    // M36.5.55：预览（execute=false）复用 console 时也把结果区（bottom）折叠——divider 压到
    // 接近底部，bottom（DBN Output/Tx 结果区占位）收起不显示，满足"未执行不显示结果面板"。
    // T-FirstExecResultInvisible F3：折叠归口 embedMode——仅 PREVIEW 且结果区无内容才折叠
    //（上方清空失败/竞态下已有内容则保持展开，不吞用户可见的结果）。
    if (!executeAfterPrepare) {
        (full as? javax.swing.JSplitPane)?.let { sp ->
            val collapse = ConsoleEmbedMode.PREVIEW.shouldCollapseDivider(resultAreaHasContent(sharedUi))
            if (collapse) {
                // write-safe: UI-only（纯 Swing/只读，无文档写）
                ApplicationManager.getApplication().invokeLater({
                    sp.setDividerLocation(1.0)
                    sp.revalidate()
                    sp.repaint()
                }, com.intellij.openapi.application.ModalityState.any())
            }
        }
    }
    // M36.5.6：接管结果换入回调——后续任意路径执行产生新结果内容时换入本面板；
    // 已嵌完整组件场景由面板同实例防抖幂等吸收。
    pendingOnConsole = onConsole
    logDataContextCheck(top, console, editor, execOption, LOG)
    clearConsolePrompt(console, LOG)
    LOG.info("[M36] shared console reused (ds=$dsName, execute=$executeAfterPrepare, liveResult=${liveResult != null})")
    if (executeAfterPrepare) {
        if (prepareOwner != null && !prepareGate.beginSubmission(prepareOwner)) return true
        executeOnConsole(
            project, console, editor, refreshedModel ?: scriptModel, execOption, sharedUi,
            onResultsReady = null, onError = onError, onErrorRich = onErrorRich,
            wholeScriptExec = wholeScriptExec,
            dialectGateRequest = dialectGateRequest ?: sharedDialectGateRequest,
            onDialectGate = onDialectGate ?: sharedDialectGateCallback,
            onSubmission = onSubmission,
        )
    }
    callback?.invoke(true)
    return true
}

/** M36.5.5：释放共享控制台缓存（控制台失效时懒清理；JdbcConsole 本体由 IDE 管理）。 */

internal fun DataSourceSelector.attachResultsHook(
    ui: Any?,
    topBlock: javax.swing.JComponent,
    resultComponent: javax.swing.JComponent?,
    console: Any,
    sql: String,
    LOG: com.intellij.openapi.diagnostic.Logger,
) {
    if (ui == null || resultComponent == null) return
    runCatching {
        val cm = ui.javaClass.getMethod("getContentManager").invoke(ui)
            as com.intellij.ui.content.ContentManager
        cm.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
            override fun contentAdded(event: com.intellij.ui.content.ContentManagerEvent) {
                val name = event.content?.displayName ?: return
                LOG.info("[M36] contentAdded: $name")
                if (name.equals("Output", ignoreCase = true) || name.equals("输出", ignoreCase = true)) return
                // T-ExplainFirstNative：空名占位（DBN 先 add 后改名）换入必然过早——网格未构建，
                // 交由 startResultTabRetry 在改名后 select+换入
                if (name.isBlank()) return
                val callback = pendingOnConsole ?: run {
                    LOG.info("[M36] contentAdded swap-in SKIPPED (pendingOnConsole=null)")
                    return
                }
                // 换入时重新取结果区组件——RunnerLayoutUi 可能在面板分离/重挂后重建组件，
                // prepare 时捕获的引用可能已非当前展示实例（M36.5.5 复用面板空显示的疑因）。
                val liveResult = runCatching {
                    ui.javaClass.getMethod("getComponent").invoke(ui) as? javax.swing.JComponent
                }.getOrNull() ?: resultComponent
                lastLiveResultSwapped = liveResult
                // M36.5.41-A4：持久整控台原位换底（不重建 SplitPane，身份稳定）；持久容器
                // 缺失时 swap 助手回退 buildConsoleSplitPane（feature flag，可回退旧路径）。
                val full = swapResultIntoPersistentConsole(
                    sharedFullComponent ?: preparedFullComponent,
                    liveResult, topBlock, LOG,
                )
                preparedFullComponent = full
                sharedFullComponent = full
                sharedTopBlock = topBlock
                LOG.info("[M36] contentAdded swap-in dispatched (name=$name, rebuilt=true)")
                ApplicationManager.getApplication().invokeLater({
                    runCatching { callback.invoke(full) }.onFailure { ex ->
                        LOG.warn("[M36] contentAdded swap-in FAILED: ${ex.javaClass.simpleName}: ${ex.message}", ex)
                    }
                    // M36.5.25c：换入后重写 SQL 到 console document——topBlock reparent 到新
                    // SplitPane 后 editor 内容可能被清空（图1 SQL 区域空白根因），用**当前** SQL
                    // 文本写回 console document 保证编辑器显示完整 SQL。
                    // M36.5.56 修复：原实现用 attachResultsHook 捕获的**准备时 sql 快照**
                    // 重写——用户执行新 SQL 后 contentAdded 触发时用它覆盖，导致 SQL 回退
                    // 成上一个（日志实证：执行 417 后 `console doc rewritten (len=183)`，SQL
                    // 变回 183）。改用**当前编辑器文档文本**（用户当前 SQL），不再用过期快照。
                    runCatching {
                        val currentSql = (sharedEditor ?: preparedEditor)
                            ?.document?.text?.takeIf { it.isNotBlank() } ?: sql
                        val consoleDoc = console.javaClass.getMethod("getDocument")
                            .invoke(console) as? com.intellij.openapi.editor.Document
                        if (consoleDoc != null && consoleDoc.text != currentSql) {
                            @Suppress("DEPRECATION")
                            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
                                console.javaClass.getMethod("getProject").invoke(console) as? com.intellij.openapi.project.Project
                                    ?: return@runCatching
                            ) {
                                consoleDoc.setText(currentSql)
                                // T-ExecDocSync2 F3：同临界区落盘——console document 即 exec 文件
                                // document（attachConsole 挂载点），只 setText 不落盘滞留
                                // 「已修改未保存」窗口（第四轮根因 G4，开发计划 §1.2）。
                                // 落盘失败不回滚改写本身（显示优先），仅记录
                                runCatching {
                                    val p = console.javaClass.getMethod("getProject").invoke(console)
                                        as? com.intellij.openapi.project.Project
                                    if (p != null) {
                                        com.intellij.psi.PsiDocumentManager.getInstance(p)
                                            .commitDocument(consoleDoc)
                                        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                                            .saveDocument(consoleDoc)
                                    }
                                }.onFailure { e ->
                                    LOG.warn("[M36] contentAdded post-rewrite save FAILED: ${e.message}")
                                }
                            }
                            // 光标移到文档开头（用户体验：能立即看到第一条 SQL）
                            runCatching {
                                val ed = com.intellij.openapi.editor.EditorFactory.getInstance()
                                    .getEditors(consoleDoc).firstOrNull()
                                ed?.let { ed.selectionModel.removeSelection(); ed.caretModel.moveToOffset(0) }
                            }
                            LOG.info("[M36] contentAdded: console doc rewritten (len=${consoleDoc.textLength})")
                        }
                    }.onFailure { e ->
                        LOG.warn("[M36] contentAdded rewrite FAILED: ${e.message}")
                    }
                    // T-WSC-02：本回调写 console 文档（setText + commitDocument + saveDocument）且用的是
                    // 快照文本、不幂等——必须「投递时即 write-safe」，不能用重投递自愈，故 any() 改
                    // nonModal（any() 恒 write-unsafe，见 DocWriteContext KDoc）。
                }, com.intellij.openapi.application.ModalityState.nonModal())
            }
        })
        LOG.info("[M36] results hook attached (contentAdded -> embed results)")
    }.onFailure { e ->
        LOG.warn("[M36] results hook attach FAILED: ${e.message}")
    }
}

/**
 * M36.5.6：嵌入后自检数据上下文——原生 ▶ 执行按钮启用链（QueryActionBase：EDITOR +
 * JdbcConsole.findConsole 的 CLIENT_KEY 分支）经 DataManager 在嵌入块上解析验证。
 * M36.5.8：补齐 ▶ 启用链的剩余环节探测——isSqlAtPlace(psiFile, editor)（isApplicable
 * 依赖）与 getSelectedStatementsRange(client, editor, execOption)（最终 setEnabled 依据，
 * = scriptModel.getTextRange().length > 0）——每步结果落日志，▶ 禁用时一眼定位断点。
 */

internal fun DataSourceSelector.writeConsoleOutput(console: Any?, text: String, isError: Boolean = false) {
    if (console == null || text.isBlank()) return
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
    runCatching {
        val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console)
        if (consoleView == null) { LOG.info("[M36] writeConsoleOutput: consoleView null"); return }
        // ① 直接向 historyViewer 的 document 追加文本（Output 控制台显示的就是此 document）
        //    ——比走 ConsoleView.print 更可靠（不依赖 ConsoleViewContentType 枚举类是否存在）。
        val historyViewer = consoleView.javaClass.getMethod("getHistoryViewer").invoke(consoleView)
        if (historyViewer != null) {
            val doc = historyViewer.javaClass.getMethod("getDocument").invoke(historyViewer)
            if (doc is com.intellij.openapi.editor.Document) {
                val append = if (doc.text.isNotEmpty() && !doc.text.endsWith("\n")) "\n" else ""
                try {
                    val proj = (console as? com.intellij.openapi.project.Project) ?: runCatching {
                        console.javaClass.getMethod("getProject").invoke(console) as? com.intellij.openapi.project.Project
                    }.getOrNull()
                    @Suppress("DEPRECATION")
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(proj) {
                        doc.insertString(doc.textLength, "$append$text")
                    }
                    // 滚到末尾（新输出可见）
                    // write-safe: 无 document 写（反射替换 DBN script model）
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                        runCatching {
                            // 滚动到底：LanguageConsoleView 的 caret/scrollPosition
                            val editor = com.intellij.openapi.editor.EditorFactory.getInstance()
                                .getEditors(doc).firstOrNull()
                            if (editor != null) {
                                editor.caretModel.moveToOffset(doc.textLength)
                                editor.scrollingModel.scrollToCaret(
                                    com.intellij.openapi.editor.ScrollType.RELATIVE,
                                )
                            }
                        }
                    }, com.intellij.openapi.application.ModalityState.any())
                    LOG.info("[M36] writeConsoleOutput: appended to history doc len=${doc.textLength}")
                    return
                } catch (e: Throwable) {
                    // T-FatalPassthrough T-FP-02：JVM 级致命错误透传（见 util/FatalErrors.kt）——
                    // 本文件是执行链长驻路径，捕获面覆盖 Error：普通异常（含可选能力探测的
                    // ClassNotFoundException）仍走既有降级，VirtualMachineError/LinkageError 不得
                    // 被吞成一条日志后让执行链带病继续。
                    if (FatalErrors.isFatal(e)) throw e
                    LOG.info("[M36] writeConsoleOutput doc-append FAILED: ${e.message}")
                }
            }
        }
        // ② 兜底 1：ConsoleView.print(String, ConsoleViewContentType) 反射
        val contentClass = runCatching { Class.forName("com.intellij.execution.ui.ConsoleViewContentType") }.getOrNull()
        if (contentClass != null) {
            val ct = try {
                val fieldName = if (isError) "ERROR_OUTPUT" else "NORMAL_OUTPUT"
                contentClass.getField(fieldName).get(null)
            } catch (e: Throwable) {
                if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
                contentClass.fields.firstOrNull()?.get(null)
            }
            val print = consoleView.javaClass.methods.firstOrNull { m ->
                m.name == "print" && m.parameterCount >= 2 && m.parameterTypes[0] == String::class.java
            }
            if (ct != null && print != null) {
                print.invoke(consoleView, text, ct)
                LOG.info("[M36] writeConsoleOutput: ConsoleView.print ok")
                return
            } else {
                LOG.info("[M36] writeConsoleOutput: ct=${ct != null} print=${print != null}")
            }
        } else {
            LOG.info("[M36] writeConsoleOutput: ConsoleViewContentType class not found")
        }
    }.onFailure { e ->
        // T-FatalPassthrough T-FP-02b（独立复核阻断 1 修正）：本函数的 runCatching 包住上面两处
        // fatal 透传（doc-append 捕获块 / ConsoleViewContentType 字段回退捕获块）——在此把 fatal
        // 吞成一行 INFO 等于透传无效（改前实测：fatal 被转成 "[M36] writeConsoleOutput FAILED: …"
        // 后执行链带病继续，正是上面捕获块注释声称的反面）。反射链先解包 InvocationTargetException
        // 再判（同本文件原生执行链 :2604-2607 形态；只判外层会漏判目标方法抛出的 fatal）。
        // 非 fatal 路径行为逐字节不变：仍只记原 INFO 行，函数返回 Unit、调用方（EDT 排出的
        // writeConsoleOutput + onError 回调）行为不变；fatal 则透传到平台 EDT 处理器记录现场。
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        if (FatalErrors.isFatal(cause)) throw cause
        LOG.info("[M36] writeConsoleOutput FAILED: ${e.message}")
    }
}

/**
 * M36.5.8/M36.5.9/M36.5.13：执行后结果区刷新——**限时多次重取**。新执行会重建 RunnerLayoutUi 的
 * 内容组件，重建时机与数据到达相关（单次重取可能仍取到旧实例——M36.5.8 实测刷新命中后
 * 显示依旧停留上一轮）；EDT Timer 600ms×8 重取 `ui.getComponent()`。
 *
 * M36.5.13 变更：
 *  - [forceSwapIn] = true（startResultTabRetry 找到结果 Tab 后传入）：**立即强制换入一次**——
 *    旧 ui 的组件实例可能恒定（M36.5.10 盘点实证"刷新定时器 8 拍实例恒定"），"实例变化才换入"
 *    的去重导致新结果永远不显示（用户实测第二次执行后仍显示第一次结果）；换入后更新
 *    lastLiveResultSwapped 基线，后续实例变化仍可继续换入。
 *  - Timer **每拍清提示符**——DatabaseLogView 在执行/数据渲染时重设 prompt（用户实测
 *    prepare/reuse/入口清理后仍重现），4.8s 窗口内持续压制。
 */

internal fun DataSourceSelector.dumpSplitBounds(sp: javax.swing.JSplitPane, log: com.intellij.openapi.diagnostic.Logger) {
    // T-Perf-13：取证 dump 降 debug 门控（非 debug 模式零开销）
    if (!log.isDebugEnabled) return
    runCatching {
        fun b(c: java.awt.Component?): String = if (c == null) "null" else
            "${c.javaClass.simpleName}@${System.identityHashCode(c)} bounds=${c.bounds} pref=${c.preferredSize} min=${c.minimumSize} max=${c.maximumSize}"
        log.debug("[M36][BOUNDS] sp=${b(sp)} divider=${sp.dividerLocation}")
        log.debug("[M36][BOUNDS]   top=${b(sp.topComponent)}")
        (sp.topComponent as? java.awt.Container)?.components?.forEachIndexed { i, c ->
            log.debug("[M36][BOUNDS]   top.child[$i]=${b(c)}")
            if (c is java.awt.Container) c.components.forEachIndexed { j, d ->
                log.debug("[M36][BOUNDS]   top.child[$i].child[$j]=${b(d)}")
                // M36.5.61：编辑器视图（PanelWithFloatingToolbar）内部是否含未拉伸的 scrollpane/
                // viewport——mask 真相在此层（编辑器 view 有 bounds 但只 paint 内容高度）
                if (d is java.awt.Container) d.components.forEachIndexed { k, e ->
                    log.debug("[M36][BOUNDS]   top.child[$i].child[$j].child[$k]=${b(e)}")
                    if (e is javax.swing.JScrollPane) {
                        log.info("[M36][BOUNDS]     scrollpane viewport=${e.viewport?.let { v -> "${b(v)} vsbV=${e.verticalScrollBar?.isVisible} hsbV=${e.horizontalScrollBar?.isVisible}" } }")
                    }
                }
            }
        }
        log.info("[M36][BOUNDS]   bottom=${b(sp.bottomComponent)}")
        // M36.5.63：递归看结果区内部——RunnerLayoutUi 结果页有内部 layout（Output/结果 sub-split），
        // 换入时若内部 divider 塌缩（结果页只显示 Output 一行），外层 bounds 正确但内部是坏的。
        fun dumpDeep(c: java.awt.Component?, depth: Int, path: String) {
            if (c == null || depth > 12) return
            val spExtra = if (c is javax.swing.JSplitPane) " div=${c.dividerLocation} split(${c.orientation})" else ""
            log.info("[M36][BOUNDS]   ${path}=${b(c)}$spExtra")
            if (c is java.awt.Container) {
                var idx = 0
                for (d in c.components) {
                    dumpDeep(d, depth + 1, "$path.child[$idx]")
                    idx++
                }
            }
        }
        // 结果区整体（MyNonOpaquePanel/MyComponent）+ 内部两级，重点找 JSplitPane/塌缩 divider
        (sp.bottomComponent as? java.awt.Container)?.components?.forEachIndexed { i, c ->
            log.info("[M36][BOUNDS]   bottom.child[$i]=${b(c)}")
            if (c is java.awt.Container) c.components.forEachIndexed { j, d ->
                dumpDeep(d, 2, "bottom.child[$i].child[$j]")
            }
        }
        // M36.5.68：定位 Output 控制台（ConsoleView/EditorImpl 文本视图）真实高度——问题 SQL 后
        // 结果 grid 内容 ProviderWrapper 填满 234px，但承载 Output 历史文本的滚动视图可能只按
        // 内容高度渲染（2 行），下方留白。深扫所有 JScrollPane/JTextComponent，看谁没撑满。
        fun scanTextViews(c: java.awt.Component?, path: String) {
            if (c == null) return
            val cls = c.javaClass.name
            if (c is javax.swing.JScrollPane || cls.contains("ConsoleView") || cls.contains("EditorImpl") || c is javax.swing.text.JTextComponent) {
                log.info("[M36][BOUNDS][TEXT] $path ${cls.substringAfterLast('.')} bounds=${c.bounds} pref=${c.preferredSize}")
                if (c is javax.swing.JScrollPane) {
                    log.info("[M36][BOUNDS][TEXT]   viewport=${c.viewport?.let { it.bounds.toString() }} vsbV=${c.verticalScrollBar?.isVisible}")
                    c.viewport?.let { scanTextViews(it.view, "$path.view") }
                }
            }
            if (c is java.awt.Container) {
                var idx = 0
                for (d in c.components) { scanTextViews(d, "$path.child[$idx]"); idx++ }
            }
        }
        (sp.bottomComponent as? java.awt.Container)?.let { scanTextViews(it, "bottom") }
    }.onFailure { e ->
        log.info("[M36][BOUNDS] dump failed: ${e.message}")
    }
}

/**
 * T-ErrorLocate T-ELU-01/03：错误投递**统一收口**——富通道优先，富通道缺席才回退旧
 * `onError(String)`；两者**互斥**，不再先后各投一次。
 *
 * **为什么必须互斥**（T-ELU-01 实测的真相，与计划 §一.1 的「按钮可见但点了没反应」不同）：
 * 消费方 `SqlPreviewPanel` 的 `onError` 恒为 `resultPanel.showError(errorMsg)`，而
 * `showError` 会清掉定位动作与定位按钮（`ui/SqlResultPanel.kt` 的
 * `locateHandler = null` + `errorLocateButton.isVisible = false`）。原实现先投富通道
 * （`showErrorRich` 置按钮可见）、紧接着无条件投旧通道——**同一 EDT 任务内**后者立刻把
 * 前者抹掉，端态恒为「按钮不可见」，T-ELU-01 的「先解析后显隐」因此形同 no-op。
 * 两者呈现的文本同源（富通道 `showErrorRich` 内部就是 `showError(info.message)`），
 * 故富通道成功时不再投旧通道，展示行为等价而定位态得以保留。
 *
 * 富通道自身抛异常（`runCatching`）时**回退旧通道**——不让「结构化」这一增强把错误呈现吞掉。
 *
 * **2026-09-23 真机走查补记（本函数曾丢失过，勿再删）**：本函数在分支上实现于 `4186231`，
 * 但 `914724c`（「O-48/O-49 采信 master 侧已合入实现」）把 O-49 整体改回 master 侧形态，
 * 该收口随之消失，端态退回「按钮永不出现」——真机走查实证：一条锚点可解析的失败语句
 * （`DELETE FROM no_such_table_xyz`）面板只出现红字 +「复制错误信息」，**没有定位按钮**。
 *
 * @param cause        解包后的异常（取 SQLState/errorCode；控制台历史文本错误传 null）
 * @param message      错误文本（位置 marker 从它提取）
 * @param sqlMaxLength 提交/被执行文本长度（T-ELU-03 越界钳制上界）；null = 不钳制
 */

internal fun DataSourceSelector.afterSubmitted(
    project: Project,
    ui: Any?,
    console: Any,
    onResultsReady: ((javax.swing.JComponent) -> Unit)?,
    onError: ((String) -> Unit)?,
    LOG: com.intellij.openapi.diagnostic.Logger,
    dsName: String? = null,
    demotedOverride: Boolean = false,
    sqlText: String? = null,
    onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
    onSubmission: ((ExecutionSubmission) -> Unit)? = null,
) {
    onSubmission?.invoke(ExecutionSubmission.SUBMITTED)
    // M36.5.44：真实提交时刻递增换入代际——作废上一个执行遗留的 refresh Timer（防滞后一轮）。
    swapGeneration++
    // write-safe: UI-only（纯 Swing/只读，无文档写）
    ApplicationManager.getApplication().invokeLater({
        // ① 快照：本次提交时已存在的结果 Tab **名称**（判定"新结果"的基线）。
        // M36.5.22b：实例身份判定失效——DBN 复用同一个 Content 实例并改名/更新
        //（17:25 会话实证：pre-existing=[Result 1]，retry#1 已见 Result 2，但
        // `it !in preExisting` 仍为 false → select=null）。改以 displayName 为键。
        val pre = resultContents(ui)
        val preExistingNames = pre.map { it.displayName }.toSet()
        LOG.info("[M36] result watch started (pre-existing-names=$preExistingNames, ids=${pre.map { System.identityHashCode(it) }})")
        // ★A49 取证：提交时刻结果区组件的**宿主**——判「有没有被摘走」的决定性读数（无论本拍是否
        // 发生 swap 都打）。`parent` 全类名是判别力所在：`…ServiceViewComponentWrapper` = 仍在 DBN
        // 原生宿主（未摘）；`…buildConsoleSplitPane$1` = 已在我方容器（摘了）。
        runCatching {
            val liveNow = ui?.javaClass?.getMethod("getComponent")?.invoke(ui) as? javax.swing.JComponent
            val mySp = sharedFullComponent ?: preparedFullComponent
            val myBottom = (mySp as? javax.swing.JSplitPane)?.bottomComponent
            val viewShowing = runCatching {
                val v = console.javaClass.getMethod("getView").invoke(console)
                v?.javaClass?.getMethod("isShowing")?.invoke(v)
            }.getOrNull()
            LOG.info(
                "[M36][A49] result host at submit: parent=${liveNow?.parent?.javaClass?.name ?: "null"}, " +
                    "mySplitBottom=${myBottom?.javaClass?.name ?: "null"}, " +
                    "stolen=${liveNow != null && mySp != null && liveNow === myBottom && liveNow.parent === mySp}, " +
                    "viewShowing=$viewShowing",
            )
        }
        // ② 8s 连接等待监控（从提交时刻起算）。M36.5.44 放宽：原判定 `!newTab || !namespaceOk`
        // 双条件任一为假即报超时——但连接建立慢（首个 SQL，query 在 8s 后才完成）时
        // newTab=false 且 namespace 可能暂空，**误报"执行超时"**（用户截图红字实证）。
        // 改为：只要 8s 后**出现了新结果 Tab**（resultContents 有非 Output 且名不在快照）即视为
        // 成功；仅当"既无新 Tab 又无有效 namespace"（连接确实没建起来）才提示凭据/驱动。
        if (ui != null && onError != null) {
            val uiForWatch = ui
            val hintOnError = onError
            val consoleForWatch = console
            val watch = javax.swing.Timer(8000, null)
            watch.addActionListener {
                watch.stop()
                val newTab = resultContents(uiForWatch).any { it.displayName.isNotBlank() && it.displayName !in preExistingNames }
                val namespaceOk = runCatching {
                    val ns = consoleForWatch.javaClass
                        .getMethod("getCurrentNamespace").invoke(consoleForWatch)
                    ns != null && ns.toString().isNotBlank()
                }.getOrDefault(true)
                if (!newTab && !namespaceOk) {
                    // T-ExecSilentNoop：提交后 8s 回执——namespace 都出来了说明连接**已建立**、
                    // 探针显示未提交（freshModel 回落）则归因"未提交"而非凭据；否则才落到
                    // 凭据/驱动。与 40 拍窗口共用 executionHintReported 去重，同次执行只报一次。
                    if (!executionHintReported) {
                        executionHintReported = true
                        val hint = if (demotedOverride) {
                            MpSqlVisualizerBundle.message("execute.notSubmitted.hint", dsName ?: "")
                        } else {
                            MpSqlVisualizerBundle.message("execute.credential.hint")
                        }
                        LOG.info("[M36] no NEW result tab AND no namespace within 8s (newTab=$newTab, namespaceOk=$namespaceOk, demotedOverride=$demotedOverride) - hint=${
                            if (demotedOverride) "notSubmitted" else "credential"
                        }")
                        hintOnError.invoke(hint)
                    }
                } else {
                    LOG.info("[M36] result/layout OK within 8s (newTab=$newTab, namespaceOk=$namespaceOk) - no hint")
                }
            }
            watch.isRepeats = false
            watch.start()
        }
        // ③ executePrepared 路径：右侧已嵌持久整控台（M36.5.25i 统一 embed）——原位换底
        //    后回调同一实例（面板侧 embedConsole 同实例防抖幂等吸收，身份稳定）。
        //    JSplitPane 操作必须一体在 EDT（M36.5.11 教训）。
        //    M36.5.44：换入**实时结果组件**（ui.getComponent()）而非 preparedResultComponent
        //    （prepare 时捕获的占位，从未更新——用它会在第二次执行时把"首个 SQL 已换入的
        //    真实结果"覆盖回占位，正是"滞后一轮"的直接来源）。拿不到实时组件则跳过，
        //    交由 refreshResultsComponent 兜底。
        if (onResultsReady != null) {
            val top = preparedTopBlock
            val liveResult = runCatching {
                ui?.javaClass?.getMethod("getComponent")?.invoke(ui) as? javax.swing.JComponent
            }.getOrNull() ?: preparedResultComponent
            if (top != null && liveResult != null) {
                // M36.5.43：swap 助手强制换入且不返回 null——结果区从 DBN 宿主摘入持久 SplitPane。
                val full = swapResultIntoPersistentConsole(preparedFullComponent, liveResult, top, LOG)
                onResultsReady.invoke(full)
                clearConsolePrompt(console, LOG)
            }
        }
        // ④ 结果 Tab 监听（只认新 Tab 名）
        if (ui != null) {
            startResultTabRetry(project, ui, onError, preExistingNames, dsName = dsName, demotedOverride = demotedOverride, sqlText = sqlText, onErrorRich = onErrorRich)
        }
    }, com.intellij.openapi.application.ModalityState.any())
}
/**
 * ★A48：结果换入判定（纯函数，[refreshResultsComponent] 每拍调用）——在既有三条判据
 * （强制首换 / 组件实例变化 / 结果页签名称集合变化）之外补**第四判据：已换入组件被 DBN 挂回原生宿主**。
 *
 * 根因（M36.5.43 沙盒实证，见 [swapResultIntoPersistentConsole] KDoc）：DBN 结果区组件的
 * 「长期老家」是 `ServiceViewComponentWrapper`，DBN 在**本次提交新建结果页签**时会把它重新
 * 挂回该宿主（同一实例，仅 parent 变）。原判据只比较**组件身份**（`live !== lastLiveResultSwapped`）
 * 与**页签名称集合**——re-parent 两者都不变，判据恒 false，SplitPane 底部因此滞留空组件，
 * 结果只落在 IDE 底部原生控制台（用户实证「同一会话第一次点执行预览窗内看不到结果，再点一次
 * 才有」）。复用旧页签路径不新建内容、不触发挂父，故不中招——与两次执行日志对照一致
 * （newTab=true 坏 / newTab=false 好）。M36.5.43 声称的「被 DBN 抢走则下拍换回」正是该判据，
 * 但身份去重把它短路成了**一次性换入**。
 *
 * @param host        持久整控台 SplitPane（换入目标；非分栏形态传 null）
 * @param hostBottom  [host] 当前底部组件
 * @param live        本拍从 `ui.getComponent()` 取到的结果组件
 * @param lastSwapped 上一次换入的组件（去重基线）
 * @return true = 本拍需要（重新）换入
 */
internal fun DataSourceSelector.resultSwapNeeded(
    host: java.awt.Component?,
    hostBottom: java.awt.Component?,
    live: java.awt.Component?,
    lastSwapped: java.awt.Component?,
    namesChanged: Boolean,
    forceSwapIn: Boolean,
    alreadySwapped: Boolean,
): Boolean {
    if (live == null) return false
    if (forceSwapIn && !alreadySwapped) return true
    if (live !== lastSwapped) return true
    if (namesChanged) return true
    // 第四判据：身份/名称都未变，但组件已不在本 SplitPane 底部（被 DBN 挂回原生宿主）——
    // 身份去重看不见 re-parent，须按「仍在位」判定，幂等重挂回底部。
    return host != null && (hostBottom !== live || live.parent !== host)
}

/**
 * ★A49：是否组我方整控台 SplitPane——即是否允许把 DBN 结果区组件（`ui.getComponent()`）摘进
 * 本插件容器（[buildConsoleSplitPane] / [swapResultIntoPersistentConsole]）。**只有存在嵌入宿主
 * （onConsole 回调）时才允许。**
 *
 * 无宿主路径（SQL 快捷模板三动作 / 日志转 SQL 的「执行」，二者都走
 * `SqlExecutor.execute(onConsole = null)`）只把语句交给**原生 Database 控制台**执行，控制台由
 * DBN 在 IDE 底部 Services 视图自行展示。此时组出来的 SplitPane **永不展示**（`onConsole?.invoke`
 * 是 no-op，`pendingOnConsole` 也为 null 故 [refreshResultsComponent] 直接早退、无人回收），
 * 摘取结果组件对本路径**无任何收益**，却扰动 DBN 自己的组件树（把结果区组件从原生宿主
 * `ServiceViewComponentWrapper` 摘下、再挂进我方容器）——属 ★A48 的**同族现象**（A48 修的是嵌入
 * 路径上「回收判据看不见 re-parent」，本判据消的是无宿主路径上「本就不该摘」）。
 *
 * **归因更正（2026-10-13）**：本判据最初的立项依据是「录制里原生控制台结果网格停在 `0 rows`」，
 * 后经沙盒控制台 **Output 页原文**查明该现象真因是**调用方传了不存在的表名**——录制脚本的右键
 * 锚点按单宽估算 CJK 列宽、落到 `schema.sql` 注释里的中文词上，动作据其拼出
 * `SELECT … FROM 对齐插件测试`，DBN 正确报 `[42S02][1146] Table 'mpj_demo.对齐插件测试' doesn't exist`，
 * 网格 0 rows 是该报错的**正常表现**，**与插件行为无关**（锚点 x 610→740 修正后重录一次通过，
 * 网格 4 行）。故本判据是排查该现象时**顺带发现并收敛**的隐患，**不是该现象的修复**；保留理由＝
 * 无宿主路径摘取原生组件无收益、有扰动风险，去掉后更安全。
 *
 * 两处组入点（prepare 侧 [prepareAfterEditorResolved]、复用侧 [reuseSharedConsole]）同用本判据；
 * 嵌入/预览路径（`onConsole != null`）的判据在条件化简后与改前**逐字等价**，行为不变。
 *
 * @param hasEmbedHost       本次准备/执行是否带嵌入宿主回调（`onConsole != null`）
 * @param hasResultComponent DBN 结果区组件是否取到（`resultComponent`/`liveResult` != null）
 * @return true = 组 SplitPane 并换入（有宿主）；false = 不组不摘，保持原生控制台渲染
 */
internal fun shouldComposeEmbedSplitPane(
    hasEmbedHost: Boolean,
    hasResultComponent: Boolean,
): Boolean = hasEmbedHost && hasResultComponent

/**
 * T-SqlTemplateUx P2 补：`refreshResultsComponent` 的**组件换入**段是否需要执行——需同时具备
 * 嵌入宿主（`pendingOnConsole`）与持久容器（topBlock）。无宿主时摘 DBN 结果组件无收益、
 * 只扰动其组件树（[shouldComposeEmbedSplitPane] 的 A49 口径）。
 *
 * **错误晚检段不受本判据约束**：无嵌入宿主路径（SQL 快捷模板三动作 / 日志转 SQL 的「执行」，
 * 均 `SqlExecutor.execute(onConsole = null)`）同样需要执行失败反馈——本函数拆两段正是为此，
 * 详见 [refreshResultsComponent] 的 T-SqlTemplateUx P2 补说明。
 */
internal fun refreshSwapEnabled(
    hasEmbedHost: Boolean,
    hasTopBlock: Boolean,
): Boolean = hasEmbedHost && hasTopBlock

/** 读取 RunnerLayoutUi 当前全部结果内容（排除 Output/输出）。 */

internal fun DataSourceSelector.refreshResultsComponent(ui: Any, LOG: com.intellij.openapi.diagnostic.Logger, forceSwapIn: Boolean = false, generation: Int = swapGeneration, onError: ((String) -> Unit)? = null, onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null, sqlText: String? = null) {
    // T-SqlTemplateUx P2 补（真机 2026-10-13 录制实录）：本函数是「结果页签已出现、错误晚到」的
    // **唯一**晚检通道（[startResultTabRetry] 一旦找到结果 Tab 即停、不再扫错误）。此前首行以
    // `pendingOnConsole` 非空判定整体早退，把**无嵌入宿主**路径（SQL 快捷模板三动作 / 日志转 SQL
    // 的「执行」，均 SqlExecutor.execute(onConsole = null)）一并挡在门外——DBN 错误晚于结果页签写入
    // history 时无人检测，执行失败对用户完全静默（画面只剩控制台报错 + 0 rows）。故拆两段：
    // **错误晚检恒跑**（与宿主无关）；**组件换入**用 [refreshSwapEnabled] 收窄（无宿主不摘 DBN 组件，A49 口径）。
    val callback = pendingOnConsole
    val top = sharedTopBlock ?: preparedTopBlock
    val swapEnabled = refreshSwapEnabled(hasEmbedHost = callback != null, hasTopBlock = top != null)
    val shared = sharedConsole
    var ticks = 0
    var swapped = false
    // T-ExplainFirstNative：结果 Tab 名称集合逐拍快照——集合变化（空名占位改名/新 Tab 出现）
    // 说明结果内容更新，即使组件实例未变也要补一次换入重绘（首点网格晚于换入构建的兜底）
    var lastResultNames: Set<String>? = null
    val timer = javax.swing.Timer(600, null)
    timer.addActionListener(object : java.awt.event.ActionListener {
        override fun actionPerformed(e: java.awt.event.ActionEvent) {
            ticks++
            // M36.5.44：代际不匹配 → 新执行已覆盖本 watch，丢弃（防"滞后一轮"互踩）
            if (generation != swapGeneration) {
                timer.stop()
                LOG.info("[M36] results refresh cancelled (generation $generation != ${swapGeneration})")
                return
            }
            // M36.5.57：每拍早检错误（refresh 8 拍窗口覆盖 DBN 错误写入较晚的时机）——
            // 首次执行结果 Tab 复用后 startResultTabRetry 找到 result 即停，但 DBN 把错误
            // 写入 history 可能晚于其时点 → 首次执行漏检（用户"执行两次才出现错误"）。
            // 本 Timer 在此阶段仍运转，每拍检查本次执行新增的错误并上报。
            // T-ExplainConsoleFirst W7：本函数没有 project 参数（结果区刷新的通用组件），
            // 故不传升格所需的工程上下文——跨源列证据在另两处（早检 / 超时兜底）生效。
            extractConsoleError(shared ?: preparedConsole, LOG, sqlText)
                ?.takeIf { it != lastReportedError }?.let { err ->
                    timer.stop()
                    lastReportedError = err
                    LOG.info("[M36] refresh console error detected (tick=$ticks): ${err.take(120)}")
                    // M36.5.70：把错误写入 Output 控制台可视区（DBN 报错时 Output 内容视图 0x0 不显示）
                    // T-WSC-02：writeConsoleOutput 内部是文档写（runWriteCommandAction + insertString），
                    // 且上游是 Swing Timer 拍（Timer 活动同样非用户活动）——必须 write-safe 模态投递，
                    // any() 恒 write-unsafe（见 DocWriteContext KDoc）。
                    ApplicationManager.getApplication().invokeLater({
                        writeConsoleOutput(
                            shared ?: preparedConsole,
                            "$EXEC_FAILURE_ECHO_PREFIX $err\n",
                            isError = true,
                        )
                        // T-ELU-01/03：语句级错误同 [startResultTabRetry] 的早检路径——走富通道收口
                        deliverError(onErrorRich, onError, null, err, sqlText?.length)
                    }, com.intellij.openapi.application.ModalityState.nonModal())
                    return
                }
            // M36.5.13：每拍清提示符（压制执行后重设）
            if (shared != null) clearConsolePrompt(shared, LOG)
            val names = resultContents(ui).map { it.displayName }.toSet()
            val namesChanged = lastResultNames != null && names != lastResultNames
            lastResultNames = names
            val live = runCatching {
                ui.javaClass.getMethod("getComponent").invoke(ui) as? javax.swing.JComponent
            }.getOrNull()
            // ★A48：第四判据（re-parent 回收）参与换入决策——详见 resultSwapNeeded KDoc。
            val host = sharedFullComponent
            val instanceChanged = live !== lastLiveResultSwapped
            val shouldSwap = resultSwapNeeded(
                host = host,
                hostBottom = (host as? javax.swing.JSplitPane)?.bottomComponent,
                live = live,
                lastSwapped = lastLiveResultSwapped,
                namesChanged = namesChanged,
                forceSwapIn = forceSwapIn,
                alreadySwapped = swapped,
            )
            // 仅第四判据为真 = 身份与名称都没变、组件被 DBN 挂回原生宿主 → 本拍是「回收重挂」
            val reclaimed = shouldSwap && !(forceSwapIn && !swapped) && !instanceChanged && !namesChanged
            // 无宿主（swapEnabled=false）时不做换入——错误晚检已在上方独立完成；`top != null` 供 smart-cast
            // （逻辑上 swapEnabled 已蕴含之）。
            if (swapEnabled && top != null && shouldSwap && live != null) {
                // M36.5.41-A4：持久整控台原位换底（不重建 SplitPane，身份稳定）；
                // M36.5.43：swap 助手不再返回 null（强制换入），持久容器缺失时回退
                // buildConsoleSplitPane（feature flag）。
                val full = swapResultIntoPersistentConsole(sharedFullComponent, live, top, LOG)
                val changed = live !== lastLiveResultSwapped
                swapped = true
                lastLiveResultSwapped = live
                sharedFullComponent = full
                preparedFullComponent = full
                val cb = pendingOnConsole
                if (cb != null) {
                    // Timer 回调已在 EDT，直接换入（模态 pump 中 Timer 照常 fire）
                    cb.invoke(full)
                }
                LOG.info("[M36] results refreshed (tick=$ticks, forced=$forceSwapIn, instanceChanged=$changed, namesChanged=$namesChanged, reclaimed=$reclaimed, gen=$generation)")
            }
            if (ticks >= 8) {
                timer.stop()
                LOG.info("[M36] results refresh watch finished (ticks=$ticks)")
            }
        }
    })
    timer.start()
}

/**
 * M36.5.6：结果区换入监听——RunnerLayoutUi 的 ContentManager 上任意结果内容新增
 * （contentAdded，排除 Output 自身）即把宿主面板从「编辑器块」换入「完整 SplitPane」。
 * 覆盖原生 ▶ / 控制台 Ctrl+Enter 等插件外执行路径（此前仅插件 executePrepared 有
 * onResultsReady 换入）。topBlock/resultComponent 闭包自捕获（复用路径会清 prepared*
 * 字段，监听器不可依赖它们）；换入组件懒构建并缓存（顶部块已被内嵌"偷走"，预建实例缺
 * 子组件——M36.5.2 教训）。同实例重复换入由面板 embedConsole 防抖兜底。
 * 待机回调 [pendingOnConsole] 由创建面板设置、复用面板接管（面板可能已关闭重建）。
 */

internal fun DataSourceSelector.installOutputHistoryFillLayout(root: java.awt.Component, log: com.intellij.openapi.diagnostic.Logger) {
    runCatching {
        // M36.5.76：搜索范围收紧到**结果区（bottom）**——M36.5.75 实证缺陷：从整个 SplitPane
        // 深搜时第一个命中的 PWT 是 topBlock 顶部编辑器（同为 EditorImpl$PanelWithFloatingToolbar），
        // 从它向上找不到 EMHSBL 面板而整体 skip（沙盒 68 次 `not found; skip`，0 次安装）。
        val sp = root as? javax.swing.JSplitPane
        val searchRoot: java.awt.Component = sp?.bottomComponent ?: root
        if (searchRoot !is java.awt.Container) return
        // 1) 定位结果区内全部 Output 历史编辑器（类名精确匹配，结果网格类名不同绝不误伤）
        val targets = ArrayList<java.awt.Component>()
        fun findAll(c: java.awt.Component, depth: Int) {
            if (targets.size >= 4 || depth > 24) return
            if (c.javaClass.name.contains("EditorImpl\$PanelWithFloatingToolbar")) { targets.add(c); return }
            if (c is java.awt.Container) for (d in c.components) findAll(d, depth + 1)
        }
        findAll(searchRoot, 0)
        if (targets.isEmpty()) {
            log.debug("[M36][OUTFILL] no output editor (EditorImpl\$PanelWithFloatingToolbar) in result area; skip")
            return
        }
        var installedAny = false
        for (t in targets) {
            // 幂等：直接父层已装过则跳过（重复 realign/预览+执行双路径）
            if (t.parent?.layout is FillTargetLayout) continue
            // 2) 从 target 直接父层向上收集容器链，锚定到 EMHSBL 布局面板（含，contains 匹配兼容子类）
            val chain = ArrayList<java.awt.Container>()
            var anchored = false
            var cur: java.awt.Container? = t.parent
            while (cur != null) {
                chain.add(cur)
                if (cur.layout?.javaClass?.name?.contains("EditorMergedHorizontalScrollBarLayout") == true) {
                    anchored = true
                    break
                }
                if (cur === searchRoot) break
                cur = cur.parent
            }
            if (!anchored) {
                // M36.5.76：诊断——祖先链一次性打点（节流 2s），定位 Output 编辑器真实上层结构
                val now = System.currentTimeMillis()
                if (now - lastOutFillDiagAt > 2000) {
                    lastOutFillDiagAt = now
                    val chainDesc = StringBuilder()
                    var p: java.awt.Container? = t.parent
                    var lvl = 0
                    while (p != null && lvl < 10) {
                        chainDesc.append("\n  ↑$lvl ${p.javaClass.name} layout=${p.layout?.javaClass?.name} bounds=${p.bounds}")
                        if (p === searchRoot) break
                        p = p.parent; lvl++
                    }
                    log.debug("[M36][OUTFILL] EMHSBL panel not found above output editor; ancestors=$chainDesc")
                }
                continue
            }
            // 3) 逐层安装填充布局（跳过 JScrollPane——ScrollPaneLayout 由滚动机制管理，不可替换）
            var child: java.awt.Component = t
            for (c in chain) {
                if (c is javax.swing.JScrollPane) {
                    log.debug("[M36][OUTFILL] chain node is JScrollPane (${c.javaClass.name}), keep its layout")
                } else if (c.layout !is FillTargetLayout) {
                    val oldLayout = c.layout?.javaClass?.name ?: "null"
                    val before = child.bounds
                    c.layout = FillTargetLayout(child)
                    c.revalidate()
                    log.debug("[M36][OUTFILL] installed on ${c.javaClass.name} (was $oldLayout) target=${child.javaClass.simpleName} before=$before")
                    installedAny = true
                }
                child = c
            }
        }
        if (installedAny) {
            searchRoot.revalidate()
            searchRoot.repaint()
        }
    }.onFailure { e ->
        log.warn("[M36][OUTFILL] install failed: ${e.message}")
    }
}

/**
 * M36.5.75：目标子组件填充布局——[target] 占满容器可用高度（可见 [javax.swing.JScrollBar]
 * 占底部条位），其余子组件高度 0（Output 内的输入编辑器是重复编辑表面，用户在 topBlock
 * 编辑 SQL）。每次 validate 自动重跑（幂等），不存在一次性 setSize 被压回的问题；
 * 自证日志节流（每实例前 3 次 target 高度修正打点，教训 11）。
 */

internal fun DataSourceSelector.createTempPsiFile(
    project: Project,
    sql: String,
    renderedDialectName: String? = null,
): Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? {
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
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
    val vf = try {
        com.intellij.openapi.application.WriteIntentReadAction
            .compute<com.intellij.openapi.vfs.VirtualFile?> {
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
 * T-PreviewExecDs-02 F1：消费 exec 临时文件 vf+psi 缓存——可用（归属当前项目且 vf 有效）
 * 返回配对，否则清缓存返回 null（走冷路径重建）。抽 internal 供跨项目隔离测试直测。
 * 跨项目泄漏实锤见开发计划 §2.2：旧实现只查 vf.isValid，在 B 项目执行时返回 A 项目的
 * vf+psi → runner 按 A 项目 facade 枚举数据源 → 弹窗显示另一项目数据源 → console 因
 * facade 找不到该数据源被 getActiveConsoles 清扫 → "parent has already been disposed"。
 */

internal fun DataSourceSelector.projectExecFile(project: Project, sql: String): java.io.File? {
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
    // T-UiFixGate9：生命周期守卫——dispose（含 temporarily-disposed）后放弃整个写链。首建形态
    // （VFS 无条目）的裸写 FileUtil.writeToFile 不经 document、无任何 dispose 检查，死项目上照写
    // 磁盘即第二冲突源的一部分；返回 null 走既有「writeFile 失败 → 跳过」降级。
    if (project.isDisposed) {
        LOG.info("[M36] projectExecFile abandoned (project disposed, T-UiFixGate9): project=${project.name}")
        return null
    }
    val base = project.basePath ?: run {
        // T-ExecCopyFix F2a：basePath null 是「无异常的 null」，原来连日志都没有
        LOG.warn("[M36] projectExecFile FAILED: project.basePath null (project=${project.name})")
        return null
    }
    val dir = java.io.File(base, ".idea${java.io.File.separator}${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}${java.io.File.separator}exec")
    if (!dir.exists() && !dir.mkdirs()) {
        // T-ExecCopyFix F2a：mkdirs 返回值原来被忽略，目录建不起来后写盘抛异常再被吞
        LOG.warn("[M36] projectExecFile FAILED: mkdirs false (${dir.path})")
        return null
    }
    val f = java.io.File(dir, "mpsql-exec.sql")
    synchronized(execFileWriteLock) {
        val existing = LocalFileSystem.getInstance().findFileByIoFile(f)
        if (existing != null) {
            // T-PreviewFast P1：**差量快路径**——派发前先判当前内容是否已等于目标 SQL。相同则
            // 直接返回，不派发、不落盘、不等 EDT（连续预览同一 SQL 是常见形态：真机日志 14:17
            // 五连预览每次都白付 2s 派发等待 + 一次无效落盘 + 一轮索引噪音）。
            // 判据双源：document 文本（有已加载文档时，UI 所见为准）或 vf 内容（无文档时）。
            if (execFileContentMatches(project, existing, sql)) {
                LOG.info("[M36] exec file content already matches — write SKIPPED (T-PreviewFast P1): ${f.name}")
                return f
            }
            // T-EC-01/T-EC-03：document 写统一派 EDT（EDT 直调同步；BGT 派发 + 有界等待）。
            // 262 实证 document setText BGT 硬抛（见 writeVfTextViaDocument KDoc）——旧实现
            // 的 BGT document 分支每次必炸、静默掉进裸写兜底，即真机冲突链本体。
            // T-UiFixGate9：fire-and-forget 形态退役（原 T-PreviewFast P2 的 nonBlocking=true）——
            // 排队写与「本链自己的 refresh 会话 / 测试边界迟到的 VFS 会话」赛跑：写任务的
            // setText→save 窗口（探针实测 ~30ms）被会话的旧内容/建条目事件命中即收集冲突，
            // EDT 下一拍处理即抛（20261018 门禁三轮实录 + [PROBE-GATE9] 探针实锤）。有界等待让
            // 写收敛始终在本链执行窗口内完成，调用方（预热链）拿到返回后才发起 refresh；
            // 真机代价不变（EDT 健康 → 毫秒级返回），2s 预算只在 EDT 病态冻结时烧掉，而那正是
            // 必须放弃写/refresh 的场景（dispose 守卫接手）。
            val updated = dispatchDocWriteOnEdt(project, existing, sql)
            if (updated) {
                LOG.info("[M36] exec file updated via document+save (disk synced in-critical-section): ${f.name}")
                return f
            }
            // T-ExecDocSync3（第四轮真机复现的收口，20260917 真实 IDE 日志实证）：**VFS 已有
            // 该文件时一律不裸写盘**。原 T-EC-02 兜底守卫查 getCachedDocument==null 就放行裸写，
            // 实机证伪——EDT 忙（冷启动/索引，日志同刻可见 DumbService enter dumb mode）时
            // dispatch 有界等待 2s 超时返回 false，此刻「排队的 EDT 写任务」尚未执行、document
            // 也还没建，getCachedDocument 恒 null；随之而来的裸写先改盘（12,590），排队任务
            // 随后 ExecutionException 建出 document（resolver 日志 oldFileStamp:0 = 文档建于文件
            // 写入之前）并标脏，裸写引发的 VFS 变更落到这个刚建的文档上 →
            // MemoryDiskConflictResolver「重载 mpsql-exec.sql？」弹窗（12,609），23ms 后文档写入
            // 才自己落盘（12,613）。即**插件与自己的排队写赛跑**，而非外部改盘。
            // 宁内容旧一拍：排队任务/later 的 console doc 同步链（attachConsole 后的
            // prepareAfterEditorResolved、executeOnConsole 执行前 T-EC-01 同款同步）都会重写并
            // 落盘，执行内容不受影响。附带收益：冷启动 EDT 忙时不再有裸写 → 该场景彻底无冲突面。
            LOG.warn(
                "[M36] exec doc-write not completed (EDT dispatch timeout/WriteAction failure); " +
                    "raw write SKIPPED to avoid File Cache Conflict (T-ExecDocSync3): ${f.name}",
            )
            return f
        }
        // VFS 无该条目（首次创建）：不可能存在 document（无 vf 可挂），裸写安全
        FileUtil.writeToFile(f, sql)
        return f
    }
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

/** T-PerfSweep2-04：已挂项目关闭钩子的项目集——防对同一项目重复 Disposer.register。 */

internal fun DataSourceSelector.buildConsoleSplitPane(
    topBlock: javax.swing.JComponent,
    resultComponent: javax.swing.JComponent,
): javax.swing.JComponent {
    // M36.5.55：给 top（编辑器块）设最小高度兜底——JSplitPane 初始 divider 按 preferred 推算，
    // 编辑器块（BorderLayout 包装 editorComponentRaw）preferred 可能为 0（未布局），导致 top
    // collapse 到 0 高（沙盒实证 topH=0，setDividerLocation(0.6) 也撑不起来）。minimumSize
    // 让 JSplitPane 至少给编辑器块留 260px，不被压塌。
    topBlock.minimumSize = java.awt.Dimension(topBlock.minimumSize?.width ?: 400, 260)
    // M36.5.64：给底（结果区）设最小高度兜底——DBN 结果区（RunnerLayoutUi grid）preferred=84px
    // （只够 Output 一行），re-parent 后 JSplitPane 每次重排都会把 bottom 拉回 preferred
    // （沙盒实证 `topH=569 total=656`=86%，bottom=84px）。minimumSize 让 JSplitPane 无法把
    // 结果区压到 84px，至少留 160px，配合 realignSplitDivider 双向校正 60%，结果网格不再塌缩。
    resultComponent.minimumSize = java.awt.Dimension(
        resultComponent.minimumSize?.width ?: 400, 160,
    )
    return object : javax.swing.JSplitPane(
        javax.swing.JSplitPane.VERTICAL_SPLIT, topBlock, resultComponent,
    ) {
        override fun paint(g: java.awt.Graphics) {
            super.paint(g)
            g.color = com.intellij.util.ui.UIUtil.getPanelBackground()
            g.fillRect(0, getDividerLocation(), width, getDividerSize())
        }
    }.apply {
        // M36.5.25f：编辑器 60% / 结果区 40%——原 0.4 导致 SQL 区域只占 40%（窗口放大时
        // 额外空间 60% 给结果区，SQL 被压扁）；0.6 让编辑器主导高度，随窗口自适应。
        resizeWeight = 0.6
        isContinuousLayout = true
        isOneTouchExpandable = false
        dividerSize = 3
        border = com.intellij.util.ui.JBUI.Borders.empty()
        background = com.intellij.util.ui.UIUtil.getPanelBackground()
        // M36.6.2：分隔位置保险（每个实例自带，覆盖 prepare/reuse/contentAdded 重建全部路径）——
        // 构造时子组件 0x0 invalid（沙盒日志 toString 实证），首次真实布局的 divider 由 preferred
        // 尺寸推算，落点随构造时序漂移（用户三张截图：编辑器塌缩 ~40px / 编辑器截断无结果区 /
        // 结果区仅剩窄条，每次打开形态不同）。首触获得真实高度后无条件强制 60%，一次性监听，
        // 不干预用户后续拖拽。
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                if (height < 200) return
                removeComponentListener(this)
                setDividerLocation(0.6)
                revalidate()
                repaint()
            }
        })
    }
}

/**
 * M36.5.41-A4：持久整控台——结果刷新时**原位换底**（bottomComponent 指向新结果实例），
 * 不再重建 SplitPane。重建会让面板侧 embedConsole 身份漂移（sameAsPrev=false，方案 A 判据
 * 失效），且旧 SplitPane 反复摘挂正是「右侧空白/显示链断裂」的来源（M36.5 根因级问题）。
 * 顶部块（原生头部工具栏+编辑器）自构建起不再摘动；仅当整控台容器缺失（full 非分栏）时
 * 回退 [buildConsoleSplitPane]（feature flag 语义，可回退旧路径）。
 *
 * M36.5.43 修正守卫：原实现"liveResult.parent != null 且 != sp → 跳过本拍"过度保守——
 * 沙盒日志实证 `parent=ServiceViewComponentWrapper`（DBN 的服务视图宿主，结果的**长期老家**，
 * 非 DBN 临时 reparent 中转），守卫把它误判为"临时持有"而**每拍跳过**，结果区永远进不了
 * 面板（16:05 日志 `result component held by DBN ... skip this tick` 连续数十拍）。
 * 正确语义：只要 liveResult 是当前结果实例且不在本 SplitPane（parent !== sp），就**强制换入**
 *（从 ServiceView 摘出挂回面板）——DBN 的 ServiceView 是它"默认展示"宿主，方案 A 下结果
 * 属于面板，我们保持不变式 SplitPane.bottom == liveResult。换入后 parent 必为 sp；被 DBN
 * 抢走则下一拍再换回（幂等）。
 */

internal fun DataSourceSelector.dispatchDocWriteOnEdt(
    project: Project,
    vf: com.intellij.openapi.vfs.VirtualFile,
    sql: String,
    waitBudgetMs: Long = EDT_EXEC_DOC_SYNC_WAIT_MS,
): Boolean {
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) {
        // T-PreviewEdtGuard S2：EDT 直调同样过写安全闸——R33 三验真机日志实证 2 组
        // Write-unsafe context（帧=writeVfTextViaDocument）：write-unsafe EDT 活动（any() 投递 /
        // 「写不安全进入」的模态泵）里 WriteAction.compute 并不 confer 写安全资格（T-WriteSafeCtx
        // 平台源码定例：「写入合法性与是否在 WriteCommandAction 内无关」），commitDocument 的
        // PSI 事件链（PsiDocumentManagerBase 为平台断言点之一）即报 Write-unsafe context!。
        // 本分支是 [writeVfTextViaDocument] 调用面里唯一未过 DocWriteContext 闸的入口
        // （本函数另两形态已闸；471/1699/2776 三处直调各自包 WriteAction，R33 日志无实证指向）。
        // 判为可写 → 就地写（常态行为逐字不变）；判为不安全 → 转 nonModal 延后一拍并返回
        // false（写体 docWriteOnce 幂等：内容相同不 setText、已落盘不 save；现有调用方——
        // gStrip 回写不消费返回值、syncExecVfContentIfNeeded 各调用方亦不消费——延后语义安全，
        // 与下方有界等待路径「判不安全立即交还」同一纪律）。
        var ok = false
        DocWriteContext.runWriteSafe(project, "exec doc sync (edt direct)") {
            ok = docWriteOnce(project, vf, sql)
        }
        return ok
    }
    if (waitBudgetMs <= 0L) {
        // project 即 owner disposable：项目关闭后丢弃该拍（写目标就是项目内临时文件，重跑无意义）
        // T-UiFixGate9：本分支产品面已无调用方（fire-and-forget 退役，见 projectExecFile 内注释）
        // ——排队写与 VFS 会话赛跑收集冲突，**勿为预热链重新启用**；分支保留仅为 API 完整。
        DocWriteContext.invokeLaterWriteSafe(project, "exec prewarm doc write (fire-and-forget)") {
            runCatching { docWriteOnce(project, vf, sql) }
        }
        LOG.info("[M36] exec doc write dispatched fire-and-forget (T-PreviewFast P2)")
        return true
    }
    val done = java.util.concurrent.CountDownLatch(1)
    var ok = false
    app.invokeLater {
        try {
            DocWriteContext.runWriteSafe(project, "exec doc sync (bounded)") {
                ok = docWriteOnce(project, vf, sql)
            }
        } finally {
            done.countDown()
        }
    }
    val signaled = runCatching {
        done.await(waitBudgetMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }.getOrDefault(false)
    if (!signaled) {
        LOG.warn("[M36] exec doc write EDT dispatch timeout after ${waitBudgetMs}ms (EDT busy?)")
    }
    return signaled && ok
}

/** document 写单次尝试：WriteAction 内 [writeVfTextViaDocument]；失败原因必达日志（旧 getOrDefault 吞 Throwable 的教训）。 */

internal fun DataSourceSelector.swapResultIntoPersistentConsole(
    full: javax.swing.JComponent?,
    liveResult: javax.swing.JComponent,
    topBlock: javax.swing.JComponent,
    LOG: com.intellij.openapi.diagnostic.Logger,
): javax.swing.JComponent {
    val sp = full as? javax.swing.JSplitPane
        ?: return buildConsoleSplitPane(topBlock, liveResult)
    // ★A49 取证修正：换入**前**记录结果组件的真实宿主。旧日志在 `sp.bottomComponent = liveResult`
    // 之后才读 parent，恒打印本 SplitPane，证明不了「有没有从 DBN 宿主摘走」。改在改动前取，
    // 并用**全类名**（判别力所在）：`…ServiceViewComponentWrapper` = 仍在 DBN 原生宿主；
    // `…DataSourceSelectorResultsKt$buildConsoleSplitPane$1` = 已在我方容器。
    val prevParent = liveResult.parent
    val current = sp.bottomComponent
    if (current === liveResult && liveResult.parent === sp) {
        // M36.5.45：同实例也要强制重绘——DBN 复用同一 Content 组件（DataGrid），实例不变
        // 但内容已更新（如重复查询同一表），不重绘则用户仍看到上一个 SQL 结果（"执行 A
        // 仍显示 B"沙盒实证 17:04:41 `instanceChanged=false`）。repaint 触发 DBN 重渲染。
        // M36.5.46：同步校准 divider——同实例换入时 DBN 可能已 reparent/重挂，divider 落点
        // 漂移到顶部（编辑器被压缩，用户截图"执行 A 后布局塌缩"实证）。布局初无尺寸不强改，
        // 下一拍（尺寸就绪）再强制 60%。force=true：执行换入覆盖预览折叠的 divider=1.0。
        // M36.5.64：同实例路径也设 bottom 最小高度——否则 JSplitPane 重排时把结果区压回
        // preferred(84px)，结果网格塌缩（沙盒 topH=569 total=656 实证）。
        liveResult.minimumSize = java.awt.Dimension(
            liveResult.minimumSize?.width ?: 400, 160,
        )
        realignSplitDivider(sp, force = true)
        sp.revalidate()
        sp.repaint()
        return sp
    }
    // 强制换入：从 DBN 宿主（ServiceView 等）摘出，挂回本 SplitPane 底部。
    if (current != null) sp.remove(current)
    // ★A48：显式从**外部宿主**摘出——DBN 在新建结果页签时会把结果组件重新挂回
    // ServiceViewComponentWrapper（同一实例、仅 parent 变），不摘会让组件继续留在原生宿主
    // （预览窗内空白、结果只出现在 IDE 底部原生控制台）。parent 为 sp/无父时本行 no-op。
    (liveResult.parent)?.takeIf { it !== sp }?.remove(liveResult)
    sp.bottomComponent = liveResult
    // M36.5.64：换入路径也设 bottom 最小高度——防 JSplitPane 重排时把结果区压回 preferred。
    liveResult.minimumSize = java.awt.Dimension(
        liveResult.minimumSize?.width ?: 400, 160,
    )
    // M36.5.63：取证 liveResult 运行时类型 + 其内部是否 JSplitPane（RunnerLayoutUi 结果页内部
    // sub-split 塌缩是 "Restore Default Layout 修复" 的根源——内外层两个 SplitPane 嵌套时，
    // 外层 setDividerLocation 只调外层，内层（结果页 Output/结果/消息）塌缩仍显示 Output 一行）。
    runCatching {
        LOG.info("[M36] liveResult class=${liveResult.javaClass.name}, hasSplit=${liveResult is javax.swing.JSplitPane}")
        if (liveResult is javax.swing.JSplitPane) {
            LOG.info("[M36] liveResult divider=${liveResult.dividerLocation} orientation=${liveResult.orientation} top=${liveResult.topComponent?.javaClass?.simpleName} bottom=${liveResult.bottomComponent?.javaClass?.simpleName}")
        }
    }
    realignSplitDivider(sp, force = true)
    sp.revalidate()
    sp.repaint()
    LOG.info("[M36] persistent console bottom swapped (result id=${System.identityHashCode(liveResult)}, prevParent=${prevParent?.javaClass?.name ?: "null"}, fromExternalHost=${prevParent != null && prevParent !== sp})")
    return sp
}

/** M36.5.52/53：校准整控台 SplitPane 分隔——[force]=true（执行换入）时**无条件**拉回 60%
 *（覆盖预览折叠的 1.0）；[force]=false（componentListener 兜底）时仅当顶部<35% 才校正。
 * 调用方：执行结果换入（force=true）、预览折叠后布局漂移兜底（force=false）。 */

internal fun DataSourceSelector.installExecConflictProbe(project: Project) {
    if (!conflictProbeProjects.add(project)) return
    project.messageBus.connect(project).subscribe(
        com.intellij.openapi.fileEditor.FileDocumentManagerListener.TOPIC,
        object : com.intellij.openapi.fileEditor.FileDocumentManagerListener {
            override fun beforeFileContentReload(
                vf: com.intellij.openapi.vfs.VirtualFile,
                document: com.intellij.openapi.editor.Document,
            ) {
                if (!isExecVfPath(vf.path)) return
                val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                    .warn(
                        "[T-ExecDocSync2][conflict-probe] reload-with-unsaved-doc: ${vf.path}" +
                            ", docLen=${document.textLength}, docStamp=${document.modificationStamp}" +
                            ", vfLen=${vf.length}, vfStamp=${vf.modificationStamp}" +
                            ", unsaved=${runCatching { fdm.isDocumentUnsaved(document) }.getOrNull()}",
                    )
            }
        },
    )
}

/**
 * M36.5.12：项目内执行文件路径——`<projectBase>/.idea/sql-visualizer/exec/mpsql-exec.sql`
 * （F-44-4：目录随插件更名，旧名 mybatis-plus-sql-visualizer 不再使用）。
 * 目录不存在则创建；文件写入 SQL（覆盖）。与 sql-history 同根（.idea/sql-visualizer/），
 * 但**独立子目录** exec（sql-history 读取器只扫 sql-history 子目录，互不污染）。
 *
 * M36.5.13/M36.5.43：VFS 已知文件走 document 驱动写（历史：裸 FileUtil.writeToFile 覆盖
 * 磁盘触发 "File Cache Conflict" 模态弹窗，MemoryDiskConflictResolver，沙盒 12:08:06/
 * 16:05:39 两次实证）。T-ExecDocSync 三点收口（同一问题族第三轮）：
 *  ① **同临界区落盘**（T-EC-01，[writeVfTextViaDocument]）——补 saveDocument，磁盘即时
 *     与内存一致，不再留「已修改未保存」窗口（M36.5.43 只改内存、磁盘靠自动保存追赶，
 *     窗口期内任何外部改盘即弹冲突窗，easy-code-demo 真机截图实证）；
 *  ② **document 写统一派 EDT**（[dispatchDocWriteOnEdt]，DialectPreMapping 两段式同款）——
 *     262 平台文件 document 的 setText 包 CommandProcessor BGT 硬抛 EDT-only（javap+沙盒
 *     堆栈双实证）：旧实现 BGT 预热的 document 分支每次必炸、静默掉进裸写兜底 = 真机
 *     File Cache Conflict 链本体。BGT 预热自此不再降级；
 *  ③ **裸写兜底守卫**（T-EC-02，[canFallBackToRawWrite]）——document 写失败（含注入缝）
 *     时只有无已加载 document 才允许裸写盘；有 document 一律抛 writeConflictGuard 拒写
 *     （宁内容旧一拍，执行前 console doc 同步链会重写并落盘，执行内容不受影响），
 *     失败原因 Throwable 必达日志（原 getOrDefault(false) 吞掉）；
 *     → **T-ExecDocSync3 退役**（第四轮真机日志实证判据不可靠，见方法内注释）：改为
 *     「VFS 已有该文件即绝不裸写」，冷启动 EDT 忙场景彻底无冲突面；
 *  ④ **单写方锁**（T-EC-03，[execFileWriteLock]）——串行化 BGT 预热与 EDT 冷路径。
 * VFS 缺条目路径维持裸写 + 调用方 refresh + [syncExecVfContentIfNeeded] 防御收口（T-EC-04）。
 * T-PreviewFast P1：差量快路径（内容已一致即返回）。P2 非阻塞派发（`nonBlocking=true` 的
 * fire-and-forget）已由 T-UiFixGate9 退役——排队写与 VFS 会话赛跑收集冲突，见方法内注释。
 * internal 供测试。
 */

internal fun DataSourceSelector.realignDividerOnce(sp: javax.swing.JSplitPane, force: Boolean) {
    if (sp.height < 100) return   // 布局初无尺寸，等下次
    val topH = sp.topComponent?.height ?: 0
    val total = sp.height
    val log = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
    val tooAuto = force || topH < total * 0.35 || topH > total * 0.68
    if (tooAuto) {
        log.debug("[M36] divider realign(force=$force): topH=$topH total=$total → 0.6")
        sp.setDividerLocation(0.6)
        sp.revalidate()
        sp.repaint()
        dumpSplitBounds(sp, log)
    } else {
        log.debug("[M36] divider OK: topH=$topH total=$total (no change)")
    }
    // M36.5.75：布局级修复 Output 控制台 44px 塌缩（替代 M36.5.72/73 的 fillOutputConsole
    // 一次性 setSize——被平台 EditorMergedHorizontalScrollBarLayout 每轮 layout 压回，必然失效）
    // write-safe: UI-only（纯 Swing/只读，无文档写）
    ApplicationManager.getApplication().invokeLater({
        installOutputHistoryFillLayout(sp, log)
    }, com.intellij.openapi.application.ModalityState.any())
}

/**
 * M36.5.75：Output 控制台「有内容但只显示 2 行（44px）」的根因修复——**布局替换**，非一次性 setSize。
 *
 * 根因（javap 反编译沙盒 IU-2024.3.7.1 `lib/app-client.jar` 铁证）：
 * `LanguageConsoleImpl.myPanel`（ConsoleEditorsPanel）的布局是平台私有类
 * `com.intellij.execution.console.EditorMergedHorizontalScrollBarLayout`——面板竖直二分：
 * 上=historyViewer（Output 历史编辑器，`EditorImpl$PanelWithFloatingToolbar`）、下=输入编辑器
 * （consoleExecutionEditor，其组件已被我们收养进 topBlock）。`layoutContainer` 的高度分配
 * **输入编辑器优先**：容器高度不足时输入编辑器拿 preferred，history 被压到
 * `getMinHistoryLineCount() × lineHeight`（字节码硬编码 `iconst_2` = 2 行 × 22px = **44px**；
 * 沙盒 [BOUNDS] 实证 consoleImpl=864x235 满高而 PanelWithFloatingToolbar=44、pref=1122）。
 * 平台唯一豁免分支「输入编辑器不可见 → history 占满整个容器」走不到——输入编辑器组件
 * 在我们 topBlock 中可见（isVisible 与挂载位置无关）。这同时解释了 M36.5.72/73 的一次性
 * setSize 为何必然失效：该布局管理器每轮 layout 都会重算并压回。
 *
 * 修复：在结果区（bottom）内定位 Output 历史编辑器（类名精确匹配
 * `EditorImpl$PanelWithFloatingToolbar`——结果网格的 JBScrollPane/DataGrid/TableResultView
 * 类名不同，绝不误伤，M36.5.69 教训），向上收集到 EMHSBL 布局面板（含）为止的容器链，
 * 逐层安装 [FillTargetLayout]（链上目标子组件占满、其余子组件 0 高、可见 JScrollBar 保底条）。
 * 布局管理器随每次 validate 重跑，无"被下一布局周期压回"的失效模式；逐层自证日志（教训 11）。
 */

internal fun DataSourceSelector.extractConsoleError(
    console: Any?,
    LOG: com.intellij.openapi.diagnostic.Logger,
    /** T-ExplainConsoleFirst W7：被执行 SQL——用于 `Unknown column` 的跨源升格判定（null = 不判定）。 */
    sqlText: String? = null,
    /** T-ExplainConsoleFirst W7：升格判定要枚举在籍数据源（null = 不判定）。 */
    project: Project? = null,
): String? {
    if (console == null) return null
    return runCatching {
        val text = newConsoleText(console) ?: return null
        if (text.isBlank()) return null
        val lines = text.lines().filter { it.isNotBlank() }
        // 从末尾往前找"命中错误判据"且"非纯 SQL / 非本插件自写回显"的行
        val errorLine = lines.lastOrNull { line ->
            isErrorSignatureLine(line) && !isSqlLine(line) && !isOwnEchoLine(line)
        } ?: run {
            // T-CEC-01：未命中即取证——记下本次新增文本的尾部（有界 + 每次执行只记一次），
            // 下次扩判据时以真机报文为准，不照用户截图猜形态。
            logUnrecognizedConsoleTail(lines, LOG)
            return null
        }
        // 取错误行及其后非空行构成可读上下文——错误消息常跨多行（MySQL 语法错把 "near …" 的
        // SQL 片段跟在其后）。T-CEC-02：4 行放宽到 8 行，并剥掉逐行的 `[时间戳] ` 与提示符。
        val prompt = consolePrompt(console)
        val idx = lines.indexOf(errorLine)
        val excerpt = lines.subList(idx, (idx + EXCERPT_MAX_LINES).coerceAtMost(lines.size))
            .joinToString("\n") { stripConsolePrefix(it, prompt) }
        // T-ConsoleDbContext A：附「当前库 / 数据源 / 数据源默认库」上下文——真实事故中 SQL 本身
        // 没问题，是执行落在了错的库（mpj_demo 的 user 表没有 username → 1054），而裸 MySQL 报文
        // 看不出这一层；带上上下文后一眼可归因
        val ctxLine = ExecConsoleContext.describeContext(console)
        // T-ExplainConsoleFirst W7（2026-10-24 用户实测 `Unknown column 'first_name' in 'field list'`
        // ＋上下文三处全是 mp_demo）：源没选错、错的是「SQL 与库」这一对——`first_name` 属 jpa_demo
        // 的同名 user 表，而 mp_demo 的 user 没有该列。既有分类把 `Unknown column` 一律排除在
        // WRONG_TARGET 外（「列级问题换源解决不了」），对拼错列名成立，对「同名表不同列集」漏判。
        // 这里按证据升格：当前源确证无此列 ∧ 另一在籍源的同名表带此列 → 明说「换个源能解决」。
        // 纯加法：判据不成立（缓存未热/任何源都没有/当前源其实有）一律不附加，报错原文不变。
        val crossSourceHint = runCatching {
            val sqlForEvidence = sqlText?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val dataSources = DatabaseSchemaResolver.allDataSourcesForEvidence(project) ?: return@runCatching null
            // 反射取控制台数据源（`JdbcConsole.getDataSource()`，与 consoleDataSourceId 同口径）——
            // 本文件在 Community 下也会加载，不得直接引用 DBN 类。
            val current = runCatching {
                console.javaClass.getMethod("getDataSource").invoke(console)
            }.getOrNull() ?: return@runCatching null
            val currentIdx = dataSources.indexOfFirst { it === current }
            if (currentIdx < 0) return@runCatching null
            val sources = DatabaseSchemaResolver.columnNameSourcesCached(dataSources)
            val evidence = CrossSourceColumnEvidence.assess(
                errorText = excerpt,
                sql = sqlForEvidence,
                currentSourceColumns = sources[currentIdx],
                otherSourcesColumns = sources.filterIndexed { i, _ -> i != currentIdx },
            ) ?: return@runCatching null
            LOG.info("[W7] cross-source column evidence hit: table=${evidence.table}, column=${evidence.column}")
            MpSqlVisualizerBundle.message("execute.error.crossSourceColumn", evidence.column, evidence.table)
        }.getOrNull()
        val enriched = listOfNotNull(excerpt, ctxLine, crossSourceHint).joinToString("\n")
        LOG.info("[M36] extracted console error: ${enriched.take(200)}")
        enriched
    }.getOrNull()
}

/**
 * T-CEC-03：兜底呈现——判据认不出的失败，把控制台**新增文本去掉本次 SQL 回显后的残余**交回
 * 面板，而不是给「SQL 已提交执行，但等待结果超时……检查密码输入框/测试连接」这类笼统提示
 * （真机 P15 实证：把一条 MySQL 语法错归因成密码/驱动问题，归因方向是错的）。
 *
 * 只做「减去回显」不做任何语义判断：残余是什么就报什么，面板侧附一句如实说明
 * （`execute.error.unknownShape`）——不声称它是 SQL 错误，也不猜原因。
 *
 * 剔除规则（宁少勿错）：空行；DBN 回显行（剥掉时间戳/提示符后是本次执行 SQL 的子串）；
 * DBN 状态行（Connected / completed in … 等）。全部被剔完返 null（调用方落回既有提示）。
 */

internal fun DataSourceSelector.runRunner(
    project: Project,
    runner: Any,
    silent: Boolean = false,
    onError: ((String) -> Unit)? = null,
    /** T-ModuleScopedLastUsed：失败清理只清该模块那一层（不传＝既有项目级行为）。 */
    moduleKey: String? = null,
) {
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
    // 空操作 Runner（平台组项的 EmptyRunnable）不能执行：run() 立即返回且永不回调 session，
    // 只会把准备门拖到 60s 超时。立即结算错误，让面板红字给出可行动原因。
    if (ExecDialectRouting.isNoOpRunner(runner)) {
        val name = getRunnerName(runner)
        LOG.warn("[M36] runner '$name' has EmptyRunnable; refusing to run (would wait for prepare timeout)")
        onError?.invoke(MpSqlVisualizerBundle.message("execute.datasource.noRunnable", name))
        return
    }
    runCatching {
        val method = runner.javaClass.getMethod("run")
        method.isAccessible = true
        method.invoke(runner)
        LOG.info("[M36] runRunner invoked OK")
    }.onFailure { e ->
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        LOG.warn("[M36] runRunner FAILED: ${cause.javaClass.simpleName}: ${cause.message}", cause)
        // T-DsCover：预览默认准备路径静默失败（被动展示不应弹模态打断）；
        // 并清 last-used 记忆，避免驱动损坏的数据源每次预览都白失败一次。
        // T-Perf-01：失败同时回调 onError（面板行内红字 + 「执行中」态恢复），静默与非静默路径都回调。
        val failMsg = MpSqlVisualizerBundle.message("execute.datasource.failed", cause.message ?: cause.toString())
        onError?.invoke(failMsg)
        if (silent) {
            // T-SI-02：两个键一起清（只清旧键会留下指认另一个数据源的 UUID 身份）
            // T-ModuleScopedLastUsed：带上模块键，只清该模块层（项目级兜底不动）
            forgetLastRunner(project, moduleKey)
            return
        }
        // 驱动缺失/连接失败等：提示用户到数据源设置解决（原生 Download Driver 弹窗由 Database 插件触发）
        // T-UiNative T-UN-02：改 ERROR 轻通知（原模态错误框——失败仍醒目，但不打断）；
        // 测试模式跳过 + runCatching 兜底（对齐 CopyExecutableSqlAction.showToast 约定）
        if (!com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode) {
            runCatching {
                com.intellij.notification.Notification(
                    NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("execute.title"),
                    failMsg,
                    com.intellij.notification.NotificationType.ERROR,
                ).notify(project)
            }
        }
    }
}

/**
 * 弹窗展示 Runner 列表（图标 + 数据源名称，原生 BaseListPopupStep 单击即选）。
 */

internal fun DataSourceSelector.execSilentProbe(
    console: Any,
    freshModel: Any?,
    demoted: Boolean,
    destDsName: String?,
    LOG: com.intellij.openapi.diagnostic.Logger,
): String? {
    LOG.info("[M36][EXEC] probe start (console isValid=${runCatching { console.javaClass.getMethod("isValid").invoke(console) }.getOrNull()}, " +
        "freshModel stmts=${runCatching { freshModel?.javaClass?.getMethod("statements")?.invoke(freshModel) as? Iterable<*> }.getOrNull()?.toList()?.size}, " +
        "ds=$destDsName)")
    if (demoted) {
        val reason = "freshModel demoted (vf PSI resolved 0 statements, fell back to console model)"
        LOG.info("[M36][EXEC] probe demoted -> $reason")
        return reason
    }
    return null
}

/**
 * M36.5.48：从 console 历史文档提取真实错误文本——仅当**超时无结果**（真失败/真超时）时
 * 调用。DBN 查询执行失败（SQL 语法错/表不存在等）会把错误消息写入 console history。
 *
 * 提取规则（防误判）：只认**明确的 SQL 错误判据**（[isErrorSignatureLine]），且错误行不得是
 * 纯 SQL 语句（含 SELECT/FROM/WHERE/JOIN 等 SQL 词），避免把正常 SQL 历史误判为错误
 * （日志实证 `extracted console error: WHERE t.id = 1`——A 并无真失败，正常 SQL 片段被误报）。
 *
 * M36.5.57：只扫 **consoleErrorBaseline 之后**的文本——历史残留错误（上个失败 SQL 写入的
 * Unknown column 等）不在本次范围，正常 SQL 执行后不会"带过来"旧错误。
 *
 * T-CEC-02（用户真机 P15）：判据由「短语词表」升级为「结构性判据 + 短语族」——原词表对
 * `[42000][1064] You have an error in your SQL syntax`（MySQL 语法错）一条都不含，面板因此
 * 全程无红字、无复制按钮，用户只能在 Output 页自己找。同批漏检的还有 Oracle `ORA-00933`、
 * MySQL 1062/1451 等；历史真机验证过的错误恰好全是 `Unknown column` 系，故缺口长期未现。
 */

internal fun DataSourceSelector.deliverError(
    onErrorRich: ((ExecutionErrorInfo) -> Unit)?,
    onError: ((String) -> Unit)?,
    cause: Throwable?,
    message: String,
    sqlMaxLength: Int? = null,
) {
    val rich = onErrorRich
    if (rich != null) {
        runCatching { rich(ErrorPositionExtractor.buildInfo(cause, message, sqlMaxLength)) }
            .onFailure {
                com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                    .warn("[T-ErrorLocate] onErrorRich invoke failed → fallback to onError", it)
                onError?.invoke(message)
            }
    } else {
        onError?.invoke(message)
    }
}

/**
 * O-64/T-RollbackIdentityParse：identity 包装产物的「整批直跑」ExecOption 副本。
 *
 * 平台语义（javap 243 `DatabaseSettings$ExecOption` + 常量组实证）：多语句执行范围由
 * `execInside`（光标在语句内）/ `execOutside`（光标在语句间）两个 int 决定，默认
 * `EXECUTE_INSIDE_SHOW_CHOOSER` 弹 Statements 选择层且默认只选第一条——P19 的 identity
 * 产物首语句恰是无副作用的 `SET … ON`，按默认执行等于 INSERT 没跑。本副本把两键都覆写为
 * `*_WHOLE_SCRIPT`：不弹层、整批全部执行，即「默认全选」的唯一平台杠杆。
 *
 * 只造副本：`copyFrom` 基础配置后覆写两键，preparedExecOption/sharedExecOption 缓存与
 * 用户全局设置（`getDefaultExecOption()` 活实例）一概不碰。反射不可用 → 原样返回基础
 * 实例（执行维持现态，不劣化）。测试直接覆盖本函数（DatabaseTools 在默认 test classpath）。
 */
