package cn.song.mybatisplus.visualizer.execute

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

// T-M53-II（2026-10-10）：以下为 DataSourceSelector 的「控制台准备与执行」内聚成员，
// 以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal。

internal fun DataSourceSelector.prepareAfterEditorResolved(
    project: Project,
    vf: com.intellij.openapi.vfs.VirtualFile,
    executeAfterPrepare: Boolean,
    dataSourceName: String?,
    onConsole: ((javax.swing.JComponent) -> Unit)?,
    onError: ((String) -> Unit)?,
    console: Any,
    document: com.intellij.openapi.editor.Document?,
    editor: com.intellij.openapi.editor.Editor,
    onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
    wholeScriptExec: Boolean = false,
    prepareOwner: QueuedExecuteIntent? = null,
    dialectGateRequest: ExecutionDialectGate.Request? = null,
    onDialectGate: ((ExecutionDialectGate.Verdict) -> Unit)? = null,
    onSubmission: ((ExecutionSubmission) -> Unit)? = null,
    isPrepareActive: () -> Boolean = { true },
) {
    if (!isPrepareActive()) return
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
    // T-FirstExecResultInvisible F1：排队执行意图在本链完成准备时消费（下方缓存落地后）。
    // 意图消费后、执行落地前撞上失败 → onFailure 兜底回落意图 onError（面板已 showLoading）。
    var queuedIntent: QueuedExecuteIntent? = null
    var queuedExecuted = false
    runCatching {
        val consoleClass = Class.forName(CONSOLE_CLASS)

        // T-PreviewExecDs-03 F2：getScriptModel 前 isValid 预检 + 三分量诊断——attach 后
        // console 可能已被 getActiveConsoles 清扫（isValid=false，依赖 facade 数据源加载状态，
        // 见开发计划 §2.2 反编译实锤）。disposed 直接快速失败（等待无意义，F3 自愈清缓存）；
        // 未 disposed 但分量失效时，非 EDT 上有界重查等 facade 异步窗口，EDT 不阻塞直走
        // （getScriptModel 若撞上清扫由 F3 特判接管）。
        val initialValidity = probeConsoleValidity(console, project, LOG)
        if (initialValidity != null && initialValidity.disposed) {
            LOG.warn("[M36] console DISPOSED right after attach: $initialValidity")
            throw IllegalStateException(MpSqlVisualizerBundle.message("execute.console.disposed"))
        }
        if (initialValidity != null && !initialValidity.valid &&
            !ApplicationManager.getApplication().isDispatchThread
        ) {
            runCatching {
                repeat(3) { attempt ->
                    Thread.sleep(300)
                    val v = probeConsoleValidity(console, project, LOG)
                    if (v != null && v.valid) {
                        LOG.info("[M36] console valid after retry (attempt=${attempt + 1})")
                        return@repeat
                    }
                }
            }
            LOG.warn("[M36] console still invalid after bounded retry: ${probeConsoleValidity(console, project, LOG)}")
        }

        // 3. scriptModel + execOption
        val scriptModel = consoleClass.getMethod("getScriptModel").invoke(console)
        val execOptionClass = Class.forName("com.intellij.database.settings.DatabaseSettings\$ExecOption")
        // M36.5.16：execOption 用 **DatabaseSettings.getDefaultExecOption()**（原生默认配置）
        // 而非 new 空实例——空实例的 execSelection=0（非法值），ScriptModelUtil
        // .getSelectedStatementsRange 按"执行选中部分"解析空选择 → model.getTextRange()
        // = (N,N) 零长度 → 原生 ▶ isEnabled=false 恒灰（沙盒探针 statementsRange=(0,0)/
        // (57,57) 实证，其余四键已全绿）。原生同源配置顺带修正 newTab/submitImmediately 执行语义。
        val execOption: Any = runCatching {
            Class.forName("com.intellij.database.settings.DatabaseSettings")
                .getMethod("getDefaultExecOption").invoke(null)
        }.getOrNull() ?: execOptionClass.getDeclaredConstructor().newInstance()
        if (scriptModel == null) {
            failPrepareRequests(prepareGate, prepareOwner, onSubmission, onError, queuedIntent,
                MpSqlVisualizerBundle.message("execute.failed", "scriptModel unavailable"))
            return
        }

        // M36.5.24：▶ 灰/无绿色边框根因修复——statementsRange=(0,0)（探针实证）。
        // 根因链（javap 实证）：DBN 原生 ▶ isEnabled 依赖
        //   ScriptModelUtil.getSelectedStatementsRange = getSelectionForConsole(editor, opt)
        //     → client.getScriptModel()（= console.getScriptModel()）
        //     → adjustModelForSelection → subModel(...).getTextRange()
        //   console.getScriptModel() 缓存 model，isActual() **仅比较 splitScriptMode 配置**
        //   （与文档/PSI 无关）→ attachConsole 早期（vf PSI 未 commit / document 内容未
        //   同步）创建的 model 永不重建 → SqlScriptModel.getTextRange() = myScript.getRange()
        //   = null（Script 基于 ScriptModelUtilCore.getScriptDocument(consoleVf)，空内容）
        //   → statementsRange=(0,0) → ▶ 灰、StatementRangeAnnotator 无语句 → 无绿色边框。
        // 修复两步：① console doc 内容同步进 console vf document 并 commit（PSI 对齐，
        //   与 executeOnConsole 执行前同款同步）；② 反射强制重建 scriptModel
        //   （JdbcConsole.updateScriptModel 内部 createScriptModel(console.getFile())），
        //   isActual() 不再拦截。
        var effectiveScriptModel = scriptModel
        runCatching {
            val consoleVf = consoleClass.getMethod("getVirtualFile").invoke(console)
                as? com.intellij.openapi.vfs.VirtualFile
            if (consoleVf != null) {
                val consoleText = (document as com.intellij.openapi.editor.Document).text
                @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
                com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
                    // T-ExecDocSync2 F2：同 F1 收口——commit 后补 saveDocument，console document
                    // 不再滞留「已修改未保存」（第四轮根因 G3，开发计划 §1.2）
                    writeVfTextViaDocument(project, consoleVf, consoleText)
                }
                // T-ConsoleDbContext B：控制台就绪即把库上下文对齐到「数据源 URL 里的库」——
                // DBN 的文件→数据源映射与库记忆会把会话钉在别的库上（真机事故：exec 文件被映射
                // 到 mpj_demo@localhost，而 SQL 属于 jpa_demo → Unknown column 'u.username'）。
                // 用户定案：预览默认以数据源 URL 的库为准；用户之后在控制台手调库，记忆照常保留
                //（对齐只在「与数据源默认库不一致」时切一次，已一致即 no-op，不会覆盖用户后来的选择）。
                LOG.info("[M36] console db-context: ${ExecConsoleContext.alignToDataSourceDatabase(console)}")
                // M36.5.24g：fresh 构造 + set 移入 DumbService.runWhenSmart——22:30 实证：
                // attachConsole 后 SqlDialectPusher 的 dumb task（58.443 运行 → 58.714 exit）
                // 触发 PSI 重建；在 dumb mode 中构造的 fresh（58.302，stmts=1）被 push 后
                // PSI 重建击穿 → 语句 range=null（SIt(null)）→ statementsRange 零长度。
                // runWhenSmart 保证 dialect push + PSI 完整后再构造 → 语句 range 非 null。
                val consoleForModel = console
                val vfForModel = consoleVf
                com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart {
                    // 延迟到 dialpct push 完全结束后（dumb 退出 + 一次 EDT 泵）再重建
                    // write-safe: UI-only（纯 Swing/只读，无文档写）
                    ApplicationManager.getApplication().invokeLater({
                        runCatching {
                            refreshConsoleModel(project, consoleForModel, vfForModel, LOG)
                        }.onFailure { e ->
                            LOG.warn("[M36] scriptModel refresh (smart) FAILED: ${e.message}")
                        }
                    }, com.intellij.openapi.application.ModalityState.any())
                }
            }
        }.onFailure { e ->
            LOG.warn("[M36] scriptModel vf-sync FAILED (fallback to attach-time model): ${e.message}")
        }

        // M36.5：缓存已备控制台（executePrepared 消费）
        // M36.5.10：代际递增——新控制台上下文取代旧缓存（旧挂起的 runWhenSmart 提交作废，
        // 防止在新控制台上执行旧预览 SQL）
        executionGeneration++
        preparedConsole = console
        preparedEditor = editor
        preparedScriptModel = effectiveScriptModel
        preparedExecOption = execOption
        preparedDialectGateRequest = dialectGateRequest
        preparedDialectGateCallback = onDialectGate

        // T-FirstExecResultInvisible F1：排队执行意图消费点——准备完成即取走（一次性）。
        // executeNow = 本链显式执行（selectAndExecute 全链）或排队意图就位；后者按
        // executePrepared 口径执行（见下方消费块）。仅门持有链可消费——PASS_THROUGH
        // 预览链消费会把别人的执行意图打在自己的 SQL 上。
        if (!isPrepareActive()) return
        if (prepareOwner != null) {
            queuedIntent = prepareGate.consumePendingExecute(prepareOwner)
            if (queuedIntent != null) {
                LOG.info("[M36][T-FirstExec F1] queued execute intent consumed after prepare (executeAfterPrepare=$executeAfterPrepare)")
            }
        }
        val executeNow = executeAfterPrepare || queuedIntent != null

        // 4. 拼合原生控制台 UI：顶部块（工具栏+编辑器）/ 结果区——默认展示只嵌顶部块（M36.5.2）
        val logView = runCatching {
            consoleClass.getMethod("getView").invoke(console)
        }.getOrNull()

        // 4.1 触发内容构建（独立 try-catch）
        if (logView != null) {
            // 单测/惰性平台在attach时尚未建view；这里getView后补登记同一资源。
            NativeConsoleViewLifetime.remember(project, console)
            runCatching {
                logView.javaClass.getMethod("ensureContentInitialized").invoke(logView)
            }.onFailure { e ->
                LOG.warn("[M36] ensureContentInitialized FAILED: ${e.message}")
            }
        }

        // 4.2 取 ui 与结果区组件
        val ui: Any? = logView?.let {
            runCatching { it.javaClass.getMethod("getUi").invoke(it) }.getOrNull()
        } ?: runCatching { consoleClass.getMethod("getUi").invoke(console) }.getOrNull()
        preparedUi = ui
        val resultComponent: javax.swing.JComponent? =
            ui?.javaClass?.getMethod("getComponent")?.invoke(ui) as? javax.swing.JComponent

        // 4.3 SQL 编辑器组件（EditorImpl.component）
        // M36.5.2：未执行路径下 console 编辑器从未布局，preferredSize=0 且 hidden →
        // 宿主容器/SplitPane 按首选尺寸分配时编辑器塌缩到 0 高（截图实证 "Console output"
        // 占位满屏）。强制可见 + 首选高度兜底，任何宿主布局下编辑器块均可占位可见。
        val editorComponentRaw: javax.swing.JComponent? = editor?.component?.apply {
            isVisible = true
                // M36.5.25e：editor 兜底 200——GridLayout(1,1) 已强制 fill，固定高度不再
                // 需要（700 在窗口小时会溢出）；preferredSize/minimumSize 仅作首次塌缩
                //（preferred=0）的兜底，正常自适应由布局管理器保证。
                if (preferredSize == null || preferredSize.height < 50) {
                    preferredSize = java.awt.Dimension(400, 200)
                }
                if (minimumSize == null || minimumSize.height < 200) {
                    minimumSize = java.awt.Dimension(400, 200)
                }
        }
        LOG.info("[M36] resultComponent=$resultComponent, editorComponent=$editorComponentRaw")
        if (editorComponentRaw == null) {
            failPrepareRequests(prepareGate, prepareOwner, onSubmission, onError, queuedIntent,
                consoleEditorUnavailableMessage("editorComponent"))
            return
        }

        // 4.3.1 M36.5.4：编辑器块补 DataProvider——原生头部工具栏动作（▶执行等）经
        // JdbcConsole.findConsole(event) 解析控制台，仅两条路：PlatformCoreDataKeys.FILE_EDITOR
        // （嵌入面板无 FileEditor）或 SessionClientHolder.CLIENT_KEY（组件 DataProvider 链）——
        // 嵌入面板两者皆缺 → ▶执行按钮恒禁用（沙盒实测，QueryActionBase.update 需
        // EDITOR + console）。包一层 DataProvider 直供 CLIENT_KEY=console + EDITOR=editor，
        // 工具栏动作数据上下文与原生工具窗口等效。
        val clientKeyName: String? = runCatching {
            (Class.forName("com.intellij.database.console.client.SessionClientHolder")
                .getField("CLIENT_KEY").get(null)
                    as com.intellij.openapi.actionSystem.DataKey<*>).name
        }.getOrNull()
        val consoleForData = console
        val editorForData = editor
        // M36.5.7：补 PSI_FILE/VIRTUAL_FILE——▶ 启用链 isApplicable →
        // JdbcConsoleProvider.isSqlAtPlace(psiFile, editor) 需要 PSI_FILE
        // （javap 实证）；嵌入面板无 FileEditor/平台 Provider 链，须直供。
        // PSI 预解析包 ReadAction（DataProvider.getData 可能在 BGT 动作更新线程调用）。
        val vfForData = vf
        // M36.5.14：psiForData 不再是固定快照——文件内容每次预览都会更新（setText/
        // setBinaryContent），PSI 随之重建，prepare 时预解析的 PsiFile 会 **isValid=false**，
        // isSqlAtPlace(invalidPsi) 恒 false → 原生 ▶ 恒灰（12:29:49 探针 isSqlAtPlace=false
        // 实证）。改为 lazy 取值时**失效检测 + 实时重解析**（lazy 回调在平台 read action 中）。
        var psiForData: PsiFile? = runCatching {
            @Suppress("DEPRECATION")
            ReadAction.compute<PsiFile, Throwable> {
                PsiManager.getInstance(project).findFile(vf)
            }
        }.getOrNull()
        // M36.5.9：改实现 **UiDataProvider**（legacy DataProvider 在 2024.3 被 DataValidators
        // 拒绝 PSI——PluginException "PSI element ... provided on EDT ... Use DataSink.lazy"，
        // 用户堆栈实证，validOrNull 返回 null → isApplicable 拿到 null → ▶ 恒灰）。
        // PSI_FILE 经 DataSink.lazy 异步提供（平台在 read action 中取值）；CLIENT_KEY 反射
        // 取 DataKey 后 set（值非 PSI 可同步）。
        val editorComponent: javax.swing.JComponent =
            object : com.intellij.ui.components.JBPanel<com.intellij.ui.components.JBPanel<*>>(
                java.awt.BorderLayout(),
            ), com.intellij.openapi.actionSystem.UiDataProvider {
                override fun uiDataSnapshot(sink: com.intellij.openapi.actionSystem.DataSink) {
                    // M36.5.15：补 PROJECT——原生动作（含 ▶ 执行）的 update 普遍依赖
                    // CommonDataKeys.PROJECT，嵌入面板的 DataProvider 链缺失时 AnActionEvent
                    // .project=null → 动作禁用（▶ 灰的最后一环：PSI_FILE/isSqlAtPlace 已
                    // 12:42 版本修复为 true，唯 PROJECT 未直供）
                    sink.set(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                    sink.set(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, editorForData)
                    sink.set(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE, vfForData)
                    // M36.5.14：PSI 失效检测 + 实时重解析（快照失效是 ▶ 恒灰根因）
                    sink.lazy(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE) {
                        if (psiForData?.isValid == true) psiForData else runCatching {
                            @Suppress("DEPRECATION")
                            ReadAction.compute<PsiFile, Throwable> {
                                PsiManager.getInstance(project).findFile(vfForData).also { psiForData = it }
                            }
                        }.getOrNull()
                    }
                    if (clientKeyName != null) {
                        @Suppress("UNCHECKED_CAST")
                        sink.set(
                            Class.forName("com.intellij.database.console.client.SessionClientHolder")
                                .getField("CLIENT_KEY").get(null)
                                    as com.intellij.openapi.actionSystem.DataKey<Any>,
                            consoleForData,
                        )
                    }
                }
            }.apply {
                isOpaque = false
                // M36.5.59：编辑器拉伸填充修正——BorderLayout.CENTER 尊重子组件 preferred（仅
                // ~4 行高），DBN 控制台编辑器在长文档/空余区不扩展，topBlock 下方留下大面积
                // 面板底色"空洞"（用户截图实证：编辑器仅 ~80px，顶部区 ~240px）。改 GridLayout
                // (1,1)，与 editorHolder（SqlPreviewPanel:171）同机制：强制唯一子组件（原生控制台
                // 编辑器）fill 满容器，忽略其 preferred/maximum，任何宿主布局下编辑器块均占满
                // 分配的 topBlock，不留空区。
                layout = java.awt.GridLayout(1, 1)
                // 解除编辑器组件可能的 maximumSize 上限，确保 GridLayout fill 不被 preferred 推算压回
                add(editorComponentRaw)
                editorComponentRaw.maximumSize = java.awt.Dimension(
                    Int.MAX_VALUE, Int.MAX_VALUE,
                )
                // M36.5.65：GridLayout 在某些 DBN 编辑器（自身带 header+editor 内部布局）上不生效
                // ——沙盒 BOUNDS 实证 `top=...x403` 但 `top.child[0]=...x234`（编辑器组件未填满，
                // 只到 234px，下方留面板底色空洞）。GridLayout 依赖容器 doLayout，而 DBN 编辑器
                // 组件内部 preferred=220 且不随父高扩展。兜底：随父 resize 强制把编辑器组件
                // setSize 到父容器可用高度（header 之外的全部），任何布局管理器下编辑器都填满。
                addComponentListener(object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent) {
                        val avail = size
                        if (avail.width > 0 && avail.height > 0) {
                            editorComponentRaw.setSize(
                                avail.width, avail.height,
                            )
                            editorComponentRaw.revalidate()
                        }
                    }
                })
            }

        // 4.4 原生控制台头部（M36.5.3）：JdbcConsole.buildHeaderComponent(Editor, JdbcConsole)
        // public static（javap 反编译实锤）——ActionManager 取 "Console.Jdbc.Left"（▶执行/Debug/
        // 历史/参数/设置）与 "Console.Jdbc.Right"（Tx/会话/数据源选择器）动作组 → EditorHeaderComponent
        // （左 CENTER/右 EAST）→ editor.setHeaderComponent + setPermanentHeaderComponent。
        // 与 IDE Database 控制台同一构建路径，嵌入编辑器顶部即刻拥有完整原生工具栏；
        // 失败回退 M36.5.2 的 addLeftToolbarConsoleActions 精简工具栏（仅 console 通用动作）。
        var topBlock: javax.swing.JComponent = editorComponent
        runCatching {
            val buildHeader = consoleClass.getMethod(
                "buildHeaderComponent",
                com.intellij.openapi.editor.Editor::class.java,
                consoleClass,
            )
            buildHeader.invoke(null, editor, console)
            LOG.info("[M36] native console header built (Console.Jdbc.Left/Right toolbars)")
        }.onFailure { headerError ->
            LOG.warn("[M36] buildHeaderComponent FAILED (${headerError.message}), fallback to console-actions toolbar")
            runCatching {
                val consoleView = consoleClass.getMethod("getConsoleView").invoke(console)
                    ?: return@runCatching
                val group = com.intellij.openapi.actionSystem.DefaultActionGroup()
                val util = Class.forName("com.intellij.database.console.JdbcConsoleUtil")
                val addActions = util.getMethod(
                    "addLeftToolbarConsoleActions",
                    com.intellij.openapi.actionSystem.DefaultActionGroup::class.java,
                    Class.forName("com.intellij.execution.ui.ConsoleView"),
                )
                addActions.invoke(null, group, consoleView)
                if (group.childrenCount > 0) {
                    val toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                        .createActionToolbar("MpSqlNativeConsole", group, true)
                    toolbar.targetComponent = editorComponent
                    topBlock = javax.swing.JPanel(java.awt.BorderLayout()).apply {
                        isOpaque = false
                        border = com.intellij.util.ui.JBUI.Borders.empty()
                        add(toolbar.component, java.awt.BorderLayout.NORTH)
                        add(editorComponent, java.awt.BorderLayout.CENTER)
                    }
                    LOG.info("[M36] console-actions toolbar injected: ${group.childrenCount} actions")
                }
            }.onFailure { e ->
                LOG.warn("[M36] toolbar injection skipped (fallback to bare editor): ${e.message}")
            }
        }

        // 4.5 完整控制台 = 顶部块（上）/ 结果区（下）垂直 SplitPane（M36.4 主题化形态）
        // ★A49：**无嵌入宿主（onConsole == null）时不组 SplitPane、不摘 DBN 结果组件**——
        // SQL 快捷模板 / 日志转 SQL 执行链只把语句送原生 Database 控制台执行，控制台由 DBN
        // 在 IDE 底部 Services 视图自行展示；此时组出来的 SplitPane 永不展示（onConsole?.invoke
        // 是 no-op），摘取结果组件（ui.getComponent()）无收益、只扰动 DBN 组件树。判据见
        // shouldComposeEmbedSplitPane（含归因更正：这是排查「网格 0 rows」时顺带发现的收敛，
        // 该现象真因是调用方传了不存在的表名，非插件行为）。
        val fullComponent: javax.swing.JComponent? = resultComponent?.let { result ->
            if (shouldComposeEmbedSplitPane(onConsole != null, hasResultComponent = true)) {
                buildConsoleSplitPane(topBlock, result)
            } else {
                null
            }
        }

        // 4.6 内嵌（M36.5.6：准备阶段**只嵌编辑器块**——"Console output" 空占位不再预嵌；
        // 结果区由 ContentManager 监听换入：任何执行路径（原生 ▶ / 控制台 Ctrl+Enter /
        // 插件按钮）产生结果内容（contentAdded）即换入完整 SplitPane——三路径全覆盖、
        // 以真实结果出现为准。准备+执行全链仍直接嵌完整 SplitPane（执行后立即有结果可看）。
        // 塌缩防护不变：4.3 首选高度兜底 + embedConsole 分隔保险。
        // M36.5.8：pendingOnConsole 覆盖全路径——执行后结果区组件会被 RunnerLayoutUi 重建，
        // 旧引用显示停留在上一轮（用户实测：新 SQL 执行后一直加载中、重开面板才见新结果）；
        // startResultTabRetry 找到结果 Tab 后重取组件换入（refreshResultsComponent）。
        pendingOnConsole = onConsole
        // M36.5.53：预览也要嵌**完整 SplitPane**（而非仅 topBlock）——同一 topBlock 不能同时被
        // editorHolder（预览挂载）和 SplitPane（执行换入）持有，否则执行后 SplitPane 的 top
        // 失效（沙盒实证 topH=0，setDividerLocation 撑不起）。预览时 bottom（结果区占位）通过
        // 清空 + divider 压底折叠，满足"未执行不显示结果面板"。
        val component: javax.swing.JComponent = fullComponent ?: topBlock
        preparedTopBlock = topBlock
        preparedResultComponent = resultComponent
        // M36.5.41-A4：持久整控台**始终**缓存——executePrepared 的 onResultsReady 与
        // 结果刷新都走原位换底，不再按次重建 SplitPane（身份稳定，sameAsPrev=true）。
        preparedFullComponent = fullComponent
        LOG.info("[M36] embed component=$component (executeAfterPrepare=$executeAfterPrepare)")

        if (!executeAfterPrepare) {
            attachResultsHook(ui, topBlock, resultComponent, console, editor.document.text, LOG)
        }
        logDataContextCheck(editorComponent, console, editor, execOption, LOG)
        clearConsolePrompt(console, LOG)

        // M36.5.5：填充共享控制台缓存 + 统一记忆数据源（下次预览 last-used 命中即复用，
        // 不再新建 session/连接——挂死根因见 sharedConsole 字段注释）
        sharedConsole = console
        sharedEditor = editor
        sharedScriptModel = effectiveScriptModel
        sharedExecOption = execOption
        sharedUi = ui
        sharedTopBlock = topBlock
        // M36.5.53：sharedFullComponent 缓存**完整 SplitPane**（preparedFullComponent 也是
        // fullComponent），不随预览时内嵌的 component（可能为 topBlock 编辑器）变化——否则
        // 执行时 swapResultIntoPersistentConsole 拿到的 sharedFullComponent 是 topBlock（非
        // SplitPane）→ 回退 buildConsoleSplitPane 重建 → A4 身份漂移。预留给执行复用。
        // ★A49：无嵌入宿主时 fullComponent 为 null（不组不摘）——执行复用链
        // （reuseSharedConsole）在 sharedFullComponent == null 时自行回退 buildConsoleSplitPane，
        // 语义不变（见 shouldComposeEmbedSplitPane）。
        sharedFullComponent = fullComponent
        sharedDataSourceName = dataSourceName
        sharedDialectGateRequest = dialectGateRequest
        sharedDialectGateCallback = onDialectGate
        // T-SI-02：控制台已建，数据源 UUID 从此可取（LocalDataSource.getUniqueId）——
        // 记忆升级为「UUID + 显示名」双字段，下次预览按身份命中而非猜名字。
        // T-ModuleScopedLastUsed W2：带上模块键，让「上次使用」按模块分桶（同时仍写项目级
        // 作无模块上下文的兜底）；moduleKey 缺失时行为与改前逐字一致。
        if (dataSourceName != null) {
            rememberRunner(project, dataSourceName, consoleDataSourceId(console), dialectGateRequest?.moduleKey)
        }

        // 4.7 内嵌（同步，已在 EDT）
        onConsole?.invoke(component)

        // M36.5.55：预览（尚未执行）时把结果区（bottom）折叠——divider 压到接近底部，
        // bottom（DBN Output/Tx 结果区占位）收起不显示，满足"未执行不显示结果面板"。
        // 执行时 realignSplitDivider 展开 60%。用 invokeLater 等组件布局后再设 divider。
        // T-FirstExecResultInvisible F3：折叠归口 embedMode——仅 PREVIEW 且结果区无内容才折叠
        //（本链要执行（executeNow）恒不折叠；有内容不吞用户可见的结果）。
        if (!executeNow) {
            (component as? javax.swing.JSplitPane)?.let { sp ->
                val collapse = ConsoleEmbedMode.PREVIEW.shouldCollapseDivider(resultAreaHasContent(ui))
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

        // 5. 可选立即执行（selectAndExecute 全链语义；结果区已随完整 UI 内嵌，onResultsReady=null）
        // T-FirstExecResultInvisible F1：排队执行意图消费——按 executePrepared 口径调
        // executeOnConsole（onResultsReady 齐：提交后完整 UI 换入 + 面板 EXECUTE 内嵌展开）；
        // onError/onErrorRich/wholeScriptExec 用意图自身回调（面板 showLoading 的解除与错误
        // 投递归意图，不借预览链的静默 onError——否则排队执行失败即静默卡死）。
        if (executeNow) {
            if (prepareOwner != null && !prepareGate.beginSubmission(prepareOwner)) return
            queuedIntent?.let { qi -> qi.onEditor?.invoke(editor) }
            // 2026-09-23 真机走查补：此处原**漏传** onErrorRich（默认 null）⇒ 这条路径上的错误
            // 投递收口只能回退旧通道，定位按钮永不出现。本函数签名里一直有该参数，只是没往下传
            // ——「实现有、接线漏」的又一例（同族见 ErrorPositionWiringGuardTest 的转发断言）。
            executeOnConsole(
                project, console, editor, effectiveScriptModel, execOption, ui,
                onResultsReady = queuedIntent?.onConsole,
                onError = queuedIntent?.onError ?: onError, onErrorRich = queuedIntent?.onErrorRich ?: onErrorRich,
                wholeScriptExec = queuedIntent?.wholeScriptExec ?: wholeScriptExec,
                dialectGateRequest = queuedIntent?.dialectGateRequest ?: dialectGateRequest,
                onDialectGate = queuedIntent?.onDialectGate ?: onDialectGate,
                onSubmission = selectPrepareSubmission(queuedIntent, onSubmission),
            )
            if (queuedIntent != null) queuedExecuted = true
        }
    }.onFailure { e ->
        NativeConsoleViewLifetime.unwrapAndRethrowCritical(e)
        LOG.warn("[M36] prepareInConsole FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
        // M36.3：通知调用方错误，确保 UI 从「执行中」状态恢复
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        // T-PreviewExecDs-03 F3：console 已被 getActiveConsoles 清扫（Disposer.register 撞
        // 已 dispose 父节点抛 IncorrectOperationException）→ 清单例缓存（否则下轮执行复用
        // dead 缓存继续失败）+ 可读文案，不再透平台异常长串。
        val failureMsg: String = if (cause is com.intellij.util.IncorrectOperationException &&
            cause.message?.contains("already been disposed") == true
        ) {
            LOG.warn("[M36] console disposed during prepare (swept by getActiveConsoles), clear caches for retry: ${cause.message?.take(120)}")
            clearPrepared()
            dropSharedConsole()
            MpSqlVisualizerBundle.message("execute.console.disposed")
        } else {
            cause.message ?: cause.toString()
        }
        onSubmission?.invoke(ExecutionSubmission.BLOCKED)
        onError?.invoke(failureMsg)
        // 消费前异常也必须结算仍在门里的排队请求，不能只复位后丢弃。
        if (prepareOwner != null) prepareGate.abort(prepareOwner, ExecutionSubmission.BLOCKED, failureMsg)
        // 消费后的请求已离开门，仍须单独结算。
        val qi = queuedIntent
        if (qi != null && !queuedExecuted) {
            LOG.warn("[M36][T-FirstExec F1] queued execute intent dropped by prepare failure, surface to intent onError")
            qi.complete(ExecutionSubmission.BLOCKED, failureMsg)
        }
    }

    // T-FirstExecResultInvisible F1：单飞位复位（成功/失败同尽；排队意图的消费与回落都在
    // 上方完成）——仅门持有链；PASS_THROUGH 预览链不得触碰别人的门。
    if (prepareOwner != null) prepareGate.finishPrepare(prepareOwner)
}

/**
 * T-PreviewExecDs-03 F2：console 有效性的三分量快照（反编译实锤 `isValid` =
 * `!isDisposed && isViewValid && facade.findDataSource(dataSource.uniqueId) != null`）。
 * 任何一环为 false 即可能被 `getActiveConsoles` 清扫 dispose——日志/自愈判定共用。
 */

internal fun DataSourceSelector.executeOnConsole(
    project: Project,
    console: Any,
    editor: com.intellij.openapi.editor.Editor,
    scriptModel: Any,
    execOption: Any,
    ui: Any?,
    onResultsReady: ((javax.swing.JComponent) -> Unit)?,
    onError: ((String) -> Unit)?,
    onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
    dsName: String? = sharedDataSourceName,
    wholeScriptExec: Boolean = false,
    dialectGateRequest: ExecutionDialectGate.Request? = null,
    onDialectGate: ((ExecutionDialectGate.Verdict) -> Unit)? = null,
    onSubmission: ((ExecutionSubmission) -> Unit)? = null,
) {
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
    // O-145 W7：真实提交总口再次从 JdbcConsole 取实际数据源；必须早于任何文档改写、失效、
    // submitTransactionLater/executeQueries。准备后用户切换原生控制台数据源时，session 时判定已过期，
    // 本闸负责第二次阻断，绝不按 runner 显示名或缓存名猜目标。
    val actualTarget = ExecutionDialectGate.fromConsole(console)
    if (!allowDialectGate(project, dialectGateRequest, actualTarget, onDialectGate, onError, onSubmission)) {
        LOG.warn(
            "[M36][O-145] submit blocked before console mutation: " +
                "target=${actualTarget?.stableId}/${actualTarget?.dialectName}",
        )
        return
    }
    // 只有真正通过提交前闸的模块/目标才学习；预览 prepare、冲突或取消均不写绑定。
    val moduleKey = dialectGateRequest?.moduleKey
    val dataSourceId = actualTarget?.stableId
    if (!moduleKey.isNullOrBlank() && !dataSourceId.isNullOrBlank()) {
        cn.song.mybatisplus.visualizer.sql.ModuleDataSourceBindingStore.getInstance(project)
            .remember(moduleKey, dataSourceId)
    }
    // O-64/T-RollbackIdentityParse：identity 包装产物整批直跑——覆写本次执行的 ExecOption
    // （只造**副本**传下去，prepared/shared 缓存与用户全局设置一概不动），弹层不再出现、
    // 整批（SET…ON + INSERT + SET…OFF）全部执行，即「Statements 默认全选」的平台实现杠杆。
    // 反射不可用（Database 插件类缺席）时原样返回基础实例 = 维持现态，不劣化。
    val effectiveExecOption = if (wholeScriptExec) wholeScriptExecOption(execOption) else execOption
    // T-MysqlGStrip T-MG-02：提交链总口（已备控制台路径）——提交文本源是控制台编辑器文档，
    // 执行前剥离 `\G` 后缀并把剥后文本**回写编辑器文档**（所见=所执行，避免显示/执行分叉）。
    // 回写复用既有 T-ExecDocSync 写纪律（dispatchDocWriteOnEdt → writeVfTextViaDocument：
    // setText→commit→saveDocument 同临界区，BGT 调用派 EDT），不新开写路径。
    // 方言按控制台数据源 JDBC URL 判定：非 MySQL 系方言不剥；URL 不可得 → 默认剥离（计划 §三定案）。
    val consoleVfForG = runCatching {
        console.javaClass.getMethod("getVirtualFile").invoke(console) as? com.intellij.openapi.vfs.VirtualFile
    }.getOrNull()
    val gStrip = stripGSuffixForSubmit(editor.document.text, ExecConsoleContext.dataSourceUrl(console))
    if (gStrip.stripped) {
        LOG.info(
            "[T-MysqlGStrip] stripped \\G suffix before submit " +
                "(len ${gStrip.original.length}->${gStrip.submitText.length}, vf=${consoleVfForG?.name})",
        )
        if (consoleVfForG != null) dispatchDocWriteOnEdt(project, consoleVfForG, gStrip.submitText)
    }
    // T-DdlCacheInvalidate（O-12）：DDL/事务提交语句在提交前失效插件自持 schema 缓存
    // （DBX object_cache 语义：失效须在结果返回前、失效失败只记日志不吞执行结果——
    // Oracle 系 DDL 隐式提交，DDL 报错不代表没提交）。全执行链唯一提交收口点；
    // SQL 文本取控制台编辑器文档（三调用路径共用同一文档，提交时刻即执行内容）。
    val executedSql = if (gStrip.stripped) gStrip.submitText else editor.document.text
    runCatching { MetadataInvalidator.invalidateAfterExecution(executedSql) }
        .onFailure { LOG.warn("[T-DdlCacheInvalidate] invalidate hook failed (execution continues)", it) }
    // M36.5.52：新执行重置错误去重基线——本次执行产生的错误应上报，不与上次残留混淆。
    lastReportedError = null
    // T-ExecSilentNoop：新执行亦重置提示去重标记——同一次执行只报一次提示。
    executionHintReported = false
    // M36.5.57：记录本次执行开始时 console history 基线长度——早检只扫本次之后新增文本，
    // 忽略 history 残留（正常 SQL 不再"带过来"上个失败的错误）。
    consoleErrorBaseline = consoleHistoryLength(console)
    // T-CEC-01：取证日志去重基线同批重置——每次执行至多记一条未识别尾部。
    lastUnrecognizedTail = null
    runCatching {
        val consoleClass = Class.forName(CONSOLE_CLASS)

        // 后台执行 executeQueries（M36.3）：避免 EDT 阻塞导致密码弹窗被遮挡。
        // M36.5：executeQueries 内部 Document.createRangeMarker 要求 read action，
        // 线程池线程无 read action 上下文会抛 RuntimeExceptionWithAttachments
        val execute = consoleClass.getMethod(
            "executeQueries",
            com.intellij.openapi.editor.Editor::class.java,
            Class.forName("com.intellij.database.script.ScriptModel"),
            effectiveExecOption.javaClass,
        )

        // M36.5.10：执行提交包 DumbService.runWhenSmart——索引期查询提交被 Database 插件
        // 调度层推迟（沙盒实证 09:47 会话：dumb=true 下重试 16s 无 Tab、索引结束后才真正
        // 执行，用户感知"一直加载中"）。改为**提交前等 smart**：查询只在索引完成后提交，
        // 结果 Tab 重试在真实提交后才启动；面板清空/新预览（clearPrepared/prepareInConsole/
        // reuseSharedConsole 递增 executionGeneration）→ 代际不匹配的挂起提交作废。
        val generation = executionGeneration
        // M36.5.21b：**构建版本戳**——jar 替换对运行中的沙盒 JVM 无效（class 已加载不重载），
        // 曾连续多轮"修复未生效"实为用户未重启沙盒 IDE。版本戳让日志第一行即暴露运行版本，
        // 与源码不符时先重启再排查，杜绝无效往返。
        LOG.info("[M36] executeOnConsole begin (build=$BUILD_STAMP)")
        // ★A49 只读排障日志（非功能改动，runCatching 包裹零风险）：本次提交的 ExecOption 四字段 +
        // 控制台视图是否 showing + 原生编辑器组件当前宿主。「原生控制台结果不渲染/停在 0 rows」
        // 这类现象的三列判别读数——本次该现象的真因已查明在调用方（传了不存在的表名 → 1146），
        // 本行留作同族现象的快速判因工具：newTab=true＝走 A48 家族「新建结果页签」路径；
        // viewShowing=false＝DBN 结果视图未挂载/未展示；editorCompParent=…editorComponent$1＝
        // 我方把原生编辑器组件摘进了自建容器。
        runCatching {
            fun opt(n: String) = runCatching { effectiveExecOption.javaClass.getField(n).get(effectiveExecOption) }.getOrNull()
            val viewShowing = runCatching {
                val v = consoleClass.getMethod("getView").invoke(console)
                v?.javaClass?.getMethod("isShowing")?.invoke(v)
            }.getOrNull()
            val editorCompParent = runCatching { editor.component?.parent?.javaClass?.name }.getOrNull()
            LOG.info(
                "[M36][A49] submit diag: newTab=${opt("newTab")}, execInside=${opt("execInside")}, " +
                    "execOutside=${opt("execOutside")}, execSelection=${opt("execSelection")}, " +
                    "viewShowing=$viewShowing, editorCompParent=$editorCompParent",
            )
        }
        com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart {
            if (generation != executionGeneration) {
                LOG.warn("[M36] execution submit cancelled (generation $generation != ${executionGeneration})")
                onSubmission?.invoke(ExecutionSubmission.CANCELLED)
                return@runWhenSmart
            }
            // M36.5.20：vf 同步 + **强制 commitDocument**——同步只保证 document 内容一致，
            // vf 的 PSI 需 commit 后才与 document 对齐（M36.5.18 的"滞后一轮"正是 PSI 未
            // commit 时 createScriptModel 解析了旧内容）。commit 后 findFile 的 PSI 即为
            // 用户所见 SQL。
            runCatching {
                val consoleVf = consoleClass.getMethod("getVirtualFile").invoke(console)
                    as? com.intellij.openapi.vfs.VirtualFile
                if (consoleVf != null) {
                    // T-MysqlGStrip：剥离后的文本才是本次执行内容（回写失败时编辑器文档可能仍是
                    // 原文，此处以剥后文本为准收敛 vf，保证提交内容恒定无 `\G`）
                    val consoleText = if (gStrip.stripped) gStrip.submitText else editor.document.text
                    @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
                    com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
                        // T-ExecDocSync T-EC-01：document 驱动写 + 同临界区落盘（saveDocument）——
                        // 执行前 console 手改未保存内容与磁盘收口，不留「已修改未保存」窗口
                        //（M36.5.20 的 commitDocument/PSI 对齐语义保留在 writeVfTextViaDocument 内）
                        writeVfTextViaDocument(project, consoleVf, consoleText)
                    }
                    LOG.info("[M36] console doc synced+committed+saved to vf before execution (${consoleVf.name})")
                }
            }.onFailure { e ->
                LOG.warn("[M36] sync console doc to vf FAILED: ${e.message}")
            }
            // 执行提交在 pooled thread 进行（executeQueries 内 Document.createRangeMarker 要求
            // read action；池线程无 read action 上下文会抛 RuntimeExceptionWithAttachments），
            // 但**所有 Swing 操作（JSplitPane 构造/换入/Timer 启动）必须在 EDT**——M36.5.10 曾
            // 把 buildConsoleSplitPane 放进 pooled thread，JSplitPane 构造触发编辑器 hierarchy
            // 事件 → ScrollingModelImpl.getVisibleArea → ThreadingAssertions 抛异常（用户堆栈
            // 实锤），异常阻断 onResultsReady/startResultTabRetry → 结果区不刷新/提示不显示。
            // M36.5.22：8s 连接等待监控**从提交时刻起算**（见 afterSubmitted）——此前挂在
            // 提交请求处，而原生事务派发实测可延迟 6.7s（16:53 会话实证），会导致连接
            // 正常时也误报"密码/驱动"提示。
            ApplicationManager.getApplication().executeOnPooledThread {
                var submitted = false
                try {
                    ReadAction.compute<Unit, Throwable> {
                        // M36.5.20：fresh model 回退 **vf PSI 版**（createScriptModel(findFile(vf))）。
                        // M36.5.18 的 createROFile 文本直构**不可靠**：14:55 会话 `stmts reflect
                        // failed`——RO file model 的 statements() 反射即抛异常，executeQueries
                        // 内部同样会失败 → 零语句执行（空结果 Tab）。vf 同步+commitDocument（EDT
                        // 步骤）已保证 findFile 的 PSI = 用户所见 SQL；此处按 vf PSI 建 model
                        //（DBN updateScriptModel 的同一重建路径）并**校验语句数**——count<=0 或
                        // 反射异常时回退 console.getScriptModel()（DBN 内部 model 保底可执行）。
                        val modelVf = runCatching {
                            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                                .getFile(editor.document)
                        }.getOrNull()
                            ?: runCatching {
                                consoleClass.getMethod("getVirtualFile").invoke(console)
                                    as? com.intellij.openapi.vfs.VirtualFile
                            }.getOrNull()
                        val vfPsi = modelVf?.let {
                            runCatching { PsiManager.getInstance(project).findFile(it) }.getOrNull()
                        }
                        fun statementsInfo(model: Any): String = runCatching {
                            val stmts = model.javaClass.getMethod("statements").invoke(model) as? Iterable<Any>
                            "count=${stmts?.toList()?.size}"
                        }.getOrElse { ex -> "FAILED:${ex.javaClass.simpleName}: ${ex.message}" }

                        var freshModel: Any? = vfPsi?.let { pf ->
                            runCatching {
                                val facade = Class.forName("com.intellij.sql.psi.SqlPsiFacade")
                                    .getMethod("getInstance", Project::class.java)
                                    .invoke(null, project)
                                facade.javaClass.getMethod("createScriptModel", PsiFile::class.java)
                                    .invoke(facade, pf)
                            }.getOrNull()
                        }
                        // 语句校验：vf PSI model 语句为空/反射异常 → 回退 console 内部 model
                        var freshModelDemoted = false
                        if (freshModel != null && statementsInfo(freshModel).startsWith("count=0")) {
                            LOG.warn("[M36] vf PSI model has 0 statements, fallback to console scriptModel")
                            freshModel = null
                            freshModelDemoted = true
                        }
                        if (freshModel == null) {
                            freshModel = runCatching {
                                consoleClass.getMethod("getScriptModel").invoke(console)
                            }.getOrNull() ?: scriptModel
                            freshModelDemoted = true
                        }
                        if (freshModel !== scriptModel) {
                            preparedScriptModel = freshModel
                            if (sharedConsole === console) sharedScriptModel = freshModel
                        }
                        LOG.info("[M36] executing model: class=${freshModel.javaClass.simpleName}, " +
                            "stmts=${statementsInfo(freshModel)}, vfPsiText tail=...${
                                vfPsi?.text?.replace("\n", " ")?.takeLast(30)
                            }")
                        // T-ExecSilentNoop F1a：提交前预检——探测 DBN 会静默拒绝的现场。
                        // demoted 时（vf PSI 解析 0 语句回落 console model）说明准备窗口不稳
                        //（方言 push 未完成 / PSI 未对齐），优先走 executeQueries(freshModel) 兜底
                        // 而非原生链，把"静默不执行"降级为"明确执行/明确报错"。
                        // 保守起见只在 demoted 时强制降级；非 demoted 保持 native 链原行为，
                        // 交由提交后的回执判定（afterSubmitted/startResultTabRetry）兜底。
                        val demoteToFallback = freshModelDemoted
                        execSilentProbe(console, freshModel, freshModelDemoted, dsName, LOG)
                        // M36.5.21c：执行链拆两段——vf PSI/model 探针在本 ReadAction 内；
                        // **原生 EDT 链挪到 ReadAction 外**（pooled 持读锁等 EDT、EDT 任务若需
                        // write 会死锁）。详见下方原生链处注释。
                        val vfPsiFinal = vfPsi
                        val editorFinal = editor
                        val execOptionFinal = effectiveExecOption
                        var nativeRun = false
                        if (vfPsi != null && !demoteToFallback) {
                            val providerClass = Class.forName(CONSOLE_PROVIDER_CLASS)
                            val findScriptModel = providerClass.getMethod(
                                "findScriptModel",
                                PsiFile::class.java,
                                com.intellij.psi.PsiElement::class.java,
                                com.intellij.openapi.editor.Editor::class.java,
                                effectiveExecOption.javaClass,
                            )
                            val doRunMethod = runCatching {
                                providerClass.getMethod(
                                    "doRunQueryInConsole",
                                    consoleClass,
                                    Class.forName("com.intellij.database.console.JdbcConsoleProvider\$Info"),
                                )
                            }.getOrNull()
                            if (doRunMethod == null) {
                                LOG.warn("[M36] native doRunQueryInConsole method not found, fallback")
                            } else {
                                // M36.5.21d：原生链在 **EDT + write-safe 事务**执行——
                                // doRunQueryInConsole → chooseStatements → saveAllDocuments 需要
                                // write-safe context（用户堆栈实锤：invokeLater(any) 的 NON_MODAL
                                // 是 write-unsafe，TransactionGuard 抛 "not any"）。
                                // TransactionGuard.submitTransactionLater 与原生 Ctrl+Enter 用户
                                // 事件同机制，事务内 model 修改合法。事务挂 console 为 Disposable
                                // （面板关闭取消挂起事务）。不阻塞等待：pooled 持读锁等 EDT 会死锁。
                                //
                                // M36.5.22：**提交时刻不可控**——javap 实证 TransactionGuardImpl
                                // .submitTransactionLater 内部是 invokeLaterOnWriteThread(runnable,
                                // ctx != null ? ctx.myModality : ModalityState.nonModal())，而
                                // getContextTransaction() 在写不安全/无全局进度时返回 null → 退化为
                                // NON_MODAL 派发。本轮沙盒实证该派发延迟 6.7s（首轮 43ms，次轮
                                // 6729ms）。因此**所有"提交后"动作（8s 监控 / 结果 Tab 监听 /
                                // 换入）必须在 doRunQueryInConsole 真正返回后**才启动，否则监听
                                // 窗口会在真实提交前跑完并把上一轮结果顶进面板（滞后一轮真因）。
                                // write-safe: UI-only（纯 Swing/只读，无文档写）
                                ApplicationManager.getApplication().invokeLater({
                                    if (generation != executionGeneration) {
                                        LOG.warn("[M36] execution submit cancelled at EDT (generation $generation != ${executionGeneration})")
                                        onSubmission?.invoke(ExecutionSubmission.CANCELLED)
                                        return@invokeLater
                                    }
                                    val disposable = (console as? com.intellij.openapi.Disposable)
                                        ?: com.intellij.openapi.Disposable { }
                                    try {
                                        com.intellij.openapi.application.TransactionGuard.getInstance()
                                            .submitTransactionLater(disposable) {
                                                try {
                                                    val info = findScriptModel.invoke(null, vfPsi, vfPsi, editor, execOptionFinal)
                                                    if (info == null) {
                                                        LOG.warn("[M36] native findScriptModel returned null (nothing to run?)")
                                                        // M36.5.48：findScriptModel 空 = 无可执行语句——显示**真实原因**，
                                                        // 而非笼统凭据超时提示（此前误用 execute.credential.hint，误导用户查密码/驱动）。
                                                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                                                        ApplicationManager.getApplication().invokeLater({
                                                            onError?.invoke(MpSqlVisualizerBundle.message("execute.noStatement"))
                                                        }, com.intellij.openapi.application.ModalityState.any())
                                                        return@submitTransactionLater
                                                    }
LOG.info("[M36] native findScriptModel OK, doRunQueryInConsole")
                                                doRunMethod.invoke(null, console, info)
                                                // 真实提交完成——此刻才启动结果监听
                                                LOG.info("[M36] native query SUBMITTED (starting result watch)")
                                                afterSubmitted(project, ui, console, onResultsReady, onError, LOG, dsName, demotedOverride = false, sqlText = executedSql, onErrorRich = onErrorRich, onSubmission = onSubmission)
                                                } catch (t: Throwable) {
                                                    val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                                                    // T-FP-02：反射包装层（InvocationTargetException）是普通异常，
                                                    // 直接判外层会漏判——须解包后再判 fatal 并透传（见 util/FatalErrors.kt）
                                                    if (FatalErrors.isFatal(cause)) throw cause
                                                    LOG.warn(
                                                        "[M36] native run chain FAILED: ${cause.javaClass.name}: ${cause.message}\n    " +
                                                            cause.stackTrace.take(8).joinToString("\n    at ") { it.toString() },
                                                        cause,
                                                    )
                                                    // M36.5.45：native 执行链失败必须通知 UI（此前仅 LOG.warn，用户看不到任何错误）
                                                    // T-ErrorLocate T-EL-02：富通道并行投递（SQLState/errorCode/驱动位置结构化），
                                                    // 旧 onError(String) 消费方零改动
                                                    // T-ErrorLocateUsability T-ELU-03（O-49）：接上被执行文本长度——
                                                    // 越界钳制此前只在单测生效（调用方全都不传 sqlMaxLength）
                                                    // write-safe: UI-only（纯 Swing/只读，无文档写）
                                                    ApplicationManager.getApplication().invokeLater({
                                                        deliverError(
                                                            onErrorRich, onError, cause,
                                                            cause.message ?: cause.toString(),
                                                            executedSql.length,
                                                        )
                                                    }, com.intellij.openapi.application.ModalityState.any())
                                                }
                                            }
                                    } catch (t: Throwable) {
                                        if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
                                        LOG.warn("[M36] submitTransactionLater FAILED: ${t.javaClass.simpleName}: ${t.message}", t)
                                        onSubmission?.invoke(ExecutionSubmission.BLOCKED)
                                    }
                                }, com.intellij.openapi.application.ModalityState.any())
                                nativeRun = true
                            }
                        }
                        if (!nativeRun) {
                            LOG.info("[M36][EXEC] fallback path (native not used: ${if (demoteToFallback) "demoted" else "no vf psi or doRun missing"}), executeQueries(freshModel)")
                            execute.invoke(console, editor, freshModel, execOptionFinal)
                            submitted = true
                        }
                    }
                    // M36.5.22：此处**不代表执行已发生**——原生路径的提交是异步事务（延迟不可控），
                    // 只有 executeQueries 兜底路径在此刻已真实提交。日志按路径区分，杜绝历轮
                    // "executeQueries invoked OK 但结果陈旧"的误判。
                    if (submitted) {
                        LOG.info("[M36] executeQueries invoked OK (fallback path, submitted synchronously)")
                    } else {
                        LOG.info("[M36] native transaction posted (NOT submitted yet - watch starts at submit)")
                    }
                } catch (e: Exception) {
                    LOG.warn("[M36] executeQueries FAILED: ${e.message}", e)
                    // 执行触发失败：通知调用方（UI 回调须在 EDT）
                    val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                    val msg = cause.message ?: cause.toString()
                    // M36.5.13：ModalityState.any()——模态弹窗（如 File Cache Conflict/密码框）
                    // 打开期间默认 modality 的 invokeLater 冻结（沙盒 12:10:02→12:10:50 实证
                    // 48s 挂起），换入/重试/提示全不跑 → "执行中"卡住。any() 使任务在模态
                    // pump 中照常执行（纯 Swing 更新，安全）。
                    // write-safe: UI-only（纯 Swing/只读，无文档写）
                    ApplicationManager.getApplication().invokeLater({
                        // T-ELU-01/03：经 [deliverError] 收口——富通道优先、**不再**同时投旧通道
                        // （旧通道会抹掉定位按钮），并接上被执行文本长度作越界钳制上界
                        deliverError(onErrorRich, onError, cause, msg, executedSql.length)
                    }, com.intellij.openapi.application.ModalityState.any())
                    return@executeOnPooledThread
                }
                // 兜底路径（executeQueries 同步提交）：监听在此启动；原生路径由事务内
                // afterSubmitted 启动，此处不再重复（防双重监听互相顶掉结果）。
                if (submitted) {
                    afterSubmitted(project, ui, console, onResultsReady, onError, LOG, dsName, demotedOverride = false, sqlText = executedSql, onErrorRich = onErrorRich, onSubmission = onSubmission)
                }
            }
        }
    }.onFailure { e ->
        LOG.warn("[M36] executeOnConsole FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        // T-ELU-01/03：经 [deliverError] 收口（富通道优先 + 提交文本长度钳制上界）。
        // 本对回调在 executeOnPooledThread 的后台线程上（非 EDT）——[deliverError] 只做纯计算
        // 与回调转发，不含 Swing 操作，故不改变既有线程契约。
        deliverError(onErrorRich, onError, cause, cause.message ?: cause.toString(), executedSql.length)
    }
}

/**
 * M36.5.22：**真实提交后**的统一收尾（EDT, ModalityState.any()）。
 *
 * 背景（本轮沙盒 16:53 会话实证 + javap 反编译）：
 * 原生链经 `TransactionGuard.submitTransactionLater` 投递，派发时刻由平台决定且**不可控**
 *（首轮 43ms、次轮 6729ms）。原实现把「8s 连接监控 / onResultsReady 换入 / startResultTabRetry
 * / refreshResultsComponent」全部挂在"提交请求发出"处，于是：
 *   - retry 第 1 拍（400ms）就命中**上一轮**的 Result Tab → M36.5.13 的 forceSwapIn 把它
 *     **强制顶进面板** → 用户看到旧数据（"滞后一轮"）；
 *   - 4.8s 观察窗在真实提交前就关闭 → 之后无人再换入新结果。
 *
 * 修复：所有"提交后"动作改为在真实提交后启动，并以 `preExisting` 快照限定
 * **只认本次提交之后新建的结果 Tab**。
 */

internal fun DataSourceSelector.logDataContextCheck(
    editorBlock: javax.swing.JComponent,
    console: Any,
    editor: com.intellij.openapi.editor.Editor,
    execOption: Any,
    LOG: com.intellij.openapi.diagnostic.Logger,
) {
    // T-Perf-13：诊断探针降 debug——非 debug 模式零开销（此前每次执行一次 DataManager 解析
    // + 数十条反射 + info 刷屏；需要诊断时开 debug 恢复全部输出）
    if (!LOG.isDebugEnabled) return
    // M36.5.13：ModalityState.any()——模态弹窗期间探针照常执行（默认 modality 下 invokeLater
    // 被冻结：沙盒 12:09:55→12:10:50 延迟 55s 实证，探针日志失去诊断价值）
    // write-safe: UI-only（纯 Swing/只读，无文档写）
    ApplicationManager.getApplication().invokeLater({
        runCatching {
            // M36.5.10：整个探针体包 ReadAction——lazy PSI 经 DataManager.getDataContext
            // 在 EDT 无 read action 直查会被 DataValidators 判无效返回 null（探针假阴性，
            // M36.5.9 日志 `PSI_FILE=false` 实为探针自身问题）；动作线程（ActionUpdater BGT
            // 含 read action）不受影响，仅探针需要显式包。
            @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
            ReadAction.compute<Unit, Throwable> {
                val ctx = com.intellij.ide.DataManager.getInstance()
                    .getDataContext(editorBlock)
                // T-M52 修复（verifier 2026-08-29）：DataContext.getData(String) 在 262 既标
                // deprecated-for-removal 又标 Internal，改用 DataKey.getData(ctx) 类型化取值
                val projectOk = com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.getData(ctx) != null
                val editorOk = com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR.getData(ctx) != null
                val clientKey = runCatching {
                    Class.forName("com.intellij.database.console.client.SessionClientHolder")
                        .getField("CLIENT_KEY").get(null)
                            as com.intellij.openapi.actionSystem.DataKey<*>
                }.getOrNull()
                val consoleOk = clientKey != null && clientKey.getData(ctx) != null
                val psiFile = com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE.getData(ctx)
                val psiOk = psiFile != null
                val sqlAtPlace = runCatching {
                    Class.forName("com.intellij.database.console.JdbcConsoleProvider")
                        .getMethod("isSqlAtPlace", PsiFile::class.java, com.intellij.openapi.editor.Editor::class.java)
                        .invoke(null, psiFile, editor)
                }.getOrNull()
                val statementsRange = runCatching {
                    val clientClass = Class.forName("com.intellij.database.console.client.DatabaseScriptingSessionClient")
                    Class.forName("com.intellij.database.script.ScriptModelUtil")
                        .getMethod(
                            "getSelectedStatementsRange",
                            clientClass,
                            com.intellij.openapi.editor.Editor::class.java,
                            execOption.javaClass,
                        )
                        .invoke(null, console, editor, execOption)
                }.getOrNull()
                // M36.5.23g：statementsRange 仍 (0,0) 时的诊断——打印 model 详情定位 stale/方言/光标
                val modelDiag = runCatching {
                    val model = Class.forName(CONSOLE_CLASS).getMethod("getScriptModel").invoke(console)
                    val isActual = model?.javaClass?.getMethod("isActual")?.invoke(model)
                    val stmts = model?.javaClass?.getMethod("statements")?.invoke(model) as? Iterable<*>
                    val tr = model?.javaClass?.getMethod("getTextRange")?.invoke(model)
                    // M36.5.24e：language + 语句 range 诊断——subModel(光标行).statements()
                    // .filter(isStatement) 失败的判定依据
                    val lang = runCatching {
                        model?.javaClass?.getMethod("getLanguage")?.invoke(model) as? com.intellij.lang.Language
                    }.getOrNull()
                    // M36.5.24m：先 toList 再取 detail——statements() 是单次 JBIterable，
                    // 先 take 会消耗迭代器导致 toList 返回 0（探针顺序 bug，23:10 实证）
                    val stmtsList = stmts?.toList() ?: emptyList<Any>()
                    val stmtDetail = stmtsList.take(3).map {
                        val cls = it?.javaClass?.simpleName
                        // SIt 无 getTextRange()，有 range()（ModelItBase，javap 实证）
                        val r = runCatching { it?.javaClass?.getMethod("getTextRange")?.invoke(it) }.getOrNull()
                            ?: runCatching { it?.javaClass?.getMethod("range")?.invoke(it) }.getOrNull()
                        "$cls($r)"
                    }.joinToString(",")
                    val modelIdentity = System.identityHashCode(model)
                    // M36.5.24k/m：subModel/filter 诊断——statementsRange 零长度的直接环节。
                    // getSelectedStatementsRange 的 adjustSelectionRange（isEmpty 分支）：
                    //   subModel(PositionRange(光标行)).statements().filter(isStatement).last()
                    // 返回 null → 回退 selection。PositionRange extends TextRange（javap 实证），
                    // subModel 反射参数须用 TextRange.class（PositionRange.class 精确匹配失败）。
                    val subDiag = runCatching {
                        val posRangeClass = Class.forName("com.intellij.database.script.ScriptModel\$PositionRange")
                        val posRange = posRangeClass.getConstructor(com.intellij.openapi.util.TextRange::class.java)
                            .newInstance(com.intellij.openapi.util.TextRange.create(0, editor.document.textLength))
                        val sub = model?.javaClass
                            ?.getMethod("subModel", com.intellij.openapi.util.TextRange::class.java)
                            ?.invoke(model, posRange)
                        val subStmts = runCatching {
                            (sub?.javaClass?.getMethod("statements")?.invoke(sub) as? Iterable<*>)
                        }.getOrNull()?.toList()
                        val filterCond = runCatching {
                            // 取 sub 的 statements 第一条做 isStatement 判定（经 EvaluationHelper）
                            val first = subStmts?.firstOrNull()
                            val api = first?.javaClass?.getMethod("api")?.invoke(first)
                            // M36.6.2：探针假阴性修正——EvaluationHelper.isStatement 是**实例方法**
                            //（javap：interface EvaluationHelper，EP: LanguageExtension 分发实现），
                            // 旧探针 getMethod("isStatement", apiClass).invoke(null, api) 以 null
                            // 接收者调实例方法必抛 NPE 被 runCatching 吞掉 → "isStatement=null" 自
                            // M36.5.24 起一直是探针自身的假阴性（误导 24d/26 两轮方言修复方向）。
                            // 正确链路：EP.forLanguage(model.language) 取实现 → 实例调用 isStatement。
                            val ep = Class.forName("com.intellij.sql.dialects.EvaluationHelper")
                                .getField("EP").get(null) as com.intellij.lang.LanguageExtension<*>
                            val helperInstance = runCatching {
                                lang?.let { ep.forLanguage(it) }
                            }.getOrNull()
                            val isStmt = runCatching {
                                helperInstance?.javaClass
                                    ?.getMethod("isStatement", api?.javaClass)
                                    ?.invoke(helperInstance, api) as? com.intellij.openapi.util.Condition<*>
                            }.getOrNull()
                            val v = runCatching {
                                @Suppress("UNCHECKED_CAST")
                                (isStmt as? com.intellij.openapi.util.Condition<Any>)?.value(first)
                            }.getOrNull()
                            // M36.6.2：adjustSelectionRange 空 selection 分支复刻（javap 字节码）——
                            // caret=0 时探测范围是 TextRange(0,1)（前两个字符），subScript 对 2 字符
                            // 范围的语句语义（相交/包含）直接决定 statementsRange 是否非零
                            val probe01 = runCatching {
                                val posRangeClass = Class.forName("com.intellij.database.script.ScriptModel\$PositionRange")
                                val pr = posRangeClass.getConstructor(com.intellij.openapi.util.TextRange::class.java)
                                    .newInstance(com.intellij.openapi.util.TextRange.create(0, 1))
                                val sub = model?.javaClass
                                    ?.getMethod("subModel", com.intellij.openapi.util.TextRange::class.java)
                                    ?.invoke(model, pr)
                                val stmts = runCatching {
                                    (sub?.javaClass?.getMethod("statements")?.invoke(sub) as? Iterable<*>)
                                }.getOrNull()?.toList()
                                val firstRange = stmts?.firstOrNull()?.let {
                                    runCatching { it.javaClass.getMethod("range").invoke(it) }.getOrNull()
                                }
                                "${stmts?.size}:${firstRange ?: "none"}"
                            }.getOrElse { "probe01 FAILED:${it.message}" }
                            "firstStmt=${first?.javaClass?.simpleName}(${
                                runCatching { first?.javaClass?.getMethod("range")?.invoke(first) }.getOrNull()
                            }) api=${api?.javaClass?.simpleName} helper=${helperInstance?.javaClass?.name} isStatement=$v probe01=$probe01"
                        }.getOrElse { "filter FAILED:${it.message}" }
                        "stmts=$stmtsList.size subModelStmts=${subStmts?.size} $filterCond"
                    }.getOrElse { "subDiag FAILED:${it.message}" }
                    // M36.5.24c：document 关系诊断——console.getDocument() 与 editor.document 是否
                    // 同一对象；console.getFile() 的 PSI 状态；consoleVf document 内容。
                    val docRel = runCatching {
                        val consoleDoc = Class.forName(CONSOLE_CLASS).getMethod("getDocument").invoke(console)
                        val consoleVf = Class.forName(CONSOLE_CLASS).getMethod("getVirtualFile").invoke(console)
                        val fileDoc = if (consoleVf != null) {
                            com.intellij.openapi.fileEditor.FileDocumentManager
                                .getInstance().getDocument(consoleVf as com.intellij.openapi.vfs.VirtualFile)
                        } else null
                        val file = runCatching {
                            Class.forName(CONSOLE_CLASS).getMethod("getFile").invoke(console)
                                as? com.intellij.psi.PsiFile
                        }.getOrNull()
                        "consoleDoc(${System.identityHashCode(consoleDoc)}:${
                            (consoleDoc as? com.intellij.openapi.editor.Document)?.textLength
                        }) editorDoc(${System.identityHashCode(editor.document)}:${editor.document.textLength}) " +
                            "same=${consoleDoc === editor.document} " +
                            "vfDoc(${System.identityHashCode(fileDoc)}:${
                                (fileDoc as? com.intellij.openapi.editor.Document)?.textLength
                            }) vfDocSameEditorDoc=${fileDoc === editor.document} " +
                            "consoleFile=${file?.javaClass?.simpleName}:${file?.textLength}:" +
                            "${file?.virtualFile?.name}"
                    }.getOrElse { "docRel FAILED:${it.message}" }
                    // M36.6.2：启用链模型身份判定——ScriptModelUtil.getSelectedStatementsRange 的
                    // 模型来自 DatabaseScriptingSessionClient.getScriptModel()（javap offset 30
                    // invokeinterface 实证）；SessionClientHolder<S>=<JdbcConsole,...> 说明 "client"
                    // 即 JdbcConsole 本体（则读到的就是我们替换的 myScriptModel）。isInstance 一次
                    // 判死，排除"替换未到达启用链"的可能
                    val clientCheck = runCatching {
                        val clientClass = Class.forName("com.intellij.database.console.client.DatabaseScriptingSessionClient")
                        val isClient = clientClass.isInstance(console)
                        val clientModel = if (isClient) {
                            clientClass.getMethod("getScriptModel").invoke(console)
                        } else null
                        val consoleModel = Class.forName(CONSOLE_CLASS).getMethod("getScriptModel").invoke(console)
                        "consoleIsClient=$isClient " +
                            "clientModelId=${clientModel?.let { System.identityHashCode(it) }} " +
                            "consoleModelId=${System.identityHashCode(consoleModel)} " +
                            "sameModel=${clientModel === consoleModel}"
                    }.getOrElse { "clientCheck FAILED:${it.message}" }
                    "modelIsActual=$isActual stmts=$stmtsList.size modelRange=$tr lang=${lang?.id} stmtsDetail=[$stmtDetail] modelId=$modelIdentity docLen=${editor.document.textLength} caret=${editor.caretModel.offset} execSelection=${runCatching { execOption.javaClass.getField("execSelection").getInt(execOption) }.getOrNull()} $docRel $clientCheck | $subDiag"
                }.getOrNull()
                LOG.debug(
                    "[M36] dataContext check: PROJECT=$projectOk, EDITOR=$editorOk, CLIENT_KEY=$consoleOk, PSI_FILE=$psiOk, " +
                        "isSqlAtPlace=$sqlAtPlace, statementsRange=$statementsRange, $modelDiag",
                )
            }
        }.onFailure { e ->
            LOG.warn("[M36] dataContext check FAILED: ${e.message}")
        }
    }, com.intellij.openapi.application.ModalityState.any())
}

