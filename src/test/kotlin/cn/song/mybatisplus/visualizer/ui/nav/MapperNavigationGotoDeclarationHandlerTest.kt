package cn.song.mybatisplus.visualizer.ui.nav

import cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * T-MapperNavShortcut：双向 `GotoDeclarationHandler` 用例。
 *
 * 形态说明（与 `MybatisXmlReferenceTest` 同口径）：测试夹具不自动加载 plugin.xml 的 EP，
 * 故直接驱动 [MapperNavigationGotoDeclarationHandler]；EP 接线由类尾的 plugin.xml 文本守卫钉住。
 *
 * 反向验证口径：摘掉 handler 的 Java 分支 → [testJavaMethod_命中XML语句落点] 必红；
 * 摘掉 XML 分支 → [testXml起始标签名_跳接口方法] 必红。
 */
class MapperNavigationGotoDeclarationHandlerTest : BasePlatformTestCase() {

    private val handler = MapperNavigationGotoDeclarationHandler()

    override fun setUp() {
        super.setUp()
        MybatisXmlTargets.clearFileListCacheForTest()
    }

    override fun tearDown() {
        try {
            MybatisXmlTargets.clearFileListCacheForTest()
        } finally {
            super.tearDown()
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun addMapper(
        body: String = """
            package com.demo;
            public interface UserMapper {
                int selectById();
                int missing();
            }
        """.trimIndent(),
        path: String = "com/demo/UserMapper.java",
    ): PsiFile = myFixture.addFileToProject(path, body)

    private fun addXml(body: String, namespace: String = "com.demo.UserMapper"): PsiFile =
        myFixture.addFileToProject("mapper/UserMapper.xml", "<mapper namespace=\"$namespace\">\n$body\n</mapper>")

    private fun identifierOf(file: PsiFile, text: String): PsiIdentifier =
        PsiTreeUtil.findChildrenOfType(file, PsiIdentifier::class.java).first { it.text == text }

    private fun tokensOf(file: PsiFile, type: IElementType, text: String): List<XmlToken> =
        PsiTreeUtil.findChildrenOfType(file, XmlToken::class.java)
            .filter { it.tokenType == type && it.text == text }

    private fun startTagNameToken(file: PsiFile, tagName: String): XmlToken =
        PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .first { it.name == tagName }
            .let { tag -> tokensOf(file, XmlTokenType.XML_NAME, tagName).first { it.parent === tag } }

    /** handler 不读 editor/offset（纯元素形态判定），传 null 即可。 */
    private fun targets(element: PsiElement): List<PsiElement> =
        handler.getGotoDeclarationTargets(element, element.textOffset, null)?.toList().orEmpty()

    // ------------------------------------------------------------------
    // Java → XML
    // ------------------------------------------------------------------

    fun testJavaMethod_命中XML语句落点() {
        val java = addMapper()
        val xml = addXml("<select id=\"selectById\">select 1</select>")

        val result = targets(identifierOf(java, "selectById"))

        assertEquals("Java 方法名应给出唯一 XML 落点", 1, result.size)
        assertEquals("落点应是语句 id 的文本", "selectById", result[0].text)
        assertSame("落点应在 mapper XML 内", xml, result[0].containingFile)
    }

    fun testJavaMethod_无对应XML语句_返回null() {
        val java = addMapper()
        addXml("<select id=\"selectById\">select 1</select>")

        assertTrue("无同名语句的方法不得接管 Ctrl+B", targets(identifierOf(java, "missing")).isEmpty())
    }

    fun testJavaMethod_无mapperXML_返回null() {
        val java = addMapper()
        assertTrue("项目内没有 mapper XML 时不得接管", targets(identifierOf(java, "selectById")).isEmpty())
    }

    fun test普通类方法_返回null() {
        val java = myFixture.addFileToProject(
            "com/demo/UserService.java",
            """
            package com.demo;
            public class UserService {
                public int selectById() { return 0; }
            }
            """.trimIndent(),
        )
        addXml("<select id=\"selectById\">select 1</select>", namespace = "com.demo.UserService")

        assertTrue("非接口方法不是 mapper 方法", targets(identifierOf(java, "selectById")).isEmpty())
    }

    fun test方法体内调用点_不接管() {
        val java = myFixture.addFileToProject(
            "com/demo/OtherMapper.java",
            """
            package com.demo;
            public interface OtherMapper {
                int selectById();
                default int call() { return selectById(); }
            }
            """.trimIndent(),
        )
        addXml("<select id=\"selectById\">select 1</select>", namespace = "com.demo.OtherMapper")

        val identifiers = PsiTreeUtil.findChildrenOfType(java, PsiIdentifier::class.java)
            .filter { it.text == "selectById" }
        assertEquals("夹具应有声明处与调用处两个同名 identifier", 2, identifiers.size)

        val hits = identifiers.filter { targets(it).isNotEmpty() }
        assertEquals("只接管方法声明名，调用点交给原生", 1, hits.size)
        assertTrue(hits[0].parent is PsiMethod)
    }

    fun test同名多databaseId_返回全部目标() {
        val java = addMapper()
        addXml(
            "<select id=\"selectById\" databaseId=\"mysql\">select 1</select>\n" +
                "<select id=\"selectById\" databaseId=\"oracle\">select 2</select>",
        )

        assertEquals("多方言变体应全部给出（平台弹选择器）", 2, targets(identifierOf(java, "selectById")).size)
    }

    // ------------------------------------------------------------------
    // XML → Java
    // ------------------------------------------------------------------

    fun testXml起始标签名_跳接口方法() {
        addMapper()
        val xml = addXml("<select id=\"selectById\">select 1</select>")

        val result = targets(startTagNameToken(xml, "select"))

        assertEquals(1, result.size)
        assertTrue("落点应是 PsiMethod", result[0] is PsiMethod)
        assertEquals("selectById", (result[0] as PsiMethod).name)
    }

    fun testXml闭标签名_不接管() {
        addMapper()
        val xml = addXml("<select id=\"selectById\">select 1</select>")

        val nameTokens = tokensOf(xml, XmlTokenType.XML_NAME, "select")
        assertEquals("起始与闭标签各一个 name token", 2, nameTokens.size)
        assertEquals("只有起始标签名接管", 1, nameTokens.count { targets(it).isNotEmpty() })
    }

    fun testXml_id属性值_跳接口方法() {
        addMapper()
        val xml = addXml("<select id=\"selectById\">select 1</select>")

        val valueToken = tokensOf(xml, XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN, "selectById").single()
        val result = targets(valueToken)

        assertEquals(1, result.size)
        assertEquals("selectById", (result[0] as PsiMethod).name)
    }

    fun testXml_id属性名_跳接口方法() {
        addMapper()
        val xml = addXml("<select id=\"selectById\">select 1</select>")

        val nameToken = tokensOf(xml, XmlTokenType.XML_NAME, "id").single()
        assertEquals(1, targets(nameToken).size)
    }

    fun testXml_其它属性_不接管() {
        addMapper()
        val xml = addXml("<select id=\"selectById\" databaseId=\"mysql\">select 1</select>")

        val databaseIdToken = tokensOf(xml, XmlTokenType.XML_NAME, "databaseId").single()
        assertTrue("只有 id 属性参与跳转", targets(databaseIdToken).isEmpty())
    }

    fun testXml_非mapper根_不接管() {
        addMapper()
        val xml = myFixture.addFileToProject(
            "mapper/Other.xml",
            "<root>\n<select id=\"selectById\">select 1</select>\n</root>",
        )

        assertTrue(targets(startTagNameToken(xml, "select")).isEmpty())
    }

    fun testXml_sql片段_不接管() {
        val xml = addXml("<sql id=\"selectById\">x</sql>")
        assertTrue("片段标签没有接口方法", targets(startTagNameToken(xml, "sql")).isEmpty())
    }

    // ------------------------------------------------------------------
    // EP 接线与「快捷键不私有」
    // ------------------------------------------------------------------

    private fun pluginXml(): String =
        javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!
            .readBytes().toString(Charsets.UTF_8)

    fun testPluginXml_gotoDeclarationHandler接线在场() {
        assertTrue(
            "plugin.xml 须注册 <gotoDeclarationHandler implementation=…MapperNavigationGotoDeclarationHandler>",
            Regex(
                "<gotoDeclarationHandler\\s+implementation=\"cn\\.song\\.mybatisplus\\.visualizer\\.ui\\.nav\\.MapperNavigationGotoDeclarationHandler\"",
            ).containsMatchIn(pluginXml()),
        )
    }

    fun testPluginXml_gotoRelatedProvider接线在场() {
        assertTrue(
            "plugin.xml 须注册 <gotoRelatedProvider implementation=…MapperNavigationGotoRelatedProvider>",
            Regex(
                "<gotoRelatedProvider\\s+implementation=\"cn\\.song\\.mybatisplus\\.visualizer\\.ui\\.nav\\.MapperNavigationGotoRelatedProvider\"",
            ).containsMatchIn(pluginXml()),
        )
    }

    /**
     * 快捷键不私有：新增注册段内不得出现 `keyboard-shortcut` / `mouse-shortcut`。
     * 键位必须由平台 `GotoDeclaration` / `GotoRelated` action 持有——用户改 Keymap 后自动跟随；
     * 一旦有人在这里写死键位，用户改键就会失效（用户 2026-11-05 明确要求）。
     */
    fun test新增注册段_不声明私有键位() {
        val xml = pluginXml().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val start = xml.indexOf("<gotoDeclarationHandler")
        val end = xml.indexOf("gotoRelatedProvider", start)
        assertTrue("新增注册段应存在（start=$start, end=$end）", start >= 0 && end > start)

        val block = xml.substring(start, end)
        assertFalse("注册段内不得出现 keyboard-shortcut：$block", block.contains("keyboard-shortcut"))
        assertFalse("注册段内不得出现 mouse-shortcut：$block", block.contains("mouse-shortcut"))
    }
}
