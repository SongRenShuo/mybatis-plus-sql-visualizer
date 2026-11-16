package cn.song.mybatisplus.visualizer.util

import com.intellij.ide.lightEdit.LightEditCompatible
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange

/**
 * 预览编辑器「行注释快捷键」统一为 `--` 双横线（T-PreviewLineCommentDash O-134/D136）。
 *
 * 为什么必须在这个层位（开发计划 §一 全链实证）：预览文件被 DialectPreMapping 强制映射 MySQL 方言
 * → 平台注释动作取 commenter 链拿到 `MysqlCommenter` → 行前缀 `#`（javap 双版本实锤
 * `SHARP_LINE_PREFIX = "# "`）→ 对 PostgreSQL 执行报 `[42601] syntax error`。
 *
 * **接管层位（实施期前提被推翻后的修正，见计划 §六）**：计划原定 B3＝`EditorActionManager.setActionHandler`
 * 链式覆写，实测**不可用**——平台把 `IdeActions.ACTION_COMMENT_LINE`（"CommentByLineComment"）注册到
 * `CommentByLineCommentAction`（`LangActions.xml` 实锤），它是 `MultiCaretCodeInsightAction` 的子类、
 * **不是** `EditorAction`，而 `EditorActionManagerImpl.getActionHandler/setActionHandler` 对 id 做
 * `checkcast EditorAction`（字节码实证）⇒ 调用即 `ClassCastException`（单测首跑 15/15 红实证）。
 * 修正为在**动作注册层**接管：`ActionManager.replaceAction(ACTION_COMMENT_LINE, 自有 AnAction)`，
 * 自有动作只对**打标编辑器**走本地逐行切换，其余一律原样委托平台原动作（update/actionPerformed 双向委托，
 * 菜单文案与图标从原动作模板 presentation 复制，键位绑定按 id 天然保留）。
 *
 * 作用域＝编辑器 userData 打标 + 引用计数：首个打标编辑器出现时换入，最后一个卸载时换回原动作。
 * 不触语言级 `lang.commenter` EP（B1 会波及真实 MySQL 控制台/文件）；不接管块注释 Ctrl+Shift+/（D137：
 * javap 双版本核实基类 `SqlCommenter.getBlockCommentPrefix()` 为斜杠星号、`getBlockCommentSuffix()` 为
 * 星号斜杠，MySQL/PG 块注释语义一致）。
 *
 * 语义契约：受影响行集 = 选区触及的行（行粒度；无选区 = 光标行，多光标各算各的、自底向上套用）；
 * **整段同态切换**（对齐平台手感）：全部非空行已是注释 → 剥除；否则补 `-- `，其中已是注释的行幂等跳过。
 * 前缀双认 `--` 与遗留 `#`（本轮修复前插入的 `#` 残留行也要能剥掉；双认先例 MysqlClientGSuffix.isCommentLine），
 * 剥除时连带吞掉前缀后**恰好一个**空格。文档改动包 WriteCommandAction（一步撤销）。
 *
 * 测试缝照 [EditorCopyShortcuts]：纯逻辑（行级切换）与 [DashLineCommentAction] 均 internal，
 * 单测可直接构造动作 + 自建 DataContext 驱动（不依赖真键盘分发）。
 *
 * **T-CommentShortcutKeymap（2026-11-13）**：换入/换回一律经 [replaceActionSilently]——先把自己的
 * `ShortcutSet` 置为 [CustomShortcutSet.EMPTY] 再 `replaceAction`。平台 `registerAction` 每次注册都会
 * 回填 `ProxyShortcutSet(actionId)`，而 `AnAction.setShortcutSet` 只对「当前集合恰为 EMPTY」放行，
 * 否则在 PROJECT_OPENED 之后打 `ShortcutSet of global AnActions should not be changed outside of
 * KeymapManager` 告警（真机日志实测 7 条，栈落在本类 install/uninstall）。判据与字节码偏移见
 * `docs/开发计划/20261113-1045-T-CommentShortcutKeymap-*`。
 */
internal object EditorCommentShortcuts {

    /** 打标 Key：userData 在场 = 该编辑器的行注释走本接管。 */
    private val MARKER = Key.create<Boolean>("mpsv.editor.lineCommentDash")

