package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate
import cn.song.mybatisplus.visualizer.execute.ExecutionErrorFormatter
import cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo
import cn.song.mybatisplus.visualizer.execute.SqlExecutor
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * `${table}` 快捷模板三动作（T-PreviewUx-Pack #3，吸收 DBX sqlShortcutActions 口径）：
 * mapper 编辑器 / SQL 预览编辑器内**经整串校验的表名**一键套模板送原生 Database 控制台执行——
 * EXPLAIN 执行计划 / COUNT 行数 / 抽样 10 行。三动作共享一个 action group 注册 EditorPopupMenu
 * （plugin.xml），与「生成 CRUD SQL」右键入口并存不合并菜单（目标互补：CRUD 生成 vs 诊断三动作）。
 *
 * T-SqlTemplateCaret：目标文本**不再要求先划选区**——有非空选区时优先取选区，
 * 无选区（或选区为空白）时退化为**光标处的标识符**（[identifierAtCaret]）；
 * 两路都须通过单个表标识符整串校验，否则动作禁用且点击时再次拒绝。理由是「光标停在表名上就能用」录制与使用都更顺手；光标退化分支
 * 只做词边界扫描、不依赖具体语言/SQL 注入，故在 mapper XML / `.sql` / Java / 预览弹窗文本区
 * 等任意编辑器里都成立。
 *
 * 执行通道：既有内嵌控制台链路（execute/DataSourceSelector + [SqlExecutor]）——选数据源 →
 * 原生 Database 控制台执行，凭证不出 IDE；Community（无 database 插件）由 [SqlExecutor] 提示降级。
 * 三动作产物均为查询类语句（EXPLAIN/COUNT/SELECT），免 DML 二次确认（与 EXPLAIN 既有口径一致，
 * SqlTypeClassifier.QUERY 同族）。
 */
enum class SelectedTableTemplateKind { EXPLAIN, COUNT, SAMPLE }

/** 按本次动作捕获的项目/来源方言渲染模板；null 表示该方言不支持对应形态。 */
internal fun renderSelectedTableTemplate(
    kind: SelectedTableTemplateKind,
    tableName: String,
    dialect: Dialect,
    sampleLimit: Int,
): String? = when (kind) {
    SelectedTableTemplateKind.EXPLAIN -> dialect.renderExplainStatement("SELECT * FROM $tableName")
    SelectedTableTemplateKind.COUNT -> "SELECT COUNT(*) FROM $tableName"
    SelectedTableTemplateKind.SAMPLE ->
        "SELECT * FROM $tableName ${dialect.renderLimit(LimitClause(count = sampleLimit.coerceIn(1, 10000).toLong()))}"
}

