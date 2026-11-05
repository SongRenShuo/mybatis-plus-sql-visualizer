package cn.song.mybatisplus.visualizer.ui.nav

import cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * T-MapperNavShortcut：`Ctrl+Alt+Home`（GotoRelated）关联项用例。
 *
 * 平台传入的是光标处叶子元素（`file.findElementAt(offset)` → `NavigationUtil.collectRelatedItems`），
 * 故本类以 identifier / XML token 为入口驱动 [MapperNavigationGotoRelatedProvider]。
 */
class MapperNavigationGotoRelatedProviderTest : BasePlatformTestCase() {

    private val provider = MapperNavigationGotoRelatedProvider()

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

    private fun addMapper(): PsiFile = myFixture.addFileToProject(
        "com/demo/UserMapper.java",
        """
        package com.demo;
        public interface UserMapper {
            int selectById();
        }
        """.trimIndent(),
    )

    private fun addXml(body: String, namespace: String = "com.demo.UserMapper"): PsiFile =
        myFixture.addFileToProject("mapper/UserMapper.xml", "<mapper namespace=\"$namespace\">\n$body\n</mapper>")

    private fun identifierOf(file: PsiFile, text: String): PsiIdentifier =
        PsiTreeUtil.findChildrenOfType(file, PsiIdentifier::class.java).first { it.text == text }

    private fun startTagNameToken(file: PsiFile, tagName: String): XmlToken =
        PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .first { it.name == tagName }
            .let { tag ->
                PsiTreeUtil.findChildrenOfType(file, XmlToken::class.java)
                    .first { it.tokenType == XmlTokenType.XML_NAME && it.text == tagName && it.parent === tag }
            }

    fun test接口方法_列出XML语句() {
        val java = addMapper()
        addXml("<select id=\"selectById\">select 1</select>")

        val items = provider.getItems(identifierOf(java, "selectById"))

        assertEquals(1, items.size)
        val element = items[0].element
        assertTrue("关联项应指向 XML 语句标签", element is XmlTag)
        assertEquals("selectById", (element as XmlTag).getAttributeValue("id"))
        assertEquals("selectById", items[0].customName)
    }

    fun testXML语句_列出接口方法() {
        addMapper()
        val xml = addXml("<select id=\"selectById\">select 1</select>")

        val items = provider.getItems(startTagNameToken(xml, "select"))

        assertEquals(1, items.size)
        val element = items[0].element
        assertTrue("关联项应指向接口方法", element is PsiMethod)
        assertEquals("selectById", (element as PsiMethod).name)
        assertEquals("UserMapper#selectById", items[0].customName)
    }

    fun test语句体内_也能列出接口方法() {
        addMapper()
        val xml = addXml("<select id=\"selectById\">select 1</select>")
        // 与平台一致：取光标处叶子元素（GotoRelatedSymbolAction 走 file.findElementAt(offset)）
        val bodyLeaf = xml.findElementAt(xml.text.indexOf("select 1"))!!

        val items = provider.getItems(bodyLeaf)

        assertEquals(1, items.size)
        assertTrue(items[0].element is PsiMethod)
    }

    fun test无关Java方法_零关联项() {
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

        assertTrue("普通类方法不是 mapper 方法", provider.getItems(identifierOf(java, "selectById")).isEmpty())
    }

    fun test无XML语句_零关联项() {
        val java = addMapper()
        addXml("<select id=\"other\">select 1</select>")

        assertTrue(provider.getItems(identifierOf(java, "selectById")).isEmpty())
    }
}
