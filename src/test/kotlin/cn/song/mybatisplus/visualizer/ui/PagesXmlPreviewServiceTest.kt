package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.SourceLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PagesXmlPreviewService 测试：XML 文本 → PreviewResult——
 * 语句定位、参数提取、列信息传递、异常路径。
 */
class PagesXmlPreviewServiceTest {

    private val sampleXml = """
        <pages>
            <maintainPage id="maintainLangLibPage" title="maintainLangLibPage"
                          autoQuery="true" entityClass="com.vtradex.forest.model.lang.TfLangLib">
                <datasource type="sql"><![CDATA[
                SELECT
                    t.id,
                    a.name,
                    t.name,
                    t.lang
                  from TfLangLib t
                  inner join TFApp a on t.appId = a.id
                 WHERE 1=1
                   ORDER BY t.id DESC
                ]]></datasource>
                <columns>
                    <column id="t.id" title="t.id" visible="false" sortable="true" dataType="long"/>
                    <column id="a.name" title="a.name" visible="true" sortable="true" dataType="string"/>
                    <column id="t.name" title="t.name" visible="true" sortable="true" dataType="string"/>
                    <column id="t.lang" title="t.lang" visible="true" sortable="true" dataType="enum"/>
                </columns>
            </maintainPage>
        </pages>
    """.trimIndent()

    @Test
    fun `标准预览_SQL完整`() {
        val result = PagesXmlPreviewService.preview(sampleXml, "maintainLangLibPage")
        assertTrue(result.sql.contains("SELECT"))
        assertTrue(result.sql.contains("TfLangLib"))
        assertTrue(result.sql.contains("ORDER BY t.id DESC"))
        assertNull(result.warning)
    }

    @Test
    fun `compactSql_单行`() {
        val result = PagesXmlPreviewService.preview(sampleXml, "maintainLangLibPage")
        val compact = result.compactSql
        // 单行：无换行符
        assertTrue(!compact.contains("\n"))
        assertTrue(compact.contains("SELECT"))
    }

    @Test
    fun `按id定位_命中目标`() {
        val xml = """
            <pages>
                <maintainPage id="page1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource><![CDATA[SELECT 1]]></datasource>
                </maintainPage>
                <maintainPage id="page2" title="P2" autoQuery="true" entityClass="com.B">
                    <datasource><![CDATA[SELECT 2]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val result = PagesXmlPreviewService.preview(xml, "page2")
        assertTrue(result.sql.contains("SELECT 2"))
    }

    @Test
    fun `id未命中_回落首个`() {
        val xml = """
            <pages>
                <maintainPage id="page1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource><![CDATA[SELECT 1]]></datasource>
                </maintainPage>
                <maintainPage id="page2" title="P2" autoQuery="true" entityClass="com.B">
                    <datasource><![CDATA[SELECT 2]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val result = PagesXmlPreviewService.preview(xml, "missing")
        assertTrue(result.sql.contains("SELECT 1"))
    }

    @Test
    fun `id为null_取首个`() {
        val xml = """
            <pages>
                <maintainPage id="page1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource><![CDATA[SELECT 1]]></datasource>
                </maintainPage>
                <maintainPage id="page2" title="P2" autoQuery="true" entityClass="com.B">
                    <datasource><![CDATA[SELECT 2]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val result = PagesXmlPreviewService.preview(xml, null)
        assertTrue(result.sql.contains("SELECT 1"))
    }

    @Test
    fun `无maintainPage_空结果加warning`() {
        val result = PagesXmlPreviewService.preview("<pages></pages>", null)
        assertEquals("", result.sql)
        assertNotNull(result.warning)
        assertTrue(result.warning?.contains("maintainPage") == true)
    }

    @Test
    fun `非pages根元素_警告提示`() {
        val result = PagesXmlPreviewService.preview("<configuration></configuration>", null)
        assertEquals("", result.sql)
        assertNotNull(result.warning)
        assertTrue(result.warning?.contains("pages") == true)
    }

    @Test
    fun `非良构XML_警告提示`() {
        val result = PagesXmlPreviewService.preview("<pages><maintainPage></pages>", null)
        assertNotNull(result.warning)
    }

    @Test
    fun `无参数SQL_参数列表为空`() {
        val result = PagesXmlPreviewService.preview(sampleXml, "maintainLangLibPage")
        assertEquals(emptyList<String>(), result.unresolvedParams)
        assertNull(result.paramTable)
    }

    @Test
    fun `含占位符SQL_参数提取正确`() {
        val xml = """
            <pages>
                <maintainPage id="p1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource><![CDATA[SELECT * FROM t WHERE id = ? AND name = ?]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val result = PagesXmlPreviewService.preview(xml, "p1")
        assertEquals(listOf("param1", "param2"), result.unresolvedParams)
        assertNotNull(result.paramTable)
    }

    @Test
    fun `列信息传递_pagesColumns非空`() {
        val result = PagesXmlPreviewService.preview(sampleXml, "maintainLangLibPage")
        assertNotNull(result.pagesColumns)
        assertEquals(4, result.pagesColumns?.size)
        assertEquals("t.id", result.pagesColumns?.get(0)?.id)
        assertEquals("long", result.pagesColumns?.get(0)?.dataType)
    }

    @Test
    fun `无列信息_pagesColumns为null`() {
        val xml = """
            <pages>
                <maintainPage id="p1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource><![CDATA[SELECT 1]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val result = PagesXmlPreviewService.preview(xml, "p1")
        assertNull(result.pagesColumns)
    }

    @Test
    fun `HQL内容_无工程时识别为HQL并回退原文`() {
        val xml = """
            <pages>
                <maintainPage id="hqlPage" title="HQL" autoQuery="true"
                              entityClass="com.example.model.User">
                    <datasource><![CDATA[
                    select u.name, u.email from User u
                  where u.age > 18
                  order by u.createdDate desc
                    ]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val result = PagesXmlPreviewService.preview(xml, "hqlPage")
        // T-PagesHql：entityClass 简单名 User == 根标识符 User → 识别为 HQL；
        // 无工程索引时不能转换，回退原文并给出原因（不再把 HQL 当原生 SQL 静默直显）
        assertTrue(result.sql.contains("User"))
        assertTrue(result.sql.contains("order by"))
        assertEquals(emptyList<String>(), result.unresolvedParams)
        assertNull(result.paramTable)
        assertEquals(SourceLanguage.HQL, result.sourceLanguage)
        assertEquals(result.sql, result.originalSql)
        assertNotNull(result.warning)
    }

    @Test
    fun `wrapperType为null`() {
        val result = PagesXmlPreviewService.preview(sampleXml, "maintainLangLibPage")
        assertNull(result.wrapperType)
    }

    @Test
    fun `xmlModel和previewModel为null`() {
        val result = PagesXmlPreviewService.preview(sampleXml, "maintainLangLibPage")
        assertNull(result.xmlModel)
        assertNull(result.previewModel)
    }
}