abstract class SelectedTableSqlTemplateAction(
    private val textKey: String,
    private val templateKind: SelectedTableTemplateKind,
) : AnAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.text = MpSqlVisualizerBundle.message(textKey)
        // UX Community 预判置灰：三个模板动作都经 SqlExecutor 送原生控制台执行，无 Database
        // 插件时直接灰置 + tooltip（presentation.description），不再「点了才报错」。
        val hint = cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.unavailableHint()
        e.presentation.isEnabled = validatedTableIdentifier(selectedOrCaretText(e)) != null && hint == null
        e.presentation.description = hint
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tableName = validatedTableIdentifier(selectedOrCaretText(e)) ?: return
        val sourceFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val dialect = DialectRegistry.effective(project, sourceFile)
        val sampleLimit = runCatching {
            cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.sampleLimit
        }.getOrDefault(10)
        val sql = renderSelectedTableTemplate(templateKind, tableName, dialect, sampleLimit)
        // T-SqlTemplateUx P2：失败反馈与预览面板拉平——除气泡外挂「定位到出错语句」「复制错误信息」
        // 两个可操作项（做法②：不新建面板，直接给 Notification 挂 NotificationAction）。
        // 定位锚点在动作触发时（EDT）从**触发编辑器**捕获（用户选中表名处即语句来源）；
        // 解析不出（无编辑器 / 文件已失效）则不给定位动作，退化为只给「复制错误信息」——
        // 与面板 errorLocateAction 的「先解析、后显隐」同姿态，绝不虚标可点的定位。
        val locate: (() -> Unit)? = resolveErrorLocateAnchor(
            e.getData(CommonDataKeys.EDITOR),
            e.getData(CommonDataKeys.VIRTUAL_FILE),
        )?.let { anchor ->
            { OpenFileDescriptor(project, anchor.file, anchor.line0, 0).navigate(true) }
        }
        if (sql == null) {
            notifySqlTemplateError(
                project,
                MpSqlVisualizerBundle.message("panel.explain.unsupported", dialect.name),
                locate,
            )
            return
        }
        val renderMode = if (ProjectPluginSettings.getInstance(project).effectiveDialectAuto()) {
            ExecutionDialectGate.RenderMode.AUTO
        } else {
            ExecutionDialectGate.RenderMode.MANUAL
        }
        val request = ExecutionDialectGate.Request(
            renderMode = renderMode,
            renderedDialectName = dialect.name,
            intent = ExecutionDialectGate.Intent.TEMPLATE,
            // 实际目标只在原生选择器确认后可知，当前执行 API 无法把同一目标固定给二次提交；
            // 因而目标不一致时诚实阻断，不把“可重新渲染模板”冒充为“可无损重提交通道”。
            canLosslesslyRerender = false,
            userTextUntouched = true,
        )
        executeTemplate(project, sql, locate, request)
    }

    protected open fun executeTemplate(
        project: Project,
        sql: String,
        locate: (() -> Unit)?,
        request: ExecutionDialectGate.Request,
    ) {
        // UX Community 预判置灰的代码侧守卫：动作被程序化触发时同样不提交
        //（灰置只挡 UI，不挡代码路径）。放在可覆写的执行入口上，子类测试覆写不受影响。
        if (!cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable()) return
        SqlExecutor.execute(
            project, sql, null, true,
            onError = { errorMsg -> notifySqlTemplateError(project, errorMsg, locate) },
            onErrorRich = { info -> notifySqlTemplateError(project, info, locate) },
            dialectGateRequest = request,
        )
    }

    /** 目标文本入口：从事件取编辑器，交 [editorTemplateText] 决策（选区优先、光标处退化）。 */
    private fun selectedOrCaretText(e: AnActionEvent): String? =
        e.getData(CommonDataKeys.EDITOR)?.let { editorTemplateText(it) }

    // 仅访问编辑器选择区/光标（UI 状态，非 PSI）→ EDT 更新线程安全且无慢操作风险（ConvertSqlToJavaAction 同款）
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    companion object {
        /** 提示通知组（O-4 预览操作 toast 同组）。 */
        const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"
    }
}

/**
 * T-SqlTemplateCaret：编辑器目标文本——**非空选区优先**（逐字节原样返回选区文本），
 * 否则取光标处标识符。两路都取不到返回 null（动作据此禁用）。
 *
 * 空白选区也退化到光标处：真机实证预览弹窗的 SQL 文本区在「无选区」时会报告一个纯空格选区
 * （`hasSelection=true`、`selectedText` 为 14 个空格），只按 `hasSelection()` 分流会把这个
 * 场景重新判成「无内容」而保持禁用。
 */
internal fun editorTemplateText(editor: Editor): String? {
    val selectionModel = editor.selectionModel
    val selected = if (selectionModel.hasSelection()) selectionModel.selectedText else null
    val caretIdentifier = identifierAtCaret(editor.document.charsSequence, editor.caretModel.offset)
    return resolveTemplateText(selected, caretIdentifier)
}

/**
 * T-SqlTemplateCaret：目标文本优先级（纯逻辑，便于单测）——[selectedText] 非空则原样返回；
 * 否则退回 [caretIdentifier]（仍需非空）；都没有返回 null。
 */
internal fun resolveTemplateText(selectedText: String?, caretIdentifier: String?): String? {
    if (selectedText != null && selectedText.isNotBlank()) return selectedText
    return caretIdentifier?.takeIf { it.isNotBlank() }
}

private val NON_TABLE_KEYWORDS = setOf(
    "select", "insert", "update", "delete", "drop", "truncate", "alter", "create",
    "from", "where", "join", "with", "limit", "explain",
)

