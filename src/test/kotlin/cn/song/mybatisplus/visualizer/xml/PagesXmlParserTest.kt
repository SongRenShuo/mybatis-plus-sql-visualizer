package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PagesXmlParser 测试：<pages> XML → PagesModel——
 * 语句提取、CDATA SQL、columns 解析、异常路径。
 */
class PagesXmlParserTest {

    private val sampleXml = """
        <pages>
            <maintainPage id="maintainLangLibPage" title="maintainLangLibPage"
                          autoQuery="true" entityClass="com.vtradex.forest.model.lang.TfLangLib">
                <workflow/>
                <datasource><![CDATA[
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
    fun `标准单maintainPage_字段完整`() {
        val model = PagesXmlParser().parse(sampleXml)
        assertEquals(1, model.statements.size)
        val stmt = model.statements[0]
        assertEquals("maintainLangLibPage", stmt.id)
        assertEquals("maintainLangLibPage", stmt.title)
        assertTrue(stmt.autoQuery)
        assertEquals("com.vtradex.forest.model.lang.TfLangLib", stmt.entityClass)
        assertTrue(stmt.sql.contains("SELECT"))
        assertTrue(stmt.sql.contains("TfLangLib"))
        assertTrue(stmt.sql.contains("ORDER BY t.id DESC"))
    }

    @Test
    fun `CDATA_SQL_trim后完整`() {
        val model = PagesXmlParser().parse(sampleXml)
        val sql = model.statements[0].sql
        // 首尾无空白，内容完整
        assertEquals(sql, sql.trim())
        assertTrue(sql.startsWith("SELECT"))
        assertTrue(sql.endsWith("DESC"))
    }

    @Test
    fun `columns_解析4列_属性正确`() {
        val model = PagesXmlParser().parse(sampleXml)
        val columns = model.statements[0].columns
        assertEquals(4, columns.size)

        assertEquals("t.id", columns[0].id)
        assertEquals("t.id", columns[0].title)
        assertFalse(columns[0].visible)
        assertTrue(columns[0].sortable)
        assertEquals("long", columns[0].dataType)

        assertEquals("a.name", columns[1].id)
        assertTrue(columns[1].visible)
        assertEquals("string", columns[1].dataType)

        assertEquals("t.lang", columns[3].id)
        assertEquals("enum", columns[3].dataType)
    }

    @Test
    fun `多个maintainPage_顺序一致`() {
        val xml = """
            <pages>
                <maintainPage id="page1" title="Page 1" autoQuery="true" entityClass="com.A">
                    <datasource><![CDATA[SELECT 1]]></datasource>
                </maintainPage>
                <maintainPage id="page2" title="Page 2" autoQuery="false" entityClass="com.B">
                    <datasource><![CDATA[SELECT 2]]></datasource>
                </maintainPage>
                <maintainPage id="page3" title="Page 3" autoQuery="true" entityClass="com.C">
                    <datasource><![CDATA[SELECT 3]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        assertEquals(3, model.statements.size)
        assertEquals("page1", model.statements[0].id)
        assertEquals("page2", model.statements[1].id)
        assertEquals("page3", model.statements[2].id)
        assertFalse(model.statements[1].autoQuery)
    }

    @Test
    fun `空datasource_sql为空串`() {
        val xml = """
            <pages>
                <maintainPage id="p1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        assertEquals("", model.statements[0].sql)
    }

    @Test
    fun `无columns_columns为空列表`() {
        val xml = """
            <pages>
                <maintainPage id="p1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource><![CDATA[SELECT 1]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        assertTrue(model.statements[0].columns.isEmpty())
    }

    @Test(expected = PagesXmlParseException::class)
    fun `非pages根元素_抛异常`() {
        PagesXmlParser().parse("<configuration></configuration>")
    }

    @Test(expected = PagesXmlParseException::class)
    fun `mapper根元素_抛异常`() {
        PagesXmlParser().parse("<mapper namespace='x'><select id='a'>SELECT 1</select></mapper>")
    }

    @Test(expected = PagesXmlParseException::class)
    fun `非良构XML_抛异常`() {
        PagesXmlParser().parse("<pages><maintainPage id='a'></pages>")
    }

    @Test
    fun `无maintainPage_空列表`() {
        val model = PagesXmlParser().parse("<pages></pages>")
        assertTrue(model.statements.isEmpty())
    }