    /** 行注释插入前缀（PG/MySQL 通用双横线）。 */
    private const val DASH_PREFIX = "--"

    /** 遗留 `#` 前缀（本轮修复前 MySQL commenter 产出，残留行兼容剥除）。 */
    private const val LEGACY_PREFIX = "#"

    private var installedAction: DashLineCommentAction? = null
    private var refCount = 0

    /** 给 [editor] 打标并确保全局动作已换入（幂等：重复打标不重复计数）。 */
    @Synchronized
    fun install(editor: Editor) {
        if (editor.getUserData(MARKER) == true) return
        editor.putUserData(MARKER, true)
        if (installedAction == null) {
            val manager = ActionManager.getInstance()
            val original = manager.getAction(IdeActions.ACTION_COMMENT_LINE)
            if (original != null) {
                val replacement = DashLineCommentAction(original)
                manager.replaceActionSilently(IdeActions.ACTION_COMMENT_LINE, replacement)
                installedAction = replacement
                refCount = 0
            }
        }
        refCount++
    }

    /** 摘除 [editor] 打标；最后一个打标编辑器卸载时换回原动作（幂等：未打标零动作）。 */
    @Synchronized
    fun uninstall(editor: Editor) {
        if (editor.getUserData(MARKER) != true) return
        editor.putUserData(MARKER, null)
        refCount--
        if (refCount <= 0) {
            installedAction?.let { replacement ->
                val manager = ActionManager.getInstance()
                // 只在当前在位的仍是本接管件时换回（防覆盖别的组件在期间的替换）
                if (manager.getAction(IdeActions.ACTION_COMMENT_LINE) === replacement) {
                    manager.replaceActionSilently(IdeActions.ACTION_COMMENT_LINE, replacement.original)
                }
            }
            installedAction = null
            refCount = 0
        }
    }

    /**
     * 换入/换回统一走 [replaceActionSilently]（`util/ActionReplaceSilently.kt`）：先把自己的
     * `ShortcutSet` 置为 `CustomShortcutSet.EMPTY` 再 `replaceAction`，从而不触发平台
     * 「ShortcutSet of global AnActions should not be changed outside of KeymapManager」告警
     * （判据见该文件 KDoc 与 20261113 开发计划 §二）。键位仍由平台回填的 `ProxyShortcutSet(id)`
     * 按 keymap 惰性解析，行为不变。
     */

    /** 该编辑器是否已被本接管打标（接线覆盖测试与排障用；判定与动作内一致）。 */
    internal fun isMarked(editor: Editor): Boolean = editor.getUserData(MARKER) == true

    /**
     * 行级切换纯函数：**全已注释 → 全剥；否则补 `-- `，已注释行幂等跳过**。
     * 空白行参与范围但双态都原样保留（剥无可剥、补了徒增噪音）。
     */
    internal fun toggleLines(lines: List<String>): List<String> {
        val nonBlank = lines.filter { it.isNotBlank() }
        val uncomment = nonBlank.isNotEmpty() && nonBlank.all { isCommentedLine(it) }
        return lines.map { line ->
            when {
                line.isBlank() -> line
                uncomment -> stripCommentPrefix(line)
                isCommentedLine(line) -> line
                else -> addCommentPrefix(line)
            }
        }
    }

    /** 行内容（跳过前导空白）是否以行注释开头——`--` 与遗留 `#` 双认（先例 MysqlClientGSuffix.kt:99-104）。 */
    internal fun isCommentedLine(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith(DASH_PREFIX) || t.startsWith(LEGACY_PREFIX)
    }

    /** 在首个非空白字符前插 `-- `（空白行原样保留，由调用方过滤）。 */
    private fun addCommentPrefix(line: String): String {
        val i = line.indexOfFirst { !it.isWhitespace() }
        return if (i < 0) line else line.substring(0, i) + DASH_PREFIX + " " + line.substring(i)
    }

    /** 剥 `--` / `#` 前缀并连带吞掉前缀后恰好一个空格；无前缀原样返回。 */
    private fun stripCommentPrefix(line: String): String {
        val t = line.trimStart()
        val prefix = when {
            t.startsWith(DASH_PREFIX) -> DASH_PREFIX
            t.startsWith(LEGACY_PREFIX) -> LEGACY_PREFIX
            else -> return line
        }
        val start = line.length - t.length
        var end = start + prefix.length
        if (end < line.length && line[end] == ' ') end++
        return line.substring(0, start) + line.substring(end)
    }