/** 只接受一个表名或以点连接的标识符段；不截取候选前缀来掩盖多语句输入。 */
internal fun validatedTableIdentifier(candidate: String?): String? {
    val text = candidate?.trim() ?: return null
    if (text.isEmpty() || text.length > 512 || text.any { Character.isISOControl(it) }) return null
    if (';' in text || "--" in text || "/*" in text || "*/" in text) return null
    var index = 0
    while (index < text.length) {
        val closer = QUOTE_OPENERS[text[index]]
        if (closer != null) {
            index++
            var content = false
            var closed = false
            while (index < text.length) {
                if (text[index] == closer) {
                    if (index + 1 < text.length && text[index + 1] == closer) {
                        content = true
                        index += 2
                        continue
                    }
                    index++
                    closed = true
                    break
                }
                content = true
                index++
            }
            if (!content || !closed) return null
        } else {
            val start = index
            while (index < text.length && isIdentifierChar(text[index])) index++
            if (start == index || text.substring(start, index).lowercase() in NON_TABLE_KEYWORDS) return null
        }
        while (index < text.length && text[index].isWhitespace()) index++
        if (index == text.length) return text
        if (text[index] != '.') return null
        index++
        while (index < text.length && text[index].isWhitespace()) index++
        if (index == text.length) return null
    }
    return null
}

/** 标识符字符：字母/数字/下划线（与 log/LiteralParamRecovery、sql/ExplainGate 等既有 `isWordChar` 同族）。 */
private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

/** 开引号 → 对应闭引号（SQL 三种标识符引号：反引号 / 双引号 / 方括号）。 */
private val QUOTE_OPENERS = mapOf('`' to '`', '"' to '"', '[' to ']')

/** 闭引号 → 对应开引号。 */
private val QUOTE_CLOSERS = mapOf('`' to '`', '"' to '"', ']' to '[')

/**
 * T-SqlTemplateCaret：取 [caretOffset] 处的标识符（表名/片段），取不到返回 null。
 *
 * 实现选型：**纯 `CharSequence` + 偏移量的词边界扫描**，不碰 PSI、不取编辑器高亮器/Lexer。
 * 理由是三条硬约束：
 *  1. 动作会在**任意编辑器**里被调用（mapper XML、`.sql`、Java、预览弹窗内的 SQL 文本区），
 *     语言未知、SQL 注入也未必生效——PSI 锚点与 Lexer token 都依赖具体语言/注入，命中面随宿主而变；
 *  2. `Document.getCharsSequence()` 是免拷贝视图（[cn.song.mybatisplus.visualizer.mcp.McpSourceLocator]
 *     同款用法），扫描只与光标所在词的长度相关，无慢操作；
 *  3. 纯函数（入参文本 + 偏移）可脱离编辑器做纯逻辑单测。
 *
 * 规则：
 *  - 光标优先压右侧字符、否则压左侧字符，取该字符所在的极大标识符串；
 *  - **引号包裹一起取**：紧贴的 `` ` `` / `"` / `[ ]` 成对出现时连引号一并返回（与选区路径
 *    「原样当表名」一致——见计划文档 §3.3）；
 *  - **限定名跨点**：`db.tbl` / `` `db`.`tbl` `` / `[db].[tbl]` 这类以 `.` 连接的段一并取全；
 *  - 单引号字符串**不**连引号（`'x'` 取 `x`）：`FROM 'x'` 非法，带单引号只会制造必然失败；
 *  - 光标落在空白/标点/单引号字符串边界上（两侧都无标识符字符）返回 null。
 */
internal fun identifierAtCaret(text: CharSequence, caretOffset: Int): String? {
    val n = text.length
    if (n == 0) return null
    val caret = caretOffset.coerceIn(0, n)

    var start: Int
    var end: Int
    when {
        caret < n && isIdentifierChar(text[caret]) -> {
            start = caret
            while (start > 0 && isIdentifierChar(text[start - 1])) start--
            end = caret + 1
            while (end < n && isIdentifierChar(text[end])) end++
        }
        caret > 0 && isIdentifierChar(text[caret - 1]) -> {
            start = caret - 1
            while (start > 0 && isIdentifierChar(text[start - 1])) start--
            end = caret
            while (end < n && isIdentifierChar(text[end])) end++
        }
        caret < n && QUOTE_OPENERS.containsKey(text[caret]) -> {
            start = caret
            end = scanSegmentRight(text, caret) ?: return null
        }
        caret > 0 && QUOTE_CLOSERS.containsKey(text[caret - 1]) -> {
            start = scanSegmentLeft(text, caret) ?: return null
            end = caret
        }
        else -> return null
    }

    // 引号包裹扩展：紧贴的成对引号把整段一并纳入
    if (start > 0 && end < n && QUOTE_OPENERS[text[start - 1]] == text[end]) {
        start--
        end++
    }

    // 限定名扩展（右：`seg.next`）
    while (end < n && text[end] == '.') {
        val nextEnd = scanSegmentRight(text, end + 1) ?: break
        end = nextEnd
    }
    // 限定名扩展（左：`prev.seg`）
    while (start >= 2 && text[start - 1] == '.') {
        val prevStart = scanSegmentLeft(text, start - 1) ?: break
        start = prevStart
    }

    return text.substring(start, end)
}

