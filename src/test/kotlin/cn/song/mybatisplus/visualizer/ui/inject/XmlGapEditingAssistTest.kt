package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-XmlGap W6 编辑辅助三件：编辑脏区放大、`#{}`/`${}` 成对删、live template 上下文。
 *
 * 说明：live template 的**模板文件本体**（`resources/templates/mybatis-sql.xml` + `defaultLiveTemplates`
 * EP）无法在单测里断言「敲缩写会展开」——平台模板引擎只在真实编辑器里工作；本类改为断言
 * **上下文判据**（`isInContext`）与**模板文件在场且 context id 对齐**（防「模板不生效」的静默形态）。
 */
class XmlGapEditingAssistTest : BasePlatformTestCase() {

    private val mapper = """<mapper namespace="n">
  <select id="a">SELECT #{id} FROM t</select>
  <resultMap id="rm" type="java.lang.String"/>
</mapper>"""

    // ------------------------------------------------------------------
    // 编辑脏区放大
    // ------------------------------------------------------------------

    fun testW6_脏区_语句内文本_放大到所属语句标签() {
        myFixture.configureByText("mapper.xml", mapper)
        val text = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlText::class.java)
            .first { it.text.contains("SELECT") }
        val scope = SqlMapperChangeLocalityDetector().getChangeHighlightingDirtyScopeFor(text)
        assertNotNull("语句内编辑须放大脏区", scope)
        assertEquals("脏区收窄到所属语句标签", "select", (scope as XmlTag).name)
    }

    fun testW6_脏区_非mapper文件_不介入() {
        myFixture.configureByText(
            "other.xml",
            """<configuration><settings><setting name="a" value="b"/></settings></configuration>""",
        )
        val text = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlText::class.java).firstOrNull()
        if (text != null) {
            assertNull("非 mapper 文件不放大脏区（交回平台默认语义）", SqlMapperChangeLocalityDetector().getChangeHighlightingDirtyScopeFor(text))
        }
    }

    // ------------------------------------------------------------------
    // `#{}` / `${}` 成对删
    // ------------------------------------------------------------------

    /** 光标在 `#{}` 中间按退格 → `#{}` 整段删除（而非只剩 `#}`）。 */
    /**
     * 成对删用例的驱动形态：**直调处理器**。
     *
     * 为什么不走 `myFixture.type('\b')`：测试夹具不加载 `backspaceHandlerDelegate` EP（仓内既有注入器
     * 测试同样手工注册），走 EP 的链路在夹具里恒不触发；且「平台先删 `{`、本类再补删」是两段，
     * 夹具里复现整链无谓。这里直接把夹具置成**平台已删掉 `{` 之后**的状态再直调 `charDeleted`，
     * 钉住本类逻辑本身（EP 接线由类尾 plugin.xml 守卫钉）。
     */
    private fun driveAfterBraceDeleted(c: Char = '{'): String {
        SqlMapperBackspaceHandler().charDeleted(c, myFixture.file, myFixture.editor)
        return myFixture.editor.document.text
    }

    /** 平台删掉 `#{` 的 `{` 后（残留 `#` + `}`）→ 本类补删两个字符，`#{}` 整段消失。 */
    fun testW6_成对删_井号参数() {
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n"><select id="a">SELECT #<caret>} FROM t</select></mapper>""",
        )
        val text = driveAfterBraceDeleted()
        assertFalse("`#{}` 应整段删除（不留 `#}`）", text.contains("#}"))
        assertTrue("其余文本不受影响", text.contains("SELECT  FROM t"))
    }

    /** `${}` 同形：平台删掉 `{` 后残留 `$` + `}` → 本类补删。 */
    fun testW6_成对删_美元参数() {
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n"><select id="a">SELECT ${'$'}<caret>} FROM t</select></mapper>""",
        )
        val text = driveAfterBraceDeleted()
        assertFalse("`\${}` 应整段删除（不留 `\$}`）", text.contains("\$}"))
        assertTrue("其余文本不受影响", text.contains("SELECT  FROM t"))
    }

    /** 非配对形态（左邻不是 `#`/`$`）→ 零介入，平台已删的 `{` 之外一个字符都不动。 */
    fun testW6_成对删_非配对形态_零介入() {
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n"><select id="a">SELECT a<caret>b FROM t</select></mapper>""",
        )
        val text = driveAfterBraceDeleted()
        assertEquals("非配对形态：零介入（文本原样）", myFixture.file.text, text)
    }

    /** 非 mapper 文件 → 零介入（不在本插件作用域）。 */
    fun testW6_成对删_非mapper文件_零介入() {
        myFixture.configureByText(
            "other.xml",
            """<configuration><properties>#<caret>}</properties></configuration>""",
        )
        val text = driveAfterBraceDeleted()
        assertTrue("非 mapper 文件不介入：`#}` 保留", text.contains("#}"))
    }

    /** 右邻不是 `}`（如 `#{id}` 里删掉 `{`）→ 不误删，交给用户继续输入。 */
    fun testW6_成对删_右邻非花括号_零介入() {
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n"><select id="a">SELECT #<caret>id} FROM t</select></mapper>""",
        )
        val text = driveAfterBraceDeleted()
        assertTrue("右邻非 `}`：不补删（`#id}` 原样）", text.contains("#id}"))
    }

    // ------------------------------------------------------------------
    // live template 上下文
    // ------------------------------------------------------------------

    fun testW6_模板上下文_语句内为真_根下为假() {
        myFixture.configureByText("mapper.xml", mapper)
        val ctx = MybatisSqlTemplateContextType()
        val file = myFixture.file
        val selectText = PsiTreeUtil.collectElementsOfType(file, XmlText::class.java)
            .first { it.text.contains("SELECT") }
        assertTrue("语句文本内 → 上下文为真", ctx.isInContext(file, selectText.textRange.startOffset + 2))
        val rootTag = (file as XmlFile).rootTag!!
        assertFalse("根标签标记区（非语句内）→ 上下文为假", ctx.isInContext(file, rootTag.textRange.startOffset + 1))
    }

    fun testW6_模板上下文_非mapper文件为假() {
        myFixture.configureByText("other.xml", """<configuration><settings/></configuration>""")
        val ctx = MybatisSqlTemplateContextType()
        assertFalse("非 mapper 文件 → 上下文为假", ctx.isInContext(myFixture.file, 20))
    }

    /** 模板文件在场 + context id 与上下文类常量对齐（防「模板静默不生效」）。 */
    fun testW6_模板文件在场且上下文id对齐() {
        val stream = javaClass.classLoader.getResourceAsStream("templates/mybatis-sql.xml")
        assertNotNull("模板文件须随资源打包（resources/templates/mybatis-sql.xml）", stream)
        val xml = stream!!.readBytes().toString(Charsets.UTF_8)
        assertTrue("模板组在场", xml.contains("<templateSet"))
        assertTrue("cd 模板在场", xml.contains("name=\"cd\""))
        assertTrue("co 模板在场", xml.contains("name=\"co\""))
        assertTrue(
            "context option name 须等于 MybatisSqlTemplateContextType.ID",
            xml.contains("name=\"${MybatisSqlTemplateContextType.ID}\""),
        )
        // 只断言模板**名单**（不扫全文——注释里为说明「不做哪三个」会提到竞品的模板名，扫全文会误判）
        val templateNames = Regex("""<template\s+name="([^"]+)"""").findAll(xml)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals("模板集须恰为 cd / co 两个纯骨架（不含竞品引导注释类模板）", setOf("cd", "co"), templateNames)
    }

    /** EP 接线守卫：四个 W6 EP 均在 plugin.xml 声明。 */
    fun testW6_pluginXml_四个EP接线在场() {
        val xml = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!
            .readBytes().toString(Charsets.UTF_8)
        // 打包期 patchPluginXml 会重排空白（实测 `<defaultLiveTemplates file="…" />` 带空格），
        // 故按空白容忍的正则断言，不写死字面量。
        assertTrue(
            "defaultLiveTemplates 在场",
            Regex("""<defaultLiveTemplates\s+file="/templates/mybatis-sql"\s*/>""").containsMatchIn(xml),
        )
        assertTrue(
            "liveTemplateContext 在场",
            Regex("""<liveTemplateContext\s+implementation="[^"]*MybatisSqlTemplateContextType"\s*/>""").containsMatchIn(xml),
        )
        assertTrue(
            "daemon.changeLocalityDetector 在场",
            Regex("""<daemon\.changeLocalityDetector\s+implementation="[^"]*SqlMapperChangeLocalityDetector"\s*/>""")
                .containsMatchIn(xml),
        )
        assertTrue(
            "backspaceHandlerDelegate 在场",
            Regex("""<backspaceHandlerDelegate\s+implementation="[^"]*SqlMapperBackspaceHandler"\s*/>""").containsMatchIn(xml),
        )
    }
}
