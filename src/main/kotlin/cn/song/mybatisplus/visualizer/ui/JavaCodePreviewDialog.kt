package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.reverse.JavaCommentStripper
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.lang.java.JavaLanguage
import com.intellij.lang.java.JavaSyntaxHighlighterFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

/**
 * Java 代码预览弹窗（SQL→MP Java 代码 反向转换的结果展示）。
 *
 * 只读 IDEA 编辑器展示生成代码（**Java 词法高亮**——对齐侧边栏「SQL → Java」tab
 * 结果区实现：`LexerEditorHighlighter` + `JavaSyntaxHighlighterFactory`，T-M41），
 * 底部提供"复制代码 / 关闭"。由
 * [cn.song.mybatisplus.visualizer.actions.ConvertSqlToJavaAction] 触发。
 */
// @JvmOverloads：保持二参构造真实存在——Kotlin 默认参数不生成桥构造，增量编译下
// 旧调用方字节码（既有测试）会 NoSuchMethodError（M37.4 全量测试实证，新旧双名同模板策略）。
class JavaCodePreviewDialog @JvmOverloads constructor(
    private val project: Project?,
    private val code: String,
    /** M37.4：自定义弹窗标题（null = 默认「SQL→Java」标题，既有调用点零影响）。 */
    private val dialogTitle: String? = null,
) : DialogWrapper(project, true) {

    private val editor: Editor = createJavaEditor(code)

    init {
        title = dialogTitle ?: MpSqlVisualizerBundle.message("reverse.dialog.title")
        init()
        setCancelButtonText(MpSqlVisualizerBundle.message("dialog.preview.cancel"))
    }

    /** 只读 Java 编辑器（高亮/行号/折叠观感对齐 SqlToJavaPanel 结果区；createViewer = 平台标准只读预览）。 */
    private fun createJavaEditor(code: String): Editor {
        val editor = EditorFactory.getInstance()
            .createViewer(EditorFactory.getInstance().createDocument(code), project)
        (editor as EditorEx).apply {
            highlighter = LexerEditorHighlighter(
                JavaSyntaxHighlighterFactory.getSyntaxHighlighter(JavaLanguage.INSTANCE, project, null),
                EditorColorsManager.getInstance().globalScheme,
            )
            settings.isLineNumbersShown = true
            settings.isFoldingOutlineShown = true
            settings.isIndentGuidesShown = false
            settings.isAdditionalPageAtBottom = false
            // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
            EditorWhitespaceSettings.applyTo(this)
            caretModel.moveToOffset(0)
        }
        return editor
    }

    // T-DarkTheme：滚动容器用平台 JBScrollPane。自建原生 JScrollPane 的 viewport 是裸 JViewport、
    // 四角无 Corner 组件；平台 JBScrollPane 用 JBViewport（背景取所载 view）+ 四角 Corner，
    // 深色主题下容器边缘/角落不留浅色条带。
    override fun createCenterPanel(): JComponent = JBScrollPane(editor.component).apply {
        preferredSize = Dimension(DIALOG_WIDTH, DIALOG_HEIGHT)
        minimumSize = Dimension(DIALOG_MIN_WIDTH, DIALOG_MIN_HEIGHT)
    }

    override fun createActions(): Array<Action> = arrayOf(
        object : DialogWrapperAction(MpSqlVisualizerBundle.message("reverse.dialog.copy")) {
            override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
                copyCode()
            }
        },
        cancelAction,
    )

    /** 释放编辑器（EditorFactory 创建的 Editor 必须 releaseEditor，防泄漏）。显式 public 供测试调用。 */
    public override fun dispose() {
        if (!isDisposed) {
            EditorFactory.getInstance().releaseEditor(editor)
            super.dispose()
        }
    }

    /** 复制纯 Java 代码到系统剪贴板（T-M45：剥离说明注释——只复制代码忽略「日志」；对齐 SqlPreviewPanel.copySql 的复制实现）。
     * T-UiNative T-UN-02：复制成功提示改轻通知（原模态弹窗——纯告知无需打断）；测试模式跳过
     * （对齐 CopyExecutableSqlAction.showToast 约定）。 */
    private fun copyCode() {
        UiTextClipboard.copy(copyText())
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            Notification(
                NOTIFICATION_GROUP_ID,
                MpSqlVisualizerBundle.message("plugin.name"),
                MpSqlVisualizerBundle.message("reverse.copy.done"),
                NotificationType.INFORMATION,
            ).notify(project)
        }
    }

    /** 复制文本 = 剥离注释后的当前编辑器内容（headless 测试无剪贴板，经此断言）。 */
    private fun copyText(): String = JavaCommentStripper.strip(editor.document.text)

    /** internal 测试访问器：将复制到剪贴板的文本（剥离注释后）。 */
    internal fun copyTextForTest(): String = copyText()

    /** internal 测试访问器（渲染文本断言，仿 SqlSelectionDialog.listForTest）。 */
    internal fun editorForTest(): Editor = editor

    companion object {
        /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

        const val DIALOG_WIDTH = 680
        const val DIALOG_HEIGHT = 420
        const val DIALOG_MIN_WIDTH = 480
        const val DIALOG_MIN_HEIGHT = 240
    }
}
