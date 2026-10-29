package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import cn.song.mybatisplus.visualizer.execute.ConsoleEmbedMode
import cn.song.mybatisplus.visualizer.execute.DocWriteContext
import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier
import cn.song.mybatisplus.visualizer.execute.SqlExecutor
import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.FillClause
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.sql.ExplainGate
import cn.song.mybatisplus.visualizer.sql.JoinDiagramGenerator
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.PlaceholderCardinality
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.sql.interceptor.PaginationSimulator
import cn.song.mybatisplus.visualizer.share.ui.ShareDialog
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramPanel
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.NativeJoinDiagramSupport
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContributor
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter
import cn.song.mybatisplus.visualizer.ui.inlay.PreviewInlayScope
import cn.song.mybatisplus.visualizer.util.CaseFormatUtil
import cn.song.mybatisplus.visualizer.util.EditorCommentShortcuts
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import cn.song.mybatisplus.visualizer.xml.ExprReason
import cn.song.mybatisplus.visualizer.xml.ExprSupport
import cn.song.mybatisplus.visualizer.xml.ConditionOutcome
import cn.song.mybatisplus.visualizer.xml.RenderedXmlSql
import cn.song.mybatisplus.visualizer.xml.XmlConditionView
import cn.song.mybatisplus.visualizer.xml.XmlForEachView
import cn.song.mybatisplus.visualizer.xml.ParamKind
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.XmlPreviewState
import cn.song.mybatisplus.visualizer.xml.PagesColumn
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.Disposable
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.ui.HideableTitledPanel
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Insets
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// SqlPreviewPanel 的文本排版/截断工具函数
// （T-M53-II 2026-10-10：由 SqlPreviewPanel.kt 原样搬出，同包同可见性，零行为改动。）
/**
 * T-SourceJumpFunnel P2-2：把调用栈快照 [digest] 组装成 meta 行内联摘要（截断展示）；
 * [digest] 为 null 时空串。
 *
 * T-StackHoverUi 改造：原「两段文案」（内联摘要 + tooltip 全文节点）收窄为单段——完整链不再进
 * tooltip（旧实现截断摘要+全文重复拼接、超长单行折成文本墙），改由 [StackFrameHover] 悬停弹层
 * 承接。抽出为顶层 internal 纯函数仅为让 §3.3 验收项（截断长度/截断标记）可直接单测断言。
 */
internal fun buildStackDigestInlineLine(digest: String?): String {
    if (digest == null) return ""
    val label = MpSqlVisualizerBundle.message("panel.meta.stackDigest")
    // T-StackBizFrame S3：先取景（头部全是 agent/驱动/框架帧时跳到首个项目帧），再按原契约截断。
    // 首个非内部帧本来就在开头时取景原样返回，故既有「前 120 字符 + …」行为逐字不变。
    val excerpt = StackDigestFrames.projectExcerpt(digest)
    val summary =
        if (excerpt.length > STACK_DIGEST_MAX_CHARS) {
            excerpt.substring(0, STACK_DIGEST_MAX_CHARS) + STACK_DIGEST_ELLIPSIS
        } else {
            excerpt
        }
    return "   " + label + " " + summary
}

// ------------------------------------------------------------------
// T-SourceJumpFunnel P5 复核修正：meta 行宽度预算组装（纯逻辑，脱 FontMetrics 可直测）
// ------------------------------------------------------------------

/**
 * 调用栈摘要在 meta 行里至少要拿到的宽度（px）：约等于标签「   调用栈:」自身宽度 @13pt，
 * 低于此值就整段不放摘要——宁可让主体独占预算，也不留一个看不出是什么的孤零零省略号。
 */
internal const val META_DIGEST_MIN_PX = 56

/**
 * T-M26.3 中段省略的纯函数形态（[SqlPreviewPanel.ellipsize] 逐字同源）：保留尾部 30%，
 * 超宽时显示 `头…尾`。[widthOf] 是文本像素宽度度量——注入后本函数与
 * [metaLineWithinBudget] 都能脱 FontMetrics 单测（测试用「1 字符 = 1px」替身即可）。
 */
