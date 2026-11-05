package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SourceLanguage
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-PagesHql：maintainPage `<datasource>` 的 HQL 识别与转换（平台测试，需要索引）。
 *
 * 覆盖：
 * - entityClass 根实体 + 嵌入属性 + `#{}` 框架占位符 → 转换出可执行 SQL；
 * - 实体不可解析 → 回退原文 + warning（不空白、不抛）；
 * - 原生 SQL 页（demo orderMaintain 形态）→ 保持 SQL，零行为变化。
 */
class PagesXmlHqlPreviewTest : BasePlatformTestCase() {

    private fun addEntities() {
        myFixture.addFileToProject(
            "demo/Order.java",
            """
            package demo;

            @Entity
            @Table(name = "t_order")
            class Order {
                @Id
                private Long id;
                @Column(name = "order_no")
                private String orderNo;
                @Embedded
                private UpdateInfo updateInfo;
            }

            @Embeddable
            class UpdateInfo {
                @Column(name = "be_delete")
                private Integer beDelete;
            }

            @interface Entity { String name() default ""; }
            @interface Table { String name() default ""; }
            @interface Column { String name() default ""; }
            @interface Id {}
            @interface Embedded {}
            @interface Embeddable {}
            """.trimIndent(),
        )
    }

    private fun preview(xml: String, pageId: String): PreviewResult {
        myFixture.configureByText("pages.xml", xml)
        val file = myFixture.file
        val tag = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).first { it.name == "maintainPage" }
        return PagesXmlPreviewService.preview(file.text, pageId, project, tag)
    }

    fun testHqlWithEntityClassAndFrameworkPlaceholder_isConverted() {
        addEntities()
        val xml = """
            <pages>
                <maintainPage id="orderPage" title="Order" autoQuery="true"
                              entityClass="demo.Order">
                    <datasource><![CDATA[
                    select o.id, o.orderNo, o.updateInfo.beDelete
                      from Order o
                     where o.orderNo = :orderNo
                       and o.id = #{ORDER_ID}
                    ]]></datasource>
                    <columns>
                        <column id="o.id" title="ID" dataType="long" visible="true" sortable="true"/>
                    </columns>
                </maintainPage>
            </pages>
        """.trimIndent()

        val result = preview(xml, "orderPage")

        assertEquals(SourceLanguage.HQL, result.sourceLanguage)
        assertNotNull("应保留转换前原文", result.originalSql)
        assertTrue("原文应是 HQL: ${result.originalSql}", result.originalSql!!.contains("from Order o"))
        assertTrue("实体应映射为表名: ${result.sql}", result.sql.contains("t_order"))
        assertTrue("属性应映射为列名: ${result.sql}", result.sql.contains("order_no"))
        assertTrue("嵌入属性应展平为列名: ${result.sql}", result.sql.contains("be_delete"))
        assertFalse("不应残留 HQL 实体名: ${result.sql}", result.sql.contains("Order o"))
        assertEquals(listOf("orderNo", "ORDER_ID"), result.unresolvedParams)
        assertEquals(listOf("orderNo", "ORDER_ID"), result.placeholderParamNames)
        assertNotNull("列信息页签来源不应丢失", result.pagesColumns)
        assertNull("纯转换不应有风险警示", result.warning)
    }

    fun testUnresolvableEntity_fallsBackToOriginalWithWarning() {
        val xml = """
            <pages>
                <maintainPage id="ghostPage" title="Ghost" autoQuery="true"
                              entityClass="com.missing.Ghost">
                    <datasource><![CDATA[
                    from Ghost g where g.id = :id
                    ]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()

        val result = preview(xml, "ghostPage")

        assertEquals(SourceLanguage.HQL, result.sourceLanguage)
        assertEquals(result.sql, result.originalSql)
        assertTrue("应回退原文: ${result.sql}", result.sql.contains("from Ghost g"))
        assertNotNull("应给出回退原因", result.warning)
    }

    fun testNativeSqlPage_staysSql() {
        addEntities()
        val xml = """
            <pages>
                <maintainPage id="orderSqlPage" title="Order SQL" autoQuery="true"
                              entityClass="demo.Order">
                    <datasource><![CDATA[
                    SELECT o.id, o.order_no FROM t_order o WHERE o.order_no = ?
                    ]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()

        val result = preview(xml, "orderSqlPage")

        assertEquals("根标识符是表名 → 必须保持 SQL", SourceLanguage.SQL, result.sourceLanguage)
        assertNull(result.originalSql)
        assertTrue(result.sql.contains("t_order"))
        assertEquals(listOf("param1"), result.unresolvedParams)
        assertNull(result.warning)
    }
}