/**
 * T-TS-09：控制台编辑器不可得时的用户可读提示（[prepareInConsole] 两条静默分支共用）。
 *
 * 复用既有键 `execute.failed`（"执行 SQL 失败：{0}"）＋技术性明细参数：明细是诊断串而非文案，
 * 与 `execute.tempFile.failed` 传 [lastTempFileFailure] 同款（新增专用键要动双语 bundle，
 * 不在本任务写集内）。
 *
 * @param path 触发的分支（sync = 非 EDT 同步轮询 / async = EDT 异步等待）——排障时据此定位，
 *             两条分支的成因不同（同步：调用方在非 EDT；异步：EDT 首查未命中后后台轮询）
 */

internal fun DataSourceSelector.prepareInConsole(
    project: Project,
    session: Any,
    vf: com.intellij.openapi.vfs.VirtualFile,
    executeAfterPrepare: Boolean,
    dataSourceName: String?,
    onConsole: ((javax.swing.JComponent) -> Unit)?,
    onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)?,
    onError: ((String) -> Unit)?,
    onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
    wholeScriptExec: Boolean = false,
    prepareOwner: QueuedExecuteIntent? = null,
    dialectGateRequest: ExecutionDialectGate.Request? = null,
    onDialectGate: ((ExecutionDialectGate.Verdict) -> Unit)? = null,
    onSubmission: ((ExecutionSubmission) -> Unit)? = null,
    isPrepareActive: () -> Boolean = { true },
) {
    if (!isPrepareActive()) return
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
    runCatching {
        val providerClass = Class.forName(CONSOLE_PROVIDER_CLASS)
        val sessionClass = Class.forName(SESSION_CLASS)

        // 1. attachConsole：将 JdbcConsole 挂到临时文件（同一 session）
        val attach = providerClass.getMethod(
            "attachConsole",
            Project::class.java,
            sessionClass,
            com.intellij.openapi.vfs.VirtualFile::class.java,
        )
        attach.isAccessible = true
        // T-ReverseParenWira：attachConsole 包 WriteIntentReadAction（既非 plain ReadAction，
        // 亦非裸 EDT）——三轮踩坑收口：
        //   · T-PreviewDocLock2 F1 包 plain ReadAction 压读权限断言 → DBN attachConsole 底层
        //     new EditorImpl 的 setHighlighter 走 WriteIntentReadAction，平台锁层明文禁
        //     「plain ReadAction 内再开 WIRA」硬抛（IllegalStateException ← setHighlighter:1832
        //     ← ConsoleExecutionEditor.<init>），被 runCatching 吞成 InvocationTargetException:null
        //     → 控制台从未挂载。
        //   · T-PreviewSlow F2 整段拆包还原裸 EDT → 修了 setHighlighter 嵌套，但 262 同一条构造链
        //     上 ConsoleExecutionEditor.<init>:54 → FileDocumentManager.getDocument 要求调用方已
        //     持读权限，裸 EDT 直调硬抛 RuntimeExceptionWithAttachments「Read access is allowed
        //     from inside read-action only」（真机堆栈实证），又被外层 runCatching 吞掉静默降级。
        //   · 本轮包 WIRA：隐含读权限满足 getDocument 断言；内部 setHighlighter 的 WIRA 属
        //     WIRA-in-WIRA 重入——平台只禁 plain ReadAction→WIRA，重入合法。同文件
        //     T-ExecCopyFix F2b createTempPsiFile 冷路径同款先例（Computable 单类型实参，
        //     243~262 交集——T-Wia262）。V-2 留 262 真机 idea.log 验证无新嵌套硬抛。
        val console = com.intellij.openapi.application.WriteIntentReadAction
            .compute<Any?> { attach.invoke(null, project, session, vf) }
        LOG.info("[M36] attachConsole -> $console")
        if (console == null) {
            onSubmission?.invoke(ExecutionSubmission.BLOCKED)
            abortPrepareGate(prepareOwner, null, LOG)
            return
        }

        // 在回调/后续准备可能失败之前登记已创建的原生view；不依赖prepared/shared缓存。
        NativeConsoleViewLifetime.remember(project, console)
        val consoleClass = Class.forName(CONSOLE_CLASS)

        // 2. 执行用编辑器：按 console 文档反查（先于内嵌——编辑器随 console 创建即初始化）。
        // T-ReverseParenWira：单独为 getDocument 包 plain ReadAction（T-PreviewSlow F2 注释
        // 预先授权方案）——此句只取 attachConsole 已构建好的 console 文档，不 new EditorImpl、
        // 不触发 WIRA，plain ReadAction 即满足 262 读权限断言，无嵌套风险。
        @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
        val document = ReadAction.compute<com.intellij.openapi.editor.Document?, Throwable> {
            consoleClass.getMethod("getDocument").invoke(console)
                as? com.intellij.openapi.editor.Document
        }
        // T-Perf-13：EDT 上不再 repeat(20){Thread.sleep(20)} 忙等编辑器实例（最多 400ms 冻结）——
        // 首查即中（绝大多数：编辑器随 attachConsole 同步构造）或后台线程调用：同步短轮询（原语义）；
        // EDT 首查未命中：后台轮询线程 20ms 一查，命中后回 EDT 继续余下准备链（prepareAfterEditorResolved）。
        val editorFactory = com.intellij.openapi.editor.EditorFactory.getInstance()
        fun findEditor(): com.intellij.openapi.editor.Editor? =
            document?.let { editorFactory.getEditors(it).firstOrNull() }
        val firstEditor = findEditor()
        if (firstEditor != null || !ApplicationManager.getApplication().isDispatchThread) {
            var editor = firstEditor
            if (editor == null) {
                repeat(20) {
                    if (editor != null) return@repeat
                    editor = findEditor()
                    if (editor == null) Thread.sleep(20)
                }
            }
            LOG.info("[M36] editor=$editor")
            if (editor == null) {
                // T-TS-09：原为静默 `?: return`——非 EDT 调用方（本分支只在非 EDT 到达）在控制台
                // 文档无人建编辑器时永久拿不到任何回调，UI 停在「执行中」且 errors=[]（驱动管线打通
                // 前观测到的「120s 不就绪 + 错误为空」正是此处与下方异步分支两处静默共同造成）。
                // 只补通知，不改准备链语义（onEditor/onConsole 仍只在成功路径触发）。
                LOG.warn("[M36] console editor unavailable after 400ms wait (sync path), abort prepare")
                // write-safe: UI-only（纯 Swing/只读，无文档写）——onError 由面板直接刷状态栏，
                // 本分支在非 EDT 线程上，须归位 EDT（同下方异步分支语义）
                ApplicationManager.getApplication().invokeLater({
                    onError?.invoke(consoleEditorUnavailableMessage("sync"))
                }, com.intellij.openapi.application.ModalityState.any())
                // T-FirstExecResultInvisible F1：链就此终结（未到消费点）——门持有链复位 +
                // 排队执行意图回落错误回调（原子位操作任意线程可调）
                abortPrepareGate(prepareOwner, consoleEditorUnavailableMessage("sync"), LOG)
                return
            }
            val ed = editor
            // M36.2：编辑器就绪回调（调用方注入插件补全上下文，与原生补全叠加）
            onEditor?.invoke(ed)
            prepareAfterEditorResolved(
                project, vf, executeAfterPrepare, dataSourceName, onConsole, onError, console, document, ed,
                onErrorRich, wholeScriptExec, prepareOwner, dialectGateRequest, onDialectGate, onSubmission,
                isPrepareActive,
            )
        } else {
            ApplicationManager.getApplication().executeOnPooledThread {
                var editor: com.intellij.openapi.editor.Editor? = null
                repeat(20) {
                    if (editor != null) return@repeat
                    editor = findEditor()
                    if (editor == null) Thread.sleep(20)
                }
                val resolved = editor
                // T-WSC-02：本块经 prepareAfterEditorResolved 写 console 文档（`:1354`
                // WriteAction.compute { writeVfTextViaDocument }）——文档写回调一律 write-safe
                // 模态，any() 恒 write-unsafe（见 DocWriteContext KDoc）。代价：模态弹窗存续期
                // 准备链延后到弹窗关闭，正是平台对「改模型」要求的语义。
                ApplicationManager.getApplication().invokeLater({
                    if (!isPrepareActive()) return@invokeLater
                    LOG.info("[M36] editor=$resolved (async wait)")
                    if (resolved != null) {
                        onEditor?.invoke(resolved)
                        runCatching {
                            prepareAfterEditorResolved(
                                project, vf, executeAfterPrepare, dataSourceName, onConsole, onError, console,
                                document, resolved, onErrorRich, wholeScriptExec, prepareOwner,
                                dialectGateRequest, onDialectGate, onSubmission, isPrepareActive,
                            )
                        }.onFailure { e ->
                            NativeConsoleViewLifetime.unwrapAndRethrowCritical(e)
                            LOG.warn("[M36] prepareInConsole(editor async wait) FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                            onError?.invoke(cause.message ?: cause.toString())
                            // T-FirstExecResultInvisible F1：准备链在此终结（未到消费点）——
                            // 门持有链复位 + 排队执行意图回落错误回调
                            abortPrepareGate(prepareOwner, cause.message ?: cause.toString(), LOG)
                        }
                    } else {
                        // T-TS-09：原为「无 else」——EDT 调用方（测试/面板准备链的常态）轮询 400ms
                        // 仍取不到编辑器时，本函数就此静默结束：调用方既无 onError 也无 callback，
                        // 面板停在「执行中」且 errors=[]，与上面的同步分支同款不可诊断（20260918
                        // 基线 idea.log `[M36] editor=null (async wait)` 实证）。只补通知，不改语义。
                        LOG.warn("[M36] console editor unavailable after 400ms wait (async path), abort prepare")
                        onError?.invoke(consoleEditorUnavailableMessage("async"))
                        // T-FirstExecResultInvisible F1：同上——链终结复位 + 意图回落
                        abortPrepareGate(prepareOwner, consoleEditorUnavailableMessage("async"), LOG)
                    }
                }, com.intellij.openapi.application.ModalityState.nonModal())
            }
        }
    }.onFailure { e ->
        NativeConsoleViewLifetime.unwrapAndRethrowCritical(e)
        LOG.warn("[M36] prepareInConsole FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
        // M36.3：通知调用方错误，确保 UI 从「执行中」状态恢复
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        // T-PreviewExecDs-03 F3：console 被清扫 dispose 的处置（prepare 链后段撞上时同款
        // 特判，见 prepareAfterEditorResolved onFailure）
        val failureMsg: String = if (cause is com.intellij.util.IncorrectOperationException &&
            cause.message?.contains("already been disposed") == true
        ) {
            LOG.warn("[M36] console disposed during prepareInConsole (swept), clear caches for retry: ${cause.message?.take(120)}")
            clearPrepared()
            dropSharedConsole()
            MpSqlVisualizerBundle.message("execute.console.disposed")
        } else {
            cause.message ?: cause.toString()
        }
        onSubmission?.invoke(ExecutionSubmission.BLOCKED)
        onError?.invoke(failureMsg)
        // T-FirstExecResultInvisible F1：准备链在此终结（未到消费点）——门持有链复位 +
        // 排队执行意图回落错误回调
        abortPrepareGate(prepareOwner, failureMsg, LOG)
    }
}

/**
 * T-FirstExecResultInvisible F1：准备链在到达消费点（[prepareAfterEditorResolved] 的排队
 * 意图消费）之前终结时的兜底——门持有链复位单飞位 + 已排队执行意图回落意图自身 onError
 * （意图所属面板在 executeSql 里已 showLoading 并拿到 callback(true)，静默丢弃会让状态条
 * 恒「执行中」）。PASS_THROUGH 预览链不得调用（不持有门、也无属于它的排队意图）。
 */

internal fun DataSourceSelector.refreshConsoleModel(
    project: Project,
    console: Any,
    consoleVf: com.intellij.openapi.vfs.VirtualFile,
    LOG: com.intellij.openapi.diagnostic.Logger,
) {
    runCatching {
        val consoleClass = Class.forName(CONSOLE_CLASS)
        // M36.5.26：model 的 language 必须用 **SqlDialect（方言 Language 实例）**——
        // `LanguageUtil.getLanguageForPsi` 返回 SQL 插件的 MySQLLanguage（id="MySQL"），
        // 而 DBN 的 `EvaluationHelper.isStatement` 经 `LanguageExtension.forLanguage` 注册在
        // **SqlDialectMappings 的 SqlDialect 实例**上（00:26 探针实证 isStatement=null）——
        // 两者不匹配导致 filter(isStatement) 抛弃全部语句 → statementsRange=(0,0) → ▶ 灰。
        val lang = runCatching {
            Class.forName("com.intellij.sql.dialects.SqlDialectMappings")
                .getMethod("getMapping", Project::class.java, com.intellij.openapi.vfs.VirtualFile::class.java)
                .invoke(null, project, consoleVf) as? com.intellij.lang.Language
        }.getOrNull() ?: com.intellij.lang.LanguageUtil.getLanguageForPsi(project, consoleVf)
        val fresh = Class.forName("com.intellij.sql.script.SqlScriptModel")
            .getConstructor(
                Project::class.java,
                com.intellij.openapi.vfs.VirtualFile::class.java,
                com.intellij.lang.Language::class.java,
            )
            .newInstance(project, consoleVf, lang)
        val freshStmts = runCatching {
            (fresh.javaClass.getMethod("statements").invoke(fresh) as? Iterable<*>)
        }.getOrNull()?.toList()
        val freshLang = runCatching {
            fresh.javaClass.getMethod("getLanguage").invoke(fresh)
        }.getOrNull()
        if (freshStmts == null || freshStmts.isEmpty()) {
            LOG.info(
                "[M36] refreshConsoleModel: model empty (lang=$freshLang, stmts=${freshStmts?.size}), keep console model",
            )
            return
        }
        // splitMode 对齐：isActual() = getSplitScriptMode(myScript) == settings.splitScriptMode；
        // 调 settings 使 fresh.isActual()=true → set 持久化（否则 getScriptModel() 走 updateAndGet
        // 重建为 console.getFile() 的 FileScript → 又失效）。副作用：DBN 全局 settings 的
        // splitScriptMode 改变（target 是基于 Script 的合理值，符合 DBN 派发设计）。
        val myScriptField = fresh.javaClass.getDeclaredField("myScript").apply { isAccessible = true }
        val myScript = myScriptField.get(fresh)
        val targetMode = runCatching {
            Class.forName("com.intellij.database.script.ScriptModelUtilCore")
                .getMethod("getSplitScriptMode", Class.forName("com.intellij.sql.script.Script"))
                .invoke(null, myScript)
        }.getOrNull()
        val settings = Class.forName("com.intellij.database.settings.DatabaseSettings")
            .getMethod("getSettings").invoke(null)
        val splitField = settings.javaClass.getField("splitScriptMode")
        val oldSplit = splitField.get(settings)
        if (targetMode != null && targetMode != oldSplit) {
            splitField.set(settings, targetMode)
        }
        val freshIsActual = runCatching {
            fresh.javaClass.getMethod("isActual").invoke(fresh)
        }.getOrNull()
        // 反射替换 myScriptModel 缓存（getScriptModel 首查缓存）
        val field = consoleClass.getDeclaredField("myScriptModel")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(console) as java.util.concurrent.atomic.AtomicReference<Any>).set(fresh)
        preparedScriptModel = fresh
        if (sharedConsole === console) sharedScriptModel = fresh
        // set 后立即验证：getScriptModel() 是否真的返回 fresh（22:38 实证 set 后 40ms
        // post-probe 仍 stmts=0——identityHashCode 对比确认 set 持久化与否）
        val afterSet = runCatching {
            consoleClass.getMethod("getScriptModel").invoke(console)
        }.getOrNull()
        val afterStmts = runCatching {
            (afterSet?.javaClass?.getMethod("statements")?.invoke(afterSet) as? Iterable<*>)
        }.getOrNull()?.toList()?.size
        LOG.info(
            "[M36] refreshConsoleModel: replaced with explicit-language model: " +
                "newLang=$freshLang, stmts=${freshStmts.size}, freshIsActual=$freshIsActual, " +
                "splitMode=$oldSplit->$targetMode, setAfterGetIdentity=${
                    System.identityHashCode(afterSet)
                }, setAfterStmts=$afterStmts",
        )
        // M36.5.24i：set 后 fresh 可能被 document modificationStamp 变化（editor 初始化/
        // highlight 触发）击穿 sealed 缓存 → statements() 变 0（22:44 实证 set 后 20ms
        // stmts=1→0、identity 不变）。启动 Timer 周期重查：若 console.getScriptModel()
        // 的 statements 为空则重新构造 fresh 并 set（document 稳定后 sealed 不再重建），
        // 连续稳定即停止，最多重试 6 次（约 6s，覆盖 editor 初始化窗口）。
        var stable = 0
        var retries = 0
        val timer = javax.swing.Timer(1000, null)
        timer.addActionListener {
            runCatching {
                val cur = consoleClass.getMethod("getScriptModel").invoke(console)
                val curStmts = runCatching {
                    (cur?.javaClass?.getMethod("statements")?.invoke(cur) as? Iterable<*>)
                }.getOrNull()?.toList()
                if (curStmts != null && curStmts.isNotEmpty()) {
                    stable++
                    if (stable >= 2) {
                        timer.stop()
                        LOG.info("[M36] refreshConsoleModel: model stable (stmts=${curStmts.size})")
                        // stable 后最终探针——确认 statementsRange 恢复非零（▶ isEnabled 依据）
                        runCatching {
                            val editorNow = preparedEditor ?: return@runCatching
                            val execNow = preparedExecOption ?: return@runCatching
                            val topNow = preparedTopBlock ?: return@runCatching
                            logDataContextCheck(topNow, console, editorNow, execNow, LOG)
                        }.onFailure { e ->
                            LOG.warn("[M36] refreshConsoleModel stable-probe FAILED: ${e.message}")
                        }
                    }
                } else {
                    stable = 0
                    retries++
                    if (retries > 6) {
                        timer.stop()
                        LOG.warn("[M36] refreshConsoleModel: give up after $retries retries (stmts=${curStmts?.size})")
                        return@addActionListener
                    }
                    // 重新构造 + set（document modificationStamp 已稳定）
                    val freshN = Class.forName("com.intellij.sql.script.SqlScriptModel")
                        .getConstructor(
                            Project::class.java,
                            com.intellij.openapi.vfs.VirtualFile::class.java,
                            com.intellij.lang.Language::class.java,
                        )
                        .newInstance(project, consoleVf, lang)
                    val nStmts = runCatching {
                        (freshN.javaClass.getMethod("statements").invoke(freshN) as? Iterable<*>)
                    }.getOrNull()?.toList()?.size
                    if (nStmts != null && nStmts > 0) {
                        val f2 = consoleClass.getDeclaredField("myScriptModel")
                        f2.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        (f2.get(console) as java.util.concurrent.atomic.AtomicReference<Any>).set(freshN)
                        preparedScriptModel = freshN
                        if (sharedConsole === console) sharedScriptModel = freshN
                        LOG.info("[M36] refreshConsoleModel: retry#$retries re-set fresh (stmts=$nStmts)")
                    } else {
                        LOG.info("[M36] refreshConsoleModel: retry#$retries fresh empty (stmts=$nStmts), keep current")
                    }
                }
            }.onFailure { e ->
                LOG.warn("[M36] refreshConsoleModel timer FAILED: ${e.message}")
                timer.stop()
            }
        }
        timer.isRepeats = true
        timer.start()
        // 重建后探针：确认 statementsRange 恢复非零（▶ isEnabled 依据）
        runCatching {
            val editorNow = preparedEditor ?: return@runCatching
            val execNow = preparedExecOption ?: return@runCatching
            val topNow = preparedTopBlock ?: return@runCatching
            logDataContextCheck(topNow, console, editorNow, execNow, LOG)
        }.onFailure { e ->
            LOG.warn("[M36] refreshConsoleModel post-probe FAILED: ${e.message}")
        }
    }.onFailure { e ->
        LOG.warn("[M36] refreshConsoleModel FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}

/** M36.5.2：主题化完整控制台 SplitPane（顶部块 40% / 结果区 60%）——M36.4 形态，两处构建共用。 */
