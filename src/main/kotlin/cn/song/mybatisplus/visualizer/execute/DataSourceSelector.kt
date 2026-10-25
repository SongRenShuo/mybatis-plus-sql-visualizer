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

/**
 * 原生数据源选择器（M36，反射隔离）：选数据源 → Database 控制台直接执行。
 *
 * 流程（选中后自动执行，等价原生 Ctrl+Enter 全链路）：
 *  1. 创建临时 .sql 文件（含 SQL）+ PsiFile —— 同时作为 [VF] 控制台挂载文件
 *  2. 反射 `DatabaseRunners.getAttachDataSourceRunners(psiFile, sql, consumer)` 获取
 *     原生 Runner 列表（与原生 Ctrl+Enter 数据源列表同源；consumer 在 runner.run 后收到
 *     [com.intellij.database.console.session.DatabaseSession]）
 *  3. 选中 runner → `run()` 内部建 session 并回调 consumer
 *  4. consumer 内续接执行链：`JdbcConsoleProvider.attachConsole(project, session, vf)`
 *     将控制台挂到临时文件 → 打开编辑器 → `console.executeQueries(editor, scriptModel, option)`
 *     直接执行（结果在控制台下方原生展示，无需手动 Ctrl+Enter）
 *
 * **关键教训**（多轮迭代定位）：
 *  - `MyRunner` 是包私有类 → 反射须 `isAccessible = true`
 *  - `runInDataSource`/`MyRunner.run()` 只**建 session 不执行**——SQL 执行必须由
 *    consumer 回调续接 `attachConsole + executeQueries`（此前传空 Consumer 导致"选中无反应"）
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
object DataSourceSelector {

    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    internal const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

    /** DatabaseRunners 类名（反射入口）。 */
    private const val RUNNERS_CLASS = "com.intellij.database.console.DatabaseRunners"

    /** 执行链反射目标类名。 */
    internal const val CONSOLE_PROVIDER_CLASS = "com.intellij.database.console.JdbcConsoleProvider"
    internal const val CONSOLE_CLASS = "com.intellij.database.console.JdbcConsole"
    internal const val SESSION_CLASS = "com.intellij.database.console.session.DatabaseSession"

    /** last-used 数据源记忆 key（prepareConsole 静默复用，PropertiesComponent per-project）。 */
    private const val LAST_RUNNER_KEY = "mpsql.visualizer.last.runner"

    /**
     * T-SI-02：last-used 记忆的**身份键**（数据源 UUID，LocalDataSource.getUniqueId）。
     * 旧键（显示名）继续读写，兼容一个版本后废弃（见 CHANGELOG [Unreleased]）；命中判定以
     * UUID 为主、显示名为回退——显示名同名多条时旧实现静默取首条，属「猜」，已弃。
     */
    private const val LAST_RUNNER_ID_KEY = "mpsql.visualizer.last.runner.id"

    /**
     * T-ModuleScopedLastUsed W2：已写过的**模块键清单**（逗号分隔）。
     *
     * 为什么需要单独记清单：`PropertiesComponent` 的公开 API 只有 `unsetValue/getValue/setValue`
     * （`javap` 实证，无列举全部键的方法），故「清空全部层级」时无法靠前缀扫描发现有哪些模块桶。
     * 清单在首次写该模块记忆时登记（[registerLastUsedModuleKey]），清空后一并移除。
     */
    private const val _LAST_USED_MODULE_KEYS_KEY = "mpsql.visualizer.last.runner.modules"

    /**
     * M36.5.21b：构建版本戳——与源码同文件维护，每次执行打日志。
     * 判定法：沙盒日志若与本串不符 = 沙盒跑旧 jar（未重启 IDE），先重启再排查。
     */
    internal const val BUILD_STAMP = "M36.8-exec-silent-noop"

    /** M36.5.76：OUTFILL 未锚定诊断日志节流（EDT 串行访问，无需同步）。 */
    internal var lastOutFillDiagAt = 0L

    // M36.5：已准备控制台缓存（prepareConsole 填充，executePrepared 消费；组件本体由 IDE 管理）
    internal var preparedConsole: Any? = null
    internal var preparedEditor: com.intellij.openapi.editor.Editor? = null
    internal var preparedScriptModel: Any? = null
    internal var preparedExecOption: Any? = null
    internal var preparedUi: Any? = null
    internal var preparedDialectGateRequest: ExecutionDialectGate.Request? = null
    internal var preparedDialectGateCallback: ((ExecutionDialectGate.Verdict) -> Unit)? = null

    // M36.5.4：已备控制台的 UI 块——顶部块（原生头部工具栏+编辑器 DataProvider 包装）、结果区、
    // 完整 SplitPane。内嵌统一为完整 SplitPane（三条执行路径结果都可见）；executeOnConsole 的
    // onResultsReady 换入兜底仅在完整 UI 缺失时按需重建（SplitPane 构造会 reparent 顶部块，
    // 已被内嵌"偷走"的顶部块不可复用——预建实例缺子组件的教训见 M36.5.2）。
    internal var preparedTopBlock: javax.swing.JComponent? = null
    internal var preparedResultComponent: javax.swing.JComponent? = null
    internal var preparedFullComponent: javax.swing.JComponent? = null

    // M36.5.5：共享控制台缓存（跨预览复用，原生 IDE 语义：一个数据源一个常驻控制台）。
    // 沙盒三次实证（00:22 run2 / 00:27 run5 / 01:40 runB）：每预览新建 session 的模式下，
    // **关闭上一个预览弹窗后，下一个会话的连接永不发起**（无任何日志/报错，结果区恒
    // "0 rows of 0+"；与 dumb mode 无关——同窗口内首个会话在索引中照样秒连）。复用同一
    // 控制台后不再有第二连接，首次连接保持温热；复用时仅同步 SQL 进控制台文档 + 重挂 UI。
    internal var sharedConsole: Any? = null
    internal var sharedEditor: com.intellij.openapi.editor.Editor? = null
    internal var sharedScriptModel: Any? = null
    internal var sharedExecOption: Any? = null
    internal var sharedUi: Any? = null
    internal var sharedFullComponent: javax.swing.JComponent? = null
    internal var sharedDataSourceName: String? = null
    internal var sharedDialectGateRequest: ExecutionDialectGate.Request? = null
    internal var sharedDialectGateCallback: ((ExecutionDialectGate.Verdict) -> Unit)? = null

    /** T-PreviewFlash：mpsql-exec.sql 的 vf+psi 配对缓存（路径稳定，vf 有效即复用，吸收每次预览的 VFS refresh + findFile 慢操作）。
     *  T-ExecSlowOps F3：[prewarmExecTempFile] 后台线程成为第二写方后须 volatile 跨线程可见。 */
    @Volatile
    private var cachedTempPsiFile: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? = null
    /** T-PreviewExecDs-02 F1：缓存归属项目名（跨项目拒绝诊断用，与 cachedTempPsiFile 同生命周期）。 */
    @Volatile
    private var cachedTempPsiProjectName: String? = null

    /**
     * T-ExecCopyFix F2a：最近一次 createTempPsiFile 失败的阶段原因（stage 标识或异常摘要），
     * 成功时清空——供 chooseAndPrepare 的 onError 文案携带，用户截图反馈的笼统
     * "Failed to create temporary SQL file" 自此可归因。
     */
    @Volatile
    internal var lastTempFileFailure: String? = null

    /** T-PreviewFlash：已 setMapping 的 vf（同文件幂等短路，避免每次预览重设方言触发 push all dumb）。
     * T-ExecSlowOps F3：BGT 预热链也会读写，volatile 保证跨线程可见。 */
    @Volatile
    internal var dialectMappedVf: com.intellij.openapi.vfs.VirtualFile? = null

    /**
     * O-136 T-PreviewExecDsDialect：[dialectMappedVf] 当前映射到的方言 language id
     * （null/「MySQL」= 旧行为）。与 vf 字段同生命周期成对回写——幂等短路判定为
     * 「同 vf **且同方言**」，目标数据源方言切换（MySQL↔DM）后同 vf 也必须重映射。
     */
    @Volatile
    internal var dialectMappedLanguageId: String? = null

    /** T-ExecSlowOps F2：exec 预热进行中标记（并发预览只跑一条真实链，其余直接返回）。 */
    private val execPrewarmInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * T-UiFixGate9 测试缝：exec 预暖链在途标记读数。victim 面板测试（rerender 触发预暖链）
     * 收尾前等「链静止」（先见置位、再候复位），让写收敛落在用例生命周期内——链跨出用例后，
     * 测试边界迟到的 VFS 会话会命中写窗口的「已改未存」态，MemoryDiskConflictResolver
     * 收集冲突并在收尾 EDT 泵处理时抛 CompoundRuntimeException 杀无辜测试。
     */
    internal fun execPrewarmInFlightForTest(): Boolean = execPrewarmInFlight.get()

    /**
     * T-PrewarmWilDeadlock W2：exec 预热整链预算——预热是投机性增强，链内任何平台等待
     * （VFS refresh / readAction / EDT 派发）挂死都必须有界放弃（曾因 BGT WIRA×同步 refresh
     * 自锁而永久驻留），超时放弃后弹窗冷路径按需重建兜底。
     */
    private const val EXEC_PREWARM_BUDGET_MS = 30_000L

    /** M36.5.6：结果区换入回调（准备阶段挂 ContentManager 监听，任一路径出结果即换入；复用时由新面板接管）。 */
    internal var pendingOnConsole: ((javax.swing.JComponent) -> Unit)? = null

    /** M36.5.9：最近一次换入宿主的结果区组件实例（执行后重取刷新的去重依据）。 */
    internal var lastLiveResultSwapped: java.awt.Component? = null

    /** M36.5.52：最近一次已上报的 SQL 错误文本——早检去重用（避免 console history 里上次
     * 执行的残留错误每次早检重复上报；同一错误用户已见过则不再打扰）。 */
    internal var lastReportedError: String? = null

    /** M36.5.57：本次执行开始时 console history 的长度基线——早检只扫**本次执行之后**新增的
     * 文本，忽略 history 里的旧错误残留（否则正常 SQL 会把上个失败的错误"带过来"，用户实测）。 */
    // T-PerfSweep2-01：internal 供 ConsoleErrorExtractTest 注入基线（生产路径仅 :2237 赋值）。
    internal var consoleErrorBaseline: Int = 0

    /** T-CEC-01：最近一次已记入日志的「未命中判据的控制台尾部」——取证日志去重用（每次执行至多
     * 记一条，避免 400ms 拍刷屏）。 */
    @Volatile
    internal var lastUnrecognizedTail: String? = null

    /** T-ExecSilentNoop：本次执行的提示已发出标记——8s 连接监控与 40 拍超时窗口共用，
     * 保证同一次执行只报一次提示（此前二者各按同一 key 报一次，用户见两次红字）。 */
    @Volatile
    internal var executionHintReported = false

    /**
     * M36.5.10：执行提交代际计数——executeQueries 提交经 DumbService.runWhenSmart 挂起期间，
     * 若面板清空（clearPrepared）/新预览建立新控制台上下文（prepareInConsole/reuseSharedConsole），
     * 代际不匹配的挂起提交直接放弃（否则会在新文档上执行旧预览的 SQL——文档共享）。
     */
    @Volatile
    internal var executionGeneration = 0

    /**
     * T-FirstExecResultInvisible F1：准备链单飞 + 执行意图排队门——打开预览的准备链未落地时
     * 点执行不再建第二条临时文件/会话/控制台链（H1 双准备链竞态的结构性消除），排队意图由
     * 完成准备的门持有链按 executePrepared 口径消费。纯逻辑见 [PrepareSingleFlightGate]。
     */
    internal val prepareGate = PrepareSingleFlightGate()

    /**
     * M36.5.44：结果换入代际——每次**真实提交**递增（executeOnConsole 的 afterSubmitted 前），
     * 作废上一个执行遗留的 refreshResultsComponent Timer。跨执行竞态根因：SQL1 执行后起
     * refresh Timer A，SQL2 执行后起 Timer B；若 SQL1 的结果组件重建晚于 SQL2 换入，Timer A
     * 拿到旧结果组件会**覆盖** SQL2 的新结果（"滞后一轮"用户实证）。代际不匹配的 Timer 回调
     * 直接丢弃，杜绝互踩。
     */
    @Volatile
    internal var swapGeneration = 0

    // M36.5.7：共享缓存的分件存储——复用/换入时按**实时结果区组件**重建完整 SplitPane。
    // （组合组件引用在面板分离/重挂后会过期：M36.5.5 复用面板空显示、M36.5.6 第二次预览
    // 结果栏消失、contentAdded 换入未写回共享缓存导致复用嵌了旧编辑器块，均是此类过期引用。）
    internal var sharedTopBlock: javax.swing.JComponent? = null

    /** 查询的目标或原始渲染方言未知时的确认测试缝；生产为 null 时走本地化确认框。 */
    @Volatile
    internal var unknownTargetQueryConfirmationForTest: ((ExecutionDialectGate.Verdict) -> Boolean)? = null

    /**
     * O-145 W7：最终实际目标方言闸门统一处置。request 为空保持所有旧调用逐字行为；
     * 非空时回调先收到判定，随后按状态继续、确认或阻断。AUTO_RERENDER_REQUIRED 必停当前点击，
     * 由宿主无损重渲染后要求用户复核并再次点击，绝不在同一次点击里偷偷改写后提交。
     */
    internal fun allowDialectGate(
        project: Project,
        request: ExecutionDialectGate.Request?,
        target: ExecutionDialectGate.Target?,
        onDialectGate: ((ExecutionDialectGate.Verdict) -> Unit)?,
        onError: ((String) -> Unit)?,
        onSubmission: ((ExecutionSubmission) -> Unit)? = null,
    ): Boolean {
        if (request == null) return true
        val verdict = ExecutionDialectGate.evaluate(request, target?.dialectName)
        onDialectGate?.invoke(verdict)
        val needsConfirmation = verdict.status == ExecutionDialectGate.Status.TARGET_UNKNOWN_QUERY_CONFIRM ||
            verdict.status == ExecutionDialectGate.Status.RENDER_UNKNOWN_QUERY_CONFIRM
        if (verdict.status != ExecutionDialectGate.Status.MATCH && !needsConfirmation) {
            // 先报告未提交，后交付原有详细红字，不能用通用阻断状态覆盖详细原因。
            onSubmission?.invoke(ExecutionSubmission.BLOCKED)
        }
        val allowed = when (verdict.status) {
            ExecutionDialectGate.Status.MATCH -> true
            ExecutionDialectGate.Status.TARGET_UNKNOWN_QUERY_CONFIRM,
            ExecutionDialectGate.Status.RENDER_UNKNOWN_QUERY_CONFIRM -> {
                unknownTargetQueryConfirmationForTest?.invoke(verdict) ?: runCatching {
                    val messageKey = if (verdict.status == ExecutionDialectGate.Status.TARGET_UNKNOWN_QUERY_CONFIRM) {
                        "execute.dialectGate.unknownQuery.confirm"
                    } else {
                        "execute.dialectGate.unknownRenderQuery.confirm"
                    }
                    com.intellij.openapi.ui.Messages.showYesNoDialog(
                        project,
                        MpSqlVisualizerBundle.message(messageKey),
                        MpSqlVisualizerBundle.message("execute.dialectGate.title"),
                        com.intellij.openapi.ui.Messages.getWarningIcon(),
                    ) == com.intellij.openapi.ui.Messages.YES
                }.getOrDefault(false)
            }
            ExecutionDialectGate.Status.AUTO_RERENDER_REQUIRED -> {
                if (onDialectGate == null) onError?.invoke(dialectGateMessage(verdict))
                false
            }
            // 用户定案（2026-10-25）：「方言不能影响最终的执行。哪怕方言是错的，用户手动选了
            // 数据源，可以正常提示方言不一致，但还是要能成功执行」。
            // 判据＝**两边方言都已知**（rendered 与 target 都非 null）。此时 SQL 是什么方言、
            // 目标库是什么方言都是确定事实，冲突只是「渲染来源与目标不一致」，交给数据库自己报
            // 语法错是最准确的反馈；插件不该替用户做执行层面的否决。
            // 反例保留阻断：RENDER_UNKNOWN_BLOCK / TARGET_UNKNOWN_BLOCK —— 那时**有一边是未知的**，
            // 写操作可能把 PG 语法打到 MySQL 库上，属于「无从判断」而非「判断出冲突」，仍拦。
            ExecutionDialectGate.Status.MANUAL_CONFLICT,
            ExecutionDialectGate.Status.RERENDER_UNAVAILABLE -> {
                // 提示照旧（UI 仍显示方言不一致与「切换数据源」入口），但不拦执行。
                onError?.invoke(dialectGateMessage(verdict))
                true
            }
            ExecutionDialectGate.Status.TARGET_UNKNOWN_BLOCK,
            ExecutionDialectGate.Status.RENDER_UNKNOWN_BLOCK -> {
                onError?.invoke(dialectGateMessage(verdict))
                false
            }
        }
        if (!allowed && needsConfirmation) onSubmission?.invoke(ExecutionSubmission.CANCELLED)
        return allowed
    }

    internal fun dialectGateMessage(verdict: ExecutionDialectGate.Verdict): String = when (verdict.status) {
        ExecutionDialectGate.Status.MATCH -> ""
        ExecutionDialectGate.Status.AUTO_RERENDER_REQUIRED -> MpSqlVisualizerBundle.message(
            "execute.dialectGate.rerendered",
            verdict.renderedDialectName ?: MpSqlVisualizerBundle.message("execute.dialectGate.unknownDialect"),
            verdict.targetDialectName ?: MpSqlVisualizerBundle.message("execute.dialectGate.unknownDialect"),
        )
        ExecutionDialectGate.Status.MANUAL_CONFLICT -> MpSqlVisualizerBundle.message(
            "execute.dialectGate.manualConflict",
            verdict.renderedDialectName ?: MpSqlVisualizerBundle.message("execute.dialectGate.unknownDialect"),
            verdict.targetDialectName ?: MpSqlVisualizerBundle.message("execute.dialectGate.unknownDialect"),
        )
        ExecutionDialectGate.Status.RERENDER_UNAVAILABLE -> MpSqlVisualizerBundle.message(
            "execute.dialectGate.rerenderUnavailable",
            verdict.renderedDialectName ?: MpSqlVisualizerBundle.message("execute.dialectGate.unknownDialect"),
            verdict.targetDialectName ?: MpSqlVisualizerBundle.message("execute.dialectGate.unknownDialect"),
        )
        ExecutionDialectGate.Status.TARGET_UNKNOWN_QUERY_CONFIRM ->
            MpSqlVisualizerBundle.message("execute.dialectGate.unknownQuery.confirm")
        ExecutionDialectGate.Status.TARGET_UNKNOWN_BLOCK ->
            MpSqlVisualizerBundle.message("execute.dialectGate.unknownBlock")
        ExecutionDialectGate.Status.RENDER_UNKNOWN_QUERY_CONFIRM ->
            MpSqlVisualizerBundle.message("execute.dialectGate.unknownRenderQuery.confirm")
        ExecutionDialectGate.Status.RENDER_UNKNOWN_BLOCK ->
            MpSqlVisualizerBundle.message("execute.dialectGate.unknownRenderBlock")
    }

    /** 控制台是否已就绪（prepareConsole 成功后 true）。 */
    @JvmStatic
    fun isPrepared(): Boolean = preparedConsole != null

    /**
     * T-UI030：是否有准备链正在飞。结构化 EXPLAIN 在无已绑数据源时要弹既有选择器，
     * 但已有准备链在飞时**只能等待**——预览准备对并发是 PASS_THROUGH，再走一次
     * chooseAndPrepare 会建第二条会话/控制台（T-FirstExecResultInvisible F1 已实证的坑）。
     */
    @JvmStatic
    fun isPrepareInFlight(): Boolean = prepareGate.prepareInFlight.get()

    /**
     * R33 EXPLAIN 结构化通道：当前预览会话已绑控制台的 DBN 数据源（共享/已备控制台二者取一，
     * 循 T-ConsoleDbContext 既有选择形态——预览会话用哪条数据源，EXPLAIN 就在同一条上跑）。
     *
     * 控制台有效性先探（`isValid()` 反射，复用链同款判据——失效控制台的数据源对象不可信）；
     * 取引用包 ReadAction（EDT 读 PSI 硬抛平台陷阱；[ExecConsoleContext.boundDataSource]
     * 是 DBN 链内 API，Community 下经 McpDbExecutor.isDatabaseAvailable 前置闸不会到达）。
     * 无控制台/已失效/反射失败一律 null → 调用方 fail-closed 回退直送通道。
     */
    @JvmStatic
    fun boundConsoleDataSource(): Any? {
        val console = sharedConsole ?: preparedConsole ?: return null
        val live = runCatching {
            console.javaClass.getMethod("isValid").invoke(console) == true
        }.getOrDefault(false)
        if (!live) return null
        return ReadAction.compute<Any?, RuntimeException> {
            ExecConsoleContext.boundDataSource(console)
        }
    }

    /** UI023：仅替代 DBN 状态读取；测试仍走执行文档身份检查和真实缓存读取。 */
    internal var schemaTargetReaderForTest: ((Any) -> SchemaValidationGate.Target?)? = null

    internal fun schemaValidationTarget(document: com.intellij.openapi.editor.Document?): SchemaValidationGate.Target? {
        // 首次 onEditor→embedConsole 会清 nativeConsoleEditor 辅助引用，但保留实际执行文档。
        // 只认本面板执行文档与 preparedEditor.document 同一对象；不得借共享或其他预览文档。
        if (document == null || document !== preparedEditor?.document) return null
        val console = preparedConsole ?: return null
        schemaTargetReaderForTest?.let { return it(console) }
        if (!ExecuteSqlSupport.isAvailable()) return null
        return runCatching {
            if (console.javaClass.getMethod("isValid").invoke(console) != true) return@runCatching null
            ReadAction.compute<SchemaValidationGate.Target?, RuntimeException> {
                ExecConsoleContext.schemaValidationTarget(console)
            }
        }.getOrNull()
    }

    /**
     * O-95 T-PreviewEdtGuard S3：当前预览会话绑定数据源**名**（预览面板绑定指示行数据源）。
     * 选择链与 [boundConsoleDataSource] 完全同源（共享/已备控制台二者取一 + isValid 先探 +
     * DBN 链内取引用）；取名走反射（本类对 DBN 类型一律反射惯例，Community/无 Database 插件
     * fail-closed → null）。`LocalDataSource.getName()` 出处：243 平台 jar 反编译
     * （ExecConsoleContext KDoc 同款口径：`JdbcConsole.getDataSource(): LocalDataSource`），
     * 与 `ds?.name`（ExecConsoleContext 诊断行在用）同一方法面。
     * null = 未绑定/失效/反射失败 → 面板显示未绑定灰字（panel.bindstate.unbound）。
     */
    @JvmStatic
    fun boundConsoleDataSourceName(): String? {
        val ds = boundConsoleDataSource() ?: return null
        return runCatching {
            ds.javaClass.getMethod("getName").invoke(ds) as? String
        }.getOrNull()
    }

    /**
     * O-96 测试缝：headless 无 Database 插件时注入「在籍数据源名单 + UUID→名解析」替代
     * 真链（[DataSourceSettings.getAllDataSources] 反射在 Community 沙盒恒空）；生产为 null
     * 走真链。测试用例负责置空复位（object 单例静态状态）。
     */
    internal var rememberedLookupForTest: ((Project) -> Pair<List<String>, (String) -> String?>)? = null

    /**
     * O-96 T-PreviewBindAssociation S1：last-used 记忆回退取名（绑定指示行第二优先级专用，
     * **只读**——歧义不清记忆、不弹窗、不触碰任何状态，展示路径禁副作用）。
     *
     * 判定与执行链 T-SI-02 静默复用**同一纯函数** [resolveLastUsedRunnerIndex]（UUID 精确命中
     * → 名字唯一命中 → 同名多条歧义即弃），runner 名单换为在籍数据源名
     * （[DataSourceSettings.getAllDataSources]）——指示行所说「下次执行会用哪条」与 prepareConsole
     * 静默复用的实际解析完全同源。记忆缺失 → null（快速早退）；UUID 失效（数据源已删）按
     * T-SI-02 口径退回名字判定；歧义/不存在 → null = 未绑定，不静默猜。
     *
     * 优先级契约（S3）：活控制台 [boundConsoleDataSourceName] 恒高于本记忆回退 > 未绑定。
     */
    /**
     * T-ModuleScopedLastUsed W2：`moduleKey` 非空时优先读该模块的记忆，未命中回落项目级
     * （见 [LastUsedKeys] 与 [lastUsedRunner] 的分层语义）。不传＝既有项目级行为，逐字不变。
     */
    @JvmStatic
    fun rememberedDataSourceName(project: Project, moduleKey: String? = null): String? {
        val memory = lastUsedRunner(project, moduleKey)
        if (memory.dataSourceId.isNullOrBlank() && memory.displayName.isNullOrBlank()) return null
        val (registeredNames, nameById) = rememberedLookupForTest?.invoke(project) ?: rememberedRoster(project)
        return resolveRememberedDataSourceName(memory, registeredNames, nameById)
    }

    /** O-96 S1：记忆回退取名判定（纯函数壳——复用 T-SI-02 判定，命中下标换算在籍名）。 */
    internal fun resolveRememberedDataSourceName(
        memory: LastUsedMemory,
        registeredNames: List<String>,
        dataSourceNameById: (String) -> String?,
    ): String? =
        resolveLastUsedRunnerIndex(memory, registeredNames, dataSourceNameById)
            .index?.let { registeredNames.getOrNull(it) }

    /**
     * T-ExplainPwdPrompt W4（2026-10-24）：结构化 EXPLAIN 的 **last-used 取源回落**——
     * 真绑定（[boundConsoleDataSource]）为 null 且绑定链 runner 枚举为 0（数据源无方言
     * 映射时平台 `DatabaseRunners.getAttachDataSourceRunners` 返回空；243 沙盒实测：
     * Database 视图已连接仍为 0）时，回落到面板「数据源（上次使用）」记忆取源，让结构化
     * 通道（`McpDbExecutor` askPassword=true）不依赖绑定链也能走到。
     *
     * 防错源纪律：按名解析**歧义即弃**（同名多源不猜）；方言不符由既有 allowDialectGate
     * 闸拦（EXPLAIN 同为执行旁路，同门控）。
     *
     * @return 在籍数据源对象（唯一命中）；名字缺失/歧义/枚举失败一律 null
     */
    /**
     * T-DialectInferPollution W9（2026-10-24）：EXPLAIN 链统一取源回落（结构化 W4 + 直送 W8
     * 合并重构）。原「无脑回落 last-used」有跨模块错源副作用：jar-model demo 模块（无学习条目）
     * 的表被 last-used（mp_demo）硬套 → `mp_demo.wms_inventory_jar_demo doesn't exist`（用户
     * 沙盒实测截图）。修正语义：
     *
     *  1. 预览模块有学习绑定 → 用学习源（模块归属强于全局记忆；UUID 解析在籍对象，比按名稳）；
     *  2. 学习源与 last-used **同时存在且不同** → 证据冲突不猜，返回 null（下游 NO_DATA_SOURCE
     *     引导弹数据源选择器，用户选一次即自愈更新学习与记忆）；
     *  3. 学习缺失 → last-used 唯一命中可用（兜底旧场景）——注意这是兜底不是归属，模块学习会
     *     随执行自愈。
     *
     * @param moduleKey 预览 SQL 所属模块键（面板 [cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.sourceModuleKey]）
     * @param sql 预览 SQL 原文（T-DialectInferPollution W10 表归属路由用；null/无表 = 跳过路由验证）
     */
    fun explainFallbackDataSource(project: Project, moduleKey: String?, sql: String?): Any? {
        val learned = moduleKey?.takeIf { it.isNotBlank() }?.let {
            runCatching {
                cn.song.mybatisplus.visualizer.sql.ModuleDataSourceBindingStore.getInstance(project)
                    .learnedCandidate(project, it)
            }.getOrNull()
        }
        val lastName = runCatching { rememberedDataSourceName(project, moduleKey) }.getOrNull()?.takeIf { it.isNotBlank() }
        val lastDs = lastName?.let { name ->
            runCatching {
                DataSourceSettings.getAllDataSources(project)
                    .filter { DataSourceSettings.getName(it) == name }
                    .singleOrNull()
            }.getOrNull()
        }
        val learnedId = learned?.id
        val lastId = lastDs?.let { runCatching { DataSourceSettings.getUniqueId(it) }.getOrNull() }
        var candidate: Any? = when (resolveExplainFallbackChoice(learnedId, lastId)) {
            null -> return null                    // 双证据冲突：学习源与 last-used 不同源，交用户选择
            learnedId -> runCatching {
                cn.song.mybatisplus.visualizer.execute.DataSourceSettings.getDataSourceById(project, learnedId!!)
            }.getOrNull()
            else -> lastDs
        }
        candidate ?: return null
        // T-DialectInferPollution W10：**表归属路由**——学习/last-used 证据可能陈旧或跨模块错配
        //（用户实测 `mp_demo.wms_inventory_jar_demo doesn't exist`），而「表在哪个库」是更强的
        // 归属事实：结构缓存（Database 视图展开/预览预热即就位，只读零内省）能证明哪条源持有
        // 预览引用的全部表。候选源不持有而**唯一**其他源持有 → 路由到它；多源持有 → 冲突交选择
        // 器；零源持有（缓存未热/表不存在）→ 保持候选（诚实降级，不阻断）。
        val tables = runCatching { cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator.tableNames(sql ?: "") }
            .getOrNull().orEmpty().take(8)
        if (tables.isNotEmpty()) {
            val ownerIndexes = runCatching {
                cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.cachedOwnerIndexes(project, tables)
            }.getOrNull().orEmpty()
            if (ownerIndexes.isNotEmpty()) {
                val all = runCatching { DataSourceSettings.getAllDataSources(project) }.getOrNull().orEmpty()
                val candidateName = runCatching { DataSourceSettings.getName(candidate) }.getOrNull()
                val ownerObjects = ownerIndexes.mapNotNull { all.getOrNull(it) }
                    .filter { runCatching { DataSourceSettings.getName(it) }.getOrNull() != candidateName }
                when {
                    ownerObjects.isEmpty() -> Unit                 // 候选源即持有者 → 可信，照旧
                    ownerObjects.size == 1 -> candidate = ownerObjects.single()  // 唯一持有 → 路由
                    else -> return null                            // 多源都持有 → 冲突，交用户选择
                }
            } else if (runCatching {
                    cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.cacheHasAnyTable(project)
                }.getOrDefault(false)
            ) {
                // T-DialectInferPollution W12：缓存热（有源已内省出表）但没有任何源持有预览的表
                // → 表不存在于任何已连库，错源执行必败——引导选择数据源（返回 null 走 NO_DATA_SOURCE）。
                return null
            }
            // 缓存全冷：无法证明归属，保持候选（诚实降级，Database 视图展开一次后自愈）
        }
        return candidate
    }

    /**
     * T-DialectInferPollution W9 纯判定核心（headless 直测）：回落证据选择。
     * 学习源与 last-used 同时存在且不同 → null（冲突不猜，下游引导选择数据源）；
     * 学习源优先；学习缺失时兜底 last-used。
     */
    internal fun resolveExplainFallbackChoice(learnedId: String?, lastId: String?): String? = when {
        learnedId != null && lastId != null && learnedId != lastId -> null
        learnedId != null -> learnedId
        else -> lastId
    }

    /**
     * F68a（T-PerfSweep20）：在籍数据源名单按 project 弱缓存——指示行取名链每次刷新
     * 不再现场建表（原每次 getAllDataSources 反射枚举+逐名 getName 反射）。
     * WeakHashMap 弱键随 project 关闭释放；名单陈旧窗口＝数据源增删后到下次进程内
     * 首次重建——只影响展示回退路径的「未绑定/旧名」判定，绑定优先级最高的活控制台
     * 取名（[boundConsoleDataSourceName]）不走本表。测试缝（[rememberedLookupForTest]）
     * 优先于本缓存，headless 用例不受影响。
     */
    private val rememberedRosters =
        java.util.WeakHashMap<Project, Pair<List<String>, (String) -> String?>>()

    private fun rememberedRoster(project: Project): Pair<List<String>, (String) -> String?> =
        synchronized(rememberedRosters) {
            rememberedRosters.getOrPut(project) {
                DataSourceSettings.getAllDataSources(project).map { DataSourceSettings.getName(it) } to
                    { id: String ->
                        runCatching {
                            DataSourceSettings.getDataSourceById(project, id)?.let { DataSourceSettings.getName(it) }
                        }.getOrNull()
                    }
            }
        }

    /** O-96 测试辅助：headless 直写 last-used 记忆双键（生产写入口 [rememberRunner] 为 private）。 */
    internal fun plantLastUsedMemoryForTest(project: Project, id: String?, name: String?) {
        val pc = com.intellij.ide.util.PropertiesComponent.getInstance(project)
        if (id.isNullOrBlank()) pc.unsetValue(LAST_RUNNER_ID_KEY) else pc.setValue(LAST_RUNNER_ID_KEY, id)
        if (name.isNullOrBlank()) pc.unsetValue(LAST_RUNNER_KEY) else pc.setValue(LAST_RUNNER_KEY, name)
    }

    /** 释放已准备控制台引用（面板清空/关闭时调用；JdbcConsole 本体由 IDE 管理）。 */
    @JvmStatic
    fun clearPrepared() {
        // M36.5.10：代际递增——作废所有挂起的 runWhenSmart 执行提交（面板清空）
        executionGeneration++
        preparedConsole = null
        preparedEditor = null
        preparedScriptModel = null
        preparedExecOption = null
        preparedUi = null
        preparedDialectGateRequest = null
        preparedDialectGateCallback = null
        preparedTopBlock = null
        preparedResultComponent = null
        preparedFullComponent = null
        // T-ExecCopyFix F2c：不清 cachedTempPsiFile——vf 失效守卫已兜底（下次命中前校验
        // isValid），内容覆盖写走 document 驱动不破坏 vf↔psi 配对；原实现每次预览切换清
        // 缓存，执行必落冷路径，击穿 T-PreviewFlash「跨点击吸收」的设计意图。
    }

    /**
     * T-Perf-16：面板关闭/项目关闭时释放单例滞留引用——object 单例的 prepared、shared、
     * pendingOnConsole 槽位强引用已关闭面板（pendingOnConsole 闭包捕获 SqlPreviewPanel 整棵
     * Swing 树）与原生控制台/编辑器/文档（持旧 Project 引用）。此前只有面板「清空」按钮走
     * clearPrepared，弹窗关闭/项目关闭路径无清理挂点 → 堆中滞留整棵面板树。
     */
    @JvmStatic
    fun releaseForPanel() {
        clearPrepared()
        dropSharedConsole()
        pendingOnConsole = null
    }

    /**
     * M36.5：准备控制台（不执行）——选数据源 → 建 session → attachConsole → 拼合组件回调。
     * 面板打开时调用，原生控制台即刻可见；执行由 [executePrepared] 触发。
     *
     * 数据源选择策略：唯一数据源/last-used 命中 → 静默；多数据源 → 原生弹窗（记住选择）；
     * 无数据源 → 预览路径静默 onError（不抢侧边栏），显式执行路径打开 Database 工具窗口 + onError。
     */
    @JvmStatic
    fun prepareConsole(
        project: Project,
        sql: String,
        onConsole: ((javax.swing.JComponent) -> Unit)? = null,
        onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        callback: ((Boolean) -> Unit)? = null,
        dialectGateRequest: ExecutionDialectGate.Request? = null,
        onDialectGate: ((ExecutionDialectGate.Verdict) -> Unit)? = null,
        forceChoose: Boolean = false,
        /** T-ExplainFailUx W4：用户已显式指定源名 → 按名直选建链，不再弹平台选择器。 */
        preferredRunnerName: String? = null,
        /**
         * T-ExplainSwitchNative W6（2026-10-24 用户实测「点切换数据源却重试执行了」）：**必须弹
         * 选择器**——即便 runner 枚举只剩一条（旧行为 `shouldBeChosenIfAlone` 会直选执行，用户
         * 看不到列表）。仅「切换数据源」入口置 true；预览默认准备与显式执行不受影响。
         */
        forceChoosePopup: Boolean = false,
    ) {
        chooseAndPrepare(project, sql, executeAfterPrepare = false,
            onConsole = onConsole, onEditor = onEditor, onError = onError, callback = callback,
            dialectGateRequest = dialectGateRequest, onDialectGate = onDialectGate,
            forceChoose = forceChoose, preferredRunnerName = preferredRunnerName,
            forceChoosePopup = forceChoosePopup)
    }

    /**
     * M36.5：在已准备的控制台上执行（[prepareConsole] 成功后调用）。
     * @param onResultsReady 执行触发成功回调：参数为完整 UI（顶部块+结果区 SplitPane），
     *                       调用方据此把右侧从纯编辑器切换为带结果区的完整控制台
     * @return false = 控制台未就绪（调用方回退全链 selectAndExecute）
     */
    @JvmStatic
    fun executePrepared(
        project: Project,
        onResultsReady: ((javax.swing.JComponent) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
        wholeScriptExec: Boolean = false,
        dialectGateRequest: ExecutionDialectGate.Request? = null,
        onDialectGate: ((ExecutionDialectGate.Verdict) -> Unit)? = null,
        onSubmission: ((ExecutionSubmission) -> Unit)? = null,
    ): Boolean {
        val console = preparedConsole ?: return false
        val editor = preparedEditor ?: return false
        val scriptModel = preparedScriptModel ?: return false
        val execOption = preparedExecOption ?: return false
        executeOnConsole(
            project, console, editor, scriptModel, execOption, preparedUi, onResultsReady, onError, onErrorRich,
            wholeScriptExec = wholeScriptExec,
            dialectGateRequest = dialectGateRequest ?: preparedDialectGateRequest,
            onDialectGate = onDialectGate ?: preparedDialectGateCallback,
            onSubmission = onSubmission,
        )
        // true 仍仅表示已进入准备好的控制台路径；提交结果只由 onSubmission 回报。
        return true
    }

    /**
     * 选择数据源并执行 SQL（准备 + 执行合一，等价原生 Ctrl+Enter 全链路）。
     * 控制台已就绪时（[isPrepared]）仅触发执行，不重建。
     *
     * @param project   当前项目
     * @param sql       SQL 文本
     * @param onConsole 控制台就绪回调：参数为拼合的原生 UI 组件（编辑器+结果区）。
     *                  调用方可将其内嵌到任意容器；null = 不需要内嵌
     * @param onEditor  控制台编辑器就绪回调（注入插件补全上下文等）；null = 不需要
     * @param onError   错误回调（执行链任何环节失败时调用，参数为错误信息；null = 不通知）
     * @param callback  兼容回调（true=已选择/排队，不保证提交；false=不可用/无数据源/取消）
     * @param onSubmission 提交回执；成功来自 afterSubmitted，不来自选择、排队或 runner 返回
     */
    fun selectAndExecute(
        project: Project,
        sql: String,
        onConsole: ((javax.swing.JComponent) -> Unit)? = null,
        onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        callback: ((Boolean) -> Unit)? = null,
        onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
        wholeScriptExec: Boolean = false,
        dialectGateRequest: ExecutionDialectGate.Request? = null,
        onDialectGate: ((ExecutionDialectGate.Verdict) -> Unit)? = null,
        onSubmission: ((ExecutionSubmission) -> Unit)? = null,
        /** T-DialectGateUx：强制弹选择器（跳过共享控制台复用与 last-used 静默命中）。 */
        forceChoose: Boolean = false,
    ) {
        chooseAndPrepare(project, sql, executeAfterPrepare = true,
            onConsole = onConsole, onEditor = onEditor, onError = onError, callback = callback, onErrorRich = onErrorRich,
            wholeScriptExec = wholeScriptExec, dialectGateRequest = dialectGateRequest, onDialectGate = onDialectGate,
            onSubmission = onSubmission, forceChoose = forceChoose)
    }

    /**
     * M36.5：公共链——数据源选择 → session → attachConsole → 拼合组件（→ 可选立即执行）。
     *
     * @param executeAfterPrepare true = 准备后立即执行（原 selectAndExecute 语义）；
     *                            false = 仅准备（prepareConsole，控制台默认展示）
     */
    internal fun dropSharedConsole() {
        sharedConsole = null
        sharedEditor = null
        sharedScriptModel = null
        sharedExecOption = null
        sharedUi = null
        sharedTopBlock = null
        sharedFullComponent = null
        sharedDataSourceName = null
        sharedDialectGateRequest = null
        sharedDialectGateCallback = null
    }

    /**
     * M36.5.50：清空 RunnerLayoutUi 的**结果 Tab**（保留 Output 输出与 Tx 控制台），
     * 用于预览切换时消除上个 SQL 的结果残留。反射调用 DBN 的 removeContent。
     * 与 M36.5.45b 的差异：彼时清空后无 realignSplitDivider 兜底导致布局塌缩，已回退；
     * 现 M36.5.46 换入后校准 divider，清空后 bottom 为空组件也不再塌缩。
     */
    internal fun clearResultContents(ui: Any?, LOG: com.intellij.openapi.diagnostic.Logger) {
        if (ui == null) return
        runCatching {
            val contents = ui.javaClass.getMethod("getContents").invoke(ui) as? Array<com.intellij.ui.content.Content>
                ?: return
            val remove = ui.javaClass.getMethod("removeContent", com.intellij.ui.content.Content::class.java, Boolean::class.javaPrimitiveType)
            contents.filter { !isOutputContent(it) }.forEach { content ->
                runCatching { remove.invoke(ui, content, true) }.onFailure { ex ->
                    LOG.warn("[M36] clear result content FAILED: ${ex.message}")
                }
            }
            LOG.info("[M36] result contents cleared (preview switch, removed=${contents.count { !isOutputContent(it) }})")
        }.onFailure { e ->
            LOG.warn("[M36] clear result contents FAILED: ${e.message}")
        }
    }

    /**
     * M36.5.57：取 console history 文档长度（反射 LanguageConsoleView historyViewer），
     * 用作早检基线——本次执行后新增文本的起始偏移。
     */
    internal fun consoleHistoryLength(console: Any?): Int {
        if (console == null) return 0
        return runCatching {
            val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return 0
            val historyViewer = consoleView.javaClass.getMethod("getHistoryViewer").invoke(consoleView) ?: return 0
            val doc = historyViewer.javaClass.getMethod("getDocument").invoke(historyViewer) ?: return 0
            (doc.javaClass.getMethod("getTextLength").invoke(doc) as? Int) ?: 0
        }.getOrDefault(0)
    }

    /**
     * T-ExecSilentNoop F1a 探针：提交前的一次性可执行性预检 + 现场埋点。
     *
     * DBN 侧存在两条**全静默出口**（javap 实证，database-plugin.jar）：`chooseStatements`
     * 空选集且 `execOutside==1` 时既不消费 model 也不提示、直接 return；`beforeExecuteQueries`
     * 遇 `model.statements().isEmpty()` 或 `console.isValid()==false` 返回 false → 整段跳过。
     * 两者都无输出、无结果 Tab、无异常。此探针把这两个出口对应的现场量提前照出来，
     * 归因"查询没提交"具体撞的是哪一条，并据此决定是否走 `executeQueries(freshModel)` 兜底。
     * 纯反射 + runCatching，任何一步失败降级为 null，绝不阻断执行。
     *
     * @return null 表示"DBN 侧可执行（或探不到），按 native 链走"；
     *         非 null 表示"预检判定 DBN 侧会静默拒绝"，返回真实原因描述。
     */
    internal fun extractConsoleResidue(
        console: Any?,
        sqlText: String?,
        LOG: com.intellij.openapi.diagnostic.Logger,
    ): String? {
        if (console == null) return null
        return runCatching {
            val text = newConsoleText(console) ?: return null
            if (text.isBlank()) return null
            val sqlNorm = normalizeForEcho(sqlText.orEmpty())
            val prompt = consolePrompt(console)
            val kept = text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filterNot { isConsoleStatusLine(it) }
                .filterNot { isSqlEchoLine(it, sqlNorm, prompt) }
            if (kept.isEmpty()) return null
            val joined = kept.takeLast(RESIDUE_MAX_LINES).joinToString("\n")
            val out = if (joined.length > RESIDUE_MAX_CHARS) joined.take(RESIDUE_MAX_CHARS) else joined
            LOG.info("[T-CEC] console residue (no known signature): ${out.replace('\n', '⏎').take(200)}")
            out
        }.getOrNull()
    }

    /**
     * T-CEC：读取 console history 文档中**基线之后**的新增文本（读不到 / 无新增返 null）。
     *
     * T-PerfSweep2-01 的片段物化契约在此保持（ConsoleErrorExtractTest 逐条断言）：baseline<=0
     * 走整串一次、>0 只切一刀、无增长零物化——本函数是两个定时器逐拍调用的共用读取面，
     * 抽出来只为让兜底呈现复用同一份切片语义。
     */
    internal fun newConsoleText(console: Any?): String? {
        if (console == null) return null
        val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return null
        val historyViewer = consoleView.javaClass.getMethod("getHistoryViewer").invoke(consoleView) ?: return null
        val doc = historyViewer.javaClass.getMethod("getDocument").invoke(historyViewer) ?: return null
        // T-PerfSweep2-01：getCharsSequence + subSequence 片段读取——本函数被 600ms×8 拍与
        // 400ms×40/100 拍两个定时器**逐拍**调用，此前 doc.getText() 每拍整串拷贝全史
        // （history 随长会话涨到 MB 级后是 EDT 上唯一可感知的 O(全史) 重复分配）。
        // 只物化 baseline 之后的切片；getCharsSequence 与 getText 同属 Document 接口，
        // 反射可达性一致，且免去 BadLocationException 面。
        val seq = doc.javaClass.getMethod("getCharsSequence").invoke(doc) as? CharSequence ?: return null
        // M36.5.57：只扫本次执行之后新增的文本（基线截断）——忽略 history 残留的旧错误。
        // baseline 可能因 DBN 写入历史而增长；若文本长度 <= 基线说明无新增内容，返回 null。
        val baseline = consoleErrorBaseline
        return when {
            baseline <= 0 -> seq.toString()
            seq.length > baseline -> seq.subSequence(baseline, seq.length).toString()
            else -> null
        }
    }

    /**
     * T-CEC-01：未命中判据的控制台新增文本尾部（有界 + 同一段只记一次）——取证用，供下次扩判据
     * 时核对 DBN 真实写入 history 的报文形态（是否带 `[时间戳]` 前缀、是否跨行、SQLSTATE 前缀
     * 是否在场）。行数与字符数双上限，避免长会话刷屏。
     */
    internal fun logUnrecognizedConsoleTail(lines: List<String>, LOG: com.intellij.openapi.diagnostic.Logger) {
        val tail = lines.takeLast(UNRECOGNIZED_TAIL_LINES).joinToString("\n").take(UNRECOGNIZED_TAIL_CHARS)
        if (tail.isBlank() || tail == lastUnrecognizedTail) return
        lastUnrecognizedTail = tail
        LOG.info("[T-CEC] console tail (no error signature matched): ${tail.replace('\n', '⏎')}")
    }

    /** T-CEC-02：错误上下文摘录的最大行数（错误行起算）。 */
    internal const val EXCERPT_MAX_LINES = 8

    /** T-CEC-01：取证日志的行数 / 字符数上限。 */
    private const val UNRECOGNIZED_TAIL_LINES = 6
    private const val UNRECOGNIZED_TAIL_CHARS = 600

    /** T-CEC-03：兜底呈现的行数 / 字符数上限。 */
    private const val RESIDUE_MAX_LINES = 6
    private const val RESIDUE_MAX_CHARS = 600

    /**
     * T-CEC-02：DBN 服务端错误前缀形态 `[SQLSTATE][errorCode]`——DBN 把驱动 SQLException 的
     * SQLState 与 vendor code 拼在报文最前（真机日志实证：`[42S22][1054] Unknown column …`、
     * `[42000][1064] You have an error in your SQL syntax …`）。跨方言同形，比短语词表硬：
     * 原词表认 `syntax error`，却认不出 `You have an error in your SQL syntax`。
     * 5 位定长是关键——`[2026-09-22 08:48:12]` 这类时间戳前缀（19 字符）不会被误命中。
     */
    private val SQLSTATE_PREFIX = Regex("""\[[0-9A-Za-z]{5}]\[\d+]""")

    /** T-CEC-02：Oracle 错误码形态（`ORA-00933` 等）——Oracle 报文常不带 SQLSTATE 前缀。 */
    private val ORACLE_CODE = Regex("""\bORA-\d{5}\b""", RegexOption.IGNORE_CASE)

    /** T-CEC-02：错误签名短语族（结构性判据覆盖不到的方言 / 驱动惯用报文）。 */
    internal val ERROR_SIGNATURES = listOf(
        // M36.5.48 原有 14 条（历史命中系，行为保持不动）
        "sql exception", "syntax error", "sqlstate", "unknown column",
        "unknown table", "doesn't exist", "does not exist", "access denied",
        "deadlock", "error code", "broken pipe", "connection refused",
        "[\u65e5\u8bed\u9519\u8bef]", "sql\uff08", "exception in thread",
        // T-CEC-02 新增：MySQL 语法错（用户 2026-09-22 真机 Output 报文逐字）
        "you have an error in your sql syntax",
        // T-CEC-02 新增：厂商固定报文（MySQL 1062 / 1451、Oracle ORA-00933）
        "duplicate entry", "foreign key constraint",
        "cannot add or update a child row", "cannot delete or update a parent row",
        "sql command not properly ended",
    )

    /**
     * R33 真机走查修复：EXPLAIN 附随的 SHOW WARNINGS note 行（`[HY000][1003] Note: …`，
     * 内含重写查询）——informational，不是执行失败。MySQL 服务端 1003 号属 note 消息段
     * （ER_YES，不落失败码段）；结构化通道回退后直送通道执行 EXPLAIN 成功时，DBN 会把这条
     * note 写进 history，早检逐拍扫到即被 [SQLSTATE_PREFIX] 命中误报红条（真机实证：
     * 结果已正常出，底部红条却是这条 note）。整行排除（不要求行内带 "Note" 字样——DBN
     * 是否透出级别词无源码级实锤，宁可宽放：1003 号在 MySQL 不是失败码）。
     */
    private val NOTE_1003_PREFIX = Regex("""\[HY000]\[1003]""", RegexOption.IGNORE_CASE)

    /** T-CEC-02：一行是否命中错误判据（结构性判据 + 短语族；EXPLAIN note 1003 行除外）。 */
    internal fun isErrorSignatureLine(line: String): Boolean =
        !NOTE_1003_PREFIX.containsMatchIn(line) &&
            (SQLSTATE_PREFIX.containsMatchIn(line) ||
                ORACLE_CODE.containsMatchIn(line) ||
                ERROR_SIGNATURES.any { line.contains(it, ignoreCase = true) })

    /**
     * T-CEC-02：本插件自己写进 Output 的回显行前缀（[writeConsoleOutput] 的「执行失败: 」）——
     * 提取时排除，避免「已上报的错误被自己写回控制台后又当成新错误」的自反馈。
     */
    internal const val EXEC_FAILURE_ECHO_PREFIX = "执行失败:"

    internal fun isOwnEchoLine(line: String): Boolean =
        stripConsolePrefix(line, null).startsWith(EXEC_FAILURE_ECHO_PREFIX)

    /** T-CEC-02：DBN 输出行的 `[时间戳] ` 前缀（用户真机报文逐行带此前缀）。 */
    private val TIMESTAMP_PREFIX = Regex("""^\[\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?]\s*""")

    private fun stripTimestamp(line: String): String {
        val s = line.trim()
        val m = TIMESTAMP_PREFIX.find(s) ?: return s
        return s.substring(m.range.last + 1).trim()
    }

    /** T-CEC-02：剥掉时间戳与（DBN 实读的）提示符前缀——呈现与复制都更干净。 */
    internal fun stripConsolePrefix(line: String, prompt: String?): String {
        var s = stripTimestamp(line)
        if (!prompt.isNullOrBlank() && s.startsWith(prompt)) s = s.substring(prompt.length).trim()
        return s
    }

    /**
     * T-CEC-02：读 DBN 控制台提示符（`mpj_demo>` / `ds>>`，即 `LanguageConsoleView.prompt`——
     * 243 源码 jar 实证为 Kotlin `var prompt: String?`，插件既有 [clearConsolePrompt] 已用 setPrompt）。
     * 取不到返 null：调用方退化为只剥时间戳，不猜提示符形态。
     */
    internal fun consolePrompt(console: Any?): String? = runCatching {
        if (console == null) return null
        val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return null
        (consoleView.javaClass.getMethod("getPrompt").invoke(consoleView) as? String)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** T-CEC-03：回显比对用的归一化（空白折叠 + 小写）。 */
    private val WHITESPACE_RUN = Regex("""\s+""")

    private fun normalizeForEcho(text: String): String =
        text.replace(WHITESPACE_RUN, " ").trim().lowercase()

    /** T-CEC-03：兜底剥离提示符的候选形态（仅当剩余部分是本次 SQL 子串时才据此判为回显）。 */
    private val PROMPT_CANDIDATE = Regex("""^\S{1,64}>+\s+""")

    /**
     * T-CEC-03：该行是否本次执行 SQL 的回显——剥掉时间戳后（必要时再剥提示符）是本次 SQL 文本的
     * 子串即认（多行 SQL 的每一行都是 SQL 的子串）。判据宽松方向是「多剔」：宁可残余变空退回
     * 既有提示，也不把回显当错误报给用户。
     */
    private fun isSqlEchoLine(line: String, sqlNorm: String, prompt: String?): Boolean {
        if (sqlNorm.isEmpty()) return false
        val body = stripTimestamp(line)
        val candidates = mutableListOf(body)
        if (!prompt.isNullOrBlank() && body.startsWith(prompt)) {
            candidates += body.substring(prompt.length).trim()
        }
        PROMPT_CANDIDATE.find(body)?.let { m -> candidates += body.substring(m.value.length).trim() }
        return candidates.any { c ->
            val n = normalizeForEcho(c)
            n.length >= 4 && sqlNorm.contains(n)
        }
    }

    /**
     * T-CEC-03：DBN 状态 / 结果摘要行——不是错误内容，兜底呈现时剔除。
     *
     * 判据刻意偏「多剔」：语句**成功**但 DBN 不建结果 Tab 的场景同样会走满 40 拍窗口（DML 与
     * `use` 这类工具语句的影响行数/完成回显就写在 Output 里），把 `1 row affected` 当残余报出来
     * 等于把成功说成失败；剔多了只是落回既有提示，属可接受方向（宁少勿错）。
     */
    private val CONSOLE_STATUS_LINES = listOf(
        "connected", "completed in", "executing", "fetching", "fetched",
        "cancelled", "canceled", "query ok", "affected", "took ", "elapsed",
    )

    /** T-CEC-03：结果摘要词（`1 row affected` / `N rows` / `updated` 等）——同上，偏多剔。 */
    private val CONSOLE_STATUS_WORDS =
        Regex("""\b(rows?|records?|updated|deleted|inserted)\b""", RegexOption.IGNORE_CASE)

    private fun isConsoleStatusLine(line: String): Boolean {
        val n = stripTimestamp(line).lowercase()
        return CONSOLE_STATUS_LINES.any { n.contains(it) } || CONSOLE_STATUS_WORDS.containsMatchIn(n)
    }

    /** M36.5.48：判断一行是否纯 SQL 语句（避免把「WHERE t.id=1」等当错误）。 */
    internal fun isSqlLine(line: String): Boolean {
        val lower = line.trim().lowercase()
        // 含 SQL 关键字且无错误判据命中 → 大概率是 SQL 文本而非错误
        return (lower.startsWith("select") || lower.startsWith("from") ||
            lower.startsWith("where") || lower.startsWith("join") ||
            lower.startsWith("insert") || lower.startsWith("update") ||
            lower.startsWith("delete") || lower.startsWith("create") ||
            lower.startsWith("alter") || lower.startsWith("drop")) &&
            !isErrorSignatureLine(line)
    }

    /**
     * M36.5.8：清空控制台提示符（SQL 编辑器左侧 "rbac>"/">" 装饰）——用户反馈无用干扰。
     * 反编译实锤：DatabaseLogView 经 LanguageConsoleView.setPrompt(String) 维护（默认 ">"、
     * 带搜索路径时 "<数据源>>"）；置空后若数据源/搜索路径变化可能被插件重设，属可接受回退。
     */
    /**
     * M36.5.62：清空 console 历史文档（LanguageConsoleView historyViewer 的 document）——
     * 预览（execute=false）时清除上一个 SQL 的历史残留。DBN 每次执行/预览都会把 SQL 回显到
     * historyViewer（Output tab），`clearResultContents` 只移除 result Tab（ui.removeContent），
     * **保留 Output content**——Output tab 仍显示上个 SQL 的回显文本（用户实测"预览 sqlA 时下方有
     * 之前预览执行过的 sqlB"）。预览应显示干净空态，须连历史文档一起清空。
     * 注意：执行路径（execute=true）**不清**——执行后 DBN 需重新回显本次 SQL，历史清空会让
     * 执行结果上下文断链，且 consoleErrorBaseline 依赖历史长度基线（清空会改变基线语义）。
     */
    internal fun clearConsoleHistory(console: Any?, LOG: com.intellij.openapi.diagnostic.Logger) {
        if (console == null) return
        runCatching {
            val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return
            val historyViewer = consoleView.javaClass.getMethod("getHistoryViewer").invoke(consoleView) ?: return
            val doc = historyViewer.javaClass.getMethod("getDocument").invoke(historyViewer) ?: return
            doc.javaClass.getMethod("setText", String::class.java).invoke(doc, "")
            LOG.info("[M36] console history cleared (preview switch)")
        }.onFailure { e ->
            LOG.warn("[M36] clear console history FAILED: ${e.message}")
        }
    }

    /** M36.5.8：清空控制台提示符（SQL 编辑器左侧 "rbac>"/">" 装饰）——用户反馈无用干扰。
     * 反编译实锤：DatabaseLogView 经 LanguageConsoleView.setPrompt(String) 维护（默认 ">"、
     * 带搜索路径时 "<数据源>>"）；置空后若数据源/搜索路径变化可能被插件重设，属可接受回退。
     */
    internal fun clearConsolePrompt(console: Any, LOG: com.intellij.openapi.diagnostic.Logger) {
        runCatching {
            val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return
            consoleView.javaClass.getMethod("setPrompt", String::class.java).invoke(consoleView, "")
            LOG.info("[M36] console prompt cleared")
        }.onFailure { e ->
            LOG.warn("[M36] clear console prompt FAILED: ${e.message}")
        }
    }

    /**
     * M36.5.70：用 DBN ConsoleView 原生 print API 把文本写入 Output 控制台可视区。问题 SQL（报错
     * 无结果行）时 DBN 结果区内 Output 内容视图（JBScrollPane）布局为 0x0（`[TEXT]` 探针实证），
     * historyViewer document 有错误文本但 Output 面板不显示——用户"output 里没有信息"。走
     * ConsoleView.print(String, ConsoleViewContentType) 会经 DBN 原生输出链路写入并刷新显示，
     * 不依赖/不破坏结果区内部布局（结果网格照常）。反射调用避免对 ConsoleViewContentType 的
     * 编译期依赖（其所在模块可能随 IDE 版本变动）。
     *
     * T-FatalPassthrough T-FP-02b：`internal` 供 `FatalPassthroughTest` 直测本函数的**外层 runCatching
     * 边界**（同 [execFileContentMatches] / [isExecDocOpenInEditor] 先例）——私有则行为用例无处注入。
     */
    internal fun consoleEditorUnavailableMessage(path: String): String =
        MpSqlVisualizerBundle.message(
            "execute.failed",
            "控制台编辑器不可得（$path：400ms 内 getEditors(consoleDocument) 恒空，无一方为该文档建过编辑器）",
        )

    /**
     * 准备链续接（consumer 回调内调用）：session → attachConsole → 编辑器/scriptModel →
     * 缓存已备控制台 → 拼合组件回调 →（可选）立即执行。
     *
     * 反编译实锤：logView.getContent().getComponent() = RunnerLayoutUi（仅结果 tabs），不含编辑器。
     * 编辑器组件 = editor.component（EditorImpl）。
     * M36.5.2 拼合：顶部块（原生工具栏+编辑器）；结果区执行后才随完整 SplitPane 展示。
     *
     * @param dataSourceName 所选数据源名（M36.5.5 共享缓存记忆与复用判定用；可 null）
     */
    internal fun abortPrepareGate(prepareOwner: QueuedExecuteIntent?, failureMessage: String?, LOG: com.intellij.openapi.diagnostic.Logger) {
        if (prepareGate.abort(prepareOwner, ExecutionSubmission.BLOCKED,
                failureMessage ?: MpSqlVisualizerBundle.message("execute.tempFile.failed", "prepare aborted"))) {
            LOG.warn("[M36][T-FirstExec F1] prepare chain aborted - settle current and queued requests")
        }
    }

    /**
     * M36.5：控制台准备链后段（T-Perf-13 从 [prepareInConsole] 抽出——编辑器实例就绪后执行：
     * scriptModel 装配 / 执行上下文缓存 / 原生控制台 UI 拼合内嵌 / 可选立即执行）。
     * EDT 编辑器异步等待路径会经此继续（异常处理与同步路径一致：warn + onError 面板内显示）。
     */
    internal data class ConsoleValidity(
        val disposed: Boolean,
        val viewValid: Boolean?,
        val dsInFacade: Boolean?,
    ) {
        val valid: Boolean get() = !disposed && viewValid != false && dsInFacade != false
        override fun toString(): String = "disposed=$disposed, viewValid=$viewValid, dsInFacade=$dsInFacade, valid=$valid"
    }

    /**
     * T-SI-01：共享会话复用身份判定（纯函数，默认 test 通道可覆盖）——在 `console.isValid()`
     * 与编辑器未释放之外，把 [ConsoleValidity] 的 `dsInFacade` 分量作为**独立判据**接进复用
     * 判定（复用 attach 路径既有探测，不重造）。`identity == null` 表示探测不可用（降级），
     * 按现状放行——探测失败不得把正常复用打成重建（宁可用旧会话，也不每次预览重建连接）。
     */
    internal fun reuseIdentityAccepted(identity: ConsoleValidity?): Boolean = identity == null || identity.valid

    /** T-PreviewExecDs-03 F2：反射探测 console 三分量有效性（任一环节失败降级 null，绝不阻断）。 */
    internal fun probeConsoleValidity(console: Any, project: Project, LOG: com.intellij.openapi.diagnostic.Logger): ConsoleValidity? =
        runCatching {
            val consoleClass = Class.forName(CONSOLE_CLASS)
            val disposed = runCatching {
                // 243 平台仅 Disposable 版本（标 deprecated），无 Object 重载——cast + suppress
                @Suppress("DEPRECATION")
                (console as? com.intellij.openapi.Disposable)
                    ?.let { com.intellij.openapi.util.Disposer.isDisposed(it) } ?: false
            }.getOrDefault(false)
            val viewValid = runCatching {
                consoleClass.getMethod("isViewValid").invoke(console) as? Boolean
            }.getOrNull()
            val dsInFacade = runCatching {
                val ds = consoleClass.getMethod("getDataSource").invoke(console) ?: return@runCatching null
                val id = ds.javaClass.getMethod("getUniqueId").invoke(ds) as? String ?: return@runCatching null
                val facade = Class.forName("com.intellij.database.psi.DbPsiFacade")
                    .getMethod("getInstance", Project::class.java).invoke(null, project)
                facade.javaClass.getMethod("findDataSource", String::class.java)
                    .invoke(facade, id) != null
            }.getOrNull()
            ConsoleValidity(disposed, viewValid, dsInFacade)
        }.onFailure { e ->
            LOG.warn("[M36] probeConsoleValidity FAILED: ${e.message}")
        }.getOrNull()

    /**
     * M36.5.24g：dumb 退出后重建 console 的 scriptModel——在 **DBN SqlDialectPusher 的 dialect push
     * dumb task 完成之后**（PSI 已按 MySQL 方言完整重建）构造 fresh model，避免 dumb mode 中
     * PSI 解析不完整导致语句 range=null（22:30 实证：attachConsole 后 dialect push 触发 PSI 重建，
     * dumb 中构造的 fresh 语句 range 全失效）。构造 + splitMode 对齐 + set 缓存，并更新共享缓存。
     */
    internal fun realignSplitDivider(sp: javax.swing.JSplitPane, force: Boolean = false) {
        // ① invokeLater 下一拍（组件已 add 到树）
        // write-safe: UI-only（纯 Swing/只读，无文档写）
        ApplicationManager.getApplication().invokeLater({
            realignDividerOnce(sp, force)
        }, com.intellij.openapi.application.ModalityState.any())
        // ② componentListener：每次布局后检查（force=false，只在顶部不足时校正）
        sp.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                realignDividerOnce(sp, force = false)
            }
        })
    }

    /** 单次 divider 校正：[force]=true 无条件 0.6；[force]=false 双向校正——
     *  顶部过小（topH < total*0.35，编辑器被压）或**顶部过大**（topH > total*0.68，结果区塌缩到
     *  preferred，只显示 Output 一行）都拉回 0.6。M36.5.64：原实现只校正"顶部过小"，漏掉
     *  "结果区塌缩"方向——DBN 结果区（RunnerLayoutUi grid）preferred=84px，re-parent 后内部
     *  内容变化会把 bottom 拉回 preferred（沙盒上限 `topH=569 total=656`=86%，bottom=84px），
     *  force=false 分支不触发 → 结果区一直塌缩（用户截图"Output 没撑满下方空白"）。 */
    internal class FillTargetLayout(private val target: java.awt.Component) : java.awt.LayoutManager {
        private var logged = 0
        override fun addLayoutComponent(name: String?, comp: java.awt.Component?) {}
        override fun removeLayoutComponent(comp: java.awt.Component?) {}
        override fun preferredLayoutSize(parent: java.awt.Container?): java.awt.Dimension {
            val p = parent ?: return java.awt.Dimension(0, 0)
            // 已有尺寸时按当前尺寸申报，避免祖先按内容高（history pref=上千 px）扩张/滚动
            if (p.height > 0) return java.awt.Dimension(p.width, p.height)
            val sbH = p.components.firstOrNull { it is javax.swing.JScrollBar && it.isVisible }
                ?.preferredSize?.height ?: 0
            return java.awt.Dimension(p.width, target.preferredSize.height + sbH)
        }
        override fun minimumLayoutSize(parent: java.awt.Container?): java.awt.Dimension =
            java.awt.Dimension(0, 0)
        override fun layoutContainer(parent: java.awt.Container) {
            val sb = parent.components.firstOrNull { it is javax.swing.JScrollBar && it.isVisible }
            val reserved = if (sb != null) sb.preferredSize.height.coerceAtLeast(0) else 0
            val avail = (parent.height - reserved).coerceAtLeast(0)
            for (c in parent.components) {
                when {
                    c === target -> c.setBounds(0, 0, parent.width, avail)
                    c === sb -> c.setBounds(0, avail, parent.width, reserved)
                    else -> c.setBounds(0, avail, parent.width, 0)
                }
            }
            if (logged < 3 && avail > 0 && target.height != avail) {
                logged++
                com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                    .debug("[M36][OUTFILL] layout: parent=${parent.javaClass.simpleName} h=${parent.height} target(${target.javaClass.simpleName}) → $avail")
            }
        }
    }

    /** M36.5.60：运行时组件边界取证——像素扫描无法区分"编辑器未拉伸"与"编辑器拉伸但背景同色"
     *（editor bg 43,45,48 vs panel bg 43,45,48 无差异）。直接 dump SplitPane/top/bottom 及其直接
     * 子组件的真实 bounds + size，一锤定音：topblock 是否被编辑器真的 fill，结果区是否占满 bottom。 */
    internal fun wholeScriptExecOption(base: Any): Any = runCatching {
        val settingsClass = Class.forName("com.intellij.database.settings.DatabaseSettings")
        val fresh = base.javaClass.getDeclaredConstructor().newInstance()
        base.javaClass.getMethod("copyFrom", base.javaClass).invoke(fresh, base)
        base.javaClass.getField("execInside").setInt(fresh, settingsClass.getField("EXECUTE_INSIDE_WHOLE_SCRIPT").getInt(null))
        runCatching {
            base.javaClass.getField("execOutside").setInt(fresh, settingsClass.getField("EXECUTE_OUTSIDE_WHOLE_SCRIPT").getInt(null))
        }
        fresh
    }.getOrElse { base }

    /**
     * M36.5：在指定控制台上执行 SQL（后台线程 + ReadAction + 5s 超时 + 结果 Tab 激活重试）。
     * 由 [executePrepared]（已备控制台）与准备全链（executeAfterPrepare=true）共用。
     *
     * @param onResultsReady 执行触发成功后回调完整 UI（顶部块+结果区 SplitPane）——
     *                       仅 executePrepared 路径传入（准备时只嵌了顶部块，执行后换入结果区）
     */
    internal fun resultContents(ui: Any?): List<com.intellij.ui.content.Content> {
        if (ui == null) return emptyList()
        val all = runCatching {
            ui.javaClass.getMethod("getContents").invoke(ui) as? Array<com.intellij.ui.content.Content>
        }.getOrNull() ?: return emptyList()
        return all.filter { !isOutputContent(it) }
    }

    /**
     * T-FirstExecResultInvisible F3：RunnerLayoutUi 结果区是否有真实内容（排除 Output 输出页）。
     * 反射失败降级为「无内容」（与 [resultContents] 同口径）——降级只影响折叠判定（PREVIEW
     * 多折一次），不触碰执行链。
     */
    internal fun resultAreaHasContent(ui: Any?): Boolean =
        runCatching { resultContents(ui).isNotEmpty() }.getOrDefault(false)

    /**
     * T-FirstExecResultInvisible F3：面板侧 [ConsoleEmbedMode.shouldCollapseDivider] 折叠判定用的
     * 结果区内容探测——共享/已备 RunnerLayoutUi 二者取一（面板不持有 ui 引用，归口本选择器探测，
     * 与 DS 侧两处折叠点用同一判据，避免两侧判定漂移）。
     */
    internal fun currentResultAreaHasContent(): Boolean {
        val ui = sharedUi ?: preparedUi ?: return false
        return resultAreaHasContent(ui)
    }

    private fun isOutputContent(c: com.intellij.ui.content.Content): Boolean =
        c.displayName.equals("Output", ignoreCase = true) ||
            c.displayName.equals("输出", ignoreCase = true)

    /**
     * M36.5.22b：结果 Tab 选择判定（纯函数，[startResultTabRetry] 每 tick 调用；抽出为
     * 可测函数——DBN「复用 Content 实例改名」行为可在单测中用动态代理精确复现）。
     *
     * 判定规则（按优先级）：
     *  1. displayName **不在** [preExistingNames]（提交时刻快照）中的第一个结果 Tab → 新结果，
     *     **立即选中**。注意必须按名称而非实例身份——DBN 复用同一 Content 实例并改名
     *     （17:25 沙盒实证：pre-existing=[Result 1]，retry#1 已见 Result 2 但实例身份
     *     过滤判定为旧 Tab → select=null → 滞后一轮）。
     *  2. 只有旧名称的 Tab 时：attempts >= [REUSE_ACCEPT_TICKS]（M36.5.44 降为 4 拍 1.6s）
     *     才接受复用（防"任意见到旧 Tab 就换入"把上一轮结果顶进面板——M36.5.22 真因；
     *     [swapGeneration] 已保证 watch 生命周期仅本次执行，同名 Tab 即本次结果，可快速接受）。
     *  3. 无结果 Tab（仅 Output/空）→ null（继续等待）。
     *
     * T-ExplainFirstNative：**空名占位不算结果**——DBN 首次执行先 add 空名 Content 再异步
     * 改名/建网格（262 真机 21:06:45 实证 `contentAdded: `），空名 ∉ 快照集合恒真会把占位当
     * 新结果第 1 拍选中并强制换入（网格尚未构建），事后无人补换入 → 结果滞留原生控制台。
     */
    internal fun pickResultTab(
        contents: Array<com.intellij.ui.content.Content>,
        preExistingNames: Set<String>,
        attempts: Int,
    ): com.intellij.ui.content.Content? {
        val newOne = contents.firstOrNull {
            !isOutputContent(it) && it.displayName.isNotBlank() && it.displayName !in preExistingNames
        }
        if (newOne != null) return newOne
        val staleOne = contents.firstOrNull { !isOutputContent(it) && it.displayName.isNotBlank() }
        return if (staleOne != null && attempts >= REUSE_ACCEPT_TICKS) staleOne else null
    }

    /**
     * M36.5.44：旧 Tab 复用接受的 tick 数（400ms×N）。
     * 原 15 拍（6s）对"重复执行同一 SQL、DBN 复用同名 Content"场景太慢——用户看到的上一次结果
     * 会滞留 6 秒（沙盒 16:53 实证：执行2/执行3 `retry#1..#14` 全 select=null，retry#15 才换入，
     * 期间状态栏恒"执行中..."）。降为 4 拍（1.6s）：既不卡 6 秒，又保留了"DBN 复用同名 Tab 时
     * 内容更新需要时间"的合理缓冲（2 拍可能过早换入旧内容——提交后结果渲染未必即刻完成）。
     * 同名 Tab 由 [swapGeneration] 保证属本次执行，最终都会换入；阈值只决定"多快接受复用"。
     */
    internal const val REUSE_ACCEPT_TICKS = 4

    /**
     * 结果 Tab 激活重试（EDT Timer 400ms 非阻塞；结果 Tab 异步创建）。
     * M36.5.3 dumb-aware：dumb mode（索引）期间查询提交可能被推迟（沙盒实证：方言 push-all
     * 与连接建立延至索引完成）——索引中把重试窗口放宽到 40 次（16s）；
     * 超时后按状态给准确提示：索引中 → 索引提示（execute.indexing.hint），
     * 否则 → 检查被遮挡的密码/驱动弹窗（execute.credential.hint）。
     *
     * M36.5.22b：`preExistingNames` = 提交时刻已存在的结果 Tab **名称**快照；**只认本次提交后
     * 新出现的名称**（此前按 Content 实例身份判定，DBN 复用同一个 Content 实例并改名/更新
     * 导致新 Tab 被误判为旧 Tab）。
     * M36.5.44：接受旧 Tab 复用的阈值降为 [REUSE_ACCEPT_TICKS]（2 拍 0.8s）；且回调校验
     * `generation != swapGeneration` 即停（新执行覆盖旧 watch，防空转造成"执行中..."卡顿）。
     */
    /**
     * T-DdlPostInvalidate（O-17）：执行收尾兜底失效——[MetadataInvalidator.invalidateAfterExecution]
     * 内部先 mayChangeMetadata 预判（命中才动作）且动作幂等，入口+收尾双调全程失效次数有界（1+1）。
     * **成功与失败/超时收尾都调**（DBX：失败的 DDL 可能已隐式提交——Oracle），把「过程内 DDL/
     * 预判漏判/中途结构变化」三类窗口关死；动作异常只记日志不吞收尾结果。
     */
    internal fun runPostExecutionInvalidate(sqlText: String?, LOG: com.intellij.openapi.diagnostic.Logger) {
        if (sqlText == null) return
        runCatching { MetadataInvalidator.invalidateAfterExecution(sqlText) }
            .onFailure { LOG.warn("[T-DdlPostInvalidate] post-execution invalidation failed", it) }
    }

    internal fun lastRunnerName(project: Project, moduleKey: String? = null): String? =
        lastUsedRunner(project, moduleKey).displayName

    /** M36.5：记录 last-used 数据源名（T-ModuleScopedLastUsed W2：可带模块键分桶）。 */
    internal fun rememberRunnerName(project: Project, name: String, moduleKey: String? = null) {
        rememberRunner(project, name, null, moduleKey)
    }

    /**
     * T-SI-02：last-used 记忆（UUID 身份 + 显示名，双字段）。
     * [dataSourceId] 来自 LocalDataSource.getUniqueId（[DataSourceSettings] 既有反射封装）。
     */
    internal data class LastUsedMemory(val dataSourceId: String?, val displayName: String?)

    /**
     * T-SI-02：记忆命中解析结果。[index] 命中 runner 下标；[ambiguous] 表示「记忆在、但同名
     * 多条无法唯一对应」——调用方据此清记忆走弹窗（不猜首条）。
     */
    internal data class LastUsedResolution(val index: Int?, val ambiguous: Boolean)

    /**
     * T-SI-02：last-used 记忆命中判定（纯函数，默认 test 通道可覆盖）。
     *
     * 命中顺序（UUID 优先，名字回退，皆不唯一即弃）：
     *  1. UUID 精确命中——[dataSourceNameById] 取该 UUID 当前的数据源名（数据源被改名也认，
     *     这是 UUID 相对显示名的增益），再按该名在 runner 列表里唯一匹配；
     *  2. 名字命中且唯一——旧键（显示名）兼容路径，升级用户无感；
     *  3. 其余（未命中 / 同名多条）→ 弃记忆，调用方走弹窗重新选择。
     *
     * [dataSourceNameById] 返回 null 表示该 UUID 的数据源已不存在——UUID 分量失效，退回名字判定。
     */
    internal fun resolveLastUsedRunnerIndex(
        memory: LastUsedMemory,
        runnerNames: List<String>,
        dataSourceNameById: (String) -> String?,
    ): LastUsedResolution {
        val id = memory.dataSourceId?.takeIf { it.isNotBlank() }
        if (id != null) {
            val dsName = runCatching { dataSourceNameById(id) }.getOrNull()
            if (!dsName.isNullOrBlank()) {
                val hits = runnerNames.indices.filter { runnerNames[it] == dsName }
                if (hits.size == 1) return LastUsedResolution(hits.first(), false)
                if (hits.size > 1) return LastUsedResolution(null, true)
            }
        }
        val name = memory.displayName?.takeIf { it.isNotBlank() }
        if (name != null) {
            val hits = runnerNames.indices.filter { runnerNames[it] == name }
            if (hits.size == 1) return LastUsedResolution(hits.first(), false)
            if (hits.size > 1) return LastUsedResolution(null, true)
        }
        return LastUsedResolution(null, false)
    }

    /**
     * T-ModuleScopedLastUsed W1（2026-10-24 用户问「上次使用的数据源是否按模块隔离更合适」）：
     * last-used 记忆的**键构造**（纯函数，headless 直测）。
     *
     * 为什么按模块分桶：本项目此前并存两套取源记忆且粒度不一致——项目级 last-used（喂指示行
     * 灰字与静默复用）vs 模块级 [cn.song.mybatisplus.visualizer.sql.ModuleDataSourceBindingStore]
     * （`mp-sql-visualizer-datasource.xml`，**只喂方言决策**）。于是「在 jpa_demo 模块选了
     * jpa_demo@localhost，切到 mp_demo 模块，灰字仍显示同一个源」——多模块工程的源本就按模块
     * 分（demo 仓 mp_demo / jpa_demo / mpj_demo 各连各库），项目级单一记忆必然错配。这也是
     * T-DialectInferPollution W8~W12 那批跨模块错源的根因之一。
     *
     * **硬约束**：`moduleKey` 为 null/blank 时**必须回落项目级键**（返回 [LAST_RUNNER_KEY] 族
     * 原键）——纯 `.sql` 文件、非 Java 源等入口没有模块上下文，若按模块硬隔离会退化成「每次
     * 都未绑定」，比改前更糟。
     */
    internal object LastUsedKeys {
        /** 模块级后缀分隔符：模块键是 `canonicalKey`（模块身份，非路径），拼上后仍是稳定键。 */
        private const val MODULE_SEPARATOR = "."

        /** 模块级记忆键；[moduleKey] 为空时返回 [base] 本身（即项目级原键，向后兼容）。 */
        fun key(base: String, moduleKey: String?): String =
            if (moduleKey.isNullOrBlank()) base else "$base$MODULE_SEPARATOR$moduleKey"

        fun displayNameKey(moduleKey: String?): String = key(LAST_RUNNER_KEY, moduleKey)
        fun dataSourceIdKey(moduleKey: String?): String = key(LAST_RUNNER_ID_KEY, moduleKey)
    }

    /**
     * T-ModuleScopedLastUsed W2：读 last-used 记忆双字段（异常兜底全空）。
     *
     * 三层优先级的第一、二层由调用方掌握（活控制台绑定最高）；本函数负责「模块级优先、
     * 项目级兜底」：给了 [moduleKey] 就先读模块级键，**模块级未命中再读项目级**（不能因为
     * 模块级没有就返回空——那会让新模块的第一次预览失去兜底）。
     */
    internal fun lastUsedRunner(project: Project, moduleKey: String? = null): LastUsedMemory = runCatching {
        val pc = com.intellij.ide.util.PropertiesComponent.getInstance(project)
        fun read(keyModule: String?): LastUsedMemory = LastUsedMemory(
            pc.getValue(LastUsedKeys.dataSourceIdKey(keyModule)),
            pc.getValue(LastUsedKeys.displayNameKey(keyModule)),
        )
        if (moduleKey.isNullOrBlank()) return@runCatching read(null)
        read(moduleKey).let { scoped ->
            if (scoped.dataSourceId != null || scoped.displayName != null) scoped else read(null)
        }
    }.getOrDefault(LastUsedMemory(null, null))

    /**
     * T-SI-02：写 last-used 记忆。[dataSourceId] 取不到（弹窗路径尚无控制台）时**清掉身份键**
     * ——留着上一轮的 UUID 会指认另一个数据源（身份说谎比没有身份更糟）。
     *
     * T-ModuleScopedLastUsed W2：给了 [moduleKey] 时**同时写模块级与项目级**——模块级承载
     * 「本模块上次用哪个」，项目级作为无模块上下文入口（纯 `.sql` 等）的兜底，两者不冲突。
     * 但模块级命中优先，故读取侧不会串味。
     */
    internal fun rememberRunner(project: Project, name: String, dataSourceId: String?, moduleKey: String? = null) {
        runCatching {
            val pc = com.intellij.ide.util.PropertiesComponent.getInstance(project)
            fun write(keyModule: String?) {
                pc.setValue(LastUsedKeys.displayNameKey(keyModule), name)
                if (dataSourceId.isNullOrBlank()) pc.unsetValue(LastUsedKeys.dataSourceIdKey(keyModule))
                else pc.setValue(LastUsedKeys.dataSourceIdKey(keyModule), dataSourceId)
            }
            write(null)
            if (!moduleKey.isNullOrBlank()) {
                write(moduleKey)
                registerLastUsedModuleKey(project, moduleKey)
            }
        }
    }

    /**
     * T-SI-02：清 last-used 记忆（两个键一起清，避免半截身份残留）。
     *
     * T-ModuleScopedLastUsed W2：给了 [moduleKey] 只清该模块那一层（不牵连其它模块，也不动
     * 项目级兜底——用户只是在某个模块里换了源，不该把全局兜底一起抹掉）；未给则清项目级。
     */
    internal fun forgetLastRunner(project: Project, moduleKey: String? = null) {
        runCatching {
            val pc = com.intellij.ide.util.PropertiesComponent.getInstance(project)
            fun clear(keyModule: String?) {
                pc.unsetValue(LastUsedKeys.displayNameKey(keyModule))
                pc.unsetValue(LastUsedKeys.dataSourceIdKey(keyModule))
            }
            // 未指定模块＝既有语义「清项目级」；同时清掉所有模块级分桶（W4 的「清空学习」
            // 入口要求彻底清干净，否则用户点了清空、灰字仍显示旧源）。
            clear(moduleKey)
        }
    }

    /**
     * T-ModuleScopedLastUsed W2：清**全部**层级（项目级 + 已登记的模块分桶）。
     *
     * **不能用通配扫描**：`PropertiesComponent` 的公开 API 只有 `unsetValue/getValue/setValue`
     * （`javap` 实证，无 `getAllKeys`），拿不到「有哪些键」。故模块键清单必须**由插件自己记**：
     * 每次写模块级记忆时把模块键登记进 [_LAST_USED_MODULE_KEYS_KEY]（逗号分隔，去重），
     * 清空时按清单逐个 `unsetValue`。
     */
    internal fun forgetAllLastUsed(project: Project) {
        runCatching {
            val pc = com.intellij.ide.util.PropertiesComponent.getInstance(project)
            fun clear(keyModule: String?) {
                pc.unsetValue(LastUsedKeys.displayNameKey(keyModule))
                pc.unsetValue(LastUsedKeys.dataSourceIdKey(keyModule))
            }
            clear(null)
            registeredLastUsedModuleKeys(project).forEach { clear(it) }
            pc.unsetValue(_LAST_USED_MODULE_KEYS_KEY)
        }
    }

    /** 已登记过的模块键（写模块级记忆时顺带登记，供 [forgetAllLastUsed] 精确清理）。 */
    internal fun registeredLastUsedModuleKeys(project: Project): List<String> = runCatching {
        com.intellij.ide.util.PropertiesComponent.getInstance(project)
            .getValue(_LAST_USED_MODULE_KEYS_KEY)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun registerLastUsedModuleKey(project: Project, moduleKey: String) {
        runCatching {
            val pc = com.intellij.ide.util.PropertiesComponent.getInstance(project)
            val existing = registeredLastUsedModuleKeys(project)
            if (moduleKey in existing) return
            pc.setValue(_LAST_USED_MODULE_KEYS_KEY, (existing + moduleKey).joinToString(","))
        }
    }

    /** T-SI-02：从已建控制台取数据源 UUID（复用 [DataSourceSettings] 既有反射封装；取不到 null）。 */
    internal fun consoleDataSourceId(console: Any?): String? = runCatching {
        val ds = console?.javaClass?.getMethod("getDataSource")?.invoke(console) ?: return null
        DataSourceSettings.getUniqueId(ds)
    }.getOrNull()

    /**
     * 反射调用 DatabaseRunners.getAttachDataSourceRunners 获取原生 Runner 列表。
     * [sessionConsumer] 在选中 runner 执行后收到 DatabaseSession。
     *
     * T-ExplainSwitchNative W8（2026-10-24 用户实测「点击切换数据源只有一个数据源？当前可是
     * 配置了很多」）：**用户主动切换**时优先调平台切换专用入口 `getSwitchDataSourceRunners`，
     * 失败（含签名/无会话）则回退 attach 版。
     *
     * ⚠️ W8-hotfix（同日）：`getSwitchDataSourceRunners` 的签名是
     * **`(PsiFile, String, DatabaseSession, Consumer)` 四参**（javap 实证），比 attach 版多一个
     * 「当前会话」——首版按三参反射调用必然 `NoSuchMethodException`，被 runCatching 吞成空列表
     * → `runners=0` → 用户看到「没有与该 SQL 方言匹配的已配置数据源」红条（切换数据源点了就报错）。
     * 现按真实签名调用；[currentSession] 为 null（预览尚未建会话，切换入口的常态）时**直接用
     * attach 版**——这正是切换入口此前一直正常工作的形态。
     *
     * @param allDataSources 用户主动切换时为 true（尝试切换专用入口）
     * @param currentSession 当前已 attach 的会话（无则走 attach 版）
     */
    internal fun getAttachDataSourceRunners(
        psiFile: PsiFile,
        sql: String,
        sessionConsumer: com.intellij.util.Consumer<Any>,
        allDataSources: Boolean = false,
        currentSession: Any? = null,
    ): List<Any> {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        val attach: () -> List<Any> = {
            runCatching {
                val clazz = Class.forName(RUNNERS_CLASS)
                val method = clazz.getMethod(
                    "getAttachDataSourceRunners",
                    PsiFile::class.java,
                    String::class.java,
                    com.intellij.util.Consumer::class.java,
                )
                val result = method.invoke(null, psiFile, sql, sessionConsumer)
                (result as? List<*>)?.filterNotNull() ?: emptyList()
            }.onFailure { e ->
                LOG.warn("[M36] getAttachDataSourceRunners reflection FAILED: $e", e)
            }.getOrDefault(emptyList())
        }
        if (!allDataSources || currentSession == null) return attach()
        // 切换专用入口：四参（多 currentSession）；任何失败都回退 attach 版，绝不返回空。
        val switched = runCatching {
            val clazz = Class.forName(RUNNERS_CLASS)
            val method = clazz.getMethod(
                "getSwitchDataSourceRunners",
                PsiFile::class.java,
                String::class.java,
                Class.forName(SESSION_CLASS),
                com.intellij.util.Consumer::class.java,
            )
            val result = method.invoke(null, psiFile, sql, currentSession, sessionConsumer)
            (result as? List<*>)?.filterNotNull() ?: emptyList()
        }.onFailure { e ->
            LOG.warn("[M36] getSwitchDataSourceRunners reflection FAILED (fallback to attach): $e", e)
        }.getOrDefault(emptyList())
        return switched.ifEmpty { attach() }
    }

    /**
     * W8 观测缝（用户 2026-10-24「点击切换数据源只有一个数据源？当前可是配置了很多」）：
     * 平台 Runner 枚举走 `DbImplUtil.getAppropriateDataSourcesForExec` →
     * `DbSqlUtilCore.getAppropriateDataSources` → `.filter(CAN_CONNECT_TO)`（反编译实证），
     * 因此**当前不可连接的源不会出现在弹层里**（未连过/驱动未就绪）。插件侧无法凭空补出
     * 合法的平台 Runner（`runInDataSource` 是直接建会话、不产 Runner；私有
     * `createDataSourceRunner` 不可用），故本函数只做**归因打点**，供 UI 在「可选源少于在籍源」
     * 时给出可行动说明（打开 Database 工具窗口连一次即可恢复可选）。
     *
     * @return 在籍源总数（平台不可用时 0）
     */
    internal fun registeredDataSourceCount(project: Project): Int =
        runCatching { DataSourceSettings.getAllDataSources(project).size }.getOrDefault(0)

    /** 反射获取 Runner 显示名称（数据源名，如 `jpa_demo@localhost`）。 */
    internal fun getRunnerName(runner: Any): String = runCatching {
        val method = runner.javaClass.getMethod("getDisplayName")
        method.isAccessible = true
        method.invoke(runner) as? String
    }.getOrNull() ?: "Unknown"

    /** 反射获取 Runner 图标（数据源 DB 类型图标）。 */
    private fun getRunnerIcon(runner: Any): Icon? = runCatching {
        val method = runner.javaClass.getMethod("getIcon")
        method.isAccessible = true
        method.invoke(runner) as? Icon
    }.getOrNull()

    /** 反射调用 Runner.shouldBeChosenIfAlone（单数据源时原生是否免选直接执行）。 */
    internal fun shouldBeChosenIfAlone(runner: Any): Boolean = runCatching {
        val method = runner.javaClass.getMethod("shouldBeChosenIfAlone")
        method.isAccessible = true
        method.invoke(runner) as? Boolean
    }.getOrNull() ?: false

    /** 反射调用 Runner.run：建 session → consumer 回调续接执行。失败（常见：驱动未下载）→ 面板行内回调 + 按 silent 决定是否模态引导。 */
    // T-PreviewExecDs-02 F4：显式传 project——失败路径曾用 openProjects.firstOrNull() 清 last-used
    // /弹错误框，多项目同开时清的是「第一个打开项目」的记忆（错项目 bug，见开发计划 §2.2）。
    internal fun showRunnerPopup(project: Project, runners: List<Any>, callback: (Any?) -> Unit) {
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createListPopup(createRunnerPopupStep(project, runners, callback))
            .showInFocusCenter()
    }

    internal fun createRunnerPopupStep(
        project: Project, runners: List<Any>, callback: (Any?) -> Unit,
    ): com.intellij.openapi.ui.popup.util.BaseListPopupStep<RunnerItem> {
        val items = runners.map { RunnerItem(it, getRunnerName(it), getRunnerIcon(it)) }
        // T-PreviewExecDs-02 F5：多项目同开时标题带当前项目名——runner 枚举严格按当前项目
        // facade（F1 修复后），标题注明归属避免「这是哪来的数据源」困惑；单项目不加噪音。
        val multiProject = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.size > 1
        val title = if (multiProject) {
            MpSqlVisualizerBundle.message("execute.datasource.select.in", project.name)
        } else {
            MpSqlVisualizerBundle.message("execute.datasource.select")
        }
        val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<RunnerItem>(
            title,
            items,
        ) {
            private var choiceAccepted = false
            private var delivered = false
            private fun deliver(runner: Any?) {
                if (delivered) return
                delivered = true
                callback(runner)
            }

            override fun getTextFor(value: RunnerItem): String = value.name
            override fun getIconFor(value: RunnerItem): Icon? = value.icon
            override fun onChosen(selectedValue: RunnerItem?, finalChoice: Boolean): com.intellij.openapi.ui.popup.PopupStep<*>? {
                com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                    .info("[M36] popup onChosen: ${selectedValue?.name}")
                // 243 禁止在 onChosen 内开模态确认；runner/session 续链须在弹窗关闭后启动。
                choiceAccepted = true
                return doFinalStep { deliver(selectedValue?.runner) }
            }

            override fun canceled() {
                // 243 的已显示 popup 在选择后 dispose 也经 cancel；不是用户按 Esc。
                if (!choiceAccepted) deliver(null)
            }
        }
        return step
    }

    /** 弹窗列表项（Runner 包装）。 */
    internal data class RunnerItem(val runner: Any, val name: String, val icon: Icon?)

    /**
     * 创建临时 .sql 文件（含 SQL），返回 (VirtualFile, PsiFile)。
     * VF 作为控制台挂载点（attachConsole），PsiFile 作为 Runner 上下文。
     *
     * M36.5.2：PSI 查找包 ReadAction——EDT 直调 findFile 触发 LanguageSubstitutors 慢操作
     * 硬抛 SEVERE（沙盒日志实证；M36.5 默认展示使 prepareConsole 在 showResult 的 EDT 上调用，
     * 不再仅限用户点执行的路径）。
     *
     * M36.5.3：方言预映射（执行"静默失败"根因修复）——沙盒日志实锤：临时文件 PSI 创建时的
     * 方言替换（GenericSQL→MySQL）依赖 SqlDialectPusher push-all，**dumb mode（索引）下被推迟
     * 到索引完成**；期间 executeQueries 按 GenericSQL PSI 解析语句静默不执行（无结果 Tab、
     * 连接尝试都未发起），重试超时后误报"密码过期/驱动未下载"（两次失败 run 均在索引窗口内，
     * 两次成功 run 方言在执行前已解析）。修复：findFile 前显式 setMapping（IDE 自身
     * ChangeDialectAction.setDialectImpl0 同路径：SqlDialectMappings.setMapping(file, dialect)），
     * 替换确定性命中映射、不再依赖 pusher/ProjectFileIndex（顺带消除映射查找途中的
     * ProjectFileIndex.isInContent 慢操作告警）。
     *
     * T-ExecCopyFix：失败可见化——原实现整段 runCatching{}.getOrNull() 吞异常零日志，
     * 用户真机 "Failed to create temporary SQL file" 无法归因。现分级记录每个 return null
     * 站点（stage 标识 + ioFile 路径/exists 状态），异常级 warn 带堆栈，并向调用方回传
     * 阶段原因（[lastTempFileFailure]）供错误文案携带。
     *
     * T-ExecCopyFix F2b：冷路径 refresh 段换 WriteIntentReadAction.compute（Computable 单类型
     * 实参，243~262 交集——T-Wia262）——修 262 真机最可能根因：refreshAndFindFileByIoFile 同步
     * VFS refresh 要求 write-safe 语境，262 平台 RefreshSessionImpl.launch 对 EDT-ReadAction
     * 硬抛 "Access is allowed from write thread only"（T-PreviewWiLock 同 API 先例）。WIRA 隐含
     * 读权限，refresh 后续链不受影响。
     *
     * T-ExecSlowOps F2：**本方法自此退化为兜底路径**——预览链成功后
     * [prewarmExecTempFile] 已在后台线程完成 写盘→refresh→方言映射→findFile→填缓存槽，
     * 弹窗打开后这里 [consumeCachedTempPsiFile] 必命中，EDT 上不再执行 refresh/findFile
     * （262 真机 "Slow operations are prohibited on EDT" SEVERE 本体，WorkspaceFileIndex
     * .ensureIsUpToDate）。冷路径仅存于无预览链触达的显式执行首跑等场景。原"后台化经评估
     * 否决"（BGT 无法安全做 EDT-only 的 setMapping）已被 [DialectPreMapping.ensureMapped]
     * 两段式（BGT 读探测 + invokeLater 派 EDT + 有界 latch 等待，绝不 invokeAndWait）解决。
     */
    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    internal fun consumeCachedTempPsiFile(project: Project): Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        return cachedTempPsiFile?.let { (vf, psi) ->
            if (isCacheUsable(project, vf)) {
                LOG.info("[M36] exec psi cache hit (no VFS refresh): ${vf.name}")
                vf to psi
            } else {
                // 跨项目/失效命中——弃缓存，禁止把别项目的 vf+psi 当当前项目用
                if (cachedTempPsiProjectName != null && cachedTempPsiProjectName != project.name) {
                    LOG.warn("[M36] exec psi cache REJECTED across project (cached=${cachedTempPsiProjectName}, current=${project.name}), rebuild")
                }
                cachedTempPsiFile = null
                cachedTempPsiProjectName = null
                null
            }
        }
    }

    /**
     * T-PreviewExecDs-02 F1：exec 临时文件 vf+psi 缓存是否对当前项目可用——既要 vf 有效，
     * 也要 vf 归属当前项目（basePath 前缀）。跨项目泄漏根因修复的判定纯函数（表驱动单测）。
     */
    internal fun isCacheUsable(project: Project, vf: com.intellij.openapi.vfs.VirtualFile): Boolean {
        if (!vf.isValid) return false
        val base = project.basePath ?: return false
        val nBase = com.intellij.openapi.util.io.FileUtil.toSystemIndependentName(base).trimEnd('/')
        val nPath = com.intellij.openapi.util.io.FileUtil.toSystemIndependentName(vf.path)
        return nPath.startsWith("$nBase/")
    }

    /**
     * T-EC-03：执行文件单写方锁——BGT 预热（prewarmExecTempFileInner）与 EDT 冷路径
     * （createTempPsiFile）两条链并发写同一 mpsql-exec.sql 的串行化。临界区毫秒级；
     * 两个调用点均在普通线程语境（非 WriteAction 内），持本锁再取平台写锁无环。
     */
    internal val execFileWriteLock = Any()

    /**
     * T-EC-02 的裸写守卫（canFallBackToRawWrite）已由 T-ExecDocSync3 整体退役——真机日志实证其
     * 判据不可靠（EDT 忙时 getCachedDocument 恒 null，而排队中的写任务随后会建出 document），
     * 现策略见 [projectExecFile] 内注释：VFS 已有该文件即绝不裸写。
     */

    /**
     * T-MysqlGStrip T-MG-02：提交链总口的 `\G` 剥离决策（[chooseAndPrepare] 新建控制台链与
     * [executeOnConsole] 已备控制台链共用同一入口）。
     *
     * @param dataSourceUrl 控制台数据源 JDBC URL（方言判定输入）；null = 方言不可得 →
     *                      [MysqlClientGSuffix.stripForDialect] 按 MySQL 处理（默认剥离）
     * @return 两态结果——[MysqlClientGSuffix.Result.submitText] 进 exec 文件/控制台文档，
     *         [MysqlClientGSuffix.Result.original] 留给历史链（历史记原文、提交文本剥离）
     *
     * internal 供 headless 用例直测（沙盒默认 test 任务无 Database 插件，
     * chooseAndPrepare/executeOnConsole 在默认 test 任务不可达——ExecTempFileFixTest 同款口径）。
     */
    internal fun stripGSuffixForSubmit(sqlText: String, dataSourceUrl: String?): MysqlClientGSuffix.Result =
        MysqlClientGSuffix.stripForDialect(sqlText, MysqlClientGSuffix.dialectFromUrl(dataSourceUrl))

    /**
     * T-EC-01：document 驱动写 + **同临界区落盘**——`setText → commitDocument → saveDocument`，
     * 磁盘=VFS=内存三方在同一写临界区内收口，不留「已修改未保存」窗口（该窗口内任何绕过
     * document 的磁盘改写都触发 File Cache Conflict 弹窗，弹窗期间 invokeLater 链冻结 →
     * 执行链卡死）；落盘不再依赖 IDE 自动保存追赶。
     *
     * **仅 EDT 调用**（BGT 统一经 [dispatchDocWriteOnEdt] 派发）——T-ExecDocSync 关键发现
     * （javap + 沙盒堆栈双实证）：262 平台文件 document（非 FREE_THREADED）的 setText 包
     * CommandProcessor.executeCommand，BGT 直调硬抛 EDT-only 断言（RuntimeExceptionWithAttachments）。
     * 旧实现 BGT 预热走 document 分支**每次必炸**，runCatching 吞掉后静默掉进裸写兜底——
     * 这正是真机 File Cache Conflict 链的本体（非低概率竞态）。
     *
     * document 未加载（fileDoc == null，即无冲突面）走 setBinaryContent（VFS+磁盘同步）；
     * text 相同但 document 仍 modified（用户改后还原等）时同样 commit+save 收敛。
     * internal 供测试。
     */
    internal fun writeVfTextViaDocument(project: Project, vf: com.intellij.openapi.vfs.VirtualFile, text: String) {
        // T-UiFixGate9：生命周期守卫——项目已 dispose（含测试框架的 temporarily-disposed 窗口：
        // 243 平台 ProjectImpl.isDisposed() = 容器态≥DISPOSE_IN_PROGRESS || isTemporarilyDisposed，
        // javap 实证该标志被 OR 进判定，守卫点在此窗口可达）后一律放弃写。半途写入（setText 成功、
        // commitDocument/saveDocument 撞上收尾中的项目抛异常）会留下「已修改未保存」脏文档，
        // 后续任一 VFS refresh 命中 MemoryDiskConflictResolver → EDT 延迟冲突处理杀掉无辜测试
        // （20261018 门禁 shard5 三轮实录的第二个毒源）。预热是投机性写入，重跑无意义。
        if (project.isDisposed) {
            com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                .info("[M36] exec doc write abandoned (project disposed, T-UiFixGate9): ${vf.name}")
            return
        }
        // T-PreviewFast P4：程序性写入整段开闸——文档变更事件同步派发给自动落盘监听，
        // 闸门开着即跳过（内容由本函数自己落盘，无需 1.5s 后再写一遍）。
        // T-UiFixGate9：写体内自包 WriteAction（平台可重入，已持写动作的调用方形态不变）——
        // 零窗口直写分支需要 VFS 写权限，原「等内容路径不需要写权限」的裸调约定随之失效。
        @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
        com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
            ExecDocAutosaveGate.suppressDuring {
                val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                // T-UiFixGate9：改 getCachedDocument（**不主动建 document**）+ 双路写。
                // 旧实现 getDocument 会主动建 document，随后 setText→saveDocument 之间存在
                // ~30ms「已修改未保存」窗口——该窗口对**异步 refresh 线程**可见（EDT 原子性管不到
                // 会话应用线程），边界迟到的 VFS 会话（fixture 重建/相邻测试 vf 删除重建的
                // root-model 刷新）恰在此窗口命中，MemoryDiskConflictResolver 即收集冲突并在
                // EDT 下一拍处理时抛出、杀掉正在收尾的无辜测试（20261018 门禁三轮实录 +
                // [PROBE-GATE9] 探针实锤：victim 写窗口被建条目会话正中）。零窗口直写不建
                // document，写后任一时刻 resolver 看到的都是干净态，冲突无从收集。
                // 编辑器打开（真机控制台正看着这份内容，需视图同步）或文档已脏（用户键入未存，
                // 外部覆盖须走既有冲突语义）仍走 document 路径，T-EC-01 同临界区落盘语义不变。
                val fileDoc = fdm.getCachedDocument(vf)
                if (fileDoc == null || (!isExecDocOpenInEditor(vf) && !fdm.isDocumentUnsaved(fileDoc))) {
                    vf.setBinaryContent(text.toByteArray())
                    if (fileDoc != null) {
                        // 缓存 doc 是干净态：同步从盘重载对齐新内容（平台对「外部改盘+干净 doc」
                        // 的既有语义），不产生 modified 位
                        fdm.reloadFromDisk(fileDoc)
                    }
                } else {
                    if (fileDoc.text != text) {
                        fileDoc.setText(text)
                    }
                    // setText 的 write command 结束时自动 commit；显式 commitDocument 双保险
                    // （同步后同 tick 内 findFile 的 PSI 立即对齐，M36.5.20 语义保留）
                    com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(fileDoc)
                    // T-EC-01 核心：同临界区落盘——磁盘即时与内存一致，document 不滞留 modified 态
                    // T-PreviewFast P5：落盘前先判「已修改未保存」——内容本就一致时（连续预览同一
                    // SQL 的常见路径）跳过磁盘写，消除每次预览的无效落盘与随之而来的 VFS/索引噪音
                    if (fdm.isDocumentUnsaved(fileDoc)) {
                        fdm.saveDocument(fileDoc)
                    }
                }
            }
        }
        // T-DevAcceptFix2 ④：记录写入快照（内容 + 时间戳）——差量快路径在无 document 时据此判等，
        // 取代原先的 EDT 读盘（平台 SlowOperations 断言来源）
        lastWrittenExecContent[vf.path] = ExecWrittenSnapshot(text, vf.timeStamp)
    }

    /** BGT 派 EDT document 写的有界等待预算（毫秒）——DialectPreMapping 同款取值；EDT 病态冻结时超时放行走守卫降级。 */
    internal const val EDT_EXEC_DOC_SYNC_WAIT_MS = 2000L

    /** T-DialectInferPollution W11：排队执行意图的**自有结算**超时（毫秒）——占门链 60s 逃生太长，
     *  排队者 15s 即 BLOCKED 解除加载态（门不动，owner 链照常），用户重试即可。 */
    internal const val PENDING_EXECUTE_TIMEOUT_MS = 15_000L

    /** T-DialectInferPollution W11：排队意图超时文案（沿用准备超时键，语义一致：未提交 SQL）。 */
    internal val PENDING_EXECUTE_TIMEOUT_MESSAGE: String
        get() = MpSqlVisualizerBundle.message("execute.prepare.timeout")

    /**
     * T-EC-01/T-EC-03 编排：document 写统一派发 EDT——EDT 调用方同步直执行；BGT 两段式
     * invokeLater + 有界 latch 等待（DialectPreMapping.ensureMapped 同款：BGT 等 EDT，
     * EDT 永不反向阻塞，超时自愈不互锁）。失败注入缝 [execWriteActionFailureInjection]
     * 在 [docWriteOnce] 内统一生效（EDT/BGT 两形态等价）。
     *
     * T-PreviewFast P2：`waitBudgetMs <= 0` 时走 **fire-and-forget**（派发后立即返回 true）——
     * 预热是投机性写入，T-ExecDocSync3 取消裸写后超时已无副作用，没必要让 BGT 白等 2 秒。
     *
     * T-FixWriteSafe（T-WriteSafeCtx 留档补口）：两处投递原用 1 参 `invokeLater`，即隐式
     * `ModalityState.defaultModalityState()`。平台源码实证（`openapi/application/modality.kt`：
     * `defaultModalityImpl()` 依次取 `ProgressManager.currentProgressModality` → 线程/协程上下文
     * modality → NON_MODAL）：BGT 常态取到 NON_MODAL（`TransactionGuardImpl:37` 构造期即登记为
     * write-safe，与显式 `nonModal()` 等价），但**在模态进度/带 modality 上下文里会取到该模态**；
     * 若该模态本身是「写不安全进入」的（`enteredModality:188-190` 存的是进入那一刻
     * `myWritingAllowed` 的快照），排队的 runnable 就在该模态事件泵内以 write-unsafe 身份执行 →
     * 改文档即 `Write-unsafe context!`。故两处投递的**写体**统一过 [DocWriteContext] 闸门：
     *  - fire-and-forget：整拍改以 nonModal 投递（`invokeLaterWriteSafe`）——本路径无有界等待，
     *    模态存续期延后一拍正是「模态期间禁改文档」的正确语义，不会撞任何预算；
     *  - 有界等待：**保留默认模态投递**（保证能在该模态事件泵内执行，常态与改前逐字一致），
     *    只把写体过闸（`runWriteSafe`）：常态判为可写 → 就地写 + 同拍 countDown；仅当判为不安全
     *    才转 nonModal 投递并**立即** countDown（ok=false）——不让调用方白等 2 秒预算。延后那一拍
     *    重跑 [docWriteOnce] 是幂等的（内容相同不 setText、已落盘不 saveDocument），且该超时/未完成
     *    路径已由 T-ExecDocSync3 定为无害（不裸写、内容旧一拍由后续同步链收敛）；
     *  - EDT 直调（T-PreviewEdtGuard S2 补口）：原样直跑 [docWriteOnce]，write-unsafe 活动里
     *    （WriteAction 不 confer 写安全资格，T-WriteSafeCtx）即 R33 三验 2 组 Write-unsafe 的本体
     *    ——现同样过闸（runWriteSafe）：可写就地执行（ok=写结果）；不可写转 nonModal 延后一拍
     *    并返回 false（调用方均不消费该返回值，见分支内注释）。
     */
    /** internal 供测试（T-PreviewEdtGuard S2：EDT 直调分支闸门行为直测）。 */
    internal fun docWriteOnce(project: Project, vf: com.intellij.openapi.vfs.VirtualFile, sql: String): Boolean = runCatching {
        if (execWriteActionFailureInjection) {
            error("injected exec WriteAction failure (T-ExecDocSync test seam)")
        }
        // T-UiFixGate9：派发拍执行时刻复查——fire-and-forget 写（waitBudgetMs=0）排队期间项目
        // 可能已进入 dispose/temporarily-disposed；DocWriteContext.invokeLaterWriteSafe 的
        // Disposer.isDisposed 守卫对测试框架的临时 dispose 失明（只置 ProjectImpl 标志、不动
        // ObjectTree），须以 project.isDisposed（已 OR temporarily 标志）复查并放弃写。false =
        // 有界等待路径照常走「不裸写、内容旧一拍」既有降级，语义不变。
        if (project.isDisposed) {
            com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                .info("[M36] exec doc write refused at dispatch (project disposed, T-UiFixGate9): ${vf.name}")
            return@runCatching false
        }
        @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
        com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
            writeVfTextViaDocument(project, vf, sql)
        }
        true
    }.onFailure { e ->
        com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
            .warn("[M36] exec doc write FAILED: ${e.javaClass.simpleName}: ${e.message}")
    }.getOrDefault(false)

    /**
     * T-EC-04：VFS 缺条目裸写路径的防御性收口——raw write 后由调用方 refresh 得到 vf，
     * 此处探测 document（getCachedDocument，不主动创建）或 VFS 内容与 sql 的分叉，
     * 命中则经 [dispatchDocWriteOnEdt] 补一次 document 驱动同步（T-EC-01 同款落盘收口，
     * 写侧统一派 EDT）。正常流程恒为 no-op（磁盘刚写入 == sql）；防御 VFS 陈旧条目/
     * 裸写与 refresh 之间的外部改写残留。
     *
     * T-PreviewChainNoise W1：分叉探测**按线程分流**（T-DevAcceptFix2 ④ 同款定例）。
     * 此前无条件 `vf.inputStream.readBytes()` 读盘，经 `createTempPsiFile` 的 EDT 冷路径
     * （点执行计划 → prepareConsole → 本链）每次都命中平台「Slow operations are prohibited
     * on EDT」断言（2026-10-23 用户真机日志 ×2 实证；同文件 `execFileContentMatches` 早在
     * T-DevAcceptFix2 ④ 就为同一调用链删过同款读盘，本函数漏回填）。分流判据：
     *  - 有 document：只看内存态（`execFileContentMatches` 同口径——文档是权威视图），
     *    额外认「内容已相等但未保存」为分叉（F4 收敛形态：doc=新 SQL、磁盘=旧 SQL）；
     *  - 无 document + EDT：直接判不分叉（无 document 即不存在 File Cache Conflict 条件
     *    ——冲突需「已加载且已修改」的文档；且 EDT 冷路径刚由本线程写完文件，无外部改写窗口）；
     *  - 无 document + BGT：读盘比较，保留原防御面（SlowOperations 断言仅 EDT 生效）。
     *
     * @return true = 执行了修复同步（正常流程 false）
     */
    internal fun syncExecVfContentIfNeeded(project: Project, vf: com.intellij.openapi.vfs.VirtualFile, sql: String): Boolean {
        val allowDiskRead = !ApplicationManager.getApplication().isDispatchThread
        if (!probeExecVfDivergence(vf, sql, allowDiskRead)) return false
        return dispatchDocWriteOnEdt(project, vf, sql)
    }

    /**
     * T-PreviewChainNoise W1：exec 文件分叉探测纯判定（[syncExecVfContentIfNeeded] 的探测段
     * 抽出，internal 供表驱动测试）。三态见 [syncExecVfContentIfNeeded] KDoc；`allowDiskRead`
     * 由调用方按「当前是否 EDT」给出（生产= `!isDispatchThread`，测试可显式注入）。
     *
     * document 读取包 ReadAction（BGT 无隐式读；EDT 直调合法）。**有 document 分支绝不读盘**
     * ——这正是本抽取的可测不变式（见 ExecVfDivergenceProbeTest「文档在场时盘上再变也不读」用例）。
     */
    internal fun probeExecVfDivergence(
        vf: com.intellij.openapi.vfs.VirtualFile,
        sql: String,
        allowDiskRead: Boolean,
    ): Boolean {
        val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        val doc = ReadAction.compute<com.intellij.openapi.editor.Document?, Throwable> {
            fdm.getCachedDocument(vf)
        }
        return when {
            doc != null -> doc.text != sql || fdm.isDocumentUnsaved(doc)
            allowDiskRead -> ReadAction.compute<Boolean, Throwable> {
                vf.inputStream.readBytes().toString(Charsets.UTF_8) != sql
            }
            else -> false
        }
    }

    // ------------------------------------------------------------------
    // T-ExecDocSync2 F5/F6：执行文档防抖自动落盘 + 冲突探针（第四轮）
    // ------------------------------------------------------------------

    /**
     * exec 文件 VFS 路径匹配（F5 自动落盘 / F6 探针共用过滤器；VFS 路径恒 '/' 分隔）。
     * internal 供表驱动测试。
     */
    internal fun isExecVfPath(path: String): Boolean =
        path.endsWith("/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec/mpsql-exec.sql")

    /** 防抖窗（毫秒）——连击合并，窗内 modified-unsaved 暴露面上界。 */
    private const val EXEC_DOC_AUTOSAVE_DELAY_MS = 1500L

    /** F5 防抖调度器（daemon，不阻 IDE 退出；任务本身极轻——路径过滤后仅换句柄）。 */
    private val execDocAutosaveExecutor =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mpv-exec-doc-autosave").apply { isDaemon = true }
        }

    /** F5 待执行的防抖任务（新变更到达即顶掉旧任务）。 */
    private val execDocAutosavePending =
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<*>?>(null)

    /**
     * T-PS4-11 快判缓存：上次命中 exec 路径的 document（**弱引用**——防强引用滞留已关闭文档，
     * GC 后 get()==null 自然退回原链探测）。companion 监听是应用级的，IDE 全局每次文档变更
     * 都会进 [scheduleExecDocAutosave]；exec 文档连续变更（用户在控制台键入）是唯一需要跟进
     * 落盘的形态，其余文档每次都白付 getFile + endsWith。identity 比对（===）而非 equals，
     * document 换实例（文件关闭重开）即视为未命中。
     */
    private val lastExecDocRef =
        java.util.concurrent.atomic.AtomicReference<java.lang.ref.WeakReference<com.intellij.openapi.editor.Document>?>(null)

    /**
     * F6 探针已安装项目集（连接随项目 disposable 自动断开，此处只防重复安装）。
     *
     * T-PS5-08：改**弱键集**（`WeakHashMap` 键弱引用：项目唯一强引用消失即可回收）——此前强引用
     * 集把项目对象钉到显式 `remove`（走 [hookProjectClose] 的项目 Disposer，但该函数在
     * `hookProjectsClose` 已登记时会提前 return、不再登记第二次项目级 Disposer，留下不可回收窗口）。
     * 去重/判空语义不变（条目只用于「是否已安装」判定）。
     */
    internal val conflictProbeProjects: MutableSet<Project> =
        java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<Project, Boolean>()),
        )

    /** T-PS5-08 测试缝：探针项目集（弱引用可达性断言用；生产链路无调用点）。 */
    internal fun conflictProbeProjectsForTest(): MutableSet<Project> = conflictProbeProjects

    /**
     * F5：应用级 document 变更监听（惰性安装一次）——exec 文档每次变更（用户在控制台手改/
     * 插件 setText）后 1.5s 防抖自动落盘。动机：console 编辑器文档即 exec 文件 document
     * （attachConsole 挂载点），用户手改滞留「已修改未保存」是第四轮冲突弹窗「内存中更改」
     * 一半的唯一插件拦不到的源头；帧失活自动保存最快也要切帧才触发，防抖落盘把暴露面压到
     * 防抖窗内。落盘方向恒 doc→disk（saveDocument），不回读不改写用户内容。
     */
    private fun installExecDocAutosaveListener() {
        // T-PS4-11：改两参重载 addDocumentListener(listener, disposable)，挂 [ExecDocAutosaveHost]
        // （应用级轻服务 = Disposable parent）——此前单参裸装、装后永不卸载，插件动态卸载
        // （DynamicInstallSupport → 平台 unloadPlugin）后监听残活并钉住旧 classloader，正是
        // 动态卸载屏障要杜绝的形态。挂靠定案依据见 [ExecDocAutosaveHost] KDoc。
        ExecDocAutosaveHost.getInstance().ensureListenerInstalled(
            object : com.intellij.openapi.editor.event.DocumentListener {
                override fun documentChanged(e: com.intellij.openapi.editor.event.DocumentEvent) {
                    scheduleExecDocAutosave(e.document)
                }
            },
        )
    }

    private fun scheduleExecDocAutosave(doc: com.intellij.openapi.editor.Document) {
        // T-PreviewFast P4：程序性写入不排自动落盘——闸门开着说明本次变更来自插件写入
        //（写入方自己已落盘），防抖落盘只为用户手改兜底。保持最先判（闸门语义先于快判，
        // 程序性写入高频路径不因快判命中而漏抑制）
        if (ExecDocAutosaveGate.isSuppressed()) return
        // T-PS4-11 快判：上次命中的 document 直接身份比对短路（免 getFile + endsWith）；
        // 未命中走原链探测回退，确认命中后更新缓存。弱引用被 GC 后 get()==null 视为未命中。
        if (lastExecDocRef.get()?.get() !== doc) {
            val vf = runCatching {
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(doc)
            }.getOrNull() ?: return
            if (vf == null || !isExecVfPath(vf.path)) return
            lastExecDocRef.set(java.lang.ref.WeakReference(doc))
        }
        val prev = execDocAutosavePending.getAndSet(
            execDocAutosaveExecutor.schedule(
                { flushExecDocAutosave(doc) },
                EXEC_DOC_AUTOSAVE_DELAY_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            ),
        )
        prev?.cancel(false)
    }

    private fun flushExecDocAutosave(doc: com.intellij.openapi.editor.Document) {
        ApplicationManager.getApplication().invokeLater {
            runCatching {
                if (!autosaveExecDocNow(doc)) return@invokeLater
                com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                    .info("[M36] exec doc autosaved (debounced ${EXEC_DOC_AUTOSAVE_DELAY_MS}ms, T-ExecDocSync2 F5)")
            }.onFailure { e ->
                com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                    .warn("[M36] exec doc autosave FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * F5 原语：EDT 上守卫落盘——doc 无主文件/已失效/未修改即 no-op（false）；确需落盘包
     * WriteAction saveDocument（T-EC-01 同款落盘语义）。internal 供测试。
     */
    internal fun autosaveExecDocNow(doc: com.intellij.openapi.editor.Document): Boolean {
        val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        val vf = runCatching { fdm.getFile(doc) }.getOrNull()
        if (vf == null || !vf.isValid) return false
        // 262：Document.isModified 已从接口移除——「已修改未保存」唯一查询是 isDocumentUnsaved
        if (!fdm.isDocumentUnsaved(doc)) return false
        @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
        com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
            fdm.saveDocument(doc)
        }
        return true
    }

    /**
     * F6：冲突探针——「文件缓冲区冲突」弹窗（MemoryDiskConflictResolver）响应的是平台对修改中
     * document 发起的 reload，reload 前平台经 FileDocumentManagerListener.beforeFileContentReload
     * 广播（与弹窗实现同 topic，监听先后不保证，故仅取证不拦截）。exec 文件命中即落 warn 指纹
     * （doc/vf 长度与 stamp、unsaved 态）：F1-F5 全上后若真机仍复发，idea.log 直出现场数据，
     * 替代人工复现排查。经项目 messageBus 连接（随项目 disposable 自动断开）。
     */
    internal fun mapDialectBeforePsi(
        project: Project,
        vf: com.intellij.openapi.vfs.VirtualFile,
        LOG: com.intellij.openapi.diagnostic.Logger,
        renderedDialectName: String? = null,
    ) {
        // O-145 W7：有渲染事实时 exec PSI 必须跟随渲染方言；没有 request 的旧调用继续沿用
        // O-136 的 last-used/default 数据源路由。平台无对应 parser 时降级 GenericSQL，不冒充 MySQL。
        val targetDs = if (renderedDialectName == null) ExecDialectRouting.resolveTargetDataSource(project) else null
        val renderedLanguage = renderedDialectName?.let {
            ExecDialectRouting.resolvePluginDialectLanguage(project, it)
        }
        val targetLanguage = when {
            renderedDialectName == null -> ExecDialectRouting.resolveTargetLanguage(project, targetDs)
            renderedLanguage != null -> renderedLanguage
            else -> DialectPreMapping.languageById(project, ExecDialectRouting.GENERIC_LANGUAGE_ID)
        }
        val targetId = (targetLanguage as? com.intellij.lang.Language)?.id
            ?: if (renderedDialectName == null) ExecDialectRouting.MYSQL_LANGUAGE_ID else ExecDialectRouting.GENERIC_LANGUAGE_ID
        // T-PreviewFlash：插件侧幂等短路——mpsql-exec.sql 路径稳定，同 vf 已映射过 MySQL 直接跳过，
        // 消除每次预览的点 setMapping（同值 setMapping 仍触发 SqlDialectPusher push all dumb 任务，见沙盒日志）。
        // O-136：短路收紧为「同 vf 且同方言」——目标数据源方言切换后同 vf 也必须重映射。
        val inProcessMapped = dialectMappedVf?.isValid == true && dialectMappedVf === vf && dialectMappedLanguageId == targetId
        if (DialectPreMapping.ensureMapped(project, vf, targetLanguage, inProcessMapped)) {
            dialectMappedVf = vf
            dialectMappedLanguageId = targetId
            LOG.info("[M36] dialect pre-mapped to $targetId for ${vf.name} (O-136 ds-dialect routing, ds=${targetDs?.javaClass?.simpleName})")
        }
    }

    /** 打开 Database 工具窗口（无配置数据源时引导用户，仅显式执行路径调用；预览路径静默）。 */
    internal fun openDatabaseToolWindow(project: Project) {
        runCatching {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Database")
                ?.show()
        }
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用）
    // ------------------------------------------------------------------

    /** T-ExecCopyFix F2c：exec 临时文件 vf+psi 缓存是否仍持有（clearPrepared 不清缓存断言用）。 */
    internal fun cachedTempPsiFileForTest(): Boolean = cachedTempPsiFile != null

    /**
     * T-ExplainConsoleFirst W8-fix（2026-10-24）：**调用方已确保自己在后台线程**时的预热入口。
     *
     * 与 [prewarmExecTempFile] 的区别：后者内部会再 `submit` 一个池线程并 `get(30s)` 等它——
     * 那是给「后台预热」这种非交互场景用的**自带预算**形态。若从池线程调它，就变成
     * 「池线程 A 等池线程 B」的嵌套等待；而本入口**同步跑完**，调用方自己掌控等待与续接。
     *
     * 必须传 [LOG]（调用方持有自己的 logger，便于把失败归到发起方而非本类）。
     * EDT 上调用直接拒绝并 warn——本链含 refresh/findFile 慢操作。
     */
    fun prewarmExecTempFileOnBackgroundThread(
        project: Project,
        sql: String,
        renderedDialectName: String? = null,
        LOG: com.intellij.openapi.diagnostic.Logger,
    ) {
        if (ApplicationManager.getApplication().isDispatchThread) {
            LOG.warn("[M36] prewarmExecTempFileOnBackgroundThread called on EDT — refused (slow ops)")
            return
        }
        if (project.isDisposed || sql.isBlank()) return
        prewarmExecTempFileInner(project, sql, LOG, renderedDialectName)
    }

    /**
     * T-ExecSlowOps F2：后台预热 exec 临时文件（写盘 → VFS refresh → 方言映射 → findFile →
     * 填 [cachedTempPsiFile]）——**只允许后台线程调用**（EDT 直调拒绝并 warn：本链含
     * refresh/findFile 慢操作，EDT 执行正是 262 真机堆栈①的 SEVERE 本体）。
     *
     * 由 `SqlPreviewService.previewAsync` 在池线程内、`invokeLater` 派发弹窗之前调用：弹窗打开后
     * `prepareDefaultConsole → chooseAndPrepare → consumeCachedTempPsiFile` 必命中缓存，EDT 冷路径
     * （[createTempPsiFile] 的 refresh+findFile）退化为兜底（无预览链触达的显式执行路径首跑等）。
     *
     * SQL 一致性：预热写入的是 [cn.song.mybatisplus.visualizer.model.PreviewResult.sql] 原文，与
     * 面板渲染文本（格式化/参数回填后）可能不同——与 T-PreviewFlash 缓存命中路径同款语义（缓存命中
     * 时本就不重写文件，文档内容差异由下游 console doc 同步链吸收：reuseSharedConsole setText /
     * prepareAfterEditorResolved model 同步 / executeOnConsole 执行前同步）。
     *
     * 幂等：缓存已对当前项目可用即直接返回（同项目连续预览仅一次真实链）。
     * Community/无 Database 插件场景安全：本方法不触任何 Database 类，纯 VFS/PSI。
     */
    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    fun prewarmExecTempFile(project: Project, sql: String, renderedDialectName: String? = null) {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        if (ApplicationManager.getApplication().isDispatchThread) {
            LOG.warn("[M36] prewarmExecTempFile called on EDT — refused (slow ops, T-ExecSlowOps F2)")
            return
        }
        if (project.isDisposed || sql.isBlank()) return
        val cachedVf = cachedTempPsiFile?.first
        if (cachedVf != null && isCacheUsable(project, cachedVf)) {
            // O-136：缓存命中路径同样对齐方言映射（幂等读探测稳态零写）——否则目标数据源方言
            // 切换（MySQL↔DM）后预热仍按旧映射放行，Runner 枚举按旧方言过滤。
            mapDialectBeforePsi(project, cachedVf, LOG, renderedDialectName)
            // 缓存命中≠内容新鲜：连续预览 SQL 会变，而缓存命中路径不重写文件——只刷新文件
            // 内容（projectExecFile doc 驱动三方同步，vf/psi 身份不变），保证 attachConsole
            // 初始 document 与本条预览 SQL 一致；差量内容比较在 projectExecFile 内部短路。
            //
            // T-PreviewConsoleFmt F2 + T-PreviewFast P3：**该文件正被编辑器打开时跳过内容刷新**——
            // 有人正看着这份内容时预热不得改写（预热写的是「预览原文」裸单行，会盖掉面板渲染进
            // 控制台的格式化文本；真机 20260917 日志：showSql createEditor len=274/16 行 → prewarm
            // 原文 → attach 显示 1 行）。判据为「文档已加载且挂了编辑器」——原 `console.getVirtualFile
            // () === vf` 判据实机从未命中（SKIPPED 计数 0 / refreshed 10），已退役。
            if (isExecDocOpenInEditor(cachedVf)) {
                LOG.info("[M36] exec prewarm: cache hit, exec doc open in editor — content refresh SKIPPED (T-PreviewFast P3)")
                return
            }
            // T-UiFixGate9：有界派发（原 T-PreviewFast P2 的 nonBlocking=true fire-and-forget 已退役，
            // 见 projectExecFile 内注释）——缓存命中刷新同样不得留下跨边界的排队写：排队拍落在
            // 测试/交互边界时，迟到的 VFS 会话会命中 setText→save 窗口的「已改未存」态收集冲突。
            runCatching { projectExecFile(project, sql) }.onFailure { e ->
                // T-FatalPassthrough T-FP-02b（独立复核阻断 2）：写盘链的 fatal 不得被本 runCatching
                // 吞成一条 warn（否则 prewarm 链 4 处既有透传全部作废）。非 fatal 仍原样 warn 降级。
                if (FatalErrors.isFatal(e)) throw e
                LOG.warn("[M36] exec prewarm refresh-content failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            LOG.info("[M36] exec prewarm: cache hit, content refreshed only")
            return
        }
        // 并发预览（gutter 连点/多面板）只允许一条真实预热链——其余直接返回，弹窗冷路径兜底
        if (!execPrewarmInFlight.compareAndSet(false, true)) {
            LOG.info("[M36] exec prewarm skipped (another prewarm in flight)")
            return
        }
        // T-PrewarmWilDeadlock W2：有界预热——真实链独立执行 + 预算等待，超时 cancel(true) 放弃本轮
        // 并照常清在途标记（否则一次挂死会让后续预热全部 skip，预热能力静默报废）；弹窗冷路径按需
        // 重建兜底。中断能否真正唤醒平台内部等待不作依赖（RefreshSessionImpl.waitFor 的
        // runBlockingMaybeCancellable 通常响应中断）；即便不可中断，业务也已脱困，滞留线程无 WIL 在身。
        val prewarmFuture = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit {
            prewarmExecTempFileInner(project, sql, LOG, renderedDialectName)
        }
        try {
            prewarmFuture.get(EXEC_PREWARM_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            prewarmFuture.cancel(true)
            LOG.warn(
                "[M36] exec prewarm exceeded ${EXEC_PREWARM_BUDGET_MS}ms — cancelled & abandoned " +
                    "(best-effort; cold path rebuilds on demand) (T-PrewarmWilDeadlock W2)",
            )
        } catch (e: java.util.concurrent.ExecutionException) {
            // T-FatalPassthrough：内层 fatal 经 ExecutionException 包装，拆包后原样上浮给池线程
            // 平台处理器（与直跑语义一致，previewAsync 侧 runCatching 的 fatal 放行链不受影响）；
            // 非 fatal 仅 warn（与内层既有 catch 同形态）。
            val cause = e.cause ?: e
            if (FatalErrors.isFatal(cause)) throw cause
            LOG.warn("[M36] exec prewarm failed: ${cause.javaClass.simpleName}: ${cause.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            prewarmFuture.cancel(true)
            LOG.warn("[M36] exec prewarm waiter interrupted — abandoned (T-PrewarmWilDeadlock W2)")
        } finally {
            execPrewarmInFlight.set(false)
        }
    }

    /**
     * T-PreviewFast P1：exec 文件当前内容是否已等于目标 SQL——差量快路径判据（双源）。
     *
     * 有已加载 document 时以 **document 文本**为准（UI/控制台所见即权威，也是落盘会写入的内容）；
     * 无 document 时读 **vf 内容**。两者比对失败（异常/不可读）一律返回 false（保守走正常写入路径）。
     * internal 供测试。
     */
    internal fun execFileContentMatches(
        project: Project,
        vf: com.intellij.openapi.vfs.VirtualFile,
        sql: String,
    ): Boolean = runCatching {
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getCachedDocument(vf)
        if (doc != null) {
            doc.text == sql
        } else {
            // T-DevAcceptFix2 ④（20261006 第二轮验收）：无 document 时**禁读盘**——原实现回落
            // vf.inputStream.readBytes()，命中平台「EDT 禁慢操作」断言（EXPLAIN 直送默认路径实测：
            // 点执行计划 → createTempPsiFile → 本函数，全程 EDT）。改读「最近写入内容 + 写入时
            // VFS 时间戳」内存标记：时间戳不一致（外部改盘/他进程写）即不采信，返回 false 走正常
            // 写入路径——宁可多写一次，绝不在 EDT 读盘（线程归位定例；allowSlowOperations 逃生舱禁）。
            val snap = lastWrittenExecContent[vf.path]
            snap != null && snap.timeStamp == vf.timeStamp && snap.content == sql
        }
    }.getOrDefault(false)

    /** T-DevAcceptFix2 ④：最近一次经本类写入 exec 临时文件的内容快照（内容 + 写入时 VFS 时间戳）。 */
    internal data class ExecWrittenSnapshot(val content: String, val timeStamp: Long)

    /**
     * T-DevAcceptFix2 ④：写入标记表（key = VFS 路径）。写点唯一=本类的 exec 文件写入路径
     * （[writeVfTextViaDocument]）；读点=差量快路径（[execFileContentMatches]，仅在无 document 时）。
     * 时间戳校验使「外部改盘」自动失效标记（不会误判「已一致」而跳过必要写入）。
     */
    internal val lastWrittenExecContent =
        java.util.concurrent.ConcurrentHashMap<String, ExecWrittenSnapshot>()

    /**
     * T-PreviewFast P3：exec 文件是否**正被编辑器打开**（= 有人正在看这份内容）——预热据此决定
     * 是否跳过内容刷新。
     *
     * 取代 T-PreviewConsoleFmt 的 `console.getVirtualFile() === vf` 判据：真机实测该等式从未成立
     * （`content refresh SKIPPED` 计数 0 而 `content refreshed only` 10 次），预热因此每次仍改写
     * 文件、与面板回填互相覆盖一轮。改判「该 vf 的文档已加载且挂了编辑器」——语义正是
     * 「别改用户眼前的内容」，且不依赖 DBN 内部方法。internal 供测试。
     */
    internal fun isExecDocOpenInEditor(vf: com.intellij.openapi.vfs.VirtualFile): Boolean = runCatching {
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getCachedDocument(vf)
            ?: return false
        com.intellij.openapi.editor.EditorFactory.getInstance().getEditors(doc).isNotEmpty()
    }.getOrDefault(false)

    /** [prewarmExecTempFile] 的真实链（已持有 in-flight 标记；仅后台线程调用）。 */
    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    private val hookProjectsClose =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Project, Boolean>())

    /**
     * T-ExecSlowOps F2：填 exec 临时文件 vf+psi 缓存槽（项目绑定；BGT/EDT 均可调用——volatile 写）。
     * T-PerfSweep2-04：同时挂一次性项目关闭钩子——项目关闭即清槽，不再等下次消费顶掉
     * （此前项目关闭后槽里残留该项目的 vf+psi 强引用至下次写入；isCacheUsable 惰性校验
     * 保留为双保险）。[parentDisposable] 默认项目自身，测试注入自有 Disposable 手动触发。
     */
    internal fun markCachedTempPsiFile(
        project: Project,
        vf: com.intellij.openapi.vfs.VirtualFile,
        psi: PsiFile,
        parentDisposable: com.intellij.openapi.Disposable = project,
    ) {
        cachedTempPsiFile = vf to psi
        cachedTempPsiProjectName = project.name
        hookProjectClose(project, parentDisposable)
        // T-ExecDocSync2 F5/F6 挂载点：exec 缓存槽建立即安装防抖自动落盘监听（应用级惰性一次）
        // 与冲突探针（项目级一次）；非 exec 路径 vf 进不来本方法，路径过滤双保险。
        // 安装段 runCatching 降级——探针 connect 在无 messageBus 的代理桩项目上 NPE
        // （CachedTempPsiFileTest 全代理桩实证）；两件均为增强件，失败仅失去防抖落盘
        // 与冲突取证，不得阻断缓存主链
        if (isExecVfPath(vf.path)) {
            runCatching {
                installExecDocAutosaveListener()
                installExecConflictProbe(project)
            }.onFailure { e ->
                com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                    .warn("[M36] exec doc autosave/probe install skipped: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun hookProjectClose(project: Project, parentDisposable: com.intellij.openapi.Disposable) {
        if (!hookProjectsClose.add(project)) return
        Disposer.register(parentDisposable) {
            hookProjectsClose.remove(project)
            conflictProbeProjects.remove(project)
            val cached = cachedTempPsiFile
            if (cached != null && cached.second.project == project) {
                cachedTempPsiFile = null
                cachedTempPsiProjectName = null
            }
        }
    }

    /** T-ExecCopyFix F2a：最近一次 createTempPsiFile 失败的阶段原因（成功时 null）。 */
    internal fun lastTempFileFailureForTest(): String? = lastTempFileFailure

    /** T-ExecCopyFix F2c 测试缝：等价链路置缓存槽（psi 经 PsiManager.findFile 同生产路径配对）。 */
    internal fun markCachedTempPsiFileForTest(project: Project, vf: com.intellij.openapi.vfs.VirtualFile) {
        val psi = ReadAction.compute<PsiFile?, Throwable> {
            PsiManager.getInstance(project).findFile(vf)
        }
        checkNotNull(psi) { "markCachedTempPsiFileForTest: findFile null for ${vf.name}" }
        markCachedTempPsiFile(project, vf, psi)
    }

    /** T-ExecCopyFix F2c 测试缝：清缓存槽（测试隔离收尾）。 */
    internal fun clearCachedTempPsiFileForTest() {
        cachedTempPsiFile = null
        cachedTempPsiProjectName = null
    }

    /** T-ExecDocSync 测试缝：置 true 时 [docWriteOnce] 注入失败（测 T-EC-02 守卫拒绝路径；EDT/BGT 两形态统一生效）。 */
    internal var execWriteActionFailureInjection = false

    /** T-PS4-11 测试缝：防抖任务是否在途（未完成）——监听生效/卸载的行为断言点。 */
    internal fun execDocAutosaveScheduledForTest(): Boolean =
        execDocAutosavePending.get()?.isDone == false

    /** T-PS4-11 测试缝：取消在途防抖任务并清快判缓存（测试隔离收尾，不触碰宿主挂载态）。 */
    internal fun resetExecDocAutosaveForTest() {
        execDocAutosavePending.getAndSet(null)?.cancel(false)
        lastExecDocRef.set(null)
    }
}



