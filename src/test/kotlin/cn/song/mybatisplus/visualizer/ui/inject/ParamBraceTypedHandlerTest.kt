package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * T-XmlSynHi D5 前半：typedHandler `#`/`$` 自动闭合 `{}`——
 * 先查后插（光标后方已有 `{` 不双插）、语句标签外不接管。
 */
class ParamBraceTypedHandlerTest : BasePlatformTestCase() {

    private val mapper = """<mapper namespace="n">
  <select id="a">SELECT * FROM t WHERE id = <caret></select>
</mapper>"""

    private fun openFile(text: String, caretOffset: Int) {
        myFixture.configureByText("sample.xml", text)
        myFixture.editor.caretModel.moveToOffset(caretOffset)
    }

    private fun typeChar(c: Char) {
        val handler = ParamBraceTypedHandler()
        val result = handler.beforeCharTyped(
            c,
            project,
            myFixture.editor,
            myFixture.file,
            myFixture.file.fileType,
        )
        org.junit.Assert.assertNotEquals(
            "STOP 才代表接管（插入+移光标）",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.CONTINUE,
            result,
        )
        // 模拟接管插入（与生产 WriteCommandAction 同语义）
        val doc = myFixture.editor.document
        val off = myFixture.editor.caretModel.offset
        doc.insertString(off, "$c{}")
        myFixture.editor.caretModel.moveToOffset(off + 1)
    }

    @Test
    fun testD5_语句文本内敲井号_自动闭合花括号() {
        val text = mapper.replace("<caret>", "")
        val caret = text.indexOf("WHERE id = ") + "WHERE id = ".length
        openFile(text, caret)
        // 断言接管语义：beforeCharTyped 返回 STOP = handler 接管（生产侧 WriteCommandAction 插入 #{}，
        // 光标 offset+1 居中——写动作语义已在真实编辑动作链路成立，测试环境不做重复写）。
        val handler = ParamBraceTypedHandler()
        val result = handler.beforeCharTyped('#', project, myFixture.editor, myFixture.file, myFixture.file.fileType)
        assertEquals(
            "语句文本内接管（STOP）",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.STOP,
            result,
        )
    }

    @Test
    fun testD5_先查后插_光标后方已有花括号不双插() {
        val base = "<mapper namespace=\"n\">\n  <select id=\"a\">SELECT * FROM t WHERE id = <caret>{}</select>\n</mapper>"
        val caret = base.indexOf("<caret>")
        val text = base.replace("<caret>", "")
        openFile(text, caret)
        val handler = ParamBraceTypedHandler()
        val result = handler.beforeCharTyped('#', project, myFixture.editor, myFixture.file, myFixture.file.fileType)
        assertEquals(
            "后方已有 { → 放行不接管（先查后插判据）",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.CONTINUE,
            result,
        )
    }

    @Test
    fun testD5_语句标签外_不接管() {
        val base = "<mapper namespace=\"n\"><caret>\n  <select id=\"a\">SELECT 1</select>\n</mapper>"
        val caret = base.indexOf("<caret>")
        val text = base.replace("<caret>", "")
        openFile(text, caret)
        val handler = ParamBraceTypedHandler()
        val result = handler.beforeCharTyped('#', project, myFixture.editor, myFixture.file, myFixture.file.fileType)
        assertEquals(
            "语句标签外不接管（Contin 继续原输入）",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.CONTINUE,
            result,
        )
    }

    @Test
    fun testD5_子标签标记区内_不接管() {
        // 20260928 真机实证：光标落 <trim prefix=|"where"> 属性间隙（父=select 语句标签），
        // 旧宽判（parentTag∈语句标签）误接管闭合 #{}——子标签自身标记区必须放行
        val base = "<mapper namespace=\"n\">\n  <select id=\"a\">SELECT 1\n    <trim <caret>prefix=\"where\"></trim>\n  </select>\n</mapper>"
        val caret = base.indexOf("<caret>")
        val text = base.replace("<caret>", "")
        openFile(text, caret)
        val handler = ParamBraceTypedHandler()
        val result = handler.beforeCharTyped('#', project, myFixture.editor, myFixture.file, myFixture.file.fileType)
        assertEquals(
            "子标签标记区内不接管（offset < 子标签 endOffset）",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.CONTINUE,
            result,
        )
    }

    @Test
    fun testD5_子标签边界之后_接管() {
        // `</if>` 右侧属父语句内容区，敲 # 应接管（offset >= 子标签 endOffset）
        val base = "<mapper namespace=\"n\">\n  <update id=\"u\">update t\n    <if></if><caret>\n  </update>\n</mapper>"
        val caret = base.indexOf("<caret>")
        val text = base.replace("<caret>", "")
        openFile(text, caret)
        val handler = ParamBraceTypedHandler()
        val result = handler.beforeCharTyped('#', project, myFixture.editor, myFixture.file, myFixture.file.fileType)
        assertEquals(
            "子标签边界之后（父语句内容区）接管",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.STOP,
            result,
        )
    }