internal fun ellipsizeMiddle(text: String, widthOf: (String) -> Int, maxWidth: Int): String {
    val mark = "\u2026"
    if (widthOf(text) <= maxWidth) return text
    val tailLen = (text.length * 0.3).toInt().coerceAtLeast(4).coerceAtMost(text.length - 1)
    val tail = text.takeLast(tailLen)
    var head = text.length - tailLen
    while (head > 0 && widthOf(text.take(head) + mark + tail) > maxWidth) head--
    return text.take(head) + mark + tail
}

/**
 * 尾部截断（保留头部）——用于调用栈摘要：摘要**栈顶在左**（[buildStackDigestInlineLine] 的摘要自栈顶
 * 起截），尾部是信息量最低的栈底帧，故超宽时截尾，而非 [ellipsizeMiddle] 的中段省略。
 */
internal fun clipTailToWidth(text: String, widthOf: (String) -> Int, maxWidth: Int): String {
    if (widthOf(text) <= maxWidth) return text
    val mark = "\u2026"
    var end = text.length
    while (end > 0 && widthOf(text.take(end) + mark) > maxWidth) end--
    return if (end <= 0) "" else text.take(end) + mark
}

/**
 * T-SourceJumpFunnel P5 复核修正：meta 行在宽度预算内的组装——**主体优先，摘要吃剩余宽度**。
 *
 * P5 把调用栈摘要并入可见行后，若仍把「主体 + 摘要」当**单段文本**交给 [ellipsizeMiddle]
 * （保留尾部 30%），窄窗下主体会被整段挤掉、可见行只剩「…」+ 栈底帧——主体（表名 / wrapper 类型）
 * 是这一行的主信息，栈底恰是调用链里信息量最低的一端，优先级倒挂。复核（20260920）实测默认宽度
 * 预算仅够 15~25 字符，而摘要上限 120 字符，故必然触发。
 *
 * 组装规则：主体自身超预算 → 主体中段省略（F3 原行为）；主体放得下 → 摘要按剩余宽度截尾；
 * 剩余不足 [META_DIGEST_MIN_PX] → 整段丢弃摘要。[digest] 为空（无调用栈快照）时逐字退回 F3
 * 原行为，渲染不变。
 *
 * @param full   完整行（主体 + 摘要）——放得下即整段返回（与改前逐字节一致）
 * @param head   主体（meta + 警示行）
 * @param digest 调用栈内联摘要（`   调用栈: <摘要>`；无快照时为空串）
 */
internal fun metaLineWithinBudget(
    full: String,
    head: String,
    digest: String,
    widthOf: (String) -> Int,
    budget: Int,
): String {
    if (widthOf(full) <= budget) return full
    if (digest.isEmpty()) return ellipsizeMiddle(head, widthOf, budget)
    val remain = budget - widthOf(head)
    if (remain < META_DIGEST_MIN_PX) return ellipsizeMiddle(head, widthOf, budget)
    return head + clipTailToWidth(digest, widthOf, remain)
}

/**
 * R12 T-PaginationSim：模型级重渲染后的分页 LIMIT 行回带（20261008 dev-bak 验收缺陷 #1）。
 *
 * 面板勾选/参数变更走 XmlPreviewModel.renderSql / SqlGenerator 重建——模型层 SQL 是分页后处理
 * **之前**的形态（服务端 applyPaginationShared 只作用于整条 PreviewResult），LIMIT 行会在此丢失
 * （真机实测：分页参数行与 COUNT 段都在、主 SQL 尾部无 LIMIT）。服务端把 LIMIT 行存进
 * [cn.song.mybatisplus.visualizer.model.PreviewResult.paginationLimitLine]，面板重渲染时用本函数
 * 原样带回。baseSql 已含 LIMIT（手写语句）或 limitLine 为 null 时原样返回。
 */
internal fun appendPaginationLimitLine(baseSql: String, limitLine: String?): String {
    if (limitLine == null) return baseSql
    if (PaginationSimulator.hasExistingLimit(baseSql)) return baseSql
    return baseSql.trimEnd() + "\n" + limitLine
}

