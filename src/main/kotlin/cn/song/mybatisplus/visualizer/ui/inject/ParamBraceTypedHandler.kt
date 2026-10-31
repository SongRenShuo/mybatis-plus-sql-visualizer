package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.injected.editor.DocumentWindow
import com.intellij.injected.editor.EditorWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlComment
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * T-XmlSynHi D5：mapper XML 语句文本内敲 `#` / `$` 自动闭合 `{}`（光标落 #/{ 之间）。
 *
 * 「先查后插」防双插（决策 4-A，共存插件同型行为）：插入前查光标后方字符——
 *  - 已是 `{` → 不补插（对方插件/用户已手敲 `{`，双插会产出 `#{{}}`）——直接放行原字符。
 *  （T-PerfSweep16 F46 口径对齐：历史 KDoc 声称 `}` 后也放行，实现自始只查 `{`——修文档不改行为。）
 * 守卫：XmlSynHiGate **设置开关线**（`shouldYield(project)` 单参形态）+ 根 mapper + 语句标签内。
 * （T-XmlGap W1 勘误：本 KDoc 旧述「双让位（平台/CodeHelperPro 在位即不接管）」与实现不符——
 *  本类**从不做** per-host 探测，共存安全靠下面的「先查后插」与语句标签守卫，不靠让位。）
 *
 * 243+ 注入片段击键路由（TypedHandler.callDelegates 字节码实证）：光标在注入片段内时
 * 传给本回调的 [file] 是**注入 PsiFile**（SQL 语言）而非宿主 XmlFile、[editor] 是
 * EditorWindow——须还原宿主三件套（hostFile/hostEditor/hostOffset）再套守卫；宿主路径
 * （标签标记区等非注入位）三件套原样即位。
 */
class ParamBraceTypedHandler : TypedHandlerDelegate() {

    override fun beforeCharTyped(
        c: Char,
        project: Project,
        editor: Editor,
        file: PsiFile,
        fileType: FileType,
    ): TypedHandlerDelegate.Result {
        if (c != '#' && c != '$') return TypedHandlerDelegate.Result.CONTINUE
        val inInjected = file !is XmlFile
        val hostFile = if (inInjected) {
            InjectedLanguageManager.getInstance(project).getInjectionHost(file)
                ?.containingFile as? XmlFile
                ?: return TypedHandlerDelegate.Result.CONTINUE
        } else {
            file
        }
        if (XmlSynHiGate.shouldYield(project)) return TypedHandlerDelegate.Result.CONTINUE
        val hostEditor = (editor as? EditorWindow)?.delegate ?: editor
        val offset = if (inInjected) {
            (editor.document as? DocumentWindow)
                ?.injectedToHost(editor.caretModel.offset)
                ?: editor.caretModel.offset
        } else {
            editor.caretModel.offset
        }
        // T-PerfSweep16 F39：getCharsSequence 直读底层文本（原 document.text 每次击键整文档复制一份 String）
        val text = hostEditor.document.charsSequence
        // 光标后方已有 `{`（对方插件已补/用户已敲）→ 放行，不双插
        if (offset < text.length && text[offset] == '{') return TypedHandlerDelegate.Result.CONTINUE
        // 光标须在 mapper 语句标签文本内（根 mapper 守卫 + 标签定位）；宿主偏移左邻字符定位
        val at = (offset - 1).coerceAtLeast(0)
        val el = hostFile.findElementAt(at) ?: return TypedHandlerDelegate.Result.CONTINUE
        val tag = PsiTreeUtil.getParentOfType(el, XmlTag::class.java)
            ?: return TypedHandlerDelegate.Result.CONTINUE
        if (!inStatementText(tag, offset)) return TypedHandlerDelegate.Result.CONTINUE

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            hostEditor.document.insertString(offset, "$c{}")
            hostEditor.caretModel.moveToOffset(offset + 1)
        }
        return TypedHandlerDelegate.Result.STOP
    }

    /**
     * 光标前字符所属标签是否为语句文本位（mapper 根守卫 + 语句标签集，与 injector 同口径）。
     *
     * 两态分界（T-XmlBraceNested 重写，20260928 旧判据作废）：
     *  - **最近语句标签祖先（任意深度）**：动态标签（`if`/`where`/`choose`/`trim`/…）可任意嵌套，
     *    旧「一层 parentTag∈语句标签」判法把 `<where>` 下的 `<if>` 体内正文全部漏判
     *    （R15 录制实测：真实 mapper 主流排版下功能全灭）；
     *  - **标记区不接管**：光标落在某标签的**起始标记**内（`<select id=|>`、`<trim prefix=|>`
     *    属性间隙）→ 不接管（20260928 真机实证形态，语义保留）；
     *  - **子标签之后接管**：`</if>` 右侧、自闭合 `<include/>` 之后（`offset >= 该子标签 endOffset`）
     *    → 属更外层语句内容，接管。
     *
     * 标记区边界的求法：平台 XmlTag 无 startTag/endTag 访问器（262/2025.3 javap 实证，
     * 历史误写会被编译期拦下），改由**体内容孩子**（XmlTag/XmlText/XmlComment）推导——
     * 起始标记 = 标签 startOffset 到第一个体内容孩子；无体内容孩子（空标签）整段都是标记。
     *
     * 旧判据把「子标签体内正文」（offset < endOffset）与「子标签自身标记区」混为一谈，
     * 修 20260928 的 A（标记区误接管）时引入 B（正文误放行）。
     */
    private fun inStatementText(tag: XmlTag, offset: Int): Boolean {
        if (!XmlSynHiGate.inMapperFile(tag)) return false
        val statement = generateSequence(tag) { it.parentTag }
            .take(64)
            .firstOrNull { it.name in STATEMENT_TAGS } ?: return false
        var cur: XmlTag? = tag
        while (cur != null && cur !== statement) {
            if (offset >= cur.textRange.endOffset) return true   // 子标签之后＝外层语句内容
            if (offset < bodyStart(cur)) return false            // 子标签起始标记区（含属性间隙）
            cur = cur.parentTag
        }
        // 语句标签自身：起始标记区不接管；结束标记区及其后不接管。
        // 体末端点含在内（offset == bodyEnd 是紧贴 `</if>` 前的正文追加位，敲 # 应接管）
        val (bodyStartOff, bodyEndOff) = bodyRange(statement) ?: return false
        return offset in bodyStartOff..bodyEndOff
    }

    /** 标签**体内容**（子标签 / 文本 / 注释）起点＝起始标记的结束；无体内容（空标签）返回 endOffset。 */
    private fun bodyStart(tag: XmlTag): Int =
        tag.children.firstOrNull { it is XmlTag || it is XmlText || it is XmlComment }?.textRange?.startOffset
            ?: tag.textRange.endOffset

    /** 标签体内容区间（首/末体内容孩子）；无体内容（空标签）返回 null。 */
    private fun bodyRange(tag: XmlTag): Pair<Int, Int>? {
        val body = tag.children.filter { it is XmlTag || it is XmlText || it is XmlComment }
        if (body.isEmpty()) return null
        return body.first().textRange.startOffset to body.last().textRange.endOffset
    }

    private companion object {
        private val STATEMENT_TAGS = setOf("select", "insert", "update", "delete")
    }

}
