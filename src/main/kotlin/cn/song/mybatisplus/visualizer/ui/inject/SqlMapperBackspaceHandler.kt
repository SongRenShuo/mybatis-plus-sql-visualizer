package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

/**
 * T-XmlGap W6：`#{}` / `${}` 的**成对删除**（竞品同型能力，内部调研报告 §2.3）。
 *
 * 形态选择（有意取「后删」而非「先删」）：平台先按正常语义删掉光标前的 `{`，本回调在
 * `charDeleted` 阶段看到残留 `#` + `}` 再补删这两个字符 —— 相比在 `beforeCharDeleted` 里自行接管
 * 整段删除，这条路径**不改平台原有删除语义**（无 `{` 配对时零介入），回归面最小。
 *
 * 作用域：只在**注入片段或语句宿主**内生效（MyBatis `mapper` / iBatis `sqlMap`，T-Ibatis2Support 券 D
 * 放宽根守卫；iBatis 无 `{}` 占位故实际零介入——见 [isMapperContext]）。注入形态下 `editor.document`
 * 是 DocumentWindow，删除经窗口映射回宿主，无需另做坐标还原。非语句宿主场景零介入。
 */
class SqlMapperBackspaceHandler : BackspaceHandlerDelegate() {

    override fun beforeCharDeleted(c: Char, file: PsiFile, editor: Editor) {
        // 有意留空：删除动作交平台执行，本类只在 charDeleted 阶段补删配对字符（见 KDoc）。
    }

    override fun charDeleted(c: Char, file: PsiFile, editor: Editor): Boolean {
        if (c != '{') return false
        if (!isMapperContext(file)) return false
        val document = editor.document
        val offset = editor.caretModel.offset
        // 形态：光标左邻为 `#` / `$`，且光标右邻为 `}`（即刚才删掉的是 `#{` 的 `{`）
        if (offset < 1 || offset >= document.textLength) return false
        val chars = document.charsSequence
        val left = chars[offset - 1]
        if (left != '#' && left != '$') return false
        if (chars[offset] != '}') return false
        val start = offset - 1
        val end = offset + 1
        WriteCommandAction.runWriteCommandAction(editor.project) {
            document.deleteString(start, end)
            editor.caretModel.moveToOffset(start)
        }
        return true
    }

    /** 语句宿主上下文判定：宿主 XmlFile（根 `mapper` 或 iBatis `sqlMap`）或本插件注入片段（语言 MpSqlMapper）。 */
    private fun isMapperContext(file: PsiFile): Boolean {
        if (file.language == SqlMapperLanguage) return true
        val xmlFile = file as? com.intellij.psi.xml.XmlFile ?: return false
        // T-Ibatis2Support 券 D：根守卫放宽到 iBatis `sqlMap`（同一个廉价判据，见
        // XmlSynHiGate.isStatementHostRootName）。**语义留存**：成对删的是 `#{}`/`${}` 的 `{}`，
        // 那是 MyBatis 形态——iBatis 用 `#name#`、正文里不产 `{`，故放宽后在 iBatis 文件里
        // 本条只在出现字面 `#}`/`$}` 时才触发（真实 iBatis 文档不会产出该形态，无实际介入）。
        return XmlSynHiGate.isStatementHostRootName(xmlFile.rootTag?.name)
    }
}