/** 从 [startIn] 起扫一段标识符（可带引号包裹），返回该段结束（exclusive）偏移；不成段返回 null。 */
private fun scanSegmentRight(text: CharSequence, startIn: Int): Int? {
    val n = text.length
    if (startIn >= n) return null
    val closer = QUOTE_OPENERS[text[startIn]]
    if (closer != null) {
        var i = startIn + 1
        while (i < n && isIdentifierChar(text[i])) i++
        // 至少一个内容字符 + 匹配闭引号才成段（`""` 这类空引号不成段）
        return if (i < n && text[i] == closer && i > startIn + 1) i + 1 else null
    }
    if (!isIdentifierChar(text[startIn])) return null
    var i = startIn + 1
    while (i < n && isIdentifierChar(text[i])) i++
    return i
}

/** 扫到 [endEx]（exclusive）之前为止的一段标识符（可带引号包裹），返回该段起始偏移；不成段返回 null。 */
private fun scanSegmentLeft(text: CharSequence, endEx: Int): Int? {
    if (endEx <= 0) return null
    val opener = QUOTE_CLOSERS[text[endEx - 1]]
    if (opener != null) {
        var i = endEx - 2
        while (i >= 0 && isIdentifierChar(text[i])) i--
        // 找到匹配开引号且中间至少一个内容字符才成段
        return if (i >= 0 && text[i] == opener && i <= endEx - 3) i else null
    }
    if (!isIdentifierChar(text[endEx - 1])) return null
    var i = endEx - 1
    while (i > 0 && isIdentifierChar(text[i - 1])) i--
    return i
}

/**
 * T-SqlTemplateUx P2：错误定位锚点（触发编辑器 + 选区起始行，0-based）。
 *
 * 与预览面板的 `SqlPreviewService.SourceAnchor` 同语义——都是「语句来源位置」；差别只在来源：
 * 面板锚点由预览链登记，快捷模板动作没有预览链，故在动作触发时（EDT）就地取触发编辑器。
 * 只存 [VirtualFile] 与行号（不持有 [Editor] 引用，避免动作后编辑器被释放导致的悬空引用）。
 */
internal data class ErrorLocateAnchor(val file: VirtualFile, val line0: Int)

/**
 * T-SqlTemplateUx P2：从触发上下文解析定位锚点——编辑器、文件、选区三者缺一或文件已失效
 * 一律返回 null（调用方据此不给定位动作，退化为只给「复制错误信息」）。
 *
 * 锚点取**选区起始行**：快捷模板的语句由选区文本生成，选区即语句来源；不解析错误文本
 * （错误文本里的位置指向被执行 SQL，与 mapper 原文行不一一对应，v1 不换算——与面板口径一致）。
 */
internal fun resolveErrorLocateAnchor(editor: Editor?, file: VirtualFile?): ErrorLocateAnchor? {
    if (editor == null) return null
    val target = file ?: FileDocumentManager.getInstance().getFile(editor.document)
    if (target == null || !target.isValid) return null
    val offset = runCatching { editor.selectionModel.selectionStart }.getOrNull() ?: return null
    val line0 = runCatching { editor.document.getLineNumber(offset) }.getOrNull() ?: return null
    return ErrorLocateAnchor(target, line0)
}

/**
 * T-SqlTemplateUx P2：失败通知里的一个可操作项（文案 key + 点击行为）。
 *
 * 用「key + runnable」而非直接构造 [NotificationAction]，是为了让「挂了哪些动作、点了做什么」
 * 成为不依赖真机即可断言的纯逻辑（[NotificationAction] 的点击回调只能由平台事件驱动）。
 */
