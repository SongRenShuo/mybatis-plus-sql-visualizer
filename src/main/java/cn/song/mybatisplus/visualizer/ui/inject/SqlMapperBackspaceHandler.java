// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperBackspaceHandler.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;

/**
 * T-XmlGap W6：{@code #{}} / {@code ${}} 的<b>成对删除</b>（竞品同型能力，内部调研报告 §2.3）。
 *
 * <p>形态选择（有意取「后删」而非「先删」）：平台先按正常语义删掉光标前的 {@code {}，
 * 本回调在 {@code charDeleted} 阶段看到残留 {@code #} + {@code }} 再补删这两个字符 —— 相比在
 * {@code beforeCharDeleted} 里自行接管整段删除，这条路径<b>不改平台原有删除语义</b>
 * （无 {@code {} 配对时零介入），回归面最小。</p>
 *
 * <p>作用域：只在<b>注入片段或 mapper 宿主</b>内生效（注入形态下 {@code editor.document} 是
 * DocumentWindow，删除经窗口映射回宿主，无需另做坐标还原）。非 mapper 场景零介入。</p>
 */
public class SqlMapperBackspaceHandler extends BackspaceHandlerDelegate {

    @Override
    public void beforeCharDeleted(char c, @NotNull PsiFile file, @NotNull Editor editor) {
        // 有意留空：删除动作交平台执行，本类只在 charDeleted 阶段补删配对字符（见 KDoc）。
    }

    @Override
    public boolean charDeleted(char c, @NotNull PsiFile file, @NotNull Editor editor) {
        if (c != '{') return false;
        if (!isMapperContext(file)) return false;
        com.intellij.openapi.editor.Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        // 形态：光标左邻为 `#` / `$`，且光标右邻为 `}`（即刚才删掉的是 `#{` 的 `{`）
        if (offset < 1 || offset >= document.getTextLength()) return false;
        CharSequence chars = document.getCharsSequence();
        char left = chars.charAt(offset - 1);
        if (left != '#' && left != '$') return false;
        if (chars.charAt(offset) != '}') return false;
        int start = offset - 1;
        int end = offset + 1;
        WriteCommandAction.runWriteCommandAction(editor.getProject(), () -> {
            document.deleteString(start, end);
            editor.getCaretModel().moveToOffset(start);
        });
        return true;
    }

    /** mapper 上下文判定：宿主 XmlFile（根 mapper）或本插件注入片段（语言 MpSqlMapper）。 */
    private static boolean isMapperContext(PsiFile file) {
        if (file.getLanguage() == SqlMapperLanguage.INSTANCE) return true;
        if (!(file instanceof XmlFile)) return false;
        XmlFile xmlFile = (XmlFile) file;
        return xmlFile.getRootTag() != null && xmlFile.getRootTag().getName().equals("mapper");
    }
}
