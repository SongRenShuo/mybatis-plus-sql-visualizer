package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.SourceLanguage
import cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 通用 XML 内嵌 SQL 预览服务测试（O-72 T-XmlInlineSql）。
 *
 * 覆盖：原生 SQL 直显（pages 同款）、空文本 warning、HQL 分派（JPA 实体夹具 →
 * HqlConverter 管道）、project 缺席时回退原文。
 */
class XmlInlineSqlPreviewServiceTest : BasePlatformTestCase() {

    private fun xmlFile(text: String): XmlFile {
        myFixture.configureByText("sample.xml", text)
        return myFixture.file as XmlFile
    }

    private fun tagNamed(name: String): XmlTag =
        PsiTreeUtil.collectElementsOfType(myFixture.file, XmlTag::class.java).first { it.name == name }

    fun test原生SQL直显_占位符参数表() {
        xmlFile(
            "<DataSource type=\"SQL\"><statement><![CDATA[SELECT * FROM t WHERE id = ? AND code = ?]]></statement></DataSource>",
        )
        val result = XmlInlineSqlPreviewService.preview(null, tagNamed("statement"))
        assertEquals("SQL 原样直显", "SELECT * FROM t WHERE id = ? AND code = ?", result.sql)
        assertNull("无 warning", result.warning)
        assertEquals("两个 ? 占位符", listOf("param1", "param2"), result.unresolvedParams)
        assertNotNull(result.paramTable)
        assertTrue("compactSql 无换行", !result.compactSql.contains("\n"))
    }

    fun test无type按原生直显() {
        xmlFile("<root><statement><![CDATA[DELETE FROM t]]></statement></root>")
        val result = XmlInlineSqlPreviewService.preview(null, tagNamed("statement"))
        assertEquals("DELETE FROM t", result.sql)
        assertNull(result.warning)
    }

    fun test空文本返回warning() {
        xmlFile("<root><statement>   </statement></root>")
        val result = XmlInlineSqlPreviewService.preview(null, tagNamed("statement"))
        assertNotNull("空文本应有 warning", result.warning)
        assertEquals("", result.sql)
    }

    fun testHQL_project缺席回退原文直显() {
        // project=null 时无法进 SqlPreviewService 管道——必须回退原文直显，不抛错
        xmlFile("<DataSource type=\"HQL\"><statement><![CDATA[SELECT qc.code FROM WmsQuality qc]]></statement></DataSource>")
        val result = XmlInlineSqlPreviewService.preview(null, tagNamed("statement"))
        assertEquals("回退原文", "SELECT qc.code FROM WmsQuality qc", result.sql)
    }

    fun testHQL_实体可解析时走转换管道() {
        // 实体夹具：默认包 JPA 注解实体（同 PreviewEndToEndTest 形态），findClass allScope 可命中
        myFixture.addFileToProject(
            "WmsQuality.java",
            """
            @javax.persistence.Entity
            @javax.persistence.Table(name = "wms_quality")
            public class WmsQuality {
                @javax.persistence.Id
                private Long id;
                private String code;
            }
            """.trimIndent(),
        )
        xmlFile(
            "<DataSource type=\"HQL\"><statement><![CDATA[SELECT qc.code FROM WmsQuality qc]]></statement></DataSource>",
        )
        val result = XmlInlineSqlPreviewService.preview(project, tagNamed("statement"))
        assertNotNull("HQL 管道应产出结果", result)
        assertTrue("实体名应映射为表名（wms_quality），实际：${result.sql}", result.sql.contains("wms_quality"))
        assertTrue("HQL 属性应映射为列名（code），实际：${result.sql}", result.sql.contains("code"))
    }

    fun testHQL_实体不可解析时透传原文不抛错() {
        // 未注册 WmsQuality 实体——HqlConverter 对无 TableInfo 的处理是透传原文（非异常路径）
        xmlFile(
            "<DataSource type=\"HQL\"><statement><![CDATA[SELECT qc.code FROM WmsQuality qc]]></statement></DataSource>",
        )
        val result = XmlInlineSqlPreviewService.preview(project, tagNamed("statement"))
        assertNotNull(result)
        assertTrue("至少应展示原文，实际：${result.sql}", result.sql.contains("qc.code"))
    }

    // ------------------------------------------------------------------
    // T-PagesHql 泛化：无 type、无 entityClass 的载体标签（<dataValue> 形态）
    // ------------------------------------------------------------------