    @Test
    fun `SQL含比较运算符_CDATA字面量保留`() {
        // CDATA 内文本为字面量，&lt; 不解码（与 MyBatis mapper 行为一致）
        val xml = """
            <pages>
                <maintainPage id="p1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource><![CDATA[SELECT * FROM t WHERE id < 10 AND name LIKE '%test%']]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        val sql = model.statements[0].sql
        assertTrue(sql.contains("id < 10"))
        assertTrue(sql.contains("%test%"))
    }

    @Test
    fun `HQL语句_实体类与字段正确提取`() {
        val xml = """
            <pages>
                <maintainPage id="userQuery" title="User Query" autoQuery="true"
                              entityClass="com.example.model.User">
                    <datasource><![CDATA[
                    select u.name, u.email from User u
                  where u.age > 18
                  order by u.createdDate desc
                    ]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        val stmt = model.statements[0]
        assertEquals("com.example.model.User", stmt.entityClass)
        assertTrue(stmt.sql.contains("from User u"))
        assertTrue(stmt.sql.contains("order by u.createdDate desc"))
    }

    // ------------------------------------------------------------------
    // T-PagesHql：<datasource type="..."> 显式源语言声明
    // ------------------------------------------------------------------

    @Test
    fun `datasource的type属性_解析进declaredType`() {
        val xml = """
            <pages>
                <maintainPage id="p1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource type="hql"><![CDATA[from A a]]></datasource>
                </maintainPage>
                <maintainPage id="p2" title="P2" autoQuery="true" entityClass="com.B">
                    <datasource><![CDATA[SELECT * FROM b]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        assertEquals("hql", model.statements[0].declaredType)
        assertNull("无 type 属性时为 null", model.statements[1].declaredType)
    }

    // ------------------------------------------------------------------
    // T-PagesHql：页面标签名不固定（自研框架各家不同）
    // ------------------------------------------------------------------

    @Test
    fun `页面标签名不固定_按datasource识别`() {
        val xml = """
            <pages>
                <listPage id="lp1" entityClass="com.A">
                    <datasource><![CDATA[SELECT 1]]></datasource>
                </listPage>
                <queryPage id="qp1">
                    <datasource><![CDATA[SELECT 2]]></datasource>
                </queryPage>
                <other/>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        assertEquals(listOf("lp1", "qp1"), model.statements.map { it.id })
    }

    @Test
    fun `页面无id_回落标签名`() {
        val xml = """
            <pages>
                <listPage><datasource><![CDATA[SELECT 1]]></datasource></listPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        assertEquals("listPage", model.statements.single().id)
        assertEquals("listPage", model.statements.single().title)
    }

    // ------------------------------------------------------------------
    // T-XmlDtdHardening T-XDH-05：PagesXmlParser 的 DOCTYPE / XXE 回归（改前零覆盖）
    // 与 mapper 解析器共用 SecureXml（T-XDH-01 配置收敛）——此组用例是该共用事实的回归护栏。
    // ------------------------------------------------------------------

    @Test
    fun `DOCTYPE声明放行_外部DTD不联网`() {
        // SYSTEM 指向不可达地址（127.0.0.1:1）仍能解析：EntityResolver 把 DTD 读取拦为空；
        // 实测（JDK 17.0.19）摘掉 EntityResolver 即抛 ConnectException: Connection refused
        val xml = """
            <!DOCTYPE pages SYSTEM "http://127.0.0.1:1/pages.dtd">
            <pages>
                <maintainPage id="p1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource><![CDATA[SELECT 1]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        assertEquals("SELECT 1", model.statements.single().sql)
    }

    @Test
    fun `内部实体放行_D1_A`() {
        // D1=A 定案：内联 DTD 子集维持放行（内部实体照常展开），pages 文件与 mapper 同语义。
        // 注意实体引用只在**普通文本**节点展开（CDATA 是字面量——实测 `&tbl;` 在 CDATA 里原样保留）
        val xml = """
            <!DOCTYPE pages [<!ENTITY tbl "TF_APP">]>
            <pages>
                <maintainPage id="p1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource>SELECT * FROM &tbl;</datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        assertEquals("SELECT * FROM TF_APP", model.statements.single().sql)
    }

    @Test
    fun `外部实体被清空_XXE防护`() {
        // 外部实体引用解析为空（不注入外部文件内容）——实测 /etc/hosts 内容不出现
        val xml = """
            <!DOCTYPE pages [<!ENTITY xxe SYSTEM "file:///etc/hosts">]>
            <pages>
                <maintainPage id="p1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource>SELECT '&xxe;' FROM t</datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        assertEquals("SELECT '' FROM t", model.statements.single().sql)
    }

    @Test
    fun `内联DTD指数膨胀被拒绝_与mapper同规`() {
        // 4 层 × 10 引用 × 10 字符的 billion laughs（改前实测可解析）→ 预扫拒绝
        val xml = buildString {
            append("<!DOCTYPE pages [\n<!ENTITY e0 \"xxxxxxxxxx\">\n")
            for (level in 1..4) {
                append("<!ENTITY e").append(level).append(" \"")
                repeat(10) { append("&e").append(level - 1).append(';') }
                append("\">\n")
            }
            append("]>\n<pages><maintainPage id=\"p1\" title=\"P1\" autoQuery=\"true\" entityClass=\"com.A\">")
            append("<datasource><![CDATA[SELECT '&e4;']]></datasource></maintainPage></pages>")
        }
        val e = assertThrows(PagesXmlParseException::class.java) {
            PagesXmlParser().parse(xml)
        }
        assertTrue("拒绝原因须点明实体展开，实际：${e.message}", e.message.orEmpty().contains("实体展开"))
    }

    @Test
    fun `实体声明数超限被拒绝_与mapper同规`() {
        val xml = buildString {
            append("<!DOCTYPE pages [\n")
            repeat(100) { append("<!ENTITY d").append(it).append(" \"v").append(it).append("\">\n") }
            append("]>\n<pages><maintainPage id=\"p1\" title=\"P1\" autoQuery=\"true\" entityClass=\"com.A\">")
            append("<datasource><![CDATA[SELECT '&d0;']]></datasource></maintainPage></pages>")
        }
        val e = assertThrows(PagesXmlParseException::class.java) {
            PagesXmlParser().parse(xml)
        }
        assertTrue("拒绝原因须点明实体声明，实际：${e.message}", e.message.orEmpty().contains("实体声明"))
    }
}
