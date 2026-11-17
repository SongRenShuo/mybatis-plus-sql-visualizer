// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/ParamBraceTypedHandler.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlComment;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Set;

/**
 * T-XmlSynHi D5：mapper XML 语句文本内敲 {@code #} / {@code $} 自动闭合 {@code {}}（光标落 #/{ 之间）。
 *
 * <p>「先查后插」防双插（决策 4-A，共存插件同型行为）：插入前查光标后方字符——
 * <ul>
 *  <li>已是 {@code {} → 不补插（对方插件/用户已手敲 {@code {}，双插会产出 {@code #{{}}）——直接放行原字符。</li>
 * </ul>
 * （T-PerfSweep16 F46 口径对齐：历史 KDoc 声称 {@code }} 后也放行，实现自始只查 {@code {}——修文档不改行为。）
 * 守卫：XmlSynHiGate <b>设置开关线</b>（{@code shouldYield(project)} 单参形态）+ 根 mapper + 语句标签内。
 * （T-XmlGap W1 勘误：本 KDoc 旧述「双让位（平台/CodeHelperPro 在位即不接管）」与实现不符——
 * 本类<b>从不做</b> per-host 探测，共存安全靠下面的「先查后插」与语句标签守卫，不靠让位。）
 *
 * <p>243+ 注入片段击键路由（TypedHandler.callDelegates 字节码实证）：光标在注入片段内时
 * 传给本回调的 {@code file} 是**注入 PsiFile**（SQL 语言）而非宿主 XmlFile、{@code editor} 是
 * EditorWindow——须还原宿主三件套（hostFile/hostEditor/hostOffset）再套守卫；宿主路径
 * （标签标记区等非注入位）三件套原样即位。
 */
public class ParamBraceTypedHandler extends TypedHandlerDelegate {

    private static final Set<String> STATEMENT_TAGS = Set.copyOf(Arrays.asList(
            "select", "insert", "update", "delete"));

