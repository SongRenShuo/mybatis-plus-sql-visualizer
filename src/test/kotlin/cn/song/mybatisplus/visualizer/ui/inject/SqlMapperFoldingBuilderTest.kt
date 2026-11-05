package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-XmlGap W6：mapper 语句标签的带 id 折叠（[SqlMapperFoldingBuilder]）用例。
 *
 * 判据边界（与类 KDoc 的设计前提一一对应）：
 *  - 只在 mapper 文件、只对语句标签与带 id 的 `<sql>`、只对**跨多行**标签建区；
 *  - 占位文本必须是 `标签名#id`（这是相对平台「占位=标签名」的唯一增量）；
 *  - 不自动折叠（与平台 `isCollapseXmlTags` 用户设置一致，本类不越权）。
 *
 * 平台侧已有 XML 标签折叠（`XmlCodeFoldingBuilder`，javap 实证），故本类**不**断言「标签可折叠」这件事
 * 本身（那是平台能力），只断言我们注入的**占位形态**与作用面边界。
 */
class SqlMapperFoldingBuilderTest : BasePlatformTestCase() {

    private fun regionsOf(xml: String, fileName: String = "mapper.xml"): Array<FoldingDescriptor> {
        myFixture.configureByText(fileName, xml)
        val file = myFixture.file
        val document: Document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        return SqlMapperFoldingBuilder().buildFoldRegions(file, document, false)
    }

    private val multiLineSelect = """<mapper namespace="n">
  <select id="findById">
    SELECT * FROM t
    WHERE id = #{id}
  </select>
</mapper>"""

    fun testW6_多行语句_建区且占位为标签名井号id() {
        val regions = regionsOf(multiLineSelect)
        assertEquals("多行 select 应恰好一个 fold 区", 1, regions.size)
        val region = regions.single()
        assertEquals("占位文本须为 `select#findById`", "select#findById", region.placeholderText)
        val tag = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlTag::class.java).first { it.name == "select" }
        assertEquals("折叠范围须为整个标签", tag.textRange, region.range)
    }

    fun testW6_单行语句_不建区() {
        val regions = regionsOf("""<mapper namespace="n"><select id="a">SELECT 1</select></mapper>""")
        assertEquals("单行标签折叠无视觉意义，不建区", 0, regions.size)
    }

    fun testW6_无id语句_不建区() {
        val regions = regionsOf(
            """<mapper namespace="n">
  <select>
    SELECT 1
  </select>
</mapper>""",
        )
        assertEquals("无 id 无占位可加，不建区", 0, regions.size)
    }

    fun testW6_非mapper文件_不建区() {
        val regions = regionsOf(
            """<configuration>
  <settings>
    <setting name="cacheEnabled" value="true"/>
  </settings>
</configuration>""",
            fileName = "mybatis-config.xml",
        )
        assertEquals("非 mapper 根 → 零 fold 区（不抢平台区）", 0, regions.size)
    }

    fun testW6_sql片段_带id多行_建区() {
        val regions = regionsOf(
            """<mapper namespace="n">
  <sql id="baseColumns">
    id, name, status
  </sql>
</mapper>""",
        )
        assertEquals(1, regions.size)
        assertEquals("sql#baseColumns", regions.single().placeholderText)
    }

    fun testW6_resultMap族_不建区() {
        val regions = regionsOf(
            """<mapper namespace="n">
  <resultMap id="rm" type="com.demo.UserVo">
    <result column="user_name" property="name"/>
  </resultMap>
</mapper>""",
        )
        assertEquals("resultMap 族 id 语义弱且通常很短，本类不建区", 0, regions.size)
    }

    fun testW6_不自动折叠() {
        myFixture.configureByText("mapper.xml", multiLineSelect)
        val tag = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlTag::class.java).first { it.name == "select" }
        assertFalse(
            "不自动折叠（默认折叠与否由平台 XmlCodeFoldingSettings 决定，本类不越权）",
            SqlMapperFoldingBuilder().isCollapsedByDefault(tag.node),
        )
    }

    /** EP 接线守卫：plugin.xml 须声明 lang.foldingBuilder language=XML。 */
    fun testW6_pluginXml_折叠接线在场() {
        val xml = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!
            .readBytes().toString(Charsets.UTF_8)
        assertTrue(
            "plugin.xml 须含 <lang.foldingBuilder language=\"XML\" …SqlMapperFoldingBuilder>",
            Regex(
                "<lang\\.foldingBuilder\\s+language=\"XML\"[^>]*implementationClass=\"cn\\.song\\.mybatisplus\\.visualizer\\.ui\\.inject\\.SqlMapperFoldingBuilder\"",
            ).containsMatchIn(xml),
        )
    }

    // ------------------------------------------------------------------
    // T-PerfSweep26-02（F95）：quick 短路 + 体量熔断
    // ------------------------------------------------------------------

    /**
     * F95：`quick=true`（编辑器打开瞬间的快速折叠重建 pass，平台契约要求避免复杂检查，
     * 随后 daemon `CodeFoldingPass` 会以 `quick=false` 全量重建）必须短路返回空集，
     * 不做全 PSI 子树遍历；折叠区由后续 quick=false 重建补齐（平台 XML 折叠兜底）。
     */
    fun testF95_quickTrue_短路返回空() {
        myFixture.configureByText("mapper.xml", multiLineSelect)
        val file = myFixture.file
        val document: Document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val quick = SqlMapperFoldingBuilder().buildFoldRegions(file, document, quick = true)
        assertEquals("quick=true 须短路返回零 fold 区（全量区由 quick=false 重建补齐）", 0, quick.size)
    }

    /** F95 反向对照：`quick=false`（含键入后的常规重建）行为不回归，正常 mapper 仍产折叠区。 */
    fun testF95_quickFalse_正常mapper仍建区() {
        myFixture.configureByText("mapper.xml", multiLineSelect)
        val file = myFixture.file
        val document: Document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val regions = SqlMapperFoldingBuilder().buildFoldRegions(file, document, quick = false)
        assertEquals("quick=false 行为不变：多行 select 恰一个 fold 区", 1, regions.size)
        assertEquals("select#findById", regions.single().placeholderText)
    }

    /** F95 体量熔断：文本超 `XmlSynHiGate.MAX_TEXT_LENGTH`（100K）不建区（与注入族同阈值）。 */
    fun testF95_超长文本_体量熔断返回空() {
        val stmt = "<select id=\"s\">\n  SELECT 1\n</select>\n"
        val xml = "<mapper namespace=\"n\">\n" + stmt.repeat(3000) + "</mapper>"
        assertTrue("夹具自检：文本须超熔断阈值", xml.length > XmlSynHiGate.MAX_TEXT_LENGTH)
        myFixture.configureByText("mapper.xml", xml)
        val file = myFixture.file
        val document: Document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val regions = SqlMapperFoldingBuilder().buildFoldRegions(file, document, quick = false)
        assertEquals("超长 mapper 体量熔断 → 零 fold 区", 0, regions.size)
    }
}
