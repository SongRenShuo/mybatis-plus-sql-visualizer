package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * <pages> XML gutter 行标记 provider 测试（F-44-6 续 · 补用例）：
 * 此前整文件 0 测试，statementsInRange() 缝已暴露。覆盖：
 *   - statementsInRange：选区范围内 maintainPage 候选、按 offset 排序、排除非 maintainPage；
 *   - getLineMarkerInfo：maintainPage 标签（parent 为 pages）注册图标；非 maintainPage / 非 pages 下不注册。
 */
class PagesXmlGutterLineMarkerProviderTest : BasePlatformTestCase() {

    private fun xmlFile(text: String): XmlFile {
        myFixture.configureByText("pages.xml", text)
        return myFixture.file as XmlFile
    }

    private fun tokensNamed(file: XmlFile, name: String): List<XmlToken> =
        PsiTreeUtil.collectElementsOfType(file, XmlToken::class.java).filter { it.text == name }

    fun testStatementsInRange_返回范围内maintainPage且按offset排序() {
        val file = xmlFile(
            "<pages>\n" +
                "  <maintainPage id=\"p1\"/>\n" +
                "  <other/>\n" +
                "  <maintainPage id=\"p2\"/>\n" +
                "</pages>",
        )
        val tags = PagesXmlGutterLineMarkerProvider.statementsInRange(file, 0, file.textLength)
        assertEquals("应仅返回 2 个 maintainPage（other 被排除）", 2, tags.size)
        assertEquals("按 offset 排序首项应为 p1", "p1", tags[0].getAttributeValue("id"))
        assertEquals("按 offset 排序次项应为 p2", "p2", tags[1].getAttributeValue("id"))
    }

    fun testStatementsInRange_选区范围过滤() {
        val file = xmlFile(
            "<pages>\n" +
                "  <maintainPage id=\"p1\"/>\n" +
                "  <maintainPage id=\"p2\"/>\n" +
                "</pages>",
        )
        val p2 = tokensNamed(file, "maintainPage")[1].parent as XmlTag
        val end = p2.textRange.startOffset
        val tags = PagesXmlGutterLineMarkerProvider.statementsInRange(file, 0, end)
        assertEquals("选区仅覆盖首个 maintainPage 起点前 → 返回 1", 1, tags.size)
        assertEquals("返回项应为 p1", "p1", tags[0].getAttributeValue("id"))
    }

    fun testGetLineMarkerInfo_maintainPage注册图标_非maintainPage不注册() {
        val file = xmlFile(
            "<pages>\n" +
                "  <maintainPage id=\"p1\"/>\n" +
                "  <other/>\n" +
                "</pages>",
        )
        val provider = PagesXmlGutterLineMarkerProvider()
        val mpToken = tokensNamed(file, "maintainPage").first()
        assertNotNull("maintainPage 标签应注册 gutter 图标", provider.getLineMarkerInfo(mpToken))
        val otherToken = tokensNamed(file, "other").first()
        assertNull("非 maintainPage 标签不应注册 gutter 图标", provider.getLineMarkerInfo(otherToken))
    }

    fun testGetLineMarkerInfo_非pagesXML不注册() {
        val file = xmlFile("<root><maintainPage id=\"x\"/></root>")
        val provider = PagesXmlGutterLineMarkerProvider()
        val mpToken = tokensNamed(file, "maintainPage").first()
        // maintainPage 的 parent 是 root 而非 pages → 不应注册
        assertNull("非 <pages> 下的 maintainPage 不应注册 gutter 图标", provider.getLineMarkerInfo(mpToken))
    }

    // ------------------------------------------------------------------
    // T-PagesHql：页面标签名不固定（listPage / queryPage …）
    // ------------------------------------------------------------------

    fun testGetLineMarkerInfo_自定义页面标签带datasource也注册() {
        val file = xmlFile(
            "<pages>\n" +
                "  <listPage id=\"lp1\"><datasource><![CDATA[SELECT 1]]></datasource></listPage>\n" +
                "  <queryPage id=\"qp1\"><datasource><![CDATA[SELECT 2]]></datasource></queryPage>\n" +
                "</pages>",
        )
        val provider = PagesXmlGutterLineMarkerProvider()
        assertNotNull(
            "带 <datasource> 的自定义页面标签应注册 gutter 图标",
            provider.getLineMarkerInfo(tokensNamed(file, "listPage").first()),
        )
        assertNotNull(
            "第二个自定义页面标签同样应注册",
            provider.getLineMarkerInfo(tokensNamed(file, "queryPage").first()),
        )
    }

    fun testStatementsInRange_自定义页面标签按datasource识别() {
        val file = xmlFile(
            "<pages>\n" +
                "  <listPage id=\"lp1\"><datasource><![CDATA[SELECT 1]]></datasource></listPage>\n" +
                "  <other/>\n" +
                "  <queryPage id=\"qp1\"><datasource><![CDATA[SELECT 2]]></datasource></queryPage>\n" +
                "</pages>",
        )
        val tags = PagesXmlGutterLineMarkerProvider.statementsInRange(file, 0, file.textLength)
        assertEquals("应返回 2 个带 datasource 的页面标签（other 被排除）", 2, tags.size)
        assertEquals("lp1", tags[0].getAttributeValue("id"))
        assertEquals("qp1", tags[1].getAttributeValue("id"))
    }

    fun testGetLineMarkerInfo_自定义标签无datasource不注册() {
        val file = xmlFile("<pages><listPage id=\"lp1\"/></pages>")
        val provider = PagesXmlGutterLineMarkerProvider()
        assertNull(
            "无 <datasource> 的自定义标签没有可预览内容，不应注册图标",
            provider.getLineMarkerInfo(tokensNamed(file, "listPage").first()),
        )
    }
}