    @Test
    fun testD5_pluginXml_typedHandler_EP接线在场() {
        // 真机实证（20260928）：TypedHandlerDelegate.EP_NAME 实际常量=「com.intellij.typedHandler」
        // （243/253/262 三版 javap 一致）；写成「typedHandlerDelegate」会被平台按未知 EP **静默忽略**，
        // handler 永不触发且单测（直接实例化，不经 EP 分发）探不到——守卫钉住接线本身。
        val xml = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!
            .readBytes().toString(Charsets.UTF_8)
        org.junit.Assert.assertTrue(
            "plugin.xml 须含 <typedHandler implementation=…ParamBraceTypedHandler>（EP 真名）",
            Regex("<typedHandler\\s+implementation=\"cn\\.song\\.mybatisplus\\.visualizer\\.ui\\.inject\\.ParamBraceTypedHandler\"")
                .containsMatchIn(xml),
        )
        org.junit.Assert.assertFalse(
            "历史误名 typedHandlerDelegate（未知 EP 被静默忽略）不得回潮",
            Regex("<typedHandlerDelegate\\s").containsMatchIn(xml),
        )
    }

    // ------------------------------------------------------------------
    // T-XmlBraceNested（R15 录制实测）：动态标签嵌套体内正文接管 —— 旧「一层 parent +
    // offset vs 子标签 endOffset」判据把 `<where>` 下的 `<if>` 体内正文全漏判
    // ------------------------------------------------------------------

    private fun resultFor(base: String): com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result {
        val caret = base.indexOf("<caret>")
        val text = base.replace("<caret>", "")
        openFile(text, caret)
        return ParamBraceTypedHandler().beforeCharTyped(
            '#', project, myFixture.editor, myFixture.file, myFixture.file.fileType,
        )
    }

    @Test
    fun testXmlBraceNested_where下if体内_接管() {
        // R15 实测主形态：真实 mapper 几乎全是 <where><if> 排版，旧判据在此全灭。
        // 光标须落在「敲 # 前」的位置（体内正文行尾）；光标后方是 `{` 会撞先查后插守卫（非本判据）
        val base = "<mapper namespace=\"n\">\n" +
            "  <select id=\"a\">SELECT 1\n" +
            "    <where>\n" +
            "      <if test=\"status != null\">and status = <caret></if>\n" +
            "    </where>\n" +
            "  </select>\n" +
            "</mapper>"
        assertEquals(
            "<where> 下 <if> 体内正文接管",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.STOP,
            resultFor(base),
        )
    }

    @Test
    fun testXmlBraceNested_choose下when体内_接管() {
        val base = "<mapper namespace=\"n\">\n" +
            "  <select id=\"a\">SELECT 1\n" +
            "    <choose>\n" +
            "      <when test=\"a != null\">and a = <caret></when>\n" +
            "    </choose>\n" +
            "  </select>\n" +
            "</mapper>"
        assertEquals(
            "二级嵌套 <choose><when> 体内接管",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.STOP,
            resultFor(base),
        )
    }

    @Test
    fun testXmlBraceNested_单层if体内_接管() {
        // 直接子标签体内正文：旧判据因 offset < 子标签 endOffset 误判成「标记区」也不接管
        val base = "<mapper namespace=\"n\">\n" +
            "  <update id=\"u\">update t\n" +
            "    <if test=\"a != null\">set a = <caret></if>\n" +
            "  </update>\n" +
            "</mapper>"
        assertEquals(
            "直接子标签体内正文接管（旧两态判据的 B 面回归钉）",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.STOP,
            resultFor(base),
        )
    }

    @Test
    fun testXmlBraceNested_语句标签自身属性区_不接管() {
        // 同族洞：旧判据 tag∈STATEMENT_TAGS 直放，`<select id="a|">` 里敲 # 会插出 #{}
        val base = "<mapper namespace=\"n\">\n" +
            "  <select id=\"a\" result<caret>Type=\"x\">SELECT 1</select>\n" +
            "</mapper>"
        assertEquals(
            "语句标签自身属性区不接管",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.CONTINUE,
            resultFor(base),
        )
    }

    @Test
    fun testXmlBraceNested_自闭合子标签之后_接管() {
        val base = "<mapper namespace=\"n\">\n" +
            "  <select id=\"a\">SELECT 1\n" +
            "    <include refid=\"c\"/><caret>\n" +
            "  </select>\n" +
            "</mapper>"
        assertEquals(
            "自闭合子标签之后＝父语句内容，接管",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.STOP,
            resultFor(base),
        )
    }
}