    fun test无type无entityClass_HQL按根实体自动识别并转换() {
        myFixture.addFileToProject(
            "WmsQuality.java",
            """
            @javax.persistence.Entity
            @javax.persistence.Table(name = "wms_quality")
            public class WmsQuality {
                @javax.persistence.Id
                private Long id;
                private String code;
            }
            """.trimIndent(),
        )
        xmlFile("<root><dataValue><![CDATA[SELECT qc.code FROM WmsQuality qc]]></dataValue></root>")
        val result = XmlInlineSqlPreviewService.preview(project, tagNamed("dataValue"))
        assertEquals("应自动识别为 HQL", SourceLanguage.HQL, result.sourceLanguage)
        assertTrue("实体名应映射为表名，实际：${result.sql}", result.sql.contains("wms_quality"))
        assertEquals("原文应保留", "SELECT qc.code FROM WmsQuality qc", result.originalSql)
    }

    fun test无type无entityClass_原生SQL保持直显() {
        xmlFile("<root><dataValue><![CDATA[SELECT * FROM t_order]]></dataValue></root>")
        val result = XmlInlineSqlPreviewService.preview(project, tagNamed("dataValue"))
        assertEquals("根名不可解析为实体 → 保持 SQL", SourceLanguage.SQL, result.sourceLanguage)
        assertNull(result.originalSql)
        assertEquals("SELECT * FROM t_order", result.sql)
    }

    // ------------------------------------------------------------------
    // 券 0（T-Ibatis2SlowFix）：同步预览不得付「@Entity(name) 反查」全扫代价
    // ------------------------------------------------------------------

    /**
     * 根标识符是**物理表名**、无 `entityClass` 的通用 XML 内嵌 SQL（iBatis `<select>` 同形态）：
     * 同步结论仍是保守 SQL（与改动前一致），但**不得**在关键路径上触发
     * [EntityAliasSweep] 的整段短名扫描——改动前此处扫描计数为 1（与 20261008 真机冻结事故
     * 同一形态：弹窗被探针阻塞，单次约 1s、事故实测 70s），改动后必须为 0。
     *
     * 夹具里加一个 `@Entity(name="Shared")` 的实体类：证明索引里**确有实体**（扫描本来会真的跑起来，
     * 不是「工程太干净扫不到」的空绿）。
     */
    fun test物理表名根_同步预览不触发别名全扫() {
        myFixture.addFileToProject("Foo.java", "@jakarta.persistence.Entity(name=\"Shared\") class Foo {}")
        xmlFile("<root><dataValue><![CDATA[SELECT o.id FROM t_order o WHERE o.id = ?]]></dataValue></root>")
        EntityAliasSweep.resetScanCountForTest()
        val result = XmlInlineSqlPreviewService.preview(project, tagNamed("dataValue"))
        assertEquals("物理表名不可解析为实体 → 保持 SQL", SourceLanguage.SQL, result.sourceLanguage)
        assertEquals("同步预览不得触发别名反查全扫", 0, EntityAliasSweep.scanCountForTest())
    }

    // ------------------------------------------------------------------
    // T-XmlEntityDecode：用户报障形态端到端（<sql id> 片段 + 普通文本实体）
    // ------------------------------------------------------------------

    /**
     * 用户真机形态：mapper `<sql id>` 片段不是 mapper 语句白名单项，走通用内嵌 SQL 链
     * （PSI 原文）。`&gt;=` / `&lt;=` 必须还原成比较运算符——否则「复制 SQL / 执行 SQL」
     * 得到的是 `&gt;=`，真机执行必然语法错误（报障截图形态）。
     */
    fun test普通文本实体_片段预览还原比较运算符() {
        xmlFile(
            "<mapper namespace=\"n\"><sql id=\"orderSideMaxUpdateTimeUnion\">" +
                "select o.tenant_id from tb_order o where o.update_time &gt;= #{fromTime}" +
                " and o.update_time &lt;= #{cutoffTime}" +
                "</sql></mapper>",
        )
        val result = XmlInlineSqlPreviewService.preview(null, tagNamed("sql"))
        assertTrue("SQL 中不得残留 XML 实体：${result.sql}", !result.sql.contains("&gt;"))
        assertTrue("SQL 中不得残留 XML 实体：${result.sql}", !result.sql.contains("&lt;"))
        assertTrue("比较运算符应还原：${result.sql}", result.sql.contains("o.update_time >= #{fromTime}"))
        assertTrue("比较运算符应还原：${result.sql}", result.sql.contains("o.update_time <= #{cutoffTime}"))
    }

    /** CDATA 内的 `&gt;` 是真机字面量（MyBatis 同样不解码），修复不得越界改写。 */
    fun testCDATA内实体_保持字面量() {
        xmlFile("<root><statement><![CDATA[SELECT * FROM t WHERE a &gt;= 1]]></statement></root>")
        val result = XmlInlineSqlPreviewService.preview(null, tagNamed("statement"))
        assertEquals("SELECT * FROM t WHERE a &gt;= 1", result.sql)
    }
}