internal class SqlTemplateErrorAction(val titleKey: String, val run: () -> Unit)

/**
 * T-SqlTemplateUx P2：失败通知的可操作项清单——[locate] 非 null 才给「定位到出错语句」，
 * 「复制错误信息」恒给。文案 key 复用面板既有项（`execute.error.locate` / `panel.copyError`），
 * 保证两条入口（预览面板 / 快捷模板动作）用户可见的措辞一致。
 */
internal fun sqlTemplateErrorActions(locate: (() -> Unit)?, copy: () -> Unit): List<SqlTemplateErrorAction> {
    val actions = mutableListOf<SqlTemplateErrorAction>()
    locate?.let { actions += SqlTemplateErrorAction("execute.error.locate", it) }
    actions += SqlTemplateErrorAction("panel.copyError", copy)
    return actions
}

/**
 * T-SqlTemplateUx P2：快捷模板执行失败的富反馈通知——与预览面板同款的可操作项接在气泡上
 * （做法②：不新建面板，直接给 [Notification] 挂 [NotificationAction]）。
 */
internal fun buildSqlTemplateErrorNotification(
    errorMsg: String,
    locate: (() -> Unit)?,
    copy: () -> Unit,
): Notification {
    val notification = Notification(
        SelectedTableSqlTemplateAction.NOTIFICATION_GROUP_ID,
        MpSqlVisualizerBundle.message("sqlTemplate.notify.title"),
        errorMsg,
        NotificationType.WARNING,
    )
    for (action in sqlTemplateErrorActions(locate, copy)) {
        notification.addAction(
            NotificationAction.createSimpleExpiring(MpSqlVisualizerBundle.message(action.titleKey)) { action.run() },
        )
    }
    return notification
}

/** T-SqlTemplateUx P2：投递失败反馈通知（复制动作走 [UiTextClipboard] 统一收口）。 */
internal fun notifySqlTemplateError(project: Project, errorMsg: String, locate: (() -> Unit)?) {
    buildSqlTemplateErrorNotification(errorMsg, locate) { UiTextClipboard.copy(errorMsg) }.notify(project)
}

internal fun buildSqlTemplateErrorNotification(info: ExecutionErrorInfo, locate: (() -> Unit)?): Notification {
    val text = ExecutionErrorFormatter.format(info)
    return buildSqlTemplateErrorNotification(text, locate) { UiTextClipboard.copy(text) }
}

internal fun notifySqlTemplateError(project: Project, info: ExecutionErrorInfo, locate: (() -> Unit)?) {
    buildSqlTemplateErrorNotification(info, locate).notify(project)
}

/** EXPLAIN 执行计划：`EXPLAIN SELECT * FROM <选中表>`。 */
class ExplainSelectedTableAction : SelectedTableSqlTemplateAction(
    "action.cn.song.mybatisplus.visualizer.ExplainSelectedTable.text",
    SelectedTableTemplateKind.EXPLAIN,
) {
    companion object {
        /** 动作 ID（plugin.xml 注册 id 与设置页 ShortcutEditor 共用）。 */
        const val ACTION_ID = "cn.song.mybatisplus.visualizer.ExplainSelectedTable"
    }
}

/** 行数统计：`SELECT COUNT(*) FROM <选中表>`。 */
class CountSelectedTableAction : SelectedTableSqlTemplateAction(
    "action.cn.song.mybatisplus.visualizer.CountSelectedTable.text",
    SelectedTableTemplateKind.COUNT,
) {
    companion object {
        const val ACTION_ID = "cn.song.mybatisplus.visualizer.CountSelectedTable"
    }
}

/** 抽样 N 行（R5：LIMIT 值设置页可配，默认 10；范围钳制 1..10000 防 0/负数与误输入全表）：`SELECT * FROM <选中表> LIMIT N`。 */
class SampleSelectedTableAction : SelectedTableSqlTemplateAction(
    "action.cn.song.mybatisplus.visualizer.SampleSelectedTable.text",
    SelectedTableTemplateKind.SAMPLE,
) {
    companion object {
        const val ACTION_ID = "cn.song.mybatisplus.visualizer.SampleSelectedTable"
    }
}