    /**
     * 对打标编辑器执行逐行切换（WriteCommandAction 包裹，一步撤销）。
     * 多光标各算各的受影响行集、自底向上套用（后套用的不挪先套用的偏移）。
     */
    private fun toggleLineComments(editor: Editor) {
        val document = editor.document
        if (!document.isWritable || editor.isViewer) return
        // 每个光标的受影响行集：有选区 → 选区触及的行（行粒度）；无选区 → 光标所在行
        data class Affected(
            val caret: Caret,
            val startLine: Int,
            val endLine: Int,
            val hasSelection: Boolean,
            val caretColumn: Int,
        )
        val affected = editor.caretModel.allCarets.mapNotNull { caret ->
            val caretOffset = caret.offset
            if (caret.hasSelection()) {
                val startLine = document.getLineNumber(caret.selectionStart)
                val endLine = document.getLineNumber(caret.selectionEnd)
                Affected(caret, startLine, maxOf(startLine, endLine), true, caretOffset - document.getLineStartOffset(startLine))
            } else {
                val line = document.getLineNumber(caretOffset)
                Affected(caret, line, line, false, caretOffset - document.getLineStartOffset(line))
            }
        }.distinctBy { it.startLine to it.endLine }.sortedByDescending { it.startLine }
        if (affected.isEmpty()) return
        val project = editor.project
        val commandName = MpSqlVisualizerBundle.message("preview.lineComment.commandName")
        val applyEdits = Runnable {
            for (a in affected) {
                val startOffset = document.getLineStartOffset(a.startLine)
                val endOffset = document.getLineEndOffset(a.endLine)
                val oldText = document.getText(TextRange(startOffset, endOffset))
                val oldLines = oldText.split('\n')
                val newLines = toggleLines(oldLines)
                if (newLines == oldLines) continue
                val newText = newLines.joinToString("\n")
                document.replaceString(startOffset, endOffset, newText)
                // 行索引不因切换而变（不增删行），光标/选区按新文本复位
                val newEnd = startOffset + newText.length
                if (a.hasSelection) {
                    a.caret.setSelection(startOffset, newEnd)
                }
                val newLineStart = document.getLineStartOffset(a.startLine)
                a.caret.moveToOffset(minOf(newLineStart + a.caretColumn, document.getLineEndOffset(a.startLine)))
            }
        }
        if (project != null) {
            WriteCommandAction.runWriteCommandAction(project, commandName, null, applyEdits)
        } else {
            // 无项目编辑器（三级降级兜底分支）：无命令宿主，仅保证写动作合法
            ApplicationManager.getApplication().runWriteAction(applyEdits)
        }
    }

    /**
     * 换入的动作：打标编辑器 → 本地逐行切换；其余 → **原样委托平台原动作**（update/actionPerformed 双向，
     * 零行为差异）。`DumbAware` + `LightEditCompatible` 与原动作（`CommentByLineCommentAction` 实现两者）
     * 对齐——否则换入后在索引/轻编辑态会被平台整体禁用，Ctrl+/ 在所有编辑器里失效（回归）。
     */
    internal class DashLineCommentAction(internal val original: AnAction) : DumbAwareAction(), LightEditCompatible {

        init {
            // 换入的是同一 action id：菜单/工具栏文案与图标沿用原动作模板，表现层不得退化
            templatePresentation.copyFrom(original.templatePresentation)
        }

        override fun update(e: AnActionEvent) {
            val marked = markedEditor(e)
            if (marked != null) {
                e.presentation.isEnabled = marked.document.isWritable && !marked.isViewer
                e.presentation.isVisible = true
                return
            }
            original.update(e)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val marked = markedEditor(e)
            if (marked != null) {
                toggleLineComments(marked)
                return
            }
            original.actionPerformed(e)
        }

        /** 事件上下文里的编辑器仅当已打标才由本接管处理（EDITOR 缺失/未打标 → null → 委托）。 */
        private fun markedEditor(e: AnActionEvent): Editor? {
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
            return if (editor.getUserData(MARKER) == true) editor else null
        }
    }
}