    @Override
    public Result beforeCharTyped(char c, @NotNull Project project, @NotNull Editor editor,
                                  @NotNull PsiFile file, @NotNull FileType fileType) {
        if (c != '#' && c != '$') {
            return Result.CONTINUE;
        }
        boolean inInjected = !(file instanceof XmlFile);
        XmlFile hostFile;
        if (inInjected) {
            PsiFile hostPsi = InjectedLanguageManager.getInstance(project).getInjectionHost(file) != null
                    ? InjectedLanguageManager.getInstance(project).getInjectionHost(file).getContainingFile()
                    : null;
            if (!(hostPsi instanceof XmlFile)) {
                return Result.CONTINUE;
            }
            hostFile = (XmlFile) hostPsi;
        } else {
            hostFile = (XmlFile) file;
        }
        if (XmlSynHiGate.shouldYield(project)) {
            return Result.CONTINUE;
        }
        Editor hostEditor = editor instanceof EditorWindow ? ((EditorWindow) editor).getDelegate() : editor;
        int offset = editor.getCaretModel().getOffset();
        if (inInjected && editor.getDocument() instanceof DocumentWindow) {
            Integer injectedToHost = ((DocumentWindow) editor.getDocument())
                    .injectedToHost(editor.getCaretModel().getOffset());
            if (injectedToHost != null) {
                offset = injectedToHost;
            }
        }
        // T-PerfSweep16 F39：getCharsSequence 直读底层文本（原 document.text 每次击键整文档复制一份 String）
        CharSequence text = hostEditor.getDocument().getCharsSequence();
        // 光标后方已有 `{`（对方插件已补/用户已敲）→ 放行，不双插
        if (offset < text.length() && text.charAt(offset) == '{') {
            return Result.CONTINUE;
        }
        // 光标须在 mapper 语句标签文本内（根 mapper 守卫 + 标签定位）；宿主偏移左邻字符定位
        int at = Math.max(offset - 1, 0);
        PsiElement el = hostFile.findElementAt(at);
        if (el == null) {
            return Result.CONTINUE;
        }
        XmlTag tag = PsiTreeUtil.getParentOfType(el, XmlTag.class);
        if (tag == null) {
            return Result.CONTINUE;
        }
        if (!inStatementText(tag, offset)) {
            return Result.CONTINUE;
        }

        // Kotlin lambda 捕获 var 无碍，Java 捕获须 effectively final——落 final 快照再进写命令
        final int insertOffset = offset;
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> {
            hostEditor.getDocument().insertString(insertOffset, c + "{}");
            hostEditor.getCaretModel().moveToOffset(insertOffset + 1);
        });
        return Result.STOP;
    }

    /**
     * 光标前字符所属标签是否为语句文本位（mapper 根守卫 + 语句标签集，与 injector 同口径）。
     *
     * <p>两态分界（T-XmlBraceNested 重写，20260928 旧判据作废）：
     * <ul>
     *   <li><b>最近语句标签祖先（任意深度）</b>：动态标签（{@code if}/{@code where}/{@code choose}/{@code trim}/…）
     *       可任意嵌套，旧「一层 parentTag∈语句标签」判法把 {@code <where>} 下的 {@code <if>} 体内正文
     *       全部漏判（R15 录制实测：真实 mapper 主流排版下功能全灭）；</li>
     *   <li><b>标记区不接管</b>：光标落在某标签的<b>起始标记</b>内（{@code <select id=|>}、{@code <trim prefix=|>}
     *       属性间隙）→ 不接管（20260928 真机实证形态，语义保留）；</li>
     *   <li><b>子标签之后接管</b>：{@code </if>} 右侧、自闭合 {@code <include/>} 之后
     *       （{@code offset >= 该子标签 endOffset}）→ 属更外层语句内容，接管。</li>
     * </ul></p>
     *
     * <p>标记区边界的求法：平台 XmlTag 无 startTag/endTag 访问器（262/2025.3 javap 实证，
     * 历史误写会被编译期拦下），改由<b>体内容孩子</b>（XmlTag/XmlText/XmlComment）推导——
     * 起始标记 = 标签 startOffset 到第一个体内容孩子；无体内容孩子（空标签）整段都是标记。</p>
     *
     * <p>旧判据把「子标签体内正文」（offset &lt; endOffset）与「子标签自身标记区」混为一谈，
     * 修 20260928 的 A（标记区误接管）时引入 B（正文误放行）。</p>
     */
    private static boolean inStatementText(XmlTag tag, int offset) {
        if (!XmlSynHiGate.inMapperFile(tag)) {
            return false;
        }
        // generateSequence(tag) { it.parentTag }.take(64).firstOrNull { it.name in STATEMENT_TAGS }
        XmlTag statement = null;
        int depth = 0;
        for (XmlTag cur = tag; cur != null && depth < 64; cur = cur.getParentTag(), depth++) {
            if (STATEMENT_TAGS.contains(cur.getName())) {
                statement = cur;
                break;
            }
        }
        if (statement == null) {
            return false;
        }
        XmlTag cur = tag;
        while (cur != null && cur != statement) {
            if (offset >= cur.getTextRange().getEndOffset()) {
                return true;   // 子标签之后＝外层语句内容
            }
            if (offset < bodyStart(cur)) {
                return false;  // 子标签起始标记区（含属性间隙）
            }
            cur = cur.getParentTag();
        }
        // 语句标签自身：起始标记区不接管；结束标记区及其后不接管。
        // 体末端点含在内（offset == bodyEnd 是紧贴 `</if>` 前的正文追加位，敲 # 应接管）
        int[] body = bodyRange(statement);
        if (body == null) {
            return false;
        }
        return offset >= body[0] && offset <= body[1];
    }

    /**
     * 标签<b>体内容</b>（子标签 / 文本 / 注释）起点＝起始标记的结束；无体内容（空标签）返回 endOffset。
     */
    private static int bodyStart(XmlTag tag) {
        for (PsiElement child : tag.getChildren()) {
            if (child instanceof XmlTag || child instanceof XmlText || child instanceof XmlComment) {
                return child.getTextRange().getStartOffset();
            }
        }
        return tag.getTextRange().getEndOffset();
    }

    /**
     * 标签体内容区间（首/末体内容孩子）；无体内容（空标签）返回 null。
     * Kotlin {@code Pair<Int, Int>?} 的 Java 承载为 {@code int[]{start, end}}。
     */
    private static int[] bodyRange(XmlTag tag) {
        int first = -1;
        int last = -1;
        for (PsiElement child : tag.getChildren()) {
            if (child instanceof XmlTag || child instanceof XmlText || child instanceof XmlComment) {
                if (first < 0) {
                    first = child.getTextRange().getStartOffset();
                }
                last = child.getTextRange().getEndOffset();
            }
        }
        return first < 0 ? null : new int[] {first, last};
    }
}
